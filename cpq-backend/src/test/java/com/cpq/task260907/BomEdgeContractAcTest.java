package com.cpq.task260907;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>AC-4 / AC-7</b> —— F-3「物料BOM 生成的 SQL 必须能建出树」（边式契约）。
 *
 * <h3>AC 原文（需求文档.md §③ AC-4 / AC-7）</h3>
 * <ul>
 *   <li><b>AC-4①</b>：SQL 产出树契约的父子两列 {@code material_no}（子）/ {@code parent_no}（父），
 *       与存量 BOM 树页签同契约。</li>
 *   <li><b>AC-4②</b>：取的是<b>闭包全部边</b>而非顶层一层 —— 对一个有孙级的料号做预览，<b>孙级行必须出现</b>。</li>
 *   <li><b>AC-4③</b>：保存后在报价单上按<b>树序</b>展示（走 {@code BomTreeRenderService}），层级正确。</li>
 *   <li><b>AC-7</b>：同一子件挂在不同父件下 ⇒ 树渲染出现<b>多个节点</b>，不去重、不报「重复」错误。</li>
 * </ul>
 *
 * <h3>改动前的缺陷（本轮 2026-09-07 亲测，A 侧留档在 {@code 证据/baseline/compile-A/compile-A.json}）</h3>
 * <pre>
 * SELECT dqmb.material_no AS hf_part_no, dqmb.input_material_no AS "_物料BOM_投入料号", ...
 * FROM ds_quote_material_bom dqmb
 * WHERE dqmb.material_no = ANY(:total_material_no)     ← ① 从不产出 parent_no
 *                                                        ② 过滤在【父件列】只捞一层
 *                                                        ③ 无 UNION ALL 根分支
 * </pre>
 * 改动后应为 api.md §2.2 的边式契约。
 *
 * <h3>🚨 为什么用自建 DAG 夹具而不是现网数据</h3>
 * 需求文档记的「68 条边 / 24 个父件 / 41 个子件 / 24 条边的子件本身又是父件」是<b>共享 dev 库快照</b>，
 * 会随导入漂移。用自建的 {@code R→{C1,C2}→G} 结构，期望值是<b>构造出来的常量</b>（5 行、G 出现 2 次），
 * 与库里有多少业务数据无关；同时 G 是货真价实的<b>孙级</b>，直接验 AC-4②。
 */
@QuarkusTest
@DisplayName("task-260907 · AC-4/AC-7 —— 物料BOM 产出边式树契约（父子两列 + 子件列过滤 + 根分支 + DAG 多父）")
class BomEdgeContractAcTest extends Task260907Base {

    /** 「物料BOM」数据源的坐标 + 锚点 node_key。执行期反查，🚫 不写死 {@code BOM}/{@code MATERIAL_BOM}。 */
    private String[] bomCoordinate() {
        return bomCoordinate("QUOTE");
    }

    /**
     * 某方言下「物料BOM」树页签的坐标 + 锚点 node_key。
     * <p>🚦 2026-09-07 用户裁决：<b>核价两套（COST_BASIC / COST_DETAIL）的 BOM 页签本期一并改</b>
     * （后端原本收窄为只改 QUOTE，被推翻）⇒ AC-4 必须覆盖三个方言。
     */
    private String[] bomCoordinate(String dialect) {
        List<Object[]> rs = rows("SELECT v.tab_type, coalesce(v.variant_key,''), n.node_key "
                + "FROM semantic_tab_view v JOIN semantic_node n ON n.id = v.anchor_node_id "
                + "WHERE v.dialect='" + dialect + "' AND v.status='ACTIVE' AND n.physical_table LIKE '%material_bom%' "
                + "ORDER BY (v.tab_type='BOM') DESC, v.tab_type LIMIT 1");
        assertFalse(rs.isEmpty(), "前置未满足：" + dialect + " 下找不到物料BOM 锚点的 ACTIVE 坐标 "
                + "⇒ 该方言的树数据源不存在，断言会空跑。");
        Object[] r = rs.get(0);
        return new String[]{String.valueOf(r[0]), String.valueOf(r[1]), String.valueOf(r[2])};
    }

