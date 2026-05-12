import React, { useReducer, useCallback } from 'react';
import {
  Alert,
  Button,
  Drawer,
  Modal,
  Popconfirm,
  Result,
  Select,
  Space,
  Spin,
  Steps,
  Typography,
  Upload,
} from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import type { UploadFile } from 'antd/es/upload/interface';
import { basicDataImportV5Service } from '../../services/basicDataImportV5Service';
import type {
  ImportResultDTOV5,
  ResolutionDTO,
} from '../../types/import-v5';
import BasicDataDiffDrawer from './BasicDataDiffDrawer';
import CustomerConflictDrawer from './CustomerConflictDrawer';
import OrphanRowsDrawer from './OrphanRowsDrawer';

const { Text, Title } = Typography;
const { Dragger } = Upload;
const { Option } = Select;

// ────────────────────────────────────────────────
// 状态机类型
// ────────────────────────────────────────────────
type Step =
  | 'UPLOAD'
  | 'PREVIEW_LOADING'
  | 'UI2'
  | 'UI1'
  | 'UI3'
  | 'CONFIRMING'
  | 'DONE'
  | 'ERROR';

interface State {
  step: Step;
  file: File | null;
  customerId: string;
  previewResult: ImportResultDTOV5 | null;
  basicResolutions: Map<string, ResolutionDTO>;
  customerResolutions: Map<string, ResolutionDTO>;
  orphanResolutions: ResolutionDTO[];
  importRecordId?: string;
  error?: { code: number; message: string };
}

type Action =
  | { type: 'SET_FILE'; file: File | null }
  | { type: 'SET_CUSTOMER'; customerId: string }
  | { type: 'START_PREVIEW' }
  | { type: 'PREVIEW_SUCCESS'; result: ImportResultDTOV5 }
  | { type: 'PREVIEW_ERROR'; code: number; message: string }
  | { type: 'UI2_CONFIRM' }
  | { type: 'UI1_CONFIRM' }
  | { type: 'UI3_CONFIRM'; orphanResolutions: ResolutionDTO[] }
  | { type: 'START_CONFIRMING' }
  | { type: 'CONFIRM_SUCCESS'; importRecordId: string }
  | { type: 'CONFIRM_ERROR'; code: number; message: string }
  | { type: 'SET_BASIC_RESOLUTION'; key: string; res: ResolutionDTO }
  | { type: 'SET_CUSTOMER_RESOLUTION'; key: string; res: ResolutionDTO }
  | { type: 'RESET' };

const initialState: State = {
  step: 'UPLOAD',
  file: null,
  customerId: '',
  previewResult: null,
  basicResolutions: new Map(),
  customerResolutions: new Map(),
  orphanResolutions: [],
};

