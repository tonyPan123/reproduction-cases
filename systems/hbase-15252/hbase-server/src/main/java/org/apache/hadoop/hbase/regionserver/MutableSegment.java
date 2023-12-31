/**
 *
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
package org.apache.hadoop.hbase.regionserver;

import java.util.SortedSet;

import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellComparator;
import org.apache.hadoop.hbase.classification.InterfaceAudience;

/**
 * An abstraction of a mutable segment in memstore, specifically the active segment.
 */
@InterfaceAudience.Private
public abstract class MutableSegment extends Segment {

  protected MutableSegment(MemStoreLAB memStoreLAB, long size) {
    super(memStoreLAB, size);
  }

  /**
   * Returns a subset of the segment cell set, which starts with the given cell
   * @param firstCell a cell in the segment
   * @return a subset of the segment cell set, which starts with the given cell
   */
  public abstract SortedSet<Cell> tailSet(Cell firstCell);

  /**
   * Returns the Cell comparator used by this segment
   * @return the Cell comparator used by this segment
   */
  public abstract CellComparator getComparator();

  //methods for test

  /**
   * Returns the first cell in the segment
   * @return the first cell in the segment
   */
  abstract Cell first();
}
