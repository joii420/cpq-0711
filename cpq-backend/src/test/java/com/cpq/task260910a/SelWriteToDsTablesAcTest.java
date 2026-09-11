package com.cpq.task260910a;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260910 · <b>分片 S-A</b>：选配写入侧切表的验收用例（{@code test.md §3} 的 TC-A1~TC-A4）。
 *
 * <table>
 *   <tr><th>用例</th><th>AC</th><th>类型</th><th>关键可观测断言</th></tr>
 *   <tr><td>TC-A1</td><td>AC-1</td><td>单点</td><td>{@code ds_quote_self_process_fee} 1 行 10 列逐字 + {@code unit_price} 零新增 + 渲染 SQL 读得到 Z008</td></tr>
 *   <tr><td>TC-A2</td><td>AC-2</td><td>单点</td><td>{@code ds_quote_assembly_fee} 1 行 {@code assembly_fee=0} {@code source='MANUAL'} + {@code capacity} 零新增</td></tr>
 *   <tr><td>TC-A3</td><td>AC-3</td><td>单点</td><td>外购件工序进 {@code _assembly_fee}、<b>不进</b> {@code _self_process_fee}（费用类别 D-6）</td></tr>
 *   <tr><td>TC-A4</td><td>AC-4</td><td>边界</td><td>COMPOSITE（D-15）：父轴恰好 2 行（轴=父料号，{@code input_material_no}=子件料号）+ 子轴各 1 行（自指）= 合计 4 行</td></tr>
 * </table>
 *
 * <h3>🚨 每条「零新增」断言都自带阳性对照</h3>
 * 「老表零新增」单独看是<b>恒真陷阱</b>（{@code testing.md §5.5} 形态②③）——
 * 压根没执行到写入时它同样成立。⇒ 本套用例把「新表有行」与「老表零行」<b>写在同一个用例里</b>：
 * 新表非空证明写入路径确实跑到了，老表零行才因此有意义。
 * （完整的 FT-4 证伪实验需要把 B-1 的切表改回去，属实现代码改动 ⇒ 已把步骤报主线，见 test-report。）
 */
@QuarkusTest
@DisplayName("S-A 选配写入侧切 ds_quote_* 新表（AC-1~AC-4）")
class SelWriteToDsTablesAcTest extends SelDsWriteAcBase {

    /** 渲染侧列名（{@code component_sql_view.sql_template} 现读；变了就硬失败而不是静默漏验）。 */
    private static final String COL_OPERATION_NO = "_自制加工费_工序编号";

    // ══════════════════════════ AC-1 ══════════════════════════

