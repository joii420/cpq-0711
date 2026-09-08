// ─────────────────────────────────────────────────────────────────────────────
// QuotationDatasetImportDrawer —— 报价数据「导入即建单」抽屉（task-260907 · F-3 ~ F-8）
//
// 视觉基准：`dev-docs/task-260907-报价导入建单切ds新表/原型图/02~07`。
// 接口契约：同目录 `api.md` §1 / §2 / §3。
//
// 🚨 三条不可越界（fronttask.md §0）：
//   1. `master-data/dataset/DatasetImportDrawer.tsx` **一个字节不改** —— 它被【基础资料维护】
//      的 `DatasetPartListTab.tsx` 共用。本抽屉是**新建**的建单专用件，🚫 不在共用件上加
//      `mode` / `requireCustomer` 开关（那等于把两条路径的演进绑死）。
//   2. 不改 `QuotationStep2.tsx` / `ReadonlyProductCard.tsx` / `useDriverExpansions.ts` ——
//      本任务不动渲染层。
//   3. 视觉基准是原型不是审美；偏差只允许「组件库能力所限的等价实现」。
//
// 状态机（与 V6 抽屉同构，D-15「业务流程逐行对齐」）：
//   Step1 选客户+上传 ──POST──▶ 轮询 ──┬─ SUCCESS ─▶ Step1 结果 ──▶ Step2 选模板
//                                      └─ FAILED  ─▶ 整份拒收错误清单（可重新上传）
//   Step2 建单 ──POST──▶ materializing=true ─▶ 轮询物化 ─▶ 进编辑页
// ─────────────────────────────────────────────────────────────────────────────
import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert, Button, Drawer, Descriptions, Empty, Form, Progress, Select, Space, Spin,
  Steps, Table, Tag, Tooltip, Typography, Upload, message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { UploadFile, UploadProps } from 'antd/es/upload/interface';
