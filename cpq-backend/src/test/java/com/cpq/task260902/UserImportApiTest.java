package com.cpq.task260902;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260902 · D 组 + F 组 · 用户导入（T-14 ~ T-18、T-24 ~ T-26）。
 *
 * <p>断言派生自 {@code 需求文档.md §③ D/F 组} + {@code api.md § B-4 / B-5}
 * + {@code 原型图/4-用户导入抽屉.html} / {@code 5-用户导入结果.html}。
 *
 * <h3>🚨 造数纪律</h3>
 * 所有导入用户名一律 {@code t260902} 前缀，邮箱一律 {@code @t260902.invalid}（{@code user.email} 有
 * UNIQUE 约束，复用真实邮箱会撞唯一键，那种失败长得像功能缺陷）。
 * {@code @AfterEach} 按前缀精确删除，🚫 不清库。
 */
@QuarkusTest
class UserImportApiTest extends Task260902TestBase {

    private static final String IMPORT = "/api/cpq/users/import";
    private static final String TEMPLATE = "/api/cpq/users/import/template";

    /** api.md § B-4 / B-5：模板与导入的前 6 列。 */
    private static final List<String> TEMPLATE_HEADER =
            List.of("用户名", "姓名", "邮箱", "角色", "区域", "部门");

    // ═══════════════════ T-14 → AC-14：导入模板 ═══════════════════

    /** T-14 → AC-14：模板 6 列名逐字；含 1 行示例；角色列表头带批注，批注含 4 个合法取值。 */
    @Test
    void t14_importTemplate_columnsExampleRowAndRoleComment() {
        Response res = given().when().get(TEMPLATE).thenReturn();
        assertEquals(200, res.statusCode(),
                "AC-14：下载模板应 200，实际 " + res.statusCode() + " body=" + res.asString());
        byte[] xlsx = res.asByteArray();

        List<String> h = header(xlsx);
        System.out.println("[AC-14] 模板表头 = " + h);
        assertEquals(TEMPLATE_HEADER, h, "AC-14：模板第 1 行必须逐字为 " + TEMPLATE_HEADER);

        List<List<String>> rows = dataRows(xlsx);
        System.out.println("[AC-14] 模板示例行 = " + rows);
        assertEquals(1, rows.size(), "AC-14：模板必须含 1 行示例数据，实际 " + rows.size() + " 行");
        assertFalse(cell(rows.get(0), 0).isBlank(), "AC-14：示例行的用户名不能为空");

        int roleCol = h.indexOf("角色");
        String comment = headerComment(xlsx, roleCol);
        System.out.println("[AC-14] 角色列批注 = " + comment);
        assertNotNull(comment,
                "AC-14：角色列表头必须挂单元格批注说明合法取值（第 " + (roleCol + 1) + " 列没有批注）");
        for (String label : List.of("系统管理员", "销售经理", "销售代表", "财务")) {
            assertTrue(comment.contains(label),
                    "AC-14：角色列批注必须列出 4 个合法取值，缺「" + label + "」，实际批注 = " + comment);
        }
    }

    // ═══════════════════ T-15 → AC-15：新增 + 密码回显 ═══════════════════

