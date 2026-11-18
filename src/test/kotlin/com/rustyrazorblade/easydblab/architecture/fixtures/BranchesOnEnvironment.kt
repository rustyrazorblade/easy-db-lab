package com.rustyrazorblade.easydblab.architecture.fixtures

import com.rustyrazorblade.easydblab.configuration.environment.Environment

/**
 * A deliberate violation for [com.rustyrazorblade.easydblab.architecture.EnvironmentBranchingTest]: a
 * consumer that decides by the environment's variant instead of reading the cluster contract.
 */
class BranchesOnEnvironment {
    fun isLocal(environment: Environment): Boolean = environment is Environment.K3sDocker

    fun isAws(environment: Environment): Boolean = environment == Environment.Ec2K3s

    fun isBrought(environment: Environment): Boolean = environment::class == Environment.Kubeconfig::class
}
