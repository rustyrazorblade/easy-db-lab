package com.rustyrazorblade.easydblab.commands.grafana

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.annotations.McpCommand
import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.annotations.RequiresProxy
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.commands.converters.PicoEpochMillisConverter
import com.rustyrazorblade.easydblab.configuration.ServerType
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.GrafanaAnnotationRequest
import com.rustyrazorblade.easydblab.services.GrafanaDashboardService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Creates a Grafana annotation on the running cluster's Grafana instance.
 *
 * Operators use this to drop an A/B config-change marker on the dashboards' timeline, for example
 * before and after changing a Cassandra setting. A global marker (no `--dashboard`/`--panel` scope)
 * is automatically tagged with [Constants.Grafana.GLOBAL_ANNOTATION_TAG] so it renders on the core
 * dashboards via their tag-filtered annotation query; a scoped marker renders on its target dashboard.
 *
 * The command reaches Grafana over the proxied HTTP client, so it carries `@RequiresProxy`. If the
 * Grafana API cannot be reached, [GrafanaDashboardService.createAnnotation] throws and the command
 * exits non-zero, naming the unreachable endpoint. It does NOT emit a failure event and return 0.
 */
@McpCommand
@RequireProfileSetup
@RequiresProxy
@Command(
    name = "annotate",
    description = ["Create a Grafana annotation (A/B config-change marker) on the cluster's Grafana"],
    mixinStandardHelpOptions = true,
)
class GrafanaAnnotate : PicoBaseCommand() {
    @Option(names = ["--text"], description = ["The annotation body text"], required = true)
    lateinit var text: String

    @Option(
        names = ["--tags"],
        description = ["Tags to attach to the annotation (repeat or comma-separate)"],
        split = ",",
    )
    var tags: List<String> = emptyList()

    @Option(
        names = ["--time"],
        description = ["Start time: 'now' (default), a relative offset like '-30m'/'-2h'/'-1d', an ISO-8601 instant, or epoch millis"],
        converter = [PicoEpochMillisConverter::class],
        defaultValue = "now",
    )
    var time: Long = 0

    @Option(
        names = ["--time-end"],
        description = ["Optional end time producing a region annotation (same formats as --time)"],
        converter = [PicoEpochMillisConverter::class],
    )
    var timeEnd: Long? = null

    @Option(names = ["--dashboard"], description = ["Optional dashboard UID to scope the annotation to one dashboard"])
    var dashboardUid: String? = null

    @Option(names = ["--panel"], description = ["Optional panel id to scope the annotation to one panel"])
    var panelId: Int? = null

    private val grafanaDashboardService: GrafanaDashboardService by inject()

    override fun execute() {
        val controlHost =
            clusterState.hosts[ServerType.Control]?.firstOrNull()
                ?: error("No control node found in cluster state.")

        val effectiveTags = effectiveTags()

        val annotation =
            GrafanaAnnotationRequest(
                text = text,
                tags = effectiveTags,
                time = time,
                timeEnd = timeEnd,
                dashboardUID = dashboardUid,
                panelId = panelId,
            )

        val response = grafanaDashboardService.createAnnotation(controlHost, annotation)

        eventBus.emit(
            Event.Grafana.AnnotationCreated(
                id = response.id,
                text = text,
                tags = effectiveTags,
                time = time,
            ),
        )
    }

    /**
     * The tags sent to Grafana. A GLOBAL annotation (no `--dashboard` and no `--panel` scope) gets
     * the fixed global tag appended so it renders on the core dashboards' tag-filtered annotation
     * query; the tag is de-duplicated if the operator already passed it. A scoped annotation renders
     * on its own dashboard, so it carries only the operator's tags.
     */
    private fun effectiveTags(): List<String> {
        val isGlobal = dashboardUid == null && panelId == null
        return if (isGlobal) {
            (tags + Constants.Grafana.GLOBAL_ANNOTATION_TAG).distinct()
        } else {
            tags
        }
    }
}
