package com.rustyrazorblade.easydblab.annotations

/**
 * Marks a command class as available for MCP (Model Context Protocol) server.
 * Commands with this annotation will be automatically registered as MCP tools in the server.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class McpCommand
