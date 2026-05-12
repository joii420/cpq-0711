import React, { useEffect, useState, useCallback, useRef } from 'react';
import {
  Steps, Button, Card, Form, Input, Select, DatePicker, InputNumber,
  Space, Table, message, Descriptions, Tag, Divider, Row, Col,
  Typography, Spin,
} from 'antd';
import {
  SaveOutlined, SendOutlined,
} from '@ant-design/icons';
import { useParams, useNavigate } from 'react-router-dom';
import dayjs from 'dayjs';
import { quotationService } from '../../services/quotationService';
import { quotationDriftService } from '../../services/quotationDriftService';
import { quotationSnapshotService } from '../../services/quotationSnapshotService';
import { customerService } from '../../services/customerService';
import QuotationStep2, { computeProductSubtotal, computeAllFormulas } from './QuotationStep2';
import type { DriftDetectionResult } from '../../types/quotation-drift';
import type { LineItem, ComponentDataItem, ComponentField, ComponentFormula } from './QuotationStep2';
import { useDriverExpansions, driverExpansionKey, bnfDriverLookupKey } from './useDriverExpansions';
import AddProductModal from './AddProductModal';
import { templateService } from '../../services/templateService';
import { buildLineItemFromTemplate } from './BulkImportPartsDrawer';

// antd 6.x: Steps uses `items` prop, not <Step> children
const { TextArea } = Input;
const { Text, Title } = Typography;

const statusMap: Record<string, { label: string; color: string }> = {
  DRAFT: { label: '草稿', color: 'default' },
  SUBMITTED: { label: '已提交', color: 'processing' },
  APPROVED: { label: '已批准', color: 'success' },
  REJECTED: { label: '已驳回', color: 'error' },
};

function parseJson<T>(value: T | string | null | undefined, fallback: T): T {
  if (value == null) return fallback;
  if (typeof value === 'string') {
    try { return JSON.parse(value) as T; } catch { return fallback; }
  }
  return value;
}

function normalizeFieldType(raw: string):
  'FIXED_VALUE' | 'DATA_SOURCE' | 'INPUT' | 'INPUT_TEXT' | 'INPUT_NUMBER' | 'FORMULA' | 'BASIC_DATA' {
  const t = (raw || '').toUpperCase();
  if (t === 'FORMULA') return 'FORMULA';
  if (t === 'FIXED_VALUE' || t === 'FIXED') return 'FIXED_VALUE';
  if (t === 'DATA_SOURCE') return 'DATA_SOURCE';
  // BASIC_DATA / INPUT_TEXT / INPUT_NUMBER 漏一个都会让对应字段被误归为 INPUT，
  // 触发渲染分支用 <input> 而不是 BASIC_DATA 的只读 span（典型表现：物料/元素/含量/
  // 材料损耗 4 列空白输入框）。务必跟 BulkImportPartsDrawer.normalizeFieldType 完全对齐。
  if (t === 'BASIC_DATA') return 'BASIC_DATA';
  if (t === 'INPUT_TEXT') return 'INPUT_TEXT';
  if (t === 'INPUT_NUMBER') return 'INPUT_NUMBER';
  return 'INPUT';
}

