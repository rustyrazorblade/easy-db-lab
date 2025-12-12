package com.rustyrazorblade.easydblab.annotations

/**
 * Marks a command class to trigger incremental backup after successful execution.
 * Commands with this annotation will automatically upload changed configuration files
 * to S3 after the command completes successfully.
 *
 * The backup is incremental - only files that have changed since the last backup
 * (based on SHA-256 hash comparison) will be uploaded.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class TriggerBackup
