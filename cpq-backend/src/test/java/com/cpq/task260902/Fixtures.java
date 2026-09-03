package com.cpq.task260902;

import java.util.List;
import java.util.Map;

/**
 * 三份模板 → 可导入夹具的<b>唯一构造入口</b>。
 *
 * <h3>为什么要改模板里的主数据值（2026-09-03 实查结论，不是我编的）</h3>
 * <pre>
 * SELECT code,name,status FROM process WHERE code IN ('Z053','Z008','Z490','Z002','Z611');  → 0 行
 * SELECT count(*) FROM process;                                                             → 0
 * SELECT process_no FROM process_master;                                          → Z100,Z101,TP10,TP20
 * SELECT code FROM material_recipe WHERE code IN ('00168','00006','991','992');   → 00168,00006,992（991 缺）
 * SELECT element_code FROM element WHERE element_code IN ('Cu','Ag','Ni','301');  → 四个都在，ACTIVE
 * </pre>
 * ⇒ 模板自带的工序编号在库里<b>一个都不存在</b>，材质料号 {@code 991} 也不存在。
 * 按 {@code R-7}「主数据存在性：工序编号 ∈ process、材质料号 ∈ material_recipe」，
 * <b>原样导入模板必然 400</b>，AC-11 起的所有「导入成功」前置就永远达不到。
 * ⇒ <b>2026-09-03 主线已把缺失的主数据全部补进库</b>（工序 5 个 / 元素 4 个 / 材质 991），
 * 夹具遂改为<b>动态探测</b>：库里有就原样用，只有确实缺失才回退，见 {@link MasterDataProbe}。
 *
 * <p>🚫 除这几个主数据值和 {@code TEST-DS-} 轴前缀之外，<b>夹具不改模板的任何一格</b> ——
 * 尤其 AC 点名的数值锚点（组成用量 1 / 加工费 5.5 / 项次 10 / 含量 21.11、2.78）一律保持原值。
 */
final class Fixtures {

    /**
     * 主数据存在性探针 —— 由 {@link DatasetAcTestBase} 用 {@code EntityManager} 实现。
     *
     * <p>🚩 <b>2026-09-03 04:22 起替换机制改为动态</b>：此前是一张写死的映射表
     * （{@code Z053→Z100}、{@code 991→00006} …），主线把主数据补进库之后，
     * 那张表不但多余，还<b>反过来让断言翻转</b>（{@code td02b} 断言「991 不存在」直接红）。
     *
     * <p>⇒ 现在每个值都<b>先查库</b>：库里有就原样用（这是常态），
     * 只有确实缺失时才回退到替代值，并把这次回退登记进 {@link #appliedSubstitutions}。
     * 🚫 保留机制本身而不是删掉 —— 别的会话若又删了主数据，夹具仍能跑，
     * 且回退动作会被登记下来，不会变成一次沉默的偷换。
     */
    interface MasterDataProbe {
        /** 该值是否存在于主数据表中。 */
        boolean exists(String table, String column, String value);
    }

    /** 本次构造实际发生的回退（正常情况下应为空）。 */
    static final java.util.Map<String, String> appliedSubstitutions = new java.util.LinkedHashMap<>();

    /**
     * 只在「库里查不到」时才把 {@code original} 换成 {@code fallback}。
     * 库里有就原样返回 —— 这是主数据补齐后的正常路径。
     */
    private static String resolve(MasterDataProbe probe, String table, String column,
                                  String original, String fallback) {
        if (original == null || original.isBlank() || probe == null) {
            return original;
        }
        if (probe.exists(table, column, original)) {
            return original;
        }
        appliedSubstitutions.put(table + "." + column + "=" + original,
                fallback + "（库中查无 " + original + "，回退）");
        return fallback;
    }

    /** 按探针结果把某列里缺失的值逐个换成替代值（存在的一律不动）。 */
    private static void resolveColumn(DatasetFixtureBuilder b, String sheet, String column,
                                      boolean versioned, MasterDataProbe probe,
                                      String masterTable, String masterColumn, String fallback) {
        for (int rowNo : b.dataRowNumbers(sheet, versioned)) {
            String v = b.readAsString(sheet, rowNo, column);
            if (v == null || v.isBlank()) {
                continue;
            }
            String resolved = resolve(probe, masterTable, masterColumn, v.trim(), fallback);
            if (!resolved.equals(v.trim())) {
                b.setText(sheet, rowNo, column, resolved);
            }
        }
    }

