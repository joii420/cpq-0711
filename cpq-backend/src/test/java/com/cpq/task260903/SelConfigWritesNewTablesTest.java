package com.cpq.task260903;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A-AC-1 / A-AC-6 / A-AC-7</b>：选配写入侧的正向验收 —— 数据落到 {@code ds_quote_*} 四张新表。
 *
 * <p>A-AC-1 原文：「配一个零件（2 材质，占比 70/30）+ 1 外购件，提交」⇒
 * 「① {@code ds_quote_material} 落料号主档；② {@code ds_quote_material_bom} 落材质行，
 * {@code material_ratio} 存满 12 位小数；③ {@code ds_quote_element_bom} 落元素含量；
 * ④ <b>{@code version_no} 全部 = 1</b>」。
 */
@QuarkusTest
@DisplayName("A-AC-1/6/7 选配写入 ds_quote_* 四表")
class SelConfigWritesNewTablesTest extends Task260903Base {

    /** 库里的外购件料号；取不到就硬失败（否则 A-AC-6/7 会静默退化成只测单零件）。 */
    private String anOutsourcedPartNo() {
        String no = scalar("SELECT material_no FROM material_master WHERE material_type='外购件' "
                + "ORDER BY material_no LIMIT 1");
        assertNotNull(no, "前置：需要至少 1 个 material_type='外购件' 的料号。"
                + "0 条 ⇒ A-AC-6 的双投影与 A-AC-7 的外购件身份都无从验起，本用例会假绿 ⇒ 硬失败");
        return no;
    }

    /**
     * <b>A-AC-1</b> 主场景：零件（2 材质 70/30）+ 1 外购件 → 四表落行、{@code version_no} 全 1。
     */
    @Test
    @DisplayName("A-AC-1 四表落行 + version_no 全部 = 1 + 渲染侧读得到")
    void aac1_writesFourNewTablesWithVersionOne() {
        String outsourced = anOutsourcedPartNo();
        Fx fx = newFixture("aac1");

        Response res = configure(fx, submitBody(PREFIX + "A1",
                newPart("触点", "φ5", "5×3×2", "10",
                        List.of(material(RECIPE_A, CONFIG_A, "70"),
                                material(RECIPE_B, CONFIG_B, "30")),
                        List.of(PROC_1)),
                outsourcedPart(outsourced, List.of(PROC_2))));
        assertSubmitOk(res, "A-AC-1 提交");

        String partNo = latestLinePartNo(fx);
        // ①② 主档 + BOM 行（本方法内部已含「非空」正向断言）
        long bomRows = assertNewTablesGotRows(partNo, "A-AC-1");

        // ③ 元素含量
        // 🚨 2026-09-04 修正（本条原先是我的用例缺陷，不是产品缺陷）：
        //    「零件 + 外购件」提交出来的是 COMPOSITE，响应 lineItems 有三层：
        //      COMPOSITE 父料号 / PART 零件子料号 / PART 外购件子料号。
        //    latestLinePartNo() 拿到的是**父料号**，而元素含量属于**零件子料号** ——
        //    断在父料号上恒为 0 行。实证：同一轮里 SIMPLE 提交 element_bom=5，
        //    COMPOSITE 父料号 element_bom=0、material_bom=4。
        //    ⇒ 元素断言必须打在零件子料号上。
        String childPartNo = childPartNoOf(res, outsourced);
        System.out.println("[A-AC-1③] 父料号=" + partNo + " 零件子料号=" + childPartNo);
        assertNotNull(childPartNo,
                "A-AC-1③ 前置：响应里应有一个非外购件的 PART 子料号，实际取不到 ⇒ 断言会打错靶。响应=" + res.asString());
        long elem = dsElementBom(childPartNo);
        System.out.println("[A-AC-1③] " + childPartNo + " 的 ds_quote_element_bom 行数=" + elem);
        assertTrue(elem > 0,
                "A-AC-1③：零件子料号 " + childPartNo + " 应落元素含量（2 个材质各自的元素组成），实际 0 行");

        // ④ version_no 全部 = 1 —— 🚨 先证明有行, 否则「全 1」在 0 行时也成立（test.md §3 第 3 号陷阱）
        assertVersionAllOne("ds_quote_material_bom", partNo, bomRows, "A-AC-1④");
        assertVersionAllOne("ds_quote_element_bom", childPartNo, elem, "A-AC-1④");

        // 🚨 写了 ≠ 渲染得出来（兼容视图 BOM 侧要靠 ds_quote_customer_part 反查 customer_no）
        assertVisibleThroughCompatView(partNo, "A-AC-1");
    }

