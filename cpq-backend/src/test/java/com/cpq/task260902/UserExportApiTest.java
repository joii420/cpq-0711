package com.cpq.task260902;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260902 · C 组 · 用户导出（T-12 / T-13，AC-12 / AC-13）。
 *
 * <p>断言派生自 {@code 需求文档.md §③ C 组} + {@code api.md § B-3} + {@code 原型图/3-用户列表.html} 状态 C。
 */
@QuarkusTest
class UserExportApiTest extends Task260902TestBase {

    private static final String EXPORT = "/api/cpq/users/export";

    /** AC-12：前 6 列＝导入模板列（可回导），后 2 列只读。 */
    private static final List<String> EXPECTED_HEADER = List.of(
            "用户名", "姓名", "邮箱", "角色", "区域", "部门", "状态", "创建时间");

    /** AC-12：角色列的 4 个中文标签。 */
    private static final Set<String> ROLE_LABELS = new LinkedHashSet<>(
            List.of("系统管理员", "销售经理", "销售代表", "财务"));

    // ═══════════════════ T-12 → AC-12：列结构 ═══════════════════

    /**
     * T-12 → AC-12：8 个列名逐字；角色列写中文标签；状态列写 启用/停用；
     * 🚫 文件里不含 {@code password} / {@code id} 任何列。
     */
    @Test
    void t12_userExportColumns_andNoSecretColumns() {
        Response res = given().when().get(EXPORT).thenReturn();
        assertEquals(200, res.statusCode(),
                "AC-12：管理员导出应 200，实际 " + res.statusCode() + " body=" + res.asString());
        byte[] xlsx = res.asByteArray();

        List<String> h = header(xlsx);
        System.out.println("[AC-12] 表头 = " + h);
        assertEquals(EXPECTED_HEADER, h,
                "AC-12：表头必须逐字为 " + EXPECTED_HEADER + "（前 6 列＝导入模板列，后 2 列只读）");

        // 🚫 不得出现任何密码 / 主键列（原型图 状态 C 的红线）
        for (String col : h) {
            String lower = col.toLowerCase(Locale.ROOT);
            assertFalse(lower.contains("password") || lower.contains("密码") || lower.contains("哈希")
                            || lower.equals("id") || lower.contains("hash"),
                    "AC-12：🚨 导出文件出现了敏感/内部列「" + col + "」—— 不含 id、不含任何密码字段");
        }

        Map<String, Integer> idx = headerIndex(xlsx);
        List<List<String>> rows = dataRows(xlsx);
        System.out.println("[AC-12] 数据行数 = " + rows.size()
                + (rows.isEmpty() ? "" : "，首行 = " + rows.get(0)));
        assertTrue(!rows.isEmpty(), "AC-12 前置：用户表为空 —— 逐行断言会空跑（假绿）");

        for (List<String> r : rows) {
            String username = cell(r, idx, "用户名");
            String roleLabel = cell(r, idx, "角色");
            assertTrue(ROLE_LABELS.contains(roleLabel),
                    "AC-12：角色列必须写中文标签之一 " + ROLE_LABELS + "，用户 " + username
                    + " 实际写了「" + roleLabel + "」（SYSTEM_ADMIN 这类枚举是实现细节，不该进导出文件）");

            String status = cell(r, idx, "状态");
            assertTrue("启用".equals(status) || "停用".equals(status),
                    "AC-12：状态列必须写 启用/停用，用户 " + username + " 实际「" + status + "」");

            String db = scalar("SELECT full_name || '~#~' || email || '~#~' || role || '~#~' || status " +
                    "  FROM \"user\" WHERE username = '" + username.replace("'", "''") + "'");
            assertNotNull(db, "AC-12：导出里的用户名 " + username + " 在库里查不到");
            String[] p = db.split("~#~", -1);
            assertEquals(p[0], cell(r, idx, "姓名"), "AC-12：姓名列（" + username + "）");
            assertEquals(p[1], cell(r, idx, "邮箱"), "AC-12：邮箱列（" + username + "）");
            assertEquals(roleLabelOf(p[2]), roleLabel,
                    "AC-12：角色标签映射（" + username + " 库值 " + p[2] + "）");
            assertEquals("ACTIVE".equals(p[3]) ? "启用" : "停用", status,
                    "AC-12：状态标签映射（" + username + " 库值 " + p[3] + "）");

            // 创建时间：yyyy-MM-dd HH:mm:ss（api.md § B-3）
            String created = cell(r, idx, "创建时间");
            assertTrue(created.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                    "AC-12：创建时间格式应为 yyyy-MM-dd HH:mm:ss，用户 " + username
                    + " 实际「" + created + "」");
        }

        // 区域 / 部门两表当前均 0 条（需求文档 §② 实测），因此这两列应全空 —— 但列必须在
        long regions = count("SELECT count(*) FROM region");
        long depts = count("SELECT count(*) FROM department");
        System.out.println("[AC-12] region=" + regions + " department=" + depts);
        if (regions == 0 && depts == 0) {
            for (List<String> r : rows) {
                assertTrue(cell(r, idx, "区域").isBlank() && cell(r, idx, "部门").isBlank(),
                        "AC-12：区域/部门两表均 0 条时，这两列应为空，用户 " + cell(r, idx, "用户名")
                        + " 却写了 区域=" + cell(r, idx, "区域") + " 部门=" + cell(r, idx, "部门"));
            }
        }
    }