    /**
     * T-15 → AC-15：3 个全新用户名 ⇒ 新增 3 条 / 跳过 0 条；3 个初始密码互不相同且非空；
     * 前后基准查询④ 的差值 == 3；3 条新记录 {@code is_first_login=true}、{@code status='ACTIVE'}。
     */
    @Test
    void t15_importThreeNewUsers_createdWithDistinctInitialPasswords() {
        long before = baseUserCount();
        Map<String, Object> rep = doImport(threeUserFile());
        long after = baseUserCount();
        System.out.println("[AC-15] report = " + rep);

        assertEquals(3, intOf(rep, "totalRows"), "AC-15：文件 3 行数据 ⇒ totalRows=3");
        assertEquals(3, intOf(rep, "createdCount"), "AC-15：新增 3 条");
        assertEquals(0, intOf(rep, "skippedCount"),
                "AC-15：跳过 0 条，实际跳过明细 = " + listOf(rep, "skipped"));
        assertEquals(3, after - before,
                "AC-15：🚨 导入前后基准查询④ 的差值必须恰为 3（before=" + before + " after=" + after + "）");

        List<Map<String, Object>> created = assertNonEmpty(listOf(rep, "created"), "AC-15 created 明细");
        assertEquals(3, created.size(), "AC-15：created 明细应有 3 条");

        Set<String> pwds = new LinkedHashSet<>();
        for (Map<String, Object> c : created) {
            String u = String.valueOf(c.get("username"));
            Object pw = c.get("initialPassword");
            assertNotNull(pw, "AC-15：created 明细必须逐人回显 initialPassword，用户 " + u + " 没有");
            String pws = String.valueOf(pw);
            assertFalse(pws.isBlank(), "AC-15：用户 " + u + " 的初始密码为空串");
            pwds.add(pws);

            // 🚨 api.md § B-5：不落库明文
            assertEquals(0, count("SELECT count(*) FROM \"user\" WHERE username='" + u
                            + "' AND password_hash = '" + pws.replace("'", "''") + "'"),
                    "AC-15/api.md：🚨 初始密码以明文落进了 password_hash（用户 " + u + "）");

            String row = scalar("SELECT is_first_login::text || '~#~' || status FROM \"user\" " +
                    "WHERE username = '" + u + "'");
            assertNotNull(row, "AC-15：用户 " + u + " 报告说创建了，库里却查不到");
            String[] p = row.split("~#~", -1);
            assertEquals("true", p[0], "AC-15：新用户 " + u + " 的 is_first_login 应为 true（首登强制改密）");
            assertEquals("ACTIVE", p[1], "AC-15：新用户 " + u + " 的 status 应为 ACTIVE");
        }
        System.out.println("[AC-15] 三个初始密码（去重后）= " + pwds.size() + " 个");
        assertEquals(3, pwds.size(),
                "AC-15：3 个初始密码必须互不相同，去重后只剩 " + pwds.size() + " 个 —— 生成器可能是常量");
    }

    // ═══════════════════ T-16 → AC-16：重复跳过（序列）═══════════════════

    /**
     * T-16 → AC-16（序列 AC）：同一份文件原样导第二次 ⇒ 新增 0 / 跳过 3，原因全为「用户名已存在」；
     * 基准查询④ 增量 0；且 3 个用户的 {@code full_name/role/email} 与第一次导入后<b>逐字相同</b>（未被覆盖）。
     */
    @Test
    void t16_reimportSameFile_skipsAll_andDoesNotOverwrite() {
        byte[] file = threeUserFile();
        doImport(file);

        // 第一次导入后的快照（逐字段）
        List<String> snapshotBefore = strList(
                "SELECT username || '~#~' || full_name || '~#~' || role || '~#~' || email " +
                "  FROM \"user\" WHERE username LIKE '" + USER_PREFIX + "%' ORDER BY username");
        assertNonEmpty(snapshotBefore, "AC-16 前置：第一次导入后的 3 个用户快照");
        assertEquals(3, snapshotBefore.size(), "AC-16 前置：第一次应导进 3 个用户");

        long before = baseUserCount();
        Map<String, Object> rep = doImport(file);
        long after = baseUserCount();
        System.out.println("[AC-16] 第二次 report = " + rep);

        assertEquals(0, intOf(rep, "createdCount"), "AC-16：第二次导入新增必须为 0");
        assertEquals(3, intOf(rep, "skippedCount"), "AC-16：第二次导入跳过必须为 3");
        assertEquals(0, after - before,
                "AC-16：🚨 第二次导入前后基准查询④ 的增量必须为 0（before=" + before + " after=" + after + "）");
        assertTrue(listOf(rep, "created").isEmpty(),
                "AC-16：新增为 0 时 created 明细必须为空（原型图 状态 C：整个密码区不渲染）");

        List<Map<String, Object>> skipped = assertNonEmpty(listOf(rep, "skipped"), "AC-16 skipped 明细");
        for (Map<String, Object> s : skipped) {
            assertEquals("用户名已存在", String.valueOf(s.get("reason")),
                    "AC-16：跳过原因必须逐字为「用户名已存在」，实际 " + s);
        }

        List<String> snapshotAfter = strList(
                "SELECT username || '~#~' || full_name || '~#~' || role || '~#~' || email " +
                "  FROM \"user\" WHERE username LIKE '" + USER_PREFIX + "%' ORDER BY username");
        System.out.println("[AC-16] before = " + snapshotBefore + "\n[AC-16] after  = " + snapshotAfter);
        assertEquals(snapshotBefore, snapshotAfter,
                "AC-16：🚨 重复导入不得覆盖现有用户的 姓名/角色/邮箱 —— 逐字段比对不一致");
    }

