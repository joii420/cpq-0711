package com.cpq.task260907productfilter;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>T-B7 · AC-11</b>（导入 → 切客户 → 再看，数据各自完整）—— <b>序列类用例</b>。
 *
 * <p>需求文档.md §③ AC-11：
 * ① 以客户 A 导入含 X 的报价数据 → ② 壳页选 A，确认列表与抽屉都有该料号 →
 * ③ 以客户 B 导入同一料号 → ④ 壳页切到 B 看 → ⑤ 再切回 A 看。
 * 断言：④ 显示 B 的数据；⑤ 🚨 A 的数据仍完整存在、未被第二次导入删除。
 *
 * <p>🔑 <b>只断言④会漏判静默删除</b>——④ 在 A 被删掉之后照样成立，这是需求文档原文点名的坑。
 * 本类特意把 ⑤ 写成独立、不可跳过的断言步骤。
 *
 * <p>⚠️ <b>范围声明（诚实边界）</b>：真正的「导入」由 {@code VersionedGroupWriter} /
 * Excel 解析流水线完成，那是上游任务 {@code task-260907-报价侧加客户维度} 与既有导入链路的实现细节，
 * 本任务被禁止读取实现代码，也不应该重新发明一遍导入语义。本类用<b>直接写库</b>模拟
 * 「客户 A 已导入 X」「客户 B 后来也导入了同一料号 X」这两个<b>已完成状态</b>，
 * 只验证本任务范围内的<b>读端点</b>是否会因为客户 B 的写入而误删客户 A 的行——
 * 这正是 AC-11 里「本任务」应该对之负责的那一部分（跨客户隔离的读取正确性），
 * 而不是「导入解析是否正确」（不在本任务范围）。
 */
@DisplayName("task-260907-产品管理客户过滤 · AC-11 导入→切客户→再看（序列）")
@QuarkusTest
class Ac11ImportSwitchSequenceTest extends PfTestBase {

    private static final String X = FX + "SEQX11";

    @BeforeEach
    void setUpGate() {
        assumeMaterialUniqueIndexComposite("AC-11");
    }

    @Test
    @DisplayName("AC-11①②③④⑤：A 导入 X → 确认可见 → B 导入同料号 X → 切到 B 可见 → 切回 A 仍完整存在")
    void crossCustomerImportDoesNotDeleteOtherCustomersData() {
        // ①：模拟客户 A「已导入」X
        insertMaterialRow(X, CUST_A, FX + "PRODA11-STEP1");

        // ②：确认 A 视角下可见
        Response step2 = PfApi.parts(adminSession(), PfApi.QUOTE, CUST_A, 0, 500, X);
        assertEquals(200, step2.statusCode(), "步骤②：body=" + step2.asString());
        List<Map<String, Object>> items2 = step2.jsonPath().getList("data.items");
        assertNotNull(items2, "步骤②：响应缺 data.items");
        assertFalse(items2.isEmpty(), "步骤②：客户 A 导入后应能看到 " + X + "，实际为空 ⇒ 前置条件都没满足");
        System.out.println("[AC-11②] 客户 A 可见：" + items2);

        // ③：模拟客户 B「后来也导入」了同一料号 X（复合唯一索引下这是新增一行，不是覆盖 A 的行）
        insertMaterialRow(X, CUST_B, FX + "PRODB11-STEP3");

        // ④：切到 B，应显示 B 的数据
        Response step4 = PfApi.parts(adminSession(), PfApi.QUOTE, CUST_B, 0, 500, X);
        assertEquals(200, step4.statusCode(), "步骤④：body=" + step4.asString());
        List<Map<String, Object>> items4 = step4.jsonPath().getList("data.items");
        assertNotNull(items4, "步骤④：响应缺 data.items");
        assertFalse(items4.isEmpty(), "步骤④：客户 B 导入后应能看到 " + X + "，实际为空");
        assertTrue(items4.stream().anyMatch(it -> CUST_B.equals(String.valueOf(it.get("customerNo")))),
                "步骤④：客户 B 视角下应能看到自己的 " + X + " 行，实际=" + items4);
        System.out.println("[AC-11④] 客户 B 可见：" + items4);

        // ⑤ 🚨 关键步骤：切回 A，A 的数据必须仍然完整存在（不是被 B 的"导入"顺手删掉）
        Response step5 = PfApi.parts(adminSession(), PfApi.QUOTE, CUST_A, 0, 500, X);
        assertEquals(200, step5.statusCode(), "步骤⑤：body=" + step5.asString());
        List<Map<String, Object>> items5 = step5.jsonPath().getList("data.items");
        assertNotNull(items5, "步骤⑤：响应缺 data.items");
        assertFalse(items5.isEmpty(), "步骤⑤🚨：切回客户 A 后 " + X + " 不见了！这正是 AC-11 要防的静默删除 —— "
                + "客户 B 的写入把客户 A 的行删掉了。仅断言④能看到 B 抓不住这个 bug，必须做⑤这一步");
        Map<String, Object> rowA = items5.stream()
                .filter(it -> CUST_A.equals(String.valueOf(it.get("customerNo"))))
                .findFirst().orElse(null);
        assertNotNull(rowA, "步骤⑤🚨：客户 A 视角下找不到 customerNo=" + CUST_A + " 的行，实际=" + items5);
        assertEquals(FX + "PRODA11-STEP1", String.valueOf(rowA.get("productionNo")),
                "步骤⑤：客户 A 的行内容应与步骤①写入时一致（未被覆盖/未被误改），实际=" + rowA);
        System.out.println("[AC-11⑤] ✅ 客户 A 数据仍完整存在：" + rowA);
    }
}
