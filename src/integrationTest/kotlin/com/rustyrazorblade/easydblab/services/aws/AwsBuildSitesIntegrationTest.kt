package com.rustyrazorblade.easydblab.services.aws

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.SharedLocalStack
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.providers.aws.Ec2BuilderInstances
import com.rustyrazorblade.easydblab.providers.ssh.OpenSshPublicKey
import com.rustyrazorblade.easydblab.services.ExternalIpService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest
import software.amazon.awssdk.services.ec2.model.DescribeKeyPairsRequest
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsRequest
import software.amazon.awssdk.services.ec2.model.DescribeVpcsRequest
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import software.amazon.awssdk.services.ec2.model.Filter
import software.amazon.awssdk.services.ec2.model.ImportKeyPairRequest
import software.amazon.awssdk.services.ec2.model.InstanceStateName
import software.amazon.awssdk.services.ec2.model.ResourceType
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest
import software.amazon.awssdk.services.ec2.model.Tag
import software.amazon.awssdk.services.ec2.model.TagSpecification
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

/**
 * Tests [AwsBuildSites] and [Ec2BuilderInstances] against LocalStack, which keeps each region's EC2
 * resources apart: a build sets up its packer network in its own region, imports the profile's key
 * there only when that is not the profile's region (and deletes it when the build ends), and its
 * builders are only the live instances in that region's packer VPC that carry its build id. A
 * concurrent build's builder, or an instance with the id outside the packer VPC, survives.
 *
 * The regions are not LocalStack's default, so the packer VPC other test classes create there
 * cannot affect these results.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AwsBuildSitesIntegrationTest {
    private companion object {
        const val BUILD_REGION = "eu-west-3"
        const val PROFILE_REGION = "ap-southeast-2"
        const val EMPTY_REGION = "sa-east-1"

        // The profile region of the cross-region build only, so no other test puts a packer VPC there.
        const val UNTOUCHED_PROFILE_REGION = "ca-central-1"
        const val PROFILE_KEY = "profile-key"
        const val KEYGEN_LIMIT_SECONDS = 30L
    }

    private lateinit var keyDir: File
    private lateinit var keyFile: File
    private val clients = mutableMapOf<String, Ec2Client>()

    private fun ec2(region: String): Ec2Client = clients.getOrPut(region) { SharedLocalStack.ec2Client(region) }

    @BeforeAll
    fun generateProfileKey() {
        keyDir = createTempDirectory("build-sites").toFile()
        keyFile = File(keyDir, "secret.pem")
        val keygen =
            ProcessBuilder("ssh-keygen", "-q", "-t", "rsa", "-b", "2048", "-m", "PEM", "-N", "", "-f", keyFile.absolutePath)
                .redirectErrorStream(true)
                .start()
        check(keygen.waitFor(KEYGEN_LIMIT_SECONDS, TimeUnit.SECONDS) && keygen.exitValue() == 0) { "ssh-keygen failed" }
    }

    @AfterAll
    fun cleanUp() {
        clients.values.forEach { it.close() }
        keyDir.deleteRecursively()
    }

    private fun sites(profileRegion: String = PROFILE_REGION): AwsBuildSites {
        val eventBus = EventBus()
        val emr: EMRService = mock()
        val openSearch: OpenSearchService = mock()
        whenever(emr.findClustersInVpc(any(), any())).thenReturn(emptyList())
        whenever(openSearch.findDomainsInVpc(any())).thenReturn(emptyList())
        return AwsBuildSites(
            // The site closes its client, so each open gets its own.
            ec2For = { region -> SharedLocalStack.ec2Client(region) },
            infrastructureFor = { vpcService -> AwsInfrastructureService(vpcService, emr, openSearch, eventBus) },
            eventBus = eventBus,
            externalIpService =
                object : ExternalIpService {
                    override fun getExternalIpAddress(): String = "203.0.113.10"
                },
            profileRegion = profileRegion,
            profileKeyName = PROFILE_KEY,
            profileKeyFile = keyFile,
        )
    }

    private fun packerVpcIds(region: String): List<String> =
        ec2(region)
            .describeVpcs(
                DescribeVpcsRequest.builder().filters(filter("tag:Name", Constants.Vpc.PACKER_VPC_NAME)).build(),
            ).vpcs()
            .map { it.vpcId() }

    private fun keyPairNames(region: String): List<String> =
        ec2(region).describeKeyPairs(DescribeKeyPairsRequest.builder().build()).keyPairs().map { it.keyName() }

    /** Launches one instance into a subnet of [vpcId] (or the default VPC when null), tagged with [tags]. */
    private fun launch(
        region: String,
        vpcId: String?,
        tags: Map<String, String>,
    ): String {
        val client = ec2(region)
        val image =
            client
                .describeImages(DescribeImagesRequest.builder().filters(filter("architecture", "x86_64")).build())
                .images()
                .first()
                .imageId()
        val request =
            RunInstancesRequest
                .builder()
                .imageId(image)
                .minCount(1)
                .maxCount(1)
                .tagSpecifications(
                    TagSpecification
                        .builder()
                        .resourceType(ResourceType.INSTANCE)
                        .tags(
                            tags.map { (k, v) ->
                                Tag
                                    .builder()
                                    .key(k)
                                    .value(v)
                                    .build()
                            },
                        ).build(),
                )
        vpcId?.let { vpc ->
            val subnet =
                client
                    .describeSubnets(DescribeSubnetsRequest.builder().filters(filter("vpc-id", vpc)).build())
                    .subnets()
                    .first()
                    .subnetId()
            request.subnetId(subnet)
        }
        return client
            .runInstances(request.build())
            .instances()
            .single()
            .instanceId()
    }

    private fun state(
        region: String,
        instanceId: String,
    ): InstanceStateName =
        ec2(region)
            .describeInstances(DescribeInstancesRequest.builder().instanceIds(instanceId).build())
            .reservations()
            .single()
            .instances()
            .single()
            .state()
            .name()

    private fun buildTags(buildId: String): Map<String, String> = Constants.Vpc.MARKER_TAGS + (Constants.Packer.BUILD_ID_TAG_KEY to buildId)

    private fun filter(
        name: String,
        value: String,
    ): Filter =
        Filter
            .builder()
            .name(name)
            .values(value)
            .build()

    @Test
    fun `a build outside the profile region sets up its own network and key there, and tears down only its own builders`() {
        val mine = "aaaa00000001"
        val other = "bbbb00000002"

        sites(profileRegion = UNTOUCHED_PROFILE_REGION).open(BUILD_REGION, mine).use { site ->
            assertThat(site.region).isEqualTo(BUILD_REGION)
            assertThat(site.keyPairName).isEqualTo(Constants.Packer.BUILD_KEY_PAIR_PREFIX + mine)
            assertThat(keyPairNames(BUILD_REGION)).contains(site.keyPairName)
            assertThat(packerVpcIds(BUILD_REGION)).hasSize(1)
            assertThat(packerVpcIds(UNTOUCHED_PROFILE_REGION)).`as`("the profile region is untouched").isEmpty()

            val vpc = packerVpcIds(BUILD_REGION).single()
            val myBuilder = launch(BUILD_REGION, vpc, buildTags(mine))
            val otherBuilder = launch(BUILD_REGION, vpc, buildTags(other))
            val outsideVpc = launch(BUILD_REGION, null, buildTags(mine))

            assertThat(site.builders.list().map { it.id }).containsExactly(myBuilder)
            site.builders.terminate(site.builders.list().map { it.id })

            assertThat(state(BUILD_REGION, myBuilder)).isIn(InstanceStateName.SHUTTING_DOWN, InstanceStateName.TERMINATED)
            assertThat(state(BUILD_REGION, otherBuilder)).`as`("another build's builder").isEqualTo(InstanceStateName.RUNNING)
            assertThat(state(BUILD_REGION, outsideVpc)).`as`("outside the packer VPC").isEqualTo(InstanceStateName.RUNNING)
            assertThat(site.builders.list()).`as`("terminated builders are not live").isEmpty()
        }

        assertThat(keyPairNames(BUILD_REGION))
            .`as`("the build's key pair is deleted when it ends")
            .doesNotContain(Constants.Packer.BUILD_KEY_PAIR_PREFIX + mine)
    }

    /** Imports the profile key into [region] as [name], tagged [tags], the way a landed first import call would. */
    private fun importKey(
        region: String,
        name: String,
        tags: Map<String, String>,
    ) {
        val keyTags =
            tags.map { (k, v) ->
                Tag
                    .builder()
                    .key(k)
                    .value(v)
                    .build()
            }
        val spec =
            TagSpecification
                .builder()
                .resourceType(ResourceType.KEY_PAIR)
                .tags(keyTags)
                .build()
        val request =
            ImportKeyPairRequest
                .builder()
                .keyName(name)
                .publicKeyMaterial(SdkBytes.fromUtf8String(OpenSshPublicKey.of(keyFile)))
                .tagSpecifications(spec)
                .build()
        ec2(region).importKeyPair(request)
    }

    @Test
    fun `a duplicate key pair carrying this build id is the build key, and is deleted when the build ends`() {
        val buildId = "eeee00000005"
        val name = Constants.Packer.BUILD_KEY_PAIR_PREFIX + buildId
        importKey(BUILD_REGION, name, buildTags(buildId))

        sites(profileRegion = UNTOUCHED_PROFILE_REGION).open(BUILD_REGION, buildId).use { site ->
            assertThat(site.keyPairName).isEqualTo(name)
        }

        assertThat(keyPairNames(BUILD_REGION)).doesNotContain(name)
    }

    @Test
    fun `a duplicate key pair without this build id fails the build and is left alone`() {
        val buildId = "ffff00000006"
        val name = Constants.Packer.BUILD_KEY_PAIR_PREFIX + buildId
        importKey(BUILD_REGION, name, buildTags("999900000007"))

        assertThatThrownBy { sites(profileRegion = UNTOUCHED_PROFILE_REGION).open(BUILD_REGION, buildId).close() }
            .isInstanceOf(Ec2Exception::class.java)
        assertThat(keyPairNames(BUILD_REGION)).contains(name)
    }

    @Test
    fun `a build in the profile region uses the profile key pair and imports none`() {
        val before = keyPairNames(PROFILE_REGION)

        sites().open(PROFILE_REGION, "cccc00000003").use { site ->
            assertThat(site.keyPairName).isEqualTo(PROFILE_KEY)
            assertThat(keyPairNames(PROFILE_REGION)).isEqualTo(before)
            assertThat(packerVpcIds(PROFILE_REGION)).hasSize(1)
        }
    }

    @Test
    fun `a region with no packer VPC has no builders`() {
        val builders = Ec2BuilderInstances(ec2(EMPTY_REGION), "dddd00000004")
        launch(EMPTY_REGION, null, buildTags("dddd00000004"))

        assertThat(builders.list()).isEmpty()
    }
}