    // ═══════════════════ T-17 → AC-17：非法角色，部分成功 ═══════════════════

    /**
     * T-17 → AC-17：某行角色填「财务总监」（非法值）⇒ 该行跳过、原因「角色不合法：财务总监」，
     * 同文件其余合法行<b>照常新增</b>（部分成功，不整单回滚）。
     */
    @Test
    void t17_illegalRole_rowSkipped_othersStillCreated() {
        byte[] file = buildXlsx(TEMPLATE_HEADER, List.of(
                row("t260902a", "张明", "销售代表"),
                row("t260902bad", "坏角色", "财务总监"),
                row("t260902b", "李思", "销售经理")));

        long before = baseUserCount();
        Map<String, Object> rep = doImport(file);
        long after = baseUserCount();
        System.out.println("[AC-17] report = " + rep);

        assertEquals(3, intOf(rep, "totalRows"), "AC-17：文件 3 行");
        assertEquals(2, intOf(rep, "createdCount"),
                "AC-17：🚨 部分成功 —— 非法角色那一行跳过，其余 2 行照常新增（不整单回滚）");
        assertEquals(1, intOf(rep, "skippedCount"), "AC-17：只跳 1 行");
        assertEquals(2, after - before, "AC-17：库里应恰好多出 2 个用户");

        List<Map<String, Object>> skipped = assertNonEmpty(listOf(rep, "skipped"), "AC-17 skipped 明细");
        assertEquals(1, skipped.size(), "AC-17：skipped 明细应只有 1 条");
        assertEquals("t260902bad", String.valueOf(skipped.get(0).get("username")),
                "AC-17：被跳过的应是角色非法那一行");
        assertEquals("角色不合法：财务总监", String.valueOf(skipped.get(0).get("reason")),
                "AC-17：跳过原因必须逐字为「角色不合法：财务总监」（含原值回显），实际 " + skipped.get(0));

        assertEquals(0, count("SELECT count(*) FROM \"user\" WHERE username='t260902bad'"),
                "AC-17：被跳过的行不许落库");
        assertEquals(2, count("SELECT count(*) FROM \"user\" WHERE username IN ('t260902a','t260902b')"),
                "AC-17：合法的两行必须都落库");
    }

    // ═══════════════════ T-18 → AC-18：区域/部门解析 ═══════════════════

