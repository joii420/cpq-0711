// 报价单 · 从已有产品添加 — 类型（对齐后端 com.cpq.existingproduct.dto.ExistingProductDTO）
//
// 数据源（task-260909 收敛后）：**单表 `ds_quote_customer_part`**，LEFT JOIN `v_compat_material_master`
// 取品名/规格；按本报价单客户过滤（后端从 quotation 派生 customer_no，前端不传客户）。
// 🚫 不再是老的客户料号映射表（mcm）—— 那是 task-0712 时代的读法。历史上曾并过三支
//    （mcm ∪ sel_product_no ∪ ds_quote_customer_part），task-260909 收敛回单表：
//    旧写入方 mcm（其写入端点 Q02 恒 410）/ sel_product_no 均已停写，
//    导入与选配今天都只落 ds_quote_customer_part。

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
  /** 销售料号（= ds_quote_customer_part.material_no）。 */
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
  /**
   * 🆕 task-260909 · AC-4：客户图号（= `ds_quote_customer_part.customer_drawing_no`）。
   * 可空（实测正泰 2663 行里 11 行为空）⇒ 渲染方必须落 `—`，🚫 不许空白 / undefined / null。
   */
  customerDrawingNo?: string | null;
  /**
   * 品名 —— **主数据侧名称**（= `v_compat_material_master.material_name`）。
   *
   * ⚠️ task-260909 · AC-5 起与 `customerMaterialName` **不再同源**（改动前后端两字段同取一列，
   *    两列必然渲染成同一个值，这正是本次要修掉的）。语义见 api.md §1.3。
   * 兜底：后端为空时回退 `material_no`（保证品名列不空白），
   * 🚫 **不回退到 `customerMaterialName`** —— 那会让两列又变回相同，等于 AC-5 白修。
   */
  productName?: string | null;
  /** 规格：COALESCE(NULLIF(v_compat_material_master.specification,''), dimension)（架构决策 3-A）。 */
  spec?: string | null;
  /**
   * 客户物料名 —— **客户侧名称**（= `ds_quote_customer_part.customer_part_name`）。
   * ⚠️ task-260909 · AC-5 起与 `productName` 取两个不同的列，两列显示两个不同的值
   *    （如 `罗克韦尔触桥组件A` vs `触桥组件A`）。为空时后端返回 null，前端渲染 `—`。
   */
  customerMaterialName?: string | null;
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
