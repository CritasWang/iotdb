/*
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.queryengine.plan.relational.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

public class FunctionNullability {
  private final boolean returnNullable;
  private final List<Boolean> argumentNullable;

  public FunctionNullability(boolean returnNullable, List<Boolean> argumentNullable) {
    this.returnNullable = returnNullable;
    this.argumentNullable =
        new ArrayList<>(requireNonNull(argumentNullable, "argumentNullable is null"));
  }

  public boolean isReturnNullable() {
    return returnNullable;
  }

  public boolean isArgumentNullable(int index) {
    return argumentNullable.get(index);
  }

  public List<Boolean> getArgumentNullable() {
    return argumentNullable;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    FunctionNullability that = (FunctionNullability) o;
    return returnNullable == that.returnNullable && argumentNullable.equals(that.argumentNullable);
  }

  @Override
  public int hashCode() {
    return Objects.hash(returnNullable, argumentNullable);
  }

  @Override
  public String toString() {
    return argumentNullable.stream().map(Objects::toString).collect(joining(", ", "(", ")"))
        + returnNullable;
  }
}