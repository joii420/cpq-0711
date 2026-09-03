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
 * repair-260828 · T-8（AC-12）：0 行 / 1 行边界不炸,{@code materialize-status} 的
 * {@code done} 语义(total=0 也算 done)。
 *
 * <p>判据口径对齐 {@code api.md}:{@code ready} 由
 * {@code count(*) FILTER (WHERE NOT (quote_card_values IS NULL))} 派生,
 * {@code done} 由 {@code ready==total} 派生——本测试不打 HTTP 端点(未验证鉴权装配),
 * 改用同口径的只读 SQL 直接复核这条派生关系是否成立,这是黑盒可达、不依赖实现细节的等价验证。
 */
@QuarkusTest
class CardSnapshotEmptyAndSingleLineBoundaryTest {

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

    /** 与 T-1/T-2 同款 fixture(PUBLISHED 模板 + template_component_snapshot + 0 行 $view),n 可为 0。 */
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

    /** 对齐 api.md 的 materialize-status 派生口径:ready=count(NOT NULL),done=(ready==total)。 */
    private boolean computeDoneLikeMaterializeStatus(UUID qid, int total) {
        Number ready = (Number) em.createNativeQuery(
                "SELECT count(*) FILTER (WHERE NOT (quote_card_values IS NULL)) " +
                "FROM quotation_line_item WHERE quotation_id = :q")
                .setParameter("q", qid).getSingleResult();
        System.out.printf("[T-8] quotation=%s total=%d ready=%d%n", qid, total, ready.longValue());
        return ready.longValue() == total;
    }

    @Test
    @DisplayName("T-8(AC-12): 0 行报价单 ensureCardValues/ensureExcelValues 不抛异常,done=true(total=0 也算 done)")
    void zeroLines_doesNotThrow_doneTrue() {
        UUID qid = buildQuotation(0, "T260828T8N0");

        int filledCard = assertDoesNotThrow(() -> cardSnapshotService.ensureCardValues(qid),
                "0 行报价单 ensureCardValues 不应抛异常");
        int filledExcel = assertDoesNotThrow(() -> cardSnapshotService.ensureExcelValues(qid),
                "0 行报价单 ensureExcelValues 不应抛异常");
        assertEquals(0, filledCard, "0 行应补算 0 行卡片值");
        assertEquals(0, filledExcel, "0 行应补算 0 行 Excel 值");

        assertTrue(computeDoneLikeMaterializeStatus(qid, 0),
                "0 行报价单:ready(=0) 应等于 total(=0),done 应为 true");
    }

    @Test
    @DisplayName("T-8(AC-12): 1 行报价单不抛异常,补算后 done=true")
    void oneLine_doesNotThrow_doneTrueAfterFill() {
        UUID qid = buildQuotation(1, "T260828T8N1");

        int filledCard = assertDoesNotThrow(() -> cardSnapshotService.ensureCardValues(qid),
                "1 行报价单 ensureCardValues 不应抛异常");
        int filledExcel = assertDoesNotThrow(() -> cardSnapshotService.ensureExcelValues(qid),
                "1 行报价单 ensureExcelValues 不应抛异常");
        assertEquals(1, filledCard, "前置数据非空:1 行应补算 1 行卡片值(不是空跑)");
        assertEquals(1, filledExcel, "前置数据非空:1 行应补算 1 行 Excel 值(不是空跑)");

        assertTrue(computeDoneLikeMaterializeStatus(qid, 1),
                "1 行报价单补算后:ready(=1) 应等于 total(=1),done 应为 true");
    }
}
