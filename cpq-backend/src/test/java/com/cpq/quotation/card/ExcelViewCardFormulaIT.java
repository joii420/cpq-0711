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
 * ExcelViewCardFormulaIT — 落库集成测试：验证 ExcelViewService.buildLineRowData
 * 能正确处理 CARD_FORMULA 列（调用 CardFormulaEvaluator 批量拓扑求值）。
 *
 * <p>造数策略：
 * 1. customer → "user" → quotation：3 张表，满足 FK 链。
 * 2. quotation_line_item.product_id = NULL（字段可空，FK 不触发）；template_id = NULL。
 * 3. Template 直接 native INSERT（template_series_id 无 FK 约束）；id 单独传给 buildLineRowData。
 * 4. quotation_line_component_data 只依赖 line_item_id（可空 component_id）。
 * 5. @TestTransaction 结束后自动回滚，不污染 cpq_db。
 *
 * <p>场景断言：
 *   COMP_ID:sortOrder=0, rowData=[{工序:电镀,加工费:3},{工序:酸洗,加工费:2}], subtotal=10
 *   CARD_FORMULA: A = [投料.小计] + SUM_OVER([加工] WHERE c0=='电镀', c1) = 10+3 = 13
 */
@QuarkusTest
class ExcelViewCardFormulaIT {

    @Inject
    ExcelViewService excelViewService;

    @Inject
    EntityManager em;

    private static final UUID COMP_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    @TestTransaction
    void card_formula_column_evaluates_subtotal_plus_sum_over() {

        // ── 1. customer
        UUID customerId = UUID.randomUUID();
        String custCode = "ITC" + customerId.toString().replace("-", "").substring(0, 7);
        em.createNativeQuery("""
                INSERT INTO customer (id, name, code, level, status, accumulated_amount, version, created_at, updated_at)
                VALUES (?1, 'IT-Cust-CardFormula', ?2, 'STANDARD', 'ACTIVE', 0, 0, now(), now())
                """)
          .setParameter(1, customerId)
          .setParameter(2, custCode)
          .executeUpdate();

        // ── 2. "user"（必填: username unique, full_name, email unique, password_hash, role）
        UUID userId = UUID.randomUUID();
        String uSuffix = userId.toString().replace("-", "").substring(0, 8);
        em.createNativeQuery("""
                INSERT INTO "user" (id, username, full_name, email, password_hash, role, status,
                  is_first_login, failed_login_attempts, created_at, updated_at)
                VALUES (?1, ?2, 'IT User CF', ?3, 'hash', 'SALES_REP', 'ACTIVE', true, 0, now(), now())
                """)
          .setParameter(1, userId)
          .setParameter(2, "it-cf-user-" + uSuffix)
          .setParameter(3, "itcf_" + uSuffix + "@test.com")
          .executeUpdate();

        // ── 3. quotation（quotation_number 必须 unique）
        UUID quotationId = UUID.randomUUID();
        String qNum = "IT-CF-" + quotationId.toString().replace("-", "").substring(0, 8);
        em.createNativeQuery("""
                INSERT INTO quotation
                  (id, quotation_number, customer_id, name, sales_rep_id,
                   status, total_amount, original_amount, system_discount_rate, final_discount_rate,
                   is_manually_adjusted, tax_rate, tax_amount, bound_global_variables_snapshot,
                   created_at, updated_at)
                VALUES (?1, ?2, ?3, 'IT CF Quotation', ?4,
                   'DRAFT', 0, 0, 100, 100, false, 0, 0, '[]', now(), now())
                """)
          .setParameter(1, quotationId)
          .setParameter(2, qNum)
          .setParameter(3, customerId)
          .setParameter(4, userId)
          .executeUpdate();

        // ── 4. template（template_series_id 无 FK 约束，直接随机 UUID）
        UUID templateId = UUID.randomUUID();
        String excelViewConfig = """
                [{"col_key":"A","source_type":"CARD_FORMULA",
                  "formula":"=[投料.小计] + SUM_OVER([加工] WHERE c0=='电镀', c1)",
                  "refs":{
                    "投料.小计":{"tab":"%s:0","field":"__subtotal__"},
                    "加工":{"tab":"%s:0","cols":{"c0":"工序","c1":"加工费"}}
                  }}]
                """.formatted(COMP_ID, COMP_ID).strip();

        em.createNativeQuery("""
                INSERT INTO template
                  (id, template_series_id, name, status, formulas,
                   template_sql_views_snapshot, excel_view_config, created_at, updated_at)
                VALUES (?1, ?2, 'IT-CardFormula-Tmpl', 'DRAFT', '[]', '{}',
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

        // ── 6. quotation_line_component_data（rowData 含中文字段）
        String rowData = "[{\"工序\":\"电镀\",\"加工费\":3},{\"工序\":\"酸洗\",\"加工费\":2}]";
        em.createNativeQuery("""
                INSERT INTO quotation_line_component_data
                  (id, line_item_id, component_id, tab_name, row_data, subtotal, sort_order, created_at)
                VALUES (?1, ?2, ?3, '加工', CAST(?4 AS jsonb), 10, 0, now())
                """)
          .setParameter(1, UUID.randomUUID())
          .setParameter(2, lineItemId)
          .setParameter(3, COMP_ID)
          .setParameter(4, rowData)
          .executeUpdate();

        // ── 7. flush 让 Panache list 能看到本事务内的 INSERT
        em.flush();

        // ── 8. 加载 QuotationLineItem
        QuotationLineItem li = QuotationLineItem.findById(lineItemId);
        assertNotNull(li, "QuotationLineItem should be persisted");

        // ── 9. 调用被测方法（显式传 templateId，不依赖 li.templateId=NULL）
        var out = excelViewService.buildLineRowData(li, templateId, null);

        // ── 10. 断言 A = 13
        assertNotNull(out, "buildLineRowData should return non-null map");
        Object aVal = out.get("A");
        assertNotNull(aVal, "Column A (CARD_FORMULA) should have a computed value, got null");
        assertEquals(0,
                new BigDecimal("13").compareTo(new BigDecimal(aVal.toString())),
                "A should equal 13 (subtotal=10 + sum_over_electroplating=3), got: " + aVal);
    }
}
