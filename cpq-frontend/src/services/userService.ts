import api from './api';
import { downloadExport, exportFileName } from '../utils/exportDownload';

/** 用户导入报告里「本次新建」的一行（api.md · B-5） */
export interface UserImportCreatedRow {
  /** Excel 行号（从 1 开始，含表头行） */
  rowNum: number;
  username: string;
  fullName: string;
  /** 角色枚举，如 SALES_REP */
  role: string;
  /** 角色中文标签，如「销售代表」 */
  roleLabel?: string | null;
  /** 🚨 只在本次响应里出现一次：不落库明文、不写日志、不进导出文件 */
  initialPassword: string;
  /**
   * 软提示：用户**已创建成功**，只是某个非必需字段没落上（如「部门未匹配：xxx」）。
   * 🚫 与 `skipped` 不是一回事 —— 跳过 = 整行没创建。
   */
  hint?: string | null;
}

/** 用户导入报告里「被跳过」的一行 —— 这一行**没有**创建用户 */
export interface UserImportSkippedRow {
  rowNum: number;
  username?: string | null;
  reason: string;
}

/** POST /users/import 的报告（api.md · B-5 `UserImportReportDTO`） */
export interface UserImportReport {
  /** 读到的数据行数（不含表头） */
  totalRows: number;
  createdCount: number;
  skippedCount: number;
  elapsedMs: number;
  created: UserImportCreatedRow[];
  skipped: UserImportSkippedRow[];
}

export const userService = {
  list: (params: { page?: number; size?: number; role?: string; status?: string; keyword?: string }) =>
    api.get('/users', { params }) as Promise<any>,
  create: (data: any) => api.post('/users', data) as Promise<any>,
  update: (id: string, data: any) => api.put(`/users/${id}`, data) as Promise<any>,
  updateStatus: (id: string, status: string) => api.patch(`/users/${id}`, { status }) as Promise<any>,
  resetPassword: (id: string) => api.post(`/users/${id}/reset-password`) as Promise<any>,

  // ── task-260902 · 导出 / 导入 ──

  /**
   * GET /users/export — 导出**当前筛选结果的全量**（不受分页限制，AC-13）。
   * 参数与 `list` 同名同义，但不传 page/size。
   */
  async exportUsers(params: { keyword?: string; role?: string; status?: string }): Promise<void> {
    await downloadExport('/users/export', {
      ...(params.keyword ? { keyword: params.keyword } : {}),
      ...(params.role ? { role: params.role } : {}),
      ...(params.status ? { status: params.status } : {}),
    }, exportFileName('用户列表'));
  },

  /** GET /users/import/template — 下载用户导入模板（6 列 + 1 行示例，AC-14） */
  async downloadImportTemplate(): Promise<void> {
    await downloadExport('/users/import/template', undefined, '用户导入模板.xlsx');
  },

  /**
   * POST /users/import — 上传 xlsx 批量新增用户（只新增、重复跳过）。
   * 与其余 `/users` 端点一样走 `ApiResponse<T>` 信封 ⇒ 取 `res.data`。
   * 「文件本身不可用」（非 xlsx / 表头不符）走 400 抛错；脏数据行走 200 报告里的 `skipped`。
   */
  async importUsers(file: File): Promise<UserImportReport> {
    const fd = new FormData();
    fd.append('file', file);
    const res = await api.post('/users/import', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }) as unknown as { data?: UserImportReport };
    return res?.data as UserImportReport;
  },
};
