package com.cpq.quotation.task260901;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.cpq.quotation.task260901.Task260901Support.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260901 · B. 后端只动该动的（需求文档 §③ B）—— T-9 / T-10 / T-6 / T-7
 *
 * <p>🚫 用例从 AC 原文与 {@code api.md} 契约派生，不读实现代码。
 *
 * <p><b>T-9 与 T-10 必须成对</b>（test.md §2）：T-9 单独绿没有意义 —— 一个「永远判未变」的
 * 错误实现也能让它绿。T-10（任一数值差最后一位小数 → 判「已变」）是它的对照组。
 * 两条用例的输入只差「材料净重」的最后一位小数，期望**相反**。
 */
@QuarkusTest
@TestProfile(Task260901RbacOffProfile.class)   // 见该类注释：不关 RBAC 则全包 401
@DisplayName("task-260901 语义比对（AC-6/7/9/10）")
class Task260901SemanticDiffHttpTest {

    @Inject
    EntityManager em;

    /** AC-16 列的 6 个键 —— 按 api.md §1.3 收敛表，这是 **modified 行**的完整键集。 */
    private static final List<String> MODIFIED_LINE_KEYS = List.of(
            "id", "partVersionLocked", "quoteCardValues",
            "costingCardValues", "quoteExcelValues", "costingExcelValues");

    /** added 行的键集 = 上面 6 个 + tempId（第 7 键，新行认领 DB id 的唯一手段）。 */
    private static final List<String> ADDED_LINE_KEYS = List.of(
            "id", "partVersionLocked", "quoteCardValues",
            "costingCardValues", "quoteExcelValues", "costingExcelValues", "tempId");

    private final List<Task260901HttpFixture> fixtures = new ArrayList<>();

    @AfterEach
    void cleanup() {
        fixtures.forEach(Task260901HttpFixture::cleanup);
        fixtures.clear();
    }

    private Task260901HttpFixture fixture(String label) {
        Task260901HttpFixture f = Task260901HttpFixture.create(em, label);
        fixtures.add(f);
        return f;
    }

    private <T> T read(java.util.function.Supplier<T> s) {
        return QuarkusTransaction.requiringNew().call(() -> { em.clear(); return s.get(); });
    }

