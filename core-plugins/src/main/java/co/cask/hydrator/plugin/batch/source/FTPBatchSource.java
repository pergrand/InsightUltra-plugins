/*
 * Copyright © 2016 Cask Data, Inc.
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

import co.cask.cdap.api.annotation.Description;
import co.cask.cdap.api.annotation.Macro;
import co.cask.cdap.api.annotation.Name;
import co.cask.cdap.api.annotation.Plugin;
import co.cask.cdap.etl.api.batch.BatchSource;
import co.cask.hydrator.common.ReferencePluginConfig;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * {@link BatchSource} that reads from an FTP or SFTP server.
 */
@Plugin(type = "batchsource")
@Name("FTP")
@Description("Batch source for an FTP or SFTP source. Prefix of the path ('ftp://...' or 'sftp://...') determines " +
  "the source server type, either FTP or SFTP.")
public class FTPBatchSource extends FileBatchSource {
  private static final String PATH_DESCRIPTION = "Path to file(s) to be read. Path is expected to be of the form " +
    "'prefix://username:password@hostname:port/path'. The path uses filename expansion (globbing) to read files.";
  private static final Gson GSON = new Gson();
  private static final Type MAP_STRING_STRING_TYPE = new TypeToken<Map<String, String>>() { }.getType();

  @SuppressWarnings("unused")
  private final FTPBatchSourceConfig config;

  public FTPBatchSource(FTPBatchSourceConfig config) {
    super(new FileBatchConfig(config.referenceName, config.fileRegex, null, config.inputFormatClassName,
                              limitSplits(config.fileSystemProperties), Long.MAX_VALUE,
                              config.ignoreNonExistingFolders, config.recursive, config.path, null, false, null));
    this.config = config;
  }

  // Limit the number of splits to 1 since FTPInputStream does not support seek;
  private static String limitSplits(@Nullable String fsProperties) {
    Map<String, String> providedProperties;
    if (fsProperties == null) {
      providedProperties = new HashMap<>();
    } else {
      providedProperties = GSON.fromJson(fsProperties, MAP_STRING_STRING_TYPE);
    }
    providedProperties.put(FileInputFormat.SPLIT_MINSIZE, Long.toString(Long.MAX_VALUE));
    return GSON.toJson(providedProperties);
  }

  /**
   * Config class that contains all the properties needed for FTP Batch Source.
   */
  public static class FTPBatchSourceConfig extends ReferencePluginConfig {
    @Description(PATH_DESCRIPTION)
    @Macro
    public String path;

    @Description("A JSON string representing a map of properties needed for the distributed file system.")
    @Nullable
    @Macro
    public String fileSystemProperties;

    @Description("Regex to filter out files in the path. Defaults to '.*'")
    @Nullable
    @Macro
    public String fileRegex;

    @Description("Name of the input format class, which must be a subclass of FileInputFormat. " +
      "Defaults to a CombinePathTrackingInputFormat, which is a customized version of CombineTextInputFormat " +
      "that records the file path each record was read from.")
    @Nullable
    @Macro
    public String inputFormatClassName;

    @Nullable
    @Description("Identify if path needs to be ignored or not, for case when directory or file does not exists. If " +
      "set to true it will treat the not present folder as zero input and log a warning. Default is false.")
    public Boolean ignoreNonExistingFolders;

    @Nullable
    @Description("Boolean value to determine if files are to be read recursively from the path. Default is false.")
    public Boolean recursive;

    public FTPBatchSourceConfig(String referenceName, String path, @Nullable String fileSystemProperties,
                                @Nullable String fileRegex, @Nullable String inputFormatClassName,
                                @Nullable Boolean ignoreNonExistingFolders, @Nullable Boolean recursive) {
      super(referenceName);
      this.path = path;
      this.fileSystemProperties = fileSystemProperties;
      this.fileRegex = fileRegex;
      this.inputFormatClassName = inputFormatClassName;
      this.ignoreNonExistingFolders = ignoreNonExistingFolders == null ? false : ignoreNonExistingFolders;
      this.recursive = recursive == null ? false : recursive;
    }
  }
}
