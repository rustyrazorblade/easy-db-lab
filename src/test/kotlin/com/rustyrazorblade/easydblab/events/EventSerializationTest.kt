package com.rustyrazorblade.easydblab.events

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.Extension
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import org.junit.jupiter.api.extension.TestTemplateInvocationContext
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider
import java.util.stream.Stream
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor

class EventSerializationTest {
    // --- @TestTemplate: one named invocation per concrete Event subtype ---

    @TestTemplate
    @ExtendWith(AllConcreteEventsProvider::class)
    fun `event round-trips through JSON`(event: Event) {
        val envelope =
            EventEnvelope(
                event = event,
                timestamp = "2026-01-01T00:00:00.000Z",
                commandName = "test",
            )
        val json = EventEnvelope.toJson(envelope)
        val deserialized = EventEnvelope.fromJson(json)
        assertThat(deserialized)
            .describedAs("Round-trip failed for ${event::class.qualifiedName}")
            .isEqualTo(envelope)
    }

    // --- ArchUnit: structural constraints on all concrete Event subtypes ---

    @Test
    fun `all concrete Event subtypes must be annotated with @Serializable`() {
        val classes = ClassFileImporter().importPackages("com.rustyrazorblade.easydblab.events")
        ArchRuleDefinition
            .classes()
            .that()
            .areAssignableTo(Event::class.java)
            .and()
            .areNotInterfaces()
            .should()
            .beAnnotatedWith("kotlinx.serialization.Serializable")
            .check(classes)
    }

    @Test
    fun `all concrete Event subtypes must have @SerialName`() {
        val classes = ClassFileImporter().importPackages("com.rustyrazorblade.easydblab.events")
        ArchRuleDefinition
            .classes()
            .that()
            .areAssignableTo(Event::class.java)
            .and()
            .areNotInterfaces()
            .should()
            .beAnnotatedWith("kotlinx.serialization.SerialName")
            .check(classes)
    }

    @Test
    fun `@SerialName must not use fully qualified class names`() {
        val classes = ClassFileImporter().importPackages("com.rustyrazorblade.easydblab.events")
        ArchRuleDefinition
            .classes()
            .that()
            .areAssignableTo(Event::class.java)
            .and()
            .areNotInterfaces()
            .and()
            .areAnnotatedWith("kotlinx.serialization.SerialName")
            .should(NoPackagePrefixInSerialName)
            .check(classes)
    }

    // --- Structural EventEnvelope tests not covered by the template ---

    @Test
    fun `envelope round-trips through JSON`() {
        val original =
            EventEnvelope(
                event = Event.Message("Starting cassandra0"),
                timestamp = "2026-02-23T10:15:30.123Z",
                commandName = "start",
            )
        assertThat(EventEnvelope.fromJson(EventEnvelope.toJson(original))).isEqualTo(original)
    }

    @Test
    fun `envelope with null commandName round-trips`() {
        val original =
            EventEnvelope(
                event = Event.Message("test"),
                timestamp = "2026-02-23T10:15:30.123Z",
                commandName = null,
            )
        val json = EventEnvelope.toJson(original)
        val deserialized = EventEnvelope.fromJson(json)
        assertThat(deserialized.commandName).isNull()
        assertThat(deserialized.event.toDisplayString()).isEqualTo("test")
    }
}

// --- Provider: discovers and instantiates every concrete leaf subtype of Event ---

class AllConcreteEventsProvider : TestTemplateInvocationContextProvider {
    override fun supportsTestTemplate(context: ExtensionContext): Boolean = true

    override fun provideTestTemplateInvocationContexts(context: ExtensionContext): Stream<TestTemplateInvocationContext> =
        leafSubclasses(Event::class)
            .sortedBy { it.qualifiedName }
            .map { klass -> invocationContextFor(klass) }
            .stream()

    private fun invocationContextFor(klass: KClass<out Event>): TestTemplateInvocationContext =
        object : TestTemplateInvocationContext {
            override fun getDisplayName(invocationIndex: Int): String =
                klass.qualifiedName!!
                    .removePrefix("com.rustyrazorblade.easydblab.events.Event.")

            override fun getAdditionalExtensions(): List<Extension> =
                listOf(
                    object : ParameterResolver {
                        override fun supportsParameter(
                            parameterContext: ParameterContext,
                            extensionContext: ExtensionContext,
                        ): Boolean = parameterContext.index == 0

                        override fun resolveParameter(
                            parameterContext: ParameterContext,
                            extensionContext: ExtensionContext,
                        ): Any = instantiate(klass)
                    },
                )
        }

    companion object {
        fun leafSubclasses(klass: KClass<*>): List<KClass<out Event>> =
            if (klass.isSealed) {
                klass.sealedSubclasses.flatMap { leafSubclasses(it) }
            } else {
                @Suppress("UNCHECKED_CAST")
                listOf(klass as KClass<out Event>)
            }

        fun instantiate(klass: KClass<out Event>): Event =
            klass.objectInstance
                ?: run {
                    val ctor =
                        klass.primaryConstructor
                            ?: error("No primary constructor for ${klass.qualifiedName}")
                    ctor.callBy(
                        ctor.parameters
                            .filter { !it.isOptional }
                            .associateWith { param -> defaultFor(param.type) },
                    )
                }

        private fun defaultFor(type: KType): Any? {
            if (type.isMarkedNullable) return null
            return when (val cls = type.classifier) {
                String::class -> "test"
                Int::class -> 0
                Long::class -> 0L
                Double::class -> 0.0
                Float::class -> 0.0f
                Boolean::class -> false
                List::class -> emptyList<Any>()
                Map::class -> emptyMap<Any, Any>()
                Set::class -> emptySet<Any>()
                is KClass<*> ->
                    when {
                        cls.java.isEnum ->
                            cls.java.enumConstants?.firstOrNull()
                                ?: error("Enum ${cls.qualifiedName} has no constants")
                        cls.objectInstance != null -> cls.objectInstance
                        else -> {
                            val ctor =
                                cls.primaryConstructor
                                    ?: error("Cannot instantiate ${cls.qualifiedName}: no primary constructor and not a data object")
                            ctor.callBy(
                                ctor.parameters
                                    .filter { !it.isOptional }
                                    .associateWith { p -> defaultFor(p.type) },
                            )
                        }
                    }
                else -> error("No default value strategy for type $type")
            }
        }
    }
}

private object NoPackagePrefixInSerialName : ArchCondition<JavaClass>(
    "have a short @SerialName without package prefix",
) {
    override fun check(
        item: JavaClass,
        events: ConditionEvents,
    ) {
        val annotation =
            item
                .tryGetAnnotationOfType("kotlinx.serialization.SerialName")
                .orElse(null) ?: return
        val value = annotation.get("value").orElse(null) as? String ?: return
        if (value.startsWith("com.")) {
            events.add(
                SimpleConditionEvent.violated(
                    item,
                    "${item.name} has package-prefixed @SerialName '$value' — use short form like 'Domain.ClassName'",
                ),
            )
        }
    }
}
