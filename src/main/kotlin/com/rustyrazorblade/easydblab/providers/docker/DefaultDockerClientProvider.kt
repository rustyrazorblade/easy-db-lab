package com.rustyrazorblade.easydblab.providers.docker

import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import com.rustyrazorblade.easydblab.DefaultDockerClient
import com.rustyrazorblade.easydblab.DockerClientInterface
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Default implementation of DockerClientProvider that creates a Docker client
 * using the system's default Docker configuration.
 */
class DefaultDockerClientProvider : DockerClientProvider {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * Lazily initialized Docker client instance.
     * The client is created once and reused for all subsequent calls.
     */
    private val dockerClientInstance: DockerClientInterface by lazy {
        log.info { "Initializing Docker client" }

        val dockerConfig =
            DefaultDockerClientConfig
                .createDefaultConfigBuilder()
                .build()

        val httpClient =
            ApacheDockerHttpClient
                .Builder()
                .dockerHost(dockerConfig.dockerHost)
                .sslConfig(dockerConfig.sslConfig)
                .build()

        val client = DockerClientImpl.getInstance(dockerConfig, httpClient)
        DefaultDockerClient(client, dockerConfig.dockerHost.toString())
    }

    override fun getDockerClient(): DockerClientInterface = dockerClientInstance
}
