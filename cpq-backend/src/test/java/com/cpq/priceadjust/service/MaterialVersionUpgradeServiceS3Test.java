package com.cpq.priceadjust.service;

import com.cpq.priceadjust.dto.ElementPrice;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0729 B0 · S3a（driver 行 → snapshot_rows）+ S3b（手动行 → row_data）单元测试。
 *
 * <p>自建 quotation + line_item + component + component_data 全套测试数据，测后清理。
 * 通过 {@code POST /admin/.../task0729-b0-upgrade-preview} 已用真实生产数据
 * （QT-20260726-0016，双 Ag 行）验证过一次（见交付报告），本测试补自动化回归覆盖。
 */
@QuarkusTest
class MaterialVersionUpgradeServiceS3Test {

    @Inject
    MaterialVersionUpgradeService svc;
    @Inject
    EntityManager em;

    private UUID quotationId, lineItemId, componentId, versionId;

    @AfterEach
    @Transactional
    void cleanup() {
        if (versionId != null) {
            em.createNativeQuery("DELETE FROM element_price_version_item WHERE version_id=:id").setParameter("id", versionId).executeUpdate();
            em.createNativeQuery("DELETE FROM element_price_version WHERE id=:id").setParameter("id", versionId).executeUpdate();
        }
        if (lineItemId != null) {
            em.createNativeQuery("DELETE FROM quotation_line_component_data WHERE line_item_id=:id").setParameter("id", lineItemId).executeUpdate();
            em.createNativeQuery("DELETE FROM quotation_line_item WHERE id=:id").setParameter("id", lineItemId).executeUpdate();
        }
        if (quotationId != null) {
            em.createNativeQuery("DELETE FROM quotation WHERE id=:id").setParameter("id", quotationId).executeUpdate();
        }
        if (componentId != null) {
            em.createNativeQuery("DELETE FROM component WHERE id=:id").setParameter("id", componentId).executeUpdate();
        }
    }

    /**
     * 核心场景：一个组件的 snapshot_rows 含两行同为 Ag（不同材质），一行 Cu，一行无价元素 Ni；
     * row_data 含一条 _origin=manual 的 Ag 行、一条非 manual 的驱动行快照（不应被 S3b 动）。
     * 断言：① 两条 Ag 行都改；② Cu 行改；③ Ni（不在版本明细里）不动；④ 非价格键（材质/项次）不变；
     * ⑤ manual 行按元素值命中改价；⑥ 非 manual 的 row_data 条目不受 S3b 影响；⑦ row_version 递增。
     */
    @Test
    @Transactional
    void upgradeComponentRows_multiRowSameElement_andManualRow() throws Exception {
        setUpFixture();

        Map<String, ElementPrice> versionPrices = new LinkedHashMap<>();
        versionPrices.put("Ag", new ElementPrice(new BigDecimal("999999.0000"), "EUR"));
        versionPrices.put("Cu", new ElementPrice(new BigDecimal("7777.0000"), "EUR"));
        // 故意不给 Ni 价格 —— 版本明细里没有它，验证"对不上就不动"

        // 直接调包内可见方法（同包）——注意：CDI @ApplicationScoped bean 注入的是客户端代理，
        // 代理只会正确委派非 private 方法；对 private 方法用反射 setAccessible 会绕过代理直接落到
        // 代理实例本身（其 @Inject 字段未初始化，em 为 null）。故 upgradeComponentRows /
        // RowUpdateOutcome 定为包内可见（非 private），与 S1/S2 的 loadVersionPrices /
        // locatePriceBearingComponents 同一模式，直接调用即可正确委派到真实 bean 实例。
        var pbc = new com.cpq.priceadjust.dto.UpgradeResult.PriceBearingComponent(
            componentId.toString(), "TEST-S3", "材料成本", "元素", "元素单价", "货币");

        MaterialVersionUpgradeService.RowUpdateOutcome outcome =
            svc.upgradeComponentRows(lineItemId, pbc, versionPrices);

        int rowsChanged = outcome.rowsChanged;
        boolean conflict = outcome.conflict;

        assertFalse(conflict, "首次写入不应冲突");
        // 2 条 Ag(snapshot_rows) + 1 条 Cu(snapshot_rows) + 1 条 manual Ag(row_data) = 4
        assertEquals(4, rowsChanged, "两条 Ag(snapshot_rows) + 一条 Cu(snapshot_rows) + 一条 manual Ag(row_data)");

        // 重新读库校验
        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT snapshot_rows, row_data, row_version FROM quotation_line_component_data " +
                "WHERE line_item_id=:lid AND component_id=:cid")
            .setParameter("lid", lineItemId).setParameter("cid", componentId)
            .getSingleResult();
        String snapshotRows = (String) row[0];
        String rowData = (String) row[1];
        long rowVersion = ((Number) row[2]).longValue();

        assertEquals(1L, rowVersion, "row_version 应从 0 递增到 1");