    // ══════════════════════════════════════════════════════════════════
    // T-9 / AC-9（反向）
    // AC 原文：「人为构造一行 row_data 语义相同但键顺序不同的 payload（模拟 PG 规范化差异），
    //           后端判定为『未变』，该行不产生 UPDATE。」
    // ══════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("T-9/AC-9：row_data 语义相同、键顺序不同 → 判『未变』，该行不产生 UPDATE、卡片值不被清空")
    void ac9_semanticallyEqualRowDataWithDifferentKeyOrder_isTreatedAsUnchanged() {
        Task260901HttpFixture f = fixture("ac9");

        String dbTextBefore = read(() -> rowDataText(em, f.cdAId));
        String xminBefore   = read(() -> xminOfComponentData(em, f.cdAId));
        String lineXminBefore = read(() -> xminOfLineItem(em, f.lineAId));
        String quoteCvBefore = read(() -> cardValues(em, f.lineAId, "quote_card_values"));
        String costCvBefore  = read(() -> cardValues(em, f.lineAId, "costing_card_values"));

        // ── 分辨力守卫（🚨 缺了这条，T-9 就可能在"两串本来就一样"的情况下白拿一个绿）──
        assertNotNull(dbTextBefore, "前置：库中 row_data 应存在");
        assertNotNull(xminBefore, "前置：应能读到 componentData 的 xmin");
        assertNotNull(quoteCvBefore, "前置：quote_card_values 应为非空哨兵（否则『没被清空』无从判定）");
        assertTrue(quoteCvBefore.contains("KEEP-ME"), "前置：quote_card_values 应含哨兵，实际：" + quoteCvBefore);
        assertNotEquals(Task260901HttpFixture.ROW_DATA_A_REORDERED, dbTextBefore,
                "🚨 本用例的前提是「payload 文本 ≠ 库中文本」。两者若逐字相同，字符串比对也能过，" +
                "T-9 就没有分辨力了。实际库中文本：" + dbTextBefore);

        long v0 = read(() -> userDataVersion(em, f.quotationId));
        String body = draftBody(v0, null, "[]",
                "[" + modifiedLine(f.lineAId, f.quoteTemplateId, 0, f.componentId,
                        Task260901HttpFixture.TAB_NAME, Task260901HttpFixture.ROW_DATA_A_REORDERED) + "]",
                "[]");
        Response r = putDraft(f.quotationId, body);
        ok(r, "AC-9 的 PUT /draft");

        String dbTextAfter = read(() -> rowDataText(em, f.cdAId));
        String xminAfter   = read(() -> xminOfComponentData(em, f.cdAId));
        String lineXminAfter = read(() -> xminOfLineItem(em, f.lineAId));
        String quoteCvAfter = read(() -> cardValues(em, f.lineAId, "quote_card_values"));
        String costCvAfter  = read(() -> cardValues(em, f.lineAId, "costing_card_values"));

        assertEquals(dbTextBefore, dbTextAfter,
                "AC-9：语义相同的 payload 不应改变库中 row_data。前=" + dbTextBefore + " 后=" + dbTextAfter);
        assertEquals(xminBefore, xminAfter,
                "AC-9：『该行不产生 UPDATE』—— componentData 的 xmin 变了说明这行被写过。前=" + xminBefore + " 后=" + xminAfter);
        assertEquals(quoteCvBefore, quoteCvAfter,
                "AC-9：未变的行不得清空 quote_card_values（清空 = 触发不必要的全量重算，正是本次要修的病灶）。后=" + quoteCvAfter);
        assertEquals(costCvBefore, costCvAfter,
                "AC-9：未变的行不得清空 costing_card_values。后=" + costCvAfter);
        // line item 本身可能因单头/总价被合法更新，这里只做诊断输出，不作硬判据
        System.out.println("[T-9][诊断] lineItem xmin " + lineXminBefore + " → " + lineXminAfter);
    }

    // ══════════════════════════════════════════════════════════════════
    // T-10 / AC-10（反向 —— T-9 的对照组）
    // AC 原文：「改动 row_data 中任意一个值（哪怕只差最后一位小数）后保存，该行必须被判定为『已变』
    //           并写入，卡片值被清空重算。」
    // ══════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("T-10/AC-10：row_data 只差最后一位小数 → 必须判『已变』，写入 + 卡片值置 NULL")
    void ac10_lastDigitDifference_isTreatedAsChanged() {
        Task260901HttpFixture f = fixture("ac10");

        String dbTextBefore = read(() -> rowDataText(em, f.cdAId));
        String xminBefore   = read(() -> xminOfComponentData(em, f.cdAId));
        assertNotNull(dbTextBefore, "前置：库中 row_data 应存在");
        assertTrue(dbTextBefore.contains("1000"), "前置：库中「材料净重」应是 1000，实际：" + dbTextBefore);
        assertNotNull(read(() -> cardValues(em, f.lineAId, "quote_card_values")),
                "前置：quote_card_values 应为非空哨兵（否则『被清空』无从判定）");

        // T-9 / T-10 的输入只差最后一位小数 —— 这就是这一对用例的分辨力所在
        assertNotEquals(Task260901HttpFixture.ROW_DATA_A_REORDERED,
                Task260901HttpFixture.ROW_DATA_A_LAST_DIGIT_DIFF,
                "T-9 与 T-10 的输入必须不同，否则两条用例是同一条");

        long v0 = read(() -> userDataVersion(em, f.quotationId));
        String body = draftBody(v0, null, "[]",
                "[" + modifiedLine(f.lineAId, f.quoteTemplateId, 0, f.componentId,
                        Task260901HttpFixture.TAB_NAME, Task260901HttpFixture.ROW_DATA_A_LAST_DIGIT_DIFF) + "]",
                "[]");
        ok(putDraft(f.quotationId, body), "AC-10 的 PUT /draft");

        String dbTextAfter = read(() -> rowDataText(em, f.cdAId));
        String xminAfter   = read(() -> xminOfComponentData(em, f.cdAId));
        String quoteCvAfter = read(() -> cardValues(em, f.lineAId, "quote_card_values"));
        String costCvAfter  = read(() -> cardValues(em, f.lineAId, "costing_card_values"));

        assertNotEquals(dbTextBefore, dbTextAfter, "AC-10：差一位小数也必须写入库。库中仍是：" + dbTextAfter);
        assertTrue(dbTextAfter != null && dbTextAfter.contains("1000.00000000001"),
                "AC-10：新值 1000.00000000001 必须落库，实际：" + dbTextAfter);
        assertNotEquals(xminBefore, xminAfter,
                "AC-10：该行必须真的被 UPDATE（xmin 应变化）。前=" + xminBefore + " 后=" + xminAfter);
        assertNull(quoteCvAfter, "AC-10：已变的行必须清空 quote_card_values 以触发重算，实际：" + quoteCvAfter);
        assertNull(costCvAfter, "AC-10：已变的行必须清空 costing_card_values，实际：" + costCvAfter);
    }