    /**
     * <b>AC-1（单点 · 自制工序落新表）</b> 原文：
     * 「前置：选配新建零件「品名 A1 / 规格 234 / 尺寸 234 / 总重 11」，材质 {@code AgCu90} 占比 100，
     * 工序选 {@code Z008}。断言：① {@code ds_quote_self_process_fee} 新增 <b>1 行</b>：
     * {@code material_no=} 新铸料号 · {@code input_material_no=} 同一新铸料号（D-4）·
     * {@code operation_no='Z008'} · {@code operation_item_seq=1} · {@code item_seq=1} ·
     * {@code currency='CNY'} · {@code pricing_unit=} {@code process_master} 里 {@code Z008} 的
     * {@code standard_unit}（空则 {@code 'KG'}）· {@code value IS NULL} · {@code version_no=1} ·
     * {@code source='MANUAL'}；② {@code unit_price} 表<b>零新增</b>；
     * ③ 卡片「自制加工费」页签渲染出<b>该 1 行</b>，{@code 工序编号} 列显示 {@code Z008}」。
     */
    @Test
    @DisplayName("TC-A1 · AC-1 自制工序落 ds_quote_self_process_fee（10 列逐字）+ unit_price 零新增 + 渲染读得到")
    void tcA1_selfProcessFeeGoesToNewTable() {
        assertEnvSanity();
        Fx fx = newFixture("a1");

        // 🚨 前置：本片客户是全新的 ⇒ 提交前两张表在本客户下必须是 0 行。
        //    不打这一枪，「新增 1 行」可能是把存量行数错当成新增（testing.md §5.5）。
        assertEquals(0L, count("SELECT count(*) FROM ds_quote_self_process_fee WHERE customer_no='"
                        + fx.customerNo() + "'"),
                "TC-A1 前置：新建客户名下 ds_quote_self_process_fee 应为 0 行（否则「新增 1 行」判据失效）");
        assertEquals(0L, count("SELECT count(*) FROM unit_price WHERE customer_no='" + fx.customerNo() + "'"),
                "TC-A1 前置：新建客户名下 unit_price 应为 0 行");

        Response res = configure(fx, submitBody("SIMPLE", PREFIX + "A1-" + RUN_ID,
                newPart(PREFIX + "A1", "234", "234", "11",
                        List.of(material(RECIPE_AGCU90, CONFIG_AGCU90, "100")),
                        List.of(PROC_Z008))));
        assertSubmitOk(res, "TC-A1 提交");

        String partNo = rootPartNo(fx);
        System.out.println("[TC-A1] 新铸销售料号=" + partNo
                + " 报价行结构=" + fmt(lineItemsOf(fx)));

        // ── ① 新表 1 行，10 列逐字 ──
        List<Object[]> spf = selfProcessRows(fx);
        System.out.println("[TC-A1①] ds_quote_self_process_fee(customer_no=" + fx.customerNo() + ") = " + fmt(spf));
        assertEquals(1, spf.size(),
                "AC-1①：本片客户名下 ds_quote_self_process_fee 应恰好 1 行，实际 " + spf.size() + " 行：" + fmt(spf));

        Object[] r = spf.get(0);
        String expUnit = expectedPricingUnit(PROC_Z008);
        assertEquals(partNo, String.valueOf(r[0]), "AC-1① material_no 应为新铸料号。整行=" + Arrays.toString(r));
        assertEquals(partNo, String.valueOf(r[1]),
                "AC-1① input_material_no 应为同一新铸料号（D-4：SIMPLE 时零件料号=自己）。整行=" + Arrays.toString(r));
        assertEquals(PROC_Z008, String.valueOf(r[2]), "AC-1① operation_no 应为 Z008。整行=" + Arrays.toString(r));
        assertEquals("1", String.valueOf(r[3]), "AC-1① operation_item_seq 应为 1。整行=" + Arrays.toString(r));
        assertEquals("1", String.valueOf(r[4]), "AC-1① item_seq 应为 1。整行=" + Arrays.toString(r));
        assertEquals("CNY", String.valueOf(r[5]), "AC-1① currency 应为 CNY。整行=" + Arrays.toString(r));
        assertEquals(expUnit, String.valueOf(r[6]),
                "AC-1① pricing_unit 应取 process_master['" + PROC_Z008 + "'].standard_unit（空则 KG）= "
                        + expUnit + "。整行=" + Arrays.toString(r));
        assertNull(r[7], "AC-1① value 应为 NULL（选配不采集单价）。整行=" + Arrays.toString(r));
        assertEquals("1", String.valueOf(r[8]), "AC-1① version_no 应为 1。整行=" + Arrays.toString(r));
        assertEquals("MANUAL", String.valueOf(r[9]), "AC-1① source 应为 MANUAL。整行=" + Arrays.toString(r));

        // ── ② unit_price 零新增（🔑 阳性对照 = 上面的 1 行已经证明写入路径跑到了）──
        assertUnitPriceProbeCanSeeRows();
        long upByFinished = count("SELECT count(*) FROM unit_price WHERE finished_material_no='" + partNo + "'");
        long upByCode = count("SELECT count(*) FROM unit_price WHERE code='" + partNo + "'");
        long upByCust = count("SELECT count(*) FROM unit_price WHERE customer_no='" + fx.customerNo() + "'");
        System.out.println("[TC-A1②] unit_price 命中：finished_material_no=" + upByFinished
                + " code=" + upByCode + " customer_no=" + upByCust
                + "（阳性对照：新表已有 1 行 ⇒ 写入路径确实执行过，本条零断言不是空跑）");
        assertEquals(0L, upByFinished,
                "AC-1②：unit_price 应零新增（AC 原文判据 finished_material_no=新料号），实际 " + upByFinished + " 行");
        assertEquals(0L, upByCode,
                "AC-1② 补强：老映射把零件料号放在 unit_price.code ⇒ 按 code 也必须 0 行，实际 " + upByCode);
        assertEquals(0L, upByCust,
                "AC-1② 补强：本片客户名下 unit_price 整体必须 0 行，实际 " + upByCust);

        // ── ③ 渲染侧：卡片「自制加工费」页签的 SQL 读得到该行，工序编号列 = Z008 ──
        assertSelfProcessTabRendersZ008(fx, partNo);
    }

