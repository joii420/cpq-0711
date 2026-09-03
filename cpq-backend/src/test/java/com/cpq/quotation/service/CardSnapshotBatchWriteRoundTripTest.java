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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260828 · T-2（AC-5）：③/④ 两段的 UPDATE 往返次数 = 批数（不是行数）。
 *
 * <p><b>判据来源</b>：{@code api.md}"新增的日志埋点"一节的逐字契约——
 * {@code [perf] ensure-cardvalues-write quotation=<uuid> rows=<N> batches=<M> updates=<K>}
 * / {@code [perf] ensure-excel-write ...} 同款，{@code updates} = {@code executeBatch()}
 * 调用次数，是 api.md 点名的"AC-5 的唯一可复核依据"（{@code pg_stat_statements} 在
 * {@code cpq_db} 上未安装）。
 *
 * <p><b>捕获手法</b>：该行经 JBoss Logging（{@code Logger.info}）输出，不是裸
 * {@code System.out.println}，`System.setOut` 换不到已初始化的 appender 引用；改用
 * {@code java.util.logging.Handler} 挂在根 logger 上拦截 {@link LogRecord}，方法结束后卸载。
 *
 * <p><b>还原实验</b>：本测试断言"至少捕获到 1 行匹配日志"——B-6 埋点在改动前的 master
 * 基线上根本不存在（纯新增日志，无同名旧格式），git stash 掉 {@code CardSnapshotService.java}
 * 后重跑，预期该断言直接失败（0 行匹配）——过程与结论见 test-report.md。
 */
@QuarkusTest
class CardSnapshotBatchWriteRoundTripTest {

    private static final Pattern CARD_WRITE = Pattern.compile(
            "\\[perf\\] ensure-cardvalues-write quotation=([0-9a-fA-F-]+) rows=(\\d+) batches=(\\d+) updates=(\\d+)");
    private static final Pattern EXCEL_WRITE = Pattern.compile(
            "\\[perf\\] ensure-excel-write\\s+quotation=([0-9a-fA-F-]+) rows=(\\d+) batches=(\\d+) updates=(\\d+)");

    @Inject EntityManager em;
    @Inject CardSnapshotService cardSnapshotService;

    private final List<UUID> quotationIds = new ArrayList<>();
    private final List<UUID> templateIds = new ArrayList<>();
    private final List<UUID> componentIds = new ArrayList<>();

