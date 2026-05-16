package com.rustyrazorblade.easydblab.services

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@Serializable
data class WorkloadInstallConfig(
    val name: String,
    val description: String = "",
    val version: String = "",
    @SerialName("collision-check")
    val collisionCheck: Boolean = false,
    @SerialName("phase-guards")
    val phaseGuards: Map<String, Boolean> = emptyMap(),
    val dashboards: List<DashboardRef> = emptyList(),
    val args: List<WorkloadArgSpec> = emptyList(),
    val install: List<InstallStep> = emptyList(),
    val start: List<InstallStep> = emptyList(),
    val stop: List<InstallStep> = emptyList(),
    val uninstall: List<InstallStep> = emptyList(),
) {
    fun isGuardedForPhase(phase: String): Boolean = phaseGuards[phase] ?: (collisionCheck && phase == "start")
}

@Serializable
data class DashboardRef(
    val path: String,
    val name: String = "",
)

@Serializable
data class WorkloadArgSpec(
    val flag: String,
    val variable: String,
    val description: String = "",
    val required: Boolean = false,
    val type: ArgType = ArgType.STRING,
    val default: String = "",
    val suffix: String = "",
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
