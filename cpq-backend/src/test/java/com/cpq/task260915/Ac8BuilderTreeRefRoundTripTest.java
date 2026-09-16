package com.cpq.task260915;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;

/**
 * task-260915 · <b>AC-8</b>（分片 S-全局）—— builder 树页签 + {@code tree_ref} 公式能导入成功。
 *
 * <h3>AC 原文（需求文档.md §③「单点 AC」）</h3>
 * <blockquote>
 * <b>AC-8 · builder 树页签 + {@code tree_ref} 公式能导入成功</b><br>
 * · 前置：源目录中一个 {@code builder_config->>'tabType' = 'BOM'} 且 {@code component.tab_type IS NULL}
 *   的组件，其公式含 {@code tree_ref}<br>
 * · 操作：导出 → 导入新目录<br>
 * · 断言：导入<b>成功</b>（HTTP 200，{@code createdItems} 含该组件），<b>不再</b>出现
 *   {@code tabType=(未配置)} 报错；导入后该组件在报价渲染中 {@code tree_ref} 公式能算出非空数值
 *   （不是 {@code —}、不是「加载中…」）
 * </blockquote>
 *
 * <h3>🚨 本 AC 最大的假绿风险：空跑（test.md §1 点名）</h3>
 * 实查全库 ACTIVE 组件中含 {@code tree_ref}/{@code tree_attr} 公式的为 <b>0 个</b>。
 * 不造数的话，「树公式能导入」这条断言<b>没有任何输入</b>，用例照样绿。
 * ⇒ {@link #ac8_builderTreeTabWithTreeRef_survivesRoundTripImport()} 的前三步全是<b>前置非空断言</b>，
 * 任何一步不成立都在「导入」之前就硬失败，并明说这是<b>前置未满足</b>而不是 AC 结论。
 *
 * <h3>🚫 为什么在自建目录 M 上造数，而不是改共享的「取值配置器测试」目录</h3>
 * test.md §1：共享目录是别人的写入面，在上面造样本会污染环境，也让本片无法归为「私有写」。
 * 本用例的 M / N 两个目录都是本轮自建（{@code RT-SG-260915-*}），{@code @AfterEach} 按 id 清理。
 *
 * <h3>⚠️ AC-8 后半句「在报价渲染中能算出非空数值」= 本层验不到</h3>
 * 那句要的是<b>报价单渲染结果</b>（需要 模板装配 → 建报价单 → 卡片取值 的完整夹具），
 * 而 test.md §4 明确「本任务不需要 Playwright」。⇒ 本类<b>不覆盖</b>这半句，
 * 在回报中按【未验证】显式列出，交主线亲验（CLAUDE.md §4.5 步骤 4 b「走用户视角的完整路径」）。
 * 🚫 不许拿下面的「阳性对照」冒充它 —— 那验的是<b>校验判据</b>，不是<b>渲染取值</b>。
 */
@QuarkusTest
@DisplayName("task-260915 · AC-8 —— builder 树页签 + tree_ref 公式导出再导入不被自己的校验拒绝")
class Ac8BuilderTreeRefRoundTripTest extends Task260915SgBase {

    private static final String AC = "AC-8";

    /** AC-8 原文点名的那句报错 —— 断言它<b>不再</b>出现。 */
    private static final String OLD_ERROR_FRAGMENT = "tabType=(未配置)";

