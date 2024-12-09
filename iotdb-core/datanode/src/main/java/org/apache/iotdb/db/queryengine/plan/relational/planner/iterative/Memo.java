/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.queryengine.plan.relational.planner.iterative;

import org.apache.iotdb.db.queryengine.common.QueryId;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.PlanNode;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;
import static org.apache.iotdb.db.queryengine.plan.relational.planner.iterative.Plans.resolveGroupReferences;

/**
 * Stores a plan in a form that's efficient to mutate locally (i.e. without having to do full
 * ancestor tree rewrites due to plan nodes being immutable).
 *
 * <p>Each node in a plan is placed in a group, and it's children are replaced with symbolic
 * references to the corresponding groups.
 *
 * <p>For example, a plan like:
 *
 * <pre>
 *    A -> B -> C -> D
 *           \> E -> F
 * </pre>
 *
 * would be stored as:
 *
 * <pre>
 * root: G0
 *
 * G0 : { A -> G1 }
 * G1 : { B -> [G2, G3] }
 * G2 : { C -> G4 }
 * G3 : { E -> G5 }
 * G4 : { D }
 * G5 : { F }
 * </pre>
 *
 * Groups are reference-counted, and groups that become unreachable from the root due to mutations
 * in a subtree get garbage-collected.
 */
public class Memo {
  private static final int ROOT_GROUP_REF = 0;

  private final QueryId idAllocator;
  private final int rootGroup;

  private final Map<Integer, Group> groups = new HashMap<>();

  private int nextGroupId = ROOT_GROUP_REF + 1;

  public Memo(QueryId idAllocator, PlanNode plan) {
    this.idAllocator = idAllocator;
    rootGroup = insertRecursive(plan);
    groups.get(rootGroup).incomingReferences.add(ROOT_GROUP_REF);
  }

  public int getRootGroup() {
    return rootGroup;
  }

  private Group getGroup(int group) {
    checkArgument(groups.containsKey(group), "Invalid group: %s", group);
    return groups.get(group);
  }

  public PlanNode getNode(int group) {
    return getGroup(group).membership;
  }

  public PlanNode resolve(GroupReference groupReference) {
    return getNode(groupReference.getGroupId());
  }

  public PlanNode extract() {
    return extract(getNode(rootGroup));
  }

  private PlanNode extract(PlanNode node) {
    return resolveGroupReferences(node, Lookup.from(planNode -> Stream.of(this.resolve(planNode))));
  }

  public PlanNode replace(int groupId, PlanNode node, String reason) {
    Group group = getGroup(groupId);
    PlanNode old = group.membership;

    checkArgument(
        new HashSet<>(old.getOutputSymbols()).equals(new HashSet<>(node.getOutputSymbols())),
        "%s: transformed expression doesn't produce same outputs: %s vs %s",
        reason,
        old.getOutputSymbols(),
        node.getOutputSymbols());

    if (node instanceof GroupReference) {
      node = getNode(((GroupReference) node).getGroupId());
    } else {
      node = insertChildrenAndRewrite(node);
    }

    incrementReferenceCounts(node, groupId);
    group.membership = node;
    decrementReferenceCounts(old, groupId);
    evictStatisticsAndCost(group);

    return node;
  }

  private void evictStatisticsAndCost(Group group) {
    for (int parentGroup : group.incomingReferences.elementSet()) {
      if (parentGroup != ROOT_GROUP_REF) {
        evictStatisticsAndCost(getGroup(parentGroup));
      }
    }
  }

  private void incrementReferenceCounts(PlanNode fromNode, int fromGroup) {
    Set<Integer> references = getAllReferences(fromNode);

    for (int group : references) {
      groups.get(group).incomingReferences.add(fromGroup);
    }
  }

  private void decrementReferenceCounts(PlanNode fromNode, int fromGroup) {
    Set<Integer> references = getAllReferences(fromNode);

    for (int group : references) {
      Group childGroup = groups.get(group);
      checkState(childGroup.incomingReferences.remove(fromGroup), "Reference to remove not found");

      if (childGroup.incomingReferences.isEmpty()) {
        deleteGroup(group);
      }
    }
  }

  private Set<Integer> getAllReferences(PlanNode node) {
    return node.getChildren().stream()
        .map(GroupReference.class::cast)
        .map(GroupReference::getGroupId)
        .collect(Collectors.toSet());
  }

  private void deleteGroup(int group) {
    checkArgument(
        getGroup(group).incomingReferences.isEmpty(),
        "Cannot delete group that has incoming references");
    PlanNode deletedNode = groups.remove(group).membership;
    decrementReferenceCounts(deletedNode, group);
  }

  private PlanNode insertChildrenAndRewrite(PlanNode node) {
    return node.replaceChildren(
        node.getChildren().stream()
            .map(
                child ->
                    new GroupReference(
                        idAllocator.genPlanNodeId(),
                        insertRecursive(child),
                        child.getOutputSymbols()))
            .collect(Collectors.toList()));
  }

  private int insertRecursive(PlanNode node) {
    if (node instanceof GroupReference) {
      return ((GroupReference) node).getGroupId();
    }

    int group = nextGroupId();
    PlanNode rewritten = insertChildrenAndRewrite(node);

    groups.put(group, Group.withMember(rewritten));
    incrementReferenceCounts(rewritten, group);

    return group;
  }

  private int nextGroupId() {
    return nextGroupId++;
  }

  public int getGroupCount() {
    return groups.size();
  }

  private static final class Group {
    static Group withMember(PlanNode member) {
      return new Group(member);
    }

    private PlanNode membership;
    private final Multiset<Integer> incomingReferences = HashMultiset.create();

    /*@Nullable
    private PlanNodeStatsEstimate stats;
    @Nullable
    private PlanCostEstimate cost;*/

    private Group(PlanNode member) {
      this.membership = requireNonNull(member, "member is null");
    }
  }
}
