package com.cpq.task260902;

import io.restassured.response.Response;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>task-260907 · AC-2（整组删除不得跨客户误删 —— 本任务最重要的一条）+ AC-5（三步序列）。</b>
 *
 * <h3>AC-2 原文（需求文档.md 第 3 节）</h3>
 * 操作：以 CUST-0004 导入料号 X，<b>再以 CUST-0001 导入同一料号 X</b>。<br>
 * 断言：
 * <ol>
 *   <li><b>本次导入所写入的每一张表</b>，其 customer_no 全 = 本次所选客户，NULL 行数 = <b>0</b>
 *       （不锁「15 张」这个数字）；</li>
 *   <li>ds_quote_material_bom 中该料号<b>两个客户的行并存，第一次导入的行未被删除</b>。</li>
 * </ol>
 *
 * <h3>为什么判据必须写成「两个客户并存」</h3>
 * 只断言 (1) <b>抓不住静默删除</b> —— (1) 在删除发生后照样成立。
 * 失败形态是<b>静默删数据，不是撞键报错</b>：13 张带版本表无任何业务唯一索引，
 * 隔离全靠 VersionedGroupWriter 的整组删除 + 重插。
 *
 * <h3>判据取的是状态本身，不是副作用</h3>
 * 「第一次导入的行未被删除」用两个互补的状态判据同时钉：
 * <ul>
 *   <li><b>id 集合包含</b>：第一次导入后拿到的 id，第二次导入后必须<b>逐个仍在主表里</b>
 *       （行被删掉再以新 id 重插，也算「第一次导入的行被删了」）；</li>
 *   <li><b>按客户计数不变</b>：customer_no = 客户A 的行数，两次导入前后<b>相等</b>
 *       （防「行还在但 customer_no 被就地改写成客户B」这种更隐蔽的形态）。</li>
 * </ul>
 */
@QuarkusTest
@DisplayName("task-260907 · AC-2 跨客户不误删 + AC-5 导入/维护/再导入三步序列")
class CustDimImportIsolationAcTest extends CustDimBase {

    /** 报价侧「本次导入可能写到」的主表面：全部 ds_quote_%（排除 _history / _record 镜像）。 */
    private List<String> quoteBaseTables() {
        List<String> out = new ArrayList<>();
        for (String t : dsQuoteTables()) {
            if (!t.endsWith("_history") && !t.endsWith("_record")) {
                out.add(t);
            }
        }
        return out;
    }

    /** 某张表里属于本轮轴值的 id 集合（不存在 material_no 列的表返回空集）。 */
    private Set<Long> idsOf(String table, String axis) {
        if (!columnExists(table, "material_no")) {
            return Set.of();
        }
        Set<Long> s = new LinkedHashSet<>();
        for (Object o : col("SELECT id FROM " + table + " WHERE material_no = '" + axis + "' ORDER BY id")) {
            s.add(((Number) o).longValue());
        }
        return s;
    }

    private long countByCustomer(String table, String axis, String customerNo) {
        if (!columnExists(table, "material_no") || !columnExists(table, "customer_no")) {
            return -1L;
        }
        return count("SELECT count(*) FROM " + table + " WHERE material_no = '" + axis
                + "' AND customer_no = '" + customerNo + "'");
    }

    // ============================================================
    // TS-00 阳性对照：夹具真的写进去了（先证明干预生效，再看颜色）
    // ============================================================

