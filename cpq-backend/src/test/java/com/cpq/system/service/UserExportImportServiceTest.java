package com.cpq.system.service;

import com.cpq.system.dto.UserImportReportDTO;
import com.cpq.system.entity.User;
import com.cpq.system.exception.UserApiException;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用户导出 / 导入模板 / 批量导入测试（task-260902 · B-3 / B-4 / B-5）。
 *
 * <p>造数一律用本任务专属前缀 {@code t260902_}；{@code @TestTransaction} 每个用例独立事务并<b>回滚</b>，
 * 不在共享开发库 {@code cpq_db_0724} 留残留。
 * 🚫 没有任何清库 / {@code TRUNCATE} / 无 WHERE 的 DELETE。
 */
@QuarkusTest
public class UserExportImportServiceTest {

    private static final String P = "t260902_";

    @Inject
    UserExportImportService service;

    @Inject
    EntityManager em;

    // ──────────────────────────── 构造 xlsx ────────────────────────────

    /** rows: [用户名, 姓名, 邮箱, 角色, 区域, 部门] */
    private byte[] build(String[][] rows) throws Exception {
        return build(UserExportImportService.IMPORT_HEADER.toArray(new String[0]), rows);
    }

    private byte[] build(String[] header, String[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet(UserExportImportService.SHEET_NAME);
            Row h = s.createRow(0);
            for (int i = 0; i < header.length; i++) h.createCell(i).setCellValue(header[i]);
            for (int i = 0; i < rows.length; i++) {
                Row r = s.createRow(i + 1);
                for (int j = 0; j < rows[i].length; j++) r.createCell(j).setCellValue(rows[i][j]);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private Sheet read(byte[] xlsx) throws Exception {
        Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx));
        return wb.getSheetAt(0);
    }

    private String str(Cell c) {
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> new BigDecimal(Double.toString(c.getNumericCellValue()))
                                .stripTrailingZeros().toPlainString();
            default -> "";
        };
    }

    private String[] row(String u, String name, String email, String role) {
        return new String[]{u, name, email, role, "", ""};
    }

    // ──────────────────────────── B-4 模板 ────────────────────────────

