/**
 * task-261030 · S-FLOW 夹具（唯一的夹具文件）
 *
 * `/api/**` 全部由 `page.route` 用本文件应答：不连后端、不连任何库、不写任何业务数据。命名前缀 `T261030_COST_`。
 *
 * 字段依据：本任务 `backend-audit.md`（B-1，2026-10-30 14:02）——
 *   G-1 所有 BigDecimal 序列化为 **JSON 字符串**（去尾零），null 仍为 null；
 *   G-2 DTO 的 null 字段照常输出 `"key": null`；
 *   G-3 信封 `{"code":200,"message":"success","data":…}`，前端只看 HTTP 状态；
 *   §1.3 / §1.4 结构与卡片值形态；§2 页面加载接口清单；§2.4 夹具 C 最小样本；§2.5 TemplateDTO 样本。
 *   §6+.1 SaveDraftResponse 白名单；§6+.2 核价 Excel 视图样本；§6+.3 比对视图三接口样本。
 * 每处仍属推测、或审核明说「未验证」的位置标 `TODO(backend-audit)`。
 *
 * 🚨 审核 §2.4 末尾明确：「这份结构 + 卡片值能否让前端计算链算出 1717.135540717」**未验证**。
 *    ⇒ spec 的 baseline 模式（master + 同一份夹具）先做夹具自证（CAL-1 / CAL-2）：master 的旧核价金额区必须显示
 *       1717.135540717、报价侧必须是 `888.123456789 USD` / `(VAT) 1003.579506172 USD`、无核价结构 / 生成失败必须复现原缺陷。
 *       自证不过 = 夹具没走到被测路径，候选侧任何红绿都不作数，按 test.md §3 停下报主线。
 */
import type { Page, Route } from '@playwright/test';

export const PREFIX = 'T261030_COST_';
export const PART_NO = `${PREFIX}P1`;
export const QUOTE_SUBTOTAL = '888.123456789';
export const COSTING_SUBTOTAL = '1717.135540717';
export const COSTING_FAILED_ERROR = `核价渲染失败: ${PREFIX}mock`;

/** 夹具 id：只存在于拦截层，库里没有、也不会写进库（UUID v4 形态）。 */
export const IDS = {
  quotationUsd: '26103000-0000-4000-8000-0000000000a1',
  quotationCny: '26103000-0000-4000-8000-0000000000a2',
  quotationBlankCurrency: '26103000-0000-4000-8000-0000000000a3',
  quotationNoCosting: '26103000-0000-4000-8000-0000000000a4',
  quotationCostingFailed: '26103000-0000-4000-8000-0000000000a5',
  customer: '26103000-0000-4000-8000-0000000000c1',
  customerCny: '26103000-0000-4000-8000-0000000000c2',
  quoteTemplate: '26103000-0000-4000-8000-0000000000b1',
  costingTemplate: '26103000-0000-4000-8000-0000000000b2',
  costingComponent: '26103000-0000-4000-8000-0000000000d1',
  quoteComponent: '26103000-0000-4000-8000-0000000000d2',
  lineItem: '26103000-0000-4000-8000-0000000000e1',
  costingOrder: '26103000-0000-4000-8000-0000000000f1',
  user: '26103000-0000-4000-8000-000000000011',
} as const;

export type QuotationVariant = 'USD' | 'CNY' | 'BLANK_CURRENCY' | 'NO_COSTING_STRUCTURE' | 'COSTING_FAILED';

export const QUOTATION_ID_BY_VARIANT: Record<QuotationVariant, string> = {
  USD: IDS.quotationUsd,
  CNY: IDS.quotationCny,
  BLANK_CURRENCY: IDS.quotationBlankCurrency,
  NO_COSTING_STRUCTURE: IDS.quotationNoCosting,
  COSTING_FAILED: IDS.quotationCostingFailed,
};

// ───────────────────────────── 结构 / 卡片值（backend-audit §1.3 / §1.4 / §2.5） ─────────────────────────────

