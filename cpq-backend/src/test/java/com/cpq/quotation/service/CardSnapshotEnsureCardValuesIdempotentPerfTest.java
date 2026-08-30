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
 * repair-260829（卡片值算早了骨架值锁死）· T-07（AC-7，IS NULL 自愈判据收益保留）。
 *
 * <p><b>验什么</b>：B-1（落库前产物自检）+ B-1b（物化进行中守卫）两层新增判据，不应改变
 * {@code ensureCardValues} 既有的"只补算缺失行"（{@code quote_card_values IS NULL}）语义——
 * 对一批已就绪的行连续调用两次，第二次识别出的"需要补算"行数必须是 0，不能退化成每次全量重算
 * （{@code EnsureResult.computed} 复用的仍是 {@code missing.size()} 口径，退化的话第二次也会
 * 返回等于行数的非零值）。
 *
 * <p>行数选 60（而非 AC-7 原文举例的 1845）：本条只需要"非平凡的多行样本 + 能测出耗时差异"，
 * 不需要复刻大单量场景（那是 {@code task-260825} 性能专项的范围），控制在秒级即可。
 */
@QuarkusTest
class CardSnapshotEnsureCardValuesIdempotentPerfTest {

    private static final String TAG = "T260829T7";
    private static final int N = 60;

    @Inject EntityManager em;
    @Inject CardSnapshotService cardSnapshotService;

    private UUID componentId, templateId, quotationId;

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

    private void buildFixture() {
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
                    .setParameter("tpl", "SELECT '" + TAG + "-P1'::text AS hf_part_no, '真实值'::text AS \"名称\"")
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
        QuarkusTransaction.requiringNew().run(() -> {
            UUID customerId = toUUID(em.createNativeQuery("SELECT id FROM customer LIMIT 1").getResultList().get(0));
            UUID salesRepId = toUUID(em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getResultList().get(0));
            em.createNativeQuery("INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, " +
                    "customer_template_id, tax_rate, tax_amount, created_at, updated_at) " +
                    "VALUES (:id, :qn, :cid, :name, :srid, 'DRAFT', :tid, 0, 0, now(), now())")
                    .setParameter("id", quotationId).setParameter("qn", TAG + "-" + quotationId.toString().substring(0, 8))
                    .setParameter("cid", customerId).setParameter("name", TAG).setParameter("srid", salesRepId)
                    .setParameter("tid", templateId).executeUpdate();
            for (int i = 0; i < N; i++) {
                UUID lid = UUID.randomUUID();
                em.createNativeQuery("INSERT INTO quotation_line_item (id, quotation_id, template_id, " +
                        "product_part_no_snapshot, sort_order, created_at) VALUES (:id, :qid, :tid, :pn, :so, now())")
                        .setParameter("id", lid).setParameter("qid", quotationId).setParameter("tid", templateId)
                        .setParameter("pn", TAG + "-P" + i).setParameter("so", i).executeUpdate();
                em.createNativeQuery("INSERT INTO quotation_line_component_data (id, line_item_id, component_id, tab_name, " +
                        "row_data, snapshot_rows) VALUES (:id, :lid, :cid, :tab, CAST(:rd AS jsonb), CAST(:sr AS jsonb))")
                        .setParameter("id", UUID.randomUUID()).setParameter("lid", lid).setParameter("cid", componentId)
                        .setParameter("tab", TAG + "页签")
                        .setParameter("rd", "[{\"row_index\":0,\"名称\":\"真实值\"}]")
                        .setParameter("sr", "[{\"driverRow\":{\"hf_part_no\":\"" + TAG + "-P1\",\"名称\":\"真实值\"}," +
                                "\"basicDataValues\":{}}]")
                        .executeUpdate();
            }
        });
    }

    private int countMissing() {
        Number n = (Number) em.createNativeQuery(
                "SELECT count(*) FROM quotation_line_item WHERE quotation_id = :q AND quote_card_values IS NULL")
                .setParameter("q", quotationId).getSingleResult();
        return n.intValue();
    }

    @Test
    @DisplayName("T-07(AC-7): 连续两次ensureCardValues,第二次识别的需补算行数==0,且明显快于首次")
    void secondCall_identifiesZeroMissing_andIsFasterThanFirst() {
        buildFixture();
        assertEquals(N, countMissing(), "前置数据非空:开跑前应有" + N + "行缺失(未算过)");

        long t0 = System.nanoTime();
        int filled1 = cardSnapshotService.ensureCardValues(quotationId);
        long elapsed1 = System.nanoTime() - t0;
        assertEquals(N, filled1, "首次ensureCardValues应补算全部" + N + "行(非空验证,不是空跑)");
        assertEquals(0, countMissing(), "首次算完后应无缺失行");

        long t1 = System.nanoTime();
        int filled2 = cardSnapshotService.ensureCardValues(quotationId);
        long elapsed2 = System.nanoTime() - t1;
        assertEquals(0, filled2,
                "第二次ensureCardValues识别出的需要补算行数必须==0(IS NULL判据未被B-1/B-1b破坏," +
                "不应退化成每次全量重算)");

        double ms1 = elapsed1 / 1_000_000.0, ms2 = elapsed2 / 1_000_000.0;
        System.out.printf("[T-07] 首次=%.1fms(补算%d行) 二次=%.1fms(补算%d行,应为0)%n",
                ms1, filled1, ms2, filled2);
        assertTrue(ms2 < ms1,
                String.format("第二次耗时(%.1fms)应明显低于首次(%.1fms)——若接近甚至更慢,说明B-1/B-1b" +
                        "的额外判据在'无需补算'的快速路径上引入了不该有的开销", ms2, ms1));
    }
}
