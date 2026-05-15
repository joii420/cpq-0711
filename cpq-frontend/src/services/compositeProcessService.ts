import api from './api';

export interface CompositeProcessParamDef {
  id: string;
  label: string;
  unit: string;
  type: 'number' | 'text';
  placeholder?: string;
}

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

export const compositeProcessService = {
  async list(): Promise<CompositeProcessDef[]> {
    const res = await api.get('/composite-processes');
    return (res as unknown as CompositeProcessDef[]) ?? [];
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
