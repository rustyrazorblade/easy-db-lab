package com.rustyrazorblade.easydblab.configuration

// Extension functions for ClusterS3Path providing convenience methods
// for cluster configuration file paths under the config/ directory.
//
// These are pure path builders that delegate to ClusterS3Path.resolve.

/** Path for K3s kubeconfig file. */
fun ClusterS3Path.kubeconfig(): ClusterS3Path = resolve(ClusterS3Path.CONFIG_DIR).resolve(ClusterS3Path.KUBECONFIG_FILE)

/** Path for Kubernetes manifests directory. */
fun ClusterS3Path.k8s(): ClusterS3Path = resolve(ClusterS3Path.CONFIG_DIR).resolve(ClusterS3Path.K8S_DIR)

/** Path for cluster configuration files directory. */
fun ClusterS3Path.config(): ClusterS3Path = resolve(ClusterS3Path.CONFIG_DIR)

/** Path for Cassandra patch configuration file. */
fun ClusterS3Path.cassandraPatch(): ClusterS3Path = resolve(ClusterS3Path.CONFIG_DIR).resolve(ClusterS3Path.CASSANDRA_PATCH_FILE)

/** Path for Cassandra configuration directory (local cassandra/ dir). */
fun ClusterS3Path.cassandraConfig(): ClusterS3Path = resolve(ClusterS3Path.CONFIG_DIR).resolve(ClusterS3Path.CASSANDRA_CONFIG_DIR)

/** Path for cassandra_versions.yaml file. */
fun ClusterS3Path.cassandraVersions(): ClusterS3Path = resolve(ClusterS3Path.CONFIG_DIR).resolve(ClusterS3Path.CASSANDRA_VERSIONS_FILE)

/** Path for env.sh file (main environment script with SSH aliases, kubectl, etc.). */
fun ClusterS3Path.envScript(): ClusterS3Path = resolve(ClusterS3Path.CONFIG_DIR).resolve(ClusterS3Path.ENV_SCRIPT_FILE)

/** Path for environment.sh file (stress environment variables). */
fun ClusterS3Path.environmentScript(): ClusterS3Path = resolve(ClusterS3Path.CONFIG_DIR).resolve(ClusterS3Path.ENVIRONMENT_FILE)

/** Path for setup_instance.sh file. */
fun ClusterS3Path.setupInstanceScript(): ClusterS3Path = resolve(ClusterS3Path.CONFIG_DIR).resolve(ClusterS3Path.SETUP_INSTANCE_FILE)

/** Path for state.json file. */
fun ClusterS3Path.stateJson(): ClusterS3Path = resolve(ClusterS3Path.CONFIG_DIR).resolve(ClusterS3Path.STATE_JSON_FILE)