    /**
     * <b>A-AC-1②</b> 的精度专项：{@code material_ratio} <b>存满 12 位小数</b>。
     *
     * <p>📌 用 70/30 验不出精度 —— 它们在任何 scale 下都是精确的。
     * ⇒ 本用例故意用<b>把 12 位小数占满</b>的占比，若写入侧有任何四舍五入/截断，这里立刻红。
     */
    @Test
    @DisplayName("A-AC-1② material_ratio 存满 12 位小数不被截断")
    void aac1_materialRatioKeeps12Decimals() {
        Fx fx = newFixture("aac1p");
        String rA = "66.666666666666";
        String rB = "33.333333333334";   // 两者之和恰为 100

        Response res = configure(fx, submitBody(PREFIX + "A1P",
                newPart("触点", "φ5", "5×3×2", "10",
                        List.of(material(RECIPE_A, CONFIG_A, rA),
                                material(RECIPE_B, CONFIG_B, rB)),
                        List.of(PROC_1))));
        assertSubmitOk(res, "A-AC-1② 精度提交");

        String partNo = latestLinePartNo(fx);
        assertNewTablesGotRows(partNo, "A-AC-1②");

        List<Object> ratios = col("SELECT material_ratio::text FROM ds_quote_material_bom "
                + "WHERE material_no='" + partNo + "' ORDER BY material_ratio DESC");
        System.out.println("[A-AC-1②] 落库 material_ratio=" + ratios);
        assertTrue(ratios.size() >= 2,
                "A-AC-1② 前置：应至少 2 条材质行，实际 " + ratios.size() + " ⇒ 精度断言会空跑");

        for (Object r : ratios) {
            String t = String.valueOf(r);
            int scale = t.contains(".") ? t.length() - t.indexOf('.') - 1 : 0;
            assertEquals(12, scale,
                    "A-AC-1②：material_ratio 应存满 12 位小数，实际 '" + t + "' 只有 " + scale + " 位");
        }
        assertTrue(ratios.stream().map(String::valueOf).anyMatch(t -> t.startsWith("66.666666666666")),
                "A-AC-1②：提交的 " + rA + " 必须原样落库，未被四舍五入。实际=" + ratios);
        assertTrue(ratios.stream().map(String::valueOf).anyMatch(t -> t.startsWith("33.333333333334")),
                "A-AC-1②：提交的 " + rB + " 必须原样落库，未被四舍五入。实际=" + ratios);
    }

