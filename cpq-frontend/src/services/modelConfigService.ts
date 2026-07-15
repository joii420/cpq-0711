import api from './api';
import type {
  ModelConfigCurrentParams,
  ModelConfigDTO,
  ModelConfigListParams,
  ModelConfigUploadPayload,
  PageResult,
} from '../types/modelConfig';

// 3D 模型配置（task-0712 B5，api.md §4）。后端统一 ApiResponse<T> 包络（{success,data,message}）；
// api.ts 拦截器只解一层 axios response.data，业务侧需要再解一层 .data 才拿到真实 payload
// （与 v6MasterDataService.unwrap 同惯用法，见该文件注释）。
const unwrap = <T>(r: any): T => (r && typeof r === 'object' && 'data' in r ? (r.data as T) : (r as T));

export const modelConfigService = {
  /** GET /model-configs — 配置中心列表（分 Tab：subjectType SALES_PART/MATERIAL + keyword + 分页）。 */
  async list(params: ModelConfigListParams): Promise<PageResult<ModelConfigDTO>> {
    const res = await api.get('/model-configs', { params });
    return unwrap<PageResult<ModelConfigDTO>>(res);
  },

  /**
   * POST /model-configs（multipart）— 上传新版本。
   * 版本号 = 该 subject 现有 max(version)+1；setCurrent=true 时同 subject 旧 isCurrent 自动降级。
   */
  async upload(payload: ModelConfigUploadPayload): Promise<ModelConfigDTO> {
    const fd = new FormData();
    fd.append('subjectType', payload.subjectType);
    fd.append('subjectKey', payload.subjectKey);
    if (payload.label) fd.append('label', payload.label);
    fd.append('glbFile', payload.glbFile);
    if (payload.thumbnailFile) fd.append('thumbnailFile', payload.thumbnailFile);
    fd.append('setCurrent', String(payload.setCurrent));
    const res = await api.post('/model-configs', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return unwrap<ModelConfigDTO>(res);
  },

  /** GET /model-configs/versions — 某 subject（subjectType+subjectKey）全部历史版本，含当前版本。 */
  async versions(params: ModelConfigCurrentParams): Promise<ModelConfigDTO[]> {
    const res = await api.get('/model-configs/versions', { params });
    return unwrap<ModelConfigDTO[]>(res) ?? [];
  },

  /** PUT /model-configs/{id}/set-current — 设为当前版本；同 subject 其余版本自动降级。 */
  async setCurrent(id: string): Promise<ModelConfigDTO> {
    const res = await api.put(`/model-configs/${id}/set-current`);
    return unwrap<ModelConfigDTO>(res);
  },

  /** DELETE /model-configs/{id} — 危险动作，调用方需先二次确认。 */
  async remove(id: string): Promise<void> {
    await api.delete(`/model-configs/${id}`);
  },

  /**
   * GET /model-configs/current — 选配 / 已有产品添加抽屉实时带出 3D（D15）。
   * 无当前版本时返回 null（非错误，`data:null`），前端占位"未配置 3D 模型"，不阻断流程。
   *
   * 材质/切行时会连续触发，调用方应传 AbortSignal 丢弃过期响应（不在 service 层引入额外全局缓存，
   * 保持简单；用法同 componentService.batchExpand / formulaService.batchEvaluate 的 signal 惯例）。
   */
  async current(params: ModelConfigCurrentParams, signal?: AbortSignal): Promise<ModelConfigDTO | null> {
    const res = await api.get('/model-configs/current', { params, signal });
    return unwrap<ModelConfigDTO | null>(res) ?? null;
  },
};
