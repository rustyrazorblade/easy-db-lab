package com.rustyrazorblade.easydblab.commands.clickhouse

/**
 * Tests for ClickHouseStart command.
 *
 * Note: The business logic for shard/replica configuration has been extracted
 * to ClickHouseConfigService and is tested in ClickHouseConfigServiceTest.
 *
 * This command is a thin orchestration layer that:
 * - Validates cluster state (control nodes, db nodes)
 * - Delegates to K8sService for manifest application
 * - Delegates to ClickHouseConfigService for config generation
 *
 * Integration-level command tests would require mocking K8sService and
 * cluster state, which is better suited for end-to-end testing.
 */
class ClickHouseStartTest
