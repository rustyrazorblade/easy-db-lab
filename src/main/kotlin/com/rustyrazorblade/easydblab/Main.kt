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

    // Register shutdown hook for telemetry flush
    Runtime.getRuntime().addShutdownHook(
        Thread {
            try {
                val telemetry = getKoin().get<TelemetryProvider>()
                telemetry.shutdown()
            } catch (e: Exception) {
                // Ignore errors during shutdown
            }
        },
    )

    val parser = CommandLineParser()
    try {
        parser.eval(arguments)
    } catch (e: DockerException) {
        log.error(e) { "Docker connection error" }
        with(TermColors()) {
            println(red("There was an error connecting to docker.  Please check if it is running."))
        }
        exitProcess(Constants.ExitCodes.ERROR)
    } catch (e: java.rmi.RemoteException) {
        log.error(e) { "Remote execution error" }
        with(TermColors()) {
            println(red("There was an error executing the remote command.  Try rerunning it."))
        }
        exitProcess(Constants.ExitCodes.ERROR)
    } catch (e: IllegalArgumentException) {
        log.error(e) { "Invalid argument provided" }
        with(TermColors()) { println(red("Invalid argument: ${e.message}")) }
        exitProcess(Constants.ExitCodes.ERROR)
    } catch (e: IllegalStateException) {
        log.error(e) { "Invalid state encountered" }
        with(TermColors()) { println(red("Invalid state: ${e.message}")) }
        exitProcess(Constants.ExitCodes.ERROR)
    } catch (e: StsException) {
        // Handle AWS STS credential validation errors
        if (e.statusCode() == Constants.HttpStatus.FORBIDDEN) {
            // Permission denied - credentials are valid but lack permissions
            log.error(e) { "AWS permission denied" }
            // The error message with policy has already been displayed by AWS.checkPermissions()
            with(TermColors()) {
                println(red("\nApplication cannot continue without required AWS permissions."))
                println(yellow("For detailed error information, check the logs (logs/info.log by default)"))
            }
        } else {
            // Authentication error - invalid or missing credentials
            log.error(e) { "AWS credential authentication failed" }
            // The error message has already been displayed by AWS.checkPermissions()
            with(TermColors()) {
                println(red("\nApplication cannot continue without valid AWS credentials."))
                println(yellow("For detailed error information, check the logs (logs/info.log by default)"))
            }
        }
        exitProcess(Constants.ExitCodes.ERROR)
    } catch (e: S3Exception) {
        // Handle S3-specific permission errors
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
    } catch (e: Ec2Exception) {
        // Handle EC2-specific permission errors
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
    } catch (e: SdkServiceException) {
        // Catch other AWS service errors (403 Forbidden status code)
        if (e.statusCode() == Constants.HttpStatus.FORBIDDEN) {
            log.error(e) { "AWS permission error" }
            // The error message with policy has already been displayed by the service layer
            with(TermColors()) {
                println(red("\nApplication cannot continue without required AWS permissions."))
                println(yellow("For detailed error information, check the logs (logs/info.log by default)"))
            }
            exitProcess(Constants.ExitCodes.ERROR)
        }
        throw e
    } catch (e: RuntimeException) {
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
}
