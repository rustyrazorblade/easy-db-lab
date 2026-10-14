package com.rustyrazorblade.easydblab.commands.ingress

import com.rustyrazorblade.easydblab.BaseKoinTest
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.events.EventEnvelope
import com.rustyrazorblade.easydblab.events.EventListener
import com.rustyrazorblade.easydblab.kernel.CommandFailedException
import com.rustyrazorblade.easydblab.network.Ipv4Cidr
import com.rustyrazorblade.easydblab.services.aws.AllowOutcome
import com.rustyrazorblade.easydblab.services.aws.IngressResult
import com.rustyrazorblade.easydblab.services.aws.InvalidCidr
import com.rustyrazorblade.easydblab.services.aws.RevokeOutcome
import com.rustyrazorblade.easydblab.services.aws.SshIngressService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.get
import picocli.CommandLine
import kotlin.reflect.full.findAnnotation

/**
 * `ingress allow` and `ingress revoke` turn the service's results into typed events and an exit
 * status.  The service is a fake: it returns whichever result a test sets.
 */
class IngressCommandsTest : BaseKoinTest() {
    private val events = mutableListOf<Event>()
    private val service = FakeSshIngressService()

    override fun additionalTestModules(): List<Module> = listOf(module { single<SshIngressService> { service } })

    @BeforeEach
    fun captureEvents() {
        get<EventBus>().addListener(
            object : EventListener {
                override fun onEvent(envelope: EventEnvelope) {
                    events += envelope.event
                }

                override fun close() = Unit
            },
        )
    }

    @Test
    fun `allow passes every --cidr value to the service and reports each outcome`() {
        service.allowResult =
            IngressResult.Applied(
                SG,
                detected = false,
                outcomes = listOf(AllowOutcome.Added(cidr("203.0.113.7/32")), AllowOutcome.AlreadyPresent(cidr("198.51.100.0/24"))),
            )

        run(IngressAllow(), "--cidr", "203.0.113.7", "--cidr", "198.51.100.0/24")

        assertThat(service.calls).containsExactly(listOf("203.0.113.7", "198.51.100.0/24"))
        assertThat(events).containsExactly(
            Event.Ingress.RuleAdded(SG, "203.0.113.7/32", detected = false),
            Event.Ingress.RuleAlreadyPresent(SG, "198.51.100.0/24", detected = false),
        )
    }

    @Test
    fun `allow with no value passes an empty list and reports the detected IP as detected`() {
        service.allowResult =
            IngressResult.Applied(SG, detected = true, outcomes = listOf(AllowOutcome.Added(cidr("203.0.113.10/32"))))

        run(IngressAllow())

        assertThat(service.calls).containsExactly(emptyList())
        assertThat(events).containsExactly(Event.Ingress.RuleAdded(SG, "203.0.113.10/32", detected = true))
    }

    @Test
    fun `allow reports a failed CIDR, still reports the others, and exits non-zero naming it`() {
        service.allowResult =
            IngressResult.Applied(
                SG,
                detected = false,
                outcomes =
                    listOf(
                        AllowOutcome.Failed(cidr("203.0.113.7/32"), "rule limit reached"),
                        AllowOutcome.Added(cidr("198.51.100.0/24")),
                    ),
            )

        assertThatThrownBy { run(IngressAllow(), "--cidr", "203.0.113.7", "--cidr", "198.51.100.0/24") }
            .isInstanceOf(CommandFailedException::class.java)
            .hasMessageContaining("203.0.113.7/32")
        assertThat(events).containsExactly(
            Event.Ingress.RuleChangeFailed(SG, "203.0.113.7/32", Event.Ingress.Action.ALLOW, "rule limit reached"),
            Event.Ingress.RuleAdded(SG, "198.51.100.0/24", detected = false),
        )
    }

    @Test
    fun `revoke removes what matches and exits non-zero naming the CIDR with no rule`() {
        service.revokeResult =
            IngressResult.Applied(
                SG,
                detected = false,
                outcomes = listOf(RevokeOutcome.NotFound(cidr("192.0.2.1/32")), RevokeOutcome.Removed(cidr("203.0.113.7/32"))),
            )

        assertThatThrownBy { run(IngressRevoke(), "--cidr", "192.0.2.1", "--cidr", "203.0.113.7") }
            .isInstanceOf(CommandFailedException::class.java)
            .hasMessageContaining("192.0.2.1/32")
            .hasMessageNotContaining("203.0.113.7/32")
        assertThat(events).containsExactly(
            Event.Ingress.RuleNotFound(SG, "192.0.2.1/32"),
            Event.Ingress.RuleRemoved(SG, "203.0.113.7/32", detected = false),
        )
    }

