package com.cpq.task260904;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第二批 · <b>AC-20（反向 · 选定数据源后展示该表全部字段，一列不少）</b>。
 *
 * <h3>AC 原文（需求文档.md §3.3）</h3>
 * 前置：分别以「物料BOM」「物料与元素BOM」「物料」「自制加工费」四个数据源新建组件。
 * 操作：逐个清点左侧「可用字段」面板的字段数与字段名。断言：
 * <ol>
 *   <li>各数据源展示的字段 = 该表全部业务列，即物理列减去
 *       {@code id / version_no / row_fingerprint / source / created_at / created_by / updated_at / updated_by}。
 *       逐表核对：{@code ds_quote_material_bom} 12（物理 20）· {@code ds_quote_element_bom} 12（物理 20）·
 *       {@code ds_quote_material} 9（物理 15）· {@code ds_quote_self_process_fee} 9（物理 17）；</li>
 *   <li>改动前配成「零件」或「外购件」的组件改选「物料BOM」后，字段清单与改动前逐字相同
 *       （⚠️ 原文注「仅 QUOTE 方言成立，核价两套各挂 2 组」）；</li>
 *   <li>「物料与元素BOM」额外含价格策略组（{@code groupKind='PRICE'}），其余三个都没有；</li>
 *   <li>主源组末尾的查名展开字段（{@code syntheticLookupFields}）照常出现。</li>
 * </ol>
 *
 * <h3>🚨 test.md §3「TC-20 必须逐列比名字，不能只比数量」</h3>
 * 只断言「字段数 = 12」的话，某列被替换成另一列照样绿。⇒ 本类全部用<b>集合相等</b>断言，
 * 数量只用来在失败信息里定位。且期望集合<b>从 {@code information_schema} 现算</b>，
 * AC 原文里的 12/12/9/9 只作为<b>交叉核对</b>打印，🚫 不作为唯一判据。
 *
 * <h3>⚠️ 两处与 AC 原文不符，已按实测处理（详见 test.md §7）</h3>
 * <ul>
 *   <li><b>AC-20②</b>：原文注「核价两套各挂 2 组（多一个 {@code QUOTE_MATERIAL_BRIDGE(AUX)}）」——
 *       <b>已过期</b>。{@code task-260819} 的 B-50 按 NARROW 边把桥剔除后，实测三方言各挂 <b>1</b> 组。
 *       ⇒ 本类按实测断言「三方言下 BOM/零件/外购件 的字段清单逐字相同」，并把每方言的实际组数打印存档。</li>
 *   <li><b>AC-20④</b>：{@code field-tree} 响应里<b>没有 {@code syntheticLookupFields} 这个键</b>
 *       （响应字段实测为 {@code tabType/variantKey/anchorDesc/availableSources/availableTabTypes/
 *       tabTypesFallback/variants/switches/groups}）。⇒ 该断言<b>无观测口，本类不覆盖</b>，
 *       已在 test.md 列为交付缺口并上报主线，🚫 不擅自解释成别的可测含义。</li>
 * </ul>
 */
@QuarkusTest
@DisplayName("task-260904 · AC-20 —— 数据源字段面板：业务列一列不少、退役页签改选后清单逐字相同")
class FieldPanelColumnsAcTest extends Batch2Base {

    /** AC-20① 点名的四个 QUOTE 数据源（坐标 → AC 原文声明的业务列数，用于交叉核对）。 */
    private static final Map<String, Integer> AC20_NAMED_SOURCES = new LinkedHashMap<>(Map.of(
            "BOM/", 12,               // 物料BOM        → ds_quote_material_bom
            "材质元素/", 12,            // 物料与元素BOM   → ds_quote_element_bom
            "主件/", 9,                // 物料           → ds_quote_material
            "费用类/SELF_PROCESS_FEE", 9 // 自制加工费     → ds_quote_self_process_fee
    ));

