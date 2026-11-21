package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet

/** A [GrafanaRelease] with nothing in it, for tests that mock [GrafanaDeployService]. */
val EMPTY_GRAFANA_RELEASE = GrafanaRelease(TenantSet.of("default"), emptyList(), emptySet())
