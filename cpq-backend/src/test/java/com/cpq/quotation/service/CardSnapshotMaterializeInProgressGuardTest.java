package com.cpq.quotation.service;

import com.cpq.basicdata.v6.service.MaterializeRegistry;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260829（卡片值算早了骨架值锁死）· T-15（AC-2，B-1b 第二层防线）。
 *
 * <p><b>为什么需要 B-1b</b>：B-1 的 {@code isEarlySkeletonRender} 只能拦"①步写了一部分"的窗口
 * （此时该行至少有一个 tab 内组件的 comp_data 已非空,条件②能命中）;拦不住"①步一行 comp_data
 * 都还没写"的窗口——此时条件②天然为false(没有任何非空snapshot_rows可比对),B-1不拦,会把
 * 全空骨架值当"合法空结果"正常放行落库。B-1b 在真正开始计算前先问一句"这个报价单的建单后置
 * 物化是否还在飞"(`MaterializeRegistry.isInProgress`),命中则直接不算、不落库,把这个窗口也堵上。
 *
 * <p><b>覆盖什么</b>：{@code registry.begin(qid)} 模拟"①步正在飞"这个状态,验证此时调用
 * {@code ensureCardValues} 会被拦住(不落库、有对应WARN,与B-1的WARN文案用不同tag以便区分是
 * 哪层防线拦的);{@code registry.end(qid)} 后同一份数据应能正常算出。
 */
@QuarkusTest
class CardSnapshotMaterializeInProgressGuardTest {

    private static final String TAG = "T260829T15";

    @Inject EntityManager em;
    @Inject CardSnapshotService cardSnapshotService;
    @Inject MaterializeRegistry registry;

    private UUID componentId, templateId, quotationId, lineId;

    private static UUID toUUID(Object o) {
        return (o instanceof UUID u) ? u : UUID.fromString(o.toString());
    }

