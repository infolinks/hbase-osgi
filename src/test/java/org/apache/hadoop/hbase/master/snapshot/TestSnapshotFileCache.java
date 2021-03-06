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
package org.apache.hadoop.hbase.master.snapshot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.Iterables;
import com.google.common.collect.ObjectArrays;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.MediumTests;
import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos.SnapshotDescription;
import org.apache.hadoop.hbase.snapshot.SnapshotDescriptionUtils;
import org.apache.hadoop.hbase.snapshot.SnapshotReferenceUtil;
import org.apache.hadoop.hbase.snapshot.TakeSnapshotUtils;
import org.apache.hadoop.hbase.util.FSUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Test that we correctly reload the cache, filter directories, etc.
 */
@Category(MediumTests.class)
public class TestSnapshotFileCache {

  private static final Log LOG = LogFactory.getLog(TestSnapshotFileCache.class);
  private static final HBaseTestingUtility UTIL = new HBaseTestingUtility();
  private static FileSystem fs;
  private static Path rootDir;

  @BeforeClass
  public static void startCluster() throws Exception {
    UTIL.startMiniDFSCluster(1);
    fs = UTIL.getDFSCluster().getFileSystem();
    rootDir = UTIL.getDefaultRootDirPath();
  }

  @AfterClass
  public static void stopCluster() throws Exception {
    UTIL.shutdownMiniDFSCluster();
  }

  @After
  public void cleanupFiles() throws Exception {
    // cleanup the snapshot directory
    Path snapshotDir = SnapshotDescriptionUtils.getSnapshotsDir(rootDir);
    fs.delete(snapshotDir, true);
  }

  @Test(timeout = 10000000)
  public void testLoadAndDelete() throws Exception {
    // don't refresh the cache unless we tell it to
    long period = Long.MAX_VALUE;
    Path snapshotDir = SnapshotDescriptionUtils.getSnapshotsDir(rootDir);
    SnapshotFileCache cache = new SnapshotFileCache(fs, rootDir, period, 10000000,
        "test-snapshot-file-cache-refresh", new SnapshotFiles());

    Path snapshot = new Path(snapshotDir, "snapshot");
    Path region = new Path(snapshot, "7e91021");
    Path family = new Path(region, "fam");
    Path file1 = new Path(family, "file1");
    Path file2 = new Path(family, "file2");


    // create two hfiles under the snapshot
    fs.createNewFile(file1);
    fs.createNewFile(file2);

    FSUtils.logFileSystemState(fs, rootDir, LOG);

    // then make sure the cache finds them
    Iterable<FileStatus> nonSnapshotFiles = cache.getUnreferencedFiles(
            Arrays.asList(FSUtils.listStatus(fs, family))
    );
    assertFalse("Cache didn't find:" + file1, Iterables.contains(nonSnapshotFiles, file1));
    assertFalse("Cache didn't find:" + file2, Iterables.contains(nonSnapshotFiles, file2));
    String not = "file-shouldn't-be-found";
    assertFalse("Cache found '" + not + "', but it shouldn't have.", Iterables.contains(nonSnapshotFiles, not));

    // make sure we get a little bit of separation in the modification times
    // its okay if we sleep a little longer (b/c of GC pause), as long as we sleep a little
    Thread.sleep(10);

    LOG.debug("Deleting snapshot.");
    // then delete the snapshot and make sure that we can still find the files
    if (!fs.delete(snapshot, true)) {
      throw new IOException("Couldn't delete " + snapshot + " for an unknown reason.");
    }
    FSUtils.logFileSystemState(fs, rootDir, LOG);


    LOG.debug("Checking to see if file is deleted.");
    nonSnapshotFiles = cache.getUnreferencedFiles(
            nonSnapshotFiles
    );
    
    assertFalse("Cache didn't find:" + file1, Iterables.contains(nonSnapshotFiles, file1));
    assertFalse("Cache didn't find:" + file2, Iterables.contains(nonSnapshotFiles, file2));

    // then trigger a refresh
    cache.triggerCacheRefreshForTesting();

    nonSnapshotFiles = cache.getUnreferencedFiles(
            nonSnapshotFiles
    );
    // and not it shouldn't find those files
    assertFalse("Cache found '" + file1 + "', but it shouldn't have.",
            Iterables.contains(nonSnapshotFiles, file1));
    assertFalse("Cache found '" + file2 + "', but it shouldn't have.",
            Iterables.contains(nonSnapshotFiles, file2));

    fs.delete(snapshotDir, true);
  }