    // ══════════════════════════════════════════════════════════════════
    // T-6 / AC-6（小规模同型）
    // AC 原文（1845 行版在 E2E task260901-backend-effects.spec.ts）：
    //   「只有 1 条的 row_data 内容发生变化」。这里用 2 行 fixture 验同一条不变量：
    //   改 lineA，lineB 的 componentData 必须逐字节不变且 xmin 不变。
    // ══════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("T-6/AC-6：改 A 行 → B 行的 componentData 逐字节不变且未被 UPDATE")
    void ac6_untouchedLineComponentDataUnchanged() {
        Task260901HttpFixture f = fixture("ac6");

        String bTextBefore = read(() -> rowDataText(em, f.cdBId));
        String bXminBefore = read(() -> xminOfComponentData(em, f.cdBId));
        assertNotNull(bTextBefore, "前置：B 行 row_data 必须存在（为空则本用例空跑）");
        assertTrue(bTextBefore.contains("AgCu90"), "前置：B 行 row_data 应是 B 的数据，实际：" + bTextBefore);

        long v0 = read(() -> userDataVersion(em, f.quotationId));
        ok(putDraft(f.quotationId, draftBody(v0, null, "[]",
                "[" + modifiedLine(f.lineAId, f.quoteTemplateId, 0, f.componentId,
                        Task260901HttpFixture.TAB_NAME, Task260901HttpFixture.ROW_DATA_A_LAST_DIGIT_DIFF) + "]",
                "[]")), "AC-6 的 PUT /draft");

        assertEquals(bTextBefore, read(() -> rowDataText(em, f.cdBId)),
                "AC-6：未出现在 modified 里的 B 行，其 row_data 必须逐字节不变");
        assertEquals(bXminBefore, read(() -> xminOfComponentData(em, f.cdBId)),
                "AC-6：未变的行不得产生 UPDATE（B 行 componentData 的 xmin 变了）");
    }

