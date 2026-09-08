package com.cpq.task260907r;

import com.cpq.task260902.SelConfigAcTestBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>T-17（AC-17）—— 选配链路不被波及。</b>
 *
 * <h3>为什么单独一个类（而不是塞进 {@link ReverseRegressionAcTest}）</h3>
 * 选配链路的夹具（产品分类 / 材质配方 / 客户绑分类 / 铸料号 / 还原）在
 * {@code com.cpq.task260902.SelConfigAcTestBase}（736 行）里<b>已经有一份成熟实现</b>，
 * 连 {@code @AfterEach} 精确还原与 {@code assertNoResidue} 都齐了。
 * Java 单继承 ⇒ 要复用它就不能同时继承 {@code Task260907RBase}。
 * 🚫 <b>刻意不重写一份</b>：同一件事两套夹具实现必然漂移（本项目在
 * {@code VersionedGroupWriter} / {@code PricingSheetRegistry} / {@code D-31} 上治过三次同一个病）。
 *
 * <h3>🅱️ A/B 的两侧</h3>
 * B 侧（改动前）= {@code localhost:8081}（master 代码、同一个库）；
 * A 侧（改动后）= 本 {@code @QuarkusTest} 进程。两侧在同一条用例里背靠背跑，
 * 判别与纪律见 {@link MasterSideHttp}。
 *
 * <h3>⚠️ 本类会写共享库，还原分两段</h3>
 * 超类的 {@code @AfterEach} 按 {@code customer_no} + 本轮铸出的料号精确还原，
 * 但它<b>不认识</b>本段新增的 {@code _record}，也不清 {@code _history}（选配链路原来不升版）。
 * ⇒ {@link #cleanupRecordAndHistory} 补上这两类，且<b>先于</b>超类执行
 * （JUnit 5：子类 {@code @AfterEach} 早于父类）。
 */
@QuarkusTest
@DisplayName("T-17 · 选配链路反向回归（AC-17）")
class SelectionChainRegressionAcTest extends SelConfigAcTestBase {

    private final MasterSideHttp master = new MasterSideHttp();

    /** ds 原生「物料与元素BOM」组件与它的 builder 视图 —— 自造组件照抄它的 builder_config。 */
    private static final java.util.UUID SRC_COMPONENT =
            java.util.UUID.fromString("196aadee-b89f-4f81-984b-4c6747b59149");
    private static final String SRC_VIEW = "builder_196aadeeb89f";

    private java.util.UUID builderComponentId;
    private final List<java.util.UUID> builderTemplateIds = new ArrayList<>();
    private String builderViewName;

    /** 本轮铸出的销售料号（两侧各一批），@AfterEach 按它精确清 {@code _record} / {@code _history}。 */
    private final List<String> mintedPartNos = new ArrayList<>();
    private final List<String> touchedQuotationIds = new ArrayList<>();

    /**
     * <b>AC-17</b>：
     * ① {@code ConfigureProductService} / {@code SelDsQuoteWriter} 写入 {@code ds_quote_*} 的结果，
     *    <b>在本次夹具自己写入的行范围内</b>逐行相同（🚫 不许整表 md5）；
     * ② 报价单渲染出的<b>页签数与每页签行数</b>改动前后相同（🚫 不写「渲染正常」，写具体计数）；
     * ③ 核价通过后该单状态 = {@code APPROVED}，且其涉及的料号组<b>版本号增量</b>与改动前相同。
     *
     * <h3>🚨 「逐行相同」怎么才不是空验证</h3>
     * 两侧是<b>不同客户、不同铸号</b>的两张单 ⇒ 行里必然有一批值天然不同
     * （{@code id} / {@code material_no} / {@code customer_no} / 时间戳 / 指纹）。
     * ⇒ 比对前按<b>可归因</b>的规则归一化这些列（AC-16② 的「数据层差异逐条可归因」同款），
     * 归一化之后仍不同的，就是真回归。
     * <p>🔑 并且必须先 {@link #assertNonEmpty}：两侧都 0 行时「逐行相同」恒真。
     */
    @Test
    @DisplayName("T-17 · 夹具行范围内逐行相同 + 页签/行数计数相同 + 版本号增量相同")
    void t17_selectionConfigChainUnaffected() {
        master.login();

        // ── 两套**同构**夹具：同一个分类、同一份材质配方、同样的提交体，只有客户不同
        Fx fxB = newFixture("17B");   // B 侧（改动前，走 8081 = master）
        Fx fxA = newFixture("17A");   // A 侧（改动后，走本进程）
        touchedQuotationIds.add(fxB.quotationId().toString());
        touchedQuotationIds.add(fxA.quotationId().toString());

        // 🔑 报价模板必须由调用方显式绑（服务端不做匹配，见 bindQuoteTemplate 的说明）
        UUID dsTpl = publishedDsTemplate();
        bindQuoteTemplateMaster(fxB, dsTpl, "T-17 B 侧");
        bindQuoteTemplate(fxA, dsTpl, "T-17 A 侧");

        Map<String, Object> body = submitBody(PREFIX + "T17", newPart(
                "触点", "φ5", "5×3×2", "10",
                List.of(material(RECIPE_A, CONFIG_A, "70"), material(RECIPE_B, CONFIG_B, "30")),
                List.of(PROC_1)));

        // ── B 侧：选配建产品（master 代码）
        MasterSideHttp.R rb = master.post("/api/cpq/configure-product/quotations/" + fxB.quotationId(),
                toJson(body));
        if (rb.status() != 200) {
            fail("⛔ B 侧（master, " + MasterSideHttp.BASE + "）选配建产品失败：HTTP " + rb.status()
                    + " body=" + MasterSideHttp.trim(rb.body())
                    + "。这是**用例环境失败**，不是业务结论 —— B 侧跑不起来就做不了 A/B，"
                    + "🚫 不许降级成只验 A 侧。");
        }
        // ── A 侧：同样的提交体（本分支代码）
        Response ra = configure(fxA, body);
        assertSubmitOk(ra, "T-17 A 侧（本分支）选配建产品");

        String partB = latestLinePartNo(fxB);
        String partA = latestLinePartNo(fxA);
        mintedPartNos.add(partB);
        mintedPartNos.add(partA);
        System.out.println("[T-17] 铸出销售料号：B(master)=" + partB + "  A(本分支)=" + partA);
        // 🔬 与 t17b 的对照量：同样先绑了 customer_template_id，这里 line_item.template_id 是什么？
        System.out.println("[T-17·对照] quotation.customer_template_id="
                + scalar("SELECT coalesce(customer_template_id::text,'<NULL>') FROM quotation WHERE id = '"
                        + fxA.quotationId() + "'")
                + " | line_item.template_id="
                + scalar("SELECT coalesce(template_id::text,'<NULL>') FROM quotation_line_item "
                        + "WHERE quotation_id = '" + fxA.quotationId() + "' LIMIT 1")
                + " | 组件数据行数="
                + count("SELECT count(*) FROM quotation_line_component_data cd "
                        + "JOIN quotation_line_item li ON li.id = cd.line_item_id "
                        + "WHERE li.quotation_id = '" + fxA.quotationId() + "'"));

        // ══ 🚨 A/B 阳性对照：两侧确实是两份代码 ══
        //    同一张单（fxA）：master 侧的预览**不含** dsBackfill，本进程侧**必含**。
        MasterSideHttp.R pvB = master.get("/api/cpq/quotations/" + fxA.quotationId()
                + "/costing-approve/preview");
        assertEquals(200, pvB.status(), "A/B 判别：master 侧预览应 200。body=" + MasterSideHttp.trim(pvB.body()));
        assertFalse(pvB.data().has("dsBackfill"),
                "🚨 A/B 阳性对照失败：" + MasterSideHttp.BASE + " 的预览里出现了 dsBackfill ⇒ "
                        + "8081 跑的不是 master，两侧是同一份代码，任何「相同」都是恒真的假绿。");
        Response pvA = given().get("/api/cpq/quotations/" + fxA.quotationId()
                + "/costing-approve/preview").thenReturn();
        assertEquals(200, pvA.statusCode(), "A/B 判别：本进程预览应 200。body=" + pvA.asString());
        assertTrue(pvA.asString().contains("dsBackfill"),
                "🚨 A/B 阳性对照失败：本进程（改动后）的预览里**没有** dsBackfill ⇒ A 侧不是本分支代码。");
        System.out.println("[T-17] A/B 窗口已验明：B 侧无 dsBackfill / A 侧有 dsBackfill");

        // ══ AC-17①：ds_quote_* 在**本次夹具行范围内**逐行相同（归一化后）══
        List<String> dsTables = col("SELECT c.table_name FROM information_schema.columns c "
                + "WHERE c.table_schema='public' AND c.table_name LIKE 'ds\\_quote\\_%' "
                + "  AND c.table_name NOT LIKE '%\\_history' AND c.table_name NOT LIKE '%\\_record' "
                + "  AND c.column_name='material_no' ORDER BY 1")
                .stream().map(String::valueOf).toList();
        int comparedTables = 0;
        long comparedRows = 0;
        for (String t : dsTables) {
            List<String> rb2 = normalizedRows(t, partB, fxB.customerNo());
            List<String> ra2 = normalizedRows(t, partA, fxA.customerNo());
            if (rb2.isEmpty() && ra2.isEmpty()) continue;   // 该表本链路不写，跳过
            comparedTables++;
            comparedRows += rb2.size();
            assertEquals(rb2, ra2,
                    "🔑 AC-17①：" + t + " 上选配写入的行，改动前后应逐行相同（已按可归因规则归一化"
                            + " id/料号/客户号/时间戳/指纹）。B 侧(master)=" + rb2 + "  A 侧(本分支)=" + ra2);
        }
        assertNonEmpty(comparedTables, "AC-17① 实际参与比对的 ds_quote_* 表数");
        assertNonEmpty(comparedRows, "AC-17① 实际参与比对的行数");
        System.out.println("[T-17] AC-17① 通过：" + comparedTables + " 张 ds_quote_* 表 / "
                + comparedRows + " 行，归一化后逐行相同");

        // ══ AC-17②：页签数与每页签行数 —— 具体计数，🚫 不写「渲染正常」══
        Map<String, Integer> tabsB = tabRowCounts(fxB);
        Map<String, Integer> tabsA = tabRowCounts(fxA);
        assertNonEmpty(tabsB.size(), "AC-17② B 侧页签数");
        assertEquals(tabsB.size(), tabsA.size(),
                "AC-17②：页签数改动前后应相同。B=" + tabsB.size() + tabsB + "  A=" + tabsA.size() + tabsA);
        assertEquals(tabsB, tabsA,
                "AC-17②：每页签行数改动前后应逐项相同。B=" + tabsB + "  A=" + tabsA);
        System.out.println("[T-17] AC-17② 通过：页签数=" + tabsB.size() + "，每页签行数=" + tabsB);

        // ══ AC-17③（🚦 2026-09-08 用户裁决：换成更强的不变量）══
        //
        // 🕰️ 原判据：「A/B 两侧版本号增量**相同**」。它写在 D-40/D-41 之前 ——
        //    那两条给建单流程补了 _record 写入 ⇒ 选配单现在**有 _record**
        //    ⇒ 它会真的参与回填 ⇒ A 侧升版而 master 侧对新链路 no-op。
        //    ⇒ 原判据被本期新功能推翻（实测两轮：A={material_bom=1} vs B={material_bom=0}）。
        //    🚫 这不是缺陷，是预期结果 —— 详见 需求文档.md AC-17③ 的留痕。
        //
        // 🔑 新判据：**A 侧多出的升版，必须恰好对应预览 dsBackfill 里判 UPGRADED 的那些组；
        //    B 侧（master，无回填能力）恒 0。**
        //    比原判据强在哪：原判据只「禁止变化」，新判据验证「新功能真的按预览说的那样写」——
        //    预览说升哪几组就必须恰好升那几组，**预览说谎会被它抓住**，原判据抓不住。
        //
        // 🚫 判据里刻意**不写任何具体表名/数字**（本项目在写死数字上栽过三次）：
        //    写的是「恰好等于预览判 UPGRADED 的集合」，不是「material_bom 增量 = 1」。
        Map<String, Integer> verB0 = groupVersions(partB);
        Map<String, Integer> verA0 = groupVersions(partA);
        System.out.println("[T-17] 核价通过前版本：B=" + verB0 + "  A=" + verA0);

        approveMasterSide(fxB);
        // 🔑 必须拿**用于本次通过的那一份预览**（token 同源），否则「预览说的」与「实际写的」
        //    可能来自两次不同的重算，比对就失去意义。
        io.restassured.path.json.JsonPath approvePv = approveInProcess(fxA);

        assertEquals("APPROVED", quotationStatus(fxB.quotationId().toString()),
                "AC-17③：B 侧核价通过后状态应为 APPROVED");
        assertEquals("APPROVED", quotationStatus(fxA.quotationId().toString()),
                "AC-17③：A 侧核价通过后状态应为 APPROVED");

        Map<String, List<String>> pvResults = dsBackfillResults(approvePv);
        Set<String> upgradedTables = new TreeSet<>();
        Set<String> unchangedOnlyTables = new TreeSet<>();
        for (Map.Entry<String, List<String>> e : pvResults.entrySet()) {
            if (e.getValue().contains("UPGRADED")) upgradedTables.add(e.getKey());
            else if (!e.getValue().isEmpty()) unchangedOnlyTables.add(e.getKey());
        }
        System.out.println("[T-17] A 侧预览 dsBackfill 逐表判定：" + pvResults);
        System.out.println("[T-17] 预览判 UPGRADED 的表=" + upgradedTables
                + "  预览判无 UPGRADED（阴性对照用）的表=" + unchangedOnlyTables);

        Map<String, Integer> verB1 = groupVersions(partB);
        Map<String, Integer> verA1 = groupVersions(partA);
        Map<String, Integer> dB = delta(verB0, verB1);
        Map<String, Integer> dA = delta(verA0, verA1);
        System.out.println("[T-17] 核价通过后版本：B=" + verB1 + "  A=" + verA1);
        System.out.println("[T-17] 版本号增量：B(master)=" + dB + "  A(本分支)=" + dA);
        assertNonEmpty(dB.size(), "AC-17③ 参与比对的料号组数");

        // ── ① B 侧（master，无回填能力）增量恒 0 ──────────────────────────────
        for (Map.Entry<String, Integer> e : dB.entrySet()) {
            assertEquals(0, e.getValue().intValue(),
                    "🔑 AC-17③-①：B 侧(master) 没有 ds 回填能力，任何表的版本号增量都应为 0，"
                            + "但 " + e.getKey() + " 增了 " + e.getValue() + "。完整 dB=" + dB);
        }

        // ── 🚨 阳性对照：预览必须真的判出至少一个 UPGRADED 组 ─────────────────
        //    否则 upgradedTables 为空 ⇒ ② 退化成「空集 == 空集」、③ 循环 0 次，两条恒真。
        assertNonEmpty(upgradedTables.size(),
                "AC-17③ 阳性对照：预览 dsBackfill 判 UPGRADED 的表集合（逐表判定=" + pvResults + "）");

        // ── ② A 侧非零增量的表集合 **恰好等于** 预览判 UPGRADED 的表集合 ──────
        //    🚫 不是「⊇」：多升了没预告的组，和少升了预告过的组，都必须红。
        Set<String> changedA = new TreeSet<>();
        for (Map.Entry<String, Integer> e : dA.entrySet()) {
            if (e.getValue() != 0) changedA.add(e.getKey());
        }
        assertEquals(upgradedTables, changedA,
                "🔑 AC-17③-②：A 侧实际升版的表集合应**恰好**等于预览判 UPGRADED 的表集合。"
                        + "预览说要升=" + upgradedTables + "  实际升了=" + changedA
                        + "。左多右少 ⇒ 预告了却没写；左少右多 ⇒ 写了没预告（财务看不到）。"
                        + "预览逐表判定=" + pvResults + "  dA=" + dA);

        // ── ③ 每个升版的表，增量恰好 1（一次核价通过只升一版）────────────────
        for (String t : upgradedTables) {
            assertEquals(1, dA.getOrDefault(t, 0).intValue(),
                    "🔑 AC-17③-③：一次核价通过对同一张表只应升一版，但 " + t
                            + " 的增量是 " + dA.get(t) + "。dA=" + dA);
        }

        // ── 🚨 阴性对照：预览判 UNCHANGED（该表无任何 UPGRADED 组）的表，A 侧增量必须 0 ──
        //    与 ②③ 成对：只有 ②③ 绿而本条也绿，才说明判据分得清「该升的」和「不该升的」。
        assertNonEmpty(unchangedOnlyTables.size(),
                "AC-17③ 阴性对照：预览里**没有** UPGRADED 组的表集合 —— 一张都没有时本条循环 0 次、"
                        + "恒真恒通过，那样的绿不构成证据。预览逐表判定=" + pvResults);
        for (String t : unchangedOnlyTables) {
            assertEquals(0, dA.getOrDefault(t, 0).intValue(),
                    "🔑 AC-17③ 阴性对照：预览没说要升 " + t + "（判定=" + pvResults.get(t)
                            + "），A 侧却把它升了 " + dA.get(t) + " 版。dA=" + dA);
        }
        System.out.println("[T-17] AC-17③ 通过：B 侧增量全 0；A 侧升版表集合 " + changedA
                + " 恰好 == 预览判 UPGRADED 的集合，每张 +1；阴性对照 " + unchangedOnlyTables
                + " 增量全 0");
    }

    /**
     * 预览响应里 {@code dsBackfill.tables[]} 的「表名 → 该表各组的 result 列表」。
     *
     * <p>result 取值见 {@code api.md}：{@code CREATED / UPGRADED / UNCHANGED / BLOCKED}。
     * 🚫 刻意返回<b>整个列表</b>而不是布尔：调用方要能在失败消息里把原始判定打出来，
     * 否则红了只知道「集合不等」，不知道预览到底说了什么。
     */
    @SuppressWarnings("unchecked")
    private Map<String, List<String>> dsBackfillResults(io.restassured.path.json.JsonPath pv) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<Map<String, Object>> tables = pv.getList("data.dsBackfill.tables");
        if (tables == null) return out;
        for (Map<String, Object> t : tables) {
            List<String> rs = new ArrayList<>();
            Object gs = t.get("groups");
            if (gs instanceof List<?> list) {
                for (Object g : list) {
                    if (g instanceof Map<?, ?> gm) rs.add(String.valueOf(gm.get("result")));
                }
            }
            out.put(String.valueOf(t.get("tableName")), rs);
        }
        return out;
    }

    /**
     * 🚦 <b>主线裁决二 · 第②条：自造「绑 {@code builder_config} 非空组件」的选配模板。</b>
     *
     * <h3>为什么必须另造，而不是拿现网跑</h3>
     * 只读实查（{@code component_sql_view.builder_config}）：现网选配单的 5 个组件
     * （产品 / 材料成本 / 外购件成本 / BOM / 加工费）<b>builder_config 全为 NULL</b>
     * ⇒ 选配链路当前<b>完全不接</b> ds 回填 ⇒ {@code dsBackfill.tables=[]}
     * ⇒ AC-17③「版本号增量相同」在现网配置下<b>恒真式成立</b>。
     *
     * <p>🔑 那是<b>配置的偶然，不是结构的保证</b>。AC-17 防的是「我的回填改动打坏选配」——
     * 哪天有人给选配模板绑一个 builder 组件，这条守卫才开始有意义，
     * 而<b>那时候没人会回头补用例</b>。⇒ 现在就把非空的那一路造出来。
     *
     * <h3>🚨 非空守卫是本条的命门</h3>
     * {@code dsBackfill.tables} 仍为空 ⇒ <b>说明 builder 组件没绑对，是夹具错，不是通过</b>。
     * 🚫 绝不能让它以「两侧都是 0、逐项相同」的形态报绿 —— 那正是本条要消灭的恒真。
     */
    @Test
    @DisplayName("T-17b · 自造 builder_config 非空的选配模板 → AC-17③ 第一次成为非恒真断言")
    void t17b_selectionChainWithBuilderBoundComponent() {
        master.login();

        UUID categoryId = createCategory("17b");
        Fx fxB = newFixture("17bB", categoryId);
        Fx fxA = newFixture("17bA", categoryId);
        // 🔬 实测根因（2026-09-07 干净库）：三张 ds 原生模板全部绑
        //    (customer_id=1f5818d8…, category_id=2c4b8ed8…) ⇒ **模板按 (客户, 分类) 匹配**。
        //    我首版只改了 category_id、customer_id 照抄源模板 ⇒ 夹具客户永远匹配不上
        //    ⇒ 选配单建出来了但**一个页签都没有**（dsBackfill.tables=0 且 nonParticipating=0，
        //       后者为 0 正是「压根没有组件被看到」的signature，不是「组件不参与」）。
        //    ⇒ 每个夹具客户各绑一张模板。
        UUID tplB = buildBuilderBoundTemplate(categoryId, fxB.customerId());
        UUID tplA = buildBuilderBoundTemplate(categoryId, fxA.customerId());
        bindQuoteTemplateMaster(fxB, tplB, "T-17b B 侧");
        bindQuoteTemplate(fxA, tplA, "T-17b A 侧");
        touchedQuotationIds.add(fxB.quotationId().toString());
        touchedQuotationIds.add(fxA.quotationId().toString());

        // 🔬 实测：单材质写 ratio=70 会被后端拒（400 MATERIAL_RATIO_SUM_INVALID
        //    「材质占比合计为 70%，需要正好 100%」）⇒ 单材质必须 100。
        //    这是**我的夹具错**，不是被测缺陷 —— t17 用两个材质 70+30 才恰好合法。
        Map<String, Object> body = submitBody(PREFIX + "T17b", newPart(
                "触点", "φ5", "5×3×2", "10",
                List.of(material(RECIPE_A, CONFIG_A, "100")),
                List.of(PROC_1)));

        MasterSideHttp.R rb = master.post("/api/cpq/configure-product/quotations/" + fxB.quotationId(),
                toJson(body));
        if (rb.status() != 200) {
            fail("⛔ B 侧（master）选配建产品失败：HTTP " + rb.status()
                    + " body=" + MasterSideHttp.trim(rb.body()) + "。用例环境失败，不是业务结论。");
        }
        assertSubmitOk(configure(fxA, body), "T-17b A 侧选配建产品");

        String partB = latestLinePartNo(fxB);
        String partA = latestLinePartNo(fxA);
        mintedPartNos.add(partB);
        mintedPartNos.add(partA);

        // ══ 🚨 命门：A 侧的 dsBackfill 必须**非空**，否则本条什么都没验到 ══
        Response pv = given().get("/api/cpq/quotations/" + fxA.quotationId()
                + "/costing-approve/preview").thenReturn();
        assertEquals(200, pv.statusCode(), "T-17b A 侧预览应 200。body=" + pv.asString());
        int tables = pv.jsonPath().getList("data.dsBackfill.tables") == null
                ? 0 : pv.jsonPath().getList("data.dsBackfill.tables").size();
        java.util.List<Object> nonPart = pv.jsonPath().getList("data.dsBackfill.nonParticipating");
        System.out.println("[T-17b] A 侧 dsBackfill.tables=" + tables
                + " nonParticipating=" + (nonPart == null ? 0 : nonPart.size())
                + " summary=" + pv.jsonPath().getMap("data.dsBackfill.summary"));
        // ══ 🔬 三跳定位：DsBackfillCollector 的三个静默早退**产出形状一模一样**
        //    （都是 tables=0 且 nonParticipating=0），不逐跳量就分不清卡在哪。
        //      :95  lineIds      —— 本单的 line_item
        //      :101 compIds      —— SELECT DISTINCT component_id FROM quotation_line_component_data
        //                          WHERE line_item_id IN (:ids) AND component_id IS NOT NULL
        //      :104 resolveAll(compIds)
        long hop1 = count("SELECT count(*) FROM quotation_line_item WHERE quotation_id = '"
                + fxA.quotationId() + "'");
        long hop2 = count("SELECT count(DISTINCT cd.component_id) FROM quotation_line_component_data cd "
                + "JOIN quotation_line_item li ON li.id = cd.line_item_id "
                + "WHERE li.quotation_id = '" + fxA.quotationId() + "' AND cd.component_id IS NOT NULL");
        long compDataRows = count("SELECT count(*) FROM quotation_line_component_data cd "
                + "JOIN quotation_line_item li ON li.id = cd.line_item_id "
                + "WHERE li.quotation_id = '" + fxA.quotationId() + "'");
        Object boundTpl = scalar("SELECT coalesce(customer_template_id::text,'<NULL>') FROM quotation "
                + "WHERE id = '" + fxA.quotationId() + "'");
        Object lineTpl = scalar("SELECT coalesce(template_id::text,'<NULL>') FROM quotation_line_item "
                + "WHERE quotation_id = '" + fxA.quotationId() + "' LIMIT 1");
        // 🔬 再钉一枪：物化读的是**冻结快照**还是 template_component？
        //    与 ds 原生模板逐项对照（后者能物化出 14 行）。
        for (String tblName : List.of("template_component", "template_component_snapshot")) {
            if (count("SELECT count(*) FROM information_schema.tables WHERE table_schema='public'"
                    + " AND table_name='" + tblName + "'") == 0) {
                System.out.println("[T-17b·快照] 表 " + tblName + " 不存在");
                continue;
            }
            System.out.println("[T-17b·快照] " + tblName
                    + "：自造模板=" + count("SELECT count(*) FROM " + tblName
                        + " WHERE template_id = '" + tplA + "'")
                    + "  ds原生(a2228dae)=" + count("SELECT count(*) FROM " + tblName
                        + " WHERE template_id = 'a2228dae-7a54-4921-a66f-da4eed41a6c1'"));
        }
        System.out.println("[T-17b·三跳] hop1 lineIds=" + hop1
                + " | hop2 compIds(非空 component_id 去重)=" + hop2
                + " | 组件数据行数=" + compDataRows
                + " | quotation.customer_template_id=" + boundTpl
                + " | line_item.template_id=" + lineTpl
                + "  ⇒ 卡点：" + (hop1 == 0 ? "第 1 跳（本单没有 line_item）"
                    : hop2 == 0 ? "第 2 跳（组件数据的 component_id 全为空或无组件数据）"
                    : "第 3 跳（compIds 非空但 resolveAll 一个都没解出来）"));

        assertNonEmpty(tables,
                "🚨 T-17b 的命门：自造模板绑了 builder_config 非空的组件，A 侧 dsBackfill.tables 却仍为空。\n"
                        + "   ⇒ **这是夹具错，不是通过** —— builder 组件没被选配链路用上"
                        + "（可能模板没按 category 匹配到、或组件没挂进模板、或 driver 路径没解出锚表）。\n"
                        + "   🚫 绝不许把它读成「AC-17③ 通过」：tables 为空时两侧增量都是 0，"
                        + "「逐项相同」恒真，和现网那条恒真式成立是同一个病。\n"
                        + "   诊断入手：nonParticipating 里是不是列着我的自造组件 + 它的 reason。"
                        + " nonParticipating=" + nonPart);

        // ══ 到这里 dsBackfill 非空，AC-17③ 才第一次是非恒真的 ══
        Map<String, Integer> verB0 = groupVersions(partB);
        Map<String, Integer> verA0 = groupVersions(partA);
        approveMasterSide(fxB);
        approveInProcess(fxA);
        Map<String, Integer> dB = delta(verB0, groupVersions(partB));
        Map<String, Integer> dA = delta(verA0, groupVersions(partA));
        System.out.println("[T-17b] 版本号增量：B(master)=" + dB + "  A(本分支)=" + dA);

        assertEquals("APPROVED", quotationStatus(fxB.quotationId().toString()), "T-17b B 侧应 APPROVED");
        assertEquals("APPROVED", quotationStatus(fxA.quotationId().toString()), "T-17b A 侧应 APPROVED");
        assertNonEmpty(dA.size(), "T-17b 参与比对的料号组数（A 侧）");
        assertEquals(dB, dA,
                "🔑 AC-17③（非恒真版）：选配模板绑了 builder 组件之后，核价通过的料号组版本号增量"
                        + "仍应与改动前相同。B 侧(master)=" + dB + "  A 侧(本分支)=" + dA + "。\n"
                        + "⚠️ 红了先别当回归：本段新增的正是「核价通过 → 回填升版」，master 侧对新链路是 no-op。"
                        + "把两侧增量 + 该组的 result（UNCHANGED / UPGRADED / BLOCKED）摆出来报主线裁决，"
                        + "🚫 不自行改判据去凑绿。");
    }

    /**
     * 造一个 {@code builder_config} <b>非空</b>的组件 + 一张挂它的模板，并绑到 {@code categoryId}。
     *
     * <p>手法照抄 {@code PartialColumnScopeAcTest#buildPartialColumnTemplate}（已跑通）：
     * <b>整行复制</b> ds 原生组件与它的 {@code component_sql_view}，只改 id / name / 视图名，
     * 🔑 {@code builder_config} <b>原样照抄不过滤</b>（要的就是它非空），
     * {@code tabType / variantKey / dialect} 等坐标一律不动 —— 改了就解不出锚表。
     * <p>🚫 <b>全程只新增行，不改任何现网配置</b>（不动源组件、不动源模板、不动现网选配模板）。
     */
    private UUID buildBuilderBoundTemplate(UUID categoryId, UUID customerId) {
        boolean firstTime = (builderComponentId == null);
        if (firstTime) {
            builderComponentId = UUID.randomUUID();
            builderViewName = "builder_" + builderComponentId.toString().replace("-", "").substring(0, 12);
        }
        UUID templateId = UUID.randomUUID();
        builderTemplateIds.add(templateId);

        // 🔬 实测（2026-09-07 干净库，第二次归因）：现存选配单的 line item 拿到的是
        //    template_id=a2228dae…（**v1.2**，全系列最新），而三张模板同 series、同 (customer, category)
        //    ⇒ 解析是「**取该 series 里版本最高的一张**」。
        //    我的夹具模板照抄源 series 且 version='v1.0' ⇒ **永远输给 v1.2**，于是一个页签都挂不上
        //    （症状：dsBackfill.tables=0 **且** nonParticipating=0 —— 后者为 0 才是关键，
        //     它说明「压根没有组件被看到」，而不是「组件不参与」）。
        //
        // ⇒ 给夹具模板一个**独立 series**（实查：template_series 不是表、template_series_id 无 FK，
        //    是自由 UUID 列）。
        // 🚫 刻意**不**改成 version='v9.9' 去抢源 series 的最高版：若解析没把 customer_id 算进键，
        //    那样会让我的测试模板**劫持真实 ds 原生链路**，而后端代理正在同一个库跑 C′ 证据。
        //    宁可夹具挂不上（可见、可诊断），也不制造一个会影响别人的全局副作用。
        String seriesId = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            if (firstTime) {
            em.createNativeQuery(
                            "INSERT INTO component (id,name,code,tab_type,data_driver_path,fields,status,created_at,updated_at) "
                                    + "SELECT :nid,:nm,:cd,tab_type,:ddp,fields,status,now(),now() "
                                    + "FROM component WHERE id = :src")
                    .setParameter("nid", builderComponentId)
                    .setParameter("nm", PREFIX + "选配-builder元素BOM")
                    .setParameter("cd", PREFIX + builderComponentId.toString().substring(0, 8))
                    .setParameter("ddp", "$" + builderViewName)
                    .setParameter("src", SRC_COMPONENT)
                    .executeUpdate();
            // 🔑 builder_config 原样照抄（非空即目的）
            em.createNativeQuery(
                            "INSERT INTO component_sql_view (id,component_id,sql_view_name,sql_template,"
                                    + "  declared_columns,required_variables,scope,status,builder_config,builder_version,created_at) "
                                    + "SELECT gen_random_uuid(),:nid,:vn,sql_template,declared_columns,"
                                    + "  required_variables,scope,status,builder_config,builder_version,now() "
                                    + "FROM component_sql_view WHERE sql_view_name = :src")
                    .setParameter("nid", builderComponentId).setParameter("vn", builderViewName)
                    .setParameter("src", SRC_VIEW)
                    .executeUpdate();
            }
            // 模板整行复制源模板（保 template_kind / is_default 等匹配坐标），只改 id/name/category
            em.createNativeQuery(
                            "INSERT INTO template (id,template_series_id,name,version,category,status,"
                                    + "  template_kind,is_default,category_id,customer_id,created_at,updated_at) "
                                    + "SELECT :tid, CAST(:sid AS uuid), :nm, version, category, 'PUBLISHED', "
                                    + "  template_kind, is_default, CAST(:cat AS uuid), CAST(:cust AS uuid), now(), now() "
                                    + "FROM template WHERE id = :src")
                    .setParameter("tid", templateId).setParameter("sid", seriesId)
                    .setParameter("nm", PREFIX + "选配builder模板-" + templateId.toString().substring(0, 6))
                    .setParameter("cat", categoryId.toString())
                    .setParameter("cust", customerId.toString())
                    .setParameter("src", Task260907RBase.DS_TEMPLATE_ID)
                    .executeUpdate();
            em.createNativeQuery(
                            "INSERT INTO template_component (id,template_id,component_id,sort_order) "
                                    + "VALUES (gen_random_uuid(), :tid, :cid, 0)")
                    .setParameter("tid", templateId).setParameter("cid", builderComponentId)
                    .executeUpdate();
        });

        // 🚨 构造自检：builder_config 真的非空，否则本条整个失去意义
        long nonNull = count("SELECT count(*) FROM component_sql_view WHERE sql_view_name = '"
                + builderViewName + "' AND builder_config IS NOT NULL");
        assertEquals(1L, nonNull,
                "构造自检失败：自造组件的 component_sql_view.builder_config 应非空，实际非空行数 " + nonNull
                        + " ⇒ 后面「绑了 builder 组件」这个前提不成立，本用例什么都验不到");
        // 🔑 自造模板必须**冻结**，否则绑定时返
        //    409 TEMPLATE_NOT_FROZEN「该模板的渲染配置冻结快照为空」。
        //    main-api.md §【模板】首次冻结：POST /templates/{id}/freeze，
        //    🔒 零行守卫 —— 仅当 template_component_snapshot 行数为 0 时可用
        //    ⇒ 结构上不可能覆盖已有快照，对现网模板无副作用。
        Response fz = given().contentType(io.restassured.http.ContentType.JSON)
                .post("/api/cpq/templates/" + templateId + "/freeze").thenReturn();
        assertEquals(200, fz.statusCode(),
                "T-17b：自造模板 " + templateId + " 冻结失败（HTTP " + fz.statusCode() + "）⇒ "
                        + "后续绑定会返 409 TEMPLATE_NOT_FROZEN。body=" + fz.asString());
        System.out.println("[T-17b] 已造 builder 组件 " + builderComponentId + " / 视图 " + builderViewName
                + " / 模板 " + templateId + "（已冻结）→ 分类 " + categoryId + " · 客户 " + customerId);
        return templateId;
    }

    /**
     * ③ 清掉自造的配置行（组件 / 视图 / 模板 / 挂载），🚫 只删本轮自己建的那几个 id。
     * <p>📌 必须排在 {@link #teardownOwnQuotations()} <b>之后</b>：模板被本轮报价单的
     * {@code customer_template_id} 引用着，报价单没删掉时这里的 {@code DELETE FROM template} 会抛 FK。
     */
    private void cleanupBuilderConfig() {
        if (builderComponentId == null && builderTemplateIds.isEmpty()) return;
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                for (UUID t : builderTemplateIds) {
                    em.createNativeQuery("DELETE FROM template_component WHERE template_id = :t")
                            .setParameter("t", t).executeUpdate();
                    em.createNativeQuery("DELETE FROM template WHERE id = :t AND name LIKE :p")
                            .setParameter("t", t).setParameter("p", PREFIX + "%").executeUpdate();
                }
                if (builderComponentId != null) {
                    em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id = :c")
                            .setParameter("c", builderComponentId).executeUpdate();
                    em.createNativeQuery("DELETE FROM component WHERE id = :c AND name LIKE :p")
                            .setParameter("c", builderComponentId).setParameter("p", PREFIX + "%").executeUpdate();
                }
            });
        } finally {
            builderComponentId = null;
            builderTemplateIds.clear();
        }
    }

    /**
     * 🔑 <b>把报价模板显式绑到报价单上 —— 这是唯一能生效的途径。</b>
     *
     * <h3>🕰️ 2026-09-08 主线查实现给出的答案（我此前三个假设全部建立在错的前提上）</h3>
     * {@code ConfigureProductService} <b>根本不解析报价模板</b> —— 它的 import 只有
     * {@code com.cpq.seltemplate.*}（<b>选配</b>模板），不碰 {@code com.cpq.template}（<b>报价</b>模板）。
     * 报价模板是<b>调用方在请求体里显式传进来</b>的：
     * {@code SaveDraftRequest.customerTemplateId} → {@code QuotationService:314-316}
     * {@code validateTemplateBinding(id, "QUOTATION", null)} → {@code q.customerTemplateId = …}。
     *
     * <p>⇒ 我此前试的三条（只绑 {@code category_id} / 每客户一张 / 独立 {@code template_series_id}）
     * <b>全部不可能成立</b>：{@code categoryId} 在该实现里命中 <b>0</b> 次。
     * 📌 教训：<b>「挂不上」时先确认「到底有没有人在做匹配」，再去猜匹配键</b> ——
     * 我连猜三轮，猜的是一个不存在的机制。
     *
     * <p>⚠️ 走真实的 {@code PUT /draft}（🚫 不用 SQL 直接 UPDATE）：那条路会过
     * {@code validateTemplateBinding}，模板 {@code template_kind} 不对会在这里被明确拒绝，
     * 而不是变成后面「一个页签都没有」的哑谜。
     */
    private void bindQuoteTemplate(Fx fx, UUID templateId, String what) {
        String body = "{\"baseVersion\":0,\"customerTemplateId\":\"" + templateId + "\","
                + "\"added\":[],\"modified\":[],\"removed\":[]}";
        Response r = given().contentType(io.restassured.http.ContentType.JSON).body(body)
                .put("/api/cpq/quotations/" + fx.quotationId() + "/draft").thenReturn();
        assertEquals(200, r.statusCode(),
                what + "：绑定报价模板 " + templateId + " 失败（HTTP " + r.statusCode() + "）。"
                        + "⚠️ 若是 validateTemplateBinding 拒的，报错里会写清原因（多半是 template_kind "
                        + "不是 QUOTATION）—— **先读它，别再猜匹配键**。body=" + r.asString());
        Object bound = scalar("SELECT customer_template_id FROM quotation WHERE id = '"
                + fx.quotationId() + "'");
        assertEquals(String.valueOf(templateId), String.valueOf(bound),
                what + "：绑定后 quotation.customer_template_id 应等于 " + templateId + "，实际 " + bound);
    }

    /** master 侧同样要绑（两侧必须用同一套模板，否则 A/B 比的是两个东西）。 */
    private void bindQuoteTemplateMaster(Fx fx, UUID templateId, String what) {
        String body = "{\"baseVersion\":0,\"customerTemplateId\":\"" + templateId + "\","
                + "\"added\":[],\"modified\":[],\"removed\":[]}";
        MasterSideHttp.R r = master.put("/api/cpq/quotations/" + fx.quotationId() + "/draft", body);
        assertEquals(200, r.status(),
                what + "：B 侧绑定报价模板失败（HTTP " + r.status() + "）。body="
                        + MasterSideHttp.trim(r.body()));
    }

    /**
     * 现网「能用的那一版」ds 原生报价模板。
     * 🚨 <b>按 {@code version} 取，🚫 绝不按 {@code name}</b> —— 三张模板 {@code name} 一模一样
     * （都写着 v1.0），按 name 会随机拿到 13 页签那版。
     */
    private UUID publishedDsTemplate() {
        String id = scalar("SELECT id FROM template WHERE version = 'v1.2' AND status = 'PUBLISHED' "
                + "AND template_kind = 'QUOTATION' ORDER BY updated_at DESC LIMIT 1");
        assertTrue(id != null,
                "前置：找不到 version='v1.2' 的 PUBLISHED 报价模板 —— 现网模板集变了，"
                        + "本用例的夹具前提不成立（🚫 不要退而按 name 取）");
        return UUID.fromString(id);
    }

    // ─────────────────────────── 归一化与计数 ───────────────────────────

    /**
     * 某表在<b>本次夹具料号范围内</b>的行，按可归因规则归一化后的有序文本。
     *
     * <p>剔除/替换的都是「两侧天然必然不同」的维度，逐条可归因：
     * {@code id}（自增）· {@code material_no}（系统铸号）· {@code customer_no}（各自客户）·
     * {@code created_at/updated_at/created_by/updated_by}（时间与操作人）·
     * {@code row_fingerprint}（含料号 ⇒ 必然不同）。
     * 🚫 其余一律不剔 —— 剔多了就成了「怎么比都相同」的空验证。
     */
    private List<String> normalizedRows(String table, String partNo, String customerNo) {
        String t = safeIdent(table);
        List<Object> raw = col("SELECT to_jsonb(x)::text FROM " + t + " x "
                + "WHERE x.material_no = '" + partNo.replace("'", "''") + "' ORDER BY x.id");
        List<String> out = new ArrayList<>();
        for (Object o : raw) {
            String s = String.valueOf(o);
            s = s.replace(partNo, "<PART>").replace(customerNo, "<CUST>");
            s = s.replaceAll("\"(id|created_at|updated_at|created_by|updated_by|row_fingerprint)\":\\s*(\"[^\"]*\"|null|[0-9.]+)", "\"$1\":<N>");
            out.add(s);
        }
        return out;
    }

    /** {@code 页签名 → 行数} —— AC-17② 要的「具体计数」。 */
    private Map<String, Integer> tabRowCounts(Fx fx) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Object[] r : rows("SELECT cd.tab_name, "
                + "  coalesce(jsonb_array_length(cd.snapshot_rows), "
                + "           jsonb_array_length(cd.row_data), 0) "
                + "FROM quotation_line_component_data cd "
                + "JOIN quotation_line_item li ON li.id = cd.line_item_id "
                + "WHERE li.quotation_id = '" + fx.quotationId() + "' ORDER BY cd.tab_name, cd.sort_order")) {
            String tab = String.valueOf(r[0]);
            int n = r[1] == null ? 0 : ((Number) r[1]).intValue();
            out.merge(tab, n, Integer::sum);
        }
        return out;
    }

    /** {@code 表名 → 该料号组当前 version_no}（只收有该料号的带版本表）。 */
    private Map<String, Integer> groupVersions(String partNo) {
        Map<String, Integer> out = new LinkedHashMap<>();
        List<String> tables = col("SELECT c.table_name FROM information_schema.columns c "
                + "WHERE c.table_schema='public' AND c.table_name LIKE 'ds\\_quote\\_%' "
                + "  AND c.table_name NOT LIKE '%\\_history' AND c.table_name NOT LIKE '%\\_record' "
                + "  AND c.column_name='version_no' ORDER BY 1")
                .stream().map(String::valueOf).toList();
        for (String t : tables) {
            String s = safeIdent(t);
            if (count("SELECT count(*) FROM " + s + " WHERE material_no = '" + partNo.replace("'", "''") + "'") == 0) {
                continue;
            }
            Object v = em.createNativeQuery("SELECT max(version_no) FROM " + s
                    + " WHERE material_no = '" + partNo.replace("'", "''") + "'").getSingleResult();
            out.put(t, v == null ? 0 : ((Number) v).intValue());
        }
        return out;
    }

    /** 增量 = 通过后 − 通过前（并集口径：通过后新出现的组按 0 起算）。 */
    private Map<String, Integer> delta(Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String k : new java.util.TreeSet<>(
                new java.util.LinkedHashSet<>(new ArrayList<>(after.keySet())) {{ addAll(before.keySet()); }})) {
            out.put(k, after.getOrDefault(k, 0) - before.getOrDefault(k, 0));
        }
        return out;
    }

    // ─────────────────────────── 提交 / 核价通过 ───────────────────────────

    private void approveMasterSide(Fx fx) {
        MasterSideHttp.R s = master.post("/api/cpq/quotations/" + fx.quotationId() + "/submit", "{}");
        assertEquals(200, s.status(), "T-17 B 侧提交应 200。body=" + MasterSideHttp.trim(s.body()));
        MasterSideHttp.R pv = master.get("/api/cpq/quotations/" + fx.quotationId()
                + "/costing-approve/preview");
        assertEquals(200, pv.status(), "T-17 B 侧预览应 200。body=" + MasterSideHttp.trim(pv.body()));
        String token = pv.data().path("previewToken").asText(null);
        assertTrue(token != null && !token.isBlank(), "T-17 B 侧预览缺 previewToken");
        MasterSideHttp.R ap = master.post("/api/cpq/quotations/" + fx.quotationId() + "/costing-approve",
                "{\"comment\":\"" + PREFIX + "T17-B\",\"previewToken\":\"" + token + "\"}");
        assertEquals(200, ap.status(), "T-17 B 侧核价通过应 200。body=" + MasterSideHttp.trim(ap.body()));
    }

    /**
     * A 侧（本分支进程内）核价通过。
     * @return 本次通过所用的那一份<b>预览</b>响应（token 同源）—— AC-17③ 要拿它的
     *         {@code dsBackfill} 判定与实际落库结果对账，🚫 不能另起一次预览。
     */
    private io.restassured.path.json.JsonPath approveInProcess(Fx fx) {
        // ⚠️ 不带 Content-Type 会返 415（实测），不是业务错
        Response s = given().contentType(io.restassured.http.ContentType.JSON)
                .post("/api/cpq/quotations/" + fx.quotationId() + "/submit").thenReturn();
        assertEquals(200, s.statusCode(), "T-17 A 侧提交应 200。body=" + s.asString());
        Response pv = given().get("/api/cpq/quotations/" + fx.quotationId()
                + "/costing-approve/preview").thenReturn();
        assertEquals(200, pv.statusCode(), "T-17 A 侧预览应 200。body=" + pv.asString());
        String token = pv.jsonPath().getString("data.previewToken");
        assertTrue(token != null && !token.isBlank(), "T-17 A 侧预览缺 previewToken。body=" + pv.asString());
        Response ap = given().contentType(io.restassured.http.ContentType.JSON)
                .body(Map.of("comment", PREFIX + "T17-A", "previewToken", token))
                .post("/api/cpq/quotations/" + fx.quotationId() + "/costing-approve").thenReturn();
        assertEquals(200, ap.statusCode(), "T-17 A 侧核价通过应 200。body=" + ap.asString());
        return pv.jsonPath();
    }

    private String quotationStatus(String quotationId) {
        return scalar("SELECT status FROM quotation WHERE id = '" + quotationId + "'");
    }

    // ─────────────────────────── 还原（补超类不认识的两类）───────────────────────────

    /**
     * 清掉本轮往 {@code ds_quote_*_record} / {@code _history} 写的行。
     *
     * <p>超类的 {@code @AfterEach} 只认 {@code source='MANUAL'} 的主表行，
     * <b>不认识</b>本段新增的 {@code _record}，也不清 {@code _history}
     * （选配链路原本不升版，那两类以前根本不会产生）。
     * ⇒ 不补这一段，每跑一轮就在共享库里多留一批，且<b>是沉默的</b>。
     *
     * <p>🚨 收窄条件 = 本轮<b>自己铸出的料号</b> / 本轮<b>自己的报价单 id</b>，
     * <b>先 count 再删</b>（{@code CLAUDE.md} §3.2 第一步）。🚫 绝不按表清。
     * <p>📌 JUnit 5：子类 {@code @AfterEach} 先于父类执行 ⇒ 这里跑时报价单还在，料号还查得到。
     */
    /**
     * 🚦 <b>本类唯一的 {@code @AfterEach} 编排器（2026-09-08 新增，用户裁决 §2）。</b>
     *
     * <h3>为什么要合并成一个、并且显式定序</h3>
     * 原来本类有<b>两个</b> {@code @AfterEach}（{@code cleanupRecordAndHistory} /
     * {@code cleanupBuilderConfig}）。同一个类里多个 {@code @AfterEach} 的执行次序
     * <b>JUnit 5 不作保证</b>，而本轮的红<b>恰恰是次序问题</b> ⇒ 必须自己定序。
     *
     * <h3>🔬 实测根因（2026-09-07 三轮逐字一致，🚫 不是「偶发」）</h3>
     * 症状是超类 {@code SelConfigAcTestBase.assertNoResidue} 报
     * 「{@code sel_part_signature} 仍有 {@code T2609xxxx} 的残留」。
     * <b>但那不是根因</b> —— 超类<b>确实</b>执行了 {@code DELETE FROM sel_part_signature}，
     * 只是它整个 per-fixture 清理跑在<b>一个事务</b>里，后面某条语句抛了 FK 异常
     * ⇒ <b>整段回滚</b> ⇒ 那条 DELETE 被撤销。原始异常两条：
     * <pre>
     * t17 ：ERROR: update or delete on table "quotation" violates foreign key constraint
     *       "costing_order_quotation_id_fkey" on table "costing_order"
     * t17b：ERROR: update or delete on table "customer" violates foreign key constraint
     *       "template_customer_id_fkey" on table "template"
     *       （其 Suppressed：template 又被 quotation.customer_template_id 挡着删不掉）
     * </pre>
     * <b>两个阻塞都是本段自己造出来的</b>，超类没理由认识它们：
     * ① {@code costing_order} 是<b>核价通过</b>建的 —— 选配用例原本不走核价通过；
     * ② 那张 {@code template} 是 {@code T-17b} 自造的 builder 模板，且被本轮报价单引用。
     *
     * <p>🚫 <b>因此「直接删 {@code sel_part_signature}」治不了</b>：删掉它，超类的下一条断言
     * （{@code customer} 残留）立刻接着红 —— 红只是换个地方，而且更难归因。
     * ⇒ 正解是<b>把我自己造出来的两个 FK 阻塞先拆掉</b>，让超类原本就写好的清理跑得完。
     *
     * <p>⚠️ <b>这是补超类的清理缺口，根治应在 {@code SelConfigAcTestBase}</b>
     * （它的 per-fixture 清理应当按 FK 拓扑序、或每步独立事务）。
     * 🚫 本轮不动别人的基类（跨任务）。下一个人不要以为这是本用例特有的需求。
     */
    @AfterEach
    void cleanupOwnFixturesInOrder() {
        try {
            cleanupRecordAndHistory();     // ① _record / _history（超类不认识的两类）
            teardownOwnQuotations();       // ② 报价单 + 其全部下游（拆掉 costing_order 这个 FK 阻塞）
            cleanupBuilderConfig();        // ③ 自造 builder 组件/视图/模板（拆掉 template 这个 FK 阻塞）
        } finally {
            // 🚨 状态清空必须在**编排器**这一层，🚫 不能留在 ① 里 ——
            //    2026-09-08 实测踩过：① 的 finally 先把 touchedQuotationIds 清了，
            //    ② 拿到空列表直接 return，于是「② 一行日志都不打」而红照旧。
            //    症状（残留没消）与「② 压根没跑」长得一模一样，正是 testing.md 说的假象。
            mintedPartNos.clear();
            touchedQuotationIds.clear();
        }
    }

    /**
     * ② 删掉<b>本轮自己造的报价单</b>及其全部下游行。
     *
     * <p>🔑 超类也删报价单，但它删在<b>自己那个事务里、且在 {@code costing_order} 之后没有兜底</b>；
     * 而核价通过建出的 {@code costing_order} 会把 {@code DELETE FROM quotation} 顶回去。
     * ⇒ 在这里先删干净，超类那几条 DELETE 就退化成 no-op，它的事务不再回滚。
     *
     * <p>🚨 收窄条件 = {@code touchedQuotationIds}（<b>只有本用例自己建的单</b>），<b>先 count 再删</b>。
     * 🚫 绝不按表清、绝不按前缀扫全库。
     * <p>下游表清单<b>动态派生</b>（🚫 不硬编码 —— 硬编码在「以后又多一张下游表」时会沉默地漏清）。
     */
    private void teardownOwnQuotations() {
        if (touchedQuotationIds.isEmpty()) return;
        List<String> downstream = col("SELECT c.table_name FROM information_schema.columns c "
                + "JOIN information_schema.tables t ON t.table_name = c.table_name "
                + "  AND t.table_schema = 'public' AND t.table_type = 'BASE TABLE' "
                + "WHERE c.table_schema = 'public' AND c.column_name = 'quotation_id' "
                + "  AND c.table_name <> 'quotation' ORDER BY 1")
                .stream().map(String::valueOf).toList();
        for (String qid : List.copyOf(touchedQuotationIds)) {
            try {
                QuarkusTransaction.requiringNew().run(() -> {
                    // 先删挂在 line_item 上的子表（它们按 line_item_id 关联，不带 quotation_id 列）
                    for (String t : List.of("quotation_line_process", "quotation_line_item_snapshot",
                            "quotation_line_component_data")) {
                        if (!tableExists(t)) continue;
                        em.createNativeQuery("DELETE FROM " + safeIdent(t) + " WHERE line_item_id IN "
                                        + "(SELECT id FROM quotation_line_item WHERE quotation_id = CAST(:q AS uuid))")
                                .setParameter("q", qid).executeUpdate();
                    }
                    for (String t : downstream) {
                        String s = safeIdent(t);
                        long n = count("SELECT count(*) FROM " + s + " WHERE quotation_id = '" + qid + "'");
                        if (n == 0) continue;
                        em.createNativeQuery("DELETE FROM " + s + " WHERE quotation_id = CAST(:q AS uuid)")
                                .setParameter("q", qid).executeUpdate();
                        System.out.println("[T-17 还原] " + t + " 清掉下游 " + n + " 行（quotation=" + qid + "）");
                    }
                    long n = count("SELECT count(*) FROM quotation WHERE id = '" + qid + "'");
                    if (n > 0) {
                        em.createNativeQuery("DELETE FROM quotation WHERE id = CAST(:q AS uuid)")
                                .setParameter("q", qid).executeUpdate();
                        System.out.println("[T-17 还原] quotation 清掉 " + n + " 行（" + qid + "）");
                    }
                });
            } catch (RuntimeException e) {
                // 🚫 不吞：清理失败必须可见，否则下一轮会以「业务缺陷」的面目出现
                System.out.println("[T-17 还原] ⚠️ 报价单 " + qid + " 拆除失败（清理问题，非业务结论）：" + e);
            }
        }
    }

    private void cleanupRecordAndHistory() {
        if (mintedPartNos.isEmpty() && touchedQuotationIds.isEmpty()) return;
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                List<String> recTables = col("SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema='public' AND table_name LIKE 'ds\\_quote\\_%\\_record' ORDER BY 1")
                        .stream().map(String::valueOf).toList();
                for (String t : recTables) {
                    String s = safeIdent(t);
                    for (String qid : touchedQuotationIds) {
                        long n = count("SELECT count(*) FROM " + s + " WHERE quotation_id = '" + qid + "'");
                        if (n == 0) continue;
                        em.createNativeQuery("DELETE FROM " + s + " WHERE quotation_id = CAST(:q AS uuid)")
                                .setParameter("q", qid).executeUpdate();
                        System.out.println("[T-17 还原] " + t + " 清掉 " + n + " 行（quotation=" + qid + "）");
                    }
                }
                List<String> mainAndHist = col("SELECT c.table_name FROM information_schema.columns c "
                        + "WHERE c.table_schema='public' AND c.table_name LIKE 'ds\\_quote\\_%' "
                        + "  AND c.table_name NOT LIKE '%\\_record' AND c.column_name='material_no' ORDER BY 1")
                        .stream().map(String::valueOf).toList();
                for (String t : mainAndHist) {
                    String s = safeIdent(t);
                    for (String pn : mintedPartNos) {
                        long n = count("SELECT count(*) FROM " + s + " WHERE material_no = '"
                                + pn.replace("'", "''") + "'");
                        if (n == 0) continue;
                        em.createNativeQuery("DELETE FROM " + s + " WHERE material_no = :p")
                                .setParameter("p", pn).executeUpdate();
                        System.out.println("[T-17 还原] " + t + " 清掉 " + n + " 行（料号=" + pn + "）");
                    }
                }
            });
        } catch (RuntimeException e) {
            System.out.println("[T-17 还原] ⚠️ _record/_history 清理失败（清理问题，非业务结论）：" + e);
        }
        // 🚫 刻意不在这里 clear —— 见 cleanupOwnFixturesInOrder 的 finally
    }

    // ─────────────────────────── 小工具 ───────────────────────────

    private void assertNonEmpty(long n, String what) {
        assertTrue(n > 0, "🚨 空验证守卫：" + what + " 实测 " + n
                + "。为 0 时后面的「逐行相同 / 计数相同」恒真恒通过，此刻的绿不构成任何证据。");
    }

    private static String safeIdent(String s) {
        if (!s.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("非法标识符：" + s);
        return s;
    }

    private static String toJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
