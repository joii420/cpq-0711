package com.cpq.priceadjust.ac260918;

import com.cpq.common.security.SessionHelper;
import com.cpq.priceadjust.ac260918.Rp0918aFixture.Edge;
import com.cpq.priceadjust.ac260918.Rp0918aFixture.Quote;
import com.cpq.priceadjust.ac260918.Rp0918aSnapshots.Revision;
import com.cpq.priceadjust.service.PriceAdjustBudgetService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.cpq.priceadjust.ac260918.Rp0918aFixture.P_PREV;
import static com.cpq.priceadjust.ac260918.Rp0918aFixture.P_TARGET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * repair-260918 · S-BE · <b>T-BE-19 / AC-19</b>（无副作用 · 小单 · 反-4）：
 * 「12 行以内的小单，同一料号升版，<b>改动前后</b>结果逐位一致：subtotal、quote_card_values、整单 total_amount、
 * 本期快照三列（解析后逐项相等）」。
 *
 * <p>「改动前」只能在旧代码上跑出来 ⇒ A/B 指纹法：同一确定性夹具，在两套代码上各跑一次本类，输出<b>去掉本轮随机
 * id 后</b>的规范化指纹，再逐项比较。
 * <ul>
 *   <li>本类<b>只依赖 master 上已有的类</b>（不引用本任务新增的类），可原样拷到干净的 master 检出里跑；
 *       依赖的辅助类：Rp0918aProfile / Rp0918aDb / Rp0918aFixture / Rp0918aApi / Rp0918aSnapshots / Rp0918aEvidence；</li>
 *   <li>标签：{@code -Drp0918a.ac19.label=master}（基线）/ 默认 {@code branch}；指纹写到
 *       {@code <证据根>/AC-19/fingerprint-<label>.json}（稳定路径，供另一侧读取），同时在本轮目录留一份副本；</li>
 *   <li>label=branch 时读取 {@code fingerprint-master.json} 比较；<b>缺基线直接失败</b>（不跳过，避免假绿）。</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(Rp0918aProfile.class)
class Ac260918T19SmallQuoteRegressionTest {

    @Inject
    EntityManager em;
    @Inject
    PriceAdjustBudgetService budget;
    @InjectMock
    SessionHelper sessionHelper;

    private Rp0918aFixture fx;

    @AfterEach
    void tearDown() {
        if (fx != null) {
            fx.awaitQuiet(2_000, 60_000);
            fx.cleanup();
        }
    }

