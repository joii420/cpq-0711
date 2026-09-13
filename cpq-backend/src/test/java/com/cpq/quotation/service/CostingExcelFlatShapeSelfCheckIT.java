package com.cpq.quotation.service;

import com.cpq.quotation.entity.QuotationLineItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260912 <b>开发自测</b>（backend-engineer 的 B-4 证据，非正式测试资产）：
 * 核价 Excel 落库形态由「BOM 树 N 行」切到「每产品一行 + 取卡片值」之后，
 * {@code CardSnapshotService.buildExcelValues(li, tpl, cust, cardValuesJson, costingTree)}
 * 这个<b>唯一开关</b>的两个方向各自产出什么。
 *
 * <p>覆盖：
 * <ul>
 *   <li><b>AC-3</b>（{@code costingTree=false} → {@code rows} 长度 1、无 {@code treeMode} 键）</li>
 *   <li><b>AC-2 的值</b>（该唯一行四列 = 各页签总计 489985 / 5438667.5 / 5.8 / 5438673.3）</li>
 *   <li><b>AC-8①</b>（把开关拨回 {@code true} → 立刻回到树形 3 行 + {@code treeMode:true}，
 *       AC-3 的两条断言同时为假 ⇒ 该 AC 有鉴别力，不是「怎么改都绿」）</li>
 *   <li><b>AC-6</b>（页签总计确为 0 时对应列输出 {@code "0"}，不编造回退值、不是 null/空串）</li>
 * </ul>
 *
 * <p>夹具逐字沿用 {@code CostingExcelTreeTabKeyIT}（对齐 {@code cpq_db_0910} 的
 * {@code QT-20260911-0010 / S0001 / 核价通用1}：裸 componentId 作 tabKey、subtotal 为字符串、
 * BOM 树 spine 3 节点）。全程 {@code @TestTransaction} 回滚，且
 * {@code buildExcelValues} 只返回字符串不落库 —— <b>不写任何 {@code *_excel_values} 数据</b>。
 */
@QuarkusTest
class CostingExcelFlatShapeSelfCheckIT {

    private static final ObjectMapper M = new ObjectMapper();

    @Inject
    CardSnapshotService cardSnapshotService;

    @Inject
    EntityManager em;

    private static final UUID BOM   = UUID.fromString("a2d0dc3a-9506-4b7e-8d5e-8e512433a201");
    private static final UUID ELEM  = UUID.fromString("1e41ecb6-b663-42a1-b8da-122961699d26");
    private static final UUID PROC  = UUID.fromString("36eb1efb-8e35-45bd-8dc7-199521a759b9");
    private static final UUID TOTAL = UUID.fromString("203382c5-38fd-4a81-978e-610afc8da3bd");

    /** 现网 costing_card_values 形态：tab 无 sortOrder，subtotal/subtotalByColumn 为字符串。 */
    private static String cards(String procSubtotal) {
        return """
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
          {"componentId":"%s","tabName":"加工费","componentType":"NORMAL","subtotal":"%s",
           "subtotalByColumn":{"加工费":"%s"},
           "editRows":[],"formulaResults":[],"baseRows":[],
           "resolvedRows":[{"工序编号":"OP10","加工费":%s}]},
          {"componentId":"%s","tabName":"核价小计1","componentType":"SUBTOTAL","subtotal":"5438673.3",
           "editRows":[],"formulaResults":[],"baseRows":[],"resolvedRows":[]}]}
        """.formatted(BOM, ELEM, PROC, procSubtotal, procSubtotal, procSubtotal, TOTAL).strip();
    }

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

