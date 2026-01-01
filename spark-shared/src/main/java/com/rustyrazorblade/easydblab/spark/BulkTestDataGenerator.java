package com.rustyrazorblade.easydblab.spark;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

import static org.apache.spark.sql.functions.expr;

/**
 * Generates test data for bulk write performance testing with a clustered table schema:
 * - partition_id: bigint (distributed across configurable number of partitions)
 * - sequence_id: bigint (sequential ordering within each partition)
 * - course: blob (16 bytes, deterministic from row id)
 * - marks: bigint (0-99, deterministic from row id)
 *
 * Uses DataFrame API with spark.range() for lazy evaluation - supports TB-scale
 * data generation (tens of billions of rows) without memory pressure.
 *
 * Data is deterministic: the same row id always produces the same values,
 * enabling reproducible tests.
 */
public class BulkTestDataGenerator implements DataGenerator {

    @Override
    public StructType getSchema() {
        return new StructType()
            .add("partition_id", DataTypes.LongType, false)
            .add("sequence_id", DataTypes.LongType, false)
            .add("course", DataTypes.BinaryType, false)
            .add("marks", DataTypes.LongType, false);
    }

    @Override
    public Dataset<Row> generate(SparkSession spark, long rowCount, int parallelism, long partitionCount) {
        // spark.range() is lazy - generates data on-the-fly without memory pressure
        // Each row gets a unique id from 0 to rowCount-1
        return spark.range(0, rowCount, 1, parallelism)
            // Distribute rows evenly across Cassandra partitions
            .withColumn("partition_id", expr("id % " + partitionCount))
            // Sequential ordering within each partition
            .withColumn("sequence_id", expr("floor(id / " + partitionCount + ")"))
            // Deterministic 16-byte blob from MD5 hash (32 hex chars = 16 bytes)
            .withColumn("course", expr("unhex(substring(md5(cast(id as string)), 1, 32))"))
            // Deterministic marks value 0-99
            .withColumn("marks", expr("abs(hash(id)) % 100"))
            // Remove the original range column, keep only our schema columns
            .drop("id");
    }
}
