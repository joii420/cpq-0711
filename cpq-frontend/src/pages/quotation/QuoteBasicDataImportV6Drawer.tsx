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
  Steps,
  Table,
  Tag,
  Typography,
  Upload,
} from 'antd';
import type { UploadFile, UploadProps } from 'antd/es/upload/interface';
import { useEffect, useMemo, useState } from 'react';

import api from '../../services/api';
import { customerService } from '../../services/customerService';
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

  const handleCommit = async () => {
    if (!result?.importRecordId) return message.warning('请先完成 Step 1 导入');
    if (!customerId) return message.warning('客户信息丢失');
    if (!formValid) return message.warning('请填写报价单名称 + 选择客户报价模板');
    setCommitting(true);
    try {
      const resp: any = await api.post('/basic-data-import/v6/quote/create-quotation', {
        importRecordId: result.importRecordId,
        customerId,
        name: createForm.name,
        categoryId: createForm.categoryId,
        customerTemplateId: createForm.customerTemplateId,
        costingTemplateId: createForm.costingTemplateId,
      });
      const data: CommitResultDTO = resp.data?.data ?? resp.data;
      message.success(`报价单已创建（涉及 ${data.hfPairsCount} 个料号），正在跳转…`);
      onClose();
      const qs = new URLSearchParams({ autoPopulate: '1' });
      if (data.importRecordId) qs.set('importRecordId', data.importRecordId);
      navigate(`/quotations/${data.quotationId}/edit?${qs.toString()}`);
    } catch (e: any) {
      message.error(e?.message ?? '建报价单失败');
    } finally {
      setCommitting(false);
    }
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
      onClose={onClose}
      destroyOnClose
      footer={
        step === 1 ? (
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
            <Button onClick={onClose}>取消</Button>
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
            <Button onClick={() => setStep(1)}>上一步</Button>
            <Space>
              <Button onClick={onClose}>取消</Button>
              <Button
                type="primary"
                loading={committing}
                disabled={!formValid}
                onClick={handleCommit}
              >
                {committing ? '创建中…' : '创建报价单'}
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
        </Space>
      )}
    </Drawer>
  );
}
