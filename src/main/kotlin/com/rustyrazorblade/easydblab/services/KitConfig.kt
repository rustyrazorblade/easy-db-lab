package com.rustyrazorblade.easydblab.services

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.rustyrazorblade.easydblab.Constants
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Declares how a kit's metrics reach the OTel collector.
 *
 * - [Scrape]: kit exposes a Prometheus endpoint; OTel DaemonSet scrapes it via hostPort
 * - [JavaAgent]: JVM kit uses the OTel Java agent JAR at /usr/local/otel/opentelemetry-javaagent.jar
 * - [HelmNative]: kit has built-in telemetry via helm values; no OTel config change needed
 */
@Serializable
sealed class KitMetrics {
    @Serializable
    @SerialName("scrape")
    data class Scrape(
        val port: Int,
        val path: String = "/metrics",
        val job: String = "",
        val username: String = "",
    ) : KitMetrics()

    @Serializable
    @SerialName("java-agent")
    data class JavaAgent(
        @SerialName("service-name")
        val serviceName: String,
    ) : KitMetrics()

    @Serializable
    @SerialName("helm-native")
    data object HelmNative : KitMetrics()
}

@Serializable
data class KitEndpoint(
    val name: String,
    @SerialName("node-type")
    val nodeType: String,
    val port: Int,
    val type: EndpointType,
    val scheme: String = "",
    val path: String = "",
    val database: String = "",
) {
    @Serializable
    enum class EndpointType {
        @SerialName("http")
        HTTP,

        @SerialName("https")
        HTTPS,

        @SerialName("jdbc")
        JDBC,

        @SerialName("native")
        NATIVE,

        @SerialName("cql")
        CQL,

        @SerialName("postgresql")
        POSTGRESQL,

        @SerialName("mysql")
        MYSQL,

        @SerialName("kafka")
        KAFKA,
    }

    /**
     * Formats a connection URL for this endpoint using [ip] as the host address.
     *
     * [port] defaults to the endpoint's declared port; callers that tunnel the connection
     * through a loopback listener (e.g. the SOCKS TCP bridge) pass the listener's local port
     * so the URL points at the bridge instead of the private-IP authority.
     */
    fun formatUrl(
        ip: String,
        port: Int = this.port,
    ): String =
        when (type) {
            EndpointType.HTTP -> "http://$ip:$port$path"
            EndpointType.HTTPS -> "https://$ip:$port$path"
            EndpointType.JDBC -> "jdbc:$scheme://$ip:$port$path"
            EndpointType.NATIVE, EndpointType.CQL, EndpointType.KAFKA -> "$ip:$port"
            EndpointType.POSTGRESQL -> "postgresql://$ip:$port/$database"
            EndpointType.MYSQL -> "mysql://$ip:$port/$database"
        }

    /**
     * Produces TARGET_* environment variables for this endpoint given the resolved [ip]
     * and the kit's [sqlCap]. Returns an empty map for endpoint types with no TARGET_*
     * convention (HTTPS, NATIVE, CQL).
     */
    fun toTargetVars(
        ip: String,
        sqlCap: KitCapability?,
    ): Map<String, String> {
        val sqlUser = sqlCap?.user.orEmpty()
        return when (type) {
            EndpointType.JDBC ->
                mapOf(
                    "TARGET_JDBC_URL" to formatUrl(ip),
                    "TARGET_JDBC_USER" to sqlUser,
                    "TARGET_JDBC_DRIVER" to sqlCap?.driverClass.orEmpty(),
                )
            EndpointType.POSTGRESQL ->
                mapOf(
                    "TARGET_PG_HOST" to ip,
                    "TARGET_PG_PORT" to port.toString(),
                    "TARGET_PG_USER" to sqlUser,
                    "TARGET_PG_DATABASE" to database,
                )
            EndpointType.MYSQL ->
                mapOf(
                    "TARGET_MYSQL_HOST" to ip,
                    "TARGET_MYSQL_PORT" to port.toString(),
                    "TARGET_MYSQL_USER" to sqlUser,
                    "TARGET_MYSQL_DATABASE" to database,
                )
            EndpointType.HTTP -> mapOf("TARGET_HTTP_URL" to formatUrl(ip))
            EndpointType.KAFKA -> mapOf("TARGET_KAFKA_BOOTSTRAP" to formatUrl(ip))
            else -> emptyMap()
        }
    }
}

@Serializable
data class KitRuntime(
    val type: RuntimeType,
    val release: String = "",
    val selector: String = "",
    val name: String = "",
    val namespace: String = "default",
) {
    @Serializable
    enum class RuntimeType {
        @SerialName("helm")
        HELM,

        @SerialName("deployment")
        DEPLOYMENT,

        @SerialName("statefulset")
        STATEFULSET,

        @SerialName("pods")
        PODS,
    }
}

/**
 * Declares the primary node pool a kit targets.
 * Used to validate cluster topology before install.
 */
@Serializable
enum class KitType {
    @SerialName("db")
    DB,

    @SerialName("app")
    APP,
}

/**
 * Declares a database capability for a kit, which causes a CLI command to be registered
 * automatically under the kit's subcommand group.
 *
 * Known types: "sql". Unknown [type] values are ignored during command registration —
 * the factory filters to recognised types via the [type] field. Using a flat data class
 * (rather than a sealed class with kaml polymorphism) allows unknown future types to be
 * parsed from kit.yaml without error.
 *
 * Example kit.yaml fragment:
 * ```yaml
 * capabilities:
 *   - type: sql
 *     user: easy-db-lab
 *     driver-class: com.facebook.presto.jdbc.PrestoDriver
 * ```
 */