    /**
     * <b>A-AC-6</b>：「配一个 COMPOSITE 产品（零件 + 外购件）」⇒
     * 「{@code ds_quote_material_bom} 里该父料号<b>既有 {@code output_material_type='ASSEMBLY'} 行、
     * 也有 {@code ='RECIPE'} 行</b>（对齐 V6 双投影语义）」。
     */
    @Test
    @DisplayName("A-AC-6 双投影：同一父料号 ASSEMBLY 行与 RECIPE 行并存")
    void aac6_dualProjectionAssemblyAndRecipe() {
        String outsourced = anOutsourcedPartNo();
        Fx fx = newFixture("aac6");

        Response res = configure(fx, submitBody(PREFIX + "A6",
                newPart("触点", "φ5", "5×3×2", "10",
                        List.of(material(RECIPE_A, CONFIG_A, "70"),
                                material(RECIPE_B, CONFIG_B, "30")),
                        List.of(PROC_1)),
                outsourcedPart(outsourced, List.of(PROC_2))));
        assertSubmitOk(res, "A-AC-6 提交");

        String partNo = latestLinePartNo(fx);
        assertNewTablesGotRows(partNo, "A-AC-6");

        List<Object[]> proj = rows("SELECT output_material_type, count(*) FROM ds_quote_material_bom "
                + "WHERE material_no='" + partNo + "' GROUP BY 1 ORDER BY 1");
        System.out.println("[A-AC-6] 父料号 " + partNo + " 的投影分布="
                + proj.stream().map(java.util.Arrays::toString).toList());

        long assembly = count("SELECT count(*) FROM ds_quote_material_bom WHERE material_no='"
                + partNo + "' AND output_material_type='ASSEMBLY'");
        long recipe = count("SELECT count(*) FROM ds_quote_material_bom WHERE material_no='"
                + partNo + "' AND output_material_type='RECIPE'");
        assertTrue(assembly > 0,
                "A-AC-6：组合产品的父料号应有 output_material_type='ASSEMBLY' 行，实际 0 行。投影分布=" + proj);
        assertTrue(recipe > 0,
                "A-AC-6：同一父料号还应有 output_material_type='RECIPE' 行（双投影，对齐 V6 语义），"
                        + "实际 0 行。投影分布=" + proj);
    }

