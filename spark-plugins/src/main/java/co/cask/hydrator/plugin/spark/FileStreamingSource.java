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

package co.cask.hydrator.plugin.spark;

import co.cask.cdap.api.annotation.Description;
import co.cask.cdap.api.annotation.Macro;
import co.cask.cdap.api.annotation.Name;
import co.cask.cdap.api.annotation.Plugin;
import co.cask.cdap.api.data.format.FormatSpecification;
import co.cask.cdap.api.data.format.RecordFormat;
import co.cask.cdap.api.data.format.StructuredRecord;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.api.flow.flowlet.StreamEvent;
import co.cask.cdap.etl.api.PipelineConfigurer;
import co.cask.cdap.etl.api.streaming.StreamingContext;
import co.cask.cdap.etl.api.streaming.StreamingSource;
import co.cask.cdap.format.RecordFormats;
import co.cask.hydrator.common.ReferencePluginConfig;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import scala.Tuple2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Source that monitors a directory and reads files from it.
 */
@Plugin(type = StreamingSource.PLUGIN_TYPE)
@Name("File")
@Description("File streaming source. Streams data from files that are atomically moved into a specified directory.")
public class FileStreamingSource extends ReferenceStreamingSource<StructuredRecord> {
  private final Conf conf;

  public FileStreamingSource(Conf conf) {
    super(conf);
    this.conf = conf;
  }

  @Override
  public void configurePipeline(PipelineConfigurer pipelineConfigurer) throws IllegalArgumentException {
    super.configurePipeline(pipelineConfigurer);
    conf.validate();
    pipelineConfigurer.getStageConfigurer().setOutputSchema(conf.getSchema());
  }

  @Override
  public JavaDStream<StructuredRecord> getStream(StreamingContext context) throws Exception {
    conf.validate();
    context.registerLineage(conf.referenceName);

    JavaStreamingContext jsc = context.getSparkStreamingContext();

    Function<Path, Boolean> filter =
      conf.extensions == null ? new NoFilter() : new ExtensionFilter(conf.getExtensions());

    jsc.ssc().conf().set("spark.streaming.fileStream.minRememberDuration", conf.ignoreThreshold + "s");
    return jsc.fileStream(conf.path, LongWritable.class, Text.class,
                          TextInputFormat.class, filter, false)
      .map(new FormatFunction(conf.format, conf.schema));
  }

  /**
   * Doesn't filter any files.
   */
  private static class NoFilter implements Function<Path, Boolean> {
    @Override
    public Boolean call(Path path) throws Exception {
      return true;
    }
  }

  /**
   * Filters out files that don't have one of the supported extensions.
   */
  private static class ExtensionFilter implements Function<Path, Boolean> {
    private final Set<String> extensions;

    ExtensionFilter(Set<String> extensions) {
      this.extensions = extensions;
    }

    @Override
    public Boolean call(Path path) throws Exception {
      String extension = Files.getFileExtension(path.getName());
      return extensions.contains(extension);
    }
  }

  /**
   * Transforms kafka key and message into a structured record when message format and schema are given.
   * Everything here should be serializable, as Spark Streaming will serialize all functions.
   */
  private static class FormatFunction implements Function<Tuple2<LongWritable, Text>, StructuredRecord> {
    private final String format;
    private final String schemaStr;
    private transient Schema schema;
    private transient RecordFormat<StreamEvent, StructuredRecord> recordFormat;

    FormatFunction(String format, String schemaStr) {
      this.format = format;
      this.schemaStr = schemaStr;
    }

    @Override
    public StructuredRecord call(Tuple2<LongWritable, Text> in) throws Exception {
      // first time this was called, initialize schema and time, key, and message fields.
      if (recordFormat == null) {
        schema = Schema.parseJson(schemaStr);
        FormatSpecification spec = new FormatSpecification(format, schema, new HashMap<String, String>());
        recordFormat = RecordFormats.createInitializedFormat(spec);
      }

      StructuredRecord.Builder builder = StructuredRecord.builder(schema);
      StructuredRecord messageRecord = recordFormat.read(new StreamEvent(ByteBuffer.wrap(in._2().copyBytes())));
      for (Schema.Field messageField : messageRecord.getSchema().getFields()) {
        String fieldName = messageField.getName();
        builder.set(fieldName, messageRecord.get(fieldName));
      }
      return builder.build();
    }
  }

  /**
   * Configuration for the source.
   */
  public static class Conf extends ReferencePluginConfig {
    private static final Set<String> FORMATS = ImmutableSet.of("text", "csv", "tsv", "clf", "grok", "syslog");

    @Macro
    @Description("The format of the source files. Must be text, csv, tsv, clf, grok, or syslog. Defaults to text.")
    @Nullable
    private String format;

    @Description("The schema of the source files.")
    private String schema;

    @Macro
    @Description("The path to the directory containing source files to stream.")
    private String path;

    @Macro
    @Description("Ignore files after they are older than this many seconds. Defaults to 60.")
    @Nullable
    private Integer ignoreThreshold;

    @Macro
    @Description("Comma separated list of file extensions to accept. If not specified, all files in the directory " +
      "will be read. Otherwise, only files with an extension in this list will be read.")
    @Nullable
    private String extensions;

    public Conf() {
      super(null);
      this.path = "";
      this.format = "text";
      this.schema = null;
      this.ignoreThreshold = 60;
      this.extensions = null;
    }

    private void validate() {
      if (!containsMacro(format) && !FORMATS.contains(format)) {
        throw new IllegalArgumentException(
          String.format("Invalid format '%s'. Must be one of %s", format, Joiner.on(',').join(FORMATS)));
      }
      getSchema();
    }

    private Schema getSchema() {
      try {
        return Schema.parseJson(schema);
      } catch (IOException e) {
        throw new IllegalArgumentException("Unable to parse schema. Reason: " + e.getMessage());
      }
    }

    private Set<String> getExtensions() {
      Set<String> extensionsSet = new HashSet<>();
      if (extensions == null) {
        return extensionsSet;
      }
      for (String extension : Splitter.on(',').trimResults().split(extensions)) {
        extensionsSet.add(extension);
      }
      return extensionsSet;
    }
  }

}
