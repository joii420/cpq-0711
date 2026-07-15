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
  /** 客户产品编号。占位行（选配发号副作用，customer_product_no=NULL）已在服务层过滤（F005），前端恒非空。 */
  customerProductNo?: string | null;
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
