package app.tapisvirtuel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.Locale
import java.util.UUID
import kotlin.math.atan
import kotlin.math.roundToInt

/**
 * Fait passer le téléphone pour un tapis de course connecté :
 *  - service FTMS (Fitness Machine, profil tapis) : vitesse, inclinaison, distance, temps
 *  - service RSC (capteur de foulée) en secours, pour les apps qui ne lisent que ça
 */
@SuppressLint("MissingPermission")
class BleTreadmill(
    private val context: Context,
    private val manager: BluetoothManager,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(text: String)
        fun onVisible(name: String)
        fun onConnections(count: Int)
        fun onTargetSpeed(kmh: Double)
        fun onTargetIncline(pct: Double)
    }

    companion object {
        private fun uuid16(v: Int): UUID =
            UUID.fromString(String.format(Locale.US, "0000%04x-0000-1000-8000-00805f9b34fb", v))

        val FTMS: UUID = uuid16(0x1826)
        val FTMS_FEATURE: UUID = uuid16(0x2ACC)
        val TREADMILL_DATA: UUID = uuid16(0x2ACD)
        val SPEED_RANGE: UUID = uuid16(0x2AD4)
        val INCLINE_RANGE: UUID = uuid16(0x2AD5)
        val CONTROL_POINT: UUID = uuid16(0x2AD9)
        val MACHINE_STATUS: UUID = uuid16(0x2ADA)
        val RSC: UUID = uuid16(0x1814)
        val RSC_MEASUREMENT: UUID = uuid16(0x2A53)
        val RSC_FEATURE: UUID = uuid16(0x2A54)
        val CCCD: UUID = uuid16(0x2902)
    }

    // État publié (écrit par l'activité, lu ici)
    @Volatile var speedKmh = 0.0
    @Volatile var inclinePct = 0.0
    @Volatile var distanceM = 0.0
    @Volatile var elapsedS = 0

    private val main = Handler(Looper.getMainLooper())
    private var server: BluetoothGattServer? = null
    private val pendingServices = ArrayDeque<BluetoothGattService>()
    private val devices = mutableSetOf<BluetoothDevice>()
    private val subscriptions = mutableMapOf<BluetoothDevice, MutableSet<UUID>>()

    private data class Pending(
        val device: BluetoothDevice,
        val ch: BluetoothGattCharacteristic,
        val value: ByteArray,
        val confirm: Boolean,
    )
    private val queue = ArrayDeque<Pending>()
    private var busySince = 0L

    // ---------- Cycle de vie ----------

    fun start() {
        val s = manager.openGattServer(context, callback)
        if (s == null) {
            listener.onStatus("Impossible d'ouvrir le serveur Bluetooth. Redémarre le Bluetooth du téléphone.")
            return
        }
        server = s
        pendingServices.clear()
        pendingServices.add(buildFtms())
        pendingServices.add(buildRsc())
        addNextService()
    }

    fun stop() {
        try { manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advCallback) } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        server = null
        devices.clear()
        subscriptions.clear()
        queue.clear()
    }

    /** Appelé chaque seconde (thread principal) : envoie les mesures aux appareils abonnés. */
    fun publish() {
        val s = server ?: return
        val tdChar = s.getService(FTMS)?.getCharacteristic(TREADMILL_DATA)
        val rscChar = s.getService(RSC)?.getCharacteristic(RSC_MEASUREMENT)
        val td = treadmillData()
        val rs = rscMeasurement()
        for (d in devices) {
            val subs = subscriptions[d] ?: continue
            if (tdChar != null && TREADMILL_DATA in subs) enqueue(Pending(d, tdChar, td, false))
            if (rscChar != null && RSC_MEASUREMENT in subs) enqueue(Pending(d, rscChar, rs, false))
        }
    }

    // ---------- Services ----------

    private fun readChar(uuid: UUID) = BluetoothGattCharacteristic(
        uuid,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ,
    )

    private fun notifyChar(uuid: UUID, indicate: Boolean = false, write: Boolean = false): BluetoothGattCharacteristic {
        var props = if (indicate) BluetoothGattCharacteristic.PROPERTY_INDICATE
        else BluetoothGattCharacteristic.PROPERTY_NOTIFY
        var perms = 0
        if (write) {
            props = props or BluetoothGattCharacteristic.PROPERTY_WRITE
            perms = BluetoothGattCharacteristic.PERMISSION_WRITE
        }
        return BluetoothGattCharacteristic(uuid, props, perms).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                )
            )
        }
    }

    private fun buildFtms() = BluetoothGattService(FTMS, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        addCharacteristic(readChar(FTMS_FEATURE))
        addCharacteristic(notifyChar(TREADMILL_DATA))
        addCharacteristic(readChar(SPEED_RANGE))
        addCharacteristic(readChar(INCLINE_RANGE))
        addCharacteristic(notifyChar(CONTROL_POINT, indicate = true, write = true))
        addCharacteristic(notifyChar(MACHINE_STATUS))
    }

    private fun buildRsc() = BluetoothGattService(RSC, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        addCharacteristic(notifyChar(RSC_MEASUREMENT))
        addCharacteristic(readChar(RSC_FEATURE))
    }

    private fun addNextService() {
        val next = pendingServices.removeFirstOrNull()
        if (next != null) server?.addService(next) else startAdvertising()
    }

    // ---------- Annonce Bluetooth ----------

    private var advertiseWithName = true

    private fun startAdvertising() {
        val advertiser = manager.adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            listener.onStatus("Ce téléphone ne peut pas se faire passer pour un capteur Bluetooth.")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // Données de service FTMS : machine disponible (0x01) + type tapis (bit 0)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(FTMS))
            .addServiceUuid(ParcelUuid(RSC))
            .addServiceData(ParcelUuid(FTMS), byteArrayOf(0x01, 0x01, 0x00))
            .build()
        val scan = AdvertiseData.Builder().setIncludeDeviceName(advertiseWithName).build()
        advertiser.startAdvertising(settings, data, scan, advCallback)
    }

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            main.post {
                listener.onVisible(manager.adapter?.name ?: "ce téléphone")
            }
        }

        override fun onStartFailure(errorCode: Int) {
            main.post {
                if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && advertiseWithName) {
                    advertiseWithName = false
                    startAdvertising()
                } else if (errorCode != ADVERTISE_FAILED_ALREADY_STARTED) {
                    listener.onStatus("Échec de l'annonce Bluetooth (code $errorCode)")
                }
            }
        }
    }

    // ---------- Valeurs envoyées ----------

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le24(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte())
    private fun le32(v: Int) = le16(v) + le16(v ushr 16)
    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun s16(b: ByteArray, i: Int) = u16(b, i).toShort().toInt()

    private fun readValue(uuid: UUID): ByteArray? = when (uuid) {
        // Distance totale (bit 2) + inclinaison (bit 3) + temps écoulé (bit 12)
        // Consignes acceptées : vitesse (bit 0) + inclinaison (bit 1), juste affichées
        FTMS_FEATURE -> le32(0x0000100C) + le32(0x00000003)
        SPEED_RANGE -> le16(0) + le16(2500) + le16(10)      // 0 à 25 km/h, pas 0,1
        INCLINE_RANGE -> le16(0) + le16(150) + le16(5)      // 0 à 15 %, pas 0,5
        RSC_FEATURE -> le16(0x0002)                          // distance totale
        else -> null
    }

    private fun treadmillData(): ByteArray {
        // Vitesse instantanée (toujours présente) + distance + inclinaison/rampe + temps écoulé
        val flags = 0x0004 or 0x0008 or 0x0400
        val speed = (speedKmh * 100).roundToInt().coerceIn(0, 0xFFFF)
        val dist = distanceM.toInt().coerceIn(0, 0xFFFFFF)
        val incl = (inclinePct * 10).roundToInt()
        val ramp = (Math.toDegrees(atan(inclinePct / 100.0)) * 10).roundToInt()
        return le16(flags) + le16(speed) + le24(dist) + le16(incl) + le16(ramp) +
            le16(elapsedS.coerceIn(0, 0xFFFF))
    }

    private fun rscMeasurement(): ByteArray {
        val running = speedKmh >= 7.0
        val flags = 0x02 or (if (running) 0x04 else 0)
        val speed = (speedKmh / 3.6 * 256).roundToInt().coerceIn(0, 0xFFFF)
        val cadence = estimatedCadence()
        val dist = (distanceM * 10).toLong().coerceIn(0, 0xFFFFFFFFL).toInt()
        return byteArrayOf(flags.toByte()) + le16(speed) + byteArrayOf(cadence.toByte()) + le32(dist)
    }

    /** Pas de capteur de pas : estimation grossière, seulement pour le profil foulée. */
    private fun estimatedCadence(): Int {
        val v = speedKmh
        if (v <= 0.0) return 0
        val spm = if (v < 7.0) 100 + v * 4 else 160 + (v - 10) * 2.5
        return spm.roundToInt().coerceIn(0, 220)
    }

    // ---------- File d'envoi (Android n'accepte qu'un envoi à la fois) ----------

    private fun enqueue(p: Pending) {
        queue.addLast(p)
        while (queue.size > 20) queue.removeFirst()
        pump()
    }

    private fun pump() {
        val now = SystemClock.elapsedRealtime()
        if (busySince != 0L && now - busySince < 500) return
        busySince = 0L
        val p = queue.removeFirstOrNull() ?: return
        if (p.device !in devices) { pump(); return }
        busySince = now
        if (!send(p)) {
            busySince = 0L
            main.post { pump() }
        }
    }

    @Suppress("DEPRECATION")
    private fun send(p: Pending): Boolean {
        val s = server ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                s.notifyCharacteristicChanged(p.device, p.ch, p.confirm, p.value) == BluetoothStatusCodes.SUCCESS
            } else {
                p.ch.value = p.value
                s.notifyCharacteristicChanged(p.device, p.ch, p.confirm)
            }
        } catch (_: Exception) {
            false
        }
    }

    // ---------- Consignes reçues de MyWhoosh ----------

    private fun handleControl(device: BluetoothDevice, ch: BluetoothGattCharacteristic, v: ByteArray) {
        val op = v[0].toInt() and 0xFF
        when (op) {
            0x02 -> if (v.size >= 3) listener.onTargetSpeed(u16(v, 1) / 100.0)
            0x03 -> if (v.size >= 3) listener.onTargetIncline(s16(v, 1) / 10.0)
        }
        // Prise de contrôle, reset, consignes, démarrage, pause : toujours « OK »
        val supported = op in setOf(0x00, 0x01, 0x02, 0x03, 0x07, 0x08)
        val result: Byte = if (supported) 0x01 else 0x02
        if (subscriptions[device]?.contains(CONTROL_POINT) == true) {
            enqueue(Pending(device, ch, byteArrayOf(0x80.toByte(), op.toByte(), result), true))
        }
    }

    // ---------- Callbacks GATT ----------

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            main.post { addNextService() }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    devices.add(device)
                } else {
                    devices.remove(device)
                    subscriptions.remove(device)
                    queue.removeAll { it.device == device }
                }
                listener.onConnections(devices.size)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            val value = readValue(characteristic.uuid) ?: ByteArray(0)
            if (offset > value.size) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            } else {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (characteristic.uuid == CONTROL_POINT && value != null && value.isNotEmpty()) {
                val copy = value.copyOf()
                main.post { handleControl(device, characteristic, copy) }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            val on = subscriptions[device]?.contains(descriptor.characteristic.uuid) == true
            val value = if (on) byteArrayOf(0x01, 0x00) else byteArrayOf(0x00, 0x00)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (descriptor.uuid == CCCD) {
                val enabled = value != null && value.isNotEmpty() && (value[0].toInt() and 0x03) != 0
                val charUuid = descriptor.characteristic.uuid
                main.post {
                    val s = subscriptions.getOrPut(device) { mutableSetOf() }
                    if (enabled) s.add(charUuid) else s.remove(charUuid)
                }
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            main.post {
                busySince = 0L
                pump()
            }
        }
    }
}
