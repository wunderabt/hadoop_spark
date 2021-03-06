/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package poc;

import java.io.IOException;
import java.io.File;
import java.nio.ByteBuffer;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.nio.channels.FileChannel;

import org.apache.avro.Schema;
import org.apache.avro.file.DataFileConstants;
import org.apache.avro.mapred.AvroKey;
import org.apache.avro.mapred.AvroValue;
import org.apache.avro.mapreduce.AvroJob;
import org.apache.avro.mapreduce.AvroKeyInputFormat;
import org.apache.avro.mapreduce.AvroKeyValueOutputFormat;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.avro.file.DataFileConstants;

import example.avro.mp3;

public class MapReduceMD5sum extends Configured implements Tool {

  public static class MD5sumMapper extends
      Mapper<AvroKey<mp3>, NullWritable, AvroKey<String>, AvroValue<ByteBuffer>> {

    @Override
    public void map(AvroKey<mp3> key, NullWritable value, Context context)
        throws IOException, InterruptedException {

      CharSequence mp3_name = key.datum().getName();
      ByteBuffer mp3_content = key.datum().getFile();

      // write the file to disk
      File file = new File(mp3_name.toString() + ".out");
      FileChannel write_channel = new FileOutputStream(file, false).getChannel();
      write_channel.write(mp3_content);
      write_channel.close();

      // run an external command on it
      //Process p = Runtime.getRuntime().exec("md5sum " + mp3_name.toString() + ".out > " + mp3_name.toString() + ".md5");
      //Process p = Runtime.getRuntime().exec(System.getProperty("user.dir") + "/md5sumw.sh " + mp3_name.toString() + ".out");
      Process p = Runtime.getRuntime().exec("gzip " + mp3_name.toString() + ".out");
      p.waitFor();

      // read the output and add as value
      long zipfile_length = new File(mp3_name.toString() + ".out.gz").length();
      FileInputStream zipfile = new FileInputStream(mp3_name.toString() + ".out.gz");
      ByteBuffer zipfile_content = ByteBuffer.allocate((int) zipfile_length);
      zipfile.getChannel().read(zipfile_content);

      //System.out.println("WRITTEN " + zipfile_content.position() +
      //                   " / " + zipfile_content.limit() +
      //                   " / " + zipfile_content.capacity());
      zipfile_content.flip(); // like python: seek(0)
      context.write(new AvroKey<String>(mp3_name.toString()),
                    new AvroValue<ByteBuffer>(zipfile_content));
      zipfile.close();
    }
  }

  public static class MD5sumReducer extends
      Reducer<AvroKey<String>, AvroValue<ByteBuffer>, AvroKey<String>, AvroValue<Integer>> {

    @Override
    public void reduce(AvroKey<String> key, Iterable<AvroValue<ByteBuffer>> values,
        Context context) throws IOException, InterruptedException {

      int bytelength = -1;
      for (AvroValue<ByteBuffer> buf : values) {
        //System.out.println("REDUCING!!!! -- " + buf);
        bytelength = buf.datum().capacity();
      }
      context.write(new AvroKey<String>(key.toString()), new AvroValue<Integer>(bytelength));
    }
  }

  public int run(String[] args) throws Exception {
    if (args.length != 2) {
      System.err.println("Usage: MapReduceMD5sum <input path> <output path>");
      return -1;
    }

    Job job = new Job(getConf());
    job.setJarByClass(MapReduceMD5sum.class);
    job.setJobName("MD5sum");

    FileInputFormat.setInputPaths(job, new Path(args[0]));
    FileOutputFormat.setOutputPath(job, new Path(args[1]));
    // use "deflate" = LZW compression for all avros
    FileOutputFormat.setCompressOutput(job, true);
    job.getConfiguration().set(AvroJob.CONF_OUTPUT_CODEC, DataFileConstants.DEFLATE_CODEC);

    job.setInputFormatClass(AvroKeyInputFormat.class);
    job.setMapperClass(MD5sumMapper.class);
    AvroJob.setInputKeySchema(job, mp3.getClassSchema());
    AvroJob.setMapOutputKeySchema(job, Schema.create(Schema.Type.STRING));
    AvroJob.setMapOutputValueSchema(job, Schema.create(Schema.Type.BYTES));

    job.setOutputFormatClass(AvroKeyValueOutputFormat.class);
    job.setReducerClass(MD5sumReducer.class);
    AvroJob.setOutputKeySchema(job, Schema.create(Schema.Type.STRING));
    AvroJob.setOutputValueSchema(job, Schema.create(Schema.Type.INT));

    
    job.setNumReduceTasks(1); // TODO: couldn't we use more than a single reducer?

    return (job.waitForCompletion(true) ? 0 : 1);
  }

  public static void main(String[] args) throws Exception {
    int res = ToolRunner.run(new MapReduceMD5sum(), args);
    System.exit(res);
  }
}
