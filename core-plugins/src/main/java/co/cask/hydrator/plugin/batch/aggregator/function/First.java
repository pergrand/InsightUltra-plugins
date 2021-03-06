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

package co.cask.hydrator.plugin.batch.aggregator.function;

import co.cask.cdap.api.data.format.StructuredRecord;
import co.cask.cdap.api.data.schema.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * Return the first element in a group of {@link StructuredRecord}s.
 *
 * @param <T> type of aggregate value
 */
public class First<T> implements SelectionFunction, AggregateFunction<T> {
  private final String fieldName;
  private final Schema fieldSchema;
  private boolean isFirst;
  private StructuredRecord firstRecord;
  private T first;

  public First(String fieldName, Schema fieldSchema) {
    this.fieldName = fieldName;
    this.fieldSchema = fieldSchema;
  }

  @Override
  public void beginFunction() {
    isFirst = true;
    first = null;
    firstRecord = null;
  }

  @Override
  public void operateOn(StructuredRecord record) {
    if (isFirst) {
      first = record.get(fieldName);
      firstRecord = record;
      isFirst = false;
    }
  }

  @Override
  public T getAggregate() {
    return first;
  }

  @Override
  public Schema getOutputSchema() {
    return fieldSchema;
  }

  @Override
  public List<StructuredRecord> getSelectedRecords() {
    List<StructuredRecord> recordList = new ArrayList<>();
    if (firstRecord != null) {
      recordList.add(firstRecord);
    }
    return recordList;
  }
}
