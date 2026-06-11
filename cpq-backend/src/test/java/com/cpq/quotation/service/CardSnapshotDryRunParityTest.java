package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 🔴 命门0 对拍：token 试算（草稿公式注入）逐行值 == 真实卡片渲染（持久化公式）逐行值。
 *
 * <p><b>两条真实路径</b>（差异点仅在「草稿公式/草稿行键注入」vs「持久化公式/持久化行键」，
 * 两者最终都走同一渲染装配 {@link CardSnapshotService#assembleTabsWithFormulaResults}）：
 * <ul>
 *   <li>试算路径：{@link CardSnapshotService#dryRunTokenRowsCore}（草稿公式 {@code __dryrun__} 注入 + 草稿行键覆盖）。</li>
 *   <li>渲染路径：把同一公式以持久化方式写进宿主 component.formulas + snapshot 宿主 tab，
 *       走 {@link CardSnapshotService#assembleTabsWithFormulaResultsForTest}（与 refreshQuoteCardValues 同一装配内核），
 *       取宿主 tab formulaResults。</li>
 * </ul>
 *
 * <p>两路喂<b>完全相同的 baseRows</b>（确定性注入），证明命门0：试算==渲染。
 *
 * <p>造数（照 {@link CardSnapshotCrossTabTest} 范式 + 落库 component.row_key_fields 供装配读持久化行键）：
 * <ul>
 *   <li>宿主投料 tab：rowKeyFields=[子件]，字段含 金额(INPUT_NUMBER)；baseRow 螺丝 金额=10。</li>
 *   <li>细 source 加工 tab：rowKeyFields=[子件,工序]，字段 工时(INPUT_NUMBER)；baseRow 螺丝/钻 工时=3、螺丝/铣 工时=5。</li>
 * </ul>
 * <p>草稿公式 = {@code 金额 * SUM([加工.工时])} → 螺丝行 = 10 * (3+5) = 80。
 *
 * <p><b>为何用 dryRunTokenRowsCore + assembleForTest 而非 expand 路径走 refreshQuoteCardValues</b>：
 * 真实 driver expand 经 readonly 数据源连接池查 SQL 视图，与 {@code @TestTransaction}（未提交 DDL/数据
 * 对 readonly 连接不可见）冲突，无法在自包含单测里造确定性 driver 行。故注入相同 baseRows 到两路装配，
 * 对拍「草稿注入 vs 持久化」这一<b>真实分歧点</b>（baseRows/editRows 装配完全同源，差异仅在公式与行键来源）。
 */
@QuarkusTest
class CardSnapshotDryRunParityTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Inject CardSnapshotService cardSnapshotService;
    @Inject EntityManager em;

    private ObjectNode field(String name, String type) {
        ObjectNode f = M.createObjectNode();
        f.put("name", name);
        f.put("field_type", type);
        return f;
    }

    /** 草稿/落库公式 token：金额 * SUM([加工.工时])（source = 加工组件 id）。 */
    private ArrayNode draftTokens(String processCid) {
        ArrayNode expr = M.createArrayNode();
        ObjectNode t1 = M.createObjectNode();
        t1.put("type", "field");
        t1.put("value", "金额");
        expr.add(t1);
        ObjectNode op = M.createObjectNode();
        op.put("type", "operator");
        op.put("value", "*");
        expr.add(op);
        ObjectNode ref = M.createObjectNode();
        ref.put("type", "cross_tab_ref");
        ref.put("source", processCid);
        ref.put("target", "工时");
        ref.put("agg", "SUM");
        ArrayNode match = ref.putArray("match");
        ObjectNode pair = M.createObjectNode();
        pair.put("a", "子件");
        pair.put("b", "子件");
        match.add(pair);
        expr.add(ref);
        return expr;
    }

    /** baseRows 形态行：driverRow={...}, basicDataValues={}. */
    private ObjectNode driverBaseRow(Map<String, Object> driver) {
        ObjectNode row = M.createObjectNode();
        ObjectNode dr = M.createObjectNode();
        for (var e : driver.entrySet()) {
            if (e.getValue() instanceof Number n) dr.put(e.getKey(), n.doubleValue());
            else dr.put(e.getKey(), String.valueOf(e.getValue()));
        }
        row.set("driverRow", dr);
        row.set("basicDataValues", M.createObjectNode());
        return row;
    }

    /** 落一个组件（Component 实体）：name, row_key_fields(JSONB 数组)。data_driver_path 留空。 */
    private UUID insertComponent(String name, List<String> rowKeyFields) {
        com.cpq.component.entity.Component c = new com.cpq.component.entity.Component();
        c.name = name;
        c.code = "DRYRUN-" + UUID.randomUUID().toString().substring(0, 8);
        c.componentType = "NORMAL";
        c.rowKeyFields = writeJson(rowKeyFields);
        c.fields = "[]";
        c.formulas = "[]";
        c.persist();
        return c.id;
    }

    private String writeJson(Object o) {
        try { return M.writeValueAsString(o); } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** 一个 snapshot tab 节点（NORMAL）。 */
    private ObjectNode tab(String cid, String tabName, List<ObjectNode> fields) {
        ObjectNode t = M.createObjectNode();
        t.put("componentId", cid);
        t.put("componentCode", "C-" + tabName);
        t.put("componentType", "NORMAL");
        t.put("tabName", tabName);
        ArrayNode fs = t.putArray("fields");
        for (ObjectNode f : fields) fs.add(f);
        t.putArray("formulas");
        t.putArray("formula_assignments");
        return t;
    }

    @Test
    @TestTransaction
    void dryRunTokenRowsEqualsRenderRowByRow() throws Exception {
        // ── 组件落库（供装配读持久化 row_key_fields）──
        UUID processCid = insertComponent("加工", List.of("子件", "工序")); // 细 source 行键 [子件,工序]
        UUID feedCid = insertComponent("投料", List.of("子件"));          // 宿主行键 [子件]
        em.flush();

        // ── snapshot 组装（宿主投料在前、加工 source 在后；拓扑序会先算加工 source）──
        ObjectNode feedTab = tab(feedCid.toString(), "投料",
            List.of(field("子件", "INPUT_TEXT"), field("金额", "INPUT_NUMBER")));
        ObjectNode processTab = tab(processCid.toString(), "加工",
            List.of(field("子件", "INPUT_TEXT"), field("工序", "INPUT_TEXT"), field("工时", "INPUT_NUMBER")));
        ArrayNode snapshot = M.createArrayNode();
        snapshot.add(feedTab);
        snapshot.add(processTab);

        // ── baseRows（两路共用，确定性）──
        // 投料：螺丝 金额=10（金额 INPUT 落在 driverRow 即可被 mergedRow 读到）
        ArrayNode feedBase = M.createArrayNode();
        feedBase.add(driverBaseRow(Map.of("子件", "螺丝", "金额", 10)));
        // 加工：螺丝/钻 工时=3、螺丝/铣 工时=5
        ArrayNode procBase = M.createArrayNode();
        procBase.add(driverBaseRow(Map.of("子件", "螺丝", "工序", "钻", "工时", 3)));
        procBase.add(driverBaseRow(Map.of("子件", "螺丝", "工序", "铣", "工时", 5)));

        Map<String, ArrayNode> baseRowsByComp = new LinkedHashMap<>();
        baseRowsByComp.put(feedCid.toString(), feedBase);
        baseRowsByComp.put(processCid.toString(), procBase);

        // ═══════════════ 试算路径：dryRunTokenRowsCore（草稿注入）═══════════════
        ArrayNode tokens = draftTokens(processCid.toString());
        List<Map<String, Object>> dryRows = cardSnapshotService.dryRunTokenRowsCore(
            snapshot, feedCid.toString(), tokens, List.of("子件"), baseRowsByComp, null);

        // 断言1：螺丝行 == 80
        assertEquals(1, dryRows.size(), "投料宿主应 1 行（螺丝）");
        Object dryVal = dryRows.get(0).get("value");
        assertNotNull(dryVal, "螺丝行 value 不应为空");
        assertEquals(80.0, ((Number) dryVal).doubleValue(), 1e-9,
            "螺丝行 = 金额10 * SUM(加工.工时=3+5) = 80");

        // ═══════════════ 渲染路径：持久化公式（同一装配内核）═══════════════
        // 把同一公式写进宿主组件 formulas（持久化）+ snapshot 宿主 tab 加 __dryrun__ FORMULA 字段/公式
        ArrayNode persistFormulas = M.createArrayNode();
        ObjectNode fm = M.createObjectNode();
        fm.put("name", "__dryrun__");
        fm.put("fieldName", "__dryrun__");
        fm.set("expression", draftTokens(processCid.toString()));
        persistFormulas.add(fm);
        em.createNativeQuery("UPDATE component SET formulas = cast(:fml as jsonb) WHERE id = :id")
            .setParameter("fml", M.writeValueAsString(persistFormulas))
            .setParameter("id", feedCid)
            .executeUpdate();
        em.flush();

        // snapshot 宿主 tab 同步加 FORMULA 字段 + formulas（渲染从 snapshot 读结构）
        ObjectNode renderFeedTab = feedTab.deepCopy();
        ((ArrayNode) renderFeedTab.path("fields")).add(field("__dryrun__", "FORMULA"));
        renderFeedTab.set("formulas", persistFormulas.deepCopy());
        ArrayNode renderSnapshot = M.createArrayNode();
        renderSnapshot.add(renderFeedTab);
        renderSnapshot.add(processTab.deepCopy());

        String renderJson = cardSnapshotService.assembleTabsWithFormulaResultsForTest(
            renderSnapshot, baseRowsByComp, null);
        JsonNode renderRoot = M.readTree(renderJson);

        JsonNode hostFr = null;
        for (JsonNode t : renderRoot.path("tabs")) {
            if (feedCid.toString().equals(t.path("componentId").asText(""))) { hostFr = t.path("formulaResults"); break; }
        }
        assertNotNull(hostFr, "应找到宿主投料 tab");

        // 断言2：逐行对拍
        assertEquals(dryRows.size(), hostFr.size(), "渲染行数应与 dryRun 行数相等");
        for (Map<String, Object> dr : dryRows) {
            String drKey = String.valueOf(dr.get("rowKey"));
            double drVal = ((Number) dr.get("value")).doubleValue();
            JsonNode renderRow = null;
            for (JsonNode fr : hostFr) {
                if (drKey.equals(fr.path("rowKey").asText(""))) { renderRow = fr; break; }
            }
            assertNotNull(renderRow, "渲染应有 rowKey=" + drKey + " 行");
            double renderVal = renderRow.path("values").path("__dryrun__").asDouble(Double.NaN);
            assertEquals(drVal, renderVal, 1e-9,
                "命门0 逐行对拍：rowKey=" + drKey + " 试算=" + drVal + " 应等于渲染=" + renderVal);
        }
    }
}