    /**
     * T-18 → AC-18：区域/部门<b>留空</b> ⇒ 正常新增、两列 NULL；
     * 填一个<b>不存在</b>的部门名 ⇒ 该行<b>仍然新增成功</b>、{@code department_id} 为 NULL，
     * 并在报告的「提示」栏出现「部门未匹配：不存在的部门」（🚫 不因此拒绝整行）。
     */
    @Test
    void t18_blankAndUnmatchedRegionDepartment_stillCreated_withHint() {
        byte[] file = buildXlsx(TEMPLATE_HEADER, List.of(
                List.of("t260902a", "张明", "t260902a@t260902.invalid", "销售代表", "", ""),
                List.of("t260902c", "王赫", "t260902c@t260902.invalid", "财务", "", "不存在的部门")));

        Map<String, Object> rep = doImport(file);
        System.out.println("[AC-18] report = " + rep);

        assertEquals(2, intOf(rep, "createdCount"),
                "AC-18：🚨 区域/部门匹配不上不许拒绝整行 —— 两行都必须创建成功");
        assertEquals(0, intOf(rep, "skippedCount"),
                "AC-18：不许有跳过，实际跳过明细 = " + listOf(rep, "skipped"));

        for (String u : List.of("t260902a", "t260902c")) {
            assertEquals(1, count("SELECT count(*) FROM \"user\" WHERE username='" + u + "'"),
                    "AC-18：用户 " + u + " 应已创建");
            assertNull(scalar("SELECT region_id::text FROM \"user\" WHERE username='" + u + "'"),
                    "AC-18：区域列留空 ⇒ region_id 必须为 NULL（用户 " + u + "）");
            assertNull(scalar("SELECT department_id::text FROM \"user\" WHERE username='" + u + "'"),
                    "AC-18：部门匹配不到 ⇒ department_id 必须为 NULL（用户 " + u + "）");
        }

        // 提示挂在「创建成功」那一行上（原型图 状态 A：跳过 ≠ 提示）
        List<Map<String, Object>> created = assertNonEmpty(listOf(rep, "created"), "AC-18 created 明细");
        Map<String, Object> c = created.stream()
                .filter(m -> "t260902c".equals(String.valueOf(m.get("username"))))
                .findFirst().orElseThrow(() ->
                        new AssertionError("AC-18：created 明细里找不到 t260902c，实际 = " + created));
        System.out.println("[AC-18] t260902c 明细 = " + c);
        assertNotNull(c.get("hint"),
                "AC-18：填了匹配不到的部门时，成功行的 hint 必须给出提示，实际 = " + c);
        assertEquals("部门未匹配：不存在的部门", String.valueOf(c.get("hint")),
                "AC-18：提示文案必须逐字为「部门未匹配：不存在的部门」，实际 " + c.get("hint"));

        Map<String, Object> a = created.stream()
                .filter(m -> "t260902a".equals(String.valueOf(m.get("username"))))
                .findFirst().orElseThrow();
        assertTrue(a.get("hint") == null || String.valueOf(a.get("hint")).isBlank(),
                "AC-18：区域/部门都留空的行不该有提示（留空是合法的，不是「未匹配」），实际 " + a);
    }

    // ═══════════════════ T-24 → AC-24：文件本身不可用（边界）═══════════════════