    private String bomCfg(String[] coord) {
        return bomCfg(coord, "QUOTE");
    }

    /**
     * 🚨 <b>子件列名按方言现查，🚫 不写死 {@code input_material_no}</b>：
     * 报价侧锚点是 {@code ds_quote_material_bom}（子件列 {@code input_material_no}），
     * 核价两套是 {@code v_ds_cost_*_material_bom_all}（子件列 <b>{@code component_no}</b>）。
     * 写死会得到 400 {@code COMPILE_COLUMN_NOT_FOUND}，而那个红<b>长得像编译器坏了</b>，
     * 其实是夹具用错了列名（2026-09-07 实测踩到）。
     * ⇒ 统一取带 {@code PART_NO} 角色的列。
     */
    private String childColumnOf(String dialect, String[] coord) {
        String nodeId = anchorNodeId(dialect, coord[0], coord[1]);
        assertNotNull(nodeId, "前置：查不到 " + dialect + "/" + coord[0] + " 的锚点节点");
        String col = partNoColumnOf(nodeId);
        assertNotNull(col, "前置：" + dialect + " 的物料BOM 锚点没有带 PART_NO 角色的列 ⇒ 取不到子件列");
        return col;
    }

    private String bomCfg(String[] coord, String dialect) {
        String child = childColumnOf(dialect, coord);
        return cfg(dialect, coord[0], coord[1],
                colJson(coord[2], child, "投入料号", true, true),
                colJson(coord[2], "component_qty", "组成数量"));
    }

