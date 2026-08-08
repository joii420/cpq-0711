package com.cpq.quotation.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-0806 · 守卫 1（FR-4）单元测试：{@link DriverBatchSafetyAuditor} 判定表（需求文档 §5.2）
 * 4 个分支全覆盖，含"解析失败 → 逐项"这条保守兜底分支。
 *
 * <p>自建测试数据（component + component_sql_view，通过原生 SQL 直插，绕开 sql_template 的
 * dry-run 校验——这条路径本就只读 {@code sql_template} 字符串，不需要真的能执行），测后自行清理。
 */
@QuarkusTest
class DriverBatchSafetyAuditorTest {

    @Inject
    DriverBatchSafetyAuditor auditor;
    @Inject
    EntityManager em;

    private final List<UUID> componentIdsToClean = new ArrayList<>();

    @AfterEach
    @Transactional
    void cleanup() {
        for (UUID id : componentIdsToClean) {
            // component_sql_view 有 ON DELETE CASCADE，删 component 即可级联清理。
            em.createNativeQuery("DELETE FROM component WHERE id = :id").setParameter("id", id).executeUpdate();
        }
        componentIdsToClean.clear();
    }

    /** 建一个 driver 组件 + 其 $view（sql_template 任意），返回 componentId。 */
    @Transactional
    UUID seedComponent(String sqlTemplate) {
        UUID componentId = UUID.randomUUID();
        String viewName = "bsa_test_view_" + componentId.toString().replace("-", "");
        em.createNativeQuery(
                "INSERT INTO component (id, name, code, fields, formulas, data_driver_path) " +
                "VALUES (:id, 'BSA测试组件', :code, '[]', '[]', :ddp)")
            .setParameter("id", componentId)
            .setParameter("code", "TEST-BSA-" + componentId)
            .setParameter("ddp", "$" + viewName)
            .executeUpdate();
        em.createNativeQuery(
                "INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template) " +
                "VALUES (:id, :cid, :vn, :tpl)")
            .setParameter("id", UUID.randomUUID())
            .setParameter("cid", componentId)
            .setParameter("vn", viewName)
            .setParameter("tpl", sqlTemplate)
            .executeUpdate();
        componentIdsToClean.add(componentId);
        return componentId;
    }

    // ---- 分支 1：GLOBAL —— 无维度占位符 ----

    @Test
    @Transactional
    void classify_noDimensionPlaceholder_isGlobal() {
        UUID componentId = seedComponent(
            "SELECT hf_part_no, unit_price FROM v_material_price WHERE customer_code = :customerCode");
        String ddp = (String) em.createNativeQuery("SELECT data_driver_path FROM component WHERE id = :id")
            .setParameter("id", componentId).getSingleResult();

        BatchSafetyLevel level = auditor.classifyComponent(componentId, ddp);

        assertEquals(BatchSafetyLevel.GLOBAL, level, "无 :priceBaseDate/:quotationId/:lineItemId -> GLOBAL");
    }

    // ---- 分支 2：PER_PRICE_BASE_DATE —— 仅含 :priceBaseDate ----

    @Test
    @Transactional
    void classify_onlyPriceBaseDate_isPerPriceBaseDate() {
        UUID componentId = seedComponent(
            "SELECT hf_part_no, f_material_element_price(:customerCode, :priceBaseDate) AS unit_price " +
            "FROM v_material_element WHERE customer_code = :customerCode");
        String ddp = (String) em.createNativeQuery("SELECT data_driver_path FROM component WHERE id = :id")
            .setParameter("id", componentId).getSingleResult();

        BatchSafetyLevel level = auditor.classifyComponent(componentId, ddp);

        assertEquals(BatchSafetyLevel.PER_PRICE_BASE_DATE, level, "含 :priceBaseDate 且不含 quotationId/lineItemId -> PER_PRICE_BASE_DATE");
    }

    // ---- 分支 3：PER_LINE_ITEM —— 含 :quotationId 或 :lineItemId ----

    @Test
    @Transactional
    void classify_containsQuotationId_isPerLineItem() {
        UUID componentId = seedComponent(
            "SELECT hf_part_no FROM v_process_mirror WHERE quotation_id = :quotationId");
        String ddp = (String) em.createNativeQuery("SELECT data_driver_path FROM component WHERE id = :id")
            .setParameter("id", componentId).getSingleResult();

        BatchSafetyLevel level = auditor.classifyComponent(componentId, ddp);

        assertEquals(BatchSafetyLevel.PER_LINE_ITEM, level, "含 :quotationId -> PER_LINE_ITEM 强制逐项");
    }

    @Test
    @Transactional
    void classify_containsLineItemIdAndPriceBaseDate_isPerLineItem() {
        // 同时含 :priceBaseDate 与 :lineItemId —— PER_LINE_ITEM 判定优先级最高（§5.2：最不安全者胜）。
        UUID componentId = seedComponent(
            "SELECT hf_part_no, f_material_element_price(:customerCode, :priceBaseDate) AS unit_price " +
            "FROM v_process_mirror WHERE line_item_id = :lineItemId");
        String ddp = (String) em.createNativeQuery("SELECT data_driver_path FROM component WHERE id = :id")
            .setParameter("id", componentId).getSingleResult();

        BatchSafetyLevel level = auditor.classifyComponent(componentId, ddp);

        assertEquals(BatchSafetyLevel.PER_LINE_ITEM, level,
            "同时含 :priceBaseDate 与 :lineItemId 时 PER_LINE_ITEM 优先，不能被判成 PER_PRICE_BASE_DATE");
    }