    @Test
    void tBe19_ac19_smallQuoteUpgradeIdenticalBeforeAndAfterChange() throws Exception {
        String label = System.getProperty("rp0918a.ac19.label", "branch");
        Rp0918aDb db = new Rp0918aDb(em);
        Rp0918aSnapshots snaps = new Rp0918aSnapshots(db);
        fx = new Rp0918aFixture(db);
        fx.createCustomer().createAdjustStrategy();
        fx.createTemplate();
        Mockito.when(sessionHelper.getCurrentUserId(ArgumentMatchers.any())).thenReturn(fx.salesRepId);
        // padBlocks = 0：填充串以本轮随机 run 为盐，会让两次运行的指纹天然不同（假红），AC-19 不需要体量
        Quote q = fx.createQuote("S19", 12, null, Edge.NONE, 0);
        fx.addScopeFromQuotes(List.of(q.id()));
        UUID vPrev = fx.insertVersion("PREV", "SUPERSEDED", P_PREV, null, null, 7_200);
        UUID vTarget = fx.insertVersion("TGT", "PENDING", P_TARGET, P_PREV,
            P_TARGET.subtract(P_PREV).divide(P_PREV, 12, RoundingMode.HALF_UP), 3_600);
        fx.setPointersFromQuotes(vPrev, List.of(q.id()));
        String material = q.material(5);
        Map<String, UUID> rv = fx.createReviewsViaBudget(budget, vTarget, List.of(material));

        UUID job = Rp0918aApi.approveOk(rv.values());
        assertEquals("SUCCESS", Rp0918aApi.awaitJobTerminal(db, job, 120_000), "小单升版批次应成功");

        // ── 组装指纹 ──
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode fp = f.objectNode();
        ObjectNode lines = fp.putObject("lines");
        for (Object[] r : db.rows("SELECT sort_order, product_part_no_snapshot, subtotal::text, quote_card_values::text "
            + "FROM quotation_line_item WHERE quotation_id = :q ORDER BY sort_order", "q", q.id())) {
            ObjectNode ln = lines.putObject(String.valueOf(((Number) r[0]).intValue()));
            ln.put("material", (String) r[1]);
            ln.put("subtotal", (String) r[2]);
            ln.set("quote_card_values", r[3] == null ? f.nullNode() : Rp0918aSnapshots.parse((String) r[3]));
        }
        fp.put("total_amount", db.text("SELECT total_amount::text FROM quotation WHERE id = :q", "q", q.id()));
        Revision cur = snaps.current(q.id(), vTarget);
        assertNotNull(cur, "本期版本记录缺失");
        ObjectNode c = fp.putObject("current_revision");
        c.set("quote_card_values", cur.quoteCardValues());
        c.set("costing_card_values", cur.costingCardValues());
        c.set("snapshot_rows", cur.snapshotRows());
        c.set("upgraded_material_nos", Rp0918aApi.MAPPER.valueToTree(cur.upgradedMaterialNos()));

        Map<String, String> ids = new LinkedHashMap<>();
        ids.put(q.id().toString(), "QUOTE");
        for (Map.Entry<Integer, UUID> e : q.lineByOrdinal().entrySet()) ids.put(e.getValue().toString(), "LINE#" + e.getKey());
        ids.put(fx.template.elementComponentId().toString(), "COMP_ELEM");
        ids.put(fx.template.subtotalComponentId().toString(), "COMP_SUB");
        ids.put(fx.template.id().toString(), "TPL");
        ids.put(vTarget.toString(), "VER_TARGET");
        ids.put(vPrev.toString(), "VER_PREV");
        ids.put(fx.customerId.toString(), "CUST_ID");
        String text = Rp0918aApi.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(fp);
        for (Map.Entry<String, String> e : ids.entrySet()) text = text.replace(e.getKey(), e.getValue());
        text = text.replace(fx.run, "RUN");
        // 其余未点名的随机 UUID（如模板系列 id 若被写进卡片）统一抹成占位，避免两次运行天然不同造成假红
        text = text.replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<UUID>");
        text = text.replaceAll("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([+-]\\d{2}(:?\\d{2})?|Z)?", "<TS>");
        JsonNode normalized = Rp0918aApi.MAPPER.readTree(text);

        Path dir = Rp0918aEvidence.root().resolve("AC-19");
        Rp0918aEvidence.writeFile(dir.resolve("fingerprint-" + label + ".json"), text);
        Rp0918aEvidence.writeFile(Rp0918aEvidence.runDir().resolve("AC-19-fingerprint-" + label + ".json"), text);
        assertTrue(lines.size() == 12, "指纹应覆盖 12 行");

        if ("master".equals(label)) {
            Rp0918aEvidence.log("AC-19", "已生成改动前基线 " + dir.resolve("fingerprint-master.json"));
            return;
        }
        Path baseline = dir.resolve("fingerprint-master.json");
        if (!Files.exists(baseline)) {
            fail("AC-19 缺改动前基线 " + baseline + " —— 需在 master 代码上以 -Drp0918a.ac19.label=master 跑本类生成");
        }
        JsonNode base = Rp0918aApi.MAPPER.readTree(Files.readString(baseline));
        String diff = Rp0918aSnapshots.firstDiff(base, normalized, "$");
        Rp0918aEvidence.log("AC-19", "与基线比较：" + (diff == null ? "逐项一致" : "第一处差异 " + diff));
        assertNull(diff, "AC-19：小单升版结果改动前后应逐位一致，第一处差异：" + diff);
    }
}
