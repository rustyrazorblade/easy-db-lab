## ADDED Requirements

### Requirement: Library works in the cluster namespace

Every library function that calls the cluster (the gauge push's collector pod lookup and its pod-proxy URL, the Helm install, scale and uninstall helpers, and the `cluster-config` read) SHALL act in the namespace named by `NAMESPACE`, never in a fixed namespace. A function that needs it SHALL fail fast when `NAMESPACE` is not set, with `NAMESPACE is not set`, using a bash-3.2-safe `${NAMESPACE:?}` check. The gauge push SHALL use the same `NAMESPACE` for the pod lookup and for the pod-proxy path.

#### Scenario: Gauge push in the cluster namespace

- **WHEN** `NAMESPACE=lab-a` and `edl_push_gauges` runs
- **THEN** it looks up the core collector pod in `lab-a` and posts to `/api/v1/namespaces/lab-a/pods/<pod>:4318/proxy/v1/metrics`

#### Scenario: Helm helper in the cluster namespace

- **WHEN** `NAMESPACE=lab-a` and a library Helm helper installs or uninstalls a release
- **THEN** it passes `--namespace lab-a`

#### Scenario: NAMESPACE missing

- **WHEN** a library function that calls the cluster runs with no `NAMESPACE` set
- **THEN** it exits non-zero with `NAMESPACE is not set` and makes no cluster call

#### Scenario: NAMESPACE check under bash 3.2

- **WHEN** the library's namespace check runs under `/bin/bash` 3.2 with `set -u`
- **THEN** it behaves as under a current bash