    @Test
    @DisplayName("AC-8: 树页签+tree_ref 导出→导入 新目录 → HTTP 200 且 createdItems 含该组件，不再报 tabType=(未配置)")
    void ac8_builderTreeTabWithTreeRef_survivesRoundTripImport() throws Exception {
        String viewFp0 = foreignViewFingerprint();
        String semFp0 = semanticBaselineFingerprint();
        printGlobalFootprint("开跑前", viewFp0, semFp0);

        // ════════ 造数：目录 M 里造出 AC-8 前置要求的那种组件 ════════
        UUID dirM = createDirectory("M");
        UUID compM = createComponentInDir(dirM, "树页签", null, null);

        // ① 用取数配置器的真实保存路径把它变成 builder 树页签
        //    🚨 这一步就是本片归 S-全局 的原因：PUT /builder 触发全量视图重编译。
        saveBuilderOk(compM, CFG_MATERIAL_BOM, AC + " 前置①");

        // ② 前置非空断言 A —— 「builder 树页签」这个身份真的成立
        String tabTypeInBuilder = scalar(
                "SELECT builder_config->>'tabType' FROM component_sql_view WHERE component_id = '" + compM + "'");
        assertEquals("BOM", tabTypeInBuilder, AC + " 前置未满足：自建组件的 builder_config->>'tabType' 不是 BOM"
                + "（实际=" + tabTypeInBuilder + "）⇒ 它根本不是「builder 树页签」，AC-8 的前置不成立，"
                + "后面「树公式能导入」的断言全部空跑。这是夹具故障，不是 AC 结论。");
        String tabTypeColumn = scalar("SELECT tab_type FROM component WHERE id = '" + compM + "'");
        assertNull(tabTypeColumn, AC + " 前置未满足：component.tab_type 不为空（实际=" + tabTypeColumn + "）"
                + " ⇒ 那样即使导入成功也可能是走老的单判据放行的，验不到 AC-8 要验的那条链路。");

        // ③ 在它上面加一条含 tree_ref 的公式（配置期双判据会放行 —— 需求文档 §根因）
        String formulaName = PREFIX + "父取值-" + RUN_ID;
        Response save = saveComponent(compM, treeRefFormulaBody(formulaName, "组成数量"));
        assertReachedBusinessLayer(save, AC + " 前置③");
        assertEquals(200, save.statusCode(), AC + " 前置未满足：在 builder 树页签上保存 tree_ref 公式被拒（"
                + save.statusCode() + "）⇒ 造不出样本，AC-8 无法验。body=" + save.asString()
                + "\n（需求文档 §根因：组件新建/更新走的是双判据（builder 语义 OR tab_type），此处应放行）");

        // ④ 🚨 前置非空断言 B —— 这条公式确实存在于库里（test.md §1「AC-8 空跑」的破法）
        long withTreeRef = count("SELECT count(*) FROM component WHERE id = '" + compM
                + "' AND formulas::text LIKE '%tree_ref%'");
        assertEquals(1, withTreeRef, AC + " 前置未满足：组件 " + compM + " 的 formulas 里查不到 tree_ref "
                + "⇒ 「树公式能导入」这条断言没有输入，会以「通过」的形态空跑（testing.md §3）。"
                + "实际 formulas=" + scalar("SELECT formulas::text FROM component WHERE id = '" + compM + "'"));
        System.out.println("[" + AC + " 前置✅] compM=" + compM + " formulas="
                + scalar("SELECT formulas::text FROM component WHERE id = '" + compM + "'"));

        String codeM = scalar("SELECT code FROM component WHERE id = '" + compM + "'");
        assertTrue(codeM != null && !codeM.isBlank(), AC + " 前置未满足：组件没有 code，无法在导入结果里配对。");

        // ════════ 操作：导出 M ════════
        String rawA = exportRaw(dirM, AC);
        JsonNode bundleA = parseBundle(rawA, AC);
        JsonNode itemM = findItem(bundleA, codeM);

        // ⑤ 🚨 前置非空断言 C —— 导出包里真的带着这条树公式
        //    包里没有它的话，后面「导入成功」只证明了「导入一个没有树公式的组件会成功」，那是恒真。
        assertTrue(itemM.path("formulas").toString().contains("tree_ref"),
                AC + " 前置未满足：导出包里组件 " + codeM + " 的 formulas 不含 tree_ref "
                + "⇒ 后面的「导入成功」是恒真断言（导入一个没有树公式的组件本来就会成功）。"
                + "实际 formulas=" + itemM.path("formulas"));
        System.out.println("[" + AC + " 前置✅] 导出包里 " + codeM + " 带着 tree_ref");

        // ════════ 操作：导入新目录 N ════════
        UUID dirN = createDirectory("N");
        Response commit = importCommit(dirN, rawA, "RENAME", AC);

        // ── 断言 1：导入成功（HTTP 200）──────────────────────────────────────
        assertEquals(200, commit.statusCode(), AC + "：导入应成功（HTTP 200），实际=" + commit.statusCode()
                + "\n  这正是本任务起因的那条报错路径 —— 在源库合法保存的树页签组件，导出再导入被自己的校验拒绝。"
                + "\n  body=" + commit.asString());

        // ── 断言 2：不再出现 tabType=(未配置) ────────────────────────────────
        assertFalse(commit.asString().contains(OLD_ERROR_FRAGMENT),
                AC + "：导入响应里仍出现「" + OLD_ERROR_FRAGMENT + "」⇒ 导入端的树身份判据仍是单判据"
                + "（只认 component.tab_type），没有与配置期对齐。body=" + commit.asString());

        // ── 断言 3：createdItems 含该组件 ────────────────────────────────────
        JsonNode created = createdItemsOf(commit, AC);
        System.out.println("[" + AC + "] 导入响应 created[] 实际值 = " + created);
        assertTrue(created.size() > 0, AC + "：导入返回 200 但 createdItems 为空数组 "
                + "⇒ 「含该组件」的断言会在空集合上空跑。body=" + commit.asString());
        assertTrue(createdContainsCode(created, codeM),
                AC + "：createdItems 里找不到组件 " + codeM + "（RENAME 策略下 originalCode 或 finalCode 命中即算）。"
                + "实际 createdItems=" + created);

        // ── 断言 4（落库复核，只查自己的目录）：树公式真的进了新目录 ──────────
        long inN = count("SELECT count(*) FROM component WHERE directory_id = '" + dirN
                + "' AND formulas::text LIKE '%tree_ref%'");
        assertEquals(1, inN, AC + "：新目录 " + dirN + " 里含 tree_ref 公式的组件应为 1 个，实际=" + inN
                + " ⇒ 导入返回了 200，但公式在落库时被丢掉了（「成功」是假的）。");

        // ── 链路自检（不重复认领 AC-1，AC-1 归 S-A）：树身份也随包到了新库 ────
        String newCompId = scalar("SELECT id::text FROM component WHERE directory_id = '" + dirN
                + "' AND formulas::text LIKE '%tree_ref%'");
        String newTabType = scalar("SELECT builder_config->>'tabType' FROM component_sql_view "
                + "WHERE component_id = '" + newCompId + "'");
        System.out.println("[" + AC + "] 新目录组件 " + newCompId + " 的 builder_config->>'tabType' = " + newTabType
                + "（🚫 builder_config 保真本身归 AC-1 / 分片 S-A，此处只打印不断言）");

        // ════════ 阳性/阴性对照：证明「导入成功」不是因为闸门被拆了 ════════
        // 🔑 testing.md §4.4 / §5.5：只验「该放的放了」，把闸门改成「全部放行」也能过。
        //    ⇒ 必须配一条「不该放的仍然拦住」，否则断言 1~3 的绿说明不了闸门是活的。
        assertGateStillLive(dirM);

        printGlobalFootprint("跑完后", foreignViewFingerprint(), semanticBaselineFingerprint());
        assertEquals(semFp0, semanticBaselineFingerprint(),
                AC + "：语义图基线（semantic_tab_view / semantic_node）在本片跑动前后发生了变化 "
                + "⇒ 本片越界改了配置基线（test.md §3 明令不许动）。");
    }

