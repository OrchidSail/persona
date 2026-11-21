package persona.android.assist

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import persona.android.MainActivity
import persona.android.PersonaApp
import persona.android.R
import persona.core.navigation.AskPersonaLink
import persona.feature.chat.AssistDraftBus
import persona.feature.chat.ChatLinkContext

/**
 * Receives system assist context and screenshot callbacks, persists any screenshot strictly inside
 * private cache, prefills Persona's chat composer draft via [AssistDraftBus], opens [MainActivity]
 * with the composer focused (never auto-sending), and promptly finishes the session.
 */
class PersonaVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appScope: CoroutineScope
        get() = (context.applicationContext as? PersonaApp)?.scope ?: mainScope

    private var coordinator: AssistSessionCoordinator? = null
    private var currentHandoffId: String = ""

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        // Persona opens its own MainActivity composer rather than rendering an overlay window.
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startCoordinator(showFlags)
    }

    override fun onHandleAssist(state: AssistState) {
        val active = ensureCoordinator(SHOW_WITH_ASSIST)
        val contextCard = buildContextCard(state.assistData, state.assistStructure, state.assistContent)
        active.onAssist(contextCard, isFocused = state.isFocused)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        val active = ensureCoordinator(SHOW_WITH_ASSIST)
        val contextCard = buildContextCard(data, structure, content)
        active.onAssist(contextCard, isFocused = true)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        val active = ensureCoordinator(SHOW_WITH_SCREENSHOT)
        active.onScreenshot(screenshot)
    }

    override fun onHide() {
        coordinator?.onSessionClosed()
        super.onHide()
    }

    override fun onDestroy() {
        coordinator?.onSessionClosed()
        coordinator = null
        mainScope.cancel()
        super.onDestroy()
    }

    private fun ensureCoordinator(fallbackFlags: Int): AssistSessionCoordinator =
        coordinator ?: startCoordinator(fallbackFlags)

    private fun startCoordinator(showFlags: Int): AssistSessionCoordinator {
        coordinator?.onSessionClosed()
        val handoffId = UUID.randomUUID().toString()
        currentHandoffId = handoffId
        val appContext = context.applicationContext
        val created = AssistSessionCoordinator(
            mainScope = mainScope,
            ioScope = appScope,
            expectAssist = (showFlags and SHOW_WITH_ASSIST) != 0,
            expectScreenshot = (showFlags and SHOW_WITH_SCREENSHOT) != 0,
            handoffId = handoffId,
            saveScreenshot = { bitmap ->
                withContext(Dispatchers.IO) {
                    AssistScreenshotStore.save(appContext, bitmap, handoffId)
                }
            },
            publishDraft = { id, screenshot, ctx ->
                AssistDraftBus.publish(id = id, screenshot = screenshot, context = ctx)
            },
            attachLateScreenshot = { id, screenshot ->
                AssistDraftBus.attachScreenshot(id = id, attachment = screenshot)
            },
            attachLateContext = { id, ctx ->
                AssistDraftBus.attachContext(id = id, context = ctx)
            },
            launchPersona = { launchMainComposer() },
            finishSession = { runCatching { finish() } },
        )
        coordinator = created
        created.start()
        return created
    }

    private fun buildContextCard(
        data: Bundle?,
        structure: AssistStructure?,
        content: AssistContent?,
    ): ChatLinkContext? {
        val snapshot = AssistContextExtractor.snapshotFromAssist(data, structure, content)
        val safe = AssistContextExtractor.extract(snapshot, context.packageName)
        return AssistContextExtractor.toLinkContext(
            safe = safe,
            id = currentHandoffId.ifEmpty { UUID.randomUUID().toString() },
            fallbackTitle = context.getString(R.string.app_assist_context_title),
        )
    }

    private fun launchMainComposer() {
        val intent = assistMainIntent(context)
        val launched = runCatching {
            startAssistantActivity(intent)
            true
        }.getOrDefault(false)
        if (!launched) {
            runCatching { context.startActivity(intent) }
        }
    }
}

/** Builds the launch intent that opens Persona's main chat and focuses its composer without sending. */
internal fun assistMainIntent(context: Context): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(AskPersonaLink), context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