    // ══════════════════════════════════════════════════════════════════
    // T-7 / AC-7（小规模同型）
    // AC 原文：「只有被改的那一行卡片值被清空」。
    // ══════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("T-7/AC-7：只有被改的那一行卡片值被置 NULL，未改的行保持原值")
    void ac7_onlyChangedLineCardValuesCleared() {
        Task260901HttpFixture f = fixture("ac7");

        // 🚨 非空守卫：两行的卡片值保存前都必须非空，否则"只有 1 行被置 NULL"没有分辨力
        assertNotNull(read(() -> cardValues(em, f.lineAId, "quote_card_values")), "前置：A 行 quote 卡片值应非空");
        assertNotNull(read(() -> cardValues(em, f.lineBId, "quote_card_values")), "前置：B 行 quote 卡片值应非空");
        assertNotNull(read(() -> cardValues(em, f.lineAId, "costing_card_values")), "前置：A 行 costing 卡片值应非空");
        assertNotNull(read(() -> cardValues(em, f.lineBId, "costing_card_values")), "前置：B 行 costing 卡片值应非空");
        String bQuoteBefore = read(() -> cardValues(em, f.lineBId, "quote_card_values"));
        String bCostBefore  = read(() -> cardValues(em, f.lineBId, "costing_card_values"));

        long v0 = read(() -> userDataVersion(em, f.quotationId));
        ok(putDraft(f.quotationId, draftBody(v0, null, "[]",
                "[" + modifiedLine(f.lineAId, f.quoteTemplateId, 0, f.componentId,
                        Task260901HttpFixture.TAB_NAME, Task260901HttpFixture.ROW_DATA_A_LAST_DIGIT_DIFF) + "]",
                "[]")), "AC-7 的 PUT /draft");

        long quoteNull = read(() -> count(em,
                "SELECT count(*) FROM quotation_line_item WHERE quotation_id=?1 AND quote_card_values IS NULL", f.quotationId));
        long costNull = read(() -> count(em,
                "SELECT count(*) FROM quotation_line_item WHERE quotation_id=?1 AND costing_card_values IS NULL", f.quotationId));

        assertEquals(1L, quoteNull, "AC-7：quote_card_values IS NULL 应恰为 1 行");
        assertEquals(1L, costNull, "AC-7：costing_card_values IS NULL 应恰为 1 行");
        assertNull(read(() -> cardValues(em, f.lineAId, "quote_card_values")), "AC-7：被清空的应是 A 行");
        assertEquals(bQuoteBefore, read(() -> cardValues(em, f.lineBId, "quote_card_values")),
                "AC-7：B 行 quote 卡片值必须逐字不变");
        assertEquals(bCostBefore, read(() -> cardValues(em, f.lineBId, "costing_card_values")),
                "AC-7：B 行 costing 卡片值必须逐字不变");
    }

    // ══════════════════════════════════════════════════════════════════
    // AC-16（响应键集，与规模无关，放后端更稳）
    // AC 原文：「响应中每个 line 元素只含这 6 个键：id、partVersionLocked、quoteCardValues、
    //           costingCardValues、quoteExcelValues、costingExcelValues …… 不含 componentData。」
    //
    // 🚨 断言对象**限定为 modified 行**（test.md §1 的 AC-16 行 + api.md §1.3「tempId 的作用域」
    //    收敛表）：added 行另有第 7 个键 tempId，那是新行认领 DB id 的唯一手段。
    //    若把「恰好 6 键」套到 added 行上，后端为过本用例就会砍掉 tempId ⇒ 新行拿不到 id ⇒
    //    下次保存重复插入 —— **正是 AC-17 要防的那件事**，两条 AC 会互相打死。
    //    本类用两个方法把这条作用域钉死：本方法守 modified 恰好 6 键，
    //    {@code ac16b_addedLineCarriesTempIdAsSeventhKey} 守 added 必须多带 tempId。
    // ══════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("T-16/AC-16：modified 行的响应元素恰好 6 个键，不含 tempId、不含 componentData")
    void ac16_modifiedLineKeysAreExactlyTheSix() {
        Task260901HttpFixture f = fixture("ac16");
        long v0 = read(() -> userDataVersion(em, f.quotationId));

        Response r = putDraft(f.quotationId, draftBody(v0, null, "[]",
                "[" + modifiedLine(f.lineAId, f.quoteTemplateId, 0, f.componentId,
                        Task260901HttpFixture.TAB_NAME, Task260901HttpFixture.ROW_DATA_A_LAST_DIGIT_DIFF) + "]",
                "[]"));
        JsonNode data = ok(r, "AC-16 的 PUT /draft");

        assertTrue(data.has("userDataVersion"), "api.md §1.3：响应根部必须带 userDataVersion。响应：" + r.asString());
        JsonNode lines = data.path("lineItems");
        assertTrue(lines.isArray(), "AC-16：data.lineItems 应为数组。响应：" + r.asString());
        assertEquals(1, lines.size(), "AC-16：只应回传 modified 的那 1 行。响应：" + r.asString());

        JsonNode line = lines.get(0);
        List<String> actual = new ArrayList<>();
        line.fieldNames().forEachRemaining(actual::add);
        assertEquals(f.lineAId.toString(), line.path("id").asText(),
                "AC-16：回传的行必须就是被改的那一行（本次 added 为空，故它必然是 modified 行）");
        assertEquals(MODIFIED_LINE_KEYS.size(), actual.size(),
                "AC-16：modified 行不应有多余键（恰好 " + MODIFIED_LINE_KEYS.size() + " 个）。实际键集 = " + actual);
        for (String k : MODIFIED_LINE_KEYS) {
            assertTrue(line.has(k), "AC-16：modified 行必须含键 " + k + "，实际键集 = " + actual);
        }
        assertFalse(line.has("tempId"),
                "api.md §1.3：tempId 是 added 行的专属键，modified 行不应带它（该行本来就有 DB id）。实际键集 = " + actual);
        assertFalse(line.has("componentData"),
                "AC-16：响应绝不能含 componentData（9.3 MB、前端一个字节都没读）。实际键集 = " + actual);
    }

