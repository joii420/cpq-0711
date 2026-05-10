import axios from 'axios';
import type {
  SystemConfigDTO,
  CreateSystemConfigRequest,
  UpdateSystemConfigRequest,
  ConfigCategory,
} from '../types/system-config';

// 系统配置 API 路径不在 /api/cpq 下，需要独立实例
const sysApi = axios.create({
  baseURL: '/api/system',
  timeout: 30000,
  withCredentials: true,
  headers: { 'Content-Type': 'application/json' },
});

sysApi.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (error.response?.status === 401) {
      window.location.href = '/login';
    }
    const msg = error.response?.data?.message || 'Network error';
    return Promise.reject(new Error(msg));
  },
);

// Mock 开关
const USE_MOCK = import.meta.env.VITE_USE_MOCK_SYSTEM_CONFIG === 'true';

// ---- Mock 数据 ----
const MOCK_CONFIGS: SystemConfigDTO[] = [
  { configKey: 'quote.validity.days', configValue: '30', defaultValue: '30', dataType: 'NUMBER', category: 'validation', description: '报价单有效天数', modifiableBy: 'SALES_MANAGER', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-04-20T10:00:00Z' },
  { configKey: 'quote.max.line.items', configValue: '200', defaultValue: '200', dataType: 'NUMBER', category: 'validation', description: '报价单最大行项目数', modifiableBy: 'SYSTEM_ADMIN', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' },
  { configKey: 'import.batch.size', configValue: '500', defaultValue: '500', dataType: 'NUMBER', category: 'import', description: '导入批次大小', modifiableBy: 'SYSTEM_ADMIN', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' },
  { configKey: 'import.timeout.seconds', configValue: '120', defaultValue: '120', dataType: 'NUMBER', category: 'import', description: '导入超时时间（秒）', modifiableBy: 'SYSTEM_ADMIN', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' },
  { configKey: 'data.retention.days', configValue: '365', defaultValue: '365', dataType: 'NUMBER', category: 'retention', description: '数据保留天数', modifiableBy: 'SYSTEM_ADMIN', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' },
  { configKey: 'element.price.auto.update', configValue: 'false', defaultValue: 'false', dataType: 'BOOLEAN', category: 'element_price', description: '元素单价是否自动更新', modifiableBy: 'SALES_MANAGER', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-04-25T08:00:00Z' },
  { configKey: 'element.price.default.currency', configValue: 'RMB', defaultValue: 'RMB', dataType: 'STRING', category: 'element_price', description: '元素价格默认币种', modifiableBy: 'SALES_MANAGER', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' },
  { configKey: 'business.discount.max', configValue: '0.30', defaultValue: '0.30', dataType: 'NUMBER', category: 'business', description: '最大折扣率', modifiableBy: 'SALES_MANAGER', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-04-22T14:30:00Z' },
  { configKey: 'business.auto.approve.threshold', configValue: '10000', defaultValue: '10000', dataType: 'NUMBER', category: 'business', description: '自动审批金额阈值（元）', modifiableBy: 'SYSTEM_ADMIN', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' },
];

// ---- Service ----
export const systemConfigService = {
  list: (category?: ConfigCategory): Promise<{ data: SystemConfigDTO[] }> => {
    if (USE_MOCK) {
      const filtered = category ? MOCK_CONFIGS.filter((c) => c.category === category) : MOCK_CONFIGS;
      return Promise.resolve({ data: filtered });
    }
    return sysApi.get('/configs', { params: category ? { category } : undefined }) as Promise<{ data: SystemConfigDTO[] }>;
  },

  get: (key: string): Promise<{ data: SystemConfigDTO }> => {
    if (USE_MOCK) {
      const found = MOCK_CONFIGS.find((c) => c.configKey === key);
      if (!found) return Promise.reject(new Error('Not found'));
      return Promise.resolve({ data: found });
    }
    return sysApi.get(`/configs/${key}`) as Promise<{ data: SystemConfigDTO }>;
  },

  create: (req: CreateSystemConfigRequest): Promise<{ data: SystemConfigDTO }> => {
    if (USE_MOCK) {
      const newItem: SystemConfigDTO = { ...req, defaultValue: req.defaultValue || null, description: req.description || null, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() };
      MOCK_CONFIGS.push(newItem);
      return Promise.resolve({ data: newItem });
    }
    return sysApi.post('/configs', req) as Promise<{ data: SystemConfigDTO }>;
  },

  update: (key: string, req: UpdateSystemConfigRequest): Promise<{ data: SystemConfigDTO }> => {
    if (USE_MOCK) {
      const idx = MOCK_CONFIGS.findIndex((c) => c.configKey === key);
      if (idx < 0) return Promise.reject(new Error('Not found'));
      MOCK_CONFIGS[idx] = { ...MOCK_CONFIGS[idx], ...req, updatedAt: new Date().toISOString() };
      return Promise.resolve({ data: MOCK_CONFIGS[idx] });
    }
    return sysApi.put(`/configs/${key}`, req) as Promise<{ data: SystemConfigDTO }>;
  },

  delete: (key: string): Promise<{ data: void }> => {
    if (USE_MOCK) {
      const idx = MOCK_CONFIGS.findIndex((c) => c.configKey === key);
      if (idx >= 0) MOCK_CONFIGS.splice(idx, 1);
      return Promise.resolve({ data: undefined });
    }
    return sysApi.delete(`/configs/${key}`) as Promise<{ data: void }>;
  },
};
