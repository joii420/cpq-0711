import { ArrowRightOutlined, InboxOutlined, ReloadOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import {
  Alert,
  Button,
  Drawer,
  Empty,
  message,
  Progress,
  Select,
  Space,
  Spin,
  Steps,
  Table,
  Tag,
  Typography,
  Upload,
} from 'antd';
import type { UploadFile, UploadProps } from 'antd/es/upload/interface';
import { useEffect, useMemo, useRef, useState } from 'react';

import api from '../../services/api';
import { customerService } from '../../services/customerService';
import { quotationService } from '../../services/quotationService';
import {
  basicDataImportV6Service,
  type ImportProgress,
  type ImportResultDTO,
  type SheetResultDTO,
} from '../../services/basicDataImportV6Service';
import QuotationCreateForm, { type QuotationFormValue } from './QuotationCreateForm';

const { Dragger } = Upload;
const { Text } = Typography;

interface Props {
  open: boolean;
  onClose: () => void;
  /** 从报价单列表传入的默认客户（可为空，让用户在 Step 1 自选）。 */
  defaultCustomerId?: string;
}

interface CustomerOption {
  id: string;
  name: string;
  productCategoryId?: string;
}

interface CommitResultDTO {
  quotationId: string;
  importRecordId: string;
  hfPairsCount: number;
}

export default function QuoteBasicDataImportV6Drawer({ open, onClose, defaultCustomerId }: Props) {
  const navigate = useNavigate();

  const [step, setStep] = useState<1 | 2>(1);
  const [customers, setCustomers] = useState<CustomerOption[]>([]);
  const [customersLoading, setCustomersLoading] = useState(false);
  const [customerId, setCustomerId] = useState<string | undefined>(defaultCustomerId);
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [processing, setProcessing] = useState(false);
  const [progress, setProgress] = useState<ImportProgress | null>(null);
  const [result, setResult] = useState<ImportResultDTO | null>(null);

  const [createForm, setCreateForm] = useState<QuotationFormValue>({
    name: '',
    categoryId: undefined,
    customerTemplateId: undefined,
    costingTemplateId: undefined,
  });
  const [formValid, setFormValid] = useState(false);
  const [committing, setCommitting] = useState(false);
  const [autoHints, setAutoHints] = useState<{ customer?: string; costing?: string }>({});
  const [enteringStep2, setEnteringStep2] = useState(false);
  // 自动带出每次打开抽屉只跑一次:返回上一步再进 Step 2 时不重新覆盖用户手改的模板/分类
  const [autoFilled, setAutoFilled] = useState(false);

  // task-260825 D-5 + F-10/F-11：建单改「发起 → 轮询只读状态 → 完成」三段式（AC-13/AC-14）。
  const [materializing, setMaterializing] = useState(false);
  const [materializeElapsedMs, setMaterializeElapsedMs] = useState(0);
  // F-11 放宽：只读端点带真实 ready/total，可显示「已完成 N / M」，不再是无进度的纯等待
  const [materializeStatus, setMaterializeStatus] = useState<{ ready: number; total: number } | null>(null);
  const [materializeError, setMaterializeError] = useState<string | null>(null);
  const [pendingQuotation, setPendingQuotation] = useState<{ quotationId: string; importRecordId?: string; hfPairsCount?: number } | null>(null);
  // 用户在轮询期间关闭抽屉（AC-14③ 允许）：置位后轮询完成不再自动 onClose()/navigate，避免"已关闭却被跳走"的意外体验；
  // 后台计算本身不受影响（ensureCardValues 是服务端异步任务，前端只是不再等它）。
  const pollAbortRef = useRef(false);

  useEffect(() => {
    if (!open) return;
    setStep(1);
    setCustomerId(defaultCustomerId);
    setResult(null);
    setProcessing(false);
    setProgress(null);
    setFileList([]);
    setCreateForm({ name: '', categoryId: undefined, customerTemplateId: undefined, costingTemplateId: undefined });
    setFormValid(false);
    setAutoHints({});
    setAutoFilled(false);
    setMaterializing(false);
    setMaterializeElapsedMs(0);
    setMaterializeStatus(null);
    setMaterializeError(null);
    setPendingQuotation(null);
    pollAbortRef.current = false;
    setCustomersLoading(true);
    customerService
      .list({ page: 0, size: 200 })
      .then((r: any) => {
        const list: any[] = r.data?.content ?? r.data ?? [];
        setCustomers(list.map((c: any) => ({ id: c.id, name: c.name, productCategoryId: c.productCategoryId })));
      })
      .catch(() => message.error('加载客户列表失败'))
      .finally(() => setCustomersLoading(false));
  }, [open, defaultCustomerId]);

  const customerName = useMemo(
    () => customers.find((c) => c.id === customerId)?.name ?? '',
    [customers, customerId],
  );

  // task-0712: 产品分类由客户绑定带出，锁定 QuotationCreateForm 的分类下拉
  const customerCategoryId = useMemo(
    () => customers.find((c) => c.id === customerId)?.productCategoryId,
    [customers, customerId],
  );

  const draggerProps: UploadProps = {
    name: 'file',
    multiple: false,
    accept: '.xlsx',
    fileList,
    beforeUpload: (file) => {
      setFileList([file as unknown as UploadFile]);
      return false;
    },
    onRemove: () => setFileList([]),
  };

  const quoteHintOf = (source?: string, version?: string): string | undefined => {
    switch (source) {
      case 'LAST_USED': return `上次使用 · 最新版${version ? ' ' + version : ''}`;
      case 'CUSTOMER_SPECIFIC_FALLBACK': return '无历史 · 客户专属最新';
      case 'GENERAL_FALLBACK': return '无历史 · 通用最新';
      default: return undefined;
    }
  };
  const costingHintOf = (source?: string, version?: string): string | undefined => {
    switch (source) {
      case 'CUSTOMER_SPECIFIC': return `客户专属 · 最新${version ? ' ' + version : ''}`;
      case 'GENERAL': return `通用 · 最新${version ? ' ' + version : ''}`;
      default: return undefined;
    }
  };

  const handleUpload = async () => {
    if (!customerId) return message.warning('请先选择客户');
    if (fileList.length === 0) return message.warning('请先上传 Excel 文件');
    setSubmitting(true);
    setResult(null);
    setProgress(null);
    setProcessing(true);
    try {
      const file = (fileList[0] as unknown as { originFileObj?: File }).originFileObj
        ?? (fileList[0] as unknown as File);
      // 后端异步：POST 立即返回 importRecordId(PROCESSING)，前端轮询直到终态（不撞超时）。
      const pending = await basicDataImportV6Service.importQuote(customerId, file as File);
      const r = await basicDataImportV6Service.pollImportResult(pending.importRecordId, {
        intervalMs: 1500,
        onTick: (rec) => setProgress(basicDataImportV6Service.parseProgress(rec)),
      });
      setResult(r);
      if (r.status === 'SUCCESS') message.success(`导入成功 ${r.totalSuccessRows} 行`);
      else if (r.status === 'PARTIAL')
        message.warning(`部分成功：${r.totalSuccessRows} / 失败 ${r.totalFailedRows}`);
      else message.error(`导入失败 ${r.totalFailedRows} 行`);
    } catch (e: any) {
      message.error(e?.message ?? '导入异常');
    } finally {
      setProcessing(false);
      setSubmitting(false);
    }
  };

  const enterStep2 = async () => {
    if (!customerId) return;
    // 已自动带出过(用户可能已手改)→ 直接回到 Step 2,不再覆盖
    if (autoFilled) {
      setStep(2);
      return;
    }
    setEnteringStep2(true);
    try {
      const resp: any = await api.get('/templates/auto-defaults', { params: { customerId } });
      const d = resp?.data ?? resp; // axios 拦截器已返回 ApiResponse body, payload 在 .data
      setCreateForm({
        name: createForm.name || `${customerName} 报价单`,
        categoryId: d?.categoryId ?? undefined,
        customerTemplateId: d?.customerTemplateId ?? undefined,
        costingTemplateId: d?.costingTemplateId ?? undefined,
      });
      setAutoHints({
        customer: quoteHintOf(d?.customerTemplateSource, d?.customerTemplateVersion),
        costing: costingHintOf(d?.costingTemplateSource, d?.costingTemplateVersion),
      });
    } catch {
      setAutoHints({}); // 静默降级:不预填, 走现状默认分类 + 手选
    } finally {
      setEnteringStep2(false);
      setAutoFilled(true);
      setStep(2);
    }
  };

  /**
   * F-10/F-11 轮询段：POST create-quotation 已返回（建单+建行完成），进「正在计算」态并轮询
   * **只读**状态端点 `materialize-status` 直到 done=true，再进编辑页。
   * 🚫 不再把 `ensure-card-values` 当主循环——那是 2026-08-26 亲验抓到的竞态根因（丢过 345 行，见
   * backtask.md B-22）；`ensure-card-values` 现在只在只读端点判定「后台死了」时由
   * `quotationService.pollMaterializeStatus` 内部节流触发一次自愈调用。
   */
  const runMaterializePoll = async (quotationId: string, importRecordId?: string, hfPairsCount?: number) => {
    setMaterializing(true);
    setMaterializeError(null);
    setMaterializeElapsedMs(0);
    setMaterializeStatus(null);
    const startedAt = Date.now();
    try {
      await quotationService.pollMaterializeStatus(quotationId, {
        intervalMs: 1500,
        onTick: (status) => {
          setMaterializeElapsedMs(Date.now() - startedAt);
          setMaterializeStatus({ ready: status.ready, total: status.total });
        },
      });
      if (pollAbortRef.current) return; // 用户已中途关闭抽屉，不再自动跳转（AC-14③）
      setMaterializing(false);
      message.success(`报价单已创建（涉及 ${hfPairsCount ?? 0} 个料号），正在跳转…`);
      onClose();
      const qs = new URLSearchParams({ autoPopulate: '1' });
      if (importRecordId) qs.set('importRecordId', importRecordId);
      navigate(`/quotations/${quotationId}/edit?${qs.toString()}`);
    } catch (e: any) {
      if (pollAbortRef.current) return;
      setMaterializing(false);
      // AC-14①：非续轮信号的错误（含 20 分钟兜底超时）一律显式提示 + 停止轮询，不许无限转圈
      setMaterializeError(
        e?.isPollTimeout ? (e.message as string) : (e?.message || '卡片值计算失败，请重试'),
      );
    }
  };

  const handleCommit = async () => {
    if (!result?.importRecordId) return message.warning('请先完成 Step 1 导入');
    if (!customerId) return message.warning('客户信息丢失');
    if (!formValid) return message.warning('请填写报价单名称 + 选择客户报价模板');
    setCommitting(true);
    setMaterializeError(null);
    try {
      // 建单 POST：D-5 之后只做建单 + 建行，目标 <5s 内返回，不再等物化（api.md「契约变更」）。
      const resp: any = await api.post('/basic-data-import/v6/quote/create-quotation', {
        importRecordId: result.importRecordId,
        customerId,
        name: createForm.name,
        categoryId: createForm.categoryId,
        customerTemplateId: createForm.customerTemplateId,
        costingTemplateId: createForm.costingTemplateId,
      });
      const data: CommitResultDTO = resp.data?.data ?? resp.data;
      pollAbortRef.current = false;
      setPendingQuotation({ quotationId: data.quotationId, importRecordId: data.importRecordId, hfPairsCount: data.hfPairsCount });
      await runMaterializePoll(data.quotationId, data.importRecordId, data.hfPairsCount);
    } catch (e: any) {
      message.error(e?.message ?? '建报价单失败');
    } finally {
      setCommitting(false);
    }
  };

  /** 重试：quotationId 已创建，不重新 POST create-quotation，直接重新进入只读状态轮询（内含 F-11 自愈）。 */
  const handleRetryMaterialize = () => {
    if (!pendingQuotation) return;
    pollAbortRef.current = false;
    runMaterializePoll(pendingQuotation.quotationId, pendingQuotation.importRecordId, pendingQuotation.hfPairsCount);
  };

  /** 抽屉关闭统一入口：轮询在飞时置位 pollAbortRef，防止关闭后轮询完成又把用户"跳走"。 */
  const handleDrawerClose = () => {
    if (materializing) pollAbortRef.current = true;
    onClose();
  };

  const statusTag = useMemo(() => {
    if (!result) return null;
    const color = result.status === 'SUCCESS' ? 'green' : result.status === 'PARTIAL' ? 'orange' : 'red';
    return <Tag color={color}>{result.status}</Tag>;
  }, [result]);

  const sheetColumns = [
    { title: 'Sheet', dataIndex: 'sheetName', width: 200 },
    {
      title: '行数',
      width: 140,
      render: (_: unknown, r: SheetResultDTO) => `${r.successRows} / ${r.totalRows}`,
    },
    { title: '失败', dataIndex: 'failedRows', width: 80 },
    {
      title: '写入',
      render: (_: unknown, r: SheetResultDTO) =>
        Object.entries(r.writtenCounts ?? {}).map(([t, n]) => (
          <Tag key={t}>{t}:{n}</Tag>
        )),
    },
  ];

  const canEnterStep2 = result && result.status !== 'FAILED';

  return (
    <Drawer
      title="报价基础数据导入 (V6 · 19 Sheet)"
      width={1000}
      placement="right"
      open={open}
      onClose={handleDrawerClose}
      destroyOnClose
      footer={
        step === 1 ? (
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
            <Button onClick={handleDrawerClose}>取消</Button>
            <Button
              type="primary"
              icon={<ArrowRightOutlined />}
              disabled={!canEnterStep2}
              loading={enteringStep2}
              onClick={enterStep2}
            >
              下一步：选模板 + 建报价单
            </Button>
          </div>
        ) : (
          <div style={{ display: 'flex', justifyContent: 'space-between' }}>
            <Button onClick={() => setStep(1)} disabled={committing || materializing}>上一步</Button>
            <Space>
              {/* AC-14③：轮询/失败态期间仍允许关闭抽屉，后台任务不受影响，只是前端不再等它 */}
              <Button onClick={handleDrawerClose}>取消</Button>
              <Button
                type="primary"
                loading={committing || materializing}
                disabled={!formValid || materializing}
                onClick={handleCommit}
              >
                {materializing ? '正在计算卡片值…' : committing ? '创建中…' : '创建报价单'}
              </Button>
            </Space>
          </div>
        )
      }
    >
      <Steps
        current={step - 1}
        items={[{ title: '上传 + 入库' }, { title: '选模板 + 建报价单' }]}
        style={{ marginBottom: 16 }}
      />

      {step === 1 && (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <div>
            <Text strong>选择客户</Text>
            <Select
              style={{ width: '100%', marginTop: 8 }}
              placeholder="请选择客户"
              loading={customersLoading}
              value={customerId}
              onChange={setCustomerId}
              showSearch
              optionFilterProp="label"
              options={customers.map((c) => ({ value: c.id, label: c.name }))}
            />
          </div>

          <Dragger {...draggerProps}>
            <p className="ant-upload-drag-icon"><InboxOutlined /></p>
            <p className="ant-upload-text">点击或拖拽 .xlsx 文件到此区域</p>
            <p className="ant-upload-hint">19 Sheet 报价基础数据 / 单文件 / customer_no 系统自动注入</p>
          </Dragger>

          <Space>
            <Button onClick={() => { setResult(null); setFileList([]); }}>
              <ReloadOutlined /> 重置
            </Button>
            <Button type="primary" onClick={handleUpload} loading={submitting}
                    disabled={!customerId || fileList.length === 0}>
              开始导入
            </Button>
          </Space>

          {processing && !result && (
            <Alert
              type="info"
              showIcon
              message="后台导入处理中…"
              description={
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Progress
                    percent={progress ? Math.round((progress.done / progress.total) * 100) : 0}
                    status="active"
                  />
                  <Text type="secondary">
                    {progress
                      ? `正在处理：${progress.current || '…'}（${progress.done}/${progress.total} Sheet）`
                      : '准备中…'}
                  </Text>
                  <Text type="secondary">大文件在后台执行，请勿关闭抽屉；完成后展示各 Sheet 结果。</Text>
                </Space>
              }
            />
          )}

          {result && (
            <>
              <Alert
                type={result.status === 'SUCCESS' ? 'success' : result.status === 'PARTIAL' ? 'warning' : 'error'}
                showIcon
                message={
                  <Space>
                    导入完成 {statusTag}
                    <Text>成功 {result.totalSuccessRows} 行</Text>
                    {result.totalFailedRows > 0 && <Text type="danger">失败 {result.totalFailedRows} 行</Text>}
                  </Space>
                }
              />
              <Table
                size="small"
                rowKey="sheetName"
                pagination={false}
                columns={sheetColumns as any}
                dataSource={result.sheetResults}
                expandable={{
                  expandedRowRender: (r) =>
                    r.errors?.length ? (
                      <Table
                        size="small"
                        rowKey={(e: any) => `${e.rowNo}-${e.column}-${e.message}`}
                        pagination={false}
                        columns={[
                          { title: '行号', dataIndex: 'rowNo', width: 80 },
                          { title: '列', dataIndex: 'column', width: 200 },
                          { title: '错误', dataIndex: 'message' },
                        ]}
                        dataSource={r.errors}
                      />
                    ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无错误" />,
                  rowExpandable: (r) => (r.failedRows ?? 0) > 0,
                }}
              />
            </>
          )}
        </Space>
      )}

      {step === 2 && customerId && (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Alert
            type="info"
            showIcon
            message={
              <Space>
                Step 1 已完成入库 {statusTag}
                <Text>共 {result?.totalSuccessRows ?? 0} 行</Text>
                <Text type="secondary">下面选模板 + 填报价单名，创建后会自动跳转编辑页并按本次导入料号填充 LineItem</Text>
              </Space>
            }
          />
          <QuotationCreateForm
            customerId={customerId}
            customerName={customerName}
            lockedCategoryId={customerCategoryId}
            value={createForm}
            onChange={setCreateForm}
            onValidityChange={setFormValid}
            customerTemplateHint={autoHints.customer}
            costingTemplateHint={autoHints.costing}
          />

          {/* F-10/F-11：建单已完成，后台正在物化卡片值；只读状态端点带真实 ready/total，显示真实进度 */}
          {materializing && (
            <Alert
              type="info"
              showIcon
              icon={<Spin size="small" />}
              message="正在计算卡片值…"
              description={
                <Space direction="vertical" size={4} style={{ width: '100%' }}>
                  {materializeStatus && materializeStatus.total > 0 && (
                    <Progress
                      percent={Math.floor((materializeStatus.ready / materializeStatus.total) * 100)}
                      status="active"
                      format={() => `${materializeStatus.ready} / ${materializeStatus.total}`}
                    />
                  )}
                  <Text type="secondary">
                    报价单已创建，后台正在整单物化卡片值，请勿重复提交
                    {materializeElapsedMs > 0 ? `（已等待 ${Math.round(materializeElapsedMs / 1000)} 秒）` : ''}。
                  </Text>
                  <Text type="secondary">完成后会自动跳转编辑页；也可先关闭本抽屉，稍后在报价单列表中查看该单。</Text>
                </Space>
              }
            />
          )}

          {/* D-5 · F-8：轮询拿到非 409 错误或超过兜底时长 → 显式提示 + 停止轮询，不无限转圈 */}
          {materializeError && (
            <Alert
              type="error"
              showIcon
              message="卡片值计算未完成"
              description={
                <Space direction="vertical" size={8} style={{ width: '100%' }}>
                  <Text>{materializeError}</Text>
                  <Space>
                    <Button size="small" onClick={handleRetryMaterialize}>重新计算</Button>
                    <Button size="small" onClick={handleDrawerClose}>先关闭，稍后查看</Button>
                  </Space>
                </Space>
              }
            />
          )}
        </Space>
      )}
    </Drawer>
  );
}
