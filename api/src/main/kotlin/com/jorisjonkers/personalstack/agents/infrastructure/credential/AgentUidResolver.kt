package com.jorisjonkers.personalstack.agents.infrastructure.credential

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves the OS username(s) a configured uid can present over
 * `SO_PEERCRED` (see [GitCredentialPeerAuthorization]). Reads `/etc/passwd`
 * directly — plain text, no JNI/native call — so a container with a
 * `agent:x:10002:...` entry (ADR 0003) resolves to `"agent"`; the raw uid
 * string is always accepted too, in case NSS resolution ever fails and
 * the JDK falls back to the decimal uid.
 */
object AgentUidResolver {
    private val log = LoggerFactory.getLogger(AgentUidResolver::class.java)

    fun resolveAllowedNames(
        uid: Long,
        passwdPath: Path = Path.of("/etc/passwd"),
    ): Set<String> {
        val resolved =
            runCatching { Files.readAllLines(passwdPath) }.getOrElse { ex ->
                log.warn("could not read {} to resolve uid {}: {}", passwdPath, uid, ex.message)
                emptyList()
            }
        val name = resolved.firstNotNullOfOrNull { line -> nameForUid(line, uid) }
        return setOfNotNull(name, uid.toString())
    }

    private fun nameForUid(
        passwdLine: String,
        uid: Long,
    ): String? {
        val fields = passwdLine.split(":")
        return fields.getOrNull(NAME_FIELD)?.takeIf { fields.getOrNull(UID_FIELD) == uid.toString() }
    }

    private const val NAME_FIELD = 0
    private const val UID_FIELD = 2
}
