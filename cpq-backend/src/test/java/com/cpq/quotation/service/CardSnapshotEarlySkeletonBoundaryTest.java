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
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260829（卡片值算早了骨架值锁死）· T-06 / T-10 / T-11 / T-12（AC-6、AC-10 三边界）。
 *
 * <p><b>与 {@code CreateQuotationEmptyGuardBoundaryTest} 的关系</b>：那个文件验的是<b>另一条并发线
 * ①步空转守卫</b>（{@code checkMaterializeOutcome}，挂在 {@code CreateQuotationMaterializer}），
 * 判据是"明细行>0 且 driver组件>0 且 comp_data==0"；本文件验的是<b>本线③步产物自检</b>
 * （{@link CardSnapshotService#isEarlySkeletonRender}，挂在 {@code ensureCardValuesDetailed}
 * 落库前），判据是"所有页签 baseRows 合计==0 且该行某 tab 内组件 snapshot_rows 非空"。两把守卫
 * 独立存在，调用入口也不同——本文件统一直调 {@code cardSnapshotService.ensureCardValues(qid)}，
 * 不经过 {@code CreateQuotationMaterializer.materialize}。
 *
 * <p><b>T-11 fixture 的一个关键坑（已避开）</b>：{@code PublishedTemplateReader.verifyConsistentWithJsonb}
 * 把"{@code template_component_snapshot} 行数==0 且 {@code components_snapshot} jsonb 长度==0"
 * 判定为"未冻结"（D17），会抛 {@link com.cpq.template.exception.TemplateNotFrozenException}——
 * 也就是说模板不存在"合法冻结但组件数为0"这种状态。因此 T-11 的"0 个 driver 组件"不能造成
 * "0 个组件"，只能造成"有 1 个组件，但它不是 driver"（{@code data_driver_path} 为 null，
 * 无 SQL 视图、该行也没有对应 comp_data）——这才是业务上"0 driver组件"真正对应的可冻结状态。
 */
@QuarkusTest
class CardSnapshotEarlySkeletonBoundaryTest {

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

    /** 一个组件规格:是否有 driver(SQL 视图) + SQL 模板(仅 hasDriver=true 时用) + componentType。 */
    private record CompSpec(String tag, boolean hasDriver, String sql, String componentType) {}

    /** 通用 fixture:按 specs 建 N 个组件的模板 + lineItemCount 行 quotation_line_item。 */
    private UUID buildQuotation(String tag, List<CompSpec> specs, int lineItemCount) {
        List<UUID> compIds = new ArrayList<>();
        for (CompSpec spec : specs) {
            UUID cid = UUID.randomUUID();
            compIds.add(cid);
            componentIds.add(cid);
            String ddp = spec.hasDriver() ? "$" + spec.tag().toLowerCase() + "_view" : null;
            QuarkusTransaction.requiringNew().run(() -> {
                em.createNativeQuery("INSERT INTO component (id, name, code, fields, formulas, data_driver_path, created_at, updated_at) " +
                        "VALUES (:id, :name, :code, CAST(:fields AS jsonb), CAST('[]' AS jsonb), :ddp, now(), now())")
                        .setParameter("id", cid).setParameter("name", spec.tag() + "-组件")
                        .setParameter("code", spec.tag() + "-" + cid.toString().substring(0, 8))
                        .setParameter("fields", "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}]")
                        .setParameter("ddp", ddp).executeUpdate();
                if (spec.hasDriver()) {
                    em.createNativeQuery("INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template, declared_columns, created_at, updated_at) " +
                            "VALUES (:id, :cid, :vn, :tpl, '[]', now(), now())")
                            .setParameter("id", UUID.randomUUID()).setParameter("cid", cid)
                            .setParameter("vn", spec.tag().toLowerCase() + "_view")
                            .setParameter("tpl", spec.sql())
                            .executeUpdate();
                }
            });
        }

        UUID templateId = UUID.randomUUID();
        templateIds.add(templateId);
        StringBuilder snapArr = new StringBuilder("[");
        for (int i = 0; i < specs.size(); i++) {
            CompSpec spec = specs.get(i);
            UUID cid = compIds.get(i);
            if (i > 0) snapArr.append(',');
            snapArr.append("{\"id\":\"").append(UUID.randomUUID()).append("\",\"componentId\":\"").append(cid)
                    .append("\",\"componentName\":\"").append(spec.tag()).append("-组件\",\"componentCode\":\"")
                    .append(spec.tag()).append("\",\"componentType\":\"").append(spec.componentType())
                    .append("\",\"tabName\":\"").append(spec.tag()).append("页签\",\"sortOrder\":").append(i)
                    .append(",\"fields\":[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}],\"formulas\":[]");
            if (spec.hasDriver()) snapArr.append(",\"data_driver_path\":\"$").append(spec.tag().toLowerCase()).append("_view\"");
            snapArr.append('}');
        }
        snapArr.append(']');
        String snapshot = snapArr.toString();

        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO template (id, template_series_id, name, template_kind, status, components_snapshot, created_at, updated_at) " +
                    "VALUES (:id, :tsid, :name, 'QUOTATION', 'PUBLISHED', CAST(:snap AS jsonb), now(), now())")
                    .setParameter("id", templateId).setParameter("tsid", UUID.randomUUID())
                    .setParameter("name", tag + "-模板").setParameter("snap", snapshot).executeUpdate();
            for (int i = 0; i < specs.size(); i++) {
                CompSpec spec = specs.get(i);
                UUID cid = compIds.get(i);
                UUID tcId = UUID.randomUUID();
                em.createNativeQuery("INSERT INTO template_component (id, template_id, component_id, sort_order, tab_name, created_at) " +
                        "VALUES (:id, :tid, :cid, :so, :tab, now())")
                        .setParameter("id", tcId).setParameter("tid", templateId).setParameter("cid", cid)
                        .setParameter("so", i).setParameter("tab", spec.tag() + "页签").executeUpdate();
                em.createNativeQuery("INSERT INTO template_component_snapshot (template_id,template_component_id,component_id," +
                        "sort_order,tab_name,component_name,component_code,component_type,fields,formulas," +
                        "element_code_field,element_price_field,element_currency_field) " +
                        "VALUES (:templateId,:templateComponentId,:componentId,:so,:tabName," +
                        ":componentName,:componentCode,:ctype,CAST(:fields AS jsonb),CAST('[]' AS jsonb),NULL,NULL,NULL)")
                        .setParameter("templateId", templateId).setParameter("templateComponentId", tcId)
                        .setParameter("componentId", cid).setParameter("so", i).setParameter("tabName", spec.tag() + "页签")
                        .setParameter("componentName", spec.tag() + "-组件").setParameter("componentCode", spec.tag())
                        .setParameter("ctype", spec.componentType())
                        .setParameter("fields", "[{\"name\":\"名称\",\"field_type\":\"INPUT_TEXT\"}]")
                        .executeUpdate();
            }
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
            for (int i = 0; i < lineItemCount; i++) {
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

    private String readCardValues(UUID qid) {
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery(
                "SELECT quote_card_values::text FROM quotation_line_item WHERE quotation_id = :q ORDER BY sort_order LIMIT 1")
                .setParameter("q", qid).getResultList();
        return rows.isEmpty() ? null : (String) rows.get(0);
    }

    /** 与既有同族测试(CardSnapshotBatchWriteRoundTripTest 等)同款:JUL Handler 拦截 WARNING 及以上级别。 */
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

    private void assertNoEarlySkeletonWarn(List<String> warnings, String caseTag) {
        for (String w : warnings) {
            assertFalse(w.contains("cardvalues-early-skeleton"),
                    "[" + caseTag + "] 本场景是合法边界,不应触发B-1'算早了'告警,实际=" + w);
        }
    }

    @Test
    @DisplayName("T-06(AC-6): 组件视图合法返0行(WHERE FALSE)、无预置comp_data → 正常落库、无WARN")
    void t06_legitEmptyView_writesNormallyNoWarn() {
        UUID qid = buildQuotation("T06B", List.of(
                new CompSpec("T06B", true, "SELECT 'T06B-P0'::text AS hf_part_no, 'x'::text AS \"名称\" WHERE FALSE", "NORMAL")
        ), 1);

        List<String> warnings = captureWarnDuring(() -> {
            int filled = cardSnapshotService.ensureCardValues(qid);
            assertEquals(1, filled, "前置数据非空:应补算1行(不是空跑)");
        });

        String cv = readCardValues(qid);
        assertNotNull(cv, "合法空结果(视图WHERE FALSE)仍应正常落库,quote_card_values不应为NULL");
        assertFalse(cv.contains("__cardValueFailed"), "不应落失败哨兵,实际=" + cv);
        assertNoEarlySkeletonWarn(warnings, "T06");
        System.out.printf("[T06] quote_card_values=%s warnings=%s%n", cv, warnings);
    }

    @Test
    @DisplayName("T-10(AC-10-①): 明细行0条 → 不抛异常、不误报、返回0")
    void t10_zeroLineItems_noThrowNoWarn() {
        UUID qid = buildQuotation("T10B", List.of(
                new CompSpec("T10B", true, "SELECT 'T10B-P0'::text AS hf_part_no, 'x'::text AS \"名称\" WHERE FALSE", "NORMAL")
        ), 0);

        List<String> warnings = captureWarnDuring(() -> {
            int filled = assertDoesNotThrow(() -> cardSnapshotService.ensureCardValues(qid),
                    "0行明细ensureCardValues不应抛异常");
            assertEquals(0, filled, "0行应补算0行");
        });
        assertNoEarlySkeletonWarn(warnings, "T10");
    }

    @Test
    @DisplayName("T-11(AC-10-②): 模板挂0个driver组件(1个无driver的普通组件) → 不误报,正常落库")
    void t11_zeroDriverComponents_noFalsePositive() {
        // 该组件 hasDriver=false: data_driver_path=null,无SQL视图,该行也不会有comp_data——
        // 业务上"0 driver组件"的可冻结形态(模板冻结校验不允许"0组件"这种全空状态,见类注释)。
        UUID qid = buildQuotation("T11B", List.of(
                new CompSpec("T11B", false, null, "NORMAL")
        ), 1);

        List<String> warnings = captureWarnDuring(() -> {
            int filled = assertDoesNotThrow(() -> cardSnapshotService.ensureCardValues(qid),
                    "0个driver组件的模板ensureCardValues不应抛异常");
            assertEquals(1, filled, "前置数据非空:1行应补算1行(不是空跑,只是tab内容为空)");
        });

        String cv = readCardValues(qid);
        assertNotNull(cv, "0 driver组件属于合法态,应正常落库");
        assertNoEarlySkeletonWarn(warnings, "T11");
        System.out.printf("[T11] quote_card_values=%s warnings=%s%n", cv, warnings);
    }

    @Test
    @DisplayName("T-12(AC-10-③ SUBTOTAL陷阱): NORMAL驱动tab有真实数据 + SUBTOTAL tab恒0行 → 不误报")
    void t12_subtotalTabAlwaysZero_doesNotFalsePositive() {
        UUID qid = buildQuotation("T12B", List.of(
                new CompSpec("T12BN", true, "SELECT 'T12BN-P0'::text AS hf_part_no, '真实值'::text AS \"名称\"", "NORMAL"),
                new CompSpec("T12BS", false, null, "SUBTOTAL")
        ), 1);

        // 关键修正(2026-08-29 首跑实测抓到的构造缺陷):ensureCardValues 的 baseRows 数据源是
        // quotation_line_component_data.snapshot_rows(黑盒实证见 CardSnapshotSkeletonSelfHealTest
        // 类注释引用的 CardValuesRecomputeStableTest 已知事实),不会在 ensureCardValues 内部重新
        // 查询组件挂的 SQL 视图——buildQuotation 只建了组件与视图,不预插 comp_data,所以 NORMAL
        // 组件的 tab 在没有预插 comp_data 时同样会渲染出 baseRows=[](首次实测确认:cv 里两个 tab
        // 都是空数组,断言"应包含真实值"失败)。T-12 要验的是"一个tab非空+一个tab(SUBTOTAL)恒空"
        // 这个混合态不误报,所以必须显式为 T12BN 预插非空 snapshot_rows,不能依赖它自动查视图产生。
        UUID normalComponentId = toUUID(em.createNativeQuery(
                "SELECT id FROM component WHERE code LIKE 'T12BN-%' LIMIT 1").getResultList().get(0));
        UUID lineId = toUUID(em.createNativeQuery(
                "SELECT id FROM quotation_line_item WHERE quotation_id = :q LIMIT 1")
                .setParameter("q", qid).getResultList().get(0));
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO quotation_line_component_data (id, line_item_id, component_id, tab_name, " +
                    "row_data, snapshot_rows) VALUES (:id, :lid, :cid, 'T12BN页签', CAST(:rd AS jsonb), CAST(:sr AS jsonb))")
                    .setParameter("id", UUID.randomUUID()).setParameter("lid", lineId).setParameter("cid", normalComponentId)
                    .setParameter("rd", "[{\"row_index\":0,\"名称\":\"真实值\"}]")
                    .setParameter("sr", "[{\"driverRow\":{\"hf_part_no\":\"T12BN-P0\",\"名称\":\"真实值\"}," +
                            "\"basicDataValues\":{}}]")
                    .executeUpdate();
        });

        List<String> warnings = captureWarnDuring(() -> {
            int filled = cardSnapshotService.ensureCardValues(qid);
            assertEquals(1, filled, "前置数据非空:应补算1行");
        });

        String cv = readCardValues(qid);
        assertNotNull(cv, "NORMAL tab有真实数据,不应被误判为算早了");
        assertTrue(cv.contains("真实值"), "应包含NORMAL驱动tab的真实内容,实际=" + cv);
        assertNoEarlySkeletonWarn(warnings,
                "T12(判据必须是'所有页签baseRows合计为0',SUBTOTAL单独为0不应触发误报)");
        System.out.printf("[T12] quote_card_values=%s warnings=%s%n", cv, warnings);
    }
}
