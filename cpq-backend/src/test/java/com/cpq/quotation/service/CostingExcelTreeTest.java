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
 * P2-B 核价 Excel <b>树形分支</b>的回归守卫：
 * {@code CardSnapshotService.buildExcelValues(li, tpl, cust, cardValuesJson, costingTree=true)}
 * → {@code ExcelViewService.buildLineTreeRows} 仍按快照树页签的 spine 逐节点出多行，
 * 产出 {@code {rows:[N], treeMode:true}}，每行带 {@code __hfPartNo/__lvl/__bomVersion} 等系统列。
 *
 * <p><b>2026-09-12 repair-260912 改造（用户裁决）</b>，改造前后的差别：
 * <ul>
 *   <li><b>被测对象换了</b>：原先走生产落库路径 {@code refreshCostingCardValues(quotationId)}
 *       再读回 {@code costing_excel_values} 断言 {@code treeMode==true}。该生产路径已按用户裁决
 *       切成 {@code costingTree=false}（核价 Excel 不再走树形），原断言恒为假 —— 它守的是被推翻的语义。
 *       现改为<b>直接调 {@code buildExcelValues(..., true)}</b>：守的是我们<b>刻意保留</b>
 *       的树形代码本身（AC-7「不删 {@code buildLineTreeRows}」），与生产路径怎么传参解耦。</li>
 *   <li><b>不再写库</b>：{@code buildExcelValues} 只返回字符串，本测试不再触碰
 *       {@code costing_excel_values}（原实现经 {@code refreshCostingCardValues} 会写它）。</li>
 *   <li><b>不再 Skipped</b>：原实现依赖库里存在料号 {@code 3120018220} 的核价 line item，
 *       而 {@code cpq_db_test} 与 {@code cpq_db_0910} 实查<b>都是 0 条</b> ⇒ {@code Assumptions}
 *       恒跳过 ⇒ 长期是「假绿」。现自建夹具（形态对齐 {@code CostingExcelFlatShapeSelfCheckIT}），
 *       {@code @TestTransaction} 结束回滚。</li>
 * </ul>
 *
 * <p>⚠️ 守卫边界：spine 的 {@code __bomVersion} 等系统列由 {@code BomTreeRenderService} 写进
 * 卡片值快照，本层只负责<b>原样透传</b>；「内部节点带自身 BOM 版本、叶子为空」那条业务语义
 * 由渲染侧的测试守，本测试守的是「树形分支还在 + 逐节点出行 + 系统列不丢」。
 */
