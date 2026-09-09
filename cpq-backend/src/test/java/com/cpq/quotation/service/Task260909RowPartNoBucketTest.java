package com.cpq.quotation.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260909 · S1 只读片 · <b>AC-15（契约 · 后端用例）</b>
 *
 * <p>AC-15 原文（{@code 需求文档.md §③ 四、行归属键}）逐字：
 * <pre>
 *   非树页签的 driver 行：
 *   ① 只有 hf_part_no（无 material_no）时，按 hf_part_no 分桶落到对应卡片；
 *   ② 只有 material_no（无 hf_part_no）时，回落 material_no 分桶（存量保护）；
 *   ③ 两者都无时，该行落选且不计入 kept。
 * </pre>
 *
 * <p><b>纯 JUnit，不带 {@code @QuarkusTest}、不碰 DB</b> —— 与既有
 * {@link BomTreeParentNoGuardTest} 同型（那组用例已把判据从 {@code render()} 的深层循环里
 * 抽成静态方法 {@code BomTreeRenderService.assertParentNoPresent}）。
 * 🚨 本类同样<b>禁止连库</b>：{@code mvnw test} 直连共享开发库 {@code cpq_db_0724}
 * （{@code CLAUDE.md §1}），带夹具的测试会在共享库留残留。
 *
 * <p>═══════════════════════════════════════════════════════════════════════
 * <h2>⚠️ 本类对实现侧的<b>契约请求</b>（写用例时实现尚不存在，故用反射寻址）</h2>
 *
 * <p>AC-15 要求的是一个<b>纯静态、无副作用</b>的行归属键解析：给一行 driver 数据，
 * 返回它该落到哪张卡片的料号；两者皆无则返回 {@code null}（= 落选、不计入 kept）。
 *
 * <p>写这组用例时实现代码尚未产出，而<b>方法名由后端工程师决定</b>
 * （测试不得读实现去反推 —— {@code testing.md §1}）。⇒ 本类按候选名反射寻址：
 * <pre>
 *   static String flatBucketKey(Map&lt;String,Object&gt; row)     &lt;-- 2026-09-09 主线确认的真名
 *   static String resolveRowPartNo(Map&lt;String,Object&gt; row)
 *   static String rowBucketKey(Map&lt;String,Object&gt; row)
 *   static String resolveBucketKey(Map&lt;String,Object&gt; row)
 *   static String rowPartNoOf(Map&lt;String,Object&gt; row)
 * </pre>
 * 一个都找不到 → <b>硬失败并打印这段契约</b>，🚫 不 skip
 * （skip 掉的契约用例会以「全部通过」的样子混过去 —— {@code testing.md §3} 假绿之首）。
 *
 * <p>🚦 <b>请主线转告后端</b>：若实际方法名/签名与上表不同，把真名告诉测试即可，
 * 本类改一行常量；<b>断言语义（①②③三条）逐字不变</b>。
 */
@DisplayName("AC-15 非树页签行归属键：hf_part_no 优先，material_no 回落，都无则落选")
class Task260909RowPartNoBucketTest {

    /**
     * 候选方法名（见类注释的契约请求）。命中第一个即用。
     *
     * <p>📌 2026-09-09 主线确认实际契约为
     * {@code BomTreeRenderService#flatBucketKey(Map<String,Object>) : String}（static，包级可见），
     * 置于首位；其余候选保留作向后兼容。<b>本次只改本常量，断言语义一字未动。</b>
     */
    private static final String[] CANDIDATES = {
            "flatBucketKey", "resolveRowPartNo", "rowBucketKey", "resolveBucketKey", "rowPartNoOf",
    };

    private static final String CONTRACT_HINT =
            "\n══════════════════════════════════════════════════════════════════\n"
          + "🚨 AC-15 契约未暴露：在 BomTreeRenderService 上找不到可用于行归属键解析的静态方法。\n"
          + "  需要（名字可议，语义不可议）：\n"
          + "    static String <name>(Map<String,Object> row)\n"
          + "      · row 含非空 hf_part_no            → 返回 hf_part_no 的值\n"
          + "      · row 只含非空 material_no         → 返回 material_no 的值（存量保护）\n"
          + "      · 两者都无 / 都为空白              → 返回 null（该行落选，不计入 kept）\n"
          + "  已尝试的候选名：" + String.join(", ", CANDIDATES) + "\n"
          + "  ⚠️ 这是**契约缺口**（测试环境/接口问题），本条判【未验证】；\n"
          + "     🚫 不得因此把用例改成 skip 或降级断言。\n"
          + "══════════════════════════════════════════════════════════════════\n";

