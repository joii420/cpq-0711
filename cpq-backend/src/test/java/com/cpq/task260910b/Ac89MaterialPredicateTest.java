package com.cpq.task260910b;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260910 · 分片 S-B · <b>{@code TC-B4}（AC-8）/ {@code TC-B5}（AC-9）</b>
 * —— 材质来源改判据（{@code S-3}）。
 *
 * <h3>本组用例的核心（D-1）</h3>
 * 材质判据是「{@code input_material_no} 能否 JOIN 上 {@code material_recipe.code}」，
 * <b>不是 {@code output_material_type}</b>。该列实测 8 种取值
 * （{@code 成品} 2645 / {@code ASSEMBLY} 55 / {@code RECIPE} 39~41 / NULL 3 /
 *  {@code 产出类型1} 2 / {@code 产出类型2} 2 / {@code OUTSOURCED} 1 / {@code 零件} 1），
 * 是用户自填的业务字段。
 *
 * <p>断言指回 {@code 需求文档.md §③ S-3}；材质字段形状指回 {@code api.md §2.1}。🚫 未读实现代码。
 */
@QuarkusTest
@DisplayName("S-B · AC-8/9 材质多值与 JOIN 命中判据")
public class Ac89MaterialPredicateTest extends SbBase {

    @BeforeEach
    void seed() {
        seedReadSideFixtures();
    }

    // ══════════════════════════ TC-B4 · AC-8 ══════════════════════════

