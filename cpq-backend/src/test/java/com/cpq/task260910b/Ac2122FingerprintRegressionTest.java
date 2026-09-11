package com.cpq.task260910b;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260910 · 分片 S-B · <b>{@code TC-B6}（AC-21）/ {@code TC-B7}（AC-22）</b> —— 回归 AC。
 *
 * <h3>这两条 AC 在回归什么</h3>
 * 本任务把「已有产品的工序顺序」的数据源从 {@code unit_price} 切到
 * {@code ds_quote_self_process_fee}（{@code api.md §2.4}）。指纹计算与发号器<b>本身不改</b>
 * （{@code 需求文档 §4.2}），所以这两条验的是「<b>不该变的没变</b>」：
 * <ul>
 *   <li>{@code AC-21}：同输入再走一次 ⇒ 仍命中复用，且 {@code ds_quote_material} 不重复铸号、
 *       {@code ds_quote_customer_part} 仍按「一料号多编号」追加一行；</li>
 *   <li>{@code AC-22}：换序仍命中，且确认页（{@code lookup-fingerprint} 的
 *       {@code snapshot.processes}）显示<b>已有产品的真实落库顺序</b> {@code （Z100 → Z101）}
 *       —— 换源后此断言仍成立。</li>
 * </ul>
 *
 * <h3>🚨 私有写纪律</h3>
 * 本组用例走完整选配提交（会写库），⇒ 一律用<b>本片自建的临时客户</b>
 * （{@code T2610B*}，见 {@link #newFixture}），🚫 不在 {@code CUST-0001} /
 * {@code CUST-0004} 下提交任何东西，也不碰别片前缀。清理在 {@code @AfterEach}（等价 finally）。
 */
@QuarkusTest
@DisplayName("S-B · AC-21/22 指纹复用与工序顺序回归")
public class Ac2122FingerprintRegressionTest extends SbBase {

    /** 本组的零件输入：材质 {@code 00006}（配置 {@code 00006-01}）占比 100，工序 {@code Z100 → Z101}。 */
    private Map<String, Object> partWithProcesses(List<String> processNos) {
        return newPart(PREFIX + "零件A", "A13", "2343", "11",
                List.of(material(RECIPE_A, RECIPE_A + "-01", "100")), processNos);
    }

    // ══════════════════════════ TC-B6 · AC-21 ══════════════════════════

    /**
     * <b>AC-21 原文</b>：「用<b>完全相同</b>的输入再走一次选配 ⇒ 命中复用
     * （响应 {@code fingerprintMatched=true}、{@code reusedHfPartNos} 含原料号），
     * 且 {@code ds_quote_material} / {@code _customer_part} 行为与改动前一致
     * （复用时仍写 {@code _customer_part} 一行 = 一料号多编号）」。
     */
    @Test
    @DisplayName("TC-B6 · AC-21：同输入再选配 ⇒ fingerprintMatched=true + 复用原料号 + 不重复铸号")
    void tcB6_ac21_fingerprintReuseUnchanged() {
        Fx fx = newFixture("AC21");
        String pnA = PREFIX + "PROD-A-" + RUN_ID;
        String pnB = PREFIX + "PROD-B-" + RUN_ID;

        // ── 第一次提交（应<b>不</b>命中复用，铸新号）──
        Response r1 = configure(fx, submitBody(pnA, partWithProcesses(List.of(PROC_1, PROC_2))));
        assertReachedBusinessLayer(r1, "AC-21 第一次提交");
        assertEquals(200, r1.statusCode(), "AC-21：第一次选配提交应 200，实际=" + r1.statusCode()
                + " body=" + trunc(r1.asString()));
        System.out.println("[TC-B6 第一次] " + trunc(r1.asString()));
        Boolean matched1 = bool(r1, "fingerprintMatched");
        String minted = firstPartNo(r1);
        assertNotNull(minted, "AC-21：第一次提交应返回 lineItems[0].productPartNo，body=" + trunc(r1.asString()));
        assertEquals(Boolean.FALSE, matched1, "AC-21 阳性对照：第一次提交<b>不</b>应命中复用，"
                + "实际 fingerprintMatched=" + matched1
                + "\n  ⇒ 第一次就 true 说明库里已有同指纹残留，此时「第二次命中」是零证据。");

        long matAfter1 = scalarLong("SELECT count(*) FROM ds_quote_material "
                + "WHERE customer_no=?1 AND material_no=?2", fx.customerNo(), minted);
        long cpAfter1 = scalarLong("SELECT count(*) FROM ds_quote_customer_part WHERE customer_no=?1",
                fx.customerNo());
        System.out.println("[TC-B6 第一次落库] 料号=" + minted
                + " ds_quote_material=" + matAfter1 + " 行, ds_quote_customer_part=" + cpAfter1 + " 行");
        assertEquals(1L, matAfter1, "AC-21 前提：第一次提交后新铸料号在 ds_quote_material 应恰好 1 行");
        assertEquals(1L, cpAfter1, "AC-21 前提：第一次提交后 ds_quote_customer_part 应恰好 1 行（编号 "
                + pnA + " → " + minted + "）");

        // ── 第二次提交：完全相同的零件输入，只换客户产品编号 ──
        Response r2 = configure(fx, submitBody(pnB, partWithProcesses(List.of(PROC_1, PROC_2))));
        assertReachedBusinessLayer(r2, "AC-21 第二次提交");
        assertEquals(200, r2.statusCode(), "AC-21：第二次选配提交应 200，实际=" + r2.statusCode()
                + " body=" + trunc(r2.asString()));
        System.out.println("[TC-B6 第二次] " + trunc(r2.asString()));

        // ── AC-21 断言① fingerprintMatched=true ──
        Boolean matched2 = bool(r2, "fingerprintMatched");
        assertEquals(Boolean.TRUE, matched2, "AC-21①：完全相同的输入应命中复用，"
                + "实际 fingerprintMatched=" + matched2 + " body=" + trunc(r2.asString()));

        // ── AC-21 断言② reusedHfPartNos 含原料号 ──
        List<String> reused = strings(r2, "reusedHfPartNos");
        System.out.println("[TC-B6 复用] reusedHfPartNos=" + reused + "（期望含 " + minted + "）");
        assertNonEmpty(reused, "AC-21②：reusedHfPartNos");
        assertTrue(reused.contains(minted), "AC-21②：reusedHfPartNos 应含第一次铸的 " + minted
                + "，实际=" + reused);

        // ── AC-21 断言③ ds_quote_material 不重复铸号 ──
        long matAfter2 = scalarLong("SELECT count(*) FROM ds_quote_material WHERE customer_no=?1",
                fx.customerNo());
        long mintedRows = scalarLong("SELECT count(*) FROM ds_quote_material "
                + "WHERE customer_no=?1 AND material_no=?2", fx.customerNo(), minted);
        System.out.println("[TC-B6 第二次落库] 本客户 ds_quote_material=" + matAfter2
                + " 行（其中 " + minted + " 占 " + mintedRows + " 行）");
        assertEquals(1L, mintedRows, "AC-21③：复用时 " + minted + " 在 ds_quote_material 仍应恰好 1 行"
                + "（🚫 不许重复铸号）");
        assertEquals(1L, matAfter2, "AC-21③：本客户名下料号总数应仍为 1（复用 ⇒ 不铸新号），实际=" + matAfter2);

        // ── AC-21 断言④ _customer_part 仍按「一料号多编号」追加一行 ──
        long cpAfter2 = scalarLong("SELECT count(*) FROM ds_quote_customer_part WHERE customer_no=?1",
                fx.customerNo());
        List<String> pairs = strList("SELECT customer_product_no || ' → ' || COALESCE(material_no,'(null)') "
                + "FROM ds_quote_customer_part WHERE customer_no=?1 ORDER BY customer_product_no",
                fx.customerNo());
        System.out.println("[TC-B6 一料号多编号] ds_quote_customer_part=" + cpAfter2 + " 行：" + pairs);
        assertEquals(2L, cpAfter2, "AC-21④：复用时仍应写 ds_quote_customer_part 一行"
                + "（一料号多编号）⇒ 两次提交后应恰好 2 行，实际=" + cpAfter2 + " 明细=" + pairs);
        assertEquals(2L, scalarLong("SELECT count(*) FROM ds_quote_customer_part "
                + "WHERE customer_no=?1 AND material_no=?2", fx.customerNo(), minted),
                "AC-21④：两行都应指向同一个销售料号 " + minted + "，实际=" + pairs);

        // ── 🧪 反方向（不该复用时<b>不许</b>误复用）──
        //    只验「该复用时复用」会被「指纹恒命中」这种坏实现骗过：那种实现下一个新料号都不会铸，
        //    而「查到有行」会被存量数据骗过（派工单点名的假绿形态）。⇒ 改一个输入维度必须<b>不</b>命中、且铸新号。
        String pnC = PREFIX + "PROD-C-" + RUN_ID;
        Response r3 = configure(fx, submitBody(pnC,
                newPart(PREFIX + "零件A", "A13", "2343", "12",       // 只改总重 11 → 12
                        List.of(material(RECIPE_A, RECIPE_A + "-01", "100")), List.of(PROC_1, PROC_2))));
        assertReachedBusinessLayer(r3, "AC-21 反方向提交（改总重）");
        assertEquals(200, r3.statusCode(), "反方向：提交应 200，body=" + trunc(r3.asString()));
        Boolean matched3 = bool(r3, "fingerprintMatched");
        String minted3 = firstPartNo(r3);
        System.out.println("[TC-B6 反方向] 只改总重 11→12 → matched=" + matched3 + " 料号=" + minted3
                + "（期望 matched=false 且是一个<b>新</b>料号）");
        assertEquals(Boolean.FALSE, matched3, "AC-21 反方向：输入变了（总重 11→12）就<b>不该</b>命中复用，"
                + "实际 fingerprintMatched=" + matched3
                + "\n  ⇒ 恒 true 说明指纹口径坏了，此时「该复用时复用」是零证据。body=" + trunc(r3.asString()));
        assertNotNull(minted3, "AC-21 反方向：应返回料号，body=" + trunc(r3.asString()));
        assertFalse(minted.equals(minted3), "AC-21 反方向：应铸<b>新</b>料号，实际仍是 " + minted);
        long matAfter3 = scalarLong("SELECT count(*) FROM ds_quote_material WHERE customer_no=?1",
                fx.customerNo());
        System.out.println("[TC-B6 反方向落库] 本客户 ds_quote_material=" + matAfter3
                + " 行（期望 2：" + minted + " + " + minted3 + "）");
        assertEquals(2L, matAfter3, "AC-21 反方向：不同指纹应各占 1 行 ⇒ 本客户应恰好 2 行，实际=" + matAfter3);
    }

    // ══════════════════════════ TC-B7 · AC-22 ══════════════════════════

    /**
     * <b>AC-22 原文</b>：「对已有料号（工序落库顺序 {@code Z100→Z101}），用 {@code Z101→Z100}
     * 的顺序走选配。断言：① 命中复用；② 确认页显示已有产品的真实工序顺序 {@code （Z100 → Z101）}
     * （数据源从 {@code unit_price} 切到 {@code ds_quote_self_process_fee} 后此断言仍成立）」。
     *
     * <p>「确认页显示的顺序」的接口出口是 {@code POST /configure-product/lookup-fingerprint} 的
     * {@code snapshot.processes}（{@code api.md §2.4} 点名「{@code processes} ✅ 保留 ——
     * AC-22 唯一消费方」），按 {@code operation_item_seq} 升序。
     */
    @Test
    @DisplayName("TC-B7 · AC-22：换序仍命中 + 确认页显示真实落库顺序（Z100 → Z101）")
    void tcB7_ac22_reorderStillMatchesAndShowsStoredOrder() {
        Fx fx = newFixture("AC22");
        String pnA = PREFIX + "ORD-A-" + RUN_ID;

        // ── 前置：以 Z100 → Z101 的顺序落一个料号 ──
        Response r1 = configure(fx, submitBody(pnA, partWithProcesses(List.of(PROC_1, PROC_2))));
        assertReachedBusinessLayer(r1, "AC-22 前置提交");
        assertEquals(200, r1.statusCode(), "AC-22 前置：提交应 200，body=" + trunc(r1.asString()));
        String minted = firstPartNo(r1);
        assertNotNull(minted, "AC-22 前置：应返回新铸料号，body=" + trunc(r1.asString()));
        System.out.println("[TC-B7 前置] 料号=" + minted + " 提交顺序=[" + PROC_1 + ", " + PROC_2 + "]");

        // ── 前置自证：工序真的落了库，且顺序是 Z100 → Z101 ──
        //    🔑 两张表都读出来打印：本任务把源从 unit_price 切到 ds_quote_self_process_fee，
        //       打印两侧才能在红的时候一眼看出是「没写新表」还是「顺序错」。
        List<String> newTable = strList("SELECT operation_no FROM ds_quote_self_process_fee "
                + "WHERE customer_no=?1 AND material_no=?2 ORDER BY operation_item_seq",
                fx.customerNo(), minted);
        List<String> oldTable = tableExists("unit_price")
                ? strList("SELECT operation_no FROM unit_price WHERE customer_no=?1 "
                        + "AND finished_material_no=?2 AND cost_type='自制加工费' ORDER BY seq_no",
                        fx.customerNo(), minted)
                : List.of();
        System.out.println("[TC-B7 落库对照] ds_quote_self_process_fee=" + newTable
                + " / unit_price(自制加工费)=" + oldTable);
        assertNonEmpty(newTable, "AC-22 前置：工序应落在<b>新表</b> ds_quote_self_process_fee（api.md §2.4 "
                + "已把 processes 的数据源切到它）。两表实际：新表=" + newTable + " / unit_price=" + oldTable
                + "\n  ⇒ 新表为空而老表有行 = 数据源没切，此时下面「确认页顺序正确」是走老表得出的，不算 AC-22 达成。");
        assertEquals(List.of(PROC_1, PROC_2), newTable, "AC-22 前置：新表 ds_quote_self_process_fee 的落库顺序"
                + "应为 [" + PROC_1 + ", " + PROC_2 + "]，实际=" + newTable);

        // ── AC-22① 换序仍命中（提交路径）──
        String pnB = PREFIX + "ORD-B-" + RUN_ID;
        Response r2 = configure(fx, submitBody(pnB, partWithProcesses(List.of(PROC_2, PROC_1))));
        assertReachedBusinessLayer(r2, "AC-22 换序提交");
        assertEquals(200, r2.statusCode(), "AC-22①：换序提交应 200，body=" + trunc(r2.asString()));
        Boolean matched = bool(r2, "fingerprintMatched");
        List<String> reused = strings(r2, "reusedHfPartNos");
        System.out.println("[TC-B7 换序提交] 顺序=[" + PROC_2 + ", " + PROC_1 + "] → matched=" + matched
                + " reused=" + reused);
        assertEquals(Boolean.TRUE, matched, "AC-22①：换序（" + PROC_2 + " → " + PROC_1
                + "）应仍命中复用，实际 fingerprintMatched=" + matched + " body=" + trunc(r2.asString()));
        assertTrue(reused.contains(minted), "AC-22①：reusedHfPartNos 应含 " + minted + "，实际=" + reused);

        // ── AC-22② 确认页（lookup-fingerprint）显示已有产品的真实顺序 ──
        Map<String, Object> lookup = new LinkedHashMap<>();
        lookup.put("customerNo", fx.customerNo());
        lookup.put("parts", List.of(partWithProcesses(List.of(PROC_2, PROC_1))));
        Response lf = given().contentType(ContentType.JSON).body(lookup).post(LOOKUP_FP).thenReturn();
        assertReachedBusinessLayer(lf, "AC-22② lookup-fingerprint");
        assertEquals(200, lf.statusCode(), "AC-22②：lookup-fingerprint 应 200，body=" + trunc(lf.asString()));
        System.out.println("[TC-B7 确认页] " + trunc(lf.asString()));
        assertEquals(Boolean.TRUE, lf.jsonPath().get("matched"),
                "AC-22②：预览应命中（与提交同口径），body=" + trunc(lf.asString()));
        assertEquals(minted, lf.jsonPath().getString("matchedPartNo"),
                "AC-22②：matchedPartNo 应为 " + minted);

        List<String> shown = lf.jsonPath().getList("snapshot.processes.processCode", String.class);
        System.out.println("[TC-B7 确认页顺序] snapshot.processes.processCode=" + shown
                + "（期望 [" + PROC_1 + ", " + PROC_2 + "]，即已有产品的真实落库顺序）");
        assertNonEmpty(shown, "AC-22②：snapshot.processes");
        assertEquals(List.of(PROC_1, PROC_2), shown, "AC-22②：确认页应显示<b>已有产品的真实工序顺序</b>（"
                + PROC_1 + " → " + PROC_2 + "），而不是本次输入的换序（" + PROC_2 + " → " + PROC_1
                + "）。实际=" + shown
                + "\n  ⇒ 数据源已从 unit_price 切到 ds_quote_self_process_fee（api.md §2.4），"
                + "顺序按 operation_item_seq 升序。");

        // ── api.md §2.4 契约裁剪：两个无消费方字段应已删除（B-6 / 服务 AC-22）──
        //    🔑 按<b>键是否存在</b>判，不按「值是否为 null」判：字段留着但值为 null 也算契约没裁剪掉，
        //       而 jsonPath().get() 两种情况都返回 null，会把「没删」读成「删了」（假绿）。
        Map<String, Object> snap = lf.jsonPath().get("snapshot");
        assertNotNull(snap, "AC-22②：响应应含 snapshot 对象（api.md §2.4），body=" + trunc(lf.asString()));
        System.out.println("[TC-B7 契约裁剪] snapshot 的键集合=" + snap.keySet()
                + "（api.md §2.4 要求：processes 保留、unitWeightGrams 与 compositeProcesses 删除）");
        assertFalse(snap.containsKey("unitWeightGrams"), "api.md §2.4：snapshot 里仍存在键 "
                + "unitWeightGrams（原查 v_compat_material_master.unit_weight，前端零消费方，B-6 应删除）"
                + "，实际值=" + snap.get("unitWeightGrams") + "，键集合=" + snap.keySet());
        assertFalse(snap.containsKey("compositeProcesses"), "api.md §2.4：snapshot 里仍存在键 "
                + "compositeProcesses（原查 capacity，前端零消费方，B-6 应删除），实际值="
                + snap.get("compositeProcesses") + "，键集合=" + snap.keySet());
        assertTrue(snap.containsKey("processes"), "api.md §2.4：processes 是 AC-22 的唯一消费方，必须保留。"
                + "键集合=" + snap.keySet());
    }

    // ══════════════════════════ 解析助手 ══════════════════════════

    private Boolean bool(Response res, String path) {
        Object v = res.jsonPath().get(path);
        if (v == null) v = res.jsonPath().get("data." + path);
        return v == null ? null : Boolean.valueOf(String.valueOf(v));
    }

    private String firstPartNo(Response res) {
        String v = res.jsonPath().getString("lineItems[0].productPartNo");
        return v != null ? v : res.jsonPath().getString("data.lineItems[0].productPartNo");
    }

    private List<String> strings(Response res, String path) {
        List<String> v = res.jsonPath().getList(path, String.class);
        if (v == null) v = res.jsonPath().getList("data." + path, String.class);
        return v == null ? new ArrayList<>() : v;
    }
}
