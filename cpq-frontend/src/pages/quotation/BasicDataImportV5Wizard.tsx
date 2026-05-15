/**
 * BasicDataImportV5Wizard — V6 staging-based 三步导入向导
 *
 * Step 1: 选客户 + 上传 Excel → POST /import-session/upload → sessionId + diffPayload
 * Step 2: 版本确认（料号版本 + 客户冲突 + 孤儿行）→ debounce PUT /import-session/{id}/decisions
 * Step 3: 创建报价单表单 → POST /import-session/{id}/commit → 跳转 /quotations/{id}/edit
 *
 * 设计文档：docs/superpowers/specs/2026-05-12-import-v6-staging-design.md
 */
import React, { useState, useCallback, useRef } from 'react';
import {
  Alert,
  Button,
  Collapse,
  Drawer,
  Modal,
  Select,
  Space,
  Spin,
  Steps,
  Typography,
  Upload,
  message,
} from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import type { UploadFile } from 'antd/es/upload/interface';
import { useNavigate } from 'react-router-dom';
import { importSessionService } from '../../services/importSessionService';
import type {
  DiffPayload,
  PartVersionDecisionItem,
  CustomerConflictItem,
  OrphanItem,
  PartVersionAction,
  CustomerConflictAction,
  OrphanAction,
  DecisionEntry,
} from '../../types/import-v6';
import PartVersionDecisionList from './PartVersionDecisionList';
import CustomerConflictSection from './CustomerConflictSection';
import OrphanRowsSection from './OrphanRowsSection';
import QuotationCreateForm, { type QuotationFormValue } from './QuotationCreateForm';

const { Text } = Typography;
const { Dragger } = Upload;
const { Option } = Select;
const { Panel } = Collapse;

// ────────────────────────────────────────────────
// Props
// ────────────────────────────────────────────────
interface BasicDataImportV5WizardProps {
  open: boolean;
  customers: { id: string; name: string }[];
  defaultCustomerId?: string;
  onClose: () => void;
  onSuccess?: (quotationId: string, customerId: string) => void;
  title?: string;
  /**
   * V90+V94 历史扩展占位:隐藏客户选择(核价基础数据导入用),组件内部消费由后续 follow-up 实现.
   * 当前仅接受 prop 防止 TS 报错;运行时无效果.
   */
  hideCustomer?: boolean;
  /**
   * V94 历史扩展占位:导入目标模板类型 ('QUOTATION' / 'COSTING' / 'BOTH'),
   * 影响 BasicDataImportServiceV5.parseExcel 的 requestKind 路由.组件内部消费由后续 follow-up 实现.
   */
  templateKind?: string;
}

// ────────────────────────────────────────────────
// 默认表单值
// ────────────────────────────────────────────────
const defaultCreateForm: QuotationFormValue = {
  name: '',
  categoryId: undefined,
  customerTemplateId: undefined,
  costingTemplateId: undefined,
};

