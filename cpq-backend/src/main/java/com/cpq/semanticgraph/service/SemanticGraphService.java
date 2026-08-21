package com.cpq.semanticgraph.service;

import com.cpq.builder.compiler.BuilderConfig;
import com.cpq.builder.compiler.FieldTreeBuilder;
import com.cpq.common.exception.BusinessException;
import com.cpq.semanticgraph.dto.SemanticGraphDTOs.*;
import com.cpq.semanticgraph.entity.*;
import com.cpq.semanticgraph.exception.SemanticNodeReferencedException;
import com.cpq.system.service.OperationLogService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 语义图 CRUD + 保存期校验编排（task-260819 B-3 / B-4）。
 *
 * <p>写操作次序：① 校验（四道，不阻断项仅记录）② 落库 ③ {@link SemanticGraphLoader#reload()}
 * 整体换引用 ④ 写 {@code OperationLog} 审计（D-29：不自建审计表）。
 */
@ApplicationScoped
public class SemanticGraphService {

    @Inject SemanticGraphLoader loader;
    @Inject SemanticGraphValidator validator;
    @Inject OperationLogService operationLogService;
    @Inject FieldTreeBuilder fieldTreeBuilder;

    // ---------------- 读 ----------------

    public GraphDTO getGraph() {
        SemanticGraphSnapshot snap = loader.get();
        return SemanticGraphMapper.toGraphDTO(snap, snap.version);
    }

    /**
     * 字段树接口（task-260819 B-7）：按 tabType/variantKey 找到对应 {@code semantic_tab_view}，
     * 返回 {@code {groups:[...]}} 形状（2026-08-21 裁决，api.md §1.4；原扁平 {@code List<NodeDTO>}
     * 实现已废弃——前端与测试都按分组形状实现，扁平结构联调必炸）。
     *
     * @param selectedConfigJson 当前已选列（JSON 数组，与 builder_config.columns 同形），
     *                           null/空 = 不计算 conflict（api.md §1.4 之"只有带 selectedConfig 才算 conflict"）
     */
    public FieldTreeBuilder.FieldTreeResponse getFieldTree(String tabType, String variantKey,
                                                            List<BuilderConfig.ColumnConfig> selectedConfigJson) {
        SemanticGraphSnapshot snap = loader.get();
        return fieldTreeBuilder.build(snap, tabType, variantKey, selectedConfigJson);
    }

    // ---------------- 写：节点 ----------------

    @Transactional
    public int createNode(NodeUpsertRequest req, String operatorId) {
        validator.requireAllOrThrow(List.of(
                validator.checkTableExists(req.physicalTable)
        ));
        SemanticNode n = new SemanticNode();
        n.id = UUID.randomUUID();
        applyNode(n, req);
        n.createdBy = operatorId;
        n.persist();

        if (req.columns != null) {
            for (ColumnUpsertRequest c : req.columns) {
                if (req.physicalTable != null && !req.physicalTable.isBlank()) {
                    validator.requireAllOrThrow(List.of(validator.checkColumnExists(req.physicalTable, c.dbColumn)));
                }
                SemanticNodeColumn col = new SemanticNodeColumn();
                col.id = UUID.randomUUID();
                col.nodeId = n.id;
                applyColumn(col, c);
                col.createdBy = operatorId;
                col.persist();
            }
        }
        int newVersion = loader.reload().version;
        auditLog(operatorId, "SEMANTIC_NODE_CREATE", "semantic_node", n.id, "新增语义图节点 " + n.nodeKey);
        return newVersion;
    }

    @Transactional
    public int deleteNode(UUID id, String operatorId) {
        SemanticNode n = SemanticNode.findById(id);
        if (n == null) throw new BusinessException(404, "节点不存在");

        List<String> refEdges = SemanticEdge.<SemanticEdge>list("fromNodeId = ?1 or toNodeId = ?1", id)
                .stream().map(e -> e.edgeKind + ":" + e.id).collect(Collectors.toList());
        List<String> refTabViews = SemanticTabViewNode.<SemanticTabViewNode>list("nodeId", id)
                .stream().map(tvn -> {
                    SemanticTabView tv = SemanticTabView.findById(tvn.viewId);
                    return tv != null ? tv.tabType + (tv.variantLabel != null ? "·" + tv.variantLabel : "") : tvn.viewId.toString();
                }).collect(Collectors.toList());
        SemanticTabView asAnchor = SemanticTabView.find("anchorNodeId", id).firstResult();
        if (asAnchor != null) refTabViews.add(asAnchor.tabType + "（锚点）");

        if (!refEdges.isEmpty() || !refTabViews.isEmpty()) {
            throw new SemanticNodeReferencedException(
                    "节点 " + n.displayName + " 仍被引用，无法删除", refEdges, refTabViews);
        }
        SemanticNodeColumn.delete("nodeId", id);
        n.delete();
        int newVersion = loader.reload().version;
        auditLog(operatorId, "SEMANTIC_NODE_DELETE", "semantic_node", id, "删除语义图节点 " + n.nodeKey);
        return newVersion;
    }

    @Transactional
    public int updateNode(UUID id, NodeUpsertRequest req, String operatorId) {
        SemanticNode n = SemanticNode.findById(id);
        if (n == null) throw new BusinessException(404, "节点不存在");
        if (req.physicalTable != null) {
            validator.requireAllOrThrow(List.of(validator.checkTableExists(req.physicalTable)));
        }
        applyNode(n, req);
        n.updatedBy = operatorId;
        n.updatedAt = LocalDateTime.now();
        int newVersion = loader.reload().version;
        auditLog(operatorId, "SEMANTIC_NODE_UPDATE", "semantic_node", n.id, "更新语义图节点 " + n.nodeKey);
        return newVersion;
    }

    /** 给已有节点补一列（AC-53②）。 */
    @Transactional
    public int addNodeColumn(UUID nodeId, ColumnUpsertRequest req, String operatorId) {
        SemanticNode n = SemanticNode.findById(nodeId);
        if (n == null) throw new BusinessException(404, "节点不存在: " + nodeId);
        if (n.physicalTable != null && !n.physicalTable.isBlank()) {
            validator.requireAllOrThrow(List.of(validator.checkColumnExists(n.physicalTable, req.dbColumn)));
        }
        SemanticNodeColumn col = new SemanticNodeColumn();
        col.id = UUID.randomUUID();
        col.nodeId = nodeId;
        applyColumn(col, req);
        col.createdBy = operatorId;
        col.persist();
        int newVersion = loader.reload().version;
        auditLog(operatorId, "SEMANTIC_NODE_COLUMN_ADD", "semantic_node_column", col.id,
                "节点 " + n.nodeKey + " 新增列 " + req.dbColumn);
        return newVersion;
    }

    // ---------------- 写：边 ----------------

    @Transactional
    public int createEdge(EdgeUpsertRequest req, String operatorId) {
        SemanticGraphSnapshot snap = loader.get();
        SemanticNode from = snap.nodeById.get(req.fromNodeId);
        SemanticNode to = snap.nodeById.get(req.toNodeId);
        if (from == null || to == null) throw new BusinessException(400, "fromNodeId/toNodeId 不存在");

        List<SemanticGraphValidator.CheckResult> checks = new ArrayList<>();
        checks.add(validator.checkTableExists(to.physicalTable));
        if ("MANY_TO_ONE".equals(req.cardinality) && to.physicalTable != null && req.keys != null && !req.keys.isEmpty()) {
            List<String> rightCols = req.keys.stream().map(k -> k.rightColumn).collect(Collectors.toList());
            checks.add(validator.checkEdgeCardinality(to.physicalTable, rightCols));
        }
        checks.add(validator.checkPathUniqueness(snap, req.fromNodeId, req.toNodeId));
        validator.requireAllOrThrow(checks);

        SemanticEdge e = new SemanticEdge();
        e.id = UUID.randomUUID();
        e.fromNodeId = req.fromNodeId;
        e.toNodeId = req.toNodeId;
        e.edgeKind = req.edgeKind;
        e.cardinality = req.cardinality;
        e.fallbackOrder = req.fallbackOrder;
        e.coalesceGroup = req.coalesceGroup;
        e.note = req.note;
        e.assertStatus = statusOf(checks, "EDGE_CARDINALITY");
        e.assertSampleRows = sampleRowsOf(checks);
        e.createdBy = operatorId;
        e.persist();

        if (req.keys != null) {
            for (EdgeKeyDTO k : req.keys) {
                SemanticEdgeKey ek = new SemanticEdgeKey();
                ek.id = UUID.randomUUID();
                ek.edgeId = e.id;
                ek.leftColumn = k.leftColumn;
                ek.rightColumn = k.rightColumn;
                ek.seq = k.seq;
                ek.persist();
            }
        }
        int newVersion = loader.reload().version;
        auditLog(operatorId, "SEMANTIC_EDGE_CREATE", "semantic_edge", e.id,
                "新增语义图边 " + from.nodeKey + " -> " + to.nodeKey);
        return newVersion;
    }

    @Transactional
    public int deleteEdge(UUID id, String operatorId) {
        SemanticEdge e = SemanticEdge.findById(id);
        if (e == null) throw new BusinessException(404, "边不存在");
        SemanticEdgeKey.delete("edgeId", id);
        e.delete();
        int newVersion = loader.reload().version;
        auditLog(operatorId, "SEMANTIC_EDGE_DELETE", "semantic_edge", id, "删除语义图边");
        return newVersion;
    }

    // task-260819 D-40（2026-08-21 主线裁决）：deleteEdgeByNodes 已下线并连带端点一起删除——
    // 按 (fromNodeId,toNodeId) 业务键批量删边，不区分 edge_kind、不区分种子边/测试临时边，
    // 实测已把种子里真实存在的 E02 GRAIN 边连带删掉（V390 补回）。清理测试边一律用 deleteEdge(id)
    // （按创建时返回的主键删，不按"造它时用的那对参数"反查）。

    /**
     * 边的部分更新（AC-57①：只改 {@code fallbackOrder} 这类单字段场景）。未出现在 {@code partial}
     * 里的字段保持原值——PUT 语义上是"部分覆盖"而非节点/tab-view 那种整份替换，因为边的高频改法
     * 就是"调一下 COALESCE 顺序"，要求调用方每次都把 keys/cardinality 全量回传不必要地增加出错面。
     */
    @Transactional
    public int updateEdgePartial(UUID id, Map<String, Object> partial, String operatorId) {
        SemanticEdge e = SemanticEdge.findById(id);
        if (e == null) throw new BusinessException(404, "边不存在");
        if (partial != null) {
            if (partial.containsKey("cardinality")) e.cardinality = (String) partial.get("cardinality");
            if (partial.containsKey("edgeKind")) e.edgeKind = (String) partial.get("edgeKind");
            if (partial.containsKey("fallbackOrder")) {
                Object v = partial.get("fallbackOrder");
                e.fallbackOrder = v == null ? null : ((Number) v).intValue();
            }
            if (partial.containsKey("coalesceGroup")) e.coalesceGroup = (String) partial.get("coalesceGroup");
            if (partial.containsKey("note")) e.note = (String) partial.get("note");
        }
        e.updatedBy = operatorId;
        e.updatedAt = LocalDateTime.now();
        int newVersion = loader.reload().version;
        auditLog(operatorId, "SEMANTIC_EDGE_UPDATE", "semantic_edge", id, "更新语义图边（部分字段）");
        return newVersion;
    }

    // ---------------- 写：页签视图 ----------------

    @Transactional
    public int createTabView(TabViewUpsertRequest req, String operatorId) {
        SemanticGraphSnapshot snap = loader.get();
        if (!snap.nodeById.containsKey(req.anchorNodeId)) {
            throw new BusinessException(400, "anchorNodeId 不存在");
        }
        if (req.nodes != null) {
            for (TabViewNodeUpsertRequest n : req.nodes) {
                if (!snap.nodeById.containsKey(n.nodeId)) {
                    throw new BusinessException(400, "nodes[].nodeId 不存在: " + n.nodeId);
                }
                // AC-55：主源以外的每个节点都必须与锚点之间路径唯一（多路径直接拒绝保存）
                var check = validator.checkPathUniqueness(snap, req.anchorNodeId, n.nodeId);
                if (check.blocks()) {
                    validator.requireAllOrThrow(List.of(check));
                }
            }
        }
        SemanticTabView tv = new SemanticTabView();
        tv.id = UUID.randomUUID();
        tv.tabType = req.tabType;
        tv.variantKey = req.variantKey == null ? "" : req.variantKey;
        tv.variantLabel = req.variantLabel;
        tv.anchorNodeId = req.anchorNodeId;
        tv.switches = req.switches == null ? new String[0] : req.switches.toArray(new String[0]);
        tv.dialect = req.dialect == null ? "QUOTE" : req.dialect;
        tv.createdBy = operatorId;
        tv.persist();

        if (req.nodes != null) {
            int i = 0;
            for (TabViewNodeUpsertRequest n : req.nodes) {
                SemanticTabViewNode tvn = new SemanticTabViewNode();
                tvn.id = UUID.randomUUID();
                tvn.viewId = tv.id;
                tvn.nodeId = n.nodeId;
                tvn.role = n.role;
                tvn.addDims = n.addDims == null ? new String[0] : n.addDims.toArray(new String[0]);
                tvn.sortOrder = i++;
                tvn.createdBy = operatorId;
                tvn.persist();
            }
        }
        int newVersion = loader.reload().version;
        auditLog(operatorId, "SEMANTIC_TABVIEW_CREATE", "semantic_tab_view", tv.id,
                "新增页签视图 " + tv.tabType + (tv.variantLabel != null ? "·" + tv.variantLabel : ""));
        return newVersion;
    }

    @Transactional
    public int deleteTabView(UUID id, String operatorId) {
        SemanticTabView tv = SemanticTabView.findById(id);
        if (tv == null) throw new BusinessException(404, "页签视图不存在");
        SemanticTabViewColumn.delete("viewId", id);
        SemanticTabViewNode.delete("viewId", id);
        tv.delete();
        int newVersion = loader.reload().version;
        auditLog(operatorId, "SEMANTIC_TABVIEW_DELETE", "semantic_tab_view", id, "删除页签视图 " + tv.tabType);
        return newVersion;
    }

    /** 页签视图部分更新（如只改 {@code switches}）。 */
    @Transactional
    @SuppressWarnings("unchecked")
    public int updateTabViewPartial(UUID id, Map<String, Object> partial, String operatorId) {
        SemanticTabView tv = SemanticTabView.findById(id);
        if (tv == null) throw new BusinessException(404, "页签视图不存在");
        if (partial != null) {
            if (partial.containsKey("variantLabel")) tv.variantLabel = (String) partial.get("variantLabel");
            if (partial.containsKey("switches")) {
                Object sw = partial.get("switches");
                tv.switches = sw == null ? new String[0] : ((List<String>) sw).toArray(new String[0]);
            }
            if (partial.containsKey("anchorNodeId") && partial.get("anchorNodeId") != null) {
                UUID newAnchor = UUID.fromString(String.valueOf(partial.get("anchorNodeId")));
                if (!loader.get().nodeById.containsKey(newAnchor)) {
                    throw new BusinessException(400, "anchorNodeId 不存在: " + newAnchor);
                }
                tv.anchorNodeId = newAnchor;
            }
        }
        tv.updatedBy = operatorId;
        tv.updatedAt = LocalDateTime.now();
        int newVersion = loader.reload().version;
        auditLog(operatorId, "SEMANTIC_TABVIEW_UPDATE", "semantic_tab_view", id, "更新页签视图（部分字段）");
        return newVersion;
    }

    // ---------------- 只读校验（干跑，POST /validate） ----------------

    public List<SemanticGraphValidator.CheckResult> validateEdgeDryRun(EdgeUpsertRequest req) {
        SemanticGraphSnapshot snap = loader.get();
        SemanticNode from = snap.nodeById.get(req.fromNodeId);
        SemanticNode to = snap.nodeById.get(req.toNodeId);
        List<SemanticGraphValidator.CheckResult> checks = new ArrayList<>();
        if (from == null || to == null) {
            checks.add(new SemanticGraphValidator.CheckResult("PHYSICAL_EXISTENCE", "FAIL", "节点不存在", Map.of()));
            return checks;
        }
        checks.add(validator.checkTableExists(to.physicalTable));
        if ("MANY_TO_ONE".equals(req.cardinality) && to.physicalTable != null && req.keys != null && !req.keys.isEmpty()) {
            List<String> rightCols = req.keys.stream().map(k -> k.rightColumn).collect(Collectors.toList());
            checks.add(validator.checkEdgeCardinality(to.physicalTable, rightCols));
        }
        checks.add(validator.checkPathUniqueness(snap, req.fromNodeId, req.toNodeId));
        return checks;
    }

    // ---------------- helpers ----------------

    /**
     * 审计写入按 best-effort：{@code operation_log.operator_id} 是 NOT NULL 列，若拿不到合法
     * operatorId（理论上不该发生——写端点都经 {@code @RoleAllowed} 强制登录）宁可跳过这一行审计，
     * 也不能让审计失败连带把本该成功的业务写入一起回滚（曾在已下线的按业务键批量删边路径上
     * 实测复现：传 {@code null} 直接触发 {@code operation_log} 的 NOT NULL 违反，整个事务 500）。
     */
    private void auditLog(String operatorId, String operationType, String targetType, UUID targetId, String summary) {
        UUID uid = toUuid(operatorId);
        if (uid == null) return;
        operationLogService.log(uid, operationType, targetType, targetId, summary);
    }

    private static UUID toUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    private void applyNode(SemanticNode n, NodeUpsertRequest req) {
        n.nodeKey = req.nodeKey;
        n.displayName = req.displayName;
        n.shortName = req.shortName;
        n.nodeKind = req.nodeKind;
        n.physicalTable = req.physicalTable;
        n.scope = req.scope == null ? "NONE" : req.scope;
        n.anchorExpr = req.anchorExpr;
        n.grainColumns = req.grainColumns == null ? new String[0] : req.grainColumns.toArray(new String[0]);
        n.discriminator = req.discriminator;
        n.fixedPredicate = req.fixedPredicate;
        n.funcSignature = req.funcSignature;
        n.dialect = req.dialect == null ? "QUOTE" : req.dialect;
        n.sourceHandler = req.sourceHandler;
        n.note = req.note;
        n.updatedAt = LocalDateTime.now();
    }

    private void applyColumn(SemanticNodeColumn col, ColumnUpsertRequest req) {
        col.dbColumn = req.dbColumn;
        col.displayName = req.displayName;
        col.dataType = req.dataType;
        col.isCode = req.isCode;
        col.roles = req.roles == null ? new String[0] : req.roles.toArray(new String[0]);
        col.sortOrder = req.sortOrder;
    }

    private String statusOf(List<SemanticGraphValidator.CheckResult> checks, String checkName) {
        return checks.stream().filter(c -> c.check.equals(checkName)).findFirst()
                .map(c -> c.status).orElse("NA");
    }

    private Long sampleRowsOf(List<SemanticGraphValidator.CheckResult> checks) {
        for (SemanticGraphValidator.CheckResult c : checks) {
            if (c.detail != null && c.detail.get("sampleRows") != null) {
                return ((Number) c.detail.get("sampleRows")).longValue();
            }
        }
        return null;
    }
}