import { DeleteOutlined, FileExcelOutlined, InboxOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';

import api from '../../services/api';
import { customerService } from '../../services/customerService';
import { quotationService } from '../../services/quotationService';
import { templateService } from '../../services/templateService';
import {
  quoteDatasetImportService,
  type QuoteImportRecord,
  type QuoteImportSheetSummary,
  type QuoteImportValidationError,
} from '../../services/quoteDatasetImportService';
// 🚫 零改动复用：`GET /dataset/quote/customer-parts` 是 task-260903 已合 master 的**只读**端点
//    （api.md §5「零改动」同族）。Step 2 的「将建出的产品行」预览与 0 条空态判据都取自它，
//    本任务不新增读端点、不改它一个字节。
import { listCustomerParts, QUOTE_BASE_PATH } from '../product/productHubApi';
import type { CustomerPartItem } from '../product/productHubTypes';
import QuotationCreateForm, { type QuotationFormValue } from './QuotationCreateForm';
import { SHEET_HINT, UPLOAD_NEED_CUSTOMER_TIP } from './quoteDatasetImportConfig';

const { Dragger } = Upload;
const { Text } = Typography;


interface CustomerOption {
  id: string;
  name: string;
  code?: string;
  productCategoryId?: string;
}

interface Props {
  open: boolean;
  onClose: () => void;
  /** 从报价单列表传入的默认客户（可为空，让用户在 Step 1 自选）。 */
  defaultCustomerId?: string;
  /** 建单成功（用于列表刷新）。 */
  onCreated?: () => void;
}

function fmtSize(bytes?: number): string {
  if (bytes === undefined || bytes === null) return '';
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024).toFixed(1)} KB`;
}

/** 超长值统一截断 + `title` 悬停全文（AC-18② / 原型 07）。🚫 不许换行撑高、不许横向滚出抽屉。 */
const Ell: React.FC<{ text?: string | null; max?: number; mono?: boolean }> = ({ text, max = 300, mono }) => {
  const v = text ?? '';
  if (!v) return <Text type="secondary">—</Text>;
  return (
    <span
      title={v}
      style={{
        display: 'inline-block',
        maxWidth: max,
        overflow: 'hidden',
        textOverflow: 'ellipsis',
        whiteSpace: 'nowrap',
        verticalAlign: 'bottom',
        fontFamily: mono ? 'ui-monospace, SFMono-Regular, Menlo, monospace' : undefined,
      }}
    >
      {v}
    </span>
  );
};

const QuotationDatasetImportDrawer: React.FC<Props> = ({ open, onClose, defaultCustomerId, onCreated }) => {
  const navigate = useNavigate();

  // ── Step 1 ────────────────────────────────────────────────────────────────
  const [step, setStep] = useState<1 | 2>(1);
  const [customers, setCustomers] = useState<CustomerOption[]>([]);
  const [customersLoading, setCustomersLoading] = useState(false);
  const [customerId, setCustomerId] = useState<string | undefined>(defaultCustomerId);
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [importing, setImporting] = useState(false);
  const [importRecordId, setImportRecordId] = useState<string | null>(null);
  const [record, setRecord] = useState<QuoteImportRecord | null>(null);
  /** 轮询超时 / 网络异常等「没拿到终态」的原因（F-4 ④：必须有上限 + 可重试入口）。 */
  const [importWaitError, setImportWaitError] = useState<string | null>(null);
  const [importStartedAt, setImportStartedAt] = useState<number | null>(null);
  const [importElapsedMs, setImportElapsedMs] = useState<number | null>(null);

  // ── Step 2 ────────────────────────────────────────────────────────────────
  const [createForm, setCreateForm] = useState<QuotationFormValue>({
    name: '', categoryId: undefined, customerTemplateId: undefined, costingTemplateId: undefined,
  });
  const [formValid, setFormValid] = useState(false);
  const [autoHints, setAutoHints] = useState<{ customer?: string; costing?: string }>({});
  const [enteringStep2, setEnteringStep2] = useState(false);
  // 自动带出每次打开抽屉只跑一次：返回上一步再进 Step 2 时不覆盖用户手改的模板/分类（F-6 ②）
  const [autoFilled, setAutoFilled] = useState(false);
  const [candidates, setCandidates] = useState<{ total: number; items: CustomerPartItem[] } | null>(null);
  const [candidatesLoading, setCandidatesLoading] = useState(false);

  // ── 建单 + 物化 ───────────────────────────────────────────────────────────
  const [committing, setCommitting] = useState(false);
  const [created, setCreated] = useState<
    { quotationId: string; quotationNumber?: string; lineItemsCount: number } | null
  >(null);
  const [templateLabel, setTemplateLabel] = useState<string>('');
  const [materializing, setMaterializing] = useState(false);
  const [materializeStatus, setMaterializeStatus] = useState<{ ready: number; total: number } | null>(null);
  const [materializeError, setMaterializeError] = useState<string | null>(null);
  /**
   * 用户在轮询期间关闭抽屉（AC-5 / 原型 03 · 06 都明写「可以关闭本窗口」）：置位后轮询完成
   * 不再自动跳转，避免「已关闭却被跳走」。后台任务本身不受影响。
   */
  const pollAbortRef = useRef(false);

  const resetAll = () => {
    setStep(1);
    setCustomerId(defaultCustomerId);
    setFileList([]);
    setSubmitting(false);
    setImporting(false);
    setImportRecordId(null);
    setRecord(null);
    setImportWaitError(null);
    setImportStartedAt(null);
    setImportElapsedMs(null);
    setCreateForm({ name: '', categoryId: undefined, customerTemplateId: undefined, costingTemplateId: undefined });
    setFormValid(false);
    setAutoHints({});
    setAutoFilled(false);
    setCandidates(null);
    setCommitting(false);
    setCreated(null);
    setTemplateLabel('');
    setMaterializing(false);
    setMaterializeStatus(null);
    setMaterializeError(null);
    pollAbortRef.current = false;
  };

  /**
   * 是否有一次「还没走完」的会话在身上。
   * 🚨 有则**不能在重新打开时清空** —— 原型 03 明写「可以关闭本窗口…或重新打开本抽屉继续建单」，
   *    清空等于把后台还在跑的批次弄丢，用户只能去【导入历史】自己找。
   *    ⇒ 本组件**刻意不加 `destroyOnClose`**：关闭只是隐藏，轮询与状态都留着。
   */
  const hasLiveSession = !!importRecordId || !!created;

  useEffect(() => {
    if (!open) return;
    if (!hasLiveSession) resetAll();
    setCustomersLoading(true);
    customerService
      .list({ page: 0, size: 200 })
      .then((r: any) => {
        const list: any[] = r.data?.content ?? r.data ?? [];
        setCustomers(list.map((c: any) => ({
          id: c.id, name: c.name, code: c.code, productCategoryId: c.productCategoryId,
        })));
      })
      .catch(() => message.error('加载客户列表失败'))
      .finally(() => setCustomersLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, defaultCustomerId]);

  const currentCustomer = useMemo(
    () => customers.find((c) => c.id === customerId),
    [customers, customerId],
  );
  const customerName = currentCustomer?.name ?? '';
  const customerCode = currentCustomer?.code ?? '';
  const customerLabel = customerCode ? `${customerName}（${customerCode}）` : customerName;

  const picked = fileList[0] as unknown as (File & UploadFile) | undefined;
  const pickedName = picked?.name ?? '';

  const draggerProps: UploadProps = {
    name: 'file',
    multiple: false,
    maxCount: 1,
    accept: '.xlsx',
    fileList,
    showUploadList: false,
    disabled: !customerId || importing,
    beforeUpload: (file) => {
      setFileList([file as unknown as UploadFile]);
      setRecord(null);
      setImportWaitError(null);
      return false; // 交给「开始导入」手动提交
    },
    onRemove: () => setFileList([]),
  };

  // ── 轮询导入（F-4） ───────────────────────────────────────────────────────
  const runImportPoll = async (recordId: string, startedAt: number) => {
    setImporting(true);
    setImportWaitError(null);
    try {
      const r = await quoteDatasetImportService.pollImportRecord(recordId, {
        intervalMs: 1500,
        onTick: (rec) => setRecord(rec),
      });
      if (!r) return;
      setRecord(r);
      setImportElapsedMs(Date.now() - startedAt);
      if (r.status === 'SUCCESS') message.success(`导入成功，共 ${r.successRows ?? 0} 行写入`);
      else message.error(`导入校验未通过，共 ${r.errors?.length ?? 0} 处问题，本次未写入任何数据`);
    } catch (e: any) {
      // F-4 ④：有上限、超时给可重试入口，🚫 不许无限轮询转圈
      setImportWaitError(e?.message ?? '等待导入结果失败');
    } finally {
      setImporting(false);
    }
  };

  const handleUpload = async () => {
    if (!customerId) return message.warning(UPLOAD_NEED_CUSTOMER_TIP);
    if (!picked) return message.warning('请先上传 Excel 文件');
    setSubmitting(true);
    setRecord(null);
    setImportWaitError(null);
    setImportElapsedMs(null);
    const startedAt = Date.now();
    setImportStartedAt(startedAt);
    try {
      const file = ((picked as any).originFileObj ?? picked) as File;
      const accepted = await quoteDatasetImportService.importQuoteDataset(customerId, file);
      setImportRecordId(accepted.importRecordId);
      await runImportPoll(accepted.importRecordId, startedAt);
    } catch (e: any) {
      // 400（缺客户/缺文件/客户无 code）、403、404、500 —— 同步段就失败，没有 importRecordId
      message.error(e?.message ?? '导入失败');
    } finally {
      setSubmitting(false);
    }
  };

  /** 超时后的「继续等待」：不重新上传，只重进轮询（后台批次还在跑）。 */
  const handleContinueWait = () => {
    if (!importRecordId) return;
    runImportPoll(importRecordId, importStartedAt ?? Date.now());
  };

  /** 校验失败 → 重新上传：保留客户，清掉文件与结果，回到 Step 1 表单态。 */
  const handleReupload = () => {
    setFileList([]);
    setRecord(null);
    setImportRecordId(null);
    setImportWaitError(null);
    setImportElapsedMs(null);
  };

  // ── 进 Step 2（F-6） ──────────────────────────────────────────────────────
  const loadCandidates = async (code: string) => {
    setCandidatesLoading(true);
    try {
      const r = await listCustomerParts({ page: 0, size: 5, customerNo: code }, QUOTE_BASE_PATH);
      setCandidates({ total: r.total ?? 0, items: r.items ?? [] });
    } catch {
      // 端点异常时不伪造数据（🚫 不做 mock 兜底）：预览留空并给出说明，建单本身不受影响
      setCandidates(null);
    } finally {
      setCandidatesLoading(false);
    }
  };

  const enterStep2 = async () => {
    if (!customerId) return;
    if (customerCode) loadCandidates(customerCode);
    // 已自动带出过（用户可能已手改）→ 直接回 Step 2，不再覆盖（F-6 ②）
    if (autoFilled) { setStep(2); return; }
    setEnteringStep2(true);
    try {
      const resp: any = await api.get('/templates/auto-defaults', { params: { customerId } });
      const d = resp?.data ?? resp;
      setCreateForm((prev) => ({
        name: prev.name || `${customerName} 报价单`,
        categoryId: d?.categoryId ?? undefined,
        customerTemplateId: d?.customerTemplateId ?? undefined,
        costingTemplateId: d?.costingTemplateId ?? undefined,
      }));
      setAutoHints({
        customer: quoteHintOf(d?.customerTemplateSource, d?.customerTemplateVersion),
        costing: costingHintOf(d?.costingTemplateSource, d?.costingTemplateVersion),
      });
    } catch {
      setAutoHints({}); // 静默降级：不预填，走默认分类 + 手选
    } finally {
      setEnteringStep2(false);
      setAutoFilled(true);
      setStep(2);
    }
  };

  // ── 建单 + 物化轮询（F-7） ────────────────────────────────────────────────
  /**
   * 🚨 主循环用**只读**端点 `GET /quotations/{id}/materialize-status`（`pollMaterializeStatus`），
   *    不是反复 POST `ensure-card-values` —— 后者不是纯探针，抢到单飞锁会自己变成几十秒的计算工人，
   *    被 Reaper 杀掉会永久丢行（task-260825 实测丢过 345/1845 行）。自愈调用由
   *    `pollMaterializeStatus` 内部按 30s 节流触发一次，仍满足 api.md §5「ensure-card-values 复用」。
   *    只读端点同时给出 `ready/total`，才画得出原型 06 的「已完成 7 / 12」。
   */
  const runMaterializePoll = async (quotationId: string) => {
    setMaterializing(true);
    setMaterializeError(null);
    setMaterializeStatus(null);
    try {
      await quotationService.pollMaterializeStatus(quotationId, {
        intervalMs: 1500,
        onTick: (s) => setMaterializeStatus({ ready: s.ready, total: s.total }),
      });
      if (pollAbortRef.current) { setMaterializing(false); return; } // 用户已关抽屉，不自动跳转
      setMaterializing(false);
      goEdit(quotationId);
    } catch (e: any) {
      setMaterializing(false);
      if (pollAbortRef.current) return;
      setMaterializeError(e?.message || '卡片值计算失败，请重试');
    }
  };

  /**
   * 进编辑页。
   * 🚫 **不带 `?autoPopulate=1`**：那是 V6 导入流的标记，会让 `QuotationWizard` 在
   *    「明细行为 0」时回头调 `GET /quotations/customer-part-candidates`（**V6 的
   *    `material_customer_map` 候选**）自行建行 —— 那正是 AC-18 要求「明细行为 0」的场景，
   *    带上它会凭空造出 V6 侧的产品行，直接违反 AC-18。本链路的行由后端 create-quotation
   *    同事务建好，编辑页按普通草稿打开即可。
   */
  const goEdit = (quotationId: string) => {
    onCreated?.();
    resetAll();
    onClose();
    navigate(`/quotations/${quotationId}/edit`);
  };

  const handleCommit = async () => {
    if (!record?.importRecordId) return message.warning('请先完成 Step 1 导入');
    if (!customerId) return message.warning('客户信息丢失');
    if (!formValid) return message.warning('请填写报价单名称 + 选择报价模板');
    setCommitting(true);
    setMaterializeError(null);
    try {
      const r = await quoteDatasetImportService.createQuotation({
        importRecordId: record.importRecordId,
        customerId,
        name: createForm.name,
        categoryId: createForm.categoryId,
        customerTemplateId: createForm.customerTemplateId!,
        costingTemplateId: createForm.costingTemplateId,
      });
      pollAbortRef.current = false;
      setCreated({
        quotationId: r.quotationId,
        quotationNumber: (r as any).quotationNumber,
        lineItemsCount: r.lineItemsCount ?? 0,
      });
      // 报价模板名只为原型 06 的摘要行展示，取不到不影响主流程
      if (createForm.customerTemplateId) {
        templateService.getByIdCached(createForm.customerTemplateId)
          .then((res: any) => {
            const t = res?.data ?? res;
            if (t?.name) setTemplateLabel(`${t.name}${t.version ? ' ' + t.version : ''}`);
          })
          .catch(() => setTemplateLabel(''));
      }
      // materializing 是**唯一显式的「要去轮询」信号**（api.md §3）：
      // 🚫 不许靠 cardValuesReady==false 猜（分不清「真失败」和「还没开始算」）
      if (r.materializing) await runMaterializePoll(r.quotationId);
      else goEdit(r.quotationId);
    } catch (e: any) {
      message.error(e?.message ?? '建报价单失败');
    } finally {
      setCommitting(false);
    }
  };

  /** 重试：`quotationId` 已创建 ⇒ **不重新 POST create-quotation**，只重进轮询（原型 06）。 */
  const handleRetryMaterialize = () => {
    if (!created) return;
    pollAbortRef.current = false;
    runMaterializePoll(created.quotationId);
  };

  /** 关闭统一入口：轮询在飞时置位，防止关闭后轮询完成又把用户「跳走」。 */
  const handleDrawerClose = () => {
    if (materializing) pollAbortRef.current = true;
    onClose();
  };

  // ── 派生视图状态 ──────────────────────────────────────────────────────────
  const status = record?.status;
  const isFailed = status === 'FAILED';
  const isSuccess = status === 'SUCCESS';
  const inProgress = importing || (!!importRecordId && status === 'PROCESSING' && !importWaitError);
  const progress = record?.progress ?? null;
  const progressPct = progress && progress.total > 0
    ? Math.min(99, Math.round((progress.done / progress.total) * 100))
    : 0;
  const candidateCount = candidates?.total ?? 0;
  const isEmptyCandidates = !!candidates && candidateCount === 0;

  // 三段 Steps 状态：Step1 未完成 → 0；导入成功/Step2 → 1；建单完成（物化中/失败）→ 2
  const stepsCurrent = created ? 2 : (isSuccess || step === 2 ? 1 : 0);

  const sheetSummaryColumns: ColumnsType<QuoteImportSheetSummary> = [
    { title: 'Sheet', dataIndex: 'sheetName', key: 'sheetName', width: 180, ellipsis: { showTitle: true } },
    {
      title: '类型',
      key: 'kind',
      width: 100,
      render: (_: unknown, r) => (r.kind === 'VERSIONED'
        ? <Tag color="gold">带版本</Tag>
        : <Tag color="blue">免版本</Tag>),
    },
    {
      title: '结果',
      key: 'result',
      render: (_: unknown, r) => (r.kind === 'VERSIONED'
        ? `轴值 ${r.axisCount ?? 0} · 新建 ${r.created ?? 0} · 升版 ${r.upgraded ?? 0} · 未变 ${r.unchanged ?? 0}`
        : `新增 ${r.inserted ?? 0} · 更新 ${r.updated ?? 0}`),
    },
  ];

  const errorColumns: ColumnsType<QuoteImportValidationError> = [
    { title: 'Sheet', dataIndex: 'sheetName', key: 'sheetName', width: 132, ellipsis: { showTitle: true } },
    { title: '行号', dataIndex: 'rowNum', key: 'rowNum', width: 60 },
    { title: '列', dataIndex: 'columnLabel', key: 'columnLabel', width: 110, ellipsis: { showTitle: true } },
    {
      title: '值',
      dataIndex: 'value',
      key: 'value',
      width: 120,
      ellipsis: { showTitle: true },
      render: (v: unknown) => (v === undefined || v === null || v === ''
        ? <Text type="secondary">—</Text>
        : <span className="mono">{String(v)}</span>),
    },
    { title: '原因', dataIndex: 'reason', key: 'reason' },
  ];

  const candidateColumns: ColumnsType<CustomerPartItem> = [
    { title: '客户产品编号', dataIndex: 'customerProductNo', key: 'customerProductNo', width: 190, ellipsis: { showTitle: true } },
    {
      title: '客户料号名称',
      dataIndex: 'customerPartName',
      key: 'customerPartName',
      ellipsis: { showTitle: true },
      render: (v?: string | null) => (v ? v : <Text type="secondary">—</Text>),
    },
    { title: '销售料号', dataIndex: 'materialNo', key: 'materialNo', width: 170, ellipsis: { showTitle: true } },
    {
      title: '品名',
      dataIndex: 'materialName',
      key: 'materialName',
      width: 130,
      ellipsis: { showTitle: true },
      // 现网 ds_quote_material.material_name 大量为空 ⇒「—」是真实形态，不是缺陷（原型 05 注解）
      render: (v?: string | null) => (v ? v : <Text type="secondary">—</Text>),
    },
  ];

  // ── Footer（每个状态一套，逐屏对齐原型） ─────────────────────────────────
  const footer = (() => {
    if (created) {
      if (materializeError) {
        return (
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
            <Button onClick={() => goEdit(created.quotationId)}>进入编辑页</Button>
            <Button type="primary" onClick={handleRetryMaterialize}>重试计算</Button>
          </div>
        );
      }
      return (
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <Button onClick={handleDrawerClose}>关闭（后台继续）</Button>
          <Tooltip title={materializing ? '计算完成后可进入' : undefined}>
            <Button type="primary" disabled={materializing} onClick={() => goEdit(created.quotationId)}>
              进入编辑页
            </Button>
          </Tooltip>
        </div>
      );
    }
    if (step === 2) {
      return (
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <Button onClick={() => setStep(1)} disabled={committing}>上一步</Button>
          <Tooltip title={formValid ? undefined : '请填写报价单名称并选择报价模板'}>
            <Button type="primary" loading={committing} disabled={!formValid} onClick={handleCommit}>
              {isEmptyCandidates ? '仍然创建（0 个产品行）' : '创建报价单'}
            </Button>
          </Tooltip>
        </div>
      );
    }
    if (isFailed) {
      return (
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <Button onClick={handleDrawerClose}>取消</Button>
          <Button type="primary" onClick={handleReupload}>重新上传</Button>
        </div>
      );
    }
    if (isSuccess) {
      return (
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <Button onClick={handleReupload}>上一步</Button>
          <Button type="primary" loading={enteringStep2} onClick={enterStep2}>下一步</Button>
        </div>
      );
    }
    if (inProgress || importWaitError) {
      return (
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <Button onClick={handleDrawerClose}>关闭（后台继续）</Button>
          {importWaitError
            ? <Button type="primary" onClick={handleContinueWait}>继续等待</Button>
            : (
              <Tooltip title="导入完成后才能进入建单">
                <Button type="primary" disabled>下一步</Button>
              </Tooltip>
            )}
        </div>
      );
    }
    // 原型 02 禁用态底部提示逐字：「请先选择客户并上传文件」（不分「缺客户 / 缺文件」两句）
    const uploadBlockReason = (!customerId || !picked) ? '请先选择客户并上传文件' : undefined;
    return (
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
        <Button onClick={handleDrawerClose}>取消</Button>
        <Tooltip title={uploadBlockReason}>
          <Button type="primary" loading={submitting} disabled={!customerId || !picked} onClick={handleUpload}>
            开始导入
          </Button>
        </Tooltip>
      </div>
    );
  })();

  return (
    <Drawer
      title="导入报价数据"
      width={780}
      placement="right"
      open={open}
      onClose={handleDrawerClose}
      // 🚫 刻意不加 destroyOnClose：关闭后台继续跑，重开要能接着建单（原型 03 的承诺）
      footer={footer}
    >
      <Steps
        current={stepsCurrent}
        items={[{ title: '选客户并上传' }, { title: '选模板并建单' }]}
        style={{ marginBottom: 24 }}
      />

      {/* ═════════ 建单完成 · 物化中 / 物化失败（原型 06） ═════════ */}
      {created && (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          {materializeError ? (
            <Alert
              type="error"
              showIcon
              message="卡片值计算未完成"
              description={
                <span>
                  <b>报价单已创建成功，数据没有丢失</b> —— 只是卡片值还没算完。
                  可以重试计算，或直接进入编辑页（页面会自行触发计算）。
                  <br />
                  <Text type="secondary">{materializeError}</Text>
                </span>
              }
            />
          ) : (
            <Alert
              type="success"
              showIcon
              message={<b>报价单已创建{created.quotationNumber ? ` ${created.quotationNumber}` : ''}</b>}
              description={`共建出 ${created.lineItemsCount} 个产品行。正在后台计算卡片值，完成后自动进入编辑页。`}
            />
          )}

          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="客户"><Ell text={customerLabel} /></Descriptions.Item>
            <Descriptions.Item label="报价单名称"><Ell text={createForm.name} /></Descriptions.Item>
            {templateLabel && (
              <Descriptions.Item label="报价模板"><Ell text={templateLabel} /></Descriptions.Item>
            )}
            <Descriptions.Item label="产品行">{created.lineItemsCount}</Descriptions.Item>
            {materializeStatus && (
              <Descriptions.Item label="已完成">
                {materializeStatus.ready} / {materializeStatus.total}
              </Descriptions.Item>
            )}
          </Descriptions>

          {materializing && (
            <div>
              <div style={{ marginBottom: 8 }}>
                <Spin size="small" /> <span style={{ marginLeft: 8 }}>正在计算卡片值…</span>
              </div>
              <Progress
                percent={materializeStatus && materializeStatus.total > 0
                  ? Math.floor((materializeStatus.ready / materializeStatus.total) * 100)
                  : 0}
                status="active"
              />
              {materializeStatus && materializeStatus.total > 0 && (
                <Text type="secondary">
                  已完成 <b>{materializeStatus.ready} / {materializeStatus.total}</b> 个产品
                </Text>
              )}
            </div>
          )}

          {materializing ? (
            <Alert
              type="warning"
              showIcon
              message={<b>可以关闭本窗口</b>}
              description="计算在后台继续。关闭后可从报价单列表直接打开该单，届时若仍在计算会继续显示进度。"
            />
          ) : (
            <Alert
              type="warning"
              showIcon
              message={<b>重试不会重复建单</b>}
              description="quotationId 已存在时只重新进入轮询，不再 POST create-quotation。"
            />
          )}
        </Space>
      )}

      {/* ═════════ Step 2 · 选模板并建单（原型 05 / 07） ═════════ */}
      {!created && step === 2 && customerId && (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          {isEmptyCandidates ? (
            <Alert
              type="warning"
              showIcon
              message={<b>该客户在本次导入数据中没有客户料号</b>}
              description="建单可以继续，但不会产生任何产品行。若这不符合预期，请检查 Excel「客户料号」Sheet 的客户编号列。"
            />
          ) : (
            <Alert
              type="info"
              showIcon
              message={
                <Space size={8}>
                  <b>Step 1 已完成入库</b>
                  <Tag color="green">SUCCESS</Tag>
                </Space>
              }
              description={
                <span>
                  本次共 {record?.successRows ?? 0} 行写入；该客户在「客户料号」中有{' '}
                  <b>{candidatesLoading ? '…' : candidateCount}</b> 条，建单后将产生{' '}
                  {candidatesLoading ? '…' : candidateCount} 个产品行。
                </span>
              }
            />
          )}

          <QuotationCreateForm
            customerId={customerId}
            customerName={customerName}
            lockedCategoryId={currentCustomer?.productCategoryId}
            value={createForm}
            onChange={setCreateForm}
            onValidityChange={setFormValid}
            customerTemplateHint={autoHints.customer}
            costingTemplateHint={autoHints.costing}
          />

          <div>
            <div style={{ marginBottom: 8, fontWeight: 500 }}>
              将建出的产品行{candidateCount > 5 ? '（前 5 条）' : ''}
            </div>
            <Table<CustomerPartItem>
              size="small"
              rowKey={(r, i) => `${r.customerNo}-${r.customerProductNo}-${i}`}
              loading={candidatesLoading}
              columns={candidateColumns}
              dataSource={candidates?.items ?? []}
              pagination={false}
              locale={{
                emptyText: (
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description={candidates
                      ? `客户「${customerLabel}」在本次导入中没有客户料号`
                      : '暂无法预览产品行（不影响建单）'}
                  />
                ),
              }}
            />
            {candidateCount > 0 && (
              <div style={{ marginTop: 8 }}>
                <Text type="secondary">…共 {candidateCount} 条</Text>
              </div>
            )}
          </div>
        </Space>
      )}

      {/* ═════════ Step 1 ═════════ */}
      {!created && step === 1 && (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          {/* ── 校验失败（原型 04） ── */}
          {isFailed && (
            <>
              <Alert
                type="error"
                showIcon
                message={
                  <b>导入校验未通过，共 {record?.errors?.length ?? 0} 处问题 —— 本次未写入任何数据</b>
                }
                description="16 张表的数据量与导入前完全一致。请修正 Excel 后重新上传。"
              />
              <Descriptions column={1} size="small" bordered>
                <Descriptions.Item label="客户"><Ell text={customerLabel} /></Descriptions.Item>
                <Descriptions.Item label="文件">
                  <Ell text={record?.originalFileName || pickedName} max={280} />
                </Descriptions.Item>
              </Descriptions>
              <div>
                <div style={{ marginBottom: 8, fontWeight: 500 }}>问题清单</div>
                <Table<QuoteImportValidationError>
                  size="small"
                  rowKey={(e, i) => `${e.sheetName}-${e.rowNum}-${e.columnLabel}-${i}`}
                  columns={errorColumns}
                  dataSource={record?.errors ?? []}
                  // 🚫 AC-4 / F-5：错误**全部**可见 —— 分页等于「只显示前 N 条」，一律关分页走内部滚动
                  pagination={false}
                  scroll={(record?.errors?.length ?? 0) > 6 ? { y: 260 } : undefined}
                />
                <div style={{ marginTop: 8 }}>
                  <Text type="secondary">问题较多时列表可滚动 —— 全部错误一次列出，不止第一条。</Text>
                </div>
              </div>
            </>
          )}

          {/* ── 导入成功（原型 03 下半） ── */}
          {isSuccess && (
            <>
              <Alert
                type="success"
                showIcon
                message={<b>导入完成 · 共 {record?.successRows ?? 0} 行写入</b>}
                description={
                  <span>
                    {importElapsedMs != null ? `用时 ${(importElapsedMs / 1000).toFixed(1)} 秒。` : ''}
                    下一步选择报价模板与产品分类，即可建出报价单。
                  </span>
                }
              />
              <div>
                <div style={{ marginBottom: 8, fontWeight: 500 }}>逐 Sheet 结果</div>
                <Table<QuoteImportSheetSummary>
                  size="small"
                  rowKey={(r, i) => `${r.sheetName}-${i}`}
                  columns={sheetSummaryColumns}
                  dataSource={record?.summary ?? []}
                  pagination={false}
                  scroll={(record?.summary?.length ?? 0) > 8 ? { y: 300 } : undefined}
                />
              </div>
              <Alert
                type="warning"
                showIcon
                message={<b>「未变」不是没导入</b>}
                description="轴值（销售料号）的整组行内容与库中完全一致时不升版、一行不写 —— 这是增量语义，不是失败。"
              />
            </>
          )}

          {/* ── 导入中（原型 03 上半） ── */}
          {!isFailed && !isSuccess && (inProgress || importWaitError) && (
            <>
              <Descriptions column={1} size="small" bordered>
                <Descriptions.Item label="客户"><Ell text={customerLabel} /></Descriptions.Item>
                <Descriptions.Item label="文件"><Ell text={pickedName} max={280} /></Descriptions.Item>
                <Descriptions.Item label="导入批次">
                  <span style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: 12 }}>
                    {importRecordId}
                  </span>
                </Descriptions.Item>
              </Descriptions>

              {importWaitError ? (
                <Alert
                  type="error"
                  showIcon
                  message="未能拿到导入结果"
                  description={
                    <span>
                      {importWaitError}
                      <br />
                      <Text type="secondary">
                        导入批次仍在后台，可点「继续等待」重进轮询，或到【导入历史】查看结果。
                      </Text>
                    </span>
                  }
                />
              ) : (
                <>
                  <div style={{ marginBottom: 8 }}>正在导入…</div>
                  <Progress percent={progressPct} status="active" />
                  <Text type="secondary">
                    {progress
                      ? <>正在处理：<b>{progress.current || '…'}</b>（第 {progress.done} / {progress.total} 步）</>
                      : '准备中…'}
                  </Text>
                  <Alert
                    type="info"
                    showIcon
                    message={<b>可以关闭本窗口</b>}
                    description="导入在后台继续执行。完成后可在【导入历史】查看结果，或重新打开本抽屉继续建单。"
                  />
                </>
              )}
            </>
          )}

          {/* ── 表单态：选客户 + 上传（原型 02） ── */}
          {!isFailed && !isSuccess && !inProgress && !importWaitError && (
            <Form layout="vertical">
              {/* 🚫 刻意不给 validateStatus='error'：原型 02 的禁用态**没有**红色报错 ——
                  抽屉一打开就飘红是在还没交互时就指责用户。必选语义由「*」+ 上传区禁用
                  +「开始导入」禁用 tooltip 三处表达（AC-2①）。 */}
              <Form.Item
                label="客户"
                required
                extra="导入的数据按销售料号入库；客户决定本次建出哪些产品行。"
              >
                <Select
                  placeholder="请选择客户"
                  loading={customersLoading}
                  value={customerId}
                  onChange={(v) => { setCustomerId(v); setAutoFilled(false); }}
                  showSearch
                  optionFilterProp="label"
                  options={customers.map((c) => ({
                    value: c.id,
                    label: c.code ? `${c.name}（${c.code}）` : c.name,
                  }))}
                />
              </Form.Item>

              <Form.Item
                label="报价数据 Excel"
                required
                extra={customerId
                  ? undefined
                  : '未选客户时上传区禁用 —— 前端不许自己放行空客户，后端同样校验。'}
              >
                <Dragger {...draggerProps} style={{ background: '#fafafa' }}>
                  <p className="ant-upload-drag-icon"><InboxOutlined /></p>
                  <p className="ant-upload-text">
                    {customerId ? '点击或拖拽文件到此处上传' : UPLOAD_NEED_CUSTOMER_TIP}
                  </p>
                  <p className="ant-upload-hint">{SHEET_HINT}</p>
                </Dragger>

                {picked && (
                  <div
                    style={{
                      marginTop: 12, display: 'flex', alignItems: 'center', gap: 8,
                      border: '1px solid #f0f0f0', borderRadius: 6, padding: '6px 12px',
                    }}
                  >
                    <FileExcelOutlined />
                    <span style={{ flex: 1, minWidth: 0 }}>
                      <Ell text={picked.name} max={340} />
                    </span>
                    <Text type="secondary">{fmtSize(picked.size)}</Text>
                    <Button
                      type="text"
                      size="small"
                      icon={<DeleteOutlined />}
                      onClick={() => setFileList([])}
                    />
                  </div>
                )}
              </Form.Item>

              {picked && (
                <Alert
                  type="info"
                  showIcon
                  message={<b>整份拒收</b>}
                  description="校验阶段零写库：只要有一处不合格，本次一行都不会写入，错误会逐条列出。"
                />
              )}
            </Form>
          )}
        </Space>
      )}
    </Drawer>
  );
};

/** 报价模板自动带出的来源提示（与 V6 抽屉同口径，`GET /templates/auto-defaults`）。 */
function quoteHintOf(source?: string, version?: string): string | undefined {
  switch (source) {
    case 'LAST_USED': return `上次使用 · 最新版${version ? ' ' + version : ''}`;
    case 'CUSTOMER_SPECIFIC_FALLBACK': return '无历史 · 客户专属最新';
    case 'GENERAL_FALLBACK': return '无历史 · 通用最新';
    default: return undefined;
  }
}

function costingHintOf(source?: string, version?: string): string | undefined {
  switch (source) {
    case 'CUSTOMER_SPECIFIC': return `客户专属 · 最新${version ? ' ' + version : ''}`;
    case 'GENERAL': return `通用 · 最新${version ? ' ' + version : ''}`;
    default: return undefined;
  }
}

export default QuotationDatasetImportDrawer;