    /**
     * <b>A-AC-7</b>（2026-09-04 用户裁决改写后的原文）：
     * 「{@code ds_quote_material.material_type}：① 外购件 = <b>{@code 外购件}</b>；
     * ② 零件子料号 = <b>{@code 零件}</b>；③ 🆕 <b>COMPOSITE 主产品 = {@code 零件}</b>
     * （2026-09-04 用户裁决，<b>覆盖</b>原「传 {@code null}，🚫 不许拿零件凑数」）。
     * 🚫 <b>不得有 NULL</b>」。
     *
     * <h4>🚨 父件 / 子件必须显式区分（本套用例踩过三次）</h4>
     * {@code latestLinePartNo()} 在 COMPOSITE 下返回的是<b>父料号</b>。本用例三个断言分别打在
     * <b>三个不同的料号</b>上，因此一律从 {@code quotation_line_item.composite_type} 取，
     * 并把「父=X 零件子=Y 外购子=Z」打进日志。
     *
     * <h4>🚩 {@code Assumptions.abort} 已删除（上一轮的「未验证」被消除）</h4>
     * 上一轮 ① 无从验起，是因为库里存量外购件<b>只有 {@code TEST-Q13-CODE} 一条、且它已在
     * {@code ds_quote_material}（{@code source=IMPORT}）</b> ⇒ 本次提交根本不会「创建」它。
     * 本轮改为 {@link #createSyntheticOutsourcedPart()} 自建一条<b>本轮独有</b>的外购件，
     * 场景可构造 ⇒ ① 变成真断言，abort 不再需要。
     */
    @Test
    @DisplayName("A-AC-7 三态齐备：外购件=外购件 / 零件子料号=零件 / COMPOSITE 主产品=零件")
    void aac7_materialTypeThreeStatesAllPresent() {
        // 前置：V409 必须已落（该列在 V409 之前不存在）
        assertEquals(1L, count("SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name='ds_quote_material' AND column_name='material_type'"),
                "A-AC-7 前置：ds_quote_material.material_type 列不存在 ⇒ V409 尚未落地。"
                        + "这是**环境前置缺失**，不是被测功能的结论。");

        String outsourced = createSyntheticOutsourcedPart();
        // 🚨 提交前快照：证明本轮三个料号都是**新造**的。少了这一关，取值断言可能打在
        //    存量 IMPORT 行上（它本来就带 material_type）⇒ 恒真（testing.md §3 第 2、3 号陷阱）。
        java.util.Set<String> before = dsMaterialNoSnapshot();

        Fx fx = newFixture("aac7");
        Response res = configure(fx, submitBody(PREFIX + "A7",
                newPart("触点", "φ5", "5×3×2", "10",
                        List.of(material(RECIPE_A, CONFIG_A, "70"),
                                material(RECIPE_B, CONFIG_B, "30")),
                        List.of(PROC_1)),
                outsourcedPart(outsourced, List.of(PROC_2))));
        assertSubmitOk(res, "A-AC-7 提交");

        // ── 先把三个角色分清楚，再断言 ──
        String parent = compositeParentPartNo(fx);
        List<String> children = childPartNos(fx);
        System.out.println("[A-AC-7] 报价行结构="
                + lineItemsOf(fx).stream().map(java.util.Arrays::toString).toList());
        assertNotNull(parent,
                "A-AC-7③ 前置：『零件 + 外购件』应铸出 COMPOSITE 主产品行（composite_type='COMPOSITE'），"
                        + "实际取不到 ⇒ ③ 无从验起。行结构见上一行日志。");
        assertTrue(children.contains(outsourced),
                "A-AC-7① 前置：外购件 " + outsourced + " 应作为子件出现在报价行里，实际子件="
                        + children + " ⇒ ① 会打错靶");
        List<String> partChildren = children.stream().filter(c -> !c.equals(outsourced)).toList();
        assertEquals(1, partChildren.size(),
                "A-AC-7② 前置：应恰好 1 个零件子料号，实际=" + partChildren + "（全部子件=" + children + "）");
        String partChild = partChildren.get(0);
        System.out.println("[A-AC-7] 角色分派：父(COMPOSITE 主产品)=" + parent
                + "  零件子料号=" + partChild + "  外购件子料号=" + outsourced);

        List<String> cast = new java.util.ArrayList<>(List.of(parent));
        cast.addAll(children);
        assertFreshlyCast(before, cast, "A-AC-7");

        List<Object[]> got = rows("SELECT material_no, coalesce(material_type,'(null)'), source "
                + "FROM ds_quote_material WHERE material_no IN ("
                + cast.stream().map(c -> "'" + c + "'").collect(java.util.stream.Collectors.joining(","))
                + ") ORDER BY material_no");
        System.out.println("[A-AC-7] 本轮铸出料号的 material_type 实际落库="
                + got.stream().map(java.util.Arrays::toString).toList());

        // ① 外购件
        assertEquals("外购件", materialTypeOf(outsourced),
                "A-AC-7①：本轮新建的外购件 " + outsourced + " 的 material_type 应为『外购件』，实际="
                        + materialTypeOf(outsourced) + "。落库实况=" + got.stream().map(java.util.Arrays::toString).toList());
        // ② 零件子料号
        assertEquals("零件", materialTypeOf(partChild),
                "A-AC-7②：零件子料号 " + partChild + " 的 material_type 应为『零件』，实际="
                        + materialTypeOf(partChild));
        // ③ COMPOSITE 主产品（2026-09-04 裁决新增；🚫 上一轮的「父件传 null」已作废）
        assertEquals("零件", materialTypeOf(parent),
                "A-AC-7③：COMPOSITE 主产品 " + parent + " 的 material_type 应为『零件』"
                        + "（2026-09-04 用户裁决原话：『物料表中主产品的类型应该是[零件]』），实际="
                        + materialTypeOf(parent)
                        + "。⚠️ 若实际是 (null)，说明写入侧仍是被裁决作废的『主产品传 null』旧口径。");

        // 🚫 不得有 NULL —— 本轮铸出的这批料号里 material_type IS NULL 的行数必须 = 0
        long nulls = count("SELECT count(*) FROM ds_quote_material WHERE material_type IS NULL "
                + "AND material_no IN ("
                + cast.stream().map(c -> "'" + c + "'").collect(java.util.stream.Collectors.joining(",")) + ")");
        assertEquals(0L, nulls,
                "A-AC-7🚫：本轮铸出的 " + cast.size() + " 个料号里，material_type IS NULL 的行数应为 0，实际 "
                        + nulls + " 行。落库实况=" + got.stream().map(java.util.Arrays::toString).toList());
    }

