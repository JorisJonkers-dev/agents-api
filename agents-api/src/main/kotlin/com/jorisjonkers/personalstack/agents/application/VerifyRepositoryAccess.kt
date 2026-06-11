package com.jorisjonkers.personalstack.agents.application

import com.jorisjonkers.personalstack.agents.domain.model.AccessVerification
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.BranchProtectionClient
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * Combined deploy-key verification: the gateway's read/write probe
 * plus the GitHub branch-protection lookup, flattened into the single
 * result shape the UI renders. Every sub-check degrades gracefully —
 * an unreachable gateway leaves read/write null and a missing token
 * leaves protection null; neither throws.
 */
@Service
class VerifyRepositoryAccess(
    private val gateway: AgentGatewayClient,
    private val branchProtection: BranchProtectionClient,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class Result(
        val read: Boolean?,
        val write: Boolean?,
        val defaultBranchProtected: Boolean?,
        val checkedAt: Instant,
        val messages: List<String>,
    ) {
        fun toAccessVerification() =
            AccessVerification(
                read = read,
                write = write,
                defaultBranchProtected = defaultBranchProtected,
                checkedAt = checkedAt,
                messages = messages,
            )
    }

    fun verify(
        repoUrl: String,
        defaultBranch: String,
    ): Result {
        val messages = mutableListOf<String>()

        val access = gateway.verifyAccess(repoUrl, defaultBranch)
        val read = access?.read
        val write = access?.write
        when {
            access == null ->
                messages += "deploy-key access could not be verified (verify gateway unavailable)"
            !access.read ->
                messages += "deploy key cannot read the repository: ${access.detail}"
            !access.write ->
                messages += "deploy key is read-only — agent commits/pushes will fail: ${access.detail}"
        }

        val protected = branchProtection.isBranchProtected(repoUrl, defaultBranch)
        when (protected) {
            null -> messages += "branch protection on '$defaultBranch' could not be determined"
            false -> messages += "default branch '$defaultBranch' is NOT protected on GitHub"
            true -> Unit
        }

        return Result(
            read = read,
            write = write,
            defaultBranchProtected = protected,
            checkedAt = Instant.now(clock),
            messages = messages,
        )
    }
}
