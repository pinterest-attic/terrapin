package com.pinterest.terrapin.hadoop;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.Compression;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.output.FileOutputCommitter;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

import java.io.IOException;

/**
 * HFileOutputFormat for outputting fingerprint mod sharded HFiles using a mapreduce
 * job.
 */
public class HFileOutputFormat extends FileOutputFormat<BytesWritable, BytesWritable> {

    /**
     * Returns the compression string. Defaults to SNAPPY compression.
     *
     * @param compressionString One of SNAPPY, GZ, LZO, LZ4 or NONE.
     * @return The corresponding Compression.Algorithm enum type.
     */
    public static Compression.Algorithm getAlgorithm(String compressionString) {
        Compression.Algorithm compressionAlgo = Compression.Algorithm.SNAPPY;
        if (compressionString == null) {
            return compressionAlgo;
        }
        try {
            compressionAlgo = Compression.Algorithm.valueOf(compressionString);
        } catch (Throwable t) {
            // Use the default.
            return compressionAlgo;
        }
        return compressionAlgo;
    }

    public RecordWriter<BytesWritable, BytesWritable> getRecordWriter(
            TaskAttemptContext context) throws IOException {
    // Get the path of the temporary output file
    final Path outputPath = FileOutputFormat.getOutputPath(context);
    final Path outputDir = new FileOutputCommitter(outputPath, context).getWorkPath();
    final Configuration conf = context.getConfiguration();
    final FileSystem fs = outputDir.getFileSystem(conf);

    int blockSize = conf.getInt("hfile.blocksize", 16384);
    // Default to snappy.
    Compression.Algorithm compressionAlgorithm = getAlgorithm(
                conf.get("hfile.compression", "SNAPPY"));
        final StoreFile.Writer writer = new StoreFile.WriterBuilder(conf, new CacheConfig(conf),
                fs, blockSize).
                withFilePath(new Path(outputPath, "part-"
                        + String.format("%05d", context.getTaskAttemptID().getTaskID().getId()))).
                withCompression(compressionAlgorithm).
                build();
        RecordWriter<BytesWritable, BytesWritable> recordWriter =
                new RecordWriter<BytesWritable, BytesWritable>() {
                    @Override
                    public void write(BytesWritable key, BytesWritable value)
                            throws IOException, InterruptedException {
                        // Mapreduce reuses the same Text objects and hence sometimes they have some
                        // additional left over garbage at the end from previous keys. So we need to
                        // retrieve the String objects which do not have the additional garbage. getBytes()
                        // call does not work well.
                        byte[] row = new byte[key.getLength()];
                        byte[] val = new byte[value.getLength()];
                        for (int i = 0; i < row.length; i++) {
                            row[i] = key.getBytes()[i];
                        }
                        for (int i = 0; i < val.length; i++) {
                            val[i] = value.getBytes()[i];
                        }
                        writer.append(new KeyValue(row,
                                Bytes.toBytes("cf"),
                                Bytes.toBytes(""),
                                val));
                    }

                    @Override
                    public void close(TaskAttemptContext taskAttemptContext)
                            throws IOException, InterruptedException {
                        writer.close();
                    }
                };
        return recordWriter;
    }
}