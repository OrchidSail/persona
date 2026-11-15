package dev.persona.watch.extensions

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import dev.persona.watch.CredentialStore
import dev.persona.watch.HubDirectory
import dev.persona.watch.R
import dev.persona.watch.data.HubConfig
import dev.persona.watch.ui.WatchTheme as T
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/** Wrist-sized, read-only extension projections. Provider actions never become Android controls. */
class ExtensionWidgetsActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var diskCache: ExtensionCacheStore
    private var group = ""
    private var deepLink: String? = null
    private var client: ExtensionClient? = null
    private var catalogReady = false
    private var config: HubConfig? = null
    private var widgets = emptyList<ExtensionWidget>()
    private var selected: ExtensionWidget? = null
    private var selectedKey: String? = null
    private var projection: ExtensionWidgetView? = null
    private var chartValues: WidgetItem.Chart? = null
    private var problem: ExtensionProblem? = null
    private var busy = false
    private var unregisterSystemBack: (() -> Unit)? = null
    @Volatile private var foreground = false
    @Volatile private var generation = 0
    private val expire = Runnable { if (foreground) render(true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = T.background
        window.navigationBarColor = T.background
        group = intent.getStringExtra("group").orEmpty()
        deepLink = intent.dataString
        diskCache = ExtensionComplications.store(this)
        selectedKey = savedInstanceState?.getString("widget") ?: intent.getStringExtra("widget_key")
        selectedKey?.let { key ->
            val segments = key.split('/')
            if (segments.size != 2 || segments.any { !ExtensionParser.validID(it) }) problem = ExtensionProblem.INVALID_LINK
        }
        if (group.isNotEmpty() && !group.matches(Regex("[a-f0-9-]{36}"))) problem = ExtensionProblem.INVALID_LINK
        if (deepLink != null && ExtensionLink.parse(deepLink!!, group) == null) problem = ExtensionProblem.INVALID_LINK
        render()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback { navigateBack() }
            val dispatcher = onBackInvokedDispatcher
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            unregisterSystemBack = { dispatcher.unregisterOnBackInvokedCallback(callback) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("widget", selected?.key ?: selectedKey)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (problem != ExtensionProblem.INVALID_LINK) loadCatalog()
    }

    override fun onPause() {
        foreground = false
        generation++
        busy = false
        main.removeCallbacks(expire)
        super.onPause()
    }

    override fun onDestroy() {
        unregisterSystemBack?.invoke()
        unregisterSystemBack = null
        generation++
        io.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Legacy system back; Android 13 and later use OnBackInvokedDispatcher")
    override fun onBackPressed() = navigateBack()

    private fun navigateBack() {
        if (isFinishing || isDestroyed) return
        generation++; busy = false
        when {
            chartValues != null -> { chartValues = null; render() }
            selected != null && deepLink == null -> { selected = null; selectedKey = null; projection = null; problem = null; render() }
            else -> finish()
        }
    }

    private fun savedConfig(): HubConfig? = if (group.isEmpty()) CredentialStore(this).load()
        else HubDirectory(this).additional().singleOrNull { it.id == group }?.config

    /** Every refresh rechecks the saved group, including after returning from another activity. */
    private fun loadCatalog() {
        if (busy || isDestroyed) return
        val ticket = ++generation
        busy = true
        catalogReady = false
        problem = null
        val previousWidgets = widgets
        val previousProjection = projection
        val previousSelectedKey = selected?.key
        val requestedKey = previousSelectedKey ?: selectedKey
        // Hide cached private content until the connection identity has been checked off the UI thread.
        widgets = emptyList(); projection = null
        render()
        io.execute {
            val result = runCatching {
                val saved = savedConfig()?.validated() ?: throw ExtensionException(ExtensionProblem.UNPAIRED)
                val same = saved == config
                val api = if (same) client ?: ExtensionClient(saved, language()) else ExtensionClient(saved, language())
                val savedWidgets = runCatching { diskCache.catalog(group, saved) }.getOrNull().orEmpty()
                val savedWidget = deepLink?.let { ExtensionLink.parse(it, group)?.projection(savedWidgets) }
                    ?: savedWidgets.firstOrNull { it.key == requestedKey }
                val savedView = savedWidget?.let { runCatching { diskCache.projection(group, saved, it, it.family)?.view }.getOrNull() }
                val catalogResult = runCatching { api.widgets() }
                if (savedConfig() != saved) throw ExtensionException(ExtensionProblem.CONNECTION_CHANGED)
                catalogResult.onSuccess { runCatching { diskCache.saveCatalog(group, saved, it) }; ExtensionComplications.changed(this) }
                    .onFailure { failure -> if (problemOf(failure) in setOf(ExtensionProblem.AUTHENTICATION, ExtensionProblem.FORBIDDEN, ExtensionProblem.UNAVAILABLE)) {
                        runCatching { diskCache.invalidate(group, saved) }; ExtensionComplications.changed(this)
                    } }
                LoadResult(saved, api, same, catalogResult, savedWidgets, savedWidget, savedView)
            }
            main.post {
                if (!foreground || isDestroyed || ticket != generation) return@post
                busy = false
                result.fold({ loaded ->
                    if (!loaded.same) { selected = null; chartValues = null }
                    config = loaded.config
                    client = loaded.client
                    loaded.catalog.fold({ found ->
                        catalogReady = true
                        widgets = found
                        selected = deepLink?.let { ExtensionLink.parse(it, group)?.projection(found) }
                            ?: found.firstOrNull { it.key == (selected?.key ?: selectedKey) }
                        if ((deepLink != null || requestedKey != null) && selected == null) problem = ExtensionProblem.UNAVAILABLE
                        projection = if (loaded.same && selected?.key == previousSelectedKey) previousProjection
                            else loaded.savedView.takeIf { loaded.savedWidget?.key == selected?.key }
                        render()
                        selected?.let { loadWidget(it, force = false) }
                    }, { failure ->
                        problem = problemOf(failure)
                        val mayKeep = problem == ExtensionProblem.RETRYABLE
                        widgets = if (mayKeep) loaded.savedWidgets.ifEmpty { if (loaded.same) previousWidgets else emptyList() } else emptyList()
                        projection = if (mayKeep) loaded.savedView ?: previousProjection.takeIf { loaded.same } else null
                        selected = if (mayKeep) loaded.savedWidget ?: selected.takeIf { loaded.same } else null
                        render()
                    })
                }, { failure ->
                    problem = problemOf(failure)
                    config = null; client = null; widgets = emptyList(); selected = null; projection = null; chartValues = null
                    render()
                })
            }
        }
    }

    private fun loadWidget(widget: ExtensionWidget, force: Boolean) {
        if (busy) return
        selected = widget; selectedKey = widget.key; chartValues = null
        // A disk snapshot cannot authorize a new projection: retry discovery after an offline catalogue.
        if (!catalogReady) { loadCatalog(); return }
        val api = client ?: return
        val expected = config ?: return
        val ticket = ++generation
        busy = true; problem = null; render()
        io.execute {
            val result = runCatching {
                if (savedConfig() != expected) throw ExtensionException(ExtensionProblem.CONNECTION_CHANGED)
                api.widget(widget, force).also {
                    if (savedConfig() != expected) throw ExtensionException(ExtensionProblem.CONNECTION_CHANGED)
                    runCatching { diskCache.saveProjection(group, expected, widget, widget.family, it) }
                    ExtensionComplications.changed(this)
                }
            }.onFailure { failure ->
                if (problemOf(failure) in setOf(ExtensionProblem.AUTHENTICATION, ExtensionProblem.FORBIDDEN, ExtensionProblem.UNAVAILABLE)) {
                    runCatching { diskCache.invalidate(group, expected) }
                    ExtensionComplications.changed(this)
                }
            }
            main.post {
                if (!foreground || isDestroyed || ticket != generation) return@post
                busy = false
                result.fold({ projection = it }, { failure ->
                    problem = problemOf(failure)
                    if (problem != ExtensionProblem.RETRYABLE) projection = null
                    if (problem in setOf(ExtensionProblem.AUTHENTICATION, ExtensionProblem.FORBIDDEN, ExtensionProblem.CONNECTION_CHANGED)) {
                        widgets = emptyList(); selected = null; selectedKey = null; chartValues = null
                    }
                })
                render()
            }
            if (result.isSuccess) refreshConfiguredFaces(api, expected, widget, ticket)
        }
    }

    /** User-initiated foreground refresh also updates only the accessory families the user configured. */
    private fun refreshConfiguredFaces(api: ExtensionClient, expected: HubConfig, widget: ExtensionWidget, ticket: Int) {
        val families = runCatching { diskCache.configured(group, expected, widget).map { it.family }.distinct() }.getOrDefault(emptyList())
        for (family in families.filter { it != widget.family }) {
            if (!foreground || generation != ticket || savedConfig() != expected) return
            runCatching { api.widget(widget, force = true, family = family) }.onSuccess { view ->
                if (savedConfig() == expected) runCatching { diskCache.saveProjection(group, expected, widget, family, view) }
            }.onFailure { failure ->
                if (problemOf(failure) in setOf(ExtensionProblem.AUTHENTICATION, ExtensionProblem.FORBIDDEN)) runCatching { diskCache.invalidate(group, expected) }
            }
        }
        ExtensionComplications.changed(this)
    }

    private fun render(preserveScroll: Boolean = false) {
        val old = if (preserveScroll && ::scroll.isInitialized) scroll.scrollY else 0
        main.removeCallbacks(expire)
        scroll = ScrollView(this).apply { setBackgroundColor(T.background); isFillViewport = true }
        body = T.column(this).apply { setPadding(dp(T.screenInset), dp(T.roundEndInset), dp(T.screenInset), dp(T.roundEndInset)) }
        scroll.addView(body); setContentView(scroll)
        text(selected?.title ?: getString(R.string.extension_widgets), T.title, bold = true)
        if (busy) text(getString(R.string.loading), T.caption, T.secondary)
        problem?.let { text(getString(problemString(it)), T.body, T.working) }
        val values = chartValues
        if (values != null) {
            values.series.forEach { series ->
                text(series.name, T.heading, bold = true)
                series.points.forEach { point -> text(pointText(point, values.unit, values.kind == "candlestick"), T.caption) }
            }
            button(getString(R.string.back), duringLoad = true) { navigateBack() }
        } else if (selected != null) {
            projection?.let { view ->
                if (view.title.isNotBlank() && view.title != selected?.title) text(view.title, T.heading, bold = true)
                if (problem == ExtensionProblem.RETRYABLE || view.expiresAtMs <= System.currentTimeMillis())
                    text(getString(R.string.extension_stale), T.caption, T.working)
                else main.postDelayed(expire, (view.expiresAtMs - System.currentTimeMillis()).coerceAtMost(86_400_000L))
                if (view.items.isEmpty()) text(getString(R.string.extension_no_content), T.body, T.secondary)
                view.items.forEach(::item)
            }
            text(getString(R.string.extension_read_only), T.caption, T.secondary)
            button(getString(R.string.refresh)) { selected?.let { loadWidget(it, force = true) } }
            button(getString(R.string.back), duringLoad = true) { navigateBack() }
        } else {
            if (!busy && problem == null && widgets.isEmpty()) text(getString(R.string.extension_empty), T.body, T.secondary)
            widgets.forEach { widget ->
                button(widget.title) { projection = null; loadWidget(widget, force = false) }
                if (widget.source.isNotBlank()) text(widget.source, T.caption, T.secondary)
            }
            if (problem != ExtensionProblem.INVALID_LINK) button(getString(R.string.refresh)) { loadCatalog() }
            button(getString(R.string.back), duringLoad = true) { finish() }
        }
        scroll.post { scroll.scrollTo(0, old) }
    }

    private fun item(value: WidgetItem) {
        val card = T.column(this).apply {
            background = T.surface(this@ExtensionWidgetsActivity)
            setPadding(dp(T.padding), dp(T.gap), dp(T.padding), dp(T.gap))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(T.gap) }
        }
        val outer = body
        body.addView(card); body = card
        when (value) {
            is WidgetItem.Text -> text(value.text)
            is WidgetItem.Metric -> metric(value.metric)
            is WidgetItem.Progress -> {
                text(value.label, T.caption, T.secondary)
                val percent = NumberFormat.getPercentInstance().format(value.ratio)
                text(percent, T.heading)
                body.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = ExtensionTokens.progressSteps; progress = (value.ratio * max).toInt()
                    progressTintList = android.content.res.ColorStateList.valueOf(T.accent)
                    contentDescription = "${value.label}: $percent"
                    layoutParams = LinearLayout.LayoutParams(-1, dp(ExtensionTokens.progressHeight))
                })
                text(value.detail, T.caption, T.secondary)
            }
            is WidgetItem.Row -> { text(value.title, T.heading); text(value.subtitle, T.caption, T.secondary); text(value.value) }
            is WidgetItem.Stats -> value.values.forEach(::metric)
            is WidgetItem.Chart -> chart(value)
        }
        body = outer
    }

    private fun metric(value: WidgetMetric) {
        text(value.label, T.caption, T.secondary)
        text(value.value, T.heading, bold = true)
        text(value.detail, T.caption, T.secondary)
    }

    private fun chart(value: WidgetItem.Chart) {
        text(value.title, T.heading)
        value.series.forEach { series ->
            text(series.name, T.caption, T.secondary)
            if (value.kind == "candlestick") series.points.take(4).forEach { text(pointText(it, value.unit, true), T.caption) }
            else if (series.points.isNotEmpty()) {
                body.addView(WidgetChartView(this, value.kind, series.points).apply {
                    contentDescription = series.points.joinToString("\n") { pointText(it, value.unit, false) }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                })
                text(pointText(series.points.last(), value.unit, false), T.caption)
            }
        }
        if (value.series.any { it.points.isNotEmpty() }) button(getString(R.string.extension_chart_values)) { chartValues = value; render() }
    }

    private fun pointText(point: WidgetPoint, unit: String, candle: Boolean): String {
        if (candle) return getString(R.string.extension_candle_values, point.label, number(point.open), number(point.high), number(point.low), number(point.close))
        return "${point.label}: ${point.display ?: number(point.value)}$unit"
    }
    private fun number(value: Double?) = value?.let { String.format(Locale.getDefault(), "%.4g", it) } ?: getString(R.string.extension_no_value)
    private fun text(value: String, size: Float = T.body, color: Int = T.ink, bold: Boolean = false) {
        if (value.isBlank()) return
        body.addView(T.text(this, value, size, color, bold).apply {
            setPadding(T.zero, dp(T.lineGap), T.zero, dp(T.lineGap))
            setTextIsSelectable(true)
            if (bold) isAccessibilityHeading = true
        })
    }
    private fun button(label: String, duringLoad: Boolean = false, action: () -> Unit) {
        body.addView(T.button(this, label, onClick = action).apply { isEnabled = duringLoad || !busy; contentDescription = label })
    }
    private fun language() = when (resources.configuration.locales[0].language) { "zh" -> "zh-Hans"; "ja" -> "ja"; else -> "en" }
    private fun dp(value: Int) = T.dp(this, value)
    private fun problemOf(error: Throwable) = (error as? ExtensionException)?.problem ?: ExtensionProblem.RETRYABLE
    private fun problemString(problem: ExtensionProblem) = when (problem) {
        ExtensionProblem.UNPAIRED -> R.string.extension_unpaired
        ExtensionProblem.AUTHENTICATION -> R.string.authentication_failed
        ExtensionProblem.FORBIDDEN -> R.string.extension_forbidden
        ExtensionProblem.UNAVAILABLE -> R.string.extension_unavailable
        ExtensionProblem.RETRYABLE -> R.string.extension_retryable
        ExtensionProblem.INVALID_RESPONSE -> R.string.extension_invalid_response
        ExtensionProblem.INVALID_LINK -> R.string.extension_invalid_link
        ExtensionProblem.CONNECTION_CHANGED -> R.string.extension_connection_changed
    }
    private data class LoadResult(val config: HubConfig, val client: ExtensionClient, val same: Boolean, val catalog: Result<List<ExtensionWidget>>,
        val savedWidgets: List<ExtensionWidget>, val savedWidget: ExtensionWidget?, val savedView: ExtensionWidgetView?)
}

