import api from './api';

export interface WithdrawRequest {
  id: string;
  quotationId: string;
  requestedBy: string;
  requestedByName?: string;
  reason: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  decidedBy?: string;
  decidedByName?: string;
  decidedAt?: string;
  decisionNote?: string;
  createdAt: string;
}

export const quotationWithdrawService = {
  list: (quotationId: string) =>
    api.get(`/quotations/${quotationId}/withdraw-requests`) as Promise<{ data: WithdrawRequest[] }>,
  getPending: (quotationId: string) =>
    api.get(`/quotations/${quotationId}/withdraw-requests/pending`) as Promise<{ data: WithdrawRequest | null }>,
  request: (quotationId: string, reason: string) =>
    api.post(`/quotations/${quotationId}/withdraw-request`, { reason }) as Promise<{ data: WithdrawRequest }>,
  approve: (quotationId: string, note?: string) =>
    api.post(`/quotations/${quotationId}/withdraw/approve`, { note }) as Promise<{ data: WithdrawRequest }>,
  reject: (quotationId: string, note?: string) =>
    api.post(`/quotations/${quotationId}/withdraw/reject`, { note }) as Promise<{ data: WithdrawRequest }>,
};
