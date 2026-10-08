package com.rustyrazorblade.easydblab.providers.ssm

import com.rustyrazorblade.easydblab.Constants
import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.providers.aws.AWSCredentialsManager
import org.koin.dsl.module
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ssm.SsmClient
import java.time.Duration

/**
 * Koin module for the SSM Session Manager SSH transport.
 *
 * Provides SsmSessionCommandBuilder, bound to the profile's region and AWS identity, and the
 * SsmSessionTerminator that ends a stopped forward's session, signing as that same identity. The route
 * that uses it is chosen by transport in the SSH module. The binding is inert until the `ssm`
 * route asks for it, and the credentials file is only written once a command is built.
 */
val ssmModule =
    module {
        single {
            val user = get<User>()
            val credentialsManager = AWSCredentialsManager(get<Context>().profileDir, get<AwsCredentialsProvider>())
            val profileDir = get<Context>().profileDir
            SsmSessionCommandBuilder(
                user.region,
                { SsmCliCredentials.forUser(user, credentialsManager) },
                sshProxyWrapper = { SsmProxyWrapper(profileDir).install() },
            )
        }

        single<SsmSessionTerminator> {
            val region = Region.of(get<User>().region)
            val credentials = get<AwsCredentialsProvider>()
            // The client is built on first use, off the main thread, not here at Koin resolution.
            SdkSsmSessionTerminator(credentials) {
                SsmClient
                    .builder()
                    .region(region)
                    .credentialsProvider(credentials)
                    // Bounded per attempt and overall, so the SDK's retries fit in the stop's 20s budget.
                    .overrideConfiguration { config ->
                        config
                            .apiCallAttemptTimeout(Duration.ofSeconds(Constants.Ssm.TERMINATE_SESSION_ATTEMPT_TIMEOUT_SECONDS))
                            .apiCallTimeout(Duration.ofSeconds(Constants.Ssm.TERMINATE_SESSION_TIMEOUT_SECONDS))
                    }.build()
            }
        }
    }
