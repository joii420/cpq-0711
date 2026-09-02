package com.cpq.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260901 B-5b — {@link JsonSemanticEquality} 单测（AC-9 / AC-10）。
 *
 * <p>纯 JUnit，不需要数据库：这是整条链路上唯一「判错就静默丢用户编辑」的判定函数，
 * 值得单独钉死。样本取自 dev 库基准单 {@code QT-20260830-0210} 的真实 {@code row_data}。
 */
@DisplayName("JsonSemanticEqualityTest — saveDraft「这一行到底变没变」的判定")
class JsonSemanticEqualityTest {

    /** 库里 jsonb 读回来的形态：键按 UTF-8 字节长度重排 + ": " / ", " 空格。 */
    private static final String PG_CANONICAL =
        "[{\"row_index\": 0, \"材料成本\": \"0\"}, "
      + "{\"单位\": \"g\", \"料件\": \"AgNi11#-Ⅰ\", \"row_index\": 1, \"损耗率\": 0, "
      + "\"材料净重\": 1000, \"材料成本\": \"0\", \"材料毛重\": 1, \"组成数量\": 1}]";

    /** 前端 JSON.stringify 的形态：插入序、无空格。与上面语义完全相同。 */
    private static final String FRONTEND_STRINGIFY =
        "[{\"材料成本\":\"0\",\"row_index\":0},"
      + "{\"料件\":\"AgNi11#-Ⅰ\",\"单位\":\"g\",\"组成数量\":1,\"材料毛重\":1,\"材料净重\":1000,"
      + "\"损耗率\":0,\"材料成本\":\"0\",\"row_index\":1}]";

    @Test
    @DisplayName("AC-9：键序不同 + 空格不同，语义相同 → 判「未变」")
    void keyOrderAndWhitespaceDoNotCount() {
        assertNotEquals(PG_CANONICAL, FRONTEND_STRINGIFY,
                "前置：两个串必须是文本不等的，否则本用例证明不了任何事");
        assertTrue(JsonSemanticEquality.equal(PG_CANONICAL, FRONTEND_STRINGIFY),
                "PG 规范化文本与前端 stringify 语义相同，必须判为未变——"
              + "判成「变了」就等于本次优化白做（每行每次都 UPDATE）");
    }

    @Test
    @DisplayName("AC-10：只差最后一位小数 → 判「已变」")
    void lastDecimalDigitCounts() {
        String a = "[{\"材料净重\":\"3.3\"}]";
        String b = "[{\"材料净重\":\"3.4\"}]";
        assertFalse(JsonSemanticEquality.equal(a, b), "3.3 vs 3.4 必须判为已变");

        String p = "[{\"x\":\"1.234567890\"}]";
        String qq = "[{\"x\":\"1.234567891\"}]";
        assertFalse(JsonSemanticEquality.equal(p, qq), "末位小数不同必须判为已变");
    }

    @Test
    @DisplayName("AC-10 延伸：末尾零（3.30 vs 3.3）判「已变」——有效位数是业务信息，宁可多写一次")
    void trailingZeroScaleCounts() {
        assertFalse(JsonSemanticEquality.equal("[{\"x\":3.30}]", "[{\"x\":3.3}]"),
                "数值型 token 的 scale 差异必须判为已变（失败方向必须是多写而不是漏写）");
        assertFalse(JsonSemanticEquality.equal("[{\"x\":\"3.30\"}]", "[{\"x\":\"3.3\"}]"),
                "字符串型小数同理");
    }

    @Test
    @DisplayName("失败方向：null / 非法 JSON 一律判「已变」，绝不能反过来")
    void failClosed() {
        assertFalse(JsonSemanticEquality.equal(null, "[]"), "任一侧 null → 已变");
        assertFalse(JsonSemanticEquality.equal("[]", null), "任一侧 null → 已变");
        assertFalse(JsonSemanticEquality.equal(null, null), "两侧都 null → 仍按已变（判不准就当变了）");
        assertFalse(JsonSemanticEquality.equal("[{", "[{"), "非法 JSON → 已变");
        assertFalse(JsonSemanticEquality.equal("", "[]"), "空串 → 已变");
        assertFalse(JsonSemanticEquality.equal("[] trailing", "[]"), "尾部有残余 → 已变");
    }

    @Test
    @DisplayName("嵌套结构 / 数组顺序 / 类型差异")
    void structuralCases() {
        assertTrue(JsonSemanticEquality.equal(
                "{\"a\":{\"x\":1,\"y\":2},\"b\":[1,2]}",
                "{\"b\":[1,2],\"a\":{\"y\":2,\"x\":1}}"),
                "嵌套对象的键序同样不算差异");
        assertFalse(JsonSemanticEquality.equal("[1,2]", "[2,1]"),
                "数组是有序的，顺序不同 = 已变");
        assertFalse(JsonSemanticEquality.equal("{\"x\":1}", "{\"x\":\"1\"}"),
                "数字 1 与字符串 \"1\" 类型不同 = 已变（报价链路对 token 类型敏感）");
        assertTrue(JsonSemanticEquality.equal("[{}]", "[{}]"), "空对象数组（真实数据里大量存在）");
    }

    @Test
    @DisplayName("快路径：文本完全相同直接返回 true（不解析）")
    void identicalTextFastPath() {
        assertTrue(JsonSemanticEquality.equal(PG_CANONICAL, PG_CANONICAL));
    }
}