    /**
     * <b>AC-8 原文</b>：「取其中一个多材质料号 M ⇒ 已有零件搜索命中 M ⇒
     * 返回的材质字段含<b>该料号全部 N 个材质</b>（N = 该料号 {@code input_material_no}
     * 能 JOIN 上 {@code material_recipe.code} 的行数），🚫 不是 1 个」。
     *
     * <h3>🔴 前置数字已按 {@code D-17} 删除</h3>
     * AC 原文里的「2 个材质的 7 个料号、4 个材质的 2 个料号」<b>已删</b>，那是<b>忽略客户</b>的口径。
     * 按 {@code D-2} 之后查询的真实粒度 {@code (customer_no, material_no)} 实测为
     * <b>2 材质 → 5 个、4 材质 → 0 个</b> ⇒ <b>「4 材质」在该维度下根本不存在</b>，
     * 🚫 本用例<b>不许</b>断言 4 材质的场景（永远造不出来）。
     *
     * <p>⇒ 取数纪律（{@code D-17}）：用<b>本片自造</b>的 {@link #MULTI}（N=2：{@code 00006} / {@code 00168}，
     * 另有 1 行 JOIN 落空）作被测对象；<b>现网料号只作交叉验证</b> ——
     * 锚点 {@code (CUST-0004, S0013)} = 2 个材质（{@code 00017 AgCu70 45%} / {@code 992 AgNi11#-Ⅰ 55%}）。
     */
    @Test
    @DisplayName("TC-B4 · AC-8：多材质料号返回全部 N 个材质（🚫 不是 1 个）")
    void tcB4_ac8_allMaterialsReturned() {
        // ── ① 自造对照：N=2 ──
        long n = scalarLong("SELECT count(*) FROM ds_quote_material_bom b "
                + "JOIN material_recipe mr ON mr.code = b.input_material_no "
                + "WHERE b.material_no=?1 AND b.customer_no=?2", MULTI, CUST_4);
        assertEquals(2L, n, "前提自证：" + MULTI + " 的 N 应为 2 —— 造数没成功时本用例是零证据");

        Response res = searchParts(CUST_4, MULTI);
        assertReachedBusinessLayer(res, "AC-8 search-parts(" + CUST_4 + ", " + MULTI + ")");
        assertEquals(200, res.statusCode(), "AC-8：应 200，body=" + trunc(res.asString()));
        Set<String> got = materialCodesOf(res, MULTI);
        System.out.println("[TC-B4 实际] " + MULTI + " 返回的材质 code 集合=" + got
                + "（期望恰好 [00006, 00168]，N=" + n + "）");
        assertFalse(got.isEmpty(), "AC-8：" + MULTI + " 的材质字段为空 —— 空结果让后续断言恒真（假绿）。"
                + "\n  原始响应=" + trunc(res.asString()));
        assertEquals(Set.of(RECIPE_A, RECIPE_B), got,
                "AC-8：应带出全部 " + n + " 个材质 [00006, 00168]，实际=" + got
                + (got.size() == 1 ? "\n  ⇒ 只带出 1 个 = AC-8 点名要防的形态（单值未多值化）。" : ""));

        // ── ②（D-17 登记）粒度事实自证：(customer_no, material_no) 维度下「4 材质」应为 0 个
        //     —— 这一行是为了让「🚫 不许断言 4 材质」这条纪律在报告里有可核数字，不是断言。
        List<Object[]> dist = rowList("SELECT n, count(*) FROM ("
                + "  SELECT b.customer_no, b.material_no, count(*) n"
                + "    FROM ds_quote_material_bom b"
                + "    JOIN material_recipe mr ON mr.code = b.input_material_no"
                + "   GROUP BY b.customer_no, b.material_no) t"
                + " GROUP BY n ORDER BY n");
        StringBuilder sb = new StringBuilder();
        for (Object[] r : dist) sb.append(r[0]).append(" 材质→").append(r[1]).append(" 个; ");
        System.out.println("[TC-B4 D-17 粒度事实] 按 (customer_no, material_no) 分组的材质数分布：" + sb);

        // ── ③ 现网真实多材质料号交叉验证（D-17 点名锚点 (CUST-0004, S0013) = 2 个材质）──
        List<Object[]> expect = expectedCodes("S0013", CUST_4);
        if (expect.isEmpty()) {
            System.out.println("[TC-B4 交叉] ⚠️ 现网 S0013 已无可 JOIN 的 BOM 行，跳过交叉验证"
                    + "（不影响 ① 的结论；已在报告里登记为「环境数据漂移」）");
        } else {
            assertEquals(2, expect.size(), "D-17 交叉锚点前提：(CUST-0004, S0013) 应恰好 2 个材质，实际="
                    + expect.size() + " ⇒ 前提变了就不能再拿它当交叉验证锚点（🚫 不许顺着实际值改期望）");
            Set<String> want = new LinkedHashSet<>();
            for (Object[] r : expect) want.add(String.valueOf(r[0]));
            Response r2 = searchParts(CUST_4, "S0013");
            assertReachedBusinessLayer(r2, "AC-8 交叉 search-parts(S0013)");
            Set<String> got2 = materialCodesOf(r2, "S0013");
            System.out.println("[TC-B4 交叉] S0013@" + CUST_4 + " 期望=" + want + " 实际=" + got2);
            assertEquals(want, got2, "AC-8 交叉验证：S0013 的材质集合应为 " + want + "，实际=" + got2);
        }
    }

    // ══════════════════════════ TC-B5 · AC-9 ══════════════════════════

