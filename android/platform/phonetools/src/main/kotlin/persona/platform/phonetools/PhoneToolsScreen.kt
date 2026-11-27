package persona.platform.phonetools

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import persona.core.designsystem.GroupPosition
import persona.core.designsystem.JButton
import persona.core.designsystem.JButtonKind
import persona.core.designsystem.JButtonSize
import persona.core.designsystem.JDialog
import persona.core.designsystem.JDivider
import persona.core.designsystem.JGroupItem
import persona.core.designsystem.JListPage
import persona.core.designsystem.JNotice
import persona.core.designsystem.JSectionHeader
import persona.core.designsystem.JSwitchRow
import persona.core.designsystem.JTag
import persona.core.designsystem.JTextField
import persona.core.designsystem.PersonaTheme
import persona.core.designsystem.Status
import persona.core.designsystem.Tokens
import persona.core.designsystem.dense
import persona.core.designsystem.rowPadding
import persona.core.navigation.Navigator
import persona.platform.phonetools.control.PhoneControlAccessibilityService
import persona.platform.phonetools.control.PhoneControlNotificationListener
import persona.platform.phonetools.control.PhoneControlPermissionsChecker
import persona.platform.phonetools.control.shell.PrivilegedShellError
import persona.platform.phonetools.control.shell.PrivilegedShellStatus
import persona.platform.phonetools.control.shell.PrivilegedShellUiState

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
        PermissionController.createRequestPermissionResultContract(),
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
            Text(
                stringResource(R.string.phonetools_intro),
                Modifier.padding(bottom = 16.dp.dense),
                style = PersonaTheme.type.callout,
                color = PersonaTheme.palette.ink2,
            )
        }
        item(key = "remote_phone_control") {
            PhoneControlSection(
                runtime = runtime,
                revision = revision,
                onError = { error = it },
                onClearError = { error = null },
            )
        }
        item(key = "phone_data_section_header") {
            JSectionHeader(stringResource(R.string.phonetools_data_section), sub = true)
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
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp.dense).padding(bottom = 16.dp.dense),
                    verticalArrangement = Arrangement.spacedBy(8.dp.dense),
                ) {
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
                            JButton(
                                stringResource(if (status == PhoneAccess.Partial) R.string.phonetools_choose_again else R.string.phonetools_allow_access),
                                {
                                    val permissions = runtime.permissions.permissions(source)
                                    if (permissions.isNotEmpty()) launcher.launch(permissions)
                                },
                                kind = JButtonKind.Secondary,
                                size = JButtonSize.Small,
                                enabled = runtime.permissions.permissions(source).isNotEmpty(),
                            )
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
            Text(
                stringResource(R.string.phonetools_footnote),
                style = PersonaTheme.type.caption,
                color = PersonaTheme.palette.ink3,
            )
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
    val shellUi by runtime.control.privilegedShell.state.collectAsStateWithLifecycle()
    val snapshot = remember(revision, remoteControlEnabled, allowOtp, blockPayment, a11yService, notifConnected, shellUi) {
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
    val contentInset = rowPadding
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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = contentInset).padding(bottom = Tokens.Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                JTag(statusText, status = statusTone)
            }
        }
        if (remoteControlEnabled) {
            JSectionHeader(stringResource(R.string.phonetools_control_perms_section), sub = true)
            JGroupItem(GroupPosition.Single, Modifier.padding(bottom = Tokens.Space.m)) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = contentInset, vertical = Tokens.Space.m),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Space.s),
                ) {
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
                        JDivider()
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

            JSectionHeader(stringResource(R.string.phonetools_control_vd_section), sub = true)
            JGroupItem(GroupPosition.Single, Modifier.padding(bottom = Tokens.Space.m)) {
                BackgroundVirtualDisplaySubsection(
                    runtime = runtime,
                    onError = onError,
                    onClearError = onClearError,
                )
            }

            JSectionHeader(stringResource(R.string.phonetools_control_safety_section), sub = true)
            JGroupItem(GroupPosition.Single, Modifier.padding(bottom = Tokens.Space.m)) {
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
                    modifier = Modifier.padding(horizontal = contentInset).padding(bottom = Tokens.Space.m),
                    style = PersonaTheme.type.caption,
                    color = PersonaTheme.palette.ink3,
                )
            }
        }
    }
}

