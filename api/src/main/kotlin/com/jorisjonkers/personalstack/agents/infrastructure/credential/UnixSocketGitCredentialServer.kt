package com.jorisjonkers.personalstack.agents.infrastructure.credential

import com.fasterxml.jackson.databind.ObjectMapper
import com.jorisjonkers.personalstack.agents.application.credential.WorkspaceGitCredentialService
import com.jorisjonkers.personalstack.agents.application.workspace.WorkspaceDirectoryService
import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.port.GitCredentialSocketManager
import jdk.net.ExtendedSocketOptions
import jdk.net.UnixDomainPrincipal
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One unix-domain-socket listener per Workspace at
 * `<workspaceDir>/.agents-api/git-credentials.sock`, newline-delimited
 * JSON, one request per connection (#63). The Workspace a request is
 * scoped to comes from *which socket it connected to*, never from
 * anything the client sends — see [WorkspaceGitCredentialService].
 *
 * The peer check is the load-bearing control: [GitCredentialPeerAuthorization]
 * rejects anything but the configured agent uid, including `api` and
 * root, before a request line is even read. The socket file's own mode
 * (`rw-rw----`, see [WorkspaceDirectoryService.ensureCredentialSocketDirCreated]
 * for why its group ends up `agent`) is defence in depth only — it does
 * not stop root.
 */
@Component
class UnixSocketGitCredentialServer(
    private val props: AgentRuntimeProperties,
    private val directories: WorkspaceDirectoryService,
    private val credentialService: WorkspaceGitCredentialService,
    private val objectMapper: ObjectMapper,
) : GitCredentialSocketManager,
    DisposableBean {
    private val log = LoggerFactory.getLogger(UnixSocketGitCredentialServer::class.java)
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val listeners = ConcurrentHashMap<WorkspaceId, Listener>()
    private val allowedPeerNames: Set<String> by lazy { AgentUidResolver.resolveAllowedNames(props.agentUid) }

    private data class Listener(
        val channel: ServerSocketChannel,
        val socketPath: Path,
    )

    override fun ensureStarted(workspaceId: WorkspaceId) {
        listeners.computeIfAbsent(workspaceId) { startListening(it) }
    }

    override fun stop(workspaceId: WorkspaceId) {
        listeners.remove(workspaceId)?.let(::closeListener)
    }

    override fun destroy() {
        listeners.keys.toList().forEach(::stop)
        executor.shutdownNow()
    }

    private fun startListening(workspaceId: WorkspaceId): Listener {
        val dir = directories.ensureCredentialSocketDirCreated(workspaceId)
        val socketPath = dir.resolve(SOCKET_FILE_NAME)
        Files.deleteIfExists(socketPath)
        val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        channel.bind(UnixDomainSocketAddress.of(socketPath))
        runCatching {
            Files.setPosixFilePermissions(socketPath, PosixFilePermissions.fromString(SOCKET_FILE_PERMISSIONS))
        }.onFailure { log.warn("could not chmod git-credential socket for workspace {}: {}", workspaceId, it.message) }
        val listener = Listener(channel, socketPath)
        executor.submit { acceptLoop(workspaceId, channel) }
        log.info("git-credential socket listening for workspace {} at {}", workspaceId.value, socketPath)
        return listener
    }

    private fun closeListener(listener: Listener) {
        runCatching { listener.channel.close() }
        runCatching { Files.deleteIfExists(listener.socketPath) }
    }

    private fun acceptLoop(
        workspaceId: WorkspaceId,
        channel: ServerSocketChannel,
    ) {
        while (channel.isOpen) {
            val client = runCatching { channel.accept() }.getOrNull() ?: return
            executor.submit { serve(workspaceId, client) }
        }
    }

    private fun serve(
        workspaceId: WorkspaceId,
        client: SocketChannel,
    ) {
        client.use {
            val response =
                runCatching { handle(workspaceId, it) }
                    .getOrElse { ex ->
                        log.warn("git-credential request for workspace {} failed", workspaceId.value, ex)
                        errorJson("internal error")
                    }
            writeLine(it, response)
        }
    }

    private fun handle(
        workspaceId: WorkspaceId,
        client: SocketChannel,
    ): String {
        val peerName = peerPrincipalName(client)
        if (!GitCredentialPeerAuthorization.isAuthorized(peerName, allowedPeerNames)) {
            log.warn("rejected git-credential connection for workspace {} from peer {}", workspaceId.value, peerName)
            return errorJson("unauthorized peer")
        }
        val repoUrl = readRepoUrl(client) ?: return errorJson("malformed request")
        return when (val result = credentialService.mintFor(workspaceId, repoUrl)) {
            is WorkspaceGitCredentialService.Result.Success -> successJson(result.token, result.expiresAt)
            is WorkspaceGitCredentialService.Result.Denied -> errorJson(result.reason)
        }
    }

    private fun peerPrincipalName(client: SocketChannel): String? =
        runCatching {
            (client.getOption(ExtendedSocketOptions.SO_PEERCRED) as UnixDomainPrincipal).user().name
        }.getOrNull()

    private fun readRepoUrl(client: SocketChannel): String? {
        val line = Channels.newInputStream(client).bufferedReader(StandardCharsets.UTF_8).readLine() ?: return null
        val request = runCatching { objectMapper.readValue(line, WireRequest::class.java) }.getOrNull()
        return request?.repoUrl?.takeIf { it.isNotBlank() }
    }

    private fun successJson(
        token: String,
        expiresAt: Instant,
    ): String = objectMapper.writeValueAsString(WireSuccess(token, expiresAt))

    private fun errorJson(reason: String): String = objectMapper.writeValueAsString(WireError(reason))

    private fun writeLine(
        client: SocketChannel,
        line: String,
    ) {
        val buffer = ByteBuffer.wrap((line + "\n").toByteArray(StandardCharsets.UTF_8))
        while (buffer.hasRemaining()) client.write(buffer)
    }

    private data class WireRequest(
        val repoUrl: String? = null,
    )

    private data class WireSuccess(
        val token: String,
        val expiresAt: Instant,
    )

    private data class WireError(
        val error: String,
    )

    private companion object {
        const val SOCKET_FILE_NAME = "git-credentials.sock"
        const val SOCKET_FILE_PERMISSIONS = "rw-rw----"
    }
}
