package dev.persona.watch.pet

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.persona.watch.CredentialStore
import dev.persona.watch.HubDirectory
import dev.persona.watch.HubGroupReader
import dev.persona.watch.R
import dev.persona.watch.data.HubConfig
import dev.persona.watch.data.HubException
import dev.persona.watch.data.WatchSnapshot
import dev.persona.watch.ui.WatchTheme as T
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/** A glanceable companion for everyone. Only this visible page animates the original Milk Frog. */
class PetActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val connections = Executors.newFixedThreadPool(4)
    private var groupCache = emptyMap<String, WatchSnapshot>()
    private var config: HubConfig? = null
    private var pack: PetPack? = null
    private var manifest: PetManifest? = null
    private var status = PetStatus(PetState.OFFLINE)
    private var updatedAt = 0L
    private var foreground = false
    private var busy = false
    @Volatile private var generation = 0
    private var frames = emptyList<Bitmap>()
    private var face: Bitmap? = null
    private var clipName = ""
    private var clipStart = 0L
    private lateinit var art: ImageView
    private lateinit var stateLabel: TextView
    private lateinit var dateLabel: TextView
    private lateinit var notice: TextView
    private val poll = object : Runnable {
        override fun run() { if (foreground) { if (interactive()) refresh(false); handler.postDelayed(this, PetTokens.pollMs) } }
    }
    private val animate = object : Runnable {
        override fun run() {
            if (!foreground) return
            val clip = manifest?.clips?.get(clipName)
            if (interactive() && motionEnabled() && clip != null && frames.isNotEmpty()) {
                val index = ((SystemClock.elapsedRealtime() - clipStart) * clip.fps / 1000).toInt()
                if (clipName == "tap" && index >= frames.size) { play(status.state.wire); return }
                art.setImageBitmap(frames[index % frames.size])
                handler.postDelayed(this, (1000 / clip.fps).toLong())
            } else {
                art.setImageBitmap(face)
                handler.postDelayed(this, PetTokens.stillCheckMs)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = T.background; window.navigationBarColor = T.background
        config = runCatching { CredentialStore(this).load()?.validated() }.getOrNull()
        pack = config?.let { PetPack(cacheDir, it) }
        val body = T.column(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(T.screenInset), dp(T.roundEndInset), dp(T.screenInset), dp(T.roundEndInset))
        }
        body.addView(T.text(this, getString(R.string.pet_title), T.title, bold = true).apply { isAccessibilityHeading = true })
        art = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(PetTokens.artSizeDp), dp(PetTokens.artSizeDp))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.pet_tap)
            isClickable = true
            setOnClickListener {
                if (manifest != null) { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); play(if (status.state == PetState.IDLE) "tap" else status.state.wire) }
            }
        }
        body.addView(art)
        stateLabel = T.text(this, getString(R.string.loading), T.heading).apply { gravity = Gravity.CENTER }
        dateLabel = T.text(this, "", T.caption, T.secondary).apply { gravity = Gravity.CENTER }
        notice = T.text(this, "", T.body, T.secondary).apply { gravity = Gravity.CENTER }
        body.addView(stateLabel); body.addView(dateLabel); body.addView(notice)
        body.addView(T.button(this, getString(R.string.retry)) { refresh(true) })
        body.addView(T.button(this, getString(R.string.back)) { finish() })
        setContentView(ScrollView(this).apply { setBackgroundColor(T.background); addView(body) })
        val connection = config
        if (connection == null) {
            stateLabel.text = getString(R.string.pet_unavailable)
            notice.text = getString(R.string.pet_pair_first)
        } else {
            val saved = PetBridge.read(this, connection)
            status = saved.visible(); updatedAt = saved.updatedAt
            worker.execute {
                val cached = pack?.cached()
                handler.post { if (!isDestroyed && cached != null) { manifest = cached; showState(); play(status.state.wire) } }
            }
        }
    }

    override fun onResume() {
        super.onResume(); foreground = true
        val visible = PetStoredStatus(status, updatedAt).visible()
        if (status != visible) { status = visible; showState(); play(status.state.wire) }
        refresh(manifest == null)
        handler.postDelayed(poll, PetTokens.pollMs)
        handler.post(animate)
    }
    override fun onPause() {
        foreground = false
        handler.removeCallbacks(poll); handler.removeCallbacks(animate)
        art.setImageBitmap(face)
        super.onPause()
    }
    override fun onDestroy() {
        generation++; handler.removeCallbacksAndMessages(null); worker.shutdownNow(); connections.shutdownNow()
        art.setImageDrawable(null)
        frames.forEach { if (!it.isRecycled) it.recycle() }
        face?.let { if (!it.isRecycled) it.recycle() }
        frames = emptyList(); face = null
        super.onDestroy()
    }

    private fun refresh(fetchAssets: Boolean) {
        val connection = config ?: return
        if (busy) return
        busy = true
        if (manifest == null) notice.text = getString(R.string.pet_loading)
        worker.execute {
            var nextManifest = manifest
            val assetResult = if (fetchAssets || nextManifest == null) runCatching { pack!!.fetch() } else null
            assetResult?.getOrNull()?.let { nextManifest = it }
            val extras = HubDirectory(applicationContext).additional()
            val read = runCatching { HubGroupReader.read(connection, extras, groupCache,
                resources.configuration.locales[0].toLanguageTag(), connections) }
            read.getOrNull()?.let { groupCache = it.cache }
            val allFailed = read.isFailure || read.getOrNull()?.failed?.size == extras.size + 1
            val partlyFailed = read.getOrNull()?.failed?.isNotEmpty() == true
            val nextStatus = PetStatus.from(read.getOrNull()?.snapshot, allFailed, partlyUnreachable = partlyFailed)
            val nextDate = if (allFailed) updatedAt else read.getOrNull()?.snapshot?.updatedAt ?: updatedAt
            PetBridge.publish(applicationContext, connection, nextStatus, nextDate)
            handler.post {
                if (isDestroyed) return@post
                busy = false
                val changed = status != nextStatus || manifest == null && nextManifest != null
                status = nextStatus; updatedAt = nextDate; manifest = nextManifest
                showState()
                notice.text = when {
                    manifest == null && assetResult?.exceptionOrNull().let { it is HubException && it.status in listOf(403, 404) } -> getString(R.string.pet_unsupported)
                    manifest == null -> getString(R.string.pet_unavailable_body)
                    allFailed -> getString(R.string.pet_retry_body)
                    partlyFailed -> getString(R.string.hub_partial)
                    else -> ""
                }
                if (changed || frames.isEmpty()) play(status.state.wire)
            }
        }
    }

    private fun showState() {
        stateLabel.text = if (manifest == null) getString(R.string.pet_unavailable) else PetPresentation.title(this, status)
        dateLabel.text = if (updatedAt > 0) getString(R.string.pet_updated, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(updatedAt))) else ""
        art.visibility = if (manifest == null) View.GONE else View.VISIBLE
    }

    private fun play(name: String) {
        val current = manifest ?: return
        val clip = current.clips[name] ?: return
        val source = pack ?: return
        val currentGeneration = ++generation
        val currentState = status.state.wire
        clipName = name
        handler.removeCallbacks(animate)
        worker.execute {
            if (isDestroyed || currentGeneration != generation) return@execute
            val still = current.faces[currentState]?.let { runCatching { PetPresentation.face(source.file(it)) }.getOrNull() }
            val decoded = runCatching { PetAnimation.decode(source.file(clip.asset).readBytes(), clip.frames) }.getOrDefault(emptyList())
            handler.post {
                if (isDestroyed || currentGeneration != generation) {
                    still?.recycle()
                    decoded.forEach { it.recycle() }
                    return@post
                }
                val previousFace = face
                val previousFrames = frames
                face = still; frames = decoded; clipStart = SystemClock.elapsedRealtime()
                art.setImageBitmap(still)
                previousFace?.let { if (!it.isRecycled) it.recycle() }
                previousFrames.forEach { if (!it.isRecycled) it.recycle() }
                if (foreground) handler.post(animate)
            }
        }
    }

    private fun interactive() = getSystemService(PowerManager::class.java).isInteractive
    private fun motionEnabled() = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    private fun dp(value: Int) = T.dp(this, value)
}
