// Shared types for Template Configuration module

export type ProductAttrSource = 'PRODUCT_SPEC' | 'PRODUCT_CATEGORY' | 'PRODUCT_TAGS' | 'PRODUCT_DRAWING_NO' | 'PRODUCT_DIMENSION' | 'PRODUCT_MATERIAL' | 'CUSTOM';

export interface ProductAttribute {
  key: string;
  name: string;
  field_type: 'TEXT' | 'NUMBER' | 'TEXTAREA' | 'FIXED_VALUE';
  required: boolean;
  default_value?: string;
  sort_order: number;
  source?: ProductAttrSource;  // where the value comes from
}

export interface TemplateComponentItem {
  id: string;
  componentId: string;
  tabName: string;
  sortOrder: number;
  fields?: any[];
  /** V204: 模板级覆盖 — 非空时 publish 用此值覆盖 component.dataDriverPath */
  dataDriverPathOverride?: string | null;
  /** V204: 模板级覆盖 — 非空 JSON 字符串时 publish 用此值覆盖 component.fields */
  fieldsOverride?: string | null;
}

export interface TemplateData {
  id: string;
  name: string;
  code?: string;
  /** @deprecated 使用 categoryId,与「产品分类管理」模块联通 */
  category?: string;
  /** 与产品分类管理(product_category)关联,主用字段 */
  categoryId?: string;
  /** 后端 DTO 返回的分类名(便于显示) */
  categoryName?: string;
  description?: string;
  usageNote?: string;
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
  /** 模板类型：QUOTATION(报价模板) / COSTING(核价模板) / EXCEL(Excel视图) */
  templateKind?: 'QUOTATION' | 'COSTING' | 'EXCEL';
  version?: string;
  templateSeriesId?: string;
  productAttributes?: ProductAttribute[];
  subtotalFormula?: string | any[];
  components?: TemplateComponentItem[];
}

export interface VersionHistoryItem {
  id: string;
  version: string;
  status: string;
  publishedAt: string;
}

export const CATEGORY_OPTIONS = [
  { value: 'STANDARD_PARTS', label: '标准件' },
  { value: 'CUSTOM_PARTS', label: '定制件' },
  { value: 'RAW_MATERIALS', label: '原材料' },
];

export const STATUS_COLORS: Record<string, string> = {
  DRAFT: 'blue',
  PUBLISHED: 'green',
  ARCHIVED: 'default',
};

export const STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  ARCHIVED: '已归档',
};

export const FIELD_TYPE_LABELS: Record<string, string> = {
  FIXED_VALUE: '固定值',
  DATA_SOURCE: '数据源',
  INPUT: '手工输入',
  INPUT_TEXT: '文本输入',
  INPUT_NUMBER: '数字输入',
  FORMULA: '公式',
};