    /**
     * <b>A-AC-11</b>（2026-09-04 新增）：「选配铸出的<b>所有</b>新料号，
     * {@code ds_quote_material.category_code} = <b>{@code 000000}</b>
     * （{@code product_category} 里名为「默认分类」）。🚫 不得为 NULL」。
     *
     * <p>🚨 <b>恒真自查</b>：如果断言落在<b>存量</b>行上，它是恒真的 ——
     * 现存 45 条 {@code source=IMPORT} 行本来<b>全部</b>就是 {@code 000000}（实测 2026-09-04）。
     * ⇒ 必须先用 {@link #assertFreshlyCast} 证明这些料号<b>提交前不存在</b>，
     * 断言才落在「本轮新造的行」上。
     */
    @Test
    @DisplayName("A-AC-11 选配铸出的所有新料号 category_code = 000000（默认分类）")
    void aac11_newlyCastMaterialsGetDefaultCategory() {
        assertEquals(1L, count("SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name='ds_quote_material' AND column_name='category_code'"),
                "A-AC-11 前置：ds_quote_material.category_code 列不存在 ⇒ 环境前置缺失，不是被测功能的结论。");
        assertEquals("默认分类", scalar("SELECT name FROM product_category WHERE code='000000'"),
                "A-AC-11 前置：product_category 里 code='000000' 应名为『默认分类』（AC 原文口径），"
                        + "取不到或名字不对 ⇒ 断言的常量 000000 失去业务含义。");

        String outsourced = createSyntheticOutsourcedPart();
        java.util.Set<String> before = dsMaterialNoSnapshot();

        Fx fx = newFixture("aac11");
        Response res = configure(fx, submitBody(PREFIX + "A11",
                newPart("触点", "φ5", "5×3×2", "10",
                        List.of(material(RECIPE_A, CONFIG_A, "70"),
                                material(RECIPE_B, CONFIG_B, "30")),
                        List.of(PROC_1)),
                outsourcedPart(outsourced, List.of(PROC_2))));
        assertSubmitOk(res, "A-AC-11 提交");

        String parent = compositeParentPartNo(fx);
        List<String> children = childPartNos(fx);
        assertNotNull(parent, "A-AC-11 前置：应铸出 COMPOSITE 主产品行，实际取不到。行结构="
                + lineItemsOf(fx).stream().map(java.util.Arrays::toString).toList());
        System.out.println("[A-AC-11] 角色分派：父(COMPOSITE 主产品)=" + parent
                + "  子件=" + children + "（其中外购件=" + outsourced + "）");

        List<String> cast = new java.util.ArrayList<>(List.of(parent));
        cast.addAll(children);
        // 🚨 「所有新料号」= 父 + 全部子件，一个都不许漏；且必须证明它们提交前不存在（否则恒真）
        assertFreshlyCast(before, cast, "A-AC-11");

        String inList = cast.stream().map(c -> "'" + c + "'")
                .collect(java.util.stream.Collectors.joining(","));
        List<Object[]> got = rows("SELECT material_no, coalesce(category_code,'(null)'), source "
                + "FROM ds_quote_material WHERE material_no IN (" + inList + ") ORDER BY material_no");
        System.out.println("[A-AC-11] 本轮铸出料号的 category_code 实际落库="
                + got.stream().map(java.util.Arrays::toString).toList());
        assertEquals(cast.size(), got.size(),
                "A-AC-11 前置：本轮铸出 " + cast.size() + " 个料号（" + cast + "），"
                        + "但 ds_quote_material 只读到 " + got.size() + " 条主档 ⇒ 有料号根本没落主档，断言会漏掉它");

        for (Object[] r : got) {
            assertEquals("000000", String.valueOf(r[1]),
                    "A-AC-11：选配铸出的新料号 " + r[0] + "（source=" + r[2] + "）的 category_code 应为『000000』"
                            + "（默认分类），实际=" + r[1]
                            + "。⚠️ 若实际是 (null)，说明写入侧仍是被 2026-09-04 裁决作废的"
                            + "『category_code 本方法刻意不写』旧口径。全量落库实况="
                            + got.stream().map(java.util.Arrays::toString).toList());
        }
    }

