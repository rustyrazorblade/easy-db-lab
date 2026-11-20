package com.rustyrazorblade.easydblab.configuration.agents

import com.rustyrazorblade.easydblab.Constants

/**
 * A Java agent a kit or stress JVM loads, and the image its jar comes from.
 *
 * Agents reach a pod through an init container that copies [pathInImage] out of [image] (see
 * [JavaAgentDelivery]), never through a hostPath into the AMI, so a pod gets its agents on any node
 * of any cluster. Each image holds one agent, so the two upgrade independently.
 *
 * @property image the image reference, pinned to the agent version the AMI ships
 * @property pathInImage where the jar sits inside [image]
 * @property fileName the jar's name under [Constants.Kit.AGENTS_MOUNT] in the JVM container
 */
enum class JavaAgent(
    val image: String,
    val pathInImage: String,
    val fileName: String,
) {
    /** The OpenTelemetry Java agent, from the upstream operator's auto-instrumentation image. */
    OTEL(
        image = "${Constants.Kit.Agents.OTEL_IMAGE_REPOSITORY}:${Constants.OtelJavaAgent.VERSION}",
        pathInImage = "/javaagent.jar",
        fileName = "opentelemetry-javaagent.jar",
    ),

    /** The Pyroscope Java agent, from the small image easy-db-lab publishes. */
    PYROSCOPE(
        image = Constants.Kit.Agents.PYROSCOPE_IMAGE,
        pathInImage = "/pyroscope.jar",
        fileName = "pyroscope.jar",
    ),
}
