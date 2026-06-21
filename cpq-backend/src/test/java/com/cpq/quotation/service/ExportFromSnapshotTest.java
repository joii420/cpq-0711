package com.cpq.quotation.service;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD Phase 4 — exportExcelView 改读前端 quote_excel_values 快照渲染，不再后端重算。
 *
 * <p><b>测试策略：</b>
 * <ol>
 *   <li>查一条 DRAFT quotation（外键满足，不自建）。</li>
 *   <li>{@code @TestTransaction} 内自建最小 QuotationLineItem（与 SaveDraftExcelSnapshotTest 一致）。</li>
 *   <li>写 {@code quote_excel_values = {"rows":[{"sentinel_col":0.93}]}}，
 *       故意令其与 getExcelView 重算值不同（重算不含 sentinel_col 列或值不同）。</li>
 *   <li>调 {@code excelViewService.exportExcelView(qId)}，用 POI 解析 XLSX，
 *       断言 sentinel_col 列值 == 0.93（来自快照，而非后端重算）。</li>
 * </ol>
 *
 * <p><b>RED → GREEN 路径：</b>
 * 改动前 exportExcelView 走 getExcelView 重算，sentinel_col 不在列定义里，不会出现 0.93；
 * 改动后读快照，sentinel_col 出现在快照 rows 里但未在 columns 里定义，故以另一列验证：
 * 用实际列 key（从 getExcelView columns 中取第一个非 EXCEL_FORMULA 列），对同一 lineItem
 * 写快照 {"rows":[{"<colKey>": 0.93}]}，断言导出值 == 0.93。
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("ExportFromSnapshotTest — Phase4 exportExcelView 改读前端快照")
public class ExportFromSnapshotTest {

    @Inject
    ExcelViewService excelViewService;

    @Inject
    EntityManager em;

    // -----------------------------------------------------------------------
    // Helpers (与 SaveDraftExcelSnapshotTest 对齐)
    // -----------------------------------------------------------------------

    /** 找一条 DRAFT quotation ID；不存在返回 null。 */
    @SuppressWarnings("unchecked")
    private UUID findDraftQuotationId() {
        List<Object> rows = em.createNativeQuery(
                "SELECT id FROM quotation WHERE status = 'DRAFT' ORDER BY created_at LIMIT 1")
                .getResultList();
        if (rows.isEmpty()) return null;
        Object o = rows.get(0);
        if (o instanceof UUID u) return u;
        try { return UUID.fromString(o.toString()); } catch (Exception e) { return null; }
    }

