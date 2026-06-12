package com.cpq.formula;

import com.cpq.component.formula.TokenMappabilityValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 6: TokenMappabilityValidator KSUM 镜像规则单测（白名单/J/I2/M + 空 match 豁免）。
 *
 * <p>直接构造非法 token 调 {@link TokenMappabilityValidator#validate(List)}，断言拒绝/放行。
 *
 * <p>覆盖场景：
 * <ol>
 *   <li>inner 含宿主列(b_field) → 拒</li>
 *   <li>K 套 K (targetExpr 含嵌套 projectToHostKey cross_tab_ref) → 拒</li>
 *   <li>顶层裸 projectToHostKey (M) → 拒</li>
 *   <li>I2 同页签 KSUM + 裸引 → 拒</li>
 *   <li>inner 含 path (全局变量) → 不拒（防回归误拒）</li>
 *   <li>合法 KSUM 子 token(match 非空, field.source 一致) → 不拒</li>
 *   <li>外层 KSUM 容器 match=[] → 豁免（不因空 match 拒）</li>
 *   <li>多 source 外层 match=[] → 豁免</li>
 *   <li>KSUM 子 token match 为空 → 拒（不豁免）</li>
 *   <li>普通 cross_tab_ref match 空（无 KSUM/多 source）→ 仍拒</li>
 * </ol>
 */
@DisplayName("TokenMappabilityValidator — KSUM 镜像规则")
class TokenMappabilityValidatorKsumTest {

    private final TokenMappabilityValidator validator = new TokenMappabilityValidator();

    private static final String SOURCE_A = "comp-aaa";
    private static final String SOURCE_B = "comp-bbb";

    // ------------------------------------------------------------------
    // 辅助构造方法
    // ------------------------------------------------------------------

    /** 构造合法 KSUM 子 token（projectToHostKey=true, match 非空, targetExpr 全白名单）。 */
    private Map<String, Object> validKsumSubToken(String source) {
        Map<String, Object> t = new HashMap<>();
        t.put("type", "cross_tab_ref");
        t.put("projectToHostKey", true);
        t.put("source", source);
        t.put("agg", "SUM");
        // match 非空（宿主行键约束）
        t.put("match", List.of(Map.of("a", "料件", "b", "料件")));
        // targetExpr: 合法白名单 — field(费用)
        Map<String, Object> fieldToken = new HashMap<>();
        fieldToken.put("type", "field");
        fieldToken.put("value", "费用");
        fieldToken.put("source", source); // source 一致
        t.put("targetExpr", List.of(fieldToken));
        return t;
    }

    /**
     * 构造外层 KSUM 容器 token（match=[], targetExpr 内含 KSUM 子 token）。
     * 外层 match=[] 应被豁免（isKsumContainer=true）。
     */
    private Map<String, Object> ksumContainerToken(Map<String, Object> ksumSubToken) {
        Map<String, Object> t = new HashMap<>();
        t.put("type", "cross_tab_ref");
        t.put("source", SOURCE_A);
        t.put("agg", "SUM");
        t.put("match", new ArrayList<>()); // 空 — 豁免
        List<Object> te = new ArrayList<>();
        Map<String, Object> fieldA = new HashMap<>();
        fieldA.put("type", "field");
        fieldA.put("value", "单价");
        te.add(fieldA);
        Map<String, Object> op = new HashMap<>();
        op.put("type", "operator");
        op.put("value", "+");
        te.add(op);
        te.add(ksumSubToken);
        t.put("targetExpr", te);
        return t;
    }

    // ------------------------------------------------------------------
    // T1: inner 含 b_field（宿主列）→ 拒（白名单不含 b_field）
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T1: KSUM inner targetExpr 含 b_field → 拒（白名单不含宿主列）")
    void inner_bField_rejected() {
        Map<String, Object> subToken = new HashMap<>(validKsumSubToken(SOURCE_B));
        Map<String, Object> bFieldToken = new HashMap<>();
        bFieldToken.put("type", "b_field");
        bFieldToken.put("value", "宿主列");
        subToken.put("targetExpr", List.of(bFieldToken));

        Map<String, Object> outer = ksumContainerToken(subToken);
        TokenMappabilityValidator.Result r = validator.validate(List.of(outer));

        assertFalse(r.mappable(), "b_field 在 KSUM inner 中应被拒绝");
        assertNotNull(r.reason());
    }

    // ------------------------------------------------------------------
    // T2: K 套 K (targetExpr 含嵌套 cross_tab_ref，J 规则) → 拒
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T2: KSUM inner targetExpr 含嵌套 cross_tab_ref（K 套 K，J 规则）→ 拒")
    void inner_nestedCrossTabRef_J_rejected() {
        // 构造 sub token，其 targetExpr 内含另一个 cross_tab_ref（含 projectToHostKey）
        Map<String, Object> subToken = new HashMap<>(validKsumSubToken(SOURCE_B));
        Map<String, Object> nestedCrossTab = new HashMap<>();
        nestedCrossTab.put("type", "cross_tab_ref");
        nestedCrossTab.put("projectToHostKey", true);
        nestedCrossTab.put("source", SOURCE_B);
        nestedCrossTab.put("agg", "SUM");
        nestedCrossTab.put("match", List.of(Map.of("a", "k", "b", "k")));
        subToken.put("targetExpr", List.of(nestedCrossTab));

        Map<String, Object> outer = ksumContainerToken(subToken);
        TokenMappabilityValidator.Result r = validator.validate(List.of(outer));

        assertFalse(r.mappable(), "KSUM inner 含嵌套 cross_tab_ref 应被拒绝（J 规则）");
        assertNotNull(r.reason());
    }

    // ------------------------------------------------------------------
    // T2b: targetExpr 内非 projectToHostKey 的 cross_tab_ref → 同样拒（J 规则）
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T2b: KSUM outer targetExpr 含普通 cross_tab_ref（非 KSUM 子 token，J 规则）→ 拒")
    void outer_targetExpr_nonProj_crossTabRef_J_rejected() {
        Map<String, Object> subToken = validKsumSubToken(SOURCE_B);

        // 构造外层，targetExpr 里还额外放一个普通 cross_tab_ref（proj=false）
        Map<String, Object> normalCrossTab = new HashMap<>();
        normalCrossTab.put("type", "cross_tab_ref");
        // projectToHostKey 缺失 / false → J 规则拒
        normalCrossTab.put("source", SOURCE_A);
        normalCrossTab.put("agg", "SUM");
        normalCrossTab.put("match", List.of(Map.of("a", "k", "b", "k")));

        Map<String, Object> outer = new HashMap<>();
        outer.put("type", "cross_tab_ref");
        outer.put("source", SOURCE_A);
        outer.put("agg", "SUM");
        outer.put("match", new ArrayList<>());
        outer.put("targetExpr", List.of(subToken, normalCrossTab));

        TokenMappabilityValidator.Result r = validator.validate(List.of(outer));
        assertFalse(r.mappable(), "outer targetExpr 含普通 cross_tab_ref(proj=false) 应被拒绝（J 规则）");
    }

    // ------------------------------------------------------------------
    // T3: 顶层裸 projectToHostKey=true（M 规则）→ 拒
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T3: 顶层裸 projectToHostKey=true（M 规则）→ 拒")
    void topLevel_bareProjectToHostKey_M_rejected() {
        Map<String, Object> bareKsum = validKsumSubToken(SOURCE_B);
        // bareKsum 含 projectToHostKey=true，直接放到顶层 expr

        TokenMappabilityValidator.Result r = validator.validate(List.of(bareKsum));
        assertFalse(r.mappable(), "顶层裸 projectToHostKey=true 应被拒绝（M 规则）");
        assertTrue(r.reason().contains("M") || r.reason().contains("顶层"),
            "错误信息应提及 M 规则或顶层: " + r.reason());
    }

    // ------------------------------------------------------------------
    // T4: I2 同页签 KSUM + 裸引（外层裸引用 source 与 KSUM 子 source 重叠）→ 拒
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T4: I2 同页签 KSUM + 裸引（source 重叠）→ 拒")
    void I2_sameSourceKsumAndBareRef_rejected() {
        // 外层裸引用 SOURCE_B
        Map<String, Object> bareRef = new HashMap<>();
        bareRef.put("type", "cross_tab_ref");
        bareRef.put("source", SOURCE_B);
        bareRef.put("agg", "SUM");
        bareRef.put("match", List.of(Map.of("a", "k", "b", "k")));
        Map<String, Object> fieldB = new HashMap<>();
        fieldB.put("type", "field");
        fieldB.put("value", "费用");
        bareRef.put("targetExpr", List.of(fieldB));

        // 同时 KSUM 容器的子 token source 也是 SOURCE_B → I2 冲突
        Map<String, Object> subToken = validKsumSubToken(SOURCE_B);
        Map<String, Object> container = ksumContainerToken(subToken);

        TokenMappabilityValidator.Result r = validator.validate(List.of(bareRef, container));
        assertFalse(r.mappable(), "I2: 外层裸引用与 KSUM 子 token 引用同一页签应被拒绝");
        assertTrue(r.reason().contains("I2") || r.reason().contains("重叠"),
            "错误信息应提及 I2 或重叠: " + r.reason());
    }

    // ------------------------------------------------------------------
    // T5: inner 含 path（全局变量）→ 不拒（白名单包含 path，防回归误拒）
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T5: KSUM inner targetExpr 含 path（全局变量 token）→ 放行（白名单含 path）")
    void inner_pathToken_allowed() {
        Map<String, Object> subToken = new HashMap<>(validKsumSubToken(SOURCE_B));
        Map<String, Object> pathToken = new HashMap<>();
        pathToken.put("type", "path");
        pathToken.put("path", "{v_global.汇率}");
        Map<String, Object> fieldToken = new HashMap<>();
        fieldToken.put("type", "field");
        fieldToken.put("value", "费用");
        fieldToken.put("source", SOURCE_B);
        subToken.put("targetExpr", List.of(fieldToken, new HashMap<>(Map.of("type", "operator", "value", "*")), pathToken));

        Map<String, Object> outer = ksumContainerToken(subToken);
        TokenMappabilityValidator.Result r = validator.validate(List.of(outer));

        assertTrue(r.mappable(), "path token（全局变量）在 KSUM inner 中应被放行，实际 reason: " + r.reason());
    }

    // ------------------------------------------------------------------
    // T6: 合法 KSUM 子 token（match 非空, field.source 一致）→ 不拒
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T6: 合法 KSUM 子 token（match 非空, field.source 一致）→ 通过")
    void validKsumSubToken_passes() {
        Map<String, Object> subToken = validKsumSubToken(SOURCE_B);
        Map<String, Object> outer = ksumContainerToken(subToken);
        TokenMappabilityValidator.Result r = validator.validate(List.of(outer));

        assertTrue(r.mappable(), "合法 KSUM 子 token 应通过，实际 reason: " + r.reason());
    }

    // ------------------------------------------------------------------
    // T7: 外层 KSUM 容器 match=[] → 豁免（不因空 match 拒绝）
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T7: 外层 KSUM 容器 match=[] → 豁免（isKsumContainer=true）")
    void ksumContainer_emptyMatch_exempted() {
        Map<String, Object> subToken = validKsumSubToken(SOURCE_B);
        // 构造 outer，match 明确为空列表
        Map<String, Object> outer = new HashMap<>();
        outer.put("type", "cross_tab_ref");
        outer.put("source", SOURCE_A);
        outer.put("agg", "SUM");
        outer.put("match", new ArrayList<>()); // 空 → 豁免
        Map<String, Object> fieldA = new HashMap<>();
        fieldA.put("type", "field");
        fieldA.put("value", "单价");
        outer.put("targetExpr", List.of(fieldA, subToken));

        TokenMappabilityValidator.Result r = validator.validate(List.of(outer));
        assertTrue(r.mappable(), "KSUM 容器外层 match=[] 应豁免，实际 reason: " + r.reason());
    }

    // ------------------------------------------------------------------
    // T8: 多 source 外层 match=[] → 豁免（isMultiSource=true）
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T8: 多 source 外层 match=[] → 豁免（sources.length>=2）")
    void multiSource_emptyMatch_exempted() {
        Map<String, Object> outer = new HashMap<>();
        outer.put("type", "cross_tab_ref");
        outer.put("source", SOURCE_A);
        outer.put("agg", "SUM");
        outer.put("match", new ArrayList<>()); // 空 → 豁免（多 source）
        Map<String, Object> fieldA = new HashMap<>();
        fieldA.put("type", "field");
        fieldA.put("value", "单价");
        outer.put("targetExpr", List.of(fieldA));
        // sources 长度 >= 2
        outer.put("sources", List.of(
            Map.of("source", SOURCE_A, "match", List.of(Map.of("a", "料件", "b", "料件"))),
            Map.of("source", SOURCE_B, "match", List.of(Map.of("a", "料件", "b", "料件")))
        ));

        TokenMappabilityValidator.Result r = validator.validate(List.of(outer));
        assertTrue(r.mappable(), "多 source 外层 match=[] 应豁免，实际 reason: " + r.reason());
    }

    // ------------------------------------------------------------------
    // T9: KSUM 子 token match 为空 → 拒（无豁免）
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T9: KSUM 子 token match 为空 → 拒（无豁免）")
    void ksumSubToken_emptyMatch_rejected() {
        Map<String, Object> subToken = validKsumSubToken(SOURCE_B);
        subToken.put("match", new ArrayList<>()); // 子 token match 清空

        Map<String, Object> outer = ksumContainerToken(subToken);
        TokenMappabilityValidator.Result r = validator.validate(List.of(outer));

        assertFalse(r.mappable(), "KSUM 子 token match=[] 应被拒绝（无豁免）");
        assertNotNull(r.reason());
    }

    // ------------------------------------------------------------------
    // T10: 普通 cross_tab_ref match 空（无 KSUM/多 source）→ 仍拒
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T10: 普通 cross_tab_ref match=[] （非 KSUM 容器）→ 仍拒")
    void normalCrossTabRef_emptyMatch_stillRejected() {
        Map<String, Object> t = new HashMap<>();
        t.put("type", "cross_tab_ref");
        t.put("source", SOURCE_A);
        t.put("agg", "SUM");
        t.put("match", new ArrayList<>()); // 空，无 KSUM 豁免
        Map<String, Object> fieldA = new HashMap<>();
        fieldA.put("type", "field");
        fieldA.put("value", "单价");
        t.put("targetExpr", List.of(fieldA)); // 无 KSUM 子 token

        TokenMappabilityValidator.Result r = validator.validate(List.of(t));
        assertFalse(r.mappable(), "普通 cross_tab_ref match=[] 应仍然被拒绝");
        assertNotNull(r.reason());
    }

    // ------------------------------------------------------------------
    // T11: inner targetExpr 中 field.source 与子 token source 不一致 → 拒（跨页签）
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T11: KSUM inner field.source 与子 token source 不一致 → 拒（跨页签）")
    void inner_fieldSourceMismatch_rejected() {
        Map<String, Object> subToken = new HashMap<>(validKsumSubToken(SOURCE_B));
        // 构造 targetExpr 内 field.source = SOURCE_A（与子 token source=SOURCE_B 不一致）
        Map<String, Object> wrongSourceField = new HashMap<>();
        wrongSourceField.put("type", "field");
        wrongSourceField.put("value", "费用");
        wrongSourceField.put("source", SOURCE_A); // 跨页签
        subToken.put("targetExpr", List.of(wrongSourceField));

        Map<String, Object> outer = ksumContainerToken(subToken);
        TokenMappabilityValidator.Result r = validator.validate(List.of(outer));

        assertFalse(r.mappable(), "KSUM inner field.source 跨页签应被拒绝");
        assertNotNull(r.reason());
    }

    // ------------------------------------------------------------------
    // T12: inner 含 component_subtotal → 拒（白名单不含）
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T12: KSUM inner targetExpr 含 component_subtotal → 拒（白名单不含）")
    void inner_componentSubtotal_rejected() {
        Map<String, Object> subToken = new HashMap<>(validKsumSubToken(SOURCE_B));
        Map<String, Object> csToken = new HashMap<>();
        csToken.put("type", "component_subtotal");
        csToken.put("component_code", "COMP_X");
        subToken.put("targetExpr", List.of(csToken));

        Map<String, Object> outer = ksumContainerToken(subToken);
        TokenMappabilityValidator.Result r = validator.validate(List.of(outer));

        assertFalse(r.mappable(), "component_subtotal 在 KSUM inner 中应被拒绝");
        assertNotNull(r.reason());
    }

    // ------------------------------------------------------------------
    // T13: 合法普通 cross_tab_ref（match 非空，无 KSUM）→ 通过（回归保护）
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T13: 合法普通 cross_tab_ref（match 非空）→ 通过（回归保护）")
    void normalCrossTabRef_nonEmptyMatch_passes() {
        Map<String, Object> t = new HashMap<>();
        t.put("type", "cross_tab_ref");
        t.put("source", SOURCE_A);
        t.put("agg", "SUM");
        t.put("match", List.of(Map.of("a", "料件", "b", "料件")));
        Map<String, Object> fieldA = new HashMap<>();
        fieldA.put("type", "field");
        fieldA.put("value", "单价");
        t.put("targetExpr", List.of(fieldA));

        TokenMappabilityValidator.Result r = validator.validate(List.of(t));
        assertTrue(r.mappable(), "合法普通 cross_tab_ref 应通过");
    }
}
