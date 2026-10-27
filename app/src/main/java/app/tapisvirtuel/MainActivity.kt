package app.tapisvirtuel

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

class MainActivity : Activity(), BleTreadmill.Listener {

    private lateinit var ui: Ui
    private val handler = Handler(Looper.getMainLooper())
    private var ble: BleTreadmill? = null

    // Séance
    private var speedTenths = 80      // km/h × 10
    private var inclineTenths = 0     // % × 10
    private var running = false
    private var inSession = false
    private var distanceM = 0.0
    private var elapsedMs = 0L
    private var lastTick = 0L
    private var targetSpeed: Double? = null
    private var targetIncline: Double? = null

    // Bluetooth
    private var connections = 0
    private var deviceName: String? = null
    private var visible = false
    private var bleProblem: String? = null

    // Accueil
    private lateinit var homeScreen: View
    private lateinit var heroTitle: TextView
    private lateinit var heroText: TextView
    private lateinit var btRow: Ui.ListRow
    private lateinit var nameRow: Ui.ListRow

    // Effort
    private lateinit var effortScreen: View
    private lateinit var stateBadge: TextView
    private lateinit var speedView: TextView
    private lateinit var gauge: Gauge
    private lateinit var tileIncline: Ui.Tile
    private lateinit var tileDistance: Ui.Tile
    private lateinit var tileTime: Ui.Tile
    private lateinit var tilePace: Ui.Tile
    private lateinit var pauseButton: TextView

    private lateinit var sheet: Sheet

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (running) {
                val dt = now - lastTick
                elapsedMs += dt
                distanceM += speedTenths / 10.0 / 3.6 * dt / 1000.0
            }
            lastTick = now
            pushState()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui = Ui(this)

        val host = FrameLayout(this)
        homeScreen = buildHome()
        effortScreen = buildEffort()
        host.addView(homeScreen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        host.addView(effortScreen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sheet = Sheet(ui, host)
        setContentView(host)
        showHome()

        lastTick = SystemClock.elapsedRealtime()
        handler.post(tick)

        val missing = neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startBle() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        ble?.stop()
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (sheet.isShowing) sheet.hide()
        else if (inSession) askFinish()
        else super.onBackPressed()
    }

    // ---------- Bluetooth ----------

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else emptyArray()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startBle()
        else onStatus("Autorise « Appareils à proximité » dans les réglages de l'app.")
    }

    private fun startBle() {
        val manager = getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        when {
            manager == null || adapter == null -> onStatus("Ce téléphone n'a pas de Bluetooth utilisable.")
            !adapter.isEnabled -> onStatus("Active le Bluetooth, puis relance l'app.")
            else -> ble = BleTreadmill(this, manager, this).also { it.start() }
        }
    }

    override fun onStatus(text: String) {
        bleProblem = text
        visible = false
        refresh()
    }

    override fun onVisible(name: String) {
        deviceName = name
        visible = true
        bleProblem = null
        refresh()
    }

    override fun onConnections(count: Int) {
        connections = count
        refresh()
    }

    override fun onTargetSpeed(kmh: Double) {
        targetSpeed = kmh
        refresh()
    }

    override fun onTargetIncline(pct: Double) {
        targetIncline = pct
        refresh()
    }

    // ---------- Séance ----------

    private fun startSession() {
        inSession = true
        running = true
        lastTick = SystemClock.elapsedRealtime()
        showEffort()
        pushState()
    }

    private fun toggleRun() {
        running = !running
        lastTick = SystemClock.elapsedRealtime()
        pushState()
    }

    private fun endSession() {
        sheet.hide()
        inSession = false
        running = false
        distanceM = 0.0
        elapsedMs = 0L
        targetSpeed = null
        targetIncline = null
        showHome()
        pushState()
    }

    private fun changeSpeed(delta: Int) {
        speedTenths = (speedTenths + delta).coerceIn(0, 250)
        pushState()
    }

    private fun changeIncline(delta: Int) {
        inclineTenths = (inclineTenths + delta).coerceIn(0, 150)
        pushState()
    }

    private fun pushState() {
        ble?.let {
            it.speedKmh = if (running) speedTenths / 10.0 else 0.0
            it.inclinePct = inclineTenths / 10.0
            it.distanceM = distanceM
            it.elapsedS = (elapsedMs / 1000).toInt()
            it.publish()
        }
        refresh()
    }

    // ---------- Navigation ----------

