import api from './api';

export interface TemplateFormula {
  name: string;
  expression: string;
  dataType: string;
  dependsOn?: string[];
  description?: string;
}

export interface EvaluateContext {
  customerId: string;
  partNo: string;
}

export interface EvaluateResult {
  value: any;
  trace?: Record<string, any>;
}

export const templateFormulaService = {
  list: (templateId: string): Promise<any> =>
    api.get(`/templates/${templateId}/formulas`),

  add: (templateId: string, data: TemplateFormula): Promise<any> =>
    api.post(`/templates/${templateId}/formulas`, data),

  update: (templateId: string, name: string, data: TemplateFormula): Promise<any> =>
    api.put(`/templates/${templateId}/formulas/${encodeURIComponent(name)}`, data),

  delete: (templateId: string, name: string): Promise<any> =>
    api.delete(`/templates/${templateId}/formulas/${encodeURIComponent(name)}`),

  evaluate: (templateId: string, name: string, ctx: EvaluateContext): Promise<any> =>
    api.post(
      `/templates/${templateId}/formulas/${encodeURIComponent(name)}/evaluate`,
      ctx
    ),
};
