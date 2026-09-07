package com.cpq.task260907;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * <b>AC-8 / AC-9 / AC-10</b> —— 三条反向护栏（防误伤）。
 *
 * <h3>AC 原文（需求文档.md §③）</h3>
 * <ul>
 *   <li><b>AC-8（非树数据源不受 F-3 影响）</b>：「物料」「自制加工费」「材质元素」等非树数据源生成的 SQL
 *       <b>不带</b>父子列、<b>不做</b>闭包展开，与改动前<b>逐字相同</b>。</li>
 *   <li><b>AC-9（task-260904 的收缩成果不被改坏）</b>：① 数据源下拉不含已退役的「零件/外购件」，
 *       且「物料BOM」label 只出现一次；② QUOTE「材质元素」仍只有 1 个 {@code groupKind='PRICE'} 分组；
 *       ③ 组件详情表单仍无「页签类型」下拉（E2E 承担）；④ 传入已退役的 {@code tabType=零件} 仍返 200。</li>
 *   <li><b>AC-10（存量组件零变化）</b>：① 未绑数据源的组件，其 {@code tab_type}/{@code data_driver_path}/
 *       字段配置一字不变（改动前后逐行 md5 比对）；② 存量 {@code tab_type='BOM'} 的组件渲染结果不变。</li>
 * </ul>
 *
 * <h3>🚨 A 侧基线纪律（test.md §2）</h3>
 * A 侧采于 {@code master = a81f2c40}，🚫 <b>不重采</b> —— 重采 = 拿当前值当基线 = 断言退化成恒真。
 * 本类只采 B 侧再 diff。
 * <p>⚠️ diff 出差异时 🚫 不许直接归因「本次引入」：共享 dev 库有多条线并发写入。
 * ⇒ 本类把差异按<b>三类</b>分开报，只有第 ① 类才构成本任务的失败：
 * <ol>
 *   <li><b>基线里有、现在变了</b> → 真差异，硬失败（这是 AC-10 要防的）；</li>
 *   <li><b>基线里有、现在没了</b> → 硬失败（组件被删）；</li>
 *   <li><b>基线里没有、现在多出来</b> → 只打印。多半是并发线/别的测试新建的组件，
 *       把它算成失败会让本条变成一条随共享库漂移的不稳定用例。</li>
 * </ol>
 *
 * <h3>🚨 守卫顺序（test.md §3④，本任务线真踩过）</h3>
 * 主线采基线时脚本产出空集，而守卫写的是「收集不合格项，列表为空即通过」⇒ <b>全都没采到时恒真通过</b>。
 * ⇒ 本类每条 diff 都<b>先断言基线非空、再断言交集非空</b>，然后才断言元素一致。
 */
@QuarkusTest
@DisplayName("task-260907 · AC-8/9/10 —— 反向护栏：非树 SQL 逐字不变 · 260904 成果不倒退 · 存量组件零变化")
class ZeroChangeGuardAcTest extends Task260907Base {

    private static final List<String> RETIRED_TAB_TYPES = List.of("零件", "外购件");

    // ═══════════════════════════════════════════════════════════════════
    // AC-8 —— 非树数据源的编译产物与 A 侧逐字相同
    // ═══════════════════════════════════════════════════════════════════

