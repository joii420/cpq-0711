package com.cpq.semanticgraph.resource;

import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.semanticgraph.dto.SemanticGraphDTOs.*;
import com.cpq.semanticgraph.service.SemanticGraphService;
import com.cpq.semanticgraph.service.SemanticGraphValidator;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 语义图读写端点（task-260819 B-4，api.md §1）。
 *
 * <p>{@code GET} 放开全部 4 个配置角色（AC-56③：内容完全相同——因为都读同一个不可变快照，
 * 权限只影响能不能改，不影响能不能看）；{@code POST/PUT/DELETE} 限 {@code SYSTEM_ADMIN}
 * （AC-56①：非超管一律 403，且库中数据逐行不变——校验先于任何写库动作）。
 *
 * <p>⚠️ 本资源族的响应体**不套** {@code ApiResponse{code,message,data}} 信封——api.md §1 的每个
 * 例子（{@code GET /} 的 {@code graphVersion/nodes/edges/tabViews}、写端点的
 * {@code graphVersion}、错误响应的 {@code code/failedCheck/detail}）都是**顶层字段**，这是本
 * 端点族自己的显式契约，与项目里其余资源的 ApiResponse 惯例不同，不要"顺手"统一回去。
 */
@Path("/api/cpq/config/semantic-graph")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})
public class SemanticGraphResource {

    @Inject SemanticGraphService service;
    @Inject SessionHelper sessionHelper;
    @Context HttpServerRequest vertxRequest;

    @GET
    public GraphDTO getGraph() {
        return service.getGraph();
    }

    @GET
    @Path("/field-tree")
    public List<NodeDTO> fieldTree(@QueryParam("tabType") String tabType,
                                    @QueryParam("variantKey") String variantKey) {
        return service.getFieldTree(tabType, variantKey);
    }

    // ---------------- 节点 ----------------

    @POST
    @Path("/nodes")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Map<String, Object> createNode(NodeUpsertRequest req) {
        int v = service.createNode(req, currentOperator());
        return Map.of("graphVersion", v);
    }

    @PUT
    @Path("/nodes/{id}")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Map<String, Object> updateNode(@PathParam("id") UUID id, NodeUpsertRequest req) {
        int v = service.updateNode(id, req, currentOperator());
        return Map.of("graphVersion", v);
    }

    @DELETE
    @Path("/nodes/{id}")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Map<String, Object> deleteNode(@PathParam("id") UUID id) {
        int v = service.deleteNode(id, currentOperator());
        return Map.of("graphVersion", v);
    }

    /** 给已有节点补一列（AC-53②：db_column 填不存在的列必须被拒）。 */
    @POST
    @Path("/nodes/{nodeId}/columns")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Map<String, Object> addNodeColumn(@PathParam("nodeId") UUID nodeId, ColumnUpsertRequest req) {
        int v = service.addNodeColumn(nodeId, req, currentOperator());
        return Map.of("graphVersion", v);
    }

    // ---------------- 边 ----------------

    @POST
    @Path("/edges")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Map<String, Object> createEdge(EdgeUpsertRequest req) {
        int v = service.createEdge(req, currentOperator());
        return Map.of("graphVersion", v);
    }

    /** 部分更新（如只改 {@code fallbackOrder}），未出现的字段保持原值不动（AC-57①）。 */
    @PUT
    @Path("/edges/{id}")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Map<String, Object> updateEdge(@PathParam("id") UUID id, Map<String, Object> partial) {
        int v = service.updateEdgePartial(id, partial, currentOperator());
        return Map.of("graphVersion", v);
    }

    @DELETE
    @Path("/edges/{id}")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Map<String, Object> deleteEdge(@PathParam("id") UUID id) {
        int v = service.deleteEdge(id, currentOperator());
        return Map.of("graphVersion", v);
    }

    /** 按 (fromNodeId,toNodeId) 删边——供管理页/测试清理用，不强依赖先查出 edgeId。 */
    @DELETE
    @Path("/edges/by-nodes")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Map<String, Object> deleteEdgeByNodes(@QueryParam("fromNodeId") UUID fromNodeId,
                                                  @QueryParam("toNodeId") UUID toNodeId) {
        int v = service.deleteEdgeByNodes(fromNodeId, toNodeId, currentOperator());
        return Map.of("graphVersion", v);
    }

    // ---------------- 页签视图 ----------------

    @POST
    @Path("/tab-views")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Map<String, Object> createTabView(TabViewUpsertRequest req) {
        int v = service.createTabView(req, currentOperator());
        return Map.of("graphVersion", v);
    }

    /** 部分更新（如只改 {@code switches}）。 */
    @PUT
    @Path("/tab-views/{id}")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Map<String, Object> updateTabView(@PathParam("id") UUID id, Map<String, Object> partial) {
        int v = service.updateTabViewPartial(id, partial, currentOperator());
        return Map.of("graphVersion", v);
    }

    @DELETE
    @Path("/tab-views/{id}")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Map<String, Object> deleteTabView(@PathParam("id") UUID id) {
        int v = service.deleteTabView(id, currentOperator());
        return Map.of("graphVersion", v);
    }

    // ---------------- 干跑校验 ----------------

    @POST
    @Path("/validate")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Map<String, Object> validate(EdgeUpsertRequest req) {
        List<SemanticGraphValidator.CheckResult> checks = service.validateEdgeDryRun(req);
        List<Map<String, String>> summary = checks.stream()
                .map(c -> Map.of("check", c.check, "status", c.status,
                        "message", c.message == null ? "" : c.message))
                .toList();
        boolean pass = checks.stream().noneMatch(SemanticGraphValidator.CheckResult::blocks);
        return Map.of("pass", pass, "checks", summary);
    }

    private String currentOperator() {
        try {
            return sessionHelper.getCurrentUserIdOrFallback(vertxRequest).toString();
        } catch (Exception e) {
            return null;
        }
    }
}
