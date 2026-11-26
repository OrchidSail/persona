package persona.platform.phonetools.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpExtractorAndConsumeTest {

    @Test
    fun extractsChineseAndEnglishOtpsAndRedactsCode() {
        val smsZh = NotificationEntry(
            id = "n1",
            packageName = "com.android.mms",
            title = "Meituan",
            text = "【美团】您的验证码为 482913，5分钟内有效", // i18n-ignore: test SMS OTP text
            postedAtMs = 1_000L,
        )
        val extractedZh = OtpExtractor.extract(smsZh)
        assertNotNull(extractedZh)
        assertEquals("482913", extractedZh!!.code)
        assertFalse(extractedZh.redactedPreview.contains("482913"))
        assertTrue(extractedZh.redactedPreview.contains("[otp-redacted]"))

        val smsZhShort = NotificationEntry(
            id = "n2",
            packageName = "com.android.mms",
            title = "Delivery",
            text = "您的短信码是 9182，请勿泄露", // i18n-ignore: test SMS OTP text
            postedAtMs = 2_000L,
        )
        assertEquals("9182", OtpExtractor.extract(smsZhShort)?.code)

        val smsEn = NotificationEntry(
            id = "n3",
            packageName = "com.google.android.apps.messaging",
            title = "Acme",
            text = "Your verification code is 730194. Do not share it.",
            postedAtMs = 3_000L,
        )
        val extractedEn = OtpExtractor.extract(smsEn)
        assertNotNull(extractedEn)
        assertEquals("730194", extractedEn!!.code)
        assertFalse(extractedEn.redactedPreview.contains("730194"))
    }

    @Test
    fun ignoresCurrencyAmountsAndCalendarYears() {
        val paymentSms = NotificationEntry(
            id = "money-1",
            packageName = "com.android.mms",
            title = "Bank",
            text = "验证码提示：您2026年消费2000元，动态码 519204", // i18n-ignore: test SMS currency filter
            postedAtMs = 5_000L,
        )
        val extracted = OtpExtractor.extract(paymentSms)
        assertNotNull(extracted)
        assertEquals("519204", extracted!!.code)

        val currencyOnly = NotificationEntry(
            id = "money-2",
            packageName = "com.android.mms",
            title = "Bank",
            text = "验证码相关提醒：金额 2000元 于 2026 年入账", // i18n-ignore: test SMS currency filter
            postedAtMs = 6_000L,
        )
        assertNull(OtpExtractor.extract(currencyOnly))
    }

    @Test
    fun notificationBufferSupportsOneTimeConsumptionAndExpiry() {
        var now = 10_000L
        val buffer = NotificationBuffer(clock = { now })
        buffer.record(
            NotificationEntry(
                id = "otp-1",
                packageName = "com.android.mms",
                title = "ShopApp",
                text = "Your verification code: 654321",
                postedAtMs = 9_500L,
            ),
        )

        // Peek via findLatestOtp does NOT consume.
        val peeked = buffer.findLatestOtp(windowMs = 60_000L)
        assertNotNull(peeked)
        assertEquals("654321", peeked!!.code)
        assertFalse(peeked.toMeta().redactedPreview.orEmpty().contains("654321"))

        // First consume succeeds and returns the code.
        val consumed = buffer.consumeOtp(windowMs = 60_000L, otpId = "otp-1", consume = true)
        assertNotNull(consumed)
        assertEquals("654321", consumed!!.code)

        // Second consume or peek returns null because otp-1 is spent.
        assertNull(buffer.consumeOtp(windowMs = 60_000L, otpId = "otp-1", consume = true))
        assertNull(buffer.findLatestOtp(windowMs = 60_000L))

        // Record a fresh code and advance clock past window.
        buffer.record(
            NotificationEntry(
                id = "otp-2",
                packageName = "com.android.mms",
                title = "ShopApp",
                text = "Login code is 112233",
                postedAtMs = 10_000L,
            ),
        )
        now = 250_000L
        assertNull(buffer.findLatestOtp(windowMs = 180_000L))
    }
}
