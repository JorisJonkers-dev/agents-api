package com.jorisjonkers.personalstack.agents.credential

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists

/**
 * Exercises `container/git-credential-agents-api` — the real shell script,
 * as `sh` and `python3` will run it in the image, not a Kotlin
 * re-implementation of it — against a **real unix domain socket** speaking
 * exactly the wire protocol [com.jorisjonkers.personalstack.agents.infrastructure.credential.UnixSocketGitCredentialServer]
 * speaks (newline-delimited JSON, one request per connection).
 *
 * This proves: the script finds a socket by walking up from its cwd,
 * speaks git's credential-helper protocol on stdin/stdout correctly, uses
 * python3 for the actual socket round-trip, and — the load-bearing case —
 * stays silent and exits 0 on every failure path (no socket, a denial, a
 * malformed reply) rather than aborting git with a non-zero exit.
 *
 * This does NOT prove: that `git -c credential.helper=agents-api clone`
 * actually invokes this script inside a real Agent Session, that it
 * resolves via `PATH=/usr/local/bin:/usr/bin:/bin` the way run-as-agent
 * leaves it, or that the *real* server
 * ([com.jorisjonkers.personalstack.agents.infrastructure.credential.UnixSocketGitCredentialServer])
 * replies the way this test's hand-rolled one does — only that this
 * script's side of the wire protocol is correct against a socket that
 * receives and replies exactly as that server does.
 *
 * Skipped, not failed, when `sh` or `python3` is missing — mirrors
 * [com.jorisjonkers.personalstack.agents.shell.ShellAgentSessionIntegrationTest]
 * gating on tmux.
 */
@Tag("integration")
class GitCredentialAgentsApiHelperIntegrationTest {
    private lateinit var workspaceDir: Path
    private lateinit var scriptPath: Path
    private lateinit var repoCwd: Path

    @BeforeEach
    fun setUp() {
        assumeTrue(commandOnPath("sh"), "sh is not installed on this runner — skipping")
        assumeTrue(commandOnPath("python3"), "python3 is not installed on this runner — skipping")
        scriptPath =
            locateScript()
                ?: error("container/git-credential-agents-api not found from ${System.getProperty("user.dir")}")
        // Not JUnit's @TempDir: that resolves under the JVM's default tmpdir,
        // which on this machine is deep enough to blow AF_UNIX's ~104-byte
        // sun_path limit — a macOS/JVM path-length artifact, not something
        // the real container (short /workspaces/<id> paths) ever hits.
        workspaceDir = Files.createTempDirectory(Path.of("/tmp"), "gc-it-")
        Files.createDirectories(workspaceDir.resolve(".agents-api"))
        // Deep enough to prove the walk-up actually walks, not just checks $PWD.
        repoCwd = Files.createDirectories(workspaceDir.resolve("my-repo/src/main"))
    }

    @AfterEach
    fun tearDown() {
        if (this::workspaceDir.isInitialized) {
            Files
                .walk(workspaceDir)
                .sorted(Comparator.reverseOrder())
                .forEach { it.deleteIfExists() }
        }
    }

    @Test
    fun getPrintsTheMintedCredentialsWhenTheSocketRepliesSuccessfully() {
        withMockServer(socketPath()) { """{"token":"ghs_it_token","expiresAt":"2026-01-01T00:00:00Z"}""" }.use {
            val result = runHelper("get", CREDENTIAL_REQUEST_LINES)

            assertThat(result.exitCode).isEqualTo(0)
            assertThat(result.stdout).isEqualTo("username=x-access-token\npassword=ghs_it_token\n")
            // python3's json.dumps separates key/value with ": " by default —
            // valid JSON either way, and the real server's Jackson parser
            // does not care, but this is what actually goes over the wire.
            assertThat(it.lastRequest).isEqualTo("""{"repoUrl": "https://github.com/owner/repo.git"}""")
        }
    }

    @Test
    fun getPrintsNothingAndExitsZeroWhenTheSocketDenies() {
        withMockServer(socketPath()) { """{"error":"denied"}""" }.use {
            val result = runHelper("get", CREDENTIAL_REQUEST_LINES)

            assertThat(result.exitCode).isEqualTo(0)
            assertThat(result.stdout).isEmpty()
        }
    }

