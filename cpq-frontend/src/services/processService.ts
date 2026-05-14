import api from './api';

export interface ProductProcessItem {
  processId: string;
  sortOrder: number;
  isRequired: boolean;
}

export interface Process {
  id: string;
  code: string;
  name: string;
  category: string;
  description?: string;
  isRequired?: boolean;
  sortOrder?: number;
  status?: 'ACTIVE' | 'DISABLED';
}

export interface ProcessUpsertRequest {
  code: string;
  name: string;
  category: string;
  description?: string;
  isRequired?: boolean;
  sortOrder?: number;
  status?: 'ACTIVE' | 'DISABLED';
}

export const PROCESS_CATEGORIES: Array<{ value: string; label: string }> = [
  { value: 'SURFACE_TREATMENT', label: '表面处理' },
  { value: 'MACHINING',         label: '机加工' },
  { value: 'HEAT_TREATMENT',    label: '热处理' },
  { value: 'ASSEMBLY',          label: '装配' },
  { value: 'INSPECTION',        label: '检验' },
  { value: 'PACKAGING',         label: '包装' },
];

export const processService = {
  // --- existing exports (product-process binding, kept intact) ---
  listAll: (category?: string) => api.get('/processes', { params: { category } }) as Promise<any>,
  getProductProcesses: (productId: string) => api.get(`/processes/products/${productId}/processes`) as Promise<any>,
  bindProcesses: (productId: string, processes: ProductProcessItem[]) =>
    api.post(`/processes/products/${productId}/processes`, { processes }) as Promise<any>,
  unbindAll: (productId: string) => api.delete(`/processes/products/${productId}/processes`) as Promise<any>,

  // --- CRUD for ProcessManagement page ---
  async list(params?: { category?: string }): Promise<Process[]> {
    const res = await api.get('/processes', { params });
    return Array.isArray(res) ? res : ((res as any)?.data ?? (res as any)?.content ?? []);
  },
  async detail(id: string): Promise<Process> {
    return (await api.get(`/processes/${id}`)) as Process;
  },
  async create(req: ProcessUpsertRequest): Promise<Process> {
    return (await api.post('/processes', req)) as Process;
  },
  async update(id: string, req: ProcessUpsertRequest): Promise<Process> {
    return (await api.put(`/processes/${id}`, req)) as Process;
  },
  async deleteSoft(id: string): Promise<void> {
    await api.delete(`/processes/${id}`);
  },
};
