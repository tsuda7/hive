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

package org.apache.hadoop.hive.metastore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.SerDeInfo;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.serde2.columnar.LazyBinaryColumnarSerDe;
import org.apache.hadoop.util.StringUtils;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.collect.ImmutableMap;

public class TestReplChangeManager {
  private static HiveMetaStoreClient client;
  private static HiveConf hiveConf;
  private static Warehouse warehouse;
  private static MiniDFSCluster m_dfs;
  private static String cmroot;

  @BeforeClass
  public static void setUp() throws Exception {
    m_dfs = new MiniDFSCluster.Builder(new Configuration()).numDataNodes(1).format(true).build();
    hiveConf = new HiveConf(TestReplChangeManager.class);
    hiveConf.set(HiveConf.ConfVars.METASTOREWAREHOUSE.varname,
        "hdfs://" + m_dfs.getNameNode().getHostAndPort() + HiveConf.ConfVars.METASTOREWAREHOUSE.defaultStrVal);
    hiveConf.setBoolean(HiveConf.ConfVars.REPLCMENABLED.varname, true);
    cmroot = "hdfs://" + m_dfs.getNameNode().getHostAndPort() + "/cmroot";
    hiveConf.set(HiveConf.ConfVars.REPLCMDIR.varname, cmroot);
    hiveConf.setInt(CommonConfigurationKeysPublic.FS_TRASH_INTERVAL_KEY, 60);
    warehouse = new Warehouse(hiveConf);
    try {
      client = new HiveMetaStoreClient(hiveConf);
    } catch (Throwable e) {
      System.err.println("Unable to open the metastore");
      System.err.println(StringUtils.stringifyException(e));
      throw e;
    }
  }

  @AfterClass
  public static void tearDown() throws Exception {
    try {
      m_dfs.shutdown();
      client.close();
    } catch (Throwable e) {
      System.err.println("Unable to close metastore");
      System.err.println(StringUtils.stringifyException(e));
      throw e;
    }
  }

  private Partition createPartition(String dbName, String tblName,
      List<FieldSchema> columns, List<String> partVals, SerDeInfo serdeInfo) {
    StorageDescriptor sd = new StorageDescriptor(columns, null,
        "org.apache.hadoop.hive.ql.io.orc.OrcInputFormat",
        "org.apache.hadoop.hive.ql.io.orc.OrcOutputFormat",
        false, 0, serdeInfo, null, null, null);
    return new Partition(partVals, dbName, tblName, 0, 0, sd, null);
  }

  private void createFile(Path path, String content) throws IOException {
    FSDataOutputStream output = path.getFileSystem(hiveConf).create(path);
    output.writeChars(content);
    output.close();
  }

  @Test
  public void testRecyclePartTable() throws Exception {
    // Create db1/t1/dt=20160101/part
    //              /dt=20160102/part
    //              /dt=20160103/part
    // Test: recycle single file (dt=20160101/part)
    //       recycle single partition (dt=20160102)
    //       recycle table t1
    String dbName = "db1";
    client.dropDatabase(dbName, true, true);

    Database db = new Database();
    db.setName(dbName);
    client.createDatabase(db);

    String tblName = "t1";
    List<FieldSchema> columns = new ArrayList<FieldSchema>();
    columns.add(new FieldSchema("foo", "string", ""));
    columns.add(new FieldSchema("bar", "string", ""));

    List<FieldSchema> partColumns = new ArrayList<FieldSchema>();
    partColumns.add(new FieldSchema("dt", "string", ""));

    SerDeInfo serdeInfo = new SerDeInfo("LBCSerDe", LazyBinaryColumnarSerDe.class.getCanonicalName(), new HashMap<String, String>());

    StorageDescriptor sd
      = new StorageDescriptor(columns, null,
          "org.apache.hadoop.hive.ql.io.orc.OrcInputFormat",
          "org.apache.hadoop.hive.ql.io.orc.OrcOutputFormat",
    false, 0, serdeInfo, null, null, null);
    Map<String, String> tableParameters = new HashMap<String, String>();

    Table tbl = new Table(tblName, dbName, "", 0, 0, 0, sd, partColumns, tableParameters, "", "", "");

    client.createTable(tbl);

    List<String> values = Arrays.asList("20160101");
    Partition part1 = createPartition(dbName, tblName, columns, values, serdeInfo);
    client.add_partition(part1);

    values = Arrays.asList("20160102");
    Partition part2 = createPartition(dbName, tblName, columns, values, serdeInfo);
    client.add_partition(part2);

    values = Arrays.asList("20160103");
    Partition part3 = createPartition(dbName, tblName, columns, values, serdeInfo);
    client.add_partition(part3);

    Path part1Path = new Path(warehouse.getPartitionPath(db, tblName, ImmutableMap.of("dt", "20160101")), "part");
    createFile(part1Path, "p1");
    String path1Chksum = ReplChangeManager.getCksumString(part1Path, hiveConf);

    Path part2Path = new Path(warehouse.getPartitionPath(db, tblName, ImmutableMap.of("dt", "20160102")), "part");
    createFile(part2Path, "p2");
    String path2Chksum = ReplChangeManager.getCksumString(part2Path, hiveConf);

    Path part3Path = new Path(warehouse.getPartitionPath(db, tblName, ImmutableMap.of("dt", "20160103")), "part");
    createFile(part3Path, "p3");
    String path3Chksum = ReplChangeManager.getCksumString(part3Path, hiveConf);

    Assert.assertTrue(part1Path.getFileSystem(hiveConf).exists(part1Path));
    Assert.assertTrue(part2Path.getFileSystem(hiveConf).exists(part2Path));
    Assert.assertTrue(part3Path.getFileSystem(hiveConf).exists(part3Path));

    ReplChangeManager cm = ReplChangeManager.getInstance(hiveConf);
    // verify cm.recycle(db, table, part) api moves file to cmroot dir
    int ret = cm.recycle(part1Path, false);
    Assert.assertEquals(ret, 1);
    Path cmPart1Path = ReplChangeManager.getCMPath(part1Path, hiveConf, path1Chksum);
    Assert.assertTrue(cmPart1Path.getFileSystem(hiveConf).exists(cmPart1Path));

    // Verify dropPartition recycle part files
    client.dropPartition(dbName, tblName, Arrays.asList("20160102"));
    Assert.assertFalse(part2Path.getFileSystem(hiveConf).exists(part2Path));
    Path cmPart2Path = ReplChangeManager.getCMPath(part2Path, hiveConf, path2Chksum);
    Assert.assertTrue(cmPart2Path.getFileSystem(hiveConf).exists(cmPart2Path));

    // Verify dropTable recycle partition files
    client.dropTable(dbName, tblName);
    Assert.assertFalse(part3Path.getFileSystem(hiveConf).exists(part3Path));
    Path cmPart3Path = ReplChangeManager.getCMPath(part3Path, hiveConf, path3Chksum);
    Assert.assertTrue(cmPart3Path.getFileSystem(hiveConf).exists(cmPart3Path));

    client.dropDatabase(dbName, true, true);
  }