/** Enrich saved componentData with fields/formulas from template snapshot */
async function enrichComponentData(
  templateId: string,
  savedCompData: any[],
): Promise<ComponentDataItem[]> {
  if (!templateId || savedCompData.length === 0) return savedCompData;
  // 先把 rowData 字符串解析成 rows，作为最低保障——即使后面 templateSnapshot
  // 拉取失败 / 匹配不到，至少 input 单元格能从 row[key] 拿到值，不会出现
  // "列表行数在但所有单元格空白" 的情况（Bug E）。
  const withRows = savedCompData.map((saved: any) => {
    if (Array.isArray(saved.rows)) return saved;
    const parsed = parseJson<any[]>(saved.rowData, []);
    return { ...saved, rows: Array.isArray(parsed) ? parsed : [] };
  });
  try {
    const res = await templateService.getById(templateId);
    const tmpl = res.data;
    const snapshot: any[] = parseJson(tmpl.componentsSnapshot, []);

    return withRows.map((saved: any) => {
      // Match saved comp to snapshot by componentId or tabName
      const snapshotComp = snapshot.find(
        (sc: any) => (sc.componentId || sc.component_id) === saved.componentId
      ) || snapshot.find(
        (sc: any) => (sc.tabName || sc.tab_name) === saved.tabName
      );

      if (!snapshotComp) return saved; // Can't enrich, return with rows already filled

      const fields: ComponentField[] = (snapshotComp.fields || []).map((f: any) => ({
        name: f.name || f.key || '',
        field_type: normalizeFieldType(f.field_type || f.type || ''),
        content: f.content,
        is_amount: f.is_amount,
        is_subtotal: f.is_subtotal,
        is_required: f.is_required,
        formula_name: f.formula_name,
        datasource_binding: f.datasource_binding,
        // BASIC_DATA 字段必须带上 basic_data_path —— 渲染分支 / driver 展开 lookup
        // 都靠它，缺了 BASIC_DATA 直接显示"未配置路径"。
        basic_data_path: f.basic_data_path,
        sort_order: f.sort_order,
        label: f.label || f.name || '',
        key: f.name || f.key || '',
      }));

      const formulas: ComponentFormula[] = (snapshotComp.formulas || []).map((fm: any) => ({
        name: fm.name || '',
        expression: Array.isArray(fm.expression) ? fm.expression : [],
        result_type: fm.result_type,
      }));

      // Merge: keep saved row data, enrich with fields/formulas from snapshot
      // rowData from backend is a JSON string; rows from fresh add is an array
      // withRows 已保证 saved.rows 存在；空数组时退回 [{}] 至少给一行让 UI 不空白
      const savedRows = Array.isArray(saved.rows) ? saved.rows : [];
      const rows: Record<string, any>[] = savedRows.length > 0 ? savedRows : [{}];

      // 从模板快照补回 componentType 和 dataDriverPath —— 后端 ComponentDataDTO 不保存它们，
      // 不补的话刷新后小计组件会被当成普通 tab 渲染、driver 展开也无法触发（AP-2 续）。
      const componentType = saved.componentType
        || snapshotComp.component_type
        || snapshotComp.componentType
        || 'NORMAL';
      const dataDriverPath = saved.dataDriverPath
        || snapshotComp.data_driver_path
        || snapshotComp.dataDriverPath
        || undefined;

      return {
        componentId: saved.componentId || snapshotComp.componentId || '',
        componentCode: saved.componentCode || snapshotComp.componentCode || '',
        componentType,
        tabName: saved.tabName || snapshotComp.tabName || '',
        fields,
        formulas,
        rows,
        subtotal: saved.subtotal || 0,
        dataDriverPath,
      } as ComponentDataItem;
    });
  } catch {
    // 模板拉取失败时也至少返回 withRows，保证 input 不会因为 rows=undefined 全空
    return withRows;
  }
}

/**
 * 从模板拉取 productAttributes schema（字段定义列表）。
 * LineItem.productAttributes 是 schema 而非值——后端 SaveDraftRequest 没有这个维度，
 * 刷新后必须从模板再拉一次回填，否则产品卡片"产品属性"区域整块空白（AP-2 续）。
 */
