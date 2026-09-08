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
 * <b>探针 · {@code ds_quote_material_bom} 这条路 + 「BOM 页签到底是不是树」</b>
 *
 * <h3>背景：我上一轮的前提被污染过</h3>
 * 我曾报「模板 13 个组件里没有物料BOM ⇒ {@code ds_quote_material_bom} 走不通页签这条路」，
 * 并据此把 AC 用例改挂 {@code ds_quote_element_bom}。
 * 实为<b>库里有两个同名模板</b>（都叫「报价模板 · ds 原生 v1.0」、都 PUBLISHED）：
 * <pre>
 *   df379593… 11:38  13 组件   ← 我当时拿到的（旧）
 *   875a5c9f… 13:09  14 组件   ← 多一个 T260907-物料BOM
 * </pre>
 * ⚠️ <b>所以夹具一律按 UUID 取模板，🚫 不要按名字</b> —— 按名字查会返两行。
 *
 * <h3>本类要回答两个问题，🚫 都不靠推断</h3>
 * <ol>
 *   <li><b>P-1</b>：新模板 + 物料BOM 组件能否把 {@code ds_quote_material_bom_record} 写出来？</li>
 *   <li><b>P-2</b>：这个 BOM 页签<b>是不是树</b>（闭包会不会把子件所属其他组的行带进来）？
 *       🚨 {@code semantic_tab_view} 的 {@code switches} 实测是 <b>{@code &#123;&#125;}</b>、且表里没有
 *       {@code semantic} 列 ⇒ <b>「是不是树」没有声明式判据，只能实测</b>。
 *       🚫 不许因为 {@code tab_type='BOM'} 就断定它是树。</li>
 * </ol>
 *
 * <p>🔑 <b>P-2 的实测设计</b>：给父件 A 造一行、其「投入料号」指向子件 B，并让 B <b>自己也有一组</b> BOM 行。
 * 然后看这张单的 {@code _record}：
 * <ul>
 *   <li>只出现 A 的行 ⇒ <b>平铺</b>，AC-13 的树维度仍未覆盖；</li>
 *   <li>同时出现 B 的行 ⇒ <b>闭包/树</b>，AC-13 的树维度这才谈得上。</li>
 * </ul>
 * 本类<b>不做通过/失败判定</b>（探针），把实际结果打出来交主线裁。
 */
@QuarkusTest
@DisplayName("探针 · material_bom 通路 + BOM 页签是否为树")
class MaterialBomProbeTest extends Task260907RBase {

    /** 🚨 按 UUID 取，🚫 不按名字（库里两个同名模板）。 */
    private static final UUID TEMPLATE_V2 = UUID.fromString("875a5c9f-3579-4c9d-b600-1791ff25afa7");
    private static final UUID COMP_MBOM = UUID.fromString("f6d51727-bd1e-4cc3-a839-94208cc12f41");
    private static final String TAB_MBOM = "T260907-物料BOM";
    private static final String MBOM = "ds_quote_material_bom";

    @AfterEach
    void tearDown() {
        cleanupOwnFixtures();
    }

    @Test
    @DisplayName("P-1/P-2 · material_bom 能否写出 _record；BOM 页签是否闭包展开")
    void probeMaterialBomAndTreeShape() {
        requireRecordLayer();

        // 前置：两个模板同名，确认我取的是 14 组件那个
        long comps = count("SELECT count(*) FROM template_component WHERE template_id = '" + TEMPLATE_V2 + "'");
        assertEquals(14L, comps, "前置：应取到 14 组件的新模板，实际 " + comps
                + " ⇒ 可能又拿到旧模板（两个同名，务必按 UUID）");

        String parent = PREFIX + "P-" + UUID.randomUUID().toString().substring(0, 6);
        String child = PREFIX + "C-" + UUID.randomUUID().toString().substring(0, 6);

        // 🚨 2026-09-07：先建客户再灌 BOM —— 上游 V425 让 customer_no 变 NOT NULL，
        //    且轴已是复合 (customer_no, material_no)，子件组必须与父件单同客户才在同一个轴上。
        Fx fx = newFixture("PROBE");
        // 🚨 内联 INSERT 不经基类入口 ⇒ 必须显式登记，否则 @AfterEach 认不出它、留残留。
        //    （🚫 清理已改为「只删本进程登记过的轴值」，不再按前缀全清 —— 后端代理同期共用该前缀。）
        trackAxis(parent);
        trackAxis(child);

        // ── 子件 B 自己有一组 BOM（2 行）—— 闭包若成立，它们会被带进父件的展开
        inTx(() -> {
            for (int i = 1; i <= 2; i++) {
                em.createNativeQuery(
                                "INSERT INTO " + MBOM + " (customer_no,material_no,item_seq,input_material_no,component_qty,"
                                        + "version_no,row_fingerprint,source,created_at) "
                                        + "VALUES (:cn,:mn,:seq,:in,CAST(:q AS numeric),1,:fp,'TEST',now())")
                        .setParameter("cn", fx.customerNo())
                        .setParameter("mn", child).setParameter("seq", i)
                        .setParameter("in", PREFIX + "LEAF" + i).setParameter("q", String.valueOf(i * 5))
                        .setParameter("fp", contentFingerprint(child, i, PREFIX + "LEAF" + i))
                        .executeUpdate();
            }
        });
        assertFixtureNonEmpty(count("SELECT count(*) FROM " + MBOM + " WHERE material_no = '" + child + "'"),
                "子件 B 自己的 BOM 组行数");

        // ── 父件 A：一行，投入料号 = 子件 B
        Response r = putDraftMbom(fx, parent, child);
        requireStatusBeforeDiff(r, 200, "P-1 saveDraft（物料BOM 页签）");

        long recRows = count("SELECT count(*) FROM " + MBOM + "_record WHERE quotation_id = '"
                + fx.quotationId() + "'");
        System.out.println("[P-1] " + MBOM + "_record 本单行数 = " + recRows
                + "  ⇒ " + (recRows > 0 ? "✅ material_bom 这条路通了" : "❌ 仍写不出来"));

        if (recRows == 0) {
            System.out.println("[P-2] ⛔ 无法判定树形态：_record 为空，闭包与否无从观察。");
            return;
        }

        // ── P-2：_record 里出现了哪些轴值 / 哪些投入料号
        List<Object> axes = col("SELECT DISTINCT material_no FROM " + MBOM + "_record "
                + "WHERE quotation_id = '" + fx.quotationId() + "' ORDER BY 1");
        List<Object> inputs = col("SELECT DISTINCT coalesce(input_material_no,'~') FROM " + MBOM + "_record "
                + "WHERE quotation_id = '" + fx.quotationId() + "' ORDER BY 1");
        System.out.println("[P-2] _record 里的轴值 = " + axes);
        System.out.println("[P-2] _record 里的投入料号 = " + inputs);

        boolean childPulledIn = axes.stream().anyMatch(a -> child.equals(String.valueOf(a)))
                || inputs.stream().anyMatch(i -> String.valueOf(i).startsWith(PREFIX + "LEAF"));
        System.out.println("[P-2] ⇒ " + (childPulledIn
                ? "🌳 **闭包/树**：子件 B 所属的行被带进了父件的展开 ⇒ AC-13 的树维度这才谈得上覆盖"
                : "▭ **平铺**：只出现父件 A 自己表征的行 ⇒ AC-13 的树维度**仍未覆盖**，"
                  + "「有 BOM 组件」≠「树能渲染」，标注维持「⚠️ 未完全覆盖」"));

        // 预览侧同样打一份，便于主线交叉核对
        Response sub = submit(fx);
        if (sub.statusCode() == 200) {
            Response pv = getPreview(fx.quotationId());
            if (pv.statusCode() == 200) {
                JsonNode ds = dsBackfill(ok(pv, "P-2 预览"));
                System.out.println("[P-2] 预览 summary = " + ds.path("summary"));
                for (JsonNode t : ds.path("tables")) {
                    System.out.println("[P-2] 表 " + t.path("tableName").asText()
                            + " 的组轴值 = " + t.path("groups").findValuesAsText("axisValue"));
                }
            }
        }
    }

    private Response putDraftMbom(Fx fx, String parent, String child) {
        String rowData = "[{"
                + "\"销售料号\":\"" + parent + "\",\"项次\":\"1\",\"投入料号\":\"" + child + "\","
                + "\"单重\":\"1.5\",\"产出料号类型\":\"BOM\",\"组成数量\":\"2\","
                + "\"材料毛重\":\"3.0\",\"材料净重\":\"2.8\",\"重量单位\":\"kg\","
                + "\"材料占比（%）\":\"50\",\"损耗率（%）\":\"1\",\"不良率（%）\":\"0.5\"}]";
        String body = "{\"baseVersion\":0,\"added\":[{"
                + "\"id\":null,\"tempId\":\"" + PREFIX + "tm\","
                + "\"templateId\":\"" + TEMPLATE_V2 + "\","
                + "\"sortOrder\":0,\"compositeType\":\"SIMPLE\","
                + "\"productPartNo\":\"" + parent + "\",\"annualVolume\":1,"
                + "\"componentData\":[{\"componentId\":\"" + COMP_MBOM + "\","
                + "\"tabName\":\"" + TAB_MBOM + "\","
                + "\"rowData\":" + jsonStr(rowData) + ",\"sortOrder\":0}]}],"
                + "\"modified\":[],\"removed\":[]}";
        return RestAssured.given().cookies(adminCookies()).contentType(ContentType.JSON).body(body)
                .when().put("/api/cpq/quotations/" + fx.quotationId() + "/draft").thenReturn();
    }
}
