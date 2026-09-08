package com.cpq.task260907r;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🚨 <b>夹具自检 —— 在依赖夹具的 19 条用例之前先证明「夹具本身能把 {@code _record} 造出来」。</b>
 *
 * <h3>为什么必须先有这一条</h3>
 * 2026-09-07 实测：全库 13 张 {@code _record} <b>合计 0 行</b>。
 * 若直接去跑 AC-2/AC-6/AC-13…，一旦断言失败会有<b>两个无法区分</b>的解释：
 * <ol>
 *   <li>我的夹具建错了（模板/组件/页签没绑上 ⇒ 压根没触发写入）；</li>
 *   <li>{@code _record} 写入功能本身没接上。</li>
 * </ol>
 * 把二者混在一起，就会出现最贵的那类误报：<b>把「功能没实现」报成「用例没写对」，或者反过来</b>。
 *
 * <p>⇒ 本类只做一件事：<b>用最短路径把 `_record` 写出来</b>。
 * 它绿 = 夹具可信，后面 19 条的红才能干净归因到实现；
 * 它红 = 先修夹具或先报缺陷，<b>🚫 不许带着它去跑那 19 条</b>。
 *
 * <h3>为什么 8081 上永远验不了这件事</h3>
 * {@code quotation/service/dsrecord/} 这个包<b>只在 worktree 里且未提交</b>，
 * 而 8081 跑的是主工作区代码 ⇒ <b>8081 必然写不出 {@code _record}</b>，与夹具对不对无关。
 * ⇒ {@code _record} <b>只能</b>在 worktree 的 {@code @QuarkusTest} 里被驱动（那里跑的才是被测代码）。
 *
 * <h3>⚠️ 为什么没用 {@code inRollback}</h3>
 * 主线建议用 {@code inRollback}（构造性零残留）。<b>这里用不了</b>：
 * 夹具要经 <b>HTTP</b>（{@code PUT /draft}）驱动，而 HTTP 请求跑在<b>自己的事务</b>里，
 * <b>看不见未提交的报价单</b> —— 用 {@code inRollback} 包住的话，saveDraft 会报「报价单不存在」。
 * ⇒ 只能走 committed 夹具 + {@code @AfterEach} 前缀清理
 * （{@link Task260907RBase#cleanupOwnDatasetRows} 已按前缀 + {@code source='TEST'} 双重收窄，实测残留 0）。
 */
@QuarkusTest
@DisplayName("🚨 夹具自检 · 证明 _record 真能被造出来")
class RecordFixtureSelfTest extends Task260907RBase {

    /** 报价模板 · ds 原生 v1.0（PUBLISHED，13 组件，锚 13 张 ds_quote_*）。 */
    static final UUID TEMPLATE_ID = UUID.fromString("df379593-8f9c-4974-b90f-1429b3349869");
    /** T260907-物料与元素BOM ⇒ 锚 ds_quote_element_bom（主线已由后端独立复刻 SQL 验过映射）。 */
    static final UUID COMP_ELEMENT_BOM = UUID.fromString("196aadee-b89f-4f81-984b-4c6747b59149");
    static final String TAB_ELEMENT_BOM = "T260907-物料与元素BOM";

    @AfterEach
    void tearDown() {
        cleanupOwnFixtures();
    }

    @Test
    @DisplayName("FX-01 · 建单 → saveDraft → ds_quote_element_bom_record 出现本单的行")
    void fx01_saveDraftWritesRecord() {
        // ── 前置：模板必须还在且 PUBLISHED（🚨 它是共享库里的对象，别的会话可能改动/删除）
        Object tplStatus = scalar("SELECT status FROM template WHERE id = '" + TEMPLATE_ID + "'");
        assertNotNull(tplStatus,
                "前置未满足：模板 " + TEMPLATE_ID + "（报价模板 · ds 原生 v1.0）不存在了。"
                        + "这是**环境前置**，不是被测功能的结论。");
        assertEquals("PUBLISHED", String.valueOf(tplStatus),
                "前置未满足：模板状态应为 PUBLISHED，实际 " + tplStatus);
        long comps = count("SELECT count(*) FROM template_component WHERE template_id = '" + TEMPLATE_ID + "'");
        assertFixtureNonEmpty(comps, "模板挂载的组件数");

        Fx fx = newFixture("FX01");
        String partNo = PREFIX + "E-" + fx.quotationId().toString().substring(0, 6);
        // 🚨 必须登记：cleanupOwnDatasetRows() **只删 trackAxis 登记过的轴值**，
        //    而 _record 表外键数 = 0 ⇒ deleteReferencingRows()（清单从 pg_constraint 派生）
        //    永远够不到它 ⇒ 不登记 = 每跑一轮在库里永久留一行 _record 孤儿（父单已删）。
        //    2026-09-08 实证：本类三条用例各漏 1 行，E/E2/E3 三族已累积到各 7 行。
        trackAxis(partNo);

        // ── 阳性对照：动手前，本单在 _record 上必须是 0 行。
        //    不先证明这一点，「跑完有行了」可能是别的会话/上一轮留下的。
        long before = count("SELECT count(*) FROM ds_quote_element_bom_record WHERE quotation_id = '"
                + fx.quotationId() + "'");
        assertEquals(0L, before, "夹具起点不干净：本单在 _record 上已有 " + before + " 行");

        // ── 驱动：saveDraft 三数组协议（added），契约见既有 Task260901Support#addedLine
        String rowData = "[{"
                + "\"销售料号\":\"" + partNo + "\","
                + "\"材质料号\":\"" + PREFIX + "MAT\","
                + "\"项次\":\"1\","
                + "\"元素\":\"" + PREFIX + "EL\","
                + "\"组成含量（%）\":\"50\","
                + "\"损耗率%\":\"1\","
                + "\"毛用量\":\"2.5\","
                + "\"毛用量单位\":\"kg\","
                + "\"净用量\":\"2.4\","
                + "\"净用量单位\":\"kg\","
                + "\"回收折扣(%)\":\"10\","
                + "\"回收量\":\"0.1\""
                + "}]";
        Response r = putDraft(fx.quotationId(), draftBodyAdded(partNo, rowData));

        // 🚨 共同纪律②：任何结论之前先断言状态码。
        //    状态码不对就是**用例环境失败**，🚫 不许让它流进「_record 没写出来」这个结论。
        requireStatusBeforeDiff(r, 200, "FX-01 saveDraft PUT /draft");

        // ── 🔬 中间态诊断：saveDraft 到底把我的 rowData 落到哪一路了？
        //    依据 RECORD.md 的既有教训：「提交期按行读真实值要走 componentData 两路
        //    （snapshot_rows + row_data）」—— 若投影侧只读其中一路，而 saveDraft 只落了另一路，
        //    就会出现「绑定命中 1，却 sheets=0」这种**中间断链**。
        //    ⇒ 把两路都打出来，让「rows=0」有据可归因，而不是停在「反正没写」。
        for (Object[] row : rows("SELECT cd.id, cd.component_id, "
                + "  coalesce(length(cd.row_data::text),0), "
                + "  coalesce(length(cd.snapshot_rows::text),0), "
                + "  left(coalesce(cd.row_data::text,'<NULL>'), 160), "
                + "  left(coalesce(cd.snapshot_rows::text,'<NULL>'), 160) "
                + "FROM quotation_line_component_data cd "
                + "JOIN quotation_line_item li ON li.id = cd.line_item_id "
                + "WHERE li.quotation_id = '" + fx.quotationId() + "'")) {
            System.out.println("[FX-01·诊断] component_data id=" + row[0] + " componentId=" + row[1]
                    + "\n    row_data 长度=" + row[2] + "  snapshot_rows 长度=" + row[3]
                    + "\n    row_data      = " + row[4]
                    + "\n    snapshot_rows = " + row[5]);
        }
        System.out.println("[FX-01·诊断] line_item 数="
                + count("SELECT count(*) FROM quotation_line_item WHERE quotation_id = '" + fx.quotationId() + "'")
                + "；其 product_part_no_snapshot="
                + col("SELECT coalesce(product_part_no_snapshot,'<NULL>') FROM quotation_line_item "
                + "WHERE quotation_id = '" + fx.quotationId() + "'"));

        // ── 结论：本单在 _record 上应出现行
        long after = count("SELECT count(*) FROM ds_quote_element_bom_record WHERE quotation_id = '"
                + fx.quotationId() + "'");
        System.out.println("[FX-01] saveDraft 后 ds_quote_element_bom_record 本单行数 = " + after
                + "；全库 _record 合计 = " + totalRecordRows());

        assertTrue(after > 0,
                "🚨 夹具自检失败：saveDraft 返 200，但 ds_quote_element_bom_record 没有本单（quotation_id="
                        + fx.quotationId() + "）的任何行。\n"
                        + "⇒ 二义已被排除：本用例跑在 worktree 的 @QuarkusTest 里（被测代码就在这儿，"
                        + "不是 8081 的主仓代码），且模板绑定已由后端独立复刻 SQL 验过 ⇒ "
                        + "**这指向 _record 写入未接上，属产品侧**，而不是夹具建错。\n"
                        + "（若要排除最后一点夹具嫌疑：检查 saveDraft 是否真的建出了 line item ——"
                        + " 本单 line_item 数 = " + count("SELECT count(*) FROM quotation_line_item "
                        + "WHERE quotation_id = '" + fx.quotationId() + "'") + "）");
    }

    /**
     * <b>FX-02 · 判别实验：把 {@code snapshot_rows} 填上，投影是否就出来了？</b>
     *
     * <p>FX-01 实测到的状态是：绑定<b>命中 1</b>、{@code row_data} 有完整 12 字段含轴值、
     * {@code snapshot_rows} 为 <b>NULL</b>、投影产出 {@code sheets=0 axes=0 rows=0}、<b>无异常</b>。
     *
     * <p>本条把唯一的差异项（{@code snapshot_rows}）补上，再触发一次同步：
     * <ul>
     *   <li><b>写出来了</b> ⇒ 证明投影取的是 {@code snapshot_rows} 这一路，
     *       而 {@code saveDraft} 对本形态只落了 {@code row_data} ⇒ <b>两路没对齐</b>；</li>
     *   <li><b>还是 0</b> ⇒ 缺口不在这里，得换方向查（结论同样有效，只是排除了一个候选）。</li>
     * </ul>
     *
     * <p>🔑 <b>先证明干预生效</b>：填完先断言 {@code snapshot_rows} 真的非空了，
     * 否则「还是 0」可能只是因为我压根没改上（{@code RECORD.md} task-260825 的教训）。
     *
     * <p>⚠️ 这是**诊断用例**，不是 AC 用例 —— 它服务于「让 FX-01 的红可归因」，
     * 🚫 不进 AC 追溯矩阵。
     */
    @Test
    @DisplayName("FX-02 · 判别：补上 snapshot_rows 后投影是否产出（定位 rows=0 的缺口）")
    void fx02_snapshotRowsIsTheMissingInput() {
        Fx fx = newFixture("FX02");
        String partNo = PREFIX + "E2-" + fx.quotationId().toString().substring(0, 6);
        // 🚨 必须登记：cleanupOwnDatasetRows() **只删 trackAxis 登记过的轴值**，
        //    而 _record 表外键数 = 0 ⇒ deleteReferencingRows()（清单从 pg_constraint 派生）
        //    永远够不到它 ⇒ 不登记 = 每跑一轮在库里永久留一行 _record 孤儿（父单已删）。
        //    2026-09-08 实证：本类三条用例各漏 1 行，E/E2/E3 三族已累积到各 7 行。
        trackAxis(partNo);
        String rowData = elementBomRowData(partNo);

        Response r1 = putDraft(fx.quotationId(), draftBodyAdded(partNo, rowData));
        requireStatusBeforeDiff(r1, 200, "FX-02 首次 saveDraft");

        Object cdId = scalar("SELECT cd.id FROM quotation_line_component_data cd "
                + "JOIN quotation_line_item li ON li.id = cd.line_item_id "
                + "WHERE li.quotation_id = '" + fx.quotationId() + "' LIMIT 1");
        assertNotNull(cdId, "前置：本单应有 component_data 行，实际没有 ⇒ 夹具没建成");

        long recAfterFirst = count("SELECT count(*) FROM ds_quote_element_bom_record "
                + "WHERE quotation_id = '" + fx.quotationId() + "'");

        // ── 干预：把 row_data 原样拷进 snapshot_rows（命中面 = 我自己的 1 行）
        inTx(() -> em.createNativeQuery(
                        "UPDATE quotation_line_component_data SET snapshot_rows = row_data WHERE id = :id")
                .setParameter("id", cdId).executeUpdate());

        // 🚨 先证明干预生效，再看结果
        long snapLen = count("SELECT coalesce(length(snapshot_rows::text),0) "
                + "FROM quotation_line_component_data WHERE id = '" + cdId + "'");
        assertFixtureNonEmpty(snapLen,
                "干预未生效：snapshot_rows 仍为空 ⇒ 下面无论出不出行都不构成证据");
        System.out.println("[FX-02] 干预已生效：snapshot_rows 长度 = " + snapLen);

        // ── 再触发一次同步（改一个值，走 modified 让 hasLinePayload 成立）
        Response r2 = putDraft(fx.quotationId(), draftBodyModified(fx, partNo));
        requireStatusBeforeDiff(r2, 200, "FX-02 二次 saveDraft");

        long recAfterSecond = count("SELECT count(*) FROM ds_quote_element_bom_record "
                + "WHERE quotation_id = '" + fx.quotationId() + "'");
        System.out.println("[FX-02] 结论数据：补 snapshot_rows 前 _record=" + recAfterFirst
                + "，补后再同步 _record=" + recAfterSecond);
        System.out.println("[FX-02] ⇒ " + (recAfterSecond > recAfterFirst
                ? "补上 snapshot_rows 后投影**产出了行** ⇒ 缺口在「saveDraft 只落 row_data、投影读 snapshot_rows」这条断链上"
                : "补上 snapshot_rows 后投影**仍为 0** ⇒ 缺口不在 snapshot_rows，已排除该候选，需换方向"));

        // 🚫 本条**不做通过/失败判定** —— 它是诊断，两种结果都是有效信息。
        //    只保证它不会以「绿」的形态掩盖 FX-01 的红：FX-01 才是判据。
    }

    /**
     * <b>FX-03 · 链路探路：saveDraft → submit → preview，看 `dsBackfill` 能走到哪一步。</b>
     *
     * <p>在把 3 个 AC 测试类从 {@code ds_quote_material_bom} 改挂到 {@code ds_quote_element_bom} 之前，
     * 先花一次运行确认<b>整条链路真的通</b>。🚫 不先探路就大改，改完跑不通会分不清
     * 「改错了」还是「链路本来就不通」——又是一次不可归因的红。
     *
     * <p>本条<b>不做通过/失败判定</b>，只把每一跳的实际结果打出来。
     */
    @Test
    @DisplayName("FX-03 · 探路：saveDraft → submit → preview 的 dsBackfill 实际形状")
    void fx03_probeFullChain() {
        Fx fx = newFixture("FX03");
        String partNo = PREFIX + "E3-" + fx.quotationId().toString().substring(0, 6);
        // 🚨 必须登记：cleanupOwnDatasetRows() **只删 trackAxis 登记过的轴值**，
        //    而 _record 表外键数 = 0 ⇒ deleteReferencingRows()（清单从 pg_constraint 派生）
        //    永远够不到它 ⇒ 不登记 = 每跑一轮在库里永久留一行 _record 孤儿（父单已删）。
        //    2026-09-08 实证：本类三条用例各漏 1 行，E/E2/E3 三族已累积到各 7 行。
        trackAxis(partNo);

        Response r1 = putDraft(fx.quotationId(), draftBodyAdded(partNo, elementBomRowData(partNo)));
        requireStatusBeforeDiff(r1, 200, "FX-03 saveDraft");
        System.out.println("[FX-03] ① saveDraft 200；本单 _record 行数 = "
                + count("SELECT count(*) FROM ds_quote_element_bom_record WHERE quotation_id = '"
                + fx.quotationId() + "'"));

        Response r2 = RestAssured.given().cookies(adminCookies()).contentType(ContentType.JSON)
                .when().post("/api/cpq/quotations/" + fx.quotationId() + "/submit").thenReturn();
        System.out.println("[FX-03] ② submit → HTTP " + r2.statusCode()
                + "；状态 = " + scalar("SELECT status FROM quotation WHERE id = '" + fx.quotationId() + "'")
                + (r2.statusCode() == 200 ? "" : "；body=" + left(r2.asString(), 300)));

        Response r3 = getPreview(fx.quotationId());
        System.out.println("[FX-03] ③ preview → HTTP " + r3.statusCode());
        if (r3.statusCode() == 200) {
            JsonNode ds = json(r3).path("data").path("dsBackfill");
            System.out.println("[FX-03]    dsBackfill.applicable=" + ds.path("applicable")
                    + " confirmRequired=" + ds.path("confirmRequired"));
            System.out.println("[FX-03]    summary=" + ds.path("summary"));
            System.out.println("[FX-03]    tables 数=" + ds.path("tables").size()
                    + " nonParticipating 数=" + ds.path("nonParticipating").size());
            if (ds.path("tables").size() > 0) {
                System.out.println("[FX-03]    第一个 group=" + left(ds.path("tables").get(0)
                        .path("groups").toString(), 700));
            }
            System.out.println("[FX-03]    nonParticipating=" + left(ds.path("nonParticipating").toString(), 400));
        } else {
            System.out.println("[FX-03]    body=" + left(r3.asString(), 400));
        }
    }

    private static String left(String s, int n) {
        return s == null ? "<null>" : (s.length() <= n ? s : s.substring(0, n) + "…");
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private String elementBomRowData(String partNo) {
        return "[{"
                + "\"销售料号\":\"" + partNo + "\",\"材质料号\":\"" + PREFIX + "MAT\",\"项次\":\"1\","
                + "\"元素\":\"" + PREFIX + "EL\",\"组成含量（%）\":\"50\",\"损耗率%\":\"1\","
                + "\"毛用量\":\"2.5\",\"毛用量单位\":\"kg\",\"净用量\":\"2.4\",\"净用量单位\":\"kg\","
                + "\"回收折扣(%)\":\"10\",\"回收量\":\"0.1\"}]";
    }

    /** 二次保存：以 modified 复用既有 line item，改一个数值列，触发 hasLinePayload。 */
    private String draftBodyModified(Fx fx, String partNo) {
        Object liId = scalar("SELECT id FROM quotation_line_item WHERE quotation_id = '"
                + fx.quotationId() + "' LIMIT 1");
        assertNotNull(liId, "二次保存前置：找不到 line item");
        String rowData = elementBomRowData(partNo).replace("\"毛用量\":\"2.5\"", "\"毛用量\":\"9.9\"");
        String modified = "[{"
                + "\"id\":\"" + liId + "\",\"templateId\":\"" + TEMPLATE_ID + "\","
                + "\"sortOrder\":0,\"compositeType\":\"SIMPLE\","
                + "\"componentData\":[{"
                + "\"componentId\":\"" + COMP_ELEMENT_BOM + "\","
                + "\"tabName\":\"" + TAB_ELEMENT_BOM + "\","
                + "\"rowData\":" + quoteJson(rowData) + ",\"sortOrder\":0}]}]";
        long ver = count("SELECT coalesce(user_data_version,0) FROM quotation WHERE id = '"
                + fx.quotationId() + "'");
        return "{\"baseVersion\":" + ver + ",\"added\":[],\"modified\":" + modified + ",\"removed\":[]}";
    }

    private long totalRecordRows() {
        Object v = scalar("SELECT coalesce(sum(n),0) FROM (SELECT (xpath('/row/c/text()', "
                + "query_to_xml(format('SELECT count(*) c FROM %I', table_name), false, true, '')))[1]::text::int n "
                + "FROM information_schema.tables WHERE table_schema='public' "
                + "  AND table_name LIKE 'ds\\_quote\\_%\\_record') s");
        return v == null ? 0L : ((Number) v).longValue();
    }

    private Response putDraft(UUID quotationId, String jsonBody) {
        return RestAssured.given().cookies(adminCookies()).contentType(ContentType.JSON).body(jsonBody)
                .when().put("/api/cpq/quotations/" + quotationId + "/draft").thenReturn();
    }

    /** 三数组协议的请求体（只带 added 一行）。形状照既有 {@code Task260901Support#addedLine}。 */
    private String draftBodyAdded(String partNo, String rowDataJson) {
        String added = "[{"
                + "\"id\":null,\"tempId\":\"" + PREFIX + "t1\","
                + "\"templateId\":\"" + TEMPLATE_ID + "\","
                + "\"sortOrder\":0,\"compositeType\":\"SIMPLE\","
                + "\"productPartNo\":\"" + partNo + "\",\"annualVolume\":1,"
                + "\"componentData\":[{"
                + "\"componentId\":\"" + COMP_ELEMENT_BOM + "\","
                + "\"tabName\":\"" + TAB_ELEMENT_BOM + "\","
                + "\"rowData\":" + quoteJson(rowDataJson) + ",\"sortOrder\":0}]}]";
        return "{\"baseVersion\":0,\"added\":" + added + ",\"modified\":[],\"removed\":[]}";
    }

    /** {@code rowData} 在既有契约里是**字符串**，故把 JSON 数组整体转义成 JSON 字符串。 */
    private static String quoteJson(String raw) {
        try {
            return MAPPER.writeValueAsString(raw);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
