package com.rustyrazorblade.easydblab.commands

import com.rustyrazorblade.easydblab.configuration.ClientNetworks
import com.rustyrazorblade.easydblab.configuration.ClusterState
import com.rustyrazorblade.easydblab.configuration.environment.ClientNetworkOrigin
import com.rustyrazorblade.easydblab.configuration.environment.ClusterContract
import com.rustyrazorblade.easydblab.configuration.isVeryWideClientNetwork
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kubernetes.KubeApi
import com.rustyrazorblade.easydblab.network.Ipv4Cidr
import com.rustyrazorblade.easydblab.services.ClientNetworkProbe
import com.rustyrazorblade.easydblab.services.ClientNetworkProbeResult

/**
 * The client networks `init` records, kept out of [Init] so that command only sequences its steps.
 * [resolve] parses the `--client-network` values and probes a brought cluster, before anything is
 * cleaned or written; [applyTo] puts the result in the state `init` saves; [report] tells the user
 * what the passwordless database kits will trust.
 *
 * @property extraClients the parsed `--client-network` values, in the order given
 * @property probe what the nodes of a brought cluster reported, or null when no cluster was probed
 */
class InitClientNetworks private constructor(
    private val extraClients: List<Ipv4Cidr>,
    private val probe: ClientNetworkProbeResult?,
) {
    /**
     * [built] with the client networks to record. A probed cluster records what it reported. With
     * no probe, easy-db-lab knows the networks of the nodes it builds and records none
     * ([ClientNetworks.from] derives them); anywhere else (K3s in Docker, until its provisioner
     * knows the Docker and pod networks) only the `--client-network` values are known, so they are
     * recorded, or nothing when none was given.
     */
    fun applyTo(built: ClusterState): ClusterState {
        val extra = extraClients.map { it.value }
        val recordedOnly = ClusterContract.of(built).clientNetworkOrigin == ClientNetworkOrigin.Recorded
        val recorded = probe?.networks?.cidrs?.map { it.value } ?: extra.takeIf { it.isNotEmpty() && recordedOnly }
        return built.copy(clientNetworks = recorded, extraClientNetworks = extra)
    }

    /**
     * Warns about each very wide network [state] records ([Event.Setup.WideClientNetwork]): an
     * `--client-network` value, or a pod CIDR a probed node reported. Then lists every network the
     * kits will trust ([Event.Setup.ClientNetworksRecorded]), and warns when the probed nodes reported
     * no pod CIDR ([Event.Setup.NoPodCidrClientNetworks]). In an environment whose networks
     * easy-db-lab did not build, with none recorded, it emits [Event.Setup.NoClientNetworksRecorded]
     * instead of the list. No warning fails `init`.
     */
    fun report(
        state: ClusterState,
        eventBus: EventBus,
    ) {
        extraClients.filter { it.isVeryWideClientNetwork }.forEach { cidr ->
            eventBus.emit(Event.Setup.WideClientNetwork(cidr.value, cidr.addressCount, Event.Setup.ClientNetworkSource.OPTION))
        }
        probe
            ?.networks
            ?.cidrs
            .orEmpty()
            .filter { it.isVeryWideClientNetwork && it !in extraClients }
            .forEach { cidr ->
                eventBus.emit(Event.Setup.WideClientNetwork(cidr.value, cidr.addressCount, Event.Setup.ClientNetworkSource.NODES))
            }
        val contract = ClusterContract.of(state)
        if (contract.clientNetworkOrigin == ClientNetworkOrigin.Recorded && state.clientNetworks == null) {
            eventBus.emit(Event.Setup.NoClientNetworksRecorded(contract.environment.displayName))
            return
        }
        val recorded = ClientNetworks.from(state).cidrs.map { it.value }
        eventBus.emit(Event.Setup.ClientNetworksRecorded(recorded))
        if (probe?.noPodCidr == true) eventBus.emit(Event.Setup.NoPodCidrClientNetworks(recorded))
    }

    companion object {
        /**
         * Parses [options] and, for a [broughtCluster], probes its nodes with them as overrides.
         *
         * @throws IllegalArgumentException naming the value when an option is not an IPv4 network or
         * is `/0`, or when the probe finds no network
         */
        fun resolve(
            options: List<String>,
            broughtCluster: KubeApi?,
            clientNetworkProbe: () -> ClientNetworkProbe,
        ): InitClientNetworks {
            val extraClients = options.map(ClientNetworks::parseOption)
            val probe = broughtCluster?.let { api -> clientNetworkProbe().probe(api, extraClients) }
            return InitClientNetworks(extraClients, probe)
        }
    }
}
