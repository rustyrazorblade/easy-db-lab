package com.rustyrazorblade.easydblab

import com.github.ajalt.mordant.TermColors
import com.github.dockerjava.api.exception.DockerException
import com.rustyrazorblade.easydblab.di.KoinModules
import com.rustyrazorblade.easydblab.observability.TelemetryProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.getKoin
import software.amazon.awssdk.core.exception.SdkServiceException
import software.amazon.awssdk.services.ec2.model.Ec2Exception
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.sts.model.StsException
import kotlin.system.exitProcess

private val log = KotlinLogging.logger {}

@Suppress("TooGenericExceptionCaught")
fun main(arguments: Array<String>) {
    // Initialize Koin dependency injection
    startKoin {
        modules(KoinModules.getAllModules())
    }

    registerShutdownHook()

    val parser = CommandLineParser()
    try {
        parser.eval(arguments)
    } catch (e: DockerException) {
        handleDockerError(e)
    } catch (e: java.rmi.RemoteException) {
        handleRemoteError(e)
    } catch (e: IllegalArgumentException) {
        handleIllegalArgumentError(e)
    } catch (e: IllegalStateException) {
        handleIllegalStateError(e)
    } catch (e: StsException) {
        handleStsError(e)
    } catch (e: S3Exception) {
        handleS3Error(e)
    } catch (e: Ec2Exception) {
        handleEc2Error(e)
    } catch (e: SdkServiceException) {
        handleSdkServiceError(e)
    } catch (e: RuntimeException) {
        handleRuntimeError(e)
    }
}

private fun registerShutdownHook() {
    Runtime.getRuntime().addShutdownHook(
        Thread {
            try {
                val telemetry = getKoin().get<TelemetryProvider>()
                telemetry.shutdown()
            } catch (_: Exception) {
                // Ignore errors during shutdown
            }
        },
    )
}

private fun handleDockerError(e: DockerException) {
    log.error(e) { "Docker connection error" }
    with(TermColors()) {
        println(red("There was an error connecting to docker.  Please check if it is running."))
    }
    exitProcess(Constants.ExitCodes.ERROR)
}

private fun handleRemoteError(e: java.rmi.RemoteException) {
    log.error(e) { "Remote execution error" }
    with(TermColors()) {
        println(red("There was an error executing the remote command.  Try rerunning it."))
    }
    exitProcess(Constants.ExitCodes.ERROR)
}

private fun handleIllegalArgumentError(e: IllegalArgumentException) {
    log.error(e) { "Invalid argument provided" }
    with(TermColors()) { println(red("Invalid argument: ${e.message}")) }
    exitProcess(Constants.ExitCodes.ERROR)
}

private fun handleIllegalStateError(e: IllegalStateException) {
    log.error(e) { "Invalid state encountered" }
    with(TermColors()) { println(red("Invalid state: ${e.message}")) }
    exitProcess(Constants.ExitCodes.ERROR)
}

private fun handleStsError(e: StsException) {
    if (e.statusCode() == Constants.HttpStatus.FORBIDDEN) {
        log.error(e) { "AWS permission denied" }
        with(TermColors()) {
            println(red("\nApplication cannot continue without required AWS permissions."))
            println(yellow("For detailed error information, check the logs (logs/info.log by default)"))
        }
    } else {
        log.error(e) { "AWS credential authentication failed" }
        with(TermColors()) {
            println(red("\nApplication cannot continue without valid AWS credentials."))
            println(yellow("For detailed error information, check the logs (logs/info.log by default)"))
        }
    }
    exitProcess(Constants.ExitCodes.ERROR)
}

private fun handleS3Error(e: S3Exception) {
    if (e.statusCode() == Constants.HttpStatus.FORBIDDEN) {
        log.error(e) { "S3 permission error" }
        with(TermColors()) {
            println(red("\n\nS3 Permission Error: ${e.awsErrorDetails().errorMessage()}"))
            println(red("\nYou need to add the EasyDBLabEC2 IAM policy which includes S3 permissions."))
            println(yellow("\nFor detailed error information, check the logs (logs/info.log by default)"))
        }
        exitProcess(Constants.ExitCodes.ERROR)
    }
    throw e
}

private fun handleEc2Error(e: Ec2Exception) {
    if (e.statusCode() == Constants.HttpStatus.FORBIDDEN) {
        log.error(e) { "EC2 permission error" }
        with(TermColors()) {
            println(red("\n\nEC2 Permission Error: ${e.awsErrorDetails().errorMessage()}"))
            println(red("\nYou need to add the EasyDBLabEC2 IAM policy which includes EC2 permissions."))
            println(yellow("\nFor detailed error information, check the logs (logs/info.log by default)"))
        }
        exitProcess(Constants.ExitCodes.ERROR)
    }
    throw e
}

private fun handleSdkServiceError(e: SdkServiceException) {
    if (e.statusCode() == Constants.HttpStatus.FORBIDDEN) {
        log.error(e) { "AWS permission error" }
        with(TermColors()) {
            println(red("\nApplication cannot continue without required AWS permissions."))
            println(yellow("For detailed error information, check the logs (logs/info.log by default)"))
        }
        exitProcess(Constants.ExitCodes.ERROR)
    }
    throw e
}

private fun handleRuntimeError(e: RuntimeException) {
    log.error(e) { "A runtime exception has occurred" }
    with(TermColors()) {
        println(red("An unexpected error has occurred."))
        println(
            red(
                "Does this look like an error with easy-db-lab?  If so, please file a bug report at " +
                    "https://github.com/rustyrazorblade/easy-db-lab/ with the following information:",
            ),
        )
        println(e.message)
        println(yellow("For detailed error information including stack trace, check the logs (logs/info.log by default)"))
    }
    exitProcess(Constants.ExitCodes.ERROR)
}
