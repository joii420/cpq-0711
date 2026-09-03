// 报价单 · 从已有产品添加 — 类型（task-0712 B3，api.md §2；对齐后端 com.cpq.existingproduct.dto.ExistingProductDTO）
// 数据源 material_customer_map，按本报价单客户过滤（后端从 quotation 派生 customer_no，前端不传客户）。

/**
 * 分页包络（真实后端类 com.cpq.common.dto.PageResult）：content/totalElements/page/size/totalPages，
 * 不是 items/total。与 modelConfig.ts 的同名类型结构一致但独立声明（见该文件注释，两个 domain 各自 self-contained）。
 */
export interface PageResult<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** `GET /quotations/{quotationId}/existing-products` 列表行。 */
export interface ExistingProductDTO {
  /** 销售料号（= material_customer_map.material_no）。 */
  materialNo: string;
  /**
   * 客户产品编号 —— **代表编号**（后端 `DISTINCT ON (material_no)` 取 `created_at` 最早的那个）。
   * ⚠️ task-260902 起语义收紧为「代表」：一个销售料号可能对应**多个**客户产品编号
   *    （方案甲下 `sel_product_no.quote_part_no` 刻意不唯一）。
   * 📌 既有读取方（如「加入报价单」时映射 `LineItem.customerProductNo`）继续读它，语义不变。
   */
  customerProductNo?: string | null;
  /**
   * 🆕 task-260902 · AC-12b⑤-b：该销售料号名下**全部**客户产品编号，按 `created_at` 升序。
   *
   * 为什么需要它：销售甲用 `T260902-A` 配出料号 X，销售乙用 `T260902-B` 配了相同配置复用了 X。
   * 列表按代表编号去重后只显示 `T260902-A` ⇒ **乙认不出这是自己的产品**。
   * 本任务修的是「选配产品在产品库里找不回」，这条是它的另一面：从**找不到**变成**认不出**。
   *
   * 🚨 **可能不存在或为空**（后端未上线 / mcm 来源的老数据）⇒ 渲染方必须回退到
   *    `customerProductNo`，🚫 绝不能渲染成 `undefined` 或空白单元格（AP-31 族：
   *    宁可显示旧值，也不要空占位）。
   */
  customerProductNos?: string[] | null;
  /** 品名（= customer_material_name）。 */
  productName?: string | null;
  /** 规格：COALESCE(NULLIF(material_master.specification,''), dimension)（架构决策 3-A）。 */
  spec?: string | null;
  /** 客户物料名称（与 productName 同源，供前端分别映射到 CustomerPartCandidate 两个槽位）。 */
  customerMaterialName?: string | null;
  /** 该料号是否配了当前版本 3D 模型（model_config is_current 命中）。 */
  has3d: boolean;
  /** 3D 缩略图 URL；无则 null。 */
  thumbnailUrl?: string | null;
  /** 来源（A 方案）：EXISTING=真·已有产品（有客户产品号）；CONFIGURED=选配发号（客户产品号待导入分配）。 */
  source?: 'EXISTING' | 'CONFIGURED' | string | null;
  /** 选配产品类型：SIMPLE | COMPOSITE（仅 source=CONFIGURED 有值）。 */
  configProductType?: string | null;
}

/** `GET /quotations/{quotationId}/existing-products` 查询参数（全部可选，服务端 AND 组合、模糊匹配）。 */
export interface ExistingProductQueryParams {
  customerProductNo?: string;
  salesPartNo?: string;
  productName?: string;
  spec?: string;
  page?: number;
  size?: number;
}
