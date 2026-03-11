package com.rustyrazorblade.easydblab.mcp

import com.rustyrazorblade.easydblab.BaseKoinTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class McpSchemaValidationTest : BaseKoinTest() {
    private lateinit var registry: McpToolRegistry

    @BeforeEach
    fun setup() {
        registry = McpToolRegistry()
    }

    @Test
    fun `check for JSON Schema 2020-12 compliance issues`() {
        val tools = registry.getTools()

        println("Checking all ${tools.size} tools for JSON Schema 2020-12 compliance issues...\n")

        var issueCount = 0

        tools.forEachIndexed { index, tool ->
            val issues = mutableListOf<String>()
            val schema = tool.inputSchema

            validateRootSchema(schema, issues)

            val properties = schema["properties"]?.jsonObject
            if (properties != null) {
                validateProperties(properties, issues)
            }

            validateRequiredField(schema, properties, issues)

            if (issues.isNotEmpty()) {
                printIssues(index, tool.name, schema, issues)
                issueCount++
            }
        }

        if (issueCount == 0) {
            println("All tools pass JSON Schema 2020-12 compliance checks")
        } else {
            println("Found issues in $issueCount tools")
        }
    }

    private fun validateRootSchema(
        schema: JsonObject,
        issues: MutableList<String>,
    ) {
        if (!schema.containsKey("type")) {
            issues.add("Missing root 'type' field")
        }
        if (!schema.containsKey("properties")) {
            issues.add("Missing 'properties' field")
        }
        val additionalProps = schema["additionalProperties"]
        if (additionalProps != null && additionalProps !is JsonPrimitive) {
            issues.add("additionalProperties must be a boolean primitive")
        }
    }

    private fun validateProperties(
        properties: JsonObject,
        issues: MutableList<String>,
    ) {
        properties.forEach { (propName, propValue) ->
            val prop = propValue.jsonObject
            validatePropertyType(prop, propName, issues)
            validatePropertyEnum(prop, propName, issues)
            validatePropertyDefault(prop, propName, issues)
            validateEnumDefault(prop, propName, issues)
            validateNestedObject(prop, propName, issues)
            validateDescription(prop, propName, issues)
        }
    }

    private fun validatePropertyType(
        prop: JsonObject,
        propName: String,
        issues: MutableList<String>,
    ) {
        if (!prop.containsKey("type")) {
            issues.add("Property '$propName' missing 'type' field")
        }
    }

    private fun validatePropertyEnum(
        prop: JsonObject,
        propName: String,
        issues: MutableList<String>,
    ) {
        val enumField = prop["enum"]
        if (enumField != null && enumField !is JsonArray) {
            issues.add("Property '$propName' has invalid 'enum' field (must be array)")
        }
    }

    private fun validatePropertyDefault(
        prop: JsonObject,
        propName: String,
        issues: MutableList<String>,
    ) {
        val type = prop["type"]?.jsonPrimitive?.content ?: return
        val defaultValue = prop["default"] ?: return

        when (type) {
            "string" -> {
                if (defaultValue !is JsonPrimitive || !defaultValue.isString) {
                    issues.add("Property '$propName' has non-string default for string type")
                }
            }
            "number" -> {
                if (defaultValue !is JsonPrimitive || defaultValue.doubleOrNull == null) {
                    issues.add("Property '$propName' has non-number default for number type")
                }
            }
            "boolean" -> {
                if (defaultValue !is JsonPrimitive || defaultValue.booleanOrNull == null) {
                    issues.add("Property '$propName' has non-boolean default for boolean type")
                }
            }
        }
    }

    private fun validateEnumDefault(
        prop: JsonObject,
        propName: String,
        issues: MutableList<String>,
    ) {
        val enumField = prop["enum"]
        val defaultValue = prop["default"]
        if (enumField is JsonArray && defaultValue != null) {
            val enumValues = enumField.map { it.jsonPrimitive.content }
            val defaultStr = defaultValue.jsonPrimitive.content
            if (defaultStr !in enumValues) {
                issues.add(
                    "Property '$propName' default '$defaultStr' not in enum values: $enumValues",
                )
            }
        }
    }

    private fun validateNestedObject(
        prop: JsonObject,
        propName: String,
        issues: MutableList<String>,
    ) {
        val type = prop["type"]?.jsonPrimitive?.content
        if (type == "object" && !prop.containsKey("properties")) {
            issues.add(
                "Property '$propName' is type 'object' but missing 'properties' definition",
            )
        }
    }

    private fun validateDescription(
        prop: JsonObject,
        propName: String,
        issues: MutableList<String>,
    ) {
        val description = prop["description"]
        if (description != null && (description !is JsonPrimitive || !description.isString)) {
            issues.add("Property '$propName' has non-string description")
        }
    }

    private fun validateRequiredField(
        schema: JsonObject,
        properties: JsonObject?,
        issues: MutableList<String>,
    ) {
        val required = schema["required"] ?: return
        if (required !is JsonArray) {
            issues.add("'required' field must be an array")
            return
        }
        required.forEach { item ->
            if (item !is JsonPrimitive || !item.isString) {
                issues.add("'required' array contains non-string value")
            }
            val reqField = item.jsonPrimitive.content
            if (properties != null && !properties.containsKey(reqField)) {
                issues.add("Required field '$reqField' not found in properties")
            }
        }
    }

    private fun printIssues(
        index: Int,
        toolName: String,
        schema: JsonObject,
        issues: List<String>,
    ) {
        println("Tool $index ($toolName) - ${issues.size} issues:")
        issues.forEach { println("  - $it") }
        println()

        if (index == 15) {
            println("Full schema for tool 15:")
            println(
                Json { prettyPrint = true }
                    .encodeToString(JsonObject.serializer(), schema),
            )
            println()
        }
    }
}
