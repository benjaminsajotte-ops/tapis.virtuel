package app.tapisvirtuel

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

/** Couleurs de la charte, lues depuis res/values/colors.xml (seule source). */
class Palette(ctx: Context) {
    val bg = ctx.getColor(R.color.bg)
    val surface = ctx.getColor(R.color.surface)
    val surface2 = ctx.getColor(R.color.surface_2)
    val deep = ctx.getColor(R.color.deep)
    val line = ctx.getColor(R.color.line)
    val ink = ctx.getColor(R.color.ink)
    val muted = ctx.getColor(R.color.muted)
    val accent = ctx.getColor(R.color.accent)
    val accentInk = ctx.getColor(R.color.accent_ink)
    val ok = ctx.getColor(R.color.ok)
    val warn = ctx.getColor(R.color.warn)
    val bad = ctx.getColor(R.color.bad)
    val veil = ctx.getColor(R.color.veil)
}

/** Fabrique des composants de base. Tous les écrans les réutilisent. */
class Ui(private val ctx: Context) {
    val c = Palette(ctx)
    private val condensed: Typeface = ctx.resources.getFont(R.font.barlow_condensed_bold)
    private val condensedItalic: Typeface = ctx.resources.getFont(R.font.barlow_condensed_bold_italic)
    private val body: Typeface = ctx.resources.getFont(R.font.barlow_regular)
    private val bodySemi: Typeface = ctx.resources.getFont(R.font.barlow_semibold)
    private val bodyBold: Typeface = ctx.resources.getFont(R.font.barlow_bold)

    fun dp(v: Number): Int = (v.toFloat() * ctx.resources.displayMetrics.density).roundToInt()

    fun rounded(color: Int, radiusDp: Number, strokeColor: Int? = null, strokeDp: Number = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
        }

    /** Retour visuel au toucher, dérivé de la couleur du texte. */
    private fun pressable(content: Drawable?, radiusDp: Number, rippleBase: Int): Drawable {
        val ripple = Color.argb(56, Color.red(rippleBase), Color.green(rippleBase), Color.blue(rippleBase))
        return RippleDrawable(ColorStateList.valueOf(ripple), content, rounded(c.ink, radiusDp))
    }

    // ---------- Texte ----------

    private fun base(sizeSp: Float, font: Typeface, color: Int, lineMult: Float = 1f) = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = font
        setTextColor(color)
        includeFontPadding = false
        setLineSpacing(0f, lineMult)
    }

    /** Titre de page : Barlow Condensed 700, 32, interligne 1,05. */
    fun pageTitle(text: String) = base(32f, condensed, c.ink, 1.05f).apply { this.text = text }

    /** Titre de carte : 22. */
    fun cardTitle(text: String, color: Int = c.ink) = base(22f, condensed, color, 1.05f).apply { this.text = text }

    /** Texte courant : Barlow 17, interligne 1,45. */
    fun bodyText(text: String, color: Int = c.ink) = base(17f, body, color, 1.45f).apply { this.text = text }

    /** Texte secondaire : 15. */
    fun secondary(text: String, color: Int = c.muted) = base(15f, body, color, 1.45f).apply { this.text = text }

    /** Légende : 13. */
    fun caption(text: String, color: Int = c.muted) = base(13f, body, color, 1.3f).apply { this.text = text }

    /** Étiquette : majuscules, 700, interlettrage 1 px. */
    fun label(text: String, color: Int = c.muted, sizeSp: Float = 12f) = base(sizeSp, bodyBold, color).apply {
        this.text = text.uppercase()
        letterSpacing = 1f / sizeSp
    }

    /** Grand chiffre : Barlow Condensed 700. */
    fun bigNumber(sizeSp: Float, color: Int = c.ink) = base(sizeSp, condensed, color, 1f)

    // ---------- Logo ----------

    fun logo(name: String): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(LogoMark(ctx, c.accent), LinearLayout.LayoutParams(dp(26), dp(14)))
        addView(base(30f, condensedItalic, c.ink).apply { text = name },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(8) })
        contentDescription = name
    }

    // ---------- Icônes ----------

    fun icon(res: Int, sizeDp: Int, color: Int): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // ---------- Boutons ----------

    private fun button(text: String, bg: Int?, fg: Int, onClick: () -> Unit, stroke: Int? = null) = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = bodySemi
        setTextColor(fg)
        gravity = Gravity.CENTER
        includeFontPadding = false
        minHeight = dp(48)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        val content = if (bg != null || stroke != null) rounded(bg ?: Color.TRANSPARENT, 14, stroke, 2) else null
        background = pressable(content, 14, fg)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    fun primaryButton(text: String, onClick: () -> Unit) = button(text, c.accent, c.accentInk, onClick)
    fun secondaryButton(text: String, onClick: () -> Unit) = button(text, c.surface2, c.ink, onClick)
    fun ghostButton(text: String, onClick: () -> Unit) = button(text, null, c.muted, onClick)
    fun dangerButton(text: String, onClick: () -> Unit) = button(text, c.bad, c.accentInk, onClick)
    /** Bouton blanc de la carte héros (texte couleur du fond). */
    fun heroButton(text: String, onClick: () -> Unit) = button(text, c.accentInk, c.bg, onClick)

    /** Bouton carré avec icône (en-tête 44, carte héros 48). */
    fun squareIconButton(res: Int, bg: Int, fg: Int, description: String, sizeDp: Int = 44, onClick: () -> Unit) =
        FrameLayout(ctx).apply {
            background = pressable(rounded(bg, 14), 14, fg)
            contentDescription = description
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(icon(res, 22, fg), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }

    // ---------- Conteneurs ----------

    /** Carte : fond surface, arrondi 18, marge interne 14 × 16, sans bordure. */
    fun card(bg: Int = c.surface, radiusDp: Int = 18) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(bg, radiusDp)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    /** Badge d'état : pilule, 11 px, 700. */
    fun badge(text: String, bg: Int, fg: Int, sizeSp: Float = 11f) = base(sizeSp, bodyBold, fg).apply {
        this.text = text.uppercase()
        letterSpacing = 1f / sizeSp
        background = rounded(bg, 999)
        setPadding(dp(10), dp(5), dp(10), dp(5))
    }

    fun setBadge(v: TextView, text: String, bg: Int, fg: Int) {
        v.text = text.uppercase()
        v.background = rounded(bg, 999)
        v.setTextColor(fg)
    }

    fun separator() = View(ctx).apply {
        setBackgroundColor(c.line)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    class ListRow(val view: View, val title: TextView, val subtitle: TextView, val badge: TextView)

    /** Ligne de liste : carré d'icône 38, titre 600 sur une ligne, sous-titre 13, badge à droite. */
    fun listRow(iconRes: Int, title: String, subtitle: String): ListRow {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(0, dp(8), 0, dp(8))
        }
        val square = FrameLayout(ctx).apply {
            background = rounded(c.surface, 10)
            addView(icon(iconRes, 20, c.muted), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        }
        row.addView(square, LinearLayout.LayoutParams(dp(38), dp(38)))

        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val t = base(17f, bodySemi, c.ink).apply {
            text = title
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val s = caption(subtitle).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        texts.addView(t)
        texts.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(4) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = dp(12); marginEnd = dp(12) })

        val b = badge("", c.surface2, c.ink).apply { visibility = View.GONE }
        row.addView(b)
        return ListRow(row, t, s, b)
    }

    /** Tuile de chiffre pour l'écran d'effort. */
    class Tile(val view: View, val value: TextView, val sub: TextView)

    fun tile(name: String): Tile {
        val card = card(radiusDp = 16)
        val v = bigNumber(34f)
        val s = caption("").apply { visibility = View.GONE }
        card.addView(label(name, sizeSp = 11f))
        card.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6) })
        card.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(2) })
        return Tile(card, v, s)
    }

    /** Rangée de boutons de même largeur, écart 8. */
    fun row(vararg views: View, heightDp: Int = 48) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, dp(heightDp), 1f).apply { if (i > 0) marginStart = dp(8) })
        }
    }

    fun addTo(parent: LinearLayout, v: View, topDp: Int = 0, width: Int = ViewGroup.LayoutParams.MATCH_PARENT) {
        parent.addView(v, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(topDp) })
    }
}

