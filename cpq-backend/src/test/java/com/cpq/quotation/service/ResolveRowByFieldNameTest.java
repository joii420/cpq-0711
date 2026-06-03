package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ResolveRowByFieldNameTest {

    @Inject FormulaCalculator calc;
    private static final ObjectMapper M = new ObjectMapper();

    // 字段定义：元素(BASIC_DATA $ys_view.元素) / 含量(BASIC_DATA $ys_view.含量) /
    //           类型(BASIC_DATA $ys_view.material_type, 别名≠字段名) / 单价(INPUT_NUMBER) / 小计(FORMULA)
    private static final String FIELDS = """
      [ {"name":"元素","field_type":"BASIC_DATA","basic_data_path":"$ys_view.元素"},
        {"name":"含量","field_type":"BASIC_DATA","basic_data_path":"$ys_view.含量"},
        {"name":"类型","field_type":"BASIC_DATA","basic_data_path":"$ys_view.material_type"},
        {"name":"单价","field_type":"INPUT_NUMBER"},
        {"name":"小计","field_type":"FORMULA"} ]
      """;

    @Test
    void resolvesByFieldNameIncludingStringAndAliasMismatch() throws Exception {
        JsonNode fields = M.readTree(FIELDS);
        // driverRow: SQL 别名键(含 material_type 标量, 新鲜快照形态)
        JsonNode driverRow = M.readTree(
            "{\"元素\":\"Cu\",\"含量\":70,\"material_type\":\"非银点类\",\"hf_part_no\":\"P1\"}");
        // basicDataValues: path 键(bnfDriverLookupKey 形态)
        JsonNode bdv = M.readTree(
            "{\"{$ys_view.元素}\":\"Cu\",\"{$ys_view.含量}\":70,\"{$ys_view.material_type}\":\"非银点类\"}");
        JsonNode editValues = M.readTree("{\"单价\":12.5}");
        JsonNode formulaValues = M.readTree("{\"小计\":875.0}");

        Map<String, Object> row = calc.resolveRowByFieldName(fields, driverRow, bdv, editValues, formulaValues);

        // 字符串字段保留 + 按字段名(类型, 不是 material_type)落键
        assertEquals("非银点类", row.get("类型"));
        assertEquals("Cu", row.get("元素"));
        // 数值字段
        assertEquals(70, ((Number) row.get("含量")).intValue());
        // INPUT 来自 editValues
        assertEquals(12.5, ((Number) row.get("单价")).doubleValue(), 1e-9);
        // FORMULA 来自 formulaValues
        assertEquals(875.0, ((Number) row.get("小计")).doubleValue(), 1e-9);
        // 关键: 没有把 SQL 别名 material_type 当字段名泄漏(应只暴露字段名键)
        assertFalse(row.containsKey("material_type"), "不应出现 SQL 别名 material_type, 只按字段名 类型");
    }
}