interface SideSpec {
  side: 'QUOTE' | 'COSTING';
  templateId: string;
  templateKind: 'QUOTATION' | 'COSTING';
  componentId: string;
  componentCode: string;
  tabName: string;
  fieldName: string;
  view: string;
  amount: string;
}

const QUOTE_SIDE: SideSpec = {
  side: 'QUOTE',
  templateId: IDS.quoteTemplate,
  templateKind: 'QUOTATION', // backend-audit §2.4：「同 §1.3 形状，templateKind=QUOTATION」
  componentId: IDS.quoteComponent,
  componentCode: `${PREFIX}Q1`,
  tabName: `${PREFIX}报价明细`,
  fieldName: '报价金额',
  view: `$${PREFIX}quote_view`,
  amount: QUOTE_SUBTOTAL,
};

const COSTING_SIDE: SideSpec = {
  side: 'COSTING',
  templateId: IDS.costingTemplate,
  templateKind: 'COSTING',
  componentId: IDS.costingComponent,
  componentCode: `${PREFIX}C1`,
  tabName: `${PREFIX}核价明细`,
  fieldName: '核价金额',
  view: `$${PREFIX}view`,
  amount: COSTING_SUBTOTAL,
};

/** 冻结结构（camelCase，§1.3）。 */
function cardStructure(spec: SideSpec): Record<string, unknown> {
  return {
    version: 2,
    templateId: spec.templateId,
    templateKind: spec.templateKind,
    productAttributes: [],
    tabs: [{
      componentId: spec.componentId,
      componentCode: spec.componentCode,
      tabName: spec.tabName,
      sortOrder: 0,
      componentType: 'NORMAL',
      dataDriverPath: spec.view,
      rowKeyFields: [],
      fields: [{
        name: spec.fieldName,
        fieldType: 'BASIC_DATA',
        label: spec.fieldName,
        sortOrder: 0,
        isAmount: true,
        isRequired: false,
        isSubtotal: true,
        width: 0,
        editable: false,
        basicDataPath: `${spec.view}.amount`,
      }],
      formulas: [],
    }],
  };
}

/**
 * 卡片值快照（JSON 字符串，§1.4）。
 * fixture-recipe.md 配方 A（A-1）：`basicDataValues` 的键 = "{" + basicDataPath + "}"（不是字段名）。
 * 能否让 master / 候选算出 spec.amount 由 CAL-1 自证。
 */
function cardValues(spec: SideSpec): string {
  return JSON.stringify({
    tabs: [{
      componentId: spec.componentId,
      tabName: spec.tabName,
      componentType: 'NORMAL',
      baseRows: [{ driverRow: { amount: spec.amount }, basicDataValues: { [`{${spec.view}.amount}`]: spec.amount } }],
      editRows: [],
      formulaResults: [],
      subtotal: spec.amount,
    }],
  });
}

/** 模板快照（JSON 字符串、snake_case，§2.5）；componentId 必须与结构 tabs 一致（§2.4 末尾）。 */
function templateDto(spec: SideSpec): Record<string, unknown> {
  return {
    id: spec.templateId,
    name: `${PREFIX}${spec.side === 'QUOTE' ? '报价模板' : '核价模板'}`,
    templateKind: spec.templateKind,
    status: 'PUBLISHED',
    productAttributes: [],
    subtotalFormula: [],
    components: [],
    warnings: [],
    componentsSnapshot: JSON.stringify([{
      componentId: spec.componentId,
      componentCode: spec.componentCode,
      tabName: spec.tabName,
      sortOrder: 0,
      componentType: 'NORMAL',
      data_driver_path: spec.view,
      fields: [{
        name: spec.fieldName,
        field_type: 'BASIC_DATA',
        sort_order: 0,
        is_amount: true,
        is_subtotal: true,
        basic_data_path: `${spec.view}.amount`,
      }],
      formulas: [],
    }]),
  };
}

// ───────────────────────────── QuotationDTO（§2.4 夹具 C + 变体） ─────────────────────────────

