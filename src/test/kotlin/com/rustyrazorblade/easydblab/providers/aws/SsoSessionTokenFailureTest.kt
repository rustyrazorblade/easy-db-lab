package com.rustyrazorblade.easydblab.providers.aws

import com.rustyrazorblade.easydblab.kernel.ProviderErrorKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.ssooidc.SsoOidcClient
import software.amazon.awssdk.services.ssooidc.SsoOidcTokenProvider
import software.amazon.awssdk.services.ssooidc.internal.OnDiskTokenManager
import software.amazon.awssdk.services.ssooidc.model.CreateTokenRequest
import software.amazon.awssdk.services.ssooidc.model.InvalidGrantException
import software.amazon.awssdk.utils.internal.SystemSettingUtilsTestBackdoor
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * An `sso_session` profile's failures, raised by the SDK's real [SsoOidcTokenProvider] reading a
 * token cache: an expired session whose refresh is refused ("Token is expired") and a session that
 * was never logged in ("Unable to load SSO token"). Both must be credentials errors with the
 * `aws sso login` remedy, not ordinary execution errors.
 *
 * The SDK fixes its token cache directory from `HOME` once, when the class loads, so this class
 * points `HOME` at a temporary directory before that and checks the cache really lives there, so
 * it never reads or writes the operator's own `~/.aws`. Only the OIDC refresh call is mocked: it is
 * a network call, and the test needs it to fail as an expired refresh token does.
 */
internal class SsoSessionTokenFailureTest {
    companion object {
        @JvmStatic
        @TempDir
        lateinit var home: Path

        private lateinit var cacheDir: Path

        @JvmStatic
        @BeforeAll
        fun pointHomeAtTempDir() {
            SystemSettingUtilsTestBackdoor.addEnvironmentVariableOverride("HOME", home.toString())
            val field = OnDiskTokenManager::class.java.getDeclaredField("DEFAULT_TOKEN_LOCATION")
            field.isAccessible = true
            cacheDir = field.get(null) as Path
            check(cacheDir.startsWith(home)) { "SSO token cache is $cacheDir, outside the test HOME; refusing to touch it" }
            Files.createDirectories(cacheDir)
        }

        @JvmStatic
        @AfterAll
        fun restoreHome() {
            SystemSettingUtilsTestBackdoor.clearEnvironmentVariableOverrides()
        }
    }

    private val handler = AwsErrorHandler()

    private fun provider(
        sessionName: String,
        client: SsoOidcClient = mock(),
    ): SsoOidcTokenProvider =
        SsoOidcTokenProvider
            .builder()
            .sessionName(sessionName)
            .ssoOidcClient(client)
            .asyncTokenUpdateEnabled(false)
            .build()

    @Test
    fun `an expired sso_session token whose refresh is refused is a credentials error`() {
        val session = "edl-expired-${UUID.randomUUID()}"
        // A cached token as `aws sso login` writes it, expired an hour ago, with a refresh token.
        OnDiskTokenManager.create(cacheDir, session).use { cache ->
            cache.storeToken(
                software.amazon.awssdk.services.ssooidc.internal.SsoOidcToken
                    .builder()
                    .accessToken("access")
                    .expiresAt(Instant.now().minusSeconds(3600))
                    .refreshToken("refresh")
                    .clientId("client")
                    .clientSecret("client-secret")
                    .registrationExpiresAt(Instant.now().plusSeconds(3600))
                    .region("us-west-2")
                    .startUrl("https://example.awsapps.com/start")
                    .build(),
            )
        }
        val client = mock<SsoOidcClient>()
        whenever(client.createToken(any<CreateTokenRequest>()))
            .thenThrow(
                InvalidGrantException
                    .builder()
                    .statusCode(400)
                    .message("Refresh token has expired")
                    .build(),
            )

        val failure = provider(session, client).use { runCatching { it.resolveToken() }.exceptionOrNull() }

        assertThat(failure).hasMessageStartingWith("Token is expired")
        val description = handler.describe(failure!!)!!
        assertThat(description.kind).isEqualTo(ProviderErrorKind.CREDENTIALS)
        assertThat(description.remedy).contains("aws sso login")
    }

    @Test
    fun `an sso_session that was never logged in is a credentials error`() {
        val failure = provider("edl-missing-${UUID.randomUUID()}").use { runCatching { it.resolveToken() }.exceptionOrNull() }

        assertThat(failure).hasMessageStartingWith("Unable to load SSO token")
        assertThat(handler.describe(failure!!)!!.kind).isEqualTo(ProviderErrorKind.CREDENTIALS)
    }
}
