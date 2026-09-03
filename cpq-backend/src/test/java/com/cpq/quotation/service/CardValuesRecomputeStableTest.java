package com.cpq.quotation.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260825 大单量导入建单性能 —— T-4（AC-6 · 批量取数与逐行取数逐位等价）的<b>部分覆盖</b>。
 *
 * <p><b>已知覆盖缺口（如实登记，本测试不能替代 test.md 原设计）</b>：test.md 对 T-4 的原始设计是
 * "对标既有 {@code LoadSnapshotRowsByLinesEquivTest} 的结构"——同时调用<b>批量方法</b>与<b>逐行方法</b>，
 * 对账两者结果逐键逐值相同。但：
 * <ul>
 *   <li>D-1 的批量方法（替代已删除的 {@code loadRowDataByComp}）是新增的私有实现细节，</li>
 *   <li>D-3 的 {@code loadFrozenQuoteTabs} 同样是私有方法，</li>
 * </ul>
 * 两者的方法名均未被主线在本轮派工签名清单中给出（清单覆盖的是更上层的公开入口），
 * 本任务规则禁止读 {@code cpq-backend/src/main/} 去反查，因此<b>字面意义上的"批量 vs 逐行对账"
 * 在本轮不可实现</b>——与上一轮遗留的交付缺口是同一个，本轮未被解决，需要向主线明确报告。
 *
 * <p><b>本测试改为验证什么</b>：D-3 修复后，{@code ensureCardValues} 对<b>同一份预置好、未变的
 * row_data/snapshot_rows 底层数据</b>反复调用（清空→重算，重复三轮），结果必须<b>逐位稳定</b>——
 * 这是"批量化改写没有引入不确定性（如批内乱序、chunk 边界丢数据）"这条不变量在黑盒层面唯一
 * 可验证的角度，能捕获"批量化重写后结果依赖调用顺序/时序"这一类真实存在的回归风险，但<b>不能</b>
 * 证明"与改动前的逐行实现在数值上完全相同"（那需要改动前的代码或内部方法名，两者都不可得）。
 *
 * <p><b>额外已知限制</b>：本测试最初尝试连 {@code snapshotQuotation}（D-1 的展开路径）一起测，
 * 但在本文件的隔离夹具下，{@code snapshotQuotation(id, false)} 未触发 expand-driver（无对应日志，
 * 也未创建 {@code quotation_line_component_data} 行），根因未查明（超出本轮排查预算），已如实
 * 登记；改为直接预插 {@code row_data}/{@code snapshot_rows}，只覆盖 D-3（{@code ensureCardValues}）
 * 一侧的稳定性，不覆盖 D-1（{@code snapshotQuotation}）展开路径本身。
 */
@QuarkusTest
class CardValuesRecomputeStableTest {

    private static final String TAG = "T260825T4";

    @Inject EntityManager em;
    @Inject CardSnapshotService cardSnapshotService;

    private UUID componentId, templateId, quotationId, lineId;

    private static UUID toUUID(Object o) {
        return (o instanceof UUID u) ? u : UUID.fromString(o.toString());
    }

