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
}

export const compositeProcessService = {
  async list(): Promise<CompositeProcessDef[]> {
    const res = await api.get('/composite-processes');
    return (res as CompositeProcessDef[]) ?? [];
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
};