// ────────────────────────────────────────────────
// 主组件
// ────────────────────────────────────────────────
const BasicDataImportV5Wizard: React.FC<BasicDataImportV5WizardProps> = ({
  open,
  customers,
  defaultCustomerId,
  onClose,
  onSuccess,
  title,
}) => {
  const navigate = useNavigate();

  // ── 步骤 (1-indexed) ──
  const [step, setStep] = useState<1 | 2 | 3>(1);

  // ── Step 1 状态 ──
  const [customerId, setCustomerId] = useState<string>(defaultCustomerId ?? '');
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);

  // ── Step 2 状态（从 diffPayload 初始化） ──
  const [sessionId, setSessionId] = useState<string>('');
  const [partVersions, setPartVersions] = useState<PartVersionDecisionItem[]>([]);
  const [conflicts, setConflicts] = useState<CustomerConflictItem[]>([]);
  const [orphans, setOrphans] = useState<OrphanItem[]>([]);
  const [validationWarnings, setValidationWarnings] = useState<string[]>([]);

  // ── Step 3 状态 ──
  const [createForm, setCreateForm] = useState<QuotationFormValue>(defaultCreateForm);
  const [formValid, setFormValid] = useState(false);
  const [committing, setCommitting] = useState(false);

  // debounce timer ref
  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // ── 重置所有状态 ──
  const resetAll = useCallback(() => {
    setStep(1);
    setCustomerId(defaultCustomerId ?? '');
    setFile(null);
    setUploading(false);
    setUploadError(null);
    setSessionId('');
    setPartVersions([]);
    setConflicts([]);
    setOrphans([]);
    setValidationWarnings([]);
    setCreateForm(defaultCreateForm);
    setFormValid(false);
    setCommitting(false);
  }, [defaultCustomerId]);

  // 每次打开时重置
  React.useEffect(() => {
    if (open) resetAll();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  // ── 文件列表（受控） ──
  const fileList: UploadFile[] = file
    ? [{ uid: '1', name: file.name, status: 'done', size: file.size } as UploadFile]
    : [];

  // ────────────────────────────────────────────────
  // Step 1 → Step 2：上传文件
  // ────────────────────────────────────────────────
  const handleUpload = async () => {
    if (!file || !customerId) return;
    setUploading(true);
    setUploadError(null);
    try {
      const result = await importSessionService.upload(customerId, file);
      const diff: DiffPayload = result.diffPayload ?? {
        partVersionDecisions: [],
        customerConflicts: [],
        orphanRows: [],
        validation: { hasErrors: false, errors: [], warnings: [] },
      };

      // 校验失败直接展示错误
      if (diff.validation?.hasErrors) {
        const errs = diff.validation.errors ?? [];
        setUploadError(errs.length > 0 ? errs.join('\n') : '校验未通过，请检查 Excel 数据');
        return;
      }

      setSessionId(result.sessionId);
      // 将 defaultAction 赋给 action 字段（后端返回 defaultAction，前端展示为初始值）
      setPartVersions(
        (diff.partVersionDecisions ?? []).map((item: any) => ({
          ...item,
          action: item.action ?? item.defaultAction ?? 'BUMP',
        }))
      );
      setConflicts(
        (diff.customerConflicts ?? []).map((item: any) => ({
          ...item,
          action: item.action ?? 'USE_EXCEL',
        }))
      );
      setOrphans(
        (diff.orphanRows ?? []).map((item: any) => ({
          ...item,
          action: item.action ?? 'DISCARD',
        }))
      );
      setValidationWarnings(diff.validation?.warnings ?? []);
      setStep(2);
    } catch (err: any) {
      setUploadError(err?.message ?? '上传失败，请重试');
    } finally {
      setUploading(false);
    }
  };

  // ────────────────────────────────────────────────
  // Step 2：决策变更 → debounce 500ms PUT /decisions
  // ────────────────────────────────────────────────
  const sendDecisions = useCallback(
    (
      pv: PartVersionDecisionItem[],
      cc: CustomerConflictItem[],
      or: OrphanItem[]
    ) => {
      if (!sessionId) return;
      if (debounceTimer.current) clearTimeout(debounceTimer.current);
      debounceTimer.current = setTimeout(async () => {
        const decisions: DecisionEntry[] = [
          ...pv.map((item) => ({
            decisionType: 'PART_VERSION' as const,
            decisionKey: item.key,
            action: item.action,
          })),
          ...cc.map((item) => ({
            decisionType: 'CUSTOMER_CONFLICT' as const,
            decisionKey: item.key,
            action: item.action,
          })),
          ...or.map((item) => ({
            decisionType: 'ORPHAN' as const,
            decisionKey: item.key,
            action: item.action,
            ...(item.action === 'LINK_EXISTING' && (item as any).targetPartId
              ? { targetPartId: (item as any).targetPartId }
              : {}),
          })),
        ];
        try {
          await importSessionService.updateDecisions(sessionId, { decisions });
        } catch {
          // 静默失败，用户下一次操作或 commit 时会重试
        }
      }, 500);
    },
    [sessionId]
  );

  const handleVersionChange = (key: string, action: PartVersionAction) => {
    const updated = partVersions.map((item) =>
      item.key === key ? { ...item, action } : item
    );
    setPartVersions(updated);
    sendDecisions(updated, conflicts, orphans);
  };

  const handleConflictChange = (key: string, action: CustomerConflictAction) => {
    const updated = conflicts.map((item) =>
      item.key === key ? { ...item, action } : item
    );
    setConflicts(updated);
    sendDecisions(partVersions, updated, orphans);
  };

  const handleOrphanChange = (key: string, action: OrphanAction) => {
    const updated = orphans.map((item) =>
      item.key === key ? { ...item, action } : item
    );
    setOrphans(updated);
    sendDecisions(partVersions, conflicts, updated);
  };

  // ────────────────────────────────────────────────
  // Step 3 → Commit：创建报价单
  // ────────────────────────────────────────────────
  const handleCommit = async () => {
    if (!formValid || !sessionId) return;
    if (!createForm.customerTemplateId) {
      message.warning('请先选择客户报价模板');
      return;
    }
    setCommitting(true);
    try {
      const result = await importSessionService.commit(sessionId, {
        name: createForm.name,
        categoryId: createForm.categoryId!,
        customerTemplateId: createForm.customerTemplateId,
        costingTemplateId: createForm.costingTemplateId,
      });
      message.success('报价单已创建，正在跳转…');
      onSuccess?.(result.quotationId, customerId);
      // V6: 带 importRecordId 让 Step2 按本次导入精确过滤涉及料号
      //     避免拉客户全部历史 mapping → 显示多余产品
      //     （参见 AP-23 / CustomerPartCandidateService.java 注释）
      const qs = new URLSearchParams({ autoPopulate: '1' });
      if (result.importRecordId) qs.set('importRecordId', result.importRecordId);
      navigate(`/quotations/${result.quotationId}/edit?${qs.toString()}`);
      onClose();
    } catch (err: any) {
      message.error(err?.message ?? '创建报价单失败，请重试');
    } finally {
      setCommitting(false);
    }
  };

  // ────────────────────────────────────────────────
  // 关闭保护：二次确认 → DELETE session
  // ────────────────────────────────────────────────
  const handleCloseRequest = () => {
    if (step === 1 && !sessionId) {
      // 尚未上传，直接关闭
      onClose();
      return;
    }
    Modal.confirm({
      title: '确认取消导入？',
      content: '取消后本次上传的 staging 数据将被清除，正式数据表不受影响。',
      okText: '确认取消',
      cancelText: '继续操作',
      okButtonProps: { danger: true },
      onOk: async () => {
        if (sessionId) {
          try {
            await importSessionService.cancel(sessionId);
          } catch {
            // 忽略清理失败，服务端有 24h 自动清理
          }
        }
        resetAll();
        onClose();
      },
    });
  };

  // ────────────────────────────────────────────────
  // 渲染 Step 1
  // ────────────────────────────────────────────────
  const renderStep1 = () => (
    <Space direction="vertical" style={{ width: '100%' }} size={16}>
      <div>
        <Text strong>选择客户</Text>
        <Select
          style={{ width: '100%', marginTop: 8 }}
          placeholder="请选择客户"
          value={customerId || undefined}
          onChange={(val) => setCustomerId(val)}
          showSearch
          optionFilterProp="children"
        >
          {customers.map((c) => (
            <Option key={c.id} value={c.id}>
              {c.name}
            </Option>
          ))}
        </Select>
      </div>

      <div>
        <Text strong>上传 Excel 文件</Text>
        <Dragger
          style={{ marginTop: 8 }}
          accept=".xlsx,.xls"
          beforeUpload={(f) => {
            setFile(f);
            setUploadError(null);
            return false; // 阻止自动上传
          }}
          onRemove={() => { setFile(null); setUploadError(null); }}
          fileList={fileList}
          maxCount={1}
        >
          <p className="ant-upload-drag-icon">
            <InboxOutlined />
          </p>
          <p className="ant-upload-text">点击或拖拽文件到此区域上传</p>
          <p className="ant-upload-hint">支持 .xlsx / .xls 格式，单次导入一个文件</p>
        </Dragger>
      </div>

      {uploadError && (
        <Alert
          type="error"
          showIcon
          message="上传/校验失败"
          description={<pre style={{ margin: 0, whiteSpace: 'pre-wrap', fontSize: 12 }}>{uploadError}</pre>}
        />
      )}
    </Space>
  );

  // ────────────────────────────────────────────────
  // 渲染 Step 2
  // ────────────────────────────────────────────────
  const renderStep2 = () => (
    <Space direction="vertical" style={{ width: '100%' }} size={16}>
      {validationWarnings.length > 0 && (
        <Alert
          type="warning"
          closable
          message="导入警告"
          description={
            <ul style={{ margin: 0, paddingLeft: 16 }}>
              {validationWarnings.map((w, i) => <li key={i}>{w}</li>)}
            </ul>
          }
        />
      )}

      <Collapse
        defaultActiveKey={['versions', 'conflicts', 'orphans']}
        bordered={false}
      >
        {/* 区块 A：料号版本变更 */}
        <Panel
          key="versions"
          header={
            <Space>
              <Text strong>料号版本变更</Text>
              <Text type="secondary" style={{ fontSize: 12 }}>
                {partVersions.length > 0
                  ? `${partVersions.length} 个料号`
                  : '无'}
              </Text>
            </Space>
          }
        >
          <PartVersionDecisionList
            items={partVersions}
            onChange={handleVersionChange}
          />
        </Panel>

        {/* 区块 B：客户冲突 */}
        <Panel
          key="conflicts"
          header={
            <Space>
              <Text strong>客户料号冲突</Text>
              <Text type="secondary" style={{ fontSize: 12 }}>
                {conflicts.length > 0 ? `${conflicts.length} 个冲突` : '无'}
              </Text>
            </Space>
          }
        >
          <CustomerConflictSection
            items={conflicts}
            onChange={handleConflictChange}
          />
        </Panel>

        {/* 区块 C：孤儿行 */}
        <Panel
          key="orphans"
          header={
            <Space>
              <Text strong>孤儿行处理</Text>
              <Text type="secondary" style={{ fontSize: 12 }}>
                {orphans.length > 0 ? `${orphans.length} 条孤儿行` : '无'}
              </Text>
            </Space>
          }
        >
          <OrphanRowsSection
            items={orphans}
            onChange={handleOrphanChange}
          />
        </Panel>
      </Collapse>
    </Space>
  );

  // ────────────────────────────────────────────────
  // 渲染 Step 3
  // ────────────────────────────────────────────────
  const renderStep3 = () => {
    const customer = customers.find((c) => c.id === customerId);
    return (
      <QuotationCreateForm
        customerId={customerId}
        customerName={customer?.name ?? ''}
        value={createForm}
        onChange={setCreateForm}
        onValidityChange={setFormValid}
      />
    );
  };

  // ────────────────────────────────────────────────
  // Drawer Footer 按钮
  // ────────────────────────────────────────────────
  const renderFooter = () => {
    if (step === 1) {
      return (
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <Button onClick={handleCloseRequest}>取消</Button>
          <Button
            type="primary"
            loading={uploading}
            disabled={!file || !customerId || uploading}
            onClick={handleUpload}
          >
            {uploading ? '分析中…' : '下一步'}
          </Button>
        </div>
      );
    }

    if (step === 2) {
      return (
        <div style={{ display: 'flex', justifyContent: 'space-between' }}>
          <Button danger onClick={handleCloseRequest}>取消并清理</Button>
          <Space>
            <Button onClick={() => setStep(1)}>上一步</Button>
            <Button type="primary" onClick={() => setStep(3)}>下一步</Button>
          </Space>
        </div>
      );
    }

    // step === 3
    return (
      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
        <Button danger onClick={handleCloseRequest}>取消并清理</Button>
        <Space>
          <Button onClick={() => setStep(2)}>上一步</Button>
          <Button
            type="primary"
            loading={committing}
            disabled={!formValid || committing}
            onClick={handleCommit}
          >
            {committing ? '创建中…' : '创建报价单'}
          </Button>
        </Space>
      </div>
    );
  };

  return (
    <Drawer
      title={title ?? '基础数据导入（V6）'}
      placement="right"
      width={960}
      open={open}
      onClose={handleCloseRequest}
      maskClosable={false}
      keyboard={false}
      destroyOnClose={false}
      footer={renderFooter()}
    >
      {/* 顶部步骤指示器 */}
      <Steps
        current={step - 1}
        size="small"
        style={{ marginBottom: 24 }}
        items={[
          { title: '上传文件' },
          { title: '版本确认' },
          { title: '创建报价单' },
        ]}
      />

      {/* 步骤内容 */}
      {step === 1 && renderStep1()}
      {step === 2 && renderStep2()}
      {step === 3 && renderStep3()}
    </Drawer>
  );
};

export default BasicDataImportV5Wizard;
