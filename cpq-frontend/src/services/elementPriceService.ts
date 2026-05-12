import api from './api';
import type {
  ElementReferenceDTO,
  ElementPriceHistoryPageDTO,
  AvailableElementDTO,
  ManualPriceEntryRequest,
} from '../types/element-price';

// Mock 开关
const USE_MOCK = import.meta.env.VITE_USE_MOCK_ELEMENT_PRICE === 'true';

// ---- Mock 数据 ----

const MOCK_REFERENCE: Record<string, ElementReferenceDTO> = {
  Ag: {
    elementName: 'Ag',
    price: 5500,
    currency: 'RMB',
    unit: '克',
    priceDate: '2026-04-26',
    enteredByName: '管理员',
    note: '上海金属交易所报价',
  },
  Cu: {
    elementName: 'Cu',
    price: 68000,
    currency: 'RMB',
    unit: '吨',
    priceDate: '2026-04-26',
    enteredByName: '管理员',
    note: 'LME 铜价',
  },
  Au: {
    elementName: 'Au',
    price: 620,
    currency: 'RMB',
    unit: '克',
    priceDate: '2026-04-26',
    enteredByName: '管理员',
    note: '上海黄金交易所报价',
  },
};

const MOCK_HISTORY = [
  { id: 'mock-1', elementName: 'Ag', price: 5500, currency: 'RMB', unit: '克', priceDate: '2026-04-26', enteredAt: '2026-04-26T09:00:00Z', enteredByName: '管理员', note: '上海金属交易所报价' },
  { id: 'mock-2', elementName: 'Ag', price: 5480, currency: 'RMB', unit: '克', priceDate: '2026-04-25', enteredAt: '2026-04-25T09:00:00Z', enteredByName: '管理员', note: '' },
  { id: 'mock-3', elementName: 'Cu', price: 68000, currency: 'RMB', unit: '吨', priceDate: '2026-04-26', enteredAt: '2026-04-26T09:30:00Z', enteredByName: '管理员', note: 'LME 铜价' },
  { id: 'mock-4', elementName: 'Au', price: 620, currency: 'RMB', unit: '克', priceDate: '2026-04-26', enteredAt: '2026-04-26T10:00:00Z', enteredByName: '管理员', note: '上海黄金交易所报价' },
  { id: 'mock-5', elementName: 'Au', price: 615, currency: 'RMB', unit: '克', priceDate: '2026-04-25', enteredAt: '2026-04-25T10:00:00Z', enteredByName: '管理员', note: '' },
];

const MOCK_AVAILABLE_ELEMENTS: AvailableElementDTO[] = [
  { elementName: 'Ag', unit: '克', currency: 'RMB' },
  { elementName: 'Cu', unit: '吨', currency: 'RMB' },
  { elementName: 'Au', unit: '克', currency: 'RMB' },
];

// ---- Service ----

export const elementPriceService = {
  /** 获取某元素的最新参考价（用于报价填价提示） */
  getReference: async (elementName: string, priceDate?: string): Promise<ElementReferenceDTO | null> => {
    if (USE_MOCK) {
      await new Promise(r => setTimeout(r, 200));
      return MOCK_REFERENCE[elementName] ?? null;
    }
    return api.get<ElementReferenceDTO>('/element-prices/reference', {
      params: { elementName, priceDate },
    }) as unknown as Promise<ElementReferenceDTO | null>;
  },

  /** 查询历史价格列表（分页） */
  listHistory: async (params: {
    elementName?: string;
    from?: string;
    to?: string;
    page?: number;
    size?: number;
  }): Promise<ElementPriceHistoryPageDTO> => {
    if (USE_MOCK) {
      await new Promise(r => setTimeout(r, 300));
      let items = [...MOCK_HISTORY];
      if (params.elementName) {
        items = items.filter(i => i.elementName === params.elementName);
      }
      const page = params.page ?? 0;
      const size = params.size ?? 10;
      const start = page * size;
      const paged = items.slice(start, start + size);
      return { data: paged, total: items.length, page, size };
    }
    return api.get<ElementPriceHistoryPageDTO>('/element-prices/history', { params }) as unknown as Promise<ElementPriceHistoryPageDTO>;
  },

  /** 手动录入参考价 */
  upsertManual: async (data: ManualPriceEntryRequest): Promise<void> => {
    if (USE_MOCK) {
      await new Promise(r => setTimeout(r, 400));
      console.log('[Mock] elementPriceService.upsertManual', data);
      return;
    }
    await api.post('/element-prices/manual', data);
  },

  /** 获取可用元素列表（用于筛选器下拉） */
  listAvailableElements: async (): Promise<AvailableElementDTO[]> => {
    if (USE_MOCK) {
      await new Promise(r => setTimeout(r, 150));
      return MOCK_AVAILABLE_ELEMENTS;
    }
    return api.get<AvailableElementDTO[]>('/element-prices/available-elements') as unknown as Promise<AvailableElementDTO[]>;
  },
};