  @Test
  public void testRecycleNonPartTable() throws Exception {
    // Create db2/t1/part1
    //              /part2
    //              /part3
    // Test: recycle single file (part1)
    //       recycle table t1
    String dbName = "db2";
    client.dropDatabase(dbName, true, true);

    Database db = new Database();
    db.setName(dbName);
    client.createDatabase(db);

    String tblName = "t1";
    List<FieldSchema> columns = new ArrayList<FieldSchema>();
    columns.add(new FieldSchema("foo", "string", ""));
    columns.add(new FieldSchema("bar", "string", ""));

    SerDeInfo serdeInfo = new SerDeInfo("LBCSerDe", LazyBinaryColumnarSerDe.class.getCanonicalName(), new HashMap<String, String>());

    StorageDescriptor sd
      = new StorageDescriptor(columns, null,
          "org.apache.hadoop.hive.ql.io.orc.OrcInputFormat",
          "org.apache.hadoop.hive.ql.io.orc.OrcOutputFormat",
    false, 0, serdeInfo, null, null, null);
    Map<String, String> tableParameters = new HashMap<String, String>();

    Table tbl = new Table(tblName, dbName, "", 0, 0, 0, sd, null, tableParameters, "", "", "");

    client.createTable(tbl);

    Path filePath1 = new Path(warehouse.getTablePath(db, tblName), "part1");
    createFile(filePath1, "f1");
    String fileChksum1 = ReplChangeManager.getCksumString(filePath1, hiveConf);

    Path filePath2 = new Path(warehouse.getTablePath(db, tblName), "part2");
    createFile(filePath2, "f2");
    String fileChksum2 = ReplChangeManager.getCksumString(filePath2, hiveConf);

    Path filePath3 = new Path(warehouse.getTablePath(db, tblName), "part3");
    createFile(filePath3, "f3");
    String fileChksum3 = ReplChangeManager.getCksumString(filePath3, hiveConf);

    Assert.assertTrue(filePath1.getFileSystem(hiveConf).exists(filePath1));
    Assert.assertTrue(filePath2.getFileSystem(hiveConf).exists(filePath2));
    Assert.assertTrue(filePath3.getFileSystem(hiveConf).exists(filePath3));

    ReplChangeManager cm = ReplChangeManager.getInstance(hiveConf);
    // verify cm.recycle(Path) api moves file to cmroot dir
    cm.recycle(filePath1, false);
    Assert.assertFalse(filePath1.getFileSystem(hiveConf).exists(filePath1));

    Path cmPath1 = ReplChangeManager.getCMPath(filePath1, hiveConf, fileChksum1);
    Assert.assertTrue(cmPath1.getFileSystem(hiveConf).exists(cmPath1));

    // Verify dropTable recycle table files
    client.dropTable(dbName, tblName);

    Path cmPath2 = ReplChangeManager.getCMPath(filePath2, hiveConf, fileChksum2);
    Assert.assertFalse(filePath2.getFileSystem(hiveConf).exists(filePath2));
    Assert.assertTrue(cmPath2.getFileSystem(hiveConf).exists(cmPath2));

    Path cmPath3 = ReplChangeManager.getCMPath(filePath3, hiveConf, fileChksum3);
    Assert.assertFalse(filePath3.getFileSystem(hiveConf).exists(filePath3));
    Assert.assertTrue(cmPath3.getFileSystem(hiveConf).exists(cmPath3));

    client.dropDatabase(dbName, true, true);
  }

