package com.cpq.quotation.card;

import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.service.ExcelViewService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260911 端到端集成测试：<b>核价 Excel 树</b>的取数链路
 * （{@code buildLineTreeRows → parseEffectiveRows → CardEffectiveRows.parse → CardDataProvider
 * → TabJoinPlanEvaluator}），也就是真正写进 {@code costing_excel_values} 的那条路径。
 *
 * <p>⚠️ 与既有 {@code ExcelViewTabJoinFormulaIT} 的区别（那条**覆盖不到本缺陷**）：
 * <ul>
 *   <li>那条不传 {@code cardValuesJson} ⇒ 走 {@code effectiveRows == null} 分支
 *       （{@code ComponentDataEffectiveRows}，早就是双键），本条传卡片值快照 ⇒ 走
 *       {@code CardEffectiveRows.parse}（本次修复的那条）</li>
 *   <li>那条的 {@code tabKey} 是 {@code componentId:sortOrder}，本条是<b>裸 componentId</b>
 *       —— 与现网 Excel 列配置一致（R1）</li>
 *   <li>本条的 {@code subtotal}/{@code subtotalByColumn} 是 <b>JSON 字符串</b>
 *       —— 与现网 {@code costing_card_values} 一致（R2）</li>
 * </ul>
 *
 * <p>夹具逐字对齐 {@code cpq_db_0910} 的 {@code QT-20260911-0010 / S0001 / 核价通用1}：
 * 页签 subtotal = 489985 / 5438667.5 / 5.8 / 5438673.3，tab 内无 {@code sortOrder}，
 * BOM 树 spine 3 个节点。期望每个节点行的四列都等于对应页签总计（列表达式是 {@code [页签(总计)]}，
 * {@code filterByNodeId} 不按节点重算 subtotal —— 见 AC「已知语义」）。
 *
 * <p>{@code @TestTransaction} 结束后自动回滚，不污染 cpq_db_test。
 */
@QuarkusTest
class CostingExcelTreeTabKeyIT {

    @Inject
    ExcelViewService excelViewService;

    @Inject
    EntityManager em;

    private static final UUID BOM   = UUID.fromString("a2d0dc3a-9506-4b7e-8d5e-8e512433a201");
    private static final UUID ELEM  = UUID.fromString("1e41ecb6-b663-42a1-b8da-122961699d26");
    private static final UUID PROC  = UUID.fromString("36eb1efb-8e35-45bd-8dc7-199521a759b9");
    private static final UUID TOTAL = UUID.fromString("203382c5-38fd-4a81-978e-610afc8da3bd");

    /** 现网 costing_card_values 形态：tab 无 sortOrder，subtotal/subtotalByColumn 为字符串。 */
    private static final String COSTING_CARD_VALUES = """
        {"tabs":[
          {"componentId":"%s","tabName":"BOM","componentType":"NORMAL","subtotal":"5438667.5",
           "subtotalByColumn":{"物料成本":"5438667.5"},
           "editRows":[],"formulaResults":[],
           "baseRows":[
             {"__nodeId":"300001","__parentId":null,"__lvl":1,"__hfPartNo":"300001",
              "__parentNo":null,"__bomVersion":"1","driverRow":{"生产料号":"300001"},"basicDataValues":{}},
             {"__nodeId":"300001/300012","__parentId":"300001","__lvl":2,"__hfPartNo":"300012",
              "__parentNo":"300001","__bomVersion":"1","driverRow":{"生产料号":"300012"},"basicDataValues":{}},
             {"__nodeId":"300001/300013","__parentId":"300001","__lvl":2,"__hfPartNo":"300013",
              "__parentNo":"300001","__bomVersion":"1","driverRow":{"生产料号":"300013"},"basicDataValues":{}}],
           "resolvedRows":[
             {"__nodeId":"300001","生产料号":"300001","物料成本":5438667.5},
             {"__nodeId":"300001/300012","生产料号":"300012","物料成本":0},
             {"__nodeId":"300001/300013","生产料号":"300013","物料成本":0}]},
          {"componentId":"%s","tabName":"材质元素","componentType":"NORMAL","subtotal":"489985",
           "subtotalByColumn":{"元素成本":"489985"},
           "editRows":[],"formulaResults":[],"baseRows":[],
           "resolvedRows":[{"元素代码":"Ag","元素成本":489985}]},
          {"componentId":"%s","tabName":"加工费","componentType":"NORMAL","subtotal":"5.8",
           "subtotalByColumn":{"加工费":"5.8"},
           "editRows":[],"formulaResults":[],"baseRows":[],
           "resolvedRows":[{"工序编号":"OP10","加工费":5.8}]},
          {"componentId":"%s","tabName":"核价小计1","componentType":"SUBTOTAL","subtotal":"5438673.3",
           "editRows":[],"formulaResults":[],"baseRows":[],"resolvedRows":[]}]}
        """.formatted(BOM, ELEM, PROC, TOTAL).strip();

