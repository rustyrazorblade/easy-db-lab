package com.rustyrazorblade.easydblab.di

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.ContextFactory
import com.rustyrazorblade.easydblab.Docker
import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.containers.PackerImage
import com.rustyrazorblade.easydblab.events.RedisConfiguration
import com.rustyrazorblade.easydblab.events.RedisEndpoint
import com.rustyrazorblade.easydblab.events.RedisPublisher
import com.rustyrazorblade.easydblab.kubernetes.KubernetesService
import com.rustyrazorblade.easydblab.providers.docker.DockerClientProvider
import com.rustyrazorblade.easydblab.providers.ssh.SSHConnectionProvider
import com.rustyrazorblade.easydblab.services.KitWorkloadProbe
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.Koin
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.definition.BeanDefinition
import org.koin.core.parameter.ParametersHolder
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ecr.EcrClient
import software.amazon.awssdk.services.emr.EmrClient
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.opensearch.OpenSearchClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient
import software.amazon.awssdk.services.sts.StsClient
import java.io.File
import kotlin.reflect.KClass

/**
 * Resolves every definition in every production Koin module the CLI starts with
 * ([KoinModules.getAllModules]), so a registration Koin cannot build fails here, not in a real `up`.
 *
 * The trap this guards: `singleOf`/`factoryOf` resolve every constructor parameter with `get()` and
 * ignore Kotlin default values, so a class with a defaulted `Int`, `Duration` or function parameter
 * registered that way throws `NoDefinitionFoundException` the first time it is resolved. Koin's own
 * `verify()` does not catch it: it treats a defaulted parameter as optional and passes. Only an
 * actual `get()` does.
 *
 * Only external clients and ports are replaced: the AWS SDK clients, the SSH connection pool (which
 * loads a key file), the Docker client, Redis, and the profile, workspace and state file locations.
 * Everything else is the production wiring.
 */
class KoinModulesResolutionTest {
    @TempDir
    lateinit var tempDir: File

    /** The external clients and ports, replaced so resolution touches no network, key file or home directory. */
    private fun externalPortsModule() =
        module {
            val contextFactory = ContextFactory(File(tempDir, "home"), File(tempDir, "workspace"))
            single { contextFactory }
            single<Context> { contextFactory.getDefault() }
            single {
                User(
                    email = "test@example.com",
                    region = "us-west-2",
                    keyName = "test-key",
                    awsProfile = "",
                    awsAccessKey = "test-access-key",
                    awsSecret = "test-secret",
                )
            }
            single { ClusterStateManager(File(tempDir, "state.json")) }
            single<RedisConfiguration> { RedisConfiguration.NotConfigured }
            // The publisher pings Redis on construction; a mock stands in with the endpoint it reports.
            single<RedisPublisher> { mock { on { endpoint } doReturn RedisEndpoint.parse("redis://localhost:6379/events") } }
            single<SSHConnectionProvider> { mock() }
            single<DockerClientProvider> { mock { on { getDockerClient() } doReturn mock() } }
            single<IamClient> { mock() }
            single<Ec2Client> { mock() }
            single<S3Client> { mock() }
            single<StsClient> { mock() }
            single<EmrClient> { mock() }
            single<OpenSearchClient> { mock() }
            single<EcrClient> { mock() }
            single<ServiceQuotasClient> { mock() }
        }

    /**
     * The runtime parameters each parameterized definition is resolved with, as its callers pass them.
     * A new parameterized definition needs an entry here; without one the test names it.
     */
    private fun parametersFor(
        type: KClass<*>,
        koin: Koin,
    ): ParametersHolder? {
        val kubeconfig = File(tempDir, "kubeconfig").path
        return when (type) {
            Docker::class -> parametersOf(koin.get<Context>())
            PackerImage::class -> parametersOf(koin.get<Docker> { parametersOf(koin.get<Context>()) })
            KubernetesService::class -> parametersOf(kubeconfig)
            KitWorkloadProbe::class -> parametersOf(kubeconfig)
            else -> null
        }
    }

    @OptIn(KoinInternalApi::class)
    private fun productionDefinitions(): List<BeanDefinition<*>> =
        KoinModules
            .getAllModules()
            .flatMap { module -> module.mappings.values }
            .map { factory -> factory.beanDefinition }
            .distinct()

    @Test
    fun `every definition in the production modules resolves`() {
        val definitions = productionDefinitions()
        val app = koinApplication { modules(KoinModules.getAllModules() + externalPortsModule()) }
        try {
            val koin = app.koin
            val failures =
                definitions.mapNotNull { definition ->
                    val type = definition.primaryType
                    val parameters = parametersFor(type, koin)
                    runCatching {
                        koin.get<Any>(type, definition.qualifier) { parameters ?: parametersOf() }
                    }.exceptionOrNull()?.let { error -> describe(definition, error) }
                }

            assertThat(definitions).isNotEmpty
            assertThat(failures).isEmpty()
        } finally {
            app.close()
        }
    }

    private fun describe(
        definition: BeanDefinition<*>,
        error: Throwable,
    ): String {
        val root = generateSequence(error) { it.cause }.last()
        val name = definition.qualifier?.let { "${definition.primaryType.qualifiedName} ($it)" } ?: definition.primaryType.qualifiedName
        return "$name: ${root::class.simpleName}: ${root.message}"
    }
}
