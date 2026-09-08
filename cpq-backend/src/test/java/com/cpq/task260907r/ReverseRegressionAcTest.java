package com.cpq.task260907r;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>T-15 / T-16 / T-17 —— 反向回归</b>
 * （AC-15 老回填链路行为不变 · AC-16 核价两套数据集不被波及 · AC-17 选配链路不被波及）
 *
 * <h3>🚨 三条 AC 已于 2026-09-07 按「活数据陷阱」重写，本类照新版写</h3>
 * 原判据都是「与改动前<b>逐行 md5 相同</b>」，看着像不变量，<b>实际不是</b> ——
 * md5 罩住的是共享库里别的会话也在写的行。
 * 🔬 实证：在 <b>master 同码</b>上重采基线，14 个端点里 <b>13 个逐字节相同、1 个仍漂移</b>
 * （{@code total} 47→49，别的会话写的）。同一份代码、纯数据漂移，diff 照红。
 *
 * <p>⇒ 本类一律遵守两条共同纪律：
 * <ol>
 *   <li>比对范围按 {@code T260907R-} 前缀 / 本次夹具的轴值<b>收窄</b>；确需整体比时拆
 *       <b>结构层严格逐字</b>（{@link #assertStructureIdentical}）+ <b>数据层差异逐条可归因</b>两层。</li>
 *   <li><b>任何 diff 类断言之前先断言 HTTP 状态码</b>（{@link #requireStatusBeforeDiff}）——
 *       实证：一轮 session 过期，14 个端点全返 401，diff 忠实报出「14/14 全漂移」，
 *       差点产出「本任务把所有维护端点都搞坏了」的<b>假红</b>报告。</li>
 * </ol>
 */
@QuarkusTest
@DisplayName("AC-15/16/17 · 反向回归")
class ReverseRegressionAcTest extends Task260907RBase {

    /**
     * 🅱️ A/B 的「改动前」那一侧 —— 共享 dev server {@code localhost:8081}（master 代码，同一个库）。
     * 判别与纪律见 {@link MasterSideHttp}。
     */
    private final MasterSideHttp master = new MasterSideHttp();

    @AfterEach
    void tearDown() {
        cleanupOwnFixtures();
        cleanupOwnCostFixtures();
    }

    /**
     * 🚨 <b>A/B 的公共前置</b>：证明 B 侧活着、能登录、<b>且确实是另一份代码</b>。
     *
     * <p>不打最后这一枪，「两侧结果相同」有两个无法区分的解释：① 本次改动确实没波及；
     * ② <b>两侧根本是同一份代码</b>。后者让整条反向回归恒真。
     * 判别手段：master 侧的 {@code costing-approve/preview} <b>不含</b> {@code dsBackfill} 段
     * （{@code dsrecord} 包只在本 worktree 里），而本进程侧<b>必须含</b>。
     *
     * @return 用作判别靶子的那张 ds 新链路报价单（本任务自己的夹具）
     */
    private Fx openAbWindow(String label) {
        master.login();
        String mat = PREFIX + label + "-" + java.util.UUID.randomUUID().toString().substring(0, 6);
        Fx probe = newSubmittedOrder(label + "probe", mat, List.of(
                new EbomRow(1, PREFIX + "E1", "10.0", "1.1")));
        // B 侧：不该有 dsBackfill
        master.assertIsMasterSide(probe.quotationId().toString());
        // A 侧：必须有 dsBackfill（配对的阳性对照 —— 否则「B 没有」也可能只是端点坏了）
        JsonNode aData = ok(getPreview(probe.quotationId()), "A 侧判别预览");
        assertTrue(aData.has("dsBackfill"),
                "🚨 A/B 阳性对照失败：本进程（改动后）的预览响应里**没有** dsBackfill 段 ⇒ "
                        + "A 侧跑的不是本分支代码，A/B 两侧无法区分。data=" + aData);
        System.out.println("[A/B] 窗口已开：B 侧 " + MasterSideHttp.BASE + "（master，无 dsBackfill）"
                + " vs A 侧 进程内（本分支，有 dsBackfill）；判别靶子 = " + probe.quotationNo());
        return probe;
    }

    /**
     * <b>T-15②（AC-15 后半，2026-09-07 重写）</b>：对<b>同一张</b>存量老单走核价通过预览，
     * 其回填摘要（{@code versionedGroups}/{@code addedRows}/{@code deletedRows}/{@code changedRows}
     * 四个数字）改动前后相同。
     *
     * <p>⚠️ AC 原文明写：必须是<b>同一张单、同一时刻窗口内</b>的 A/B，
     * 🚫 不许拿历史记录的数字比 —— 历史数字是别的时刻别的库状态下采的，必然漂移。
     *
     * <h3>🅱️ B 侧从哪来（2026-09-07 打通）</h3>
     * B 侧 = {@code localhost:8081}（跑 master、连同一个库 {@code cpq_db_0724}）；
     * A 侧 = 本 {@code @QuarkusTest} 进程（跑本分支）。两侧在同一条用例里<b>背靠背</b>调用
     * ⇒ 「同一张单 + 同一时刻窗口 + 改动前后」三个条件同时成立。
     * 🚫 <b>不用历史记录、不用重采基线</b>（重采会退化成「基线=当前」的恒真断言）。
     *
     * <h3>🚨 三条不可省的守卫</h3>
     * <ol>
     *   <li><b>状态码先行</b>：两侧都必须 200 才允许进 diff（否则 401 会被 diff 忠实报成「全漂移」的假红）。</li>
     *   <li><b>基线非空</b>：{@code 0/0/0/0 vs 0/0/0/0} 恒相等恒通过。⇒ 必须先<b>找到</b>一张
     *       摘要非零的存量老单；一张都找不到就<b>硬失败并说清</b>，🚫 不许拿空基线报绿。</li>
     *   <li><b>老单确实不走新回填</b>：{@code dsBackfill.applicable == false}
     *       —— 这是「老链路」这个前提本身。</li>
     * </ol>
     *
     * <p>🔑 本条<b>只做预览（只读、无副作用、幂等，见 {@code api.md §1}）</b>。
     * 🚫 绝不对别人的存量单点「核价通过」—— 那会真的写 V6 主表，属 {@code CLAUDE.md} §3.2 的环境销毁。
     */
    @Test
    @DisplayName("T-15② · 同一张老单的回填摘要四个数字，改动前后相同（同时刻窗口 A/B）")
    void t15_legacyBackfillSummaryUnchangedOnSameOrder() {
        requireRecordLayer();
        openAbWindow("15");

        // ── 找一张**存量老单**（非 ds 原生模板）且其老回填摘要**非空**
        List<Object> candidates = col(
                "SELECT DISTINCT q.id::text FROM quotation q "
                        + "JOIN quotation_line_item li ON li.quotation_id = q.id "
                        + "WHERE li.template_id IS NOT NULL AND li.template_id <> '" + DS_TEMPLATE_ID + "' "
                        + "  AND q.quotation_number NOT LIKE '" + PREFIX + "%' "
                        + "ORDER BY 1");
        assertFixtureNonEmpty(candidates.size(),
                "存量老单候选（非 ds 原生模板、非本任务夹具）");

        String legacyId = null;
        JsonNode bSummary = null;
        List<String> tried = new java.util.ArrayList<>();
        // 按行数从多到少试（行数多的更可能有非零摘要），最多试 5 张，避免把整库扫一遍
        List<Object> ordered = col(
                "SELECT q.id::text FROM quotation q "
                        + "JOIN quotation_line_item li ON li.quotation_id = q.id "
                        + "WHERE li.template_id IS NOT NULL AND li.template_id <> '" + DS_TEMPLATE_ID + "' "
                        + "  AND q.quotation_number NOT LIKE '" + PREFIX + "%' "
                        + "GROUP BY q.id ORDER BY count(li.id) DESC LIMIT 5");
        for (Object o : ordered) {
            String id = String.valueOf(o);
            MasterSideHttp.R r = master.get("/api/cpq/quotations/" + id + "/costing-approve/preview");
            if (r.status() != 200) { tried.add(id + "→HTTP " + r.status()); continue; }
            JsonNode sum = r.data().path("summary");
            tried.add(id + "→" + sum);
            if (sum.path("versionedGroups").asInt(0) > 0) { legacyId = id; bSummary = sum; break; }
        }
        if (legacyId == null) {
            fail("⛔ 基线非空守卫未满足（**不是被测功能的结论**）：试过的存量老单其老回填摘要"
                    + "全部为 0/0/0/0 ⇒ A/B 会退化成「空 vs 空」，恒相等恒通过，"
                    + "那样的绿不构成任何证据（test.md §六）。试过：" + tried);
        }
        System.out.println("[T-15②] 选定存量老单 " + legacyId + "；B 侧（master）摘要 = " + bSummary);

        // ── A 侧（改动后，本分支进程内）—— 同一张单、紧接着调，中间不隔任何东西
        Response aResp = getPreview(java.util.UUID.fromString(legacyId));
        requireStatusBeforeDiff(aResp, 200, "T-15② A 侧（改动后）对存量老单 " + legacyId + " 的预览");
        JsonNode aData = ok(aResp, "T-15② A 侧预览");
        JsonNode aSummary = aData.path("summary");
        System.out.println("[T-15②] A 侧（本分支）摘要 = " + aSummary);

        // ── 守卫 3：这张确实是「老链路」的单 —— 判据是**新回填一个字节都不会写它**。
        JsonNode ds = aData.path("dsBackfill");
        assertEquals(0, ds.path("tables").size(),
                "T-15② 前置：存量老单不该被 ds 新回填触碰 ⇒ tables 应为空，实际 "
                        + ds.path("tables").size() + " 张。dsBackfill=" + ds);
        assertEquals(0, ds.path("summary").path("upgradedGroups").asInt(-1),
                "T-15② 前置：存量老单不该有任何组被升版，实际 "
                        + ds.path("summary").path("upgradedGroups") + "。dsBackfill=" + ds);
        assertApplicableInvariant(ds, "T-15② 的存量老单 " + legacyId);
        System.out.println("[T-15②] 老单前置成立：新回填 tables=0 / upgradedGroups=0；"
                + "applicable=" + ds.path("applicable")
                + "（按主线裁决一的新定义正确 —— nonParticipating 非空 ⇒ 有信息要呈现 ⇒ 恒 true）；"
                + " nonParticipating=" + ds.path("nonParticipating").size() + " 个组件");

        // ── 判据：四个数字逐字相同
        for (String k : List.of("versionedGroups", "addedRows", "deletedRows", "changedRows")) {
            assertEquals(bSummary.path(k).asLong(-1), aSummary.path(k).asLong(-2),
                    "🔑 AC-15②：同一张存量老单 " + legacyId + " 的老回填摘要 " + k
                            + " 改动前后应逐字相同。B 侧(master, " + MasterSideHttp.BASE + ")="
                            + bSummary.path(k) + "，A 侧(本分支, 进程内)=" + aSummary.path(k)
                            + "。完整 B=" + bSummary + " A=" + aSummary);
        }
        // 基线非空的二次确认（守卫 2 的落点）
        assertFixtureNonEmpty(aSummary.path("versionedGroups").asLong(0),
                "AC-15② 的比对基线 versionedGroups");
        System.out.println("[T-15②] ✅ 四个数字逐字相同，且 versionedGroups="
                + aSummary.path("versionedGroups") + " 非空 ⇒ 比对非空跑");
    }

    /**
     * <b>T-16（AC-16，2026-09-07 重写为两层）</b>：核价两套数据集
     * （{@code COST_BASIC} / {@code COST_DETAIL}）的<b>维护端保存</b>不被波及。
     *
     * <p>① <b>结构层严格</b>：两条路径的响应字段集、嵌套结构、HTTP 状态码，改动前后逐字相同；
     * ② <b>数据层可归因</b>：落库结果的差异必须逐条能归因到本次改动之外的原因，
     *    比对范围<b>收窄到本次夹具自己写入的行</b>（自造 {@code T260907R-} 轴值，🚫 不碰现网 11 个轴值）；
     * ③ <b>前置</b>：做任何 diff 之前先断言 HTTP 状态码。
     *
     * <h3>🔑 数据层判据为什么这样设计（不是「跑两次看一样」）</h3>
     * 两套核价与本段新增的核价通过回填<b>共用 {@code VersionedGroupWriter}</b> —— 那才是回归面。
     * ⇒ 用三态契约把它逼出来，每一步都能单独变红：
     * <ol>
     *   <li>B 侧(master) 建组 → 必须 {@code CREATED} v1；</li>
     *   <li>A 侧(本分支) 原样保存 → 必须 {@code UNCHANGED}，且该组落库内容
     *       <b>逐字未变</b>（{@code UNCHANGED} 契约：一行不写，连 {@code updated_at} 都不许动）；</li>
     *   <li>A 侧改值保存 → 必须 {@code UPGRADED} v2，旧版<b>完整</b>进 {@code _history}
     *       —— 🔑 这一步是<b>灵敏度对照</b>：没有它，第 2 步的 {@code UNCHANGED} 可能只是
     *       「写入器坏成永远不写」，那种坏法上面全都是绿的；</li>
     *   <li>B 侧(master) 回读 → 必须读到 A 侧写的 v2（两侧对同一份数据的读一致）。</li>
     * </ol>
     *
     * <p>⚠️ 与 {@code 报价侧加客户维度} 的 {@code AC-6} 是同一个回归面，已确认两条 AC 不互相污染。
     * <p>📌 <b>覆盖缺口（如实登记）</b>：AC-16 说的「导入」那一条路走
     * {@code POST /dataset/{ds}/import}（multipart {@code .xlsx}），本条<b>未覆盖</b> ——
     * 需要一份合规 Excel 夹具。🚫 不写成「已验证导入」。
     */
    @Test
    @DisplayName("T-16 · COST_BASIC/COST_DETAIL 结构层逐字相同 + 数据层差异可归因")
    void t16_costingDatasetsUnaffected() {
        requireRecordLayer();
        openAbWindow("16");

        for (String dataset : List.of("cost-basic", "cost-detail")) {
            String axis = COST_PREFIX + dataset.replace("-", "") + "-"
                    + java.util.UUID.randomUUID().toString().substring(0, 6);
            seedCostAxis(dataset, axis);
            String rowsPath = "/api/cpq/dataset/" + dataset + "/parts/" + axis
                    + "/sheets/MATERIAL_BOM/rows";
            String table = "ds_" + dataset.replace("-", "_") + "_material_bom";

            // ══ ① 结构层：只读端点两侧 jsonShape + 状态码逐字相同 ══
            for (String path : List.of(
                    "/api/cpq/dataset/" + dataset + "/sheets",
                    "/api/cpq/dataset/" + dataset + "/parts?page=0&size=3",
                    "/api/cpq/dataset/" + dataset + "/parts/" + axis + "/overview",
                    rowsPath)) {
                MasterSideHttp.R b = master.get(path);
                Response a = inProcGet(path);
                if (b.status() != a.statusCode()) {
                    fail("🚨 AC-16①：" + dataset + " " + path + " 的 HTTP 状态码改动前后不同 —— "
                            + "B 侧(master)=" + b.status() + " A 侧(本分支)=" + a.statusCode()
                            + "。🚫 状态码不一致时任何 diff 结论都不成立。B body="
                            + MasterSideHttp.trim(b.body()) + " A body=" + MasterSideHttp.trim(a.asString()));
                }
                requireStatusBeforeDiff(a, 200, "AC-16① " + dataset + " " + path);
                assertStructureIdentical(b.json(), json(a), "AC-16① " + dataset + " " + path);
            }
            System.out.println("[T-16][" + dataset + "] ① 结构层：4 个只读端点两侧逐字相同 ✅");

            // ══ ② 数据层（收窄到自造轴值 " + axis + "）══
            String payload = costRowsBody(null, "1.000000000000");
            MasterSideHttp.R created = master.put(rowsPath, payload);
            assertEquals(200, created.status(), "AC-16② B 侧建组应 200。body=" + MasterSideHttp.trim(created.body()));
            assertEquals("CREATED", created.data().path("result").asText(),
                    "AC-16② 前置：B 侧(master) 首次保存该轴值应判 CREATED。data=" + created.data());
            assertEquals(1, created.data().path("versionNo").asInt(-1), "AC-16② 前置：应为 v1");
            long baseRows = count("SELECT count(*) FROM " + table + " WHERE production_no = '" + axis + "'");
            assertFixtureNonEmpty(baseRows, dataset + " 夹具组 " + axis + " 的落库行数");
            String d1 = scopedDigest(table, "production_no", List.of(axis), "AC-16② " + dataset + " 建组后");

            // A 侧原样保存 → UNCHANGED 且逐字未变
            Response same = inProcPut(rowsPath, costRowsBody(1, "1.000000000000"));
            requireStatusBeforeDiff(same, 200, "AC-16② A 侧原样保存 " + dataset);
            assertEquals("UNCHANGED", ok(same, "AC-16② A 侧原样保存").path("result").asText(),
                    "🔑 AC-16②：A 侧(本分支) 原样保存同一组，三态判定应与 master 同源、判 UNCHANGED。"
                            + "data=" + ok(same, "x"));
            String d2 = scopedDigest(table, "production_no", List.of(axis), "AC-16② " + dataset + " 原样保存后");
            assertEquals(d1, d2,
                    "🔑 AC-16②：UNCHANGED 契约是**一行不写、连 updated_at 都不许动**，"
                            + "但 " + dataset + " 的组 " + axis + " 落库内容变了 ⇒ 本次改动波及了共用写入器。");

            // 🚨 灵敏度对照：改值必须真的升版（否则上面的 UNCHANGED 可能只是「永远不写」）
            Response upg = inProcPut(rowsPath, costRowsBody(1, "9.000000000000"));
            requireStatusBeforeDiff(upg, 200, "AC-16② A 侧改值保存 " + dataset);
            JsonNode upgData = ok(upg, "AC-16② A 侧改值保存");
            assertEquals("UPGRADED", upgData.path("result").asText(),
                    "🚨 AC-16② 灵敏度对照失败：改了值却没判 UPGRADED（" + upgData + "）⇒ "
                            + "写入器可能坏成「永远不写」，那种坏法下上面的 UNCHANGED 是恒真的空验证。");
            assertEquals(2, upgData.path("versionNo").asInt(-1), "AC-16②：改值后应升到 v2");
            long histRows = count("SELECT count(*) FROM " + table + "_history WHERE production_no = '"
                    + axis + "' AND version_no = 1");
            assertEquals(baseRows, histRows,
                    "AC-16②：升版后 v1 应**完整**进 _history（" + baseRows + " 行），实际 " + histRows);
            String d3 = scopedDigest(table, "production_no", List.of(axis), "AC-16② " + dataset + " 升版后");
            assertFalse(d1.equals(d3),
                    "🚨 AC-16② 灵敏度对照失败：判了 UPGRADED，但组内容 digest 逐字未变 ⇒ "
                            + "我的 digest 观测不到变化，上面 d1==d2 那条是空验证。");

            // B 侧回读：两侧对同一份数据的读一致
            MasterSideHttp.R back = master.get(rowsPath);
            assertEquals(200, back.status(), "AC-16② B 侧回读应 200。body=" + MasterSideHttp.trim(back.body()));
            assertEquals(2, back.data().path("versionNo").asInt(-1),
                    "AC-16②：B 侧(master) 回读应看到 A 侧写的 v2，实际 " + back.data().path("versionNo"));
            System.out.println("[T-16][" + dataset + "] ② 数据层：CREATED v1 → UNCHANGED(逐字未变) → "
                    + "UPGRADED v2(_history 完整 " + histRows + " 行) → B 侧回读 v2 ✅");
        }
    }

    // ═══════════════════════ T-17（AC-17）已迁出本类 ═══════════════════════
    //
    // 🕰️ 2026-09-07：T-17 需要选配链路的全套夹具（产品分类 / 材质配方 / 客户绑分类 / 铸料号 / 还原），
    //    而 `com.cpq.task260902.SelConfigAcTestBase`（736 行）里已有一份成熟实现。
    //    Java 单继承 ⇒ 复用它就不能同时继承 Task260907RBase
    //    ⇒ T-17 迁至 {@link SelectionChainRegressionAcTest}。
    //
    // 🚫 **本类刻意不再保留 T-17 的 pending 桩**（同 t03 去重的理由）：
    //    同一条 AC 被两个类覆盖，报告里一边写「⛔ 待接实现」、一边写结论，
    //    读报告的人无法判断哪个是真的，而 pending 那份**看起来像真实的覆盖缺口**。

    /**
     * 🚦 <b>主线裁决一（2026-09-07）落成的不变量</b>：
     * <blockquote>
     * {@code applicable == false} <b>当且仅当</b>「该单完全不涉及 ds_ 体系、没有任何信息要呈现」，
     * 即 {@code tables=[]} <b>且</b> {@code recordStale=null} <b>且</b> {@code nonParticipating=[]}
     * 三者同时成立。三者之一非空 ⇒ {@code applicable} 恒 {@code true}。
     * </blockquote>
     *
     * <h3>🕰️ 这条不变量的来历</h3>
     * 本用例首版按 {@code api.md:43} 的字面定义写「老单 ⇒ applicable=false」，实测返 {@code true}。
     * 上报后主线核原文，判定是 {@code api.md} 自身两条约定打架：
     * {@code :43} 的定义太窄，而 {@code :120}（「{@code recordStale} 非空时恒 true，
     * 否则警告恰好在最该出现时整块不渲染」）的原则是对的。
     * ⇒ <b>实现不改，改文档</b>；本方法把裁决后的定义钉成可执行判据。
     *
     * <p>🚫 <b>刻意写成双向</b>，不写成「老单恒 true」——
     * 后者依赖「当前这张老单恰好有 nonParticipating」这个<b>数据分布</b>，
     * 明天换一张 nonParticipating 为空的老单它就该是 false，而那条断言会假红。
     * 判据落在数据分布上时，它不是弱证据，是零证据。
     */
    private void assertApplicableInvariant(JsonNode ds, String what) {
        boolean nothingToShow = ds.path("tables").size() == 0
                && (ds.path("recordStale").isMissingNode() || ds.path("recordStale").isNull())
                && ds.path("nonParticipating").size() == 0;
        boolean applicable = ds.path("applicable").asBoolean(false);
        assertEquals(!nothingToShow, applicable,
                "🚦 裁决一不变量（双向）：" + what + " —— applicable 应 == 「有东西要呈现」。"
                        + "实测 tables=" + ds.path("tables").size()
                        + " recordStale=" + ds.path("recordStale")
                        + " nonParticipating=" + ds.path("nonParticipating").size()
                        + " ⇒ 三者全空=" + nothingToShow + "，期望 applicable=" + (!nothingToShow)
                        + "，实际 " + applicable + "。dsBackfill=" + ds);
    }

    // ═══════════════════════ 核价数据集夹具（T-16）═══════════════════════

    /** 🚨 {@code production_no} 上的本任务命名空间。清理只按它删，🚫 绝不按表清。 */
    private static final String COST_PREFIX = "T260907R-COST-";

    /** 在数据集的**物料表**登记轴值（导入/维护端都要求轴值已登记，见 main-api.md §6.8 R-8）。 */
    private void seedCostAxis(String dataset, String axis) {
        String matTable = "ds_" + dataset.replace("-", "_") + "_material";
        sqlSafe(matTable);
        inTx(() -> em.createNativeQuery("INSERT INTO " + matTable
                        + " (production_no,material_name,source,created_at) "
                        + "VALUES (:a,:n,'TEST',now()) ON CONFLICT (production_no) DO NOTHING")
                .setParameter("a", axis).setParameter("n", PREFIX + "核价夹具").executeUpdate());
        assertFixtureNonEmpty(count("SELECT count(*) FROM " + matTable + " WHERE production_no = '"
                + axis + "'"), "核价数据集 " + dataset + " 的轴值登记行");
    }

    /**
     * {@code PUT rows} 请求体。两套数据集的 {@code MATERIAL_BOM} 列集实测一致
     * （{@code operation_no} 是 SUBDIM 且非必填，🚫 刻意不填 —— 它挂主数据下拉，
     * 乱填会撞「主数据不存在」校验，那是<b>夹具错</b>不是被测缺陷）。
     */
    private String costRowsBody(Integer baseVersion, String componentQty) {
        return "{\"baseVersion\":" + (baseVersion == null ? "null" : baseVersion) + ",\"rows\":[{"
                + "\"item_seq\":10,\"component_no\":\"" + PREFIX + "C1\","
                + "\"usage_characteristic\":\"0\","
                + "\"component_qty\":\"" + componentQty + "\",\"component_qty_unit\":\"PCS\","
                + "\"base_qty\":\"1.000000000000\",\"base_qty_unit\":\"PCS\","
                + "\"material_loss_rate\":\"0.000000000000\","
                + "\"material_fixed_loss\":\"0.000000000000\","
                + "\"defect_rate\":\"0.000000000000\"}]}";
    }

    /**
     * 清掉本轮往 {@code ds_cost_*} 写的前缀化夹具行。
     * 表清单<b>动态派生</b>（🚫 不硬编码 —— 硬编码在「以后又多一张表」时会沉默地漏清），
     * 收窄条件 = {@code production_no LIKE 'T260907R-COST-%'}，<b>先 count 再删</b>。
     */
    private void cleanupOwnCostFixtures() {
        @SuppressWarnings("unchecked")
        List<String> tables = (List<String>) (List<?>) em.createNativeQuery(
                        "SELECT c.table_name FROM information_schema.columns c "
                                + "WHERE c.table_schema='public' AND c.table_name LIKE 'ds\\_cost\\_%' "
                                + "  AND c.column_name='production_no' ORDER BY 1").getResultList();
        if (tables.isEmpty()) return;
        inTx(() -> {
            for (String t : tables) {
                sqlSafe(t);
                long n = count("SELECT count(*) FROM " + t + " WHERE production_no LIKE '" + COST_PREFIX + "%'");
                if (n == 0) continue;
                em.createNativeQuery("DELETE FROM " + t + " WHERE production_no LIKE :p")
                        .setParameter("p", COST_PREFIX + "%").executeUpdate();
                System.out.println("[" + PREFIX + "cleanup] " + t + " 清掉本轮核价夹具 " + n + " 行");
            }
        });
    }

    // ═══════════════════════ A 侧（本进程 = 改动后）HTTP ═══════════════════════

    private Response inProcGet(String path) {
        return RestAssured.given().cookies(adminCookies()).when().get(path).thenReturn();
    }

    private Response inProcPut(String path, String body) {
        return RestAssured.given().cookies(adminCookies()).contentType(ContentType.JSON).body(body)
                .when().put(path).thenReturn();
    }

}
