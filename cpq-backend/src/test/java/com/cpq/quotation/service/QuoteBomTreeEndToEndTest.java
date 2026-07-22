package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.configure.service.ConfigureSnapshotService;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0721 —— 报价侧 BOM 树 端到端集成验证（B3 物化 + B5 类型判定 + B6 加叶子 + B7 删除预览/执行）。
 *
 * <p><b>数据现状核实（2026-07-21 自测发现）</b>：委托方原假设"现网 3110520789 同挂
 * 2120011658/2120011659 两支"的 DAG 可直接复用（对应 {@code costing-bom-tree.spec.ts}/
 * {@code CostingBomTreeSnapshotTest} 引用的历史 fixture）。实测：
 * <ol>
 *   <li>{@code CostingBomTreeSnapshotTest}（既有核价侧同款验证）现跑出 <b>Skipped</b>
 *       （无 {@code product_part_no_snapshot='3120018220'} 且 {@code costing_card_template_id}
 *       非空的核价 line item ——旧 fixture 已被清理）；</li>
 *   <li>直接对 {@code material_bom_item} 跑 BOMV2 递归 SQL，{@code 2120011658}/{@code 2120011659}
 *       在当前数据下均为叶子（无 {@code material_no='2120011658'} 的自身 BOM 行，只有
 *       {@code S-2120011658} 变体才有子件 {@code 3110520789}）——现网数据已漂移，不再天然支持该 DAG。
 * </ol>
 * 故本测试改用<b>自建 usage=QUOTE 的递归 SQL 配置</b>（字面 VALUES 边表，不碰
 * {@code material_bom_item}），但沿用文档里的<b>真实料号编号</b>重建同构 DAG
 * （{@code 3110520789} 同挂 {@code 2120011658}/{@code 2120011659}下），以验证 B3/B6/B7 的
 * 真实链路（不是重新证明级联算法本身——算法已由 {@link BomTreeCascadeCalculatorTest} 用相同坐标
 * 单测覆盖）。
 *
 * <p><b>清理策略</b>：不使用 {@code @TestTransaction}（会让内部 {@code REQUIRES_NEW} 子事务
 * 真实提交、外层回滚也救不回来——见 {@code ConfigureSnapshotService} 大量
 * {@code @Transactional(REQUIRES_NEW)}）。改为每个数据库改动都用
 * {@code QuarkusTransaction.requiringNew()} 包裹真实提交，{@link #cleanup()} 在
 * {@code @AfterEach} 里按依赖倒序真实 DELETE，测试失败也会执行。
 */
@QuarkusTest
@DisplayName("QuoteBomTreeEndToEndTest — 报价侧树 B3/B6/B7 端到端")
class QuoteBomTreeEndToEndTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String TAG = "T0721E2E";

    @Inject EntityManager em;
    @Inject ConfigureSnapshotService configureSnapshotService;
    @Inject QuotationTreeService quotationTreeService;

    // fixture ids（供 @AfterEach 清理）
    private UUID quoteConfigId, treeComponentId, materialComponentId, templateId,
            treeViewId, matViewId, tcTreeId, tcMatId, quotationId, lineItemId;

    /**
     * task-0721 收尾：委托方已在共享库建了一份【持久化】usage=QUOTE 递归配置(TASK0721-QUOTE-BOMV2克隆，
     * 供 UI 验收，不清理)，与本类原假设"QUOTE usage 全局无 active 行"冲突——{@code uq_bom_tree_config_active_per_usage}
     * 每 usage 至多一条 active，本类若直接插入自己的 active 行会撞唯一约束。
     * 修法：建自己的行前，先记录并暂时下线任何已存在的 QUOTE active 行，@AfterEach 里删完自己的行后
     * 原样恢复（不删除/不篡改持久化 fixture 内容，只是"临时借道"）。
     */
    private UUID preExistingActiveQuoteConfigId;

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (lineItemId != null) {
                em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id = :id")
                        .setParameter("id", lineItemId).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_line_item WHERE id = :id")
                        .setParameter("id", lineItemId).executeUpdate();
            }
            if (quotationId != null) {
                em.createNativeQuery("DELETE FROM quotation WHERE id = :id")
                        .setParameter("id", quotationId).executeUpdate();
            }
            if (tcTreeId != null) em.createNativeQuery("DELETE FROM template_component WHERE id = :id").setParameter("id", tcTreeId).executeUpdate();
            if (tcMatId != null) em.createNativeQuery("DELETE FROM template_component WHERE id = :id").setParameter("id", tcMatId).executeUpdate();
            if (templateId != null) em.createNativeQuery("DELETE FROM template WHERE id = :id").setParameter("id", templateId).executeUpdate();
            if (treeViewId != null) em.createNativeQuery("DELETE FROM component_sql_view WHERE id = :id").setParameter("id", treeViewId).executeUpdate();
            if (matViewId != null) em.createNativeQuery("DELETE FROM component_sql_view WHERE id = :id").setParameter("id", matViewId).executeUpdate();
            if (treeComponentId != null) em.createNativeQuery("DELETE FROM component WHERE id = :id").setParameter("id", treeComponentId).executeUpdate();
            if (materialComponentId != null) em.createNativeQuery("DELETE FROM component WHERE id = :id").setParameter("id", materialComponentId).executeUpdate();
            if (quoteConfigId != null) em.createNativeQuery("DELETE FROM costing_bom_tree_config WHERE id = :id").setParameter("id", quoteConfigId).executeUpdate();
            // 兜底：按 TAG 前缀再扫一遍，防止某个 id 因异常提前退出未记录
            em.createNativeQuery("DELETE FROM component WHERE code LIKE :p").setParameter("p", TAG + "%").executeUpdate();
            em.createNativeQuery("DELETE FROM template WHERE name LIKE :p").setParameter("p", TAG + "%").executeUpdate();
            em.createNativeQuery("DELETE FROM costing_bom_tree_config WHERE name LIKE :p").setParameter("p", TAG + "%").executeUpdate();
            // 恢复"借道"前已存在的 QUOTE active 行(如持久化验收 fixture)，不留篡改痕迹
            if (preExistingActiveQuoteConfigId != null) {
                em.createNativeQuery("UPDATE costing_bom_tree_config SET is_active = true WHERE id = :id")
                        .setParameter("id", preExistingActiveQuoteConfigId).executeUpdate();
            }
        });
    }

    /** 合成 DAG：3120018220 -> {2120011658,2120011659} -> 3110520789(共2occ) -> 2101110225(共2occ)。 */
    private static final String SYNTHETIC_RECURSIVE_SQL =
        "WITH RECURSIVE edges(parent_no, material_no) AS (\n" +
        "  VALUES\n" +
        "    ('3120018220'::text, '2120011658'::text),\n" +
        "    ('3120018220'::text, '2120011659'::text),\n" +
        "    ('2120011658'::text, '3110520789'::text),\n" +
        "    ('2120011659'::text, '3110520789'::text),\n" +
        "    ('3110520789'::text, '2101110225'::text)\n" +
        "),\n" +
        "bom AS (\n" +
        "  SELECT p::text AS root_no, p::text AS material_no, CAST(NULL AS text) AS bom_version,\n" +
        "         CAST(NULL AS text) AS parent_no, p::text AS node_path\n" +
        "  FROM unnest(:production_part_nos) AS p\n" +
        "  UNION ALL\n" +
        "  SELECT b.root_no, e.material_no, CAST(NULL AS text) AS bom_version, e.parent_no,\n" +
        "         (b.node_path || '/' || e.material_no)::text AS node_path\n" +
        "  FROM edges e JOIN bom b ON e.parent_no = b.material_no\n" +
        ")\n" +
        "SELECT root_no, material_no, bom_version, parent_no, node_path FROM bom";

    /** 建 fixture：QUOTE 递归配置 + 树组件 + 材质元素组件 + 模板 + 报价单 + 报价行。 */
    private void buildFixture() {
        QuarkusTransaction.requiringNew().run(() -> {
            // ① QUOTE usage 递归 SQL 配置(合成边表,不碰 material_bom_item) + 激活。
            // 借道前先暂时下线任何已存在的 QUOTE active 行(如持久化验收 fixture)，@AfterEach 里恢复。
            @SuppressWarnings("unchecked")
            List<Object> existingActive = em.createNativeQuery(
                    "SELECT id FROM costing_bom_tree_config WHERE usage = 'QUOTE' AND is_active = true")
                    .getResultList();
            if (!existingActive.isEmpty()) {
                preExistingActiveQuoteConfigId = toUUID(existingActive.get(0));
                em.createNativeQuery("UPDATE costing_bom_tree_config SET is_active = false WHERE id = :id")
                        .setParameter("id", preExistingActiveQuoteConfigId).executeUpdate();
            }

            Object[] cfg = new Object[1];
            em.createNativeQuery(
                    "INSERT INTO costing_bom_tree_config (id, name, sql_template, is_active, usage, created_at, updated_at) " +
                    "VALUES (:id, :name, :sql, true, 'QUOTE', now(), now())")
                    .setParameter("id", quoteConfigId = UUID.randomUUID())
                    .setParameter("name", TAG + "-recursive-cfg")
                    .setParameter("sql", SYNTHETIC_RECURSIVE_SQL)
                    .executeUpdate();

            // ② 树组件(tab_type=BOM) + 其 SQL 视图(返回 0 行业务数据,只验证骨架)
            Component treeComp = new Component();
            treeComp.name = TAG + "-BOM树";
            treeComp.code = TAG + "-TREE-" + UUID.randomUUID().toString().substring(0, 6);
            treeComp.fields = "[]";
            treeComp.formulas = "[]";
            treeComp.tabType = "BOM";
            treeComp.bomRecursiveExpand = true;
            treeComp.dataDriverPath = "$" + TAG.toLowerCase() + "_tree_view";
            treeComp.persist();
            treeComponentId = treeComp.id;

            ComponentSqlView treeView = new ComponentSqlView();
            treeView.componentId = treeComp.id;
            treeView.sqlViewName = TAG.toLowerCase() + "_tree_view";
            treeView.sqlTemplate = "SELECT NULL::text AS material_no, NULL::text AS parent_no WHERE FALSE";
            treeView.declaredColumns = "[]";
            treeView.persist();
            treeViewId = treeView.id;

            // ③ 材质元素组件（tab_type=材质元素, part_no_field=料号），其视图给 2101110225 一条业务行
            Component matComp = new Component();
            matComp.name = TAG + "-材质元素";
            matComp.code = TAG + "-MAT-" + UUID.randomUUID().toString().substring(0, 6);
            matComp.fields = "[{\"name\":\"料号\",\"field_type\":\"INPUT_TEXT\"}]";
            matComp.formulas = "[]";
            matComp.tabType = "材质元素";
            matComp.partNoField = "料号";
            matComp.dataDriverPath = "$" + TAG.toLowerCase() + "_mat_view";
            matComp.persist();
            materialComponentId = matComp.id;

            ComponentSqlView matView = new ComponentSqlView();
            matView.componentId = matComp.id;
            matView.sqlViewName = TAG.toLowerCase() + "_mat_view";
            // hf_part_no 是 SqlViewExecutor 通用基础设施的过滤列约定（AP-53）——即便单值 expand
            // 也会拼 "hf_part_no = ANY(:hfPartNos)"，驱动视图必须暴露该列，否则报
            // "column inner_q.hf_part_no does not exist"。
            // hf_part_no 桶键必须是"本产品"的料号(3120018220)——它是 bucket/expandMulti 用来把
            // 一次$view查询结果分派回各 lineItem 的分桶键，不是这一行材质本身的物料标识；
            // "料号"(=partNoField 显式配置的业务列)才是材质本身的标识，两者语义不同，此前误把
            // hf_part_no 设成材质标识(2101110225)导致 bucket.get(productPartNo) 永远查不到该行。
            matView.sqlTemplate = "SELECT '3120018220'::text AS hf_part_no, '2101110225'::text AS material_no, '2101110225'::text AS \"料号\"";
            matView.declaredColumns = "[]";
            matView.persist();
            matViewId = matView.id;

            // ④ 报价模板 + 挂载两个组件
            Template tpl = new Template();
            tpl.templateSeriesId = UUID.randomUUID();
            tpl.name = TAG + "-模板";
            tpl.templateKind = "QUOTATION";
            tpl.status = "DRAFT";
            tpl.createdAt = OffsetDateTime.now();
            tpl.updatedAt = OffsetDateTime.now();
            tpl.persist();
            templateId = tpl.id;

            TemplateComponent tcTree = new TemplateComponent();
            tcTree.templateId = tpl.id;
            tcTree.componentId = treeComp.id;
            tcTree.tabName = "BOM树";
            tcTree.createdAt = OffsetDateTime.now();
            tcTree.persist();
            tcTreeId = tcTree.id;

            TemplateComponent tcMat = new TemplateComponent();
            tcMat.templateId = tpl.id;
            tcMat.componentId = matComp.id;
            tcMat.tabName = "材质元素";
            tcMat.createdAt = OffsetDateTime.now();
            tcMat.persist();
            tcMatId = tcMat.id;

            // ④.5 冻结 template.components_snapshot —— buildCardValues 读的是这份 JSONB 冻结列
            // （非实时 template_component 联表；只有生产 TemplateService#publish() 会写它），本测试
            // 直接构造 persist，未走真实 publish() 是为了绕开其无关校验（cross_tab_ref/小计配置等），
            // 字段形状与 TemplateService.publish() 第 219-243 行一致（componentId/tabName/fields/
            // data_driver_path 等，assembleTabsWithFormulaResults 消费所需的最小字段集）。
            try {
                com.fasterxml.jackson.databind.node.ArrayNode snapshot = M.createArrayNode();
                com.fasterxml.jackson.databind.node.ObjectNode treeEntry = snapshot.addObject();
                treeEntry.put("id", tcTree.id.toString());
                treeEntry.put("componentId", treeComp.id.toString());
                treeEntry.put("componentName", treeComp.name);
                treeEntry.put("componentCode", treeComp.code);
                treeEntry.put("componentType", "NORMAL");
                treeEntry.put("tabName", "BOM树");
                treeEntry.put("sortOrder", 0);
                treeEntry.set("fields", M.readTree(treeComp.fields));
                treeEntry.set("formulas", M.readTree(treeComp.formulas));
                treeEntry.put("data_driver_path", treeComp.dataDriverPath);

                com.fasterxml.jackson.databind.node.ObjectNode matEntry = snapshot.addObject();
                matEntry.put("id", tcMat.id.toString());
                matEntry.put("componentId", matComp.id.toString());
                matEntry.put("componentName", matComp.name);
                matEntry.put("componentCode", matComp.code);
                matEntry.put("componentType", "NORMAL");
                matEntry.put("tabName", "材质元素");
                matEntry.put("sortOrder", 1);
                matEntry.set("fields", M.readTree(matComp.fields));
                matEntry.set("formulas", M.readTree(matComp.formulas));
                matEntry.put("data_driver_path", matComp.dataDriverPath);

                em.createNativeQuery("UPDATE template SET components_snapshot = CAST(:snap AS jsonb) WHERE id = :id")
                        .setParameter("snap", M.writeValueAsString(snapshot))
                        .setParameter("id", tpl.id)
                        .executeUpdate();
            } catch (Exception e) {
                throw new RuntimeException("构造 template.components_snapshot 失败", e);
            }

            // ⑤ 报价单 + 报价行（root 用真实料号 3120018220，符合委托方"复用现有 DAG 料号"的精神）
            @SuppressWarnings("unchecked")
            List<Object> customers = em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList();
            assertFalse(customers.isEmpty(), "DB 无任何 customer,无法建报价单 fixture");
            UUID customerId = toUUID(customers.get(0));
            @SuppressWarnings("unchecked")
            List<Object> users = em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList();
            assertFalse(users.isEmpty(), "DB 无任何 user,无法建报价单 fixture");
            UUID salesRepId = toUUID(users.get(0));

            quotationId = UUID.randomUUID();
            em.createNativeQuery(
                    "INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, " +
                    " customer_template_id, tax_rate, tax_amount, created_at, updated_at) " +
                    "VALUES (:id, :qn, :cid, :name, :srid, 'DRAFT', :tid, 0, 0, now(), now())")
                    .setParameter("id", quotationId)
                    .setParameter("qn", TAG + "-" + quotationId.toString().substring(0, 8))
                    .setParameter("cid", customerId)
                    .setParameter("name", TAG + "-测试报价单")
                    .setParameter("srid", salesRepId)
                    .setParameter("tid", templateId)
                    .executeUpdate();

            lineItemId = UUID.randomUUID();
            em.createNativeQuery(
                    "INSERT INTO quotation_line_item (id, quotation_id, template_id, product_part_no_snapshot, " +
                    " sort_order, created_at) VALUES (:id, :qid, :tid, :pn, 0, now())")
                    .setParameter("id", lineItemId)
                    .setParameter("qid", quotationId)
                    .setParameter("tid", templateId)
                    .setParameter("pn", "3120018220")
                    .executeUpdate();
        });
    }

    private static UUID toUUID(Object o) {
        if (o instanceof UUID u) return u;
        return UUID.fromString(o.toString());
    }

    @SuppressWarnings("unchecked")
    private String readSnapshotRows(UUID lineId, UUID componentId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<Object> r = em.createNativeQuery(
                    "SELECT snapshot_rows::text FROM quotation_line_component_data " +
                    "WHERE line_item_id = :lid AND component_id = :cid")
                    .setParameter("lid", lineId).setParameter("cid", componentId)
                    .getResultList();
            return r.isEmpty() ? null : (String) r.get(0);
        });
    }

    @SuppressWarnings("unchecked")
    private String readDeletedTreeNodes(UUID lineId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<Object> r = em.createNativeQuery(
                    "SELECT deleted_tree_nodes::text FROM quotation_line_item WHERE id = :lid")
                    .setParameter("lid", lineId)
                    .getResultList();
            return r.isEmpty() ? null : (String) r.get(0);
        });
    }

    @Test
    @DisplayName("B3：真实物化 + spine 全节点 + 7 系统列 + __nodeType 判定")
    void b3_materializesSpineWithSystemColumnsAndNodeType() throws Exception {
        buildFixture();

        // 触发物化(与生产 saveDraft 后置调用同一入口)
        configureSnapshotService.snapshotQuotation(quotationId);

        String rowsJson = readSnapshotRows(lineItemId, treeComponentId);
        assertNotNull(rowsJson, "树组件 snapshot_rows 应已写入");
        JsonNode rows = M.readTree(rowsJson);
        assertTrue(rows.isArray(), "snapshot_rows 应是数组");

        // spine 应有 7 个 occurrence：root(3120018220) + 2120011658 + 2120011659
        //  + 3110520789(×2occ) + 2101110225(×2occ)
        assertEquals(7, rows.size(), "spine occurrence 数应为 7(root+2子+3110520789×2+2101110225×2),实际=" + rows.size());

        int rootCount = 0, dupPart = 0, withNodeType = 0;
        Map<String, Integer> nodeTypeByPart = new java.util.HashMap<>();
        for (JsonNode row : rows) {
            for (String sysCol : new String[]{"__nodeId", "__parentId", "__lvl", "__hfPartNo", "__parentNo", "__bomVersion", "__nodeType"}) {
                assertTrue(row.has(sysCol), "每行必须含系统列 " + sysCol + ", 实际行=" + row);
            }
            if (row.path("__hfPartNo").asText("").equals("3120018220")) rootCount++;
            if (row.path("__hfPartNo").asText("").equals("3110520789")) dupPart++;
            String nt = row.path("__nodeType").isNull() ? null : row.path("__nodeType").asText(null);
            if (nt != null) withNodeType++;
            nodeTypeByPart.put(row.path("__hfPartNo").asText(""), nt == null ? -1 : 1);
        }
        assertEquals(1, rootCount, "根节点应恰好 1 个 occurrence");
        assertEquals(2, dupPart, "3110520789 应有 2 个 occurrence(DAG 重复子件)");
        assertTrue(withNodeType >= 2, "至少 2101110225(材质) + 3110520789(结构推导零件) 应判定出类型,实际判定行数=" + withNodeType);

        // 连跑两次验证行数稳定(AP-51)
        configureSnapshotService.snapshotQuotation(quotationId);
        String rowsJson2 = readSnapshotRows(lineItemId, treeComponentId);
        JsonNode rows2 = M.readTree(rowsJson2);
        assertEquals(7, rows2.size(), "刷新后行数应保持稳定,不递增(AP-51)");

        System.out.println("[T0721E2E-B3] spine=" + rows.size() + " rootCount=" + rootCount
                + " dupPart(3110520789)=" + dupPart + " withNodeType=" + withNodeType);
    }

    @Test
    @DisplayName("B6：真实 add-leaf —— 挂到 2120011658 下的候选料号 2101110225(材质)")
    void b6_addLeaf_realEndpoint() {
        buildFixture();
        configureSnapshotService.snapshotQuotation(quotationId);

        Map<String, Object> resp = quotationTreeService.addLeaf(
                quotationId, lineItemId, treeComponentId, "3120018220/2120011658", "2101110225");
        assertNotNull(resp.get("nodeId"));
        assertEquals("材质", resp.get("nodeType"), "2101110225 命中材质元素页签,应判定为材质");
        assertNotNull(resp.get("quoteCardValues"), "应回灌整单 quoteCardValues");
        String newNodeId = (String) resp.get("nodeId");
        assertTrue(newNodeId.startsWith("3120018220/2120011658/__manual_"), "新节点 id 应挂在宿主节点下: " + newNodeId);

        // 校验真的插入到宿主节点行组之后（紧邻，不是 append 到数组末尾）
        String rowsJson = readSnapshotRows(lineItemId, treeComponentId);
        try {
            JsonNode rows = M.readTree(rowsJson);
            int hostIdx = -1, newIdx = -1;
            for (int i = 0; i < rows.size(); i++) {
                String nid = rows.get(i).path("__nodeId").asText("");
                if ("3120018220/2120011658".equals(nid)) hostIdx = i;
                if (newNodeId.equals(nid)) newIdx = i;
            }
            assertTrue(hostIdx >= 0, "宿主节点应仍在");
            assertEquals(hostIdx + 1, newIdx, "新叶子应紧邻插入在宿主节点之后,实际 hostIdx=" + hostIdx + " newIdx=" + newIdx);
        } catch (Exception e) { fail(e); }

        System.out.println("[T0721E2E-B6] addLeaf ok, newNodeId=" + newNodeId);
    }

    @Test
    @DisplayName("B6：宿主为材质类型节点时禁止加叶子(400)")
    void b6_addLeaf_rejectsWhenHostIsMaterial() {
        buildFixture();
        configureSnapshotService.snapshotQuotation(quotationId);
        // 2101110225 节点本身命中材质元素页签("材质"类型) —— 但它不在树上(只在material tab),
        // 用真正在树上的材质节点：加一个叶子后,以它为宿主试图再加一个叶子
        Map<String, Object> firstLeaf = quotationTreeService.addLeaf(
                quotationId, lineItemId, treeComponentId, "3120018220/2120011658", "2101110225");
        String materialNodeId = (String) firstLeaf.get("nodeId");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                quotationTreeService.addLeaf(quotationId, lineItemId, treeComponentId, materialNodeId, "2101110225"));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("材质"), ex.getMessage());
    }

    @Test
    @DisplayName("B7 ★DAG：剪 2120011658 支的 3110520789 → 不级联(retainedParts);再剪 2120011659 支 → 级联删除材质元素行")
    void b7_dagCascade_realEndpoints() {
        buildFixture();
        configureSnapshotService.snapshotQuotation(quotationId);

        String node658 = "3120018220/2120011658/3110520789";
        String node659 = "3120018220/2120011659/3110520789";

        // ① 预览剪 658 支
        Map<String, Object> preview1 = quotationTreeService.previewDelete(
                quotationId, lineItemId, treeComponentId, "PRUNE", node658, null);
        List<Map<String, Object>> retained1 = (List<Map<String, Object>>) preview1.get("retainedParts");
        List<Map<String, Object>> cascade1 = (List<Map<String, Object>>) preview1.get("cascadeTabs");
        assertTrue(cascade1.isEmpty(), "第一刀不应有级联(3110520789 在 659 支还有 occurrence)");
        assertTrue(retained1.stream().anyMatch(m -> "3110520789".equals(m.get("partNo"))),
                "3110520789 应出现在 retainedParts, 实际=" + retained1);
        String token1 = (String) preview1.get("previewToken");
        assertNotNull(token1);

        // ② 执行剪 658 支
        Map<String, Object> exec1 = quotationTreeService.executeDelete(
                quotationId, lineItemId, treeComponentId, "PRUNE", node658, null, token1);
        assertNotNull(exec1.get("quoteCardValues"));
        System.out.println("[T0721E2E-B7-DEBUG] deletedTreeNodes after exec1 = " + readDeletedTreeNodes(lineItemId));

        // ③ 预览剪 659 支(此时 3110520789 已无剩余 occurrence → 应级联,含材质元素页签的 2101110225 行)
        Map<String, Object> preview2 = quotationTreeService.previewDelete(
                quotationId, lineItemId, treeComponentId, "PRUNE", node659, null);
        List<Map<String, Object>> retained2 = (List<Map<String, Object>>) preview2.get("retainedParts");
        List<Map<String, Object>> cascade2 = (List<Map<String, Object>>) preview2.get("cascadeTabs");
        assertTrue(retained2.isEmpty(), "第二刀后不应再有 retainedParts, 实际=" + retained2);
        assertFalse(cascade2.isEmpty(), "第二刀应级联删除材质元素页签的行");
        boolean foundMatCascade = cascade2.stream().anyMatch(tab ->
                "材质元素".equals(tab.get("tabName")));
        assertTrue(foundMatCascade, "级联清单应含材质元素页签, 实际=" + cascade2);
        String token2 = (String) preview2.get("previewToken");

        // ④ 执行剪 659 支 → 真正级联删除
        Map<String, Object> exec2 = quotationTreeService.executeDelete(
                quotationId, lineItemId, treeComponentId, "PRUNE", node659, null, token2);
        Map<String, Object> cascadeKeys = (Map<String, Object>) exec2.get("cascadeDeletedRowKeys");
        assertTrue(cascadeKeys.containsKey(materialComponentId.toString()),
                "级联删除结果应含材质元素组件的墓碑, 实际=" + cascadeKeys);

        // ⑤ previewToken 防漂移：exec1 用过的 token1 现在应已失效(树已变化)
        BusinessException tokenEx = assertThrows(BusinessException.class, () ->
                quotationTreeService.executeDelete(quotationId, lineItemId, treeComponentId, "PRUNE", node658, null, token1));
        assertEquals(409, tokenEx.getCode(), "旧 token 应返回 409 要求重新预览");

        System.out.println("[T0721E2E-B7] DAG cascade verified: retained1=" + retained1
                + " cascade2=" + cascade2 + " cascadeKeys=" + cascadeKeys);
    }
}