@QuarkusTest
class CostingExcelTreeTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Inject CardSnapshotService cardSnapshotService;
    @Inject EntityManager em;

    private static final UUID BOM   = UUID.fromString("a2d0dc3a-9506-4b7e-8d5e-8e512433a201");
    private static final UUID ELEM  = UUID.fromString("1e41ecb6-b663-42a1-b8da-122961699d26");
    private static final UUID PROC  = UUID.fromString("36eb1efb-8e35-45bd-8dc7-199521a759b9");
    private static final UUID TOTAL = UUID.fromString("203382c5-38fd-4a81-978e-610afc8da3bd");

    /** 树 spine 3 节点：根(lvl1,自身版本2000) → 内部(lvl2,自身版本2010) → 叶子(lvl3,无自身 BOM ⇒ 版本空)。 */
    private static final String COSTING_CARD_VALUES = """
        {"tabs":[
          {"componentId":"%s","tabName":"BOM","componentType":"NORMAL","subtotal":"5438667.5",
           "subtotalByColumn":{"物料成本":"5438667.5"},
           "editRows":[],"formulaResults":[],
           "baseRows":[
             {"__nodeId":"300001","__parentId":null,"__lvl":1,"__hfPartNo":"300001",
              "__parentNo":null,"__bomVersion":"2000","driverRow":{"生产料号":"300001"},"basicDataValues":{}},
             {"__nodeId":"300001/300012","__parentId":"300001","__lvl":2,"__hfPartNo":"300012",
              "__parentNo":"300001","__bomVersion":"2010","driverRow":{"生产料号":"300012"},"basicDataValues":{}},
             {"__nodeId":"300001/300012/00081","__parentId":"300001/300012","__lvl":3,"__hfPartNo":"00081",
              "__parentNo":"300012","__bomVersion":"","driverRow":{"生产料号":"00081"},"basicDataValues":{}}],
           "resolvedRows":[
             {"__nodeId":"300001","生产料号":"300001","物料成本":5438667.5},
             {"__nodeId":"300001/300012","生产料号":"300012","物料成本":0},
             {"__nodeId":"300001/300012/00081","生产料号":"00081","物料成本":0}]},
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

    /** @return [lineItemId, templateId, customerId] */
    private UUID[] fixture() {
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
        return new UUID[]{ lineItemId, templateId, customerId };
    }

    @Test
    @TestTransaction
    @DisplayName("AC-7 守卫：buildExcelValues(costingTree=true) 仍按 spine 逐节点出树形多行 + treeMode:true")
    void costingTreeBranch_stillRendersBomTree() {
        UUID[] f = fixture();
        QuotationLineItem li = QuotationLineItem.findById(f[0]);
        assertNotNull(li, "夹具 line item 应已在本事务内持久化");

        String json = cardSnapshotService.buildExcelValues(li, f[1], f[2], COSTING_CARD_VALUES, true);
        assertNotNull(json, "树形分支不应返回 null");

        JsonNode root;
        try { root = M.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }

        assertTrue(root.path("treeMode").asBoolean(false),
            "树形分支应标 treeMode=true，实得 " + json);

        JsonNode rows = root.path("rows");
        assertTrue(rows.isArray(), "rows 应为数组");
        assertEquals(3, rows.size(),
            "行数 = 快照树页签 spine 节点数(3)；若塌成 1 说明树形分支被误删/误接成平铺重载");

        boolean hasRoot = false;
        int withVersion = 0;
        for (JsonNode r : rows) {
            assertTrue(r.has("__hfPartNo"), "每行带 __hfPartNo");
            assertTrue(r.has("__lvl"), "每行带 __lvl");
            assertTrue(r.has("__nodeId"), "每行带 __nodeId");

            boolean hasBusinessCol = false;
            for (java.util.Iterator<String> it = r.fieldNames(); it.hasNext(); ) {
                if (!it.next().startsWith("_")) { hasBusinessCol = true; break; }
            }
            assertTrue(hasBusinessCol, "每行应含至少一个业务配置列(非 __ 系统列)，行=" + r);

            if (!r.path("__bomVersion").asText("").isEmpty()) withVersion++;
            if ("300001".equals(r.path("__hfPartNo").asText())) {
                hasRoot = true;
                assertEquals(1, r.path("__lvl").asInt(-1), "根 __lvl=1");
                assertEquals("2000", r.path("__bomVersion").asText(""), "根自身 BOM 版本应原样透传=2000");
            }
        }
        assertTrue(hasRoot, "应含根料号行");
        // 系统列原样透传守卫：快照里 2 个节点有版本、叶子为空 ⇒ 透传后必须还是 2 / 非全量。
        assertEquals(2, withVersion,
            "__bomVersion 应从快照原样透传（2 个内部节点有值、叶子空），实得 " + withVersion);

        // 列表达式是 [页签(总计)]，filterByNodeId 不按节点重算 subtotal ⇒ 每行都是整页签总计（既有语义）。
        for (JsonNode r : rows) {
            assertBigDecimal("col_1", "489985", r);
            assertBigDecimal("col_2", "5438667.5", r);
            assertBigDecimal("col_3", "5.8", r);
            assertBigDecimal("col_4", "5438673.3", r);
        }
        System.out.println("[CostingExcelTree] costingTree=true → rows=3 treeMode=true 系统列透传 四列=总计 ✅");
    }

    @Test
    @TestTransaction
    @DisplayName("鉴别力对照：同一入参翻转 costingTree ⇒ true 出 3 行树形 / false 出 1 行平铺")
    void treeSwitchIsWiredAndDiscriminating() {
        UUID[] f = fixture();
        QuotationLineItem li = QuotationLineItem.findById(f[0]);

        String tree = cardSnapshotService.buildExcelValues(li, f[1], f[2], COSTING_CARD_VALUES, true);
        String flat = cardSnapshotService.buildExcelValues(li, f[1], f[2], COSTING_CARD_VALUES, false);
        JsonNode rt, rf;
        try { rt = M.readTree(tree); rf = M.readTree(flat); }
        catch (Exception e) { throw new RuntimeException(e); }

        assertEquals(3, rt.path("rows").size(), "true → spine 3 行");
        assertEquals(1, rf.path("rows").size(), "false → 每产品一行");
        assertTrue(rt.path("treeMode").asBoolean(false), "true → 写 treeMode");
        assertFalse(rf.has("treeMode"), "false → 不写 treeMode");
        assertNotEquals(rt.path("rows").size(), rf.path("rows").size(),
            "开关没接上的话本用例无鉴别力");
        System.out.println("[CostingExcelTree] 开关鉴别力：true=3行/有treeMode ; false=1行/无treeMode ✅");
    }

    private static void assertBigDecimal(String key, String expected, JsonNode row) {
        JsonNode v = row.path(key);
        assertFalse(v.isMissingNode() || v.isNull(), key + " 不应为 null/缺失，行=" + row);
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(v.asText())),
            key + " 期望 " + expected + " 实得 " + v.asText());
    }
}
