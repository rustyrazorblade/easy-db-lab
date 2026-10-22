package com.rustyrazorblade.easydblab.export

import java.io.File

/**
 * Splits Loki's shared index files into per-tenant files, on a local copy of the index.
 *
 * Loki's ingesters write the index of every tenant they received into shared multi-tenant files at the
 * root of each index table, named after the uploading ingester (its tenant and cluster). Only Loki's
 * compactor splits them into `<table>/<tenant>/` files. A tenant export copies the shared files with
 * the tenant's own, splits them, and keeps only the tenant's files (owner decision 22), so the zip holds
 * nothing of another tenant.
 */
fun interface LokiIndexSplit {
    /**
     * Splits every shared file in the tables of the filesystem store [store] (which holds
     * `loki/index/<table>/`) into per-tenant files, and removes it. [project] is a directory of the
     * split's own, for whatever it runs and the logs it keeps.
     */
    fun split(
        store: File,
        project: File,
    )
}
