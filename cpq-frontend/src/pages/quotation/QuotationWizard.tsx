import React, { useEffect, useState, useCallback, useRef } from 'react';
import {
  Steps, Button, Card, Form, Input, Select, DatePicker, InputNumber,
  Space, Table, message, Descriptions, Tag, Divider, Row, Col,
  Typography, Spin, Alert,
} from 'antd';
import {
  SaveOutlined, SendOutlined,
} from '@ant-design/icons';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import dayjs from 'dayjs';
import { quotationService } from '../../services/quotationService';
import { quotationDriftService } from '../../services/quotationDriftService';
import { quotationSnapshotService } from '../../services/quotationSnapshotService';
import { customerService } from '../../services/customerService';
import QuotationStep2, { computeProductSubtotal, computeAllFormulas, buildSnapshotExpansions, EMPTY_LINEITEMS } from './QuotationStep2';
import QuotationStep3 from './QuotationStep3';
import type { DriftDetectionResult } from '../../types/quotation-drift';
import type { LineItem, ComponentDataItem } from './QuotationStep2';
import { useDriverExpansions, driverExpansionKey, bnfDriverLookupKey, fieldsOverrideHash } from './useDriverExpansions';
import { safeSetLocalDraft } from './draftCache';
import { stableDraftDedupKey } from './draftPayloadDedup';
import AddProductModal from './AddProductModal';
import ConfigureProductDrawer from './ConfigureProductDrawer';
import QuotationCreateForm from './QuotationCreateForm';
import type { QuotationFormValue } from './QuotationCreateForm';
import { templateService } from '../../services/templateService';
import { buildLineItemFromTemplate } from './BulkImportPartsDrawer';
import { enrichComponentData, loadProductAttributes, buildComponentDataFromStructure, productAttributesFromStructure } from './enrichComponentData';
import { globalVariableService } from '../../services/globalVariableService';
import type { GlobalVariableDefinition } from '../../services/globalVariableService';
import { splitRows, rowAt } from './manualRows';
import { coerceInputNumber } from './inputDefaults';
import type { CostingTemplateColumn } from '../../services/costingTemplateService';
import { buildExcelSnapshot } from './buildExcelSnapshot';
// lazy-cardvalues：纯判定函数抽到小模块(便于单测,不拉本文件重依赖),运行时由此 import 复用。
import { shouldWarmCardValues } from './cardValuesWarm';
import RowKeyConflictDrawer, { type RowKeyConflictDTO } from './RowKeyConflictDrawer';

// antd 6.x: Steps uses `items` prop, not <Step> children
const { TextArea } = Input;

/** 递归把所有数值规范化为 4 位定点,消除 live↔snap 求值浮点尾差,保证 payload 去重稳定。 */
export function normalizeDraftPayloadNumbers<T>(payload: T): T {
  const norm = (v: any): any => {
    if (typeof v === 'number') return Number.isFinite(v) ? Number(v.toFixed(4)) : v;
    if (Array.isArray(v)) return v.map(norm);
    if (v && typeof v === 'object') {
      const o: any = {};
      for (const k of Object.keys(v)) o[k] = norm(v[k]);
      return o;
    }
    return v;
  };
  return norm(payload);
}
const { Text, Title } = Typography;

const statusMap: Record<string, { label: string; color: string }> = {
  DRAFT: { label: '草稿', color: 'default' },
  SUBMITTED: { label: '已提交', color: 'processing' },
  APPROVED: { label: '已批准', color: 'success' },
  REJECTED: { label: '已驳回', color: 'error' },
};

// Re-export for draft payload — uses the real formula engine
function computeProductSubtotalSafe(
  li: LineItem,
  driverExpansions?: import('./useDriverExpansions').DriverExpansionMap,
  customerId?: string,
): number {
  return computeProductSubtotal(li, driverExpansions, customerId);
}