export function buildQuotation(variant: QuotationVariant): Record<string, unknown> {
  const id = QUOTATION_ID_BY_VARIANT[variant];
  // §2.4 变体：AC-3 → CNY/"1"；「币种为空」线上不可达，只作前端兜底用例（currency:""）。
  const currency = variant === 'CNY' ? 'CNY' : variant === 'BLANK_CURRENCY' ? '' : 'USD';
  const exchangeRate = currency === 'USD' ? '7.1234' : '1';
  const noCosting = variant === 'NO_COSTING_STRUCTURE'; // §2.4 AC-4①：形态 A「未绑核价模板」
  const costingCardValues = noCosting
    ? null
    : variant === 'COSTING_FAILED'
      // §2.4 AC-4②：保持夹具 C，只把目标行 costingCardValues 换成带原文哨兵
      ? JSON.stringify({ tabs: [], __cardValueFailed: true, __errorMsg: COSTING_FAILED_ERROR })
      : cardValues(COSTING_SIDE);
  return {
    id,
    quotationNumber: `${PREFIX}QT-${variant}`,
    // 客户默认币种与报价币种一致（建单取客户 defaultCurrency，§1.1）。
    customerId: currency === 'USD' ? IDS.customer : IDS.customerCny,
    currency,
    exchangeRate,
    exchangeRateDate: '2026-10-30',
    exchangeRateSource: null,
    exchangeRateCapturedAt: '2026-10-30T10:00:00+08:00',
    name: `${PREFIX}夹具${variant}`,
    projectName: `${PREFIX}项目`,
    status: 'DRAFT',
    totalAmount: QUOTE_SUBTOTAL,
    originalAmount: QUOTE_SUBTOTAL,
    finalDiscountRate: null,
    defaultVatRate: '13',
    defaultVatSource: 'CUSTOMER',
    customerTemplateId: IDS.quoteTemplate,
    categoryId: null,
    costingCardTemplateId: noCosting ? null : IDS.costingTemplate,
    quoteCardStructure: cardStructure(QUOTE_SIDE),
    quoteExcelStructure: null,
    costingCardStructure: noCosting ? null : cardStructure(COSTING_SIDE),
    costingExcelStructure: null,
    // §6+.2：核价 Excel 列（snake_case，原样透传）；报价侧本任务不验，保持空。
    quoteExcelColumns: [],
    costingExcelColumns: noCosting ? null : COSTING_EXCEL_COLUMNS,
    userDataVersion: 0, // §1.1：编辑页保存前要它做并发基线，缺失则拒绝保存
    cardValuesWarming: false,
    approvalHistory: [],
    lineItems: [{
      id: IDS.lineItem,
      productId: null,
      templateId: IDS.quoteTemplate,
      vatRate: '13',
      vatRateRatio: '0.13', // G-1：必须是字符串，数字 0.13 会被编辑页当成缺失
      vatSource: 'CUSTOMER',
      productPartNo: PART_NO,
      productName: `${PREFIX}产品1`,
      productAttributeValues: '{}',
      subtotal: QUOTE_SUBTOTAL, // §3：报价侧产品小计（刻意与核价不同，用于检出串值）
      sortOrder: 0,
      partVersionLocked: 1,
      compositeType: 'SIMPLE',
      parentLineItemId: null,
      processes: [],
      compositeProcesses: [],
      componentData: [],
      quoteCardValues: cardValues(QUOTE_SIDE),
      costingCardValues,
      quoteExcelValues: '{"rows":[]}',
      costingExcelValues: noCosting ? null : COSTING_EXCEL_VALUES,
    }],
  };
}

/** §6+.2 核价 Excel 视图最小样本（非树，不造 `__nodeId`）。 */
const COSTING_EXCEL_COLUMNS = [
  { col_key: `${PREFIX}part`, title: '料号', source_type: 'PRODUCT_ATTRIBUTE', field_key: '料号' },
  {
    col_key: `${PREFIX}amount`, title: '核价金额', source_type: 'CARD_FORMULA', field_key: '核价金额',
    comparison_tag: null, hidden: false, display_format: { type: 'NUMBER' },
  },
];
const COSTING_EXCEL_VALUES = JSON.stringify({ rows: [{ [`${PREFIX}part`]: PART_NO, [`${PREFIX}amount`]: COSTING_SUBTOTAL }] });

