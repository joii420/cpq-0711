import api from './api';
import type { EffectiveTemplateDTO } from '../types/configure';

/** `POST /sel-templates` upsert 请求体（api.md §1.1）；归属维度 = 客户产品分类（task-0712 换轴）。 */
export interface SelTemplateUpsertRequest {
  productCategoryId: string;
  name: string;
  status?: string;
  items: Array<{ paramTypeCode: string; enabled: boolean; allowedValues: string[] }>;
}

export const selTemplateService = {
  listParamTypes: () => api.get('/sel-param-types') as Promise<any>,
  candidates: (code: string) => api.get(`/sel-param-types/${code}/candidates`) as Promise<any>,
  list: () => api.get('/sel-templates') as Promise<any>,
  getById: (id: string) => api.get(`/sel-templates/${id}`) as Promise<any>,
  upsert: (data: SelTemplateUpsertRequest) => api.post('/sel-templates', data) as Promise<any>,
  delete: (id: string) => api.delete(`/sel-templates/${id}`) as Promise<any>,
  /**
   * GET /sel-templates/effective?customerNo= — 有效模板解析（api.md §1.4，D6）。
   * 选配添加抽屉打开即调；兜底链：客户行业模板 → __DEFAULT__ → hasTemplate=false。
   *
   * 注意：与上面几个方法不同，本方法已内部解开 ApiResponse 信封，直接返回 `data` 载荷
   * （调用方不需要再 `.then(res => res.data)`）——因为这是本次(task-0712)新增方法，
   * 直接给类型安全的返回值；其余方法维持历史 `Promise<any>` 原始信封行为不动，避免动老代码。
   */
  effective: async (customerNo: string): Promise<EffectiveTemplateDTO> => {
    const res: any = await api.get('/sel-templates/effective', { params: { customerNo } });
    return (res && typeof res === 'object' && 'data' in res ? res.data : res) as EffectiveTemplateDTO;
  },
};
