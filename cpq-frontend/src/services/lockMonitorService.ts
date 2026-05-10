import axios from 'axios';
import type { ProductImportLockDTO, DdlLockStatusDTO } from '../types/lock-monitor';

// 锁监控 API 路径在 /api/system，独立实例
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
const USE_MOCK = import.meta.env.VITE_USE_MOCK_LOCKS === 'true';

// ---- Mock 数据 ----
const now = new Date();
const MOCK_IMPORT_LOCKS: ProductImportLockDTO[] = [
  {
    id: 'lock-001',
    customerId: 'cust-001',
    partNo: 'PN-20240001',
    granularity: 'PART',
    lockedBy: 'user-admin',
    importRecordId: 'rec-001',
    lockedAt: new Date(now.getTime() - 600000).toISOString(),
    lastHeartbeatAt: new Date(now.getTime() - 15000).toISOString(),
    expiresAt: new Date(now.getTime() + 1200000).toISOString(),
    status: 'ACTIVE',
    releasedAt: null,
    releaseReason: null,
  },
  {
    id: 'lock-002',
    customerId: 'cust-002',
    partNo: null,
    granularity: 'CUSTOMER',
    lockedBy: 'user-manager',
    importRecordId: 'rec-002',
    lockedAt: new Date(now.getTime() - 120000).toISOString(),
    lastHeartbeatAt: new Date(now.getTime() - 5000).toISOString(),
    expiresAt: new Date(now.getTime() + 1800000).toISOString(),
    status: 'ACTIVE',
    releasedAt: null,
    releaseReason: null,
  },
];

const MOCK_DDL_LOCK: DdlLockStatusDTO = {
  locked: true,
  lockedBy: 'user-admin',
  lockedAt: new Date(now.getTime() - 300000).toISOString(),
  expiresAt: new Date(now.getTime() + 600000).toISOString(),
  operationDesc: '执行 V55 DDL 迁移：添加字段重要性列',
};

// ---- Service ----
export const lockMonitorService = {
  listProductImportLocks: (): Promise<{ data: ProductImportLockDTO[] }> => {
    if (USE_MOCK) return Promise.resolve({ data: [...MOCK_IMPORT_LOCKS] });
    return sysApi.get('/locks/product-imports') as Promise<{ data: ProductImportLockDTO[] }>;
  },

  releaseProductImportLock: (lockId: string): Promise<{ data: void }> => {
    if (USE_MOCK) {
      const idx = MOCK_IMPORT_LOCKS.findIndex((l) => l.id === lockId);
      if (idx >= 0) MOCK_IMPORT_LOCKS.splice(idx, 1);
      return Promise.resolve({ data: undefined });
    }
    return sysApi.post(`/locks/product-imports/${lockId}/release`, {}) as Promise<{ data: void }>;
  },

  getDdlLockStatus: (): Promise<{ data: DdlLockStatusDTO }> => {
    if (USE_MOCK) return Promise.resolve({ data: { ...MOCK_DDL_LOCK } });
    return sysApi.get('/locks/ddl') as Promise<{ data: DdlLockStatusDTO }>;
  },

  releaseDdlLock: (): Promise<{ data: void }> => {
    if (USE_MOCK) {
      MOCK_DDL_LOCK.locked = false;
      return Promise.resolve({ data: undefined });
    }
    return sysApi.post('/locks/ddl/release', {}) as Promise<{ data: void }>;
  },
};