/** §6+.3 比对视图三接口最小样本（config.columns=null → 前端补默认「产品合计」列）。 */
function buildComparisonMeta(): Record<string, unknown> {
  return {
    quoteTabs: [{
      componentId: QUOTE_SIDE.componentId, tabName: QUOTE_SIDE.tabName, sortOrder: 0,
      metrics: [
        { key: QUOTE_SIDE.fieldName, label: QUOTE_SIDE.fieldName, type: 'SUBTOTAL_FIELD' },
        { key: '__TAB_TOTAL__', label: '页签合计', type: 'TAB_TOTAL' },
      ],
    }],
    costingTabs: [{
      componentId: COSTING_SIDE.componentId, tabName: COSTING_SIDE.tabName, sortOrder: 0,
      metrics: [
        { key: COSTING_SIDE.fieldName, label: COSTING_SIDE.fieldName, type: 'SUBTOTAL_FIELD' },
        { key: '__TAB_TOTAL__', label: '页签合计', type: 'TAB_TOTAL' },
      ],
    }],
  };
}

function buildComparisonData(): Record<string, unknown> {
  return {
    rows: [{
      partNo: PART_NO,
      productName: `${PREFIX}产品1`,
      presence: 'BOTH',
      quote: {
        productTotal: QUOTE_SUBTOTAL,
        tabs: { [QUOTE_SIDE.componentId]: { tabTotal: QUOTE_SUBTOTAL, subtotals: { [QUOTE_SIDE.fieldName]: QUOTE_SUBTOTAL } } },
      },
      costing: {
        productTotal: COSTING_SUBTOTAL,
        tabs: { [COSTING_SIDE.componentId]: { tabTotal: COSTING_SUBTOTAL, subtotals: { [COSTING_SIDE.fieldName]: COSTING_SUBTOTAL } } },
      },
    }],
  };
}

function buildComparisonConfig(quotationId: string, bucket: string): Record<string, unknown> {
  return { quotationId, bucket, columns: null, updatedAt: null };
}

/** §2.3 CostingOrderDetailDTO：frozenDto 是字符串（QuotationDTO + 顶层 gvDefs）。 */
export function buildCostingOrder(): Record<string, unknown> {
  const frozen = { ...buildQuotation('USD'), gvDefs: [] };
  return {
    costingOrderId: IDS.costingOrder,
    quotationId: IDS.quotationUsd,
    costingOrderNumber: `${PREFIX}HJ-0001`,
    status: 'PENDING',
    rejectReason: null,
    totalAmount: QUOTE_SUBTOTAL,
    frozenDto: JSON.stringify(frozen),
    createdAt: '2026-10-30T10:00:00+08:00',
    reviewedAt: null,
    costingRender: {
      [IDS.lineItem]: { costingCardValues: cardValues(COSTING_SIDE), costingExcelValues: COSTING_EXCEL_VALUES },
    },
    costingTotalAmount: COSTING_SUBTOTAL, // 「核价单据总价」读它（§2.3）
    versionOverrides: [],
    editable: true,
  };
}

/** §2.0 P-1：角色只由 role 决定显示位数；SYSTEM_ADMIN → 9 位，且能进编辑页与核价管理页。 */
export function buildCurrentUser(): Record<string, unknown> {
  return {
    id: IDS.user,
    username: `${PREFIX}admin`,
    fullName: `${PREFIX}系统管理员`,
    role: 'SYSTEM_ADMIN',
    forceChangePassword: false,
  };
}

/** §2.2 E-3 CustomerDTO（十进制为字符串）。 */
function buildCustomer(currency: 'USD' | 'CNY'): Record<string, unknown> {
  return {
    id: currency === 'USD' ? IDS.customer : IDS.customerCny,
    name: `${PREFIX}客户`,
    code: `${PREFIX}CUST`,
    defaultCurrency: currency,
    defaultVatRate: '13',
    status: 'ACTIVE',
  };
}