    @AfterEach
    void cleanup() {
        if (quotationId != null) registry.end(quotationId); // 保险丝:防止断言失败导致标志悬挂污染后续测试
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

    private void buildHealthyFixture() {
        componentId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO component (id, name, code, fields, formulas, data_driver_path, created_at, updated_at) " +
                    "VALUES (:id, :name, :code, CAST(:fields AS jsonb), CAST('[]' AS jsonb), :ddp, now(), now())")
                    .setParameter("id", componentId).setParameter("name", TAG + "-驱动组件")
                    .setParameter("code", TAG + "-" + componentId.toString().substring(0, 8))
                    .setParameter("fields", "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}]")
                    .setParameter("ddp", "$" + TAG.toLowerCase() + "_view").executeUpdate();
            em.createNativeQuery("INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template, declared_columns, created_at, updated_at) " +
                    "VALUES (:id, :cid, :vn, :tpl, '[]', now(), now())")
                    .setParameter("id", UUID.randomUUID()).setParameter("cid", componentId)
                    .setParameter("vn", TAG.toLowerCase() + "_view")
                    .setParameter("tpl", "SELECT '" + TAG + "-P1'::text AS hf_part_no, '正在物化'::text AS \"名称\"")
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
                    .setParameter("id", templateId).setParameter("tsid", UUID.randomUUID())
                    .setParameter("name", TAG + "-模板").setParameter("snap", snapshot).executeUpdate();
            UUID tcId = UUID.randomUUID();
            em.createNativeQuery("INSERT INTO template_component (id, template_id, component_id, sort_order, tab_name, created_at) " +
                    "VALUES (:id, :tid, :cid, 0, :tab, now())")
                    .setParameter("id", tcId).setParameter("tid", templateId).setParameter("cid", componentId)
                    .setParameter("tab", TAG + "页签").executeUpdate();
            em.createNativeQuery("INSERT INTO template_component_snapshot (template_id,template_component_id,component_id," +
                    "sort_order,tab_name,component_name,component_code,component_type,fields,formulas," +
                    "element_code_field,element_price_field,element_currency_field) " +
                    "VALUES (:templateId,:templateComponentId,:componentId,0,:tabName," +
                    ":componentName,:componentCode,'NORMAL',CAST(:fields AS jsonb),CAST('[]' AS jsonb),NULL,NULL,NULL)")
                    .setParameter("templateId", templateId).setParameter("templateComponentId", tcId)
                    .setParameter("componentId", componentId).setParameter("tabName", TAG + "页签")
                    .setParameter("componentName", TAG + "-驱动组件").setParameter("componentCode", TAG)
                    .setParameter("fields", "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}]")
                    .executeUpdate();
        });

        quotationId = UUID.randomUUID();
        lineId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            UUID customerId = toUUID(em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList().get(0));
            UUID salesRepId = toUUID(em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList().get(0));
            em.createNativeQuery("INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, " +
                    "customer_template_id, tax_rate, tax_amount, created_at, updated_at) " +
                    "VALUES (:id, :qn, :cid, :name, :srid, 'DRAFT', :tid, 0, 0, now(), now())")
                    .setParameter("id", quotationId).setParameter("qn", TAG + "-" + quotationId.toString().substring(0, 8))
                    .setParameter("cid", customerId).setParameter("name", TAG).setParameter("srid", salesRepId)
                    .setParameter("tid", templateId).executeUpdate();
            em.createNativeQuery("INSERT INTO quotation_line_item (id, quotation_id, template_id, " +
                    "product_part_no_snapshot, sort_order, created_at) VALUES (:id, :qid, :tid, :pn, 0, now())")
                    .setParameter("id", lineId).setParameter("qid", quotationId).setParameter("tid", templateId)
                    .setParameter("pn", TAG + "-P0").executeUpdate();
            em.createNativeQuery("INSERT INTO quotation_line_component_data (id, line_item_id, component_id, tab_name, " +
                    "row_data, snapshot_rows) VALUES (:id, :lid, :cid, :tab, CAST(:rd AS jsonb), CAST(:sr AS jsonb))")
                    .setParameter("id", UUID.randomUUID()).setParameter("lid", lineId).setParameter("cid", componentId)
                    .setParameter("tab", TAG + "页签")
                    .setParameter("rd", "[{\"row_index\":0,\"名称\":\"正在物化\"}]")
                    .setParameter("sr", "[{\"driverRow\":{\"hf_part_no\":\"" + TAG + "-P1\",\"名称\":\"正在物化\"}," +
                            "\"basicDataValues\":{}}]")
                    .executeUpdate();
        });
    }

    private String readQuoteCardValues() {
        Object v = em.createNativeQuery("SELECT quote_card_values::text FROM quotation_line_item WHERE id = :id")
                .setParameter("id", lineId).getSingleResult();
        return v == null ? null : v.toString();
    }

    private List<String> captureWarnDuring(Runnable action) {
        List<String> captured = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue() && record.getMessage() != null) {
                    captured.add(record.getMessage());
                }
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Logger root = Logger.getLogger("");
        root.addHandler(handler);
        try {
            action.run();
        } finally {
            root.removeHandler(handler);
        }
        return captured;
    }

    @Test
    @DisplayName("T-15(AC-2,B-1b): registry.isInProgress=true 时 ensureCardValues 不算不落库+返回WARMING_IN_PROGRESS+有WARN;end后恢复正常")
    void materializeInProgress_blocksComputation_thenRecoversAfterEnd() {
        buildHealthyFixture();
        assertNull(readQuoteCardValues(), "开跑前 quote_card_values 应为 NULL");

        registry.begin(quotationId);
        assertTrue(registry.isInProgress(quotationId), "前置条件确认: registry 应标记为进行中");

        List<String> warnings1 = captureWarnDuring(() -> {
            // 🔴 主线复审抓到的静默失败(2026-08-29):B-1b 命中时最初返回 EnsureResult(0,0,0)——
            // QuotationService.submit:900 只认 WARMING_IN_PROGRESS(-1) 和 failedBatches>0 两个 409
            // 分支,返回0两个都不触发,会直接放行用缺失的卡片值冻结金额。断言必须精确到"返回值是
            // WARMING_IN_PROGRESS(-1)",不能只断言"没落库"——后者只证明"没干活",证明不了
            // "正确地告诉了调用方没干活"(本任务族反复踩的"动作对了但信号没发对"那类坑)。
            CardSnapshotService.EnsureResult r = cardSnapshotService.ensureCardValuesDetailed(quotationId, false);
            assertEquals(CardSnapshotService.WARMING_IN_PROGRESS, r.computed,
                    "物化进行中时,ensureCardValuesDetailed 的 computed 必须是 WARMING_IN_PROGRESS(-1)," +
                    "不能是0(0会让 submit 的409门禁误判'补算完成'从而冻结缺失金额)");
        });
        String cvDuringMaterialize = readQuoteCardValues();
        assertNull(cvDuringMaterialize, "物化进行中时不应落库,quote_card_values 应仍为 NULL");
        assertTrue(warnings1.stream().anyMatch(w -> w.contains("ensure-cardvalues-materializing")),
                "应有 B-1b 专属WARN(ensure-cardvalues-materializing),实际 warnings=" + warnings1);

        registry.end(quotationId);
        assertFalse(registry.isInProgress(quotationId), "end 后应不再是进行中状态");

        List<String> warnings2 = captureWarnDuring(() -> {
            int filled = cardSnapshotService.ensureCardValues(quotationId);
            assertEquals(1, filled, "物化结束后,同一行应能被正常补算(不是空跑)");
        });
        String cvAfter = readQuoteCardValues();
        assertNotNull(cvAfter, "物化结束后应正常落库,不再为NULL");
        assertTrue(cvAfter.contains("正在物化"), "应包含预插的真实内容,实际=" + cvAfter);
        assertFalse(warnings2.stream().anyMatch(w -> w.contains("ensure-cardvalues-materializing")),
                "物化已结束,不应再有B-1b的WARN,实际=" + warnings2);

        System.out.printf("[T-15] 进行中拦截=%s(NULL) → end后恢复=%s%n", cvDuringMaterialize, cvAfter);
    }
}
