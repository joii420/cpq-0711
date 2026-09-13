package com.cpq.component.service;

import com.cpq.component.dto.ExpandDriverResponse;
import com.cpq.semanticgraph.service.RowScopeSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260911 · B-6 / B-7 的常驻回归（AC-3 / AC-5 / AC-6 / AC-7）。
 *
 * <h3>为什么必须有这个类</h3>
 * 本任务把「按本行客编收窄」从 SQL 层挪到了分发层。挪走之后，SQL 层<b>顺带丢掉了一个没人显式写过的
 * 保底行为</b>：标量谓词 {@code = :customerProductNo} 绑 NULL 时恒 UNKNOWN ⇒ 该料号所有 {@code dqcp}
 * 行都不匹配 ⇒ LEFT JOIN <b>天然产生一行「未匹配」记录</b>。换成集合谓词后，只要该料号还有别的客编
 * 在集合里就会匹配上 ⇒ 那一行<b>根本不产生</b>。S2 在 SQL 层实测：桶内属于空客编明细行的行数 = <b>0</b>，
 * 影响 <b>128/3970</b> 明细行。
 *
 * <p>🚨 关键在于 <b>它是被静默拿掉的</b> —— 不报错、不告警，只表现为那类卡片整页签 0 行。
 * B-6 的兜底就是把这一行补回来；<b>一个会静默失效的兜底，必须有机械信号证明它还活着</b>，
 * 否则只是把静默失效推迟了一层。这个类就是那个信号。
 *
 * <h3>🚨 共享库红线（CLAUDE.md §3.2）</h3>
 * 本类<b>刻意不是 {@code @QuarkusTest}</b>：驱动的是 {@link RowScopeProjector#projectRows} 这个
 * <b>纯函数内核</b>，输入全是内存对象 ⇒ <b>不启 Quarkus、不连库、不写任何一行数据</b>。
 * 沿用 {@code SemanticCompilerAxisNarrowGuardTest} 立的先例。
 */
class RowScopeProjectionTest {

    // ── 取材自真实编译产物 builder_221dc7668ab6（2026-09-11 实查，勿改写措辞）──
    private static final String CP_COL = "_客户料号_客户产品编号";   // 作用域列（CUSTOMER_PART.customer_product_no）
    private static final String CP_NAME_COL = "_客户料号_客户零件名称"; // 同源于 dqcp 的另一列
    private static final String MAT_COL = "_物料_品名";              // 物料侧列（同一个 dqm 行，各桶内行完全相同）
    /**
     * 🚨 <b>实查形态：带花括号</b>（2026-09-11 S3 缺陷的根因就在这一行）。
     * {@code ExpandDriverResponse.Row#basicDataValues} 的契约是「key = 字段原始路径<b>(含花括号)</b>」，
     * 读取侧 {@code FormulaCalculator#bnfDriverLookupKey} 也统一补花括号。
     * 本夹具上一轮写成<b>不带</b>花括号 ⇒ 单测恒绿、生产恒不置空（{@code endsWith("." + 列名)} 对
     * 以 {@code '}'} 收尾的真实键永远为 false）。实库取证：
     * {@code quotation_line_component_data.snapshot_rows} 的 basicDataValues 键确为
     * {@code "{$builder_221dc7668ab6._客户料号_客户产品编号}"}。
     */
    private static final String BD_PATH = "{$builder_221dc7668ab6." + CP_COL + "}";
    /** 同源于 dqcp 的另一列，同样带花括号。 */
    private static final String BD_NAME_PATH = "{$builder_221dc7668ab6." + CP_NAME_COL + "}";
    /** 历史/兼容形态：不带花括号也必须能被置空（两种都认，见 {@code RowScopeProjector#bdvKeyTargets}）。 */
    private static final String BD_PATH_NO_BRACE = "$builder_221dc7668ab6." + CP_COL;

    private static final RowScopeProjector.CompScope SCOPE = new RowScopeProjector.CompScope(
            true,
            Map.of(CP_COL, "customer_product_no"),
            new java.util.LinkedHashSet<>(List.of(CP_COL, CP_NAME_COL)));

    private static ExpandDriverResponse.Row row(String cp, String cpName) {
        ExpandDriverResponse.Row r = new ExpandDriverResponse.Row();
        Map<String, Object> dr = new LinkedHashMap<>();
        dr.put(MAT_COL, "接触片");          // 物料侧：桶内各行完全相同
        dr.put(CP_COL, cp);
        dr.put(CP_NAME_COL, cpName);
        r.driverRow = dr;
        Map<String, Object> bd = new LinkedHashMap<>();
        bd.put(BD_PATH, cp);                // 字段 default_source.path 指向该视图列（实查形态：含花括号）
        bd.put(BD_NAME_PATH, cpName);       // 同源于对端表的另一列
        bd.put(BD_PATH_NO_BRACE, cp);       // 兼容形态：不带花括号也要清
        bd.put("{$builder_221dc7668ab6." + MAT_COL + "}", "接触片");   // 物料侧：🚫 不许被清
        r.basicDataValues = bd;
        return r;
    }

    /** 桶 = 整单去重集合查回的超集：同一料号 3 个客编各一行。 */
    private static ExpandDriverResponse bucket() {
        ExpandDriverResponse e = new ExpandDriverResponse();
        e.driverPath = "$builder_221dc7668ab6";
        e.rows = new java.util.ArrayList<>(List.of(
                row("X-CP1", "零件一"), row("X-CP2", "零件二"), row("X-CP3", "零件三")));
        e.rowCount = 3;
        return e;
    }

    private static Map<String, String> want(String cp) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("customer_product_no", cp);
        return m;
    }

    // ───────────────────────── AC-3：每行各挑到自己的那一行 ─────────────────────────

    @Test
    @DisplayName("AC-3 同料号 3 个明细行客编不同 → 各渲染 1 行，且客编一一对应（🚫 不是三行都显示同一个）")
    void ac3_eachLinePicksItsOwnRow() {
        for (String cp : List.of("X-CP1", "X-CP2", "X-CP3")) {
            ExpandDriverResponse out = RowScopeProjector.projectRows(SCOPE, want(cp), bucket());
            assertEquals(1, out.rowCount, "明细行 " + cp + " 应恰好 1 行");
            assertEquals(cp, out.rows.get(0).driverRow.get(CP_COL));
            // 物料侧列不受影响
            assertEquals("接触片", out.rows.get(0).driverRow.get(MAT_COL));
        }
    }

    // ───────────────────────── AC-5：空客编 —— 本任务最容易静默失败的一条 ─────────────────────────

    @Test
    @DisplayName("AC-5 明细行客编为空 → 仍 1 行（🚫 不是 0 行）；客编列空；物料侧列有值")
    void ac5_blankScopeValueStillYieldsOneRow() {
        // 本行客编为空：集合 {CP1,CP2,CP3} 里没有 NULL 项，桶里 3 行没有一行属于它。
        // 标量谓词时代这一行由 LEFT JOIN 未匹配天然产生；集合谓词把它弄没了 ⇒ 必须由 B-6 补回来。
        ExpandDriverResponse out = RowScopeProjector.projectRows(SCOPE, want(null), bucket());

        assertEquals(1, out.rowCount, "🚨 AC-5：必须仍是 1 行，返 0 行就是 S2 实证的那个静默失败面");
        ExpandDriverResponse.Row r = out.rows.get(0);
        assertNull(r.driverRow.get(CP_COL), "客编列必须为空");
        assertNull(r.driverRow.get(CP_NAME_COL),
                "同源于对端表的其余列也必须置空 —— 否则会显示**别的明细行**的客户侧数据，"
                + "那比显示空更糟：它看起来完全正常但归属是错的");
        assertNull(r.basicDataValues.get(BD_PATH),
                "🚨 basicDataValues 同样要清（键**含花括号**，实查形态）—— 2026-09-11 S3 实测："
                + "只清 driverRow 时，resolveRowByFieldName 解 INPUT_* 会退到 default_source→"
                + "basicDataValues，从这里读到 pivot 行（属于别的明细行）的客编并写进 row_data 与输入框");
        assertNull(r.basicDataValues.get(BD_NAME_PATH), "同源于对端表的其余列在 basicDataValues 里也要清");
        assertNull(r.basicDataValues.get(BD_PATH_NO_BRACE), "不带花括号的历史形态同样要清");
        assertEquals("接触片", r.basicDataValues.get("{$builder_221dc7668ab6." + MAT_COL + "}"),
                "🚫 物料侧的 basicDataValues 条目不许被误清（它不同源于对端表）");
        assertEquals("接触片", r.driverRow.get(MAT_COL), "物料侧列必须有值（同一个 dqm 行，桶内各行相同）");
    }

    @Test
    @DisplayName("AC-5 变体：客编非空但对端表查无此编号 → 与「客编为空」同款兜底 1 行")
    void ac5_unknownScopeValueFallsBackToo() {
        ExpandDriverResponse out = RowScopeProjector.projectRows(SCOPE, want("X-NOT-EXIST"), bucket());
        assertEquals(1, out.rowCount);
        assertNull(out.rows.get(0).driverRow.get(CP_COL));
        assertEquals("接触片", out.rows.get(0).driverRow.get(MAT_COL));
    }

    @Test
    @DisplayName("空白字符串与 null 等价（明细行「没填」与视图侧 NULL 是同一件事）")
    void blankStringNormalizesToNull() {
        assertEquals(1, RowScopeProjector.projectRows(SCOPE, want("   "), bucket()).rowCount);
        assertNull(RowScopeSupport.normalize("  "));
        assertNull(RowScopeSupport.normalize(null));
    }

    // ───────────────────────── AC-6：两行客编相同 ─────────────────────────

    @Test
    @DisplayName("AC-6 两个明细行客编相同 → 各 1 行且内容相同（🚫 不出现「一行拿到两条」或「一行拿不到」）")
    void ac6_duplicateScopeValues() {
        ExpandDriverResponse a = RowScopeProjector.projectRows(SCOPE, want("X-CP2"), bucket());
        ExpandDriverResponse b = RowScopeProjector.projectRows(SCOPE, want("X-CP2"), bucket());
        assertEquals(1, a.rowCount);
        assertEquals(1, b.rowCount);
        assertEquals(a.rows.get(0).driverRow.get(CP_COL), b.rows.get(0).driverRow.get(CP_COL));
    }

    // ───────────────────────── AC-7：加法式，未打标记的组件逐字节不变 ─────────────────────────

    @Test
    @DisplayName("AC-7 组件视图没带集合谓词（CompScope 非 active）→ **原样返回入参对象本身**")
    void ac7_inactiveScopeIsIdentity() {
        ExpandDriverResponse in = bucket();
        ExpandDriverResponse out = RowScopeProjector.projectRows(
                RowScopeProjector.CompScope.INACTIVE, want("X-CP1"), in);
        assertSame(in, out, "必须是同一个对象引用 —— 不只是「内容相同」，连新建对象都不许有");
        assertEquals(3, out.rowCount);
    }

    @Test
    @DisplayName("桶内本来就 0 行 → 原样返回，🚫 不凭空造出一行（那是真的没数据，不是「挑不到」）")
    void emptyBucketIsNotFabricated() {
        ExpandDriverResponse empty = new ExpandDriverResponse();
        empty.rows = new java.util.ArrayList<>();
        empty.rowCount = 0;
        ExpandDriverResponse out = RowScopeProjector.projectRows(SCOPE, want(null), empty);
        assertSame(empty, out);
        assertEquals(0, out.rowCount);
    }

    // ───────────────────────── AP-37：桶里的行被多明细行共享，绝不许就地改 ─────────────────────────

    @Test
    @DisplayName("AP-37 兜底构造行必须另建对象 —— 桶里的 pivot 行不得被 mutate")
    void ap37_pivotRowNotMutated() {
        ExpandDriverResponse bkt = bucket();
        ExpandDriverResponse.Row pivot = bkt.rows.get(0);

        ExpandDriverResponse out = RowScopeProjector.projectRows(SCOPE, want(null), bkt);

        assertEquals("X-CP1", pivot.driverRow.get(CP_COL),
                "🚨 桶里的原行必须纹丝不动 —— 它被同料号的其它明细行共享，就地置空会把别人的数据也抹掉");
        assertEquals("X-CP1", pivot.basicDataValues.get(BD_PATH));
        assertEquals("X-CP1", pivot.basicDataValues.get(BD_PATH_NO_BRACE));
        assertNull(out.rows.get(0).driverRow.get(CP_COL));
        assertTrue(out.rows.get(0) != pivot, "兜底行必须是新对象");
    }

    @Test
    @DisplayName("命中分支不改桶、不改入参响应对象")
    void matchBranchDoesNotMutateInput() {
        ExpandDriverResponse bkt = bucket();
        ExpandDriverResponse out = RowScopeProjector.projectRows(SCOPE, want("X-CP2"), bkt);
        assertEquals(3, bkt.rowCount, "入参响应对象必须纹丝不动");
        assertEquals(3, bkt.rows.size());
        assertEquals(1, out.rowCount);
        assertNotNull(out.driverPath);
        assertEquals(bkt.driverPath, out.driverPath);
    }

    // ─────────── S2 实测回归：**直接反序列化真实 batch-expand 响应**，不用手搓结构 ───────────

    /**
     * 🚨 <b>取自 live {@code POST /api/cpq/components/batch-expand} 的原始响应</b>
     * （证据：{@code 证据/S2/原始输出/01-batch-expand原始响应-MAIN_EMPTY_NEG.json}，
     * 夹具 {@code QT-20260911-S2FRESH}，料号 {@code T0911S2-M1}）——
     * 键名、花括号、值类型、列顺序全部原样照抄，<b>一个字都没简化</b>。
     *
     * <p><b>为什么必须用真实结构</b>：上一轮的手搓夹具把 {@code basicDataValues} 的键写成了
     * <b>不带花括号</b>的 {@code "$builder_xxx._客户料号_客户产品编号"}，而真实形态是
     * <b>带花括号</b>的 {@code "{$builder_xxx._客户料号_客户产品编号}"}。置空逻辑用的是
     * {@code endsWith("." + 视图列名)} ⇒ 对真实键（以 {@code '}'} 收尾）<b>恒 false</b>。
     * 结果：<b>单测绿、运行时红</b> —— 手搓夹具把唯一能抓住它的那个信号也一并抹掉了。
     */
    private static final String LIVE_BUCKET_JSON = """
            {
              "rowCount": 3,
              "driverPath": "$builder_221dc7668ab6",
              "rows": [
                {
                  "driverRow": {"_客户料号_客户产品编号":"T0911S2-CP1","_物料_销售料号":"T0911S2-M1","_物料_规格":"SPEC-S2-A","_物料_尺寸":"20x10x5","hf_part_no":"T0911S2-M1","_物料_品名":"S2测试件甲","_物料_旧料号":"OLD-S2-A","_物料_单重":"7.777777777777","_物料_生产料号":"PROD-S2-A"},
                  "basicDataValues": {"{$builder_221dc7668ab6._物料_销售料号}":"T0911S2-M1","{$builder_221dc7668ab6._客户料号_客户产品编号}":"T0911S2-CP1","{$builder_221dc7668ab6._物料_生产料号}":"PROD-S2-A","{$builder_221dc7668ab6._物料_品名}":"S2测试件甲","{$builder_221dc7668ab6._物料_规格}":"SPEC-S2-A","{$builder_221dc7668ab6._物料_尺寸}":"20x10x5","{$builder_221dc7668ab6._物料_旧料号}":"OLD-S2-A","{$builder_221dc7668ab6._物料_单重}":"7.777777777777"}
                },
                {
                  "driverRow": {"_客户料号_客户产品编号":"T0911S2-CP2","_物料_销售料号":"T0911S2-M1","_物料_规格":"SPEC-S2-A","_物料_尺寸":"20x10x5","hf_part_no":"T0911S2-M1","_物料_品名":"S2测试件甲","_物料_旧料号":"OLD-S2-A","_物料_单重":"7.777777777777","_物料_生产料号":"PROD-S2-A"},
                  "basicDataValues": {"{$builder_221dc7668ab6._物料_销售料号}":"T0911S2-M1","{$builder_221dc7668ab6._客户料号_客户产品编号}":"T0911S2-CP2","{$builder_221dc7668ab6._物料_生产料号}":"PROD-S2-A","{$builder_221dc7668ab6._物料_品名}":"S2测试件甲","{$builder_221dc7668ab6._物料_规格}":"SPEC-S2-A","{$builder_221dc7668ab6._物料_尺寸}":"20x10x5","{$builder_221dc7668ab6._物料_旧料号}":"OLD-S2-A","{$builder_221dc7668ab6._物料_单重}":"7.777777777777"}
                },
                {
                  "driverRow": {"_客户料号_客户产品编号":"T0911S2-CP3","_物料_销售料号":"T0911S2-M1","_物料_规格":"SPEC-S2-A","_物料_尺寸":"20x10x5","hf_part_no":"T0911S2-M1","_物料_品名":"S2测试件甲","_物料_旧料号":"OLD-S2-A","_物料_单重":"7.777777777777","_物料_生产料号":"PROD-S2-A"},
                  "basicDataValues": {"{$builder_221dc7668ab6._物料_销售料号}":"T0911S2-M1","{$builder_221dc7668ab6._客户料号_客户产品编号}":"T0911S2-CP3","{$builder_221dc7668ab6._物料_生产料号}":"PROD-S2-A","{$builder_221dc7668ab6._物料_品名}":"S2测试件甲","{$builder_221dc7668ab6._物料_规格}":"SPEC-S2-A","{$builder_221dc7668ab6._物料_尺寸}":"20x10x5","{$builder_221dc7668ab6._物料_旧料号}":"OLD-S2-A","{$builder_221dc7668ab6._物料_单重}":"7.777777777777"}
                }
              ]
            }
            """;

    private static final String LIVE_CP_KEY = "{$builder_221dc7668ab6._客户料号_客户产品编号}";
    private static final String LIVE_MAT_KEY = "{$builder_221dc7668ab6._物料_品名}";

    private static ExpandDriverResponse liveBucket() throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(LIVE_BUCKET_JSON, ExpandDriverResponse.class);
    }

    @Test
    @DisplayName("🚨 S2 回归：真实 batch-expand 结构 + 空客编 → driverRow 与 basicDataValues **两处同源键都置空**")
    void s2Regression_liveShape_bothMapsBlanked() throws Exception {
        ExpandDriverResponse out = RowScopeProjector.projectRows(SCOPE, want(null), liveBucket());

        assertEquals(1, out.rowCount, "AC-5：仍 1 行");
        ExpandDriverResponse.Row r = out.rows.get(0);
        assertNull(r.driverRow.get(CP_COL), "driverRow 侧（上一轮已生效）");
        assertNull(r.basicDataValues.get(LIVE_CP_KEY),
                "🚨 basicDataValues 侧：S2 在 live batch-expand 响应里实测到 T0911S2-CP1 泄漏，"
                + "这是**不经持久化**就已存在的泄漏，row_data / 卡片标题 / 浏览器显示全是它的下游");
        // 物料侧两条都必须留着（它们同源于 dqm，桶内各行完全相同，置空会把好数据一起抹掉）
        assertEquals("S2测试件甲", r.driverRow.get("_物料_品名"));
        assertEquals("S2测试件甲", r.basicDataValues.get(LIVE_MAT_KEY));
        assertEquals("T0911S2-M1", r.driverRow.get("hf_part_no"));
    }

    @Test
    @DisplayName("S2 回归：真实结构下命中分支仍各取各的（🚫 修兜底不许把正常行也带歪）")
    void s2Regression_liveShape_matchBranchIntact() throws Exception {
        ExpandDriverResponse out = RowScopeProjector.projectRows(SCOPE, want("T0911S2-CP2"), liveBucket());
        assertEquals(1, out.rowCount);
        assertEquals("T0911S2-CP2", out.rows.get(0).driverRow.get(CP_COL));
        assertEquals("T0911S2-CP2", out.rows.get(0).basicDataValues.get(LIVE_CP_KEY),
                "命中分支返回桶内原行，basicDataValues 不动");
    }

    // ───────────────── basicDataValues 键形态契约（2026-09-11 S3 缺陷的直接回归） ─────────────────

    @Test
    @DisplayName("🚨 bdvKeyTargets：带花括号的实查键必须匹配 —— 上一轮就是这里恒 false 导致生产串号")
    void bdvKeyTargets_bracedRealWorldForm() {
        assertTrue(RowScopeProjector.bdvKeyTargets("{$builder_221dc7668ab6." + CP_COL + "}", CP_COL),
                "实查形态（含花括号）必须匹配");
        assertTrue(RowScopeProjector.bdvKeyTargets("$builder_221dc7668ab6." + CP_COL, CP_COL),
                "不带花括号的形态也必须匹配");
        assertTrue(RowScopeProjector.bdvKeyTargets("  {$builder_221dc7668ab6." + CP_COL + "}  ", CP_COL),
                "两端空白不影响判定（bnfDriverLookupKey 同样 trim）");
    }

    @Test
    @DisplayName("bdvKeyTargets：🚫 不许误伤 —— 别的列、后缀撞名、@gvar、null 一律不匹配")
    void bdvKeyTargets_doesNotOverBlank() {
        assertTrue(!RowScopeProjector.bdvKeyTargets("{$builder_221dc7668ab6." + MAT_COL + "}", CP_COL),
                "物料侧列不得被清");
        assertTrue(!RowScopeProjector.bdvKeyTargets("{$builder_221dc7668ab6._X" + CP_COL + "}", CP_COL),
                "按「.<列名>」结尾匹配，🚫 不是 contains —— 后缀撞名不许误清");
        assertTrue(!RowScopeProjector.bdvKeyTargets("@gvar:SOME_CODE", CP_COL));
        assertTrue(!RowScopeProjector.bdvKeyTargets(null, CP_COL));
        assertTrue(!RowScopeProjector.bdvKeyTargets("{$builder_x." + CP_COL + "}", null));
    }

    // ───────────────────────── 占位符命名契约（api.md §3：单数 → 复数） ─────────────────────────

    @Test
    @DisplayName("api.md §3：集合占位符是复数 :customerProductNos，与 repair-260910 的单数刻意不同名")
    void setParamNameIsPluralAndDistinct() {
        assertEquals("customerProductNos", RowScopeSupport.setParamName("customer_product_no"));
        // 不同名是刻意的：存量未重编译的 sql_template 带的是单数 :customerProductNo，
        // 不会被本机制误绑，两套口径不会半新半旧混在一起。
        assertTrue(!"customerProductNo".equals(RowScopeSupport.setParamName("customer_product_no")));
    }

    @Test
    @DisplayName("语义图列名 → 明细行列名：约定同名，只有 customer_product_no 是例外")
    void lineItemColumnMapping() {
        assertEquals("customer_part_no", RowScopeSupport.lineItemColumnFor("customer_product_no"));
        assertEquals("some_other_col", RowScopeSupport.lineItemColumnFor("some_other_col"));
    }
}
