package com.cpq.task260915;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>AC-4 / AC-5</b> —— 现网<b>没有样本</b>的两个字段，必须先造数再验往返。
 *
 * <h3>AC 原文（需求文档.md §③）</h3>
 * <ul>
 *   <li><b>AC-4</b> 前置：<b>手工给源目录任一组件的 {@code tree_config} 写入一个非空 JSON</b>
 *       （当前库该列全为空，不造数则断言空跑）；断言：导入后该组件 {@code tree_config::text} 与源相同。</li>
 *   <li><b>AC-5</b> 前置：<b>手工把源目录某个视图的 {@code status} 改为 {@code 'INACTIVE'}</b>；
 *       断言：导入后该视图 {@code status = 'INACTIVE'}，<b>不是</b> {@code 'ACTIVE'}。
 *       （需求文档 §② 记载的丢失后果：「导入端硬编码 ACTIVE，非 ACTIVE 视图会被悄悄激活」。）</li>
 * </ul>
 *
 * <h3>🚨 造数落在哪里：不在共享源目录上，在自己的副本 M 上</h3>
 * AC 原文写的是「给<b>源目录</b>任一组件写入」。
 * 🚫 本片<b>不这么做</b> —— 「取值配置器测试」是共享目录，改它属于污染别人的环境
 * （test.md §1：不许在现有共享目录上改数据造样本；testing.md §4.3）。
 * ✅ 改为：先把源目录<b>只读导入</b>成本片私有的副本 <b>M</b>，在 M 上造 {@code tree_config} 与
 * {@code INACTIVE} 视图，再 M → 包 → <b>N</b>，比对 M vs N。
 * 语义等价（验的都是「这两列能不能扛住一次导出+导入」），且写入面全私有。
 *
 * <h3>🚨 这条链路上必须先破的假绿</h3>
 * M 本身是导入产物。若导入丢字段，M 会「天生残缺」，M vs N 就变成「两边都为 NULL 地相等」。
 * ⇒ 本类每一步都<b>先断言 M 侧的造数真的落库了</b>（{@code tree_config} 非空 / {@code status='INACTIVE'} 命中 1 行），
 * 再去比 N。造数没落库 ⇒ 硬失败，不进比对。
 */
@QuarkusTest
@DisplayName("task-260915 S-A · AC-4/5 需造数字段的往返保真")
class Ac4Ac5CraftedSamplesTest extends Task260915Base {

    /** 造给 {@code tree_config} 的样本 —— 故意多层嵌套 + 含中文 + 含数组，单层平铺的值验不出「被裁剪」。 */
    private static final String TREE_CONFIG_SAMPLE =
            "{\"rootField\":\"料号\",\"parentField\":\"父料号\",\"levels\":[1,2,3],"
          + "\"options\":{\"expandAll\":true,\"maxDepth\":5,\"label\":\"RT-SA-260915 树配置样本\"}}";

