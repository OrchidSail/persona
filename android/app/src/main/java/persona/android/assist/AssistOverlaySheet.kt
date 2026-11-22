package persona.android.assist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import persona.android.PersonaApp
import persona.android.R
import persona.core.contracts.api.DigitalAssistantModelCatalog
import persona.core.contracts.api.DigitalAssistantModelChoice
import persona.core.designsystem.AppFeedback
import persona.core.designsystem.AppStyle
import persona.core.designsystem.AttachmentState
import persona.core.designsystem.AttachmentThumb
import persona.core.designsystem.JButton
import persona.core.designsystem.JButtonSize
import persona.core.designsystem.JChip
import persona.core.designsystem.JDivider
import persona.core.designsystem.JIcon
import persona.core.designsystem.JIconButton
import persona.core.designsystem.JNotice
import persona.core.designsystem.JSpinner
import persona.core.designsystem.JTextButton
import persona.core.designsystem.LocalAppFeedback
import persona.core.designsystem.LocalAppStyleStore
import persona.core.designsystem.LocalPersonaDesign
import persona.core.designsystem.LocalPersonaName
import persona.core.designsystem.LocalPersonaPack
import persona.core.designsystem.PersonaTheme
import persona.core.designsystem.SharedMotion
import persona.core.designsystem.Status
import persona.core.designsystem.Tokens
import persona.core.designsystem.icons.PersonaIcons
import persona.feature.chat.StreamingReplyMarkdown

/** Wraps the Digital Assistant overlay in Persona's active theme, appearance, feedback, and safe link handler. */
@Composable
internal fun AssistOverlayEnvironment(
    onOpenExternalUri: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as? PersonaApp
    val currentStyle = app?.appearance?.style?.collectAsState()?.value ?: AppStyle.Default
    val face = app?.appearance?.face?.collectAsState()?.value
    val pack = app?.personas?.current?.collectAsState()?.value
    val openExternal by rememberUpdatedState(onOpenExternalUri)
    val failedMessage = stringResource(R.string.app_error_failed)
    val feedback = remember(context, failedMessage) {
        object : AppFeedback {
            override fun failure(error: Throwable, operation: Boolean): Boolean =
                notice(error.message ?: failedMessage)

            override fun notice(message: String): Boolean {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
                return true
            }
        }
    }
    val uriHandler = remember {
        object : UriHandler {
            override fun openUri(uri: String) {
                val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return
                val scheme = parsed.scheme?.lowercase()
                if ((scheme == "https" || scheme == "http") && !parsed.host.isNullOrBlank()) {
                    openExternal(uri)
                }
            }
        }
    }
    PersonaTheme(style = currentStyle) {
        val providers = buildList<androidx.compose.runtime.ProvidedValue<*>> {
            if (app != null) add(LocalAppStyleStore provides app.appearance)
            if (face != null) add(LocalPersonaDesign provides face)
            add(LocalPersonaPack provides pack)
            add(LocalPersonaName provides (pack?.manifest?.name ?: "Persona"))
            add(LocalAppFeedback provides feedback)
            add(LocalUriHandler provides uriHandler)
        }
        CompositionLocalProvider(*providers.toTypedArray(), content = content)
    }
}

/**
 * Muse / Gemini-inspired floating card shown over the foreground app when Persona is invoked
 * as Android's Default Digital Assistant (`ROLE_ASSISTANT`) or via `ACTION_ASSIST`.
 *
 * Visual & layout highlights:
 * - True independent floating card with generous four-corner rounding, horizontal breathing margins,
 *   and bottom lift above `WindowInsets.ime.union(WindowInsets.navigationBars)` (paired with
 *   `SOFT_INPUT_ADJUST_NOTHING` on the overlay window so IME never double-shifts the card).
 * - Layered vertical gradient depth scrim keeping the top of the underlying app crystal clear.
 * - Restrained AI luminous rim border (`palette.ai` -> `palette.accent`) that softly breathes
 *   while Persona is replying or streaming, strictly backed by `PersonaTheme` tokens & motion specs.
 */