/**
 * §6+.1 SaveDraftResponse —— 按完整字段清单**白名单**构造（SaveDraftResponse.java:56-101）。
 * 只改单头（AC-10 / AC-7④ 只改「关联项目」）⇒ `lineItems: []`；`userDataVersion` = 请求 baseVersion + 1（无则旧值 + 1）。
 * 与 QuotationDTO 的差集 7 个字段（currency / exchangeRate / exchangeRateDate / exchangeRateSource /
 * exchangeRateCapturedAt / defaultVatRate / defaultVatSource）照真实形态**不出现**。
 */
const SAVE_DRAFT_HEADER_KEYS = [
  'id', 'quotationNumber', 'customerId', 'name', 'contactId', 'contactName', 'contactPhone', 'contactEmail',
  'projectName', 'opportunityId', 'salesRepId', 'quoteType', 'priority', 'stage', 'expectedCloseDate', 'status',
  'totalAmount', 'expiryDate', 'paymentTerms', 'deliveryCycle', 'originalAmount', 'systemDiscountRate',
  'finalDiscountRate', 'taxRate', 'taxAmount', 'discountAdjustmentReason', 'isManuallyAdjusted', 'sourceQuotationId',
  'assignedApproverId', 'customerTemplateId', 'categoryId', 'costingCardTemplateId', 'remarks', 'snapshotCustomerName',
  'snapshotCustomerLevel', 'snapshotCustomerRegion', 'snapshotCustomerIndustry', 'snapshotCustomerAddress',
  'createdAt', 'updatedAt',
] as const;

function buildSaveDraftResponse(quotation: Record<string, unknown>, request: Record<string, unknown> | null): Record<string, unknown> {
  const source: Record<string, unknown> = {
    ...quotation,
    snapshotCustomerName: `${PREFIX}客户`,
    expiryDate: '2026-11-29',
    updatedAt: '2026-10-30T10:05:00+08:00',
  };
  const header: Record<string, unknown> = {};
  for (const key of SAVE_DRAFT_HEADER_KEYS) header[key] = Object.hasOwn(source, key) ? source[key] : null; // G-2：null 照常输出
  if (request && typeof request.projectName === 'string') header.projectName = request.projectName;
  const baseVersion = typeof request?.baseVersion === 'number' ? request.baseVersion : Number(quotation.userDataVersion ?? 0);
  return { ...header, userDataVersion: baseVersion + 1, lineItems: [] };
}

// ───────────────────────────── 拦截路由（§2 页面加载接口清单） ─────────────────────────────

function envelope(data: unknown): string {
  return JSON.stringify({ code: 200, message: 'success', data: data ?? null });
}

export interface MockLedger {
  hits: Record<string, number>;
  unhandled: string[];
  draftBodies: Array<{ json: unknown; text: string }>;
  /** 已发给页面的保存响应原文（信封 JSON 文本）。 */
  draftResponses: string[];
}

interface RouteDef {
  key: string;
  method: 'GET' | 'POST' | 'PUT';
  pattern: RegExp;
  respond: (match: RegExpMatchArray, route: Route, ledger: MockLedger) => { status?: number; body: string };
}

const UUID = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
const NOT_FOUND = { status: 404, body: '{"code":404,"message":"T261030_COST_ fixture not found"}' };

function quotationById(id: string): Record<string, unknown> | undefined {
  const variant = (Object.keys(QUOTATION_ID_BY_VARIANT) as QuotationVariant[])
    .find((key) => QUOTATION_ID_BY_VARIANT[key] === id);
  return variant ? buildQuotation(variant) : undefined;
}

function templateById(id: string): Record<string, unknown> | undefined {
  if (id === IDS.quoteTemplate) return templateDto(QUOTE_SIDE);
  if (id === IDS.costingTemplate) return templateDto(COSTING_SIDE);
  return undefined;
}

