import api from './api';

export interface SheetResultDTO {
  sheetName: string;
  totalRows: number;
  successRows: number;
  failedRows: number;
  errors: Array<{ rowNo: number; column: string; message: string }>;
  writtenCounts: Record<string, number>;
}

export interface ImportResultDTO {
  importRecordId: string;
  systemType: 'QUOTE' | 'PRICING';
  status: 'SUCCESS' | 'PARTIAL' | 'FAILED';
  totalSuccessRows: number;
  totalFailedRows: number;
  sheetResults: SheetResultDTO[];
}

const BASE = '/basic-data-import/v6';

export const basicDataImportV6Service = {
  /** 报价基础数据导入（19 Sheet）。customerId 必填——customer_no 由后端转换注入。 */
  async importQuote(customerId: string, file: File): Promise<ImportResultDTO> {
    const fd = new FormData();
    fd.append('customerId', customerId);
    fd.append('file', file);
    const res: any = await api.post(`${BASE}/quote`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return (res.data ?? res) as ImportResultDTO;
  },

  /** 核价基础数据导入（24 Sheet）。无 customerId（customer_no 从 Excel 行读取）。 */
  async importPricing(file: File): Promise<ImportResultDTO> {
    const fd = new FormData();
    fd.append('file', file);
    const res: any = await api.post(`${BASE}/pricing`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return (res.data ?? res) as ImportResultDTO;
  },

  /** 查询导入记录详情。 */
  async getResult(recordId: string): Promise<Record<string, unknown>> {
    const res: any = await api.get(`${BASE}/${recordId}`);
    return (res.data ?? res) as Record<string, unknown>;
  },
};