/** Parallélogramme du logo (22 × 14, incliné de 14°). */
class LogoMark(ctx: Context, private val color: Int) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@LogoMark.color }
    private val path = Path()
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val skew = h * 0.2493f // tan(14°)
        path.reset()
        path.moveTo(skew, 0f)
        path.lineTo(w, 0f)
        path.lineTo(w - skew, h)
        path.lineTo(0f, h)
        path.close()
        canvas.drawPath(path, paint)
    }
}

/** Jauge horizontale : piste surface, zone cible ok à 45 %, repère blanc. */
class Gauge(ctx: Context, private val c: Palette) : View(ctx) {
    var value = 0.0
        set(v) { field = v; invalidate() }
    var target: Double? = null
        set(v) { field = v; invalidate() }
    var tolerance = 0.2

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val d = ctx.resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val trackH = 12 * d
        val top = (h - trackH) / 2
        val max = max(20.0, max(value, target ?: 0.0) + 2.0)
        fun x(v: Double) = (v.coerceIn(0.0, max) / max * w).toFloat()

        paint.color = c.surface
        rect.set(0f, top, w, top + trackH)
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, paint)

        target?.let { t ->
            paint.color = c.ok
            paint.alpha = 115
            rect.set(x(t - tolerance - 0.3), top, x(t + tolerance + 0.3), top + trackH)
            canvas.drawRoundRect(rect, trackH / 2, trackH / 2, paint)
            paint.alpha = 255
        }

        paint.color = c.accentInk
        val mx = x(value).coerceIn(2 * d, w - 2 * d)
        rect.set(mx - 2 * d, 0f, mx + 2 * d, h)
        canvas.drawRoundRect(rect, 2 * d, 2 * d, paint)
    }
}

/** Fenêtre du bas : fond bg, coins hauts 24, poignée 40 × 5, voile. */
class Sheet(private val ui: Ui, private val host: FrameLayout) {
    private val veil = FrameLayout(host.context).apply {
        setBackgroundColor(ui.c.veil)
        isClickable = true
        visibility = View.GONE
        setOnClickListener { hide() }
    }
    private val panel = LinearLayout(host.context).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        background = GradientDrawable().apply {
            setColor(ui.c.bg)
            val r = ui.dp(24).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        elevation = ui.dp(16).toFloat()
        setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(24))
    }
    private val content = LinearLayout(host.context).apply { orientation = LinearLayout.VERTICAL }

    val isShowing get() = veil.visibility == View.VISIBLE

    init {
        val handle = View(host.context).apply { background = ui.rounded(ui.c.surface2, 999) }
        panel.addView(handle, LinearLayout.LayoutParams(ui.dp(40), ui.dp(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = ui.dp(16)
        })
        panel.addView(content)
        veil.addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
        ))
        host.addView(veil, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    fun show(build: (LinearLayout) -> Unit) {
        content.removeAllViews()
        build(content)
        veil.visibility = View.VISIBLE
        panel.translationY = ui.dp(320).toFloat()
        panel.animate().translationY(0f).setDuration(200).start()
    }

    fun hide() {
        veil.visibility = View.GONE
    }
}
