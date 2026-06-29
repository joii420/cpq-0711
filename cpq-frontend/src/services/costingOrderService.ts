import api from './api';

export interface CostingOrderListItem {
  quotationId: string;
  quotationNumber: string;
  customerName: string;
  currency: string;
  submittedByName: string;
  status: string;
  createdAt: string;
}

const base = '/costing-orders';

export const costingOrderService = {
  list: (params?: { status?: string; sort?: string }): Promise<{ data: CostingOrderListItem[] }> =>
    api.get(base, { params }) as Promise<{ data: CostingOrderListItem[] }>,

  approve: (quotationId: string, comment?: string): Promise<{ data: unknown }> =>
    api.post(`/quotations/${quotationId}/costing-approve`, { comment }) as Promise<{ data: unknown }>,

  reject: (quotationId: string, comment: string): Promise<{ data: unknown }> =>
    api.post(`/quotations/${quotationId}/costing-reject`, { comment }) as Promise<{ data: unknown }>,
};
