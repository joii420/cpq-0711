package com.cpq.task260904;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
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
 * 第二批 · <b>AC-1 / AC-2 / AC-3</b> —— 取数配置器的数据源清单契约（{@code api.md §1.2}）。
 *
 * <h3>AC 原文（需求文档.md §3.1）</h3>
 * <ul>
 *   <li><b>AC-1（锚点推导为 BOM 树）</b>：数据源选「物料BOM」⇒ ① 无「页签类型」下拉；② 只读回显「BOM 树」；
 *       ③ 生成的 SQL 含 {@code WITH RECURSIVE} 与 {@code UNION ALL} 根分支；④ 保存后走 {@code BomTreeRenderService} 渲染。</li>
 *   <li><b>AC-2（锚点推导为材质元素）</b>：数据源选「物料与元素BOM」⇒ ① 无下拉；② 只读回显「材质元素」；
 *       ③ 字段面板出现 {@code groupKind='PRICE'} 的价格策略原子组；
 *       ④ 拖入「元素单价」后编译产物含 {@code LEFT JOIN f_material_element_price}，且元素编码列被自动补入输出列。</li>
 *   <li><b>AC-3（其余数据源无页签类型概念）</b>：「物料」「自制加工费」「来料固定加工费」三者均
 *       ① 无下拉；② 无只读回显；③ 字段面板<b>不出现</b>价格策略原子组；④ 生成 SQL 不含 {@code WITH RECURSIVE}。</li>
 * </ul>
 *
 * <h3>本类覆盖 / 不覆盖</h3>
 * <table>
 *   <tr><td>AC-1① · AC-2① · AC-3①②</td><td>UI 断言（「有没有下拉」「有没有回显」）—— <b>由 E2E 承担</b>，
 *       后端只能提供其前提：清单里带 {@code semantic} 三态供前端分支。本类断言该前提成立。</td></tr>
 *   <tr><td>AC-1② · AC-2②</td><td>回显文案来自 {@code availableSources[].label} + {@code semantic}，本类断言这对键值正确。</td></tr>
 *   <tr><td>AC-1③</td><td>🚨 <b>与现网不符，未写成断言</b> —— 见 {@link #ac1_treeSourceCompilesWithoutRecursiveCte}。</td></tr>
 *   <tr><td>AC-1④</td><td>第一批 {@code DualCriteriaAcTest.ac21_newTreeComponentGetsUnionDriver} 已取证，本类不重复。</td></tr>
 *   <tr><td>AC-2③④ · AC-3③④</td><td>本类全覆盖。</td></tr>
 * </table>
 *
 * <h3>🚨 为什么本类几乎不出现具体数字</h3>
 * 主线实测 QUOTE 11 / COST_BASIC 10 / COST_DETAIL 18，但那是<b>共享 dev 库当前配置数据的快照</b>。
 * 断言写死它 ⇒ 下一次语义图种子迁移就把用例打红，而那个红长得和产品回归一模一样。
 * ⇒ 期望值一律从 {@code semantic_tab_view} 现算（{@link #expectedSourceCoordinates}），数字只打印。
 */
@QuarkusTest
@DisplayName("task-260904 · AC-1/2/3 —— 数据源清单：退役项不出现、按方言收窄、价格策略只跟材质元素")
class DataSourceCatalogAcTest extends Batch2Base {

    // ═══════════════════════════════════════════════════════════════════════
    // AC-1 ① · S-4 —— 退役的「零件 / 外购件」不出现在新建组件的可选清单里
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-1/S-4：三方言的 availableSources 与 availableTabTypes 均不含「零件」「外购件」；"
            + "且 semantic_tab_view 45 行一行未动（过滤在 API 层，不是把数据停用/删掉）")
    void ac1_retiredTabTypesAbsentFromCatalogButRowsUntouched() {
        // ── 阳性对照①：先证明 dialect 入参真被消费，否则下面三轮其实测的是同一个方言 ──
        assertDialectParamIsHonored("AC-1");

        // ── 阳性对照②：库里那 6 行（3 方言 × {零件,外购件}）必须仍在、仍 ACTIVE ──
        //    这是 S-4 的红线（需求文档 §2.1 S-4）：30 个存量零件/外购件组件靠 field-tree 按坐标
        //    查得到才能打开配置页（AC-25①）。若它们被停用/删除，「清单里没有」会以另一种原因成立
        //    ——那时本用例照样绿，却掩盖了一次破坏。
        long retiredActive = count("SELECT count(*) FROM semantic_tab_view "
                + "WHERE status = 'ACTIVE' AND tab_type IN ('零件','外购件')");
        assertEquals(RETIRED_TAB_TYPES.size() * (long) DIALECTS.size(), retiredActive,
                "AC-1 阳性对照：semantic_tab_view 里「零件/外购件」的 ACTIVE 行应为 3 方言 × 2 类 = 6 行 "
                        + "（S-4 明写「45 行仍一行不动、全部保持 ACTIVE」），实际=" + retiredActive
                        + " ⇒ 若为 0，则「清单里不含退役项」是被『数据被停用』这个副作用满足的，不是过滤生效。");
        long totalActive = count("SELECT count(*) FROM semantic_tab_view WHERE status = 'ACTIVE'");
        System.out.println("[AC-1] semantic_tab_view ACTIVE 总行数=" + totalActive
                + "（其中零件/外购件=" + retiredActive + "）🚫 数字仅记录，不作断言");

        for (String dialect : DIALECTS) {
            List<Map<String, Object>> sources = availableSources(dialect, "AC-1(" + dialect + ")");

            // ① 清单里不含退役页签类型（按 tabType 判，这是内部坐标，label 只是显示名）
            assertNoneMatch(sources, "tabType", RETIRED_TAB_TYPES,
                    "AC-1(" + dialect + ")：availableSources 里仍出现已退役的页签类型 —— "
                            + "用户在取数配置器里照样选得到「零件」「外购件」，主诉求未达成。");

            // ② availableTabTypes 同步过滤（需求文档 §2.1 S-4 ②：漏了它前端下拉照样选得到）
            List<String> tabTypes = fieldTreeOk(sources.get(0).get("tabType").toString(),
                    String.valueOf(sources.get(0).get("variantKey")), dialect, "AC-1")
                    .jsonPath().getList("availableTabTypes");
            if (tabTypes != null) {
                List<String> leaked = new ArrayList<>(tabTypes);
                leaked.retainAll(RETIRED_TAB_TYPES);
                assertTrue(leaked.isEmpty(), "AC-1(" + dialect + ")：availableTabTypes 仍含退役值 " + leaked
                        + " —— 需求文档 §2.1 S-4 ②：「不过滤则用户照样选得到」。实际=" + tabTypes);
                System.out.println("[AC-1] " + dialect + " availableTabTypes=" + tabTypes);
            }

            // ③ 清单 = 库里该方言 ACTIVE 且非退役的坐标集合，一个不多一个不少（结构性，非数字）
            Set<String> expected = expectedSourceCoordinates(dialect);
            Set<String> actual = new LinkedHashSet<>();
            for (Map<String, Object> s : sources) actual.add(coordOf(s));
            assertEquals(expected, actual, "AC-1(" + dialect + ")：availableSources 的坐标集合应恰好等于 "
                    + "「semantic_tab_view 中 ACTIVE 且 tab_type 不在退役名单」的集合 —— "
                    + "多出来 = 漏过滤，少掉 = 误伤了别的数据源（AC-25① 会跟着坏）。");
            System.out.println("[AC-1] " + dialect + " availableSources=" + actual.size()
                    + " 条（🚫 移动靶，仅记录）：" + actual);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-3 —— 按 dialect 收窄；每条的 dialect 恒等于入参；sourceKey 唯一；semantic 三态
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-3：清单按 dialect 收窄，每条 dialect 恒等于入参、sourceKey 唯一；"
            + "semantic 恰好三态（TREE ×1 / MATERIAL_ELEMENT ×1 / 其余显式 null）")
    void ac3_catalogScopedByDialectAndSemanticTriState() {
        assertDialectParamIsHonored("AC-3");

        for (String dialect : DIALECTS) {
            List<Map<String, Object>> sources = availableSources(dialect, "AC-3(" + dialect + ")");

            // ── 每条的 dialect 恒等于入参 ──
            List<String> wrongDialect = new ArrayList<>();
            for (Map<String, Object> s : sources) {
                if (!dialect.equals(String.valueOf(s.get("dialect")))) {
                    wrongDialect.add(s.get("sourceKey") + "→" + s.get("dialect"));
                }
            }
            assertTrue(wrongDialect.isEmpty(), "AC-3(" + dialect + ")：availableSources 里有条目的 dialect "
                    + "不等于入参 —— 前端会把错误方言的三段坐标原样回传，落到 findFirst() 静默取错行"
                    + "（api.md §0）。实际=" + wrongDialect);

            // ── sourceKey 在方言内唯一（前端下拉用它当 key；重复 = 选不中 / React key 冲突）──
            Set<String> keys = new LinkedHashSet<>();
            List<String> dup = new ArrayList<>();
            for (Map<String, Object> s : sources) {
                if (!keys.add(String.valueOf(s.get("sourceKey")))) dup.add(String.valueOf(s.get("sourceKey")));
            }
            assertTrue(dup.isEmpty(), "AC-3(" + dialect + ")：sourceKey 在方言内重复=" + dup);

            // ── 三段坐标齐全，且 semantic 这个键必须在（值可以为 null，但键不能缺）──
            //    api.md §1.2：「前端不得自己按 label 或 sourceKey 硬编码判断语义，一律读 semantic」。
            //    键缺失时前端读到 undefined，与「普通源」不可区分 —— 但那是巧合等价，不是契约。
            for (Map<String, Object> s : sources) {
                String where = "AC-3(" + dialect + ") 源=" + s.get("sourceKey");
                assertNotNull(s.get("sourceKey"), where + "：缺 sourceKey");
                assertNotNull(s.get("label"), where + "：缺 label（只读回显要用它）");
                assertNotNull(s.get("tabType"), where + "：缺 tabType（三段坐标之一）");
                assertTrue(s.containsKey("variantKey"), where + "：缺 variantKey（三段坐标之一）");
                assertTrue(s.containsKey("semantic"), where + "：响应里没有 semantic 这个键 —— "
                        + "api.md §1.2 要求普通源也显式给出 semantic:null，前端才能只读它做分支。");
            }

            // ── semantic 三态：TREE 恰好 1、MATERIAL_ELEMENT 恰好 1、其余全为 null ──
            List<String> tree = new ArrayList<>();
            List<String> elem = new ArrayList<>();
            List<String> other = new ArrayList<>();
            for (Map<String, Object> s : sources) {
                Object sem = s.get("semantic");
                if (sem == null) { other.add(String.valueOf(s.get("sourceKey"))); continue; }
                switch (String.valueOf(sem)) {
                    case "TREE" -> tree.add(String.valueOf(s.get("sourceKey")));
                    case "MATERIAL_ELEMENT" -> elem.add(String.valueOf(s.get("sourceKey")));
                    default -> throw new AssertionError("AC-3(" + dialect + ")：semantic 出现契约外的第四种取值「"
                            + sem + "」（api.md §1.2 只定义 TREE / MATERIAL_ELEMENT / null），源="
                            + s.get("sourceKey") + " ⇒ 前端的三分支会走进 default，行为未定义。");
                }
            }
            assertEquals(1, tree.size(), "AC-3(" + dialect + ")：semantic='TREE' 的数据源应恰好 1 个"
                    + "（S-1：锚点 MATERIAL_BOM → BOM 树），实际=" + tree
                    + " ⇒ 为 0 则 AC-1 的树语义整条链断供；>1 则用户面临歧义选择。");
            assertEquals(1, elem.size(), "AC-3(" + dialect + ")：semantic='MATERIAL_ELEMENT' 的数据源应恰好 1 个"
                    + "（S-1：锚点 ELEMENT_BOM → 材质元素），实际=" + elem);
            assertFalse(other.isEmpty(), "AC-3(" + dialect + ")：一个 semantic=null 的普通源都没有 "
                    + "⇒ AC-3「其余数据源无页签类型概念」的断言会空跑。");
            System.out.println("[AC-3] " + dialect + " semantic 三态：TREE=" + tree + " MATERIAL_ELEMENT=" + elem
                    + " null=" + other.size() + " 个 " + other);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-2③ / AC-3③ —— 价格策略原子组只在 semantic == MATERIAL_ELEMENT 下出现
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-2③/AC-3③：三方言逐源清点 —— 出现 groupKind='PRICE' 的源 ⊆ semantic='MATERIAL_ELEMENT' 的源；"
            + "且 QUOTE/材质元素 确实有 PRICE 组（否则「⊆」是空真）")
    void ac2_priceStrategyGroupOnlyUnderMaterialElement() {
        List<String> priceCarriers = new ArrayList<>();
        List<String> materialElementSources = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        int scanned = 0;

        for (String dialect : DIALECTS) {
            for (Map<String, Object> s : availableSources(dialect, "AC-2(" + dialect + ")")) {
                scanned++;
                String id = dialect + "/" + s.get("sourceKey");
                boolean isElement = "MATERIAL_ELEMENT".equals(String.valueOf(s.get("semantic")));
                if (isElement) materialElementSources.add(id);

                List<Group> gs = groupsOf(String.valueOf(s.get("tabType")),
                        String.valueOf(s.get("variantKey")), dialect, "AC-2(" + id + ")");
                List<Group> price = priceGroups(gs);
                if (!price.isEmpty()) {
                    priceCarriers.add(id + price.stream().map(Group::groupKey).toList());
                    // AC-3③「其余数据源字段面板不出现价格策略原子组」的反面
                    if (!isElement) violations.add(id + " semantic=" + s.get("semantic") + " 却挂了 " + price);
                }
            }
        }

        assertTrue(scanned > 0, "AC-2：一个数据源都没扫到 ⇒ 全部断言空跑。");
        // 🚨 空真守卫：若一个 PRICE 组都不存在，「PRICE ⊆ MATERIAL_ELEMENT」恒成立而什么都没证明。
        assertFalse(priceCarriers.isEmpty(), "AC-2③：三方言逐源扫完，没有任何数据源挂 groupKind='PRICE' 的组 "
                + "⇒ 价格策略原子组根本没出现，AC-2③ 未达成；同时「只在材质元素下可用」这条断言会以空真通过。");
        assertFalse(materialElementSources.isEmpty(), "AC-2：没有任何 semantic='MATERIAL_ELEMENT' 的源 ⇒ 断言空跑。");
        assertTrue(violations.isEmpty(), "AC-2③/AC-3③：价格策略原子组出现在了非「材质元素」语义的数据源上 —— "
                + "AC-3③ 明写「其余三个都没有」。违例=" + violations);

        System.out.println("[AC-2] 扫描数据源 " + scanned + " 个；挂 PRICE 组的=" + priceCarriers
                + "；semantic=MATERIAL_ELEMENT 的=" + materialElementSources);

        // ── 正向：QUOTE 的材质元素必须真的挂到了 PRICE 组（不是靠别的方言凑出的非空）──
        List<Group> quoteElement = groupsOf("材质元素", "", "QUOTE", "AC-2③ 正向");
        assertFalse(priceGroups(quoteElement).isEmpty(),
                "AC-2③：QUOTE 方言的「物料与元素BOM」字段面板未出现 groupKind='PRICE' 的价格策略原子组，实际组="
                        + quoteElement.stream().map(g -> g.groupKey() + "[" + g.groupKind() + "]").toList());
        System.out.println("[AC-2③] QUOTE/材质元素 的组=" + quoteElement.stream()
                .map(g -> g.groupKey() + "[" + g.groupKind() + "]×" + g.sourceColumns().size()).toList());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-2④ —— 拖入「元素单价」后编译产物含 LEFT JOIN，且元素编码列被自动补入输出列
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-2④：只配「材质料号 + 元素单价」编译 ⇒ 产物含 LEFT JOIN f_material_element_price，"
            + "且未被配置的元素编码列被自动补入输出列（阴性对照：不拖单价则不出现该 JOIN）")
    void ac2_priceColumnPullsInJoinAndElementCode() {
        UUID cid = createBlankComponent("A2-price");

        String withoutPrice = """
                { "tabType": "材质元素", "variantKey": "", "dialect": "QUOTE", "columns": [
                  {"sourceNodeKey":"ELEMENT_BOM","sourceColumn":"material_part_no","fieldName":"材质料号","isRowKey":true,"isPartNo":true}
                ]}
                """;
        String withPrice = """
                { "tabType": "材质元素", "variantKey": "", "dialect": "QUOTE", "columns": [
                  {"sourceNodeKey":"ELEMENT_BOM","sourceColumn":"material_part_no","fieldName":"材质料号","isRowKey":true,"isPartNo":true},
                  {"sourceNodeKey":"FUNC_ELEMENT_PRICE","sourceColumn":"unit_price","fieldName":"元素单价"}
                ]}
                """;

        // 🚨 阴性对照先跑：证明「含 LEFT JOIN」不是这个数据源的恒定属性，而是拖入单价列带来的。
        String sqlBefore = compileSql(cid, withoutPrice, "AC-2④ 阴性对照");
        assertFalse(sqlBefore.contains("f_material_element_price"),
                "AC-2④ 阴性对照：没拖「元素单价」时编译产物就已经含 f_material_element_price ⇒ "
                        + "阳性断言不具分辨力（不管改成什么都会绿）。sql=" + sqlBefore);

        String sqlAfter = compileSql(cid, withPrice, "AC-2④");
        assertTrue(sqlAfter.contains("LEFT JOIN f_material_element_price"),
                "AC-2④：拖入「元素单价」后编译产物应含 LEFT JOIN f_material_element_price，实际 sql=" + sqlAfter);

        // 元素编码列自动补入输出列：配置里只有 material_part_no + unit_price，产物却应多出元素列。
        // 🚨 用 element_code 的**精确 viewColumn**比对，🚫 不用 display_name 做 contains ——
        //    display_name 是「元素」，而其它列的 viewColumn 是「_物料与元素BOM_…」，同样含「元素」二字，
        //    contains 会把不相干的列也算成命中（分辨力为零）。
        String elementViewColumn = viewColumnOf("材质元素", "", "QUOTE", "element_code");
        assertNotNull(elementViewColumn, "AC-2④ 前置：field-tree 里查不到 element_code 的 viewColumn ⇒ 断言无从构造。");

        List<String> declaredWithout = compileCfg(cid, withoutPrice).jsonPath().getList("declaredColumns");
        assertNotNull(declaredWithout, "AC-2④：阴性对照的编译产物无 declaredColumns。");
        assertFalse(declaredWithout.contains(elementViewColumn),
                "AC-2④ 阴性对照：没拖「元素单价」时输出列里就已含元素编码列「" + elementViewColumn
                        + "」⇒ 阳性断言不具分辨力。declaredColumns=" + declaredWithout);

        List<String> declared = compileCfg(cid, withPrice).jsonPath().getList("declaredColumns");
        assertNotNull(declared, "AC-2④：编译产物无 declaredColumns。");
        assertTrue(declared.contains(elementViewColumn),
                "AC-2④：元素编码列「" + elementViewColumn + "」未被自动补入输出列。"
                        + "配置里只声明了 material_part_no + unit_price，产物 declaredColumns=" + declared);
        System.out.println("[AC-2④] 阴性对照 declaredColumns=" + declaredWithout
                + "\n         拖入单价后 declaredColumns=" + declared
                + "\n         自动补入的元素编码列=" + elementViewColumn);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-3④ —— 普通数据源的 SQL 不含 WITH RECURSIVE
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-3④：semantic=null 的普通数据源（物料 / 自制加工费 / 来料固定加工费）编译产物均不含 WITH RECURSIVE")
    void ac3_plainSourcesCompileWithoutRecursiveCte() {
        UUID cid = createBlankComponent("A3-plain");
        int checked = 0;

        for (Map<String, Object> s : availableSources("QUOTE", "AC-3④")) {
            if (s.get("semantic") != null) continue;                     // 只测普通源
            String tabType = String.valueOf(s.get("tabType"));
            String variantKey = String.valueOf(s.get("variantKey"));
            String sourceKey = String.valueOf(s.get("sourceKey"));

            // 该源的一个可用列：现场从语义图取，🚫 不写死列名（列是配置数据）。
            // 🚨 按坐标解析出的锚点节点 id 取，🚫 不按 node_key —— 同名 node_key 三个方言各有一个节点，
            //    跨方言取到的列在本方言的表里不存在，保存/编译会报 PHYSICAL_EXISTENCE（夹具错，非产品缺陷）。
            String nodeId = anchorNodeId("QUOTE", tabType, variantKey);
            if (nodeId == null) continue;
            String anchorCol = partNoColumnOf(nodeId);
            if (anchorCol == null) continue;

            String cfg = "{\"tabType\":\"" + tabType + "\",\"variantKey\":\"" + variantKey
                    + "\",\"dialect\":\"QUOTE\",\"columns\":[{\"sourceNodeKey\":\"" + sourceKey
                    + "\",\"sourceColumn\":\"" + anchorCol + "\",\"fieldName\":\"标识列\",\"isRowKey\":true,\"isPartNo\":true}]}";
            String sql = compileSql(cid, cfg, "AC-3④(" + sourceKey + ")");
            assertFalse(sql.toUpperCase().contains("WITH RECURSIVE"),
                    "AC-3④：普通数据源「" + s.get("label") + "」(" + sourceKey + ", semantic=null) 的编译产物"
                            + "不应含 WITH RECURSIVE，实际 sql=" + sql);
            checked++;
        }

        assertTrue(checked >= 3, "AC-3④ 原文点名了三个数据源（物料 / 自制加工费 / 来料固定加工费）。"
                + "本轮只成功编译了 " + checked + " 个普通源 ⇒ 覆盖不足，断言可能空跑。");
        System.out.println("[AC-3④] 已编译并断言「不含 WITH RECURSIVE」的普通数据源=" + checked + " 个");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 🚨 AC-1③ —— 与现网不符，本用例只取证不断言
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * <b>AC-1③ 原文</b>：「生成的 SQL 含 {@code WITH RECURSIVE} 与 {@code UNION ALL} 根分支」。
     *
     * <h3>🚨 实测：该断言在现网不成立，且不是本次改动造成的</h3>
     * <pre>
     * 语义编译器对 MATERIAL_BOM 锚点产出的 SQL：
     *   SELECT dqmb.material_no AS hf_part_no, ... FROM ds_quote_material_bom dqmb
     *   WHERE dqmb.material_no = ANY(:total_material_no) ORDER BY ...
     *   ⇒ 无 WITH RECURSIVE，无 UNION ALL
     *
     * 库侧交叉验证（改动前后同样成立）：
     *   bom_recursive_expand = true 的 22 个组件里，sql_template 含 WITH RECURSIVE 的 = 0
     *   （其中 21 个是存量手写视图组件 tab_type='BOM'，1 个是配置器产出）
     * </pre>
     * ⇒ <b>BOM 树的递归展开根本不在 {@code $view} 的 SQL 里</b>，它由
     * {@code component.bom_recursive_expand} + 渲染层的 BOM union driver 承担
     * （第一批 AC-21② 已取证：物化后 {@code snapshot_rows} 是三行树骨架）。
     *
     * <p>⇒ 🚫 <b>本用例不把 AC-1③ 写成断言</b>（那会写出一条明知必红、且红的原因是 AC 写错而非代码错的用例），
     * 也 🚫 <b>不擅自把它改写成一条能过的断言</b>。本用例只做两件事：
     * <ol>
     *   <li>把实际编译产物打印出来存档，供主线裁决；</li>
     *   <li>断言 AC-1③ <b>真正想保护的东西</b> —— 「树数据源的产物确实以 BOM 表为锚点、且带按单收窄谓词」
     *       —— 这条与 AC-1④「保存后走 BomTreeRenderService 渲染」同源，且第一批已独立取证。</li>
     * </ol>
     * <b>待主线澄清后再决定 AC-1③ 是改文档还是改实现。</b>
     */
    @Test
    @DisplayName("AC-1③【与现网不符 · 只取证不断言】树数据源的编译产物实测不含 WITH RECURSIVE —— "
            + "递归由 bom_recursive_expand + 渲染层承担，不在 $view SQL 里")
    void ac1_treeSourceCompilesWithoutRecursiveCte() {
        UUID cid = createBlankComponent("A1-tree");
        String sql = compileSql(cid, CFG_MATERIAL_BOM, "AC-1③ 取证");

        boolean hasRecursive = sql.toUpperCase().contains("WITH RECURSIVE");
        boolean hasUnionAll = sql.toUpperCase().contains("UNION ALL");
        long treeCompsWithRecursiveTemplate = count(
                "SELECT count(*) FROM component c JOIN component_sql_view v ON v.component_id = c.id "
                        + "WHERE c.bom_recursive_expand AND upper(v.sql_template) LIKE '%WITH RECURSIVE%'");
        long treeComps = count("SELECT count(*) FROM component WHERE bom_recursive_expand");

        System.out.println("""
                [AC-1③ 取证] ⚠️ AC 原文要求「SQL 含 WITH RECURSIVE 与 UNION ALL 根分支」，实测：
                  编译产物 含 WITH RECURSIVE = %s ；含 UNION ALL = %s
                  库侧交叉：bom_recursive_expand=true 的组件 %d 个，其中 sql_template 含 WITH RECURSIVE 的 %d 个
                  ⇒ 递归展开不在 $view SQL 层，改动前后一致。已上报主线，待裁决改文档还是改实现。
                --- 实际编译产物 ---
                %s""".formatted(hasRecursive, hasUnionAll, treeComps, treeCompsWithRecursiveTemplate, sql));

        // AC-1③ 真正要保护的：树数据源的产物确实落在 BOM 锚点表上、且带按单收窄谓词。
        String anchorTable = anchorPhysicalTable("QUOTE", "BOM", "");
        assertNotNull(anchorTable, "AC-1③：查不到 QUOTE/BOM 的锚点物理表 ⇒ 断言无从构造。");
        assertTrue(sql.contains(anchorTable),
                "AC-1③（等价断言）：树数据源的编译产物应 FROM 锚点表 " + anchorTable + "，实际 sql=" + sql);
        assertTrue(sql.contains(":total_material_no"),
                "AC-1③（等价断言）：产物应带按单收窄谓词 :total_material_no，否则渲染期会取到全库 BOM。sql=" + sql);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-1② / AC-2② —— 只读回显的数据来源（label + semantic）正确
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-1②/AC-2②：树数据源与材质元素数据源的 label 与 semantic 成对正确 —— 前端只读回显的唯一依据")
    void ac1and2_readonlyBadgeSourceFields() {
        for (String dialect : DIALECTS) {
            Map<String, Object> tree = null;
            Map<String, Object> elem = null;
            for (Map<String, Object> s : availableSources(dialect, "AC-1②(" + dialect + ")")) {
                if ("TREE".equals(String.valueOf(s.get("semantic")))) tree = s;
                if ("MATERIAL_ELEMENT".equals(String.valueOf(s.get("semantic")))) elem = s;
            }
            assertNotNull(tree, "AC-1②(" + dialect + ")：清单里找不到 semantic='TREE' 的数据源。");
            assertNotNull(elem, "AC-2②(" + dialect + ")：清单里找不到 semantic='MATERIAL_ELEMENT' 的数据源。");

            // label 必须是数据源名（用户选的东西），🚫 不是「BOM 树」「材质元素」这类抽象页签类型名 ——
            // S-2：「用户直接选具体数据源（如『物料BOM』『自制加工费』）」。
            assertEquals(anchorDisplayName(dialect, String.valueOf(tree.get("tabType")),
                            String.valueOf(tree.get("variantKey"))), String.valueOf(tree.get("label")),
                    "AC-1②(" + dialect + ")：树数据源的 label 应是锚点节点的 display_name（数据源名），实际=" + tree.get("label"));
            assertEquals(anchorDisplayName(dialect, String.valueOf(elem.get("tabType")),
                            String.valueOf(elem.get("variantKey"))), String.valueOf(elem.get("label")),
                    "AC-2②(" + dialect + ")：材质元素数据源的 label 应是锚点节点的 display_name，实际=" + elem.get("label"));

            System.out.println("[AC-1②/AC-2②] " + dialect
                    + " TREE → sourceKey=" + tree.get("sourceKey") + " label=" + tree.get("label")
                    + " tabType=" + tree.get("tabType")
                    + " ｜ MATERIAL_ELEMENT → sourceKey=" + elem.get("sourceKey") + " label=" + elem.get("label")
                    + " tabType=" + elem.get("tabType"));
        }
    }

    /** 从 field-tree 的字段面板里取某个物理列的 {@code viewColumn}（精确匹配用，🚫 不用 display_name 做 contains）。 */
    @SuppressWarnings("unchecked")
    private String viewColumnOf(String tabType, String variantKey, String dialect, String sourceColumn) {
        Response r = fieldTreeOk(tabType, variantKey, dialect, "viewColumnOf");
        List<Map<String, Object>> groups = (List<Map<String, Object>>) r.jsonPath().get("groups");
        if (groups == null) return null;
        for (Map<String, Object> g : groups) {
            List<Map<String, Object>> fs = (List<Map<String, Object>>) g.get("fields");
            if (fs == null) continue;
            for (Map<String, Object> f : fs) {
                if (sourceColumn.equals(String.valueOf(f.get("sourceColumn")))) {
                    return String.valueOf(f.get("viewColumn"));
                }
            }
        }
        return null;
    }

    private String anchorDisplayName(String dialect, String tabType, String variantKey) {
        return scalar("SELECT n.display_name FROM semantic_tab_view v JOIN semantic_node n ON n.id = v.anchor_node_id "
                + "WHERE v.dialect = '" + dialect + "' AND v.tab_type = '" + tabType + "' "
                + "AND coalesce(v.variant_key,'') = '" + variantKey + "' AND v.status = 'ACTIVE'");
    }
}
