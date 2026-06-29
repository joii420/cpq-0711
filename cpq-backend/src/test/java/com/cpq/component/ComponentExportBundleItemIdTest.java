package com.cpq.component;

import com.cpq.component.dto.ComponentExportBundle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD 失败测试 → 实现 → 通过:验证导出 bundle 的 {@link ComponentExportBundle.Item#id}
 * 非空且是合法 UUID 字符串。
 *
 * <p>此测试属于纯单测(无 @QuarkusTest,不启动容器/DB),只做 round-trip 断言。
 * 导出服务层写 id 的集成校验靠后续 G2/G3 的 @QuarkusTest 覆盖。
 */
class ComponentExportBundleItemIdTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Item.id 能在序列化 → 反序列化 round-trip 后完整保留。 */
    @Test
    void item_id_survivesRoundTrip() throws Exception {
        String sourceId = UUID.randomUUID().toString();

        ComponentExportBundle bundle = new ComponentExportBundle();
        bundle.exportedAt = "2026-06-18T00:00:00Z";

        ComponentExportBundle.Item item = new ComponentExportBundle.Item();
        item.id = sourceId;                      // 导出端写入原始组件 id
        item.code = "COMP-001";
        item.name = "测试组件";
        item.componentType = "BASIC";
        item.fields = MAPPER.createArrayNode();
        item.formulas = MAPPER.createArrayNode();
        item.excelColumns = MAPPER.createArrayNode();

        bundle.components = List.of(item);

        // serialize → deserialize round-trip
        String json = MAPPER.writeValueAsString(bundle);
        ComponentExportBundle restored = MAPPER.readValue(json, ComponentExportBundle.class);

        assertNotNull(restored.components, "components 不应丢失");
        assertEquals(1, restored.components.size());
        ComponentExportBundle.Item restoredItem = restored.components.get(0);

        // 核心断言: id 非空且与写入值完全一致
        assertNotNull(restoredItem.id, "Item.id 经 round-trip 后不应为 null — 导出端必须写入原始组件 id");
        assertEquals(sourceId, restoredItem.id, "Item.id 应与源组件 id 完全一致");

        // 额外守护:id 是合法 UUID 格式(不是空串 / 随意字符串)
        assertDoesNotThrow(() -> UUID.fromString(restoredItem.id),
                "Item.id 必须是合法 UUID 字符串,实际值: " + restoredItem.id);
    }

    /**
     * 老 bundle(无 id 字段)反序列化后 id=null —— 这是预期的向后兼容行为。
     * 导入端(G3)会对 id=null 做降级处理。
     */
    @Test
    void item_id_isNullWhenAbsentInJson() throws Exception {
        // 手写一个不含 id 字段的老格式 bundle JSON
        String oldBundleJson = "{"
                + "\"bundleVersion\":\"1.0\","
                + "\"components\":[{"
                + "  \"code\":\"COMP-OLD\","
                + "  \"name\":\"老组件\","
                + "  \"componentType\":\"BASIC\","
                + "  \"fields\":[],"
                + "  \"formulas\":[],"
                + "  \"excelColumns\":[]"
                + "}]"
                + "}";

        ComponentExportBundle restored = MAPPER.readValue(oldBundleJson, ComponentExportBundle.class);

        assertNotNull(restored.components);
        assertEquals(1, restored.components.size());
        // 老 bundle 没有 id 字段 → 反序列化后 id=null (向后兼容)
        assertNull(restored.components.get(0).id,
                "老 bundle(无 id 字段)反序列化后 id 应为 null,导入端需对 null 做降级");
    }
}
