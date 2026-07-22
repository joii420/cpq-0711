package com.cpq.quotation.service;

import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
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
 * task-0721 follow-up（2026-07-22，真实数据 QT-20260722-2085 暴露）—— 回归测试：
 * 树组件 {@code $view} 本身查询报错时，{@link CardSnapshotService#refreshDraftQuoteCards} /
 * {@link CardSnapshotService#refreshQuoteCardValues} <b>不应</b>因此整体失败，树 tab 仍须从
 * {@code snapshot_rows} 拿到完整冻结 spine，且非树 tab 仍正常实时刷新。
 *
 * <p><b>真实事故根因</b>：{@link CardSnapshotService#expandFlatDriverBaseRows} 此前对
 * {@code tab_type='BOM'} 树组件也会跑 live {@code $view}（按本行 partNo 单值展开）。若该 $view
 * 本身有缺陷（如未输出 {@code hf_part_no} 列——COMP-0306 真实案例）触发 PostgreSQL 报错，
 * 该错误会把<b>当前 JTA 事务</b>整体置为 aborted（"current transaction is aborted, commands
 * ignored until end of transaction block"），导致同一事务内紧随其后的
 * {@link CardSnapshotService#overlayTreeTabsFromFrozenSnapshot} 查询也失败，整个
 * {@code refreshQuoteCardValues} 抛异常被外层 catch 吞掉，{@code quote_card_values} 保留旧值不更新
 * （symptom：树 tab 卡在物化时的行数，加叶子/剪枝等操作后的最新 snapshot_rows 刷不出来）。
 *
 * <p><b>修复</b>：{@code expandFlatDriverBaseRows} 现在遍历时直接跳过 {@code tab_type='BOM'}
 * 组件（不跑 live $view，交给 {@code overlayTreeTabsFromFrozenSnapshot} 从 snapshot_rows 恢复）——
 * 本测试故意给树组件挂一个引用不存在列的 $view（复现真实报错场景），验证：
 * <ol>
 *   <li>{@code refreshDraftQuoteCards} 不抛异常、正常返回；</li>
 *   <li>树 tab baseRows 仍等于 snapshot_rows 冻结的 2 行，每行带 __nodeId；</li>
 *   <li>同一行内的非树（flat）组件仍正常实时刷新（未被树组件的报错连坐）；</li>
 *   <li>{@code quote_values_at} 真的被更新（证明整个刷新流程完整跑完，未被吞异常提前中断）。</li>
 * </ol>
 *
 * <p>清理策略与 {@code QuoteBomTreeEndToEndTest} 一致：{@code QuarkusTransaction.requiringNew()}
 * 真提交 + {@code @AfterEach} 按依赖倒序真 DELETE + TAG 前缀兜底扫描。
 */
@QuarkusTest
@DisplayName("RefreshQuoteCardValuesTreeResilienceTest — 树 $view 报错不应 abort 整个刷新流程")
class RefreshQuoteCardValuesTreeResilienceTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String TAG = "T0721RESIL";

    @Inject EntityManager em;
    @Inject CardSnapshotService cardSnapshotService;

    private UUID treeComponentId, flatComponentId, treeViewId, flatViewId,
            templateId, tcTreeId, tcFlatId, quotationId, lineItemId;

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
            if (tcFlatId != null) em.createNativeQuery("DELETE FROM template_component WHERE id = :id").setParameter("id", tcFlatId).executeUpdate();
            if (templateId != null) em.createNativeQuery("DELETE FROM template WHERE id = :id").setParameter("id", templateId).executeUpdate();
            if (treeViewId != null) em.createNativeQuery("DELETE FROM component_sql_view WHERE id = :id").setParameter("id", treeViewId).executeUpdate();
            if (flatViewId != null) em.createNativeQuery("DELETE FROM component_sql_view WHERE id = :id").setParameter("id", flatViewId).executeUpdate();
            if (treeComponentId != null) em.createNativeQuery("DELETE FROM component WHERE id = :id").setParameter("id", treeComponentId).executeUpdate();
            if (flatComponentId != null) em.createNativeQuery("DELETE FROM component WHERE id = :id").setParameter("id", flatComponentId).executeUpdate();
            // 兜底：按 TAG 前缀再扫一遍
            em.createNativeQuery("DELETE FROM component WHERE code LIKE :p").setParameter("p", TAG + "%").executeUpdate();
            em.createNativeQuery("DELETE FROM template WHERE name LIKE :p").setParameter("p", TAG + "%").executeUpdate();
        });
    }

    /** 建 fixture：树组件(tab_type=BOM，$view 故意引用不存在的列触发报错) + 一个正常 flat 组件 + 模板 + 报价单。 */
    private void buildFixture() {
        QuarkusTransaction.requiringNew().run(() -> {
            // ① 树组件：$view 引用不存在的列 nonexistent_column_xyz，复现 COMP-0306 真实报错
            //    ("column ... does not exist")。
            Component treeComp = new Component();
            treeComp.name = TAG + "-BOM树(故意坏视图)";
            treeComp.code = TAG + "-TREE-" + UUID.randomUUID().toString().substring(0, 6);
            treeComp.fields = "[]";
            treeComp.formulas = "[]";
            treeComp.tabType = "BOM";
            treeComp.bomRecursiveExpand = true;
            treeComp.dataDriverPath = "$" + TAG.toLowerCase() + "_broken_tree_view";
            treeComp.persist();
            treeComponentId = treeComp.id;

            ComponentSqlView treeView = new ComponentSqlView();
            treeView.componentId = treeComp.id;
            treeView.sqlViewName = TAG.toLowerCase() + "_broken_tree_view";
            // 故意引用组件表里不存在的列，触发 PostgreSQL "column ... does not exist"
            treeView.sqlTemplate = "SELECT nonexistent_column_xyz AS hf_part_no FROM component LIMIT 1";
            treeView.declaredColumns = "[]";
            treeView.persist();
            treeViewId = treeView.id;

            // ② 正常 flat 组件：验证树组件报错不连坐同行其它组件的实时刷新
            Component flatComp = new Component();
            flatComp.name = TAG + "-正常平铺组件";
            flatComp.code = TAG + "-FLAT-" + UUID.randomUUID().toString().substring(0, 6);
            flatComp.fields = "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}]";
            flatComp.formulas = "[]";
            flatComp.dataDriverPath = "$" + TAG.toLowerCase() + "_flat_view";
            flatComp.persist();
            flatComponentId = flatComp.id;

            ComponentSqlView flatView = new ComponentSqlView();
            flatView.componentId = flatComp.id;
            flatView.sqlViewName = TAG.toLowerCase() + "_flat_view";
            flatView.sqlTemplate = "SELECT '" + TAG + "-P1'::text AS hf_part_no, '正常数据'::text AS \"名称\"";
            flatView.declaredColumns = "[]";
            flatView.persist();
            flatViewId = flatView.id;

            // ③ 模板挂载两个组件
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

            TemplateComponent tcFlat = new TemplateComponent();
            tcFlat.templateId = tpl.id;
            tcFlat.componentId = flatComp.id;
            tcFlat.tabName = "平铺页签";
            tcFlat.createdAt = OffsetDateTime.now();
            tcFlat.persist();
            tcFlatId = tcFlat.id;

            // 冻结 template.components_snapshot（buildCardValues/assembleTabsWithFormulaResults 读这个）
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
                treeEntry.put("tab_type", "BOM");

                com.fasterxml.jackson.databind.node.ObjectNode flatEntry = snapshot.addObject();
                flatEntry.put("id", tcFlat.id.toString());
                flatEntry.put("componentId", flatComp.id.toString());
                flatEntry.put("componentName", flatComp.name);
                flatEntry.put("componentCode", flatComp.code);
                flatEntry.put("componentType", "NORMAL");
                flatEntry.put("tabName", "平铺页签");
                flatEntry.put("sortOrder", 1);
                flatEntry.set("fields", M.readTree(flatComp.fields));
                flatEntry.set("formulas", M.readTree(flatComp.formulas));
                flatEntry.put("data_driver_path", flatComp.dataDriverPath);

                em.createNativeQuery("UPDATE template SET components_snapshot = CAST(:snap AS jsonb) WHERE id = :id")
                        .setParameter("snap", M.writeValueAsString(snapshot))
                        .setParameter("id", tpl.id)
                        .executeUpdate();
            } catch (Exception e) {
                throw new RuntimeException("构造 template.components_snapshot 失败", e);
            }

            // ④ 报价单 + 报价行（真实客户/销售，产品料号 = TAG-P1，与 flat 组件的 hf_part_no 对应）
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
                    " sort_order, created_at, card_snapshot_at) VALUES (:id, :qid, :tid, :pn, 0, now(), now())")
                    .setParameter("id", lineItemId)
                    .setParameter("qid", quotationId)
                    .setParameter("tid", templateId)
                    .setParameter("pn", TAG + "-P1")
                    .executeUpdate();

            // ⑤ 树组件 snapshot_rows 预先冻结 2 行(root + child)，模拟"已首次物化"的既有状态
            //    （本测试不依赖 BomTreeRenderService 真实递归，直接手工冻结，聚焦验证刷新链路本身）。
            String treeRows = "["
                + "{\"driverRow\":{},\"basicDataValues\":{},\"__nodeId\":\"ROOT\",\"__parentId\":null,"
                + "  \"__lvl\":0,\"__hfPartNo\":\"" + TAG + "-P1\",\"__parentNo\":null,\"__bomVersion\":null,\"__nodeType\":null},"
                + "{\"driverRow\":{},\"basicDataValues\":{},\"__nodeId\":\"ROOT/CHILD\",\"__parentId\":\"ROOT\","
                + "  \"__lvl\":1,\"__hfPartNo\":\"" + TAG + "-CHILD\",\"__parentNo\":\"" + TAG + "-P1\",\"__bomVersion\":null,\"__nodeType\":null}"
                + "]";
            em.createNativeQuery(
                    "INSERT INTO quotation_line_component_data (id, line_item_id, component_id, tab_name, snapshot_rows) " +
                    "VALUES (:id, :lid, :cid, :tab, CAST(:rows AS jsonb))")
                    .setParameter("id", UUID.randomUUID())
                    .setParameter("lid", lineItemId)
                    .setParameter("cid", treeComponentId)
                    .setParameter("tab", "BOM树")
                    .setParameter("rows", treeRows)
                    .executeUpdate();

            // flat 组件也先占位一份旧 quote_card_values(2 tab，均为空)，模拟"上次保存的旧值"，
            // 用于验证 refresh 后确实被新数据覆盖(而不是巧合地一直是这份旧值)。
            String staleCardValues = "{\"tabs\":["
                + "{\"componentId\":\"" + treeComponentId + "\",\"tabName\":\"BOM树\",\"baseRows\":[]},"
                + "{\"componentId\":\"" + flatComponentId + "\",\"tabName\":\"平铺页签\",\"baseRows\":[]}"
                + "]}";
            em.createNativeQuery(
                    "UPDATE quotation_line_item SET quote_card_values = CAST(:v AS jsonb) WHERE id = :id")
                    .setParameter("v", staleCardValues)
                    .setParameter("id", lineItemId)
                    .executeUpdate();
        });
    }

    private static UUID toUUID(Object o) {
        if (o instanceof UUID u) return u;
        return UUID.fromString(o.toString());
    }

    @Test
    @DisplayName("树 $view 报 SQL 错误时，refreshDraftQuoteCards 不 abort，树 tab 仍从 snapshot_rows 恢复完整 spine，flat tab 仍正常实时刷新")
    void refreshDraftQuoteCards_survivesBrokenTreeView() throws Exception {
        buildFixture();

        // 刷新前基线时间戳
        Object beforeAt = QuarkusTransaction.requiringNew().call(() -> {
            @SuppressWarnings("unchecked")
            List<Object> r = em.createNativeQuery(
                    "SELECT quote_values_at FROM quotation_line_item WHERE id = :id")
                    .setParameter("id", lineItemId).getResultList();
            return r.isEmpty() ? null : r.get(0);
        });

        // 真调 refreshDraftQuoteCards —— 与 REST 端点 POST .../refresh-card-snapshot 同一方法
        int refreshed = cardSnapshotService.refreshDraftQuoteCards(quotationId);
        assertEquals(1, refreshed, "应恰好重刷 1 行");

        // 读回最新 quote_card_values + quote_values_at
        String cardValuesJson = QuarkusTransaction.requiringNew().call(() -> {
            @SuppressWarnings("unchecked")
            List<Object> r = em.createNativeQuery(
                    "SELECT quote_card_values::text FROM quotation_line_item WHERE id = :id")
                    .setParameter("id", lineItemId).getResultList();
            return r.isEmpty() ? null : (String) r.get(0);
        });
        Object afterAt = QuarkusTransaction.requiringNew().call(() -> {
            @SuppressWarnings("unchecked")
            List<Object> r = em.createNativeQuery(
                    "SELECT quote_values_at FROM quotation_line_item WHERE id = :id")
                    .setParameter("id", lineItemId).getResultList();
            return r.isEmpty() ? null : r.get(0);
        });

        assertNotNull(cardValuesJson, "quote_card_values 不应为 null（不应因树组件报错整体失败）");
        assertNotEquals(String.valueOf(beforeAt), String.valueOf(afterAt),
                "quote_values_at 应真的被更新（证明刷新流程完整跑完，不是被吞异常提前中断）: before="
                        + beforeAt + " after=" + afterAt);

        JsonNode cardValues = M.readTree(cardValuesJson);
        JsonNode treeTab = null, flatTab = null;
        for (JsonNode tab : cardValues.path("tabs")) {
            if ("BOM树".equals(tab.path("tabName").asText(""))) treeTab = tab;
            if ("平铺页签".equals(tab.path("tabName").asText(""))) flatTab = tab;
        }
        assertNotNull(treeTab, "应含「BOM树」页签");
        assertNotNull(flatTab, "应含「平铺页签」页签");

        JsonNode treeBaseRows = treeTab.path("baseRows");
        assertTrue(treeBaseRows.isArray() && treeBaseRows.size() == 2,
                "树 tab baseRows 应恢复为 snapshot_rows 冻结的 2 行(root+child)，实际=" + treeBaseRows.size());
        int withNodeId = 0;
        for (JsonNode row : treeBaseRows) {
            if (row.has("__nodeId") && !row.path("__nodeId").isNull()) withNodeId++;
        }
        assertEquals(2, withNodeId, "每行都应带 __nodeId(系统列未被剥离)");

        JsonNode flatBaseRows = flatTab.path("baseRows");
        assertTrue(flatBaseRows.isArray() && flatBaseRows.size() == 1,
                "flat tab 应正常实时展开出 1 行(未被树组件的报错连坐)，实际=" + flatBaseRows.size());
        assertEquals("正常数据", flatBaseRows.get(0).path("driverRow").path("名称").asText(""),
                "flat tab 应拿到 $view 的真实数据(未被树组件报错拖累)");

        System.out.println("[T0721-RESIL] refreshDraftQuoteCards 在树组件 $view 报错下仍正常完成："
                + "树tab=" + treeBaseRows.size() + "行 flat tab=" + flatBaseRows.size() + "行 "
                + "quote_values_at 已更新 ✅");
    }
}
