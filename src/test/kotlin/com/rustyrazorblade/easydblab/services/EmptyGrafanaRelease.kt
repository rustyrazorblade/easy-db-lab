package com.rustyrazorblade.easydblab.services

import com.rustyrazorblade.easydblab.configuration.grafana.TenantSet
import com.rustyrazorblade.easydblab.configuration.tenancy.TestScopes

/** A [GrafanaRelease] with nothing in it, for tests that mock [GrafanaDeployService]. */
val EMPTY_GRAFANA_RELEASE = GrafanaRelease(TenantSet.of("default"), emptyList(), emptySet(), TestScopes.EC2)
