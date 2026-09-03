package com.cpq.system.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户批量导入的结果报告（task-260902 · B-5，api.md B-5）。
 *
 * <p><b>语义：只新增，不修改，不删除。</b>部分成功，不整单回滚 —— 某行不合格只跳过该行，
 * 其余合格行照常创建。
 *
 * <p>🚨 {@link CreatedUser#initialPassword} <b>只在本响应里出现一次</b>：
 * 不落库明文、<b>不写任何日志</b>、不进导出文件。
 */
public class UserImportReportDTO {

    /** 文件里的有效数据行数（全空行不计）。 */
    public int totalRows;

    /** 本次实际新建的用户数。 */
    public int createdCount;

    /** 被跳过的行数。 */
    public int skippedCount;

    public long elapsedMs;

    /** 仅本次新建的用户，逐人带系统生成的初始密码。 */
    public List<CreatedUser> created = new ArrayList<>();

    /** 被跳过的行，逐行给出原因。 */
    public List<SkippedRow> skipped = new ArrayList<>();

    /** 新建成功的一行。 */
    public static class CreatedUser {
        /** Excel 行号（1-based，表头是第 1 行 ⇒ 首个数据行是 2）。 */
        public int rowNum;
        public String username;
        public String fullName;
        /** 角色枚举值，如 {@code SALES_REP}。 */
        public String role;
        /** 角色中文标签，如「销售代表」。 */
        public String roleLabel;
        /** 🚨 仅当次响应回显，不落库、不写日志。 */
        public String initialPassword;
        /** 软提示（区域/部门未匹配等）；无提示为 null。<b>不影响该行创建成功</b>。 */
        public String hint;

        public CreatedUser(int rowNum, String username, String fullName, String role,
                           String roleLabel, String initialPassword, String hint) {
            this.rowNum = rowNum;
            this.username = username;
            this.fullName = fullName;
            this.role = role;
            this.roleLabel = roleLabel;
            this.initialPassword = initialPassword;
            this.hint = hint;
        }
    }

    /** 被跳过的一行。 */
    public static class SkippedRow {
        public int rowNum;
        public String username;
        public String reason;

        public SkippedRow(int rowNum, String username, String reason) {
            this.rowNum = rowNum;
            this.username = username;
            this.reason = reason;
        }
    }
}