    /**
     * <b>AC-9① 原文</b>（反向多判）：「料号 {@code PERF0909-B00001} 的 BOM 行
     * {@code output_material_type='成品'}、{@code input_material_no='T260907T-RM01'}
     * （一个零件料号，JOIN {@code material_recipe} 落空）⇒ 该料号的材质字段为<b>空</b>
     * （{@code T260907T-RM01} 不是材质，🚫 不许当材质带出）」。
     *
     * <p>🔑 「材质为空」是阴性断言 ⇒ 必须先证明<b>该料号本身能被搜到</b>，
     * 否则「搜不到 ⇒ 材质当然为空」会让本用例恒绿（{@code testing.md §5.5} 形态③）。
     */
    @Test
    @DisplayName("TC-B5a · AC-9①：声明「成品」但 JOIN 落空 ⇒ 材质必须为空（PERF0909-B00001）")
    void tcB5a_ac9_joinMissMeansNoMaterial() {
        // ── 前提自证：该料号有 1 行 BOM、omt='成品'、input 不是材质 ──
        long bomRows = scalarLong("SELECT count(*) FROM ds_quote_material_bom "
                + "WHERE material_no=?1 AND customer_no=?2", AC9_MISS_PART, CUST_4);
        long joinHits = scalarLong("SELECT count(*) FROM ds_quote_material_bom b "
                + "JOIN material_recipe mr ON mr.code=b.input_material_no "
                + "WHERE b.material_no=?1 AND b.customer_no=?2", AC9_MISS_PART, CUST_4);
        String omt = scalarStr("SELECT output_material_type FROM ds_quote_material_bom "
                + "WHERE material_no=?1 AND customer_no=?2 ORDER BY item_seq LIMIT 1", AC9_MISS_PART, CUST_4);
        String input = scalarStr("SELECT input_material_no FROM ds_quote_material_bom "
                + "WHERE material_no=?1 AND customer_no=?2 ORDER BY item_seq LIMIT 1", AC9_MISS_PART, CUST_4);
        System.out.println("[TC-B5a 前提] " + AC9_MISS_PART + "@" + CUST_4 + " BOM 行=" + bomRows
                + " JOIN 命中=" + joinHits + " output_material_type=" + omt + " input=" + input);
        assertTrue(bomRows >= 1, "环境前提已变：" + AC9_MISS_PART + " 在 " + CUST_4
                + " 下应有 BOM 行（需求文档 §2.2 实测）。前提不成立 ⇒ 结论无效，🚫 不许读成缺陷。");
        assertEquals(0L, joinHits, "环境前提已变：" + AC9_MISS_PART + " 的 BOM 行现在能 JOIN 上材质了（"
                + joinHits + " 行）⇒ 「材质应为空」不再是正确期望。");
        assertEquals("成品", omt, "环境前提：该行 output_material_type 应为『成品』，实际=" + omt);
        assertEquals(AC9_MISS_INPUT, input, "环境前提：该行 input_material_no 应为 " + AC9_MISS_INPUT);

        // ── 阳性对照：该料号必须能被搜到（否则「材质为空」恒真）──
        Response res = searchParts(CUST_4, AC9_MISS_PART);
        assertReachedBusinessLayer(res, "AC-9① search-parts(" + AC9_MISS_PART + ")");
        assertEquals(200, res.statusCode(), "AC-9①：应 200，body=" + trunc(res.asString()));
        List<String> hits = partNosOf(res);
        System.out.println("[TC-B5a 阳性对照] 搜 " + AC9_MISS_PART + " → " + hits);
        assertNonEmpty(hits, "AC-9① 阳性对照：料号本身必须搜得到");
        assertTrue(hits.contains(AC9_MISS_PART), "AC-9① 阳性对照：结果里应含 " + AC9_MISS_PART
                + "，实际=" + hits + "\n  ⇒ 搜不到时「材质为空」是零证据（AC-9 有 LEFT JOIN 要求，"
                + "外购件与组合父料号没有材质行也不能被吞掉，api.md §2.1）。");

        // ── AC-9① 主断言：材质字段为空 ──
        Set<String> got = materialCodesOf(res, AC9_MISS_PART);
        System.out.println("[TC-B5a 主断言] " + AC9_MISS_PART + " 材质集合=" + got + "（期望空）");
        assertTrue(got.isEmpty(), "AC-9①【判据用错】" + AC9_MISS_PART + " 的材质字段应为空，实际带出 "
                + got + "\n  ⇒ " + AC9_MISS_INPUT + " 是零件料号、JOIN material_recipe 落空，"
                + "不许当材质带出（D-1：判据是 JOIN 命中，不是 output_material_type='成品'）。");
    }