const QuotationWizard: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [form] = Form.useForm();
  const [currentStep, setCurrentStep] = useState(0);
  const [loading, setLoading] = useState(false);
  const [quotation, setQuotation] = useState<any>(null);
  const [quotationId, setQuotationId] = useState<string | null>(id || null);

  // Step 1 data
  const [customers, setCustomers] = useState<any[]>([]);
  const [customerSearch, setCustomerSearch] = useState('');
  const [selectedCustomer, setSelectedCustomer] = useState<any>(null);
  const [contacts, setContacts] = useState<any[]>([]);
  // 来自"基础数据导入"流程：URL 含 ?autoPopulate=1 → Step1 客户字段不可更换。
  // 关键：必须用 useRef 在挂载时一次性记下，不能每次 render 重算——QuotationStep2 在
  // autoPopulate finally 里会 history.replaceState 把 ?autoPopulate=1 清掉（防止刷新重复
  // 触发自动展开），重算后 isImportFlow 立刻变 false，会让"导入流自动保存"的 effect
  // 错过它本该触发的窗口。
  const isImportFlowRef = useRef<boolean>(
    typeof window !== 'undefined'
      && new URLSearchParams(window.location.search).get('autoPopulate') === '1'
  );
  const isImportFlow = isImportFlowRef.current;

  // Step 2 data
  const [lineItems, setLineItems] = useState<LineItem[]>([]);
  const [addProductModalOpen, setAddProductModalOpen] = useState(false);
  const [customerTemplateId, setCustomerTemplateId] = useState<string | undefined>();
  // V72：核价模板（template 表 templateKind='COSTING'）— 用于「核价单」视图渲染产品卡片
  const [costingCardTemplateId, setCostingCardTemplateId] = useState<string | undefined>();
  // T34: 选配添加 Drawer 开关
  const [configureDrawerOpen, setConfigureDrawerOpen] = useState(false);
  // T35: Step1 表单（QuotationCreateForm 4 字段）是否已填完
  const [step1Valid, setStep1Valid] = useState(false);
  // ★ Bug 修复: QuotationCreateForm 是受控组件，必须把 4 字段值放到 React state，
  // 否则用户输入 → form.setFieldsValue → 不触发 wizard 重渲染 → value prop 不变 → input 看起来无法填写
  const [step1FormValue, setStep1FormValue] = useState<QuotationFormValue>({
    name: '',
    categoryId: undefined,
    customerTemplateId: undefined,
    costingTemplateId: undefined,
  });
  // Driver 展开结果上提到 wizard 层，便于 buildDraftPayload 在保存前
  // 把 BASIC_DATA / FORMULA 计算结果快照写入 rowData（WYSIWYG）。
  const customerIdValue = (selectedCustomer?.id) || form.getFieldValue('customerId') || undefined;
  // V202+ (2026-05-19): useDriverExpansions 返回 { cache, invalidate } 解决"配置前缓存 0 行/旧值, 配置后不重拉"问题.
  // invalidate(partNos) 清掉指定料号相关 key, 下一轮 fingerprint 改变时自动 re-fetch.
  //
  // Phase4 Task1 (2026-06-01) — autosave 瞬态 batch-expand 自愈:
  // 根因(E2E 实证): autosave 全量重建报价行换新 line id → 本 hook fingerprint(含 li.id)变化
  // → tasks 重建 → 新 key miss cache → 旧链路重发 /batch-expand(渲染期瞬态 ~1~2 次)。
  // 修法: 当所有报价行已有 quoteCardValues(快照模式) 时, 不再 batch-expand(传 EMPTY_LINEITEMS),
  // 改从行级值快照构造 expansions 喂 buildDraftPayload/snapshotRows——rowCount + basicDataValues
  // 与 batch-expand 完全同源(快照本就由它生成), 行编辑值仍取自 cd.rows, 故 snapshotRows 输出不变、
  // 编辑往返存活不受影响(E2E 守护)。新增产品(无快照)时 useSnapAll=false 自动回退实时 batch-expand。
  const useSnapAll = lineItems.length > 0 && lineItems.every(li => !!li.quoteCardValues);
  const { cache: driverExpansionsLive, invalidate: invalidateDriverExpansions } =
    useDriverExpansions(useSnapAll ? EMPTY_LINEITEMS : lineItems, customerIdValue, quotationId ?? undefined);
  // rowKeyFieldsByComp 供 buildSnapshotExpansions 按墓碑过滤（AP-54）；来自顶层结构快照。
  const rowKeyFieldsByComp = React.useMemo(() => {
    const m = new Map<string, string[]>();
    ((quotation?.quoteCardStructure as import('../../services/quotationService').CardStructure | null)?.tabs ?? [])
      .forEach((t: any) => { if (t.componentId) m.set(t.componentId, t.rowKeyFields ?? []); });
    return m;
  }, [quotation?.quoteCardStructure]);
  const driverExpansionsSnap = React.useMemo(
    () => (useSnapAll ? buildSnapshotExpansions(lineItems, 'QUOTE', customerIdValue, rowKeyFieldsByComp) : {}),
    [useSnapAll, lineItems, customerIdValue, rowKeyFieldsByComp],
  );
  const driverExpansions = React.useMemo(
    () => (useSnapAll ? driverExpansionsSnap : driverExpansionsLive),
    [useSnapAll, driverExpansionsSnap, driverExpansionsLive],
  );

  // 动态 key 全局变量定义字典 — 供 computeAllFormulas 在 buildDraftPayload 中正确求值动态 key 公式
  // 空 map = 动态 key token 兜底 0 (旧行为); list() 失败时同样兜底 0 不影响静态 key 场景
  const [gvDefs, setGvDefs] = useState<Record<string, GlobalVariableDefinition>>({});
  useEffect(() => {
    globalVariableService.list()
      .then((res: any) => {
        const arr: GlobalVariableDefinition[] = Array.isArray(res) ? res
          : Array.isArray(res?.data) ? res.data
          : [];
        const map: Record<string, GlobalVariableDefinition> = {};
        for (const d of arr) { if (d?.code) map[d.code] = d; }
        setGvDefs(map);
      })
      .catch(() => setGvDefs({}));
  }, []);

  // Phase 3（2026-06-21）：报价 Excel 列定义 ref — 供 buildDraftPayload 按 customerTemplateId 拉一次。
  // 用 ref 存储避免触发 re-render；失败时静默退化为空数组（quoteExcelValues=undefined，后端兜底）。
  const excelColumnsRef = useRef<CostingTemplateColumn[]>([]);
  useEffect(() => {
    if (!customerTemplateId) { excelColumnsRef.current = []; return; }
    // I1 fix（2026-06-21）：customerTemplateId 快速切换时旧响应可能晚于新响应到达，
    // 用 cancelled 标志丢弃过时响应，防止旧列定义覆盖新 ref。
    let cancelled = false;
    // Phase2.5：取后端解析的有效列（v2 引用配置 excel_component_id 客户端无法解析，须经 getEffectiveColumns）。
    // 端点直接返回解析列数组；v2/legacy 都能拿到 A/B/C，使 saveDraft buildExcelSnapshot 算出非空快照。
    templateService.getEffectiveExcelColumns(customerTemplateId)
      .then((r: any) => {
        if (cancelled) return;
        try {
          const body = r?.data ?? r;
          excelColumnsRef.current = Array.isArray(body) ? body : [];
        } catch {
          excelColumnsRef.current = [];
        }
      })
      .catch(() => { excelColumnsRef.current = []; });
    return () => { cancelled = true; };
  }, [customerTemplateId]);

  // 2026-06-01: 取消 10 秒定时自动保存（用户决议）。草稿持久化改为按需触发：
  //   ① 基础数据导入流程创建后自动保存一次（下方 import-auto-save effect）；
  //   ② 报价卡片单元格编辑走 editQuoteCardValue 端点即时回写（Task3）；
  //   ③ 手动「保存草稿」按钮 / 步骤切换 / 提交。
  // 保留 autoSaveDraft 函数 + ref 供 ①/手动复用；仅删除定时器。
  const lastSaveRef = useRef<string>('');
  // 自动保存死循环修复(方案 B):syncingRef 切断 saveDraft 回填 → lineItems effect → 再次调度保存的反馈环。
  //   syncLineItemsFromResponse 调用前置位 true;监听 lineItems 的 effect 读到 true 即消费复位并 return,
  //   不再调度保存。用户真实编辑直接调 scheduleAutoSave 或走不同路径,syncingRef 始终为 false,不受影响。
  const syncingRef = useRef(false);
  // 自动保存死循环修复(方案 A):tempId → 后端持久化 DB id 的稳定映射。
  //   导入建的行只有稳定 tempId、初始无 id;saveDraft 响应回填 DB id 后记入此表。
  //   buildDraftPayload 以 li.id || 本表[tempId] 兜底发送,确保某次重建抹掉 li.id 时仍能凭
  //   稳定 tempId 找回 DB id → payload 不再 null↔id 振荡 → lastSaveRef 去重命中 → 不再死循环。
  //   tempId 为全局唯一 UUID,跨报价单不冲突,无需重置。
  const dbIdByTempId = useRef<Map<string, string>>(new Map());
  // setInterval 注册时只依赖 quotationId，会捕获首次渲染时的 autoSaveDraft 闭包，
  // 其中 lineItems 仍为初始 []。改用最新值 ref，让定时器始终调用最新版本的 autoSaveDraft，
  // 避免每 10 秒自动保存的负载里 lineItems 永远是空数组（页面刷新后表格全空的根因）。
  const autoSaveDraftRef = useRef<(() => Promise<void>) | undefined>(undefined);
  // 基础数据导入流程：autoPopulate 完成后立即触发一次保存草稿，把"已自动加入 N 个产品"
  // 持久化下来，避免用户刷新前丢数据。一次性，避免重复触发。
  const importAutoSavedRef = useRef(false);
  // task-0712 展示修复：后端 create-quotation 已服务端建行 → 跳过客户端 autoPopulate + import-auto-save，
  // 避免重复行 / saveDraft 全删全建抹掉后端 snapshot_rows 触发「加载中」回退。
  const backendBuiltLinesRef = useRef(false);
  // ③ autoSaveDraft 串行化：导入流下 import-auto-save effect 与 lineItems-change effect
  // 会用「不同」payload（driverExpansions 仍在陆续到位）几乎同时触发两次保存；
  // 两条 id=null payload 的后端事务重叠 → 各插 85 行、谁的「删未保留行」都删不到对方 → 170。
  // savingRef：有保存在飞时不再并发起第二次；pendingSaveRef：飞行中到来的变更，落地后补跑一次（取最新 payload）。
  const savingRef = useRef(false);
  const pendingSaveRef = useRef(false);
  // 止血B(2026-06-25):保存在飞状态,驱动「保存草稿/下一步/上一步」按钮禁用 + loading,
  //   配合 handleSaveDraft 的 savingRef 在飞守卫,杜绝卡顿期连点并发触发 saveDraft(→OptimisticLock/腐蚀)。
  const [saving, setSaving] = useState(false);
  // Plan 1b：提交行键冲突 Drawer
  const [rowKeyConflicts, setRowKeyConflicts] = useState<RowKeyConflictDTO[]>([]);
  const [conflictDrawerOpen, setConflictDrawerOpen] = useState(false);
  const [locateTarget, setLocateTarget] = useState<{ lineItemId?: string; productPartNo?: string; componentId?: string; seq: number } | null>(null);
  const locateSeqRef = useRef(0);
  // Plan A(2026-06-24 空白BUG止血):autosave 默认拒绝门。
  //   背景:打开报价单时 applyQuotationData(:300 basicItems)+ enrich(:425)两处**程序化** setLineItems
  //   触发 autosave 风暴 → saveDraft 慢 + 并发 → 占满后端线程池 → getById 超时 → 退空本地缓存 → 空白页
  //   (违反 :470「草稿默认冻结、打开不重刷」设计意图)。
  //   修法:autosave effect 默认 return,**只有真实用户编辑(经 setLineItemsByUser 置位)才放行**。
  //   打开 / enrich / saveDraft 响应回填等所有程序化 setLineItems 走原始 setLineItems,不置位 → 不 autosave。
  //   表单头部字段编辑走独立的 onValuesChange→scheduleAutoSave 路径(不经此 effect),不受影响。
  const userEditedRef = useRef(false);
  // 用户编辑专用 setter:置位 userEditedRef 后再改 lineItems,使 autosave effect 放行本次变化。
  // 子组件(Step2/Step3/各 Drawer)的 lineItems 写入一律走它;程序化写入仍用原始 setLineItems。
  const setLineItemsByUser = useCallback((update: Parameters<typeof setLineItems>[0]) => {
    userEditedRef.current = true;
    setLineItems(update);
  }, []);

  // Load existing quotation
  useEffect(() => {
    if (id) {
      loadQuotation(id);
    } else {
      // 新建模式：从 ?customerId=xxx URL 参数自动预填客户（从 V6 导入 Drawer 跳转过来时用）
      const presetCustomerId = searchParams.get('customerId');
      if (presetCustomerId) {
        form.setFieldsValue({ customerId: presetCustomerId });
        loadCustomerDetail(presetCustomerId);
        loadContacts(presetCustomerId);
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  // 2026-06-01: 事件驱动自动保存（替代 10s 轮询）。
  //   草稿内容变化(lineItems / 表单字段)时, 防抖 ~1.5s 触发一次 autoSaveDraft;
  //   autoSaveDraft 内部 lastSaveRef 去重 → payload 未变则不发请求(空闲零请求)。
  //   作用: 保证编辑落库(row_data 重开存活 + Excel/提交读新), 而无 10s 空转轮询。
  const autoSaveDebounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // 2026-06-26：暂时关闭「用户编辑失焦自动触发 draft」(lineItems 编辑 effect:279 + 表单 onValuesChange:1626)。
  //   背景:autosave 在首存慢时反复触发(4 份快照回填→payload churn→重发链),把 draft 体感乘 3。
  //   现策略:draft 仅由「导入首存(import-auto-save effect 直调 autoSaveDraft)」+「手动保存草稿/下一步/上一步/提交
  //   按钮(handleSaveDraft)」触发;编辑失焦不再自动存。改回:删掉下面的 return(恢复 1.5s 防抖自动保存)。
  // ⚠️ lazy-cardvalues 防御(参考 BL-0013):若将来重开此 flag,防抖编辑会反复走 autoSaveDraft,
  //   而 saveDraft 已不再算卡片值(每次存后卡片值置 NULL)→ autoSaveDraft 里的 warm 会被每次编辑必触发
  //   → 变高频 ensure 风暴。重开前须给 warm 加节流/去抖(如仅在卡片值真缺时 + 限频),不能直接打开。
  const EDIT_AUTOSAVE_ENABLED = false;
  const scheduleAutoSave = useCallback(() => {
    if (!EDIT_AUTOSAVE_ENABLED) return;   // 编辑失焦自动保存已暂时关闭(见上)
    if (!quotationId) return;
    if (autoSaveDebounceRef.current) clearTimeout(autoSaveDebounceRef.current);
    autoSaveDebounceRef.current = setTimeout(() => {
      autoSaveDraftRef.current?.();
    }, 1500);
  }, [quotationId]);
  // 卸载/换单时清理待触发的防抖保存
  useEffect(() => () => {
    if (autoSaveDebounceRef.current) clearTimeout(autoSaveDebounceRef.current);
  }, []);
  // lineItems 变化(单元格编辑/增删产品/选配)→ 调度防抖保存。
  useEffect(() => {
    if (!quotationId) return;
    if (lineItems.length === 0) return;
    // 方案 B guard:syncLineItemsFromResponse 回填触发的那一次变化不再调度保存(它本身就是刚保存的结果)。
    if (syncingRef.current) {
      syncingRef.current = false;
      return;
    }
    // Plan A 默认拒绝门:仅"真实用户编辑"(经 setLineItemsByUser 置位)才 autosave;
    // 打开 / enrich 等程序化 setLineItems 不置位 → 直接 return,不发请求(止血风暴)。
    // 命中即消费复位:saveDraft 后 syncLineItemsFromResponse 回填(及后续程序化变化)不再误触发。
    if (!userEditedRef.current) return;
    userEditedRef.current = false;
    scheduleAutoSave();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lineItems, quotationId]);

  // Step3 初始化逻辑已移入 QuotationStep3 组件内部（D6 强刷 lineUnitPrice = subtotal）
  // 旧的整单折扣自动计算 effect 已废弃（V1 行级折扣由 Step3 组件处理）

  const applyQuotationData = (q: any) => {
    // Plan A:打开/加载一律从"默认拒绝"起步,清掉可能残留的用户编辑标记(防跨报价单泄漏 → 打开误存)。
    userEditedRef.current = false;
    setQuotation(q);
    setQuotationId(q.id);
    form.setFieldsValue({
      customerId: q.customerId,
      name: q.name,
      contactId: q.contactId,
      contactName: q.contactName,
      contactPhone: q.contactPhone,
      contactEmail: q.contactEmail,
      projectName: q.projectName,
      opportunityId: q.opportunityId,
      quoteType: q.quoteType,
      priority: q.priority,
      stage: q.stage,
      expectedCloseDate: q.expectedCloseDate ? dayjs(q.expectedCloseDate) : null,
      paymentTerms: q.paymentTerms,
      deliveryCycle: q.deliveryCycle,
      expiryDate: q.expiryDate ? dayjs(q.expiryDate) : null,
      remarks: q.remarks,
      finalDiscountRate: q.finalDiscountRate,
      discountAdjustmentReason: q.discountAdjustmentReason,
    });
    if (q.customerId) {
      loadCustomerDetail(q.customerId);
      loadContacts(q.customerId);
    }
    setCustomerTemplateId(q.customerTemplateId || undefined);
    setCostingCardTemplateId(q.costingCardTemplateId || undefined);
    // 把已有报价单的 4 字段回灌到 Step1 React state, 让编辑模式下「选择模板」Card 显示已存值
    setStep1FormValue({
      name: q.name || '',
      categoryId: q.categoryId || undefined,
      customerTemplateId: q.customerTemplateId || undefined,
      costingTemplateId: q.costingCardTemplateId || undefined,
    });
    if (q.lineItems) {
      // Build basic lineItems first, then enrich componentData from template snapshots
      const basicItems: LineItem[] = q.lineItems.map((li: any) => {
        // PRD：图号属性 ⇄ customer_drawing_no 绑定。已存在 quotation 加载时如果 productAttributeValues.图号
        // 是空但 customerDrawingNo 有值，自动填回——保持新建 / 旧单两种入口的展示一致。
        const rawAttrs = li.productAttributeValues
          ? (typeof li.productAttributeValues === 'string'
              ? JSON.parse(li.productAttributeValues)
              : li.productAttributeValues)
          : {};
        if (li.customerDrawingNo
            && Object.prototype.hasOwnProperty.call(rawAttrs, '图号')
            && !rawAttrs['图号']) {
          rawAttrs['图号'] = li.customerDrawingNo;
        }
        return ({
        // line_item id (后端 DTO 已带, 用于版本切换 PATCH endpoint)
        id: li.id,
        productId: li.productId || '',
        productName: li.productName || '',
        productPartNo: li.productPartNo || '',
        // 客户料号需要从 API 回读保留（QuotationStep2 用它做 customer-mapping），
        // 否则刷新后 partNo 映射重置为 null。
        customerPartNo: li.customerPartNo || '',
        // 客户视角展示（PRD）：产品卡片优先显示这三项；后端按 (customerId, hfPartNo) 反查 mat_customer_part_mapping
        customerPartName: li.customerPartName || '',
        customerProductNo: li.customerProductNo || '',
        customerDrawingNo: li.customerDrawingNo || '',
        // 生产料号详情（mat_part 主档）—— 卡片右侧 popover 用
        hfPartInfo: li.hfPartInfo || undefined,
        templateId: li.templateId || '',
        templateName: li.templateName || '',
        productAttributeValues: rawAttrs,
        componentData: li.componentData || [],
        subtotal: li.subtotal || 0,
        // 工序回读:从 GET 的 processes 填 processIds,使 saveDraft 能回写 quotation_line_process
        // (选配/导入工序跨保存存活,刷新后不丢)。
        processIds: Array.isArray(li.processes) ? li.processes.map((p: any) => p.processId).filter(Boolean) : [],
        // 选配-组合工艺 per-quote:GET 回读步骤,带到本行供 saveDraft 透传(跨保存存活)
        compositeProcesses: Array.isArray(li.compositeProcesses) ? li.compositeProcesses : [],
        // 料号版本锁定 (后端 DTO 已带, 用于产品卡片版本 Tag 显示)
        partVersionLocked: li.partVersionLocked,
        // productType (mat_part.product_type 反查) — 用于 ProductCard 按类型条件渲染 Tab
        productType: li.productType,
        // V169 选配组合关系 — A 修复: filter PART 子卡片需要这两个字段
        compositeType: li.compositeType,
        parentLineItemId: li.parentLineItemId,
        // Step3 新增 9 字段回读（AP-2：round-trip 不丢字段）
        annualVolume: li.annualVolume ?? undefined,
        discountSource: li.discountSource ?? undefined,
        discountBaseAmount: li.discountBaseAmount != null ? Number(li.discountBaseAmount) : undefined,
        discountRateApplied: li.discountRateApplied != null ? Number(li.discountRateApplied) : undefined,
        lineDiscountAmount: li.lineDiscountAmount != null ? Number(li.lineDiscountAmount) : undefined,
        lineUnitPrice: li.lineUnitPrice != null ? Number(li.lineUnitPrice) : undefined,
        lineFinalPrice: li.lineFinalPrice != null ? Number(li.lineFinalPrice) : undefined,
        lineTotalAmount: li.lineTotalAmount != null ? Number(li.lineTotalAmount) : undefined,
        discountRuleCode: li.discountRuleCode ?? undefined,
        // 报价单整份快照 Phase2 Task8: 行级值快照(后端 JSON 字符串) → 渲染脱钩用
        quoteCardValues: li.quoteCardValues ?? undefined,
        costingCardValues: li.costingCardValues ?? undefined,
        // Excel 值快照（后端已算好）→ useExcelSnapshotRows 直接渲染，不回退实时拉数
        quoteExcelValues: li.quoteExcelValues ?? undefined,
        costingExcelValues: li.costingExcelValues ?? undefined,
      }) as LineItem;
      });
      // 基础数据导入 autoPopulate 与慢速 loadQuotation 的竞态修复:
      //   DRAFT 打开走 refreshCardSnapshot + 二次 getById, applyQuotationData 落得很慢;
      //   dev StrictMode 下 loadQuotation 还会双跑。若这次"加载到的空单"(basicItems=[])
      //   覆盖落在 autoPopulate 已把产品加进 state 之后, 就会把刚加的产品抹掉 → 随后防抖
      //   autosave 把 0 行持久化 → DB 空 → 重开空白(QT-1554 实证: saveDraft 收到 lineItems=0)。
      //   修法: 导入流下, 加载结果为空但 state 已有(autoPopulate 加的)产品时, 不用空结果清空。
      //   (enrich 那条 setLineItems 已有长度护栏; 此处是唯一无护栏的硬覆盖。)
      setLineItems(prev =>
        (isImportFlow && basicItems.length === 0 && prev.length > 0) ? prev : basicItems
      );
      // task-0712 展示修复：加载到的明细行已带持久化 DB id（后端 create-quotation 服务端建行标志）
      // → 关闭客户端建行/自动保存，防重复行 + 防 saveDraft 全删全建抹掉后端 snapshot_rows 触发回退。
      if (isImportFlow && basicItems.length > 0 && basicItems.some((li: any) => !!li.id)) {
        backendBuiltLinesRef.current = true;
        wizardAutoPopulatedRef.current = true;
        importAutoSavedRef.current = true;
      }

      // Async: enrich each lineItem's componentData with fields/formulas from template.
      // 关键：enrich 完成后必须用函数式 setState 合并到当前 state，而不是整体替换。
      // 否则 enrich 在 100~500ms 之间完成时，如果用户已经在产品卡上输入了内容，
      // 这一次 setLineItems(enrichedItems) 会把当前 state 整张盖掉 → 输入丢失。
      // 典型表现：先开始输入的产品卡数据全没，后输入的产品卡数据保住。
      // 同时：基础数据导入流程下，productAttributes schema 也是模板内的，
      // 后端 SaveDraftRequest 没有这维度——必须从模板再拉一次回填。
      // Phase4 Task5: 报价侧结构脱钩 —— 有 quote_card_structure(且 templateId 对得上) 时, componentData
      //   结构 + productAttributes 全部从结构快照同步组装, **不再 GET /templates**(旁路 enrich/loadProductAttributes)。
      //   无结构 / templateId 不匹配(存量单 / 核价模板) → 回退原 enrich + loadProductAttributes(读模板)。
      const quoteStruct = (q?.quoteCardStructure ?? null) as import('../../services/quotationService').CardStructure | null;
      Promise.all(
        basicItems.map(async (li) => {
          if (!li.templateId) return li;
          const canUseStruct = !!quoteStruct
            && Array.isArray(quoteStruct.tabs) && quoteStruct.tabs.length > 0
            && (!quoteStruct.templateId || String(quoteStruct.templateId) === String(li.templateId));
          if (canUseStruct) {
            const compData = buildComponentDataFromStructure(quoteStruct!, li.componentData);
            const productAttributes = (li.productAttributes && li.productAttributes.length > 0)
              ? li.productAttributes
              : productAttributesFromStructure(quoteStruct!);
            return { ...li, componentData: compData, productAttributes };
          }
          // 2026-05-17: componentData=[] 也要 enrich(选配创建的 lineItem 后端不落 component_data
          //   → 前端必须从模板 snapshot 构建结构, 否则卡片空白).
          // 2026-05-19(AP-37 续): 即便 saved.fields 已落, 仍可能因历史 bug (同 cid 多实例
          //   反查塌缩 / saved.tabName 覆盖 snapshot.tabName 等) 出现 tabName 重复污染.
          //   把 enrich 改为"始终跑", 由 enrichComponentData 内部以 snapshot 为权威修复结构.
          //   性能上 fetchTemplateOnce 已 SWR 缓存, 同模板复用不会重复请求.
          const needProductAttrs = !li.productAttributes || li.productAttributes.length === 0;
          const [enrichedCompData, productAttributes] = await Promise.all([
            enrichComponentData(li.templateId, li.componentData),
            needProductAttrs
              ? loadProductAttributes(li.templateId)
              : Promise.resolve(li.productAttributes!),
          ]);
          return { ...li, componentData: enrichedCompData, productAttributes };
        })
      ).then(enrichedItems => {
        // 2026-05-19 修: 初次 fetch 时 comp.fields 还没 enrich, useDriverExpansions 没发 overrideFieldsJson,
        // 后端按 component 默认 fields 返 mat_process.* keys + 缺 @gvar — 缓存"错的"数据.
        // 现在 enrich 完成 comp.fields 有 v_composite_child_* 路径 + datasource_binding,
        // 但 fingerprint 只看 cids 没变 → 不重 fetch → cache 卡在错的数据 → UI 永久"加载中".
        // 解决: enrich 完毕显式 invalidate 涉及料号的 cache, 下一轮自动 refetch with 新 overrideFieldsJson.
        const partNos = enrichedItems
          .map((li: any) => li?.productPartNo)
          .filter((x: any): x is string => typeof x === 'string' && x.length > 0);
        if (partNos.length > 0) invalidateDriverExpansions(partNos);
        setLineItems(prev => {
          // 取与当前 state 相同长度（用户可能已经增删过 lineItem，这种情况下放弃 enrich）
          if (prev.length !== enrichedItems.length) return prev;
          return prev.map((cur, i) => {
            const enriched = enrichedItems[i] as any;
            // 报价单 ID 维度对不上（用户切换报价单了）也跳过
            if (cur.productId !== enriched.productId
                && cur.productPartNo !== enriched.productPartNo) {
              return cur;
            }
            // 合并 componentData：保留 cur.rows（用户实际输入），
            // 用 enriched 的 fields / formulas / componentId / tabName 等元数据
            const mergedComponentData = (enriched.componentData || []).map((ec: any, ci: number) => {
              const curComp: any = cur.componentData?.[ci];
              if (!curComp) return ec;
              const rowsCur = Array.isArray(curComp.rows) ? curComp.rows : null;
              const rowsEnr = Array.isArray(ec.rows) ? ec.rows : [];
              // 用户输入若已超过 enriched 默认行（filler 扩展过）以用户为准
              // 用户没动过（rowsCur 为空 / 都是空对象）则用 enriched 默认值
              const hasUserInput = rowsCur && rowsCur.some((r: Record<string, any>) =>
                r && Object.keys(r).some(k => k !== 'row_index' && r[k] != null && r[k] !== '')
              );
              return {
                ...ec,
                rows: hasUserInput ? rowsCur : rowsEnr,
              };
            });
            // 关键：只把"模板派生 schema"（componentData / productAttributes）覆盖到当前 state；
            // 用户值字段（productAttributeValues / 等）保留 cur 的最新值，避免 enrichment 在
            // 用户输入窗口期内整张盖（AP-9）。
            return {
              ...cur,
              componentData: mergedComponentData,
              productAttributes: enriched.productAttributes ?? cur.productAttributes,
            };
          });
        });
      });
    }
  };

  const loadQuotation = async (qId: string) => {
    setLoading(true);
    try {
      const res = await quotationService.getById(qId);
      // 草稿默认冻结（2026-06-18）：打开不再自动重刷，直接读已冻快照渲染。
      // 需要最新基础数据 → 用户在 Step2 主动点「刷新基础数据」按钮（Task B2）。
      applyQuotationData(res.data);
      // lazy-cardvalues 打开兜底:若仍缺卡片值(warm 未跑/未完成/受损存量单)→ 同步 ensure 一次再渲染,
      //   避免 QuotationStep2 闸门回退实时 batch-expand/evaluate 风暴。仍在 loading 内 await(沿用向导既有 loading)。
      const opened = (res.data?.lineItems ?? []) as any[];
      // 大单 ensure 可阻塞 ~9-12s,通用 spinner 外再给一条轻提示(对齐 QuotationStep2 的 ensureExcelValues)。
      let backupData: any = res.data;
      if (shouldWarmCardValues(opened)) {
        const hide = message.loading('正在准备快速浏览…', 0);
        try {
          let r = await quotationService.ensureCardValues(qId);
          // warm 在飞（另一并发 warm 占单飞锁,典型如导入后台补算)→ 退避轮询等它完成再回灌,
          // 免用户手动刷新(BL-0027 族:快照后台异步补完不自动重拉)。最多 ~16s,超时则回落现有兜底。
          let warmAttempts = 0;
          while (r?.data?.cardValuesWarming && warmAttempts < 20) {
            warmAttempts++;
            await new Promise(resolve => setTimeout(resolve, 800));
            r = await quotationService.ensureCardValues(qId);
          }
          if (r?.data && !r.data.cardValuesWarming) {
            applyQuotationData(r.data);  // 回灌带卡片值的 DTO
            backupData = r.data;         // 本地备份也存 warmed 副本,避免后端失败回退又恢复无卡片值版本
          }
          // 轮询超时仍 warming:保持现有渲染/兜底,不重复 apply(极端场景,用户可手刷)
        } catch {
          // ensure 失败 → 回退现有实时渲染(同今天);静默,不弹 error 吓用户
        } finally {
          hide();
        }
      }
      // Update localStorage backup on successful load
      safeSetLocalDraft(`cpq-draft-${qId}`, JSON.stringify(backupData));
    } catch (e: any) {
      // P2-9: Try localStorage fallback on backend failure
      const local = localStorage.getItem(`cpq-draft-${qId}`);
      if (local) {
        try {
          const localData = JSON.parse(local);
          applyQuotationData(localData);
          message.warning('后端加载失败，已从本地缓存恢复');
        } catch {
          message.error(e.message);
        }
      } else {
        message.error(e.message);
      }
    } finally {
      setLoading(false);
    }
  };

  const loadCustomerDetail = async (custId: string) => {
    try {
      const res = await customerService.getById(custId);
      setSelectedCustomer(res.data);
      // 同时把当前客户灌进 customers 列表——否则 Select 的 options 找不到这个 UUID 对应的
      // label，下拉框只能显示一串 UUID（已加载的报价单刷新后必现）。
      setCustomers(prev => (prev.some(c => c.id === res.data?.id) ? prev : [...prev, res.data]));
    } catch {
      // ignore
    }
  };

  const loadContacts = async (custId: string) => {
    try {
      const res = await customerService.listContacts(custId);
      const list = res.data || [];
      setContacts(list);
      // 2026-05-14: PRD §3.2.1 — 客户加载完成后自动选主要联系人(isPrimary=true).
      // 仅当 contactId 尚未选定时触发,避免覆盖编辑模式已存值或用户手动调整.
      const currentContactId = form.getFieldValue('contactId');
      if (!currentContactId) {
        const primary = list.find((c: any) => c.isPrimary);
        if (primary) {
          form.setFieldsValue({
            contactId: primary.id,
            contactName: primary.name,
            contactPhone: primary.phone,
            contactEmail: primary.email,
          });
        }
      }
    } catch {
      setContacts([]);
    }
  };

  const searchCustomers = async (value: string) => {
    setCustomerSearch(value);
    try {
      const params: any = { page: 0, size: 20 };
      if (value && value.trim().length > 0) {
        params.keyword = value.trim();
      }
      const res = await customerService.list(params);
      const data = res.data;
      setCustomers(Array.isArray(data) ? data : (data?.content || []));
    } catch {
      setCustomers([]);
    }
  };

  // Load initial customer list on mount
  useEffect(() => {
    searchCustomers('');
  }, []);

  const handleCustomerSelect = (custId: string) => {
    const prevCustomerId = form.getFieldValue('customerId');
    form.setFieldValue('customerId', custId);
    // 切换客户时(prev !== curr)清空旧联系人字段,让 loadContacts 自动选新客户的主要联系人.
    // 首次选客户(prev 为空)时不需要清(本来就空),也走 auto-pick.
    if (prevCustomerId && prevCustomerId !== custId) {
      form.setFieldsValue({
        contactId: undefined,
        contactName: '',
        contactPhone: '',
        contactEmail: '',
      });
    }
    loadCustomerDetail(custId);
    loadContacts(custId);
    // 把基础信息卡里用户可能已输入的"报价单名称"灌进 step1FormValue,
    // 避免 QuotationCreateForm 受控渲染时显示空 + 自动填充"<客户名> 报价单"覆盖用户输入
    const existingName = form.getFieldValue('name');
    if (existingName) {
      setStep1FormValue(prev => ({ ...prev, name: existingName }));
    }
  };

  // SaveDraft 成功后用响应回填 lineItem.partVersionLocked
  // 仅按 id 匹配后只更新版本字段，不动 productAttributeValues / componentData 等用户可能正在编辑的字段
  // 无变更时返回原 state 引用，跳过 re-render
  // 保存后回填:saveDraft 全量重建会把每行 id 换成新 UUID,响应按 sortOrder ASC 返回
  // (buildDraftPayload 发送 sortOrder=数组下标)→ 按 index 对齐回填新 id + partVersionLocked。
  // 关键:行 id 同步后 useDriverExpansions 的 fingerprint(含 li.id)变化 → 自动用新 id 重拉展开,
  // 命中保存时刚写好的快照(snapshotQuotation 在响应返回前已落库)→ 工序等"按行(quotation_line_process)
  // 存储"的快照数据无需手动刷新即出现(修:导入产品工序加入时空、刷新才有)。
  // 不能按 id 匹配:新行 id 恰恰是变化的那一维,按 id 必匹配不上(旧实现对新行/导入行回填失效)。
  // buildDraftPayload 不发送 line id → 回填 id 不改变下次 payload → 无再保存死循环。
  // Phase4 Task5: saveDraft/create 响应 DTO 不含 4 份结构快照(仅 getById 暴露)。
  // 直接 setQuotation(res.data) 会把 quoteCardStructure 等抹成 undefined → Step2 结构脱钩逻辑
  // (quoteTemplateComponentIds / componentData 组装)瞬时回退 enrich → 触发一次 GET /templates。
  // 故保存后合并时保留上一份 quotation 的结构快照(响应缺失才回填)。
  const setQuotationPreservingStructures = (resData: any) => {
    if (!resData) return;
    setQuotation((prev: any) => {
      const merged: any = { ...resData };
      for (const k of ['quoteCardStructure', 'costingCardStructure', 'quoteExcelStructure', 'costingExcelStructure']) {
        if (merged[k] == null && prev?.[k] != null) merged[k] = prev[k];
      }
      return merged;
    });
  };

  const syncLineItemsFromResponse = (resData: any) => {
    const respLines = resData?.lineItems;
    if (!Array.isArray(respLines)) return;
    // 方案 B:置位 syncingRef,让 lineItems effect 跳过本次回填引发的调度,切断死循环。
    syncingRef.current = true;
    setLineItems(prev => {
      // 数量不一致(理论不会)时不冒险按 index 错位回填,退化为依赖手动刷新。
      if (respLines.length !== prev.length) return prev;
      let changed = false;
      const next = prev.map((item, i) => {
        const r = respLines[i];
        if (!r) return item;
        // 方案 A:按 index 对齐记下 tempId → DB id,供 buildDraftPayload 在 li.id 被重建抹掉时兜底回填
        const tempId = (item as any).tempId;
        if (r.id != null && tempId) dbIdByTempId.current.set(String(tempId), String(r.id));
        const patch: any = {};
        if (r.id != null && String(r.id) !== String((item as any).id)) patch.id = r.id;
        if (r.partVersionLocked != null && r.partVersionLocked !== item.partVersionLocked) {
          patch.partVersionLocked = r.partVersionLocked;
        }
        // 回灌后端 saveDraft 时算好的 4 份值快照(报价/核价 × 卡片/Excel)。
        // 基础数据导入流程：autoPopulate 加的产品 buildLineItemFromTemplate 不含这些快照,
        // autoSave(saveDraft) 后端 snapshotLineValues 已算好并随响应返回, 但旧码只回灌 id/版本号
        // → 前端 li.costingExcelValues 仍 undefined → 核价 Excel 视图首屏空白("—"), 要整页刷新
        // (loadQuotation)才显示。这里就地回灌, 第一眼即正确(与刷新后一致)。
        for (const k of ['quoteCardValues', 'costingCardValues', 'quoteExcelValues', 'costingExcelValues'] as const) {
          if (r[k] != null && r[k] !== (item as any)[k]) patch[k] = r[k];
        }
        if (Object.keys(patch).length > 0) { changed = true; return { ...item, ...patch }; }
        return item;
      });
      return changed ? next : prev;
    });
  };

  // lazy-cardvalues：首存/显式保存成功后 warm。不阻塞、不挡操作,失败静默。
  // 2026-07-03:warm 建好快照后**就地回灌**卡片值到当前 lineItems(不整页 reload、不打断编辑),
  //   让单元格从「加载中…」解析出真值,免手刷。导入流程尤其需要:saveDraft 返回时快照尚未建完
  //   (后端异步补算),响应里 costingCardValues 仍空 → syncLineItemsFromResponse 补不到 → 加载中;
  //   本 warm 轮询等补算完成后再 sync,补齐。并发 warm 占单飞锁(cardValuesWarming)时退避轮询。
  const warmCardValues = useCallback(async (qId: string, items: any[]) => {
    if (!shouldWarmCardValues(items)) return;
    try {
      let r = await quotationService.ensureCardValues(qId);
      let attempts = 0;
      while (r?.data?.cardValuesWarming && attempts < 20) {
        attempts++;
        await new Promise(resolve => setTimeout(resolve, 800));
        r = await quotationService.ensureCardValues(qId);
      }
      if (r?.data && !r.data.cardValuesWarming) {
        syncLineItemsFromResponse(r.data);  // 回灌建好的 4 份卡片/Excel 值 → 单元格解析出真值
      }
    } catch { /* warm best-effort;打开守卫兜底 */ }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const autoSaveDraft = useCallback(async () => {
    if (!quotationId) return;
    // ③ 串行化：已有保存在飞 → 记一个待补跑标记后直接返回，不并发第二条 id=null payload。
    if (savingRef.current) { pendingSaveRef.current = true; return; }
    savingRef.current = true;
    setSaving(true);
    try {
      const values = form.getFieldsValue();
      const payload = normalizeDraftPayloadNumbers(buildDraftPayload(values));
      // P0(2026-06-26):去重只比用户输入,剔除随 driverExpansions live→snap 翻转而重算的派生字段
      //   (subtotal / quoteExcelValues / rowData)。否则首存后回填快照翻转模式 → payload 串变 → 去重失效
      //   → pendingSaveRef 补发 → 三连发。详见 draftPayloadDedup.ts。
      const dedupKey = stableDraftDedupKey(payload);
      if (dedupKey === lastSaveRef.current) return;
      lastSaveRef.current = dedupKey;
      const res = await quotationService.saveDraft(quotationId, payload);
      // BUMP 后端把新 partVersionLocked 写入 DB，前端本地 state 需同步回填，
      // 避免「卡片版本号停在旧值直到强刷」的 UX 漂移；同时回填重建后的新行 id，
      // 触发 driver 展开按新 id 重拉 → 导入工序等按行快照无需刷新即出现。
      syncLineItemsFromResponse(res?.data);
      // lazy-cardvalues：saveDraft 不再算卡片值 → 此处 fire-and-forget warm(导入首存的主路径)。
      //   用响应里的最新行(其卡片值此刻为 NULL)判定;guard 内部 shouldWarmCardValues 决定是否真发。
      warmCardValues(quotationId, (res?.data?.lineItems ?? lineItems) as any[]);
      // P2-9: backup to localStorage on success
      safeSetLocalDraft(`cpq-draft-${quotationId}`, JSON.stringify(payload));
    } catch {
      // P2-9: fallback to localStorage on failure
      try {
        const values = form.getFieldsValue();
        const payload = buildDraftPayload(values);
        safeSetLocalDraft(`cpq-draft-${quotationId}`, JSON.stringify(payload));
        message.warning('网络异常，已保存到本地缓存');
      } catch {
        // ignore
      }
    } finally {
      savingRef.current = false;
      setSaving(false);
      // 飞行期间有新的保存请求被合并 → 现在串行补跑一次（取最新 lineItems/expansion 的 payload）。
      // 此时第一次保存的 syncLineItemsFromResponse 已回填行 id，补跑 payload 带 id → 后端就地复用，不再新增重复行。
      if (pendingSaveRef.current) {
        pendingSaveRef.current = false;
        autoSaveDraftRef.current?.();
      }
    }
    // 把 driverExpansions / customerIdValue 列入 deps，让 buildDraftPayload → snapshotRows
    // 内部访问的是最新的 expansion 缓存。否则 useCallback 会缓存空 expansion 的旧闭包：
    // 导入流自动保存即便等到 expansion ready 才触发，autoSaveDraft 内部仍会读到空 driverExpansions
    // → snapshotRows 落 1 行而不是展开后的 N 行（明细页只看到 1 行 — 数据的根因）。
  }, [quotationId, form, lineItems, driverExpansions, customerIdValue, warmCardValues]);

  // 让 setInterval 总是调用最新的 autoSaveDraft（避开闭包陷阱）
  useEffect(() => {
    autoSaveDraftRef.current = autoSaveDraft;
  }, [autoSaveDraft]);

  // 基础数据导入流程：autoPopulate 在 QuotationStep2 完成 → lineItems 第一次非空时自动保存一次。
  // 这样用户从导入页跳过来就立刻有持久化数据，不必等 10 秒 interval 或手动点保存草稿。
  //
  // 关键时序：必须等 driverExpansions 把所有需要展开的组件都拿回来再保存。否则 saveDraft
  // 内部的 snapshotRows 看不到 expansion 就只会落 1 行（buildEmptyRow 的初始行），
  // 报价单明细里看到的就是"投料成本只有 1 行空数据"——driver 展开是异步的，比 setLineItems
  // 慢一拍。先用 driverExpansions 是否覆盖所有期待 key 做闸门。
  useEffect(() => {
    if (backendBuiltLinesRef.current) return; // task-0712：后端已服务端建行，跳过客户端首存
    if (!isImportFlow) return;
    if (importAutoSavedRef.current) return;
    if (!quotationId) return;
    if (lineItems.length === 0) return;
    // 收集所有需要展开（dataDriverPath 非空）的组件 key
    const expectedKeys: string[] = [];
    for (const li of lineItems) {
      if (!li.productPartNo) continue;
      for (const comp of li.componentData || []) {
        if (!comp.componentId) continue;
        if (!(comp as any).dataDriverPath) continue;
        // Bug B: lineItemId = li.id || li.tempId || ''
        const lineItemIdExpected = (li as any).id || (li as any).tempId || '';
        expectedKeys.push(driverExpansionKey(lineItemIdExpected, li.productPartNo, comp.componentId, customerIdValue, (comp as any).dataDriverPath, fieldsOverrideHash((comp as any).fields)));
      }
    }
    // 只要有期待 key，就必须等 driverExpansions 全部到位再保存
    if (expectedKeys.length > 0) {
      const allReady = expectedKeys.every(k => driverExpansions[k] !== undefined);
      if (!allReady) return;
    }
    importAutoSavedRef.current = true;
    // 立即触发一次保存（silent，仅吐 toast 由 autoSaveDraft 自己控制）
    autoSaveDraftRef.current?.();
  }, [isImportFlow, quotationId, lineItems, driverExpansions, customerIdValue]);

  // 基础数据导入流程：把 autoPopulate 提升到 wizard 层运行。
  // 关键：QuotationWizard 用 steps[currentStep].content() 只渲染当前步骤；
  // 用户停留在 Step 1 选择客户时 QuotationStep2 根本没挂载，原本写在 Step2 里的
  // autoPopulate 永远不会触发——结果是产品行从未生成、自动保存也无数据可写。
  // 把它搬到 wizard 这一层 + isImportFlowRef 闸门，确保挂载即跑。
  // 同时上一段的 import-auto-save effect 会监听 lineItems.length 转正，立即落 DB。
  const wizardAutoPopulatedRef = useRef(false);
  useEffect(() => {
    if (backendBuiltLinesRef.current) return; // task-0712：后端已服务端建行，跳过客户端 autoPopulate
    if (!isImportFlow) return;
    if (wizardAutoPopulatedRef.current) return;
    if (!customerTemplateId) return;
    const customerId = (selectedCustomer?.id) || form.getFieldValue('customerId');
    if (!customerId) return;
    if (lineItems.length > 0) return; // 已有数据（loadQuotation 已加载或先前已自动展开），不重复
    wizardAutoPopulatedRef.current = true;
    const params = new URLSearchParams(window.location.search);
    const importRecordId = params.get('importRecordId') || undefined;
    (async () => {
      try {
        const [candRes, tmplRes] = await Promise.all([
          quotationService.listCustomerPartCandidates(customerId, importRecordId),
          templateService.getByIdCached(customerTemplateId),
        ]);
        const partList: any[] = (candRes.data as any) || [];
        const tmpl = tmplRes.data;
        if (partList.length === 0) {
          message.info('该客户暂无基础数据料号，请先导入或手工添加产品');
          return;
        }
        const items = partList.map((p: any) => buildLineItemFromTemplate(tmpl, p));
        setLineItems(prev => [...prev, ...items]);
        message.success(`已基于模板「${tmpl.name}${tmpl.version ?? ''}」自动加入 ${items.length} 个产品`);
      } catch (e: any) {
        message.error(`自动展开失败：${e?.message ?? '未知错误'}，请手动点「+ 添加产品」`);
        wizardAutoPopulatedRef.current = false; // 失败时允许后续手动重试 step2 内置 autoPopulate
      } finally {
        // 清掉 URL 参数避免刷新重复触发；isImportFlowRef 已在挂载时锁定，不受影响
        const url = new URL(window.location.href);
        url.searchParams.delete('autoPopulate');
        url.searchParams.delete('importRecordId');
        window.history.replaceState({}, '', url.pathname + url.search);
      }
    })();
  }, [isImportFlow, customerTemplateId, selectedCustomer?.id, lineItems.length, form]);

  const buildDraftPayload = (values: any) => {
    return {
      name: values.name,
      contactId: values.contactId,
      contactName: values.contactName,
      contactPhone: values.contactPhone,
      contactEmail: values.contactEmail,
      projectName: values.projectName,
      opportunityId: values.opportunityId,
      quoteType: values.quoteType,
      priority: values.priority,
      stage: values.stage,
      expectedCloseDate: values.expectedCloseDate ? values.expectedCloseDate.format('YYYY-MM-DD') : null,
      paymentTerms: values.paymentTerms,
      deliveryCycle: values.deliveryCycle,
      expiryDate: values.expiryDate ? values.expiryDate.format('YYYY-MM-DD') : null,
      remarks: values.remarks,
      finalDiscountRate: values.finalDiscountRate,
      discountAdjustmentReason: values.discountAdjustmentReason,
      // 2026-05-18: 报价模板 / 核价模板 — 之前漏传, 导致 quotation 表 customer_template_id 永远 NULL,
      // 刷新报价单详情时 Step1 拿不到模板. 这两个字段优先取 wizard 层 state (Step1 用户当前选择),
      // 否则回退 form values (与 onChange setFieldsValue 双轨保险).
      customerTemplateId: customerTemplateId ?? values.customerTemplateId ?? null,
      costingCardTemplateId: costingCardTemplateId ?? values.costingCardTemplateId ?? null,
      lineItems: lineItems.map((li, idx) => ({
        // 2026-06-01: 回传已存在行的 line id → 后端按 id UPSERT(就地更新, 不换 UUID)。
        //   id 稳定后 payload 含 id 也不会 churn(去重正常), 且 editQuoteCardValue 不再撞已删 id。
        //   新增/未持久化行无 id → 送 null, 后端新建。
        id: (li as any).id || dbIdByTempId.current.get(String((li as any).tempId)) || null,
        // 后端 SaveDraftRequest.LineItemDraft.productId 是 UUID，Jackson 无法把
        // 空字符串反序列化成 UUID（整次保存直接 400）。批量导入分支会把
        // productId 置成 ''；这里统一空串归零为 null，避免静默保存失败。
        productId: li.productId || null,
        templateId: li.templateId || null,
        // V5 批量导入流程 productId 为空，但 productPartNo 来自 mat_part；
        // 必须送回后端写入 product_part_no_snapshot，否则刷新后 driver 展开
        // 拿不到料号，BASIC_DATA 列（物料/元素/含量/材料损耗）全空。
        productPartNo: li.productPartNo || null,
        productName: li.productName || null,
        // V6 修复: buildLineItemFromTemplate 写入的字段名是 customerProductNo
        // (与 mat_customer_part_mapping.customer_product_no 对齐), 但后端 SaveDraft DTO
        // 字段名是 customerPartNo。这里加兜底, 否则 SaveDraft 收到 null
        // → 跳过 part_version_locked 查询 → 卡片版本号停在 2000 + 读路径丢版本过滤 → BOM 重复显示。
        customerPartNo: (li as any).customerPartNo || (li as any).customerProductNo || null,
        productAttributeValues: JSON.stringify(li.productAttributeValues || {}),
        subtotal: computeProductSubtotalSafe(li, driverExpansions, customerIdValue),
        sortOrder: idx,
        // 选配/回读的工序回传:后端 saveDraft 据此回写 quotation_line_process(工序跨保存存活)。
        // 导入行此处为空(不携带 processIds),改由 seedProcessesFromBase 让后端从基础工序 seed。
        processIds: Array.isArray((li as any).processIds) ? (li as any).processIds : [],
        // 选配-组合工艺 per-quote:透传步骤,后端 saveDraft 据此重写(换 line id 后存活)
        compositeProcesses: Array.isArray((li as any).compositeProcesses) ? (li as any).compositeProcesses : [],
        // 导入来源标记透传:后端 saveDraft 据此从基础工序 seed 本行 quotation_line_process
        seedProcessesFromBase: (li as any).seedProcessesFromBase ?? undefined,
        // V169 选配组合产品父子关系 — saveDraft 全量重建时必须透传:
        //   compositeType 直接透传 (SIMPLE/COMPOSITE/PART)
        //   parentLineItemId 旧 UUID 已被 CASCADE 删, 不能传; 改传 tempParentIndex (父在 list 的位置)
        //   后端 saveDraft 二阶段 UPDATE: newIds[tempParentIndex] 作新 parent_line_item_id
        compositeType: li.compositeType ?? null,
        tempParentIndex: li.parentLineItemId
          ? lineItems.findIndex(p => p.id === li.parentLineItemId)
          : null,
        // Step3 新增 9 字段透传（AP-2：round-trip 不丢字段，字段名严格对齐 spec §5.3）
        annualVolume: li.annualVolume ?? null,
        discountSource: li.discountSource ?? null,
        discountBaseAmount: li.discountBaseAmount ?? null,
        discountRateApplied: li.discountRateApplied ?? null,
        lineDiscountAmount: li.lineDiscountAmount ?? null,
        lineUnitPrice: li.lineUnitPrice ?? null,
        lineFinalPrice: li.lineFinalPrice ?? null,
        lineTotalAmount: li.lineTotalAmount ?? null,
        discountRuleCode: li.discountRuleCode ?? null,
        // Phase 3（2026-06-21）：前端单引擎算好的报价 Excel 快照，随 saveDraft 原样落库。
        // 后端 snapshotLineValues 守卫：仅当 li.quoteExcelValues==null 时才 buildExcelValues 兜底。
        // C1 fix（2026-06-21）：buildExcelSnapshot 未传 pathCache 时 BNF/VARIABLE 列 cache-miss
        // 会返 '__loading__' 哨兵。JSON.stringify 不抛错，哨兵会原样落库，守卫见非 null 不重算
        // → 脏值永久固化（历史教训：excel-snapshot-no-persist-loading-sentinel）。
        // 最小稳健修法：算完后检测含哨兵则放弃前端快照，让后端 buildExcelValues 兜底。
        quoteExcelValues: (() => {
          try {
            const cols = excelColumnsRef.current;
            if (!cols?.length) return undefined;
            const snap = buildExcelSnapshot(li, cols, driverExpansions, customerIdValue, { globalVariableDefs: gvDefs });
            // C1 guard: if any cell still holds '__loading__' (BNF path not yet resolved),
            // discard the frontend snapshot and let the backend fallback recalculate.
            if (snap.rows.some(r => Object.values(r).some(v => v === '__loading__'))) return undefined;
            return JSON.stringify(snap);
          } catch {
            return undefined;
          }
        })(),
        componentData: (li.componentData || []).map((cd, ci) => ({
          componentId: cd.componentId || null,
          tabName: cd.tabName || '',
          // WYSIWYG 快照：保存前把 BASIC_DATA（driver 展开行级值）
          // 与 FORMULA（公式引擎计算结果）一并写入 rowData，让"屏幕看到的数据 == DB 存的数据"。
          rowData: JSON.stringify(snapshotRows(li, cd, ci)),
          subtotal: cd.subtotal || 0,
          sortOrder: ci,
        })),
      })),
    };
  };

  // 把行内 BASIC_DATA / FORMULA 字段的运行时值，按行写回到 row[fieldName]，再序列化保存。
  // 不修改 React state，仅生成 payload 用的副本，避免 state 写入风暴。
  const snapshotRows = (li: LineItem, cd: ComponentDataItem, _ci: number): Record<string, any>[] => {
    const partNo = li.productPartNo || '';
    const fields = cd.fields || [];
    const componentId = cd.componentId || '';
    // Bug B: lineItemId = li.id || li.tempId || ''
    const lineItemIdSnap = (li as any).id || (li as any).tempId || '';
    const expansionKey = (partNo && componentId)
      ? driverExpansionKey(lineItemIdSnap, partNo, componentId, customerIdValue, cd.dataDriverPath, fieldsOverrideHash(fields as any[]))
      : '';
    const expansion = expansionKey ? driverExpansions[expansionKey] : undefined;

    // 构建本 line item 的 component subtotals（公式引擎需要）
    const componentSubtotals: Record<string, number> = {};
    (li.componentData || []).forEach(c => {
      if (c.tabName) componentSubtotals[c.tabName] = c.subtotal || 0;
    });

    // Phase 1 Task 8: 用 splitRows/rowAt 迭代 driver 行 + 手动新增行，
    // 修复原先 rowCount 只迭代 driver 行、comp.rows 末尾手动行被跳过、保存后丢失的 bug。
    // AP-51 不变：driver 权威，driverCount 严格等于 expansion.rowCount（不取 max）。
    const s = splitRows(cd, expansion as any);

    const out: Record<string, any>[] = [];
    // 2026-05-17: 累加公式支持. 按 row_index 顺序遍历, 把上一行的 is_subtotal 字段值
    // 作为 previousRowSubtotal 传给下一行的 computeAllFormulas, 同 ProductCard 渲染逻辑一致.
    // Plan 2b：上一行全量公式值，previous_row_subtotal 按本列取。
    let prevRowValues: Record<string, number | null> | undefined = undefined;
    for (let i = 0; i < s.totalRows; i++) {
      const ra = rowAt(i, cd, s);

      // 手动行：原样序列化（含 _origin:'manual' 与用户已填各列值），不做富化
      if (ra.isManual) {
        out.push({ ...ra.row });
        continue;
      }

      const baseRow = ra.row;
      const basicDataValues = ra.expIndex >= 0 ? (expansion as any)?.rows?.[ra.expIndex]?.basicDataValues : undefined;

      // 1. snapshot BASIC_DATA values from driver expansion → row[key]
      const enriched: Record<string, any> = { ...baseRow };
      for (const f of fields) {
        if (f.field_type !== 'BASIC_DATA' || !f.basic_data_path) continue;
        const fieldKey = f.name || f.key || '';
        if (!fieldKey) continue;
        if (basicDataValues) {
          const lookupKey = bnfDriverLookupKey(f.basic_data_path);
          if (Object.prototype.hasOwnProperty.call(basicDataValues, lookupKey)) {
            const v = basicDataValues[lookupKey];
            const norm = Array.isArray(v) ? (v.length === 1 ? v[0] : v) : v;
            enriched[fieldKey] = norm ?? null;
          }
        }
      }

      // 1.5. snapshot FIXED_VALUE defaults → row[key]
      // driver 展开行 / 早期版本 lineItems 的 baseRow 可能没经过 buildEmptyRow，
      // 不写就会让保存后的明细页 / 重新加载后的编辑页那一列空白（材料损耗一类配置常量）。
      for (const f of fields) {
        if (f.field_type !== 'FIXED_VALUE') continue;
        if (f.content == null || f.content === '') continue;
        const fieldKey = f.name || f.key || '';
        if (!fieldKey) continue;
        if (enriched[fieldKey] === undefined || enriched[fieldKey] === null || enriched[fieldKey] === '') {
          enriched[fieldKey] = f.content;
        }
      }

      // 1.6. snapshot INPUT 静态默认值 → row[key]
      //   仅"无 default_source"的静态 content 冻结落库（常量，冻结安全，后端核价/Excel 才读得到）；
      //   有 default_source 的字段不冻结——其值由各消费点解析器/后端实时给出（"源优先、实时"）。
      for (const f of fields) {
        if (f.field_type !== 'INPUT_TEXT' && f.field_type !== 'INPUT_NUMBER') continue;
        if (f.default_source) continue;                 // 有源 → 不冻结
        if (f.content == null || f.content === '') continue;
        const fieldKey = f.name || f.key || '';
        if (!fieldKey) continue;
        if (enriched[fieldKey] === undefined || enriched[fieldKey] === null || enriched[fieldKey] === '') {
          enriched[fieldKey] = f.field_type === 'INPUT_NUMBER'
            ? (coerceInputNumber(f.content) ?? f.content)  // 数值列归一，非法保留原值
            : f.content;
        }
      }

      // 2. compute FORMULA values via formula engine → row[key]
      try {
        const formulaCache = computeAllFormulas(
          cd, enriched, componentSubtotals,
          undefined, undefined, partNo, basicDataValues, undefined,
          gvDefs, undefined, prevRowValues,   // B-GV-1: gvDefs; Plan 2b: 末位 prevRowValues(按本列)
        );
        for (const f of fields) {
          if (f.field_type !== 'FORMULA') continue;
          const fieldKey = f.name || f.key || '';
          if (!fieldKey) continue;
          if (formulaCache[fieldKey] != null) {
            enriched[fieldKey] = formulaCache[fieldKey];
          }
        }
        // Plan 2b：本行全量公式值留给下一行，各列下一行按本列取 prev。
        prevRowValues = formulaCache;
      } catch {
        // 公式计算失败不阻塞保存（沿用原值）
      }

      out.push(enriched);
    }
    return out;
  };

  const handleCreateQuotation = async () => {
    try {
      const values = await form.validateFields(['customerId', 'name']);
      const res = await quotationService.create({
        customerId: values.customerId,
        name: values.name,
        quoteType: form.getFieldValue('quoteType'),
        priority: form.getFieldValue('priority'),
        stage: form.getFieldValue('stage'),
        projectName: form.getFieldValue('projectName'),
        expectedCloseDate: form.getFieldValue('expectedCloseDate')?.format('YYYY-MM-DD'),
      });
      const newId = res.data.id;
      setQuotationId(newId);
      setQuotationPreservingStructures(res.data);
      message.success('报价单已创建');
      // Update URL without full reload
      window.history.replaceState(null, '', `/quotations/${newId}/edit`);
    } catch (e: any) {
      if (e.errorFields) return; // form validation
      message.error(e.message);
    }
  };

  const handleSaveDraft = async (silent = false) => {
    if (!quotationId) return;
    // 止血B(2026-06-25)在飞守卫:已有保存在飞(autoSave 或手动)→ 标记补跑后返回,不并发起第二条
    //   saveDraft。背景:首存慢(几十秒)时用户在卡顿期连点「保存草稿/下一步」会并发触发多个 saveDraft,
    //   阶段③ 卡片值循环互删被对方重建的行 → OptimisticLock + 可能腐蚀数据(939e072e 被抹0行同机制)。
    //   与 autoSaveDraft 共用 savingRef/pendingSaveRef,使所有 save(自动+手动+步骤切换)全局串行。
    if (savingRef.current) {
      pendingSaveRef.current = true;
      if (!silent) message.info('正在保存中，请稍候…');
      return;
    }
    savingRef.current = true;
    setSaving(true);
    try {
      const values = form.getFieldsValue();
      // 与 autoSaveDraft 同口径:规范化数值后再 PUT/落 localStorage,避免手动/自动保存写库精度不一致
      const payload = normalizeDraftPayloadNumbers(buildDraftPayload(values));
      const res = await quotationService.saveDraft(quotationId, payload);
      // P0:与 autoSaveDraft 同口径登记去重键,使 finally 的 pendingSaveRef 补发(用户输入未变时)被去重,
      //   不再因首存回填快照翻转模式而多发一次 PUT。
      lastSaveRef.current = stableDraftDedupKey(payload);
      setQuotationPreservingStructures(res.data);
      // 回填重建后的新行 id + partVersionLocked,避免卡片版本号停在旧值、并触发展开按新 id 重拉
      syncLineItemsFromResponse(res.data);
      // lazy-cardvalues：显式保存(保存草稿/下一步/提交前置存)成功后 warm,与导入首存同口径,fire-and-forget。
      warmCardValues(quotationId, (res.data?.lineItems ?? lineItems) as any[]);
      if (!silent) message.success('草稿已保存');
      safeSetLocalDraft(`cpq-draft-${quotationId}`, JSON.stringify(payload));
    } catch (e: any) {
      try {
        const values2 = form.getFieldsValue();
        const payload2 = normalizeDraftPayloadNumbers(buildDraftPayload(values2));
        safeSetLocalDraft(`cpq-draft-${quotationId}`, JSON.stringify(payload2));
        if (!silent) message.warning('已保存到本地，网络恢复后将同步');
      } catch {
        if (!silent) message.error(e.message);
      }
    } finally {
      savingRef.current = false;
      setSaving(false);
      // 飞行期间合并的保存请求,落地后补跑一次(取最新 payload),与 autoSaveDraft 同款。
      if (pendingSaveRef.current) {
        pendingSaveRef.current = false;
        autoSaveDraftRef.current?.();
      }
    }
  };

  const handleSubmit = async () => {
    if (!quotationId) return;
    try {
      // Save draft first
      await handleSaveDraft();
      await quotationSnapshotService.submit(quotationId);
      message.success('报价单已提交审批');
      // Reload to get updated status and driftDetection
      await loadQuotation(quotationId);
    } catch (e: any) {
      const conflicts = e?.payload?.conflicts;
      if (Array.isArray(conflicts) && conflicts.length) {
        setRowKeyConflicts(conflicts);
        setConflictDrawerOpen(true);
      } else {
        message.error(e.message);
      }
    }
  };

  const handleLocateConflict = (c: RowKeyConflictDTO) => {
    locateSeqRef.current += 1;
    setLocateTarget({ lineItemId: c.lineItemId, productPartNo: c.productPartNo, componentId: c.componentId, seq: locateSeqRef.current });
    setConflictDrawerOpen(false);
    setCurrentStep(1);
  };

  const handleRefreshDrift = async () => {
    if (!quotationId) return;
    try {
      await quotationDriftService.refreshVersions(quotationId);
      message.success('已更新至最新版本基础数据');
      await loadQuotation(quotationId);
    } catch (e: any) {
      message.error(e.message || '刷新版本失败，请稍后重试');
    }
  };

  const handleCalculateDiscount = async () => {
    if (!quotationId) return;
    const originalAmount = lineItems.reduce((sum, li) => sum + computeProductSubtotal(li, driverExpansions, customerIdValue), 0);
    if (originalAmount <= 0) {
      message.warning('没有可计算的金额');
      return;
    }
    try {
      const res = await quotationService.calculateDiscount(quotationId, originalAmount);
      setQuotationPreservingStructures(res.data);
      form.setFieldValue('finalDiscountRate', res.data.finalDiscountRate);
      message.success('折扣已计算');
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const handleRemoveLineItem = (index: number) => {
    setLineItemsByUser(prev => prev.filter((_, i) => i !== index));
  };

  const handleUpdateLineItem = (index: number, data: Partial<LineItem> | ((prev: LineItem) => Partial<LineItem>)) => {
    setLineItemsByUser(prev => prev.map((item, i) => {
      if (i !== index) return item;
      const patch = typeof data === 'function' ? data(item) : data;
      return { ...item, ...patch };
    }));
  };

  /**
   * B.3 选配产品确认回调 — 走标准 enrichment 路径.
   *
   * 流程:
   * 1. 把后端 minimal DTO 映射为完整 LineItem (与 applyQuotationData 同款 mapping, 但选配场景客户料号
   *    映射 / hfPartInfo 都暂为空, 后续刷新走 GET 后会从 DB join 反查回来)
   * 2. 函数式 setLineItems 追加新行 (防 AP-9 race: 用户切走 / 添加其他产品时不丢)
   * 3. 异步 enrichComponentData + loadProductAttributes 拉模板 schema, 合回 state 时按 id 匹配,
   *    用户已开始编辑的字段保留 (与 applyQuotationData 的 enrich 合并策略一致)
   *
   * 关键: 选配 line_item 落库时后端已经把 template_id + product_attribute_values 默认值写好,
   * 前端 enrichment 不再依赖 quotation.customer_template_id 全局兜底.
   */
  const onConfigureConfirm = async (rawItems: any[]) => {
    setConfigureDrawerOpen(false);
    if (!rawItems || rawItems.length === 0) return;
    // V202+ (2026-05-19): 配置流程在后端写过 mat_process / mat_bom, 但 cache 里可能有
    // 配置前的"0 行/旧值"陈旧 expansion → componentHasData 误判隐藏 Tab.
    // 先把涉及的 partNos 从 cache 清掉, fingerprint 下一轮重 fetch.
    const affectedPartNos = rawItems
      .map((li: any) => li.productPartNo)
      .filter((x: any): x is string => typeof x === 'string' && x.length > 0);
    if (affectedPartNos.length > 0) {
      invalidateDriverExpansions(affectedPartNos);
    }
    const basicItems: LineItem[] = rawItems.map((li: any) => {
      const rawAttrs = li.productAttributeValues
        ? (typeof li.productAttributeValues === 'string'
            ? (() => { try { return JSON.parse(li.productAttributeValues); } catch { return {}; } })()
            : li.productAttributeValues)
        : {};
      return ({
        id: li.id,
        productId: li.productId || '',
        productName: li.productName || '',
        productPartNo: li.productPartNo || '',
        customerPartNo: li.customerPartNo || '',
        customerPartName: li.customerPartName || '',
        customerProductNo: li.customerProductNo || '',
        customerDrawingNo: li.customerDrawingNo || '',
        hfPartInfo: li.hfPartInfo || undefined,
        templateId: li.templateId || customerTemplateId || '',
        templateName: li.templateName || '',
        productAttributeValues: rawAttrs,
        componentData: li.componentData || [],
        subtotal: li.subtotal || 0,
        partVersionLocked: li.partVersionLocked,
        // 后端 buildLineItemDTO 已经透传 compositeType('SIMPLE'/'COMPOSITE'/'PART'),
        // 选配场景下父级=COMPOSITE,子配件=PART,SIMPLE 产品=SIMPLE.
        // ProductCard 按 productType 渲染 Tab 时, 把 compositeType 折算到 productType:
        //   COMPOSITE 父级 → productType=COMPOSITE
        //   其余(PART / SIMPLE)→ productType=SIMPLE
        productType: li.compositeType === 'COMPOSITE' ? 'COMPOSITE' : 'SIMPLE',
        // 保留 compositeType + parentLineItemId 让渲染层 filter PART 子卡片 (A 修复)
        compositeType: li.compositeType,
        parentLineItemId: li.parentLineItemId,
        // 选配工序回传:后端 buildLineItemDTO 透传 processIds,带到 lineItem 上,
        // 使 saveDraft 能回写 quotation_line_process(选配工序跨保存存活)。
        processIds: Array.isArray(li.processIds) ? li.processIds : [],
        // 选配-组合工艺 per-quote:从 configure 响应带回步骤,供 saveDraft 透传存活
        compositeProcesses: Array.isArray(li.compositeProcesses) ? li.compositeProcesses : [],
      }) as LineItem;
    });
    // 1. 立即追加新行 (函数式, 防 race) —— 用户确认选配 = 真实编辑,放行 autosave
    setLineItemsByUser(prev => [...prev, ...basicItems]);
    // 2. 异步拉模板 schema (componentData + productAttributes), 合回 state
    const enriched = await Promise.all(basicItems.map(async (li) => {
      if (!li.templateId) return li;
      const [enrichedCompData, productAttributes] = await Promise.all([
        enrichComponentData(li.templateId, li.componentData),
        loadProductAttributes(li.templateId),
      ]);
      return { ...li, componentData: enrichedCompData, productAttributes };
    }));
    // 2.5 同 loadQuotation: enrich 完成后 invalidate driver expansion cache.
    // 初次 fetch 时 comp.fields 没 enrich → 缓存了"错的"数据 (mat_process.* keys + 缺 @gvar).
    // 不 invalidate, fingerprint 仅基于 cids 不变化, 不会重 fetch, UI 永久"加载中"
    if (affectedPartNos.length > 0) {
      invalidateDriverExpansions(affectedPartNos);
    }
    // 3. 按 id 匹配回灌 (用户已开始编辑的不动 — 但选配新行 id 唯一不冲突, 简单 replace 即可)
    //    仍属用户选配流程的产物,放行 autosave(保证选配新行 enrich 后的结构被持久化)。
    setLineItemsByUser(prev => prev.map(cur => {
      const updated = enriched.find(e => e.id && e.id === cur.id);
      if (!updated) return cur;
      return {
        ...cur,
        componentData: updated.componentData,
        productAttributes: updated.productAttributes,
      };
    }));
  };

  const next = () => {
    if (currentStep === 0 && !quotationId) {
      handleCreateQuotation().then(() => {
        setCurrentStep(prev => prev + 1);
      });
      return;
    }
    // Fire-and-forget: save draft silently in background
    if (quotationId) {
      handleSaveDraft(true);
    }
    setCurrentStep(prev => Math.min(prev + 1, 4));
  };

  const prev = () => {
    if (quotationId) {
      handleSaveDraft(true);
    }
    setCurrentStep(prev => Math.max(prev - 1, 0));
  };

  // --- Step renderers ---

  const renderStep1 = () => (
    <div>
      <Row gutter={24}>
        <Col span={16}>
          <Card title="报价单基本信息" size="small">
            <Form.Item name="customerId" label="客户" rules={[{ required: true, message: '请选择客户' }]}>
              <Select
                showSearch
                placeholder="搜索并选择客户"
                filterOption={false}
                onSearch={searchCustomers}
                onChange={handleCustomerSelect}
                options={customers.map(c => ({ label: `${c.name} (${c.code || ''})`, value: c.id }))}
                // 2026-05-18: 报价单一旦生成 (quotationId 非空), 客户绑定不可改 — 与 lineItems / 模板锁定一致, 避免客户错位.
                // 基础数据导入流程同样不允许换客户 (导入抽屉已选定).
                disabled={isImportFlow || !!quotationId}
              />
            </Form.Item>
            {/* 未选客户时显示基础名称输入框；选客户后由 QuotationCreateForm 接管名称字段 */}
            {!selectedCustomer && (
              <Form.Item name="name" label="报价单名称" rules={[{ required: true, message: '请输入名称' }]}>
                <Input placeholder="输入报价单名称" />
              </Form.Item>
            )}
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item name="projectName" label="项目名称">
                  <Input placeholder="关联项目" />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="opportunityId" label="商机编号">
                  <Input placeholder="商机ID" />
                </Form.Item>
              </Col>
            </Row>
            <Row gutter={16}>
              <Col span={8}>
                <Form.Item name="quoteType" label="报价类型">
                  <Select options={[
                    { label: '标准报价', value: 'STANDARD' },
                    { label: '折扣报价', value: 'DISCOUNT' },
                    { label: '批量报价', value: 'BULK' },
                  ]} />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="priority" label="优先级">
                  <Select options={[
                    { label: '高', value: 'HIGH' },
                    { label: '中', value: 'MEDIUM' },
                    { label: '低', value: 'LOW' },
                  ]} />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="stage" label="阶段">
                  <Select options={[
                    { label: '初步接洽', value: 'INITIAL_CONTACT' },
                    { label: '需求确认', value: 'REQUIREMENT_CONFIRMATION' },
                    { label: '报价中', value: 'QUOTING' },
                    { label: '谈判中', value: 'NEGOTIATION' },
                  ]} />
                </Form.Item>
              </Col>
            </Row>
            <Form.Item name="expectedCloseDate" label="预计成交日">
              <DatePicker style={{ width: '100%' }} />
            </Form.Item>
          </Card>

          <Card title="联系人" size="small" style={{ marginTop: 16 }}>
            <Form.Item name="contactId" label="选择联系人">
              <Select
                allowClear
                placeholder="选择联系人"
                options={contacts.map(c => ({
                  label: `${c.name} - ${c.phone}${c.isPrimary ? ' (主要)' : ''}`,
                  value: c.id,
                }))}
                onChange={(val) => {
                  const contact = contacts.find(c => c.id === val);
                  if (contact) {
                    form.setFieldsValue({
                      contactName: contact.name,
                      contactPhone: contact.phone,
                      contactEmail: contact.email,
                    });
                  }
                }}
              />
            </Form.Item>
            <Row gutter={16}>
              <Col span={8}>
                <Form.Item name="contactName" label="联系人姓名">
                  <Input />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="contactPhone" label="电话">
                  <Input />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="contactEmail" label="邮箱">
                  <Input />
                </Form.Item>
              </Col>
            </Row>
          </Card>
        </Col>

        <Col span={8}>
          {selectedCustomer && (
            <Card title="客户概况" size="small">
              <Descriptions column={1} size="small">
                <Descriptions.Item label="名称">{selectedCustomer.name}</Descriptions.Item>
                <Descriptions.Item label="编码">{selectedCustomer.code}</Descriptions.Item>
                <Descriptions.Item label="等级">
                  <Tag>{selectedCustomer.level}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label="行业">{selectedCustomer.industry || '-'}</Descriptions.Item>
                <Descriptions.Item label="区域">{selectedCustomer.region || '-'}</Descriptions.Item>
                <Descriptions.Item label="累计金额">
                  {selectedCustomer.accumulatedAmount != null
                    ? `¥${Number(selectedCustomer.accumulatedAmount).toLocaleString()}`
                    : '-'}
                </Descriptions.Item>
              </Descriptions>
            </Card>
          )}
        </Col>
      </Row>

      {/* T35 + A 视觉: 选客户后渲染独立「选择模板」Card —— 模板提供"选配产品"的卡片渲染结构.
          入口 B (基础数据导入流程) 时, 模板已经在导入抽屉里选定, 加 Alert 提示用户无需重复. */}
      {selectedCustomer && (
        <Card
          title={
            <span>
              选择模板 <Text type="secondary" style={{ fontSize: 13, fontWeight: 'normal', marginLeft: 8 }}>
                决定产品卡片 / Excel 视图的结构,以及"选配添加"使用的组件集
              </Text>
            </span>
          }
          size="small"
          style={{ marginTop: 16 }}
        >
          {isImportFlow && (
            <Alert
              type="success"
              showIcon
              message="✓ 模板已在基础数据导入时选定,可直接进入下一步"
              style={{ marginBottom: 12 }}
            />
          )}
          <QuotationCreateForm
            customerId={selectedCustomer.id}
            customerName={selectedCustomer.name}
            value={step1FormValue}
            onChange={(v: QuotationFormValue) => {
              // 1. React state 是受控组件的唯一真相源 - 必须先 setState 才能让 input 显示新值
              setStep1FormValue(v);
              // 2. 同步到 antd form (用于 Step5 submit 时 form.validateFields / form.getFieldsValue)
              form.setFieldsValue({
                name: v.name,
                categoryId: v.categoryId,
                customerTemplateId: v.customerTemplateId,
                costingCardTemplateId: v.costingTemplateId,
              });
              // 3. 同步到 wizard 层的 customerTemplateId / costingCardTemplateId state，
              //    让 Step2 选配 Dropdown / buildDraftPayload 能拿到最新模板 ID
              if (v.customerTemplateId !== undefined) setCustomerTemplateId(v.customerTemplateId);
              if (v.costingTemplateId !== undefined) setCostingCardTemplateId(v.costingTemplateId);
            }}
            onValidityChange={setStep1Valid}
            // 2026-05-18: 报价单已生成 (quotationId 非空) 后, 产品分类 / 报价模板 / 核价模板 不可改.
            // 联系人由父组件 (本 Wizard) 管理, 仍可改.
            readOnly={!!quotationId}
          />
        </Card>
      )}
    </div>
  );

  const renderStep2 = () => (
    <div>
      <QuotationStep2
        lineItems={lineItems}
        onAddProduct={() => setAddProductModalOpen(true)}
        onAddConfigured={() => setConfigureDrawerOpen(true)}
        onAddBatch={(items) => setLineItemsByUser((prev) => {
          // 去重:同一 productPartNo 只保留一份(以现有为准,新增的覆盖不了)
          const existing = new Set(prev.map((p) => p.productPartNo).filter(Boolean));
          const newItems = items.filter((it) => !it.productPartNo || !existing.has(it.productPartNo));
          return [...prev, ...newItems];
        })}
        onRemoveProduct={handleRemoveLineItem}
        onUpdateLineItem={handleUpdateLineItem}
        customerId={form.getFieldValue('customerId') || selectedCustomer?.id}
        quotationId={quotationId || undefined}
        customerTemplateId={customerTemplateId}
        costingCardTemplateId={costingCardTemplateId}
        driftDetection={(quotation?.driftDetection as DriftDetectionResult) || undefined}
        onRefreshQuotation={handleRefreshDrift}
        onReloadQuotation={() => loadQuotation(quotationId!)}
        quotationStatus={quotation?.status}
        quoteCardStructure={quotation?.quoteCardStructure ?? null}
        costingCardStructure={quotation?.costingCardStructure ?? null}
        locateTarget={locateTarget}
      />

      <AddProductModal
        open={addProductModalOpen}
        quotationId={quotationId || undefined}
        customerTemplateId={customerTemplateId}
        onCancel={() => setAddProductModalOpen(false)}
        onConfirm={(newItems) => {
          // F4：从已有产品添加改为批量多选，去重规则对齐 onAddBatch（同 productPartNo 只保留一份，以现有为准）。
          setLineItemsByUser((prev) => {
            const existing = new Set(prev.map((p) => p.productPartNo).filter(Boolean));
            const deduped = newItems.filter((it) => !it.productPartNo || !existing.has(it.productPartNo));
            return [...prev, ...deduped];
          });
          setAddProductModalOpen(false);
        }}
      />

      <ConfigureProductDrawer
        open={configureDrawerOpen}
        quotationId={quotationId || ''}
        customerNo={selectedCustomer?.code}
        onCancel={() => setConfigureDrawerOpen(false)}
        onConfirm={onConfigureConfirm}
      />
    </div>
  );

  const renderStep3 = () => (
    <QuotationStep3
      quotationId={quotationId || undefined}
      lineItems={lineItems}
      baseCurrency={quotation?.baseCurrency || 'CNY'}
      driverExpansions={driverExpansions}
      customerId={customerIdValue}
      onUpdate={(updater) => setLineItemsByUser(prev => updater(prev))}
    />
  );

  const renderStep4 = () => (
    <Card title="交易条款" size="small">
      <Form.Item name="paymentTerms" label="付款条件">
        <TextArea rows={4} placeholder="例如：预付30%，交付后30天付清余款" />
      </Form.Item>
      <Form.Item name="deliveryCycle" label="交货周期 (天)">
        <InputNumber min={1} style={{ width: 200 }} placeholder="天数" />
      </Form.Item>
      <Form.Item
        name="expiryDate"
        label="报价有效期"
        initialValue={dayjs().add(30, 'day')}
      >
        <DatePicker style={{ width: 200 }} />
      </Form.Item>
      <Form.Item name="remarks" label="备注">
        <TextArea rows={3} placeholder="输入备注信息" />
      </Form.Item>
    </Card>
  );

  const renderStep5 = () => {
    const originalAmount = lineItems.reduce((sum, li) => sum + computeProductSubtotal(li, driverExpansions, customerIdValue), 0);
    const discountRate = form.getFieldValue('finalDiscountRate') || quotation?.finalDiscountRate || 100;
    const totalAmount = originalAmount * discountRate / 100;
    const isDraft = !quotation || quotation.status === 'DRAFT';

    return (
      <div>
        <Card title="报价单总览" size="small">
          <Descriptions column={2} bordered size="small">
            <Descriptions.Item label="报价单号">
              {quotation?.quotationNumber || '-'}
            </Descriptions.Item>
            <Descriptions.Item label="状态">
              <Tag color={statusMap[quotation?.status]?.color || 'default'}>
                {statusMap[quotation?.status]?.label || quotation?.status || '-'}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="报价单名称">{form.getFieldValue('name')}</Descriptions.Item>
            <Descriptions.Item label="客户">{selectedCustomer?.name || quotation?.snapshotCustomerName || '-'}</Descriptions.Item>
            <Descriptions.Item label="项目名称">{form.getFieldValue('projectName') || '-'}</Descriptions.Item>
            <Descriptions.Item label="联系人">{form.getFieldValue('contactName') || '-'}</Descriptions.Item>
            <Descriptions.Item label="报价类型">{form.getFieldValue('quoteType') || '-'}</Descriptions.Item>
            <Descriptions.Item label="优先级">{form.getFieldValue('priority') || '-'}</Descriptions.Item>
          </Descriptions>
        </Card>

        <Card title="产品明细" size="small" style={{ marginTop: 16 }}>
          <Table
            rowKey="key"
            dataSource={lineItems}
            pagination={false}
            size="small"
            columns={[
              { title: '产品名称', dataIndex: 'productName' },
              { title: '产品料号', dataIndex: 'productPartNo' },
              { title: '小计', dataIndex: 'subtotal', render: (v: number) => `¥${(v || 0).toLocaleString()}` },
            ]}
            summary={() => (
              <Table.Summary>
                <Table.Summary.Row>
                  <Table.Summary.Cell index={0} colSpan={2}><Text strong>合计</Text></Table.Summary.Cell>
                  <Table.Summary.Cell index={1}>
                    <Text strong>¥{originalAmount.toLocaleString()}</Text>
                  </Table.Summary.Cell>
                </Table.Summary.Row>
              </Table.Summary>
            )}
          />
        </Card>

        <Card title="定价" size="small" style={{ marginTop: 16 }}>
          <Descriptions column={2} bordered size="small">
            <Descriptions.Item label="原始总金额">¥{originalAmount.toLocaleString()}</Descriptions.Item>
            <Descriptions.Item label="折扣率">{discountRate}%</Descriptions.Item>
            <Descriptions.Item label="最终总金额">
              <Text strong style={{ color: '#1890ff', fontSize: 16 }}>¥{totalAmount.toLocaleString()}</Text>
            </Descriptions.Item>
          </Descriptions>
        </Card>

        <Card title="交易条款" size="small" style={{ marginTop: 16 }}>
          <Descriptions column={2} bordered size="small">
            <Descriptions.Item label="付款条件">{form.getFieldValue('paymentTerms') || '-'}</Descriptions.Item>
            <Descriptions.Item label="交货周期">{form.getFieldValue('deliveryCycle') ? `${form.getFieldValue('deliveryCycle')} 天` : '-'}</Descriptions.Item>
            <Descriptions.Item label="到期日">{quotation?.expiryDate || '-'}</Descriptions.Item>
          </Descriptions>
        </Card>

        {isDraft && (
          <div style={{ marginTop: 24, textAlign: 'center' }}>
            <Button
              type="primary"
              size="large"
              icon={<SendOutlined />}
              onClick={handleSubmit}
            >
              提交审批
            </Button>
          </div>
        )}
      </div>
    );
  };

  const steps = [
    { title: '选择客户', content: renderStep1 },
    { title: '添加产品', content: renderStep2 },
    { title: '优惠策略', content: renderStep3 },
    { title: '交易条款', content: renderStep4 },
    { title: '提交审批', content: renderStep5 },
  ];

  return (
    <Spin spinning={loading}>
      <Card
        title={
          <Space>
            <span>{id ? '编辑报价单' : '新建报价单'}</span>
            {quotation && (
              <Tag color={statusMap[quotation.status]?.color || 'default'}>
                {quotation.quotationNumber} - {statusMap[quotation.status]?.label || quotation.status}
              </Tag>
            )}
          </Space>
        }
        extra={
          <Space>
            {quotationId && (
              <Button icon={<SaveOutlined />} loading={saving} disabled={saving} onClick={() => handleSaveDraft()}>
                保存草稿
              </Button>
            )}
            <Button onClick={() => navigate('/quotations')}>返回列表</Button>
          </Space>
        }
      >
        <Steps
          current={currentStep}
          style={{ marginBottom: 32 }}
          items={steps.map(s => ({ title: s.title }))}
        />

        <Form
          form={form}
          layout="vertical"
          onValuesChange={() => scheduleAutoSave()}
          initialValues={{
            quoteType: 'STANDARD',
            priority: 'MEDIUM',
            stage: 'INITIAL_CONTACT',
            finalDiscountRate: 100,
          }}
        >
          <div style={{ minHeight: 400 }}>
            {steps[currentStep].content()}
          </div>
        </Form>

        <Divider />

        <div style={{ display: 'flex', justifyContent: 'space-between' }}>
          <Button disabled={currentStep === 0 || saving} onClick={prev}>
            上一步
          </Button>
          <Button
            type="primary"
            loading={saving}
            onClick={next}
            disabled={saving || currentStep === steps.length - 1 || (currentStep === 0 && !!selectedCustomer && !step1Valid)}
            title={currentStep === 0 && !!selectedCustomer && !step1Valid ? '请先填写产品分类和报价模板' : undefined}
          >
            下一步
          </Button>
        </div>
      </Card>
      <RowKeyConflictDrawer
        open={conflictDrawerOpen}
        conflicts={rowKeyConflicts}
        onLocate={handleLocateConflict}
        onClose={() => setConflictDrawerOpen(false)}
      />
    </Spin>
  );
};

export default QuotationWizard;
