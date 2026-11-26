package persona.platform.phonetools.control

/**
 * Context describing a candidate UI action and the screen it targets.
 */
data class ActionRiskContext(
    val actionName: String,
    val targetLabel: String = "",
    val targetResourceId: String = "",
    val currentPackage: String = "",
    val visibleTexts: List<String> = emptyList(),
    val hasPasswordField: Boolean = false,
)

sealed interface GateDecision {
    /** Proceed directly without pausing the task. */
    data object Allow : GateDecision

    /** Pause into waiting_on_phone until the user completes or hands back control on the device. */
    data class RequirePhone(
        val waitingFor: String,
        val reason: String,
        val app: String? = null,
    ) : GateDecision
}

/**
 * Deterministic phone-local safety gate evaluated before executing remote actions or reading OTPs.
 *
 * Rules:
 * 1. Explicit hand_to_user pauses into waiting_on_phone ("hand_to_user").
 * 2. Notification OTP actions respect [SafetyGateConfig.allowNotificationOtpAutoLogin] (default ON).
 * 3. Physical biometric / graphic slider / puzzle CAPTCHA blockers ALWAYS pause into "biometric_or_captcha".
 * 4. When OTP auto-login is OFF, login OTP clicks pause into "login_verification".
 * 5. Real payment / fund transfer confirmations pause into "payment_confirmation" while
 *    [SafetyGateConfig.blockRealPayment] is ON (default ON, toggleable on phone).
 * 6. Ordinary actions execute directly.
 */
class SafetyGatePolicy(
    private val configProvider: () -> SafetyGateConfig,
) {
    fun evaluate(ctx: ActionRiskContext): GateDecision {
        val config = configProvider()
        val action = ctx.actionName.trim().lowercase()
        val screenBlob = (ctx.visibleTexts + ctx.targetLabel + ctx.targetResourceId)
            .joinToString(" ")
            .lowercase()
        val targetBlob = (ctx.targetLabel + " " + ctx.targetResourceId).lowercase()

        if (action == "hand_to_user" || action == "request_phone_takeover") {
            return GateDecision.RequirePhone(
                waitingFor = "hand_to_user",
                reason = ctx.targetLabel.ifBlank { "Manual interaction requested on phone" },
                app = ctx.currentPackage.ifBlank { null },
            )
        }

        if (action in OTP_ACTIONS) {
            return if (config.allowNotificationOtpAutoLogin) {
                GateDecision.Allow
            } else {
                GateDecision.RequirePhone(
                    waitingFor = "login_verification",
                    reason = "Notification OTP auto-login is disabled on phone",
                    app = ctx.currentPackage.ifBlank { null },
                )
            }
        }

        if (isPhysicalBiometricOrCaptcha(screenBlob, ctx.currentPackage)) {
            return GateDecision.RequirePhone(
                waitingFor = "biometric_or_captcha",
                reason = "Biometric prompt or graphic CAPTCHA detected; complete on phone",
                app = ctx.currentPackage.ifBlank { null },
            )
        }

        if (!config.allowNotificationOtpAutoLogin && isOtpLoginAction(targetBlob, screenBlob)) {
            return GateDecision.RequirePhone(
                waitingFor = "login_verification",
                reason = "Notification OTP auto-login is disabled on phone; complete login on device",
                app = ctx.currentPackage.ifBlank { null },
            )
        }

        if (config.blockRealPayment && isRealPaymentAction(action, targetBlob, screenBlob, ctx.currentPackage)) {
            return GateDecision.RequirePhone(
                waitingFor = "payment_confirmation",
                reason = "Real payment confirmation blocked by phone safety gate; confirm on device",
                app = ctx.currentPackage.ifBlank { null },
            )
        }

        return GateDecision.Allow
    }

    private fun isPhysicalBiometricOrCaptcha(screenBlob: String, pkg: String): Boolean {
        if (pkg.contains("biometric", ignoreCase = true)) return true
        return BIOMETRIC_OR_CAPTCHA_MARKERS.any { it in screenBlob }
    }

    private fun isOtpLoginAction(targetBlob: String, screenBlob: String): Boolean {
        val onOtpScreen = OTP_SCREEN_MARKERS.any { it in screenBlob }
        val isAuthClick = OTP_ACTION_MARKERS.any { it in targetBlob }
        return onOtpScreen && isAuthClick
    }

    private fun isRealPaymentAction(
        action: String,
        targetBlob: String,
        screenBlob: String,
        pkg: String,
    ): Boolean {
        if (action !in INTERACTIVE_ACTIONS) return false
        if (PAYMENT_CONFIRM_TARGETS.any { it in targetBlob }) return true

        val inPaymentCashier = pkg in PAYMENT_PACKAGES || PAYMENT_CASHIER_MARKERS.any { it in screenBlob }
        val genericConfirmClick = GENERIC_PAY_BUTTONS.any { it in targetBlob }
        return inPaymentCashier && genericConfirmClick
    }

    companion object {
        private val INTERACTIVE_ACTIONS = setOf("tap", "tap_xy", "double_tap", "long_press", "input_text")
        private val OTP_ACTIONS = setOf("read_verification_code", "fill_verification_code", "otp_consume", "read_notifications")

        private val BIOMETRIC_OR_CAPTCHA_MARKERS = listOf(
            "向右滑动完成拼图", "拖动下方滑块", "滑动拼图", "滑动验证", "安全验证滑块", // i18n-ignore: matches third-party CAPTCHA screens
            "请按顺序点击", "点击图中", "指纹验证", "面容识别", "刷脸验证", "人脸核验", // i18n-ignore: matches third-party biometric screens
            "验证指纹", "touch the fingerprint sensor", "verify your fingerprint", // i18n-ignore: matches biometric prompt text
            "face authentication", "slide to verify", "drag the slider", "puzzle captcha",
        )

        private val OTP_SCREEN_MARKERS = listOf(
            "获取验证码", "输入验证码", "短信验证码", "验证码登录", "verification code", "enter otp", // i18n-ignore: matches third-party OTP login screens
        )

        private val OTP_ACTION_MARKERS = listOf(
            "获取验证码", "登录", "验证并登录", "下一步", "sign in", "log in", "verify", // i18n-ignore: matches third-party OTP login buttons
        )

        private val PAYMENT_PACKAGES = setOf(
            "com.eg.android.AlipayGphone",
            "com.unionpay",
            "com.icbc",
            "com.chinamworld.main",
            "com.cmbchina.ccd.pluto.cmbActivity",
            "com.android.bankabc",
        )

        private val PAYMENT_CONFIRM_TARGETS = listOf(
            "立即支付", "确认付款", "确认支付", "去支付", "免密支付", "极速支付", "支付密码", // i18n-ignore: matches third-party payment buttons
            "确认转账", "立即转账", "确认充值", "确认提现", "刷脸支付", "指纹支付", // i18n-ignore: matches third-party payment buttons
            "pay now", "confirm payment", "complete payment", "authorize payment", "transfer now",
        )

        private val PAYMENT_CASHIER_MARKERS = listOf(
            "收银台", "付款方式", "支付密码", "请输入支付密码", "微信支付", "支付宝支付", // i18n-ignore: matches third-party cashier pages
            "余额支付", "花呗", "银行卡支付", "免密支付", "confirm and pay", "payment method", // i18n-ignore: matches third-party cashier pages
        )

        private val GENERIC_PAY_BUTTONS = listOf(
            "支付", "付款", "转账", "确认", "pay", "confirm", // i18n-ignore: matches third-party cashier buttons
        )
    }
}