    private fun showHome() {
        homeScreen.visibility = View.VISIBLE
        effortScreen.visibility = View.GONE
        window.statusBarColor = ui.c.bg
        window.navigationBarColor = ui.c.bg
    }

    private fun showEffort() {
        homeScreen.visibility = View.GONE
        effortScreen.visibility = View.VISIBLE
        window.statusBarColor = ui.c.deep
        window.navigationBarColor = ui.c.deep
    }

    private fun askFinish() {
        sheet.show { box ->
            ui.addTo(box, ui.cardTitle("Terminer la séance ?"))
            ui.addTo(box, ui.secondary("La distance et le temps repartent à zéro."), 8)
            ui.addTo(box, ui.dangerButton("Terminer") { endSession() }, 24)
            ui.addTo(box, ui.ghostButton("Continuer") { sheet.hide() }, 8)
        }
    }

    private fun showHelp() {
        val name = deviceName ?: "ce téléphone"
        val steps = listOf(
            "Lance MyWhoosh sur ton PC ou ta tablette, pas sur ce téléphone.",
            "En mode course, ouvre l'appairage et choisis le tapis « $name ».",
            "Connecte ta ceinture cardio directement à MyWhoosh.",
            "Démarre ici, puis recopie chaque changement fait sur le tapis.",
        )
        sheet.show { box ->
            ui.addTo(box, ui.cardTitle("Connecter MyWhoosh"))
            steps.forEachIndexed { i, s -> ui.addTo(box, ui.bodyText("${i + 1}. $s"), if (i == 0) 12 else 8) }
            ui.addTo(box, ui.caption("Ça marche aussi avec Zwift, de la même façon."), 12)
            ui.addTo(box, ui.primaryButton("Compris") { sheet.hide() }, 24)
        }
    }

    // ---------- Mise à jour de l'affichage ----------

    private fun fr(v: Double, decimals: Int = 1) = String.format(Locale.FRANCE, "%.${decimals}f", v)