    /**
     * <b>A-AC-7🚫 与 A-AC-11🚫 的判据原文</b>（需求文档 §4.2 逐字照抄）：
     * <ul>
     *   <li>A-AC-7：{@code 选配铸出的料号里 material_type IS NULL 的行数 = 0}</li>
     *   <li>A-AC-11：{@code SELECT count(*) FROM ds_quote_material WHERE source='MANUAL'
     *       AND category_code IS DISTINCT FROM '000000'} = 0</li>
     * </ul>
     *
     * <p>🚨 这两条判据的作用域是<b>整张表的 {@code source='MANUAL'} 全集</b>，不只是本轮新造的行 ——
     * 与上面两个用例（只约束本轮产出）<b>不是同一个口径</b>，故单独成一个用例，
     * 让红/绿的归因不会混在一起。失败信息里会把「本轮之前就存在的行」单独列出来，
     * 因为那类行<b>不是写入侧改一行代码就能变绿的</b>，需要主线裁决（补一支回填迁移 or 收窄判据口径）。
     */
    @Test
    @DisplayName("A-AC-7🚫/A-AC-11🚫 判据原文：全表 source=MANUAL 不得有 NULL 类型 / 非默认分类")
    void acLiteralGuards_noNullTypeAndNoNonDefaultCategoryAmongManualRows() {
        List<Object[]> all = rows("SELECT material_no, coalesce(material_type,'(null)'), "
                + "coalesce(category_code,'(null)'), created_at::text "
                + "FROM ds_quote_material WHERE source='MANUAL' ORDER BY created_at, material_no");
        System.out.println("[判据原文] ds_quote_material 全部 source='MANUAL' 行（" + all.size() + " 条）="
                + all.stream().map(java.util.Arrays::toString).toList());
        assertTrue(all.size() > 0,
                "判据前置：ds_quote_material 里应有 source='MANUAL'（选配铸出）的行，实际 0 条 ⇒ "
                        + "两条判据都会在『压根没有行』的情况下成立（testing.md §3 第 3 号陷阱：空跑即假绿）");

        long nullType = count("SELECT count(*) FROM ds_quote_material "
                + "WHERE source='MANUAL' AND material_type IS NULL");
        long badCat = count("SELECT count(*) FROM ds_quote_material "
                + "WHERE source='MANUAL' AND category_code IS DISTINCT FROM '000000'");
        System.out.println("[判据原文] material_type IS NULL 行数=" + nullType
                + "；category_code IS DISTINCT FROM '000000' 行数=" + badCat);

        assertEquals(0L, nullType,
                "A-AC-7🚫 判据原文：选配铸出（source='MANUAL'）的料号里 material_type IS NULL 的行数应为 0，实际 "
                        + nullType + " 行。全部 MANUAL 行（含 created_at）="
                        + all.stream().map(java.util.Arrays::toString).toList()
                        + "\n  ⚠️ 归因提示：若这些行的 created_at **早于本轮**，那是写入侧改好之前就已落库的存量行，"
                        + "写入侧修复不会追溯改写它们 ⇒ 需主线裁决（补回填迁移 or 把判据口径收窄成「本轮产出」）。");
        assertEquals(0L, badCat,
                "A-AC-11🚫 判据原文：SELECT count(*) FROM ds_quote_material WHERE source='MANUAL' "
                        + "AND category_code IS DISTINCT FROM '000000' 应为 0，实际 " + badCat
                        + " 行。全部 MANUAL 行（含 created_at）="
                        + all.stream().map(java.util.Arrays::toString).toList()
                        + "\n  ⚠️ 归因提示同上。");
    }

    /** {@code ds_quote_material.material_type} 实际值（不存在返 null，NULL 返 {@code (null)}）。 */
    private String materialTypeOf(String materialNo) {
        return scalar("SELECT coalesce(material_type,'(null)') FROM ds_quote_material "
                + "WHERE material_no='" + materialNo + "'");
    }

