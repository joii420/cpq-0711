package com.cpq.quotation.service;

import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-0729 Task3 — {@link CardSnapshotService#refreshQuoteCardValues(QuotationLineItem, boolean)}
 * 空覆盖护栏（backtask.md §二 Task2.1，与 AP-60 同族不变量：任何情况下都不得用一次重查得到的空结果，
 * 覆盖已持久化的非空数据）。
 *
 * <p>对应 backtask.md §三：
 * <ul>
 *   <li>T5 护栏·重查空：新 baseRows 0 行 + 旧 quote_card_values 该组件非空 → 旧值被保留，
 *       {@link CardSnapshotService#EMPTY_OVERWRITE_BLOCKED_COUNT} +1。</li>
 *   <li>T6 护栏·正常更新不受影响：新 baseRows 非空 → 正常覆盖为新值，计数器不变。</li>
 * </ul>
 *
 * <p>测试策略：不退化为对护栏逻辑的孤立单测——护栏代码内联在 refreshQuoteCardValues 方法体内
 * （未抽取为可单独调用的纯函数），要验证它就必须走完整的 refresh 链路。fixture 模式与
 * {@code RefreshQuoteCardValuesTreeResilienceTest}（同包既有测试，验证树 $view 报错不 abort 整个
 * 刷新流程）完全一致：真实 $view 驱动一个 flat 组件，T5 让 $view 恒返回 0 行（{@code WHERE FALSE}，
 * 确定性、不依赖任何共享库数据），T6 让 $view 恒返回 1 行新值；旧 {@code quote_card_values} 直接用
 * 原生 SQL 手工写入一份"已持久化的非空 baseRows"，模拟真实场景里"上一次成功 expand 落库的旧值"。
 */
@QuarkusTest
@DisplayName("RefreshQuoteCardValuesEmptyOverwriteGuardTest — 空覆盖护栏（重查空不得覆盖已持久化非空数据）")
class RefreshQuoteCardValuesEmptyOverwriteGuardTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String TAG = "T0729GUARD";

    @Inject EntityManager em;
    @Inject CardSnapshotService cardSnapshotService;

    private UUID flatComponentId, flatViewId, templateId, tcFlatId, quotationId, lineItemId;

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
            if (tcFlatId != null) em.createNativeQuery("DELETE FROM template_component WHERE id = :id").setParameter("id", tcFlatId).executeUpdate();
            if (templateId != null) em.createNativeQuery("DELETE FROM template WHERE id = :id").setParameter("id", templateId).executeUpdate();
            if (flatViewId != null) em.createNativeQuery("DELETE FROM component_sql_view WHERE id = :id").setParameter("id", flatViewId).executeUpdate();
            if (flatComponentId != null) em.createNativeQuery("DELETE FROM component WHERE id = :id").setParameter("id", flatComponentId).executeUpdate();
            // 兜底：按 TAG 前缀再扫一遍
            em.createNativeQuery("DELETE FROM component WHERE code LIKE :p").setParameter("p", TAG + "%").executeUpdate();
            em.createNativeQuery("DELETE FROM template WHERE name LIKE :p").setParameter("p", TAG + "%").executeUpdate();
        });
    }

    private static UUID toUUID(Object o) {
        if (o instanceof UUID u) return u;
        return UUID.fromString(o.toString());
    }

    /**
     * 建 fixture：1 个 flat 驱动组件（$view 由调用方指定，控制返回 0 行或 1 行）+ 模板 + 报价单 + 行，
     * 旧 quote_card_values 手工写入 1 行非空 baseRows（标记 OLD_MARKER，模拟"已持久化的旧值"）。
     */
    private void buildFixture(String suffix, String viewSqlTemplate) {
        QuarkusTransaction.requiringNew().run(() -> {
            Component flatComp = new Component();
            flatComp.name = TAG + "-" + suffix + "-flat";
            flatComp.code = TAG + "-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
            flatComp.fields = "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}]";
            flatComp.formulas = "[]";
            flatComp.dataDriverPath = "$" + (TAG + "_" + suffix).toLowerCase();
            flatComp.persist();
            flatComponentId = flatComp.id;

            ComponentSqlView view = new ComponentSqlView();
            view.componentId = flatComp.id;
            view.sqlViewName = flatComp.dataDriverPath.substring(1);
            view.sqlTemplate = viewSqlTemplate;
            view.declaredColumns = "[]";
            view.persist();
            flatViewId = view.id;

            Template tpl = new Template();
            tpl.templateSeriesId = UUID.randomUUID();
            tpl.name = TAG + "-" + suffix + "-模板";
            tpl.templateKind = "QUOTATION";
            tpl.status = "DRAFT";
            tpl.createdAt = OffsetDateTime.now();
            tpl.updatedAt = OffsetDateTime.now();
            tpl.persist();
            templateId = tpl.id;

            TemplateComponent tcFlat = new TemplateComponent();
            tcFlat.templateId = tpl.id;
            tcFlat.componentId = flatComp.id;
            tcFlat.tabName = "平铺页签";
            tcFlat.createdAt = OffsetDateTime.now();
            tcFlat.persist();
            tcFlatId = tcFlat.id;

            try {
                com.fasterxml.jackson.databind.node.ArrayNode snapshot = M.createArrayNode();
                com.fasterxml.jackson.databind.node.ObjectNode flatEntry = snapshot.addObject();
                flatEntry.put("id", tcFlat.id.toString());
                flatEntry.put("componentId", flatComp.id.toString());
                flatEntry.put("componentName", flatComp.name);
                flatEntry.put("componentCode", flatComp.code);
                flatEntry.put("componentType", "NORMAL");
                flatEntry.put("tabName", "平铺页签");
                flatEntry.put("sortOrder", 0);
                flatEntry.set("fields", M.readTree(flatComp.fields));
                flatEntry.set("formulas", M.readTree(flatComp.formulas));
                flatEntry.put("data_driver_path", flatComp.dataDriverPath);

                tpl.componentsSnapshot = M.writeValueAsString(snapshot);
                tpl.persist();
            } catch (Exception e) {
                throw new RuntimeException("构造 template.components_snapshot 失败", e);
            }

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
                    .setParameter("qn", TAG + "-" + suffix + "-" + quotationId.toString().substring(0, 8))
                    .setParameter("cid", customerId)
                    .setParameter("name", TAG + "-" + suffix + "-测试报价单")
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
                    .setParameter("pn", TAG + "-" + suffix + "-P1")
                    .executeUpdate();

            // 旧 quote_card_values：该组件的 baseRows 手工写入 1 行非空(标记 OLD_MARKER)，
            // 模拟"上一次成功 expand 落库的旧值"（真实场景：pending 域切换/基础数据临时缺失导致本次重查为空）。
            String staleCardValues = "{\"tabs\":["
                + "{\"componentId\":\"" + flatComponentId + "\",\"tabName\":\"平铺页签\",\"baseRows\":["
                + "  {\"driverRow\":{\"名称\":\"OLD_MARKER_VALUE\"},\"basicDataValues\":{}}"
                + "]}"
                + "]}";
            em.createNativeQuery(
                    "UPDATE quotation_line_item SET quote_card_values = CAST(:v AS jsonb) WHERE id = :id")
                    .setParameter("v", staleCardValues)
                    .setParameter("id", lineItemId)
                    .executeUpdate();
        });
    }

    private JsonNode readFlatTabBaseRows() {
        String cardValuesJson = QuarkusTransaction.requiringNew().call(() -> {
            @SuppressWarnings("unchecked")
            List<Object> r = em.createNativeQuery(
                    "SELECT quote_card_values::text FROM quotation_line_item WHERE id = :id")
                    .setParameter("id", lineItemId).getResultList();
            return r.isEmpty() ? null : (String) r.get(0);
        });
        assertNotNull(cardValuesJson, "quote_card_values 不应为 null");
        try {
            JsonNode root = M.readTree(cardValuesJson);
            for (JsonNode tab : root.path("tabs")) {
                if (flatComponentId.toString().equals(tab.path("componentId").asText(""))) {
                    return tab.path("baseRows");
                }
            }
            fail("未找到平铺页签 tab, quote_card_values=" + cardValuesJson);
            return null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -----------------------------------------------------------------------
    // T5: 护栏 · 重查空 —— 旧值非空 + 新查 0 行 → 拦截覆盖，保留旧值，计数器 +1
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("T5: $view 本次重查返回 0 行 + 旧 baseRows 非空 → 护栏拦截，旧值原样保留，EMPTY_OVERWRITE_BLOCKED_COUNT +1")
    void refresh_blocksEmptyOverwrite_whenNewQueryReturnsZeroRows() {
        // $view 恒返回 0 行（WHERE FALSE，确定性，不依赖任何共享库数据）
        // hf_part_no 字面量必须与 buildFixture 里 line item 的 product_part_no_snapshot(TAG-t5-P1，
        // 大小写敏感)完全一致，否则不管 WHERE FALSE 与否都无法验证"按 partNo 匹配"这一真实语义。
        String emptyViewSql = "SELECT '" + TAG + "-t5-P1'::text AS hf_part_no, 'x'::text AS \"名称\" WHERE FALSE";
        buildFixture("t5", emptyViewSql);

        long blockedBefore = CardSnapshotService.EMPTY_OVERWRITE_BLOCKED_COUNT.get();

        QuotationLineItem li = QuarkusTransaction.requiringNew().call(() -> QuotationLineItem.findById(lineItemId));
        cardSnapshotService.refreshQuoteCardValues(li, true);

        long blockedAfter = CardSnapshotService.EMPTY_OVERWRITE_BLOCKED_COUNT.get();
        assertEquals(blockedBefore + 1, blockedAfter,
                "本次重查该组件 0 行、旧值非空 → 护栏必须拦截一次，计数器应恰好 +1");

        JsonNode baseRows = readFlatTabBaseRows();
        assertTrue(baseRows.isArray() && baseRows.size() == 1,
                "护栏应把旧 baseRows(1 行) 放回，不得被空结果覆盖成 0 行，实际=" + baseRows);
        assertEquals("OLD_MARKER_VALUE", baseRows.get(0).path("driverRow").path("名称").asText(""),
                "保留的应是旧值内容(OLD_MARKER_VALUE)，证明真的是「拦截覆盖」而非巧合");
    }

    // -----------------------------------------------------------------------
    // T6: 护栏 · 正常更新不受影响 —— 新查非空 → 正常覆盖为新值，计数器不变
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("T6: $view 本次重查返回 1 行新值 → 正常覆盖为新值(不触发护栏)，EMPTY_OVERWRITE_BLOCKED_COUNT 不变")
    void refresh_overwritesNormally_whenNewQueryReturnsNonEmptyRows() {
        // hf_part_no 字面量必须与 buildFixture 里 line item 的 product_part_no_snapshot(TAG-t6-P1，
        // 大小写敏感)完全一致，否则即便 $view 本身有 1 行也会因 hf_part_no 分桶匹配不上而查出 0 行
        // （首次跑本测试时正是踩了这个坑——字面量写成大写 "-T6-P1"，与 fixture 的小写 "-t6-P1" 不符）。
        String nonEmptyViewSql = "SELECT '" + TAG + "-t6-P1'::text AS hf_part_no, 'NEW_MARKER_VALUE'::text AS \"名称\"";
        buildFixture("t6", nonEmptyViewSql);

        long blockedBefore = CardSnapshotService.EMPTY_OVERWRITE_BLOCKED_COUNT.get();

        QuotationLineItem li = QuarkusTransaction.requiringNew().call(() -> QuotationLineItem.findById(lineItemId));
        cardSnapshotService.refreshQuoteCardValues(li, true);

        long blockedAfter = CardSnapshotService.EMPTY_OVERWRITE_BLOCKED_COUNT.get();
        assertEquals(blockedBefore, blockedAfter,
                "本次重查非空 → 护栏不应触发，计数器不应变化");

        JsonNode baseRows = readFlatTabBaseRows();
        assertTrue(baseRows.isArray() && baseRows.size() == 1,
                "应正常展开出 $view 的 1 行新数据，实际=" + baseRows);
        assertEquals("NEW_MARKER_VALUE", baseRows.get(0).path("driverRow").path("名称").asText(""),
                "应是本次重查的新值(NEW_MARKER_VALUE)，证明正常覆盖(不是巧合保留旧值)");
    }
}