    @Test
    @DisplayName("TS-00 阳性对照：一份报价夹具导入后，本轮轴值在多张 ds_quote_* 主表里都有行 —— "
            + "否则 AC-2 的「每一张表」是在一个很小甚至空的面上空跑")
    void ts00_fixturePositiveControl() {
        Response r = importQuote(CUST_A, AXIS, CPN_A, "custdim-ts00.xlsx");
        assertStatus(r, 200, "TS-00 阳性对照");
        System.out.println("[TS-00] 导入 summary = " + r.jsonPath().getList("data.summary"));
        if (!substitutions.isEmpty()) {
            System.out.println("[TS-00] 夹具发生了主数据回退（正常路径应为空）：" + substitutions);
        }

        Map<String, Long> written = new LinkedHashMap<>();
        for (String t : quoteBaseTables()) {
            long n = columnExists(t, "material_no")
                    ? count("SELECT count(*) FROM " + t + " WHERE material_no = '" + AXIS + "'")
                    : (columnExists(t, "scheme_no")
                        ? count("SELECT count(*) FROM " + t + " WHERE scheme_no = '" + SCHEME_NO + "'")
                        : 0L);
            if (n > 0) {
                written.put(t, n);
            }
        }
        System.out.println("[TS-00] 本轮轴值 " + AXIS + " 落到的主表 = " + written);
        assertTrue(written.size() >= 3,
                "TS-00：本轮夹具只写到 " + written.size() + " 张主表（" + written + "）。"
                        + "AC-2 的「本次导入所写入的每一张表」判据需要足够宽的面，"
                        + "少于 3 张说明夹具没造出应有的数据 —— 这是夹具的问题，不是实现的问题。");
        assertTrue(written.containsKey(T_MATERIAL_BOM),
                "TS-00：ds_quote_material_bom 里没有本轮轴值的行，而 AC-2(2) 点名就验这张表。实际=" + written);
    }

    // ============================================================
    // AC-2 主体
    // ============================================================

    @Test
    @DisplayName("AC-2：以 CUST-0004 导入料号 X，再以 CUST-0001 导入同一料号 X —— "
            + "(1) 本次写入的每张表 customer_no 全 = 本次客户且 NULL 行数 0；"
            + "(2) 两个客户的行并存，第一次导入的行未被删除")
    void ac2_secondCustomerImportMustNotDeleteFirstCustomerRows() {
        // ── 第一次导入：客户 A ──────────────────────────────────────
        Response r1 = importQuote(CUST_A, AXIS, CPN_A, "custdim-ac2-a.xlsx");
        assertStatus(r1, 200, "AC-2 前置（第一次导入，客户 " + CUST_A + "）");

        Map<String, Set<Long>> idsAfter1 = new LinkedHashMap<>();
        Map<String, Long> countAafter1 = new LinkedHashMap<>();
        for (String t : quoteBaseTables()) {
            Set<Long> ids = idsOf(t, AXIS);
            if (!ids.isEmpty()) {
                idsAfter1.put(t, ids);
                countAafter1.put(t, countByCustomer(t, AXIS, CUST_A));
            }
        }
        // 守卫在前：没有第一批行，「第一批行没被删」恒真
        assertFalse(idsAfter1.isEmpty(),
                "AC-2 前置：第一次导入后主表里一行都没有本轮轴值 " + AXIS + "，"
                        + "「第一次导入的行未被删除」是恒真的空验证。响应=" + r1.asString());
        assertTrue(idsAfter1.containsKey(T_MATERIAL_BOM),
                "AC-2 前置：ds_quote_material_bom 没写进去，AC-2(2) 点名的表是空的。落表情况="
                        + idsAfter1.keySet());
        System.out.println("[AC-2] 第一次导入(" + CUST_A + ")后落表 = "
                + idsAfter1.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue().size()).toList());

        // 第一次导入本身也要满足 (1)：全部 = 客户 A
        for (Map.Entry<String, Set<Long>> e : idsAfter1.entrySet()) {
            String t = e.getKey();
            if (!columnExists(t, "customer_no")) {
                continue;
            }
            assertNonEmptyThenAllEqual(
                    strCol("SELECT customer_no FROM " + t + " WHERE material_no = '" + AXIS + "'"),
                    CUST_A, "AC-2(1) 第一次导入 · " + t);
        }

        // ── 第二次导入：客户 B，同一料号 X ────────────────────────────
        Response r2 = importQuote(CUST_B, AXIS, CPN_B, "custdim-ac2-b.xlsx");
        assertStatus(r2, 200, "AC-2（第二次导入，客户 " + CUST_B + "）");
        System.out.println("[AC-2] 第二次导入 summary = " + r2.jsonPath().getList("data.summary"));