    /** api.md § B-3：角色 → 中文标签。 */
    private String roleLabelOf(String role) {
        switch (role) {
            case "SYSTEM_ADMIN":    return "系统管理员";
            case "SALES_MANAGER":   return "销售经理";
            case "SALES_REP":       return "销售代表";
            case "PRICING_MANAGER": return "财务";
            default: throw new AssertionError("未知 role = " + role + "（DB 的 chk_user_role 只允许 4 种）");
        }
    }

    // ═══════════════════ T-13 → AC-13：跟随筛选 + 全量 ═══════════════════

    /**
     * T-13 → AC-13：不加筛选 ⇒ 行数 == 基准查询④；
     * {@code role=SALES_REP} ⇒ 每行角色列均为「销售代表」，且集合 == 同参数 SQL。
     */
    @Test
    void t13_userExportFollowsFilter_andIsFullSet() {
        long before = baseUserCount();
        byte[] all = exportWith();
        long after = baseUserCount();
        int fileRows = dataRows(all).size();
        System.out.printf("[AC-13] 不筛选导出行数 = %d ｜ 基准查询④ = %d~%d%n", fileRows, before, after);
        assertTrue(fileRows > 0, "AC-13：不筛选导出 0 行 —— 断言空跑（假绿）");
        assertTrue(fileRows >= Math.min(before, after) && fileRows <= Math.max(before, after),
                "AC-13：不筛选导出行数应 == 基准查询④（" + before + "→" + after + "），实际 " + fileRows);

        // role=SALES_REP
        byte[] reps = exportWith("role", "SALES_REP");
        Map<String, Integer> idx = headerIndex(reps);
        List<String> fileNames = new ArrayList<>();
        for (List<String> r : dataRows(reps)) {
            fileNames.add(cell(r, idx, "用户名"));
            assertEquals("销售代表", cell(r, idx, "角色"),
                    "AC-13：筛「销售代表」后每行角色列都必须是 销售代表，用户 "
                    + cell(r, idx, "用户名") + " 却是「" + cell(r, idx, "角色") + "」");
        }
        Set<String> dbNames = new LinkedHashSet<>(strList(
                "SELECT username FROM \"user\" WHERE role='SALES_REP' ORDER BY username"));
        System.out.println("[AC-13] role=SALES_REP 文件 = " + fileNames + " / 库 = " + dbNames);
        assertNonEmpty(new ArrayList<>(dbNames), "AC-13 前置：库里的 SALES_REP 用户");
        assertEquals(dbNames, new LinkedHashSet<>(fileNames),
                "AC-13：筛「销售代表」后导出集合 == 同条件下库里的集合");
        assertTrue(fileNames.size() < fileRows,
                "AC-13：筛选后行数应严格小于全量（" + fileNames.size() + " vs " + fileRows
                + "）—— 相等说明筛选参数没生效");
    }

    /** AC-23 后端兜底。 */
    @Test
    void t13b_emptyFilterResult_returns200WithHeaderOnly() {
        byte[] xlsx = exportWith("keyword", "zzz不存在zzz");
        System.out.println("[AC-23·用户后端] 表头 = " + header(xlsx) + " 行数 = " + dataRows(xlsx).size());
        assertEquals(EXPECTED_HEADER, header(xlsx), "AC-23：空结果也必须带完整 8 列表头");
        assertEquals(0, dataRows(xlsx).size(), "AC-23：搜 zzz不存在zzz 应导出 0 数据行");
    }

    private byte[] exportWith(String... kv) {
        var req = given();
        for (int i = 0; i + 1 < kv.length; i += 2) req = req.queryParam(kv[i], kv[i + 1]);
        Response res = req.when().get(EXPORT).thenReturn();
        assertEquals(200, res.statusCode(),
                "用户导出应 200，实际 " + res.statusCode() + " body=" + res.asString());
        return res.asByteArray();
    }
}