    /**
     * AC-1③ 的接口层证据：<b>用渲染侧自己的 SQL</b>（{@code component_sql_view.sql_template} 现读）
     * 查一次，断言非空且 {@code 工序编号} 列 = {@code Z008}。
     *
     * <p>⚠️ 这不等于「在浏览器里看见了」—— UI 层的证据由主线亲验（{@code testing.md §2}：
     * 只有接口层覆盖的 AC 不算已验收）。本条能证明的是：<b>数据落在渲染侧真正会去查的那张表、
     * 那一组过滤条件（{@code material_no} + {@code customer_no}）下</b>，
     * 而这正是「写了却渲染不出来」这类事故（AP-31 族）的判据。
     */
    private void assertSelfProcessTabRendersZ008(Fx fx, String partNo) {
        String tpl = scalar("SELECT v.sql_template FROM component_sql_view v JOIN component c ON c.id=v.component_id "
                + "WHERE v.sql_template ILIKE '%ds_quote_self_process_fee%' AND c.name='自制加工费' LIMIT 1");
        assertNotNull(tpl, "AC-1③ 前置：找不到「自制加工费」组件的 SQL 视图模板（component_sql_view）"
                + " ⇒ 渲染侧读什么无从查证。这是**前置缺失**，不是被测功能的结论");
        assertTrue(tpl.contains(COL_OPERATION_NO),
                "AC-1③ 前置：渲染侧模板里没有列 \"" + COL_OPERATION_NO + "\" ⇒ 断言的列名与实际契约不一致，"
                        + "须主线确认。模板=" + tpl);
        String sql = tpl.replace(":total_material_no", "ARRAY['" + partNo + "']")
                .replace(":customerCode", "'" + fx.customerNo() + "'");

        long all = count("SELECT count(*) FROM (" + sql + ") t");
        long z008 = count("SELECT count(*) FROM (" + sql + ") t WHERE t.\"" + COL_OPERATION_NO + "\"='"
                + PROC_Z008 + "'");
        System.out.println("[TC-A1③] 渲染侧 SQL 返回 " + all + " 行，其中工序编号=Z008 的 " + z008 + " 行");
        assertTrue(all > 0,
                "AC-1③：卡片「自制加工费」页签的 SQL 对本料号返回 0 行 ⇒ 写进去了却渲染不出来"
                        + "（空列表/0 行一律不算通过）。料号=" + partNo + " 客户=" + fx.customerNo());
        assertEquals(1L, z008,
                "AC-1③：应渲染出该 1 行且工序编号列显示 Z008，实际命中 " + z008 + " 行（总 " + all + " 行）");
    }

    // ══════════════════════════ AC-2 ══════════════════════════

