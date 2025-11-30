package com.rustyrazorblade.easycasslab

import com.rustyrazorblade.easycasslab.configuration.Host
import com.rustyrazorblade.easycasslab.configuration.TFState
import com.rustyrazorblade.easycasslab.configuration.User
import com.rustyrazorblade.easycasslab.di.TFStateProvider
import com.rustyrazorblade.easycasslab.di.contextModule
import com.rustyrazorblade.easycasslab.output.BufferedOutputHandler
import com.rustyrazorblade.easycasslab.output.OutputHandler
import com.rustyrazorblade.easycasslab.providers.aws.AMIValidator
import com.rustyrazorblade.easycasslab.providers.aws.AWS
import com.rustyrazorblade.easycasslab.providers.ssh.DefaultSSHConfiguration
import com.rustyrazorblade.easycasslab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easycasslab.providers.ssh.SSHConfiguration
import com.rustyrazorblade.easycasslab.providers.ssh.SSHConnectionProvider
import com.rustyrazorblade.easycasslab.ssh.ISSHClient
import com.rustyrazorblade.easycasslab.ssh.MockSSHClient
import com.rustyrazorblade.easycasslab.ssh.Response
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.mock
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sts.StsClient
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Path

/** Test modules for Koin dependency injection in tests. */
object TestModules {
    /**
     * Core test modules that should always be loaded in tests. These modules provide mocks for
     * services that should NEVER make real calls during testing.
     *
     * Includes:
     * - AWS services (to prevent real API calls and charges)
     * - Output handlers (to capture output for testing)
     * - SSH services (to prevent real SSH connections)
     *
     * @return List of core modules that should always be present in tests
     */
    fun coreTestModules(): List<Module> =
        listOf(
            testContextModule(),
            testAWSModule(),
            testOutputModule(),
            testSSHModule(),
            testTerraformModule(),
        )

    /**
     * Test module that provides a fresh Context instance for each test. Creates a new temporary
     * directory and test user configuration. This ensures test isolation and allows safe cleanup.
     *
     * @return Module providing a test Context
     */
    fun testContextModule(): Module {
        val testContext = TestContextFactory.createTestContext()
        val contextFactory = ContextFactory(testContext.easycasslabUserDirectory)
        return module {
            // Include all context module definitions
            includes(contextModule(contextFactory))

            // Also provide Context directly for tests that need it
            single { contextFactory.getDefault() }
        }
    }

    /**
     * Creates a test output module with buffered output handler. This captures all output for
     * verification in tests.
     */
    fun testOutputModule() =
        module {
            // Use CompositeOutputHandler with BufferedOutputHandler for tests
            // This matches the production pattern but uses a test-friendly handler
            // Use single to ensure all components get the same instance within a test
            single<OutputHandler> { BufferedOutputHandler() }
        }

    /**
     * Creates a test AWS module with mock implementations. This ensures no real AWS API calls are
     * made during testing.
     */
    fun testAWSModule() =
        module {
            // Mock User configuration for AWS
            single {
                User(
                    email = "test@example.com",
                    region = "us-west-2",
                    keyName = "test-key",
                    sshKeyPath = "test-key-path",
                    awsProfile = "",
                    awsAccessKey = "test-access-key",
                    awsSecret = "test-secret",
                    axonOpsOrg = "",
                    axonOpsKey = "",
                )
            }

            // Mock individual AWS SDK clients
            single { mock<IamClient>() }
            single { mock<S3Client>() }
            single { mock<StsClient>() }

            // AWS service with mocked IAM, S3, and STS clients
            // Using real AWS class with mocked clients ensures the service logic
            // is tested while preventing actual AWS API calls
            single { AWS(get<IamClient>(), get<S3Client>(), get<StsClient>(), get()) }

            // Mock AMIValidator to prevent AMI validation during tests
            single { mock<AMIValidator>() }
        }

    /** Creates a test SSH module with mock implementations. */
    fun testSSHModule() =
        module {
            // Mock SSH configuration
            single<SSHConfiguration> { DefaultSSHConfiguration(keyPath = "test") }

            // Mock SSH connection provider
            single<SSHConnectionProvider> {
                object : SSHConnectionProvider {
                    private val mockClient = MockSSHClient()

                    override fun getConnection(host: Host): ISSHClient = mockClient

                    override fun stop() {
                        // No-op for mock implementation
                    }
                }
            }

            // Mock remote operations service
            factory<RemoteOperationsService> {
                object : RemoteOperationsService {
                    override fun executeRemotely(
                        host: Host,
                        command: String,
                        output: Boolean,
                        secret: Boolean,
                    ): Response = Response("")

                    override fun upload(
                        host: Host,
                        local: Path,
                        remote: String,
                    ) {
                        // No-op for mock implementation
                    }

                    override fun uploadDirectory(
                        host: Host,
                        localDir: File,
                        remoteDir: String,
                    ) {
                        // No-op for mock implementation
                    }

                    override fun uploadDirectory(
                        host: Host,
                        version: Version,
                    ) {
                        // No-op for mock implementation
                    }

                    override fun download(
                        host: Host,
                        remote: String,
                        local: Path,
                    ) {
                        // No-op for mock implementation
                    }

                    override fun downloadDirectory(
                        host: Host,
                        remoteDir: String,
                        localDir: File,
                        includeFilters: List<String>,
                        excludeFilters: List<String>,
                    ) {
                        // No-op for mock implementation
                    }

                    override fun getRemoteVersion(
                        host: Host,
                        inputVersion: String,
                    ): Version =
                        if (inputVersion == "current") {
                            Version("/usr/local/cassandra/5.0")
                        } else {
                            Version.fromString(inputVersion)
                        }
                }
            }
        }

    /**
     * Creates a test Terraform module with mock implementations. This provides a mock
     * TFStateProvider that returns empty state for testing.
     */
    fun testTerraformModule() =
        module {
            // Mock TFStateProvider
            single<TFStateProvider> {
                object : TFStateProvider {
                    override fun parseFromFile(file: File): TFState {
                        // Return a mock TFState with minimal valid JSON
                        val json =
                            """
                            {
                                "version": 4,
                                "terraform_version": "1.0.0",
                                "serial": 1,
                                "lineage": "test",
                                "outputs": {},
                                "resources": []
                            }
                            """.trimIndent()
                        return TFState(get(), ByteArrayInputStream(json.toByteArray()))
                    }

                    override fun parseFromStream(stream: InputStream): TFState = TFState(get(), stream)

                    override fun getDefault(): TFState = parseFromFile(File("terraform.tfstate"))
                }
            }

            // Provide a factory for TFState (for backward compatibility)
            factory { get<TFStateProvider>().getDefault() }
        }
}
