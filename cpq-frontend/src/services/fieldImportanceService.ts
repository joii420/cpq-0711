import api from './api';
import type { FieldImportanceItem, UpdateFieldImportanceRequest } from '../types/field-importance';

// Mock 开关
const USE_MOCK = import.meta.env.VITE_USE_MOCK_FIELD_IMPORTANCE === 'true';

// ---- Mock 数据 ----
const MOCK_ATTRIBUTES: FieldImportanceItem[] = [
  { id: 'attr-001', configId: 'sheet-001', columnLetter: 'A', columnTitle: '物料编号', variableCode: 'mat_code', variableLabel: '物料编号', dataType: 'IDENTIFIER', importanceLevel: 'CRITICAL', affectsCalculation: true, isRequired: true, remark: '', status: 'ACTIVE', sortOrder: 1 },
  { id: 'attr-002', configId: 'sheet-001', columnLetter: 'B', columnTitle: '物料名称', variableCode: 'mat_name', variableLabel: '物料名称', dataType: 'IDENTIFIER', importanceLevel: 'CRITICAL', affectsCalculation: false, isRequired: true, remark: '', status: 'ACTIVE', sortOrder: 2 },
  { id: 'attr-003', configId: 'sheet-001', columnLetter: 'C', columnTitle: '规格型号', variableCode: 'mat_spec', variableLabel: '规格型号', dataType: 'IDENTIFIER', importanceLevel: 'IMPORTANT', affectsCalculation: false, isRequired: false, remark: '', status: 'ACTIVE', sortOrder: 3 },
  { id: 'attr-004', configId: 'sheet-001', columnLetter: 'D', columnTitle: '单价', variableCode: 'mat_unit_price', variableLabel: '单价', dataType: 'VALUE', importanceLevel: 'CRITICAL', affectsCalculation: true, isRequired: true, remark: '影响报价计算', status: 'ACTIVE', sortOrder: 4 },
  { id: 'attr-005', configId: 'sheet-001', columnLetter: 'E', columnTitle: '备注', variableCode: 'mat_remark', variableLabel: '备注', dataType: 'IDENTIFIER', importanceLevel: 'NORMAL', affectsCalculation: false, isRequired: false, remark: '', status: 'ACTIVE', sortOrder: 5 },
  { id: 'attr-006', configId: 'sheet-002', columnLetter: 'A', columnTitle: 'BOM编号', variableCode: 'bom_code', variableLabel: 'BOM编号', dataType: 'IDENTIFIER', importanceLevel: 'CRITICAL', affectsCalculation: true, isRequired: true, remark: '', status: 'ACTIVE', sortOrder: 1 },
  { id: 'attr-007', configId: 'sheet-002', columnLetter: 'B', columnTitle: '用量', variableCode: 'bom_qty', variableLabel: '用量', dataType: 'VALUE', importanceLevel: 'CRITICAL', affectsCalculation: true, isRequired: true, remark: '用于 BOM 展开计算', status: 'ACTIVE', sortOrder: 2 },
];

// TODO: 后端 PUT /api/cpq/basic-data-attributes/{id}/importance 端点尚未实现
// BasicDataConfigResource 中只有 PUT /attributes/{id} 更新全部字段
// 前端当前使用 mock 模式，联调时需后端补充专用 importance 端点，或前端改为调用全量 PUT
// RECORD TODO: backend needs PUT /api/cpq/basic-data-attributes/{id}/importance endpoint

export const fieldImportanceService = {
  /**
   * 获取指定 sheet 下的所有属性（含重要性字段）
   * 后端 GET /api/cpq/basic-data-config/attributes?sheetId= 返回 BasicDataAttributeDTO
   * 注意：后端 BasicDataAttributeDTO 目前不含 importanceLevel/affectsCalculation，
   * 这两个字段需要后端扩展，当前 mock 模拟带这些字段的完整数据
   */
  listBySheet: (sheetId: string): Promise<{ data: FieldImportanceItem[] }> => {
    if (USE_MOCK) {
      const filtered = MOCK_ATTRIBUTES.filter((a) => a.configId === sheetId);
      return Promise.resolve({ data: filtered });
    }
    // 实际调用时，后端需扩展 BasicDataAttributeDTO 含重要性字段
    return api.get('/basic-data-config/attributes', { params: { sheetId } }) as Promise<{ data: FieldImportanceItem[] }>;
  },

  /**
   * 更新字段重要性
   * TODO: 后端需补充专用 endpoint PUT /basic-data-attributes/{id}/importance
   * 当前 mock 处理；联调时替换下方注释掉的真实调用
   */
  updateImportance: (id: string, req: UpdateFieldImportanceRequest): Promise<{ data: FieldImportanceItem }> => {
    if (USE_MOCK) {
      const idx = MOCK_ATTRIBUTES.findIndex((a) => a.id === id);
      if (idx < 0) return Promise.reject(new Error('Not found'));
      MOCK_ATTRIBUTES[idx] = { ...MOCK_ATTRIBUTES[idx], ...req };
      return Promise.resolve({ data: MOCK_ATTRIBUTES[idx] });
    }
    // 真实调用（后端补充后启用）：
    // return api.put(`/basic-data-attributes/${id}/importance`, req) as Promise<{ data: FieldImportanceItem }>;
    // 临时 fallback：调用全量更新接口，只传重要性相关字段
    return api.put(`/basic-data-config/attributes/${id}`, req) as Promise<{ data: FieldImportanceItem }>;
  },
};