    // ---- 分支 4：解析失败 / 读不到视图 -> 保守兜底 PER_LINE_ITEM ----

    @Test
    void classify_blankDriverPath_fallsBackToPerLineItem() {
        BatchSafetyLevel level = auditor.classifyComponent(UUID.randomUUID(), "");
        assertEquals(BatchSafetyLevel.PER_LINE_ITEM, level, "data_driver_path 为空 -> 保守兜底 PER_LINE_ITEM");
    }

    @Test
    void classify_nullDriverPath_fallsBackToPerLineItem() {
        BatchSafetyLevel level = auditor.classifyComponent(UUID.randomUUID(), null);
        assertEquals(BatchSafetyLevel.PER_LINE_ITEM, level, "data_driver_path 为 null -> 保守兜底 PER_LINE_ITEM");
    }

    @Test
    void classify_nonViewFormPath_fallsBackToPerLineItem() {
        // 非 $view 形态（如历史 BNF 直连路径），extractSqlViewName 解析不出视图名。
        BatchSafetyLevel level = auditor.classifyComponent(UUID.randomUUID(), "mat_process.hf_part_no");
        assertEquals(BatchSafetyLevel.PER_LINE_ITEM, level, "非 $view 形态解析不出视图名 -> 保守兜底 PER_LINE_ITEM");
    }

    @Test
    @Transactional
    void classify_viewNotFound_fallsBackToPerLineItem() {
        // $view 形态但对应 component_sql_view 行不存在（未落库 / componentId 不匹配）。
        BatchSafetyLevel level = auditor.classifyComponent(UUID.randomUUID(), "$nonexistent_view_xyz");
        assertEquals(BatchSafetyLevel.PER_LINE_ITEM, level, "读不到视图 -> 保守兜底 PER_LINE_ITEM");
    }

    // ---- worstLevelForTemplate：模板整体取"最不安全"级别 ----

    @Test
    @Transactional
    void worstLevelForTemplate_takesMostUnsafeAcrossComponents() {
        UUID templateId = UUID.randomUUID();
        UUID globalComp = seedComponent("SELECT hf_part_no FROM v_x WHERE customer_code = :customerCode");
        UUID dateComp = seedComponent(
            "SELECT hf_part_no, f_material_element_price(:customerCode, :priceBaseDate) v FROM v_y");
        UUID lineItemComp = seedComponent("SELECT hf_part_no FROM v_z WHERE quotation_id = :quotationId");

        // template_component.template_id 有 FK REFERENCES template(id)，需要先建一条最小 template 行
        // （template_series_id 只是 NOT NULL，无 FK 约束，随便填一个 UUID 即可）。
        em.createNativeQuery(
                "INSERT INTO template (id, template_series_id, name) VALUES (:id, :sid, 'BSA测试模板')")
            .setParameter("id", templateId)
            .setParameter("sid", UUID.randomUUID())
            .executeUpdate();
        int sortOrder = 0;
        for (UUID cid : List.of(globalComp, dateComp, lineItemComp)) {
            em.createNativeQuery(
                    "INSERT INTO template_component (id, template_id, component_id, sort_order) " +
                    "VALUES (:id, :tid, :cid, :so)")
                .setParameter("id", UUID.randomUUID())
                .setParameter("tid", templateId)
                .setParameter("cid", cid)
                .setParameter("so", sortOrder++)
                .executeUpdate();
        }

        try {
            Map<String, BatchSafetyLevel> perComponent = auditor.classifyTemplateDriverComponents(templateId);
            assertEquals(3, perComponent.size(), "3 个 driver 组件都应被审计到");
            assertEquals(BatchSafetyLevel.GLOBAL, perComponent.get(globalComp.toString()));
            assertEquals(BatchSafetyLevel.PER_PRICE_BASE_DATE, perComponent.get(dateComp.toString()));
            assertEquals(BatchSafetyLevel.PER_LINE_ITEM, perComponent.get(lineItemComp.toString()));

            BatchSafetyLevel worst = auditor.worstLevelForTemplate(templateId);
            assertEquals(BatchSafetyLevel.PER_LINE_ITEM, worst, "模板整体取最不安全级别");
        } finally {
            // DELETE template 级联清 template_component（ON DELETE CASCADE），组件另在 @AfterEach 清理。
            em.createNativeQuery("DELETE FROM template WHERE id = :tid")
                .setParameter("tid", templateId).executeUpdate();
        }
    }

    @Test
    void worstLevelForTemplate_emptyTemplate_isGlobal() {
        assertEquals(BatchSafetyLevel.GLOBAL, auditor.worstLevelForTemplate(UUID.randomUUID()),
            "空模板（无 driver 组件）视为 GLOBAL");
        assertTrue(true);
    }
}
