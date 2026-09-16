package com.cpq.task260915;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260915 · 分片 S-B · <b>AC-9</b>：{@code bundleVersion} 升版 {@code "1.0"} → {@code "1.1"}。
 *
 * <p><b>AC-9 原文</b>：断言：新导出的包顶层 {@code bundleVersion} 字段值为字符串 {@code "1.1"}。
 *
 * <p>本类另带一条<b>附属契约形状断言</b>（AC-9-b）：1.1 包的 {@code components[]} / {@code sqlViews[]}
 * 里那 8 个新键<b>确实存在</b>。它与 S-A 的 AC-1~AC-6 <b>不重叠</b> —— S-A 验的是"往返后值相等"，
 * 这里只验"升版之后包里有这几个键"，是 {@code bundleVersion} 从 1.0 跳到 1.1 这件事的<b>语义本身</b>。
 * 没有它，AC-9 就退化成"改了一个字符串常量"。
 */
@QuarkusTest
@TestProfile(Sb260915Profile.class)
@DisplayName("task-260915 S-B · AC-9 导出包版本号升 1.1")
class Ac9ExportBundleVersionTest extends Sb260915TestBase {

    /** 建一个含 1 个"字段齐全"组件 + 1 个 builder 视图的目录，作为导出输入。 */
    private UUID seedDirectory() {
        UUID dir = createDirectory("AC9");
        UUID comp = insertComponent(dir, PREFIX + "AC9-C1", PREFIX + "AC9 组件",
                "[{\"name\":\"甲\",\"field_type\":\"INPUT_TEXT\"}]", "[]",
                "{\"idField\":\"料号\",\"parentField\":\"父料号\",\"defaultExpanded\":true}", true,
                "元素编号", "元素单价", "币种");
        insertSqlView(comp, "rt_sb_ac9_view", "SELECT 1 AS x",
                "{\"dialect\":\"QUOTE\",\"tabType\":\"BOM\",\"switches\":null,\"axisScope\":\"CLOSURE\","
                        + "\"variantKey\":\"\",\"priceStrategy\":null,\"builderVersion\":1,\"columns\":[]}", 1);
        return dir;
    }

    @Test
    @DisplayName("AC-9-a: GET /export 顶层 bundleVersion == 字符串 \"1.1\"")
    void exportedBundleVersionIs11() throws Exception {
        UUID dir = seedDirectory();

        Response r = exportDirectory(dir);
        assertEquals(200, r.statusCode(), "导出应 200，实际 body=" + r.asString());

        JsonNode bundle = M.readTree(r.asString());

        // 前置：包里必须真有组件，否则下面的断言是在一个空包上做的（AC-16 才是空目录场景，不是这里）
        assertTrue(bundle.path("components").isArray() && bundle.path("components").size() == 1,
                "前置：本目录应恰好导出 1 个组件，实际 components=" + bundle.path("components").size()
                        + " —— 夹具没生效，下面的断言等于空跑");

        JsonNode v = bundle.path("bundleVersion");
        assertTrue(v.isTextual(),
                "bundleVersion 必须是 JSON 字符串（api.md 写的是 \"1.1\" 带引号），实际节点类型="
                        + v.getNodeType() + " 值=" + v);
        assertEquals("1.1", v.asText(),
                "AC-9：新导出的包 bundleVersion 应为 \"1.1\"，实际 " + v.asText()
                        + " —— 老包（1.0）与新包必须能被导入端区分开，否则 AC-11 的降级报错无从判定");
    }

    @Test
    @DisplayName("AC-9-b（附属）: 1.1 包的 components[]/sqlViews[] 里 8 个新键都在（升版的语义本体）")
    void exportedBundleCarriesEightNewKeys() throws Exception {
        UUID dir = seedDirectory();

        Response r = exportDirectory(dir);
        assertEquals(200, r.statusCode(), "导出应 200，实际 body=" + r.asString());
        JsonNode bundle = M.readTree(r.asString());
        JsonNode item = bundle.path("components").path(0);
        assertTrue(item.isObject(), "前置：导出包里应有 1 个组件对象，实际=" + bundle.path("components"));
        JsonNode view = item.path("sqlViews").path(0);
        assertTrue(view.isObject(), "前置：该组件应有 1 条 sqlView，实际=" + item.path("sqlViews"));

        List<String> missing = new ArrayList<>();
        for (String k : List.of("treeConfig", "bomRecursiveExpand",
                "elementCodeField", "elementPriceField", "elementCurrencyField")) {
            if (!item.has(k)) {
                missing.add("components[0]." + k);
            }
        }
        for (String k : List.of("builderConfig", "builderVersion", "status")) {
            if (!view.has(k)) {
                missing.add("components[0].sqlViews[0]." + k);
            }
        }
        assertTrue(missing.isEmpty(),
                "1.1 包缺了这些键: " + missing
                        + "\n → 夹具这 8 个值全部造成了非空，键还不出现，说明导出端根本没带上它们"
                        + "（而不是 Jackson 的 NON_NULL 省略）。实际 item=" + item.toString());
    }
}
