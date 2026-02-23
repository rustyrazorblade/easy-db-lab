package com.rustyrazorblade.easydblab

import com.rustyrazorblade.easydblab.commands.PicoCommand
import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.di.commandsModule
import com.rustyrazorblade.easydblab.di.prompterModule
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.AWS
import com.rustyrazorblade.easydblab.providers.aws.AWSClientFactory
import com.rustyrazorblade.easydblab.providers.ssh.DefaultSSHConfiguration
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.providers.ssh.SSHConfiguration
import com.rustyrazorblade.easydblab.providers.ssh.SSHConnectionProvider
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.aws.AMIValidator
import com.rustyrazorblade.easydblab.services.aws.AwsS3BucketService
import com.rustyrazorblade.easydblab.ssh.ISSHClient
import com.rustyrazorblade.easydblab.ssh.MockSSHClient
import com.rustyrazorblade.easydblab.ssh.Response
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse
import java.io.File
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
     * @param tempDir JUnit @TempDir directory for test isolation
     * @return List of core modules that should always be present in tests
     */
    fun coreTestModules(tempDir: File): List<Module> =
        listOf(
            testContextModule(tempDir),
            testAWSModule(),
            testOutputModule(),
            testEventBusModule(),
            testSSHModule(),
            testCommandExecutorModule(),
            commandsModule,
            prompterModule,
        )

    /**
     * Test module that provides a fresh Context instance for each test. Creates a new temporary
     * directory and test user configuration. This ensures test isolation and allows safe cleanup.
     *
     * @param tempDir JUnit @TempDir directory for test isolation
     * @return Module providing a test Context
     */
    fun testContextModule(tempDir: File): Module {
        val testContext = TestContextFactory.createTestContext(tempDir)
        // Use the testContext's workingDirectory to ensure tests don't affect the real project
        val contextFactory =
            ContextFactory(
                baseDirectory = testContext.easyDbLabUserDirectory,
                workingDirectory = testContext.workingDirectory,
            )
        return module {
            // Provide ContextFactory for tests that need it
            single { contextFactory }

            // Provide Context instance
            single<Context> { contextFactory.getDefault() }

            // Provide UserConfigProvider
            single {
                val context = get<Context>()
                UserConfigProvider(context.profileDir)
            }
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
            single {
                mock<StsClient>().also { mockSts ->
                    val response =
                        GetCallerIdentityResponse
                            .builder()
                            .account("123456789012")
                            .arn("arn:aws:iam::123456789012:user/test")
                            .userId("AIDATEST123")
                            .build()
                    whenever(mockSts.getCallerIdentity(any<GetCallerIdentityRequest>())).thenReturn(response)
                }
            }

            // AWS service with mocked IAM, S3, and STS clients
            // Using real AWS class with mocked clients ensures the service logic
            // is tested while preventing actual AWS API calls
            single { AWS(get<IamClient>(), get<S3Client>(), get<StsClient>()) }

            // Mock AWSClientFactory that returns the injected AWS service
            single<AWSClientFactory> {
                val aws = get<AWS>()
                object : AWSClientFactory {
                    override fun createAWSClient(
                        accessKey: String,
                        secret: String,
                        region: software.amazon.awssdk.regions.Region,
                    ): AWS = aws

                    override fun createAWSClientWithProfile(
                        profileName: String,
                        region: software.amazon.awssdk.regions.Region,
                    ): AWS = aws

                    override fun createEc2Client(
                        accessKey: String,
                        secret: String,
                        region: software.amazon.awssdk.regions.Region,
                    ): software.amazon.awssdk.services.ec2.Ec2Client = mock()

                    override fun createEc2ClientWithProfile(
                        profileName: String,
                        region: software.amazon.awssdk.regions.Region,
                    ): software.amazon.awssdk.services.ec2.Ec2Client = mock()
                }
            }

            // Mock AMIValidator to prevent AMI validation during tests
            single { mock<AMIValidator>() }

            // AwsS3BucketService using mocked AWS
            single { AwsS3BucketService(get<AWS>()) }
        }

    /**
     * Creates a test EventBus module. The EventBus is wired with a listener that
     * forwards events to the BufferedOutputHandler so existing test assertions
     * against outputHandler.messages continue to work.
     */
    fun testEventBusModule() =
        module {
            single {
                val outputHandler = get<OutputHandler>()
                val eventBus = EventBus()
                eventBus.addListener(
                    object : EventListener {
                        override fun onEvent(envelope: EventEnvelope) {
                            val text = envelope.event.toDisplayString()
                            if (envelope.event.isError()) {
                                outputHandler.handleError(text)
                            } else {
                                outputHandler.handleMessage(text)
                            }
                        }

                        override fun close() {}
                    },
                )
                eventBus
            }
        }

    /**
     * Creates a test CommandExecutor module with a simple pass-through implementation.
     * This directly calls command.call() without the full lifecycle to keep tests simple.
     */
    fun testCommandExecutorModule() =
        module {
            single<CommandExecutor> {
                object : CommandExecutor {
                    override fun <T : PicoCommand> execute(commandFactory: () -> T): Int {
                        val command = commandFactory()
                        return command.call()
                    }

                    override fun <T : PicoCommand> schedule(commandFactory: () -> T) {
                        // For tests, just execute immediately
                        val command = commandFactory()
                        command.call()
                    }
                }
            }
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
     * Creates a test Prompter module with a TestPrompter that returns predefined responses.
     * Use this when testing commands that require specific user input responses.
     *
     * @param responses Map of question text (or partial match) to response value
     * @return Module providing a TestPrompter with the given responses
     */
    fun testPrompterModule(responses: Map<String, String> = emptyMap()) =
        module {
            single<Prompter> { TestPrompter(responses) }
        }
}