    /**
     * A 侧编译产物基线。
     * <p>📌 <b>这是本轮补采的 A 侧</b>（{@code 证据/baseline/compile-A/compile-A.json}）：
     * test.md §2 的三份基线里<b>没有编译产物</b>，而 AC-8 要的是「与改动前逐字相同」。
     * 采集时点 2026-09-07，源是<b>主仓 8081</b>（后端源码干净 = master 代码，本任务后端一行未落），
     * 与 test.md §2 的基线同源同时点。🚫 同样不重采。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> compileBaseline() {
        File f = locateBaseline("compile-A/compile-A.json");
        assertNotNull(f, "AC-8：找不到 A 侧编译基线 compile-A/compile-A.json ⇒ 「与改动前逐字相同」无从比对。"
                + "从 scratch 隔离副本跑时要传 -Dtask260907.baseline.dir=<worktree>/dev-docs/task-260907-取数配置器补齐/证据/baseline");
        try {
            String json = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            Map<String, Map<String, Object>> parsed =
                    (Map<String, Map<String, Object>>) io.restassured.path.json.JsonPath.from(json).get("");
            assertFalse(parsed.isEmpty(), "AC-8：A 侧编译基线为空 ⇒ 「逐字相同」会退化成空比空（恒真）。文件=" + f);
            return parsed;
        } catch (Exception e) {
            throw new AssertionError("AC-8：读 A 侧编译基线失败：" + f + " → " + e, e);
        }
    }

    /** A 侧基线里那 4 个坐标对应的 builder_config。与采集脚本一字对应。 */
    private Map<String, String> abConfigs() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("MATERIAL", cfg("QUOTE", "主件", "",
                colJson("MATERIAL", "material_no", "料号", true, true),
                colJson("MATERIAL", "material_name", "品名")));
        m.put("SELF_PROCESS_FEE", cfg("QUOTE", "费用类", "SELF_PROCESS_FEE",
                colJson("SELF_PROCESS_FEE", "input_material_no", "投入料号", true, true),
                colJson("SELF_PROCESS_FEE", "value", "自制加工费")));
        m.put("ELEMENT_BOM", cfg("QUOTE", "材质元素", "",
                colJson("ELEMENT_BOM", "material_part_no", "料号", true, true),
                colJson("ELEMENT_BOM", "content_pct", "含量")));
        m.put("MATERIAL_BOM", cfg("QUOTE", "BOM", "",
                colJson("MATERIAL_BOM", "input_material_no", "投入料号", true, true),
                colJson("MATERIAL_BOM", "component_qty", "组成数量")));
        return m;
    }

    @Test
    @DisplayName("AC-8：三个【非树】数据源（物料 / 自制加工费 / 材质元素）的编译产物与 A 侧【逐字相同】；"
            + "且都不含 parent_no / UNION ALL 根分支 / WITH RECURSIVE")
    void ac8_nonTreeSourcesByteIdenticalToBaseline() {
        Map<String, Map<String, Object>> base = compileBaseline();
        Map<String, String> configs = abConfigs();
        List<String> nonTree = List.of("MATERIAL", "SELF_PROCESS_FEE", "ELEMENT_BOM");

        // 守卫顺序：先证明基线里这三项都在（否则下面的循环可能一条都不跑）
        for (String k : nonTree) {
            assertTrue(base.containsKey(k), "AC-8：A 侧基线缺少 " + k + " ⇒ 该源的「逐字相同」根本没被比对。基线键=" + base.keySet());
        }

        UUID cid = createBlankComponent("ac8");
        List<String> drift = new ArrayList<>();
        for (String k : nonTree) {
            String aSql = String.valueOf(base.get(k).get("sql"));
            assertFalse(aSql.isBlank(), "AC-8：基线里 " + k + " 的 SQL 是空串 ⇒ 比对恒真");
            String bSql = compileSql(cid, configs.get(k), "AC-8(" + k + ")");

            // 结构层（就算基线漂了，这三条也必须成立）
            assertFalse(bSql.contains("parent_no"), "AC-8：非树数据源 " + k + " 的 SQL 出现了 parent_no ⇒ F-3 误伤。SQL=\n" + bSql);
            assertFalse(bSql.toUpperCase().contains("UNION ALL"), "AC-8：非树数据源 " + k
                    + " 的 SQL 出现了 UNION ALL 根分支 ⇒ F-3 误伤。SQL=\n" + bSql);
            assertFalse(bSql.toUpperCase().contains("WITH RECURSIVE"), "AC-8：非树数据源 " + k
                    + " 的 SQL 出现了 WITH RECURSIVE ⇒ 闭包展开被误加。SQL=\n" + bSql);

            if (!aSql.equals(bSql)) {
                drift.add("\n──── " + k + " 与 A 侧不一致\n  A(md5=" + md5(aSql) + "):\n" + aSql
                        + "\n  B(md5=" + md5(bSql) + "):\n" + bSql);
            } else {
                System.out.println("[AC-8] ✅ " + k + " 与 A 侧逐字相同（md5=" + md5(bSql) + "）");
            }
        }
        assertTrue(drift.isEmpty(), "AC-8：非树数据源的编译产物与改动前不一致（A 侧采于 master=a81f2c40）。"
                + "⚠️ 判定前先确认不是并发线改的语义图种子导致 —— 但无论根因是谁，本条都必须停下来查，"
                + "🚫 不许直接改基线让它变绿。" + drift);
    }

    @Test
    @DisplayName("AC-8 阳性对照 + AC-4 佐证：A 侧基线里【树】数据源的 SQL 确实缺 parent_no —— "
            + "证明基线不是空壳、且 F-3 修的确有其事")
    void ac8_baselineIsRealAndTreeSideWasBroken() {
        Map<String, Map<String, Object>> base = compileBaseline();
        assertTrue(base.containsKey("MATERIAL_BOM"), "A 侧基线缺 MATERIAL_BOM");
        String aTree = String.valueOf(base.get("MATERIAL_BOM").get("sql"));
        System.out.println("[AC-8对照] A 侧树 SQL:\n" + aTree);
        assertFalse(aTree.isBlank(), "A 侧树 SQL 为空 ⇒ 基线是空壳，AC-8 的比对不可信");
        assertFalse(aTree.contains("parent_no"),
                "🚨 A 侧基线里【树】数据源的 SQL 竟已含 parent_no ⇒ 基线不是改动前的状态（可能采晚了、"
                        + "或采到了别人的服务端）。此时 AC-8/AC-4 的 A/B 论证全部失效，判【未验证】。A 侧 SQL=\n" + aTree);
        assertTrue(aTree.contains("material_no = ANY(:total_material_no)"),
                "🚨 A 侧基线里树 SQL 的过滤不在父件列上 ⇒ 与需求文档记录的缺陷形态不符，基线可疑。A 侧 SQL=\n" + aTree);
        System.out.println("[AC-8对照] ✅ A 侧确为「无 parent_no + 过滤在父件列」的缺陷形态，基线可信");
    }

    // ═══════════════════════════════════════════════════════════════════
    // AC-9 —— task-260904 的收缩成果不被改坏
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-9①：三方言的 availableSources 均不含已退役的「零件/外购件」，且「物料BOM」label 只出现一次；"
            + "阳性对照 —— 库里那 6 行仍在且仍 ACTIVE（收缩发生在 API 出口，不是把数据停用）")
    void ac9_retiredTabTypesStillFilteredAtApiOnly() {
        assertDialectParamIsHonored("AC-9①");

        // 阳性对照：若这 6 行被停用/删除，「清单里没有」会以另一种原因成立，用例照样绿却掩盖了一次破坏
        long retiredActive = count("SELECT count(*) FROM semantic_tab_view WHERE status='ACTIVE' "
                + "AND tab_type IN ('" + String.join("','", RETIRED_TAB_TYPES) + "')");
        assertEquals(RETIRED_TAB_TYPES.size() * (long) DIALECTS.size(), retiredActive,
                "AC-9① 阳性对照：semantic_tab_view 里「零件/外购件」的 ACTIVE 行应为 3 方言 × 2 类 = 6 行"
                        + "（task-260904 S-4：45 行一行不动、全部保持 ACTIVE，30 个存量组件靠它才打得开），实际=" + retiredActive);

        for (String dialect : DIALECTS) {
            List<Map<String, Object>> sources = availableSources(dialect, "AC-9①(" + dialect + ")");
            for (Map<String, Object> s : sources) {
                assertFalse(RETIRED_TAB_TYPES.contains(String.valueOf(s.get("tabType"))),
                        "AC-9①：" + dialect + " 的数据源清单出现了已退役的 tabType=" + s.get("tabType")
                                + "（项=" + s + "）⇒ task-260904 的退役过滤被改坏。");
            }
            List<String> labels = labelsOf(sources);
            List<String> dup = labels.stream().filter(l -> labels.indexOf(l) != labels.lastIndexOf(l)).distinct().toList();
            assertTrue(dup.isEmpty(), "AC-9①：" + dialect + " 的数据源 label 出现重复 =" + dup
                    + "。🔑 BOM/零件/外购件三坐标共用锚点 MATERIAL_BOM、label 都叫「物料BOM」，"
                    + "退役过滤一旦被移除就会出现三个同名项 —— 这是最灵敏的症状。实际清单=" + labels);
            System.out.println("[AC-9①] ✅ " + dialect + " 清单 " + labels.size() + " 项无退役值、无重名");
        }
    }

    @Test
    @DisplayName("AC-9②：QUOTE「材质元素」仍恰好 1 个 groupKind='PRICE' 分组（task-260904 AC-30 修好的重复组不得回归）")
    void ac9_priceGroupStillSingle() {
        List<Object[]> rs = rows("SELECT v.tab_type, coalesce(v.variant_key,'') FROM semantic_tab_view v "
                + "JOIN semantic_node n ON n.id=v.anchor_node_id "
                + "WHERE v.dialect='QUOTE' AND v.status='ACTIVE' AND v.tab_type='材质元素'");
        assertFalse(rs.isEmpty(), "AC-9② 前置：QUOTE 下找不到「材质元素」坐标 ⇒ 断言空跑");
        List<Group> groups = groupsOf(String.valueOf(rs.get(0)[0]), String.valueOf(rs.get(0)[1]), "QUOTE", "AC-9②");
        List<Group> price = groups.stream().filter(g -> "PRICE".equals(g.groupKind())).toList();
        System.out.println("[AC-9②] QUOTE/材质元素 分组="
                + groups.stream().map(g -> g.groupKey() + "(" + g.groupKind() + ")").toList());
        assertEquals(1, price.size(), "AC-9②：QUOTE「材质元素」的 PRICE 分组应恰好 1 个"
                + "（task-260904 AC-30 修复前为 2 个重复组），实际=" + price.size() + " → " + price);
    }

    @Test
    @DisplayName("AC-9④：传入已退役的 tabType=零件 / 外购件，field-tree 仍返 200 —— "
            + "收缩发生在【入口】(availableSources 不列出)，不在【校验】，否则存量组件当场打不开")
    void ac9_retiredTabTypeStillOpensWith200() {
        for (String retired : RETIRED_TAB_TYPES) {
            Response r = fieldTree(retired, "", "QUOTE");
            System.out.println("[AC-9④] field-tree(tabType=" + retired + ") → HTTP " + r.statusCode());
            assertEquals(200, r.statusCode(), "AC-9④：传入已退役的 tabType=" + retired + " 应仍返 200"
                    + "（api.md §1.5 的红字：否则 142 个存量组件会当场打不开）。实际=" + r.statusCode()
                    + " body=" + r.asString());
            assertNotNull(r.jsonPath().get("groups"), "AC-9④：tabType=" + retired + " 返 200 但没有 groups ⇒ 打开后是空面板");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // AC-10 —— 存量组件零变化
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-10①：A 侧基线 components.txt 里的每个组件，其 tab_type / data_driver_path / "
            + "bom_recursive_expand / fields 的 md5 逐行不变（新增组件只打印不判失败）")
    void ac10_existingComponentsUnchanged() {
        File f = locateBaseline("components.txt");
        assertNotNull(f, "AC-10①：找不到 A 侧基线 components.txt。从 scratch 副本跑要传 -Dtask260907.baseline.dir=…");
        List<String> lines;
        try {
            lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("AC-10①：读基线失败 " + f + " → " + e, e);
        }
        // 守卫顺序①：先证明基线非空
        Map<String, String> baseline = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.isBlank()) continue;
            int i = line.indexOf('|');
            assertTrue(i > 0, "AC-10①：基线行格式异常（应为 id|tab_type|ddp|expand|md5）：" + line);
            baseline.put(line.substring(0, i), line.substring(i + 1));
        }
        assertFalse(baseline.isEmpty(), "AC-10①：A 侧基线 components.txt 为空 ⇒ 比对退化成恒真通过。文件=" + f);
        System.out.println("[AC-10①] A 侧基线 " + baseline.size() + " 行（README 记 222 行）");

        // B 侧现采（同一口径）
        Map<String, String> now = new LinkedHashMap<>();
        for (Object[] r : rows("SELECT c.id::text, coalesce(c.tab_type,''), coalesce(c.data_driver_path,''), "
                + "coalesce(c.bom_recursive_expand,false)::text, md5(coalesce(c.fields::text,'')) "
                + "FROM component c ORDER BY c.id")) {
            now.put(String.valueOf(r[0]), r[1] + "|" + r[2] + "|" + r[3] + "|" + r[4]);
        }
        assertFalse(now.isEmpty(), "AC-10①：B 侧一个组件都没采到 ⇒ 比对不可信");

        // 守卫顺序②：先证明交集非空，再断言交集内一致
        Set<String> common = new LinkedHashSet<>(baseline.keySet());
        common.retainAll(now.keySet());
        assertFalse(common.isEmpty(), "AC-10①：A/B 两侧组件 id 交集为空 ⇒ 逐行比对一条都没跑（恒真通过）。"
                + "A=" + baseline.size() + " B=" + now.size());
        System.out.println("[AC-10①] 交集 " + common.size() + " 个组件参与逐行比对");

        List<String> changed = new ArrayList<>();
        for (String id : common) {
            if (!baseline.get(id).equals(now.get(id))) {
                changed.add("\n  " + id + "\n    A=" + baseline.get(id) + "\n    B=" + now.get(id));
            }
        }
        List<String> vanished = baseline.keySet().stream().filter(id -> !now.containsKey(id)).toList();
        List<String> added = now.keySet().stream().filter(id -> !baseline.containsKey(id)).toList();
        System.out.println("[AC-10①] 变更=" + changed.size() + " 消失=" + vanished.size()
                + " 新增=" + added.size() + "（新增只打印，多半是并发线/别的用例建的，🚫 不作失败判据）");
        if (!added.isEmpty()) System.out.println("[AC-10①] 新增 id=" + added);

        assertTrue(vanished.isEmpty(), "AC-10①：基线里的组件消失了 =" + vanished + " ⇒ 存量组件被删。");
        assertTrue(changed.isEmpty(), "AC-10①：存量组件配置发生了变化（应一字不变）。"
                + "⚠️ 判为『本次引入』前先对照干净 master 做 A/B —— 共享 dev 库有多条线并发写入。" + changed);
    }

    @Test
    @DisplayName("AC-10②：A 侧基线 tree-components.txt 里 21 个存量树组件的视图 md5 逐行不变")
    void ac10_existingTreeComponentViewsUnchanged() {
        File f = locateBaseline("tree-components.txt");
        assertNotNull(f, "AC-10②：找不到 A 侧基线 tree-components.txt");
        List<String> baseline;
        try {
            baseline = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8).stream()
                    .filter(s -> !s.isBlank()).toList();
        } catch (Exception e) {
            throw new AssertionError("AC-10②：读基线失败 " + f + " → " + e, e);
        }
        assertFalse(baseline.isEmpty(), "AC-10②：A 侧树组件基线为空 ⇒ 比对恒真通过");
        System.out.println("[AC-10②] A 侧基线 " + baseline.size() + " 行（README 记 21 行）");

        List<String> now = new ArrayList<>();
        // 🔑 口径与基线采集脚本一致：col1 = 组件名（不是 tab_type）。
        //    本轮已在改动前实测「本查询 ↔ 基线文件」逐行完全复现（21/21），确认口径没歪 ——
        //    否则 AC-10② 会因口径不同而红成看似回归。
        for (Object[] r : rows("SELECT c.name, coalesce(c.data_driver_path,''), "
                + "md5(coalesce(v.sql_template,'')) FROM component c "
                + "LEFT JOIN component_sql_view v ON v.component_id = c.id "
                + "WHERE c.tab_type='BOM' OR c.bom_recursive_expand = true "
                + "ORDER BY 1,2,3")) {
            now.add(r[0] + "|" + r[1] + "|" + r[2]);
        }
        assertFalse(now.isEmpty(), "AC-10②：B 侧一个树组件都没采到 ⇒ 比对不可信");

        // 基线文件是按同样口径排序落盘的；这里按多重集比对，避免排序细节差异造成假红
        List<String> sortedBase = new ArrayList<>(baseline);
        List<String> sortedNow = new ArrayList<>(now);
        sortedBase.sort(String::compareTo);
        sortedNow.sort(String::compareTo);
        System.out.println("[AC-10②] B 侧 " + sortedNow.size() + " 行");

        List<String> lost = new ArrayList<>(sortedBase);
        sortedNow.forEach(lost::remove);
        List<String> extra = new ArrayList<>(sortedNow);
        sortedBase.forEach(extra::remove);
        if (!extra.isEmpty()) System.out.println("[AC-10②] 多出来的行（只打印，多半是并发线新建）=" + extra);
        assertTrue(lost.isEmpty(), "AC-10②：存量树组件的视图发生了变化或消失 —— 基线里有、现在没有的行 =" + lost
                + "\n⚠️ 判为『本次引入』前先对照干净 master 做 A/B。B 侧现状=" + sortedNow);
    }
}