function reducer(state: State, action: Action): State {
  switch (action.type) {
    case 'SET_FILE':
      return { ...state, file: action.file };
    case 'SET_CUSTOMER':
      return { ...state, customerId: action.customerId };
    case 'START_PREVIEW':
      return { ...state, step: 'PREVIEW_LOADING', error: undefined };
    case 'PREVIEW_SUCCESS': {
      const result = action.result;
      // 后端有可能返回 null,统一规整为数组
      const safeResult: ImportResultDTOV5 = {
        ...result,
        basicDataDiffs: result.basicDataDiffs ?? [],
        customerDataConflicts: result.customerDataConflicts ?? [],
        orphanRows: result.orphanRows ?? [],
        validation: result.validation ?? { hasErrors: false, errors: [], warnings: [] },
      };
      // 校验失败时不进入差异/冲突/写入流程,直接转 ERROR 展示错误
      if (safeResult.validation.hasErrors) {
        const errs = safeResult.validation.errors ?? [];
        const msg = errs.length > 0
          ? errs.map((e: any) =>
              typeof e === 'string'
                ? e
                : `[${e.bvCode ?? ''}] ${e.sheet ?? ''}${e.row ? ' 行 ' + e.row : ''} ${e.message ?? JSON.stringify(e)}`
            ).join('\n')
          : '校验未通过,请检查 Excel 数据';
        return {
          ...state,
          step: 'ERROR',
          previewResult: safeResult,
          error: { code: 400, message: msg },
        };
      }
      // 流程：UI2(基础差异) → UI1(客户冲突) → UI3(孤儿行) → CONFIRMING
      let nextStep: Step = 'CONFIRMING';
      if (safeResult.basicDataDiffs.length > 0) {
        nextStep = 'UI2';
      } else if (safeResult.customerDataConflicts.length > 0) {
        nextStep = 'UI1';
      } else if (safeResult.orphanRows.length > 0) {
        nextStep = 'UI3';
      }
      return {
        ...state,
        step: nextStep,
        previewResult: safeResult,
        basicResolutions: new Map(),
        customerResolutions: new Map(),
        orphanResolutions: [],
      };
    }
    case 'PREVIEW_ERROR':
      return {
        ...state,
        step: 'ERROR',
        error: { code: action.code, message: action.message },
      };
    case 'UI2_CONFIRM': {
      const hasConflicts = (state.previewResult?.customerDataConflicts?.length ?? 0) > 0;
      const hasOrphans = (state.previewResult?.orphanRows?.length ?? 0) > 0;
      let nextStep: Step = 'CONFIRMING';
      if (hasConflicts) nextStep = 'UI1';
      else if (hasOrphans) nextStep = 'UI3';
      return { ...state, step: nextStep };
    }
    case 'UI1_CONFIRM': {
      const hasOrphans = (state.previewResult?.orphanRows?.length ?? 0) > 0;
      return { ...state, step: hasOrphans ? 'UI3' : 'CONFIRMING' };
    }
    case 'UI3_CONFIRM':
      return { ...state, step: 'CONFIRMING', orphanResolutions: action.orphanResolutions };
    case 'START_CONFIRMING':
      return { ...state, step: 'CONFIRMING', error: undefined };
    case 'CONFIRM_SUCCESS':
      return { ...state, step: 'DONE', importRecordId: action.importRecordId };
    case 'CONFIRM_ERROR':
      return {
        ...state,
        step: 'ERROR',
        error: { code: action.code, message: action.message },
      };
    case 'SET_BASIC_RESOLUTION': {
      const m = new Map(state.basicResolutions);
      m.set(action.key, action.res);
      return { ...state, basicResolutions: m };
    }
    case 'SET_CUSTOMER_RESOLUTION': {
      const m = new Map(state.customerResolutions);
      m.set(action.key, action.res);
      return { ...state, customerResolutions: m };
    }
    case 'RESET':
      return { ...initialState, customerId: state.customerId, orphanResolutions: [] };
    default:
      return state;
  }
}

// ────────────────────────────────────────────────
// 步骤序号映射（供 Steps 组件显示）
// ────────────────────────────────────────────────
function stepIndex(step: Step): number {
  switch (step) {
    case 'UPLOAD':
    case 'PREVIEW_LOADING':
      return 0;
    case 'UI2':
      return 1;
    case 'UI1':
      return 2;
    case 'UI3':
      return 3;
    case 'CONFIRMING':
      return 4;
    case 'DONE':
      return 5;
    case 'ERROR':
      return 0;
    default:
      return 0;
  }
}

// ────────────────────────────────────────────────
// Props
// ────────────────────────────────────────────────
interface BasicDataImportV5WizardProps {
  open: boolean;
  customers: { id: string; name: string }[];
  defaultCustomerId?: string;
  onClose: () => void;
  onSuccess?: (importRecordId: string, customerId: string) => void;
  /** V90: 允许调用方覆盖 Drawer 标题 (默认 "V5 增强导入向导", 核价基础数据导入用 "核价基础数据 Excel 导入") */
  title?: string;
  /** V90: 隐藏客户选择器(核价基础数据全局,不绑客户)。开启时自动用首个可用 customer 兜底, 上传不需要再选 */
  hideCustomer?: boolean;
  /** V94: 'QUOTATION' (报价单基础数据) / 'COSTING' (核价基础数据)。后端按此选择同名 sheet 的不同配置。默认 QUOTATION */
  templateKind?: 'QUOTATION' | 'COSTING';
}

