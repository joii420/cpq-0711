package com.cpq.task260902;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260902 · B 组 · 工序导出（T-10 / T-11，AC-10 / AC-11）。
 *
 * <p>断言派生自 {@code 需求文档.md §③ B 组} + {@code api.md § B-2} + {@code 原型图/2-工序页签-工具栏.html}。
 *
 * <p>🚨 <b>列名刻意与页面表头不同名</b>（AC-10 / 原型图 状态 D 的警示条）：
 * 页面显示「工序<b>分类</b>」「标准<b>货币</b>」，导出必须写「工序<b>类别</b>」「标准<b>币种</b>」——
 * 那是 {@code ProcessMasterImportService} 的 {@code COL_*} 常量，用页面表头导出的文件回导时这两列会被丢弃。
 */
@QuarkusTest
class ProcessExportApiTest extends Task260902TestBase {

    private static final String EXPORT = "/api/cpq/v6/process-master/export";

    /** AC-10 的 7 个列名，逐字取自 api.md § B-2。工序导入按<b>列名</b>匹配，顺序无关，但列名必须同名。 */
    private static final List<String> EXPECTED_HEADER = List.of(
            "工序编号", "工序名称", "工序类别", "是否外协", "标准币种", "标准单位", "默认不良率");

    // ═══════════════════ T-10 → AC-10：列结构 ═══════════════════

    /**
     * T-10 → AC-10：7 个列名逐字；「是否外协」写 {@code 是/否}（不是 true/false）；
     * 「默认不良率」写原始小数（{@code 0.01}，不是 {@code 1.00%}）。
     */
    @Test
    void t10_processExportColumns_matchImportColumnConstants() {
        Response res = given().when().get(EXPORT).thenReturn();
        assertEquals(200, res.statusCode(),
                "AC-10：管理员导出应 200，实际 " + res.statusCode() + " body=" + res.asString());
        byte[] xlsx = res.asByteArray();

        List<String> h = header(xlsx);
        System.out.println("[AC-10] 表头 = " + h);
        assertEquals(EXPECTED_HEADER, h.subList(0, Math.min(EXPECTED_HEADER.size(), h.size())),
                "AC-10：🚨 7 个列名必须逐字为 " + EXPECTED_HEADER
                + " —— 尤其「工序类别」不是「工序分类」、「标准币种」不是「标准货币」，"
                + "用页面表头导出的文件回导时这两列会被当未知列丢弃");
        assertEquals(EXPECTED_HEADER.size(), h.size(),
                "AC-10：导出应恰好 7 列，实际 " + h.size() + " 列 = " + h);

        Map<String, Integer> idx = headerIndex(xlsx);
        List<List<String>> rows = dataRows(xlsx);
        System.out.println("[AC-10] 数据行数 = " + rows.size()
                + (rows.isEmpty() ? "" : "，首行 = " + rows.get(0)));
        assertTrue(!rows.isEmpty(),
                "AC-10 前置：工序表为空 —— 下面的逐行断言会空跑（假绿）。请先在库里保留至少 1 条工序。");

        for (List<String> r : rows) {
            String no = cell(r, idx, "工序编号");
            String out = cell(r, idx, "是否外协");
            assertTrue("是".equals(out) || "否".equals(out),
                    "AC-10：「是否外协」必须写中文 是/否，工序 " + no + " 实际写了「" + out + "」"
                    + "（true/false 是实现细节，不该出现在给人看的文件里）");

            String rate = cell(r, idx, "默认不良率");
            if (!rate.isBlank()) {
                assertFalse(rate.contains("%"),
                        "AC-10：「默认不良率」必须写原始小数（如 0.01），工序 " + no
                        + " 实际写了「" + rate + "」—— 百分号串回导时解析失败");
                BigDecimal v = decimal(rate, "AC-10 默认不良率(" + no + ")");
                String db = scalar("SELECT default_defect_rate::text FROM process_master " +
                        "WHERE process_no = '" + no.replace("'", "''") + "'");
                if (db != null) {
                    assertEquals(0, v.compareTo(new BigDecimal(db)),
                            "AC-10：默认不良率应等于库值，工序 " + no + " 库=" + db + " 文件=" + v);
                }
            }

            // 逐字段与库比对（工序编号/名称/类别/币种/单位）
            String dbRow = scalar(
                    "SELECT coalesce(process_name,'') || '~#~' || coalesce(process_category,'') || '~#~' || " +
                    "       coalesce(standard_currency,'') || '~#~' || coalesce(standard_unit,'') || '~#~' || " +
                    "       (CASE WHEN is_outsource THEN '是' ELSE '否' END) " +
                    "  FROM process_master WHERE process_no = '" + no.replace("'", "''") + "'");
            assertNotNull(dbRow, "AC-10：导出里的工序编号 " + no + " 在库里查不到");
            String[] p = dbRow.split("~#~", -1);
            assertEquals(p[0], cell(r, idx, "工序名称"), "AC-10：工序名称列（" + no + "）");
            assertEquals(p[1], cell(r, idx, "工序类别"), "AC-10：工序类别列取 process_category（" + no + "）");
            assertEquals(p[2], cell(r, idx, "标准币种"), "AC-10：标准币种列取 standard_currency（" + no + "）");
            assertEquals(p[3], cell(r, idx, "标准单位"), "AC-10：标准单位列取 standard_unit（" + no + "）");
            assertEquals(p[4], out, "AC-10：是否外协列（" + no + "）");
        }
    }