  @Test
  public void testClearer() throws Exception {
    FileSystem fs = warehouse.getWhRoot().getFileSystem(hiveConf);
    long now = System.currentTimeMillis();
    Path dirDb = new Path(warehouse.getWhRoot(), "db3");
    fs.mkdirs(dirDb);
    Path dirTbl1 = new Path(dirDb, "tbl1");
    fs.mkdirs(dirTbl1);
    Path part11 = new Path(dirTbl1, "part1");
    createFile(part11, "testClearer11");
    String fileChksum11 = ReplChangeManager.getCksumString(part11, hiveConf);
    Path part12 = new Path(dirTbl1, "part2");
    createFile(part12, "testClearer12");
    String fileChksum12 = ReplChangeManager.getCksumString(part12, hiveConf);
    Path dirTbl2 = new Path(dirDb, "tbl2");
    fs.mkdirs(dirTbl2);
    Path part21 = new Path(dirTbl2, "part1");
    createFile(part21, "testClearer21");
    String fileChksum21 = ReplChangeManager.getCksumString(part21, hiveConf);
    Path part22 = new Path(dirTbl2, "part2");
    createFile(part22, "testClearer22");
    String fileChksum22 = ReplChangeManager.getCksumString(part22, hiveConf);
    Path dirTbl3 = new Path(dirDb, "tbl3");
    fs.mkdirs(dirTbl3);
    Path part31 = new Path(dirTbl3, "part1");
    createFile(part31, "testClearer31");
    String fileChksum31 = ReplChangeManager.getCksumString(part31, hiveConf);
    Path part32 = new Path(dirTbl3, "part2");
    createFile(part32, "testClearer32");
    String fileChksum32 = ReplChangeManager.getCksumString(part32, hiveConf);

    ReplChangeManager.getInstance(hiveConf).recycle(dirTbl1, false);
    ReplChangeManager.getInstance(hiveConf).recycle(dirTbl2, false);
    ReplChangeManager.getInstance(hiveConf).recycle(dirTbl3, true);

    Assert.assertTrue(fs.exists(ReplChangeManager.getCMPath(part11, hiveConf, fileChksum11)));
    Assert.assertTrue(fs.exists(ReplChangeManager.getCMPath(part12, hiveConf, fileChksum12)));
    Assert.assertTrue(fs.exists(ReplChangeManager.getCMPath(part21, hiveConf, fileChksum21)));
    Assert.assertTrue(fs.exists(ReplChangeManager.getCMPath(part22, hiveConf, fileChksum22)));
    Assert.assertTrue(fs.exists(ReplChangeManager.getCMPath(part31, hiveConf, fileChksum31)));
    Assert.assertTrue(fs.exists(ReplChangeManager.getCMPath(part32, hiveConf, fileChksum32)));

    fs.setTimes(ReplChangeManager.getCMPath(part11, hiveConf, fileChksum11), now - 86400*1000*2, now - 86400*1000*2);
    fs.setTimes(ReplChangeManager.getCMPath(part21, hiveConf, fileChksum21), now - 86400*1000*2, now - 86400*1000*2);
    fs.setTimes(ReplChangeManager.getCMPath(part31, hiveConf, fileChksum31), now - 86400*1000*2, now - 86400*1000*2);
    fs.setTimes(ReplChangeManager.getCMPath(part32, hiveConf, fileChksum32), now - 86400*1000*2, now - 86400*1000*2);

    ReplChangeManager.scheduleCMClearer(hiveConf);

    long start = System.currentTimeMillis();
    long end;
    boolean cleared = false;
    do {
      Thread.sleep(200);
      end = System.currentTimeMillis();
      if (end - start > 5000) {
        Assert.fail("timeout, cmroot has not been cleared");
      }
      if (!fs.exists(ReplChangeManager.getCMPath(part11, hiveConf, fileChksum11)) &&
          fs.exists(ReplChangeManager.getCMPath(part12, hiveConf, fileChksum12)) &&
          !fs.exists(ReplChangeManager.getCMPath(part21, hiveConf, fileChksum21)) &&
          fs.exists(ReplChangeManager.getCMPath(part22, hiveConf, fileChksum22)) &&
          !fs.exists(ReplChangeManager.getCMPath(part31, hiveConf, fileChksum31)) &&
          !fs.exists(ReplChangeManager.getCMPath(part31, hiveConf, fileChksum31))) {
        cleared = true;
      }
    } while (!cleared);
  }
}
