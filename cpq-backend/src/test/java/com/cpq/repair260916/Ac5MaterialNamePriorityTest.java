package com.cpq.repair260916;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * repair-260916 · S-2 · <b>AC-5 取值优先级与空值</b>（T2.1 ~ T2.4）。
 *
 * <blockquote>AC-5 原文（问题说明 ⑥ · 边界）：<br>
 * ① 同一个号在<b>本客户</b>物料表与材质表<b>都有</b> → 材料名 = <b>物料表名称</b>；<br>
 * ② 两表都没有 → 该行<b>仍在</b>，材料名为空（接口返回 {@code null}，页面显示「—」），不报错；<br>
 * ③ 该号只在<b>另一个客户</b>的物料表里有、材质表里也有 → 材料名 = <b>材质表 symbol</b>（不取别的客户的物料名称）；
 *    只在另一个客户物料表里有、材质表里没有 → 材料名为空；<br>
 * ④ 核价侧：来料料号在核价物料表 {@code production_no} 里有 → 取物料表名称；只在材质表有 → 取 symbol；都没有 → 空。
 * </blockquote>
 *
 * <h3>观察面</h3>
 * 用户可见的取数配置器预览 {@code POST /api/cpq/components/{id}/builder/preview}
 * （{@code task-260819/api.md §2.3}）：请求体 = 裸 {@code builder_config} + 平级 {@code partNo}/{@code customerCode}。
 * 配置里「材料名」选的是物料表查名节点 {@code MAT_NAME_LK.material_name}（与存量组件一致，问题说明 ④ 证据 5），
 * 材质表那一段由连表配置自动补上 —— 本类<b>不</b>在配置里点名材质表。
 *
 * <h3>判别性设计（test.md §4 E-3）</h3>
 * <ul>
 *   <li>① 物料表名称故意 ≠ 材质 symbol ⇒「材质优先」的错误实现会红；</li>
 *   <li>③ 他客户物料名故意 ≠ symbol ⇒「跨客户取名」的错误实现会红；并配阳性对照：
 *       以他客户身份预览同一个号，必须拿到他客户物料名（证明那行数据对查名是可见的，③ 的 symbol 不是因为数据没落库）；</li>
 *   <li>「只在材质表有」的情形（③前半 / ④第二种）在改动前的连表配置下恒为 {@code null}（问题说明 ② 复现 3）⇒ 能区分修没修。</li>
 * </ul>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ac5MaterialNamePriorityTest extends R260916TBase {

    private UUID previewComponentId;

    @BeforeEach
    void setUp() {
        assertSharedPreconditions();
        previewComponentId = createComponent("AC5-PREVIEW");
    }

    @AfterEach
    void tearDown() {
        cleanupAll();
    }

    // ═══════════════════ 造数 ═══════════════════

    private void quoteMaterial(String customerNo, String materialNo, String name, String productionNo) {
        // 🚫 不绑定 null 参数（PG 驱动对无类型 null 可能报 could not determine data type）
        if (productionNo == null) {
            exec("INSERT INTO ds_quote_material (material_no, material_name, customer_no, created_at) "
                    + "VALUES (?1, ?2, ?3, now())", materialNo, name, customerNo);
        } else {
            exec("INSERT INTO ds_quote_material (material_no, material_name, production_no, customer_no, created_at) "
                    + "VALUES (?1, ?2, ?3, ?4, now())", materialNo, name, productionNo, customerNo);
        }
        System.out.println("[S-2 造数] ds_quote_material customer=" + customerNo + " no=" + materialNo
                + " name=" + name + " production_no=" + productionNo);
    }

    private void quoteFixedFee(String customerNo, String salesNo, int seq, String inputNo, int baseValue) {
        exec("INSERT INTO ds_quote_incoming_fixed_fee (material_no, item_seq, input_material_no, base_value, "
                        + "version_no, row_fingerprint, customer_no, created_at) "
                        + "VALUES (?1, ?2, ?3, ?4, 1, ?5, ?6, now())",
                salesNo, seq, inputNo, baseValue, fingerprint(), customerNo);
        System.out.println("[S-2 造数] ds_quote_incoming_fixed_fee customer=" + customerNo + " sales=" + salesNo
                + " seq=" + seq + " input=" + inputNo);
    }

    private void costMaterial(String table, String productionNo, String name) {
        exec("INSERT INTO " + table + " (production_no, material_name, created_at) VALUES (?1, ?2, now())",
                productionNo, name);
        System.out.println("[S-2 造数] " + table + " production_no=" + productionNo + " name=" + name);
    }

    private void costBasicProcessFee(String productionNo, int seq, String incomingNo) {
        exec("INSERT INTO ds_cost_basic_incoming_process_fee (production_no, item_seq, incoming_material_no, "
                        + "process_fee, version_no, row_fingerprint, created_at) VALUES (?1, ?2, ?3, 1, 1, ?4, now())",
                productionNo, seq, incomingNo, fingerprint());
        System.out.println("[S-2 造数] ds_cost_basic_incoming_process_fee production_no=" + productionNo
                + " seq=" + seq + " incoming=" + incomingNo);
    }

    private void costDetailOtherFixedFee(String productionNo, int seq, String incomingNo) {
        exec("INSERT INTO ds_cost_detail_incoming_other_fixed_fee (production_no, item_seq, incoming_material_no, "
                        + "element_name, fee, version_no, row_fingerprint, created_at) "
                        + "VALUES (?1, ?2, ?3, ?4, 1, 1, ?5, now())",
                productionNo, seq, incomingNo, PREFIX + "要素" + seq, fingerprint());
        System.out.println("[S-2 造数] ds_cost_detail_incoming_other_fixed_fee production_no=" + productionNo
                + " seq=" + seq + " incoming=" + incomingNo);
    }

    // ═══════════════════ 取数配置 ═══════════════════

    private static Map<String, Object> col(String nodeKey, String column, String fieldName, boolean partNo) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("sourceNodeKey", nodeKey);
        c.put("sourceColumn", column);
        c.put("fieldName", fieldName);
        if (partNo) {
            c.put("isRowKey", true);
            c.put("isPartNo", true);
        }
        return c;
    }

    private static Map<String, Object> cfg(String dialect, String variantKey, List<Map<String, Object>> cols) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dialect", dialect);
        m.put("tabType", "费用类");
        m.put("variantKey", variantKey);
        m.put("columns", cols);
        return m;
    }

    /** 报价侧「来料固定加工费」：投入料号 + 材料名（物料表查名节点）+ 基准值。 */
    private static Map<String, Object> quoteFixedFeeCfg() {
        return cfg("QUOTE", "INCOMING_FIXED_FEE", List.of(
                col("INCOMING_FIXED_FEE", "input_material_no", "料号", true),
                col("MAT_NAME_LK", "material_name", "材料名", false),
                col("INCOMING_FIXED_FEE", "base_value", "基准值", false)));
    }

    private static Map<String, Object> costBasicProcessFeeCfg() {
        return cfg("COST_BASIC", "INCOMING_PROCESS_FEE", List.of(
                col("INCOMING_PROCESS_FEE", "incoming_material_no", "料号", true),
                col("MAT_NAME_LK", "material_name", "材料名", false),
                col("INCOMING_PROCESS_FEE", "process_fee", "加工费", false)));
    }

    private static Map<String, Object> costDetailOtherFixedFeeCfg() {
        return cfg("COST_DETAIL", "INCOMING_OTHER_FIXED_FEE", List.of(
                col("INCOMING_OTHER_FIXED_FEE", "incoming_material_no", "料号", true),
                col("MAT_NAME_LK", "material_name", "材料名", false),
                col("INCOMING_OTHER_FIXED_FEE", "fee", "费用", false)));
    }

    private Response preview(Map<String, Object> config, String customerCode, String partNo) {
        Map<String, Object> body = new LinkedHashMap<>(config);
        body.put("customerCode", customerCode);
        body.put("partNo", partNo);
        Response r = asAdmin().body(body)
                .post("/api/cpq/components/" + previewComponentId + "/builder/preview").thenReturn();
        assertReachedBusinessLayer(r, "preview(" + config.get("dialect") + "/" + customerCode + "/" + partNo + ")");
        System.out.println("---- preview " + config.get("dialect") + " customer=" + customerCode + " partNo=" + partNo
                + " → status=" + r.statusCode() + "\n" + abbreviate(r.asString()));
        return r;
    }

    // ═══════════════════ 预览结果解析 ═══════════════════

    /** 一个预览结果里，投入料号 → 该料号所有行的「材料名」取值（保留 null）。 */
    private record Parsed(String partCol, String nameCol, Map<String, List<Object>> namesByInput,
                          int myRowCount, List<Map<String, Object>> diagnostics) {
        Object onlyName(String input) {
            List<Object> v = namesByInput.get(input);
            assertNotNull(v, "预览里找不到投入料号 " + input + " 的行（行被丢了）。namesByInput=" + namesByInput);
            assertFalse(v.isEmpty(), "投入料号 " + input + " 行数为 0");
            Object first = v.get(0);
            for (Object o : v) {
                assertEquals(first, o, "同一投入料号 " + input + " 的多行材料名不一致：" + v);
            }
            return first;
        }
    }

    @SuppressWarnings("unchecked")
    private Parsed parse(Response r, String nameColExact, Set<String> myInputs) {
        assertEquals(200, r.statusCode(), "预览应 200（AC-5「不报错」），实际=" + r.statusCode() + " body=" + r.asString());
        List<String> columns = r.jsonPath().getList("columns", String.class);
        List<Map<String, Object>> rs = (List<Map<String, Object>>) r.jsonPath().get("rows");
        List<Map<String, Object>> diags = r.jsonPath().getList("diagnostics");
        assertNotNull(columns, "响应缺 columns。body=" + r.asString());
        assertNotNull(rs, "响应缺 rows ⇒ 断言会空跑。body=" + r.asString());
        // 🚨 空跑守卫
        assertFalse(rs.isEmpty(), "🔴 预览 0 行 ⇒ 后面的取值断言全部空跑，不是通过。"
                + "\n  若是核价侧：本类按「核价预览 partNo = 生产料号」取数（2026-09-16 执行期归因）；"
                + "0 行说明该前提又变了 ⇒ 报主线，🚫 不许改判据。"
                + "\n  diagnostics=" + diags);

        // 🔄 2026-09-16 主线裁决（F-2）：报价侧列名 = `_物料_材料名`（E-5/AC-3）；核价侧 AC 未规定列名，
        //    按物理列名 `material_name` 取。两侧都要求该列名「恰好 1 个」—— 0 个或多个都判失败，防找错列假绿。
        assertNotNull(nameColExact, "量具：必须显式给出材料名列名");
        String nameCol = nameColExact;
        long hits = columns.stream().filter(nameCol::equals).count();
        assertEquals(1L, hits, "材料名列「" + nameCol + "」应恰好 1 个，实际 columns=" + columns);

        // 投入料号列：值集合覆盖我造的全部投入料号的那一列（不猜系统生成的列名）
        String partCol = null;
        for (String c : columns) {
            if (c.equals(nameCol)) {
                continue;
            }
            java.util.Set<String> vals = new java.util.HashSet<>();
            for (Map<String, Object> row : rs) {
                Object v = row.get(c);
                if (v != null) {
                    vals.add(String.valueOf(v));
                }
            }
            if (vals.containsAll(myInputs)) {
                partCol = c;
                break;
            }
        }
        assertNotNull(partCol, "🔴 没有任何一列的取值覆盖我造的投入料号 " + myInputs
                + " ⇒ 有行被丢，或取到的不是我的数据。columns=" + columns + " rows=" + rs);

        Map<String, List<Object>> names = new LinkedHashMap<>();
        int mine = 0;
        for (Map<String, Object> row : rs) {
            String in = row.get(partCol) == null ? null : String.valueOf(row.get(partCol));
            if (in == null || !myInputs.contains(in)) {
                continue;
            }
            mine++;
            assertTrue(row.containsKey(nameCol),
                    "AC-5②：接口应返回 null（键存在、值为 null），实际该行缺少键 " + nameCol + "。row=" + row);
            names.computeIfAbsent(in, k -> new ArrayList<>()).add(row.get(nameCol));
        }
        System.out.println("[AC-5 实际值] partCol=" + partCol + " nameCol=" + nameCol + " 我的行数=" + mine
                + " 投入料号→材料名=" + names + " diagnostics=" + diags);
        return new Parsed(partCol, nameCol, names, mine, diags == null ? List.of() : diags);
    }

    private static void assertNoErrorDiagnostics(Parsed p) {
        for (Map<String, Object> d : p.diagnostics()) {
            String level = String.valueOf(d.get("level"));
            assertFalse("ERROR".equalsIgnoreCase(level) || "ERR".equalsIgnoreCase(level),
                    "AC-5「不报错」：预览 diagnostics 出现错误级条目：" + d);
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "null" : (s.length() <= 3000 ? s : s.substring(0, 3000) + " …(truncated)");
    }

    // ═══════════════════ T2.1 · AC-5 ① ═══════════════════

    @Test
    @Order(1)
    @DisplayName("T2.1 AC-5①: 同号在本客户物料表与材质表都有 → 材料名 = 物料表名称（≠ 材质 symbol）")
    void t21_bothTables_materialWins() {
        String cust = PREFIX + "C1" + RUN;
        String sales = PREFIX + "S1-" + RUN;
        String m1 = PREFIX + "M1-" + RUN;
        String matName00144 = PREFIX + "物料名-" + RECIPE_CODE;
        String matNameM1 = PREFIX + "物料名-M1";
        String symbol = recipeSymbol();
        assertNotEquals(symbol, matName00144, "构造自检：物料表名称必须 ≠ symbol，否则判据不能区分优先级");

        quoteMaterial(cust, RECIPE_CODE, matName00144, null);
        quoteMaterial(cust, m1, matNameM1, null);
        quoteFixedFee(cust, sales, 1, RECIPE_CODE, 10);
        quoteFixedFee(cust, sales, 2, m1, 20);

        Parsed p = parse(preview(quoteFixedFeeCfg(), cust, sales), QUOTE_NAME_COL, Set.of(RECIPE_CODE, m1));
        assertTrue(p.myRowCount() >= 2, "我造了 2 行，预览里只找到 " + p.myRowCount());
        assertEquals(matName00144, p.onlyName(RECIPE_CODE),
                "AC-5①：" + RECIPE_CODE + " 在本客户物料表与材质表都有 ⇒ 应取物料表名称；"
                        + "若得到 " + symbol + " 说明取值顺序反了（材质优先）");
        assertEquals(matNameM1, p.onlyName(m1), "AC-5①（对照）：只在物料表的号仍取物料表名称");
        assertNoErrorDiagnostics(p);
    }

    // ═══════════════════ T2.2 · AC-5 ② ═══════════════════

    @Test
    @Order(2)
    @DisplayName("T2.2 AC-5②: 两表都没有 → 该行仍在、材料名 null、200 且无错误诊断")
    void t22_neitherTable_rowKeptNameNull() {
        String cust = PREFIX + "C2" + RUN;
        String sales = PREFIX + "S2-" + RUN;
        String n2 = PREFIX + "N2-" + RUN;
        assertEquals(0L, count("SELECT count(*) FROM material_recipe WHERE code = ?1", n2),
                "构造自检：" + n2 + " 不应在材质表");
        assertEquals(0L, count("SELECT count(*) FROM ds_quote_material WHERE material_no = ?1", n2),
                "构造自检：" + n2 + " 不应在任何客户的物料表");

        quoteFixedFee(cust, sales, 1, n2, 10);
        quoteFixedFee(cust, sales, 2, RECIPE_CODE, 20);
        long seeded = count("SELECT count(*) FROM ds_quote_incoming_fixed_fee WHERE customer_no = ?1 AND material_no = ?2",
                cust, sales);
        assertEquals(2L, seeded, "夹具自检：应落 2 行");

        Parsed p = parse(preview(quoteFixedFeeCfg(), cust, sales), QUOTE_NAME_COL, Set.of(n2, RECIPE_CODE));
        assertTrue(p.myRowCount() >= seeded,
                "AC-5②：行不丢 —— 造了 " + seeded + " 行，预览只找到 " + p.myRowCount());
        assertTrue(p.namesByInput().containsKey(n2), "AC-5②：两表都没有的 " + n2 + " 行必须仍在");
        assertNull(p.onlyName(n2), "AC-5②：两表都没有 ⇒ 材料名应为 null");
        assertEquals(recipeSymbol(), p.onlyName(RECIPE_CODE), "AC-5②（同单对照）：只在材质表的号取 symbol");
        assertNoErrorDiagnostics(p);
    }

    // ═══════════════════ T2.3 · AC-5 ③（E-3 判别） ═══════════════════

    @Test
    @Order(3)
    @DisplayName("T2.3 AC-5③: 只在他客户物料表+材质表 → symbol（不取他客户名）；只在他客户物料表 → null；阳性对照：他客户自己预览能取到他的名")
    void t23_otherCustomerMaterialNotLeaked() {
        String host = PREFIX + "C3" + RUN;
        String other = PREFIX + "C4" + RUN;
        String hostSales = PREFIX + "S3-" + RUN;
        String otherSales = PREFIX + "S3B-" + RUN;
        String x3 = PREFIX + "X3-" + RUN;
        String otherName00144 = PREFIX + "他客户名-" + RECIPE_CODE;
        String otherNameX3 = PREFIX + "他客户名-X3";
        String symbol = recipeSymbol();

        // 判别性前提
        assertNotEquals(symbol, otherName00144, "E-3：他客户物料名必须 ≠ symbol，否则区分不了");
        assertEquals(0L, count("SELECT count(*) FROM material_recipe WHERE code = ?1", x3), "构造自检：X3 不在材质表");
        long leakDecoy = count("SELECT count(*) FROM ds_quote_material WHERE material_no = ?1 "
                + "AND customer_no NOT LIKE ?2 AND material_name = ?3", RECIPE_CODE, PREFIX + "%", symbol);
        assertEquals(0L, leakDecoy, "判别性前提被破坏：库里有非本片客户的 " + RECIPE_CODE + " 物料行且名称恰为 "
                + symbol + " ⇒「跨客户串名」会伪装成「取到 symbol」，本条无法判别 —— 报主线");

        quoteMaterial(other, RECIPE_CODE, otherName00144, null);
        quoteMaterial(other, x3, otherNameX3, null);
        quoteFixedFee(host, hostSales, 1, RECIPE_CODE, 10);
        quoteFixedFee(host, hostSales, 2, x3, 20);
        // 阳性对照数据：他客户自己的来料行（销售料号不同，避免与宿主预览混行）
        quoteFixedFee(other, otherSales, 1, RECIPE_CODE, 30);
        quoteFixedFee(other, otherSales, 2, x3, 40);
        assertEquals(0L, count("SELECT count(*) FROM ds_quote_material WHERE customer_no = ?1", host),
                "构造自检：宿主客户物料表应为空");

        // 阳性对照：同一行数据在客户匹配时确实能被查到名 ⇒ 下面宿主侧的 symbol/null 不是「数据没落库」造成的
        Parsed ctrl = parse(preview(quoteFixedFeeCfg(), other, otherSales), QUOTE_NAME_COL, Set.of(RECIPE_CODE, x3));
        assertEquals(otherName00144, ctrl.onlyName(RECIPE_CODE), "阳性对照：他客户自己预览应取到他的物料名");
        assertEquals(otherNameX3, ctrl.onlyName(x3), "阳性对照：他客户自己预览应取到他的物料名（X3）");

        Parsed p = parse(preview(quoteFixedFeeCfg(), host, hostSales), QUOTE_NAME_COL, Set.of(RECIPE_CODE, x3));
        Object n00144 = p.onlyName(RECIPE_CODE);
        assertNotEquals(otherName00144, n00144, "AC-5③ / E-4：串到了别的客户的物料名称");
        assertEquals(symbol, n00144, "AC-5③：只在他客户物料表 + 材质表 ⇒ 应取材质表 symbol");
        Object nx3 = p.onlyName(x3);
        assertNotEquals(otherNameX3, nx3, "AC-5③ / E-4：X3 串到了别的客户的物料名称");
        assertNull(nx3, "AC-5③：只在他客户物料表、材质表没有 ⇒ 材料名应为空");
        assertNoErrorDiagnostics(p);
    }

    // ═══════════════════ T2.4 · AC-5 ④ 核价侧（基础核价 + 明细核价） ═══════════════════

    @Test
    @Order(4)
    @DisplayName("T2.4a AC-5④ 基础核价/来料加工费: 核价物料表有→物料名；只在材质表→symbol；都没有→null")
    void t24a_costBasic_threeCases() {
        runCostCase("COST_BASIC", "C5", "ds_cost_basic_material", costBasicProcessFeeCfg(), true);
    }

    @Test
    @Order(5)
    @DisplayName("T2.4b AC-5④ 明细核价/来料其他固定费用: 核价物料表有→物料名；只在材质表→symbol；都没有→null")
    void t24b_costDetail_threeCases() {
        runCostCase("COST_DETAIL", "C6", "ds_cost_detail_material", costDetailOtherFixedFeeCfg(), false);
    }

    private void runCostCase(String dialect, String tag, String materialTable, Map<String, Object> config,
                             boolean basic) {
        String cust = PREFIX + tag + RUN;
        String sales = PREFIX + "S" + tag + "-" + RUN;
        String prod = PREFIX + "P" + tag + "-" + RUN;
        String pm = PREFIX + "PM" + tag + "-" + RUN;
        String pn = PREFIX + "PN" + tag + "-" + RUN;
        String pmName = PREFIX + "核价物料名-" + tag;
        String symbol = recipeSymbol();

        assertEquals(0L, count("SELECT count(*) FROM material_recipe WHERE code IN (?1, ?2)", pm, pn),
                "构造自检：PM/PN 不在材质表");
        assertEquals(0L, count("SELECT count(*) FROM " + materialTable + " WHERE production_no = ?1", RECIPE_CODE),
                "前置：" + materialTable + " 不应有 " + RECIPE_CODE);

        // 销售料号 → 生产料号 的桥（本客户）
        quoteMaterial(cust, sales, PREFIX + "销售品-" + tag, prod);
        costMaterial(materialTable, pm, pmName);
        if (basic) {
            costBasicProcessFee(prod, 1, pm);
            costBasicProcessFee(prod, 2, RECIPE_CODE);
            costBasicProcessFee(prod, 3, pn);
        } else {
            costDetailOtherFixedFee(prod, 1, pm);
            costDetailOtherFixedFee(prod, 2, RECIPE_CODE);
            costDetailOtherFixedFee(prod, 3, pn);
        }

        // 🔄 2026-09-16 主线裁决（F-1，夹具输入，断言未改）：核价预览 partNo 传生产料号。
        //    依据：父任务 D-12「核价侧主轴 = 生产料号」。
        //    （留痕：首跑传销售料号得 0 行；归因时按「定位失败可读实现」例外核对过预览绑定，
        //     见 test-report-S2.md F-1 —— 该核对只用于归因，依据以 D-12 为准。）桥行保留、无害。
        Response pv = preview(config, cust, prod);
        if (pv.statusCode() == 200 && Integer.valueOf(0).equals(pv.jsonPath().getObject("rowCount", Integer.class))) {
            // 仅诊断（不判定）：0 行时打印编译产物，供主线归因「量具前提 vs 实现」
            Response cp = asAdmin().body(config)
                    .post("/api/cpq/components/" + previewComponentId + "/builder/compile").thenReturn();
            System.out.println("[T2.4 诊断·不判定] compile " + dialect + " → " + cp.statusCode() + "\n" + cp.asString());
        }
        Parsed p = parse(pv, "material_name", Set.of(pm, RECIPE_CODE, pn));
        assertTrue(p.myRowCount() >= 3, "AC-5④：造了 3 行，预览只找到 " + p.myRowCount() + "（行被丢）");
        assertEquals(pmName, p.onlyName(pm), "AC-5④ " + dialect + "：核价物料表有 ⇒ 取物料表名称");
        assertEquals(symbol, p.onlyName(RECIPE_CODE), "AC-5④ " + dialect + "：只在材质表 ⇒ 取 symbol");
        assertNull(p.onlyName(pn), "AC-5④ " + dialect + "：两边都没有 ⇒ 空");
        assertNoErrorDiagnostics(p);
    }
}