    private static Method resolver() {
        List<String> seen = new ArrayList<>();
        for (Method m : BomTreeRenderService.class.getDeclaredMethods()) {
            seen.add(m.getName() + "/" + m.getParameterCount());
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() != 1) continue;
            if (!Map.class.isAssignableFrom(m.getParameterTypes()[0])) continue;
            if (!String.class.equals(m.getReturnType())) continue;
            for (String cand : CANDIDATES) {
                if (m.getName().equals(cand)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        fail(CONTRACT_HINT + "  BomTreeRenderService 上实有的方法：" + seen);
        return null; // unreachable
    }

    @SuppressWarnings("unchecked")
    private static String bucketOf(Map<String, Object> row) {
        try {
            return (String) resolver().invoke(null, row);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("调用行归属键解析方法失败（契约/签名问题 ⇒ 判【未验证】）", e);
        }
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ── ① 只有 hf_part_no → 按 hf_part_no 分桶 ────────────────────────────
    @Test
    @DisplayName("AC-15①：只有 hf_part_no（无 material_no）→ 按 hf_part_no 分桶")
    void onlyHfPartNo_bucketsByHfPartNo() {
        assertEquals("300013", bucketOf(row("hf_part_no", "300013", "element_code", "Cu")),
                "AC-15①：全库 42 个生效视图中 41 个只有 hf_part_no ——"
              + " 它落不到卡片，核价的产品/材质元素页签就恒空（kept=0，连红都不红）");
    }

    @Test
    @DisplayName("AC-15①-b：hf_part_no 与 material_no 同时存在时，hf_part_no 优先")
    void bothPresent_hfPartNoWins() {
        assertEquals("HF-1",
                bucketOf(row("hf_part_no", "HF-1", "material_no", "MAT-1")),
                "AC-15：口径基线是「统一按 hf_part_no 归属行」（D-9），material_no 只是回落");
    }

    // ── ② 只有 material_no → 回落 material_no（存量保护）────────────────────
    @Test
    @DisplayName("AC-15②：只有 material_no（无 hf_part_no）→ 回落 material_no 分桶")
    void onlyMaterialNo_fallsBackToMaterialNo() {
        assertEquals("300012", bucketOf(row("material_no", "300012")),
                "AC-15②：这条回落是为了保护全库唯一 1 个非配置器视图，删了它那个视图会静默恒空");
    }

    // ── ③ 两者都无 → 落选（null），不计入 kept ─────────────────────────────
    @Test
    @DisplayName("AC-15③：两者都无 → 返回 null（该行落选，不计入 kept）")
    void neitherPresent_isDropped() {
        assertNull(bucketOf(row("element_code", "Cu", "content_pct", "22")),
                "AC-15③：两个键都没有的行必须落选；返回空串/占位符都会让它落进一个假桶");
    }

    @Test
    @DisplayName("AC-15③-b：键存在但值为 null / 空串 / 纯空白 → 同样落选")
    void blankValues_areDropped() {
        assertNull(bucketOf(row("hf_part_no", null, "material_no", null)),
                "AC-15③：null 值不能当成「有值」");
        assertNull(bucketOf(row("hf_part_no", "", "material_no", "")),
                "AC-15③：空串不能当成「有值」——否则所有空行会挤进同一个 \"\" 桶");
        assertNull(bucketOf(row("hf_part_no", "   ", "material_no", "\t")),
                "AC-15③：纯空白不能当成「有值」");
    }

    @Test
    @DisplayName("AC-15②-b：hf_part_no 为空白而 material_no 有值 → 仍回落 material_no")
    void blankHfPartNo_fallsBackToMaterialNo() {
        assertEquals("MAT-9", bucketOf(row("hf_part_no", "  ", "material_no", "MAT-9")),
                "AC-15：「有这个键」不等于「有值」——空白 hf_part_no 必须触发回落，否则整片行落选");
    }

    // ── 契约：桶键必须与递归 SQL 输出**逐字节相等** ─────────────────────────
    @Test
    @DisplayName("AC-15 契约：返回原值而非 trim 后的值（桶键须与递归 SQL 输出逐字节相等）")
    void returnsRawValueNotTrimmed() {
        // 🚨 trim 只用于**判空**，不得用于**产出桶键**。
        //    若这里返回 "300013"，桶键就配不上闭包里逐字节为 " 300013 " 的料号，
        //    表现为**整片行静默落选**（kept 看着正常、卡片却恒空，连红都不红）——
        //    正是本任务在消灭的那种静默形态。
        assertEquals(" 300013 ", bucketOf(row("hf_part_no", " 300013 ")),
                "AC-15 契约：桶键必须与递归 SQL 输出逐字节相等，🚫 不许 trim 后再当键");
    }

    // ── 阳性对照：证明这组断言真的执行了（testing.md §4.4 证伪实验的最小形态）──
    @Test
    @DisplayName("AC-15 阳性对照：解析方法确实被调到（否则以上全是空跑）")
    void resolverIsActuallyWired() {
        Method m = resolver();
        assertNotNull(m, CONTRACT_HINT);
        System.out.println("[AC-15] 行归属键解析方法 = "
                + m.getDeclaringClass().getSimpleName() + "#" + m.getName()
                + "(" + m.getParameterTypes()[0].getSimpleName() + ") : " + m.getReturnType().getSimpleName());
        // 同一入参必须稳定返回同一结果（纯函数，无副作用）——防「解析依赖了外部可变状态」
        Map<String, Object> r = row("hf_part_no", "P-1");
        assertEquals(bucketOf(r), bucketOf(r), "AC-15：行归属键解析必须是纯函数（同入参同出参）");
    }
}
