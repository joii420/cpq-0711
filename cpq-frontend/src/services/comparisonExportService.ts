import api from './api';
import {
  normalizeComparisonCellValue,
  type ComparisonModel,
} from '../pages/quotation/comparisonModel';

export function normalizeComparisonExportModel(model: ComparisonModel): ComparisonModel {
  return {
    columns: model.columns.map(column => ({ ...column })),
    rows: model.rows.map(row => ({
      ...row,
      cells: Object.fromEntries(Object.entries(row.cells).map(([tag, pair]) => [tag, {
        quote: normalizeComparisonCellValue(pair.quote),
        costing: normalizeComparisonCellValue(pair.costing),
        highlighted: pair.highlighted,
      }])),
    })),
  };
}

export const comparisonExportService = {
  /** POST 已算好的比对模型，后端 POI 只写值+填色，返回 xlsx blob */
  export: (quotationId: string, model: ComparisonModel) =>
    api.post(
      `/quotations/${quotationId}/comparison/export`,
      normalizeComparisonExportModel(model),
      { responseType: 'blob' },
    ) as Promise<Blob>,
};
