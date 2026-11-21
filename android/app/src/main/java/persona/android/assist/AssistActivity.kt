package persona.android.assist

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import java.util.UUID
import persona.android.R
import persona.core.designsystem.AppLanguages
import persona.feature.chat.AssistDraftBus

/**
 * Lightweight translucent fallback for `android.intent.action.ASSIST` triggers (launchers or OEM
 * fallback paths that invoke an Assist activity instead of a `VoiceInteractionSession`).
 */
class AssistActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguages.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val handoffId = UUID.randomUUID().toString()
        val snapshot = AssistContextExtractor.snapshotFromIntent(intent)
        val safe = AssistContextExtractor.extract(snapshot, packageName)
        val contextCard = AssistContextExtractor.toLinkContext(
            safe = safe,
            id = handoffId,
            fallbackTitle = getString(R.string.app_assist_context_title),
        )
        if (contextCard != null) {
            AssistDraftBus.publish(id = handoffId, screenshot = null, context = contextCard)
        }
        runCatching { startActivity(assistMainIntent(this)) }
        finish()
    }
}
