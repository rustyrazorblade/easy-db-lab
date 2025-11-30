package com.rustyrazorblade.easycasslab

import com.rustyrazorblade.easycasslab.configuration.User
import com.rustyrazorblade.easycasslab.output.OutputHandler
import com.rustyrazorblade.easycasslab.providers.aws.AWS
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
    private val aws: AWS by inject()
    private val iamClient: IamClient by inject()
    private val user: User by inject()
    private val outputHandler: OutputHandler by inject()

    @Test
    fun `test core modules are available`() {
        // Verify that all core dependencies are injected
        assertThat(aws).isNotNull()
        assertThat(iamClient).isNotNull()
        assertThat(user).isNotNull()
        assertThat(outputHandler).isNotNull()

        // Verify user has expected test values
        assertThat(user.email).isEqualTo("test@example.com")
        assertThat(user.region).isEqualTo("us-west-2")
        assertThat(user.awsAccessKey).isEqualTo("test-access-key")
    }

    @Test
    fun `test AWS service is properly mocked`() {
        // The AWS service should be a real AWS instance with mocked IAM client
        // This ensures the service logic is tested while preventing actual AWS API calls
        assertThat(aws).isInstanceOf(AWS::class.java)
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
        val sshConfig: com.rustyrazorblade.easycasslab.providers.ssh.SSHConfiguration by inject()
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