    /** 现网 excel_columns 形态：四列 TAB_JOIN_FORMULA，tabKey 均为**裸 componentId**。 */
    private static final String EXCEL_COLUMNS = """
        [{"col_key":"col_1","title":"元素小计","hidden":false,"source_type":"TAB_JOIN_FORMULA",
          "expression":"[材质元素(总计)]",
          "tabs":[{"alias":"材质元素","tabKey":"%s","rowKeyFields":["生产料号","料号","元素代码"]}]},
         {"col_key":"col_2","title":"物料小计","hidden":false,"source_type":"TAB_JOIN_FORMULA",
          "expression":"[BOM(总计)]",
          "tabs":[{"alias":"BOM","tabKey":"%s","rowKeyFields":["生产料号","料号"]}]},
         {"col_key":"col_3","title":"加工费","hidden":false,"source_type":"TAB_JOIN_FORMULA",
          "expression":"[加工费(总计)]",
          "tabs":[{"alias":"加工费","tabKey":"%s","rowKeyFields":["生产料号","工序编号"]}]},
         {"col_key":"col_4","title":"单价","hidden":false,"source_type":"TAB_JOIN_FORMULA",
          "expression":"[核价小计1(总计)]",
          "tabs":[{"alias":"核价小计1","tabKey":"%s","rowKeyFields":[]}]}]
        """.formatted(ELEM, BOM, PROC, TOTAL).strip();

    private static final String COMPONENTS_SNAPSHOT = """
        [{"componentId":"%s","sortOrder":0,"componentName":"BOM","fields":[]},
         {"componentId":"%s","sortOrder":1,"componentName":"材质元素","fields":[]},
         {"componentId":"%s","sortOrder":2,"componentName":"加工费","fields":[]},
         {"componentId":"%s","sortOrder":3,"componentName":"核价小计1","fields":[]}]
        """.formatted(BOM, ELEM, PROC, TOTAL).strip();

