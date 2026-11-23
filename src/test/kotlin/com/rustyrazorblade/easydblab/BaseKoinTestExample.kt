package com.rustyrazorblade.easydblab

import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsCredentials
import com.rustyrazorblade.easydblab.configuration.profile.aws.AwsDestinationAccess
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.AccountIdentity
import com.rustyrazorblade.easydblab.providers.aws.StsAccountIdentity
import com.rustyrazorblade.easydblab.providers.ssh.SSHConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.module.Module
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.iam.IamClient

/**
 * Example test demonstrating the use of BaseKoinTest.
 * This test verifies that the base test class correctly sets up Koin with core mocks.
 */
class BaseKoinTestExample :
    BaseKoinTest(),
    KoinComponent {
    // Inject dependencies that should be available from core modules
    private val accountIdentity: AccountIdentity by inject()
    private val iamClient: IamClient by inject()
    private val awsDestination: AwsDestinationAccess by inject()
    private val outputHandler: OutputHandler by inject()

    @Test
    fun `test core modules are available`() {
        // Verify that all core dependencies are injected
        assertThat(accountIdentity).isNotNull()
        assertThat(iamClient).isNotNull()
        assertThat(outputHandler).isNotNull()

        // The destination is read from the profile.yaml the test context wrote
        val destination = awsDestination.current()
        assertThat(destination.location.region).isEqualTo(TestProfiles.REGION)
        assertThat(destination.credentials).isEqualTo(
            AwsCredentials.AccessKey(TestProfiles.ACCESS_KEY_ID, TestProfiles.SECRET_ACCESS_KEY),
        )
    }

    @Test
    fun `test account identity is real over a mocked STS client`() {
        // The service logic runs for real while the mocked STS client prevents actual AWS API calls
        assertThat(accountIdentity).isInstanceOf(StsAccountIdentity::class.java)
    }
}

/**
 * Example test showing SSH is available by default.
 */
class BaseKoinTestWithSSH :
    BaseKoinTest(),
    KoinComponent {
    @Test
    fun `test SSH module is available by default`() {
        // SSH configuration should be available automatically from core modules
        val sshConfig: SSHConfiguration by inject()
        assertThat(sshConfig).isNotNull
        assertThat(sshConfig.keyPath).isEqualTo("test")
    }
}

/**
 * Example test with custom mock modules.
 */
class BaseKoinTestWithCustomMocks :
    BaseKoinTest(),
    KoinComponent {
    private val mockService = mock<SomeTestService>()

    override fun additionalTestModules(): List<Module> =
        listOf(
            org.koin.dsl.module {
                single { mockService }
            },
        )

    @Test
    fun `test custom mock is available`() {
        whenever(mockService.getValue()).thenReturn("mocked value")

        val service: SomeTestService by inject()
        assertThat(service).isSameAs(mockService)
        assertThat(service.getValue()).isEqualTo("mocked value")
    }

    interface SomeTestService {
        fun getValue(): String
    }
}
