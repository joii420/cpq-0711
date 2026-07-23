package com.cpq.quotation.service;

import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.configure.service.ConfigureSnapshotService;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0721 follow-up（2026-07-23 业务裁决：匹配标识放宽为"料号列或名称列"）—— 端到端真实链路验证：
 * 一个只配 {@code part_name_field}（不配 {@code part_no_field}）的「外购件」类型页签，其名称值应能
 * 正确参与树节点类型判定，走真实生产入口
 * {@link ConfigureSnapshotService#snapshotQuotation}（而非仅 {@code extractMaterialNoByField} 单测）。
 *
 * <p>场景对齐委托方截图：「外购件/费用」类页签没有料号列，只用「料件名称」（如「组成件1」）做标识。
 * 本测试构造一棵最小树（root + 1 child），child 的 {@code __hfPartNo} 恰好等于「外购件」页签
 * 「料件名称」列的取值，验证物化后树节点 {@code __nodeType} 正确判定为「外购件」。
 *
 * <p>清理策略与 {@code QuoteBomTreeEndToEndTest} 一致：{@code QuarkusTransaction.requiringNew()}
 * 真提交 + {@code @AfterEach} 按依赖倒序真 DELETE；且同样需要"借道"暂时下线持久化 fixture 的
 * usage=QUOTE 现役配置、用后原样恢复（uq_bom_tree_config_active_per_usage 每 usage 至多一条 active）。
 */
@QuarkusTest
@DisplayName("PartNameFieldTypeJudgmentEndToEndTest — 仅配 part_name_field 的页签真实参与类型判定")
class PartNameFieldTypeJudgmentEndToEndTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String TAG = "T0721NAMEFIELD";
    private static final String ROOT_PART_NO = TAG + "-ROOT";
    private static final String CHILD_NAME = "组成件1"; // 对齐委托方截图场景的候选名称值

    @Inject EntityManager em;
    @Inject ConfigureSnapshotService configureSnapshotService;

    private UUID quoteConfigId, treeComponentId, wgComponentId, templateId,
            treeViewId, wgViewId, tcTreeId, tcWgId, quotationId, lineItemId;
    private UUID preExistingActiveQuoteConfigId;

    private static final String SYNTHETIC_RECURSIVE_SQL =
        "WITH RECURSIVE edges(parent_no, material_no) AS (\n" +
        "  VALUES\n" +
        "    ('" + ROOT_PART_NO + "'::text, '" + CHILD_NAME + "'::text)\n" +
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
            if (tcWgId != null) em.createNativeQuery("DELETE FROM template_component WHERE id = :id").setParameter("id", tcWgId).executeUpdate();
            if (templateId != null) em.createNativeQuery("DELETE FROM template WHERE id = :id").setParameter("id", templateId).executeUpdate();
            if (treeViewId != null) em.createNativeQuery("DELETE FROM component_sql_view WHERE id = :id").setParameter("id", treeViewId).executeUpdate();
            if (wgViewId != null) em.createNativeQuery("DELETE FROM component_sql_view WHERE id = :id").setParameter("id", wgViewId).executeUpdate();
            if (treeComponentId != null) em.createNativeQuery("DELETE FROM component WHERE id = :id").setParameter("id", treeComponentId).executeUpdate();
            if (wgComponentId != null) em.createNativeQuery("DELETE FROM component WHERE id = :id").setParameter("id", wgComponentId).executeUpdate();
            if (quoteConfigId != null) em.createNativeQuery("DELETE FROM costing_bom_tree_config WHERE id = :id").setParameter("id", quoteConfigId).executeUpdate();
            em.createNativeQuery("DELETE FROM component WHERE code LIKE :p").setParameter("p", TAG + "%").executeUpdate();
            em.createNativeQuery("DELETE FROM template WHERE name LIKE :p").setParameter("p", TAG + "%").executeUpdate();
            em.createNativeQuery("DELETE FROM costing_bom_tree_config WHERE name LIKE :p").setParameter("p", TAG + "%").executeUpdate();
            // 恢复"借道"前已存在的 QUOTE active 行(持久化验收 fixture)
            if (preExistingActiveQuoteConfigId != null) {
                em.createNativeQuery("UPDATE costing_bom_tree_config SET is_active = true WHERE id = :id")
                        .setParameter("id", preExistingActiveQuoteConfigId).executeUpdate();
            }
        });
    }

    private void buildFixture() {
        QuarkusTransaction.requiringNew().run(() -> {
            // ① 借道：暂时下线已存在的 QUOTE active 行，用完在 cleanup() 恢复
            @SuppressWarnings("unchecked")
            List<Object> existingActive = em.createNativeQuery(
                    "SELECT id FROM costing_bom_tree_config WHERE usage = 'QUOTE' AND is_active = true")
                    .getResultList();
            if (!existingActive.isEmpty()) {
                preExistingActiveQuoteConfigId = toUUID(existingActive.get(0));
                em.createNativeQuery("UPDATE costing_bom_tree_config SET is_active = false WHERE id = :id")
                        .setParameter("id", preExistingActiveQuoteConfigId).executeUpdate();
            }

            em.createNativeQuery(
                    "INSERT INTO costing_bom_tree_config (id, name, sql_template, is_active, usage, created_at, updated_at) " +
                    "VALUES (:id, :name, :sql, true, 'QUOTE', now(), now())")
                    .setParameter("id", quoteConfigId = UUID.randomUUID())
                    .setParameter("name", TAG + "-recursive-cfg")
                    .setParameter("sql", SYNTHETIC_RECURSIVE_SQL)
                    .executeUpdate();

            // ② 树组件(tab_type=BOM) —— $view 只需满足"有 driver path"的骨架要求，0 业务行即可
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
            treeView.sqlTemplate = "SELECT NULL::text AS material_no, NULL::text AS parent_no, " +
                    "NULL::text AS hf_part_no WHERE FALSE";
            treeView.declaredColumns = "[]";
            treeView.persist();
            treeViewId = treeView.id;

            // ③ 「外购件」页签组件 —— 只配 part_name_field(料件名称)，不配 part_no_field，
            //    对齐委托方截图场景（「外购件/费用」类页签无料号列）。
            Component wgComp = new Component();
            wgComp.name = TAG + "-外购件(仅名称列)";
            wgComp.code = TAG + "-WG-" + UUID.randomUUID().toString().substring(0, 6);
            wgComp.fields = "[{\"name\":\"料件名称\",\"field_type\":\"INPUT_TEXT\"},"
                    + "{\"name\":\"要素\",\"field_type\":\"INPUT_TEXT\"}]";
            wgComp.formulas = "[]";
            wgComp.tabType = "外购件";
            wgComp.partNoField = null;       // 显式不配
            wgComp.partNameField = "料件名称"; // 只配名称列
            wgComp.dataDriverPath = "$" + TAG.toLowerCase() + "_wg_view";
            wgComp.persist();
            wgComponentId = wgComp.id;

            ComponentSqlView wgView = new ComponentSqlView();
            wgView.componentId = wgComp.id;
            wgView.sqlViewName = TAG.toLowerCase() + "_wg_view";
            // hf_part_no 桶键 = 本产品根料号(ROOT_PART_NO)；「料件名称」列的值 = CHILD_NAME，
            // 与树上子节点的 __hfPartNo 相同字符串，验证"候选值是字符串比对，可以是名称"。
            wgView.sqlTemplate = "SELECT '" + ROOT_PART_NO + "'::text AS hf_part_no, " +
                    "'" + CHILD_NAME + "'::text AS \"料件名称\", '材料费'::text AS \"要素\"";
            wgView.declaredColumns = "[]";
            wgView.persist();
            wgViewId = wgView.id;

            // ④ 模板 + 挂载两个组件
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

            TemplateComponent tcWg = new TemplateComponent();
            tcWg.templateId = tpl.id;
            tcWg.componentId = wgComp.id;
            tcWg.tabName = "外购件";
            tcWg.createdAt = OffsetDateTime.now();
            tcWg.persist();
            tcWgId = tcWg.id;

            // ⑤ 报价单 + 报价行（真实客户/销售）
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
                    .setParameter("pn", ROOT_PART_NO)
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

    @Test
    @DisplayName("真实物化链路：仅配 part_name_field 的「外购件」页签，其名称值正确参与树节点 __nodeType 判定")
    void partNameFieldOnlyTab_realMaterialization_participatesInTypeJudgment() throws Exception {
        buildFixture();

        // 真实生产入口：与 saveDraft 后置调用同一方法
        configureSnapshotService.snapshotQuotation(quotationId);

        String treeRowsJson = readSnapshotRows(lineItemId, treeComponentId);
        assertNotNull(treeRowsJson, "树组件 snapshot_rows 应已物化写入");
        JsonNode rows = M.readTree(treeRowsJson);
        assertTrue(rows.isArray() && rows.size() == 2,
                "spine 应恰 2 行(root + child)，实际=" + rows.size());

        JsonNode childRow = null;
        for (JsonNode row : rows) {
            if (CHILD_NAME.equals(row.path("__hfPartNo").asText(""))) { childRow = row; break; }
        }
        assertNotNull(childRow, "应能找到 __hfPartNo=" + CHILD_NAME + " 的子节点行");
        assertEquals("外购件", childRow.path("__nodeType").asText(null),
                "子节点「" + CHILD_NAME + "」应被判定为「外购件」类型"
                        + "（依据「外购件」页签仅配置的 part_name_field=料件名称 取值命中）"
                        + "，实际 __nodeType=" + childRow.path("__nodeType"));

        // 同时确认「外购件」页签自身真实物化了 1 行真实数据(非空)
        String wgRowsJson = readSnapshotRows(lineItemId, wgComponentId);
        assertNotNull(wgRowsJson, "「外购件」组件 snapshot_rows 应已物化写入");
        JsonNode wgRows = M.readTree(wgRowsJson);
        assertEquals(1, wgRows.size(), "「外购件」页签应有 1 行");
        assertEquals(CHILD_NAME, wgRows.get(0).path("driverRow").path("料件名称").asText(""),
                "「外购件」页签「料件名称」列取值应为真实 $view 数据");

        System.out.println("[T0721-NAMEFIELD-E2E] 仅配 part_name_field 的页签真实参与类型判定验证通过："
                + "child __nodeType=" + childRow.path("__nodeType").asText("") + " ✅");
    }
}
