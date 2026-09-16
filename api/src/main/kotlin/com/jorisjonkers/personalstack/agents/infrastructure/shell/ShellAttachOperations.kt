package com.jorisjonkers.personalstack.agents.infrastructure.shell

import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import org.springframework.stereotype.Component

/**
 * The tmux operations a live browser Attach needs: a snapshot to paint
 * on connect, a tailer to stream new output, resize, and keystroke
 * input. Split out of [com.jorisjonkers.personalstack.agents.infrastructure.integration.InContainerAgentGatewayClient]
 * so that class stays within the class-size budget, and so
 * [com.jorisjonkers.personalstack.agents.infrastructure.ws.LocalShellAttachSupport]
 * depends only on the attach-shaped surface, not the whole gateway port.
 */
@Component
class ShellAttachOperations(
    private val tmux: InContainerTmuxClient,
    private val registry: ShellSessionRegistry,
) {
    fun sendInput(
        workspace: Workspace,
        gatewayAgentId: String,
        input: String,
        enter: Boolean,
    ) {
        tmux.sendKeys(requireSession(workspace, gatewayAgentId).tmuxSessionName, input, enter)
    }

    fun capture(
        workspace: Workspace,
        gatewayAgentId: String,
    ): String = tmux.capture(requireSession(workspace, gatewayAgentId).tmuxSessionName)

    /** Ansi-escaped, CRLF-normalised screen snapshot for a fresh Attach. */
    fun snapshot(
        workspace: Workspace,
        gatewayAgentId: String,
    ): String =
        tmux
            .captureWithEscapes(requireSession(workspace, gatewayAgentId).tmuxSessionName)
            .replace("\r\n", "\n")
            .replace("\n", "\r\n")

    fun resize(
        workspace: Workspace,
        gatewayAgentId: String,
        cols: Int,
        rows: Int,
    ) {
        tmux.resize(requireSession(workspace, gatewayAgentId).tmuxSessionName, cols, rows)
    }

    /** Starts a [LogTailer] on the session's pipe-pane log; caller closes it on detach. */
    fun startTailer(
        workspace: Workspace,
        gatewayAgentId: String,
        onText: (String) -> Unit,
    ): AutoCloseable {
        val tailer = LogTailer(requireSession(workspace, gatewayAgentId).logFile, onText = onText)
        tailer.start()
        return tailer
    }

    // Never confirms whether a differently-scoped id exists — a mismatch and
    // an absent id both surface as "no such Agent Session in this Workspace".
    fun requireSession(
        workspace: Workspace,
        gatewayAgentId: String,
    ): ShellSession =
        registry.find(workspace.id, gatewayAgentId)
            ?: throw NoSuchElementException("no shell agent $gatewayAgentId in workspace ${workspace.id}")
}