        // ── 断言 (2)：两个客户并存 + 第一批行未被删 ────────────────────
        List<String> deleted = new ArrayList<>();
        List<String> overwritten = new ArrayList<>();
        for (Map.Entry<String, Set<Long>> e : idsAfter1.entrySet()) {
            String t = e.getKey();
            Set<Long> after2 = idsOf(t, AXIS);
            List<Long> gone = e.getValue().stream().filter(id -> !after2.contains(id)).toList();
            if (!gone.isEmpty()) {
                deleted.add(t + " 丢失 " + gone.size() + "/" + e.getValue().size() + " 行，id=" + gone);
            }
            long nowA = countByCustomer(t, AXIS, CUST_A);
            Long wasA = countAafter1.get(t);
            if (wasA != null && wasA >= 0 && nowA >= 0 && nowA != wasA) {
                overwritten.add(t + "：客户 " + CUST_A + " 的行数 " + wasA + " -> " + nowA);
            }
        }
        assertTrue(deleted.isEmpty(),
                "AC-2(2) 【静默删数据】：客户 " + CUST_B + " 导入料号 " + AXIS + " 之后，"
                        + "客户 " + CUST_A + " 第一次导入的行不见了：" + deleted
                        + "\n  这正是「只加列不扩轴」的失败形态 —— 不报错、不撞键、不留痕。"
                        + "\n  本项目出过跨客户串号故障（森萨塔），这不是理论风险。");
        assertTrue(overwritten.isEmpty(),
                "AC-2(2)：客户 " + CUST_A + " 的行数在客户 " + CUST_B + " 导入后变了："
                        + overwritten + "\n  行还在但 customer_no 被就地改写，同样是第一次导入的数据没了。");

        // ds_quote_material_bom：两个客户的行必须并存（AC-2(2) 的原文点名表）
        List<String> custs = strCol("SELECT DISTINCT customer_no FROM " + T_MATERIAL_BOM
                + " WHERE material_no = '" + AXIS + "' ORDER BY 1");
        assertFalse(custs.isEmpty(),
                "AC-2(2)：ds_quote_material_bom 里料号 " + AXIS + " 一行都没有，判据空跑");
        assertEquals(new TreeSet<>(List.of(CUST_A, CUST_B)), new TreeSet<>(custs),
                "AC-2(2)：ds_quote_material_bom 中料号 " + AXIS + " 的 customer_no 集合应为 {"
                        + CUST_A + ", " + CUST_B + "}，实际 = " + custs);
        System.out.println("[AC-2(2)] " + T_MATERIAL_BOM + " 中 " + AXIS + " 的客户集合 = " + custs
                + "，各自行数：" + CUST_A + "=" + countByCustomer(T_MATERIAL_BOM, AXIS, CUST_A)
                + " / " + CUST_B + "=" + countByCustomer(T_MATERIAL_BOM, AXIS, CUST_B));

        // 归档面：客户 A 的行不该被归档进 _history（归档 = 另一种「第一次导入的行没了」）
        if (tableExists(T_MATERIAL_BOM_HISTORY) && columnExists(T_MATERIAL_BOM_HISTORY, "customer_no")) {
            long archivedA = count("SELECT count(*) FROM " + T_MATERIAL_BOM_HISTORY
                    + " WHERE material_no = '" + AXIS + "' AND customer_no = '" + CUST_A + "'");
            assertEquals(0L, archivedA,
                    "AC-2(2)：客户 " + CUST_B + " 的导入把客户 " + CUST_A + " 的 " + archivedA
                            + " 行归档进了 _history —— 主表里没了，同样是跨客户误删（只是换了个去处）。");
        }

