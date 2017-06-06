/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.hydrator.plugin.batch.source;

import co.cask.cdap.api.artifact.ArtifactSummary;
import co.cask.cdap.api.artifact.ArtifactVersion;
import co.cask.cdap.api.data.format.StructuredRecord;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.api.dataset.table.Table;
import co.cask.cdap.datapipeline.DataPipelineApp;
import co.cask.cdap.datapipeline.SmartWorkflow;
import co.cask.cdap.etl.api.batch.BatchSource;
import co.cask.cdap.etl.mock.batch.MockSink;
import co.cask.cdap.etl.mock.test.HydratorTestBase;
import co.cask.cdap.etl.proto.v2.ETLBatchConfig;
import co.cask.cdap.etl.proto.v2.ETLPlugin;
import co.cask.cdap.etl.proto.v2.ETLStage;
import co.cask.cdap.proto.ProgramRunStatus;
import co.cask.cdap.proto.artifact.AppRequest;
import co.cask.cdap.proto.id.ApplicationId;
import co.cask.cdap.proto.id.ArtifactId;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.test.ApplicationManager;
import co.cask.cdap.test.DataSetManager;
import co.cask.cdap.test.TestConfiguration;
import co.cask.cdap.test.WorkflowManager;
import co.cask.hydrator.common.Constants;
import co.cask.hydrator.plugin.common.Properties;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.apache.commons.io.FileUtils;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Tests to verify configuration of {@link FileBatchSource}
 */
public class FileBatchSourceTest extends HydratorTestBase {