    /**
     * T-24 → AC-24：
     * <ul>
     *   <li>上传 {@code .txt} ⇒ 400 + 「请上传 .xlsx 文件」</li>
     *   <li>表头为 {@code 账号/名字/邮件} ⇒ 400 + 「表头不符合模板要求，请下载新模板」</li>
     *   <li>🚨 只有表头、0 行数据 ⇒ <b>200</b> + 三个计数为 0（<b>不是 400、不是 500</b>）</li>
     * </ul>
     */
    @Test
    void t24_unusableFiles_400_butEmptyFileIs200() {
        // ① 非 xlsx
        Response txt = given()
                .multiPart("file", "名单.txt", "这不是 xlsx".getBytes(StandardCharsets.UTF_8), "text/plain")
                .post(IMPORT).thenReturn();
        System.out.println("[AC-24·txt] status=" + txt.statusCode() + " body=" + txt.asString());
        assertEquals(400, txt.statusCode(), "AC-24：.txt 必须 400，不许 500，也不许当成空文件放行");
        assertTrue(txt.asString().contains("请上传 .xlsx 文件"),
                "AC-24：提示必须逐字含「请上传 .xlsx 文件」，实际 body=" + txt.asString());

        // ② 表头不符
        byte[] badHeader = buildXlsx(List.of("账号", "名字", "邮件", "角色", "区域", "部门"),
                List.of(row("t260902a", "张明", "销售代表")));
        Response bh = given()
                .multiPart("file", "旧版名单.xlsx", badHeader, XLSX_MIME).post(IMPORT).thenReturn();
        System.out.println("[AC-24·表头] status=" + bh.statusCode() + " body=" + bh.asString());
        assertEquals(400, bh.statusCode(), "AC-24：前 6 列表头不符必须 400");
        assertTrue(bh.asString().contains("表头不符合模板要求，请下载新模板"),
                "AC-24：提示必须逐字含「表头不符合模板要求，请下载新模板」，实际 body=" + bh.asString());
        assertEquals(0, count("SELECT count(*) FROM \"user\" WHERE username LIKE '" + USER_PREFIX + "%'"),
                "AC-24：表头不符时「一行都没处理」—— 不许有任何用户被创建");

        // ③ 只有表头、0 行数据 ⇒ 200 + 三个 0
        byte[] empty = buildXlsx(TEMPLATE_HEADER, List.of());
        long before = baseUserCount();
        Map<String, Object> rep = doImport(empty);
        System.out.println("[AC-24·空文件] report = " + rep);
        assertEquals(0, intOf(rep, "totalRows"), "AC-24：空文件 totalRows=0");
        assertEquals(0, intOf(rep, "createdCount"), "AC-24：空文件 createdCount=0");
        assertEquals(0, intOf(rep, "skippedCount"), "AC-24：空文件 skippedCount=0");
        assertEquals(0, baseUserCount() - before, "AC-24：空文件不许改动用户表");
    }

    // ═══════════════════ T-25 → AC-25：文件内自重复（边界）═══════════════════

    /** T-25 → AC-25：同名两行 ⇒ 首行新增、次行跳过原因「文件内用户名重复，已取首行」；库中只有 1 条。 */
    @Test
    void t25_duplicateUsernameWithinFile_firstWinsSecondSkipped() {
        byte[] file = buildXlsx(TEMPLATE_HEADER, List.of(
                List.of("t260902d", "首行王", "t260902d1@t260902.invalid", "销售代表", "", ""),
                List.of("t260902d", "次行王", "t260902d2@t260902.invalid", "财务", "", "")));

        Map<String, Object> rep = doImport(file);
        System.out.println("[AC-25] report = " + rep);

        assertEquals(1, intOf(rep, "createdCount"), "AC-25：只应新增 1 条");
        assertEquals(1, intOf(rep, "skippedCount"), "AC-25：只应跳过 1 条");

        List<Map<String, Object>> skipped = assertNonEmpty(listOf(rep, "skipped"), "AC-25 skipped 明细");
        assertEquals("文件内用户名重复，已取首行", String.valueOf(skipped.get(0).get("reason")),
                "AC-25：跳过原因必须逐字为「文件内用户名重复，已取首行」，实际 " + skipped.get(0));

        assertEquals(1, count("SELECT count(*) FROM \"user\" WHERE username='t260902d'"),
                "AC-25：库中 t260902d 只能有 1 条");
        assertEquals("首行王", scalar("SELECT full_name FROM \"user\" WHERE username='t260902d'"),
                "AC-25：「已取首行」—— 落库的必须是第 1 行的姓名，不是第 2 行");
    }

    // ═══════════════════ T-26 → AC-26：超长与非法输入（边界）═══════════════════