    /**
     * <b>阴性对照</b>：同一批夹具里造一个<b>非树</b>的 builder 页签（数据源=自制加工费），
     * 在它上面存 {@code tree_ref} 必须仍被拒 400 且文案点名是哪条公式。
     *
     * <p>🔑 它和主用例的「导入 200」构成一对：
     * <ul>
     *   <li>只有主用例绿 ⇒ 可能是闸门被改成了「全部放行」（此时本方法会红）</li>
     *   <li>只有本方法绿 ⇒ 可能是闸门变成了「全部拒绝」（此时主用例会红）</li>
     * </ul>
     * 两条同时绿，才说明判据是<b>按树身份分流</b>的。
     */
    private void assertGateStillLive(UUID dirM) {
        UUID compFee = createComponentInDir(dirM, "非树页签", null, null);
        saveBuilderOk(compFee, CFG_SELF_PROCESS_FEE, AC + " 阴性对照前置");

        String feeTabType = scalar(
                "SELECT builder_config->>'tabType' FROM component_sql_view WHERE component_id = '" + compFee + "'");
        assertEquals("费用类", feeTabType, AC + " 阴性对照前置未满足：夹具不是非树页签（tabType=" + feeTabType
                + "）⇒ 对照失去意义。");

        String feeFormula = PREFIX + "非法父取值-" + RUN_ID;
        Response r = saveComponent(compFee, treeRefFormulaBody(feeFormula, "自制加工费"));
        assertReachedBusinessLayer(r, AC + " 阴性对照");
        assertEquals(400, r.statusCode(), AC + " 阴性对照失败：非树页签用 tree_ref 竟被放行（" + r.statusCode() + "）"
                + " ⇒ 🚨 闸门已失效（变成「全部放行」），此时主用例「导入 200」的绿「不能」作为 AC-8 达成的证据。"
                + " body=" + r.asString());
        assertTrue(r.asString().contains(feeFormula), AC + " 阴性对照：报错文案应点名是哪条公式，实际 body=" + r.asString());
        System.out.println("[" + AC + " 阴性对照✅] 非树页签 + tree_ref → 400 且点名『" + feeFormula + "』"
                + " ⇒ 闸门是活的，主用例的 200 有意义");
    }

    private JsonNode findItem(JsonNode bundle, String code) {
        for (JsonNode it : bundle.path("components")) {
            if (code.equals(it.path("code").asText(null))) return it;
        }
        throw new AssertionError(AC + "：导出包里找不到 code=" + code + " 的组件 ⇒ 导出这一步就没带上自建组件，"
                + "后续断言全部空跑。包里实际的 codes=" + bundle.path("components").findValuesAsText("code"));
    }
}