  @ClassRule
  public static final TestConfiguration CONFIG = new TestConfiguration("explore.enabled", false);
  private static final ArtifactVersion CURRENT_VERSION = new ArtifactVersion("3.4.0-SNAPSHOT");
  private static final ArtifactId BATCH_APP_ARTIFACT_ID =
    NamespaceId.DEFAULT.artifact("data-pipeline", CURRENT_VERSION.getVersion());
  private static final ArtifactSummary BATCH_ARTIFACT =
    new ArtifactSummary(BATCH_APP_ARTIFACT_ID.getArtifact(), BATCH_APP_ARTIFACT_ID.getVersion());
  @ClassRule
  public static TemporaryFolder temporaryFolder = new TemporaryFolder();
  private static DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
  private static String fileName = dateFormat.format(new Date(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)));
  private static File file1;
  private static File file2;

  @BeforeClass
  public static void setupTest() throws Exception {
    setupBatchArtifacts(BATCH_APP_ARTIFACT_ID, DataPipelineApp.class);
    // add artifact for batch sources and sinks
    addPluginArtifact(NamespaceId.DEFAULT.artifact("core-plugins", "4.0.0"), BATCH_APP_ARTIFACT_ID,
                      FileBatchSource.class);

    file1 = temporaryFolder.newFolder("test").toPath().resolve(fileName + "-test1.txt").toFile();
    FileUtils.writeStringToFile(file1, "Hello,World");
    file2 = temporaryFolder.newFile(fileName + "-test2.txt");
    FileUtils.writeStringToFile(file2, "CDAP,Platform");
  }

  @AfterClass
  public static void tearDown() throws Exception {
    if (file1.exists()) {
      file1.delete();
    }
    if (file2.exists()) {
      file2.delete();
    }
    temporaryFolder.delete();
  }

  @Test
  public void testDefaults() {
    FileBatchSource.FileBatchConfig fileBatchConfig = new FileBatchSource.FileBatchConfig();
    Assert.assertEquals(ImmutableMap.<String, String>of(), fileBatchConfig.getFileSystemProperties());
    Assert.assertEquals(".*", fileBatchConfig.fileRegex);
    Assert.assertEquals(CombinePathTrackingInputFormat.class.getName(), fileBatchConfig.inputFormatClass);
    Assert.assertNotNull(fileBatchConfig.maxSplitSize);
    Assert.assertEquals(FileSourceConfig.DEFAULT_MAX_SPLIT_SIZE, (long) fileBatchConfig.maxSplitSize);
  }

  @Test
  public void testIgnoreNonExistingFolder() throws Exception {
    Map<String, String> sourceProperties = new ImmutableMap.Builder<String, String>()
      .put(Constants.Reference.REFERENCE_NAME, "TestCase")
      .put(Properties.File.PATH, "/src/test/resources/path_one/")
      .put(Properties.File.IGNORE_NON_EXISTING_FOLDERS, "true")
      .build();

    ETLStage source = new ETLStage("FileInput", new ETLPlugin("File", BatchSource.PLUGIN_TYPE, sourceProperties, null));

    String outputDatasetName = "ignore-non-existing-files";
    ETLStage sink = new ETLStage("sink", MockSink.getPlugin(outputDatasetName));

    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(source)
      .addStage(sink)
      .addConnection(source.getName(), sink.getName())
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(BATCH_ARTIFACT, etlConfig);
    ApplicationId appId = NamespaceId.DEFAULT.app("FileTest-ignore-non-existing-files");

    ApplicationManager appManager = deployApplication(appId.toId(), appRequest);

    WorkflowManager workflowManager = appManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForRuns(ProgramRunStatus.COMPLETED, 1, 5, TimeUnit.MINUTES);

    DataSetManager<Table> outputManager = getDataset(outputDatasetName);
    List<StructuredRecord> output = MockSink.readOutput(outputManager);

    Assert.assertEquals("Expected records", 0, output.size());
  }

  @Test
  public void testNotPresentFolder() throws Exception {
    Map<String, String> sourceProperties = new ImmutableMap.Builder<String, String>()
      .put(Constants.Reference.REFERENCE_NAME, "TestCase")
      .put(Properties.File.PATH, "/src/test/resources/path_one/")
      .put(Properties.File.IGNORE_NON_EXISTING_FOLDERS, "false")
      .build();

    ETLStage source = new ETLStage("FileInput", new ETLPlugin("File", BatchSource.PLUGIN_TYPE, sourceProperties, null));

    String outputDatasetName = "output-batchsourcetest";
    ETLStage sink = new ETLStage("sink", MockSink.getPlugin(outputDatasetName));

    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(source)
      .addStage(sink)
      .addConnection(source.getName(), sink.getName())
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(BATCH_ARTIFACT, etlConfig);
    ApplicationId appId = NamespaceId.DEFAULT.app("FileTest-not-present-folder");

    ApplicationManager appManager = deployApplication(appId.toId(), appRequest);

    WorkflowManager workflowManager = appManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForRuns(ProgramRunStatus.FAILED, 1, 5, TimeUnit.MINUTES);
  }

  @Test
  public void testRecursiveFolders() throws Exception {
    Map<String, String> sourceProperties = new ImmutableMap.Builder<String, String>()
      .put(Constants.Reference.REFERENCE_NAME, "TestCase")
      .put(Properties.File.PATH, "src/test/resources/")
      .put(Properties.File.FILE_REGEX, "[a-zA-Z0-9\\-:/_]*/x/[a-z0-9]*.txt$")
      .put(Properties.File.IGNORE_NON_EXISTING_FOLDERS, "false")
      .put(Properties.File.RECURSIVE, "true")
      .put("pathField", "file")
      .put("filenameOnly", "true")
      .build();

    ETLStage source = new ETLStage("FileInput", new ETLPlugin("File", BatchSource.PLUGIN_TYPE, sourceProperties, null));

    String outputDatasetName = "recursive-folders";
    ETLStage sink = new ETLStage("sink", MockSink.getPlugin(outputDatasetName));

    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(source)
      .addStage(sink)
      .addConnection(source.getName(), sink.getName())
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(BATCH_ARTIFACT, etlConfig);
    ApplicationId appId = NamespaceId.DEFAULT.app("FileTest-recursive-folders");

    ApplicationManager appManager = deployApplication(appId.toId(), appRequest);

    WorkflowManager workflowManager = appManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForRun(ProgramRunStatus.COMPLETED, 5, TimeUnit.MINUTES);

    DataSetManager<Table> outputManager = getDataset(outputDatasetName);

    Schema schema = PathTrackingInputFormat.getOutputSchema("file");
    Set<StructuredRecord> expected = ImmutableSet.of(
      StructuredRecord.builder(schema).set("offset", 0L).set("body", "Hello,World").set("file", "test1.txt").build(),
      StructuredRecord.builder(schema).set("offset", 0L).set("body", "CDAP,Platform").set("file", "test3.txt").build());
    Set<StructuredRecord> actual = new HashSet<>();
    actual.addAll(MockSink.readOutput(outputManager));
    Assert.assertEquals(expected, actual);
  }

  @Test
  public void testNonRecursiveRegex() throws Exception {
    Map<String, String> sourceProperties = new ImmutableMap.Builder<String, String>()
      .put(Constants.Reference.REFERENCE_NAME, "TestCase")
      .put(Properties.File.PATH, "src/test/resources/")
      .put(Properties.File.FILE_REGEX, ".+fileBatchSource.*")
      .put(Properties.File.IGNORE_NON_EXISTING_FOLDERS, "false")
      .put(Properties.File.RECURSIVE, "false")
      .build();

    ETLStage source = new ETLStage("FileInput", new ETLPlugin("File", BatchSource.PLUGIN_TYPE, sourceProperties, null));

    String outputDatasetName = "non-recursive-regex";
    ETLStage sink = new ETLStage("sink", MockSink.getPlugin(outputDatasetName));

    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(source)
      .addStage(sink)
      .addConnection(source.getName(), sink.getName())
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(BATCH_ARTIFACT, etlConfig);
    ApplicationId appId = NamespaceId.DEFAULT.app("FileTest-non-recursive-regex");

    ApplicationManager appManager = deployApplication(appId.toId(), appRequest);

    WorkflowManager workflowManager = appManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForRun(ProgramRunStatus.COMPLETED, 5, TimeUnit.MINUTES);

    DataSetManager<Table> outputManager = getDataset(outputDatasetName);
    List<StructuredRecord> output = MockSink.readOutput(outputManager);

    Assert.assertEquals("Expected records", 1, output.size());
    Set<String> outputValue = new HashSet<>();
    for (StructuredRecord record : output) {
      outputValue.add((String) record.get("body"));
    }
    Assert.assertTrue(outputValue.contains("CDAP,Platform"));
  }

  @Test
  public void testFileRegex() throws Exception {
    Map<String, String> sourceProperties = new ImmutableMap.Builder<String, String>()
      .put(Constants.Reference.REFERENCE_NAME, "TestCase")
      .put(Properties.File.PATH, "src/test/resources/test1/x/")
      .put(Properties.File.FILE_REGEX, ".+test.*")
      .put(Properties.File.IGNORE_NON_EXISTING_FOLDERS, "false")
      .put(Properties.File.RECURSIVE, "false")
      .build();

    ETLStage source = new ETLStage("FileInput", new ETLPlugin("File", BatchSource.PLUGIN_TYPE, sourceProperties, null));

    String outputDatasetName = "file-regex";
    ETLStage sink = new ETLStage("sink", MockSink.getPlugin(outputDatasetName));

    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(source)
      .addStage(sink)
      .addConnection(source.getName(), sink.getName())
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(BATCH_ARTIFACT, etlConfig);
    ApplicationId appId = NamespaceId.DEFAULT.app("FileTest-file-Regex");

    ApplicationManager appManager = deployApplication(appId.toId(), appRequest);

    WorkflowManager workflowManager = appManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForRun(ProgramRunStatus.COMPLETED, 5, TimeUnit.MINUTES);

    DataSetManager<Table> outputManager = getDataset(outputDatasetName);
    List<StructuredRecord> output = MockSink.readOutput(outputManager);

    Assert.assertEquals("Expected records", 1, output.size());
    Set<String> outputValue = new HashSet<>();
    for (StructuredRecord record : output) {
      outputValue.add((String) record.get("body"));
    }
    Assert.assertTrue(outputValue.contains("CDAP,Platform"));
  }

  @Test
  public void testRecursiveRegex() throws Exception {
    Map<String, String> sourceProperties = new ImmutableMap.Builder<String, String>()
      .put(Constants.Reference.REFERENCE_NAME, "TestCase")
      .put(Properties.File.PATH, "src/test/resources/")
      .put(Properties.File.FILE_REGEX, ".+fileBatchSource.*")
      .put(Properties.File.IGNORE_NON_EXISTING_FOLDERS, "false")
      .put(Properties.File.RECURSIVE, "true")
      .build();

    ETLStage source = new ETLStage("FileInput", new ETLPlugin("File", BatchSource.PLUGIN_TYPE, sourceProperties, null));

    String outputDatasetName = "recursive-regex";
    ETLStage sink = new ETLStage("sink", MockSink.getPlugin(outputDatasetName));

    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(source)
      .addStage(sink)
      .addConnection(source.getName(), sink.getName())
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(BATCH_ARTIFACT, etlConfig);
    ApplicationId appId = NamespaceId.DEFAULT.app("FileTest-recursive-regex");

    ApplicationManager appManager = deployApplication(appId.toId(), appRequest);

    WorkflowManager workflowManager = appManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForRun(ProgramRunStatus.COMPLETED, 5, TimeUnit.MINUTES);

    DataSetManager<Table> outputManager = getDataset(outputDatasetName);
    List<StructuredRecord> output = MockSink.readOutput(outputManager);

    Assert.assertEquals("Expected records", 2, output.size());
    Set<String> outputValue = new HashSet<>();
    for (StructuredRecord record : output) {
      outputValue.add((String) record.get("body"));
    }
    Assert.assertTrue(outputValue.contains("Hello,World"));
    Assert.assertTrue(outputValue.contains("CDAP,Platform"));
  }

  @Test
  public void testPathGlobbing() throws Exception {
    Map<String, String> sourceProperties = new ImmutableMap.Builder<String, String>()
      .put(Constants.Reference.REFERENCE_NAME, "TestCase")
      .put(Properties.File.PATH, "src/test/resources/*/x/")
      .put(Properties.File.FILE_REGEX, ".+.txt")
      .put(Properties.File.IGNORE_NON_EXISTING_FOLDERS, "false")
      .put(Properties.File.RECURSIVE, "false")
      .build();

    ETLStage source = new ETLStage("FileInput", new ETLPlugin("File", BatchSource.PLUGIN_TYPE, sourceProperties, null));

    String outputDatasetName = "path-globbing";
    ETLStage sink = new ETLStage("sink", MockSink.getPlugin(outputDatasetName));

    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(source)
      .addStage(sink)
      .addConnection(source.getName(), sink.getName())
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(BATCH_ARTIFACT, etlConfig);
    ApplicationId appId = NamespaceId.DEFAULT.app("FileTest-path-globbing");

    ApplicationManager appManager = deployApplication(appId.toId(), appRequest);

    WorkflowManager workflowManager = appManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForRun(ProgramRunStatus.COMPLETED, 5, TimeUnit.MINUTES);

    DataSetManager<Table> outputManager = getDataset(outputDatasetName);
    List<StructuredRecord> output = MockSink.readOutput(outputManager);

    Assert.assertEquals("Expected records", 2, output.size());
    Set<String> outputValue = new HashSet<>();
    for (StructuredRecord record : output) {
      outputValue.add((String) record.get("body"));
    }
    Assert.assertTrue(outputValue.contains("Hello,World"));
    Assert.assertTrue(outputValue.contains("CDAP,Platform"));
  }

  @Test
  public void testTimeFilterRegex() throws Exception {
    Map<String, String> sourceProperties = new ImmutableMap.Builder<String, String>()
      .put(Constants.Reference.REFERENCE_NAME, "TestCase")
      .put(Properties.File.PATH, file1.getParent().replaceAll("\\\\", "/"))
      .put(Properties.File.FILE_REGEX, "timefilter")
      .put(Properties.File.IGNORE_NON_EXISTING_FOLDERS, "false")
      .put(Properties.File.RECURSIVE, "false")
      .build();

    ETLStage source = new ETLStage("FileInput", new ETLPlugin("File", BatchSource.PLUGIN_TYPE, sourceProperties, null));

    String outputDatasetName = "time-filter";
    ETLStage sink = new ETLStage("sink", MockSink.getPlugin(outputDatasetName));

    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(source)
      .addStage(sink)
      .addConnection(source.getName(), sink.getName())
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(BATCH_ARTIFACT, etlConfig);
    ApplicationId appId = NamespaceId.DEFAULT.app("FileTest-timefilter-regex");

    ApplicationManager appManager = deployApplication(appId.toId(), appRequest);

    WorkflowManager workflowManager = appManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForRun(ProgramRunStatus.COMPLETED, 5, TimeUnit.MINUTES);

    DataSetManager<Table> outputManager = getDataset(outputDatasetName);
    List<StructuredRecord> output = MockSink.readOutput(outputManager);

    Assert.assertEquals("Expected records", 1, output.size());
    Assert.assertEquals("Hello,World", output.get(0).get("body"));
  }

  @Test
  public void testRecursiveTimeFilterRegex() throws Exception {
    Map<String, String> sourceProperties = new ImmutableMap.Builder<String, String>()
      .put(Constants.Reference.REFERENCE_NAME, "TestCase")
      .put(Properties.File.PATH, file1.getParentFile().getParent().replaceAll("\\\\", "/"))
      .put(Properties.File.FILE_REGEX, "timefilter")
      .put(Properties.File.IGNORE_NON_EXISTING_FOLDERS, "false")
      .put(Properties.File.RECURSIVE, "true")
      .build();

    ETLStage source = new ETLStage("FileInput", new ETLPlugin("File", BatchSource.PLUGIN_TYPE, sourceProperties, null));

    String outputDatasetName = "recursive-timefilter-regex";
    ETLStage sink = new ETLStage("sink", MockSink.getPlugin(outputDatasetName));

    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(source)
      .addStage(sink)
      .addConnection(source.getName(), sink.getName())
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(BATCH_ARTIFACT, etlConfig);
    ApplicationId appId = NamespaceId.DEFAULT.app("FileTest-recursive-timefilter-regex");

    ApplicationManager appManager = deployApplication(appId.toId(), appRequest);

    WorkflowManager workflowManager = appManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForRun(ProgramRunStatus.COMPLETED, 5, TimeUnit.MINUTES);

    DataSetManager<Table> outputManager = getDataset(outputDatasetName);
    List<StructuredRecord> output = MockSink.readOutput(outputManager);

    Assert.assertEquals("Expected records", 2, output.size());
    Set<String> outputValue = new HashSet<>();
    for (StructuredRecord record : output) {
      outputValue.add((String) record.get("body"));
    }
    Assert.assertTrue(outputValue.contains("Hello,World"));
    Assert.assertTrue(outputValue.contains("CDAP,Platform"));
  }
}