  @Test
  public void testWeNeverCacheTmpDirAndLoadIt() throws Exception {

    final AtomicInteger count = new AtomicInteger(0);
    // don't refresh the cache unless we tell it to
    long period = Long.MAX_VALUE;
    Path snapshotDir = SnapshotDescriptionUtils.getSnapshotsDir(rootDir);
    SnapshotFileCache cache = new SnapshotFileCache(fs, rootDir, period, 10000000,
            "test-snapshot-file-cache-refresh", new SnapshotFiles()) {
      @Override
      List<String> getSnapshotsInProgress() throws IOException {
        List<String> result = super.getSnapshotsInProgress();
        count.incrementAndGet();
        return result;
      }
    };

    // create a file in a 'completed' snapshot
    Path snapshot = new Path(snapshotDir, "snapshot");
    Path region = new Path(snapshot, "7e91021");
    Path family = new Path(region, "fam");
    Path file1 = new Path(family, "file1");
    fs.createNewFile(file1);

    FileStatus[] completedFiles = FSUtils.listStatus(fs, family);

    // create an 'in progress' snapshot
    SnapshotDescription desc = SnapshotDescription.newBuilder().setName("working").build();
    snapshot = SnapshotDescriptionUtils.getWorkingSnapshotDir(desc, rootDir);
    region = new Path(snapshot, "7e91021");
    family = new Path(region, "fam");
    Path file2 = new Path(family, "file2");
    fs.createNewFile(file2);
    cache.triggerCacheRefreshForTesting();

    Iterable<FileStatus> deletableFiles = cache.getUnreferencedFiles(Arrays.asList(
            ObjectArrays.concat(completedFiles, FSUtils.listStatus(fs, family), FileStatus.class))
    );
    assertTrue(Iterables.isEmpty(deletableFiles));
    assertEquals(1, count.get()); // we check the tmp directory

    Path file3 = new Path(family, "file3");
    fs.create(file3);
    deletableFiles = cache.getUnreferencedFiles(Arrays.asList(
            ObjectArrays.concat(completedFiles, FSUtils.listStatus(fs, family), FileStatus.class))
    );
    assertTrue(Iterables.isEmpty(deletableFiles));
    assertEquals(2, count.get()); // we check the tmp directory

  }

  @Test
  public void testLoadsTmpDir() throws Exception {
    // don't refresh the cache unless we tell it to
    long period = Long.MAX_VALUE;
    Path snapshotDir = SnapshotDescriptionUtils.getSnapshotsDir(rootDir);
    SnapshotFileCache cache = new SnapshotFileCache(fs, rootDir, period, 10000000,
        "test-snapshot-file-cache-refresh", new SnapshotFiles());

    // create a file in a 'completed' snapshot
    Path snapshot = new Path(snapshotDir, "snapshot");
    Path region = new Path(snapshot, "7e91021");
    Path family = new Path(region, "fam");
    Path file1 = new Path(family, "file1");
    fs.createNewFile(file1);

    // create an 'in progress' snapshot
    SnapshotDescription desc = SnapshotDescription.newBuilder().setName("working").build();
    snapshot = SnapshotDescriptionUtils.getWorkingSnapshotDir(desc, rootDir);
    region = new Path(snapshot, "7e91021");
    family = new Path(region, "fam");
    Path file2 = new Path(family, "file2");
    fs.createNewFile(file2);

    FSUtils.logFileSystemState(fs, rootDir, LOG);

    // then make sure the cache finds both files
    Iterable<FileStatus> nonSnapshotFiles = cache.getUnreferencedFiles(
            Arrays.asList(FSUtils.listStatus(fs, family))
    );
    assertFalse("Cache didn't find:" + file1, Iterables.contains(nonSnapshotFiles, file1));
    assertFalse("Cache didn't find:" + file2, Iterables.contains(nonSnapshotFiles, file2));
  }

