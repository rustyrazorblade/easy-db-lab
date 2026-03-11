package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.ClusterHost
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.kubernetes.KubernetesJob
import com.rustyrazorblade.easydblab.kubernetes.KubernetesPod
import com.rustyrazorblade.easydblab.kubernetes.ManifestApplier
import io.fabric8.kubernetes.api.model.DeletionPropagation
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * Implementation of job-related K8s operations: creating, deleting, listing jobs
 * and getting pod logs.
 */
class DefaultK8sJobOperations(
    private val clientProvider: K8sClientProvider,
    private val eventBus: EventBus,
) : K8sJobOperations {
    override fun createJob(
        controlHost: ClusterHost,
        namespace: String,
        jobYaml: String,
    ): Result<String> =
        runCatching {
            log.info { "Creating K8s job in namespace $namespace" }

            clientProvider.createClient(controlHost).use { client ->
                ManifestApplier.applyYaml(client, jobYaml)

                val yamlLines = jobYaml.lines()
                val nameLine = yamlLines.find { it.trim().startsWith("name:") }
                val jobName =
                    nameLine?.substringAfter("name:")?.trim()?.trim('"', '\'')
                        ?: error("Could not extract job name from YAML")

                log.info { "Created job: $jobName" }
                jobName
            }
        }

    override fun createJob(
        controlHost: ClusterHost,
        namespace: String,
        job: io.fabric8.kubernetes.api.model.batch.v1.Job,
    ): Result<String> =
        runCatching {
            log.info { "Creating K8s job '${job.metadata?.name}' in namespace $namespace" }

            clientProvider.createClient(controlHost).use { client ->
                client
                    .batch()
                    .v1()
                    .jobs()
                    .inNamespace(namespace)
                    .resource(job)
                    .create()

                val jobName =
                    job.metadata?.name
                        ?: error("Job metadata name is required")

                log.info { "Created job: $jobName" }
                jobName
            }
        }

    override fun deleteJob(
        controlHost: ClusterHost,
        namespace: String,
        jobName: String,
    ): Result<Unit> =
        runCatching {
            log.info { "Deleting job $jobName from namespace $namespace" }

            clientProvider.createClient(controlHost).use { client ->
                client
                    .batch()
                    .v1()
                    .jobs()
                    .inNamespace(namespace)
                    .withName(jobName)
                    .withPropagationPolicy(DeletionPropagation.FOREGROUND)
                    .delete()

                log.info { "Deleted job: $jobName" }
            }

            eventBus.emit(Event.K8s.JobDeleted(jobName))
        }

    override fun getJobsByLabel(
        controlHost: ClusterHost,
        namespace: String,
        labelKey: String,
        labelValue: String,
    ): Result<List<KubernetesJob>> =
        runCatching {
            log.debug { "Getting jobs with label $labelKey=$labelValue in namespace $namespace" }

            clientProvider.createClient(controlHost).use { client ->
                val jobs =
                    client
                        .batch()
                        .v1()
                        .jobs()
                        .inNamespace(namespace)
                        .withLabel(labelKey, labelValue)
                        .list()

                jobs.items.map { job ->
                    val name = job.metadata?.name ?: "unknown"
                    val succeeded = job.status?.succeeded ?: 0
                    val failed = job.status?.failed ?: 0
                    val active = job.status?.active ?: 0
                    val completions = job.spec?.completions ?: 1

                    val status =
                        when {
                            succeeded >= completions -> "Completed"
                            failed > 0 -> "Failed"
                            active > 0 -> "Running"
                            else -> "Pending"
                        }

                    val creationTime =
                        job.metadata?.creationTimestamp?.let {
                            Instant.parse(it)
                        } ?: Instant.now()
                    val age = Duration.between(creationTime, Instant.now())

                    KubernetesJob(
                        namespace = namespace,
                        name = name,
                        status = status,
                        completions = "$succeeded/$completions",
                        age = age,
                    )
                }
            }
        }

    override fun getPodsForJob(
        controlHost: ClusterHost,
        namespace: String,
        jobName: String,
    ): Result<List<KubernetesPod>> =
        runCatching {
            log.debug { "Getting pods for job $jobName in namespace $namespace" }

            clientProvider.createClient(controlHost).use { client ->
                val pods =
                    client
                        .pods()
                        .inNamespace(namespace)
                        .withLabel("job-name", jobName)
                        .list()

                pods.items.map { pod ->
                    val name = pod.metadata?.name ?: "unknown"
                    val containerStatuses = pod.status?.containerStatuses ?: emptyList()
                    val readyContainers = containerStatuses.count { it.ready == true }
                    val totalContainers = containerStatuses.size.coerceAtLeast(1)
                    val status = pod.status?.phase ?: "Unknown"
                    val restarts = containerStatuses.sumOf { it.restartCount ?: 0 }

                    val creationTime =
                        pod.metadata?.creationTimestamp?.let {
                            Instant.parse(it)
                        } ?: Instant.now()
                    val age = Duration.between(creationTime, Instant.now())

                    KubernetesPod(
                        namespace = namespace,
                        name = name,
                        status = status,
                        ready = "$readyContainers/$totalContainers",
                        restarts = restarts,
                        age = age,
                    )
                }
            }
        }

    override fun getPodLogs(
        controlHost: ClusterHost,
        namespace: String,
        podName: String,
        tailLines: Int?,
    ): Result<String> =
        runCatching {
            log.debug { "Getting logs for pod $podName in namespace $namespace" }

            clientProvider.createClient(controlHost).use { client ->
                val logRequest =
                    client
                        .pods()
                        .inNamespace(namespace)
                        .withName(podName)

                val logs =
                    if (tailLines != null) {
                        logRequest.tailingLines(tailLines).log
                    } else {
                        logRequest.log
                    }

                logs ?: ""
            }
        }
}