    @Test
    fun `revoke of the detected IP succeeds and reports it as detected`() {
        service.revokeResult =
            IngressResult.Applied(SG, detected = true, outcomes = listOf(RevokeOutcome.Removed(cidr("203.0.113.10/32"))))

        run(IngressRevoke())

        assertThat(events).containsExactly(Event.Ingress.RuleRemoved(SG, "203.0.113.10/32", detected = true))
    }

    @Test
    fun `revoke reports a failed CIDR and exits non-zero`() {
        service.revokeResult =
            IngressResult.Applied(SG, detected = false, outcomes = listOf(RevokeOutcome.Failed(cidr("0.0.0.0/0"), "denied")))

        assertThatThrownBy { run(IngressRevoke(), "--cidr", "0.0.0.0/0") }.isInstanceOf(CommandFailedException::class.java)
        assertThat(events).containsExactly(
            Event.Ingress.RuleChangeFailed(SG, "0.0.0.0/0", Event.Ingress.Action.REVOKE, "denied"),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("preconditions")
    fun `a precondition result fails allow with its one event`(
        result: IngressResult<Nothing>,
        expected: Event.Ingress,
    ) {
        service.allowResult = result

        assertThatThrownBy { run(IngressAllow(), "--cidr", "203.0.113.7") }.isInstanceOf(CommandFailedException::class.java)
        assertThat(events).containsExactly(expected)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("preconditions")
    fun `a precondition result fails revoke with its one event`(
        result: IngressResult<Nothing>,
        expected: Event.Ingress,
    ) {
        service.revokeResult = result

        assertThatThrownBy { run(IngressRevoke()) }.isInstanceOf(CommandFailedException::class.java)
        assertThat(events).containsExactly(expected)
    }

    @Test
    fun `neither command asks for the SOCKS tunnel`() {
        assertThat(IngressAllow::class.findAnnotation<RequiresProxy>()).isNull()
        assertThat(IngressRevoke::class.findAnnotation<RequiresProxy>()).isNull()
    }

    private fun run(
        command: Any,
        vararg args: String,
    ) {
        CommandLine(command).parseArgs(*args)
        (command as java.util.concurrent.Callable<*>).call()
    }

    private class FakeSshIngressService : SshIngressService {
        val calls = mutableListOf<List<String>>()
        var allowResult: IngressResult<AllowOutcome> = IngressResult.NotInWorkspace
        var revokeResult: IngressResult<RevokeOutcome> = IngressResult.NotInWorkspace

        override fun allow(rawCidrs: List<String>): IngressResult<AllowOutcome> {
            calls += rawCidrs
            return allowResult
        }

        override fun revoke(rawCidrs: List<String>): IngressResult<RevokeOutcome> {
            calls += rawCidrs
            return revokeResult
        }
    }

    companion object {
        private const val SG = "sg-0123456789abcdef0"

        private fun cidr(value: String): Ipv4Cidr = Ipv4Cidr.parse(value).getOrThrow()

        @JvmStatic
        fun preconditions(): List<Arguments> =
            listOf(
                Arguments.of(IngressResult.NotInWorkspace, Event.Ingress.NotInWorkspace),
                Arguments.of(IngressResult.NoSecurityGroupInState, Event.Ingress.NoSecurityGroupInState),
                Arguments.of(
                    IngressResult.InvalidCidrs(listOf(InvalidCidr("300.1.1.1", "octet '300' is not a number from 0 to 255"))),
                    Event.Ingress.InvalidCidrs(
                        listOf(Event.Ingress.InvalidCidrValue("300.1.1.1", "octet '300' is not a number from 0 to 255")),
                    ),
                ),
                Arguments.of(IngressResult.DetectionFailed("timed out"), Event.Ingress.PublicIpDetectionFailed("timed out")),
                Arguments.of(IngressResult.SecurityGroupGone(SG), Event.Ingress.SecurityGroupNotFound(SG)),
            )
    }
}
