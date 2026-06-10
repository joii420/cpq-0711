package com.cpq.quotation.card;

import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.ExcelViewService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExcelViewTabJoinFormulaIT — 端到端集成测试：验证 ExcelViewService.buildLineRowData
 * 能正确求值 TAB_JOIN_FORMULA 列（行键全外连对齐 + 逐行乘积自动求和）。
 *
 * <p>场景：两个组件
 *   - COMP_A（投料, sort_order=0）row_data = [{物料编码:M1, 金额:100}, {物料编码:M2, 金额:60}]
 *   - COMP_B（加工, sort_order=1）row_data = [{物料编码:M1, 工时:4},  {物料编码:M3, 工时:5}]
 *
 * <p>表达式 [投料.金额] * [加工.工时] 按物料编码全外连对齐后逐行乘积求和：
 *   M1: 100 × 4 = 400
 *   M2:  60 × 0 =   0  （加工无此物料 → 0）
 *   M3:   0 × 5 =   0  （投料无此物料 → 0）
 *   Σ = 400
 *
 * <p>@TestTransaction 结束后自动回滚，不污染 cpq_db。
 */
@QuarkusTest
class ExcelViewTabJoinFormulaIT {

    @Inject
    ExcelViewService excelViewService;

    @Inject
    EntityManager em;