    /**
     * T-26 → AC-26：用户名超 DB 列长 ⇒ 该行跳过并报明原因，<b>不抛 500</b>；
     * 邮箱填 {@code abc} ⇒ 该行跳过，原因「邮箱格式不合法」。
     *
     * <p>✅ <b>AC 原文已于 2026-09-02 修订</b>：「65 个字符」→「<b>101</b> 个字符」
     * （{@code "user".username} 实测 {@code varchar(100)}，65 根本不超长，照字面写会得到
     * 「应跳过却创建成功」的假红）。主线裁决：<b>保持本用例「现场读 information_schema 列长 +1」的做法</b>
     * —— 比写死 101 更好，列长变了也不会失效。
     */
    @Test
    void t26_overlongUsernameAndBadEmail_skippedWithReason_noServerError() {
        int maxLen = columnMaxLength("user", "username");
        System.out.println("[AC-26] user.username 的 DB 列长 = " + maxLen + "（AC 原文写的是 65）");
        assertTrue(maxLen > 0, "AC-26 前置：取不到 username 列长");
        String tooLong = USER_PREFIX + "x".repeat(Math.max(1, maxLen + 1 - USER_PREFIX.length()));
        assertTrue(tooLong.length() > maxLen,
                "AC-26 前置：构造的用户名长度 " + tooLong.length() + " 必须 > 列长 " + maxLen);

        byte[] file = buildXlsx(TEMPLATE_HEADER, List.of(
                List.of(tooLong, "超长名", "t260902long@t260902.invalid", "销售代表", "", ""),
                List.of("t260902e", "坏邮箱", "abc", "销售代表", "", ""),
                row("t260902a", "张明", "销售代表")));

        Response res = given()
                .multiPart("file", "边界.xlsx", file, XLSX_MIME).post(IMPORT).thenReturn();
        System.out.println("[AC-26] status=" + res.statusCode() + " body=" + res.asString());
        assertEquals(200, res.statusCode(),
                "AC-26：🚨 超长/非法输入必须逐行跳过并报明原因，不许把整个请求打成 "
                + res.statusCode() + "（尤其不许 500）");

        Map<String, Object> rep = report(res.jsonPath());
        assertEquals(1, intOf(rep, "createdCount"),
                "AC-26：合法的那一行仍应创建（部分成功），实际 report = " + rep);
        assertEquals(2, intOf(rep, "skippedCount"), "AC-26：超长 + 坏邮箱各跳 1 行");

        List<Map<String, Object>> skipped = assertNonEmpty(listOf(rep, "skipped"), "AC-26 skipped 明细");
        List<String> reasons = new ArrayList<>();
        for (Map<String, Object> s : skipped) reasons.add(String.valueOf(s.get("reason")));
        System.out.println("[AC-26] 跳过原因 = " + reasons);

        String expectOverlong = "用户名超长（最多 " + maxLen + " 字符）";
        assertTrue(reasons.contains(expectOverlong),
                "AC-26/api.md：须有一条逐字为「" + expectOverlong + "」的原因，实际 = " + reasons);
        assertTrue(reasons.stream().anyMatch(r -> r.startsWith("邮箱格式不合法")),
                "AC-26：须有一条以「邮箱格式不合法」开头的原因（api.md：邮箱格式不合法：<原值>），实际 = " + reasons);

        assertEquals(0, count("SELECT count(*) FROM \"user\" WHERE username='t260902e'"),
                "AC-26：坏邮箱那一行不许落库");
        assertEquals(0, count("SELECT count(*) FROM \"user\" WHERE length(username) > " + maxLen),
                "AC-26：超长用户名不许落库");
    }

    // ═══════════════ T-26b → AC-26b：邮箱的 DB 约束（边界，2026-09-02 用户追认新增）═══════════════

