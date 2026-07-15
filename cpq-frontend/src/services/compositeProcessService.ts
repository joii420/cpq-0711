import api from './api';

export interface CompositeProcessParamDef {
  id: string;
  label: string;
  unit: string;
  type: 'number' | 'text';
  placeholder?: string;
}

/**
 * `composite_process_def` CRUD 形状（getById/create/update 仍用）。该表保留给 v0.4 configurator，
 * 选配侧候选已改读 {@link CompositeProcessCandidateDTO}（见 list()，B6 架构决策 2-2A）。
 */
export interface CompositeProcessDef {
  id: string;
  code: string;
  name: string;
  icon: string;
  description: string;
  /** Raw JSON string from backend (DB JSONB column passthrough). Use parseParamSchema() to get typed list. */
  paramSchema: string;
  sortOrder: number;
  status?: 'ACTIVE' | 'INACTIVE';
}

export interface CompositeProcessDefUpsertRequest {
  code: string;
  name: string;
  icon?: string;
  description?: string;
  /** Structured param list — backend serialises to JSONB */
  paramSchema: CompositeProcessParamDef[];
  sortOrder?: number;
  status?: 'ACTIVE' | 'INACTIVE';
}

/**
 * 选配组合工艺候选（task-0712 B6，架构决策 2-2A 定稿，api.md §3.4）。
 * `GET /composite-processes` 数据源已从 `composite_process_def` 改读工序库
 * `process_master WHERE process_category='ASSEMBLY'`，DTO 变瘦（去 icon/paramSchema，放弃参数化）。
 *
 * 标识锚点 = `code`（= process_master.process_no），与前端选值 / 指纹 CPROC /
 * capacity.process_no / quotation_line_composite_process.def_code 五处一致（AP-44 精神）。
 */
export interface CompositeProcessCandidateDTO {
  code: string;
  name: string;
  currency?: string | null;
  unit?: string | null;
  defectRate?: number | null;
}

export const compositeProcessService = {
  /** GET /composite-processes — 选配候选源，返回 CompositeProcessCandidateDTO[]（无 ApiResponse 信封，沿用现状）。 */
  async list(): Promise<CompositeProcessCandidateDTO[]> {
    const res = await api.get('/composite-processes');
    return (res as unknown as CompositeProcessCandidateDTO[]) ?? [];
  },

  /** Parse paramSchema raw JSON string into typed list. Returns [] on parse error. */
  parseParamSchema(raw: string): CompositeProcessParamDef[] {
    try {
      const parsed = JSON.parse(raw || '[]');
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  },

  async detail(id: string): Promise<CompositeProcessDef> {
    return (await api.get(`/composite-processes/${id}`)) as CompositeProcessDef;
  },
  async create(req: CompositeProcessDefUpsertRequest): Promise<CompositeProcessDef> {
    return (await api.post('/composite-processes', req)) as CompositeProcessDef;
  },
  async update(id: string, req: CompositeProcessDefUpsertRequest): Promise<CompositeProcessDef> {
    return (await api.put(`/composite-processes/${id}`, req)) as CompositeProcessDef;
  },
  async deleteSoft(id: string): Promise<void> {
    await api.delete(`/composite-processes/${id}`);
  },
};
