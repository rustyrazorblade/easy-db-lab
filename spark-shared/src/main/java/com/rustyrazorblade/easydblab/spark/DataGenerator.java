package com.rustyrazorblade.easydblab.spark;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.types.StructType;

/**
 * Interface for generating test data for Spark write performance comparison.
 * Implementations should use seeded Random for reproducibility.
 */
public interface DataGenerator {
    /**
     * Returns the schema for the generated data.
     */
    StructType getSchema();

    /**
     * Generates test data as an RDD of Rows.
     *
     * @param sc Spark context
     * @param rowCount Total number of rows to generate
     * @param parallelism Number of partitions (also used as seed distribution)
     * @return RDD of Rows matching getSchema()
     */
    JavaRDD<Row> generate(JavaSparkContext sc, int rowCount, int parallelism);
}
