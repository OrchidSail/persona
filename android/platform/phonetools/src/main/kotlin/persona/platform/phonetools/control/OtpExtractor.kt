package persona.platform.phonetools.control

/**
 * Ephemeral notification captured in memory by [PhoneControlNotificationListener].
 * Never persisted to disk.
 */
data class NotificationEntry(
    val id: String,
    val packageName: String,
    val title: String,
    val text: String,
    val subText: String = "",
    val postedAtMs: Long,
) {
    val combinedText: String
        get() = listOf(title, text, subText).filter { it.isNotBlank() }.joinToString(" ")
}

data class ExtractedOtp(
    val otpId: String,
    val code: String,
    val packageName: String,
    val senderOrTitle: String,
    val postedAtMs: Long,
    val redactedPreview: String,
) {
    fun toMeta(): OtpMeta = OtpMeta(
        available = true,
        otpId = otpId,
        packageName = packageName,
        sender = senderOrTitle,
        postedAtMs = postedAtMs,
        redactedPreview = redactedPreview,
    )
}

/**
 * Extracts one-time verification codes from Chinese and English SMS/push notifications.
 * Raw codes are redacted in previews so observe/log payloads never expose plaintext OTPs.
 */
object OtpExtractor {
    private val OTP_CONTEXT_KEYWORDS = listOf(
        "验证码", "校验码", "动态码", "动态密码", "一次性密码", "确认码", "安全码", "登录码", "短信码", "取件码", "核销码", "驗證碼", "一次性密碼", // i18n-ignore: matches real SMS OTP text
        "verification code", "verify code", "security code", "login code", "passcode",
        "one-time", "otp", "2fa", "auth code", "confirm code", "code is", "code:", "code：", // i18n-ignore: matches real SMS OTP text
    )

    private val LABELED_CODE_REGEX = Regex(
        """(?i)(?:验证码|校验码|动态码|动态密码|一次性密码|驗證碼|一次性密碼|确认码|安全码|登录码|短信码|取件码|核销码|verification\s*code|verify\s*code|security\s*code|login\s*code|one[-\s]?time\s*(?:password|code)|passcode|otp|code)\s*(?:为|是|:|：|is|#|\s){0,4}([0-9]{4,8})\b""", // i18n-ignore: matches real SMS OTP text
    )
    private val STANDALONE_CODE_REGEX = Regex("""(?<![0-9A-Za-z])(?<![0-9]\.)([0-9]{4,8})(?![0-9A-Za-z])(?!\.[0-9])""")
    private val MONEY_NEAR_REGEX = Regex(
        """(?:¥|￥|\$|元|块|RMB|CNY|USD)\s*[0-9]+|[0-9]+\s*(?:元|块|RMB|CNY|USD)""", // i18n-ignore: filters currency amounts in SMS text
        RegexOption.IGNORE_CASE,
    )

    fun extract(entry: NotificationEntry): ExtractedOtp? {
        val full = entry.combinedText.trim()
        if (full.isEmpty()) return null
        val lower = full.lowercase()
        if (OTP_CONTEXT_KEYWORDS.none { it in lower }) return null

        val labeled = LABELED_CODE_REGEX.find(full)?.groupValues?.getOrNull(1)
        val candidate = labeled ?: findBestStandaloneCode(full) ?: return null
        return ExtractedOtp(
            otpId = entry.id,
            code = candidate,
            packageName = entry.packageName,
            senderOrTitle = entry.title.ifBlank { entry.packageName },
            postedAtMs = entry.postedAtMs,
            redactedPreview = redactCode(full, candidate),
        )
    }

    fun redactCode(text: String, code: String): String {
        if (code.isBlank() || text.isBlank()) return text
        return text.replace(code, "[otp-redacted]")
    }

    private fun findBestStandaloneCode(text: String): String? {
        val moneySpans = MONEY_NEAR_REGEX.findAll(text).map { it.range }.toList()
        val matches = STANDALONE_CODE_REGEX.findAll(text)
            .filter { m -> moneySpans.none { span -> m.range.first in span || m.range.last in span } }
            .map { it.groupValues[1] }
            .filterNot { it.length == 4 && it.startsWith("20") && it.toIntOrNull() in 2020..2039 }
            .toList()
        return matches.firstOrNull { it.length == 6 }
            ?: matches.firstOrNull { it.length == 4 }
            ?: matches.firstOrNull()
    }
}

/**
 * Bounded thread-safe in-memory buffer for recent notifications with one-time OTP consumption.
 * Never persisted to disk.
 */
class NotificationBuffer(
    private val maxEntries: Int = 64,
    private val maxConsumedIds: Int = 128,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val entries = ArrayDeque<NotificationEntry>()
    private val consumedIds = LinkedHashSet<String>()

    fun record(entry: NotificationEntry) {
        synchronized(lock) {
            val _ = entries.removeAll { it.id == entry.id }
            entries.addLast(entry)
            while (entries.size > maxEntries) {
                val _ = entries.removeFirst()
            }
        }
    }

    fun findLatestOtp(
        windowMs: Long = 180_000L,
        sinceMs: Long = 0L,
        appHint: String? = null,
        otpId: String? = null,
    ): ExtractedOtp? = synchronized(lock) {
        selectUnconsumedLocked(windowMs = windowMs, sinceMs = sinceMs, appHint = appHint, otpId = otpId)
    }

    fun consumeOtp(
        windowMs: Long = 180_000L,
        sinceMs: Long = 0L,
        appHint: String? = null,
        otpId: String? = null,
        consume: Boolean = true,
    ): ExtractedOtp? = synchronized(lock) {
        val found = selectUnconsumedLocked(windowMs = windowMs, sinceMs = sinceMs, appHint = appHint, otpId = otpId)
            ?: return@synchronized null
        if (consume) {
            val _ = consumedIds.add(found.otpId)
            while (consumedIds.size > maxConsumedIds) {
                val oldest = consumedIds.first()
                val _ = consumedIds.remove(oldest)
            }
        }
        found
    }

    fun size(): Int = synchronized(lock) { entries.size }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            consumedIds.clear()
        }
    }

    private fun selectUnconsumedLocked(
        windowMs: Long,
        sinceMs: Long,
        appHint: String?,
        otpId: String?,
    ): ExtractedOtp? {
        val wantedId = otpId?.trim()?.takeIf { it.isNotEmpty() }
        if (wantedId != null) {
            if (wantedId in consumedIds) return null
            val exact = entries.firstOrNull { it.id == wantedId } ?: return null
            return OtpExtractor.extract(exact)
        }
        val cutoff = maxOf(clock() - windowMs, sinceMs)
        val hint = appHint?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val candidates = entries
            .filter { it.postedAtMs >= cutoff && it.id !in consumedIds }
            .sortedByDescending { it.postedAtMs }

        if (hint != null) {
            candidates
                .filter { e -> e.packageName.lowercase().contains(hint) || e.combinedText.lowercase().contains(hint) }
                .firstNotNullOfOrNull { OtpExtractor.extract(it) }
                ?.let { return it }
        }
        return candidates.firstNotNullOfOrNull { OtpExtractor.extract(it) }
    }
}