    // ═══════════════════ T-11 → AC-11：跟随筛选 + 全量 ═══════════════════

    /**
     * T-11 → AC-11：不加筛选导出 ⇒ 数据行数 == 基准查询③；
     * 「是否外协」筛为「外协」后 ⇒ 每行「是否外协」均为 {@code 是}。
     *
     * <p>🚨 <b>本用例自己造一条外协工序</b>：现网 {@code process_master} 全部 {@code is_outsource=false}
     * （2026-09-02 实测 2 条，全 f）。不造数的话「筛外协」只能验到空集 —— 用例照样绿，
     * 但一条断言都没执行（testing.md §3 假绿第一类）。
     *
     * <p><b>登记（test.md §1 全局状态）</b>：向 {@code process_master} 写入 1 行
     * {@code t260902_out}，{@code @AfterEach} 精确删除。
     */
    @Test
    void t11_processExportFollowsFilter_andIsFullSet() {
        seedProcess("out", "T260902外协测试工序", "外协加工", true, "CNY", "PCS", "0.02");

        // ① 不传参 ⇒ 全量（== 基准查询③）
        long before = baseProcessCount();
        byte[] all = exportWith();
        long after = baseProcessCount();
        int fileRows = dataRows(all).size();
        System.out.printf("[AC-11] 不筛选导出行数 = %d ｜ 基准查询③ = %d~%d%n", fileRows, before, after);
        assertTrue(fileRows > 0, "AC-11：不筛选导出 0 行 —— 断言空跑（假绿）");
        assertTrue(fileRows >= Math.min(before, after) && fileRows <= Math.max(before, after),
                "AC-11：不筛选导出的数据行数应 == 基准查询③（" + before + "→" + after + "），实际 " + fileRows);

        // ② isOutsource=true ⇒ 每行都是「是」，且集合 == 同参数 SQL
        byte[] outsourced = exportWith("isOutsource", "true");
        Map<String, Integer> idx = headerIndex(outsourced);
        List<List<String>> rows = dataRows(outsourced);
        List<String> fileNos = new ArrayList<>();
        for (List<String> r : rows) {
            fileNos.add(cell(r, idx, "工序编号"));
            assertEquals("是", cell(r, idx, "是否外协"),
                    "AC-11：筛「外协」后每行「是否外协」都必须是 是，工序 "
                    + cell(r, idx, "工序编号") + " 却是「" + cell(r, idx, "是否外协") + "」");
        }
        Set<String> dbNos = new LinkedHashSet<>(strList(
                "SELECT process_no FROM process_master WHERE is_outsource = true ORDER BY process_no"));
        System.out.println("[AC-11] isOutsource=true 文件 = " + fileNos + " / 库 = " + dbNos);
        assertNonEmpty(new ArrayList<>(dbNos), "AC-11 前置：库里的外协工序（本用例已造数，应非空）");
        assertEquals(dbNos, new LinkedHashSet<>(fileNos),
                "AC-11：筛「外协」后导出的工序集合 == 同一条件下库里的集合");
        assertTrue(fileNos.size() < fileRows,
                "AC-11：筛选后的行数应严格小于全量（" + fileNos.size() + " vs " + fileRows
                + "）—— 相等说明筛选参数根本没生效");

        // ③ 反向：isOutsource=false 里不能混进外协工序
        byte[] inHouse = exportWith("isOutsource", "false");
        Map<String, Integer> idx2 = headerIndex(inHouse);
        List<List<String>> rows2 = dataRows(inHouse);
        assertTrue(!rows2.isEmpty(), "AC-11 前置：非外协工序为 0 条，反向断言会空跑");
        for (List<String> r : rows2) {
            assertEquals("否", cell(r, idx2, "是否外协"),
                    "AC-11：筛「非外协」的导出里混进了外协工序 " + cell(r, idx2, "工序编号"));
        }
    }

    /** AC-23 后端兜底：筛出 0 条时仍 200 + 只有表头。 */
    @Test
    void t11b_emptyFilterResult_returns200WithHeaderOnly() {
        byte[] xlsx = exportWith("keyword", "zzz不存在zzz");
        System.out.println("[AC-23·工序后端] 表头 = " + header(xlsx) + " 行数 = " + dataRows(xlsx).size());
        assertEquals(EXPECTED_HEADER, header(xlsx), "AC-23：空结果也必须带完整 7 列表头");
        assertEquals(0, dataRows(xlsx).size(), "AC-23：搜 zzz不存在zzz 应导出 0 数据行");
    }

    private byte[] exportWith(String... kv) {
        var req = given();
        for (int i = 0; i + 1 < kv.length; i += 2) req = req.queryParam(kv[i], kv[i + 1]);
        Response res = req.when().get(EXPORT).thenReturn();
        assertEquals(200, res.statusCode(),
                "工序导出应 200，实际 " + res.statusCode() + " body=" + res.asString());
        return res.asByteArray();
    }
}