    @Test
    @DisplayName("AC-4 tree_config 往返后 ::text 与源相同（造数后验，非空跑）")
    void ac4_treeConfigSurvivesRoundTrip() {
        final String AC = "AC-4";
        UUID src = sourceDirectoryId();
        RoundTrip toM = roundTrip(src, "AC4-M", AC);
        UUID dirM = toM.targetDir();

        // ── 造数：在私有副本 M 上挑一个组件写 tree_config ──
        UUID target = anyComponentIn(dirM, AC);
        int n = exec("UPDATE component SET tree_config = " + lit(TREE_CONFIG_SAMPLE) + "::jsonb"
                + " WHERE id='" + target + "'::uuid AND directory_id='" + dirM + "'::uuid");
        assertEquals(1, n, AC + "：造数 UPDATE 应命中恰好 1 行，实际 " + n + " 行 ⇒ 样本没造出来。");

        String mVal = rowText("component", List.of("tree_config"), target).get("tree_config");
        // 🚨 防空跑：造数必须真的落库，否则下面 M vs N 会退化成 NULL==NULL 恒真。
        assertNotNull(mVal, AC + "：造数后 M 侧 tree_config 仍为 NULL ⇒ 样本没落库，本条 AC 会空跑。");
        assertTrue(mVal.contains("RT-SA-260915"), AC + "：M 侧 tree_config 落库了但不含样本标记 ⇒ 读到的不是我造的那行。实际=" + mVal);
        // 目录内计数（🚫 不是全局计数）
        long inM = count("SELECT count(*) FROM component WHERE directory_id='" + dirM + "'::uuid AND tree_config IS NOT NULL");
        assertEquals(1, inM, AC + "：M 目录内 tree_config 非空的组件应为 1 个，实际 " + inM + " 个。");
        System.out.println("[" + AC + "] M 侧造数已落库：" + mVal);

        // ── M → 包 → N ──
        RoundTrip toN = roundTrip(dirM, "AC4-N", AC);
        UUID dstComp = dstOf(toN, target, dirM, AC);
        String nVal = rowText("component", List.of("tree_config"), dstComp).get("tree_config");

        assertNotNull(nVal, AC + "：往返后 N 侧 tree_config 变成了 NULL ⇒ tree_config 在导出/导入链路上丢失了。"
                + "\n  M 侧=" + mVal);
        assertEquals(mVal, nVal, AC + "：tree_config::text 往返后不同。\n  M 侧=" + mVal + "\n  N 侧=" + nVal);
        long inN = count("SELECT count(*) FROM component WHERE directory_id='" + toN.targetDir()
                + "'::uuid AND tree_config IS NOT NULL");
        assertEquals(1, inN, AC + "：N 目录内 tree_config 非空的组件应为 1 个，实际 " + inN + " 个。");
        System.out.println("[" + AC + "] ✅ tree_config 往返保真：" + nVal);
    }

    @Test
    @DisplayName("AC-5 视图 status='INACTIVE' 往返后仍是 INACTIVE，不被悄悄激活")
    void ac5_inactiveSqlViewStatusSurvivesRoundTrip() {
        final String AC = "AC-5";
        UUID src = sourceDirectoryId();
        RoundTrip toM = roundTrip(src, "AC5-M", AC);
        UUID dirM = toM.targetDir();

        // ── 造数：M 里挑一个视图置 INACTIVE；🚨 必须留下另一个 ACTIVE 视图作阳性对照 ──
        List<Object[]> views = rows("SELECT v.id::text, v.sql_view_name, c.id::text"
                + "  FROM component_sql_view v JOIN component c ON c.id = v.component_id"
                + " WHERE c.directory_id='" + dirM + "'::uuid ORDER BY v.sql_view_name");
        assertTrue(views.size() >= 2, AC + " 前置未满足：副本 M 里只有 " + views.size() + " 个视图，"
                + "无法同时构造「一个 INACTIVE + 至少一个 ACTIVE 作阳性对照」"
                + "⇒ 少了对照，「全被置成 INACTIVE」这种坏法会被当成通过。");
        UUID victimView = UUID.fromString(String.valueOf(views.get(0)[0]));
        String victimName = String.valueOf(views.get(0)[1]);
        UUID victimComp = UUID.fromString(String.valueOf(views.get(0)[2]));

        int n = exec("UPDATE component_sql_view SET status='INACTIVE' WHERE id='" + victimView + "'::uuid"
                + " AND component_id IN (SELECT id FROM component WHERE directory_id='" + dirM + "'::uuid)");
        assertEquals(1, n, AC + "：造数 UPDATE 应命中恰好 1 行，实际 " + n + " 行。");

        long mInactive = count("SELECT count(*) FROM component_sql_view v JOIN component c ON c.id=v.component_id"
                + " WHERE c.directory_id='" + dirM + "'::uuid AND v.status='INACTIVE'");
        long mActive = count("SELECT count(*) FROM component_sql_view v JOIN component c ON c.id=v.component_id"
                + " WHERE c.directory_id='" + dirM + "'::uuid AND v.status='ACTIVE'");
        assertEquals(1, mInactive, AC + "：M 目录内 INACTIVE 视图应为 1 个，实际 " + mInactive + " 个 ⇒ 样本没造对。");
        assertTrue(mActive > 0, AC + "：M 目录内没有 ACTIVE 视图了 ⇒ 阳性对照丢失。");
        System.out.println("[" + AC + "] M 侧造数：视图 " + victimName + " 已置 INACTIVE（同目录另有 " + mActive + " 个 ACTIVE）");

        // ── M → 包 → N ──
        RoundTrip toN = roundTrip(dirM, "AC5-N", AC);
        UUID dstComp = dstOf(toN, victimComp, dirM, AC);

        Map<String, Map<String, String>> nViews = viewsOf(dstComp, List.of("sql_view_name", "status"));
        Map<String, String> row = nViews.get(victimName);
        // 两种坏法给两种文案：① 视图整条没导进来（导出端把 INACTIVE 过滤掉了）；② 被改写成 ACTIVE。
        assertNotNull(row, AC + "：往返后 N 侧找不到视图「" + victimName + "」（N 侧视图名集合=" + nViews.keySet() + "）"
                + " ⇒ 导出或导入把非 ACTIVE 视图整条丢掉了。这与「被悄悄激活」是两种不同的坏法，请按本条定位。");
        assertEquals("INACTIVE", row.get("status"), AC + "：视图「" + victimName + "」往返后 status 应为 INACTIVE，实际「"
                + row.get("status") + "」⇒ 需求文档 §② 记载的「导入端硬编码 ACTIVE，非 ACTIVE 视图会被悄悄激活」复现了。");

        long nInactive = count("SELECT count(*) FROM component_sql_view v JOIN component c ON c.id=v.component_id"
                + " WHERE c.directory_id='" + toN.targetDir() + "'::uuid AND v.status='INACTIVE'");
        long nActive = count("SELECT count(*) FROM component_sql_view v JOIN component c ON c.id=v.component_id"
                + " WHERE c.directory_id='" + toN.targetDir() + "'::uuid AND v.status='ACTIVE'");
        assertEquals(mInactive, nInactive, AC + "：N 目录内 INACTIVE 视图数应与 M 相同（" + mInactive + "），实际 " + nInactive + "。");
        // 🚨 阳性对照：不能靠「把所有视图都写成 INACTIVE」来通过本条。
        assertEquals(mActive, nActive, AC + "：N 目录内 ACTIVE 视图数应与 M 相同（" + mActive + "），实际 " + nActive
                + " ⇒ status 被整体改写了，不是逐条保真。");
        System.out.println("[" + AC + "] ✅ N 侧：INACTIVE=" + nInactive + " / ACTIVE=" + nActive + "，与 M 一致。");
    }

