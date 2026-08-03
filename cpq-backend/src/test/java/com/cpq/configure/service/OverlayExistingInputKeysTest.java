package com.cpq.configure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * spec 2026-08-03 Task 6：重物化后把既有 row_data 的 INPUT 键盖回。
 *
 * <p>纯函数、不起 Quarkus——共享测试库正被并发会话改动，起 Quarkus 的测试会被
 * Flyway validate 拖累，而这段逻辑本身与数据库无关。
 */
class OverlayExistingInputKeysTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final UUID CID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    /** components_snapshot：一个组件，含 INPUT_NUMBER「损耗率」/ BASIC_DATA「元素」/ FORMULA「材料成本」 */
    private static final String SNAPSHOT = """
      [ {"componentId":"11111111-1111-1111-1111-111111111111","componentCode":"C1","tabName":"材料成本",
         "fields":[ {"name":"损耗率","field_type":"INPUT_NUMBER"},
                    {"name":"元素","field_type":"BASIC_DATA"},
                    {"name":"材料成本","field_type":"FORMULA"} ]} ]
      """;

    private Map<UUID, ArrayNode> fresh(String json) throws Exception {
        Map<UUID, ArrayNode> m = new LinkedHashMap<>();
        m.put(CID, (ArrayNode) M.readTree(json));
        return m;
    }

    private Map<UUID, JsonNode> existing(String json) throws Exception {
        Map<UUID, JsonNode> m = new LinkedHashMap<>();
        m.put(CID, M.readTree(json));
        return m;
    }

    @Test
    void 既有的用户清空必须盖回重物化结果() throws Exception {
        Map<UUID, ArrayNode> f = fresh(
            "[{\"row_index\":0,\"损耗率\":1.05,\"元素\":\"Ag\",\"材料成本\":641.9}]");
        Map<UUID, JsonNode> e = existing(
            "[{\"row_index\":0,\"损耗率\":\"\",\"元素\":\"旧\",\"材料成本\":1}]");

        ConfigureSnapshotService.overlayExistingInputKeys(M.readTree(SNAPSHOT), f, e);

        JsonNode row = f.get(CID).get(0);
        assertEquals("", row.path("损耗率").asText(), "用户清空必须保留，不能被重物化的 1.05 覆盖");
        assertEquals("Ag", row.path("元素").asText(), "BASIC_DATA 必须用新值（刷新的意义所在）");
        assertEquals(641.9, row.path("材料成本").asDouble(), 1e-9, "FORMULA 必须用重算值");
    }

    @Test
    void 既有的用户手填值必须盖回() throws Exception {
        Map<UUID, ArrayNode> f = fresh("[{\"row_index\":0,\"损耗率\":1.05}]");
        Map<UUID, JsonNode> e = existing("[{\"row_index\":0,\"损耗率\":9.99}]");

        ConfigureSnapshotService.overlayExistingInputKeys(M.readTree(SNAPSHOT), f, e);

        assertEquals(9.99, f.get(CID).get(0).path("损耗率").asDouble(), 1e-9);
    }

    @Test
    void 既有行没有该键时用新烘的值() throws Exception {
        Map<UUID, ArrayNode> f = fresh("[{\"row_index\":0,\"损耗率\":1.05}]");
        Map<UUID, JsonNode> e = existing("[{\"row_index\":0}]");

        ConfigureSnapshotService.overlayExistingInputKeys(M.readTree(SNAPSHOT), f, e);

        assertEquals(1.05, f.get(CID).get(0).path("损耗率").asDouble(), 1e-9);
    }

    @Test
    void 按rowIndex对齐而非数组下标() throws Exception {
        // 新结果 2 行(row_index 0,1)；既有只有 row_index=1 那行有用户值
        Map<UUID, ArrayNode> f = fresh(
            "[{\"row_index\":0,\"损耗率\":1.05},{\"row_index\":1,\"损耗率\":2.10}]");
        Map<UUID, JsonNode> e = existing("[{\"row_index\":1,\"损耗率\":\"\"}]");

        ConfigureSnapshotService.overlayExistingInputKeys(M.readTree(SNAPSHOT), f, e);

        assertEquals(1.05, f.get(CID).get(0).path("损耗率").asDouble(), 1e-9, "row_index=0 不受影响");
        assertEquals("", f.get(CID).get(1).path("损耗率").asText(), "row_index=1 盖回用户清空");
    }

    @Test
    void 既有为空或null时整体不变() throws Exception {
        Map<UUID, ArrayNode> f = fresh("[{\"row_index\":0,\"损耗率\":1.05}]");
        String before = f.get(CID).toString();

        ConfigureSnapshotService.overlayExistingInputKeys(M.readTree(SNAPSHOT), f, Map.of());
        assertEquals(before, f.get(CID).toString());

        ConfigureSnapshotService.overlayExistingInputKeys(M.readTree(SNAPSHOT), f, null);
        assertEquals(before, f.get(CID).toString());
    }

    @Test
    void 快照里没有的组件不动() throws Exception {
        UUID other = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Map<UUID, ArrayNode> f = new LinkedHashMap<>();
        f.put(other, (ArrayNode) M.readTree("[{\"row_index\":0,\"损耗率\":1.05}]"));
        Map<UUID, JsonNode> e = new LinkedHashMap<>();
        e.put(other, M.readTree("[{\"row_index\":0,\"损耗率\":\"\"}]"));

        ConfigureSnapshotService.overlayExistingInputKeys(M.readTree(SNAPSHOT), f, e);

        assertEquals(1.05, f.get(other).get(0).path("损耗率").asDouble(), 1e-9,
            "组件不在 components_snapshot 里 → 取不到 INPUT 字段清单 → 不动");
    }
}