    /**
     * 🚨 <b>D-24 适配</b>：把一个轴值登记进同一份 Excel 的「物料」sheet。
     *
     * <p>裁决 D-24 的判定集合是「本次 Excel 物料 sheet 的轴值 ∪ 库中物料表已有的轴值」，
     * 所以夹具凡是<b>造出新轴值</b>（改轴、造性能数据…），都必须同步登记，否则整份被拒。
     * 🚫 这不是绕过校验 —— 它就是规则要求的「同文件内先登记后引用」，
     * {@code TI-07} 第二段专门验的就是这条分支。
     */
    static void registerAxis(DatasetFixtureBuilder b, String axisColumn, String axisValue, String name) {
        for (int rowNo : b.dataRowNumbers("物料", false)) {
            if (axisValue.equals(b.readAsString("物料", rowNo, axisColumn))) {
                return; // 已登记
            }
        }
        int template = b.dataRowNumbers("物料", false).get(0);
        b.appendCopyOf("物料", template, row -> {
            row.getCell(0).setCellValue(axisValue);
            if (row.getCell(1) != null) {
                row.getCell(1).setCellValue(name);
            }
        });
    }

    /** 裁决 D-18 补入的列名（Excel 里尚无，由夹具补）。 */
    static final String CUSTOMER_NO_COLUMN = "客户编号";

    /** element 表实存的第 4 个元素代码（element_code='301'，element_name='301不锈钢'，ACTIVE）。 */
    static final String QUOTE_ELEMENT_301 = "301";

    private Fixtures() {
    }

    // ══════════════════════════ 基础核价（核价2） ══════════════════════════

    /** 「核价2」十个 sheet 的名字，顺序即 Excel 顺序（AC-26 的 9 个 tab = 去掉「物料」）。 */
    static final List<String> COST_BASIC_SHEETS = List.of(
            "物料", "物料BOM", "物料与元素BOM", "来料加工费", "来料其他费用",
            "来料其他固定费用", "加工费&组装费", "其他外加工成本", "成品其他比例费用", "成品其他固定费用");

    /** 抽屉里的 9 个带版本 tab（AC-26 原文顺序）。 */
    static final List<String> COST_BASIC_VERSIONED_SHEETS = List.of(
            "物料BOM", "物料与元素BOM", "来料加工费", "来料其他费用", "来料其他固定费用",
            "加工费&组装费", "其他外加工成本", "成品其他比例费用", "成品其他固定费用");

    /**
     * 基准夹具：模板原样 + 轴值加前缀 + 主数据值替换为库中真实值。
     * 调用方拿到后可继续改值，改完 {@code toBytes()}。
     */
    static DatasetFixtureBuilder costBasic() {
        return costBasic(null);
    }

    static DatasetFixtureBuilder costBasic(MasterDataProbe probe) {
        DatasetFixtureBuilder b = DatasetFixtureBuilder.from(DatasetFixtureBuilder.costBasicTemplate());

        // ① 轴值加 TEST-DS- 前缀（只动轴列）
        b.prefixAxisValues("物料", "生产料号", false, DatasetAcTestBase.P);
        for (String sheet : COST_BASIC_VERSIONED_SHEETS) {
            b.prefixAxisValues(sheet, "生产料号", true, DatasetAcTestBase.P);
        }

        // ② 工序编号：主线已于 2026-09-03 把 Z002/Z008/Z053/Z490/Z611 补进 process_master
        //    ⇒ 正常路径下**一个都不会被替换**；只有别的会话删了主数据才回退到 Z100。
        resolveColumn(b, "加工费&组装费", "工序编号", true, probe,
                "process_master", "process_no", DatasetAcTestBase.PROCESS_Z100);
        resolveColumn(b, "其他外加工成本", "工序编号", true, probe,
                "process_master", "process_no", DatasetAcTestBase.PROCESS_Z100);

        // ③ 材质料号：991 已由主线补进 material_recipe ⇒ 正常路径下不替换
        resolveColumn(b, "物料与元素BOM", "材质料号", true, probe,
                "material_recipe", "code", DatasetAcTestBase.RECIPE_00006);

        // ④ 元素代码：模板用的 Cu/Ag/Ni/301 本就都在
        resolveColumn(b, "物料与元素BOM", "元素代码", true, probe,
                "element", "element_code", DatasetAcTestBase.ELEMENT_CU);

        // ④ 裁决 D-23：占位行（只填轴、其余全空）按现规则报「必填项为空」并整份拒收。
        //    模板自带 7 行这种占位行（物料与元素BOM r10~r16）⇒ 原始模板导入必得 400（28 处必填为空）。
        //    这是**预期行为不是缺陷**，但它会淹没每一条校验用例的判据
        //    （AC-6 要求「含且仅含一条」错误，多出 28 条就没法验了）。
        //    ⇒ 基准夹具一律用**裁剪版**：删掉占位行。
        //    🚫 裁剪只删「只有轴、别的全空」的行，不放宽任何校验规则 —— 那会让 AC-6/7 失去意义。
        trimPlaceholderRows(b, COST_BASIC_VERSIONED_SHEETS, "生产料号");

        return b;
    }