    @Test
    fun getPrintsNothingAndExitsZeroWhenTheSocketReplyIsMalformed() {
        withMockServer(socketPath()) { "not json at all" }.use {
            val result = runHelper("get", CREDENTIAL_REQUEST_LINES)

            assertThat(result.exitCode).isEqualTo(0)
            assertThat(result.stdout).isEmpty()
        }
    }

    @Test
    fun getPrintsNothingAndExitsZeroWhenNoSocketExists() {
        val result = runHelper("get", CREDENTIAL_REQUEST_LINES)

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout).isEmpty()
    }

    @Test
    fun storeAndEraseExitZeroSilentlyWithoutTouchingTheSocket() {
        val store = runHelper("store", "url=https://x-access-token@github.com/owner/repo.git\n\n")
        val erase = runHelper("erase", "url=https://x-access-token@github.com/owner/repo.git\n\n")

        assertThat(store.exitCode).isEqualTo(0)
        assertThat(store.stdout).isEmpty()
        assertThat(erase.exitCode).isEqualTo(0)
        assertThat(erase.stdout).isEmpty()
    }

    private fun socketPath(): Path = workspaceDir.resolve(".agents-api/git-credentials.sock")

    private data class HelperResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun runHelper(
        operation: String,
        stdin: String,
    ): HelperResult {
        val process =
            ProcessBuilder("sh", scriptPath.toString(), operation)
                .directory(repoCwd.toFile())
                .start()
        process.outputStream.use { it.write(stdin.toByteArray()) }
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(PROCESS_WAIT_SECONDS, TimeUnit.SECONDS)
        check(finished) { "git-credential-agents-api did not exit within ${PROCESS_WAIT_SECONDS}s" }
        return HelperResult(process.exitValue(), stdout, stderr)
    }

    /**
     * A one-shot real unix-socket server: accepts a single connection,
     * reads the newline-JSON request line, hands it to [reply] and writes
     * back whatever that returns, exactly like
     * [com.jorisjonkers.personalstack.agents.infrastructure.credential.UnixSocketGitCredentialServer]
     * does for one request.
     */
    private fun withMockServer(
        path: Path,
        reply: (String) -> String,
    ): MockCredentialSocketServer {
        Files.deleteIfExists(path)
        val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        channel.bind(UnixDomainSocketAddress.of(path))
        val server = MockCredentialSocketServer(channel, path)
        val executor = Executors.newSingleThreadExecutor()
        server.accepted =
            executor.submit {
                channel.accept().use { client ->
                    val line =
                        Channels
                            .newInputStream(client)
                            .bufferedReader()
                            .readLine()
                            .orEmpty()
                    server.lastRequest = line
                    val response = reply(line) + "\n"
                    Channels.newOutputStream(client).write(response.toByteArray())
                }
            }
        server.executor = executor
        return server
    }

    private class MockCredentialSocketServer(
        private val channel: ServerSocketChannel,
        private val path: Path,
    ) : AutoCloseable {
        var lastRequest: String = ""
        lateinit var accepted: java.util.concurrent.Future<*>
        lateinit var executor: java.util.concurrent.ExecutorService

        override fun close() {
            accepted.get(PROCESS_WAIT_SECONDS, TimeUnit.SECONDS)
            executor.shutdownNow()
            runCatching { channel.close() }
            runCatching { Files.deleteIfExists(path) }
        }
    }

    private fun commandOnPath(command: String): Boolean =
        runCatching {
            ProcessBuilder(command, "--version").redirectErrorStream(true).start().waitFor(
                PROCESS_WAIT_SECONDS,
                TimeUnit.SECONDS,
            )
        }.getOrDefault(false)

    private fun locateScript(): Path? {
        val userDir = Path.of(System.getProperty("user.dir"))
        return listOf(
            userDir.resolve("container/git-credential-agents-api"),
            userDir.resolve("../container/git-credential-agents-api"),
        ).map { it.normalize() }.firstOrNull(Files::isRegularFile)
    }

    private companion object {
        const val PROCESS_WAIT_SECONDS = 5L
        const val CREDENTIAL_REQUEST_LINES = "protocol=https\nhost=github.com\npath=owner/repo.git\n\n"
    }
}
