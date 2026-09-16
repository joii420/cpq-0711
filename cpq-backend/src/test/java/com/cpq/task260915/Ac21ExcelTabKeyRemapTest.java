package com.cpq.task260915;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>AC-21</b> —— EXCEL 组件的跨页签引用（{@code excel_columns.tabs[].tabKey}）必须被重映射。
 *
 * <h3>AC 原文（需求文档.md §③ AC-21，开工后新增）</h3>
 * <ul>
 *   <li><b>前置</b>：源目录含至少一个 EXCEL 组件，其 {@code excel_columns} 内嵌指向<b>同目录其他组件</b>的 id。
 *       <b>先断言该前置成立</b>，否则本条空跑。</li>
 *   <li><b>断言 a（正向）</b>：新目录 EXCEL 组件的 {@code excel_columns} 里，凡指向<b>本包内</b>组件的 id，
 *       全部被替换成<b>新目录对应组件的新 id</b>；verdict SQL 的 {@code CROSS_DIR} 行数为 <b>0</b>。</li>
 *   <li><b>断言 b（不误伤）</b>：指向<b>包外</b>的引用<b>保持原值不变</b> —— 🚫 不得被清空、不得被乱指。</li>
 *   <li><b>断言 c（同族回归）</b>：{@code formulas} 里既有的 {@code cross_tab_ref.source} /
 *       {@code component_subtotal.component_code} 重映射行为<b>逐字不变</b>。</li>
 *   <li><b>阳性对照</b>：改动前的实现在同一输入下 {@code CROSS_DIR > 0}（证明这条 AC 不是恒绿）。</li>
 * </ul>
 *
 * <h3>🚨 verdict SQL 的那个坑</h3>
 * {@code tabKey} 里<b>合法地</b>存在 {@code idx:<n>} 这种<b>无 id 形态</b>，
 * {@code split_part(t->>'tabKey',':',1)::uuid} 会抛
 * {@code invalid input syntax for type uuid: "idx"} ——
 * 那是 <b>harness 崩溃</b>，却长得像「被测功能出错」。
 * ⇒ 一律先按 UUID <b>形状正则</b>过滤再转型，见 {@link Task260915Base#TABKEY_UUID_SQL}。
 *
 * <h3>{@code tabKey} 的四种形态（后端查实，本类四种都覆盖）</h3>
 * <table>
 *   <tr><th>形态</th><th>来源</th><th>期望</th></tr>
 *   <tr><td>包内<b>裸 id</b></td><td>{@code ComponentTabDefService}</td><td>换成新 id</td></tr>
 *   <tr><td>包内 <b>{@code <id>:<sortOrder>}</b></td><td>{@code ExcelViewService}</td><td><b>只换 id 段，{@code :<n>} 后缀保留</b></td></tr>
 *   <tr><td>包外 id</td><td>—</td><td><b>不动</b></td></tr>
 *   <tr><td>{@code idx:<n>}（无 id）</td><td>—</td><td><b>不动</b></td></tr>
 * </table>
 */
@QuarkusTest
@DisplayName("task-260915 S-A · AC-21 excel_columns.tabKey 跨页签引用重映射")
class Ac21ExcelTabKeyRemapTest extends Task260915Base {

    @Test
    @DisplayName("AC-21 a + 阳性对照：包内 tabKey 全部重映射，CROSS_DIR=0；复刻改动前行为则 CROSS_DIR>0")
    void ac21a_inBundleTabKeysAreRemapped() {
        final String AC = "AC-21a";
        UUID src = sourceDirectoryId();

        // ── 前置自证：源目录的 EXCEL 组件确实内嵌了指向「同目录其他组件」的 id ──
        Set<String> srcComponentIds = new LinkedHashSet<>();
        for (UUID id : componentIdsByCode(src).values()) srcComponentIds.add(id.toString());
        List<String> srcRefs = embeddedTabKeyUuids(src);
        List<String> srcInDir = new ArrayList<>();
        for (String u : srcRefs) if (srcComponentIds.contains(u)) srcInDir.add(u);
        assertFalse(srcInDir.isEmpty(), AC + " 前置未满足：源目录「" + SOURCE_DIR_NAME
                + "」的 excel_columns 里没有任何指向同目录其他组件的 id "
                + "⇒ 「包内引用被重映射」这条断言<b>没有输入</b>，用例会空跑（testing.md §3）。"
                + " 抽到的全部内嵌 UUID=" + srcRefs);
        System.out.println("[" + AC + "] 前置成立：源目录内嵌 tabKey UUID " + srcRefs.size()
                + " 个，其中指向同目录组件的 " + srcInDir.size() + " 个 = " + srcInDir);

        RoundTrip rt = roundTrip(src, "AC21A-M", AC);
        UUID dirM = rt.targetDir();

        // ── 断言 a：新目录里，凡指向「本包内」组件的 id，都不能再是源库的旧 id ──
        long crossInBundle = crossDirInBundle(dirM, srcComponentIds);
        long sameDir = sameDirRefs(dirM);
        assertEquals(0, crossInBundle, AC + "：新目录 " + dirM + " 的 excel_columns 里仍有 " + crossInBundle
                + " 处 tabKey 指向<b>源目录</b>的组件（CROSS_DIR）⇒ 包内引用没有被重映射。"
                + "\n  跨机器搬运时目标库没有这些 id，会直接成悬空引用。");
        // 🚨 防空跑：不能靠「一处引用都没有」来让 CROSS_DIR=0 通过。
        assertEquals(srcInDir.size(), sameDir, AC + "：新目录里指向<b>本目录</b>组件的 tabKey 应有 "
                + srcInDir.size() + " 处（与源目录同数），实际 " + sameDir
                + " 处 ⇒ 引用被丢弃或被指到别处，不是「重映射」。");
        assertTrue(sameDir > 0, AC + "：新目录一处 tabKey 引用都没有 ⇒ CROSS_DIR=0 是「没东西可错」，断言空跑。");
        System.out.println("[" + AC + "] ✅ 断言 a：CROSS_DIR=" + crossInBundle + "，SAME_DIR=" + sameDir
                + "（与源目录的 " + srcInDir.size() + " 处一一对应）。");

        // ══ 阳性对照 ══
        // 🚨 只验「改动后 = 0」不够 —— 那条断言在缺陷根本不存在时也恒绿。
        // 这里<b>构造性复刻改动前的行为</b>：改动前 ComponentImportService 是把源包的 excelColumns
        // <b>原样写入</b>（未经重映射）。于是把源组件的 excel_columns 原样灌进新组件，
        // 再跑同一条 verdict SQL —— 必须报出 CROSS_DIR > 0，否则说明<b>这条 SQL 根本测不出这个缺陷</b>。
        UUID srcExcel = excelComponentIn(src, AC);
        UUID dstExcel = excelComponentIn(dirM, AC);
        String backup = rowText("component", List.of("excel_columns"), dstExcel).get("excel_columns");
        assertNotNull(backup, AC + " 阳性对照前置：新目录 EXCEL 组件的 excel_columns 为 NULL，无从还原。");
        long crossAfterInjection;
        try {
            assertEquals(1, exec("UPDATE component c SET excel_columns ="
                    + " (SELECT s.excel_columns FROM component s WHERE s.id='" + srcExcel + "'::uuid)"
                    + " WHERE c.id='" + dstExcel + "'::uuid AND c.directory_id='" + dirM + "'::uuid"),
                    AC + " 阳性对照：注入 UPDATE 应命中 1 行。");
            crossAfterInjection = crossDirInBundle(dirM, srcComponentIds);
        } finally {
            // 还原（只动我自己目录里的这一行）
            exec("UPDATE component SET excel_columns=" + lit(backup) + "::jsonb WHERE id='" + dstExcel
                    + "'::uuid AND directory_id='" + dirM + "'::uuid");
        }
        assertTrue(crossAfterInjection > 0, AC + "：🚨 阳性对照失败 —— 把源组件的 excel_columns <b>原样</b>"
                + "灌进新组件（= 精确复刻改动前 ComponentImportService 的『原样写入』），"
                + "verdict SQL 却仍报 CROSS_DIR=0 ⇒ <b>这条 SQL 测不出这个缺陷</b>，"
                + "上面那句「CROSS_DIR=0」不构成任何证据。");
        assertEquals(0, crossDirInBundle(dirM, srcComponentIds),
                AC + " 阳性对照收尾：还原后 CROSS_DIR 应回到 0，说明注入已撤干净。");
        System.out.println("[" + AC + "] ✅ 阳性对照：复刻改动前行为后 CROSS_DIR=" + crossAfterInjection
                + "（>0，证明 verdict SQL 能抓到这个缺陷）；还原后回到 0。");
    }

    @Test
    @DisplayName("AC-21 b + 四种 tabKey 形态：包内裸 id/带后缀换，包外 id 与 idx:n 一律不动")
    void ac21b_allFourTabKeyShapes() {
        final String AC = "AC-21b";
        UUID src = sourceDirectoryId();
        RoundTrip toM = roundTrip(src, "AC21B-M", AC);
        UUID dirM = toM.targetDir();

        // ── 在私有副本 M 上造一个含四种形态的 excel_columns ──
        UUID excelM = excelComponentIn(dirM, AC);
        List<UUID> siblings = new ArrayList<>();
        for (Map.Entry<String, UUID> e : componentIdsByCode(dirM).entrySet()) {
            if (!e.getValue().equals(excelM)) siblings.add(e.getValue());
        }
        assertTrue(siblings.size() >= 2, AC + " 前置未满足：M 里除 EXCEL 组件外只有 " + siblings.size()
                + " 个组件，凑不出两个「包内」引用。");
        UUID inBundleBare = siblings.get(0);
        UUID inBundleSuffixed = siblings.get(1);
        // 包外引用：源目录里的组件 —— 它不在 M 这个包里，导出 M 时不会随包走。
        UUID outOfBundle = componentIdsByCode(src).values().iterator().next();
        assertFalse(siblings.contains(outOfBundle), AC + "：选中的「包外」id 居然在 M 里，对照无效。");

        final String SHAPE_BARE = inBundleBare.toString();
        final String SHAPE_SUFFIXED = inBundleSuffixed + ":3";
        final String SHAPE_OUTSIDE = outOfBundle.toString();
        final String SHAPE_IDX = "idx:2";
        String crafted = "["
                + excelCol(1, SHAPE_BARE, "包内裸id") + ","
                + excelCol(2, SHAPE_SUFFIXED, "包内带后缀") + ","
                + excelCol(3, SHAPE_OUTSIDE, "包外id") + ","
                + excelCol(4, SHAPE_IDX, "无id形态") + "]";
        assertEquals(1, exec("UPDATE component SET excel_columns=" + lit(crafted) + "::jsonb"
                + " WHERE id='" + excelM + "'::uuid AND directory_id='" + dirM + "'::uuid"),
                AC + "：造数 UPDATE 应命中 1 行。");
        List<String> before = orderedFirstTabKeys(excelM);
        assertEquals(List.of(SHAPE_BARE, SHAPE_SUFFIXED, SHAPE_OUTSIDE, SHAPE_IDX), before,
                AC + "：造数没按预期落库（顺序或取值不对），后面按下标读会读错列。实际=" + before);
        System.out.println("[" + AC + "] M 侧造数（四形态，按列序）= " + before);

        // ── M → 包 → N ──
        RoundTrip toN = roundTrip(dirM, "AC21B-N", AC);
        String mCode = scalar("SELECT code FROM component WHERE id='" + excelM + "'::uuid");
        UUID excelN = toN.dstIdByOrigCode().get(mCode);
        assertNotNull(excelN, AC + "：EXCEL 组件 " + mCode + " 在 N 侧找不到落点。");
        List<String> after = orderedFirstTabKeys(excelN);
        assertEquals(4, after.size(), AC + "：N 侧 excel_columns 应有 4 列，实际 " + after.size() + " 列 ⇒ 列被吞了。实际=" + after);
        System.out.println("[" + AC + "] N 侧实际（按列序）= " + after);

        UUID expectBare = toN.dstIdByOrigCode().get(scalar("SELECT code FROM component WHERE id='" + inBundleBare + "'::uuid"));
        UUID expectSuffixed = toN.dstIdByOrigCode().get(scalar("SELECT code FROM component WHERE id='" + inBundleSuffixed + "'::uuid"));
        assertNotNull(expectBare, AC + "：包内引用的目标组件在 N 侧找不到落点。");
        assertNotNull(expectSuffixed, AC + "：包内带后缀引用的目标组件在 N 侧找不到落点。");

        // ① 包内裸 id → 换成新 id
        assertEquals(expectBare.toString(), after.get(0), AC + " ①包内裸id：应被换成 N 侧对应组件的新 id "
                + expectBare + "，实际「" + after.get(0) + "」"
                + (SHAPE_BARE.equals(after.get(0)) ? "（仍是源库旧 id ⇒ 没重映射）" : ""));
        // ② 包内 <id>:<sortOrder> → 只换 id 段，后缀保留
        assertEquals(expectSuffixed + ":3", after.get(1), AC + " ②包内带后缀：应只换 id 段、保留 `:3` 后缀，"
                + "实际「" + after.get(1) + "」"
                + (after.get(1) != null && !after.get(1).contains(":") ? "（后缀被吃掉了 ⇒ sortOrder 丢失）" : ""));
        // ③ 包外 id → 原值不变（断言 b：不得被清空、不得被乱指）
        assertEquals(SHAPE_OUTSIDE, after.get(2), AC + " ③包外id：本包里没有这个 id，应<b>原值不变</b>，实际「"
                + after.get(2) + "」⇒ 被误伤（清空或乱指）。");
        // ④ idx:<n> 无 id 形态 → 原值不变
        assertEquals(SHAPE_IDX, after.get(3), AC + " ④无id形态：`idx:2` 应原值不变，实际「" + after.get(3) + "」。");
        System.out.println("[" + AC + "] ✅ 四形态全部符合预期：包内裸id→新id、包内带后缀→新id:3、包外id不动、idx:2 不动。");
    }

    @Test
    @DisplayName("AC-21 c 同族回归：formulas 的 component_subtotal.component_code 重映射逐字不变")
    void ac21c_formulaRemapUnchanged() {
        final String AC = "AC-21c";
        UUID src = sourceDirectoryId();

        // 前置自证：源目录里真有 component_subtotal.component_code 这种跨组件引用（否则空跑）
        List<Object[]> holders = rows("SELECT code, formulas::text FROM component WHERE directory_id='" + src
                + "'::uuid AND formulas::text LIKE '%component_subtotal%' ORDER BY code");
        assertFalse(holders.isEmpty(), AC + " 前置未满足：源目录里没有含 component_subtotal 的公式 "
                + "⇒ 「既有重映射行为不变」这条断言没有输入。");

        RoundTrip rt = roundTrip(src, "AC21C-M", AC);

        int checked = 0;
        for (Object[] h : holders) {
            String ownerCode = String.valueOf(h[0]);
            String srcFormulas = String.valueOf(h[1]);
            UUID dstId = rt.dstIdByOrigCode().get(ownerCode);
            assertNotNull(dstId, AC + "：组件 " + ownerCode + " 在新目录找不到落点。");
            String dstFormulas = rowText("component", List.of("formulas"), dstId).get("formulas");
            assertNotNull(dstFormulas, AC + "：组件 " + ownerCode + " 导入后 formulas 为 NULL。");

            for (Map.Entry<String, String> e : rt.finalCodeByOrigCode().entrySet()) {
                String origRef = "\"" + e.getKey() + "\"";
                if (!srcFormulas.contains(origRef)) continue;
                String newRef = "\"" + e.getValue() + "\"";
                assertTrue(dstFormulas.contains(newRef), AC + "：组件 " + ownerCode
                        + " 的公式原本引用 " + e.getKey() + "，导入后应重映射成 " + e.getValue()
                        + "，但新 formulas 里找不到它 ⇒ 既有的 component_subtotal.component_code 重映射<b>被本次改动破坏了</b>。"
                        + "\n  新 formulas=" + dstFormulas);
                assertFalse(dstFormulas.contains(origRef), AC + "：组件 " + ownerCode
                        + " 的公式里仍残留源库的旧 code " + e.getKey() + " ⇒ 重映射没做干净。"
                        + "\n  新 formulas=" + dstFormulas);
                checked++;
                System.out.println("[" + AC + "] " + ownerCode + " 的公式引用 " + e.getKey() + " → " + e.getValue() + " ✅");
            }
        }
        assertTrue(checked > 0, AC + "：一处跨组件公式引用都没验到 ⇒ 断言从未执行（假绿）。"
                + " 源目录里含 component_subtotal 的组件有 " + holders.size() + " 个，但没有一个引用到本包内的其他 code。");
        System.out.println("[" + AC + "] ✅ 既有重映射逐字不变，验到 " + checked + " 处跨组件公式引用。");
        System.out.println("[" + AC + "] ⚠️【未覆盖】`cross_tab_ref.source` 这一路 —— 实查全库 "
                + "`formulas::text LIKE '%cross_tab_ref%'` 命中 <b>0</b> 个组件，无真实样本可验。"
                + "造一个合成样本需要知道它的真实 schema，而那只能从实现读 ⇒ 本片不造。已上报主线。");
    }

    // ═══════════════════════════ 辅助 ═══════════════════════════

    /** 目标目录里，tabKey 指向「本包内组件的<b>源</b> id」的处数 —— 即「该重映射而没重映射」。 */
    private long crossDirInBundle(UUID dirId, Set<String> sourceComponentIds) {
        long n = 0;
        for (String u : embeddedTabKeyUuids(dirId)) if (sourceComponentIds.contains(u)) n++;
        return n;
    }

    /** 目标目录里，tabKey 指向<b>本目录自己</b>组件的处数（= 重映射成功的处数）。 */
    private long sameDirRefs(UUID dirId) {
        Set<String> own = new LinkedHashSet<>();
        for (UUID id : componentIdsByCode(dirId).values()) own.add(id.toString());
        long n = 0;
        for (String u : embeddedTabKeyUuids(dirId)) if (own.contains(u)) n++;
        return n;
    }

    private static String excelCol(int i, String tabKey, String alias) {
        return "{\"tabs\":[{\"alias\":\"" + alias + "\",\"tabKey\":\"" + tabKey + "\",\"rowKeyFields\":[]}],"
                + "\"title\":\"RT-SA-AC21-" + i + "\",\"hidden\":false,\"col_key\":\"col_" + i + "\","
                + "\"expression\":\"[x]\",\"source_type\":\"TAB_JOIN_FORMULA\"}";
    }
}