    /**
     * 删除「占位行」：轴列有值、但该 sheet 其余<b>所有</b>列都为空的行（裁决 D-23）。
     *
     * <p>⚠️ 判据故意写成「其余列全空」而不是「某几列空」——
     * 只要有任何一个业务列填了值，它就是一条真实数据行，缺必填项就该报错，不许被当占位行悄悄吃掉。
     */
    private static void trimPlaceholderRows(DatasetFixtureBuilder b, List<String> sheets, String axisColumn) {
        for (String sheet : sheets) {
            List<Integer> victims = new java.util.ArrayList<>();
            for (int rowNo : b.dataRowNumbers(sheet, true)) {
                String axis = b.readAsString(sheet, rowNo, axisColumn);
                if (axis == null || axis.isBlank()) {
                    continue; // 轴为空的是另一类问题（AC-7 要验的），不在这里动
                }
                if (b.isOnlyColumnFilled(sheet, rowNo, axisColumn)) {
                    victims.add(rowNo);
                }
            }
            // 倒序删，避免上移导致行号漂移
            for (int i = victims.size() - 1; i >= 0; i--) {
                b.deleteRow(sheet, victims.get(i));
            }
        }
    }

    // ══════════════════════════ 报价 ══════════════════════════

    /** 「报价」16 个 sheet，顺序即 Excel 顺序。 */
    static final List<String> QUOTE_SHEETS = List.of(
            "物料", "客户料号", "物料BOM", "物料与元素BOM", "来料固定加工费", "来料其他费用",
            "来料回收折扣", "自制加工费", "成品其他费用", "组成件其他费用", "组装加工费",
            "组装加工费年降", "电镀费用", "电镀方案", "来料年降", "年降系数");

    /** 报价侧 13 张带版本 sheet（= 16 − 物料 / 客户料号 / 电镀方案）。 */
    static final List<String> QUOTE_VERSIONED_SHEETS = List.of(
            "物料BOM", "物料与元素BOM", "来料固定加工费", "来料其他费用", "来料回收折扣",
            "自制加工费", "成品其他费用", "组成件其他费用", "组装加工费", "组装加工费年降",
            "电镀费用", "来料年降", "年降系数");

    /**
     * 报价基准夹具。
     *
     * <p>🚩 相对模板还多做了两处「使模板可导入」的修正，同样登记备查：
     * <ol>
     *   <li>{@code 来料回收折扣} 第 3 行是一条<b>轴值为空</b>的错位残行（模板自带：销售料号空、投入料号写着「外购件」），
     *       按 R-1「轴列一律必填」它必然触发 400 ⇒ 夹具删掉该行。<b>这条是模板缺陷，须报主线。</b></li>
     *   <li>{@code 物料与元素BOM} 的「元素」列模板填的是<b>元素名称</b>（线材 / 电解铜 / 锌锭 …），
     *       而 {@code element} 表里只有「白银」一个同名值 ⇒ 按 R-7 会 400。夹具改填实存的元素代码。</li>
     * </ol>
     */
    static DatasetFixtureBuilder quote() {
        return quote(null);
    }

