package com.cpq.quotation.card;

import com.cpq.quotation.service.ExcelViewService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GetExcelViewCostingIT — 验证 getExcelView(quotationId, templateIdOverride) 双参重载：
 * 传核价模板时按核价模板列算 CARD_FORMULA；传 null 时按报价模板列（向后兼容）。
 *
 * <p>造数策略与 ExcelViewCardFormulaIT 相同：
 *   customer → "user" → quotation → line_item(template_id = 报价模板)
 *   另建一个核价模板 (COSTING)，含 CARD_FORMULA 列 A = [元素.小计]
 *   报价模板含 FIXED_VALUE 列 Q，不含 A。
 *
 * <p>断言：
 *   getExcelView(quotationId, costingTemplateId).rows[0].A == 7（subtotal=7）
 *   getExcelView(quotationId, null).columns 含 Q，不含 A
 *
 * <p>@TestTransaction 结束后自动回滚，不污染 cpq_db。
 */
@QuarkusTest
class GetExcelViewCostingIT {

    @Inject
    ExcelViewService excelViewService;

    @Inject
    EntityManager em;

    // 稳定的 componentId，用于 CARD_FORMULA refs 里的 tab 路径
    private static final UUID COMP_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Test
    @TestTransaction
    void getExcelView_with_costingTemplateId_uses_costing_columns() {

        // ── 1. customer
        UUID customerId = UUID.randomUUID();
        String custCode = "GEV" + customerId.toString().replace("-", "").substring(0, 7);
        em.createNativeQuery("""
                INSERT INTO customer (id, name, code, level, status, accumulated_amount, version, created_at, updated_at)
                VALUES (?1, 'IT-GEV-Cust', ?2, 'STANDARD', 'ACTIVE', 0, 0, now(), now())
                """)
          .setParameter(1, customerId)
          .setParameter(2, custCode)
          .executeUpdate();

        // ── 2. "user"
        UUID userId = UUID.randomUUID();
        String uSuffix = userId.toString().replace("-", "").substring(0, 8);
        em.createNativeQuery("""
                INSERT INTO "user" (id, username, full_name, email, password_hash, role, status,
                  is_first_login, failed_login_attempts, created_at, updated_at)
                VALUES (?1, ?2, 'IT GEV User', ?3, 'hash', 'SALES_REP', 'ACTIVE', true, 0, now(), now())
                """)
          .setParameter(1, userId)
          .setParameter(2, "it-gev-user-" + uSuffix)
          .setParameter(3, "itgev_" + uSuffix + "@test.com")
          .executeUpdate();

        // ── 3. quotation
        UUID quotationId = UUID.randomUUID();
        String qNum = "IT-GEV-" + quotationId.toString().replace("-", "").substring(0, 8);
        em.createNativeQuery("""
                INSERT INTO quotation
                  (id, quotation_number, customer_id, name, sales_rep_id,
                   status, total_amount, original_amount, system_discount_rate, final_discount_rate,
                   is_manually_adjusted, tax_rate, tax_amount, bound_global_variables_snapshot,
                   created_at, updated_at)
                VALUES (?1, ?2, ?3, 'IT GEV Quotation', ?4,
                   'DRAFT', 0, 0, 100, 100, false, 0, 0, '[]', now(), now())
                """)
          .setParameter(1, quotationId)
          .setParameter(2, qNum)
          .setParameter(3, customerId)
          .setParameter(4, userId)
          .executeUpdate();

        // ── 4a. 报价模板（QUOTATION type）：excel_view_config 含 FIXED_VALUE 列 Q，不含 A
        UUID quotingTemplateId = UUID.randomUUID();
        String quotingConfig = """
                [{"col_key":"Q","label":"报价列","source_type":"FIXED_VALUE","fixed_value":"报价固定值"}]
                """.strip();
        em.createNativeQuery("""
                INSERT INTO template
                  (id, template_series_id, name, status, formulas,
                   template_sql_views_snapshot, excel_view_config, created_at, updated_at)
                VALUES (?1, ?2, 'IT-GEV-Quoting-Tmpl', 'DRAFT', '[]', '{}',
                        CAST(?3 AS jsonb), now(), now())
                """)
          .setParameter(1, quotingTemplateId)
          .setParameter(2, UUID.randomUUID())
          .setParameter(3, quotingConfig)
          .executeUpdate();

        // ── 4b. 核价模板（COSTING type）：excel_view_config 含 CARD_FORMULA 列 A = [元素.小计]
        UUID costingTemplateId = UUID.randomUUID();
        // ref: tab=COMP_ID:0, field=__subtotal__（CardFormulaEvaluator 会从 componentDataList 的 subtotal 取值）
        String costingConfig = """
                [{"col_key":"A","label":"核价列","source_type":"CARD_FORMULA",
                  "formula":"=[元素.小计]",
                  "refs":{
                    "元素.小计":{"tab":"%s:0","field":"__subtotal__"}
                  }}]
                """.formatted(COMP_ID).strip();
        em.createNativeQuery("""
                INSERT INTO template
                  (id, template_series_id, name, status, formulas,
                   template_sql_views_snapshot, excel_view_config, created_at, updated_at)
                VALUES (?1, ?2, 'IT-GEV-Costing-Tmpl', 'DRAFT', '[]', '{}',
                        CAST(?3 AS jsonb), now(), now())
                """)
          .setParameter(1, costingTemplateId)
          .setParameter(2, UUID.randomUUID())
          .setParameter(3, costingConfig)
          .executeUpdate();

        // ── 5. quotation_line_item（template_id = 报价模板）
        UUID lineItemId = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO quotation_line_item
                  (id, quotation_id, product_id, template_id, product_attribute_values,
                   subtotal, system_discount_rate, final_discount_rate, sort_order,
                   part_version_locked, composite_type, created_at)
                VALUES (?1, ?2, NULL, ?3, '{}', 0, 100, 100, 0, 2000, 'SIMPLE', now())
                """)
          .setParameter(1, lineItemId)
          .setParameter(2, quotationId)
          .setParameter(3, quotingTemplateId)
          .executeUpdate();

        // ── 6. quotation_line_component_data（subtotal=7，供 CARD_FORMULA __subtotal__ 引用）
        em.createNativeQuery("""
                INSERT INTO quotation_line_component_data
                  (id, line_item_id, component_id, tab_name, row_data, subtotal, sort_order, created_at)
                VALUES (?1, ?2, ?3, '元素', CAST('[{"qty":1}]' AS jsonb), 7, 0, now())
                """)
          .setParameter(1, UUID.randomUUID())
          .setParameter(2, lineItemId)
          .setParameter(3, COMP_ID)
          .executeUpdate();

        // ── 7. flush 让 Panache list 能看到本事务内的 INSERT
        em.flush();

        // ── 8. 用核价模板覆盖：columns 应含 A（CARD_FORMULA），rows[0].A == 7
        Map<String, Object> costingView = excelViewService.getExcelView(quotationId, costingTemplateId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> costingColumns = (List<Map<String, Object>>) costingView.get("columns");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> costingRows = (List<Map<String, Object>>) costingView.get("rows");

        assertNotNull(costingColumns, "costingView.columns should not be null");
        assertNotNull(costingRows, "costingView.rows should not be null");
        assertFalse(costingRows.isEmpty(), "costingView.rows should have at least one row");

        // columns 含 col_key=A，source_type=CARD_FORMULA
        boolean hasColA = costingColumns.stream()
                .anyMatch(c -> "A".equals(c.get("col_key")) && "CARD_FORMULA".equals(c.get("source_type")));
        assertTrue(hasColA, "Costing view columns should contain col_key=A with source_type=CARD_FORMULA");

        // rows[0].A == 7
        Object aVal = costingRows.get(0).get("A");
        assertNotNull(aVal, "Column A (CARD_FORMULA) in costing view should have a computed value, got null");
        assertEquals(0,
                new BigDecimal("7").compareTo(new BigDecimal(aVal.toString())),
                "A should equal 7 (subtotal from componentData), got: " + aVal);

        // ── 9. 不传 templateId（null → 使用 lineItems[0].templateId = 报价模板）
        Map<String, Object> quoteView = excelViewService.getExcelView(quotationId, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> quoteColumns = (List<Map<String, Object>>) quoteView.get("columns");

        assertNotNull(quoteColumns, "quoteView.columns should not be null");

        // columns 含 col_key=Q（报价模板列）
        boolean hasColQ = quoteColumns.stream()
                .anyMatch(c -> "Q".equals(c.get("col_key")));
        assertTrue(hasColQ, "Quote view columns should contain col_key=Q (from quoting template)");

        // columns 不含 col_key=A（核价模板列不应出现在报价视图中）
        boolean hasColAInQuote = quoteColumns.stream()
                .anyMatch(c -> "A".equals(c.get("col_key")));
        assertFalse(hasColAInQuote, "Quote view columns should NOT contain col_key=A (costing template column)");
    }
}
