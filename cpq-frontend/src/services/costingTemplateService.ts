// task-0723: 旧「核价模板」CRUD 服务(costingTemplateService 对象 + /costing-templates/** 端点)已随
// CostingTemplateList/CostingTemplateConfig 页面一并下线(17/17 零绑定)。
// 本文件只保留 CostingTemplateColumn 类型 —— 它是 Excel 视图列配置的共享类型，
// 被 LinkedExcelView / ReadonlyExcelView / QuotationWizard / quotationService / buildExcelSnapshot /
// comparisonModel / excelCellFormat / use*ExcelRows 等活跃渲染链路广泛引用，不可删。

export interface CostingTemplateColumn {
  col_key: string;
  title: string;
  source_type: 'VARIABLE' | 'FORMULA' | 'CARD_FORMULA' | 'TAB_JOIN_FORMULA' | 'PRODUCT_ATTRIBUTE' | 'COMPONENT_FIELD' | 'EXCEL_FORMULA' | 'FIXED_VALUE';
  variable_path?: string;
  formula?: string;
  /** CARD_FORMULA / COMPONENT_FIELD / PRODUCT_ATTRIBUTE 列的字段 key */
  field_key?: string;
  /** FIXED_VALUE 列的固定值 */
  fixed_value?: string;
  comparison_tag?: string;
  /** V86：隐藏列。仍参与 FORMULA 取值链路，但不在「核价单 Excel 视图」/「报价单 Excel 视图」展示。 */
  hidden?: boolean;
  /** 显示格式配置（百分比等） */
  display_format?: {
    type?: 'PERCENT' | 'NUMBER' | 'TEXT';
    decimals?: number;
  };
}

