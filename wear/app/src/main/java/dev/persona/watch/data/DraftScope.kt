package dev.persona.watch.data

import java.security.MessageDigest

object DraftScope {
    fun hash(primaryUrl: String, groups: Set<String>, key: String): String {
        val owner = groups.firstOrNull { group ->
            listOf("session:", "send:session:", "continue:", "resolve:").any { prefix -> key.startsWith(prefix + group + "\u0000") }
        }
        val scope = owner?.let { "group:$it" } ?: primaryUrl
        return MessageDigest.getInstance("SHA-256").digest((scope + "\u0000" + key).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
