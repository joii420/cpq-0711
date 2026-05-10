import api from './api';

export type CostingSummaryStatus = 'DRAFT' | 'COMPUTED' | 'PUBLISHED' | 'ARCHIVED';

export interface CostingSummary {
  id?: string;
  summaryNo?: string;
  hfPartNo: string;
  elementVersionId: string;
  materialVersionId: string;
  exchangeVersionId: string;
  status?: CostingSummaryStatus;
  quoteCurrency?: string;
  notes?: string;
  createdAt?: string;
  updatedAt?: string;
  computedAt?: string;
  publishedAt?: string;
}

export interface CostingSummaryOverride {
  id?: string;
  summaryId?: string;
  targetKind: 'ELEMENT' | 'MATERIAL' | 'EXCHANGE';
  targetKey: string;
  fieldName: string;
  overrideValue: number;
  notes?: string;
}

export interface CostingSummaryResult {
  id?: string;
  summaryId?: string;
  metricCode: string;
  metricLabel?: string;
  value?: number;
  currency?: string;
  formulaUsed?: string;
  sortOrder?: number;
}

const base = '/costing-summary';

export const costingSummaryService = {
  list: (params?: { hfPartNo?: string; status?: CostingSummaryStatus }) =>
    api.get(base, { params }) as Promise<{ data: CostingSummary[] }>,
  get: (id: string) =>
    api.get(`${base}/${id}`) as Promise<{ data: CostingSummary }>,
  create: (req: CostingSummary) =>
    api.post(base, req) as Promise<{ data: CostingSummary }>,
  delete: (id: string) =>
    api.delete(`${base}/${id}`) as Promise<{ data: void }>,
  compute: (id: string) =>
    api.post(`${base}/${id}/compute`) as Promise<{ data: CostingSummaryResult[] }>,
  publish: (id: string) =>
    api.post(`${base}/${id}/publish`) as Promise<{ data: CostingSummary }>,
  archive: (id: string) =>
    api.post(`${base}/${id}/archive`) as Promise<{ data: CostingSummary }>,
  listResults: (id: string) =>
    api.get(`${base}/${id}/results`) as Promise<{ data: CostingSummaryResult[] }>,

  // overrides
  listOverrides: (id: string) =>
    api.get(`${base}/${id}/overrides`) as Promise<{ data: CostingSummaryOverride[] }>,
  saveOverride: (id: string, req: CostingSummaryOverride) =>
    api.post(`${base}/${id}/overrides`, req) as Promise<{ data: CostingSummaryOverride }>,
  deleteOverride: (overrideId: string) =>
    api.delete(`${base}/overrides/${overrideId}`) as Promise<{ data: void }>,
};