    private fun refresh() {
        if (!::pauseButton.isInitialized) return
        val c = ui.c
        val speed = speedTenths / 10.0
        val name = deviceName ?: "ce téléphone"

        // Accueil
        heroTitle.text = if (connections > 0) "Connecté à MyWhoosh" else "En attente de MyWhoosh"
        heroText.text = bleProblem
            ?: if (connections > 0) "Règle ton tapis, puis démarre."
            else "Choisis « $name » dans l'appairage du tapis."
        when {
            bleProblem != null -> {
                btRow.subtitle.text = bleProblem
                ui.setBadge(btRow.badge, "À faire", c.accent, c.accentInk)
                btRow.badge.visibility = View.VISIBLE
            }
            visible -> {
                btRow.subtitle.text = "Visible pour MyWhoosh"
                ui.setBadge(btRow.badge, "Actif", c.ok, c.accentInk)
                btRow.badge.visibility = View.VISIBLE
            }
            else -> {
                btRow.subtitle.text = "Démarrage…"
                btRow.badge.visibility = View.GONE
            }
        }
        nameRow.subtitle.text = deviceName ?: "–"

        // Effort
        val t = targetSpeed
        when {
            connections == 0 -> ui.setBadge(stateBadge, "MyWhoosh non connecté", c.surface2, c.muted)
            !running -> ui.setBadge(stateBadge, "En pause", c.surface2, c.ink)
            t == null -> ui.setBadge(stateBadge, "Course libre", c.surface2, c.ink)
            abs(speed - t) <= 0.2 -> ui.setBadge(stateBadge, "Dans la cible", c.ok, c.accentInk)
            else -> ui.setBadge(stateBadge, "Vise ${fr(t)} km/h", c.warn, c.accentInk)
        }
        speedView.text = fr(speed)
        gauge.value = speed
        gauge.target = t

        tileIncline.value.text = "${fr(inclineTenths / 10.0)} %"
        val ti = targetIncline
        tileIncline.sub.text = if (ti != null) "Cible ${fr(ti)} %" else ""
        tileIncline.sub.visibility = if (ti != null) View.VISIBLE else View.GONE

        tileDistance.value.text = "${fr(distanceM / 1000, 2)} km"
        val s = elapsedMs / 1000
        tileTime.value.text = if (s >= 3600) String.format(Locale.FRANCE, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format(Locale.FRANCE, "%d:%02d", s / 60, s % 60)
        tilePace.value.text = if (speed > 0) {
            val secPerKm = (3600 / speed).toInt()
            String.format(Locale.FRANCE, "%d:%02d /km", secPerKm / 60, secPerKm % 60)
        } else "–"

        pauseButton.text = if (running) "Pause" else "Reprendre"
    }

    // ---------- Construction des écrans ----------

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(24))
    }

    /** Écran défilant, contenu centré sur 640 max. */
    private fun screen(bg: Int, col: LinearLayout): View {
        val width = min(resources.displayMetrics.widthPixels, ui.dp(640))
        val wrapper = FrameLayout(this).apply {
            addView(col, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
        return ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(wrapper)
        }
    }

    private fun buildHome(): View {
        val c = ui.c
        val col = column()
        ui.addTo(col, ui.logo("Tapis virtuel"))

        // Carte héros
        val hero = ui.card(c.accent)
        hero.addView(ui.label("MyWhoosh", c.accentInk))
        heroTitle = ui.bigNumber(30f, c.accentInk)
        ui.addTo(hero, heroTitle, 8)
        heroText = ui.secondary("", c.accentInk)
        ui.addTo(hero, heroText, 6)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(ui.heroButton("Démarrer") { startSession() },
            LinearLayout.LayoutParams(0, ui.dp(48), 1f))
        actions.addView(
            ui.squareIconButton(R.drawable.ic_help, c.bg, c.ink, "Aide pour connecter MyWhoosh", 48) { showHelp() },
            LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { marginStart = ui.dp(8) },
        )
        ui.addTo(hero, actions, 16)
        ui.addTo(col, hero, 24)

        // Liste
        ui.addTo(col, ui.label("Ce téléphone"), 32)
        btRow = ui.listRow(R.drawable.ic_bluetooth, "Bluetooth", "Démarrage…")
        nameRow = ui.listRow(R.drawable.ic_phone, "Nom dans MyWhoosh", "–")
        ui.addTo(col, btRow.view, 8)
        col.addView(ui.separator())
        ui.addTo(col, nameRow.view)

        return screen(c.bg, col)
    }

    private fun buildEffort(): View {
        val c = ui.c
        val col = column()

        stateBadge = ui.badge("Course libre", c.surface2, c.ink, 12f)
        ui.addTo(col, stateBadge, 0, ViewGroup.LayoutParams.WRAP_CONTENT)

        speedView = ui.bigNumber(120f)
        ui.addTo(col, speedView, 8, ViewGroup.LayoutParams.WRAP_CONTENT)
        ui.addTo(col, ui.secondary("km/h"), 0, ViewGroup.LayoutParams.WRAP_CONTENT)

        gauge = Gauge(this, c)
        col.addView(gauge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(24))
            .apply { topMargin = ui.dp(16) })

        tileIncline = ui.tile("Inclinaison")
        tileDistance = ui.tile("Distance")
        tileTime = ui.tile("Temps")
        tilePace = ui.tile("Allure")
        ui.addTo(col, pair(tileIncline.view, tileDistance.view), 24)
        ui.addTo(col, pair(tileTime.view, tilePace.view), 8)

        fun step(label: String, desc: String, action: () -> Unit) =
            ui.secondaryButton(label, action).apply { contentDescription = desc }

        ui.addTo(col, ui.row(
            step("−1", "Vitesse moins 1 km/h") { changeSpeed(-10) },
            step("−0,1", "Vitesse moins 0,1 km/h") { changeSpeed(-1) },
            step("+0,1", "Vitesse plus 0,1 km/h") { changeSpeed(1) },
            step("+1", "Vitesse plus 1 km/h") { changeSpeed(10) },
        ), 24)
        ui.addTo(col, ui.row(
            step("−1 %", "Inclinaison moins 1 %") { changeIncline(-10) },
            step("−0,5 %", "Inclinaison moins 0,5 %") { changeIncline(-5) },
            step("+0,5 %", "Inclinaison plus 0,5 %") { changeIncline(5) },
            step("+1 %", "Inclinaison plus 1 %") { changeIncline(10) },
        ), 8)

        pauseButton = ui.primaryButton("Pause") { toggleRun() }
        col.addView(pauseButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(56))
            .apply { topMargin = ui.dp(24) })
        ui.addTo(col, ui.ghostButton("Terminer") { askFinish() }, 8)

        return screen(c.deep, col)
    }

    private fun pair(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(8) })
    }
}
