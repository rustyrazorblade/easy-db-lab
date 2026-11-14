package com.rustyrazorblade.easydblab.services.access

import com.rustyrazorblade.easydblab.configuration.ClusterStateManager
import com.rustyrazorblade.easydblab.configuration.Exposure
import com.rustyrazorblade.easydblab.proxy.LoopbackPortSelector
import com.rustyrazorblade.easydblab.proxy.SocksProxyService
import com.rustyrazorblade.easydblab.services.K8sClientProvider
import com.rustyrazorblade.easydblab.services.ResourceManager
import org.koin.dsl.module

/**
 * Koin module for the service-access seam: one [ServiceAccess] per process, the implementation the
 * cluster's recorded [Exposure] names, registered with [ResourceManager] so every port-forward it
 * opened closes when the CLI exits.
 *
 * A port-forward builds its Kubernetes client through [K8sClientProvider], so it targets the
 * recorded kubeconfig and the context `up` pinned, never the kubeconfig's current-context.
 *
 * The Socks implementation starts or reuses the tunnel through the control node on each open and
 * dials with the port that tunnel listens on now.
 */
val serviceAccessModule =
    module {
        single<ServiceAccess> {
            val stateManager = get<ClusterStateManager>()
            val socks = get<SocksProxyService>()
            val clients = get<K8sClientProvider>()
            ExposureServiceAccess(
                exposure = { stateManager.load().exposure },
                implementations =
                    mapOf(
                        Exposure.Socks to {
                            SocksServiceAccess(stateManager::load) {
                                val control =
                                    stateManager.load().getControlHost()
                                        ?: error("No control node found. Please ensure the environment is running.")
                                socks.ensureRunning(control).localPort
                            }
                        },
                        Exposure.Direct to { DirectServiceAccess(stateManager::load) },
                        Exposure.PortForward to {
                            PortForwardServiceAccess(
                                Fabric8ServiceForwarder(clients::createClient),
                                LoopbackPortSelector()::select,
                            )
                        },
                    ),
            ).also { get<ResourceManager>().register(it) }
        }
    }