    /**
     * AC-16 的**作用域对照组**（api.md §1.3 收敛表）：{@code added} 行必须比 {@code modified} 行
     * 多带一个 {@code tempId}。
     *
     * <p>🚨 没有这条，一个「对所有行都只回传 6 键」的实现能让 {@code ac16_...} 独绿，
     * 而新增行从此永远认领不到 DB id —— AC-17 会在很后面才炸，且症状是"重复插入"，
     * 排查时不会有人想到根因在 AC-16 的用例写宽了。
     */
    @Test
    @DisplayName("T-16 对照组：added 行必须多带第 7 个键 tempId（守住 AC-17 的认领链）")
    void ac16b_addedLineCarriesTempIdAsSeventhKey() {
        Task260901HttpFixture f = fixture("ac16b");
        long v0 = read(() -> userDataVersion(em, f.quotationId));
        String tempId = "tmp-" + java.util.UUID.randomUUID();

        Response r = putDraft(f.quotationId, draftBody(v0, null,
                "[" + addedLine(tempId, f.productBId, f.quoteTemplateId, 2, f.componentId,
                        Task260901HttpFixture.TAB_NAME, Task260901HttpFixture.ROW_DATA_B, "T260901-K7") + "]",
                "[]", "[]"));
        JsonNode data = ok(r, "AC-16 对照组的 PUT /draft");

        JsonNode lines = data.path("lineItems");
        assertTrue(lines.isArray() && lines.size() == 1,
                "前置：本次只 added 1 行，响应应恰好回传 1 行。响应：" + r.asString());
        JsonNode line = lines.get(0);
        List<String> actual = new ArrayList<>();
        line.fieldNames().forEachRemaining(actual::add);

        assertEquals(tempId, line.path("tempId").asText(null),
                "api.md §1.3 收敛表：added 行必须**原样回传 tempId**（新行认领 DB id 的唯一手段）。实际键集 = " + actual
                        + "。若这里为 null，多半是为了过「恰好 6 键」而把 added 行的 tempId 也砍了 —— 那会直接打死 AC-17。");
        assertEquals(ADDED_LINE_KEYS.size(), actual.size(),
                "api.md §1.3：added 行应恰好 " + ADDED_LINE_KEYS.size() + " 个键（6 + tempId）。实际键集 = " + actual);
        for (String k : ADDED_LINE_KEYS) {
            assertTrue(line.has(k), "api.md §1.3：added 行必须含键 " + k + "，实际键集 = " + actual);
        }
        assertFalse(line.has("componentData"),
                "AC-16：added 行同样不得含 componentData。实际键集 = " + actual);
        assertNotNull(line.path("id").asText(null), "added 行必须带 DB 生成的 id");
    }
}