    /**
     * <b>AC-9② 原文</b>（正向漏判）：「{@code output_material_type} 为 {@code (NULL)} 或
     * {@code 零件} 但 {@code input_material_no} <b>能</b> JOIN 上 {@code material_recipe} 的行
     * （实测共 3 行），其材质<b>必须被带出</b>（🚫 不许因为声明不是 {@code RECIPE} 而漏掉）」。
     *
     * <p>实测这 3 行是：{@code S0002}@{@code CUST-0001}（omt NULL，input {@code 992}）·
     * {@code S0002}@{@code CUST-0004}（同）· {@code T260907-M1}@{@code CUST-0004}
     * （omt {@code 零件}，input {@code 00006}）。
     * 三行逐个验，🚫 不许只验一行就下结论。
     */
    @Test
    @DisplayName("TC-B5b · AC-9②：声明 NULL / 零件 但 JOIN 命中 ⇒ 材质必须带出（实测 3 行逐个验）")
    void tcB5b_ac9_joinHitMustSurfaceRegardlessOfDeclaredType() {
        List<Object[]> rows = rowList(
                "SELECT b.customer_no, b.material_no, b.input_material_no, "
                + "       COALESCE(b.output_material_type,'(NULL)') "
                + "  FROM ds_quote_material_bom b "
                + "  JOIN material_recipe mr ON mr.code = b.input_material_no "
                + " WHERE (b.output_material_type IS NULL OR b.output_material_type = '零件') "
                + " ORDER BY b.material_no, b.customer_no, b.item_seq");
        System.out.println("[TC-B5b 前提] 「声明非 RECIPE 但 JOIN 命中」的行数=" + rows.size());
        for (Object[] r : rows) {
            System.out.println("   " + r[0] + " / " + r[1] + " / input=" + r[2] + " / omt=" + r[3]);
        }
        assertFalse(rows.isEmpty(), "🚨 环境前提已变：库里已没有「声明非 RECIPE 但 JOIN 命中」的行 "
                + "⇒ AC-9② 无样本可验，判定为『未验证』，🚫 不许当通过。"
                + "（需求文档 §AC-9② 实测为 3 行）");

        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (Object[] r : rows) {
            String cust = String.valueOf(r[0]);
            String part = String.valueOf(r[1]);
            String input = String.valueOf(r[2]);
            String omt = String.valueOf(r[3]);
            // 该料号必须在 ds_quote_material 里有该客户的行，否则 search-parts 本来就搜不到（不是漏判）
            if (scalarLong("SELECT count(*) FROM ds_quote_material WHERE material_no=?1 AND customer_no=?2",
                    part, cust) == 0) {
                System.out.println("[TC-B5b 跳过] " + part + "@" + cust
                        + " 在 ds_quote_material 无对应行 ⇒ 不属于 search-parts 的候选面");
                continue;
            }
            checked++;
            Response res = searchParts(cust, part);
            assertReachedBusinessLayer(res, "AC-9② search-parts(" + cust + ", " + part + ")");
            if (res.statusCode() != 200) {
                failures.add(part + "@" + cust + ": HTTP " + res.statusCode() + " " + trunc(res.asString()));
                continue;
            }
            List<String> hits = partNosOf(res);
            if (!hits.contains(part)) {
                failures.add(part + "@" + cust + ": 料号本身搜不到（阳性对照失败），实际=" + hits);
                continue;
            }
            Set<String> got = materialCodesOf(res, part);
            System.out.println("[TC-B5b 实际] " + part + "@" + cust + " omt=" + omt
                    + " input=" + input + " → 材质集合=" + got);
            if (!got.contains(input)) {
                failures.add(part + "@" + cust + "（omt=" + omt + "）: 期望材质含 " + input
                        + "，实际=" + got);
            }
        }
        assertTrue(checked > 0, "AC-9②：3 行样本一个都没进入断言（全被跳过）⇒ 本用例空跑，判定为未验证");
        assertTrue(failures.isEmpty(), "AC-9②【判据用错 · 漏判】共 " + failures.size() + " 处："
                + String.join("\n   ", failures)
                + "\n  ⇒ D-1：判据是 JOIN 命中，🚫 不许因 output_material_type 不是 RECIPE 而漏掉材质。");
    }