    // ═══════════════════════════════════════════════════════════════════════
    // AC-20① —— 四个点名数据源：字段集合 == 物理列 − AC 原文的系统列
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-20①：四个点名数据源的可用字段集合 == 锚点表物理列 − AC 原文系统列（逐列比名字，不只比数量）")
    void ac20_namedSourcesExposeAllBusinessColumns() {
        List<String> report = new ArrayList<>();

        for (Map.Entry<String, Integer> e : AC20_NAMED_SOURCES.entrySet()) {
            String[] parts = e.getKey().split("/", -1);
            String tabType = parts[0];
            String variantKey = parts.length > 1 ? parts[1] : "";
            String acRef = "AC-20①(" + e.getKey() + ")";

            String table = anchorPhysicalTable("QUOTE", tabType, variantKey);
            assertNotNull(table, acRef + "：查不到锚点物理表 ⇒ 期望集合无从构造，断言会空跑。");

            Set<String> physical = physicalColumns(table);
            assertFalse(physical.isEmpty(), acRef + "：information_schema 查 " + table
                    + " 返回 0 列 ⇒ 期望集合为空，「一列不少」会恒成立。");

            Set<String> expected = new LinkedHashSet<>(physical);
            expected.removeAll(AC20_SYSTEM_COLUMNS);
            assertFalse(expected.isEmpty(), acRef + "：减掉系统列后期望集合为空 ⇒ 断言空跑。");

            List<Group> gs = groupsOf(tabType, variantKey, "QUOTE", acRef);
            Set<String> actual = new LinkedHashSet<>();
            for (Group g : mainGroups(gs)) actual.addAll(g.sourceColumns());
            assertFalse(actual.isEmpty(), acRef + "：MAIN 组一列都没有 ⇒ 断言空跑。groups="
                    + gs.stream().map(x -> x.groupKey() + "[" + x.groupKind() + "]").toList());

            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(actual);
            Set<String> extra = new LinkedHashSet<>(actual);
            extra.removeAll(expected);
            assertEquals(expected, actual, acRef + "：可用字段应恰好等于「" + table + " 的物理列 − 系统列」。"
                    + "缺失=" + missing + "（用户在配置器里配不出这些列）；多出=" + extra
                    + "（展示了不该出现的列）。物理列 " + physical.size() + " 列，期望业务列 " + expected.size()
                    + " 列，实际 " + actual.size() + " 列。");

            // 交叉核对 AC 原文里的数字：不一致不判失败（数据会漂），但必须在报告里显式说出来。
            int acDeclared = e.getValue();
            String mark = acDeclared == expected.size() ? "✅ 与 AC 原文一致" : "⚠️ 与 AC 原文(" + acDeclared + ")不一致";
            report.add(String.format("%-26s %-28s 物理%2d 业务%2d %s", e.getKey(), table,
                    physical.size(), expected.size(), mark));
        }

        System.out.println("[AC-20①] 四个点名数据源逐表核对：\n  " + String.join("\n  ", report));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-20① 扩展 —— 全部数据源都不缺业务列、也不冒出幻列
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-20① 扩展：三方言全部数据源 —— 无一列凭空多出；缺失仅允许版本控制列 is_current（视图型锚点）")
    void ac20_everySourceExposesItsBusinessColumns() {
        // 视图型锚点（v_ds_cost_*_all）比基表多一个版本控制列 is_current，它不在 AC 原文的系统列名单里。
        // 🚫 不把它塞进 AC20_SYSTEM_COLUMNS（那是逐字取自 AC 的常量），单独作为「允许缺失」白名单。
        Set<String> allowedMissing = Set.of("is_current");

        List<String> phantom = new ArrayList<>();
        List<String> unexpectedMissing = new ArrayList<>();
        int scanned = 0;

        for (String dialect : DIALECTS) {
            for (Map<String, Object> s : availableSources(dialect, "AC-20①扩展(" + dialect + ")")) {
                String tabType = String.valueOf(s.get("tabType"));
                String variantKey = String.valueOf(s.get("variantKey"));
                String id = dialect + "/" + s.get("sourceKey");

                String table = anchorPhysicalTable(dialect, tabType, variantKey);
                if (table == null) { unexpectedMissing.add(id + " 无 physical_table"); continue; }
                Set<String> expected = new LinkedHashSet<>(physicalColumns(table));
                if (expected.isEmpty()) { unexpectedMissing.add(id + " information_schema 查 " + table + " 得 0 列"); continue; }
                expected.removeAll(AC20_SYSTEM_COLUMNS);

                Set<String> actual = new LinkedHashSet<>();
                for (Group g : mainGroups(groupsOf(tabType, variantKey, dialect, "AC-20①扩展(" + id + ")"))) {
                    actual.addAll(g.sourceColumns());
                }

                Set<String> extra = new LinkedHashSet<>(actual);
                extra.removeAll(expected);
                if (!extra.isEmpty()) phantom.add(id + "(" + table + ") 多出 " + extra);

                Set<String> missing = new LinkedHashSet<>(expected);
                missing.removeAll(actual);
                missing.removeAll(allowedMissing);
                if (!missing.isEmpty()) unexpectedMissing.add(id + "(" + table + ") 缺 " + missing);
                scanned++;
            }
        }

        assertTrue(scanned > 0, "AC-20① 扩展：一个数据源都没扫到 ⇒ 断言空跑。");
        assertTrue(phantom.isEmpty(), "AC-20①：有数据源展示了锚点表里不存在的列（幻列）=" + phantom);
        assertTrue(unexpectedMissing.isEmpty(), "AC-20①：有数据源缺业务列（用户配不出来）="
                + unexpectedMissing + "（白名单仅 " + allowedMissing + "）");
        System.out.println("[AC-20① 扩展] 已逐列核对数据源 " + scanned + " 个，幻列 0 / 非白名单缺列 0");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-20② —— 退役页签改选「物料BOM」后字段清单逐字相同
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-20②：三方言下 BOM / 零件 / 外购件 三个坐标的字段清单逐字相同（含组结构）—— "
            + "⚠️ 原文『核价两套各挂 2 组』已过期，实测三方言均 1 组")
    void ac20_retiredTabTypesShareIdenticalFieldListWithBom() {
        List<String> shape = new ArrayList<>();

        for (String dialect : DIALECTS) {
            // 阳性对照：退役坐标仍能查（S-4 / AC-25①：45 行保持 ACTIVE，存量组件才打得开配置页）。
            // 若这里 404，则「清单相同」根本无从比较 —— 那是 AC-25① 的破坏，必须响亮失败。
            List<Group> bom = groupsOf("BOM", "", dialect, "AC-20②(" + dialect + "/BOM)");

            for (String retired : RETIRED_TAB_TYPES) {
                long active = count("SELECT count(*) FROM semantic_tab_view WHERE dialect = '" + dialect
                        + "' AND tab_type = '" + retired + "' AND status = 'ACTIVE'");
                assertEquals(1L, active, "AC-20② 前置：" + dialect + "/" + retired
                        + " 的 ACTIVE 行应为 1（S-4：45 行一行不动），实际=" + active
                        + " ⇒ 存量零件/外购件组件将打不开取数配置页（AC-25①）。");

                List<Group> other = groupsOf(retired, "", dialect, "AC-20②(" + dialect + "/" + retired + ")");
                assertEquals(signature(bom), signature(other),
                        "AC-20②：" + dialect + " 方言下「" + retired + "」与「BOM」的字段清单不一致 —— "
                                + "AC 原文要求「改选物料BOM后字段清单与改动前逐字相同」。"
                                + "BOM=" + signature(bom) + "；" + retired + "=" + signature(other));
            }
            shape.add(dialect + " 组数=" + bom.size() + " " + bom.stream()
                    .map(g -> g.groupKey() + "[" + g.groupKind() + "]×" + g.sourceColumns().size()).toList());
        }

        System.out.println("[AC-20②] 各方言 BOM 坐标的组结构（⚠️ AC 原文注『核价两套各挂 2 组』已过期）：\n  "
                + String.join("\n  ", shape));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-20③ —— 只有「物料与元素BOM」额外含价格策略组
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-20③：四个点名数据源中，只有「物料与元素BOM」含 groupKind='PRICE' 的组，其余三个都没有")
    void ac20_onlyElementBomCarriesPriceGroup() {
        List<String> withPrice = new ArrayList<>();
        List<String> withoutPrice = new ArrayList<>();

        for (String coord : AC20_NAMED_SOURCES.keySet()) {
            String[] parts = coord.split("/", -1);
            List<Group> gs = groupsOf(parts[0], parts.length > 1 ? parts[1] : "", "QUOTE", "AC-20③(" + coord + ")");
            (priceGroups(gs).isEmpty() ? withoutPrice : withPrice).add(coord);
        }

        assertEquals(List.of("材质元素/"), withPrice,
                "AC-20③：只有「物料与元素BOM」(材质元素) 应含价格策略组，实际含 PRICE 组的=" + withPrice
                        + "，不含的=" + withoutPrice);
        assertEquals(3, withoutPrice.size(), "AC-20③：其余三个数据源都不应含价格策略组，实际=" + withoutPrice);
        System.out.println("[AC-20③] 含 PRICE 组=" + withPrice + "；不含=" + withoutPrice);
    }

    /** 组结构签名：组 key + kind + 该组的列名有序清单。逐字比对用它，🚫 不比数量。 */
    private static List<String> signature(List<Group> groups) {
        List<String> sig = new ArrayList<>();
        for (Group g : groups) sig.add(g.groupKey() + "[" + g.groupKind() + "]" + g.sourceColumns());
        return sig;
    }
}