/** 顺序要紧：更具体的路径放前面。未登记的请求一律 501 + 记入 ledger.unhandled，spec 的 afterEach 断言为空。 */
const ROUTES: RouteDef[] = [
  // §2.0 公共
  { key: 'P-1 auth.me', method: 'GET', pattern: /^\/api\/cpq\/auth\/me$/, respond: () => ({ body: envelope(buildCurrentUser()) }) },
  { key: 'P-2 notifications.unread', method: 'GET', pattern: /^\/api\/cpq\/notifications\/unread-count$/, respond: () => ({ body: envelope({ count: 0 }) }) },
  { key: 'P-3 notifications', method: 'GET', pattern: /^\/api\/cpq\/notifications$/, respond: () => ({ body: envelope([]) }) },
  // §2.1 / §2.2 模板与全局变量
  { key: 'D-2 template.gvBindings', method: 'GET', pattern: new RegExp(`^/api/cpq/templates/(${UUID})/global-variable-bindings$`), respond: () => ({ body: envelope([]) }) },
  // fixture-recipe：编辑页核价 Excel 视图请求核价模板的 excel-view-config，回 null（不接会 501 并进错误分支）。
  { key: 'E-15 template.excelViewConfig', method: 'GET', pattern: new RegExp(`^/api/cpq/templates/(${UUID})/excel-view-config$`), respond: () => ({ body: envelope(null) }) },
  { key: 'E-9 template.effectiveColumns', method: 'GET', pattern: new RegExp(`^/api/cpq/templates/(${UUID})/excel-view-config/effective-columns$`), respond: () => ({ body: envelope([]) }) },
  { key: 'E-7 template.matchCustomerQuote', method: 'GET', pattern: /^\/api\/cpq\/templates\/match-customer-quote$/, respond: () => ({ body: envelope({ matchType: 'NONE', templates: [] }) }) }, // TODO(backend-audit): categoryId=null 时是否会调
  {
    key: 'D-4/E-10/E-11 template.get', method: 'GET', pattern: new RegExp(`^/api/cpq/templates/(${UUID})$`),
    respond: (match) => { const t = templateById(match[1]); return t ? { body: envelope(t) } : NOT_FOUND; },
  },
  { key: 'D-3/E-8 globalVariables', method: 'GET', pattern: /^\/api\/cpq\/global-variables$/, respond: () => ({ body: envelope([]) }) },
  // 计算类（仅卡片值缺失 / 有 path token 时）
  { key: 'D-5/E-12 components.batchExpand', method: 'POST', pattern: /^\/api\/cpq\/components\/batch-expand$/, respond: () => ({ body: envelope({ results: [] }) }) },
  { key: 'D-6/E-12 formulas.batchEvaluate', method: 'POST', pattern: /^\/api\/cpq\/formulas\/batch-evaluate$/, respond: () => ({ body: envelope({ results: [] }) }) },
  // §2.2 客户与分类
  {
    key: 'E-4 customer.contacts', method: 'GET', pattern: new RegExp(`^/api/cpq/customers/(${UUID})/contacts$`),
    respond: () => ({ body: envelope([]) }),
  },
  {
    key: 'E-3 customer.get', method: 'GET', pattern: new RegExp(`^/api/cpq/customers/(${UUID})$`),
    respond: (match) => {
      if (match[1] === IDS.customer) return { body: envelope(buildCustomer('USD')) };
      if (match[1] === IDS.customerCny) return { body: envelope(buildCustomer('CNY')) };
      return NOT_FOUND;
    },
  },
  {
    key: 'E-5 customers.page', method: 'GET', pattern: /^\/api\/cpq\/customers$/,
    respond: () => ({
      body: envelope({ content: [buildCustomer('USD'), buildCustomer('CNY')], page: 0, size: 20, totalElements: 2, totalPages: 1 }),
    }),
  },
  { key: 'E-6 productCategories', method: 'GET', pattern: /^\/api\/cpq\/product-categories$/, respond: () => ({ body: envelope([]) }) },
  // §2.2 报价单
  {
    key: 'E-13 quotation.draft.put', method: 'PUT', pattern: new RegExp(`^/api/cpq/quotations/(${UUID})/draft$`),
    respond: (match, route, ledger) => {
      const request = route.request();
      const json = request.postDataJSON() as Record<string, unknown> | null;
      ledger.draftBodies.push({ json, text: request.postData() ?? '' });
      const quotation = quotationById(match[1]);
      if (!quotation) return NOT_FOUND;
      const body = envelope(buildSaveDraftResponse(quotation, json));
      ledger.draftResponses.push(body); // AC-10：用例据此断言响应体确无 currency / exchangeRate*
      return { body };
    },
  },
  {
    key: 'E-2 quotation.ensureCardValues', method: 'POST', pattern: new RegExp(`^/api/cpq/quotations/(${UUID})/ensure-card-values$`),
    respond: (match) => { const q = quotationById(match[1]); return q ? { body: envelope(q) } : NOT_FOUND; },
  },
  {
    key: 'E-14 quotation.ensureExcelValues', method: 'POST', pattern: new RegExp(`^/api/cpq/quotations/(${UUID})/ensure-excel-values$`),
    respond: (match) => { const q = quotationById(match[1]); return q ? { body: envelope(q) } : NOT_FOUND; },
  },
  // D-8 比对视图（§6+.3 样本）
  {
    key: 'D-8 comparison.meta', method: 'GET', pattern: new RegExp(`^/api/cpq/quotations/(${UUID})/comparison-view/meta$`),
    respond: (match) => (quotationById(match[1]) ? { body: envelope(buildComparisonMeta()) } : NOT_FOUND),
  },
  {
    key: 'D-8 comparison.data', method: 'GET', pattern: new RegExp(`^/api/cpq/quotations/(${UUID})/comparison-view/data$`),
    respond: (match) => (quotationById(match[1]) ? { body: envelope(buildComparisonData()) } : NOT_FOUND),
  },
  {
    key: 'D-8 comparison.config', method: 'GET', pattern: new RegExp(`^/api/cpq/quotations/(${UUID})/comparison-view/config$`),
    respond: (match, route) => (quotationById(match[1])
      ? { body: envelope(buildComparisonConfig(match[1], new URL(route.request().url()).searchParams.get('bucket') ?? 'SALES')) }
      : NOT_FOUND),
  },
  {
    key: 'D-1/E-1 quotation.get', method: 'GET', pattern: new RegExp(`^/api/cpq/quotations/(${UUID})$`),
    respond: (match) => { const q = quotationById(match[1]); return q ? { body: envelope(q) } : NOT_FOUND; },
  },
  // §2.3 核价管理页
  {
    key: 'C-1 costingOrder.get', method: 'GET', pattern: new RegExp(`^/api/cpq/costing-orders/(${UUID})$`),
    respond: (match) => (match[1] === IDS.costingOrder ? { body: envelope(buildCostingOrder()) } : NOT_FOUND),
  },
];

/**
 * 安装拦截。必须在 `page.goto` 之前调用。
 * 阳性对照：每条路由在 handler 里计数（ledger.hits），spec 断言关键接口命中 > 0 ——
 * 拦截没生效时请求会打到 Vite 代理的死端口，页面渲染出来的东西与夹具无关。
 */
export async function installApiMocks(page: Page): Promise<MockLedger> {
  const ledger: MockLedger = { hits: {}, unhandled: [], draftBodies: [], draftResponses: [] };
  await page.route('**/api/**', async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    for (const def of ROUTES) {
      const match = url.pathname.match(def.pattern);
      if (match && request.method() === def.method) {
        ledger.hits[def.key] = (ledger.hits[def.key] ?? 0) + 1;
        const { status = 200, body } = def.respond(match, route, ledger);
        await route.fulfill({ status, contentType: 'application/json; charset=utf-8', body });
        return;
      }
    }
    ledger.unhandled.push(`${request.method()} ${url.pathname}${url.search}`);
    await route.fulfill({
      status: 501,
      contentType: 'application/json; charset=utf-8',
      body: JSON.stringify({ code: 501, message: `${PREFIX}unmocked ${request.method()} ${url.pathname}` }),
    });
  });
  return ledger;
}
