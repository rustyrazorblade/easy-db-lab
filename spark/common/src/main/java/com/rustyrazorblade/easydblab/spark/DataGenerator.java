package com.rustyrazorblade.easydblab.spark;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.StructType;

/**
 * Interface for generating test data for Spark write performance comparison.
 * Implementations should use deterministic column expressions for reproducibility.
 *
 * Uses DataFrame API with lazy evaluation for TB-scale data generation without
 * memory pressure.
 */
public interface DataGenerator {
    /**
     * Returns the schema for the generated data.
     */
    StructType getSchema();

    /**
     * Generates test data as a DataFrame using lazy evaluation.
     *
     * @param spark SparkSession for DataFrame creation
     * @param rowCount Total number of rows to generate (supports billions)
     * @param parallelism Number of Spark partitions for parallel generation
     * @param partitionCount Number of Cassandra partitions to distribute data across
     * @return DataFrame matching getSchema()
     */
    Dataset<Row> generate(SparkSession spark, long rowCount, int parallelism, long partitionCount);
}
