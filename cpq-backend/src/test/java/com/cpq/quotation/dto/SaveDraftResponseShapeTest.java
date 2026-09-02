package com.cpq.quotation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260901 B-4a — {@code SaveDraftResponse.Line} 的<b>序列化键集</b>（AC-16 / api.md §1.3 收敛表）。
 *
 * <p>为什么单独测「键集」而不是靠集成测试顺带覆盖：这条契约只有在 JSON 落地那一刻才成立或不成立，
 * Java 侧断言字段值一切正常也看不出来。两个方向都会出事：
 * <ul>
 *   <li>{@code modified} 行多出 {@code "tempId": null} → 7 键 → T-16 红；</li>
 *   <li>为了凑 6 键把 {@code added} 行的 {@code tempId} 也砍掉 → 新行永远拿不到 DB id →
 *       下次保存重复插入 → <b>AC-17 被打死</b>（而且是静默的：页面上看不出来，只有库里多一行）。</li>
 * </ul>
 * 靠字段级 {@code @JsonInclude(NON_NULL)} 同时满足两边——类级会连
 * {@code quoteCardValues: null} / {@code costingCardValues: null} 一起吞掉，那两个键
 * {@code api.md §1.3} 的示例明确要求回传。本测试把这三点一起钉死。
 *
 * <p>纯 JUnit + Jackson，不需要数据库。
 */
@DisplayName("SaveDraftResponseShapeTest — 响应行的键集（AC-16 / AC-17）")
class SaveDraftResponseShapeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SaveDraftResponse.Line line(String tempId) {
        SaveDraftResponse.Line l = new SaveDraftResponse.Line();
        l.id = UUID.randomUUID();
        l.tempId = tempId;
        l.partVersionLocked = 2000;
        l.quoteCardValues = null;        // 被 D-1 失效的行就是 null，必须照样出现
        l.costingCardValues = null;
        l.quoteExcelValues = "{\"rows\":[]}";
        l.costingExcelValues = "{\"rows\":[]}";
        return l;
    }

    private static List<String> keysOf(SaveDraftResponse.Line l) throws Exception {
        JsonNode n = MAPPER.readTree(MAPPER.writeValueAsString(l));
        List<String> keys = new ArrayList<>();
        n.fieldNames().forEachRemaining(keys::add);
        java.util.Collections.sort(keys);
        return keys;
    }

    @Test
    @DisplayName("modified 行（tempId=null）→ 恰好 6 个键，且两个 CardValues 以 null 出现")
    void modifiedLineHasExactlySixKeys() throws Exception {
        List<String> keys = keysOf(line(null));
        assertEquals(List.of("costingCardValues", "costingExcelValues", "id",
                             "partVersionLocked", "quoteCardValues", "quoteExcelValues"), keys,
                "AC-16：modified 行必须恰好这 6 个键。多出 tempId ⇒ T-16 红；"
              + "少了 quoteCardValues/costingCardValues ⇒ 说明 NON_NULL 被误加到类上。");

        JsonNode n = MAPPER.readTree(MAPPER.writeValueAsString(line(null)));
        assertTrue(n.get("quoteCardValues").isNull(), "被失效的行，quoteCardValues 应作为 null 回传");
        assertTrue(n.get("costingCardValues").isNull(), "costingCardValues 同理");
    }

    @Test
    @DisplayName("added 行（tempId 非空）→ 7 个键，第 7 个是 tempId（新行认领 id 的唯一手段）")
    void addedLineCarriesTempId() throws Exception {
        List<String> keys = keysOf(line("tmp-abc-123"));
        assertEquals(7, keys.size(), "AC-2/AC-17：added 行必须多带 tempId，实际键集：" + keys);
        assertTrue(keys.contains("tempId"),
                "🚫 不许为了凑 6 键把 added 行的 tempId 砍掉 —— 那会让新行永远拿不到 DB id，"
              + "下一次保存按 id=null 再插一遍，库里出现重复行（AC-17）。");
        assertEquals("tmp-abc-123",
                MAPPER.readTree(MAPPER.writeValueAsString(line("tmp-abc-123"))).get("tempId").asText(),
                "tempId 必须原样回传（前端按它认领，不按数组下标）");
    }

    @Test
    @DisplayName("响应体不含 componentData（那 9.3 MB 前端一个字节都不读）")
    void noComponentDataAnywhere() throws Exception {
        SaveDraftResponse r = new SaveDraftResponse();
        r.id = UUID.randomUUID();
        r.userDataVersion = 42;
        r.lineItems = List.of(line("t1"), line(null));
        String json = MAPPER.writeValueAsString(r);
        assertFalse(json.contains("componentData"),
                "AC-15/AC-16：响应体不得出现 componentData —— 它占 9.3 MB 且从未被前端读取");
        assertTrue(json.contains("\"userDataVersion\":42"),
                "AC-11：新版本号必须回传，前端拿它更新本地并发基线");
    }
}