    /**
     * <b>AC-9 反向加固</b>：{@code output_material_type='RECIPE'} 但 JOIN 落空的行，
     * 材质<b>必须不出现</b>。
     *
     * <p>现网真实样本 {@code S-2120011659}@{@code CUST-0001}：4 行 BOM <b>全部</b>声明
     * {@code RECIPE}，其中 {@code 00006} / {@code 00168} JOIN 命中，
     * {@code 3110520789} / {@code 3112230067} 落空。
     * ⇒ 正确结果是「恰好 2 个材质」；若实现改用 {@code output_material_type='RECIPE'} 当判据，
     * 会带出 4 个（含两个僵尸），这是 {@code 需求文档 §2.2} 记的
     * {@code v_composite_child_materials} 同族缺陷。
     *
     * <p>另加本片自造的 {@link #MULTI} 第 3 行（{@link #NON_RECIPE_INPUT} 声明 {@code RECIPE}
     * 却落空）作不受现网漂移影响的对照。
     */
    @Test
    @DisplayName("TC-B5c · AC-9 加固：声明 RECIPE 但 JOIN 落空 ⇒ 🚫 不许当材质带出")
    void tcB5c_ac9_declaredRecipeButJoinMissMustNotSurface() {
        // ── ① 自造对照 ──
        Response mine = searchParts(CUST_4, MULTI);
        assertReachedBusinessLayer(mine, "AC-9 加固 search-parts(" + MULTI + ")");
        assertEquals(200, mine.statusCode(), "应 200，body=" + trunc(mine.asString()));
        Set<String> gotMine = materialCodesOf(mine, MULTI);
        System.out.println("[TC-B5c 自造] " + MULTI + " 材质集合=" + gotMine
                + "（" + NON_RECIPE_INPUT + " 声明 RECIPE 但 JOIN 落空，🚫 不许出现）");
        assertFalse(gotMine.isEmpty(), "自造对照：材质集合为空 ⇒ 「不含 X」恒真（假绿）。响应="
                + trunc(mine.asString()));
        assertFalse(gotMine.contains(NON_RECIPE_INPUT), "AC-9 加固【判据用错 · 多判】"
                + MULTI + " 带出了 " + NON_RECIPE_INPUT + "（声明 RECIPE 但 JOIN material_recipe 落空）"
                + "，实际集合=" + gotMine);

        // ── ② 现网真实样本 S-2120011659 ──
        String part = "S-2120011659";
        List<Object[]> hit = expectedCodes(part, CUST_1);
        long declared = scalarLong("SELECT count(*) FROM ds_quote_material_bom "
                + "WHERE material_no=?1 AND customer_no=?2 AND output_material_type='RECIPE'", part, CUST_1);
        System.out.println("[TC-B5c 现网前提] " + part + "@" + CUST_1
                + " 声明 RECIPE 的行=" + declared + "，其中 JOIN 命中=" + hit.size());
        if (hit.isEmpty() || declared <= hit.size()) {
            System.out.println("[TC-B5c 现网] ⚠️ 环境已无「声明 RECIPE 但 JOIN 落空」的对照行 "
                    + "⇒ 跳过现网样本（① 的自造对照仍然成立）");
            return;
        }
        Set<String> want = new LinkedHashSet<>();
        for (Object[] r : hit) want.add(String.valueOf(r[0]));
        Response res = searchParts(CUST_1, part);
        assertReachedBusinessLayer(res, "AC-9 加固 search-parts(" + part + ")");
        assertEquals(200, res.statusCode(), "应 200，body=" + trunc(res.asString()));
        Set<String> got = materialCodesOf(res, part);
        System.out.println("[TC-B5c 现网实际] " + part + "@" + CUST_1 + " 期望=" + want
                + "（" + want.size() + " 个）实际=" + got + "（" + got.size() + " 个）");
        assertFalse(got.isEmpty(), "现网样本材质集合为空 ⇒ 断言恒真。响应=" + trunc(res.asString()));
        assertEquals(want, got, "AC-9 加固：" + part + " 声明 RECIPE 共 " + declared + " 行，"
                + "但只有 " + want.size() + " 行能 JOIN 上材质 ⇒ 材质集合应恰好是 " + want
                + "，实际=" + got + "\n  ⇒ 多出来的是「声明 RECIPE 却 JOIN 落空」的僵尸行"
                + "（需求文档 §2.2 记的同族缺陷）。");
    }

    // ══════════════════════════ 解析助手 ══════════════════════════