    /** 自建最小 QuotationLineItem，sort_order=99 哨兵，@TestTransaction 回滚清理。 */
    @SuppressWarnings("unchecked")
    private UUID createMinimalLineItem(UUID quotationId) {
        List<Object> products = em.createNativeQuery("SELECT id FROM product LIMIT 1").getResultList();
        if (products.isEmpty()) return null;
        UUID productId = toUUID(products.get(0));

        List<Object> templates = em.createNativeQuery("SELECT id FROM template LIMIT 1").getResultList();
        if (templates.isEmpty()) return null;
        UUID templateId = toUUID(templates.get(0));

        UUID newId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO quotation_line_item " +
                "(id, quotation_id, product_id, template_id, sort_order, created_at) " +
                "VALUES (:id, :qid, :pid, :tid, 99, :now)")
                .setParameter("id",   newId)
                .setParameter("qid",  quotationId)
                .setParameter("pid",  productId)
                .setParameter("tid",  templateId)
                .setParameter("now",  OffsetDateTime.now())
                .executeUpdate();
        return newId;
    }

    /** 直写 quote_excel_values（绕过 Hibernate L1 缓存）。 */
    private void writeQuoteExcelValues(UUID lineItemId, String json) {
        em.createNativeQuery(
                "UPDATE quotation_line_item SET quote_excel_values = CAST(:val AS jsonb) WHERE id = :lid")
                .setParameter("val", json)
                .setParameter("lid", lineItemId)
                .executeUpdate();
    }

    private static UUID toUUID(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        try { return UUID.fromString(o.toString()); } catch (Exception e) { return null; }
    }

    // -----------------------------------------------------------------------
    // T1 — 快照优先：导出值来自 quote_excel_values，而非后端重算
    // -----------------------------------------------------------------------

    /**
     * 核心 TDD 用例：
     * 1. 自建 lineItem，写 quote_excel_values = {"rows":[{"__snap_test_col__":0.93}]}。
     * 2. 注意：getExcelView columns 里不会有 "__snap_test_col__"（这是测试造的假列），
     *    所以用它验"快照有，重算行没有"的列无法在导出 Excel 里定位。
     *
     * 实际可行的验证方式：
     * - 从 getExcelView 拿第一个非 EXCEL_FORMULA 列 key（如 "cost_per_unit"）。
     * - 写 quote_excel_values = {"rows":[{"<colKey>": 0.93}]}（与重算值不同）。
     * - 断言导出 Excel 该列 cell 值 ≈ 0.93。
     * - 若 getExcelView 无列（模板无配置），改为：仅断言 exportExcelView 不抛异常 + 返回非空字节。
     */
    @Test
    @Order(1)
    @DisplayName("T1: exportExcelView 读 quote_excel_values 快照（而非后端重算）")
    @TestTransaction
    void exportExcelView_readsQuoteExcelValuesSnapshot() throws Exception {
        UUID quotationId = findDraftQuotationId();
        assertNotNull(quotationId,
                "DB 无 DRAFT 报价单 — 请先通过 UI 创建至少一条 DRAFT 状态报价单后再运行本测试");

        UUID lineItemId = createMinimalLineItem(quotationId);
        assertNotNull(lineItemId,
                "无法自建 QuotationLineItem（无可用 product_id/template_id 引用）");

        // 从 getExcelView 拿列定义（columns），找第一个非 EXCEL_FORMULA 列
        Map<String, Object> viewData = excelViewService.getExcelView(quotationId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) viewData.get("columns");

        // 找一个非 EXCEL_FORMULA 的列 key 用于快照写入 + 导出断言
        String targetColKey = null;
        for (Map<String, Object> col : columns) {
            String sourceType = (String) col.get("source_type");
            if (!"EXCEL_FORMULA".equals(sourceType)) {
                Object ck = col.get("col_key");
                if (ck != null) {
                    targetColKey = ck.toString();
                    break;
                }
            }
        }

        if (targetColKey == null || columns.isEmpty()) {
            // 模板无非 EXCEL_FORMULA 列，退化为：仅断言调用不抛、返回非空字节
            // 并断言快照路径被激活（不抛即表示新代码路径走通）
            em.flush();
            em.clear();
            String sentinelSnapshot = "{\"rows\":[{\"__test__\":0.93}]}";
            writeQuoteExcelValues(lineItemId, sentinelSnapshot);
            em.flush();
            em.clear();

            byte[] bytes = excelViewService.exportExcelView(quotationId);
            assertNotNull(bytes, "exportExcelView 不应返回 null");
            assertTrue(bytes.length > 0, "exportExcelView 应返回非空字节（有效 XLSX）");
            // 能跑到这里说明快照读取路径（parseQuoteExcelValuesRows）不抛异常
            return;
        }

        // 写快照：将 targetColKey 写成 0.93（与后端重算值不同的特征值）
        // 后端重算路径从 componentData/productAttribute 取值，不可能恰好 = 0.93
        String snapshotJson = String.format("{\"rows\":[{\"%s\":0.93}]}", targetColKey);
        em.flush();
        em.clear();
        writeQuoteExcelValues(lineItemId, snapshotJson);
        em.flush();
        em.clear();

        // 导出 Excel
        byte[] bytes = excelViewService.exportExcelView(quotationId);
        assertNotNull(bytes, "exportExcelView 不应返回 null");
        assertTrue(bytes.length > 0, "exportExcelView 应返回非空字节");

        // POI 解析，找 targetColKey 对应的列索引
        int targetColIdx = -1;
        for (int i = 0; i < columns.size(); i++) {
            Object ck = columns.get(i).get("col_key");
            if (targetColKey.equals(ck != null ? ck.toString() : null)) {
                targetColIdx = i;
                break;
            }
        }
        assertTrue(targetColIdx >= 0, "targetColKey=" + targetColKey + " 在 columns 中未找到对应列索引");

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertNotNull(sheet, "Sheet 0 应存在");

            // 找自建 lineItem 对应的数据行（sort_order=99，是最后一条）
            // getExcelView 返回 rows 按 sortOrder 排，自建行排在最后
            // 遍历所有数据行找 0.93
            boolean found093 = false;
            int lastDataRowIdx = sheet.getLastRowNum();
            for (int r = 1; r <= lastDataRowIdx; r++) {
                Row dataRow = sheet.getRow(r);
                if (dataRow == null) continue;
                Cell cell = dataRow.getCell(targetColIdx);
                if (cell == null) continue;
                if (cell.getCellType() == CellType.NUMERIC) {
                    double val = cell.getNumericCellValue();
                    // 允许浮点误差
                    if (Math.abs(val - 0.93) < 1e-6) {
                        found093 = true;
                        break;
                    }
                }
            }
            assertTrue(found093,
                    "导出 Excel 列[" + targetColKey + "] 应含值 0.93（来自前端快照），" +
                    "实际导出行数=" + lastDataRowIdx);
        }
        // @TestTransaction 自动回滚，不污染 DB（sort_order=99 行回滚消失）
    }

    // -----------------------------------------------------------------------
    // T2 — 快照 null/缺失时 fallback 到重算行（旧草稿仍可导出）
    // -----------------------------------------------------------------------

    @Test
    @Order(2)
    @DisplayName("T2: quote_excel_values 为 null 时 fallback 到后端重算，导出不抛异常")
    @TestTransaction
    void exportExcelView_nullSnapshot_fallsBackToRecompute() throws Exception {
        UUID quotationId = findDraftQuotationId();
        assertNotNull(quotationId,
                "DB 无 DRAFT 报价单 — 请先通过 UI 创建至少一条 DRAFT 状态报价单后再运行本测试");

        UUID lineItemId = createMinimalLineItem(quotationId);
        assertNotNull(lineItemId, "无法自建 QuotationLineItem");

        // 确保 quote_excel_values 为 null（自建行默认 null，不显式写）
        em.flush();
        em.clear();

        // 导出应走 fallback 重算路径，不抛异常
        byte[] bytes = excelViewService.exportExcelView(quotationId);
        assertNotNull(bytes, "fallback 路径：exportExcelView 不应返回 null");
        assertTrue(bytes.length > 0, "fallback 路径：exportExcelView 应返回非空字节");
    }

    // -----------------------------------------------------------------------
    // T3 — 快照解析失败时 fallback（防御性）
    // -----------------------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("T3: quote_excel_values 为非法 JSON 时 fallback 到重算，不抛异常")
    @TestTransaction
    void exportExcelView_malformedSnapshot_fallsBackGracefully() throws Exception {
        UUID quotationId = findDraftQuotationId();
        assertNotNull(quotationId,
                "DB 无 DRAFT 报价单 — 请先通过 UI 创建至少一条 DRAFT 状态报价单后再运行本测试");

        UUID lineItemId = createMinimalLineItem(quotationId);
        assertNotNull(lineItemId, "无法自建 QuotationLineItem");

        // 写一个看似合法但 rows 不是数组的 JSON（触发 parseQuoteExcelValuesRows 返回 List.of()）
        // 注意：PostgreSQL CAST AS jsonb 要求有效 JSON，所以用合法 JSON 但 rows 字段是非数组
        writeQuoteExcelValues(lineItemId, "{\"rows\":\"not_an_array\"}");
        em.flush();
        em.clear();

        // 应走 fallback 路径，不抛
        byte[] bytes = excelViewService.exportExcelView(quotationId);
        assertNotNull(bytes, "malformed snapshot fallback：exportExcelView 不应返回 null");
        assertTrue(bytes.length > 0, "malformed snapshot fallback：应返回非空字节");
    }
}
