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

package org.apache.hadoop.hbase.mapreduce.hadoopbackport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import junit.framework.TestCase;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.SmallTests;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.RawComparator;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.io.WritableComparator;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.mapred.JobConf;
import org.junit.experimental.categories.Category;

/**
 * The test is ported from Hadoop branch-0.23 with very small changes.
 */
@Category(SmallTests.class)
public class TestTotalOrderPartitioner extends TestCase {

  private static final Text[] splitStrings = new Text[] {
    // -inf            // 0
    new Text("aabbb"), // 1
    new Text("babbb"), // 2
    new Text("daddd"), // 3
    new Text("dddee"), // 4
    new Text("ddhee"), // 5
    new Text("dingo"), // 6
    new Text("hijjj"), // 7
    new Text("n"),     // 8
    new Text("yak"),   // 9
  };

  static class Check<T> {
    T data;
    int part;
    Check(T data, int part) {
      this.data = data;
      this.part = part;
    }
  }

  private static final ArrayList<Check<Text>> testStrings =
    new ArrayList<Check<Text>>();
  static {
    testStrings.add(new Check<Text>(new Text("aaaaa"), 0));
    testStrings.add(new Check<Text>(new Text("aaabb"), 0));
    testStrings.add(new Check<Text>(new Text("aabbb"), 1));
    testStrings.add(new Check<Text>(new Text("aaaaa"), 0));
    testStrings.add(new Check<Text>(new Text("babbb"), 2));
    testStrings.add(new Check<Text>(new Text("baabb"), 1));
    testStrings.add(new Check<Text>(new Text("yai"), 8));
    testStrings.add(new Check<Text>(new Text("yak"), 9));
    testStrings.add(new Check<Text>(new Text("z"), 9));
    testStrings.add(new Check<Text>(new Text("ddngo"), 5));
    testStrings.add(new Check<Text>(new Text("hi"), 6));
  };

  private static <T extends WritableComparable<?>> Path writePartitionFile(
      String testname, Configuration conf, T[] splits) throws IOException {
    final FileSystem fs = FileSystem.getLocal(conf);
    final Path testdir = new Path(System.getProperty("test.build.data", "/tmp")
                                 ).makeQualified(fs);
    Path p = new Path(testdir, testname + "/_partition.lst");
    TotalOrderPartitioner.setPartitionFile(conf, p);
    conf.setInt("mapreduce.job.reduces", splits.length + 1);
    SequenceFile.Writer w = null;
    try {
      w = SequenceFile.createWriter(fs, conf, p,
          splits[0].getClass(), NullWritable.class,
          SequenceFile.CompressionType.NONE);
      for (int i = 0; i < splits.length; ++i) {
        w.append(splits[i], NullWritable.get());
      }
    } finally {
      if (null != w)
        w.close();
    }
    return p;
  }

  public void testTotalOrderMemCmp() throws Exception {
    TotalOrderPartitioner<Text,NullWritable> partitioner =
      new TotalOrderPartitioner<Text,NullWritable>();

    // Need to use old JobConf-based variant here:
    JobConf conf = new JobConf();
    conf.setMapOutputKeyClass(Text.class);
    conf.setNumReduceTasks(splitStrings.length + 1);

    Path p = TestTotalOrderPartitioner.<Text>writePartitionFile(
        "totalordermemcmp", conf, splitStrings);
    try {
      partitioner.setConf(conf);
      NullWritable nw = NullWritable.get();
      for (Check<Text> chk : testStrings) {
        assertEquals(chk.data.toString(), chk.part,
            partitioner.getPartition(chk.data, nw, splitStrings.length + 1));
      }
    } finally {
      p.getFileSystem(conf).delete(p, true);
    }
  }

  public void testTotalOrderBinarySearch() throws Exception {
    TotalOrderPartitioner<Text,NullWritable> partitioner =
      new TotalOrderPartitioner<Text,NullWritable>();
    JobConf conf = new JobConf();
    conf.setMapOutputKeyClass(Text.class);
    conf.setNumReduceTasks(splitStrings.length + 1);

    Path p = TestTotalOrderPartitioner.<Text>writePartitionFile(
        "totalorderbinarysearch", conf, splitStrings);
    conf.setBoolean(TotalOrderPartitioner.NATURAL_ORDER, false);
    try {
      partitioner.setConf(conf);
      NullWritable nw = NullWritable.get();
      for (Check<Text> chk : testStrings) {
        assertEquals(chk.data.toString(), chk.part,
            partitioner.getPartition(chk.data, nw, splitStrings.length + 1));
      }
    } finally {
      p.getFileSystem(conf).delete(p, true);
    }
  }

  public static class ReverseStringComparator implements RawComparator<Text> {
    @Override
    public int compare(Text a, Text b) {
      return -a.compareTo(b);
    }
    @Override
    public int compare(byte[] b1, int s1, int l1, byte[] b2, int s2, int l2) {
      int n1 = WritableUtils.decodeVIntSize(b1[s1]);
      int n2 = WritableUtils.decodeVIntSize(b2[s2]);
      return -1 * WritableComparator.compareBytes(b1, s1+n1, l1-n1,
                                                  b2, s2+n2, l2-n2);
    }
  }

  public void testTotalOrderCustomComparator() throws Exception {
    TotalOrderPartitioner<Text,NullWritable> partitioner =
      new TotalOrderPartitioner<Text,NullWritable>();

    final JobConf conf = new JobConf();
    conf.setMapOutputKeyClass(Text.class);
    conf.setNumReduceTasks(splitStrings.length + 1);

    Text[] revSplitStrings = Arrays.copyOf(splitStrings, splitStrings.length);
    Arrays.sort(revSplitStrings, new ReverseStringComparator());
    Path p = TestTotalOrderPartitioner.<Text>writePartitionFile(
        "totalordercustomcomparator", conf, revSplitStrings);
    conf.setBoolean(TotalOrderPartitioner.NATURAL_ORDER, false);
    conf.setOutputKeyComparatorClass(ReverseStringComparator.class);

    ArrayList<Check<Text>> revCheck = new ArrayList<Check<Text>>();
    revCheck.add(new Check<Text>(new Text("aaaaa"), 9));
    revCheck.add(new Check<Text>(new Text("aaabb"), 9));
    revCheck.add(new Check<Text>(new Text("aabbb"), 9));
    revCheck.add(new Check<Text>(new Text("aaaaa"), 9));
    revCheck.add(new Check<Text>(new Text("babbb"), 8));
    revCheck.add(new Check<Text>(new Text("baabb"), 8));
    revCheck.add(new Check<Text>(new Text("yai"), 1));
    revCheck.add(new Check<Text>(new Text("yak"), 1));
    revCheck.add(new Check<Text>(new Text("z"), 0));
    revCheck.add(new Check<Text>(new Text("ddngo"), 4));
    revCheck.add(new Check<Text>(new Text("hi"), 3));
    try {
      partitioner.setConf(conf);
      NullWritable nw = NullWritable.get();
      for (Check<Text> chk : revCheck) {
        assertEquals(chk.data.toString(), chk.part,
            partitioner.getPartition(chk.data, nw, splitStrings.length + 1));
      }
    } finally {
      p.getFileSystem(conf).delete(p, true);
    }
  }
}
