package persona.platform.phonetools

import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import persona.core.designsystem.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import persona.core.designsystem.dense
import persona.core.navigation.Navigator
import persona.platform.phonetools.control.PhoneControlAccessibilityService
import persona.platform.phonetools.control.PhoneControlNotificationListener
import persona.platform.phonetools.control.PhoneControlPermissionsChecker
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable @SerialName("persona.platform.phonetools.PhoneToolsRoute") data object PhoneToolsRoute : NavKey

@Composable
fun PhoneToolsScreen(runtime: PhoneToolsRuntime, navigator: Navigator) {
    val enabled by runtime.settings.enabled.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        runtime.refreshPermissions(); revision++
    }
    var healthGranted by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(revision) {
        healthGranted = Health.granted(context)
    }
    val healthLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        runtime.refreshPermissions(); revision++
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, runtime) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { runtime.refreshPermissions(); revision++ }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    JListPage(title = stringResource(R.string.phonetools_title), onBack = navigator::back) {
        item {
            Text(stringResource(R.string.phonetools_intro),
                Modifier.padding(bottom = 16.dp.dense), style = PersonaTheme.type.callout, color = PersonaTheme.palette.ink2)
        }
        item(key = "remote_phone_control") {
            PhoneControlSection(
                runtime = runtime,
                revision = revision,
                onError = { error = it },
                onClearError = { error = null },
            )
        }
        items(PhoneSource.entries, key = { it.name }) { source ->
            val status = remember(revision, source, healthGranted) {
                if (source == PhoneSource.Health) runtime.permissions.status(source, healthGranted)
                else runtime.permissions.status(source)
            }
            val subtitle = when {
                source == PhoneSource.Health && !Health.available(context) ->
                    stringResource(R.string.phonetools_health_install_hint, Health.unavailableReason(context).orEmpty())
                source == PhoneSource.Health && source in enabled && healthGranted != null && healthGranted!!.isEmpty() ->
                    stringResource(R.string.phonetools_health_none_allowed)
                source == PhoneSource.Health && source in enabled && healthGranted != null && !healthGranted!!.containsAll(Health.permissions) ->
                    stringResource(R.string.phonetools_health_some_allowed)
                else -> stringResource(source.detail)
            }
            val toggleFailed = stringResource(R.string.phonetools_error_toggle)
            val storeFailed = stringResource(R.string.phonetools_error_store)
            val settingsFailed = stringResource(R.string.phonetools_error_settings)
            val systemSettingsFailed = stringResource(R.string.phonetools_error_system_settings)
            JGroupItem(GroupPosition.Single, Modifier.padding(bottom = 12.dp.dense)) {
                JSwitchRow(stringResource(source.title), checked = source in enabled, subtitle = subtitle, onCheckedChange = { value ->
                    scope.launch {
                        try { runtime.settings.setEnabled(source, value); error = null }
                        catch (_: Exception) { error = toggleFailed }
                    }
                })
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp.dense).padding(bottom = 16.dp.dense), verticalArrangement = Arrangement.spacedBy(8.dp.dense)) {
                    JTag(runtime.permissions.statusText(source, status))
                    if (source == PhoneSource.Health) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp.dense)) {
                            if (!Health.available(context)) {
                                JButton(stringResource(R.string.phonetools_get), {
                                    try { context.startActivity(Health.installIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                    catch (_: Exception) { error = storeFailed }
                                }, kind = JButtonKind.Secondary, size = JButtonSize.Small)
                            } else {
                                JButton(stringResource(if (status == PhoneAccess.Partial) R.string.phonetools_choose_again else R.string.phonetools_allow_access), {
                                    healthLauncher.launch(Health.permissions)
                                }, kind = JButtonKind.Secondary, size = JButtonSize.Small)
                                JButton(stringResource(R.string.phonetools_health_settings), {
                                    try { context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
                                    catch (_: Exception) {
                                        try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                                        catch (_: Exception) { error = settingsFailed }
                                    }
                                }, kind = JButtonKind.Secondary, size = JButtonSize.Small)
                            }
                        }
                    } else if (runtime.permissions.permissions(source).isNotEmpty() || source == PhoneSource.Notifications) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp.dense)) {
                            JButton(stringResource(if (status == PhoneAccess.Partial) R.string.phonetools_choose_again else R.string.phonetools_allow_access), {
                                val permissions = runtime.permissions.permissions(source)
                                if (permissions.isNotEmpty()) launcher.launch(permissions)
                            }, kind = JButtonKind.Secondary, size = JButtonSize.Small,
                                enabled = runtime.permissions.permissions(source).isNotEmpty())
                            JButton(stringResource(R.string.phonetools_system_settings), {
                                try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                                catch (_: Exception) { error = systemSettingsFailed }
                            }, kind = JButtonKind.Secondary, size = JButtonSize.Small)
                        }
                    }
                }
            }
        }
        item {
            Text(stringResource(R.string.phonetools_footnote),
                style = PersonaTheme.type.caption, color = PersonaTheme.palette.ink3)
        }
        error?.let { item { JNotice(it, Modifier.padding(top = 12.dp.dense), status = Status.NeedsYou) } }
    }
}

