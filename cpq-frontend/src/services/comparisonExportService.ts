import api from './api';
import type { ComparisonModel } from '../pages/quotation/comparisonModel';

export const comparisonExportService = {
  /** POST 已算好的比对模型，后端 POI 只写值+填色，返回 xlsx blob */
  export: (quotationId: string, model: ComparisonModel) =>
    api.post(`/quotations/${quotationId}/comparison/export`, model, { responseType: 'blob' }) as Promise<any>,
};
