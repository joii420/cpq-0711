package com.cpq.task260907;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * <b>AC-11</b> —— F-4「3 张年降表各自成为独立数据源」。
 *
 * <h3>AC 原文（需求文档.md §③ AC-11）</h3>
 * <ul>
 *   <li>① 数据源下拉出现三个新选项：年降系数 / 组装加工费年降 / 来料年降；</li>
 *   <li>② 各自选中后，字段面板出该表的<b>全部业务列</b>（物理列减系统列）；</li>
 *   <li>③ 拖入列后 SQL 编译通过、{@code FROM} 是对应表、带 {@code :total_material_no} 轴参数；</li>
 *   <li>④ <b>不并进「物料」页签</b> —— 选「物料」数据源时，面板里不出现这三张表的列（防行放大）。</li>
 * </ul>
 *
 * <h3>🚧 本条只能验到结构层（需求文档已登记为缺口）</h3>
 * 三张表实测<b>均 0 行</b> ⇒ 预览必然 0 行、渲染链路无法验证。
 * 🚫 <b>本类不写「预览返回 0 行」这类断言</b> —— 它恒真，写了反而掩盖「数据链路从没验过」。
 * ⇒ ③ 只验「编译产物的形态」，并<b>额外把编译出来的 SQL 真的拿到 PG 上执行一次</b>
 * （证明它语法合法、能被规划器接受），但<b>不对行数下任何结论</b>；
 * 同时用 {@link #ac11_zeroRowPremiseIsStillTrue} 把「三表 0 行」这个前提本身钉成一条会说话的记录：
 * 一旦业务导入了年降数据，那条会提示「缺口可以补验了」，而不是继续沉默。
 */
@QuarkusTest
@DisplayName("task-260907 · AC-11 —— 三张年降表各自独立成源（结构层；数据链路因 0 行未覆盖）")
class AnnualSourcesAcTest extends Task260907Base {

    /** 年降三表在语义图里的坐标：table → {tabType, variantKey, nodeKey}。执行期反查，🚫 不写死 sourceKey。 */
    private Map<String, String[]> annualCoordinates(String acRef) {
        Map<String, String[]> out = new LinkedHashMap<>();
        for (String table : ANNUAL_TABLES) {
            List<Object[]> rs = rows("SELECT v.tab_type, coalesce(v.variant_key,''), n.node_key "
                    + "FROM semantic_tab_view v JOIN semantic_node n ON n.id=v.anchor_node_id "
                    + "WHERE v.dialect='QUOTE' AND v.status='ACTIVE' AND n.physical_table='" + table + "'");
            assertFalse(rs.isEmpty(), acRef + "：语义图里没有以 " + table + " 为锚点的 QUOTE ACTIVE 坐标 "
                    + "⇒ 该表没有『独立成源』（AC-11①）。B-4 未落地或建成了附属节点。");
            assertEquals(1, rs.size(), acRef + "：" + table + " 应恰好对应 1 个坐标（一页签一表），实际=" + rs.size());
            Object[] r = rs.get(0);
            out.put(table, new String[]{String.valueOf(r[0]), String.valueOf(r[1]), String.valueOf(r[2])});
        }
        return out;
    }

    @Test
    @DisplayName("AC-11①：QUOTE 的 availableSources 新增三项（各对应一张年降表），"
            + "label 不重复、semantic 为显式 null；核价两方言【不】新增")
    void ac11_threeNewSourcesInQuoteOnly() {
        assertDialectParamIsHonored("AC-11①");

        Map<String, String[]> coords = annualCoordinates("AC-11①");
        List<Map<String, Object>> quote = availableSources("QUOTE", "AC-11①");
        Set<String> coordKeys = new LinkedHashSet<>();
        for (Map<String, Object> s : quote) {
            coordKeys.add(s.get("tabType") + "/" + (s.get("variantKey") == null ? "" : s.get("variantKey")));
        }
        System.out.println("[AC-11①] QUOTE availableSources 条数=" + quote.size()
                + "（立项时实测 11，预期 14 —— 🚫 数字仅记录不作断言）labels=" + labelsOf(quote));

        // ① 三个坐标必须都出现在清单里
        List<String> missing = new ArrayList<>();
        for (var e : coords.entrySet()) {
            String key = e.getValue()[0] + "/" + e.getValue()[1];
            if (!coordKeys.contains(key)) missing.add(e.getKey() + " → " + key);
        }
        assertTrue(missing.isEmpty(), "AC-11①：年降表的坐标没有出现在数据源下拉清单里 =" + missing
                + "。清单实有坐标=" + coordKeys);

        // label 无重复（下拉里出现两个同名项，用户根本分不清选的是哪张表）
        List<String> labels = labelsOf(quote);
        List<String> dup = labels.stream().filter(l -> labels.indexOf(l) != labels.lastIndexOf(l)).distinct().toList();
        assertTrue(dup.isEmpty(), "AC-11①：数据源 label 出现重复 =" + dup + "。实际清单=" + labels);

        // semantic 必须是【显式 null】而不是缺键（api.md §1.2 的红字：缺键会让前端 PRICE 组隐藏判断永不生效）
        for (Map<String, Object> s : quote) {
            assertTrue(s.containsKey("semantic"), "AC-11①/api.md §1.2：availableSources 项缺 semantic 键（"
                    + s + "）—— 前端用 undefined(清单未到手) 与 null(普通源) 区分两种状态，"
                    + "缺键会静默打断该设计。🚫 本任务不得引入 @JsonInclude(NON_NULL)。");
        }
        for (var e : coords.entrySet()) {
            String key = e.getValue()[0] + "/" + e.getValue()[1];
            Map<String, Object> item = quote.stream()
                    .filter(s -> key.equals(s.get("tabType") + "/" + (s.get("variantKey") == null ? "" : s.get("variantKey"))))
                    .findFirst().orElseThrow();
            assertEquals(null, item.get("semantic"), "AC-11①：年降源 " + e.getKey()
                    + " 应是普通平铺数据源（semantic=null），实际=" + item.get("semantic"));
        }

        // 反向：核价两方言不应新增这三项（本期只接报价侧）
        for (String dialect : List.of("COST_BASIC", "COST_DETAIL")) {
            Set<String> tables = new LinkedHashSet<>(strCol(
                    "SELECT n.physical_table FROM semantic_tab_view v JOIN semantic_node n ON n.id=v.anchor_node_id "
                            + "WHERE v.dialect='" + dialect + "' AND v.status='ACTIVE'"));
            assertFalse(tables.isEmpty(), "AC-11 反向：" + dialect + " 无任何 ACTIVE 坐标 ⇒ 断言空跑");
            List<String> leaked = ANNUAL_TABLES.stream().filter(tables::contains).toList();
            assertTrue(leaked.isEmpty(), "AC-11 反向：本期只接报价侧，但 " + dialect + " 也出现了年降表 " + leaked);
        }
    }

    @Test
    @DisplayName("AC-11②：三个年降源各自的字段面板出该表【全部业务列】（物理列减系统列，执行期现算）")
    void ac11_eachSourceExposesAllBusinessColumns() {
        Map<String, String[]> coords = annualCoordinates("AC-11②");
        for (var e : coords.entrySet()) {
            String table = e.getKey();
            String[] c = e.getValue();
            Set<String> expected = businessColumns(table);           // 现算，🚫 不写死 7/9/9
            Set<String> actual = allColumnsOf(groupsOf(c[0], c[1], "QUOTE", "AC-11②(" + table + ")"));
            System.out.println("[AC-11②] " + table + " 业务列(" + expected.size() + ")=" + expected
                    + "  面板列(" + actual.size() + ")=" + actual);

            assertNonEmptyThenAllMatch(actual, expected, table + " 的字段面板", "AC-11②");
            List<String> extra = actual.stream().filter(x -> !expected.contains(x)).toList();
            assertTrue(extra.isEmpty(), "AC-11②：" + table + " 的面板出现了非本表业务列 " + extra
                    + " ⇒ 一页签一表被打破。期望=" + expected + " 实际=" + actual);
        }
    }

    @Test
    @DisplayName("AC-11③：三个年降源各自拖入列后编译通过、FROM 是对应表、带 :total_material_no 轴参数，"
            + "且产出的 SQL 在 PG 上语法合法（🚫 不对行数下结论 —— 三表 0 行）")
    void ac11_compileArtifactShape() {
        Map<String, String[]> coords = annualCoordinates("AC-11③");
        for (var e : coords.entrySet()) {
            String table = e.getKey();
            String[] c = e.getValue();
            Set<String> biz = businessColumns(table);
            assertTrue(biz.contains("material_no"), "AC-11③ 前置：" + table + " 应有 material_no 作轴键，实际业务列=" + biz);
            String other = biz.stream().filter(x -> !x.equals("material_no")).findFirst().orElseThrow();

            UUID cid = createBlankComponent("ac11_" + table.substring(table.lastIndexOf('_') + 1));
            String sql = compileSql(cid, cfg("QUOTE", c[0], c[1],
                    colJson(c[2], "material_no", "料号", true, true),
                    colJson(c[2], other, other)), "AC-11③(" + table + ")");
            System.out.println("[AC-11③] " + table + " 编译产物:\n" + sql);

            assertTrue(sql.contains("FROM " + table), "AC-11③：" + table + " 的编译产物 FROM 不是本表。SQL=\n" + sql);
            assertTrue(sql.contains(":total_material_no"), "AC-11③：" + table
                    + " 的编译产物缺 :total_material_no 轴参数 ⇒ 会全表捞。SQL=\n" + sql);

            // 语法合法性：真的拿到 PG 上跑一次（用自建的不存在料号当轴 —— 只证明能被规划器接受）
            String probeAxis = PREFIX + RUN_ID + "_no_such_part";
            try {
                long n = runCompiledCount(sql, List.of(probeAxis));
                System.out.println("[AC-11③] " + table + " SQL 在 PG 上可执行（探测轴返回 " + n
                        + " 行 —— 🚫 该行数不构成任何结论，三表 0 行，数据链路【未验证】）");
            } catch (RuntimeException ex) {
                throw new AssertionError("AC-11③：" + table + " 的编译产物在 PG 上执行失败（语法/列名错）：" + ex
                        + "\nSQL=\n" + sql, ex);
            }
        }
    }

    @Test
    @DisplayName("AC-11④（反向 · 防行放大）：选「物料」数据源时，面板里【不】出现年降三表的判别列 —— "
            + "它们与物料是 1:N（都带 discount_seq），并进去会把物料行放大 N 倍")
    void ac11_annualColumnsNotMergedIntoMaterialPanel() {
        List<Object[]> rs = rows("SELECT v.tab_type, coalesce(v.variant_key,'') FROM semantic_tab_view v "
                + "JOIN semantic_node n ON n.id=v.anchor_node_id "
                + "WHERE v.dialect='QUOTE' AND v.status='ACTIVE' AND n.physical_table='" + MATERIAL_TABLE + "'");
        assertFalse(rs.isEmpty(), "AC-11④ 前置：QUOTE 下找不到「物料」坐标");
        Set<String> materialPanel = allColumnsOf(groupsOf(String.valueOf(rs.get(0)[0]), String.valueOf(rs.get(0)[1]),
                "QUOTE", "AC-11④"));
        assertFalse(materialPanel.isEmpty(), "AC-11④：物料面板列为空 ⇒「不含年降列」会恒真通过（空跑）");

        // 年降三表独有的业务列（减掉物料表也有的列，避免误判）
        Set<String> annualOnly = new LinkedHashSet<>();
        for (String t : ANNUAL_TABLES) annualOnly.addAll(businessColumns(t));
        annualOnly.removeAll(businessColumns(MATERIAL_TABLE));
        annualOnly.remove("material_no");
        assertFalse(annualOnly.isEmpty(), "AC-11④：算不出年降表独有列 ⇒ 断言空跑");

        List<String> leaked = annualOnly.stream().filter(materialPanel::contains).toList();
        assertTrue(leaked.isEmpty(), "AC-11④：「物料」面板里出现了年降表的列 " + leaked
                + " ⇒ 年降表被并进物料页签了。实测它们与物料是 1:N（都带 discount_seq），"
                + "并进去会把物料行放大 N 倍（需求文档 §② 不做什么）。物料面板列=" + materialPanel);
        System.out.println("[AC-11④] ✅ 物料面板未混入年降列（年降独有列 " + annualOnly.size() + " 个全部缺席）");
    }

    @Test
    @DisplayName("🚧 AC-11 缺口守卫：三张年降表当前均 0 行 ⇒ 数据渲染链路【未验证】。"
            + "一旦业务导入了数据，本条会说话，提醒补验（🚫 不是断言 0 行）")
    void ac11_zeroRowPremiseIsStillTrue() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String t : ANNUAL_TABLES) counts.put(t, count("SELECT count(*) FROM " + t));
        System.out.println("[AC-11缺口] 三表当前行数=" + counts);
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0) {
            System.out.println("[AC-11缺口] 🚧 三表仍全 0 行 ⇒ AC-11 判【结构层已验证 / 数据链路未验证】，"
                    + "缺口已在需求文档 §④ 登记。🚫 不写『预览返回 0 行』这类恒真断言。");
        } else {
            System.out.println("[AC-11缺口] 🔔 年降表已有数据（" + counts + "）⇒ "
                    + "AC-11 的数据渲染链路【现在可以补验了】，请回主线登记补验任务。");
        }
        // 本条不作断言：它是一条会说话的记录，不是通过/失败判据。
        // 🚫 反过来写成 assertEquals(0, total) 会在业务导入数据后变红，而那个红长得像回归。
    }

    /** 前置探针：三个年降源的 field-tree 都能 200 打开（坐标查不到会 404 COMPILE_TABVIEW_NOT_FOUND）。 */
    @Test
    @DisplayName("AC-11 前置：三个年降坐标的 field-tree 均 200（不是 404 COMPILE_TABVIEW_NOT_FOUND）")
    void ac11_fieldTreeReachable() {
        for (var e : annualCoordinates("AC-11前置").entrySet()) {
            String[] c = e.getValue();
            Response r = fieldTree(c[0], c[1], "QUOTE");
            assertEquals(200, r.statusCode(), "AC-11 前置：" + e.getKey() + " 坐标 " + c[0] + "/" + c[1]
                    + " 的 field-tree 应 200，实际=" + r.statusCode() + " body=" + r.asString());
            assertNotNull(r.jsonPath().get("groups"), "AC-11 前置：" + e.getKey() + " 的 field-tree 无 groups");
        }
    }
}
