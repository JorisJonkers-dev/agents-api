package com.jorisjonkers.personalstack.agents.infrastructure.credential

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AgentUidResolverTest {
    @Test
    fun `resolves the passwd name for the configured uid, and still accepts the raw uid`(
        @TempDir dir: Path,
    ) {
        val passwd = dir.resolve("passwd")
        Files.writeString(
            passwd,
            "root:x:0:0:root:/root:/bin/bash\n" +
                "api:x:10001:10001::/app:/usr/sbin/nologin\n" +
                "agent:x:10002:10002::/home/agent:/bin/bash\n",
        )

        val allowed = AgentUidResolver.resolveAllowedNames(10_002L, passwd)

        assertThat(allowed).containsExactlyInAnyOrder("agent", "10002")
    }

    @Test
    fun `falls back to the raw uid alone when passwd has no matching entry`(
        @TempDir dir: Path,
    ) {
        val passwd = dir.resolve("passwd")
        Files.writeString(passwd, "root:x:0:0:root:/root:/bin/bash\n")

        val allowed = AgentUidResolver.resolveAllowedNames(10_002L, passwd)

        assertThat(allowed).containsExactly("10002")
    }

    @Test
    fun `falls back to the raw uid alone when passwd is unreadable`(
        @TempDir dir: Path,
    ) {
        val missing = dir.resolve("does-not-exist")

        val allowed = AgentUidResolver.resolveAllowedNames(10_002L, missing)

        assertThat(allowed).containsExactly("10002")
    }
}
