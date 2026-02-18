package com.rustyrazorblade.easydblab.commands.aws

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.output.BufferedOutputHandler
import com.rustyrazorblade.easydblab.output.OutputHandler
import com.rustyrazorblade.easydblab.providers.aws.VpcService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class VpcsTest : BaseKoinTest() {
    private lateinit var mockVpcService: VpcService
    private lateinit var outputHandler: BufferedOutputHandler

    override fun additionalTestModules(): List<Module> =
        listOf(
            module {
                single<VpcService> { mockVpcService }
            },
        )

    @BeforeEach
    fun setupMocks() {
        mockVpcService = mock()
        outputHandler = getKoin().get<OutputHandler>() as BufferedOutputHandler
    }

    @Test
    fun `execute outputs message when no VPCs found`() {
        whenever(
            mockVpcService.findVpcsByTag(
                eq(Constants.Vpc.TAG_KEY),
                eq(Constants.Vpc.TAG_VALUE),
            ),
        ).thenReturn(emptyList())

        val command = Vpcs()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("No easy-db-lab VPCs found")
    }

    @Test
    fun `execute lists VPCs with name and cluster id`() {
        whenever(
            mockVpcService.findVpcsByTag(
                eq(Constants.Vpc.TAG_KEY),
                eq(Constants.Vpc.TAG_VALUE),
            ),
        ).thenReturn(listOf("vpc-123"))

        whenever(mockVpcService.getVpcTags("vpc-123")).thenReturn(
            mapOf("Name" to "my-cluster", "ClusterId" to "abc-123"),
        )

        val command = Vpcs()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("my-cluster")
        assertThat(output).contains("vpc-123")
        assertThat(output).contains("abc-123")
    }

    @Test
    fun `execute handles VPCs with missing tags`() {
        whenever(
            mockVpcService.findVpcsByTag(
                eq(Constants.Vpc.TAG_KEY),
                eq(Constants.Vpc.TAG_VALUE),
            ),
        ).thenReturn(listOf("vpc-456"))

        whenever(mockVpcService.getVpcTags("vpc-456")).thenReturn(emptyMap())

        val command = Vpcs()
        command.execute()

        val output = outputHandler.messages.joinToString("\n")
        assertThat(output).contains("(unnamed)")
        assertThat(output).contains("(no cluster id)")
    }
}