    /**
     * <b>AC-2（单点 · 组合工艺落新表 + {@code assembly_fee} 写 0）</b> 原文：
     * 「前置：选配 2 个配件（构成 COMPOSITE），第 3 步选组合工序（{@code process_category IN ('ASSEMBLY','组装')}）。
     * 断言：① {@code ds_quote_assembly_fee} 新增 1 行：{@code material_no=} 父料号 ·
     * {@code assembly_operation=} 该工序编号 · {@code item_seq=1} · <b>{@code assembly_fee=0}</b>（D-5）·
     * {@code currency='CNY'} · {@code pricing_unit} / {@code defect_rate} 取 {@code process_master} 对应值 ·
     * {@code version_no=1} · {@code source='MANUAL'}；② {@code capacity} 表<b>零新增</b>；
     * ③ <b>{@code assembly_fee=0} 且 {@code source='MANUAL'} 可同时查到</b>（D-5 的占位判据）」。
     */
    @Test
    @DisplayName("TC-A2 · AC-2 组合工艺落 ds_quote_assembly_fee（assembly_fee=0 / source=MANUAL）+ capacity 零新增")
    void tcA2_assemblyFeeGoesToNewTableWithZeroFee() {
        assertEnvSanity();
        String asmProc = anAssemblyProcessNo();
        Fx fx = newFixture("a2");

        assertEquals(0L, count("SELECT count(*) FROM ds_quote_assembly_fee WHERE customer_no='"
                        + fx.customerNo() + "'"),
                "TC-A2 前置：新建客户名下 ds_quote_assembly_fee 应为 0 行（否则「新增 1 行」判据失效）");

        Response res = configure(fx, bodyWithCompositeProcess(PREFIX + "A2-" + RUN_ID, asmProc));
        assertSubmitOk(res, "TC-A2 提交（组合工序=" + asmProc + "）");

        String parent = rootPartNo(fx);
        System.out.println("[TC-A2] 父料号=" + parent + " 报价行结构=" + fmt(lineItemsOf(fx)));

        // ── ① 新表 1 行，逐列 ──
        List<Object[]> asm = assemblyRows(fx);
        System.out.println("[TC-A2①] ds_quote_assembly_fee(customer_no=" + fx.customerNo() + ") = " + fmt(asm));
        assertEquals(1, asm.size(),
                "AC-2①：本片客户名下 ds_quote_assembly_fee 应恰好 1 行，实际 " + asm.size() + " 行：" + fmt(asm));
        Object[] r = asm.get(0);
        String expUnit = expectedPricingUnit(asmProc);
        String expDefect = scalar("SELECT default_defect_rate::text FROM process_master WHERE process_no='"
                + asmProc + "'");
        assertEquals(parent, String.valueOf(r[0]),
                "AC-2① material_no 应为父料号 " + parent + "。整行=" + Arrays.toString(r));
        assertEquals(asmProc, String.valueOf(r[1]),
                "AC-2① assembly_operation 应为组合工序 " + asmProc + "。整行=" + Arrays.toString(r));
        assertEquals(0, new java.math.BigDecimal(String.valueOf(r[2])).compareTo(java.math.BigDecimal.ZERO),
                "AC-2① assembly_fee 应为 0（D-5：选配不采集单价，写 0 占位），实际=" + r[2]);
        assertEquals("1", String.valueOf(r[3]), "AC-2① item_seq 应为 1。整行=" + Arrays.toString(r));
        assertEquals("CNY", String.valueOf(r[4]), "AC-2① currency 应为 CNY。整行=" + Arrays.toString(r));
        assertEquals(expUnit, String.valueOf(r[5]),
                "AC-2① pricing_unit 应取 process_master['" + asmProc + "'] 的单位 = " + expUnit
                        + "。整行=" + Arrays.toString(r));
        if (expDefect == null) {
            assertNull(r[6], "AC-2① process_master 的 default_defect_rate 为 NULL ⇒ defect_rate 也应为 NULL。整行="
                    + Arrays.toString(r));
        } else {
            assertNotNull(r[6], "AC-2① defect_rate 应取 process_master 的 default_defect_rate=" + expDefect
                    + "，实际 NULL。整行=" + Arrays.toString(r));
            assertEquals(0, new java.math.BigDecimal(expDefect).compareTo(new java.math.BigDecimal(String.valueOf(r[6]))),
                    "AC-2① defect_rate 应 = process_master 的 " + expDefect + "，实际=" + r[6]);
        }
        assertEquals("1", String.valueOf(r[7]), "AC-2① version_no 应为 1。整行=" + Arrays.toString(r));
        assertEquals("MANUAL", String.valueOf(r[8]), "AC-2① source 应为 MANUAL。整行=" + Arrays.toString(r));

        // ── ③ 「assembly_fee=0 且 source='MANUAL'」可同时查到（D-5 占位判据）──
        long placeholder = count("SELECT count(*) FROM ds_quote_assembly_fee WHERE customer_no='"
                + fx.customerNo() + "' AND assembly_fee=0 AND source='MANUAL'");
        System.out.println("[TC-A2③] assembly_fee=0 AND source='MANUAL' 命中 " + placeholder + " 行");
        assertEquals(1L, placeholder,
                "AC-2③：「选配占位、待补价」的判据（assembly_fee=0 且 source='MANUAL'）应能同时查到 1 行，实际 "
                        + placeholder);

        // ── ② capacity 零新增（capacity 无 customer_no ⇒ 按本轮铸出的料号集合收窄）──
        assertCapacityProbeCanSeeRows();
        List<String> mine = allMyPartNos(fx);
        assertTrue(mine.contains(parent), "TC-A2② 前置：本轮料号集合应含父料号，实际=" + mine);
        long cap = count("SELECT count(*) FROM capacity WHERE material_no IN ("
                + mine.stream().map(s -> "'" + s + "'").collect(Collectors.joining(",")) + ")");
        long capAsm = count("SELECT count(*) FROM capacity WHERE resource_group_no='QUOTE_ASSEMBLY' "
                + "AND material_no IN (" + mine.stream().map(s -> "'" + s + "'").collect(Collectors.joining(",")) + ")");
        System.out.println("[TC-A2②] capacity 命中本轮料号 " + mine + " ⇒ " + cap + " 行（其中 QUOTE_ASSEMBLY "
                + capAsm + " 行）。阳性对照：新表已有 1 行 ⇒ 组合工艺写入确实执行过");
        assertEquals(0L, cap, "AC-2②：capacity 表应零新增，实际本轮料号命中 " + cap + " 行");
    }

