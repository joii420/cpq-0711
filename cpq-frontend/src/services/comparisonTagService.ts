import api from './api';

export interface ComparisonTag {
  id: string;
  code: string;
  label: string;
  groupName: string;
  groupSortOrder: number;
  tagSortOrder: number;
  isBuiltin: boolean;
  status: 'ACTIVE' | 'DISABLED';
  description?: string;
  createdAt?: string;
  updatedAt?: string;
}

export const comparisonTagService = {
  list: (status?: string) =>
    api.get('/comparison-tags', { params: status ? { status } : undefined }) as Promise<{ data: ComparisonTag[] }>,
  getById: (id: string) =>
    api.get(`/comparison-tags/${id}`) as Promise<{ data: ComparisonTag }>,
  create: (data: Partial<ComparisonTag>) =>
    api.post('/comparison-tags', data) as Promise<{ data: ComparisonTag }>,
  update: (id: string, data: Partial<ComparisonTag>) =>
    api.put(`/comparison-tags/${id}`, data) as Promise<{ data: ComparisonTag }>,
  delete: (id: string) =>
    api.delete(`/comparison-tags/${id}`) as Promise<{ data: void }>,
};