@Composable
internal fun AssistOverlaySheet(
    ui: AssistOverlayUiState,
    onDraftChange: (String) -> Unit,
    onDictationHeard: (String) -> Unit,
    onNotice: (String) -> Unit,
    onAttachScreen: () -> Unit,
    onRemoveScreen: () -> Unit,
    onOpenAssistantSettings: () -> Unit = {},
    onToggleModelPicker: () -> Unit = {},
    onCloseModelPicker: () -> Unit = {},
    onRefreshModels: () -> Unit = {},
    onSelectDefaultModel: () -> Unit = {},
    onSelectModel: (DigitalAssistantModelChoice) -> Unit = {},
    onRetryWithDefaultMain: () -> Unit = {},
    onSend: () -> Unit,
    onOpenInPersona: () -> Unit,
    onDismiss: () -> Unit,
    onUnlock: () -> Unit = onOpenInPersona,
) {
    val palette = PersonaTheme.palette
    val shapes = PersonaTheme.shapes
    val reduceMotion = PersonaTheme.reduceMotion
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { Tokens.MinTouch.android.toPx() }
    val enterOffsetPx = with(density) { Tokens.Space.xl.toPx() }

    var entered by remember(ui.invocationId) { mutableStateOf(reduceMotion) }
    LaunchedEffect(ui.invocationId, reduceMotion) {
        entered = true
    }
    val enterProgress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = if (reduceMotion) SharedMotion.instant() else SharedMotion.reveal(),
        label = "assist-overlay-enter",
    )

    var dragging by remember { mutableStateOf(false) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val animatedDragOffsetPx by animateFloatAsState(
        targetValue = dragOffsetPx,
        animationSpec = if (dragging || reduceMotion) SharedMotion.instant() else SharedMotion.springBack(),
        label = "assist-overlay-drag",
    )

    val aiActive = !ui.deviceLocked && (ui.awaitingReply || ui.sending || ui.turns.any { it.streaming })
    val glowAlpha = if (aiActive && !reduceMotion) {
        val transition = rememberInfiniteTransition(label = "assist-ai-rim")
        transition.animateFloat(
            initialValue = 0.28f,
            targetValue = 0.62f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = PersonaTheme.motion.breathe, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "assist-ai-rim-alpha",
        ).value
    } else if (aiActive) {
        0.48f
    } else {
        if (palette.dark) 0.24f else 0.18f
    }

    val scrimInteraction = remember { MutableInteractionSource() }
    val latestOnCloseModelPicker by rememberUpdatedState(onCloseModelPicker)
    val closeDescription = stringResource(R.string.app_assist_close)
    val dictation = rememberOverlayDictation(onHeard = onDictationHeard, onError = onNotice)

    // Single unified inset layer: max(IME, navigation bar) when IME is open, navigation bar when closed.
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeOpen = imeBottomPx > 0
    val bottomInsetDp = with(density) {
        WindowInsets.ime.union(WindowInsets.navigationBars).getBottom(density).toDp()
    }
    val topInsetDp = with(density) {
        WindowInsets.statusBars.getTop(density).toDp()
    }
    val floatingBottomLift = if (imeOpen) Tokens.Space.s else Tokens.Space.m

    // Generous four-corner floating card radius (28.dp in Glass/Brief, panel radius in Board).
    val floatingCardRadius = if (PersonaTheme.style == AppStyle.Board) shapes.panel else Tokens.Size.wellXS
    val floatingCardShape = remember(floatingCardRadius) { RoundedCornerShape(floatingCardRadius) }

    val scrimBrush = remember(palette.scrim, palette.dark) {
        Brush.verticalGradient(
            0.0f to palette.scrim.copy(alpha = 0f),
            0.40f to palette.scrim.copy(alpha = 0.04f),
            0.72f to palette.scrim.copy(alpha = if (palette.dark) 0.18f else 0.14f),
            1.0f to palette.scrim.copy(alpha = if (palette.dark) 0.36f else 0.28f),
        )
    }
    val cardFill = palette.surfaceOpaque.copy(alpha = if (palette.dark) 0.96f else 0.98f)
    val surfaceSheen = remember(palette.strokeTop, palette.surfaceOpaque, palette.dark) {
        Brush.verticalGradient(
            0.0f to palette.strokeTop.copy(alpha = if (palette.dark) 0.14f else 0.34f),
            0.28f to palette.surfaceOpaque.copy(alpha = 0f),
        )
    }
    val rimBrush = remember(palette.ai, palette.accent, palette.strokeTop, palette.strokeBottom, glowAlpha, aiActive) {
        if (aiActive) {
            Brush.linearGradient(
                colors = listOf(
                    palette.ai.copy(alpha = glowAlpha),
                    palette.accent.copy(alpha = glowAlpha * 0.85f),
                    palette.strokeTop,
                    palette.ai.copy(alpha = glowAlpha * 0.55f),
                ),
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    palette.ai.copy(alpha = glowAlpha),
                    palette.accent.copy(alpha = glowAlpha * 0.72f),
                    palette.strokeTop,
                    palette.strokeBottom,
                ),
            )
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .testTag("assist-overlay-root"),
    ) {
        val availableHeight = (maxHeight - bottomInsetDp - topInsetDp - floatingBottomLift - Tokens.Space.m)
            .coerceAtLeast(Tokens.Size.box150)
        val sheetContentMaxHeight = availableHeight * 0.80f
        val pickerMaxHeight = availableHeight * 0.42f

        // Layered translucent vertical depth scrim: clear at top, gently grounded behind the floating card.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = enterProgress }
                .background(scrimBrush)
                .clickable(
                    interactionSource = scrimInteraction,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = closeDescription,
                    onClick = {
                        dictation?.stop()
                        onDismiss()
                    },
                )
                .semantics { contentDescription = closeDescription }
                .testTag("assist-overlay-scrim"),
        )

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(
                    start = Tokens.Space.m,
                    end = Tokens.Space.m,
                    bottom = bottomInsetDp + floatingBottomLift,
                )
                .graphicsLayer {
                    val scale = 0.96f + 0.04f * enterProgress
                    scaleX = scale
                    scaleY = scale
                    alpha = enterProgress
                    translationY = ((1f - enterProgress) * enterOffsetPx) + animatedDragOffsetPx
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = Tokens.Size.readable)
                    .shadow(
                        elevation = Tokens.Space.l,
                        shape = floatingCardShape,
                        ambientColor = palette.scrim,
                        spotColor = if (aiActive) palette.ai else palette.scrim,
                        clip = false,
                    )
                    .shadow(
                        elevation = Tokens.Space.xs,
                        shape = floatingCardShape,
                        ambientColor = palette.scrim,
                        spotColor = palette.scrim,
                        clip = false,
                    )
                    .clip(floatingCardShape)
                    .background(cardFill)
                    .background(surfaceSheen)
                    .border(
                        width = if (aiActive) Tokens.Size.line else Tokens.Size.hairline,
                        brush = rimBrush,
                        shape = floatingCardShape,
                    )
                    .pointerInput(ui.modelPickerOpen) {
                        detectTapGestures(
                            onTap = {
                                if (ui.modelPickerOpen) latestOnCloseModelPicker()
                            },
                        )
                    }
                    .testTag("assist-overlay-card"),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = sheetContentMaxHeight)
                        .animateContentSize(animationSpec = PersonaTheme.motion.size())
                        .padding(
                            start = Tokens.Space.l,
                            end = Tokens.Space.l,
                            top = Tokens.Space.xs,
                            bottom = Tokens.Space.m,
                        ),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Space.s),
                ) {
                    // Drag handle + refined compact single-row header.
                    AssistOverlayHeader(
                        ui = ui,
                        closeDescription = closeDescription,
                        dismissThresholdPx = dismissThresholdPx,
                        onDragStart = { dragging = true },
                        onDragOffset = { delta -> dragOffsetPx = (dragOffsetPx + delta).coerceAtLeast(0f) },
                        onDragRelease = {
                            dragging = false
                            if (dragOffsetPx >= dismissThresholdPx) {
                                dragOffsetPx = 0f
                                dictation?.stop()
                                onDismiss()
                            } else {
                                dragOffsetPx = 0f
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            dragOffsetPx = 0f
                        },
                        onToggleModelPicker = onToggleModelPicker,
                        onOpenInPersona = {
                            dictation?.stop()
                            onOpenInPersona()
                        },
                        onDismiss = {
                            dictation?.stop()
                            onDismiss()
                        },
                    )

                    if (ui.deviceLocked) {
                        AssistLockedCard(
                            onUnlock = {
                                dictation?.stop()
                                onUnlock()
                            },
                        )
                    } else {
                        if (ui.modelPickerOpen) {
                            AssistModelPickerPanel(
                                choice = ui.modelChoice,
                                catalog = ui.modelCatalog,
                                hubStatus = ui.hubStatus,
                                maxHeight = pickerMaxHeight,
                                onRefresh = onRefreshModels,
                                onSelectDefault = onSelectDefaultModel,
                                onSelectModel = onSelectModel,
                            )
                        }

                        // Hub unpaired / offline banner with direct handoff to Persona App.
                        when (ui.hubStatus) {
                            AssistHubStatus.Unpaired -> JNotice(
                                text = stringResource(R.string.app_assist_hub_unpaired_body),
                                title = stringResource(R.string.app_assist_hub_unpaired_title),
                                status = Status.Offline,
                                icon = PersonaIcons.offline,
                                actionLabel = stringResource(R.string.app_open_persona),
                                onAction = onOpenInPersona,
                                modifier = Modifier.testTag("assist-hub-unpaired"),
                            )

                            AssistHubStatus.Offline -> JNotice(
                                text = stringResource(R.string.app_assist_hub_offline_body),
                                title = stringResource(R.string.app_assist_hub_offline_title),
                                status = Status.Offline,
                                icon = PersonaIcons.offline,
                                actionLabel = stringResource(R.string.app_open_persona),
                                onAction = onOpenInPersona,
                                modifier = Modifier.testTag("assist-hub-offline"),
                            )

                            else -> Unit
                        }

                        ui.errorMessage?.let { error ->
                            JNotice(
                                text = error,
                                status = Status.NeedsYou,
                                icon = PersonaIcons.error,
                                actionLabel = if (ui.modelFallbackAvailable) {
                                    stringResource(R.string.app_assist_model_fallback_main)
                                } else {
                                    null
                                },
                                onAction = if (ui.modelFallbackAvailable) onRetryWithDefaultMain else null,
                                modifier = Modifier.testTag("assist-error-notice"),
                            )
                        }

                        // Expanded live streaming reply viewport (adapts cleanly when IME opens).
                        if (ui.expanded) {
                            AssistStreamingTranscript(
                                turns = ui.turns,
                                awaitingReply = ui.awaitingReply,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .fillMaxWidth()
                                    .testTag("assist-transcript"),
                            )
                        }

                        // Manual screen attachment bar above the input composer (NEVER attached automatically).
                        AssistScreenAttachmentRow(
                            ui = ui,
                            onAttachScreen = onAttachScreen,
                            onRemoveScreen = onRemoveScreen,
                            onOpenAssistantSettings = onOpenAssistantSettings,
                        )

                        // Unified rounded composer bar with optional safe dictation and Send action.
                        AssistInputBar(
                            draftText = ui.draftText,
                            canSend = ui.canSend,
                            sending = ui.sending,
                            inputEnabled = !ui.sending && ui.hubStatus != AssistHubStatus.Unpaired,
                            dictating = dictation?.listening == true,
                            onDraftChange = onDraftChange,
                            onToggleDictation = dictation?.let { control -> { control.toggle() } },
                            onSend = {
                                dictation?.stop()
                                onSend()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistOverlayHeader(
    ui: AssistOverlayUiState,
    closeDescription: String,
    dismissThresholdPx: Float,
    onDragStart: () -> Unit,
    onDragOffset: (Float) -> Unit,
    onDragRelease: () -> Unit,
    onDragCancel: () -> Unit,
    onToggleModelPicker: () -> Unit,
    onOpenInPersona: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = PersonaTheme.palette
    val type = PersonaTheme.type
    val openInPersonaLabel = stringResource(R.string.app_assist_open_in_persona)
    val defaultShortLabel = stringResource(R.string.app_assist_model_default_short)
    val modelPillLabel = if (ui.modelChoice.isDefault) {
        defaultShortLabel
    } else {
        ui.modelChoice.compactLabel.ifBlank { defaultShortLabel }
    }
    val badgeBrush = remember(palette.ai, palette.accentSoft) {
        Brush.linearGradient(listOf(palette.ai.copy(alpha = 0.22f), palette.accentSoft))
    }

    Column(
        Modifier
            .fillMaxWidth()
            .pointerInput(dismissThresholdPx) {
                detectVerticalDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = onDragRelease,
                    onDragCancel = onDragCancel,
                    onVerticalDrag = { _, dragAmount -> onDragOffset(dragAmount) },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Restrained centered drag handle pill.
        Box(
            Modifier
                .padding(top = Tokens.Space.xxs, bottom = Tokens.Space.xs)
                .size(width = Tokens.Size.slimBar, height = Tokens.Size.bar)
                .clip(CircleShape)
                .background(palette.ink3.copy(alpha = 0.26f)),
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
        ) {
            // Luminous brand mark badge.
            Box(
                Modifier
                    .size(Tokens.Size.wellXS)
                    .clip(CircleShape)
                    .background(badgeBrush)
                    .border(Tokens.Size.hairline, palette.ai.copy(alpha = 0.28f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                JIcon(
                    icon = PersonaIcons.persona,
                    contentDescription = null,
                    size = Tokens.Size.markS,
                    tint = palette.accent,
                )
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
                ) {
                    Text(
                        text = LocalPersonaName.current,
                        style = type.label.copy(fontWeight = FontWeight.SemiBold),
                        color = palette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!ui.deviceLocked) {
                        AssistCompactModelPill(
                            label = modelPillLabel,
                            open = ui.modelPickerOpen,
                            enabled = !ui.sending,
                            isCustom = !ui.modelChoice.isDefault,
                            onClick = onToggleModelPicker,
                        )
                    }
                }
                if (!ui.deviceLocked && ui.hubStatus == AssistHubStatus.Connecting) {
                    Text(
                        text = stringResource(R.string.app_assist_connecting),
                        style = type.micro,
                        color = palette.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!ui.deviceLocked) {
                JIconButton(
                    icon = PersonaIcons.openExternal,
                    contentDescription = openInPersonaLabel,
                    onClick = onOpenInPersona,
                    tint = palette.ink2,
                    modifier = Modifier.testTag("assist-open-in-persona"),
                )
            }
            JIconButton(
                icon = PersonaIcons.close,
                contentDescription = closeDescription,
                onClick = onDismiss,
                tint = palette.ink2,
                modifier = Modifier.testTag("assist-close-button"),
            )
        }
    }
}

@Composable
private fun AssistCompactModelPill(
    label: String,
    open: Boolean,
    enabled: Boolean,
    isCustom: Boolean,
    onClick: () -> Unit,
) {
    val palette = PersonaTheme.palette
    val type = PersonaTheme.type
    val description = stringResource(R.string.app_assist_model_picker_description, label)
    val fill = when {
        open || isCustom -> palette.accentSoft
        else -> palette.surfaceStrong.copy(alpha = if (palette.dark) 0.55f else 0.72f)
    }
    val stroke = if (open || isCustom) palette.accent.copy(alpha = 0.55f) else palette.line
    val contentTint = if (open || isCustom) palette.accent else palette.ink2

    Row(
        Modifier
            .heightIn(min = Tokens.Size.wellXS)
            .clip(CircleShape)
            .background(fill)
            .border(Tokens.Size.hairline, stroke, CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = description,
                onClick = onClick,
            )
            .semantics { contentDescription = description }
            .padding(horizontal = Tokens.Space.s, vertical = Tokens.Space.xs)
            .testTag("assist-model-pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
    ) {
        JIcon(
            icon = PersonaIcons.model,
            contentDescription = null,
            size = Tokens.Size.markXS,
            tint = contentTint,
        )
        Text(
            text = label,
            style = type.micro.copy(fontWeight = FontWeight.Medium),
            color = contentTint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = Tokens.Size.box150),
        )
        JIcon(
            icon = if (open) PersonaIcons.collapse else PersonaIcons.expand,
            contentDescription = null,
            size = Tokens.Size.markXS,
            tint = contentTint,
        )
    }
}

@Composable
private fun AssistModelPickerPanel(
    choice: DigitalAssistantModelChoice,
    catalog: DigitalAssistantModelCatalog,
    hubStatus: AssistHubStatus,
    maxHeight: Dp,
    onRefresh: () -> Unit,
    onSelectDefault: () -> Unit,
    onSelectModel: (DigitalAssistantModelChoice) -> Unit,
) {
    val palette = PersonaTheme.palette
    val type = PersonaTheme.type
    val shapes = PersonaTheme.shapes
    val selectedDescription = stringResource(R.string.app_assist_model_selected)

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .clip(shapes.cardShape)
            .background(palette.sunken.copy(alpha = if (palette.dark) 0.90f else 0.78f))
            .border(Tokens.Size.hairline, palette.line, shapes.cardShape)
            .padding(vertical = Tokens.Space.s)
            .testTag("assist-model-picker"),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Tokens.Space.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.app_assist_model_picker_title),
                style = type.caption.copy(fontWeight = FontWeight.SemiBold),
                color = palette.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hubStatus == AssistHubStatus.Ready && !catalog.loading) {
                JIconButton(
                    icon = PersonaIcons.retry,
                    contentDescription = stringResource(R.string.app_refresh),
                    onClick = onRefresh,
                    tint = palette.ink2,
                    modifier = Modifier.testTag("assist-model-refresh"),
                )
            }
        }
        JDivider()
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Tokens.Space.s, vertical = Tokens.Space.xs),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs),
        ) {
            item(key = "assist-model-default") {
                AssistModelPickerRow(
                    title = stringResource(R.string.app_assist_model_default_title),
                    subtitle = stringResource(R.string.app_assist_model_default_subtitle),
                    selected = choice.isDefault,
                    selectedDescription = selectedDescription,
                    onClick = onSelectDefault,
                    modifier = Modifier.testTag("assist-model-option-default"),
                )
            }
            when {
                catalog.loading -> {
                    item(key = "assist-model-loading") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Tokens.Space.s, vertical = Tokens.Space.s),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
                        ) {
                            JSpinner(Modifier.size(Tokens.Size.markS), color = palette.accent)
                            Text(
                                text = stringResource(R.string.app_assist_model_loading),
                                style = type.caption,
                                color = palette.ink3,
                            )
                        }
                    }
                }

                catalog.error != null -> {
                    item(key = "assist-model-error") {
                        Text(
                            text = catalog.error.orEmpty(),
                            style = type.caption,
                            color = palette.ink3,
                            modifier = Modifier.padding(horizontal = Tokens.Space.s, vertical = Tokens.Space.s),
                        )
                    }
                }

                catalog.groups.isEmpty() -> {
                    item(key = "assist-model-empty") {
                        Text(
                            text = stringResource(R.string.app_assist_model_empty),
                            style = type.caption,
                            color = palette.ink3,
                            modifier = Modifier.padding(horizontal = Tokens.Space.s, vertical = Tokens.Space.s),
                        )
                    }
                }

                else -> {
                    catalog.groups.forEach { group ->
                        item(key = "assist-model-group-${group.launcher}") {
                            Text(
                                text = group.title,
                                style = type.micro.copy(fontWeight = FontWeight.SemiBold),
                                color = palette.ink3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(
                                    start = Tokens.Space.s,
                                    end = Tokens.Space.s,
                                    top = Tokens.Space.s,
                                    bottom = Tokens.Space.xxs,
                                ),
                            )
                        }
                        items(
                            items = group.options,
                            key = { "assist-model-opt-${it.pick.launcher}-${it.pick.model.orEmpty()}" },
                        ) { option ->
                            AssistModelPickerRow(
                                title = option.modelLabel.ifBlank { group.title },
                                subtitle = option.description,
                                selected = choice.pick == option.pick,
                                selectedDescription = selectedDescription,
                                onClick = { onSelectModel(option.toChoice()) },
                                modifier = Modifier.testTag(
                                    "assist-model-option-${option.pick.launcher}-${option.pick.model.orEmpty()}",
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistModelPickerRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    selectedDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = PersonaTheme.palette
    val type = PersonaTheme.type
    val shapes = PersonaTheme.shapes
    Row(
        modifier
            .fillMaxWidth()
            .clip(shapes.controlShape)
            .background(if (selected) palette.accentSoft else palette.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Tokens.Space.m, vertical = Tokens.Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs),
        ) {
            Text(
                text = title,
                style = type.labelSmall.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = if (selected) palette.accent else palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = type.micro,
                    color = palette.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            JIcon(
                icon = PersonaIcons.done,
                contentDescription = selectedDescription,
                size = Tokens.Size.markS,
                tint = palette.accent,
            )
        }
    }
}

@Composable
private fun AssistLockedCard(onUnlock: () -> Unit) {
    val palette = PersonaTheme.palette
    val type = PersonaTheme.type
    val shapes = PersonaTheme.shapes
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shapes.cardShape)
            .background(palette.sunken)
            .border(Tokens.Size.hairline, palette.line, shapes.cardShape)
            .padding(Tokens.Space.l)
            .testTag("assist-locked-card"),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.m),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
        ) {
            JIcon(
                icon = PersonaIcons.locked,
                contentDescription = null,
                size = Tokens.Size.iconXS,
                tint = palette.accent,
            )
            Text(
                text = stringResource(R.string.app_assist_locked_title),
                style = type.label.copy(fontWeight = FontWeight.SemiBold),
                color = palette.ink,
            )
        }
        Text(
            text = stringResource(R.string.app_assist_locked_body),
            style = type.caption,
            color = palette.ink2,
        )
        JButton(
            text = stringResource(R.string.app_assist_unlock_action),
            onClick = onUnlock,
            size = JButtonSize.Small,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("assist-unlock-button"),
        )
    }
}

@Composable
private fun AssistStreamingTranscript(
    turns: List<AssistOverlayTurn>,
    awaitingReply: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = PersonaTheme.palette
    val type = PersonaTheme.type
    val listState = rememberLazyListState()
    var followBottom by remember { mutableStateOf(true) }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = info.totalItemsCount
            total == 0 || lastVisible >= total - 2
        }.collect { nearBottom ->
            followBottom = nearBottom
        }
    }

    val lastTurnTextLen = turns.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(turns.size, lastTurnTextLen, awaitingReply) {
        if (followBottom && (turns.isNotEmpty() || awaitingReply)) {
            val target = (turns.size + if (awaitingReply) 1 else 0) - 1
            if (target >= 0) {
                listState.scrollToItem(target)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = Tokens.Space.xs),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.m),
    ) {
        items(turns, key = { it.id }) { turn ->
            if (turn.fromUser) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
                ) {
                    Column(
                        Modifier
                            .clip(PersonaTheme.shapes.cardShape)
                            .background(palette.userBubble)
                            .padding(horizontal = Tokens.Space.m, vertical = Tokens.Space.s),
                        verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
                    ) {
                        if (turn.hasScreenAttachment) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
                            ) {
                                JIcon(
                                    icon = PersonaIcons.screen,
                                    contentDescription = null,
                                    size = Tokens.Size.markS,
                                    tint = palette.userBubbleText ?: palette.ink2,
                                )
                                Text(
                                    text = stringResource(R.string.app_assist_screen_attached),
                                    style = type.micro,
                                    color = palette.userBubbleText ?: palette.ink2,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (turn.text.isNotBlank()) {
                            Text(
                                text = turn.text,
                                style = type.body,
                                color = palette.userBubbleText ?: palette.ink,
                            )
                        }
                    }
                    val deliveryLabel = when (turn.delivery) {
                        AssistTurnDelivery.Sending -> stringResource(R.string.app_assist_delivery_sending)
                        AssistTurnDelivery.Queued -> stringResource(R.string.app_assist_delivery_queued)
                        AssistTurnDelivery.Failed -> stringResource(R.string.app_assist_delivery_failed)
                        AssistTurnDelivery.Sent -> null
                    }
                    if (deliveryLabel != null) {
                        Text(
                            text = deliveryLabel,
                            style = type.micro,
                            color = if (turn.delivery == AssistTurnDelivery.Failed) palette.danger.text else palette.ink3,
                            maxLines = 1,
                        )
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = Tokens.Space.xxs),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
                ) {
                    StreamingReplyMarkdown(
                        text = turn.text,
                        id = turn.id,
                        streaming = turn.streaming,
                        bodyStyle = type.body,
                        color = palette.ink,
                        selectable = false,
                    )
                }
            }
        }
        if (awaitingReply) {
            item(key = "assist-awaiting-reply") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = Tokens.Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
                ) {
                    JSpinner(Modifier.size(Tokens.Size.markS), color = palette.accent)
                    Text(
                        text = stringResource(R.string.app_assist_replying),
                        style = type.caption,
                        color = palette.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistScreenAttachmentRow(
    ui: AssistOverlayUiState,
    onAttachScreen: () -> Unit,
    onRemoveScreen: () -> Unit,
    onOpenAssistantSettings: () -> Unit,
) {
    val palette = PersonaTheme.palette
    val type = PersonaTheme.type
    val shapes = PersonaTheme.shapes
    when (ui.captureStatus) {
        AssistCaptureStatus.Waiting -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Tokens.Space.xs)
                    .testTag("assist-screen-waiting"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
            ) {
                JSpinner(Modifier.size(Tokens.Size.markXS), color = palette.ink3)
                Text(
                    text = stringResource(R.string.app_assist_screen_preparing),
                    style = type.micro,
                    color = palette.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        AssistCaptureStatus.Available -> {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
            ) {
                JChip(
                    text = stringResource(R.string.app_assist_attach_screen),
                    icon = PersonaIcons.screen,
                    accent = true,
                    role = Role.Button,
                    onClick = if (ui.attachingScreen || ui.sending) null else onAttachScreen,
                    modifier = Modifier.testTag("assist-attach-screen-chip"),
                )
                if (ui.attachingScreen) {
                    JSpinner(Modifier.size(Tokens.Size.markS), color = palette.accent)
                }
            }
        }

        AssistCaptureStatus.Attached -> {
            val thumb = remember(ui.attachedThumbnail) {
                ui.attachedThumbnail?.takeUnless { it.isRecycled }?.asImageBitmap()
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shapes.controlShape)
                    .background(palette.sunken.copy(alpha = if (palette.dark) 0.88f else 0.72f))
                    .border(Tokens.Size.hairline, palette.line, shapes.controlShape)
                    .padding(Tokens.Space.s)
                    .testTag("assist-screen-attached-row"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.m),
            ) {
                AttachmentThumb(
                    image = thumb,
                    contentDescription = stringResource(R.string.app_assist_screen_preview),
                    state = if (ui.sending) AttachmentState.Uploading else AttachmentState.Ready,
                    onRemove = if (ui.sending) null else onRemoveScreen,
                )
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs),
                ) {
                    Text(
                        text = stringResource(R.string.app_assist_screen_attached),
                        style = type.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = palette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ui.attachedContext?.title?.takeIf(String::isNotBlank)?.let { title ->
                        Text(
                            text = title,
                            style = type.micro,
                            color = palette.ink2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        AssistCaptureStatus.Unavailable -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Tokens.Space.xs)
                    .testTag("assist-screen-unavailable"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
            ) {
                JIcon(
                    icon = PersonaIcons.privacy,
                    contentDescription = null,
                    size = Tokens.Size.markXS,
                    tint = palette.ink3,
                )
                Text(
                    text = stringResource(R.string.app_assist_screen_unavailable),
                    style = type.micro,
                    color = palette.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        AssistCaptureStatus.TimedOut -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Tokens.Space.xs)
                    .testTag("assist-screen-timeout"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
            ) {
                JIcon(
                    icon = PersonaIcons.time,
                    contentDescription = null,
                    size = Tokens.Size.markXS,
                    tint = palette.ink3,
                )
                Text(
                    text = stringResource(R.string.app_assist_screen_timeout),
                    style = type.micro,
                    color = palette.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        AssistCaptureStatus.DisabledBySettings -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shapes.controlShape)
                    .background(palette.sunken.copy(alpha = if (palette.dark) 0.85f else 0.72f))
                    .border(Tokens.Size.hairline, palette.line, shapes.controlShape)
                    .padding(start = Tokens.Space.m, end = Tokens.Space.xs, top = Tokens.Space.xs, bottom = Tokens.Space.xs)
                    .testTag("assist-screen-disabled"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
            ) {
                JIcon(
                    icon = PersonaIcons.settings,
                    contentDescription = null,
                    size = Tokens.Size.markS,
                    tint = palette.ink2,
                )
                Text(
                    text = stringResource(R.string.app_assist_screen_disabled_by_settings),
                    style = type.micro,
                    color = palette.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                JTextButton(
                    text = stringResource(R.string.app_assist_screen_open_settings),
                    onClick = onOpenAssistantSettings,
                    modifier = Modifier.testTag("assist-open-settings-button"),
                )
            }
        }

        AssistCaptureStatus.NotSupported -> Unit
    }
}

@Composable
private fun AssistInputBar(
    draftText: String,
    canSend: Boolean,
    sending: Boolean,
    inputEnabled: Boolean,
    dictating: Boolean,
    onDraftChange: (String) -> Unit,
    onToggleDictation: (() -> Unit)?,
    onSend: () -> Unit,
) {
    val palette = PersonaTheme.palette
    val type = PersonaTheme.type
    val shapes = PersonaTheme.shapes
    var focused by remember { mutableStateOf(false) }
    val placeholder = stringResource(
        if (dictating) R.string.app_assist_dictate_listening else R.string.app_assist_input_placeholder,
    )

    Row(
        Modifier
            .fillMaxWidth()
            .clip(shapes.panelShape)
            .background(palette.sunken.copy(alpha = if (palette.dark) 0.88f else 0.72f))
            .border(
                width = if (focused) Tokens.Size.line else Tokens.Size.hairline,
                color = if (focused) palette.accent.copy(alpha = 0.78f) else palette.line,
                shape = shapes.panelShape,
            )
            .padding(start = Tokens.Space.l, end = Tokens.Space.xs, top = Tokens.Space.xs, bottom = Tokens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
    ) {
        Box(
            Modifier
                .weight(1f)
                .padding(vertical = Tokens.Space.s),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (draftText.isEmpty()) {
                Text(
                    text = placeholder,
                    style = type.body,
                    color = palette.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = draftText,
                onValueChange = onDraftChange,
                enabled = inputEnabled,
                textStyle = type.body.copy(color = palette.ink),
                cursorBrush = SolidColor(palette.accent),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    .testTag("assist-input-field"),
            )
        }
        if (onToggleDictation != null) {
            JIconButton(
                icon = if (dictating) PersonaIcons.dictating else PersonaIcons.dictate,
                contentDescription = stringResource(
                    if (dictating) R.string.app_assist_dictate_stop else R.string.app_assist_dictate,
                ),
                onClick = onToggleDictation,
                enabled = inputEnabled,
                selected = dictating,
                tint = if (dictating) palette.accent else palette.ink2,
                modifier = Modifier.testTag("assist-dictate-button"),
            )
        }
        Box(
            Modifier
                .size(Tokens.MinTouch.android)
                .clickable(
                    enabled = canSend && !sending,
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.app_send),
                    onClick = onSend,
                )
                .padding(Tokens.Space.xs)
                .testTag("assist-send-button"),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(if (canSend) palette.action else palette.surfaceStrong),
                contentAlignment = Alignment.Center,
            ) {
                if (sending) {
                    JSpinner(Modifier.size(Tokens.Size.markS), color = palette.ink2)
                } else {
                    JIcon(
                        icon = PersonaIcons.send,
                        contentDescription = stringResource(R.string.app_send),
                        size = Tokens.Size.iconXS,
                        tint = if (canSend) palette.onAction else palette.ink3,
                    )
                }
            }
        }
    }
}

/**
 * Overlay-safe voice dictation helper that never requires `LocalActivityResultRegistryOwner`
 * (which is unavailable inside a `VoiceInteractionSession`). Activates only when `RECORD_AUDIO`
 * is already granted and platform speech recognition is available.
 */
private class OverlayDictationHandle(
    private val context: Context,
    private val onDevice: Boolean,
    private val intent: Intent,
    private val onHeard: () -> (String) -> Unit,
    private val onError: () -> (String) -> Unit,
    private val stoppedMessage: String,
) {
    var listening by mutableStateOf(false)
        private set

    private var recognizer: SpeechRecognizer? = null

    fun toggle() {
        if (listening) stop() else start()
    }

    private fun start() {
        stop()
        val speech = runCatching {
            if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrNull() ?: return
        recognizer = speech
        listening = true
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                stop()
                if (words.isNotEmpty()) onHeard().invoke(words)
            }

            override fun onError(error: Int) {
                stop()
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    onError().invoke(stoppedMessage)
                }
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        runCatching { speech.startListening(intent) }.onFailure {
            stop()
            onError().invoke(stoppedMessage)
        }
    }

    fun stop() {
        listening = false
        val current = recognizer
        recognizer = null
        if (current != null) {
            runCatching { current.stopListening() }
            runCatching { current.destroy() }
        }
    }
}

@Composable
private fun rememberOverlayDictation(
    onHeard: (String) -> Unit,
    onError: (String) -> Unit,
): OverlayDictationHandle? {
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestHeard by rememberUpdatedState(onHeard)
    val latestError by rememberUpdatedState(onError)
    val stoppedMessage = stringResource(R.string.app_assist_dictate_stopped)

    val granted = remember(context) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    if (!granted) return null

    val onDevice = remember(context) {
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }
    val available = remember(context, onDevice) {
        onDevice || SpeechRecognizer.isRecognitionAvailable(context)
    }
    if (!available) return null

    val language = remember(resources) { resources.configuration.locales[0]?.toLanguageTag() }
    val intent = remember(context.packageName, language) {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        }
    }
    val handle = remember(context, onDevice, intent, stoppedMessage) {
        OverlayDictationHandle(
            context = context,
            onDevice = onDevice,
            intent = intent,
            onHeard = { latestHeard },
            onError = { latestError },
            stoppedMessage = stoppedMessage,
        )
    }

    DisposableEffect(lifecycle, handle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) handle.stop()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            handle.stop()
        }
    }
    return handle
}
