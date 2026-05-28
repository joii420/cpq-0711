import api from './api';

/**
 * V6 料号主数据 API — material_master 表
 *
 * 替代旧的 internalMaterialService(/internal-materials → internal_material 表)。
 * 「产品管理 → 产品主数据」UI 切换数据源到 V6 主表。
 */

export interface MaterialMaster {
  id: string;
  materialNo: string;
  materialName?: string | null;
  specification?: string | null;
  dimension?: string | null;
  oldMaterialNo?: string | null;
  /** 1.银点类 / 2.非银点类 / 组成件 / 边角料 */
  materialType?: string | null;
  /** 1.正常 / 2.回收料 */
  usageProperty?: string | null;
  unitWeight?: number | null;
  standardUnit?: string | null;
  createdAt?: string;
  updatedAt?: string;
  createdBy?: string | null;
  updatedBy?: string | null;
}

export interface MaterialMasterPayload {
  materialNo: string;
  materialName?: string | null;
  specification?: string | null;
  dimension?: string | null;
  oldMaterialNo?: string | null;
  materialType?: string | null;
  usageProperty?: string | null;
  unitWeight?: number | null;
  standardUnit?: string | null;
}

export const materialMasterService = {
  list: (params: { page?: number; size?: number; keyword?: string }) =>
    api.get('/material-masters', { params }) as Promise<any>,

  getById: (id: string) =>
    api.get(`/material-masters/${id}`) as Promise<any>,

  create: (data: MaterialMasterPayload) =>
    api.post('/material-masters', data) as Promise<any>,

  update: (id: string, data: MaterialMasterPayload) =>
    api.put(`/material-masters/${id}`, data) as Promise<any>,

  delete: (id: string) =>
    api.delete(`/material-masters/${id}`) as Promise<any>,
};