    @Test
    @DisplayName("AC-6 补强 · 造数后的 M → N 全字段差集仍为 0（含 tree_config 与 INACTIVE 视图）")
    void ac6_secondHopDiffIsEmptyWithCraftedFields() {
        final String AC = "AC-6(M→N)";
        UUID src = sourceDirectoryId();
        List<String> compCols = comparedColumns("component", COMPONENT_WHITELIST);
        List<String> viewCols = comparedColumns("component_sql_view", SQLVIEW_WHITELIST);
        assertDiffEngineIsWired(src, compCols, AC);

        RoundTrip toM = roundTrip(src, "AC6B-M", AC);
        UUID dirM = toM.targetDir();

        // 造齐两种现网没有的形态，让本轮差集真的覆盖到 tree_config 与非 ACTIVE 视图。
        UUID tc = anyComponentIn(dirM, AC);
        assertEquals(1, exec("UPDATE component SET tree_config=" + lit(TREE_CONFIG_SAMPLE) + "::jsonb"
                + " WHERE id='" + tc + "'::uuid AND directory_id='" + dirM + "'::uuid"), AC + "：tree_config 造数未命中 1 行。");
        List<Object> vs = col("SELECT v.id::text FROM component_sql_view v JOIN component c ON c.id=v.component_id"
                + " WHERE c.directory_id='" + dirM + "'::uuid ORDER BY v.sql_view_name LIMIT 1");
        assertFalse(vs.isEmpty(), AC + "：M 里一个视图都没有 ⇒ 视图侧的差集会空跑。");
        assertEquals(1, exec("UPDATE component_sql_view SET status='INACTIVE' WHERE id='" + vs.get(0) + "'::uuid"),
                AC + "：status 造数未命中 1 行。");

        RoundTrip toN = roundTrip(dirM, "AC6B-N", AC);

        Map<String, UUID> mIds = componentIdsByCode(dirM);
        List<UUID> mList = new ArrayList<>(), nList = new ArrayList<>();
        for (Map.Entry<String, UUID> e : mIds.entrySet()) {
            UUID d = toN.dstIdByOrigCode().get(e.getKey());
            assertNotNull(d, AC + "：M 的组件 " + e.getKey() + " 在 N 侧找不到落点。");
            mList.add(e.getValue());
            nList.add(d);
        }
        Map<String, String> mNorm = new LinkedHashMap<>(), nNorm = new LinkedHashMap<>();
        buildTokenMaps(mList, nList, "C", mNorm, nNorm);
        buildTokenMaps(viewIdsOf(mList), viewIdsOf(nList), "V", mNorm, nNorm);
        buildCodeTokenMaps(toN.finalCodeByOrigCode(), mNorm, nNorm);   // 跨组件引用的第二种载体：code

        List<Diff> raw = new ArrayList<>(), norm = new ArrayList<>();
        int pairs = 0, viewPairs = 0;
        for (int i = 0; i < mList.size(); i++) {
            String key = "M:" + mList.get(i) + " → N:" + nList.get(i);
            Map<String, String> a = rowText("component", compCols, mList.get(i));
            Map<String, String> b = rowText("component", compCols, nList.get(i));
            raw.addAll(diffRow(key, "component", compCols, a, b, null, null));
            norm.addAll(diffRow(key, "component", compCols, a, b, mNorm, nNorm));
            pairs++;
            Map<String, Map<String, String>> av = viewsOf(mList.get(i), viewCols);
            Map<String, Map<String, String>> bv = viewsOf(nList.get(i), viewCols);
            assertEquals(av.keySet(), bv.keySet(), AC + "：" + key + " 视图名集合两边不等。");
            for (String vn : av.keySet()) {
                raw.addAll(diffRow(key + "/" + vn, "component_sql_view", viewCols, av.get(vn), bv.get(vn), null, null));
                norm.addAll(diffRow(key + "/" + vn, "component_sql_view", viewCols, av.get(vn), bv.get(vn), mNorm, nNorm));
                viewPairs++;
            }
        }
        assertTrue(pairs > 0 && viewPairs > 0, AC + "：组件 " + pairs + " 对 / 视图 " + viewPairs + " 对 ⇒ 有一侧 0 次循环，断言未执行。");
        if (!raw.isEmpty()) System.out.println("[" + AC + "] 原始（未归一化）差异 " + raw.size() + " 条：" + fmt(raw));
        assertTrue(norm.isEmpty(), AC + "：归一化 id 后差集仍有 " + norm.size() + " 条（应为 0）：" + fmt(norm));
        System.out.println("[" + AC + "] ✅ 组件 " + pairs + " 对 / 视图 " + viewPairs + " 对，归一化后差异 0 条。");
    }

    // ═══════════════════════════ 辅助 ═══════════════════════════

    private UUID anyComponentIn(UUID dir, String acRef) {
        List<Object> ids = col("SELECT id::text FROM component WHERE directory_id='" + dir + "'::uuid ORDER BY code LIMIT 1");
        assertFalse(ids.isEmpty(), acRef + "：目录 " + dir + " 里一个组件都没有 ⇒ 无处造数。");
        return UUID.fromString(String.valueOf(ids.get(0)));
    }

    /** M 侧组件 → N 侧对应组件（经 M 的 code 与提交响应的 originalCode 配对）。 */
    private UUID dstOf(RoundTrip toN, UUID mComponentId, UUID dirM, String acRef) {
        String code = scalar("SELECT code FROM component WHERE id='" + mComponentId + "'::uuid"
                + " AND directory_id='" + dirM + "'::uuid");
        assertNotNull(code, acRef + "：在 M 里找不到组件 " + mComponentId + " 的 code。");
        UUID d = toN.dstIdByOrigCode().get(code);
        assertNotNull(d, acRef + "：M 的组件 " + code + " 在 N 侧找不到落点 ⇒ 它根本没被导入，本条 AC 的断言无从谈起。");
        return d;
    }
}