        // ── 断言 (1)：本次（第二次）导入新写入的每一行，customer_no 全 = CUST_B，NULL 行数 0 ──
        List<String> wrong = new ArrayList<>();
        List<String> nulls = new ArrayList<>();
        int tablesChecked = 0;
        for (String t : quoteBaseTables()) {
            if (!columnExists(t, "material_no") || !columnExists(t, "customer_no")) {
                continue;
            }
            Set<Long> before = idsAfter1.getOrDefault(t, Set.of());
            List<Object[]> newRows = rows("SELECT id, coalesce(customer_no,'<NULL>') FROM " + t
                    + " WHERE material_no = '" + AXIS + "'");
            List<String> newCusts = new ArrayList<>();
            for (Object[] row : newRows) {
                long id = ((Number) row[0]).longValue();
                if (before.contains(id)) {
                    continue;   // 第一次导入的行，不属于「本次导入所写入」
                }
                newCusts.add(String.valueOf(row[1]));
            }
            if (newCusts.isEmpty()) {
                continue;
            }
            tablesChecked++;
            Set<String> distinct = new LinkedHashSet<>(newCusts);
            if (distinct.contains("<NULL>")) {
                nulls.add(t + " 有 " + newCusts.stream().filter("<NULL>"::equals).count() + " 行 NULL");
            }
            if (!Set.of(CUST_B).containsAll(distinct)) {
                wrong.add(t + " 本次新写入行的 customer_no = " + distinct);
            }
        }
        assertTrue(tablesChecked > 0,
                "AC-2(1)：第二次导入没有在任何一张表里产生新行，「全 = 本次所选客户」是空验证。"
                        + "\n  可能形态：第二次导入把第一次的行原地改成了客户 B（那样 (2) 会先红），"
                        + "或者整份被拒收。第二次导入响应=" + r2.asString());
        System.out.println("[AC-2(1)] 第二次导入新写入行涉及 " + tablesChecked + " 张主表，全部 customer_no = "
                + CUST_B + "（NULL 0 行）");
        assertTrue(nulls.isEmpty(),
                "AC-2(1)：本次导入写入了 customer_no 为 NULL 的行：" + nulls
                        + "\n  典型真因不在 DDL（列确实建了），而在 insertAll / archive 两个写入点的列清单里。");
        assertTrue(wrong.isEmpty(),
                "AC-2(1)：本次导入（客户 " + CUST_B + "）写入的行里出现了别的客户号：" + wrong);

