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
package org.apache.hadoop.hbase.rsgroup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.BalanceRequest;
import org.apache.hadoop.hbase.client.BalanceResponse;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.hadoop.hbase.ipc.CoprocessorRpcUtils;
import org.apache.hadoop.hbase.master.MasterServices;
import org.apache.hadoop.hbase.master.procedure.ProcedureSyncWait;
import org.apache.hadoop.hbase.net.Address;
import org.apache.hadoop.hbase.procedure2.Procedure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hbase.thirdparty.com.google.common.collect.Sets;
import org.apache.hbase.thirdparty.com.google.protobuf.RpcCallback;
import org.apache.hbase.thirdparty.com.google.protobuf.RpcController;
import org.apache.hadoop.hbase.shaded.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.shaded.protobuf.generated.HBaseProtos;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.AddRSGroupRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.AddRSGroupResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.BalanceRSGroupRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.BalanceRSGroupResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.GetRSGroupInfoOfServerRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.GetRSGroupInfoOfServerResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.GetRSGroupInfoOfTableRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.GetRSGroupInfoOfTableResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.GetRSGroupInfoRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.GetRSGroupInfoResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.ListRSGroupInfosRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.ListRSGroupInfosResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.MoveServersAndTablesRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.MoveServersAndTablesResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.MoveServersRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.MoveServersResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.MoveTablesRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.MoveTablesResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.RemoveRSGroupRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.RemoveRSGroupResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.RemoveServersRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.RemoveServersResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.RenameRSGroupRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.RSGroupAdminProtos.RenameRSGroupResponse;

/**
 * Implementation of RSGroupAdminService defined in RSGroupAdmin.proto. This class calls
 * {@link RSGroupInfoManagerImpl} for actual work, converts result to protocol buffer response,
 * handles exceptions if any occurred and then calls the {@code RpcCallback} with the response.
 *
 * @deprecated Keep it here only for compatibility with {@link RSGroupAdminClient},
 *     using {@link org.apache.hadoop.hbase.master.MasterRpcServices} instead.
 */
@Deprecated
class RSGroupAdminServiceImpl extends RSGroupAdminProtos.RSGroupAdminService {

  private static final Logger LOG = LoggerFactory.getLogger(RSGroupAdminServiceImpl.class);

  private MasterServices master;

  private RSGroupInfoManager rsGroupInfoManager;

  RSGroupAdminServiceImpl() {
  }

  void initialize(MasterServices masterServices){
    this.master = masterServices;
    this.rsGroupInfoManager = masterServices.getRSGroupInfoManager();
  }

  // for backward compatible
  private RSGroupInfo fillTables(RSGroupInfo rsGroupInfo) throws IOException {
    return RSGroupUtil.fillTables(rsGroupInfo, master.getTableDescriptors().getAll().values());
  }

