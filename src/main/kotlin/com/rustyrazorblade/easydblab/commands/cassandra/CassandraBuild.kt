package com.rustyrazorblade.easydblab.commands.cassandra

import com.rustyrazorblade.easydblab.annotations.RequireProfileSetup
import com.rustyrazorblade.easydblab.commands.PicoBaseCommand
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.events.Event
import com.rustyrazorblade.easydblab.services.CassandraBuildCatalog
import com.rustyrazorblade.easydblab.services.CassandraBuildService
import org.koin.core.component.inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File

/**
 * Builds a Cassandra branch checkout on this machine and publishes it to the profile's S3 bucket.
 *
 * Exists for the committer's inner loop: build what you are working on, then install it by name
 * onto a real cluster. The build is named for what it is — base version, ticket, an optional
 * `--name` label, date, sha and the JDK it was built under — so a bucket holding a month of builds
 * is still readable.
 *
 * Needs no cluster. The account bucket belongs to the profile, so a build made today installs onto
 * whatever cluster exists next week.
 */
@RequireProfileSetup
@Command(
    name = "build",
    description = ["Build a Cassandra branch locally and publish it to S3"],
)
class CassandraBuild : PicoBaseCommand() {
    private val buildService: CassandraBuildService by inject()
    private val catalog: CassandraBuildCatalog by inject()
    private val userConfig: User by inject()

    @Parameters(
        index = "0",
        arity = "0..1",
        defaultValue = ".",
        description = ["Cassandra source checkout to build (default: current directory)"],
    )
    var sourceDir: String = "."

    @Option(
        names = ["--java", "-j"],
        required = true,
        description = ["JDK major version to build with, e.g. 11, 17, 21"],
    )
    lateinit var javaVersion: String

    @Option(
        names = ["--jira"],
        description = ["Ticket this build is for, e.g. CASSANDRA-19000"],
    )
    var jira: String = ""

    @Option(
        names = ["--name"],
        description = ["Label folded into the build's name, to tell your builds apart at a glance"],
    )
    var label: String = ""

    @Option(
        names = ["--ant-flags"],
        description = ["Extra flags passed to ant"],
    )
    var antFlags: String = ""

    override fun execute() {
        val result =
            buildService.build(
                CassandraBuildService.Request(
                    sourceDir = File(sourceDir),
                    javaVersion = javaVersion,
                    jira = jira.ifBlank { null },
                    label = label.ifBlank { null },
                    antFlags = antFlags.ifBlank { null },
                    builtBy = userConfig.email,
                ),
            )

        val location = catalog.publish(result.manifest, result.tarball)
        eventBus.emit(Event.Cassandra.BuildPublished(result.manifest.name, location.toUri()))
    }
}