/** Small-multiple plot; full labelled values remain available without relying on color or hover. */
private class WidgetChartView(context: Context, private val kind: String, private val points: List<WidgetPoint>) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = T.ink; strokeWidth = T.dp(context, ExtensionTokens.chartLine).toFloat() }
    init { layoutParams = LinearLayout.LayoutParams(-1, T.dp(context, ExtensionTokens.chartHeight)) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty() || width <= 0 || height <= 0) return
        val line = kind in setOf("line", "area")
        val scale = points.maxOf { abs(it.value) }.coerceAtLeast(1.0)
        val normalized = points.map { it.value / scale }
        val low = if (line) normalized.minOrNull()!! else minOf(0.0, normalized.minOrNull()!!)
        val high = if (line) normalized.maxOrNull()!! else maxOf(0.0, normalized.maxOrNull()!!)
        val inset = T.dp(context, T.lineGap).toFloat()
        val spanX = (width - inset * 2).coerceAtLeast(1f)
        val spanY = (height - inset * 2).coerceAtLeast(1f)
        fun x(index: Int) = inset + (index + 0.5f) * spanX / points.size
        fun y(value: Double) = inset + (if (high == low) 0.5 else 1.0 - (value - low) / (high - low)).toFloat() * spanY
        paint.color = T.ink; paint.alpha = 255
        if (line) {
            val path = Path()
            normalized.forEachIndexed { index, value -> if (index == 0) path.moveTo(x(index), y(value)) else path.lineTo(x(index), y(value)) }
            if (kind == "area") {
                val fill = Path(path).apply { lineTo(x(points.lastIndex), y(low)); lineTo(x(0), y(low)); close() }
                paint.style = Paint.Style.FILL; paint.alpha = ExtensionTokens.chartFillAlpha; canvas.drawPath(fill, paint); paint.alpha = 255
            }
            paint.style = Paint.Style.STROKE; canvas.drawPath(path, paint)
            if (points.size == 1) { paint.style = Paint.Style.FILL; canvas.drawCircle(x(0), y(normalized[0]), paint.strokeWidth, paint) }
        } else {
            paint.style = Paint.Style.FILL
            val half = minOf(T.dp(context, ExtensionTokens.chartBar).toFloat(), spanX / points.size * 0.7f) / 2
            normalized.forEachIndexed { index, value -> canvas.drawRect(x(index) - half, minOf(y(value), y(0.0)), x(index) + half, maxOf(y(value), y(0.0)), paint) }
        }
    }
}

/** Watch-specific counterparts of ExtensionSurfaceTokens; no extension controls share this surface. */
private object ExtensionTokens {
    const val chartHeight = 72
    const val chartLine = 2
    const val chartBar = 6
    const val chartFillAlpha = 42
    const val progressHeight = 8
    const val progressSteps = 1000
}