    static DatasetFixtureBuilder quote(MasterDataProbe probe) {
        DatasetFixtureBuilder b = DatasetFixtureBuilder.from(DatasetFixtureBuilder.quoteTemplate());

        // ⓪ 裁决 D-18 的「客户编号」红底必填列。
        //    🚩 2026-09-03 04:22 用户已把这一列补进模板（值 CUST-0004）⇒ 正常路径下**不再插列**。
        //    只有模板退回旧版本才补，否则会插出一列重复表头（曾因此让 FS-04 断言反转）。
        if (!b.hasColumn("客户料号", CUSTOMER_NO_COLUMN)) {
            b.insertColumnAt("客户料号", 0, CUSTOMER_NO_COLUMN, DatasetAcTestBase.CUSTOMER_ROCKWELL);
        }

        // ① 模板曾自带一条错位残行（来料回收折扣 第 3 行：销售料号空、投入料号写着「外购件」）。
        //    🚩 用户已于 2026-09-03 02:25 重存模板修掉了它。
        //    这里保留**条件式**清理：模板若再退回旧版本，夹具仍能用；模板是好的就什么都不做。
        //    🚫 但它只是兜底，不是判据 —— 判据在 TD-02e，那条会明确告诉你残行有没有回来。
        removeStrayAxisRows(b, "来料回收折扣", "销售料号");

        // ② 轴值加前缀
        b.prefixAxisValues("物料", "销售料号", false, DatasetAcTestBase.P);
        b.prefixAxisValues("客户料号", "销售料号", false, DatasetAcTestBase.P);
        for (String sheet : QUOTE_VERSIONED_SHEETS) {
            b.prefixAxisValues(sheet, "销售料号", true, DatasetAcTestBase.P);
        }

        // ③ 元素：主线已于 2026-09-03 把 线材/电解铜/钢板/锌锭 补进 element
        //    ⇒ 正常路径下不替换；缺了才回退到 Cu，且回退会登记进 appliedSubstitutions。
        resolveColumn(b, "物料与元素BOM", "元素", true, probe,
                "element", "element_code", DatasetAcTestBase.ELEMENT_CU);

        return b;
    }

    // ══════════════════════════ 工具 ══════════════════════════

    /**
     * 删除某个带版本 sheet 里「轴值为空」的残行（条件式：没有就什么都不做）。
     *
     * <p>🚫 这不是「放宽校验」—— 轴列为空本来就该被 R-1 拒收（AC-7 专门验它）。
     * 这里删的是**模板自带的、与业务无关的错位残行**，目的是让其余判据能跑到。
     * 残行是否存在由 {@code TD-02e} 单独断言，不靠这个方法沉默处理。
     */
    private static void removeStrayAxisRows(DatasetFixtureBuilder b, String sheet, String axisColumn) {
        List<Integer> victims = new java.util.ArrayList<>();
        for (int rowNo : b.dataRowNumbers(sheet, true)) {
            String axis = b.readAsString(sheet, rowNo, axisColumn);
            if (axis == null || axis.isBlank()) {
                victims.add(rowNo);
            }
        }
        for (int i = victims.size() - 1; i >= 0; i--) {
            b.deleteRow(sheet, victims.get(i));
        }
    }

    // ══════════════════════════ 详细核价（核价1） ══════════════════════════

    /** 「核价1」19 个 sheet，顺序即 Excel 顺序（AC-26 的 17 个 tab = 去掉「物料」与「电镀方案」）。 */
    static final List<String> COST_DETAIL_SHEETS = List.of(
            "物料", "物料BOM", "物料与元素BOM", "产能", "设备折旧成本", "生产设备能耗", "辅助设备能耗",
            "模具工装成本", "生产耗材BOM", "包装材料BOM", "来料加工费", "来料其他费用", "来料其他固定费用",
            "加工费&组装费", "其他外加工成本", "电镀成本", "成品其他比例费用", "成品其他固定费用", "电镀方案");

    /** 详细核价抽屉里的 17 个带版本 tab（AC-26）。 */
    static final List<String> COST_DETAIL_VERSIONED_SHEETS = List.of(
            "物料BOM", "物料与元素BOM", "产能", "设备折旧成本", "生产设备能耗", "辅助设备能耗",
            "模具工装成本", "生产耗材BOM", "包装材料BOM", "来料加工费", "来料其他费用", "来料其他固定费用",
            "加工费&组装费", "其他外加工成本", "电镀成本", "成品其他比例费用", "成品其他固定费用");

    /**
     * 详细核价基准夹具。
     * 🚩 2026-09-03 02:25 用户重存了 `核价1 - …xlsx`（此前是 BadZipFile），本方法自此可用。
     */
    static DatasetFixtureBuilder costDetail() {
        return costDetail(null);
    }

    static DatasetFixtureBuilder costDetail(MasterDataProbe probe) {
        DatasetFixtureBuilder b = DatasetFixtureBuilder.from(DatasetFixtureBuilder.costDetailTemplate());
        if (probe != null) {
            resolveColumn(b, "产能", "工序编号", true, probe,
                    "process_master", "process_no", DatasetAcTestBase.PROCESS_Z100);
        }

        b.prefixAxisValues("物料", "生产料号", false, DatasetAcTestBase.P);
        for (String sheet : COST_DETAIL_VERSIONED_SHEETS) {
            b.prefixAxisValues(sheet, "生产料号", true, DatasetAcTestBase.P);
        }
        trimPlaceholderRows(b, COST_DETAIL_VERSIONED_SHEETS, "生产料号");
        return b;
    }


}