        // 免版本表 ds_quote_material 同样验：uq 扩成 (customer_no, material_no) 后两行应并存
        long matA = countByCustomer(T_MATERIAL, AXIS, CUST_A);
        long matB = countByCustomer(T_MATERIAL, AXIS, CUST_B);
        System.out.println("[AC-2 取证] " + T_MATERIAL + "（免版本，B-6 要把 uq 扩成 (customer_no, material_no)）："
                + CUST_A + "=" + matA + " 行 / " + CUST_B + "=" + matB + " 行");
        assertTrue(matA >= 1 && matB >= 1,
                "AC-2：免版本表 " + T_MATERIAL + " 里料号 " + AXIS + " 没有做到两个客户各一行（"
                        + CUST_A + "=" + matA + " / " + CUST_B + "=" + matB + "）。"
                        + "B-6 明写 uq_ds_quote_material(material_no) 要扩成 (customer_no, material_no)。");
    }

    // ============================================================
    // AC-5 序列：导入 A -> 维护端编辑 -> 导入 B
    // ============================================================

    @Test
    @DisplayName("AC-5（序列）：客户A导入 -> 在【基础资料维护】编辑该料号某行 -> 客户B导入同料号；"
            + "每一步后两个客户的数据各自完整，且编辑不得把行的 customer_no 改掉或置空")
    void ac5_importEditImportSequence() {
        // ── ① 客户 A 导入 ──
        assertStatus(importQuote(CUST_A, AXIS, CPN_A, "custdim-ac5-1.xlsx"), 200, "AC-5 步骤①");
        long aAfter1 = countByCustomer(T_MATERIAL_BOM, AXIS, CUST_A);
        assertTrue(aAfter1 > 0,
                "AC-5 步骤① 前置：客户 " + CUST_A + " 在 " + T_MATERIAL_BOM + " 一行都没有，后面全是空验证");
        System.out.println("[AC-5 ①] " + CUST_A + " 在 " + T_MATERIAL_BOM + " 有 " + aAfter1 + " 行");

        // ── ② 维护端编辑「物料BOM」的一行 ──
        String sheetKey = resolveSheetKey("物料BOM");
        Response rowsResp = maintRows(DatasetApi.QUOTE, AXIS, sheetKey, CUST_A);
        assertStatus(rowsResp, 200, "AC-5 步骤② 读行");
        Integer baseVersion = rowsResp.jsonPath().get("data.versionNo");
        List<Map<String, Object>> current = rowsResp.jsonPath().getList("data.rows");
        assertNotNull(baseVersion, "AC-5 步骤②：读行没拿到 data.versionNo，响应=" + rowsResp.asString());
        assertNotNull(current, "AC-5 步骤②：读行没拿到 data.rows，响应=" + rowsResp.asString());
        assertFalse(current.isEmpty(),
                "AC-5 步骤②：维护端读到 0 行，「编辑后数据仍完整」是空验证。响应=" + rowsResp.asString());

        List<Map<String, Object>> payload = new ArrayList<>();
        boolean touched = false;
        for (Map<String, Object> row : current) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.remove("row_fingerprint");
            copy.remove("customer_no");           // 契约：NAME 角色列与指纹不回传；客户号不是可编辑列
            if (!touched && copy.containsKey("item_seq")) {
                // 改一个明确可编辑的数值列，制造一次真实的「整组升版」
                copy.put("component_qty", "9.5");
                touched = true;
            }
            payload.add(copy);
        }
        assertTrue(touched,
                "AC-5 步骤②：读回的行里没有 item_seq 列，无法确定改哪一格 —— 这是夹具/契约不符，"
                        + "先停下报主线。行样例=" + current.get(0));

        Response put = maintSave(DatasetApi.QUOTE, AXIS, sheetKey, CUST_A, baseVersion, payload);
        assertStatus(put, 200, "AC-5 步骤② 保存");
        System.out.println("[AC-5 ②] 保存结果 = " + put.jsonPath().get("data.result")
                + " v" + put.jsonPath().get("data.versionNo"));

        // ②的断言：编辑后客户 A 的行仍在，且 customer_no 既没被改掉也没被置空
        assertNonEmptyThenAllEqual(
                strCol("SELECT coalesce(customer_no,'<NULL>') FROM " + T_MATERIAL_BOM
                        + " WHERE material_no = '" + AXIS + "'"),
                CUST_A,
                "AC-5(2)：维护端保存后 " + T_MATERIAL_BOM + " 的 customer_no");
        long aAfter2 = countByCustomer(T_MATERIAL_BOM, AXIS, CUST_A);
        assertEquals(aAfter1, aAfter2,
                "AC-5(2)：维护端保存后客户 " + CUST_A + " 的行数从 " + aAfter1 + " 变成 " + aAfter2);

        // ── ③ 客户 B 导入同料号 ──
        assertStatus(importQuote(CUST_B, AXIS, CPN_B, "custdim-ac5-3.xlsx"), 200, "AC-5 步骤③");
        long aAfter3 = countByCustomer(T_MATERIAL_BOM, AXIS, CUST_A);
        long bAfter3 = countByCustomer(T_MATERIAL_BOM, AXIS, CUST_B);
        System.out.println("[AC-5 ③] " + CUST_A + "=" + aAfter3 + " 行 / " + CUST_B + "=" + bAfter3 + " 行");
        assertEquals(aAfter2, aAfter3,
                "AC-5(3)：客户 " + CUST_B + " 导入后，客户 " + CUST_A + " 的行数从 " + aAfter2
                        + " 变成 " + aAfter3 + " —— 三步序列里第三步把第一步的数据动了。");
        assertTrue(bAfter3 > 0,
                "AC-5(3)：客户 " + CUST_B + " 导入后自己一行都没有，「各自完整」对 B 侧是空验证");
        assertEquals(0L, count("SELECT count(*) FROM " + T_MATERIAL_BOM + " WHERE material_no = '"
                        + AXIS + "' AND customer_no IS NULL"),
                "AC-5(3)：出现了 customer_no 为 NULL 的行");
    }

    /** 按 sheetName 反查 sheetKey（契约 §2：data.sheets[].sheetKey / sheetName）。 */
    private String resolveSheetKey(String sheetName) {
        Response r = DatasetApi.sheets(session(), DatasetApi.QUOTE);
        assertStatus(r, 200, "读 sheet 元数据");
        List<Map<String, Object>> sheets = r.jsonPath().getList("data.sheets");
        assertNotNull(sheets, "GET /dataset/quote/sheets 没拿到 data.sheets，响应=" + r.asString());
        assertFalse(sheets.isEmpty(), "GET /dataset/quote/sheets 返回 0 张 sheet");
        for (Map<String, Object> s : sheets) {
            if (sheetName.equals(String.valueOf(s.get("sheetName")))) {
                return String.valueOf(s.get("sheetKey"));
            }
        }
        throw new AssertionError("sheet 元数据里没有「" + sheetName + "」，实际="
                + sheets.stream().map(s -> s.get("sheetName")).toList());
    }

    // ============================================================
    // 取证（不是 AC）：两个客户都有数据之后，维护端编辑会发生什么
    // ============================================================

    /**
     * <b>这条不是 AC，是取证。</b> AC-5 的序列把「维护端编辑」放在客户 B 导入<b>之前</b>，
     * 所以编辑发生时轴值只属于一个客户。但轴一旦变复合，维护端的
     * PUT /parts/{axisValue}/sheets/{sheetKey}/rows <b>路径里装不下客户号</b>
     * （A0-2 裁决的 ?customerNo= 由 task-260907-产品管理客户过滤 承接，不在本任务内）。
     *
     * <p>本条如实测出「两个客户都有数据时，维护端保存会不会把另一个客户的行整组删掉」，
     * 结论交主线裁决归属，<b>不在本类里替谁下结论、也不顺手修</b>。
     */
    @Test
    @DisplayName("取证（非 AC）：同一料号两个客户都有数据时，维护端整组保存对另一个客户的行做了什么")
    void probe_maintenanceSaveWhenAxisSharedByTwoCustomers() {
        assertStatus(importQuote(CUST_A, AXIS, CPN_A, "custdim-probe-a.xlsx"), 200, "取证前置A");
        assertStatus(importQuote(CUST_B, AXIS, CPN_B, "custdim-probe-b.xlsx"), 200, "取证前置B");

        long aBefore = countByCustomer(T_MATERIAL_BOM, AXIS, CUST_A);
        long bBefore = countByCustomer(T_MATERIAL_BOM, AXIS, CUST_B);
        System.out.println("[取证] 保存前：" + CUST_A + "=" + aBefore + " / " + CUST_B + "=" + bBefore);
        assertTrue(aBefore > 0 && bBefore > 0,
                "取证前置：两个客户必须都有行，否则本条什么也证不了（" + aBefore + "/" + bBefore + "）");

        String sheetKey = resolveSheetKey("物料BOM");
        Response noCustResp = DatasetApi.rows(session(), DatasetApi.QUOTE, AXIS, sheetKey, null);
        System.out.println("[取证] 不带客户号读行 -> HTTP " + noCustResp.statusCode()
                + "，行数 = " + (noCustResp.statusCode() == 200
                        ? String.valueOf(noCustResp.jsonPath().getList("data.rows").size()) : "-"));
        Response rowsResp = maintRows(DatasetApi.QUOTE, AXIS, sheetKey, CUST_A);
        System.out.println("[取证] 带客户号(" + CUST_A + ")读到的行数 = "
                + (rowsResp.statusCode() == 200 ? rowsResp.jsonPath().getList("data.rows").size() : "HTTP "
                        + rowsResp.statusCode()));

        if (rowsResp.statusCode() != 200) {
            System.out.println("[取证] 维护端读行非 200，本条到此为止（不下结论）。响应=" + rowsResp.asString());
            return;
        }
        Integer baseVersion = rowsResp.jsonPath().get("data.versionNo");
        List<Map<String, Object>> current = rowsResp.jsonPath().getList("data.rows");
        if (baseVersion == null || current == null || current.isEmpty()) {
            System.out.println("[取证] 维护端读行为空，本条到此为止（不下结论）");
            return;
        }
        List<Map<String, Object>> payload = new ArrayList<>();
        for (Map<String, Object> row : current) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.remove("row_fingerprint");
            copy.remove("customer_no");
            payload.add(copy);
        }
        Response put = maintSave(DatasetApi.QUOTE, AXIS, sheetKey, CUST_A, baseVersion, payload);
        System.out.println("[取证] 带客户号保存 HTTP " + put.statusCode() + " -> " + put.asString());

        long aAfter = countByCustomer(T_MATERIAL_BOM, AXIS, CUST_A);
        long bAfter = countByCustomer(T_MATERIAL_BOM, AXIS, CUST_B);
        System.out.println("[取证] 保存后：" + CUST_A + "=" + aBefore + "->" + aAfter
                + " / " + CUST_B + "=" + bBefore + "->" + bAfter);
        System.out.println("[取证 结论素材] 维护端端点当前没有客户号入参（A0-2 的 ?customerNo= 归 "
                + "task-260907-产品管理客户过滤）。上面这组数字说明：同一料号跨两客户时，"
                + "维护端整组保存" + ((aAfter == aBefore && bAfter == bBefore) ? "没有" : "确实")
                + "动到了另一个客户的行。归属请主线裁决。");
    }
}