    /** AC-14：模板 6 列 + 1 行示例 + 角色列表头带批注（列出 4 个合法值）。 */
    @Test
    void templateStructure() throws Exception {
        Sheet s = read(service.generateTemplate());
        Row h = s.getRow(0);
        List<String> expected = List.of("用户名", "姓名", "邮箱", "角色", "区域", "部门");
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), str(h.getCell(i)), "第 " + (i + 1) + " 列表头");
        }
        assertEquals(1, s.getLastRowNum(), "含 1 行示例数据");
        assertFalse(str(s.getRow(1).getCell(0)).isBlank(), "示例行用户名非空");

        assertNotNull(h.getCell(3).getCellComment(), "角色列表头必须挂批注");
        String note = h.getCell(3).getCellComment().getString().getString();
        for (String label : List.of("系统管理员", "销售经理", "销售代表", "财务")) {
            assertTrue(note.contains(label), "批注应列出合法角色「" + label + "」，实际=" + note);
        }
    }

    // ──────────────────────────── B-5 导入 ────────────────────────────

    /** AC-15：3 个全新用户 → 新增 3 / 跳过 0；密码互不相同；is_first_login=true、status=ACTIVE。 */
    @Test
    @TestTransaction
    void createsNewUsersWithDistinctPasswords() throws Exception {
        long before = User.count();
        UserImportReportDTO r = service.importUsers(build(new String[][]{
            row(P + "a", "张明", P + "a@example.com", "销售代表"),
            row(P + "b", "李强", P + "b@example.com", "销售经理"),
            row(P + "c", "王芳", P + "c@example.com", "财务"),
        }));

        assertEquals(3, r.createdCount, "应新增 3 条");
        assertEquals(0, r.skippedCount, "不应有跳过：" + r.skipped);
        assertEquals(3, r.totalRows);
        assertEquals(before + 3, User.count(), "库内条数增量必须是 3");

        Set<String> pwds = new HashSet<>();
        for (UserImportReportDTO.CreatedUser c : r.created) {
            assertNotNull(c.initialPassword, "每行必须回显初始密码");
            assertFalse(c.initialPassword.isBlank());
            pwds.add(c.initialPassword);
        }
        assertEquals(3, pwds.size(), "3 个密码必须互不相同");

        assertEquals("SALES_REP", r.created.get(0).role);
        assertEquals("销售代表", r.created.get(0).roleLabel);
        assertEquals("PRICING_MANAGER", r.created.get(2).role, "「财务」映射到 PRICING_MANAGER");

        User u = User.find("username", P + "a").firstResult();
        assertNotNull(u);
        assertTrue(u.isFirstLogin, "新用户必须 is_first_login=true（首登强制改密）");
        assertEquals("ACTIVE", u.status);
        assertEquals("张明", u.fullName);
        assertNotNull(u.passwordHash);
        assertFalse(u.passwordHash.equals(r.created.get(0).initialPassword),
            "库里存的必须是哈希，不是明文");
        assertTrue(u.passwordHash.startsWith("$2"), "沿用 BCrypt（与新增用户同一条路径）");
    }

    /** AC-16：同一份文件再导一次 ⇒ 新增 0 / 跳过 3「用户名已存在」，且既有字段一个字都没被改。 */
    @Test
    @TestTransaction
    void reimportSkipsAndNeverUpdates() throws Exception {
        byte[] file = build(new String[][]{
            row(P + "a", "张明", P + "a@example.com", "销售代表"),
            row(P + "b", "李强", P + "b@example.com", "销售经理"),
            row(P + "c", "王芳", P + "c@example.com", "财务"),
        });
        service.importUsers(file);
        long after1 = User.count();
        User before = User.find("username", P + "a").firstResult();
        String hash0 = before.passwordHash;

        // 第二份文件把姓名 / 角色 / 邮箱全改掉——只新增语义下这些改动必须<b>不生效</b>
        UserImportReportDTO r2 = service.importUsers(build(new String[][]{
            row(P + "a", "改名了", P + "a-changed@example.com", "系统管理员"),
            row(P + "b", "李强", P + "b@example.com", "销售经理"),
            row(P + "c", "王芳", P + "c@example.com", "财务"),
        }));

        assertEquals(0, r2.createdCount, "重复导入必须零新增");
        assertEquals(3, r2.skippedCount);
        for (UserImportReportDTO.SkippedRow s : r2.skipped) {
            assertEquals("用户名已存在", s.reason);
        }
        assertEquals(after1, User.count(), "库内条数增量必须是 0");

        User u = User.find("username", P + "a").firstResult();
        assertEquals("张明", u.fullName, "姓名不得被覆盖");
        assertEquals("SALES_REP", u.role, "角色不得被覆盖");
        assertEquals(P + "a@example.com", u.email, "邮箱不得被覆盖");
        assertEquals(hash0, u.passwordHash, "密码不得被重置");
    }

    /** AC-17：非法角色行跳过，同文件其余合法行照常新增（部分成功，不整单回滚）。 */
    @Test
    @TestTransaction
    void illegalRoleSkipsOnlyThatRow() throws Exception {
        long before = User.count();
        UserImportReportDTO r = service.importUsers(build(new String[][]{
            row(P + "ok1", "合法一", P + "ok1@example.com", "销售代表"),
            row(P + "bad", "非法角色", P + "bad@example.com", "财务总监"),
            row(P + "ok2", "合法二", P + "ok2@example.com", "销售经理"),
        }));

        assertEquals(2, r.createdCount, "其余两行必须照常新增");
        assertEquals(1, r.skippedCount);
        assertEquals("角色不合法：财务总监", r.skipped.get(0).reason);
        assertEquals(P + "bad", r.skipped.get(0).username);
        assertEquals(before + 2, User.count());
        assertNull(User.find("username", P + "bad").firstResult(), "非法行不得落库");
    }

    /** AC-18：区域/部门留空 ⇒ 正常新增且为 NULL；填不存在的名字 ⇒ 仍新增 + 软提示，不拒整行。 */
    @Test
    @TestTransaction
    void regionDepartmentUnmatchedIsHintNotRejection() throws Exception {
        UserImportReportDTO r = service.importUsers(build(new String[][]{
            {P + "blank", "留空", P + "blank@example.com", "销售代表", "", ""},
            {P + "hint", "不存在的部门", P + "hint@example.com", "销售代表", "", "不存在的部门"},
        }));

        assertEquals(2, r.createdCount, "两行都必须创建成功");
        assertEquals(0, r.skippedCount, "区域/部门匹配不上不得拒行：" + r.skipped);

        User blank = User.find("username", P + "blank").firstResult();
        assertNull(blank.regionId);
        assertNull(blank.departmentId);
        assertNull(r.created.get(0).hint, "留空不产生提示");

        User hinted = User.find("username", P + "hint").firstResult();
        assertNull(hinted.departmentId, "匹配不上时 department_id 必须是 NULL");
        assertNotNull(r.created.get(1).hint);
        assertTrue(r.created.get(1).hint.contains("部门未匹配：不存在的部门"),
            "实际提示=" + r.created.get(1).hint);
    }

    /** AC-25：文件内用户名重复 ⇒ 首行新增、次行跳过；库中只有 1 条。 */
    @Test
    @TestTransaction
    void duplicateUsernameWithinFileTakesFirstRow() throws Exception {
        UserImportReportDTO r = service.importUsers(build(new String[][]{
            row(P + "d", "首行", P + "d1@example.com", "销售代表"),
            row(P + "d", "次行", P + "d2@example.com", "销售经理"),
        }));

        assertEquals(1, r.createdCount);
        assertEquals(1, r.skippedCount);
        assertEquals("文件内用户名重复，已取首行", r.skipped.get(0).reason);
        assertEquals(1, User.count("username", P + "d"), "库中只能有 1 条");
        assertEquals("首行", User.<User>find("username", P + "d").<User>firstResult().fullName);
    }

    /** AC-26：用户名超 DB 列长 / 邮箱格式非法 ⇒ 该行跳过并报明原因，不抛 500。 */
    @Test
    @TestTransaction
    void oversizeAndMalformedInputsAreSkippedNotThrown() throws Exception {
        String tooLong = P + "x".repeat(UserExportImportService.USERNAME_MAX_LEN);
        assertTrue(tooLong.length() > UserExportImportService.USERNAME_MAX_LEN);

        UserImportReportDTO r = service.importUsers(build(new String[][]{
            row(tooLong, "超长用户名", P + "long@example.com", "销售代表"),
            row(P + "mail", "坏邮箱", "abc", "销售代表"),
            row(P + "noname", "", P + "noname@example.com", "销售代表"),
            row("", "无用户名", P + "nouser@example.com", "销售代表"),
            row(P + "good", "正常", P + "good@example.com", "销售代表"),
        }));

        assertEquals(1, r.createdCount, "只有正常那行入库");
        assertEquals(4, r.skippedCount);
        assertEquals("用户名超长（最多 " + UserExportImportService.USERNAME_MAX_LEN + " 字符）",
            r.skipped.get(0).reason);
        assertEquals("邮箱格式不合法：abc", r.skipped.get(1).reason);
        assertEquals("姓名为空", r.skipped.get(2).reason);
        assertEquals("用户名为空", r.skipped.get(3).reason);
    }

    /** AC-24：非 xlsx → 400 IMPORT_FILE_INVALID；表头不符 → 400 IMPORT_HEADER_INVALID。 */
    @Test
    @TestTransaction
    void unusableFilesAre400() throws Exception {
        UserApiException e1 = assertThrows(UserApiException.class,
            () -> service.importUsers("这不是 xlsx".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(400, e1.getCode());
        assertEquals("IMPORT_FILE_INVALID", e1.getErrorCode());
        assertEquals("请上传 .xlsx 文件", e1.getMessage());

        byte[] wrongHeader = build(new String[]{"账号", "名字", "邮件", "角色", "区域", "部门"},
            new String[][]{row("x", "y", "z@example.com", "销售代表")});
        UserApiException e2 = assertThrows(UserApiException.class,
            () -> service.importUsers(wrongHeader));
        assertEquals(400, e2.getCode());
        assertEquals("IMPORT_HEADER_INVALID", e2.getErrorCode());
        assertEquals("表头不符合模板要求，请下载新模板", e2.getMessage());
    }

    /** AC-24 续：只有表头、0 行数据 ⇒ 200 + 全 0 报告（🚫 不报 400 / 500）。 */
    @Test
    @TestTransaction
    void headerOnlyFileReturnsZeroReport() throws Exception {
        UserImportReportDTO r = service.importUsers(build(new String[0][]));
        assertEquals(0, r.totalRows);
        assertEquals(0, r.createdCount);
        assertEquals(0, r.skippedCount);
    }

    // ──────────────────────────── B-3 导出 ────────────────────────────

    /** AC-12：8 列（前 6 列＝导入模板列），角色写中文、状态写启用/停用；🚫 不含 id / 密码。 */
    @Test
    @TestTransaction
    void exportColumnsAndLabels() throws Exception {
        service.importUsers(build(new String[][]{
            row(P + "exp", "导出验证", P + "exp@example.com", "销售代表"),
        }));

        Sheet s = read(service.export(P + "exp", null, null));
        Row h = s.getRow(0);
        List<String> expected = List.of("用户名", "姓名", "邮箱", "角色", "区域", "部门", "状态", "创建时间");
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), str(h.getCell(i)), "第 " + (i + 1) + " 列表头");
        }
        assertEquals("", str(h.getCell(8)), "只有 8 列");

        assertEquals(1, s.getLastRowNum(), "断言前的非空保护：应导出 1 行");
        Row r = s.getRow(1);
        assertEquals(P + "exp", str(r.getCell(0)));
        assertEquals("导出验证", str(r.getCell(1)));
        assertEquals(P + "exp@example.com", str(r.getCell(2)));
        assertEquals("销售代表", str(r.getCell(3)), "角色列写中文标签，不是 SALES_REP");
        assertEquals("", str(r.getCell(4)));
        assertEquals("", str(r.getCell(5)));
        assertEquals("启用", str(r.getCell(6)));
        assertTrue(str(r.getCell(7)).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
            "创建时间格式 yyyy-MM-dd HH:mm:ss，实际=" + str(r.getCell(7)));

        // 🚫 全表不得出现任何 id / 密码痕迹
        User u = User.find("username", P + "exp").firstResult();
        for (int c = 0; c <= 7; c++) {
            String v = str(r.getCell(c));
            assertNotEquals(u.id.toString(), v, "导出不得含 id");
            assertNotEquals(u.passwordHash, v, "导出不得含密码哈希");
        }
    }

    /** AC-13：跟随筛选 + 全量（不受分页限制）。 */
    @Test
    @TestTransaction
    void exportFollowsFiltersAndIsNotPaged() throws Exception {
        service.importUsers(build(new String[][]{
            row(P + "r1", "代表一", P + "r1@example.com", "销售代表"),
            row(P + "r2", "代表二", P + "r2@example.com", "销售代表"),
            row(P + "m1", "经理一", P + "m1@example.com", "销售经理"),
        }));

        Sheet reps = read(service.export(P, "SALES_REP", null));
        assertEquals(2, reps.getLastRowNum());
        for (int r = 1; r <= reps.getLastRowNum(); r++) {
            assertEquals("销售代表", str(reps.getRow(r).getCell(3)));
        }

        long total = User.count();
        Sheet all = read(service.export(null, null, null));
        assertTrue(total >= 3, "断言前的非空保护");
        assertEquals(total, all.getLastRowNum(),
            "无筛选导出的数据行数必须等于全表条数（不是默认 pageSize 50）");
    }

    /** AC-23 后端兜底：筛不到人时仍返只有表头的 xlsx。 */
    @Test
    @TestTransaction
    void emptyResultStillProducesHeaderOnlyWorkbook() throws Exception {
        Sheet s = read(service.export("zzz不存在zzz260902", null, null));
        assertEquals(0, s.getLastRowNum());
        assertEquals("用户名", str(s.getRow(0).getCell(0)));
    }

    // ──────────────────────────── 回环（AC-21 的前半段） ────────────────────────────

    // ──────────────────────────── N+1 批量化验证 ────────────────────────────

    /**
     * 🚫 <b>N+1 硬指标</b>：导入的<b>查询</b>条数必须与行数 N 无关。
     *
     * <p>做法：开 Hibernate {@link Statistics}，分别导入 5 行与 20 行，比对
     * {@code queryExecutionCount} 增量 —— 相等才算过。
     * （只统计查询；INSERT 走 JDBC batch，本来就该随 N 增长，不在本指标内。）
     * ⚠️ 这是「N 翻倍而 sql 不变」的证据，不是「看起来没在循环里查库」的自我声明。
     */
    @Test
    @TestTransaction
    void importQueryCountIsIndependentOfRowCount() throws Exception {
        Statistics st = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        st.setStatisticsEnabled(true);

        long q0 = st.getQueryExecutionCount();
        service.importUsers(build(rows("n5_", 5)));
        long small = st.getQueryExecutionCount() - q0;

        long q1 = st.getQueryExecutionCount();
        service.importUsers(build(rows("n20_", 20)));
        long large = st.getQueryExecutionCount() - q1;

        assertTrue(small > 0, "断言前的非空保护：至少要跑出几条批量查询，实际=" + small);
        assertEquals(small, large,
            "5 行与 20 行的查询条数必须相同（实际 " + small + " vs " + large
                + "）—— 不等即说明循环体里在查库");
    }

    private String[][] rows(String tag, int n) {
        String[][] out = new String[n][];
        for (int i = 0; i < n; i++) {
            String u = P + tag + i;
            out[i] = row(u, "批量" + i, u + "@example.com", "销售代表");
        }
        return out;
    }

    /** 导出 → 原样回导：8 列文件的后 2 列被忽略，且「只新增」语义下零新增。 */
    @Test
    @TestTransaction
    void exportedFileCanBeReimportedWithZeroCreates() throws Exception {
        service.importUsers(build(new String[][]{
            row(P + "rt", "回环", P + "rt@example.com", "销售代表"),
        }));
        long after1 = User.count();

        UserImportReportDTO back = service.importUsers(service.export(P + "rt", null, null));
        assertEquals(0, back.createdCount, "原样回导必须零新增");
        assertEquals(1, back.skippedCount);
        assertEquals("用户名已存在", back.skipped.get(0).reason);
        assertEquals(after1, User.count(), "库内条数增量必须是 0");
    }
}
