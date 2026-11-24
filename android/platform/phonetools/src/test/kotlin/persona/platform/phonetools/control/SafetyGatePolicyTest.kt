package persona.platform.phonetools.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyGatePolicyTest {

    @Test
    fun ordinaryActionIsAllowedByDefault() {
        val policy = SafetyGatePolicy { SafetyGateConfig() }
        val decision = policy.evaluate(
            ActionRiskContext(
                actionName = "tap",
                targetLabel = "Search",
                currentPackage = "com.example.shop",
                visibleTexts = listOf("Home", "Search", "Orders"),
            ),
        )
        assertEquals(GateDecision.Allow, decision)
    }

    @Test
    fun paymentGateBlocksWhenEnabledAndAllowsWhenUserTurnsItOff() {
        var blockPayment = true
        val policy = SafetyGatePolicy { SafetyGateConfig(blockRealPayment = blockPayment) }

        val ctx = ActionRiskContext(
            actionName = "tap",
            targetLabel = "立即支付", // i18n-ignore: test third-party payment button
            currentPackage = "com.eg.android.AlipayGphone",
            visibleTexts = listOf("收银台", "立即支付"), // i18n-ignore: test third-party cashier text
        )

        val blocked = policy.evaluate(ctx)
        assertTrue(blocked is GateDecision.RequirePhone)
        assertEquals("payment_confirmation", (blocked as GateDecision.RequirePhone).waitingFor)

        blockPayment = false
        assertEquals(GateDecision.Allow, policy.evaluate(ctx))
    }

    @Test
    fun tapXyAgainstPaymentButtonIsBlocked() {
        val policy = SafetyGatePolicy { SafetyGateConfig(blockRealPayment = true) }
        val decision = policy.evaluate(
            ActionRiskContext(
                actionName = "tap_xy",
                targetLabel = "Confirm Payment",
                targetResourceId = "com.shop:id/btn_pay",
                currentPackage = "com.shop",
                visibleTexts = listOf("Payment method", "Confirm Payment"),
            ),
        )
        assertTrue(decision is GateDecision.RequirePhone)
        assertEquals("payment_confirmation", (decision as GateDecision.RequirePhone).waitingFor)
    }

    @Test
    fun biometricAndSliderCaptchaAlwaysBlockEvenWhenPaymentBlockOff() {
        val policy = SafetyGatePolicy {
            SafetyGateConfig(allowNotificationOtpAutoLogin = true, blockRealPayment = false)
        }
        val captcha = policy.evaluate(
            ActionRiskContext(
                actionName = "swipe",
                currentPackage = "com.example.app",
                visibleTexts = listOf("向右滑动完成拼图"), // i18n-ignore: test slider CAPTCHA marker
            ),
        )
        assertTrue(captcha is GateDecision.RequirePhone)
        assertEquals("biometric_or_captcha", (captcha as GateDecision.RequirePhone).waitingFor)

        val biometric = policy.evaluate(
            ActionRiskContext(
                actionName = "tap",
                targetLabel = "OK",
                currentPackage = "com.android.systemui.biometric",
                visibleTexts = listOf("Touch the fingerprint sensor"),
            ),
        )
        assertTrue(biometric is GateDecision.RequirePhone)
        assertEquals("biometric_or_captcha", (biometric as GateDecision.RequirePhone).waitingFor)
    }

    @Test
    fun otpGateRespectsAutoLoginToggle() {
        var allowOtp = true
        val policy = SafetyGatePolicy { SafetyGateConfig(allowNotificationOtpAutoLogin = allowOtp) }
        val otpCtx = ActionRiskContext(actionName = "otp_consume", currentPackage = "com.example.login")

        assertEquals(GateDecision.Allow, policy.evaluate(otpCtx))

        allowOtp = false
        val denied = policy.evaluate(otpCtx)
        assertTrue(denied is GateDecision.RequirePhone)
        assertEquals("login_verification", (denied as GateDecision.RequirePhone).waitingFor)
    }
}
