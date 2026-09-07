package com.cpq.builder.compiler;

import com.cpq.builder.exception.BuilderApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260819 · <b>B-53 护栏自身的常驻回归</b>（2026-09-04 主线复核回流 ②）。
 *
 * <h3>为什么必须有这个类</h3>
 * B-53 加的是一道「编译产物里 {@code :total_material_no} 只许被一种语义消费」的护栏。护栏防的是
 * <b>静默故障</b>（两条号段相反的谓词共存 ⇒ 恒 0 行、不抛异常、无诊断，即 B-52/D-119）。
 * 但护栏<b>自己</b>也可能无声死掉 —— 那时症状与它要防的一模一样：什么都不说。
 * ⇒ <b>一个自己会静默失效的护栏，只是把静默失效推迟了一层，没有消除。</b>
 * 所以「护栏还活着」必须有机械信号，这个类就是那个信号。
 *
 * <h3>🚨 共享库红线（CLAUDE.md §3.2）</h3>
 * 本类<b>刻意不是 {@code @QuarkusTest}</b>：驱动的是
 * {@link SemanticCompiler#checkAxisParamSingleSemantic} 这个<b>纯函数内核</b>，
 * 输入全是合成字符串 ⇒ <b>不启 Quarkus、不连库、不写任何一行数据</b>。
 * {@code test} profile 的默认库就是共享开发库 {@code cpq_db_0724}，能不碰就不碰。
 *
 * <h3>⚠️ 用例的取材纪律</h3>
 * 下面的 SQL <b>不是我编的</b>，是 2026-09-04 证伪实验里从真实编译产物逐字抄回来的
 * （{@code COST_BASIC} / 主件 / 锚点 {@code ds_cost_basic_material}）：
 * <ul>
 *   <li>{@link #BRIDGE_ONLY_SQL} —— 基线产物（护栏必须放行）；</li>
 *   <li>{@link #CONFLICT_SQL} —— 把 {@code applyFullScope} 里的 {@code !c.narrowedByBridge &&}
 *       人为删掉后编出来的产物。当时实测：{@code /preview} 返 <b>HTTP 200 + rowCount=0</b>，
 *       不抛异常 —— 这就是护栏要拦下的那一幕。</li>
 * </ul>
 */
class SemanticCompilerAxisNarrowGuardTest {

    // ---- 以下三段取自 2026-09-04 真实编译产物，勿改写措辞 ----

    /** 桥半连接谓词原文（{@code emitNarrowPredicate} 产，落 {@code anchorWhere}）。 */
    private static final String BRIDGE = "dcbm.production_no IN (SELECT dqm.production_no "
            + "FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no))";

    /** 直接轴谓词原文（{@code applyFullScope} 产）。核价侧轴列是 production_no。 */
    private static final String DIRECT = "dcbm.production_no = ANY(:total_material_no)";

    private static final String SELECT_FROM = """
            SELECT
              dcbm.production_no AS hf_part_no,
              dcbm.production_no AS "production_no"
            FROM ds_cost_basic_material dcbm
            """;

    /** ✅ 基线：只有桥。实测 preview rowCount=1。 */
    private static final String BRIDGE_ONLY_SQL =
            SELECT_FROM + "WHERE " + BRIDGE + "\nORDER BY dcbm.production_no";

    /** 🔴 B-52 复现：桥 + 直接轴谓词并列。实测 preview HTTP 200 但 rowCount=0，不报错。 */
    private static final String CONFLICT_SQL =
            SELECT_FROM + "WHERE " + BRIDGE + " AND " + DIRECT + "\nORDER BY dcbm.production_no";

    private static void check(List<String> anchorWhere, String sql) {
        SemanticCompiler.checkAxisParamSingleSemantic(anchorWhere, sql, "production_no",
                "COST_BASIC", "MATERIAL", "ds_cost_basic_material",
                List.of("1a0b8b69-23d6-593a-8924-cf7774dd228c"));
    }

    private static BuilderApiException expectReject(List<String> anchorWhere, String sql) {
        return assertThrows(BuilderApiException.class, () -> check(anchorWhere, sql));
    }

    // ================= 放行侧（护栏不许误报，否则正常编译全挂） =================

    @Test
    @DisplayName("放行①: 只有桥半连接（COST_BASIC 基线产物）")
    void bridgeOnly_passes() {
        assertDoesNotThrow(() -> check(List.of(BRIDGE), BRIDGE_ONLY_SQL));
    }

    @Test
    @DisplayName("放行②: 只有直接轴收窄、无桥（QUOTE 方言的常态）")
    void directAxisOnly_passes() {
        String sql = SELECT_FROM + "WHERE " + DIRECT + "\nORDER BY dcbm.production_no";
        assertDoesNotThrow(() -> check(List.of(DIRECT), sql));
    }

    @Test
    @DisplayName("放行③: 单锚点挂多条 NARROW 边 —— 入参出现多次是合法的，不许按次数误判")
    void multipleBridges_passes() {
        String bridge2 = "dcbm.plating_no IN (SELECT dqm2.plating_no "
                + "FROM ds_quote_material dqm2 WHERE dqm2.material_no = ANY(:total_material_no))";
        String sql = SELECT_FROM + "WHERE " + BRIDGE + " AND " + bridge2 + "\nORDER BY dcbm.production_no";
        assertDoesNotThrow(() -> check(List.of(BRIDGE, bridge2), sql));
    }

    // ================= 拦截侧 · 主目标（B-52 同型） =================

    @Test
    @DisplayName("拦截①: 桥 + 直接轴谓词共存 ⇒ CONFLICT，且点名两条谓词/方言/锚点/边 id")
    void bridgePlusDirectAxis_throwsConflict() {
        BuilderApiException ex = expectReject(List.of(BRIDGE), CONFLICT_SQL);

        assertEquals("COMPILE_AXIS_NARROW_CONFLICT", ex.getErrorCode());
        String msg = ex.getMessage();
        // 主线要求：异常信息必须点名「命中的两条谓词原文 + dialect + 锚点 + NARROW 边 id」
        assertTrue(msg.contains(DIRECT), "异常信息必须含直接轴谓词原文。实际=" + msg);
        assertTrue(msg.contains(BRIDGE), "异常信息必须含桥半连接原文。实际=" + msg);
        assertTrue(msg.contains("COST_BASIC"), "异常信息必须含 dialect。实际=" + msg);
        assertTrue(msg.contains("ds_cost_basic_material"), "异常信息必须含锚点。实际=" + msg);
        assertTrue(msg.contains("1a0b8b69-23d6-593a-8924-cf7774dd228c"),
                "异常信息必须含 NARROW 边 id。实际=" + msg);
        assertEquals(DIRECT, ex.getExtra().get("directAxisPredicate"));
    }

    @Test
    @DisplayName("拦截②: 桥被改写成 IN(SELECT 无空格 + 换行 —— 换个写法躲不掉")
    void reformattedBridge_stillDetected() {
        String odd = "dcbm.production_no IN(\n  select dqm.production_no\n  FROM ds_quote_material dqm\n"
                + "  WHERE dqm.material_no = ANY(:total_material_no))";
        String sql = SELECT_FROM + "WHERE " + odd + " AND " + DIRECT + "\nORDER BY dcbm.production_no";
        assertEquals("COMPILE_AXIS_NARROW_CONFLICT", expectReject(List.of(odd), sql).getErrorCode());
    }

    // ================= 拦截侧 · 护栏自身的失效路径（复核回流 ①） =================

    @Test
    @DisplayName("拦截③🚨: 桥没落在 anchorWhere 里（如发进 resolveSub 的局部 where）⇒ 必须 UNCLASSIFIABLE，"
            + "🚫 绝不能当『没有桥』静默放行")
    void bridgeOutsideAnchorWhere_throwsUnclassifiable() {
        // 产物里有桥，但 anchorWhere 认不出来 —— 老写法在这里会 bridgePredicates.isEmpty() → return，
        // 一声不吭地放行，而 narrowedByBridge 那套是独立的、直接轴谓词照发 ⇒ B-52 原样重现。
        BuilderApiException ex = expectReject(List.of(), CONFLICT_SQL);

        assertEquals("COMPILE_AXIS_NARROW_UNCLASSIFIABLE", ex.getErrorCode());
        assertEquals(1L, ex.getExtra().get("bridgesInArtifact"));
        assertEquals(0, ex.getExtra().get("bridgesRecognized"));
    }

    @Test
    @DisplayName("拦截④: 产物里 2 处桥但只认出 1 处 ⇒ 同样 UNCLASSIFIABLE（对账，不是有无）")
    void bridgeCountMismatch_throwsUnclassifiable() {
        String bridge2 = "dcbm.plating_no IN (SELECT dqm2.plating_no "
                + "FROM ds_quote_material dqm2 WHERE dqm2.material_no = ANY(:total_material_no))";
        String sql = SELECT_FROM + "WHERE " + BRIDGE + " AND " + bridge2 + "\nORDER BY dcbm.production_no";
        BuilderApiException ex = expectReject(List.of(BRIDGE), sql); // 只声明了 1 处
        assertEquals("COMPILE_AXIS_NARROW_UNCLASSIFIABLE", ex.getErrorCode());
        assertEquals(2L, ex.getExtra().get("bridgesInArtifact"));
    }

    @Test
    @DisplayName("拦截⑤: 剔除两种已知形态后仍在消费该入参 ⇒ 第三种形态，UNCLASSIFIABLE")
    void unknownThirdShape_throwsUnclassifiable() {
        String sql = SELECT_FROM + "WHERE " + BRIDGE
                + " AND dcbm.legacy_no = :total_material_no\nORDER BY dcbm.production_no";
        assertEquals("COMPILE_AXIS_NARROW_UNCLASSIFIABLE",
                expectReject(List.of(BRIDGE), sql).getErrorCode());
    }

    @Test
    @DisplayName("不误报: 与本入参无关的 IN (SELECT …) 子查询不算桥（判据禁止跨括号）")
    void unrelatedSubquery_notCountedAsBridge() {
        // 这条是 BRIDGE_SEMI_JOIN 用 [^()] 而不是 [\s\S] 的理由：若允许跨括号，下面这个
        // 无关子查询会一路够到后面那条直接轴谓词的 ANY(:total_material_no)，造成假报警。
        String sql = SELECT_FROM
                + "WHERE dcbm.status IN (SELECT s.code FROM ds_status s WHERE s.active = true)\n"
                + "  AND " + DIRECT + "\nORDER BY dcbm.production_no";
        assertDoesNotThrow(() -> check(List.of(DIRECT), sql));
    }
}