    /** TC-A2 的完整请求体（parts + compositeProcesses）。 */
    private java.util.Map<String, Object> bodyWithCompositeProcess(String productNo, String asmProc) {
        java.util.Map<String, Object> body = submitBody("COMPOSITE", productNo,
                newPart(PREFIX + "A2C1", "234", "234", "11",
                        List.of(material(RECIPE_AGCU90, CONFIG_AGCU90, "100")), List.of(PROC_Z008)),
                newPart(PREFIX + "A2C2", "235", "235", "12",
                        List.of(material(RECIPE_AGCU90, CONFIG_AGCU90, "100")), List.of(PROC_Z100)));
        java.util.Map<String, Object> mutable = new java.util.LinkedHashMap<>(body);
        mutable.put("compositeProcesses", List.of(compositeProcess(asmProc, List.of(0, 1))));
        return mutable;
    }

    /** 本轮在本片客户下铸出/关联的全部料号（capacity 这类无 customer_no 的表靠它收窄）。 */
    private List<String> allMyPartNos(Fx fx) {
        List<Object> l = col("SELECT DISTINCT material_no FROM ds_quote_material WHERE customer_no='"
                + fx.customerNo() + "' "
                + "UNION SELECT DISTINCT product_part_no_snapshot FROM quotation_line_item "
                + "WHERE quotation_id='" + fx.quotationId() + "' AND product_part_no_snapshot IS NOT NULL");
        List<String> out = l.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
        assertTrue(!out.isEmpty(), "本轮料号集合为空 ⇒ 按料号收窄的断言会空跑");
        return out;
    }

    // ══════════════════════════ AC-3 ══════════════════════════

