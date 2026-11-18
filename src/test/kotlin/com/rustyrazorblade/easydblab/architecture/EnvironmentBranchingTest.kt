package com.rustyrazorblade.easydblab.architecture

import com.rustyrazorblade.easydblab.architecture.fixtures.BranchesOnEnvironment
import com.rustyrazorblade.easydblab.configuration.environment.Environment
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `ClusterContract.of(state)` is the only place in main code that branches on the [Environment]
 * variant (execution-environment spec, owner decision K1). Every other class reads a contract field,
 * so a new environment adds one branch there and none in a consumer.
 *
 * A Kotlin `is` test compiles to an `instanceof` check, and a comparison with the [Environment.Ec2K3s]
 * object reads its `INSTANCE` field. Outside the `environment` package, no main class may do either.
 * Code that records an environment uses [Environment.DEFAULT] or builds a variant, which is neither.
 */
class EnvironmentBranchingTest {
    private val mainClasses: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages(ROOT)

    @Test
    fun `no main class outside the environment package branches on the environment variant`() {
        assertThat(branches(mainClasses)).isEmpty()
    }

    @Test
    fun `the rule reports an is test and a comparison with the EC2 object`() {
        val classes = ClassFileImporter().importClasses(BranchesOnEnvironment::class.java)

        assertThat(branches(classes))
            .anySatisfy { assertThat(it).contains("BranchesOnEnvironment", "instanceof", "K3sDocker") }
            .anySatisfy { assertThat(it).contains("BranchesOnEnvironment", "reads", "Ec2K3s.INSTANCE") }
    }

    /** Each `instanceof` on an environment type, and each read of the EC2 object, outside [ENVIRONMENT_PACKAGE]. */
    private fun branches(classes: JavaClasses): List<String> =
        classes
            .filterNot { it.packageName == ENVIRONMENT_PACKAGE }
            .flatMap { owner ->
                owner.codeUnits.flatMap { unit ->
                    val checks =
                        unit.instanceofChecks
                            .filter { isEnvironmentType(it.rawType) }
                            .map { "${owner.name}.${unit.name}: instanceof ${it.rawType.simpleName} (line ${it.lineNumber})" }
                    val reads =
                        unit.fieldAccesses
                            .filter { it.target.owner.name == EC2_OBJECT && it.target.name == "INSTANCE" }
                            .map { "${owner.name}.${unit.name}: reads Ec2K3s.INSTANCE (line ${it.lineNumber})" }
                    checks + reads
                }
            }

    private fun isEnvironmentType(type: JavaClass): Boolean = type.name == ENVIRONMENT || type.isAssignableTo(ENVIRONMENT)

    private companion object {
        const val ROOT = "com.rustyrazorblade.easydblab"
        const val ENVIRONMENT_PACKAGE = "$ROOT.configuration.environment"
        val ENVIRONMENT: String = Environment::class.java.name
        val EC2_OBJECT: String = Environment.Ec2K3s::class.java.name
    }
}