@Serializable
data class KitCapability(
    val type: String = "",
    val user: String = "",
    @SerialName("driver-class") val driverClass: String = "",
) {
    /**
     * Returns the (commandName, description) pair for this capability.
     * Both [KitInfo] and [KitRunnerCommandFactory] use this as the single source of truth
     * so display and registration stay in sync when new capability types are added.
     */
    fun commandEntry(kitName: String): Pair<String, String> {
        require(type.isNotBlank()) { "Capability type must not be blank" }
        return when (type) {
            "sql" -> "sql" to "Execute SQL against $kitName"
            else -> error("Unknown capability type: '$type'")
        }
    }
}

/**
 * Declares the CLI options available for a single kit command (script or lifecycle phase).
 *
 * Unlike top-level [KitConfig.args] which are persisted at install time, command args are
 * ephemeral — they apply only to the current invocation and override install-time values.
 */
@Serializable
data class KitCommandSpec(
    val description: String = "",
    val args: List<KitArgSpec> = emptyList(),
)

@Serializable
data class KitConfig(
    val name: String,
    val description: String = "",
    val version: String = "",
    @SerialName("collision-check")
    val collisionCheck: Boolean = false,
    val dashboards: List<DashboardRef> = emptyList(),
    val args: List<KitArgSpec> = emptyList(),
    val metrics: List<KitMetrics> = emptyList(),
    val endpoints: List<KitEndpoint> = emptyList(),
    val runtime: KitRuntime? = null,
    val install: List<InstallStep> = emptyList(),
    val start: List<InstallStep> = emptyList(),
    val stop: List<InstallStep> = emptyList(),
    val uninstall: List<InstallStep> = emptyList(),
    val backup: List<InstallStep> = emptyList(),
    val restore: List<InstallStep> = emptyList(),
    val hooks: KitHooks? = null,
    val type: KitType? = null,
    val capabilities: List<KitCapability> = emptyList(),
    val commands: Map<String, KitCommandSpec> = emptyMap(),
) {
    val kitRefArg: KitArgSpec? get() = args.firstOrNull { it.type == KitArgSpec.ArgType.KIT_REF }
    val extensionArg: KitArgSpec? get() = args.firstOrNull { it.type == KitArgSpec.ArgType.EXTENSION }

    fun stepsForPhase(phaseName: String): List<InstallStep> =
        when (phaseName) {
            Constants.Kit.PHASE_INSTALL -> install
            Constants.Kit.PHASE_START -> start
            Constants.Kit.PHASE_STOP -> stop
            Constants.Kit.PHASE_UNINSTALL -> uninstall
            Constants.Kit.PHASE_BACKUP -> backup
            Constants.Kit.PHASE_RESTORE -> restore
            // Return an empty list for unknown phases (e.g., script-only phases like
            // 'update-catalogs'). KitRunnerCommand falls through to findScriptFile() when
            // the typed step list is empty. We previously threw here, but that was
            // undocumented behavior that broke any script-only phase not in this fixed list.
            else -> emptyList()
        }
}

@Serializable
data class DashboardRef(
    val path: String,
    val name: String = "",
)

@Serializable
data class KitHook(
    val script: String,
    val workloads: List<String> = emptyList(),
)

@Serializable
data class KitHooks(
    @SerialName("post-workload-start")
    val postWorkloadStart: KitHook? = null,
    @SerialName("post-workload-stop")
    val postWorkloadStop: KitHook? = null,
)

@Serializable
data class KitArgSpec(
    val flag: String,
    val variable: String,
    val description: String = "",
    val required: Boolean = false,
    val type: ArgType = ArgType.STRING,
    val default: String = "",
    val capability: String = "",
) {
    @Serializable
    enum class ArgType {
        @SerialName("string")
        STRING,

        @SerialName("boolean")
        BOOLEAN,

        @SerialName("float")
        FLOAT,

        @SerialName("int")
        INT,

        @SerialName("kit-ref")
        KIT_REF,

        @SerialName("extension")
        EXTENSION,
    }
}

private val installStepModule =
    SerializersModule {
        polymorphic(InstallStep::class) {
            subclass(InstallStep.HelmRepo::class)
            subclass(InstallStep.Helm::class)
            subclass(InstallStep.HelmUninstall::class)
            subclass(InstallStep.Namespace::class)
            subclass(InstallStep.Manifest::class)
            subclass(InstallStep.ManifestUrl::class)
            subclass(InstallStep.Kustomize::class)
            subclass(InstallStep.Wait::class)
            subclass(InstallStep.Delete::class)
            subclass(InstallStep.PlatformPvs::class)
            subclass(InstallStep.PlatformPvsDelete::class)
            subclass(InstallStep.ConfigMap::class)
            subclass(InstallStep.Label::class)
            subclass(InstallStep.Exec::class)
            subclass(InstallStep.Shell::class)
        }
    }

internal val installConfigYaml =
    Yaml(
        serializersModule = installStepModule,
        configuration =
            YamlConfiguration(
                polymorphismStyle = PolymorphismStyle.Property,
                polymorphismPropertyName = "type",
            ),
    )
