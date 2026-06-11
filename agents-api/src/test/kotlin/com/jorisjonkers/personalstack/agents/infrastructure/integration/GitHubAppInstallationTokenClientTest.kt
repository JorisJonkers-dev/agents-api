package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.RequestMatcher
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64

class GitHubAppInstallationTokenClientTest {
    private val keyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun pkcs8Pem(): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded)
        return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
    }

    private fun props(
        appId: String = "123456",
        key: String = pkcs8Pem(),
        bearer: String = "",
    ) = AgentRuntimeProperties(
        namespace = "agents-system",
        image = "img",
        serviceAccount = "sa",
        claudeCredentialsPvc = "c",
        codexCredentialsPvc = "x",
        githubDeployKeySecret = "k",
        githubAppId = appId,
        githubAppPrivateKey = key,
        githubAppTokenBearer = bearer,
    )

    @Test
    fun `enabled reflects whether both id and key are present`() {
        val rc = mockk<RestClient>()
        assertThat(GitHubAppInstallationTokenClient(rc, props()).enabled).isTrue()
        assertThat(GitHubAppInstallationTokenClient(rc, props(appId = "")).enabled).isFalse()
        assertThat(GitHubAppInstallationTokenClient(rc, props(key = "")).enabled).isFalse()
    }

    @Test
    fun `mint returns null and never calls GitHub when disabled`() {
        val rc = mockk<RestClient>() // unused: must short-circuit before any HTTP
        val client = GitHubAppInstallationTokenClient(rc, props(appId = ""))
        assertThat(client.mint("git@github.com:ExtraToast/agents.git")).isNull()
    }

    @Test
    fun `mint returns null for an unparseable repo URL`() {
        val rc = mockk<RestClient>()
        val client = GitHubAppInstallationTokenClient(rc, props())
        assertThat(client.mint("not-a-url")).isNull()
    }

    @Test
    fun `mint sends a valid RS256 app JWT and a repo-scoped token request, then returns the token`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = GitHubAppInstallationTokenClient(builder.build(), props(appId = "123456"))

        var capturedAuth: String? = null
        server
            .expect(requestTo("https://api.github.com/repos/ExtraToast/agents/installation"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(RequestMatcher { req -> capturedAuth = req.headers.getFirst(HttpHeaders.AUTHORIZATION) })
            .andRespond(withSuccess("""{"id":777}""", MediaType.APPLICATION_JSON))
        server
            .expect(requestTo("https://api.github.com/app/installations/777/access_tokens"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.repositories[0]").value("agents"))
            .andExpect(jsonPath("$.permissions.contents").value("write"))
            .andExpect(jsonPath("$.permissions.pull_requests").value("write"))
            .andExpect(jsonPath("$.permissions.actions").value("write"))
            .andExpect(jsonPath("$.permissions.issues").value("write"))
            .andExpect(jsonPath("$.permissions.workflows").value("write"))
            .andExpect(jsonPath("$.permissions.packages").value("read"))
            .andRespond(
                withSuccess(
                    """{"token":"ghs_abc","expires_at":"2026-06-02T15:00:00Z",""" +
                        """"permissions":{"contents":"write","pull_requests":"write","actions":"write",""" +
                        """"issues":"write","workflows":"write","packages":"read"}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val minted = client.mint("git@github.com:ExtraToast/agents.git")

        assertThat(minted).isNotNull
        assertThat(minted!!.token).isEqualTo("ghs_abc")
        assertThat(minted.expiresAt).isEqualTo(Instant.parse("2026-06-02T15:00:00Z"))
        server.verify()

        // The Authorization header must be a Bearer RS256 JWT signed by
        // the App key, asserting the App id as issuer.
        assertThat(capturedAuth).startsWith("Bearer ")
        val jwt = capturedAuth!!.removePrefix("Bearer ")
        val parts = jwt.split(".")
        assertThat(parts).hasSize(3)
        val verifier =
            Signature.getInstance("SHA256withRSA").apply {
                initVerify(keyPair.public as RSAPublicKey)
                update("${parts[0]}.${parts[1]}".toByteArray())
            }
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue()
        val payload = String(Base64.getUrlDecoder().decode(parts[1]))
        assertThat(payload).contains("\"iss\":\"123456\"")
    }

    @Test
    fun `narrowedPermissions flags absent and weaker grants, and passes a full grant`() {
        val requested = GitHubAppInstallationTokenClient.REQUESTED_PERMISSIONS

        // A metadata-only install grants none of the requested permissions.
        assertThat(GitHubAppInstallationTokenClient.narrowedPermissions(requested, emptyMap()))
            .containsExactly("actions", "contents", "issues", "packages", "pull_requests", "workflows")

        // contents granted read-only is weaker than the requested write.
        assertThat(
            GitHubAppInstallationTokenClient.narrowedPermissions(
                requested,
                mapOf(
                    "contents" to "read",
                    "pull_requests" to "write",
                    "actions" to "write",
                    "issues" to "write",
                    "workflows" to "write",
                    "packages" to "read",
                ),
            ),
        ).containsExactly("contents")

        // Exactly the requested set — nothing narrowed.
        assertThat(
            GitHubAppInstallationTokenClient.narrowedPermissions(
                requested,
                mapOf(
                    "contents" to "write",
                    "pull_requests" to "write",
                    "actions" to "write",
                    "issues" to "write",
                    "workflows" to "write",
                    "packages" to "read",
                ),
            ),
        ).isEmpty()
    }

    @Test
    fun `mint still returns the token when the App grant is narrower than requested`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = GitHubAppInstallationTokenClient(builder.build(), props())

        server
            .expect(requestTo("https://api.github.com/repos/ExtraToast/agents/installation"))
            .andRespond(withSuccess("""{"id":777}""", MediaType.APPLICATION_JSON))
        server
            .expect(requestTo("https://api.github.com/app/installations/777/access_tokens"))
            .andRespond(
                withSuccess(
                    // GitHub narrowed the token to metadata-only — no write perms.
                    """{"token":"ghs_narrow","expires_at":"2026-06-02T15:00:00Z","permissions":{"metadata":"read"}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val minted = client.mint("git@github.com:ExtraToast/agents.git")

        assertThat(minted).isNotNull
        assertThat(minted!!.token).isEqualTo("ghs_narrow")
        server.verify()
    }

    @Test
    fun `mint returns null when the App is not installed on the owner`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = GitHubAppInstallationTokenClient(builder.build(), props())

        server
            .expect(requestTo("https://api.github.com/repos/ExtraToast/agents/installation"))
            .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND))

        assertThat(client.mint("git@github.com:ExtraToast/agents.git")).isNull()
    }

    @Test
    fun `pkcs8Der accepts a PKCS#1 key (the format GitHub issues) and produces a signable key`() {
        val der = GitHubAppInstallationTokenClient.pkcs8Der(PKCS1_FIXTURE)
        val key =
            java.security.KeyFactory
                .getInstance("RSA")
                .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(der))
        // Signing must succeed — proves the wrapped DER is a valid key.
        val signed =
            Signature
                .getInstance("SHA256withRSA")
                .apply {
                    initSign(key)
                    update("payload".toByteArray())
                }.sign()
        assertThat(signed).isNotEmpty()
    }

    @Test
    fun `pkcs8Der passes a PKCS#8 key through unchanged`() {
        val der = GitHubAppInstallationTokenClient.pkcs8Der(pkcs8Pem())
        assertThat(der).isEqualTo(keyPair.private.encoded)
    }

    companion object {
        // Throwaway 2048-bit RSA key in PKCS#1 (`BEGIN RSA PRIVATE KEY`),
        // the format GitHub hands out. Used only to prove PKCS#1 parsing.
        private val PKCS1_FIXTURE =
            """
            -----BEGIN RSA PRIVATE KEY-----
            MIIEowIBAAKCAQEAtXLq8eqG5p1rpehLCAx6Af3t1rh7PGQqFp/zP+fpvTfl38Mo
            x/ydIzXP4hGFMDxbf+BO852G+l+cJeB6CR9lAejHDpX6SEsxBkIBX5yufQtJ0KGQ
            2/ugYRRFrzGo2A7L/7o1eN2LflaELHJqvbAVb+mo8pscoKo7lbywKjWm13fIctqb
            umLPW8A4reYB8OsMgRRgZiwFpBqSqkdy2xdKvUnSo2aOmY9suRt/CUSioFLoP9Lb
            uwCCNlP93SuFhqvXJ5GTPXESt81iRjQs+25n2fI3Mzmd1+YyjKRwnQYYNO/sqgiC
            Mof4YG17hOS6uWcs1bAQWO3NvNh0dv+ebfLcGQIDAQABAoIBAAQp/vpOR4pDUpUc
            H5yvrJ0fFrY2xZ09LzoVsZ9l0xdkkQHxmJ3+Thzgv0SQ4l2ZBQCKRUWR9+cHCq5T
            2HkdH1RL40WSa4v9LcLXAPEQx3BXMfp3urtRqvyPWooKubU7obLcsx1y+CCOG7pp
            Zcm0oMlQs2/d32pQfc2R5vkRAiMvspp+SM6TXlxDzinErTAELJHGJDEjLsDlJSLy
            8hj64JPHXKi4KRNo0AkBp25CQ4UX++Y9Rw8Zq4LANOpCPIzVkVsqXAet1IoPfh3V
            bLMimR/5GTMJN3gRZKozivZESD7gK8BjLbRK4zcE8sPt+BB8sU88kLH20O/j5ODT
            Oaiq0YECgYEA7JmXd7sxVYWLhrdBH12562/p/7zDvmfBAbyRwodENdiGizY/BeH6
            L0h+1MbKYSCh8ASUtEDEtCesMYRx9JAPWf195Xu/XzRdm+2GC1106ovYROLP4pYo
            0n7ULN3wDWuy3Ib0kWj3B8U2TGm4duX97dxfNcsgbu6ypBLeZqMyUp8CgYEAxFOq
            EXesjPaEn1AId5A8mpuZBYzrBS4RuyAUJiCYQ2uU6BfolxaUp1fQVdlWjIB04r8g
            qBgRJdC/AQiHYtLYtjslhI7+majgxBP+Cl90reOCmRmIn83ZKlXyyj82+eh8nai0
            8j01yidxbvTrlcpuYxIwLzrSUsUnucz2lRY+zkcCgYEAsOB57daRoR+/GS0ykCJf
            dXUq+DbEFzo1ffjc9xJsmdyPaM9a+ijgAi0uNB+Q+F+O8IJcMQ0igJQQFMyw7GYu
            M9ZgIgkLHj9lo8ZEKYbqetWlDoqJYxli10pdkFUyurXC9z4k4/gWhUaXuzRl5O03
            knTm8K40RvpHroU0ooJqgn0CgYB6xXMJv1PZRuPCmJLi6gDsEjeMAAaMU7Xk1fej
            rChrqOASj7j0mrtVNpXiyanU7ROrJChw1bQLeNGo/MNlKkM5Gh2pGp7eSnxcQcBQ
            jkbx4t8tjIkineCbF+pfTU680wTytqiI/3wesbG+2ExmfJOxQpN9RYR3HDFugF0G
            +EVISwKBgBfOSd6gTi/sdl3x0tClz8WNdP468HPAIqakYBxIiEbQtsBSAAk9J6T3
            kcX30xuRUQq4xu98uWblvYIlTC25Lul1fw+qkEutGL/62kXfC7ct4vLMmekXrQV+
            AmUYMa7rOGRyEbgyNUvBvvGXap5WRgbpWBBIk17Dh/XYFQeCMqiN
            -----END RSA PRIVATE KEY-----
            """.trimIndent()
    }
}