@Composable
private fun PhoneControlSection(
    runtime: PhoneToolsRuntime,
    revision: Int,
    onError: (String) -> Unit,
    onClearError: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val remoteControlEnabled by runtime.settings.remoteControlEnabled.collectAsStateWithLifecycle()
    val allowOtp by runtime.settings.allowNotificationOtpAutoLogin.collectAsStateWithLifecycle()
    val blockPayment by runtime.settings.blockRealPayment.collectAsStateWithLifecycle()
    val a11yService by PhoneControlAccessibilityService.instance.collectAsStateWithLifecycle()
    val notifConnected by PhoneControlNotificationListener.isConnected.collectAsStateWithLifecycle()
    val snapshot = remember(revision, remoteControlEnabled, allowOtp, blockPayment, a11yService, notifConnected) {
        runtime.control.currentSnapshot()
    }
    val toggleFailed = stringResource(R.string.phonetools_error_toggle)
    val settingsFailed = stringResource(R.string.phonetools_error_settings)
    val statusText: String
    val statusTone: Status?
    when {
        !remoteControlEnabled -> {
            statusText = stringResource(R.string.phonetools_control_status_off)
            statusTone = null
        }
        !snapshot.permissions.accessibility -> {
            statusText = stringResource(R.string.phonetools_control_status_needs_permission)
            statusTone = Status.NeedsYou
        }
        !snapshot.residentConnection && !snapshot.permissions.batteryOptimizationIgnored -> {
            statusText = stringResource(R.string.phonetools_control_status_foreground_only)
            statusTone = Status.Working
        }
        else -> {
            statusText = stringResource(R.string.phonetools_control_status_ready)
            statusTone = Status.Unread
        }
    }
    Column(Modifier.fillMaxWidth()) {
        JSectionHeader(stringResource(R.string.phonetools_control_section_title), sub = true)
        JGroupItem(GroupPosition.Single, Modifier.padding(bottom = Tokens.Space.m)) {
            JSwitchRow(
                title = stringResource(R.string.phonetools_control_title),
                checked = remoteControlEnabled,
                subtitle = stringResource(R.string.phonetools_control_subtitle),
                subtitleLines = Int.MAX_VALUE,
                titleLines = 2,
                onCheckedChange = { value ->
                    scope.launch {
                        try {
                            runtime.settings.setRemoteControlEnabled(value)
                            onClearError()
                        } catch (_: Exception) {
                            onError(toggleFailed)
                        }
                    }
                },
            )
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Tokens.Space.l).padding(bottom = Tokens.Space.l),
                verticalArrangement = Arrangement.spacedBy(Tokens.Space.s),
            ) {
                JTag(statusText, status = statusTone)
                if (remoteControlEnabled) {
                    JDivider()
                    ControlPermissionRow(
                        title = stringResource(R.string.phonetools_control_perm_a11y_title),
                        detail = stringResource(R.string.phonetools_control_perm_a11y_detail),
                        granted = snapshot.permissions.accessibility,
                        onOpenSettings = {
                            try {
                                PhoneControlPermissionsChecker.openAccessibilitySettings(context)
                                onClearError()
                            } catch (_: Exception) {
                                onError(settingsFailed)
                            }
                        },
                    )
                    JDivider()
                    ControlPermissionRow(
                        title = stringResource(R.string.phonetools_control_perm_notif_title),
                        detail = stringResource(R.string.phonetools_control_perm_notif_detail),
                        granted = snapshot.permissions.notificationListener,
                        onOpenSettings = {
                            try {
                                PhoneControlPermissionsChecker.openNotificationListenerSettings(context)
                                onClearError()
                            } catch (_: Exception) {
                                onError(settingsFailed)
                            }
                        },
                    )
                    JDivider()
                    ControlPermissionRow(
                        title = stringResource(R.string.phonetools_control_perm_overlay_title),
                        detail = stringResource(R.string.phonetools_control_perm_overlay_detail),
                        granted = snapshot.permissions.overlay,
                        onOpenSettings = {
                            try {
                                PhoneControlPermissionsChecker.openOverlayPermissionSettings(context)
                                onClearError()
                            } catch (_: Exception) {
                                onError(settingsFailed)
                            }
                        },
                    )
                    if (!snapshot.permissions.batteryOptimizationIgnored) {
                        JNotice(
                            text = stringResource(R.string.phonetools_control_bg_notice),
                            status = Status.NeedsYou,
                            actionLabel = stringResource(R.string.phonetools_control_bg_action),
                            onAction = {
                                try {
                                    PhoneControlPermissionsChecker.requestIgnoreBatteryOptimizations(context)
                                    onClearError()
                                } catch (_: Exception) {
                                    onError(settingsFailed)
                                }
                            },
                        )
                    }
                }
            }
            if (remoteControlEnabled) {
                JDivider()
                JSwitchRow(
                    title = stringResource(R.string.phonetools_control_gate_otp_title),
                    checked = allowOtp,
                    subtitle = stringResource(R.string.phonetools_control_gate_otp_detail),
                    subtitleLines = Int.MAX_VALUE,
                    titleLines = 2,
                    onCheckedChange = { value ->
                        scope.launch {
                            try {
                                runtime.settings.setAllowNotificationOtpAutoLogin(value)
                                onClearError()
                            } catch (_: Exception) {
                                onError(toggleFailed)
                            }
                        }
                    },
                )
                JDivider()
                JSwitchRow(
                    title = stringResource(R.string.phonetools_control_gate_payment_title),
                    checked = blockPayment,
                    subtitle = stringResource(R.string.phonetools_control_gate_payment_detail),
                    subtitleLines = Int.MAX_VALUE,
                    titleLines = 2,
                    onCheckedChange = { value ->
                        scope.launch {
                            try {
                                runtime.settings.setBlockRealPayment(value)
                                onClearError()
                            } catch (_: Exception) {
                                onError(toggleFailed)
                            }
                        }
                    },
                )
                Text(
                    text = stringResource(R.string.phonetools_control_safety_footnote),
                    modifier = Modifier.padding(horizontal = Tokens.Space.l).padding(bottom = Tokens.Space.l),
                    style = PersonaTheme.type.caption,
                    color = PersonaTheme.palette.ink3,
                )
            }
        }
    }
}