  @Test
  public void testJustFindLogsDirectory() throws Exception {
    // don't refresh the cache unless we tell it to
    long period = Long.MAX_VALUE;
    Path snapshotDir = SnapshotDescriptionUtils.getSnapshotsDir(rootDir);
    SnapshotFileCache cache = new SnapshotFileCache(fs, rootDir, period, 10000000,
        "test-snapshot-file-cache-refresh", new SnapshotFileCache.SnapshotFileInspector() {
            public Collection<String> filesUnderSnapshot(final Path snapshotDir)
                throws IOException {
              return SnapshotReferenceUtil.getHLogNames(fs, snapshotDir);
            }
        });

    // create a file in a 'completed' snapshot
    Path snapshot = new Path(snapshotDir, "snapshot");
    Path region = new Path(snapshot, "7e91021");
    Path family = new Path(region, "fam");
    Path file1 = new Path(family, "file1");
    fs.createNewFile(file1);

    // and another file in the logs directory
    Path logs = TakeSnapshotUtils.getSnapshotHLogsDir(snapshot, "server");
    Path log = new Path(logs, "me.hbase.com%2C58939%2C1350424310315.1350424315552");
    fs.createNewFile(log);

    FSUtils.logFileSystemState(fs, rootDir, LOG);

    Iterable<FileStatus> nonSnapshotFiles = cache.getUnreferencedFiles(
            Arrays.asList(FSUtils.listStatus(fs, family))
    );    
    // then make sure the cache only finds the log files
    assertFalse("Cache found '" + file1 + "', but it shouldn't have.",
            Iterables.contains(nonSnapshotFiles, file1));
    assertFalse("Cache didn't find:" + log, Iterables.contains(nonSnapshotFiles, log));
  }

  @Test
  public void testReloadModifiedDirectory() throws IOException {
    // don't refresh the cache unless we tell it to
    long period = Long.MAX_VALUE;
    Path snapshotDir = SnapshotDescriptionUtils.getSnapshotsDir(rootDir);
    SnapshotFileCache cache = new SnapshotFileCache(fs, rootDir, period, 10000000,
        "test-snapshot-file-cache-refresh", new SnapshotFiles());

    Path snapshot = new Path(snapshotDir, "snapshot");
    Path region = new Path(snapshot, "7e91021");
    Path family = new Path(region, "fam");
    Path file1 = new Path(family, "file1");
    Path file2 = new Path(family, "file2");

    // create two hfiles under the snapshot
    fs.createNewFile(file1);
    fs.createNewFile(file2);

    FSUtils.logFileSystemState(fs, rootDir, LOG);

    Iterable<FileStatus> nonSnapshotFiles = cache.getUnreferencedFiles(
            Arrays.asList(FSUtils.listStatus(fs, family))
    );  
    assertFalse("Cache didn't find " + file1, Iterables.contains(nonSnapshotFiles, file1));

    // now delete the snapshot and add a file with a different name
    fs.delete(snapshot, true);
    Path file3 = new Path(family, "new_file");
    fs.createNewFile(file3);

    FSUtils.logFileSystemState(fs, rootDir, LOG);
    nonSnapshotFiles = cache.getUnreferencedFiles(
            Arrays.asList(FSUtils.listStatus(fs, family))
    );
    assertFalse("Cache didn't find new file:" + file3, Iterables.contains(nonSnapshotFiles, file3));
  }

  @Test
  public void testSnapshotTempDirReload() throws IOException {
    long period = Long.MAX_VALUE;
    Path snapshotDir = new Path(SnapshotDescriptionUtils.getSnapshotsDir(rootDir),
        SnapshotDescriptionUtils.SNAPSHOT_TMP_DIR_NAME);
    SnapshotFileCache cache = new SnapshotFileCache(fs, rootDir, period, 10000000,
        "test-snapshot-file-cache-refresh", new SnapshotFiles());

    // Add a new snapshot
    Path snapshot1 = new Path(snapshotDir, "snapshot1");
    Path file1 = new Path(new Path(new Path(snapshot1, "7e91021"), "fam"), "file1");
    fs.createNewFile(file1);
    assertTrue(cache.getSnapshotsInProgress().contains(file1.getName()));

    // Add another snapshot
    Path snapshot2 = new Path(snapshotDir, "snapshot2");
    Path file2 = new Path(new Path(new Path(snapshot2, "7e91021"), "fam2"), "file2");
    fs.createNewFile(file2);
    assertTrue(cache.getSnapshotsInProgress().contains((file2.getName())));
  }

  class SnapshotFiles implements SnapshotFileCache.SnapshotFileInspector {
    public Collection<String> filesUnderSnapshot(final Path snapshotDir) throws IOException {
      Collection<String> files =  new HashSet<String>();
      files.addAll(SnapshotReferenceUtil.getHLogNames(fs, snapshotDir));
      files.addAll(SnapshotReferenceUtil.getHFileNames(fs, snapshotDir));
      return files;
    }
  };
}
