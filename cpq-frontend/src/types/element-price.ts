// 元素参考价 DTO（与后端 ElementReferenceDTO 对应）
export interface ElementReferenceDTO {
  elementName: string;
  price: number;
  currency: string;
  unit: string;
  priceDate: string;
  enteredByName: string;
  note?: string;
}

// 历史价格列表条目
export interface ElementPriceHistoryItem {
  id: string;
  elementName: string;
  price: number;
  currency: string;
  unit: string;
  priceDate: string;
  enteredAt: string;
  enteredByName: string;
  note?: string;
}

// 历史分页结果
export interface ElementPriceHistoryPageDTO {
  data: ElementPriceHistoryItem[];
  total: number;
  page: number;
  size: number;
}

// 可选元素（来自 /available-elements）
export interface AvailableElementDTO {
  elementName: string;
  unit?: string;
  currency?: string;
}

// 手动录入请求体
export interface ManualPriceEntryRequest {
  elementName: string;
  price: number;
  currency: string;
  unit: string;
  note?: string;
}
