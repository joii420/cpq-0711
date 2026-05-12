import api from './api';

/** 料号版本日志 (S2 后端 PartVersionLogDTO 镜像) */
export interface PartVersionLog {
  customerProductNo: string;
  hfPartNo: string;
  version: number;
  contentHash: string | null;
  diffSummaryJson: string | null;
  sourceExcel: string | null;
  sourceImportId: string | null;
  createdAt: string | null;  // ISO 8601
  createdBy: string | null;
}

/** 当前+历史版本响应 */
export interface VersionInfoResponse {
  customerProductNo: string;
  hfPartNo: string;
  currentVersion: number;
  history: PartVersionLog[];
}

/** 单表 diff 计数 */
export interface DiffSummary {
  added: number;
  changed: number;
  deleted: number;
}

/** 三路判定 */
export type DecisionAction = 'NO_CHANGE' | 'REVERT_TO_HISTORICAL' | 'NEW_VERSION';

export interface VersionDecision {
  action: DecisionAction;
  currentVersion: number;
  proposedVersion: number;
  matchedHash: string | null;
  diffByTable: Record<string, DiffSummary>;
  allHistoricalVersions: number[];
}

export interface ProposeRequest {
  customerProductNo: string;
  hfPartNo: string;
  rowsByTable: Record<string, Array<Record<string, unknown>>>;
}

export interface ApplyBumpRequest {
  contentHash: string | null;
  sourceExcel: string | null;
  diffByTable: Record<string, DiffSummary> | null;
}

export const partVersionService = {
  /** 查 (cpn, hf) 当前版本 + 历史日志 */
  getVersionInfo: async (
    customerProductNo: string,
    hfPartNo: string
  ): Promise<VersionInfoResponse> => {
    const res = (await api.get(
      `/part-version/${encodeURIComponent(customerProductNo)}/${encodeURIComponent(hfPartNo)}`
    )) as unknown as { data: VersionInfoResponse };
    return res.data;
  },

  /** 计算 (cpn, hf, version) 存储指纹 */
  getFingerprint: async (
    customerProductNo: string,
    hfPartNo: string,
    version?: number
  ): Promise<{ customerProductNo: string; hfPartNo: string; version: number; contentHash: string }> => {
    const url = `/part-version/${encodeURIComponent(customerProductNo)}/${encodeURIComponent(hfPartNo)}/fingerprint` +
      (version != null ? `?version=${version}` : '');
    const res = (await api.get(url)) as unknown as {
      data: { customerProductNo: string; hfPartNo: string; version: number; contentHash: string };
    };
    return res.data;
  },

  /** 三路判定 (S2 占位实现, S3 阶段 UI 调用) */
  propose: async (req: ProposeRequest): Promise<VersionDecision> => {
    const res = (await api.post('/part-version/propose', req)) as unknown as { data: VersionDecision };
    return res.data;
  },

  /** 升版: 写日志 + bump current_version */
  apply: async (
    customerProductNo: string,
    hfPartNo: string,
    req: ApplyBumpRequest
  ): Promise<{ customerProductNo: string; hfPartNo: string; newVersion: number }> => {
    const res = (await api.post(
      `/part-version/${encodeURIComponent(customerProductNo)}/${encodeURIComponent(hfPartNo)}/apply`,
      req
    )) as unknown as { data: { customerProductNo: string; hfPartNo: string; newVersion: number } };
    return res.data;
  },

  /** 切换激活版本到历史版本 (REVERT) */
  switchVersion: async (
    customerProductNo: string,
    hfPartNo: string,
    targetVersion: number
  ): Promise<{ customerProductNo: string; hfPartNo: string; activeVersion: number }> => {
    const res = (await api.put(
      `/part-version/${encodeURIComponent(customerProductNo)}/${encodeURIComponent(hfPartNo)}/switch/${targetVersion}`
    )) as unknown as { data: { customerProductNo: string; hfPartNo: string; activeVersion: number } };
    return res.data;
  },
};