        com.fasterxml.jackson.databind.ObjectMapper M = new com.fasterxml.jackson.databind.ObjectMapper();
        var snapArr = M.readTree(snapshotRows);
        int agHit = 0;
        for (var r : snapArr) {
            var dr = r.path("driverRow");
            String el = dr.path("元素").asText("");
            if ("Ag".equals(el)) {
                assertEquals(999999.0, dr.path("元素单价").asDouble(), 0.01, "Ag 行单价应被改写");
                assertEquals("EUR", dr.path("货币").asText(), "货币键应同步写入（element_currency_field=货币）");
                agHit++;
            } else if ("Cu".equals(el)) {
                assertEquals(7777.0, dr.path("元素单价").asDouble(), 0.01, "Cu 行单价应被改写");
            } else if ("Ni".equals(el)) {
                assertEquals(50.0, dr.path("元素单价").asDouble(), 0.01,
                    "Ni 不在版本明细里，单价应保持手工原值 50，不被清 0 或误改");
            }
            // 非价格键必须逐字不变
            assertEquals("固定材质", dr.path("_材质").asText(), "非价格字段(材质)不应被 S3a 触碰");
        }
        assertEquals(2, agHit, "同一元素多行(BOM 闭包同元素不同材质) 必须逐行全改，不能只改第一条命中就停");

        var rowDataArr = M.readTree(rowData);
        boolean manualHit = false;
        for (var r : rowDataArr) {
            if ("manual".equals(r.path("_origin").asText(""))) {
                assertEquals("Ag", r.path("元素").asText());
                assertEquals(999999.0, r.path("元素单价").asDouble(), 0.01, "手动行按元素值命中应改价");
                manualHit = true;
            } else {
                // 非 manual 的 row_data 条目本次 S3b 不处理，必须保持原值
                assertEquals(118478.0, r.path("元素单价").asDouble(), 0.01,
                    "非 manual 的 row_data 条目不应被 S3b 触碰（那是 S4b 的范围，本次未实现）");
            }
        }
        assertTrue(manualHit, "manual 行必须被找到并处理");
    }

    @Transactional
    void setUpFixture() {
        componentId = UUID.randomUUID();
        // 🔒 fields 必须真的定义「元素」这个字段（S3a 靠 FormulaCalculator.resolveRowByFieldName
        // 按字段定义解析 driverRow，不是靠猜列名）。为聚焦测试"同元素多行"+"字段名解析"这两个行为，
        // 这里用最简单的 INPUT_TEXT 直接匹配（driverRow["元素"] 命中即返回，resolveRowByFieldName
        // 内部优先级第一档），不模拟真实生产 "_元素"(SQL 原始列) + BASIC_DATA default_source 那层间接
        // 寻址（那层已在 §S2 真实数据验证里跑通，此处只做单元级别的行为隔离测试）。
        String fieldsJson = "[{\"name\":\"元素\",\"field_type\":\"INPUT_TEXT\"}]";
        em.createNativeQuery(
                "INSERT INTO component (id, name, code, fields, formulas, element_code_field, " +
                "element_price_field, element_currency_field) " +
                "VALUES (:id, 'S3测试组件', :code, CAST(:fields AS jsonb), '[]', '元素', '元素单价', '货币')")
            .setParameter("id", componentId).setParameter("code", "TEST-S3-" + componentId)
            .setParameter("fields", fieldsJson)
            .executeUpdate();

        // customer_id/sales_rep_id 有 FK 约束，动态取库里已有的任意一条（不依赖硬编码 UUID，跨环境稳）
        UUID anyCustomerId = (UUID) em.createNativeQuery("SELECT id FROM customer LIMIT 1").getSingleResult();
        UUID anyUserId = (UUID) em.createNativeQuery("SELECT id FROM \"user\" LIMIT 1").getSingleResult();

        quotationId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation (id, quotation_number, customer_id, name, sales_rep_id, status, created_at, updated_at) " +
                "VALUES (:id, :no, :cust, 'S3测试单', :rep, 'DRAFT', now(), now())")
            .setParameter("id", quotationId).setParameter("no", "TEST-S3-" + quotationId)
            .setParameter("cust", anyCustomerId).setParameter("rep", anyUserId)
            .executeUpdate();

        lineItemId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation_line_item (id, quotation_id, subtotal, created_at) " +
                "VALUES (:id, :qid, 0, now())")
            .setParameter("id", lineItemId).setParameter("qid", quotationId)
            .executeUpdate();

        String snapshotRowsJson =
            "[" +
            "{\"driverRow\":{\"元素\":\"Ag\",\"_材质\":\"固定材质\",\"元素单价\":50}},"  +
            "{\"driverRow\":{\"元素\":\"Cu\",\"_材质\":\"固定材质\",\"元素单价\":10}}," +
            "{\"driverRow\":{\"元素\":\"Ni\",\"_材质\":\"固定材质\",\"元素单价\":50}}," +
            "{\"driverRow\":{\"元素\":\"Ag\",\"_材质\":\"固定材质\",\"元素单价\":50}}" +
            "]";
        String rowDataJson =
            "[" +
            "{\"元素\":\"Ag\",\"材质\":\"固定材质\",\"元素单价\":118478,\"_origin\":\"manual\"}," +
            "{\"元素\":\"Ag\",\"材质\":\"固定材质\",\"元素单价\":118478,\"row_index\":0}" +
            "]";
        em.createNativeQuery(
                "INSERT INTO quotation_line_component_data " +
                "(id, line_item_id, component_id, snapshot_rows, row_data, row_version, created_at) " +
                "VALUES (gen_random_uuid(), :lid, :cid, CAST(:sr AS jsonb), CAST(:rd AS jsonb), 0, now())")
            .setParameter("lid", lineItemId).setParameter("cid", componentId)
            .setParameter("sr", snapshotRowsJson).setParameter("rd", rowDataJson)
            .executeUpdate();
    }
}
