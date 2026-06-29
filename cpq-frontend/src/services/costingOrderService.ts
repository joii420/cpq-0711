import api from './api';

export interface CostingOrderListItem {
  costingOrderId: string;
  costingOrderNumber: string;
  quotationId: string;
  quotationNumber: string;
  customerName: string;
  currency: string;
  submittedByName: string;
  status: string;
  rejectReason?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface CostingOrderDetail {
  costingOrderId: string;
  quotationId: string;
  costingOrderNumber: string;
  status: string;
  rejectReason?: string;
  totalAmount?: number;
  frozenDto?: string;
  createdAt: string;
  reviewedAt?: string;
}

const base = '/costing-orders';

export const costingOrderService = {
  /**
   * 列表查询。status 为可重复参数（后端 List<String>），发出格式为 status=A&status=B。
   */
  list: (params?: { statuses?: string[]; keyword?: string; sort?: string }): Promise<{ data: CostingOrderListItem[] }> =>
    api.get(base, {
      params: { status: params?.statuses, keyword: params?.keyword, sort: params?.sort },
      paramsSerializer: { indexes: null },
    }) as Promise<{ data: CostingOrderListItem[] }>,

  getById: (coid: string): Promise<{ data: CostingOrderDetail }> =>
    api.get(`${base}/${coid}`) as Promise<{ data: CostingOrderDetail }>,

  approve: (quotationId: string, comment?: string): Promise<{ data: unknown }> =>
    api.post(`/quotations/${quotationId}/costing-approve`, { comment }) as Promise<{ data: unknown }>,

  reject: (quotationId: string, comment: string): Promise<{ data: unknown }> =>
    api.post(`/quotations/${quotationId}/costing-reject`, { comment }) as Promise<{ data: unknown }>,
};