    /** 投料组件 UUID（固定，用于 tabKey = COMP_A_ID:0） */
    private static final UUID COMP_A_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    /** 加工组件 UUID（固定，用于 tabKey = COMP_B_ID:1） */
    private static final UUID COMP_B_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    @TestTransaction
    void tab_join_formula_row_key_align_sum_equals_400() {

        // ── 1. customer
        UUID customerId = UUID.randomUUID();
        String custCode = "TJF" + customerId.toString().replace("-", "").substring(0, 7);
        em.createNativeQuery("""
                INSERT INTO customer (id, name, code, level, status, accumulated_amount, version, created_at, updated_at)
                VALUES (?1, 'IT-TabJoin-Cust', ?2, 'STANDARD', 'ACTIVE', 0, 0, now(), now())
                """)
          .setParameter(1, customerId)
          .setParameter(2, custCode)
          .executeUpdate();

        // ── 2. "user"（username / email 须唯一）
        UUID userId = UUID.randomUUID();
        String uSuffix = userId.toString().replace("-", "").substring(0, 8);
        em.createNativeQuery("""
                INSERT INTO "user" (id, username, full_name, email, password_hash, role, status,
                  is_first_login, failed_login_attempts, created_at, updated_at)
                VALUES (?1, ?2, 'IT TabJoin User', ?3, 'hash', 'SALES_REP', 'ACTIVE', true, 0, now(), now())
                """)
          .setParameter(1, userId)
          .setParameter(2, "it-tj-user-" + uSuffix)
          .setParameter(3, "ittj_" + uSuffix + "@test.com")
          .executeUpdate();

        // ── 3. quotation（quotation_number 须唯一）
        UUID quotationId = UUID.randomUUID();
        String qNum = "IT-TJ-" + quotationId.toString().replace("-", "").substring(0, 8);
        em.createNativeQuery("""
                INSERT INTO quotation
                  (id, quotation_number, customer_id, name, sales_rep_id,
                   status, total_amount, original_amount, system_discount_rate, final_discount_rate,
                   is_manually_adjusted, tax_rate, tax_amount, bound_global_variables_snapshot,
                   created_at, updated_at)
                VALUES (?1, ?2, ?3, 'IT TabJoin Quotation', ?4,
                   'DRAFT', 0, 0, 100, 100, false, 0, 0, '[]', now(), now())
                """)
          .setParameter(1, quotationId)
          .setParameter(2, qNum)
          .setParameter(3, customerId)
          .setParameter(4, userId)
          .executeUpdate();

        // ── 4. template：excel_view_config 包含一列 TAB_JOIN_FORMULA
        //   tabs[0].tabKey = COMP_A_ID:0（投料, sort_order=0）
        //   tabs[1].tabKey = COMP_B_ID:1（加工, sort_order=1）
        //   rowKeyFields = ["物料编码"]，在两侧都有此字段，用于全外连对齐
        UUID templateId = UUID.randomUUID();
        String excelViewConfig = """
                [{"col_key":"A","source_type":"TAB_JOIN_FORMULA",
                  "expression":"[投料.金额] * [加工.工时]",
                  "tabs":[
                    {"alias":"投料","tabKey":"%s:0","rowKeyFields":["物料编码"]},
                    {"alias":"加工","tabKey":"%s:1","rowKeyFields":["物料编码"]}
                  ]}]
                """.formatted(COMP_A_ID.toString(), COMP_B_ID.toString()).strip();

        em.createNativeQuery("""
                INSERT INTO template
                  (id, template_series_id, name, status, formulas,
                   template_sql_views_snapshot, excel_view_config, created_at, updated_at)
                VALUES (?1, ?2, 'IT-TabJoin-Tmpl', 'DRAFT', '[]', '{}',
                        CAST(?3 AS jsonb), now(), now())
                """)
          .setParameter(1, templateId)
          .setParameter(2, UUID.randomUUID())
          .setParameter(3, excelViewConfig)
          .executeUpdate();

        // ── 5. quotation_line_item（product_id=NULL 可空，template_id=NULL 可空，避免 FK 错误）
        UUID lineItemId = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO quotation_line_item
                  (id, quotation_id, product_id, template_id, product_attribute_values,
                   subtotal, system_discount_rate, final_discount_rate, sort_order,
                   part_version_locked, composite_type, created_at)
                VALUES (?1, ?2, NULL, NULL, '{}', 0, 100, 100, 0, 2000, 'SIMPLE', now())
                """)
          .setParameter(1, lineItemId)
          .setParameter(2, quotationId)
          .executeUpdate();

        // ── 6a. COMP_A 投料 component_data（sort_order=0）
        //   row_data: M1→金额100, M2→金额60
        String rowDataA = "[{\"物料编码\":\"M1\",\"金额\":100},{\"物料编码\":\"M2\",\"金额\":60}]";
        em.createNativeQuery("""
                INSERT INTO quotation_line_component_data
                  (id, line_item_id, component_id, tab_name, row_data, subtotal, sort_order, created_at)
                VALUES (?1, ?2, ?3, '投料', CAST(?4 AS jsonb), 0, 0, now())
                """)
          .setParameter(1, UUID.randomUUID())
          .setParameter(2, lineItemId)
          .setParameter(3, COMP_A_ID)
          .setParameter(4, rowDataA)
          .executeUpdate();

        // ── 6b. COMP_B 加工 component_data（sort_order=1）
        //   row_data: M1→工时4, M3→工时5
        String rowDataB = "[{\"物料编码\":\"M1\",\"工时\":4},{\"物料编码\":\"M3\",\"工时\":5}]";
        em.createNativeQuery("""
                INSERT INTO quotation_line_component_data
                  (id, line_item_id, component_id, tab_name, row_data, subtotal, sort_order, created_at)
                VALUES (?1, ?2, ?3, '加工', CAST(?4 AS jsonb), 0, 1, now())
                """)
          .setParameter(1, UUID.randomUUID())
          .setParameter(2, lineItemId)
          .setParameter(3, COMP_B_ID)
          .setParameter(4, rowDataB)
          .executeUpdate();

        // ── 7. flush 让 Panache list 能看到本事务内的 INSERT
        em.flush();

        // ── 8. 加载 QuotationLineItem
        QuotationLineItem li = QuotationLineItem.findById(lineItemId);
        assertNotNull(li, "QuotationLineItem should be persisted in this transaction");

        // ── 9. 调用被测方法（显式传 templateId，li.templateId=NULL 不影响）
        //   求值链：buildLineRowData → buildRowData → TAB_JOIN_FORMULA 分支
        //          → evaluateColumn(col, new CardDataProvider(componentDataList))
        //          → provider.rowsOf("COMP_A_ID:0") / rowsOf("COMP_B_ID:1")
        //          → alignByRowKey(["物料编码"], tabRows)
        //          → evalExpression("[投料.金额] * [加工.工时]", alignedRows, {})
        //          → hasBareDetail=true → 逐行乘积求和
        //   期望：M1:100×4=400 + M2:60×0=0 + M3:0×5=0 = 400
        var out = excelViewService.buildLineRowData(li, templateId, null);

        // ── 10. 断言 A = 400
        assertNotNull(out, "buildLineRowData should return non-null map");
        Object aVal = out.get("A");
        assertNotNull(aVal,
                "Column A (TAB_JOIN_FORMULA) should have a computed value, got null. "
                + "Possible cause: tabKey mismatch between excel_view_config and component_data "
                + "(check COMP_A_ID:0 / COMP_B_ID:1 vs CardDataProvider.keyOf)");
        assertEquals(0,
                new BigDecimal("400").compareTo(new BigDecimal(aVal.toString())),
                "A should equal 400 (M1:100×4 + M2:60×0 + M3:0×5 = 400), got: " + aVal);
    }
}
