package com.jorisjonkers.personalstack.agents.infrastructure.credential

/**
 * Decides whether a unix-socket peer may use the git-credential socket.
 * `SO_PEERCRED` only ever hands back the resolved *name* of the peer's
 * uid (`jdk.net.UnixDomainPrincipal.user().getName()`, backed by
 * `sun.nio.fs.UnixUserPrincipals.fromUid`, which prefers the NSS-resolved
 * username and only falls back to the decimal uid when that lookup
 * fails) — there is no public JDK API that returns the raw uid from an
 * accepted channel. [AgentUidResolver] resolves the configured uid to
 * the name(s) this check accepts.
 */
object GitCredentialPeerAuthorization {
    fun isAuthorized(
        peerPrincipalName: String?,
        allowedNames: Set<String>,
    ): Boolean = peerPrincipalName != null && peerPrincipalName in allowedNames
}
