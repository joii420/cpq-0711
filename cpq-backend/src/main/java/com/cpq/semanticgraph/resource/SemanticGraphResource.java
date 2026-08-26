package com.cpq.semanticgraph.resource;

import com.cpq.builder.compiler.BuilderConfig;
import com.cpq.common.security.RoleAllowed;
import com.cpq.common.security.SessionHelper;
import com.cpq.semanticgraph.dto.SemanticGraphDTOs.*;
import com.cpq.semanticgraph.service.SemanticGraphService;
import com.cpq.semanticgraph.service.SemanticGraphValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final ObjectMapper FIELD_TREE_MAPPER = new ObjectMapper();

    @GET
    @Path("/field-tree")
    public com.cpq.builder.compiler.FieldTreeBuilder.FieldTreeResponse fieldTree(
            @QueryParam("tabType") String tabType,
            @QueryParam("variantKey") String variantKey,
            @QueryParam("selectedConfig") String selectedConfigJson) {
        List<BuilderConfig.ColumnConfig> selected = null;
        if (selectedConfigJson != null && !selectedConfigJson.isBlank()) {
            try {
                selected = FIELD_TREE_MAPPER.readValue(selectedConfigJson,
                        FIELD_TREE_MAPPER.getTypeFactory().constructCollectionType(List.class, BuilderConfig.ColumnConfig.class));
            } catch (Exception ignored) {
                // 解析失败按"未带 selectedConfig"处理——conflict 恒 false，不 500
            }
        }
        return service.getFieldTree(tabType, variantKey, selected);
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

    // task-260819 D-40（2026-08-21 主线裁决）：DELETE /edges/by-nodes 端点已下线——
    // 它按 (fromNodeId,toNodeId) 业务键全删，不区分 edge_kind、不区分"种子边"还是"临时造的
    // 测试边"，实测已造成一次真实数据丢失（E02 GRAIN 边被连带删掉，V390 补回）。且该端点从未
    // 进入 api.md 契约，属超范围新增。清理测试边一律改用 DELETE /edges/{id}（按创建时返回的
    // 主键删，不按"造它时用的那对参数"反查）。

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

    /**
     * 全量重算边基数断言（task-260819 D-44，主线 2026-08-21 裁决）：种子数据里的 {@code assert_status}
     * 是写死的装饰值，从没真的跑过校验；本端点对全部 {@code MANY_TO_ONE} 边补跑一次并写回，
     * 不必等 {@code GET /} 实时算（那正是当初落库 assert_status 这一列的理由——只是欠了这个
     * 写回入口）。
     */
    @POST
    @Path("/revalidate")
    @RoleAllowed({"SYSTEM_ADMIN"})
    public Map<String, Object> revalidate() {
        return service.revalidateAllEdges(currentOperator());
    }

    private String currentOperator() {
        try {
            return sessionHelper.getCurrentUserIdOrFallback(vertxRequest).toString();
        } catch (Exception e) {
            return null;
        }
    }
}
