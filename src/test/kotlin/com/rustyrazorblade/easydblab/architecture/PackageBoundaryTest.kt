package com.rustyrazorblade.easydblab.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaCodeUnit
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Where cloud SDK types may appear, and which package dependencies stay broken.
 *
 * The AWS SDK belongs to the AWS adapter packages (`providers.aws..` and `services.aws..`); anything
 * else that names an SDK type blocks a second cloud. These rules scan main classes only, carry no
 * allowlist, and read compiled bytecode, so they also see types no import shows (an inferred return
 * type, a reified binding, a class literal).
 */
class PackageBoundaryTest {
    private val root = "com.rustyrazorblade.easydblab"
    private val sdk = "software.amazon.awssdk.."
    private val adapters = arrayOf("$root.providers.aws..", "$root.services.aws..")

    private val mainClasses: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages(root)

    @Test
    fun `the scanned set holds the entry point and no test support`() {
        val names = mainClasses.map { it.name }

        assertThat(names).contains("$root.MainKt")
        assertThat(names).doesNotContain("$root.BaseKoinTest")
    }

    @Test
    fun `no class outside the adapters depends on an SDK type`() {
        noClasses()
            .that()
            .resideOutsideOfPackages(*adapters)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(sdk)
            .check(mainClasses)
    }

    @Test
    fun `no code outside the adapters catches an SDK throwable`() {
        codeUnits()
            .that()
            .areDeclaredInClassesThat()
            .resideOutsideOfPackages(*adapters)
            .should(notCatchSdkThrowables())
            .check(mainClasses)
    }

    @Test
    fun `providers do not depend on services`() {
        noClasses()
            .that()
            .resideInAPackage("$root.providers..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("$root.services..")
            .check(mainClasses)
    }

    @Test
    fun `configuration does not depend on providers`() {
        noClasses()
            .that()
            .resideInAPackage("$root.configuration..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("$root.providers..")
            .check(mainClasses)
    }

    /**
     * ArchUnit's dependency rule misses an SDK type that appears only in a `catch` clause, so this
     * reads each code unit's try-catch blocks. The message names the method, the SDK type and where.
     */
    private fun notCatchSdkThrowables(): ArchCondition<JavaCodeUnit> =
        object : ArchCondition<JavaCodeUnit>("not catch a software.amazon.awssdk throwable") {
            override fun check(
                unit: JavaCodeUnit,
                events: ConditionEvents,
            ) {
                unit.tryCatchBlocks
                    .flatMap { it.caughtThrowables }
                    .filter { isSdk(it) }
                    .forEach { caught ->
                        events.add(
                            SimpleConditionEvent.violated(
                                unit,
                                "${unit.fullName} catches ${caught.name} (${unit.sourceCodeLocation})",
                            ),
                        )
                    }
            }

            private fun isSdk(type: JavaClass): Boolean = type.packageName.startsWith("software.amazon.awssdk")
        }
}