    /**
     * T-26b → AC-26b：{@code "user".email} 是 {@code varchar(200)} <b>NOT NULL + UNIQUE
     * （{@code user_email_key}）</b>，{@code full_name} 是 {@code varchar(200)}。
     * 不在应用层逐行拦下，INSERT 就会撞 DB 约束 ⇒ <b>整批 500</b>，与 AC-26「不抛 500」直接冲突。
     *
     * <p>断言：含 5 类脏行各一行的文件导入 ⇒ 整体 <b>200 不是 500</b>，5 行各进 {@code skipped}
     * 且原因<b>逐字</b>匹配 {@code api.md}，同文件的合法行<b>照常创建</b>。
     *
     * <p>5 类（列长一律现场读 {@code information_schema}，🚫 不写死 200）：
     * <ol>
     *   <li>邮箱为空 ⇒ {@code 邮箱为空}</li>
     *   <li>邮箱在库中已存在 ⇒ {@code 邮箱已存在：<原值>}（拿 admin 的真实邮箱来撞）</li>
     *   <li>邮箱在本文件内重复 ⇒ {@code 文件内邮箱重复，已取首行}（首行仍创建）</li>
     *   <li>姓名超长 ⇒ {@code 姓名超长（最多 N 字符）}</li>
     *   <li>邮箱超长 ⇒ {@code 邮箱超长（最多 N 字符）}（🚨 构造成<b>格式合法</b>的长邮箱，
     *       否则会被「格式不合法」先拦下，验不到超长这一支）</li>
     * </ol>
     */
    @Test
    void t26b_emailDbConstraints_allSkippedWithExactReasons_noServerError() {
        int nameMax = columnMaxLength("user", "full_name");
        int mailMax = columnMaxLength("user", "email");
        System.out.println("[AC-26b] full_name 列长 = " + nameMax + "，email 列长 = " + mailMax);

        String adminEmail = scalar("SELECT email FROM \"user\" WHERE username='admin'");
        assertNotNull(adminEmail, "AC-26b 前置：取不到 admin 的邮箱，无法构造「邮箱已存在」那一行");
        System.out.println("[AC-26b] 用来撞唯一键的既有邮箱 = " + adminEmail);

        String longName = "名".repeat(nameMax + 1);
        String domain = "@t260902.invalid";
        String longMail = "x".repeat(mailMax + 1 - domain.length()) + domain;
        assertTrue(longMail.length() > mailMax,
                "AC-26b 前置：构造的邮箱长度 " + longMail.length() + " 必须 > 列长 " + mailMax);
        String dupMail = "t260902dup@t260902.invalid";

        byte[] file = buildXlsx(TEMPLATE_HEADER, List.of(
                row("t260902a", "张明", "销售代表"),                                              // 合法对照
                List.of("t260902f", "空邮箱", "", "销售代表", "", ""),                            // ① 邮箱为空
                List.of("t260902g", "撞唯一键", adminEmail, "销售代表", "", ""),                  // ② 邮箱已存在
                List.of("t260902h", "重复邮箱首行", dupMail, "销售代表", "", ""),                 // ③ 首行 → 创建
                List.of("t260902i", "重复邮箱次行", dupMail, "销售代表", "", ""),                 // ③ 次行 → 跳过
                List.of("t260902j", longName, "t260902j@t260902.invalid", "销售代表", "", ""),    // ④ 姓名超长
                List.of("t260902k", "超长邮箱", longMail, "销售代表", "", "")));                  // ⑤ 邮箱超长

        long before = baseUserCount();
        Response res = given()
                .multiPart("file", "邮箱边界.xlsx", file, XLSX_MIME).post(IMPORT).thenReturn();
        System.out.println("[AC-26b] status=" + res.statusCode() + " body=" + res.asString());
        assertEquals(200, res.statusCode(),
                "AC-26b：🚨 5 类脏行必须逐行拦下并跳过，整批必须 200 —— 实际 " + res.statusCode()
                + "。500 = 应用层没拦，直接撞了 user_email_key / varchar 长度约束");

        Map<String, Object> rep = report(res.jsonPath());
        assertEquals(7, intOf(rep, "totalRows"), "AC-26b：文件 7 行");
        assertEquals(2, intOf(rep, "createdCount"),
                "AC-26b：合法对照行 + 重复邮箱的首行 ⇒ 应创建 2 条，实际报告 = " + rep);
        assertEquals(5, intOf(rep, "skippedCount"), "AC-26b：5 类脏行各跳 1 行");
        assertEquals(2, baseUserCount() - before, "AC-26b：库里应恰好多出 2 个用户");

        List<Map<String, Object>> skipped = assertNonEmpty(listOf(rep, "skipped"), "AC-26b skipped 明细");
        Map<String, String> byUser = new java.util.LinkedHashMap<>();
        for (Map<String, Object> sk : skipped) {
            byUser.put(String.valueOf(sk.get("username")), String.valueOf(sk.get("reason")));
        }
        System.out.println("[AC-26b] 跳过原因 = " + byUser);

        assertEquals("邮箱为空", byUser.get("t260902f"),
                "AC-26b/api.md：邮箱为空的原因必须逐字为「邮箱为空」，实际 = " + byUser);
        assertEquals("邮箱已存在：" + adminEmail, byUser.get("t260902g"),
                "AC-26b/api.md：原因必须逐字为「邮箱已存在：<原值>」（含原值回显），实际 = " + byUser);
        assertEquals("文件内邮箱重复，已取首行", byUser.get("t260902i"),
                "AC-26b/api.md：原因必须逐字为「文件内邮箱重复，已取首行」，实际 = " + byUser);
        assertEquals("姓名超长（最多 " + nameMax + " 字符）", byUser.get("t260902j"),
                "AC-26b/api.md：原因必须逐字为「姓名超长（最多 N 字符）」，实际 = " + byUser);
        assertEquals("邮箱超长（最多 " + mailMax + " 字符）", byUser.get("t260902k"),
                "AC-26b/api.md：原因必须逐字为「邮箱超长（最多 N 字符）」，实际 = " + byUser);

        // 同文件的合法行照常创建（部分成功）
        assertEquals(1, count("SELECT count(*) FROM \"user\" WHERE username='t260902a'"),
                "AC-26b：合法对照行必须照常创建");
        assertEquals(1, count("SELECT count(*) FROM \"user\" WHERE username='t260902h'"),
                "AC-26b：重复邮箱的**首行**必须创建成功（「已取首行」）");
        assertEquals(dupMail, scalar("SELECT email FROM \"user\" WHERE username='t260902h'"),
                "AC-26b：首行的邮箱应正常落库");
        // 脏行一个都不许落库
        for (String u : List.of("t260902f", "t260902g", "t260902i", "t260902j", "t260902k")) {
            assertEquals(0, count("SELECT count(*) FROM \"user\" WHERE username='" + u + "'"),
                    "AC-26b：被跳过的行 " + u + " 不许落库");
        }
        // 🚨 真实数据不变式：撞唯一键那一行绝不能把 admin 的邮箱改掉
        assertEquals(adminEmail, scalar("SELECT email FROM \"user\" WHERE username='admin'"),
                "AC-26b：🚨 撞唯一键的那一行不许改动既有用户（只新增不修改）");
    }

    // ═══════════════════════ 辅助 ═══════════════════════

    /** AC-15 / AC-16 的固定 3 行文件：t260902a/b/c。 */
    private byte[] threeUserFile() {
        return buildXlsx(TEMPLATE_HEADER, List.of(
                row("t260902a", "张明", "销售代表"),
                row("t260902b", "李思", "销售经理"),
                row("t260902c", "王赫", "财务")));
    }

    /** 一行：用户名 / 姓名 / 邮箱（按用户名派生，避开 user.email 的 UNIQUE 约束）/ 角色 / 区域空 / 部门空。 */
    private List<String> row(String username, String fullName, String roleLabel) {
        return List.of(username, fullName, username + "@t260902.invalid", roleLabel, "", "");
    }

    private Map<String, Object> doImport(byte[] xlsx) {
        Response res = given()
                .multiPart("file", "用户导入.xlsx", xlsx, XLSX_MIME)
                .post(IMPORT).thenReturn();
        assertEquals(200, res.statusCode(),
                "导入应返 200（部分成功语义，不整单回滚），实际 " + res.statusCode()
                + " body=" + res.asString());
        return report(res.jsonPath());
    }
}
