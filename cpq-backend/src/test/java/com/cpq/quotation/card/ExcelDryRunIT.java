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
 * ExcelDryRunIT — 验证 dryRun(quotationId, columns, templateId) 方法：
 * 用传入临时 columns（不读模板/不落库）对报价单逐行试算，返回 {columns, rows}。
 *
 * <p>造数策略（参照 GetExcelViewCostingIT）：
 *   customer → "user" → quotation → line_item → componentData(subtotal=7, sort_order=0)
 *   不建模板（columns 全部由调用方传入），templateId 传 null。
 *
 * <p>断言：
 *   1. 传 CARD_FORMULA 列 formula="=[元素.小计]"，refs 指向 sortOrder=0 的 componentData.__subtotal__
 *      → rows[0].A compareTo 7 == 0
 *   2. 传错误公式 formula="=[不存在.小计]"（refs 空）→ 该列值为 null，不抛异常
 *
 * <p>@TestTransaction 结束后自动回滚，不污染 cpq_db。
 */
@QuarkusTest
class ExcelDryRunIT {

    @Inject
    ExcelViewService excelViewService;

    @Inject
    EntityManager em;

    // 稳定的 componentId，refs 里 tab=COMP_ID:0 → sortOrder=0 的 componentData
    private static final UUID COMP_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Test
    @TestTransaction
    void dryRun_card_formula_resolves_subtotal() {

        // ── 1. customer
        UUID customerId = UUID.randomUUID();
        String custCode = "DR" + customerId.toString().replace("-", "").substring(0, 8);
        em.createNativeQuery("""
                INSERT INTO customer (id, name, code, level, status, accumulated_amount, version, created_at, updated_at)
                VALUES (?1, 'IT-DR-Cust', ?2, 'STANDARD', 'ACTIVE', 0, 0, now(), now())
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
                VALUES (?1, ?2, 'IT DR User', ?3, 'hash', 'SALES_REP', 'ACTIVE', true, 0, now(), now())
                """)
          .setParameter(1, userId)
          .setParameter(2, "it-dr-user-" + uSuffix)
          .setParameter(3, "itdr_" + uSuffix + "@test.com")
          .executeUpdate();

        // ── 3. quotation（无 template_id 要求，line_item 的 template_id 任意）
        UUID quotationId = UUID.randomUUID();
        String qNum = "IT-DR-" + quotationId.toString().replace("-", "").substring(0, 8);
        em.createNativeQuery("""
                INSERT INTO quotation
                  (id, quotation_number, customer_id, name, sales_rep_id,
                   status, total_amount, original_amount, system_discount_rate, final_discount_rate,
                   is_manually_adjusted, tax_rate, tax_amount, bound_global_variables_snapshot,
                   created_at, updated_at)
                VALUES (?1, ?2, ?3, 'IT DR Quotation', ?4,
                   'DRAFT', 0, 0, 100, 100, false, 0, 0, '[]', now(), now())
                """)
          .setParameter(1, quotationId)
          .setParameter(2, qNum)
          .setParameter(3, customerId)
          .setParameter(4, userId)
          .executeUpdate();

        // ── 4. 随便建一个占位模板（line_item 需要 template_id 外键；dryRun 不读这张模板）
        UUID placeholderTemplateId = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO template
                  (id, template_series_id, name, status, formulas,
                   template_sql_views_snapshot, excel_view_config, created_at, updated_at)
                VALUES (?1, ?2, 'IT-DR-Placeholder-Tmpl', 'DRAFT', '[]', '{}',
                        CAST('[]' AS jsonb), now(), now())
                """)
          .setParameter(1, placeholderTemplateId)
          .setParameter(2, UUID.randomUUID())
          .executeUpdate();

        // ── 5. quotation_line_item
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
          .setParameter(3, placeholderTemplateId)
          .executeUpdate();

        // ── 6. quotation_line_component_data（subtotal=7, sort_order=0）
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

        // ── 8. 构造临时 columns：CARD_FORMULA 列 A = [元素.小计]，refs 指向 COMP_ID:0 的 __subtotal__
        List<Map<String, Object>> cols = List.of(
            Map.of(
                "col_key", "A",
                "label", "试算列",
                "source_type", "CARD_FORMULA",
                "formula", "=[元素.小计]",
                "refs", Map.of(
                    "元素.小计", Map.of(
                        "tab", COMP_ID.toString() + ":0",
                        "field", "__subtotal__"
                    )
                )
            )
        );

        // ── 9. dryRun（templateId 传 null，不依赖任何模板公式）
        Map<String, Object> out = excelViewService.dryRun(quotationId, cols, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outColumns = (List<Map<String, Object>>) out.get("columns");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outRows = (List<Map<String, Object>>) out.get("rows");

        assertNotNull(outColumns, "dryRun result.columns should not be null");
        assertNotNull(outRows, "dryRun result.rows should not be null");
        assertFalse(outRows.isEmpty(), "dryRun result.rows should have at least one row");

        // rows[0].A == 7（CARD_FORMULA __subtotal__ 求值）
        Object aVal = outRows.get(0).get("A");
        assertNotNull(aVal, "Column A (CARD_FORMULA) should have a computed value, got null");
        assertEquals(0,
                new BigDecimal("7").compareTo(new BigDecimal(aVal.toString())),
                "A should equal 7 (subtotal from componentData), got: " + aVal);

        // _lineItemId 已注入
        Object lineItemIdInRow = outRows.get(0).get("_lineItemId");
        assertNotNull(lineItemIdInRow, "_lineItemId should be injected into each row");
        assertEquals(lineItemId.toString(), lineItemIdInRow.toString());

        // ── 10. 错误公式（refs 空）→ 列值为 null，不抛异常
        List<Map<String, Object>> badCols = List.of(
            Map.of(
                "col_key", "B",
                "label", "错误列",
                "source_type", "CARD_FORMULA",
                "formula", "=[不存在.小计]",
                "refs", Map.of()   // 空 refs → CARD_FORMULA evaluator 找不到 ref → null
            )
        );
        Map<String, Object> badOut = excelViewService.dryRun(quotationId, badCols, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> badRows = (List<Map<String, Object>>) badOut.get("rows");
        assertNotNull(badRows, "bad formula dryRun should still return rows (no exception)");
        assertFalse(badRows.isEmpty(), "bad formula dryRun should have rows");
        // B 列值为 null 或错误占位符（不抛异常即可；CARD_FORMULA evaluator 可能返回 #ERROR[...] 字符串）
        Object bVal = badRows.get(0).get("B");
        String bStr = bVal == null ? null : bVal.toString();
        boolean bIsNullOrError = bStr == null || bStr.startsWith("#ERROR") || bStr.isBlank();
        assertTrue(bIsNullOrError,
                "Column B with bad formula should yield null or #ERROR placeholder (no 500), got: " + bVal);
    }
}
