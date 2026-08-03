package com.cpq.component.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0729 B7 · ElementBindingDerivation 单测。
 *
 * <p>纯函数测试，不用 {@code @QuarkusTest}——{@code derive()} 本就不碰 DB。sql_template/fields
 * 取自开发库 {@code cpq_db_0724} 现网 COMP-0027「材料成本」/ COMP-0049「物料与元素BOM」两条
 * 真实数据的逐字节导出（2026-08 拍照，非手工臆造），是 backtask 验收 #32④ 点名的被测对象
 * （COMP-0049 是反例样本：字段名叫「元素代码」不是「元素」）。
 *
 * <p>🔴 <b>踩坑记录</b>：本测试最初写成 {@code @QuarkusTest} + {@code EntityManager} 直查现网数据，
 * 结果 sqlViewName 断言失败——排查发现 {@code mvn test} 走 {@code test} profile 连的是
 * {@code cpq_db}（另一个库），不是手工 psql 核对用的 {@code cpq_db_0724}，两库 COMP-0027 的
 * {@code component_sql_view} 内容早已分叉（{@code sql_view_name} 一个是 {@code mc_view} 一个是
 * {@code cp_view}）。改为本文件这种「纯函数 + 内嵌真实文本」写法，从根上避免这类跨库不一致。
 */
class ElementBindingDerivationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** COMP-0027「材料成本」真实 sql_template（mc_view，BOM 闭包契约）。 */
    private static final String COMP_0027_SQL = """
        -- 材料成本(材质元素, 平铺契约; 料号列=材质料号 material_part_no(料号铁律); 元素单价接客户价格策略)
        WITH RECURSIVE bom_closure AS (
          SELECT DISTINCT b.material_no AS root_no, b.material_no AS node_no, 0 AS lvl
          FROM material_bom_item b
          WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
          UNION ALL
          SELECT c.root_no, b.component_no, c.lvl + 1
          FROM bom_closure c
            JOIN material_bom_item b ON b.material_no = c.node_no
          WHERE b.system_type = 'QUOTE' AND b.is_current AND b.customer_no = :customerCode
            AND c.lvl < 10
        )
        SELECT
          COALESCE(cl.root_no, ebi.material_no) AS hf_part_no,
          ebi.material_part_no AS _料号,
          COALESCE(mr.name, mm2.material_name) AS _材质,
          ebi.seq_no AS _项次,
          ebi.component_no AS _元素,
          ebi.content AS _组成含量,
          ebi.scrap_rate AS _损耗率,
          ebi.composition_qty AS _毛重,
          ebi.issue_unit AS _毛用量单位,
          ebi.material_no AS _归属料号,
          cep.unit_price AS 元素单价
        FROM element_bom_item ebi
          LEFT JOIN bom_closure cl ON cl.node_no = ebi.material_no
          LEFT JOIN material_recipe mr  ON mr.code = ebi.material_part_no
          LEFT JOIN material_master mm2 ON mm2.material_no = ebi.material_part_no
          LEFT JOIN f_material_element_price(:customerCode, :priceBaseDate) cep
                 ON cep.element_code = ebi.component_no
                 AND cep.material_no = COALESCE(cl.root_no, ebi.material_no)
        WHERE ebi.system_type = 'QUOTE' AND ebi.is_current AND ebi.customer_no = :customerCode
        ORDER BY COALESCE(cl.lvl, 0), ebi.material_no, ebi.material_part_no, ebi.seq_no
        """;

    private static final String COMP_0027_FIELDS = """
        [
          {"name":"料号","field_type":"INPUT_TEXT","default_source":{"type":"BASIC_DATA","path":"$mc_view._料号"}},
          {"name":"材质","field_type":"INPUT_TEXT","default_source":{"type":"BASIC_DATA","path":"$mc_view._材质"}},
          {"name":"元素","field_type":"INPUT_TEXT","default_source":{"type":"BASIC_DATA","path":"$mc_view._元素"}},
          {"name":"元素单价","field_type":"INPUT_NUMBER","is_amount":true,
           "default_source":{"type":"BASIC_DATA","path":"$mc_view.元素单价"}}
        ]
        """;

    /** COMP-0049「物料与元素BOM」真实 sql_template（wl_ys_bom_view，核价侧平铺）。 */
    private static final String COMP_0049_SQL = """
        select
          ebi.material_no        as material_no,
          ebi.material_no        as hf_part_no,
          ebi.material_part_no   as material_part_no,
          coalesce(mr.name, mi.material_name) as material_name,
          mi.specification       as specification,
          mi.dimension           as dimension,
          ebi.seq_no             as seq_no,
          ebi.component_no       as component_no,
          ebi.content            as content,
          ebi.scrap_rate         as scrap_rate,
          cep.unit_price         as 元素单价
        from element_bom_item ebi
        left join material_master mi on mi.material_no = ebi.material_part_no
        left join material_recipe mr on mr.code = ebi.material_part_no
        left join f_material_element_price(:customerCode, :priceBaseDate) cep
               on cep.element_code = ebi.component_no
              and cep.material_no  = ebi.material_no
        where ebi.system_type = 'PRICING'
          and ebi.is_current = true
        order by ebi.seq_no
        """;

    /** 反例样本：字段名叫「元素代码」（不是「元素」），且是 BASIC_DATA 类型走 basic_data_path 直存。 */
    private static final String COMP_0049_FIELDS = """
        [
          {"name":"元素代码","field_type":"BASIC_DATA","basic_data_path":"$wl_ys_bom_view.component_no"},
          {"name":"元素单价","field_type":"INPUT_NUMBER","is_amount":true,
           "default_source":{"type":"BASIC_DATA","path":"$wl_ys_bom_view.元素单价"}}
        ]
        """;

    @Test
    void comp0027_materialCost_derivesElementAndPriceField() throws Exception {
        JsonNode fields = MAPPER.readTree(COMP_0027_FIELDS);
        ElementBindingDerivation.Result r = ElementBindingDerivation.derive(COMP_0027_SQL, "mc_view", fields);

        assertEquals("cep", r.alias);
        assertEquals("元素", r.elementCodeField, "COMP-0027 元素编码列应推导为「元素」");
        assertEquals("元素单价", r.elementPriceField, "COMP-0027 价格列应推导为「元素单价」");
        assertNull(r.elementCurrencyField, "COMP-0027 无货币列，应为 null");
        assertEquals("HIGH", r.confidence);
        assertTrue(r.warnings.isEmpty(), "HIGH 置信度不应有 warnings: " + r.warnings);
    }

    @Test
    void comp0049_wlYsBom_derivesElementCodeFieldAsCounterExample() throws Exception {
        JsonNode fields = MAPPER.readTree(COMP_0049_FIELDS);
        ElementBindingDerivation.Result r = ElementBindingDerivation.derive(COMP_0049_SQL, "wl_ys_bom_view", fields);

        // 反例样本：核价侧字段名叫「元素代码」不是「元素」——且该字段是 BASIC_DATA 类型走
        // basic_data_path 直接存（不是 default_source.path 包一层），推导算法必须两种存法都认。
        assertEquals("元素代码", r.elementCodeField, "COMP-0049 元素编码列应推导为「元素代码」（反例样本）");
        assertEquals("元素单价", r.elementPriceField);
        assertEquals("HIGH", r.confidence);
    }

    @Test
    void noSqlTemplate_returnsEmptyResult() {
        ElementBindingDerivation.Result r = ElementBindingDerivation.derive(null, null, MAPPER.createObjectNode());
        assertNull(r.elementCodeField);
        assertEquals("LOW", r.confidence);
    }

    @Test
    void sqlTemplateWithoutPriceFunction_returnsEmptyResult_noException() {
        ElementBindingDerivation.Result r = ElementBindingDerivation.derive(
            "SELECT id, name FROM some_table WHERE x = 1", "some_view", MAPPER.createObjectNode());
        assertNull(r.elementCodeField);
        assertNull(r.elementPriceField);
        assertNull(r.alias);
        assertEquals("LOW", r.confidence);
    }

    @Test
    void unmatchedViewColumn_yieldsLowConfidenceWithWarning() throws Exception {
        // 价格列存在但没有任何字段引用它（default_source.path 指向别的视图/别的列）
        JsonNode fields = MAPPER.readTree("""
            [{"name":"无关字段","field_type":"INPUT_TEXT",
              "default_source":{"type":"BASIC_DATA","path":"$other_view._foo"}}]
            """);
        ElementBindingDerivation.Result r = ElementBindingDerivation.derive(COMP_0027_SQL, "mc_view", fields);
        assertNull(r.elementPriceField);
        assertNull(r.elementCodeField);
        assertEquals("LOW", r.confidence);
        assertFalse(r.warnings.isEmpty());
    }
}