// ────────────────────────────────────────────────
// 组件主体
// ────────────────────────────────────────────────
const BasicDataImportV5Wizard: React.FC<BasicDataImportV5WizardProps> = ({
  open,
  customers,
  defaultCustomerId,
  onClose,
  onSuccess,
  title,
  hideCustomer,
  templateKind,
}) => {
  const requestKind = templateKind ?? 'QUOTATION';

  // V90: hideCustomer=true 时, 用首个 customer 兜底(核价数据不依赖 customer_id, 仅满足 V5 service 必填参数)
  const initialCustomerId = defaultCustomerId
    ?? (hideCustomer && customers.length > 0 ? customers[0].id : '');
  const [state, dispatch] = useReducer(reducer, {
    ...initialState,
    customerId: initialCustomerId,
  });

  const { step, file, customerId, previewResult, basicResolutions, customerResolutions, orphanResolutions } = state;

  // hideCustomer 模式下, 当 customers 列表异步加载完时, 兜底 customerId 仍未设置 → 自动取首条
  React.useEffect(() => {
    if (hideCustomer && !customerId && customers.length > 0) {
      dispatch({ type: 'SET_CUSTOMER', customerId: customers[0].id });
    }
  }, [hideCustomer, customerId, customers]);

  // 文件上传列表（受控）
  const fileList: UploadFile[] = file
    ? [{ uid: '1', name: file.name, status: 'done', size: file.size } as UploadFile]
    : [];

  // 执行预览
  const handlePreview = useCallback(async () => {
    if (!file || !customerId) return;
    dispatch({ type: 'START_PREVIEW' });
    try {
      const result = await basicDataImportV5Service.preview(file, customerId, requestKind);
      dispatch({ type: 'PREVIEW_SUCCESS', result });
    } catch (err: any) {
      dispatch({
        type: 'PREVIEW_ERROR',
        code: err?.response?.status ?? 500,
        message: err?.message ?? '预览失败，请重试',
      });
    }
  }, [file, customerId, requestKind]);

  // 执行确认导入
  const handleConfirm = useCallback(async () => {
    if (!file || !customerId) return;
    const allResolutions = [
      ...Array.from(basicResolutions.values()),
      ...Array.from(customerResolutions.values()),
      ...orphanResolutions,
    ];
    // B2: 料号版本决策 — preview 返回的 partVersionPreview 默认全部 BUMP (升版)
    // 后续 PR 可加 UI 让用户按料号选 BUMP / NO_CHANGE / SKIP, 当前自动升所有料号
    const partVersionDecisions: Record<string, string> = {};
    const previewItems = previewResult?.partVersionPreview ?? [];
    for (const item of previewItems) {
      partVersionDecisions[`${item.customerProductNo}|${item.hfPartNo}`] = 'BUMP';
    }
    try {
      const result = await basicDataImportV5Service.confirm(
        file, customerId, allResolutions, requestKind, partVersionDecisions
      );
      dispatch({
        type: 'CONFIRM_SUCCESS',
        importRecordId: result.importRecordId ?? '',
      });
      onSuccess?.(result.importRecordId ?? '', customerId);
    } catch (err: any) {
      const code = err?.response?.status ?? 500;
      if (code === 409) {
        // 数据已被他人修改
        Modal.warning({
          title: '数据已被他人修改',
          content: '检测到数据在预览后已被他人修改，请重新预览以获取最新状态。',
          okText: '重新预览',
          onOk: () => dispatch({ type: 'RESET' }),
        });
      } else {
        dispatch({
          type: 'CONFIRM_ERROR',
          code,
          message: err?.message ?? '导入失败，请重试',
        });
      }
    }
  }, [file, customerId, basicResolutions, customerResolutions, orphanResolutions, onSuccess, requestKind]);

  // 关闭向导（带确认）
  const handleClose = () => {
    if (step === 'UPLOAD' || step === 'DONE' || step === 'ERROR') {
      onClose();
    }
    // 其他步骤由 Popconfirm 处理
  };

  // CONFIRMING 步骤自动触发
  React.useEffect(() => {
    if (step === 'CONFIRMING') {
      handleConfirm();
    }
  }, [step, handleConfirm]);

  // ────────────────────────────────────────────────
  // UPLOAD 步骤内容
  // ────────────────────────────────────────────────
  const renderUploadStep = () => (
    <Space direction="vertical" style={{ width: '100%' }} size={16}>
      {!hideCustomer && (
        <div>
          <Text strong>选择客户</Text>
          <Select
            style={{ width: '100%', marginTop: 8 }}
            placeholder="请选择客户"
            value={customerId || undefined}
            onChange={(val) => dispatch({ type: 'SET_CUSTOMER', customerId: val })}
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
      )}

      <div>
        <Text strong>上传 Excel 文件</Text>
        <Dragger
          style={{ marginTop: 8 }}
          accept=".xlsx,.xls"
          beforeUpload={(f) => {
            dispatch({ type: 'SET_FILE', file: f });
            return false; // 阻止自动上传
          }}
          onRemove={() => dispatch({ type: 'SET_FILE', file: null })}
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

      <Button
        type="primary"
        block
        disabled={!file || !customerId}
        onClick={handlePreview}
      >
        开始预览 & 差异分析
      </Button>
    </Space>
  );

  // ────────────────────────────────────────────────
  // DONE 步骤内容
  // ────────────────────────────────────────────────
  const renderDoneStep = () => (
    <Result
      status="success"
      title="基础数据导入成功"
      subTitle={
        state.importRecordId
          ? `导入记录 ID：${state.importRecordId}，数据已写入系统。`
          : '数据已成功写入系统。'
      }
      extra={[
        <Button key="close" type="primary" onClick={onClose}>
          关闭
        </Button>,
        <Button key="again" onClick={() => dispatch({ type: 'RESET' })}>
          再次导入
        </Button>,
      ]}
    />
  );

  // ────────────────────────────────────────────────
  // ERROR 步骤内容
  // ────────────────────────────────────────────────
  const renderErrorStep = () => (
    <Result
      status="error"
      title="操作失败"
      subTitle={state.error?.message ?? '未知错误'}
      extra={[
        <Button key="retry" type="primary" onClick={() => dispatch({ type: 'RESET' })}>
          重新开始
        </Button>,
        <Button key="close" onClick={onClose}>
          关闭
        </Button>,
      ]}
    />
  );

  // ────────────────────────────────────────────────
  // 主抽屉内容
  // ────────────────────────────────────────────────
  const renderMainContent = () => {
    if (step === 'PREVIEW_LOADING') {
      return (
        <div style={{ textAlign: 'center', padding: '60px 0' }}>
          <Spin size="large" tip="正在分析差异，请稍候…" />
        </div>
      );
    }
    if (step === 'CONFIRMING') {
      return (
        <div style={{ textAlign: 'center', padding: '60px 0' }}>
          <Spin size="large" tip="正在写入数据，请稍候…" />
        </div>
      );
    }
    if (step === 'DONE') return renderDoneStep();
    if (step === 'ERROR') return renderErrorStep();
    // UPLOAD / UI2 / UI1 — 主抽屉始终显示上传步骤，子抽屉叠加
    return renderUploadStep();
  };

  // 主抽屉 close 按钮（带 Popconfirm）
  const needConfirmClose = step !== 'UPLOAD' && step !== 'DONE' && step !== 'ERROR';
  const closeIcon = needConfirmClose ? (
    <Popconfirm
      title="未保存的决策将丢失，确认关闭向导？"
      onConfirm={onClose}
      okText="确认关闭"
      cancelText="继续"
      placement="bottomLeft"
    >
      <span style={{ cursor: 'pointer' }}>×</span>
    </Popconfirm>
  ) : undefined;

  return (
    <>
      {/* 主容器 Drawer */}
      <Drawer
        title={title ?? "V5 增强导入向导"}
        placement="right"
        width={720}
        open={open}
        onClose={needConfirmClose ? undefined : handleClose}
        closeIcon={closeIcon}
        destroyOnClose={false}
      >
        {/* 步骤指示器 */}
        <Steps
          current={stepIndex(step)}
          size="small"
          style={{ marginBottom: 24 }}
          items={[
            { title: '上传文件' },
            { title: '差异确认' },
            { title: '冲突解决' },
            { title: '孤儿处理' },
            { title: '写入数据' },
            { title: '完成' },
          ]}
        />

        {/* 预览校验警告 */}
        {previewResult?.validation?.warnings?.length ? (
          <Alert
            type="warning"
            closable
            style={{ marginBottom: 16 }}
            message="预览警告"
            description={
              <ul style={{ margin: 0, paddingLeft: 16 }}>
                {previewResult.validation.warnings.map((w: any, i: number) => (
                  <li key={i}>
                    {typeof w === 'string'
                      ? w
                      : `[${w.bvCode ?? ''}] ${w.sheet ?? ''}${w.row ? ' 行 ' + w.row : ''} ${w.message ?? ''}`}
                  </li>
                ))}
              </ul>
            }
          />
        ) : null}

        {renderMainContent()}
      </Drawer>

      {/* UI-2 基础数据差异抽屉（叠加在主抽屉右侧） */}
      <BasicDataDiffDrawer
        open={step === 'UI2'}
        diffs={previewResult?.basicDataDiffs ?? []}
        resolutions={basicResolutions}
        onChange={(key, res) => dispatch({ type: 'SET_BASIC_RESOLUTION', key, res })}
        onConfirm={() => dispatch({ type: 'UI2_CONFIRM' })}
        onCancel={onClose}
      />

      {/* UI-1 客户冲突抽屉（叠加在最右侧） */}
      <CustomerConflictDrawer
        open={step === 'UI1'}
        conflicts={previewResult?.customerDataConflicts ?? []}
        resolutions={customerResolutions}
        onChange={(key, res) => dispatch({ type: 'SET_CUSTOMER_RESOLUTION', key, res })}
        onConfirm={() => dispatch({ type: 'UI1_CONFIRM' })}
        onCancel={onClose}
      />

      {/* UI-3 孤儿行处理抽屉（叠加在最右侧） */}
      <OrphanRowsDrawer
        open={step === 'UI3'}
        orphans={previewResult?.orphanRows ?? []}
        onClose={onClose}
        onConfirm={(resolutions) => dispatch({ type: 'UI3_CONFIRM', orphanResolutions: resolutions })}
      />
    </>
  );
};

export default BasicDataImportV5Wizard;