    /**
     * <b>AC-3（单点 · 外购件工序改走组装加工费 + 费用类别变更）</b> 原文：
     * 「前置：选配添加外购件（AC 原文举 {@code S0011}），工序选 {@code Z100}。断言：
     * ① {@code ds_quote_assembly_fee} 出现 {@code material_no=<该外购件>} ·
     * {@code assembly_operation='Z100'} · {@code assembly_fee=0} 的行；
     * ② {@code ds_quote_self_process_fee} <b>不出现</b> 该外购件的新行（费用类别已从自制变组装，D-6）；
     * ③ {@code unit_price} 零新增」。
     *
     * <p>🧩 <b>料号替换已登记</b>：AC 原文的 {@code S0011} 挂在共享客户 {@code CUST-0001}/{@code CUST-0004} 下，
     * 本片按分片纪律用自造的 {@link #OUT1}（同形态：{@code ds_quote_material} 一行，
     * {@code material_type='外购件'}，挂在本片自建客户下）。判据逐字不变。
     */
    @Test
    @DisplayName("TC-A3 · AC-3 外购件工序进 ds_quote_assembly_fee、不进 _self_process_fee（费用类别 D-6）")
    void tcA3_outsourcedProcessGoesToAssemblyFeeNotSelfProcessFee() {
        assertEnvSanity();
        Fx fx = newFixture("a3");
        String out = createOutsourcedPart(fx);

        Response res = configure(fx, submitBody("SIMPLE", PREFIX + "A3-" + RUN_ID,
                outsourcedPart(out, List.of(PROC_Z100))));
        assertSubmitOk(res, "TC-A3 提交（外购件 " + out + " 工序 " + PROC_Z100 + "）");
        System.out.println("[TC-A3] 报价行结构=" + fmt(lineItemsOf(fx)));

        // ── ① 外购件的工序落组装加工费 ──
        List<Object[]> asm = assemblyRows(fx);
        System.out.println("[TC-A3①] ds_quote_assembly_fee(customer_no=" + fx.customerNo() + ") = " + fmt(asm));
        assertTrue(!asm.isEmpty(),
                "AC-3①：外购件的工序应落 ds_quote_assembly_fee，实际本片客户名下 0 行"
                        + "（0 行一律不算通过 —— 也说明后面的「不进自制表」是空验证）");
        long hit = count("SELECT count(*) FROM ds_quote_assembly_fee WHERE customer_no='" + fx.customerNo()
                + "' AND material_no='" + out + "' AND assembly_operation='" + PROC_Z100
                + "' AND assembly_fee=0");
        assertEquals(1L, hit,
                "AC-3①：应出现 material_no='" + out + "' · assembly_operation='" + PROC_Z100
                        + "' · assembly_fee=0 的行，实际命中 " + hit + " 行。全部行=" + fmt(asm));

        // ── ② 自制加工费表不出现该外购件（费用类别变更）──
        List<Object[]> spf = selfProcessRows(fx);
        System.out.println("[TC-A3②] ds_quote_self_process_fee(customer_no=" + fx.customerNo() + ") = " + fmt(spf));
        long spfByMaterial = count("SELECT count(*) FROM ds_quote_self_process_fee WHERE customer_no='"
                + fx.customerNo() + "' AND material_no='" + out + "'");
        assertEquals(0L, spfByMaterial,
                "AC-3②：ds_quote_self_process_fee 不应出现 material_no='" + out + "' 的行（D-6 费用类别从自制变组装），"
                        + "实际 " + spfByMaterial + " 行：" + fmt(spf));
        // 🔑 补强（本次提交只含这一道外购件工序 ⇒ 自制表整体必须空；比 AC 字面判据更不易被绕过）
        assertEquals(0, spf.size(),
                "AC-3② 补强：本次提交只有外购件的一道工序 ⇒ 本片客户名下 ds_quote_self_process_fee 应整体为 0 行，"
                        + "实际 " + spf.size() + " 行：" + fmt(spf)
                        + "（若这里非空而 material_no 又不是 " + out + "，说明工序被挂到了别的轴上，请看日志原值）");

        // ── ③ unit_price 零新增 ──
        assertUnitPriceProbeCanSeeRows();
        long upByOut = count("SELECT count(*) FROM unit_price WHERE code='" + out
                + "' OR finished_material_no='" + out + "'");
        long upAll = count("SELECT count(*) FROM unit_price WHERE customer_no='" + fx.customerNo() + "'");
        System.out.println("[TC-A3③] unit_price 本片客户命中 " + upAll + " 行"
                + "，按外购件料号命中 " + upByOut
                + " 行（阳性对照：组装加工费表已有 " + asm.size() + " 行 ⇒ 写入路径确实执行过）");
        assertEquals(0L, upAll, "AC-3③：unit_price 应零新增，实际本片客户名下 " + upAll + " 行");
        assertEquals(0L, upByOut,
                "AC-3③：unit_price 按外购件料号（code / finished_material_no）也应 0 行，实际 " + upByOut);
    }

    // ══════════════════════════ AC-4 ══════════════════════════

