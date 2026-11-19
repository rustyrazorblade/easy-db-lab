package com.rustyrazorblade.easydblab.services.bringup

import com.rustyrazorblade.easydblab.events.EventBus
import com.rustyrazorblade.easydblab.output.NodeOutput

/**
 * Where a bring-up reports what happens: [eventBus] for the facts (a step started, a node failed)
 * and [nodeOutput] for the nodes' raw output, whose tail a failure report carries and where each
 * retried attempt is marked. One holder, so the coordinator and every pipeline get both together.
 */
data class BringUpReporting(
    val eventBus: EventBus,
    val nodeOutput: NodeOutput,
)
