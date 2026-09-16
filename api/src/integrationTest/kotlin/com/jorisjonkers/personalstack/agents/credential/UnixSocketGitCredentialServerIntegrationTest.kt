package com.jorisjonkers.personalstack.agents.credential

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.jorisjonkers.personalstack.agents.application.credential.WorkspaceGitCredentialService
import com.jorisjonkers.personalstack.agents.application.workspace.WorkspaceDirectoryService
import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.infrastructure.credential.UnixSocketGitCredentialServer
import com.jorisjonkers.personalstack.agents.infrastructure.process.ProcessRunner
import com.jorisjonkers.personalstack.agents.infrastructure.process.RunAsAgentCommandRunner
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jdk.net.ExtendedSocketOptions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Exercises the real `SO_PEERCRED` path end to end: a real unix domain
 * socket, a real accepted [java.nio.channels.SocketChannel], and the
 * real [jdk.net.ExtendedSocketOptions.SO_PEERCRED] read off it — nothing
 * here is mocked at the socket layer.
 *
 * This proves: the wire protocol (newline JSON request in, newline JSON
 * response out), that [com.jorisjonkers.personalstack.agents.infrastructure.credential.GitCredentialPeerAuthorization]
 * is wired to a *real* peer identity rather than a fabricated one, and
 * that a peer identity the server wasn't configured to trust is rejected
 * before any token is minted.
 *
 * This does NOT prove: that the container's actual `agent` (10002) vs
 * `api` (10001) vs root uids behave this way — this JVM test process
 * has exactly one uid, so it can only connect to itself as itself. The
 * two tests below instead treat *this process's own real uid* as the
 * peer identity under test, configuring the server to trust it (accept
 * case) or to trust a different, made-up name it can never present
 * (reject case). `SO_PEERCRED` and its resolution to a username are
 * real; only the specific uids 10001/10002/0 are assumed, on the
 * strength of `docs/adr/0003-*.md` and the manual verification recorded
 * in the #63 design notes, not exercised here. `run-as-agent` is a
 * passthrough script, exactly as in `ShellAgentSessionIntegrationTest`.
 *
 * Linux-only (`SO_PEERCRED` is not offered by the JDK on other unix
 * platforms), so this is skipped, not failed, everywhere else.
 */
@Tag("integration")
class UnixSocketGitCredentialServerIntegrationTest {
    @TempDir
    lateinit var workspacesRoot: Path

    private lateinit var props: AgentRuntimeProperties
    private lateinit var directories: WorkspaceDirectoryService
    private lateinit var credentialService: WorkspaceGitCredentialService
    private lateinit var server: UnixSocketGitCredentialServer
    private var workspaceId: WorkspaceId = WorkspaceId.random()

    @BeforeEach
    fun setUp() {
        assumeTrue(soPeerCredSupported(), "SO_PEERCRED is not supported on this runner (needs Linux)")
        workspaceId = WorkspaceId.random()
        credentialService = mockk()
    }

    @AfterEach
    fun tearDown() {
        if (this::server.isInitialized) server.stop(workspaceId)
    }

    private fun buildServer(agentUid: Long) {
        props =
            AgentRuntimeProperties(
                namespace = "agents-system",
                image = "unused",
                serviceAccount = "unused",
                claudeCredentialsPvc = "unused",
                codexCredentialsPvc = "unused",
                githubDeployKeySecret = "unused",
                workspacesRoot = workspacesRoot.toString(),
                runAsAgentPath = passthroughScript(workspacesRoot).toString(),
                agentUid = agentUid,
            )
        val commands = RunAsAgentCommandRunner(ProcessRunner(), props)
        directories = WorkspaceDirectoryService(props, commands)
        val objectMapper = ObjectMapper().registerModule(JavaTimeModule())
        server = UnixSocketGitCredentialServer(props, directories, credentialService, objectMapper)
        server.ensureStarted(workspaceId)
    }

    @Test
    fun aCallerWhoseRealUidIsNotTheConfiguredAgentUidIsRejectedBeforeAnyMintIsAttempted() {
        // Configure the server to trust a uid this test process can
        // never actually present — its own real SO_PEERCRED identity is
        // then, correctly, someone else.
        buildServer(agentUid = IMPOSSIBLE_UID)

        val response = sendRequest(socketPathFor(workspaceId), """{"repoUrl":"https://github.com/o/r.git"}""")

        assertThat(response).contains("\"error\"")
        verify(exactly = 0) { credentialService.mintFor(any(), any()) }
    }

    @Test
    fun aCallerWhoseRealUidMatchesTheConfiguredAgentUidReachesTheCredentialService() {
        buildServer(agentUid = currentProcessUid())
        val expiresAt = Instant.parse("2026-01-01T00:00:00Z")
        every { credentialService.mintFor(workspaceId, "https://github.com/o/r.git") } returns
            WorkspaceGitCredentialService.Result.Success("ghs_it", expiresAt)

        val response = sendRequest(socketPathFor(workspaceId), """{"repoUrl":"https://github.com/o/r.git"}""")

        assertThat(response).contains("\"token\":\"ghs_it\"")
    }

    private fun socketPathFor(id: WorkspaceId): Path =
        directories.credentialSocketDirFor(id).resolve("git-credentials.sock")

    private fun sendRequest(
        socketPath: Path,
        requestLine: String,
    ): String {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(socketPath))
            Channels.newOutputStream(channel).write((requestLine + "\n").toByteArray())
            return Channels
                .newInputStream(channel)
                .bufferedReader()
                .readLine()
                .orEmpty()
        }
    }

    private fun currentProcessUid(): Long =
        ProcessBuilder("id", "-u")
            .start()
            .let { process ->
                val out =
                    process.inputStream
                        .bufferedReader()
                        .readText()
                        .trim()
                process.waitFor(PROCESS_WAIT_SECONDS, TimeUnit.SECONDS)
                out.toLong()
            }

    private fun soPeerCredSupported(): Boolean =
        runCatching {
            if (!System.getProperty("os.name").contains("Linux", ignoreCase = true)) return false
            val dir = Files.createTempDirectory("so-peercred-probe")
            val path = dir.resolve("probe.sock")
            java.nio.channels.ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { listener ->
                listener.bind(UnixDomainSocketAddress.of(path))
                SocketChannel.open(StandardProtocolFamily.UNIX).use { client ->
                    client.connect(UnixDomainSocketAddress.of(path))
                    listener.accept().use { accepted ->
                        accepted.supportedOptions().contains(ExtendedSocketOptions.SO_PEERCRED)
                    }
                }
            }
        }.getOrDefault(false)

    private fun passthroughScript(dir: Path): Path {
        val script = dir.resolve("run-as-agent-passthrough.sh")
        Files.writeString(script, "#!/bin/sh\nexec \"\$@\"\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script
    }

    private companion object {
        const val IMPOSSIBLE_UID = 999_999L
        const val PROCESS_WAIT_SECONDS = 5L
    }
}
