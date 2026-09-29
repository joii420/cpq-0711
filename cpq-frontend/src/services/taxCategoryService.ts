import api from './api';

export interface TaxCategory {
  id: string;
  code: string;
  name: string;
  status: 'ACTIVE' | 'INACTIVE' | string;
  isDefault: boolean;
  effectiveVatRate?: string | number | null;
  effectiveVersionId?: string | null;
}

export interface TaxVatVersion {
  id: string;
  taxCategoryId: string;
  vatRate: string | number;
  effectiveFrom: string;
  effectiveTo?: string | null;
  status: 'ACTIVE' | 'INACTIVE' | string;
  createdAt?: string;
}

export interface TaxCategorySaveRequest {
  code?: string;
  name: string;
  status?: string;
  description?: string;
}

export interface TaxVatVersionSaveRequest {
  vatRate: number;
  effectiveFrom: string;
  effectiveTo?: string | null;
  status?: string;
}

export const taxCategoryService = {
  list: (params?: { keyword?: string; status?: string; effectiveOn?: string }) =>
    api.get('/tax-categories', { params }) as Promise<{ data: TaxCategory[] }>,
  create: (req: TaxCategorySaveRequest) =>
    api.post('/tax-categories', req) as Promise<{ data: TaxCategory }>,
  update: (id: string, req: TaxCategorySaveRequest) =>
    api.put(`/tax-categories/${id}`, req) as Promise<{ data: TaxCategory }>,
  setDefault: (id: string) => api.put('/tax-categories/default', { taxCategoryId: id }) as Promise<{ data: void }>,
  versions: (id: string) => api.get(`/tax-categories/${id}/vat-versions`) as Promise<{ data: TaxVatVersion[] }>,
  addVersion: (id: string, req: TaxVatVersionSaveRequest) =>
    api.post(`/tax-categories/${id}/vat-versions`, req) as Promise<{ data: TaxVatVersion }>,
  updateVersion: (id: string, versionId: string, req: TaxVatVersionSaveRequest) =>
    api.put(`/tax-categories/${id}/vat-versions/${versionId}`, req) as Promise<{ data: TaxVatVersion }>,
};