    /** 该料号在该客户下「能 JOIN 上 material_recipe」的材质行（code, symbol, name）。 */
    protected List<Object[]> expectedCodes(String materialNo, String customerNo) {
        return rowList("SELECT mr.code, mr.symbol, mr.name FROM ds_quote_material_bom b "
                + "JOIN material_recipe mr ON mr.code = b.input_material_no "
                + "WHERE b.material_no=?1 AND b.customer_no=?2 ORDER BY b.item_seq", materialNo, customerNo);
    }

    @SuppressWarnings("unchecked")
    protected List<Object[]> rowList(String sql, Object... p) {
        jakarta.persistence.Query q = em.createNativeQuery(sql);
        for (int i = 0; i < p.length; i++) q.setParameter(i + 1, p[i]);
        List<?> raw = q.getResultList();
        List<Object[]> out = new ArrayList<>();
        for (Object o : raw) out.add(o instanceof Object[] ? (Object[]) o : new Object[]{o});
        return out;
    }

    protected List<String> partNosOf(Response res) {
        io.restassured.path.json.JsonPath jp = res.jsonPath();
        List<String> d = jp.getList("hfPartNo", String.class);
        if (d != null && !d.isEmpty()) return d;
        List<String> w = jp.getList("data.hfPartNo", String.class);
        return w == null ? List.of() : w;
    }

    /**
     * 取某条搜索结果的材质 code 集合。
     *
     * <p>形状以 {@code api.md §2.1} 为准：{@code materials[]}，元素含
     * {@code recipeCode / recipeSymbol / recipeName / recipeSpec / recipeType}。
     * <p>⚠️ {@code api.md} 的 <b>R-2 待定项</b>写明「若前端更希望单串展示，可改为
     * {@code materialsLabel}」⇒ 本方法同时接受该形态（按 {@code /} 拆分后按 symbol 反查 code），
     * 并对<b>旧的单值形态</b>（顶层 {@code recipeCode}）给出<b>点名报错</b>而不是静默降级 ——
     * 静默降级会让 AC-8 在「未多值化」时照样绿。
     */
    protected Set<String> materialCodesOf(Response res, String partNo) {
        JsonPath jp = res.jsonPath();
        List<Map<String, Object>> items = jp.getList("$");
        if (items == null || items.isEmpty()) {
            items = jp.getList("data");
        }
        assertTrue(items != null, "响应不是数组也不是 {data:[...]}：" + trunc(res.asString()));
        Map<String, Object> row = null;
        for (Map<String, Object> it : items) {
            if (partNo.equals(String.valueOf(it.get("hfPartNo")))) { row = it; break; }
        }
        assertTrue(row != null, "响应里找不到料号 " + partNo + " 的那条结果 ⇒ 材质断言会空跑。响应="
                + trunc(res.asString()));

        Object mats = row.get("materials");
        if (mats instanceof List<?> list) {
            Set<String> out = new LinkedHashSet<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Object c = m.get("recipeCode");
                    if (c != null && !String.valueOf(c).isBlank()) out.add(String.valueOf(c));
                }
            }
            return out;
        }
        Object label = row.get("materialsLabel");
        if (label != null) {
            Set<String> out = new LinkedHashSet<>();
            for (String sym : String.valueOf(label).split("\\s*/\\s*")) {
                if (sym.isBlank()) continue;
                String code = scalarStr("SELECT code FROM material_recipe WHERE symbol=?1 LIMIT 1", sym.trim());
                out.add(code != null ? code : sym.trim());
            }
            System.out.println("[materialsLabel 形态] " + partNo + " label=" + label + " → codes=" + out);
            return out;
        }
        if (row.containsKey("recipeCode") || row.containsKey("recipeSymbol")) {
            throw new AssertionError("🔴 契约未落地：" + partNo + " 的结果仍是 api.md §2.1 已删除的"
                    + "<b>单值</b>材质形态（顶层 recipeCode/recipeSymbol），没有 materials[] 也没有 materialsLabel。"
                    + "\n  ⇒ AC-8「多材质全部带出」在此形态下结构上不可能达成。实际这条=" + row);
        }
        throw new AssertionError("响应里 " + partNo + " 既无 materials[] 也无 materialsLabel、"
                + "也无旧单值字段 —— 材质信息完全缺失，AC-8/AC-9 无从判定。实际这条=" + row);
    }
}
