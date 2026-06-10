package com.cpq.component;

import com.cpq.component.dto.ComponentExportBundle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EXCEL 组件 export bundle round-trip 纯单测(无 @QuarkusTest,不启动容器/DB)。
 *
 * <p>验证 {@link ComponentExportBundle.Item#excelColumns} 能完整经历
 * Jackson 序列化 → 反序列化 而不丢失,守住「导出 bundle 不丢 excel_columns」契约。
 * 与 {@link com.cpq.component.service.ComponentExportService} /
 * {@link com.cpq.component.service.ComponentImportService} 同款 plain {@code new ObjectMapper()}。
 */
class ComponentExportBundleExcelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void excelColumns_survivesBundleRoundTrip() throws Exception {
        ComponentExportBundle bundle = new ComponentExportBundle();
        bundle.exportedAt = "2026-06-10T00:00:00Z";

        ComponentExportBundle.Item item = new ComponentExportBundle.Item();
        item.code = "COMP-EXCEL-001";
        item.name = "Excel 视图组件";
        item.componentType = "EXCEL";
        item.fields = MAPPER.createArrayNode();
        item.formulas = MAPPER.createArrayNode();
        // 非空的 excelColumns:两条列定义
        String excelColumnsJson =
                "[{\"key\":\"col1\",\"label\":\"单价\",\"source\":\"VARIABLE\"},"
                + "{\"key\":\"col2\",\"label\":\"数量\",\"source\":\"FORMULA\",\"formula\":\"a*b\"}]";
        item.excelColumns = MAPPER.readTree(excelColumnsJson);

        bundle.components = List.of(item);

        // serialize → deserialize round-trip
        String serialized = MAPPER.writeValueAsString(bundle);
        ComponentExportBundle restored = MAPPER.readValue(serialized, ComponentExportBundle.class);

        assertNotNull(restored.components, "components 不应丢失");
        assertEquals(1, restored.components.size());
        ComponentExportBundle.Item restoredItem = restored.components.get(0);

        JsonNode restoredExcelColumns = restoredItem.excelColumns;
        assertNotNull(restoredExcelColumns, "excelColumns 经 round-trip 后不应为 null(否则 export bundle 丢列定义)");
        assertTrue(restoredExcelColumns.isArray(), "excelColumns 应是数组");
        assertEquals(2, restoredExcelColumns.size(), "两条列定义应完整保留");
        assertEquals("col1", restoredExcelColumns.get(0).path("key").asText());
        assertEquals("单价", restoredExcelColumns.get(0).path("label").asText());
        assertEquals("a*b", restoredExcelColumns.get(1).path("formula").asText());
        // 与序列化前内容完全一致
        assertEquals(item.excelColumns, restoredExcelColumns);
    }
}