    /** 建夹具，返回 [lineItemId, templateId, customerId]。 */
    private UUID[] fixture() {
        UUID customerId = UUID.randomUUID();
        String custCode = "CFS" + customerId.toString().replace("-", "").substring(0, 7);
        em.createNativeQuery("""
                INSERT INTO customer (id, name, code, level, status, accumulated_amount, version, created_at, updated_at)
                VALUES (?1, 'IT-CostingExcelFlat-Cust', ?2, 'STANDARD', 'ACTIVE', 0, 0, now(), now())
                """)
          .setParameter(1, customerId).setParameter(2, custCode).executeUpdate();

        UUID userId = UUID.randomUUID();
        String uSuffix = userId.toString().replace("-", "").substring(0, 8);
        em.createNativeQuery("""
                INSERT INTO "user" (id, username, full_name, email, password_hash, role, status,
                  is_first_login, failed_login_attempts, created_at, updated_at)
                VALUES (?1, ?2, 'IT CFS User', ?3, 'hash', 'SALES_REP', 'ACTIVE', true, 0, now(), now())
                """)
          .setParameter(1, userId)
          .setParameter(2, "it-cfs-user-" + uSuffix)
          .setParameter(3, "itcfs_" + uSuffix + "@test.com").executeUpdate();

        UUID quotationId = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO quotation
                  (id, quotation_number, customer_id, name, sales_rep_id,
                   status, total_amount, original_amount, system_discount_rate, final_discount_rate,
                   is_manually_adjusted, tax_rate, tax_amount, bound_global_variables_snapshot,
                   created_at, updated_at)
                VALUES (?1, ?2, ?3, 'IT CFS Quotation', ?4,
                   'DRAFT', 0, 0, 100, 100, false, 0, 0, '[]', now(), now())
                """)
          .setParameter(1, quotationId)
          .setParameter(2, "IT-CFS-" + quotationId.toString().replace("-", "").substring(0, 8))
          .setParameter(3, customerId).setParameter(4, userId).executeUpdate();

        UUID excelCompId = UUID.randomUUID();
        em.createNativeQuery("""
                INSERT INTO component (id, name, code, component_type, excel_columns, created_at, updated_at)
                VALUES (?1, 'IT-CFS-Excel', ?2, 'EXCEL', CAST(?3 AS jsonb), now(), now())
                """)
          .setParameter(1, excelCompId)
          .setParameter(2, "COMP-IT-CFS-" + excelCompId.toString().replace("-", "").substring(0, 8))
          .setParameter(3, EXCEL_COLUMNS).executeUpdate();

        UUID templateId = UUID.randomUUID();
        String excelViewConfig =
            "{\"version\":2,\"column_overrides\":[],\"excel_component_id\":\"" + excelCompId + "\"}";
        em.createNativeQuery("""
                INSERT INTO template
                  (id, template_series_id, name, status, formulas,
                   template_sql_views_snapshot, components_snapshot, excel_view_config, created_at, updated_at)
                VALUES (?1, ?2, 'IT-CFS-Tmpl', 'DRAFT', '[]', '{}',
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
        return new UUID[]{ lineItemId, templateId, customerId };
    }

    private static JsonNode parse(String json) {
        try { return M.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    @TestTransaction
    @DisplayName("AC-3/AC-2：costingTree=false → rows 长度 1、无 treeMode 键、四列 = 各页签总计")
    void flatShape_oneRowPerProduct_withRealSubtotals() {
        UUID[] f = fixture();
        QuotationLineItem li = QuotationLineItem.findById(f[0]);
        assertNotNull(li);

        String json = cardSnapshotService.buildExcelValues(li, f[1], f[2], cards("5.8"), false);
        JsonNode root = parse(json);

        assertFalse(root.has("treeMode"),
            "AC-3：非树形不得再写 treeMode 键，实得 " + json);
        assertEquals(1, root.path("rows").size(),
            "AC-3：每产品一行 ⇒ rows 长度必须为 1，实得 " + root.path("rows").size() + " —— " + json);

        JsonNode row = root.path("rows").get(0);
        assertBigDecimal("col_1", "489985", row);
        assertBigDecimal("col_2", "5438667.5", row);
        assertBigDecimal("col_3", "5.8", row);
        assertBigDecimal("col_4", "5438673.3", row);
        assertFalse(row.has("__nodeId"), "AC-2：非树形行不得再带 BOM 节点系统列 __nodeId");
        System.out.println("[selfcheck AC-3/AC-2] rows=1 无 treeMode 四列=489985/5438667.5/5.8/5438673.3 ✅");
    }

    @Test
    @TestTransaction
    @DisplayName("AC-8①还原实验：开关拨回 costingTree=true → 树形 3 行 + treeMode:true（AC-3 立刻为假）")
    void restoreExperiment_treeSwitchDrivesShape() {
        UUID[] f = fixture();
        QuotationLineItem li = QuotationLineItem.findById(f[0]);

        String flat = cardSnapshotService.buildExcelValues(li, f[1], f[2], cards("5.8"), false);
        String tree = cardSnapshotService.buildExcelValues(li, f[1], f[2], cards("5.8"), true);
        JsonNode rf = parse(flat), rt = parse(tree);

        // 干预确实生效：同一入参、只翻转开关，两侧形态必须不同（否则本 AC 是空验证）
        assertNotEquals(rf.path("rows").size(), rt.path("rows").size(),
            "还原实验无鉴别力：翻转 costingTree 后行数没变 —— 说明开关没接上，AC-3 的绿是假绿");
        assertEquals(1, rf.path("rows").size());
        assertEquals(3, rt.path("rows").size(), "spine 3 节点 → 树形 3 行");
        assertTrue(rt.path("treeMode").asBoolean(false), "树形分支应写 treeMode:true");
        assertFalse(rf.has("treeMode"));
        System.out.println("[selfcheck AC-8①] false→1行/无treeMode ; true→3行/treeMode:true ⇒ AC-3 可跑红 ✅");
    }

    @Test
    @TestTransaction
    @DisplayName("AC-6：页签总计确为 0 → 该列输出 \"0\"，不编造回退值、不为 null")
    void zeroSubtotalTab_stillRendersZero() {
        UUID[] f = fixture();
        QuotationLineItem li = QuotationLineItem.findById(f[0]);

        String json = cardSnapshotService.buildExcelValues(li, f[1], f[2], cards("0"), false);
        JsonNode row = parse(json).path("rows").get(0);

        assertNotNull(row, "应有一行");
        JsonNode c3 = row.path("col_3");
        assertFalse(c3.isMissingNode() || c3.isNull(),
            "AC-6：加工费页签总计为 0 时该列不得缺失/为 null，实得 " + json);
        assertEquals(0, new BigDecimal("0").compareTo(new BigDecimal(c3.asText())),
            "AC-6：期望 0，实得 " + c3.asText());
        // 其余非 0 页签不受影响（证明不是整行都塌成 0）
        assertBigDecimal("col_1", "489985", row);
        assertBigDecimal("col_2", "5438667.5", row);
        System.out.println("[selfcheck AC-6] 加工费=0 → col_3=\"" + c3.asText() + "\"，其余列不受影响 ✅");
    }

    private static void assertBigDecimal(String key, String expected, JsonNode row) {
        JsonNode v = row.path(key);
        assertFalse(v.isMissingNode() || v.isNull(), key + " 不应为 null/缺失，行=" + row);
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(v.asText())),
            key + " 期望 " + expected + " 实得 " + v.asText());
    }
}
