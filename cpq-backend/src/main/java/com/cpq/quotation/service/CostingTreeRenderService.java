package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.component.entity.CostingBomTreeConfig;
import com.cpq.component.service.ComponentDriverService;
import com.cpq.datasource.sqlview.CostingTreeVarsContext;
import com.cpq.quotation.entity.QuotationLineItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 核价单全量递归 + 按料号分组渲染重构（Task 2.2）—— 整单编排入口。
 *
 * <p>流程（整单一次，不再逐行/逐料号展开）：
 * <ol>
 *   <li>收集整单料号种子（各 {@link QuotationLineItem#productPartNoSnapshot}，去重）；</li>
 *   <li>跑当前生效的全局递归 SQL（{@link CostingBomTreeConfig#findActive()}），一次性拿到全单闭包
 *       （5 列 {@code root_no/material_no/bom_version/parent_no/node_path}）；</li>
 *   <li>纯函数分组建树（{@link CostingTreeGrouping#group}）：按根聚出每张卡片的料号集合 +
 *       全单料号并集 + 每张卡片的 spine 树行；</li>
 *   <li>对模板每个 driver 组件（{@code data_driver_path} 非空）跑一次其 $view（用全单
 *       {@code total_material_no} 收窄，替代逐行/逐料号展开），按 {@code material_no} 分桶；</li>
 *   <li>逐卡逐组件装配 baseRows：普通页签把该卡片料号集合命中的行平铺；树页签（勾选了
 *       {@code bom_recursive_expand} 的组件）以该卡片的 spine 节点为行主轴，缺数据补空行；</li>
 *   <li>返回 {@code lineItemId → componentId → baseRows}，供 {@code CardSnapshotService} 后续走
 *       公式计算（接线见 Task 3.1，<b>不在本类范围</b>）。</li>
 * </ol>
 *
 * <p><b>单模板假设</b>：本方法按<b>单一 {@code templateId}</b> 查询 driver 组件清单（与既有
 * {@code CardSnapshotService#expandTemplateDriverBaseRows(UUID templateId, ...)} 同款签名/假设一致）。
 * 若一张报价单跨多个模板，调用方需按 {@code templateId} 分组后逐组调用本方法（Task 3.1 决定）。
 *
 * <p><b>契约回顾</b>：递归 SQL 输入 {@code :production_part_nos}（text[]），页签 SQL（组件 $view）
 * 输入 {@code :total_material_no}（text[]）、输出必含 {@code material_no} 列。匹配键仅
 * {@code material_no}；落选行（{@code material_no} 不属任何卡）丢弃；同料号多 occurrence 保留；
 * 树页签 = 勾了 {@code bom_recursive_expand} 的组件。
 */
@ApplicationScoped
public class CostingTreeRenderService {

    private static final Logger LOG = Logger.getLogger(CostingTreeRenderService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    DataSource dataSource;

    @Inject
    EntityManager em;

    @Inject
    ComponentDriverService componentDriverService;

    /**
     * 整单渲染入口。
     *
     * @param templateId 本组 line items 共用的模板 ID（见类注释「单模板假设」）
     * @param lineItems  本组报价单行（同一 templateId）
     * @return {@code lineItemId → (componentId(字符串) → baseRows)}；无有效料号种子时返回空 Map。
     */
    public Map<UUID, Map<String, ArrayNode>> render(UUID templateId, List<QuotationLineItem> lineItems) {
        Map<UUID, Map<String, ArrayNode>> out = new LinkedHashMap<>();
        if (lineItems == null || lineItems.isEmpty()) {
            return out;
        }

        // ① 整单料号种子 + root_no -> lineItemId 反查（同料号可能被多个 line item 共用）
        LinkedHashSet<String> seed = new LinkedHashSet<>();
        Map<String, List<UUID>> rootToLineItemIds = new LinkedHashMap<>();
        for (QuotationLineItem li : lineItems) {
            String root = li.productPartNoSnapshot;
            if (root == null || root.isBlank()) {
                continue;
            }
            seed.add(root);
            rootToLineItemIds.computeIfAbsent(root, k -> new ArrayList<>()).add(li.id);
        }
        if (seed.isEmpty()) {
            return out;
        }

        // ② 全局生效的递归 SQL 配置
        CostingBomTreeConfig cfg = CostingBomTreeConfig.findActive();
        if (cfg == null) {
            throw new BusinessException(400, "未配置生效的核价树递归 SQL（costing_bom_tree_config 无 isActive=true 记录）");
        }

        List<CostingTreeNode> rows;
        CostingTreeVarsContext.set(new CostingTreeVarsContext.Vars(new ArrayList<>(seed), null));
        try {
            rows = queryRecursive(cfg.sqlTemplate, new ArrayList<>(seed));
        } finally {
            CostingTreeVarsContext.clear();
        }

        // ③ 纯函数分组建树
        CostingTreeGrouping.Result g = CostingTreeGrouping.group(rows);

        // ④ 模板 driver 组件清单（照抄 CardSnapshotService#expandTemplateDriverBaseRows 的既有查询）
        @SuppressWarnings("unchecked")
        List<Object[]> driverComps = em.createNativeQuery(
                        "SELECT DISTINCT c.id, c.bom_recursive_expand FROM template_component tc " +
                                "JOIN component c ON c.id = tc.component_id " +
                                "WHERE tc.template_id = :tid AND c.data_driver_path IS NOT NULL AND c.data_driver_path <> ''")
                .setParameter("tid", templateId)
                .getResultList();

        // 每个 driver 组件跑一次其 $view，按 material_no 分桶；同时记录哪些组件是树页签。
        Map<String, Map<String, List<ExpandDriverResponse.Row>>> rowsByCompThenMaterial = new LinkedHashMap<>();
        Set<String> treeTabCompIds = new HashSet<>();
        CostingTreeVarsContext.set(new CostingTreeVarsContext.Vars(null, g.totalMaterialNo));
        try {
            for (Object[] dc : driverComps) {
                if (dc == null || dc[0] == null) {
                    continue;
                }
                String cidStr = dc[0].toString();
                // 默认/非布尔 -> true（与 expandTemplateDriverBaseRows 同款兜底约定）
                boolean recursive = !(dc[1] instanceof Boolean) || (Boolean) dc[1];
                if (recursive) {
                    treeTabCompIds.add(cidStr);
                }
                UUID compId = UUID.fromString(cidStr);
                Map<String, List<ExpandDriverResponse.Row>> byMaterial = new LinkedHashMap<>();
                try {
                    // 见类注释「跑组件 $view 的入口」说明：customerId/partNo/partVersion/lineItemId 全传 null，
                    // 让 SqlViewExecutor 从 CostingTreeVarsContext 拿 :total_material_no 收窄，
                    // 不再靠 partNo/lineItemId 维度过滤（这条 $view 对整单只跑一次）。
                    // 用 expandUncached（Task 3.1 事项A）而非 expand：9-arg expand 的 expandCache key
                    // 不含 :total_material_no 维度（customerId/partNo/partVersion 全传 null → key 恒定），
                    // 30s TTL 内会与其他报价单/料号集合的同组件调用串号（AP-37 型缺维度缓存 bug）。
                    ExpandDriverResponse resp = componentDriverService.expandUncached(compId, null);
                    if (resp != null && resp.rows != null) {
                        for (ExpandDriverResponse.Row r : resp.rows) {
                            if (r == null || r.driverRow == null) {
                                continue;
                            }
                            Object mn = r.driverRow.get("material_no");
                            if (mn == null) {
                                continue; // 落选行（无 material_no）丢弃
                            }
                            byMaterial.computeIfAbsent(mn.toString(), k -> new ArrayList<>()).add(r);
                        }
                    }
                } catch (Exception e) {
                    LOG.warnf("[costing-tree-render] expand comp=%s failed: %s", cidStr, e.getMessage());
                }
                rowsByCompThenMaterial.put(cidStr, byMaterial);
            }
        } finally {
            CostingTreeVarsContext.clear();
        }

        // ⑤ 逐卡逐组件装 baseRows
        for (Map.Entry<String, List<UUID>> e : rootToLineItemIds.entrySet()) {
            String root = e.getKey();
            LinkedHashSet<String> cardMaterials = g.cardMaterialNo.getOrDefault(root, new LinkedHashSet<>());
            List<CostingTreeNode> treeRows = g.treeRowsByRoot.getOrDefault(root, List.of());
            for (UUID liId : e.getValue()) {
                Map<String, ArrayNode> baseRowsByComp = new LinkedHashMap<>();
                for (Map.Entry<String, Map<String, List<ExpandDriverResponse.Row>>> ce : rowsByCompThenMaterial.entrySet()) {
                    String cidStr = ce.getKey();
                    Map<String, List<ExpandDriverResponse.Row>> byMaterial = ce.getValue();
                    ArrayNode baseRows = MAPPER.createArrayNode();
                    if (treeTabCompIds.contains(cidStr)) {
                        // 树页签：以卡片的 spine 节点为行主轴，缺数据补空行
                        for (CostingTreeNode node : treeRows) {
                            List<ExpandDriverResponse.Row> bizRows = byMaterial.get(node.materialNo);
                            if (bizRows != null && !bizRows.isEmpty()) {
                                for (ExpandDriverResponse.Row br : bizRows) {
                                    baseRows.add(treeRowNode(node, br));
                                }
                            } else {
                                baseRows.add(treeRowNode(node, null));
                            }
                        }
                    } else {
                        // 普通页签：卡片料号集合命中的行平铺
                        for (String mat : cardMaterials) {
                            List<ExpandDriverResponse.Row> bizRows = byMaterial.get(mat);
                            if (bizRows != null) {
                                for (ExpandDriverResponse.Row br : bizRows) {
                                    baseRows.add(flatRowNode(br));
                                }
                            }
                        }
                    }
                    baseRowsByComp.put(cidStr, baseRows);
                }
                out.put(liId, baseRowsByComp);
            }
        }
        return out;
    }

    /** 递归 SQL 直接 JDBC 执行（只一个变量 :production_part_nos → PreparedStatement ? 绑定 text[]）。 */
    private List<CostingTreeNode> queryRecursive(String sqlTemplate, List<String> seed) {
        String sql = "SELECT root_no, material_no, bom_version, parent_no, node_path FROM (" +
                sqlTemplate.replace(":production_part_nos", "?") + ") q";
        List<CostingTreeNode> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("text", seed.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new CostingTreeNode(
                            rs.getString("root_no"),
                            rs.getString("material_no"),
                            rs.getString("bom_version"),
                            rs.getString("parent_no"),
                            rs.getString("node_path")));
                }
            }
        } catch (Exception e) {
            throw new BusinessException(500, "核价树递归 SQL 执行失败: " + e.getMessage());
        }
        return out;
    }

    // ─── baseRow 装配纯函数（结构对齐 CardSnapshotService#rowToNode / #spineRowNode） ───

    static ObjectNode rowNodeFrom(ExpandDriverResponse.Row row) {
        ObjectNode n = MAPPER.createObjectNode();
        n.set("driverRow",
                (row != null && row.driverRow != null) ? MAPPER.valueToTree(row.driverRow) : MAPPER.createObjectNode());
        n.set("basicDataValues",
                (row != null && row.basicDataValues != null) ? MAPPER.valueToTree(row.basicDataValues) : MAPPER.createObjectNode());
        return n;
    }

    /** 普通页签一行（对齐 {@code CardSnapshotService#rowToNode}，无系统列）。 */
    static ObjectNode flatRowNode(ExpandDriverResponse.Row bizRow) {
        return rowNodeFrom(bizRow);
    }

    /**
     * 树页签一行（对齐 {@code CardSnapshotService#spineRowNode}）：业务行 + 系统列
     * {@code __nodeId/__parentId/__lvl/__hfPartNo/__parentNo/__bomVersion}。
     *
     * <p>{@code bizRowOrNull=null} 时仍输出系统列（业务行缺失 = 空 driverRow/basicDataValues）。
     * 注：新递归 SQL 契约（root_no/material_no/bom_version/parent_no/node_path）无 is_cycle 列，
     * 故本行不含旧 {@code spineRowNode} 的 {@code __isCycle}（详见交接说明）。
     */
    static ObjectNode treeRowNode(CostingTreeNode node, ExpandDriverResponse.Row bizRowOrNull) {
        ObjectNode rowNode = rowNodeFrom(bizRowOrNull);
        rowNode.put("__nodeId", node.nodeId == null ? "" : node.nodeId);
        if (node.parentId == null) {
            rowNode.putNull("__parentId");
        } else {
            rowNode.put("__parentId", node.parentId);
        }
        rowNode.put("__lvl", node.lvl);
        if (node.materialNo == null) {
            rowNode.putNull("__hfPartNo");
        } else {
            rowNode.put("__hfPartNo", node.materialNo);
        }
        if (node.parentNo == null) {
            rowNode.putNull("__parentNo");
        } else {
            rowNode.put("__parentNo", node.parentNo);
        }
        if (node.bomVersion == null) {
            rowNode.putNull("__bomVersion");
        } else {
            rowNode.put("__bomVersion", node.bomVersion);
        }
        return rowNode;
    }
}