async function loadProductAttributes(templateId: string): Promise<NonNullable<LineItem['productAttributes']>> {
  if (!templateId) return [];
  try {
    const res = await templateService.getById(templateId);
    const tmpl = res.data;
    const productAttrs: any[] = parseJson(tmpl.productAttributes, []);
    return productAttrs.map((attr: any) => ({
      name: attr.name || attr.key || attr.fieldKey || '',
      field_type: attr.field_type || attr.fieldType || 'TEXT',
      required: !!attr.required,
      default_value: attr.default_value ?? attr.defaultValue ?? '',
      source: attr.source ?? '',
    }));
  } catch {
    return [];
  }
}

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
  // Driver 展开结果上提到 wizard 层，便于 buildDraftPayload 在保存前
  // 把 BASIC_DATA / FORMULA 计算结果快照写入 rowData（WYSIWYG）。
  const customerIdValue = (selectedCustomer?.id) || form.getFieldValue('customerId') || undefined;
  const driverExpansions = useDriverExpansions(lineItems, customerIdValue);

  // Auto-save timer
  const autoSaveRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const lastSaveRef = useRef<string>('');
  // setInterval 注册时只依赖 quotationId，会捕获首次渲染时的 autoSaveDraft 闭包，
  // 其中 lineItems 仍为初始 []。改用最新值 ref，让定时器始终调用最新版本的 autoSaveDraft，
  // 避免每 10 秒自动保存的负载里 lineItems 永远是空数组（页面刷新后表格全空的根因）。
  const autoSaveDraftRef = useRef<(() => Promise<void>) | undefined>(undefined);
  // 基础数据导入流程：autoPopulate 完成后立即触发一次保存草稿，把"已自动加入 N 个产品"
  // 持久化下来，避免用户刷新前丢数据。一次性，避免重复触发。
  const importAutoSavedRef = useRef(false);

  // Load existing quotation
  useEffect(() => {
    if (id) {
      loadQuotation(id);
    }
    return () => {
      if (autoSaveRef.current) clearInterval(autoSaveRef.current);
    };
  }, [id]);

  // Setup auto-save every 10 seconds
  useEffect(() => {
    if (quotationId) {
      autoSaveRef.current = setInterval(() => {
        autoSaveDraftRef.current?.();
      }, 10000);
      return () => {
        if (autoSaveRef.current) clearInterval(autoSaveRef.current);
      };
    }
  }, [quotationId]);

  // P2-7: Auto-calculate discount when entering step 3 (index 2)
  useEffect(() => {
    if (currentStep === 2 && quotationId) {
      handleCalculateDiscount();
    }
  }, [currentStep]);

  const applyQuotationData = (q: any) => {
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
        // 料号版本锁定 (后端 DTO 已带, 用于产品卡片版本 Tag 显示)
        partVersionLocked: li.partVersionLocked,
      }) as LineItem;
      });
      setLineItems(basicItems);

      // Async: enrich each lineItem's componentData with fields/formulas from template.
      // 关键：enrich 完成后必须用函数式 setState 合并到当前 state，而不是整体替换。
      // 否则 enrich 在 100~500ms 之间完成时，如果用户已经在产品卡上输入了内容，
      // 这一次 setLineItems(enrichedItems) 会把当前 state 整张盖掉 → 输入丢失。
      // 典型表现：先开始输入的产品卡数据全没，后输入的产品卡数据保住。
      // 同时：基础数据导入流程下，productAttributes schema 也是模板内的，
      // 后端 SaveDraftRequest 没有这维度——必须从模板再拉一次回填。
      Promise.all(
        basicItems.map(async (li) => {
          if (!li.templateId) return li;
          const needComponentEnrich = li.componentData.length > 0
              && !li.componentData.every((cd: any) => cd.fields && cd.fields.length > 0);
          const needProductAttrs = !li.productAttributes || li.productAttributes.length === 0;
          if (!needComponentEnrich && !needProductAttrs) return li;
          const [enrichedCompData, productAttributes] = await Promise.all([
            needComponentEnrich
              ? enrichComponentData(li.templateId, li.componentData)
              : Promise.resolve(li.componentData),
            needProductAttrs
              ? loadProductAttributes(li.templateId)
              : Promise.resolve(li.productAttributes!),
          ]);
          return { ...li, componentData: enrichedCompData, productAttributes };
        })
      ).then(enrichedItems => {
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
      applyQuotationData(res.data);
      // Update localStorage backup on successful load
      localStorage.setItem(`cpq-draft-${qId}`, JSON.stringify(res.data));
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
      setContacts(res.data || []);
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
    form.setFieldValue('customerId', custId);
    loadCustomerDetail(custId);
    loadContacts(custId);
  };

  const autoSaveDraft = useCallback(async () => {
    if (!quotationId) return;
    try {
      const values = form.getFieldsValue();
      const payload = buildDraftPayload(values);
      const payloadStr = JSON.stringify(payload);
      if (payloadStr === lastSaveRef.current) return;
      lastSaveRef.current = payloadStr;
      await quotationService.saveDraft(quotationId, payload);
      // P2-9: backup to localStorage on success
      localStorage.setItem(`cpq-draft-${quotationId}`, JSON.stringify(payload));
    } catch {
      // P2-9: fallback to localStorage on failure
      try {
        const values = form.getFieldsValue();
        const payload = buildDraftPayload(values);
        localStorage.setItem(`cpq-draft-${quotationId}`, JSON.stringify(payload));
        message.warning('网络异常，已保存到本地缓存');
      } catch {
        // ignore
      }
    }
    // 把 driverExpansions / customerIdValue 列入 deps，让 buildDraftPayload → snapshotRows
    // 内部访问的是最新的 expansion 缓存。否则 useCallback 会缓存空 expansion 的旧闭包：
    // 导入流自动保存即便等到 expansion ready 才触发，autoSaveDraft 内部仍会读到空 driverExpansions
    // → snapshotRows 落 1 行而不是展开后的 N 行（明细页只看到 1 行 — 数据的根因）。
  }, [quotationId, form, lineItems, driverExpansions, customerIdValue]);

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
        expectedKeys.push(driverExpansionKey(li.productPartNo, comp.componentId, customerIdValue));
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
          templateService.getById(customerTemplateId),
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
      lineItems: lineItems.map((li, idx) => ({
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
        processIds: [],
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
    const expansionKey = (partNo && componentId)
      ? driverExpansionKey(partNo, componentId, customerIdValue)
      : '';
    const expansion = expansionKey ? driverExpansions[expansionKey] : undefined;

    // 构建本 line item 的 component subtotals（公式引擎需要）
    const componentSubtotals: Record<string, number> = {};
    (li.componentData || []).forEach(c => {
      if (c.tabName) componentSubtotals[c.tabName] = c.subtotal || 0;
    });

    const baseRows = Array.isArray(cd.rows) ? cd.rows : [];
    // driver 展开模式：UI 行数 = expansion.rowCount，可能 > baseRows.length
    const rowCount = expansion && expansion.rowCount > 0
      ? Math.max(expansion.rowCount, baseRows.length)
      : baseRows.length;

    const out: Record<string, any>[] = [];
    for (let i = 0; i < rowCount; i++) {
      const baseRow = baseRows[i] || {};
      const expansionRow = expansion?.rows?.[i];
      const basicDataValues = expansionRow?.basicDataValues;

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

      // 2. compute FORMULA values via formula engine → row[key]
      try {
        const formulaCache = computeAllFormulas(
          cd, enriched, componentSubtotals,
          undefined, undefined, partNo, basicDataValues
        );
        for (const f of fields) {
          if (f.field_type !== 'FORMULA') continue;
          const fieldKey = f.name || f.key || '';
          if (!fieldKey) continue;
          if (formulaCache[fieldKey] != null) {
            enriched[fieldKey] = formulaCache[fieldKey];
          }
        }
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
      setQuotation(res.data);
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
    try {
      const values = form.getFieldsValue();
      const payload = buildDraftPayload(values);
      const res = await quotationService.saveDraft(quotationId, payload);
      setQuotation(res.data);
      if (!silent) message.success('草稿已保存');
      localStorage.setItem(`cpq-draft-${quotationId}`, JSON.stringify(payload));
    } catch (e: any) {
      try {
        const values2 = form.getFieldsValue();
        const payload2 = buildDraftPayload(values2);
        localStorage.setItem(`cpq-draft-${quotationId}`, JSON.stringify(payload2));
        if (!silent) message.warning('已保存到本地，网络恢复后将同步');
      } catch {
        if (!silent) message.error(e.message);
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
      message.error(e.message);
    }
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
      setQuotation(res.data);
      form.setFieldValue('finalDiscountRate', res.data.finalDiscountRate);
      message.success('折扣已计算');
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const handleRemoveLineItem = (index: number) => {
    setLineItems(prev => prev.filter((_, i) => i !== index));
  };

  const handleUpdateLineItem = (index: number, data: Partial<LineItem> | ((prev: LineItem) => Partial<LineItem>)) => {
    setLineItems(prev => prev.map((item, i) => {
      if (i !== index) return item;
      const patch = typeof data === 'function' ? data(item) : data;
      return { ...item, ...patch };
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
                // 基础数据导入流程：客户已经在导入抽屉里选定，编辑页不允许更换，避免 lineItems 与客户错位
                disabled={isImportFlow}
              />
            </Form.Item>
            <Form.Item name="name" label="报价单名称" rules={[{ required: true, message: '请输入名称' }]}>
              <Input placeholder="输入报价单名称" />
            </Form.Item>
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
    </div>
  );

  const renderStep2 = () => (
    <div>
      <QuotationStep2
        lineItems={lineItems}
        onAddProduct={() => setAddProductModalOpen(true)}
        onAddBatch={(items) => setLineItems((prev) => {
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
      />

      <AddProductModal
        open={addProductModalOpen}
        onCancel={() => setAddProductModalOpen(false)}
        onConfirm={(lineItem) => {
          setLineItems(prev => [...prev, lineItem]);
          setAddProductModalOpen(false);
        }}
      />
    </div>
  );

  const renderStep3 = () => {
    const originalAmount = lineItems.reduce((sum, li) => sum + computeProductSubtotal(li, driverExpansions, customerIdValue), 0);
    const discountRate = form.getFieldValue('finalDiscountRate') || quotation?.finalDiscountRate || 100;
    const totalAmount = originalAmount * discountRate / 100;

    return (
      <Card title="定价与折扣" size="small">
        <Descriptions column={2} bordered>
          <Descriptions.Item label="原始总金额">
            <Text strong>¥{originalAmount.toLocaleString()}</Text>
          </Descriptions.Item>
          <Descriptions.Item label="系统折扣率">
            {quotation?.systemDiscountRate || 100}%
          </Descriptions.Item>
          <Descriptions.Item label="最终总金额">
            <Text strong style={{ color: '#1890ff', fontSize: 18 }}>¥{totalAmount.toLocaleString()}</Text>
          </Descriptions.Item>
          <Descriptions.Item label="产品行数">{lineItems.length}</Descriptions.Item>
        </Descriptions>

        <Divider />

        <Space direction="vertical" style={{ width: '100%' }}>
          <Button onClick={handleCalculateDiscount}>自动计算折扣</Button>

          <Form.Item name="finalDiscountRate" label="手动折扣率 (%)">
            <InputNumber min={0} max={100} precision={2} style={{ width: 200 }} />
          </Form.Item>

          <Form.Item name="discountAdjustmentReason" label="折扣调整原因">
            <TextArea rows={3} placeholder="输入手动调整折扣的原因" />
          </Form.Item>
        </Space>
      </Card>
    );
  };

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
              <Button icon={<SaveOutlined />} onClick={() => handleSaveDraft()}>
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
          <Button disabled={currentStep === 0} onClick={prev}>
            上一步
          </Button>
          <Button type="primary" onClick={next} disabled={currentStep === steps.length - 1}>
            下一步
          </Button>
        </div>
      </Card>
    </Spin>
  );
};

export default QuotationWizard;
