package com.rustyrazorblade.easydblab.spark;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates test data matching the cassandra-analytics example schema:
 * - id: bigint (sequential, partitioned across executors)
 * - course: blob (16 random bytes)
 * - marks: bigint (0-99)
 *
 * Uses seeded Random for reproducibility - same rowCount and parallelism
 * will always produce the same data.
 */
public class BulkTestDataGenerator implements DataGenerator {

    private static final int COURSE_BLOB_SIZE = 16;
    private static final int MARKS_MAX = 100;

    @Override
    public StructType getSchema() {
        return new StructType()
            .add("id", DataTypes.LongType, false)
            .add("course", DataTypes.BinaryType, false)
            .add("marks", DataTypes.LongType, false);
    }

    @Override
    public JavaRDD<Row> generate(JavaSparkContext sc, int rowCount, int parallelism) {
        List<Long> seeds = new ArrayList<>();
        for (int i = 0; i < parallelism; i++) {
            seeds.add((long) i);
        }

        int rowsPerPartition = rowCount / parallelism;

        return sc.parallelize(seeds, parallelism).flatMap(seed -> {
            List<Row> rows = new ArrayList<>();
            Random random = new Random(seed);
            long startId = seed * rowsPerPartition;

            for (int i = 0; i < rowsPerPartition; i++) {
                long id = startId + i;
                byte[] courseBytes = new byte[COURSE_BLOB_SIZE];
                random.nextBytes(courseBytes);
                ByteBuffer course = ByteBuffer.wrap(courseBytes);
                long marks = random.nextInt(MARKS_MAX);
                rows.add(RowFactory.create(id, course.array(), marks));
            }
            return rows.iterator();
        });
    }
}