    @Test
    @TestTransaction
    @DisplayName("AC-1 端到端：核价 Excel 树逐节点四列 = 489985 / 5438667.5 / 5.8 / 5438673.3")
    void costingTreeRows_tabJoinColumns_yieldRealSubtotals() {
        UUID customerId = UUID.randomUUID();
        String custCode = "CET" + customerId.toString().replace("-", "").substring(0, 7);
        em.createNativeQuery("""
                INSERT INTO customer (id, name, code, level, status, accumulated_amount, version, created_at, updated_at)
                VALUES (?1, 'IT-CostingExcelTree-Cust', ?2, 'STANDARD', 'ACTIVE', 0, 0, now(), now())
                """)
          .setParameter(1, customerId).setParameter(2, custCode).executeUpdate();

        UUID userId = UUID.randomUUID();
        String uSuffix = userId.toString().replace("-", "").substring(0, 8);
        em.createNativeQuery("""
                INSERT INTO "user" (id, username, full_name, email, password_hash, role, status,
                  is_first_login, failed_login_attempts, created_at, updated_at)
                VALUES (?1, ?2, 'IT CET User', ?3, 'hash', 'SALES_REP', 'ACTIVE', true, 0, now(), now())
                """)
          .setParameter(1, userId)
          .setParameter(2, "it-cet-user-" + uSuffix)
          .setParameter(3, "itcet_" + uSuffix + "@test.com").executeUpdate();

        UUID quotationId = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO quotation
                  (id, quotation_number, customer_id, name, sales_rep_id,
                   status, total_amount, original_amount, system_discount_rate, final_discount_rate,
                   is_manually_adjusted, tax_rate, tax_amount, bound_global_variables_snapshot,
                   created_at, updated_at)
                VALUES (?1, ?2, ?3, 'IT CET Quotation', ?4,
                   'DRAFT', 0, 0, 100, 100, false, 0, 0, '[]', now(), now())
                """)
          .setParameter(1, quotationId)
          .setParameter(2, "IT-CET-" + quotationId.toString().replace("-", "").substring(0, 8))
          .setParameter(3, customerId).setParameter(4, userId).executeUpdate();

        // EXCEL 组件承载列定义（现网形态：excel_view_config 对象 → excel_component_id → component.excel_columns）
        UUID excelCompId = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO component (id, name, code, component_type, excel_columns, created_at, updated_at)
                VALUES (?1, 'IT-CET-Excel', ?2, 'EXCEL', CAST(?3 AS jsonb), now(), now())
                """)
          .setParameter(1, excelCompId)
          .setParameter(2, "COMP-IT-CET-" + excelCompId.toString().replace("-", "").substring(0, 8))
          .setParameter(3, EXCEL_COLUMNS).executeUpdate();

        UUID templateId = UUID.randomUUID();
        String excelViewConfig =
            "{\"version\":2,\"column_overrides\":[],\"excel_component_id\":\"" + excelCompId + "\"}";
        em.createNativeQuery("""
                INSERT INTO template
                  (id, template_series_id, name, status, formulas,
                   template_sql_views_snapshot, components_snapshot, excel_view_config, created_at, updated_at)
                VALUES (?1, ?2, 'IT-CET-Tmpl', 'DRAFT', '[]', '{}',
                        CAST(?3 AS jsonb), CAST(?4 AS jsonb), now(), now())
                """)
          .setParameter(1, templateId).setParameter(2, UUID.randomUUID())
          .setParameter(3, COMPONENTS_SNAPSHOT).setParameter(4, excelViewConfig).executeUpdate();

        UUID lineItemId = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO quotation_line_item
                  (id, quotation_id, product_id, template_id, product_attribute_values,
                   subtotal, system_discount_rate, final_discount_rate, sort_order,
                   part_version_locked, composite_type, created_at)
                VALUES (?1, ?2, NULL, NULL, '{}', 0, 100, 100, 0, 2000, 'SIMPLE', now())
                """)
          .setParameter(1, lineItemId).setParameter(2, quotationId).executeUpdate();

        em.flush();

        QuotationLineItem li = QuotationLineItem.findById(lineItemId);
        assertNotNull(li, "QuotationLineItem 应已在本事务内持久化");

        // 被测：核价侧真实入口（CardSnapshotService.buildExcelValues(..., costingTree=true) 调的就是它）
        List<Map<String, Object>> rows =
            excelViewService.buildLineTreeRows(li, templateId, customerId, COSTING_CARD_VALUES);

        assertEquals(3, rows.size(), "BOM 树 spine 3 个节点 → 3 行（rows 为空说明链路更早就断了）");

        Map<String, String> expected = Map.of(
            "col_1", "489985", "col_2", "5438667.5", "col_3", "5.8", "col_4", "5438673.3");

        for (Map<String, Object> row : rows) {
            String node = String.valueOf(row.get("__hfPartNo"));
            for (var e : expected.entrySet()) {
                Object v = row.get(e.getKey());
                assertNotNull(v, "节点 " + node + " 的 " + e.getKey() + " 不应为 null");
                assertEquals(0, new BigDecimal(e.getValue()).compareTo(new BigDecimal(v.toString())),
                    "节点 " + node + " 的 " + e.getKey() + " 期望 " + e.getValue() + " 实得 " + v
                        + " —— 0 说明 R1（裸 tabKey 未登记）或 R2（字符串 subtotal 读成 0）之一未修");
            }
        }
    }
}