    /**
     * <b>AC-4（边界 · COMPOSITE 的分组键映射）</b> 原文：
     * 「前置：COMPOSITE 产品，父料号 P、子件料号 C1/C2，C1 有工序 {@code Z008}、C2 有工序 {@code Z100}。
     * 断言：{@code ds_quote_self_process_fee} 出现 <b>2 行</b>，均为 {@code material_no=P}（父），
     * {@code input_material_no} 分别是 {@code C1} / {@code C2}，{@code operation_no} 分别
     * {@code Z008} / {@code Z100}（D-4：轴=父料号，投入料号=子件料号）」。
     */
    @Test
    @DisplayName("TC-A4 · AC-4 COMPOSITE（D-15 新口径）：父轴恰好 2 行 + 子轴各 1 行 = 合计 4 行")
    void tcA4_compositeMapsParentAxisAndChildInputMaterial() {
        assertEnvSanity();
        Fx fx = newFixture("a4");

        String n1 = PREFIX + "A4C1";
        String n2 = PREFIX + "A4C2";
        // 🚨 两个子件必须**不同配置**（总重 11 / 12），否则指纹复用会把它们收敛成同一个料号，
        //    「2 行、两个不同 input_material_no」这条断言就会退化成空验证。
        Response res = configure(fx, submitBody("COMPOSITE", PREFIX + "A4-" + RUN_ID,
                newPart(n1, "234", "234", "11",
                        List.of(material(RECIPE_AGCU90, CONFIG_AGCU90, "100")), List.of(PROC_Z008)),
                newPart(n2, "235", "235", "12",
                        List.of(material(RECIPE_AGCU90, CONFIG_AGCU90, "100")), List.of(PROC_Z100))));
        assertSubmitOk(res, "TC-A4 提交");

        String parent = rootPartNo(fx);
        List<String> children = childPartNos(fx);
        System.out.println("[TC-A4] 父料号=" + parent + " 子件=" + children
                + " 报价行结构=" + fmt(lineItemsOf(fx)));
        assertEquals(2, children.size(),
                "AC-4 前置：应铸出 2 个子件料号（C1/C2），实际=" + children
                        + " ⇒ 少于 2 个时「2 行、两个 input_material_no」无从验起");

        String c1 = castPartNoByName(fx, n1);
        String c2 = castPartNoByName(fx, n2);
        System.out.println("[TC-A4] 名称映射：" + n1 + " → " + c1 + " ; " + n2 + " → " + c2);
        assertNotNull(c1, "AC-4 前置：按零件名 " + n1 + " 反查不到铸出料号 ⇒ 工序与子件的对应关系无从验证");
        assertNotNull(c2, "AC-4 前置：按零件名 " + n2 + " 反查不到铸出料号");
        assertTrue(!c1.equals(c2),
                "AC-4 前置：两个子件必须是不同料号（否则指纹复用已把它们并成一个，断言退化）。c1=c1=" + c1);

        List<Object[]> spf = selfProcessRows(fx);
        System.out.println("[TC-A4] ds_quote_self_process_fee(customer_no=" + fx.customerNo() + ") = " + fmt(spf));
        assertTrue(!spf.isEmpty(),
                "AC-4：ds_quote_self_process_fee 在本片客户下 0 行 ⇒ 后面全部断言会空跑（0 行一律不算通过）");

        // ── AC-4 的实质断言：轴 = 父料号，投入料号 = 各子件料号（D-4）──
        List<Object[]> parentAxis = spf.stream().filter(r -> parent.equals(String.valueOf(r[0]))).toList();
        System.out.println("[TC-A4] 父轴行（material_no=" + parent + "）= " + fmt(parentAxis));
        assertEquals(2, parentAxis.size(),
                "AC-4：父料号 " + parent + " 为轴的行应恰好 2 行（C1 的 Z008 + C2 的 Z100），实际 "
                        + parentAxis.size() + " 行。全部行=" + fmt(spf));

        Set<String> inputs = parentAxis.stream().map(r -> String.valueOf(r[1])).collect(Collectors.toSet());
        assertEquals(Set.of(c1, c2), inputs,
                "AC-4：父轴两行的 input_material_no 应分别是子件料号 " + c1 + " / " + c2 + "，实际=" + inputs
                        + "。全部行=" + fmt(spf));

        String opOfC1 = parentAxis.stream().filter(r -> c1.equals(String.valueOf(r[1])))
                .map(r -> String.valueOf(r[2])).findFirst().orElse(null);
        String opOfC2 = parentAxis.stream().filter(r -> c2.equals(String.valueOf(r[1])))
                .map(r -> String.valueOf(r[2])).findFirst().orElse(null);
        assertEquals(PROC_Z008, opOfC1,
                "AC-4：子件 " + c1 + "（" + n1 + "，配的是 Z008）对应的 operation_no 应为 Z008，实际=" + opOfC1);
        assertEquals(PROC_Z100, opOfC2,
                "AC-4：子件 " + c2 + "（" + n2 + "，配的是 Z100）对应的 operation_no 应为 Z100，实际=" + opOfC2);

        // ── AC-4② 子轴各 1 行（🔴 D-15 新增的硬断言）──
        // D-15 原文：「子件自身也是可独立报价的料号 ⇒ 选配同时为它写一组」
        //   ⇒ [C1, C1, Z008] 与 [C2, C2, Z100] 各恰好 1 行（自指，与 AC-1 的 SIMPLE 形态同构）。
        // 🚫 按 AC-4③ 的纪律，断言按轴分别收窄，不写「整表 N 行」。
        for (Object[] pair : new Object[][] {{c1, PROC_Z008, n1}, {c2, PROC_Z100, n2}}) {
            String child = (String) pair[0];
            String proc = (String) pair[1];
            String name = (String) pair[2];
            List<Object[]> selfAxis = spf.stream()
                    .filter(r -> child.equals(String.valueOf(r[0])))
                    .toList();
            System.out.println("[TC-A4] 子轴行（material_no=" + child + " / " + name + "）= " + fmt(selfAxis));
            assertEquals(1, selfAxis.size(),
                    "AC-4②（D-15）：子件 " + child + "（" + name + "）自身为轴的行应恰好 1 行，实际 "
                            + selfAxis.size() + " 行。全部行=" + fmt(spf));
            Object[] r = selfAxis.get(0);
            assertEquals(child, String.valueOf(r[1]),
                    "AC-4②（D-15）：子轴行的 input_material_no 应自指（=" + child + "），实际=" + r[1]);
            assertEquals(proc, String.valueOf(r[2]),
                    "AC-4②（D-15）：子件 " + child + "（" + name + "，配的是 " + proc
                            + "）自轴行的 operation_no 应为 " + proc + "，实际=" + r[2]);
        }

        // ── AC-4③ 合计 4 行（本片客户维度收窄，🚫 不是整表全局计数）──
        // 父轴 2 行（[P,C1,Z008] / [P,C2,Z100]）+ 子轴 2 行（[C1,C1,Z008] / [C2,C2,Z100]）。
        // 📌 非本次引入：老表 unit_price 的存量选配数据也是双轴（cpq_db_0724 实查 CUST-0001）：
        //   finished_material_no=0526-2609000004 code=0526-2609000004（子轴）
        //   finished_material_no=0526-2609000005 code=0526-2609000004（父轴）  ⇒ 等价搬运。
        List<Object[]> childAxis = spf.stream().filter(r -> !parent.equals(String.valueOf(r[0]))).toList();
        System.out.println("[TC-A4] 轴分布：父轴 " + parentAxis.size() + " 行 / 子轴 " + childAxis.size()
                + " 行 / 本片客户合计 " + spf.size() + " 行");
        assertEquals(4, spf.size(),
                "AC-4③（D-15）：本片客户 " + fx.customerNo() + " 下 ds_quote_self_process_fee 应合计 4 行"
                        + "（父轴 2 + 子轴 2），实际 " + spf.size() + " 行 = " + fmt(spf));

        // 老表零新增（同一提交里的阳性对照：新表已有 2 行）
        long up = count("SELECT count(*) FROM unit_price WHERE customer_no='" + fx.customerNo() + "'");
        System.out.println("[TC-A4] unit_price 本片客户命中 " + up + " 行（阳性对照：新表已有 " + spf.size() + " 行）");
        assertEquals(0L, up, "AC-4 附带：unit_price 应零新增，实际 " + up + " 行");
    }
}