    private static UUID toUUID(Object o) {
        return (o instanceof UUID u) ? u : UUID.fromString(o.toString());
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (UUID qid : quotationIds) {
                em.createNativeQuery("DELETE FROM quotation_view_structure WHERE quotation_id = :q").setParameter("q", qid).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id IN (SELECT id FROM quotation_line_item WHERE quotation_id = :q)").setParameter("q", qid).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation_line_item WHERE quotation_id = :q").setParameter("q", qid).executeUpdate();
                em.createNativeQuery("DELETE FROM quotation WHERE id = :q").setParameter("q", qid).executeUpdate();
            }
            for (UUID t : templateIds) {
                em.createNativeQuery("DELETE FROM template_component_snapshot WHERE template_id = :t").setParameter("t", t).executeUpdate();
                em.createNativeQuery("DELETE FROM template_component WHERE template_id = :t").setParameter("t", t).executeUpdate();
                em.createNativeQuery("DELETE FROM template WHERE id = :t").setParameter("t", t).executeUpdate();
            }
            for (UUID c : componentIds) {
                em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id = :c").setParameter("c", c).executeUpdate();
                em.createNativeQuery("DELETE FROM component WHERE id = :c").setParameter("c", c).executeUpdate();
            }
        });
        quotationIds.clear();
        templateIds.clear();
        componentIds.clear();
    }

    /** 同 T-1 手法：PUBLISHED 模板 + template_component_snapshot + 0 行 $view + 无 componentData。 */
    private UUID buildQuotation(int n, String tag) {
        UUID componentId = UUID.randomUUID();
        componentIds.add(componentId);
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO component (id, name, code, fields, formulas, data_driver_path, created_at, updated_at) " +
                    "VALUES (:id, :name, :code, CAST(:fields AS jsonb), CAST('[]' AS jsonb), :ddp, now(), now())")
                    .setParameter("id", componentId).setParameter("name", tag + "-驱动组件")
                    .setParameter("code", tag + "-" + componentId.toString().substring(0, 8))
                    .setParameter("fields", "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}]")
                    .setParameter("ddp", "$" + tag.toLowerCase() + "_view").executeUpdate();
            em.createNativeQuery("INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template, declared_columns, created_at, updated_at) " +
                    "VALUES (:id, :cid, :vn, :tpl, '[]', now(), now())")
                    .setParameter("id", UUID.randomUUID()).setParameter("cid", componentId)
                    .setParameter("vn", tag.toLowerCase() + "_view")
                    .setParameter("tpl", "SELECT '" + tag + "-P1'::text AS hf_part_no, 'x'::text AS \"名称\" WHERE FALSE")
                    .executeUpdate();
        });

        UUID templateId = UUID.randomUUID();
        templateIds.add(templateId);
        String snapshot = "[{\"id\":\"" + UUID.randomUUID() + "\",\"componentId\":\"" + componentId +
                "\",\"componentName\":\"" + tag + "-驱动组件\",\"componentCode\":\"" + tag +
                "\",\"componentType\":\"NORMAL\",\"tabName\":\"" + tag + "页签\",\"sortOrder\":0," +
                "\"fields\":[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}],\"formulas\":[]," +
                "\"data_driver_path\":\"$" + tag.toLowerCase() + "_view\"}]";
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO template (id, template_series_id, name, template_kind, status, components_snapshot, created_at, updated_at) " +
                    "VALUES (:id, :tsid, :name, 'QUOTATION', 'PUBLISHED', CAST(:snap AS jsonb), now(), now())")
                    .setParameter("id", templateId).setParameter("tsid", UUID.randomUUID())
                    .setParameter("name", tag + "-模板").setParameter("snap", snapshot).executeUpdate();
            UUID tcId = UUID.randomUUID();
            em.createNativeQuery("INSERT INTO template_component (id, template_id, component_id, sort_order, tab_name, created_at) " +
                    "VALUES (:id, :tid, :cid, 0, :tab, now())")
                    .setParameter("id", tcId).setParameter("tid", templateId).setParameter("cid", componentId)
                    .setParameter("tab", tag + "页签").executeUpdate();
            em.createNativeQuery("INSERT INTO template_component_snapshot (template_id,template_component_id,component_id," +
                    "sort_order,tab_name,component_name,component_code,component_type,fields,formulas," +
                    "element_code_field,element_price_field,element_currency_field) " +
                    "VALUES (:templateId,:templateComponentId,:componentId,0,:tabName," +
                    ":componentName,:componentCode,'NORMAL',CAST(:fields AS jsonb),CAST('[]' AS jsonb),NULL,NULL,NULL)")
                    .setParameter("templateId", templateId).setParameter("templateComponentId", tcId)
                    .setParameter("componentId", componentId).setParameter("tabName", tag + "页签")
                    .setParameter("componentName", tag + "-驱动组件").setParameter("componentCode", tag)
                    .setParameter("fields", "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}]")
                    .executeUpdate();
        });

        UUID quotationId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            UUID customerId = toUUID(em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList().get(0));
            UUID salesRepId = toUUID(em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList().get(0));
            em.createNativeQuery("INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, " +
                    "customer_template_id, tax_rate, tax_amount, created_at, updated_at) " +
                    "VALUES (:id, :qn, :cid, :name, :srid, 'DRAFT', :tid, 0, 0, now(), now())")
                    .setParameter("id", quotationId).setParameter("qn", tag + "-" + quotationId.toString().substring(0, 8))
                    .setParameter("cid", customerId).setParameter("name", tag).setParameter("srid", salesRepId)
                    .setParameter("tid", templateId).executeUpdate();
            for (int i = 0; i < n; i++) {
                UUID lid = UUID.randomUUID();
                em.createNativeQuery("INSERT INTO quotation_line_item (id, quotation_id, template_id, " +
                        "product_part_no_snapshot, sort_order, created_at) VALUES (:id, :qid, :tid, :pn, :so, now())")
                        .setParameter("id", lid).setParameter("qid", quotationId).setParameter("tid", templateId)
                        .setParameter("pn", tag + "-P" + i).setParameter("so", i).executeUpdate();
            }
        });
        quotationIds.add(quotationId);
        return quotationId;
    }

    private List<String> captureLogsDuring(Runnable action) {
        List<String> captured = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                String msg = record.getMessage();
                if (msg == null) return;
                if (msg.contains("[perf] ensure-cardvalues-write") || msg.contains("[perf] ensure-excel-write")) {
                    captured.add(msg);
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
    @DisplayName("T-2(AC-5): ensure-cardvalues-write 的 updates(executeBatch 调用次数) 之和 == 批数,不是行数")
    void cardValuesWrite_updatesEqualsBatchCount_notRowCount() {
        UUID qid = buildQuotation(1845, "T260828T2CV");
        List<String> logs = captureLogsDuring(() -> {
            int filled = cardSnapshotService.ensureCardValues(qid);
            assertEquals(1845, filled, "前置数据非空:应补算 1845 行(不是空跑)");
        });

        List<String> matched = new ArrayList<>();
        long totalRows = 0, totalUpdates = 0;
        for (String line : logs) {
            Matcher m = CARD_WRITE.matcher(line);
            if (m.find() && qid.toString().equals(m.group(1))) {
                matched.add(line);
                totalRows += Long.parseLong(m.group(2));
                totalUpdates += Long.parseLong(m.group(4));
                System.out.println("[T-2 captured] " + line);
            }
        }

        assertFalse(matched.isEmpty(), "应至少捕获到 1 行 [perf] ensure-cardvalues-write 日志" +
                "(该埋点为 B-6 新增;若为 0 说明埋点未接上或本测试的捕获手法失效——这正是还原实验要验证的方向)");
        assertEquals(1845, totalRows, "各批次日志的 rows 之和应等于总行数 1845(非空验证:确认日志真的覆盖了全部行,不是只打了一部分)");

        int expectedBatches = (int) Math.ceil(1845.0 / 300.0); // chunk 默认 300 → 7
        System.out.printf("[T-2 AC-5] 捕获到 %d 条写日志,rows 之和=%d,updates 之和=%d,期望批数(默认chunk=300)=%d%n",
                matched.size(), totalRows, totalUpdates, expectedBatches);

        // 核心判据(AC-5):updates 之和应约等于批数(7),不是行数(1845)。
        assertTrue(totalUpdates <= expectedBatches * 2L,
                "updates(executeBatch 调用次数)之和应约等于批数(" + expectedBatches + "),不应接近行数 1845;" +
                "实测 totalUpdates=" + totalUpdates);
        assertTrue(totalUpdates < 1845,
                "updates 之和不应等于/接近行数 1845(逐行 UPDATE 的直接证据);实测=" + totalUpdates);
    }

    @Test
    @DisplayName("T-2(AC-5): ensure-excel-write 的 updates 之和 == 批数,不是行数")
    void excelWrite_updatesEqualsBatchCount_notRowCount() {
        UUID qid = buildQuotation(1845, "T260828T2EX");
        List<String> logs = captureLogsDuring(() -> {
            int filled = cardSnapshotService.ensureExcelValues(qid);
            assertEquals(1845, filled, "前置数据非空:应补算 1845 行 Excel 值(不是空跑)");
        });

        List<String> matched = new ArrayList<>();
        long totalRows = 0, totalUpdates = 0;
        for (String line : logs) {
            Matcher m = EXCEL_WRITE.matcher(line);
            if (m.find() && qid.toString().equals(m.group(1))) {
                matched.add(line);
                totalRows += Long.parseLong(m.group(2));
                totalUpdates += Long.parseLong(m.group(4));
                System.out.println("[T-2 captured] " + line);
            }
        }

        assertFalse(matched.isEmpty(), "应至少捕获到 1 行 [perf] ensure-excel-write 日志(B-6 新增)");
        assertEquals(1845, totalRows, "各批次日志的 rows 之和应等于总行数 1845(非空验证)");

        int expectedBatches = (int) Math.ceil(1845.0 / 300.0);
        System.out.printf("[T-2 AC-5] ensure-excel-write 捕获到 %d 条写日志,rows 之和=%d,updates 之和=%d,期望批数=%d%n",
                matched.size(), totalRows, totalUpdates, expectedBatches);

        assertTrue(totalUpdates <= expectedBatches * 2L,
                "updates 之和应约等于批数(" + expectedBatches + "),不应接近行数 1845;实测=" + totalUpdates);
        assertTrue(totalUpdates < 1845, "updates 之和不应接近行数 1845;实测=" + totalUpdates);
    }
}
