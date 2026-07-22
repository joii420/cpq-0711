package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.component.entity.CostingBomTreeConfig;
import com.cpq.component.service.ComponentDriverService;
import com.cpq.datasource.sqlview.CostingTreeVarsContext;
import com.cpq.datasource.sqlview.VersionFilterMacro;
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
        return render(templateId, lineItems, null);
    }

    /**
     * task-0713 B3 版本感知重载：{@code overridesByComponent} 非空时，对含 {@code :versionFilter}
     * 宏的组件 $view / 主树递归 SQL 应用该核价单已保存的版本 override（componentId → partNo →
     * viewVersion）。{@code null}/空 = 零行为变化（宏展开后 override 数组为空 → 恒退化为
     * {@code is_current}，与两参重载逐位等价）。
     */
    public Map<UUID, Map<String, ArrayNode>> render(UUID templateId, List<QuotationLineItem> lineItems,
                                                     Map<UUID, Map<String, String>> overridesByComponent) {
        Map<UUID, Map<String, ArrayNode>> out = new LinkedHashMap<>();
        if (lineItems == null || lineItems.isEmpty()) {
            return out;
        }
        Map<UUID, Map<String, String>> overrides =
                (overridesByComponent != null) ? overridesByComponent : java.util.Collections.emptyMap();

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

        // ②-pre 模板 driver 组件清单（提前到递归查询之前，供「主树」组件 override 查找 + §④ 复用，
        // 避免重复查询）。照抄 CardSnapshotService#expandTemplateDriverBaseRows 的既有查询。
        @SuppressWarnings("unchecked")
        List<Object[]> driverComps = em.createNativeQuery(
                        "SELECT DISTINCT c.id, c.bom_recursive_expand FROM template_component tc " +
                                "JOIN component c ON c.id = tc.component_id " +
                                "WHERE tc.template_id = :tid AND c.data_driver_path IS NOT NULL AND c.data_driver_path <> ''")
                .setParameter("tid", templateId)
                .getResultList();
        UUID treeComponentId = null;
        for (Object[] dc : driverComps) {
            if (dc != null && dc[0] != null && (dc[1] instanceof Boolean b) && b) {
                treeComponentId = UUID.fromString(dc[0].toString());
                break;
            }
        }
        Map<String, String> treeOverrides = (treeComponentId != null)
                ? overrides.getOrDefault(treeComponentId, java.util.Collections.emptyMap())
                : java.util.Collections.emptyMap();

        // ② 全局生效的递归 SQL 配置
        CostingBomTreeConfig cfg = CostingBomTreeConfig.findActive();
        if (cfg == null) {
            throw new BusinessException(400, "未配置生效的核价树递归 SQL（costing_bom_tree_config 无 isActive=true 记录）");
        }

        List<CostingTreeNode> rows;
        CostingTreeVarsContext.set(new CostingTreeVarsContext.Vars(new ArrayList<>(seed), null, overrides));
        try {
            rows = queryRecursive(cfg.sqlTemplate, new ArrayList<>(seed), treeOverrides);
        } finally {
            CostingTreeVarsContext.clear();
        }

        // ③ 纯函数分组建树
        CostingTreeGrouping.Result g = CostingTreeGrouping.group(rows);

        // ④ 每个 driver 组件跑一次其 $view，按 material_no 分桶；同时记录哪些组件是树页签。
        // CostingTreeVarsContext 携带整卡 overridesByComponent，SqlViewExecutor 按当前
        // SqlViewRuntimeContext.componentId 在绑定期精确解析出「这一个组件」的 override 切片
        // （见 SqlViewExecutor#injectCostingTreeVars），故此处仍可一次 set/clear 覆盖整个循环。
        Map<String, Map<String, List<ExpandDriverResponse.Row>>> rowsByCompThenMaterial = new LinkedHashMap<>();
        Set<String> treeTabCompIds = new HashSet<>();
        CostingTreeVarsContext.set(new CostingTreeVarsContext.Vars(null, g.totalMaterialNo, overrides));
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
                // 分桶键语义按组件类型不同：树页签 = (parent_no, material_no) 边键；普通页签 = material_no。
                Map<String, List<ExpandDriverResponse.Row>> byKey = new LinkedHashMap<>();
                try {
                    // 见类注释「跑组件 $view 的入口」说明：customerId/partNo/partVersion/lineItemId 全传 null，
                    // 让 SqlViewExecutor 从 CostingTreeVarsContext 拿 :total_material_no 收窄，
                    // 不再靠 partNo/lineItemId 维度过滤（这条 $view 对整单只跑一次）。
                    // 用 expandUncached（Task 3.1 事项A）而非 expand：9-arg expand 的 expandCache key
                    // 不含 :total_material_no 维度（customerId/partNo/partVersion 全传 null → key 恒定），
                    // 30s TTL 内会与其他报价单/料号集合的同组件调用串号（AP-37 型缺维度缓存 bug）。
                    ExpandDriverResponse resp = componentDriverService.expandUncached(compId, null);
                    if (resp != null && resp.rows != null) {
                        int total = 0;
                        int kept = 0;
                        int missingParent = 0;
                        for (ExpandDriverResponse.Row r : resp.rows) {
                            if (r == null || r.driverRow == null) {
                                continue;
                            }
                            total++;
                            Object mn = r.driverRow.get("material_no");
                            if (mn == null) {
                                continue; // 落选行（无 material_no）丢弃
                            }
                            kept++;
                            if (recursive) {
                                // 树页签：按 (parent_no, material_no) 边键分桶，让每个树节点只挂到
                                // 它自己那条「父→子」边的业务行（同一子件挂多父时不再重复/挂错父）。
                                Object pn = r.driverRow.get("parent_no");
                                if (pn == null) missingParent++;
                                byKey.computeIfAbsent(
                                        edgeKey(pn == null ? null : pn.toString(), mn.toString()),
                                        k -> new ArrayList<>()).add(r);
                            } else {
                                // 普通页签：按 material_no 料号维度分桶（不变）。
                                byKey.computeIfAbsent(mn.toString(), k -> new ArrayList<>()).add(r);
                            }
                        }
                        if (total > 0 && kept == 0) {
                            LOG.warnf("[costing-tree] 组件 %s 的 $view 返回 %d 行但无有效 material_no"
                                            + "（可能未输出 material_no 列），该页签数据全部落选",
                                    cidStr, total);
                        }
                        if (recursive && kept > 0 && missingParent == kept) {
                            LOG.warnf("[costing-tree] 树页签组件 %s 的 $view 未输出 parent_no（%d 行全无父件列）,"
                                            + "边匹配退化为只命中根层空父 → 业务数据可能全落空;"
                                            + "请让树页签 $view 同时输出 parent_no 与 material_no 两列",
                                    cidStr, kept);
                        }
                    }
                } catch (Exception e) {
                    LOG.warnf("[costing-tree-render] expand comp=%s failed: %s", cidStr, e.getMessage());
                }
                rowsByCompThenMaterial.put(cidStr, byKey);
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
                    Map<String, List<ExpandDriverResponse.Row>> byKey = ce.getValue();
                    ArrayNode baseRows = MAPPER.createArrayNode();
                    if (treeTabCompIds.contains(cidStr)) {
                        // 树页签：以卡片的 spine 节点为行主轴；按 (节点父件, 节点料号) 边键精确取该边的业务行
                        // → 同一子件挂多父时,每个节点只挂到自己那条边(别父件的边无对应节点 → 丢弃),缺数据补空行。
                        for (CostingTreeNode node : treeRows) {
                            List<ExpandDriverResponse.Row> bizRows = byKey.get(edgeKey(node.parentNo, node.materialNo));
                            if (bizRows != null && !bizRows.isEmpty()) {
                                for (ExpandDriverResponse.Row br : bizRows) {
                                    baseRows.add(treeRowNode(node, br));
                                }
                            } else {
                                baseRows.add(treeRowNode(node, null));
                            }
                        }
                    } else {
                        // 普通页签：卡片料号集合命中的行平铺（按 material_no,不变）
                        for (String mat : cardMaterials) {
                            List<ExpandDriverResponse.Row> bizRows = byKey.get(mat);
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

    /** 树页签边键分隔符（U+0001，料号里不会出现，避免拼接歧义）。 */
    private static final String EDGE_SEP = "\u0001";

    /**
     * 树页签边键：{@code (父件料号, 子件料号)}。根节点父件为 {@code null}，用空串占位。
     */
    static String edgeKey(String parentNo, String materialNo) {
        return (parentNo == null ? "" : parentNo) + EDGE_SEP + (materialNo == null ? "" : materialNo);
    }

    /** 递归 SQL 支持的占位符（按出现顺序绑定，见 {@link #queryRecursive}）。 */
    private static final java.util.regex.Pattern TREE_PARAM =
            java.util.regex.Pattern.compile(":(production_part_nos|__vfPart|__vfVer)\\b");

    /**
     * 递归 SQL 直接 JDBC 执行。契约里的绑定变量是 {@code :production_part_nos}（text[]，递归 CTE 常见
     * 写法会在 SQL 里多次引用同一变量，如 base case + recursive case 各引用一次），task-0713 B3 起还
     * 支持 {@code :versionFilter(...)} 宏展开后的 {@code :__vfPart / :__vfVer}（同样可能多次出现，
     * 例如本表版本列的展示子查询 + 主 JOIN 谓词各一次）。S3 修复（2026-07）确立的「按出现次数逐个绑定」
     * 范式在此扩展为<b>按出现顺序</b>逐个绑定（3 种占位符可能交替出现，不能像单占位符那样按类型分组
     * 批量绑定，否则位置错位）。
     *
     * @param treeOverrides 「主树」组件（{@code bom_recursive_expand=true}）在本核价单的 override
     *                      （parentPartNo → viewVersion）；null/空 = 零覆盖，宏展开后恒退化为
     *                      is_current（与未接入版本切换前逐位等价）。
     *
     * <p><b>TODO(task-0721-报价升版逻辑 B4，与树任务对齐)</b>：本方法走裸 JDBC，不经过
     * {@link com.cpq.datasource.sqlview.SqlViewExecutor}，故报价升版逻辑 B3 的
     * {@link com.cpq.datasource.sqlview.QuotePendingRewriter} pending 感知改写（表替换 + 遮蔽 +
     * {@code __v6_id} 锚点注入）<b>未覆盖本递归 CTE 本身</b>——若递归 SQL 直接查询白名单表
     * （{@code material_bom_item}/{@code element_bom_item} 等）且需要在报价单 DRAFT 态看到本单
     * pending 行、或将树节点自身回填（B5），需与树任务工程师协同：在 {@code expanded} 拼接前对
     * 递归 CTE 的基表引用同样跑一遍表替换（注意递归 CTE 通常有 base case + recursive case 两处
     * 自引用，逐处替换）。
     * <p>已确认<b>不需要改动</b>的部分：树节点的"业务行"侧（{@link #treeRowNode} 调用的
     * {@link #rowNodeFrom}）直接透传 {@code ExpandDriverResponse.Row.driverRow}（与
     * {@code CardSnapshotService#rowToNode} 同款 {@code MAPPER.valueToTree} 全量直通，无白名单过滤）——
     * 该业务行本身若来自走 {@code $view} 的 {@code data_driver_path}，其 {@code __v6_id} 已经随
     * B3 的 {@code SqlViewExecutor.executeAllRows} 自动带出、原样落入 {@code snapshot_rows}，
     * 不需要额外接线（这也是本类唯一与 B4 直接相关、且已天然满足的部分）。
     */
    private List<CostingTreeNode> queryRecursive(String sqlTemplate, List<String> seed,
                                                  Map<String, String> treeOverrides) {
        String expanded = VersionFilterMacro.containsMacro(sqlTemplate)
                ? VersionFilterMacro.expandForExecution(sqlTemplate) : sqlTemplate;

        List<String> vfPart = new ArrayList<>();
        List<String> vfVer = new ArrayList<>();
        if (treeOverrides != null) {
            for (Map.Entry<String, String> e : treeOverrides.entrySet()) {
                vfPart.add(e.getKey());
                vfVer.add(e.getValue());
            }
        }

        java.util.regex.Matcher m = TREE_PARAM.matcher(expanded);
        StringBuilder rewritten = new StringBuilder();
        List<String> order = new ArrayList<>();
        int lastEnd = 0;
        while (m.find()) {
            rewritten.append(expanded, lastEnd, m.start()).append('?');
            order.add(m.group(1));
            lastEnd = m.end();
        }
        rewritten.append(expanded, lastEnd, expanded.length());

        String sql = "SELECT root_no, material_no, bom_version, parent_no, node_path FROM (" + rewritten + ") q";
        List<CostingTreeNode> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            java.sql.Array seedArr = conn.createArrayOf("text", seed.toArray());
            java.sql.Array partArr = conn.createArrayOf("text", vfPart.toArray());
            java.sql.Array verArr = conn.createArrayOf("text", vfVer.toArray());
            for (int i = 0; i < order.size(); i++) {
                String name = order.get(i);
                java.sql.Array arr = "production_part_nos".equals(name) ? seedArr
                        : "__vfPart".equals(name) ? partArr : verArr;
                ps.setArray(i + 1, arr);
            }
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
     * <p>{@code bizRowOrNull=null} 时仍输出系统列（业务行缺失 = 空 basicDataValues）；driverRow 补
     * {@code material_no = node.materialNo} 作锚点（S2 修复，2026-07；对齐 §4.2「必含 material_no」——
     * 有业务行时业务行自带 material_no 不用补，空节点时前端/下游按 material_no 取数不能没锚点）。
     * 注：新递归 SQL 契约（root_no/material_no/bom_version/parent_no/node_path）无 is_cycle 列，
     * 故本行不含旧 {@code spineRowNode} 的 {@code __isCycle}（详见交接说明）。
     */
    static ObjectNode treeRowNode(CostingTreeNode node, ExpandDriverResponse.Row bizRowOrNull) {
        ObjectNode rowNode = rowNodeFrom(bizRowOrNull);
        if (bizRowOrNull == null) {
            ((ObjectNode) rowNode.get("driverRow")).put("material_no", node.materialNo);
        }
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