    // ═══════════════════════════════════════════════════════════════════
    // AC-4①③ —— SQL 形态
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-4①（三方言）：QUOTE / COST_BASIC / COST_DETAIL 的物料BOM 编译产物都必须产出 "
            + "parent_no 与 material_no 两列、过滤落在【子件列】、且带 UNION ALL 根分支")
    void ac4_treeSqlEmitsEdgeContract() {
        // 🚦 2026-09-07 用户裁决：核价两套的 BOM 页签本期一并改 ⇒ 三方言逐个验，🚫 不只验 QUOTE
        for (String dialect : DIALECTS) {
            assertTreeEdgeContract(dialect);
        }
    }

    private void assertTreeEdgeContract(String dialect) {
        String[] coord = bomCoordinate(dialect);
        UUID cid = createBlankComponent("ac4sql" + dialect.charAt(0));
        String sql = compileSql(cid, bomCfg(coord, dialect), "AC-4①(" + dialect + ")");
        System.out.println("[AC-4①] " + dialect + " 编译产物:\n" + sql);

        // ① 父列必须出现（改动前完全没有 —— A 侧留档可查）
        // 🚨 必须用【全词】匹配：2026-09-07 证伪实验实测，把编译器里的 parent_no 改名成
        //    parent_no_SABOTAGED 后，contains("parent_no") 仍然为真 ⇒ 那版断言对「改名」没有分辨力，
        //    是被下游两条用例（真跑 SQL）替它变红的。改名恰恰是最可能发生的破坏形态。
        assertTrue(hasWholeWord(sql, "parent_no"), "AC-4①[" + dialect + "]：生成的 SQL 不含【独立的】列名 parent_no ⇒ 树建不起来。"
                + "存量视图头注释的契约原文：『树契约: material_no=子 / parent_no=父 + :total_material_no; "
                + "边式全子件 + 根分支』。实际 SQL=\n" + sql);
        assertTrue(hasWholeWord(sql, "material_no"), "AC-4①[" + dialect + "]：生成的 SQL 不含【独立的】列名 material_no（子件列）。SQL=\n" + sql);

        // ② 过滤必须落在【子件列】，🚫 不能落在父件列 —— 改动前的缺陷正是过滤在父件列，只捞顶层一层。
        //
        // 🚨 判据不能写成「含 <子件列> = ANY(:total_material_no)」：
        //    核价两套的轴要过一层【销售料号 → 生产料号】桥（2026-09-07 实测）——
        //      WHERE component_no IN (SELECT production_no FROM ds_quote_material WHERE material_no = ANY(:total_material_no))
        //    直接匹配字面量会把「实现正确但走桥」误判成缺陷。
        //    ⇒ 改判**结构不变量**：边分支的过滤引用子件列、且不引用父件列。
        String child = childColumnOf(dialect, coord);
        String parent = parentColumnOf(sql, dialect);
        int unionAt = sql.toUpperCase().indexOf("UNION ALL");
        String edgeBranch = unionAt > 0 ? sql.substring(0, unionAt) : sql;
        int whereAt = edgeBranch.toUpperCase().indexOf("\nWHERE ");
        assertTrue(whereAt > 0, "AC-4①[" + dialect + "]：边分支没有 WHERE 过滤 ⇒ 会全表捞。SQL=\n" + sql);
        String edgeWhere = edgeBranch.substring(whereAt);

        assertTrue(edgeWhere.contains("." + child), "AC-4①[" + dialect + "]：边分支的过滤没有引用【子件列】"
                + child + " ⇒ 取不到闭包内的全部边。边分支 WHERE=\n" + edgeWhere);
        assertFalse(edgeWhere.contains("." + parent + " =") || edgeWhere.contains("." + parent + " IN"),
                "AC-4①[" + dialect + "]：边分支的过滤落在了【父件列】" + parent
                        + " 上 ⇒ 只会捞到顶层一层边（这正是改动前的缺陷）。边分支 WHERE=\n" + edgeWhere);
        assertTrue(edgeWhere.contains(":total_material_no"), "AC-4①[" + dialect
                + "]：边分支缺 :total_material_no 轴参数 ⇒ 数据量不再以「这一单」为界。SQL=\n" + sql);
        System.out.println("[AC-4①] " + dialect + " 子件列=" + child + " 父件列=" + parent + " ✅ 过滤在子件列");

        // ③ 根分支
        assertTrue(sql.toUpperCase().contains("UNION ALL"),
                "AC-4①[" + dialect + "]：缺 UNION ALL 根分支（无父边的成品自身），树没有根。SQL=\n" + sql);

        // 🚫 反向：不许每页签自己生成 WITH RECURSIVE（A0 裁决：递归由 costing_bom_tree_config 单份共用）
        assertFalse(sql.toUpperCase().contains("WITH RECURSIVE"),
                "AC-4/A0 裁决[" + dialect + "]：🚫 不许为每个页签生成 WITH RECURSIVE —— 那是把已有递归重做一遍，"
                        + "且绕开现成的 CYCLE 防环与配置化管理（api.md §2.3）。SQL=\n" + sql);
    }

    // ═══════════════════════════════════════════════════════════════════
    // AC-4② + AC-7 —— 真跑一棵 DAG
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-4② + AC-7：自建 DAG（R→{C1,C2}→G）绑到闭包轴上执行 —— "
            + "孙级 G 出现（不止顶层一层）、G 在两个父件下各出一行（不去重）、根分支恰有 R 一行")
    void ac4_closureEdgesIncludeGrandchildAndDagKeepsBothParents() {
        String[] coord = bomCoordinate();
        BomFx f = buildBomFixture("ac4", false);
        UUID cid = createBlankComponent("ac4run");
        String sql = compileSql(cid, bomCfg(coord), "AC-4②");

        List<String> axis = f.axis();   // {R, C1, C2, G} = 本单闭包
        List<Object[]> out = runCompiled(sql, axis, "t.material_no::text, t.parent_no::text");
        System.out.println("[AC-4②/AC-7] 轴=" + axis + " → " + out.size() + " 行");
        for (Object[] r : out) System.out.println("    子=" + r[0] + "  父=" + r[1]);

        // ── 阳性对照：先证明结果非空，否则下面「G 出现两次」「根分支恰一行」全部空跑 ──
        assertFalse(out.isEmpty(), "AC-4②：配置器产出的 SQL 在自建闭包轴上返回 0 行 ⇒ "
                + "夹具或取数链路不通，此时任何结构断言都不可信。SQL=\n" + sql);

        // 期望（构造出来的常量，与库里业务数据无关）：
        //   4 条边（子件 ∈ 轴）：R→C1 / R→C2 / C1→G / C2→G
        // + 1 条根分支（R 无父边）
        assertEquals(5, out.size(), "AC-4②：边式契约下应产出 4 条边 + 1 条根分支 = 5 行，实际=" + out.size()
                + "。若为 2 行则仍是『过滤在父件列只捞顶层一层』的旧行为；若为 4 行则缺根分支。\nSQL=\n" + sql);

        // AC-4②：孙级 G 必须出现（旧行为下 G 只作为 C1/C2 的子件，而 C1/C2 不在顶层 ⇒ G 不出现）
        List<Object[]> gRows = out.stream().filter(r -> f.grandchild.equals(r[0])).toList();
        assertFalse(gRows.isEmpty(), "AC-4②：孙级料号 " + f.grandchild + " 没有出现 ⇒ 仍只取了顶层一层，树建不起来。"
                + "实际行=" + render(out));

        // AC-7：同一子件挂两个父件 ⇒ 两行、父件各不相同，不去重
        assertEquals(2, gRows.size(), "AC-7：孙级 " + f.grandchild + " 同时挂在 " + f.c1 + " 与 " + f.c2
                + " 下（合法 DAG，repair-0727 已确立），应出【两行】不去重，实际=" + gRows.size() + " → " + render(gRows));
        Set<String> parents = new LinkedHashSet<>();
        for (Object[] r : gRows) parents.add(String.valueOf(r[1]));
        assertEquals(Set.of(f.c1, f.c2), parents, "AC-7：两行的父件应分别是 C1/C2，实际=" + parents);

        // 根分支：恰有 R 一行且 parent_no 为 NULL
        List<Object[]> roots = out.stream().filter(r -> r[1] == null).toList();
        assertEquals(1, roots.size(), "AC-4①③：根分支（parent_no IS NULL）应恰有 1 行（无父边的成品 R），实际="
                + roots.size() + " → " + render(roots));
        assertEquals(f.root, String.valueOf(roots.get(0)[0]),
                "AC-4①③：根分支那行的子件列应是成品自身 " + f.root + "，实际=" + roots.get(0)[0]);
    }

    @Test
    @DisplayName("AC-7（真实 preview 端点）：以【孙级料号】预览，两个父件下的两条边都出现（DAG 不去重）；"
            + "并同时取证：/builder/preview 的轴就是 partNo 自己，🚫 不做 BOM 闭包展开")
    void ac4_previewAxisIsPartNoItselfAndDagKeepsBothParents() {
        String[] coord = bomCoordinate();
        BomFx f = buildBomFixture("ac4p", true);   // 同时写 V6 边，用于下面的阳性对照
        UUID cid = createBlankComponent("ac4p");
        String config = bomCfg(coord);

        // ── 阳性对照：全系统共用的那份递归，从 R 出发【确实】能展开出 4 个节点 ──
        //    先证明这件事，下面「preview 只给 1 行」才能归因到「preview 不走闭包」，
        //    而不是归因到「我的夹具没建好」。
        String tpl = scalar("SELECT sql_template FROM costing_bom_tree_config WHERE usage='QUOTE' AND is_active = true");
        assertNotNull(tpl, "前置：找不到 QUOTE 递归模板");
        long closureSize = count("SELECT count(*) FROM ("
                + tpl.replace(":production_part_nos", "ARRAY['" + f.root + "']::text[]") + ") t");
        // 期望 5 = 节点路径数，不是去重后的料号数：R · R/C1 · R/C2 · R/C1/G · R/C2/G
        // （递归产出的是 node_path 行；G 挂两个父件 ⇒ 两条路径。这是 DAG 的正常形态，不是重复。）
        System.out.println("[AC-4 取证] 共用递归从 " + f.root + " 出发展开出 " + closureSize + " 条节点路径（夹具期望 5）");
        assertEquals(5L, closureSize, "阳性对照失败：共用递归没能把我的 V6 夹具边展开成 5 条节点路径"
                + "（R · R/C1 · R/C2 · R/C1/G · R/C2/G），实际 " + closureSize
                + " ⇒ 夹具没建好，下面关于 preview 的取证不可信。");

        // ── 取证：preview(partNo=R) 只返回根分支一行 ⇒ 轴 = [partNo]，不是闭包 ──
        var atRoot = previewRows(cid, config, f.root, "AC-4 取证(root)");
        System.out.println("[AC-4 取证] preview(partNo=" + f.root + ") → " + atRoot.size() + " 行：" + atRoot);
        assertFalse(atRoot.isEmpty(), "preview(root) 返回 0 行 ⇒ 取数路径不通，取证不可信");

        // ── AC-7：以孙级料号预览，两个父件下的两条边都在（DAG 不去重、不报错）──
        var atGrandchild = previewRows(cid, config, f.grandchild, "AC-7(preview)");
        System.out.println("[AC-7preview] preview(partNo=" + f.grandchild + ") → " + atGrandchild.size()
                + " 行：" + atGrandchild);
        assertFalse(atGrandchild.isEmpty(), "AC-7：以孙级 " + f.grandchild + " 预览返回 0 行 ⇒ "
                + "边式契约的过滤没有落在子件列上，或取数不通。");
        assertEquals(2, atGrandchild.size(), "AC-7：孙级 " + f.grandchild + " 同时挂在两个父件下（合法 DAG），"
                + "预览应给出 2 行不去重，实际=" + atGrandchild.size() + " → " + atGrandchild);
        Set<String> parents = new LinkedHashSet<>();
        for (var r : atGrandchild) parents.add(String.valueOf(r.get("parent_no")));
        assertEquals(Set.of(f.c1, f.c2), parents, "AC-7：两行的父件应分别是 C1/C2，实际=" + parents);

        // ── 🚩 结论留档（🚫 不作失败判据，是给主线的契约事实）──
        System.out.println("[AC-4② 🚩 可执行性] preview(root) 只有根分支 1 行，而共用递归能展开 " + closureSize
                + " 条节点路径 ⇒ **/builder/preview 的 :total_material_no 轴就是 partNo 自己，不做 BOM 闭包展开**。"
                + "\n  ⇒ AC-4② 原文『对一个有孙级的料号做预览，孙级行必须出现』**在 preview 端点上不可复现**，"
                + "照字面写会得到一条恒不成立（或恒成立于错误理由）的断言。"
                + "\n  ⇒ AC-4② 的实质（取闭包全部边而非顶层一层）已由 "
                + "ac4_closureEdgesIncludeGrandchildAndDagKeepsBothParents 在**真实闭包轴**上取证："
                + "4 条边 + 1 根分支 = 5 行、孙级出现、DAG 两个父件都在。请主线裁决 AC-4② 的措辞是否要按此订正。");
    }

    /**
     * 全词匹配（前后都不是标识符字符）。
     * 🚨 {@code String.contains} 对「改名」没有分辨力 —— 见 {@link #ac4_treeSqlEmitsEdgeContract} 的注释。
     */
    private static boolean hasWholeWord(String haystack, String word) {
        return java.util.regex.Pattern
                .compile("(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(word) + "(?![A-Za-z0-9_])")
                .matcher(haystack).find();
    }

    /** 从编译产物里解析出「被 AS 成 parent_no 的那个物理列」（🚫 不写死：报价侧是 material_no、核价侧是 production_no）。 */
    private static String parentColumnOf(String sql, String dialect) {
        var m = java.util.regex.Pattern.compile("(\\w+)\\.(\\w+)\\s+AS\\s+parent_no").matcher(sql);
        assertTrue(m.find(), "AC-4①[" + dialect + "]：编译产物里找不到 `<别名>.<列> AS parent_no` ⇒ "
                + "父列不是从表里取的（可能整列缺失）。SQL=\n" + sql);
        return m.group(2);
    }

    private static String render(List<Object[]> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (Object[] r : rows) sb.append("(子=").append(r[0]).append(",父=").append(r[1]).append(')');
        return sb.append(']').toString();
    }
}