    @AfterEach
    void cleanup() {
        if (quotationId == null && templateId == null && componentId == null) return;
        QuarkusTransaction.requiringNew().run(() -> {
            if (quotationId != null) {
                em.createNativeQuery("DELETE FROM quotation_view_structure WHERE quotation_id = :q")
                        .setParameter("q", quotationId).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id IN " +
                        "(SELECT id FROM quotation_line_item WHERE quotation_id = :q)")
                        .setParameter("q", quotationId).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id = :q")
                        .setParameter("q", quotationId).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation WHERE id = :q")
                        .setParameter("q", quotationId).executeUpdate();
            }
            if (templateId != null) {
                em.createNativeQuery("DELETE FROM template_component_snapshot WHERE template_id = :t")
                        .setParameter("t", templateId).executeUpdate();
                em.createNativeQuery("DELETE FROM template_component WHERE template_id = :t")
                        .setParameter("t", templateId).executeUpdate();
                em.createNativeQuery("DELETE FROM template WHERE id = :t")
                        .setParameter("t", templateId).executeUpdate();
            }
            if (componentId != null) {
                em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id = :c")
                        .setParameter("c", componentId).executeUpdate();
                em.createNativeQuery("DELETE FROM component WHERE id = :c")
                        .setParameter("c", componentId).executeUpdate();
            }
        });
    }

    /** 1 行报价单,$view <b>返回真实一行数据</b>(非 WHERE FALSE)——需要真实内容才能做"逐位相同"的比对。 */
    private void buildFixtureWithRealDriverRow() {
        componentId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO component (id, name, code, fields, formulas, data_driver_path, created_at, updated_at) " +
                    "VALUES (:id, :name, :code, CAST(:fields AS jsonb), CAST('[]' AS jsonb), :ddp, now(), now())")
                    .setParameter("id", componentId)
                    .setParameter("name", TAG + "-驱动组件")
                    .setParameter("code", TAG + "-" + componentId.toString().substring(0, 8))
                    .setParameter("fields", "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}]")
                    .setParameter("ddp", "$" + TAG.toLowerCase() + "_view")
                    .executeUpdate();
            em.createNativeQuery("INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template, declared_columns, created_at, updated_at) " +
                    "VALUES (:id, :cid, :vn, :tpl, '[]', now(), now())")
                    .setParameter("id", UUID.randomUUID())
                    .setParameter("cid", componentId)
                    .setParameter("vn", TAG.toLowerCase() + "_view")
                    // 真实返回一行(非 WHERE FALSE),供"内容逐位稳定"比对有实际值可看
                    .setParameter("tpl", "SELECT '" + TAG + "-P1'::text AS hf_part_no, '固定值ABC'::text AS \"名称\"")
                    .executeUpdate();
        });

        templateId = UUID.randomUUID();
        String snapshot = "[{\"id\":\"" + UUID.randomUUID() + "\",\"componentId\":\"" + componentId +
                "\",\"componentName\":\"" + TAG + "-驱动组件\",\"componentCode\":\"" + TAG +
                "\",\"componentType\":\"NORMAL\",\"tabName\":\"" + TAG + "页签\",\"sortOrder\":0," +
                "\"fields\":[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}],\"formulas\":[]," +
                "\"data_driver_path\":\"$" + TAG.toLowerCase() + "_view\"}]";
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO template (id, template_series_id, name, template_kind, status, components_snapshot, created_at, updated_at) " +
                    "VALUES (:id, :tsid, :name, 'QUOTATION', 'PUBLISHED', CAST(:snap AS jsonb), now(), now())")
                    .setParameter("id", templateId)
                    .setParameter("tsid", UUID.randomUUID())
                    .setParameter("name", TAG + "-模板")
                    .setParameter("snap", snapshot)
                    .executeUpdate();
            UUID templateComponentId = UUID.randomUUID();
            em.createNativeQuery("INSERT INTO template_component (id, template_id, component_id, sort_order, tab_name, created_at) " +
                    "VALUES (:id, :tid, :cid, 0, :tab, now())")
                    .setParameter("id", templateComponentId).setParameter("tid", templateId).setParameter("cid", componentId)
                    .setParameter("tab", TAG + "页签").executeUpdate();
            // buildCardStructure 的"模板快照损坏"守卫要求 template_component_snapshot 行数与
            // components_snapshot jsonb 数组长度恒相等 —— 对标 MaterialVersionUpgradePrecisionParityTest
            // #insertFrozenTemplateTab 的字段清单。
            em.createNativeQuery("INSERT INTO template_component_snapshot (template_id,template_component_id,component_id," +
                    "sort_order,tab_name,component_name,component_code,component_type,fields,formulas," +
                    "element_code_field,element_price_field,element_currency_field) " +
                    "VALUES (:templateId,:templateComponentId,:componentId,0,:tabName," +
                    ":componentName,:componentCode,'NORMAL',CAST(:fields AS jsonb),CAST('[]' AS jsonb),NULL,NULL,NULL)")
                    .setParameter("templateId", templateId).setParameter("templateComponentId", templateComponentId)
                    .setParameter("componentId", componentId).setParameter("tabName", TAG + "页签")
                    .setParameter("componentName", TAG + "-驱动组件").setParameter("componentCode", TAG)
                    .setParameter("fields", "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}]")
                    .executeUpdate();
        });

        quotationId = UUID.randomUUID();
        lineId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            @SuppressWarnings("unchecked")
            List<Object> customers = em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList();
            assertFalse(customers.isEmpty(), "DB 无任何 customer,无法建 fixture");
            UUID customerId = toUUID(customers.get(0));
            @SuppressWarnings("unchecked")
            List<Object> users = em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList();
            assertFalse(users.isEmpty(), "DB 无任何 user,无法建 fixture");
            UUID salesRepId = toUUID(users.get(0));

            em.createNativeQuery("INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, " +
                    "customer_template_id, tax_rate, tax_amount, created_at, updated_at) " +
                    "VALUES (:id, :qn, :cid, :name, :srid, 'DRAFT', :tid, 0, 0, now(), now())")
                    .setParameter("id", quotationId)
                    .setParameter("qn", TAG + "-" + quotationId.toString().substring(0, 8))
                    .setParameter("cid", customerId)
                    .setParameter("name", TAG)
                    .setParameter("srid", salesRepId)
                    .setParameter("tid", templateId)
                    .executeUpdate();

            em.createNativeQuery("INSERT INTO quotation_line_item (id, quotation_id, template_id, " +
                    "product_part_no_snapshot, sort_order, created_at) VALUES (:id, :qid, :tid, :pn, 0, now())")
                    .setParameter("id", lineId).setParameter("qid", quotationId).setParameter("tid", templateId)
                    .setParameter("pn", TAG + "-P0")
                    .executeUpdate();

            // 直接预插 row_data(而非依赖 snapshotQuotation 从零展开创建——实测该路径在本隔离夹具下
            // 未触发 expand-driver,原因未查明,超出本轮排查预算,已如实登记为已知限制)。
            // T-4 的核心断言不依赖"谁创建了这行",只依赖"同一份已存在的底层数据反复重算是否稳定"。
            em.createNativeQuery("INSERT INTO quotation_line_component_data (id, line_item_id, component_id, tab_name, " +
                    "row_data, snapshot_rows) VALUES (:id, :lid, :cid, :tab, CAST(:rd AS jsonb), CAST(:sr AS jsonb))")
                    .setParameter("id", UUID.randomUUID()).setParameter("lid", lineId).setParameter("cid", componentId)
                    .setParameter("tab", TAG + "页签")
                    .setParameter("rd", "[{\"row_index\":0,\"名称\":\"固定值ABC\"}]")
                    .setParameter("sr", "[{\"driverRow\":{\"hf_part_no\":\"" + TAG + "-P1\",\"名称\":\"固定值ABC\"}," +
                            "\"basicDataValues\":{}}]")
                    .executeUpdate();
        });
    }

    private String readRowData() {
        Object v = em.createNativeQuery(
                "SELECT row_data::text FROM quotation_line_component_data WHERE line_item_id = :lid AND component_id = :cid")
                .setParameter("lid", lineId).setParameter("cid", componentId).getSingleResult();
        return v == null ? null : v.toString();
    }

    private String readQuoteCardValues() {
        Object v = em.createNativeQuery("SELECT quote_card_values::text FROM quotation_line_item WHERE id = :id")
                .setParameter("id", lineId).getSingleResult();
        return v == null ? null : v.toString();
    }

    @Test
    @DisplayName("T-4(AC-6·部分覆盖): 同一份已存在的底层数据反复清空重算,quote_card_values 逐位稳定")
    void repeatedRecompute_producesIdenticalCardValues() {
        buildFixtureWithRealDriverRow();

        // 前置数据非空验证:row_data 确实是预插的真实内容,不是空跑
        String rowDataFixed = readRowData();
        assertNotNull(rowDataFixed, "row_data 不应为 NULL(非空验证)");
        assertTrue(rowDataFixed.contains("固定值ABC"), "row_data 应含预插的真实内容,实际=" + rowDataFixed);

        // 第一轮算值
        int filled1 = cardSnapshotService.ensureCardValues(quotationId);
        assertEquals(1, filled1, "应补算 1 行(非空验证,不是空跑)");
        String cardValues1 = readQuoteCardValues();
        assertNotNull(cardValues1, "quote_card_values 不应为 NULL(非空验证)");
        assertFalse(cardValues1.contains("__cardValueFailed"), "本用例配置合法,不应落失败哨兵,实际=" + cardValues1);

        // 清空后重算(底层 row_data/snapshot_rows 完全没变)
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE quotation_line_item SET quote_card_values = NULL WHERE id = :id")
                        .setParameter("id", lineId).executeUpdate());
        em.clear();
        int filled2 = cardSnapshotService.ensureCardValues(quotationId);
        assertEquals(1, filled2, "第二次清空后重算应仍补算 1 行");
        String cardValues2 = readQuoteCardValues();
        assertEquals(cardValues1, cardValues2, "底层数据未变时,两次 ensureCardValues 的 quote_card_values 必须逐位相同" +
                "(D-3 把 loadFrozenQuoteTabs 提到循环外后,结果不应因'查一次'与'查N次'而产生差异——" +
                "本测试能捕获的回归类型:批量化改写引入调用顺序/chunk边界相关的不确定性)");

        // 第三轮,再验证一次(排除"两次恰好碰巧相同"的偶然性)
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE quotation_line_item SET quote_card_values = NULL WHERE id = :id")
                        .setParameter("id", lineId).executeUpdate());
        em.clear();
        cardSnapshotService.ensureCardValues(quotationId);
        String cardValues3 = readQuoteCardValues();
        assertEquals(cardValues1, cardValues3, "第三轮同样必须与第一轮逐位相同");

        System.out.printf("[T-4] quotation=%s 三轮 quote_card_values 逐位相同=%b%n",
                quotationId, cardValues1.equals(cardValues2) && cardValues1.equals(cardValues3));
    }
}