@Composable
private fun BackgroundVirtualDisplaySubsection(
    runtime: PhoneToolsRuntime,
    onError: (String) -> Unit,
    onClearError: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shell = runtime.control.privilegedShell
    val ui by shell.state.collectAsStateWithLifecycle()
    val settingsFailed = stringResource(R.string.phonetools_error_settings)

    BackgroundVirtualDisplayContent(
        ui = ui,
        onStartDiscovery = shell::startPairingDiscovery,
        onStopDiscovery = shell::stopDiscovery,
        onOpenWirelessSettings = {
            if (shell.openWirelessDebuggingSettings(context)) onClearError()
            else onError(settingsFailed)
        },
        onReconnect = { scope.launch { shell.connectNow() } },
        onUnpair = { scope.launch { shell.unpair() } },
        onRequestShizuku = { val _ = shell.requestShizukuOrOpen(context) },
        onPairWithCode = { code, manualPort -> shell.pairWithCode(code, manualPort) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BackgroundVirtualDisplayContent(
    ui: PrivilegedShellUiState,
    onStartDiscovery: () -> Unit = {},
    onStopDiscovery: () -> Unit = {},
    onOpenWirelessSettings: () -> Unit = {},
    onReconnect: () -> Unit = {},
    onUnpair: () -> Unit = {},
    onRequestShizuku: () -> Unit = {},
    onPairWithCode: suspend (String, Int?) -> Boolean = { _, _ -> false },
) {
    val scope = rememberCoroutineScope()
    var showPairingForm by remember { mutableStateOf(false) }
    var codeInput by remember { mutableStateOf("") }
    var portInput by remember { mutableStateOf("") }

    DisposableEffect(showPairingForm) {
        if (showPairingForm) {
            onStartDiscovery()
        }
        onDispose {
            if (showPairingForm) {
                onStopDiscovery()
            }
        }
    }

    val (badgeText, badgeStatus) = when (ui.status) {
        PrivilegedShellStatus.Running -> when (ui.backend) {
            "shizuku" -> stringResource(R.string.phonetools_control_vd_status_shizuku) to Status.Unread
            "builtin_loopback_tcp_adb" -> stringResource(R.string.phonetools_control_vd_status_loopback_tcp) to Status.Unread
            else -> stringResource(R.string.phonetools_control_vd_status_builtin) to Status.Unread
        }
        PrivilegedShellStatus.Connecting -> stringResource(R.string.phonetools_control_vd_status_connecting) to Status.Working
        PrivilegedShellStatus.AwaitingAuthorization -> stringResource(R.string.phonetools_control_vd_status_awaiting_auth) to Status.NeedsYou
        PrivilegedShellStatus.Pairing -> stringResource(R.string.phonetools_control_vd_status_pairing) to Status.Working
        PrivilegedShellStatus.WirelessDebuggingOff -> stringResource(R.string.phonetools_control_vd_status_wireless_off) to Status.NeedsYou
        PrivilegedShellStatus.Failed -> stringResource(R.string.phonetools_control_vd_status_failed) to Status.NeedsYou
        PrivilegedShellStatus.NotConfigured -> stringResource(R.string.phonetools_control_vd_status_inactive) to null
    }

    val errorRes: Int? = when (ui.error) {
        PrivilegedShellError.None -> null
        PrivilegedShellError.UnsupportedSdk -> R.string.phonetools_control_vd_err_unsupported
        PrivilegedShellError.InvalidCode -> R.string.phonetools_control_vd_err_invalid_code
        PrivilegedShellError.PairingWindowNotFound -> R.string.phonetools_control_vd_err_no_port
        PrivilegedShellError.WrongCode -> R.string.phonetools_control_vd_err_wrong_code
        PrivilegedShellError.NoWifi -> R.string.phonetools_control_vd_err_no_wifi
        PrivilegedShellError.AuthorizationRevoked -> R.string.phonetools_control_vd_err_revoked
        PrivilegedShellError.LoopbackAuthTimeout -> R.string.phonetools_control_vd_err_loopback_auth_timeout
        PrivilegedShellError.LoopbackAuthDenied -> R.string.phonetools_control_vd_err_loopback_auth_denied
        PrivilegedShellError.KeyUnreadable -> R.string.phonetools_control_vd_err_key
        PrivilegedShellError.ConnectFailed -> R.string.phonetools_control_vd_err_connect
        PrivilegedShellError.HelperStartFailed -> R.string.phonetools_control_vd_err_helper
        PrivilegedShellError.Unknown -> R.string.phonetools_control_vd_err_unknown
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = rowPadding, vertical = Tokens.Space.m),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.s),
    ) {
        Text(
            text = stringResource(R.string.phonetools_control_vd_title),
            style = PersonaTheme.type.headline,
            color = PersonaTheme.palette.ink,
        )
        Text(
            text = stringResource(R.string.phonetools_control_vd_detail),
            style = PersonaTheme.type.caption,
            color = PersonaTheme.palette.ink2,
        )
        JTag(text = badgeText, status = badgeStatus)

        if (ui.scanningPorts) {
            Text(
                text = stringResource(R.string.phonetools_control_vd_port_scanning),
                style = PersonaTheme.type.caption,
                color = PersonaTheme.palette.ink2,
            )
        }
        if (ui.status == PrivilegedShellStatus.AwaitingAuthorization) {
            JNotice(
                text = stringResource(R.string.phonetools_control_vd_awaiting_auth_hint),
                status = Status.NeedsYou,
            )
        } else {
            errorRes?.let { resId ->
                JNotice(text = stringResource(resId), status = Status.NeedsYou)
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.s),
        ) {
            if (!ui.isReady) {
                JButton(
                    text = stringResource(R.string.phonetools_control_vd_open_wireless),
                    onClick = onOpenWirelessSettings,
                    kind = JButtonKind.Secondary,
                    size = JButtonSize.Small,
                )
            }
            if (!ui.wirelessPaired || ui.error == PrivilegedShellError.AuthorizationRevoked || ui.error == PrivilegedShellError.KeyUnreadable) {
                JButton(
                    text = stringResource(
                        if (showPairingForm) R.string.phonetools_control_vd_pair_hide
                        else R.string.phonetools_control_vd_pair_setup,
                    ),
                    onClick = { showPairingForm = !showPairingForm },
                    kind = JButtonKind.Secondary,
                    size = JButtonSize.Small,
                )
            }
            val canReconnect = !ui.isReady && (
                ui.wirelessPaired ||
                    ui.loopbackTcpAuthorized ||
                    ui.loopbackTcpPort != null ||
                    ui.status == PrivilegedShellStatus.Failed ||
                    ui.status == PrivilegedShellStatus.WirelessDebuggingOff
                )
            val connectBusy = ui.status == PrivilegedShellStatus.Connecting ||
                ui.status == PrivilegedShellStatus.Pairing ||
                ui.status == PrivilegedShellStatus.AwaitingAuthorization
            if (canReconnect) {
                JButton(
                    text = stringResource(R.string.phonetools_control_vd_reconnect),
                    onClick = onReconnect,
                    enabled = !connectBusy,
                    kind = JButtonKind.Secondary,
                    size = JButtonSize.Small,
                )
            }
            if (ui.wirelessPaired || ui.loopbackTcpAuthorized) {
                JButton(
                    text = stringResource(R.string.phonetools_control_vd_unpair),
                    onClick = {
                        showPairingForm = false
                        onUnpair()
                    },
                    kind = JButtonKind.Secondary,
                    size = JButtonSize.Small,
                )
            }
            if (ui.shizukuInstalled && !ui.shizukuPermissionGranted && !ui.isReady) {
                JButton(
                    text = stringResource(
                        if (ui.shizukuRunning) R.string.phonetools_control_vd_shizuku_action
                        else R.string.phonetools_control_vd_shizuku_open,
                    ),
                    onClick = onRequestShizuku,
                    kind = JButtonKind.Secondary,
                    size = JButtonSize.Small,
                )
            }
        }

        if (showPairingForm) {
            Text(
                text = stringResource(R.string.phonetools_control_vd_pair_guide),
                style = PersonaTheme.type.caption,
                color = PersonaTheme.palette.ink2,
            )
            val discoveredPort = ui.discoveredPairingPort
            val portHintText = when {
                discoveredPort != null -> stringResource(R.string.phonetools_control_vd_port_found, discoveredPort)
                ui.pairingDiscoveryActive -> stringResource(R.string.phonetools_control_vd_port_searching)
                else -> null
            }
            portHintText?.let {
                Text(it, style = PersonaTheme.type.caption, color = PersonaTheme.palette.ink2)
            }
            JTextField(
                value = codeInput,
                onValueChange = { raw -> codeInput = raw.filter { it in '0'..'9' }.take(6) },
                label = stringResource(R.string.phonetools_control_vd_code_label),
                placeholder = stringResource(R.string.phonetools_control_vd_code_placeholder),
                singleLine = true,
                autofill = false,
                monospace = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            JTextField(
                value = portInput,
                onValueChange = { raw -> portInput = raw.filter { it in '0'..'9' }.take(5) },
                label = stringResource(R.string.phonetools_control_vd_port_label),
                placeholder = stringResource(R.string.phonetools_control_vd_port_placeholder),
                singleLine = true,
                autofill = false,
                monospace = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            val busy = ui.status == PrivilegedShellStatus.Pairing || ui.status == PrivilegedShellStatus.Connecting || ui.status == PrivilegedShellStatus.AwaitingAuthorization
            JButton(
                text = stringResource(R.string.phonetools_control_vd_pair_submit),
                onClick = {
                    val manualPort = portInput.trim().toIntOrNull()
                    scope.launch {
                        if (onPairWithCode(codeInput, manualPort)) {
                            codeInput = ""
                            portInput = ""
                            showPairingForm = false
                        }
                    }
                },
                enabled = !busy && codeInput.length == 6,
                kind = JButtonKind.Primary,
                size = JButtonSize.Small,
            )
        } else if (!ui.shizukuInstalled && !ui.isReady) {
            Text(
                text = stringResource(R.string.phonetools_control_vd_shizuku_hint),
                style = PersonaTheme.type.caption,
                color = PersonaTheme.palette.ink3,
            )
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
        JDialog(
            onDismiss = { confirmations.resolve(request.token, false) },
            title = stringResource(R.string.phonetools_confirm_title),
            content = {
                Text(
                    request.summary,
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    style = PersonaTheme.type.callout,
                    color = PersonaTheme.palette.ink2,
                )
            },
            confirm = stringResource(R.string.phonetools_allow) to { confirmations.resolve(request.token, true) },
            dismiss = stringResource(R.string.phonetools_deny) to { confirmations.resolve(request.token, false) },
        )
    }
}