  @Override
  public void getRSGroupInfo(RpcController controller, GetRSGroupInfoRequest request,
      RpcCallback<GetRSGroupInfoResponse> done) {
    GetRSGroupInfoResponse.Builder builder = GetRSGroupInfoResponse.newBuilder();
    String groupName = request.getRSGroupName();
    LOG.info(
      master.getClientIdAuditPrefix() + " initiates rsgroup info retrieval, group=" + groupName);
    try {
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().preGetRSGroupInfo(groupName);
      }
      RSGroupInfo rsGroupInfo = rsGroupInfoManager.getRSGroup(groupName);
      if (rsGroupInfo != null) {
        builder.setRSGroupInfo(ProtobufUtil.toProtoGroupInfo(fillTables(rsGroupInfo)));
      }
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().postGetRSGroupInfo(groupName);
      }
    } catch (IOException e) {
      CoprocessorRpcUtils.setControllerException(controller, e);
    }
    done.run(builder.build());
  }

  @Override
  public void getRSGroupInfoOfTable(RpcController controller, GetRSGroupInfoOfTableRequest request,
      RpcCallback<GetRSGroupInfoOfTableResponse> done) {
    GetRSGroupInfoOfTableResponse.Builder builder = GetRSGroupInfoOfTableResponse.newBuilder();
    TableName tableName = ProtobufUtil.toTableName(request.getTableName());
    LOG.info(
      master.getClientIdAuditPrefix() + " initiates rsgroup info retrieval, table=" + tableName);
    try {
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().preGetRSGroupInfoOfTable(tableName);
      }
      Optional<RSGroupInfo> optGroup =
        RSGroupUtil.getRSGroupInfo(master, rsGroupInfoManager, tableName);
      if (optGroup.isPresent()) {
        builder.setRSGroupInfo(ProtobufUtil.toProtoGroupInfo(fillTables(optGroup.get())));
      } else {
        if (master.getTableStateManager().isTablePresent(tableName)) {
          RSGroupInfo rsGroupInfo = rsGroupInfoManager.getRSGroup(RSGroupInfo.DEFAULT_GROUP);
          builder.setRSGroupInfo(ProtobufUtil.toProtoGroupInfo(fillTables(rsGroupInfo)));
        }
      }

      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().postGetRSGroupInfoOfTable(tableName);
      }
    } catch (IOException e) {
      CoprocessorRpcUtils.setControllerException(controller, e);
    }
    done.run(builder.build());
  }

  @Override
  public void moveServers(RpcController controller, MoveServersRequest request,
      RpcCallback<MoveServersResponse> done) {
    MoveServersResponse.Builder builder = MoveServersResponse.newBuilder();
    Set<Address> hostPorts = Sets.newHashSet();
    for (HBaseProtos.ServerName el : request.getServersList()) {
      hostPorts.add(Address.fromParts(el.getHostName(), el.getPort()));
    }
    LOG.info(master.getClientIdAuditPrefix() + " move servers " + hostPorts + " to rsgroup " +
        request.getTargetGroup());
    try {
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().preMoveServers(hostPorts, request.getTargetGroup());
      }
      rsGroupInfoManager.moveServers(hostPorts, request.getTargetGroup());
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().postMoveServers(hostPorts, request.getTargetGroup());
      }
    } catch (IOException e) {
      CoprocessorRpcUtils.setControllerException(controller, e);
    }
    done.run(builder.build());
  }

  private void moveTablesAndWait(Set<TableName> tables, String targetGroup) throws IOException {
    List<Long> procIds = new ArrayList<Long>();
    for (TableName tableName : tables) {
      TableDescriptor oldTd = master.getTableDescriptors().get(tableName);
      if (oldTd == null) {
        continue;
      }
      TableDescriptor newTd =
          TableDescriptorBuilder.newBuilder(oldTd).setRegionServerGroup(targetGroup).build();
      procIds.add(master.modifyTable(tableName, newTd, HConstants.NO_NONCE, HConstants.NO_NONCE));
    }
    for (long procId : procIds) {
      Procedure<?> proc = master.getMasterProcedureExecutor().getProcedure(procId);
      if (proc == null) {
        continue;
      }
      ProcedureSyncWait.waitForProcedureToCompleteIOE(master.getMasterProcedureExecutor(), proc,
        Long.MAX_VALUE);
    }
  }

  @Override
  public void moveTables(RpcController controller, MoveTablesRequest request,
      RpcCallback<MoveTablesResponse> done) {
    MoveTablesResponse.Builder builder = MoveTablesResponse.newBuilder();
    Set<TableName> tables = new HashSet<>(request.getTableNameList().size());
    for (HBaseProtos.TableName tableName : request.getTableNameList()) {
      tables.add(ProtobufUtil.toTableName(tableName));
    }
    LOG.info(master.getClientIdAuditPrefix() + " move tables " + tables + " to rsgroup " +
        request.getTargetGroup());
    try {
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().preMoveTables(tables, request.getTargetGroup());
      }
      moveTablesAndWait(tables, request.getTargetGroup());
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().postMoveTables(tables, request.getTargetGroup());
      }
    } catch (IOException e) {
      CoprocessorRpcUtils.setControllerException(controller, e);
    }
    done.run(builder.build());
  }

  @Override
  public void addRSGroup(RpcController controller, AddRSGroupRequest request,
      RpcCallback<AddRSGroupResponse> done) {
    AddRSGroupResponse.Builder builder = AddRSGroupResponse.newBuilder();
    LOG.info(master.getClientIdAuditPrefix() + " add rsgroup " + request.getRSGroupName());
    try {
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().preAddRSGroup(request.getRSGroupName());
      }
      rsGroupInfoManager.addRSGroup(new RSGroupInfo(request.getRSGroupName()));
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().postAddRSGroup(request.getRSGroupName());
      }
    } catch (IOException e) {
      CoprocessorRpcUtils.setControllerException(controller, e);
    }
    done.run(builder.build());
  }

  @Override
  public void removeRSGroup(RpcController controller, RemoveRSGroupRequest request,
      RpcCallback<RemoveRSGroupResponse> done) {
    RemoveRSGroupResponse.Builder builder = RemoveRSGroupResponse.newBuilder();
    LOG.info(master.getClientIdAuditPrefix() + " remove rsgroup " + request.getRSGroupName());
    try {
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().preRemoveRSGroup(request.getRSGroupName());
      }
      rsGroupInfoManager.removeRSGroup(request.getRSGroupName());
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().postRemoveRSGroup(request.getRSGroupName());
      }
    } catch (IOException e) {
      CoprocessorRpcUtils.setControllerException(controller, e);
    }
    done.run(builder.build());
  }

  @Override
  public void balanceRSGroup(RpcController controller, BalanceRSGroupRequest request,
      RpcCallback<BalanceRSGroupResponse> done) {
    BalanceRequest balanceRequest = ProtobufUtil.toBalanceRequest(request);
    BalanceRSGroupResponse.Builder builder = BalanceRSGroupResponse.newBuilder()
      .setBalanceRan(false);

    LOG.info(
      master.getClientIdAuditPrefix() + " balance rsgroup, group=" + request.getRSGroupName());
    try {
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost()
          .preBalanceRSGroup(request.getRSGroupName(), balanceRequest);
      }

      BalanceResponse response =
        rsGroupInfoManager.balanceRSGroup(request.getRSGroupName(), balanceRequest);
      ProtobufUtil.populateBalanceRSGroupResponse(builder, response);

      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost()
          .postBalanceRSGroup(request.getRSGroupName(), balanceRequest, response);
      }
    } catch (IOException e) {
      CoprocessorRpcUtils.setControllerException(controller, e);
    }
    done.run(builder.build());
  }

  @Override
  public void listRSGroupInfos(RpcController controller, ListRSGroupInfosRequest request,
      RpcCallback<ListRSGroupInfosResponse> done) {
    ListRSGroupInfosResponse.Builder builder = ListRSGroupInfosResponse.newBuilder();
    LOG.info(master.getClientIdAuditPrefix() + " list rsgroup");
    try {
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().preListRSGroups();
      }
      List<RSGroupInfo> rsGroupInfos = rsGroupInfoManager.listRSGroups().stream()
          .map(RSGroupInfo::new).collect(Collectors.toList());
      Map<String, RSGroupInfo> name2Info = new HashMap<>();
      for (RSGroupInfo rsGroupInfo : rsGroupInfos) {
        name2Info.put(rsGroupInfo.getName(), rsGroupInfo);
      }
      for (TableDescriptor td : master.getTableDescriptors().getAll().values()) {
        String groupName = td.getRegionServerGroup().orElse(RSGroupInfo.DEFAULT_GROUP);
        RSGroupInfo rsGroupInfo = name2Info.get(groupName);
        if (rsGroupInfo != null) {
          rsGroupInfo.addTable(td.getTableName());
        }
      }
      for (RSGroupInfo rsGroupInfo : rsGroupInfos) {
        // TODO: this can be done at once outside this loop, do not need to scan all every time.
        builder.addRSGroupInfo(ProtobufUtil.toProtoGroupInfo(rsGroupInfo));
      }
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().postListRSGroups();
      }
    } catch (IOException e) {
      CoprocessorRpcUtils.setControllerException(controller, e);
    }
    done.run(builder.build());
  }

  @Override
  public void getRSGroupInfoOfServer(RpcController controller,
      GetRSGroupInfoOfServerRequest request, RpcCallback<GetRSGroupInfoOfServerResponse> done) {
    GetRSGroupInfoOfServerResponse.Builder builder = GetRSGroupInfoOfServerResponse.newBuilder();
    Address hp =
        Address.fromParts(request.getServer().getHostName(), request.getServer().getPort());
    LOG.info(master.getClientIdAuditPrefix() + " initiates rsgroup info retrieval, server=" + hp);
    try {
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().preGetRSGroupInfoOfServer(hp);
      }
      RSGroupInfo info = rsGroupInfoManager.getRSGroupOfServer(hp);
      if (info != null) {
        builder.setRSGroupInfo(ProtobufUtil.toProtoGroupInfo(fillTables(info)));
      }
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().postGetRSGroupInfoOfServer(hp);
      }
    } catch (IOException e) {
      CoprocessorRpcUtils.setControllerException(controller, e);
    }
    done.run(builder.build());
  }

  @Override
  public void moveServersAndTables(RpcController controller, MoveServersAndTablesRequest request,
      RpcCallback<MoveServersAndTablesResponse> done) {
    MoveServersAndTablesResponse.Builder builder = MoveServersAndTablesResponse.newBuilder();
    Set<Address> hostPorts = Sets.newHashSet();
    for (HBaseProtos.ServerName el : request.getServersList()) {
      hostPorts.add(Address.fromParts(el.getHostName(), el.getPort()));
    }
    Set<TableName> tables = new HashSet<>(request.getTableNameList().size());
    for (HBaseProtos.TableName tableName : request.getTableNameList()) {
      tables.add(ProtobufUtil.toTableName(tableName));
    }
    LOG.info(master.getClientIdAuditPrefix() + " move servers " + hostPorts + " and tables " +
        tables + " to rsgroup" + request.getTargetGroup());
    try {
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().preMoveServersAndTables(hostPorts, tables,
          request.getTargetGroup());
      }
      rsGroupInfoManager.moveServers(hostPorts, request.getTargetGroup());
      moveTablesAndWait(tables, request.getTargetGroup());
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().postMoveServersAndTables(hostPorts, tables,
          request.getTargetGroup());
      }
    } catch (IOException e) {
      CoprocessorRpcUtils.setControllerException(controller, e);
    }
    done.run(builder.build());
  }

  @Override
  public void removeServers(RpcController controller, RemoveServersRequest request,
      RpcCallback<RemoveServersResponse> done) {
    RemoveServersResponse.Builder builder = RemoveServersResponse.newBuilder();
    Set<Address> servers = Sets.newHashSet();
    for (HBaseProtos.ServerName el : request.getServersList()) {
      servers.add(Address.fromParts(el.getHostName(), el.getPort()));
    }
    LOG.info(
      master.getClientIdAuditPrefix() + " remove decommissioned servers from rsgroup: " + servers);
    try {
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().preRemoveServers(servers);
      }
      rsGroupInfoManager.removeServers(servers);
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().postRemoveServers(servers);
      }
    } catch (IOException e) {
      CoprocessorRpcUtils.setControllerException(controller, e);
    }
    done.run(builder.build());
  }

  @Override
  public void renameRSGroup(RpcController controller, RenameRSGroupRequest request,
      RpcCallback<RenameRSGroupResponse> done) {
    String oldRSGroup = request.getOldRsgroupName();
    String newRSGroup = request.getNewRsgroupName();
    LOG.info("{} rename rsgroup from {} to {}",
      master.getClientIdAuditPrefix(), oldRSGroup, newRSGroup);

    RenameRSGroupResponse.Builder builder = RenameRSGroupResponse.newBuilder();
    try {
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().preRenameRSGroup(oldRSGroup, newRSGroup);
      }
      rsGroupInfoManager.renameRSGroup(oldRSGroup, newRSGroup);
      if (master.getMasterCoprocessorHost() != null) {
        master.getMasterCoprocessorHost().postRenameRSGroup(oldRSGroup, newRSGroup);
      }
    } catch (IOException e) {
      CoprocessorRpcUtils.setControllerException(controller, e);
    }
    done.run(builder.build());
  }

}