@Composable
private fun ControlPermissionRow(
    title: String,
    detail: String,
    granted: Boolean,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = Tokens.Space.xs),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.xs),
    ) {
        Text(title, style = PersonaTheme.type.headline, color = PersonaTheme.palette.ink)
        Text(detail, style = PersonaTheme.type.caption, color = PersonaTheme.palette.ink2)
        Row(
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JTag(
                text = stringResource(if (granted) R.string.phonetools_status_granted else R.string.phonetools_status_denied),
                status = if (granted) Status.Unread else Status.NeedsYou,
            )
            JButton(
                text = stringResource(R.string.phonetools_control_open_settings),
                onClick = onOpenSettings,
                kind = JButtonKind.Secondary,
                size = JButtonSize.Small,
            )
        }
    }
}

/** Mount once above the app's navigation host so any screen can confirm a pending call. */
@Composable
fun PhoneConfirmationHost(confirmations: PhoneConfirmations) {
    val pending by confirmations.pending.collectAsStateWithLifecycle()
    pending?.let { request ->
        JDialog(onDismiss = { confirmations.resolve(request.token, false) }, title = stringResource(R.string.phonetools_confirm_title),
            content = {
                Text(request.summary, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    style = PersonaTheme.type.callout, color = PersonaTheme.palette.ink2)
            }, confirm = stringResource(R.string.phonetools_allow) to { confirmations.resolve(request.token, true) },
            dismiss = stringResource(R.string.phonetools_deny) to { confirmations.resolve(request.token, false) })
    }
}
