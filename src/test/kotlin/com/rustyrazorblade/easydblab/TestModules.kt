package com.rustyrazorblade.easydblab

import com.rustyrazorblade.easydblab.configuration.Host
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.configuration.UserConfigProvider
import com.rustyrazorblade.easydblab.di.commandsModule
import com.rustyrazorblade.easydblab.di.prompterModule
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kernel.PicoCommand
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.NodeOutput
import com.rustyrazorblade.easydblab.output.NodeOutputTail
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.output.RecordingNodeOutput
import com.rustyrazorblade.easydblab.providers.aws.AWSClientFactory
import com.rustyrazorblade.easydblab.providers.aws.AccountIdentity
import com.rustyrazorblade.easydblab.providers.aws.AwsErrorHandler
import com.rustyrazorblade.easydblab.providers.aws.AwsIamRoles
import com.rustyrazorblade.easydblab.providers.aws.IamRoles
import com.rustyrazorblade.easydblab.providers.aws.InstanceRolePolicies
import com.rustyrazorblade.easydblab.providers.aws.S3BucketAdmin
import com.rustyrazorblade.easydblab.providers.aws.StsAccountIdentity
import com.rustyrazorblade.easydblab.providers.ssh.DefaultSSHConfiguration
import com.rustyrazorblade.easydblab.providers.ssh.RemoteOperationsService
import com.rustyrazorblade.easydblab.providers.ssh.SSHConfiguration
import com.rustyrazorblade.easydblab.providers.ssh.SSHConnectionProvider
import com.rustyrazorblade.easydblab.proxy.ProcessSocksProxyService
import com.rustyrazorblade.easydblab.proxy.ProxyEnvFile
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.proxy.SshProcessLauncher
import com.rustyrazorblade.easydblab.proxy.ToolWrapperInstaller
import com.rustyrazorblade.easydblab.services.CassandraBuildCatalog
import com.rustyrazorblade.easydblab.services.CommandExecutor
import com.rustyrazorblade.easydblab.services.ExternalIpService
import com.rustyrazorblade.easydblab.services.TailnetCleanup
import com.rustyrazorblade.easydblab.services.TailscaleService
import com.rustyrazorblade.easydblab.services.TeardownPlanner
import com.rustyrazorblade.easydblab.services.aws.AMIValidator
import com.rustyrazorblade.easydblab.services.aws.AwsKeyPairService
import com.rustyrazorblade.easydblab.services.aws.AwsS3BucketService
import com.rustyrazorblade.easydblab.services.aws.DataBucketTeardown
import com.rustyrazorblade.easydblab.ssh.ISSHClient
import com.rustyrazorblade.easydblab.ssh.MockSSHClient
import com.rustyrazorblade.easydblab.ssh.Response
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DescribeKeyPairsRequest
import software.amazon.awssdk.services.ec2.model.DescribeKeyPairsResponse
import software.amazon.awssdk.services.ec2.model.KeyPairInfo
import software.amazon.awssdk.services.ec2.model.Tag
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetBucketTaggingRequest
import software.amazon.awssdk.services.s3.model.GetBucketTaggingResponse
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse
import java.io.File
import java.nio.file.Path
import software.amazon.awssdk.services.s3.model.Tag as S3Tag

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
                appHome = testContext.appHome,
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

            // Real: it only writes and removes the packaged tool wrappers in a workspace's bin/.
            single { ToolWrapperInstaller() }

            // Real, over the test workspace, so stopping a tunnel (`down`, `socks stop`) runs the
            // real state file and env file code. Its ssh launcher refuses to start anything: a test
            // that needs a tunnel to start registers its own service with a fake launcher.
            single { ProxyEnvFile(get<Context>().workingDirectory) }
            single<SocksProxyService> {
                ProcessSocksProxyService(
                    get(),
                    { _, _, _ -> false },
                    get(),
                    processLauncher = SshProcessLauncher { _, _ -> error("tests never launch ssh") },
                    envFile = get(),
                )
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
            single { RecordingNodeOutput() }
            single<NodeOutput> { get<RecordingNodeOutput>() }
            single<NodeOutputTail> { get<RecordingNodeOutput>() }
        }

    /**
     * A mocked EC2 client whose key pair already carries both marker tags, so the key-pair
     * backfill `up` and the image builds run finds nothing to add.
     */
    private fun markedKeyPairEc2(): Ec2Client =
        mock<Ec2Client>().also { ec2 ->
            val keyPair =
                KeyPairInfo
                    .builder()
                    .keyName("test-key")
                    .keyPairId("key-test")
                    .tags(
                        Constants.Vpc.MARKER_TAGS.map { (key, value) ->
                            Tag
                                .builder()
                                .key(key)
                                .value(value)
                                .build()
                        },
                    ).build()
            whenever(ec2.describeKeyPairs(any<DescribeKeyPairsRequest>()))
                .thenReturn(DescribeKeyPairsResponse.builder().keyPairs(keyPair).build())
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
            // A bucket that exists answers GetBucketTagging; an empty set is what a fresh one returns.
            single {
                mock<S3Client>().also { s3 ->
                    whenever(s3.getBucketTagging(any<GetBucketTaggingRequest>()))
                        .thenReturn(GetBucketTaggingResponse.builder().tagSet(emptyList<S3Tag>()).build())
                }
            }
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

            // Account identity and IAM roles over the mocked STS and IAM clients: the real logic
            // runs while no AWS API call is made
            single<AccountIdentity> { StsAccountIdentity(get<StsClient>()) }
            single<IamRoles> { AwsIamRoles(get<IamClient>(), get<AccountIdentity>()) }
            single { InstanceRolePolicies(get<IamRoles>()) }

            // Mock AWSClientFactory that returns the injected account identity
            single<AWSClientFactory> {
                val identity = get<AccountIdentity>()
                object : AWSClientFactory {
                    override fun createAccountIdentity(
                        accessKey: String,
                        secret: String,
                        region: String,
                    ): AccountIdentity = identity

                    override fun createAccountIdentityWithProfile(
                        profileName: String,
                        region: String,
                    ): AccountIdentity = identity

                    override fun createEc2Client(
                        accessKey: String,
                        secret: String,
                        region: String,
                    ): Ec2Client = markedKeyPairEc2()

                    override fun createEc2ClientWithProfile(
                        profileName: String,
                        region: String,
                    ): Ec2Client = markedKeyPairEc2()
                }
            }

            // Mock AMIValidator to prevent AMI validation during tests
            single { mock<AMIValidator>() }

            // AwsS3BucketService over the mocked S3 client
            single { AwsS3BucketService(get<S3Client>(), get<AccountIdentity>()) }
            single<S3BucketAdmin> { get<AwsS3BucketService>() }

            // The jobs `down` delegates to, built when the command first asks for one so a test
            // can still swap the Tailscale service, the bucket service or the user before then
            single<TailscaleService> { mock() }
            factory { TailnetCleanup(get(), get(), get()) }
            factory { DataBucketTeardown(get(), get()) }
            factory { TeardownPlanner(get()) }

            // Published Cassandra builds. Mocked rather than real: resolving it for real would
            // create the account bucket. Its defaults (no builds, no match) are what a profile
            // that has never run `cassandra build` actually looks like.
            single { mock<CassandraBuildCatalog>() }

            factory { AwsKeyPairService(get(), get(), AwsErrorHandler(), File(get<Context>().profileDir, "settings.yaml")) }

            // Fake external IP resolver so tests never make a network call
            single<ExternalIpService> {
                object : ExternalIpService {
                    override fun getExternalIpAddress(): String = "203.0.113.10"
                }
            }
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

                        override fun close() = Unit
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

                    override fun discard(host: Host) = Unit

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
                        notice: String,
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

                    override fun replaceDirectory(
                        host: Host,
                        localDir: File,
                        remoteDir: String,
                        owner: String,
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