    /**
     * <b>A-AC-7 → A-AC-8 的链路补强</b>：外购件身份必须<b>穿得过兼容视图</b>，不能只存在于表里。
     *
     * <p>A-AC-7 原文只要求「{@code ds_quote_material.material_type} = 外购件」——那是<b>写入侧</b>。
     * 但 A-AC-8 要求「产品卡片正常渲染出外购件」，而渲染侧读的是
     * {@code v_compat_material_master}。⇒ 只验表侧会漏掉「写了但渲染不出来」这一整类。
     *
     * <p>🚨 实测（2026-09-04）本条<b>现在是红的</b>，且根因已定位：
     * {@code v_compat_material_master} 的新表侧把 {@code material_type} 硬写成
     * {@code NULL::character varying(50)}，于是新表独有的外购件（如 {@code S0003}）
     * 在视图里 {@code material_type} 为空 ——
     * {@code SELECT count(*) FROM v_compat_material_master WHERE material_type='外购件'}
     * 只数得到 V6 侧那一条，新表侧的一条都数不到。
     */
    @Test
    @DisplayName("A-AC-7→A-AC-8 外购件身份必须穿得过兼容视图（非仅落表）")
    void aac7_outsourcedTypeMustSurviveCompatView() {
        requireCompatViews();
        List<Object[]> lost = rows(
                "SELECT m.material_no, m.material_type, coalesce(v.material_type,'(空)') "
                        + "FROM ds_quote_material m JOIN v_compat_material_master v ON v.material_no = m.material_no "
                        + "WHERE m.material_type IS NOT NULL AND v.material_type IS DISTINCT FROM m.material_type "
                        + "ORDER BY m.material_no");
        System.out.println("[A-AC-7→8] material_type 在兼容视图里丢失/变形的料号="
                + lost.stream().map(java.util.Arrays::toString).toList());

        long haveType = count("SELECT count(*) FROM ds_quote_material WHERE material_type IS NOT NULL");
        assertTrue(haveType > 0,
                "A-AC-7→8 前置：ds_quote_material 里应有带 material_type 的料号，实际 0 条 ⇒ 本断言会空跑（假绿）");

        assertEquals(List.of(), lost.stream().map(java.util.Arrays::toString).toList(),
                "A-AC-7→8：ds_quote_material.material_type 必须原样透传到 v_compat_material_master，"
                        + "否则渲染侧看不到外购件身份（A-AC-8 会渲染不出外购件）。"
                        + "上面列出的每一行都是 表里有值 / 视图里没有。");
    }

    /**
     * 从提交响应里取「零件」子料号：{@code compositeType='PART'} 且不是外购件的那个。
     * <p>SIMPLE 提交时没有子层，直接返回唯一的行料号。
     */
    private String childPartNoOf(Response res, String outsourcedNo) {
        List<Map<String, Object>> items = res.jsonPath().getList("lineItems");
        if (items == null || items.isEmpty()) return null;
        if (items.size() == 1) return String.valueOf(items.get(0).get("productPartNo"));
        return items.stream()
                .filter(i -> "PART".equals(String.valueOf(i.get("compositeType"))))
                .map(i -> String.valueOf(i.get("productPartNo")))
                .filter(pn -> !pn.equals(outsourcedNo))
                .findFirst().orElse(null);
    }

    /** {@code version_no} 全 1 —— 先要求行数 > 0，否则「全 1」在 0 行时也成立。 */
    private void assertVersionAllOne(String table, String materialNo, long expectRows, String when) {
        assertTrue(expectRows > 0, when + "：" + table + " 应有行才能验 version_no，实际 0 行 ⇒ 断言会空跑");
        List<Object> vs = col("SELECT DISTINCT version_no FROM " + table
                + " WHERE material_no='" + materialNo + "' ORDER BY 1");
        System.out.println("[" + when + "] " + table + " 的 version_no 取值集合=" + vs + "（" + expectRows + " 行）");
        assertEquals(List.of(1), vs.stream().map(v -> ((Number) v).intValue()).toList(),
                when + "：A-AC-1④ 要求 " + table + " 的 version_no 全部 = 1（选配阶段不升版），实际取值=" + vs);
    }
}
