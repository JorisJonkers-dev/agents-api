package com.jorisjonkers.personalstack.agents.infrastructure.login

import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.port.AgentLoginStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class HomeVolumeAgentLoginStoreTest {
    @Test
    fun `reports an Agent Login for each provider whose CLI has written its own login file`(
        @TempDir home: Path,
    ) {
        writeLogin(home, ".claude/.credentials.json")
        val store = HomeVolumeAgentLoginStore(home)

        assertThat(store.isPresent(WorkspaceAgentKind.CLAUDE)).isTrue()
        assertThat(store.isPresent(WorkspaceAgentKind.CODEX)).isFalse()
    }

    @Test
    fun `reports a Codex Agent Login from its own auth file`(
        @TempDir home: Path,
    ) {
        writeLogin(home, ".codex/auth.json")
        val store = HomeVolumeAgentLoginStore(home)

        assertThat(store.isPresent(WorkspaceAgentKind.CODEX)).isTrue()
    }

    // An empty file is what a half-written or truncated login leaves behind.
    // Reporting it as present sends the user to a CLI that will fail rather
    // than to the sign-in hint, which is the worse of the two wrong answers.
    @Test
    fun `does not report an Agent Login for an empty login file`(
        @TempDir home: Path,
    ) {
        val file = home.resolve(".claude/.credentials.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, "")

        assertThat(HomeVolumeAgentLoginStore(home).isPresent(WorkspaceAgentKind.CLAUDE)).isFalse()
    }

    // Criterion 9 of fleet-infra#326: a developer laptop and the integration
    // tests have no home volume mounted. An absent directory is "no login
    // yet", never a failed start.
    @Test
    fun `reports no Agent Login when the home volume is not mounted at all`(
        @TempDir dir: Path,
    ) {
        val store = HomeVolumeAgentLoginStore(dir.resolve("absent"))

        assertThat(store.isPresent(WorkspaceAgentKind.CLAUDE)).isFalse()
        assertThat(store.isPresent(WorkspaceAgentKind.CODEX)).isFalse()
    }

    // SHELL is an Agent Kind with no provider login at all. Asking is not an
    // error, and the answer is never "sign in".
    @Test
    fun `reports no Agent Login requirement for a Shell Agent Session`(
        @TempDir home: Path,
    ) {
        writeLogin(home, ".claude/.credentials.json")

        assertThat(HomeVolumeAgentLoginStore(home).isPresent(WorkspaceAgentKind.SHELL)).isFalse()
    }

    @Test
    fun `summarises every provider that can hold an Agent Login`(
        @TempDir home: Path,
    ) {
        writeLogin(home, ".codex/auth.json")

        assertThat(HomeVolumeAgentLoginStore(home).statuses())
            .containsExactly(
                AgentLoginStore.AgentLoginStatus(WorkspaceAgentKind.CLAUDE, present = false),
                AgentLoginStore.AgentLoginStatus(WorkspaceAgentKind.CODEX, present = true),
            )
    }

    private fun writeLogin(
        home: Path,
        relative: String,
    ) {
        val file = home.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, """{"token":"x"}""")
    }
}
