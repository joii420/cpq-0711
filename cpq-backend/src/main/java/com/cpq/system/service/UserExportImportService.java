package com.cpq.system.service;

import com.cpq.system.dto.UserImportReportDTO;
import com.cpq.system.dto.UserImportReportDTO.CreatedUser;
import com.cpq.system.dto.UserImportReportDTO.SkippedRow;
import com.cpq.system.entity.Department;
import com.cpq.system.entity.Region;
import com.cpq.system.entity.User;
import com.cpq.system.exception.UserApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 用户列表的导出 / 导入模板 / 批量导入（task-260902 · B-3 / B-4 / B-5，api.md B-3~B-5）。
 *
 * <p><b>导入语义：只新增，不修改，不删除。</b>用户名在库中已存在 ⇒ 整行跳过，
 * 🚫 <b>绝不 UPDATE 任何既有用户的任何字段</b>（否则误传一份老文件就会回退现有用户的角色/部门）。
 * 部分成功、不整单回滚：某行不合格只跳过该行。
 *
 * <p>🚨 <b>初始密码</b>：生成与哈希一律走 {@link UserService#generatePassword()} /
 * {@link UserService#hashPassword(String)} —— 与「新增用户」「重置密码」<b>同一条路径</b>，
 * 🚫 不在这里另写一套（两套实现＝两套强度，改策略只会改到一边）。
 * 明文<b>只在当次响应里出现一次</b>：不落库、<b>不写任何日志</b>、不进导出文件。
 *
 * <p><b>N+1 纪律</b>：三个动作的 SQL 条数都与行数 N <b>无关</b>。
 * <ul>
 *   <li>导出：用户 1 条 + 区域名 1 条 + 部门名 1 条（后两条在无引用时省略）。</li>
 *   <li>导入：查重用户名 1 条 + 查重邮箱 1 条 + 区域全表 1 条 + 部门全表 1 条，
 *       之后逐行 {@code persist()} 只入 Hibernate 的 JDBC batch，不产生 SELECT。</li>
 * </ul>
 * 🚫 循环体内没有任何查询。
 */
@ApplicationScoped
public class UserExportImportService {

    // ──────────────────────────────── 列与取值口径 ────────────────────────────────

    /** 导入模板列（＝导出文件的前 6 列，可回导）。导入端按<b>位置</b>逐列比对这 6 个。 */
    static final List<String> IMPORT_HEADER = List.of("用户名", "姓名", "邮箱", "角色", "区域", "部门");

    /** 导出的只读列（第 7 列起，回导时被忽略）。 */
    static final List<String> READONLY_HEADER = List.of("状态", "创建时间");

    static final String SHEET_NAME = "用户";

    /** 角色枚举 → 中文标签（与前端 {@code roleTag} 同表，也是 DB 检查约束 chk_user_role 的四个取值）。 */
    static final Map<String, String> ROLE_LABEL = new LinkedHashMap<>();

    /** 中文标签 → 角色枚举（导入解析用；同时也接受直接填枚举值）。 */
    private static final Map<String, String> LABEL_TO_ROLE = new LinkedHashMap<>();

    static {
        ROLE_LABEL.put("SYSTEM_ADMIN", "系统管理员");
        ROLE_LABEL.put("SALES_MANAGER", "销售经理");
        ROLE_LABEL.put("SALES_REP", "销售代表");
        ROLE_LABEL.put("PRICING_MANAGER", "财务");
        ROLE_LABEL.forEach((code, label) -> LABEL_TO_ROLE.put(label, code));
    }

    /**
     * DB 列长（{@code varchar}），超长必须<b>应用层拦</b>成跳过行，
     * 否则 INSERT 抛约束异常 → 整批 500。
     * ⚠️ 实测 {@code \d "user"}：username 100 / full_name 200 / email 200。
     */
    static final int USERNAME_MAX_LEN = 100;
    static final int FULL_NAME_MAX_LEN = 200;
    static final int EMAIL_MAX_LEN = 200;

    /** 邮箱格式：一个 @、两侧非空白、域名带点。与 {@code CreateUserRequest} 的 {@code @Email} 同量级。 */
    private static final Pattern EMAIL_RE =
        Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private static final DateTimeFormatter TS_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Inject
    UserService userService;

    // ──────────────────────────────── B-3 导出 ────────────────────────────────

    /**
     * 导出「当前筛选结果」的全量用户（不受分页限制）。
     *
     * <p>筛选条件复用 {@link UserService#buildFilter}（与 {@code GET /users} 同一套），
     * 只是不传 page/size。排序与列表一致（createdAt ASC）。
     *
     * <p>🚫 导出<b>不含 id、不含任何密码字段</b>。
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public byte[] export(String keyword, String role, String status) {
        Map<String, Object> params = new java.util.HashMap<>();
        String filter = UserService.buildFilter(role, status, keyword, params);
        List<User> users = User.<User>find(filter + " ORDER BY createdAt ASC", params).list();

        // 区域 / 部门名称：先一次性批量取成 Map 再回填，🚫 不许逐行查（N+1）
        Set<UUID> regionIds = new LinkedHashSet<>();
        Set<UUID> deptIds = new LinkedHashSet<>();
        for (User u : users) {                        // 纯内存收集，无查库
            if (u.regionId != null) regionIds.add(u.regionId);
            if (u.departmentId != null) deptIds.add(u.departmentId);
        }
        Map<UUID, String> regionNames = regionNamesByIds(regionIds);
        Map<UUID, String> deptNames = departmentNamesByIds(deptIds);

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet(SHEET_NAME);
            List<String> header = new ArrayList<>(IMPORT_HEADER);
            header.addAll(READONLY_HEADER);
            Row h = s.createRow(0);
            for (int i = 0; i < header.size(); i++) h.createCell(i).setCellValue(header.get(i));

            // 🚫 N+1 自检：本循环体只做内存 Map 查表与字符串拼装，无任何查库 / 懒加载 getter
            for (int i = 0; i < users.size(); i++) {
                User u = users.get(i);
                Row row = s.createRow(i + 1);
                row.createCell(0).setCellValue(nz(u.username));
                row.createCell(1).setCellValue(nz(u.fullName));
                row.createCell(2).setCellValue(nz(u.email));
                row.createCell(3).setCellValue(ROLE_LABEL.getOrDefault(u.role, nz(u.role)));
                row.createCell(4).setCellValue(u.regionId == null ? "" : nz(regionNames.get(u.regionId)));
                row.createCell(5).setCellValue(u.departmentId == null ? "" : nz(deptNames.get(u.departmentId)));
                row.createCell(6).setCellValue("ACTIVE".equals(u.status) ? "启用" : "停用");
                row.createCell(7).setCellValue(fmt(u.createdAt));
            }
            for (int i = 0; i < header.size(); i++) s.setColumnWidth(i, 18 * 256);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("导出用户失败: " + e.getMessage(), e);
        }
    }

    // ──────────────────────────────── B-4 导入模板 ────────────────────────────────

    /** 单 sheet 6 列空模板 + 1 行示例 + 「角色」列表头批注（列出 4 个合法值）。 */
    public byte[] generateTemplate() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet(SHEET_NAME);
            Row h = s.createRow(0);
            for (int i = 0; i < IMPORT_HEADER.size(); i++) {
                h.createCell(i).setCellValue(IMPORT_HEADER.get(i));
            }

            Row ex = s.createRow(1);
            ex.createCell(0).setCellValue("zhangming");
            ex.createCell(1).setCellValue("张明");
            ex.createCell(2).setCellValue("zhangming@example.com");
            ex.createCell(3).setCellValue("销售代表");
            ex.createCell(4).setCellValue("");
            ex.createCell(5).setCellValue("");

            CreationHelper factory = wb.getCreationHelper();
            Drawing<?> drawing = s.createDrawingPatriarch();
            ClientAnchor anchor = factory.createClientAnchor();
            anchor.setCol1(3); anchor.setCol2(8); anchor.setRow1(0); anchor.setRow2(6);
            Comment comment = drawing.createCellComment(anchor);
            comment.setString(factory.createRichTextString(
                "角色只能填以下四个之一：" + String.join(" / ", ROLE_LABEL.values()) + "。\n"
                    + "用户名必须唯一，已存在的账号会被跳过（只新增、不覆盖）。\n"
                    + "区域 / 部门可留空；填了但系统里没有同名项时该行仍会创建，只在报告里给提示。\n"
                    + "初始密码由系统生成，导入完成后在结果页逐人回显，请及时复制。"));
            h.getCell(3).setCellComment(comment);

            for (int i = 0; i < IMPORT_HEADER.size(); i++) s.setColumnWidth(i, 18 * 256);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("生成用户导入模板失败: " + e.getMessage(), e);
        }
    }

    // ──────────────────────────────── B-5 批量导入 ────────────────────────────────

    /**
     * 批量导入用户。
     *
     * <p><b>为什么整个方法一个事务不违反「部分成功」</b>：所有校验都在<b>写库之前</b>跑完
     * （① 行级纯内存校验 ② 一次批量查重），进入 INSERT 阶段的行已经全部合格 ⇒
     * 不存在「A 行插失败要回滚 B 行」的情形。事务在这里只承担批量写的原子提交，
     * 🚫 不是「全成功才提交」的整单闸门。
     *
     * <p>400 只用于<b>文件本身不可用</b>（非 xlsx / 解析失败 / 前 6 列表头不符），此时一行都没处理。
     * ⚠️ 只有表头、0 行数据<b>不是</b> 400，正常返 200 + 三个计数为 0 的报告。
     */
    @Transactional
    public UserImportReportDTO importUsers(byte[] xlsxBytes) {
        long t0 = System.nanoTime();
        UserImportReportDTO report = new UserImportReportDTO();

        List<ParsedRow> parsed = parse(xlsxBytes, report);

        // ── 一次性批量取数（SQL 条数与行数无关）──
        Set<String> candidateNames = new LinkedHashSet<>();
        Set<String> candidateEmails = new LinkedHashSet<>();
        for (ParsedRow p : parsed) {                  // 纯内存收集
            if (!p.username.isBlank()) candidateNames.add(p.username);
            if (!p.email.isBlank()) candidateEmails.add(p.email);
        }
        Set<String> existingNames = existingUsernames(candidateNames);
        Set<String> existingEmails = existingEmails(candidateEmails);
        Map<String, UUID> regionByName = regionIdsByName();
        Map<String, UUID> deptByName = departmentIdsByName();

        // ── 行级校验 + 创建（🚫 循环体内无任何查询）──
        Set<String> seenNames = new HashSet<>();
        Set<String> seenEmails = new HashSet<>();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusHours(24);

        for (ParsedRow p : parsed) {
            String reason = validate(p, existingNames, existingEmails, seenNames, seenEmails);
            if (reason != null) {
                report.skipped.add(new SkippedRow(p.rowNum, p.username, reason));
                continue;
            }
            seenNames.add(p.username);
            seenEmails.add(p.email);

            // 区域 / 部门：匹配不上 ⇒ 软提示，行照常创建（两表当前均 0 条，硬校验＝功能不可用）
            List<String> hints = new ArrayList<>(2);
            UUID regionId = null;
            if (!p.region.isBlank()) {
                regionId = regionByName.get(p.region);
                if (regionId == null) hints.add("区域未匹配：" + p.region);
            }
            UUID deptId = null;
            if (!p.department.isBlank()) {
                deptId = deptByName.get(p.department);
                if (deptId == null) hints.add("部门未匹配：" + p.department);
            }

            // 🚨 密码：复用 UserService 的同一套生成器与哈希，明文只进响应，不落库不写日志
            String rawPassword = userService.generatePassword();

            User u = new User();
            u.username = p.username;
            u.fullName = p.fullName;
            u.email = p.email;
            u.role = p.role;
            u.regionId = regionId;
            u.departmentId = deptId;
            u.passwordHash = userService.hashPassword(rawPassword);
            u.isFirstLogin = true;                    // 首登强制改密（沿用既有语义）
            u.status = "ACTIVE";
            u.initialPasswordExpiresAt = expiresAt;
            u.persist();                              // 只入 JDBC batch，不产生 SELECT

            report.created.add(new CreatedUser(p.rowNum, p.username, p.fullName, p.role,
                ROLE_LABEL.getOrDefault(p.role, p.role), rawPassword,
                hints.isEmpty() ? null : String.join("；", hints)));
        }

        report.createdCount = report.created.size();
        report.skippedCount = report.skipped.size();
        report.elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        return report;
    }

    /**
     * 行级校验，全部是<b>纯内存</b>判断（库侧的两个存在性集合已批量预取）。
     *
     * @return null = 通过；否则为跳过原因
     */
    private String validate(ParsedRow p, Set<String> existingNames, Set<String> existingEmails,
                            Set<String> seenNames, Set<String> seenEmails) {
        if (p.username.isBlank())               return "用户名为空";
        if (p.username.length() > USERNAME_MAX_LEN)
            return "用户名超长（最多 " + USERNAME_MAX_LEN + " 字符）";
        if (seenNames.contains(p.username))     return "文件内用户名重复，已取首行";
        if (existingNames.contains(p.username)) return "用户名已存在";
        if (p.fullName.isBlank())               return "姓名为空";
        if (p.fullName.length() > FULL_NAME_MAX_LEN)
            return "姓名超长（最多 " + FULL_NAME_MAX_LEN + " 字符）";
        if (p.email.isBlank())                  return "邮箱为空";
        if (p.email.length() > EMAIL_MAX_LEN)
            return "邮箱超长（最多 " + EMAIL_MAX_LEN + " 字符）";
        if (!EMAIL_RE.matcher(p.email).matches()) return "邮箱格式不合法：" + p.email;
        // email 有 UNIQUE 约束（user_email_key），不拦就是 INSERT 抛约束异常 → 整批 500
        if (seenEmails.contains(p.email))       return "文件内邮箱重复，已取首行";
        if (existingEmails.contains(p.email))   return "邮箱已存在：" + p.email;
        if (p.role == null)                     return "角色不合法：" + p.roleRaw;
        return null;
    }

    // ──────────────────────────────── 解析 ────────────────────────────────

    private List<ParsedRow> parse(byte[] xlsxBytes, UserImportReportDTO report) {
        if (xlsxBytes == null || xlsxBytes.length == 0) {
            throw UserApiException.badRequest("IMPORT_FILE_INVALID", "请上传 .xlsx 文件");
        }
        Workbook wb;
        try {
            wb = WorkbookFactory.create(new ByteArrayInputStream(xlsxBytes));
        } catch (Exception e) {
            // 非 xlsx / 损坏 / 加密 —— 一律「文件本身不可用」
            throw UserApiException.badRequest("IMPORT_FILE_INVALID", "请上传 .xlsx 文件");
        }
        try (Workbook workbook = wb) {
            Sheet sheet = workbook.getSheet(SHEET_NAME);
            if (sheet == null) {
                if (workbook.getNumberOfSheets() == 0) {
                    throw UserApiException.badRequest("IMPORT_HEADER_INVALID",
                        "表头不符合模板要求，请下载新模板");
                }
                sheet = workbook.getSheetAt(0);
            }
            assertHeader(sheet);

            List<ParsedRow> out = new ArrayList<>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                ParsedRow p = new ParsedRow();
                p.rowNum = r + 1;                     // 1-based，表头是第 1 行
                p.username = cellStr(row.getCell(0));
                p.fullName = cellStr(row.getCell(1));
                p.email = cellStr(row.getCell(2));
                p.roleRaw = cellStr(row.getCell(3));
                p.region = cellStr(row.getCell(4));
                p.department = cellStr(row.getCell(5));
                if (p.username.isBlank() && p.fullName.isBlank() && p.email.isBlank()
                    && p.roleRaw.isBlank() && p.region.isBlank() && p.department.isBlank()) {
                    continue;                         // 全空行不计入 totalRows
                }
                // 角色：接受中文标签，也接受直接填枚举值（大小写不敏感）；查无 ⇒ null，交给 validate 报错
                p.role = LABEL_TO_ROLE.get(p.roleRaw);
                if (p.role == null && ROLE_LABEL.containsKey(p.roleRaw.toUpperCase())) {
                    p.role = p.roleRaw.toUpperCase();
                }
                report.totalRows++;
                out.add(p);
            }
            return out;
        } catch (UserApiException e) {
            throw e;
        } catch (Exception e) {
            throw UserApiException.badRequest("IMPORT_FILE_INVALID", "请上传 .xlsx 文件");
        }
    }

    /** 前 6 列按<b>位置</b>逐列比对；错一位即 400（与材质导入同款纪律）。 */
    private void assertHeader(Sheet sheet) {
        Row h = sheet.getRow(0);
        if (h == null) {
            throw UserApiException.badRequest("IMPORT_HEADER_INVALID", "表头不符合模板要求，请下载新模板");
        }
        for (int i = 0; i < IMPORT_HEADER.size(); i++) {
            if (!IMPORT_HEADER.get(i).equals(cellStr(h.getCell(i)))) {
                throw UserApiException.badRequest("IMPORT_HEADER_INVALID",
                    "表头不符合模板要求，请下载新模板");
            }
        }
    }

    // ──────────────────────────────── 批量取数（各恒 1 条 SQL）────────────────────────────────

    private Set<String> existingUsernames(Set<String> names) {
        if (names.isEmpty()) return Set.of();
        List<User> hits = User.<User>find("username in ?1", names).list();
        Set<String> out = new HashSet<>(hits.size());
        for (User u : hits) out.add(u.username);
        return out;
    }

    private Set<String> existingEmails(Set<String> emails) {
        if (emails.isEmpty()) return Set.of();
        List<User> hits = User.<User>find("email in ?1", emails).list();
        Set<String> out = new HashSet<>(hits.size());
        for (User u : hits) out.add(u.email);
        return out;
    }

    /** 区域名 → id（ACTIVE 优先）。两表体量极小，一次全表比按名逐个查便宜且无 N+1。 */
    private Map<String, UUID> regionIdsByName() {
        Map<String, UUID> map = new LinkedHashMap<>();
        for (Region r : Region.<Region>listAll()) {
            if ("ACTIVE".equals(r.status)) map.put(r.name, r.id);
            else map.putIfAbsent(r.name, r.id);
        }
        return map;
    }

    private Map<String, UUID> departmentIdsByName() {
        Map<String, UUID> map = new LinkedHashMap<>();
        for (Department d : Department.<Department>listAll()) {
            if ("ACTIVE".equals(d.status)) map.put(d.name, d.id);
            else map.putIfAbsent(d.name, d.id);
        }
        return map;
    }

    private Map<UUID, String> regionNamesByIds(Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<UUID, String> map = new LinkedHashMap<>();
        for (Region r : Region.<Region>find("id in ?1", ids).list()) map.put(r.id, r.name);
        return map;
    }

    private Map<UUID, String> departmentNamesByIds(Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<UUID, String> map = new LinkedHashMap<>();
        for (Department d : Department.<Department>find("id in ?1", ids).list()) map.put(d.id, d.name);
        return map;
    }

    // ──────────────────────────────── helpers ────────────────────────────────

    private String fmt(OffsetDateTime t) {
        return t == null ? "" : t.format(TS_FMT);
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    /** 单元格取字符串：STRING 原样、NUMERIC 无科学计数、FORMULA 取缓存值（与材质导入同款）。 */
    private String cellStr(Cell c) {
        if (c == null) return "";
        switch (c.getCellType()) {
            case STRING:
                return c.getStringCellValue().trim();
            case NUMERIC:
                return numToStr(c.getNumericCellValue());
            case BOOLEAN:
                return Boolean.toString(c.getBooleanCellValue());
            case FORMULA:
                try {
                    switch (c.getCachedFormulaResultType()) {
                        case STRING: return c.getStringCellValue().trim();
                        case NUMERIC: return numToStr(c.getNumericCellValue());
                        default: return "";
                    }
                } catch (Exception e) {
                    return "";
                }
            default:
                return "";
        }
    }

    private String numToStr(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) return Long.toString((long) d);
        return new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
    }

    /** 文件里的一行（已 trim，未做任何库侧校验）。 */
    private static final class ParsedRow {
        int rowNum;
        String username;
        String fullName;
        String email;
        /** 原样文本，用于「角色不合法：<原值>」的回显。 */
        String roleRaw;
        /** 解析后的枚举值；解析不出为 null。 */
        String role;
        String region;
        String department;
    }
}
