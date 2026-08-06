import React, { useState } from 'react';
import {
  Drawer, Upload, Button, Select, Space, Alert, Table, Tag, Typography, message, notification, Descriptions, Checkbox,
} from 'antd';
import { InboxOutlined, EyeOutlined, ImportOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import {
  componentService,
  type FormulaBindingItem,
  type FormulaBindingStatus,
  type BindingSummary,
  type CrossRefIssue,
} from '../../services/componentService';

const { Dragger } = Upload;
const { Text } = Typography;

interface Props {
  open: boolean;
  targetDirId: string | null;
  targetDirName?: string;
  onClose: () => void;
  /** 导入提交成功后回调(用于刷新目录树)。 */
  onImported?: () => void;
}

type ConflictPolicy = 'RENAME' | 'SKIP' | 'ABORT';

interface ComponentPlan {
  code: string;
  name: string;
  action: string;
  newCode?: string;
  conflict: boolean;
  sqlViewCount: number;
  /** 🆕 task-0805 R2：逐字段绑定去向（不含 componentCode/componentName，由本 ComponentPlan 提供上下文）。 */
  formulaBinding?: FormulaBindingItem[];
}
interface DepItem { code: string; exists: boolean; }
interface PreviewResult {
  bundleVersion: string;
  checksumValid: boolean;
  targetDirectoryName: string;
  conflictPolicy: string;
  summary: { total: number; toCreate: number; toRename: number; toSkip: number; conflicts: number };
  components: ComponentPlan[];
  dependencies: { globalVariables: DepItem[]; datasources: DepItem[]; missingCount: number };
  canCommit: boolean;
  blockers: string[];
  /**
   * 🆕 task-0805 §1.2：高优先级但不阻断（checksum 不一致 / 跨组件引用无法重映射 / 按位置推导绑定）。
   * 修既有缺陷：这三类过去被塞进 blockers 但 canCommit 仍为 true，导致用户永远看不到——
   * 本抽屉现在无条件渲染 warnings，不再依附 canCommit。
   */
  warnings?: string[];
  /** 🆕 全 bundle 绑定汇总。 */
  bindingSummary?: BindingSummary;
  /** 🆕 R5/AC-7：跨组件引用无法重映射清单（老 bundle Item.id 缺失场景）；已随人话文案并入 warnings，此处仅保留类型供未来消费。 */
  crossRefIssues?: CrossRefIssue[];
}

const ACTION_TAG: Record<string, { color: string; text: string }> = {
  CREATE: { color: 'green', text: '新建' },
  RENAME: { color: 'blue', text: '重命名' },
  SKIP: { color: 'default', text: '跳过' },
  ABORT: { color: 'red', text: '冲突中止' },
};

const BINDING_STATUS_TAG: Record<FormulaBindingStatus, { color: string; text: string }> = {
  BOUND: { color: 'green', text: '已绑定' },
  RESOLVED_BY_NAME: { color: 'blue', text: '按名称解析' },
  RESOLVED_BY_POSITION: { color: 'orange', text: '按位置推导' },
  UNRESOLVABLE: { color: 'red', text: '无法解析' },
};

type BindingRow = FormulaBindingItem & { componentCode: string; componentName: string; action: string };

const ComponentImportDrawer: React.FC<Props> = ({ open, targetDirId, targetDirName, onClose, onImported }) => {
  const [bundle, setBundle] = useState<any>(null);
  const [fileName, setFileName] = useState<string>('');
  const [policy, setPolicy] = useState<ConflictPolicy>('RENAME');
  const [loading, setLoading] = useState(false);
  const [preview, setPreview] = useState<PreviewResult | null>(null);
  const [ignoreMissing, setIgnoreMissing] = useState(false);
  // task-0805 R3：与 ignoreMissing 形状一致、互相独立的第二个显式开关。
  const [ignoreUnboundFormulas, setIgnoreUnboundFormulas] = useState(false);
  // task-0805 F2：绑定表默认只展示非 BOUND 行，避免大包糊一脸。
  const [showAllBindings, setShowAllBindings] = useState(false);
  const [committing, setCommitting] = useState(false);

  const reset = () => {
    setBundle(null); setFileName(''); setPreview(null); setPolicy('RENAME');
    setIgnoreMissing(false); setIgnoreUnboundFormulas(false); setShowAllBindings(false);
  };

  const handleClose = () => { reset(); onClose(); };

  // 读取上传的 JSON 文件(不真正上传, 前端解析后作为预览请求 body)
  const beforeUpload = (file: File) => {
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const parsed = JSON.parse(String(reader.result));
        if (!parsed || !Array.isArray(parsed.components)) {
          message.error('文件不是有效的组件导出 bundle(缺 components)');
          return;
        }
        setBundle(parsed);
        setFileName(file.name);
        setPreview(null);
        message.success(`已载入 bundle:${parsed.components.length} 个组件`);
      } catch {
        message.error('JSON 解析失败,请确认是导出的 bundle 文件');
      }
    };
    reader.readAsText(file);
    return false; // 阻止 antd 自动上传
  };

  const doPreview = async () => {
    if (!targetDirId) return message.warning('未指定目标目录');
    if (!bundle) return message.warning('请先上传 bundle 文件');
    setLoading(true);
    setIgnoreMissing(false);
    setIgnoreUnboundFormulas(false);
    try {
      const resp: any = await componentService.importPreview(targetDirId, bundle, policy);
      setPreview((resp?.data?.data ?? resp?.data) as PreviewResult);
    } catch (e: any) {
      message.error(e?.message ?? '预览失败');
    } finally {
      setLoading(false);
    }
  };

  // 逐字段绑定去向：把每个 ComponentPlan.formulaBinding 摊平，补上组件上下文，供表格 + 提交判定共用。
  const bindingRows: BindingRow[] = preview
    ? preview.components.flatMap((p) =>
        (p.formulaBinding ?? []).map((b) => ({ ...b, componentCode: p.code, componentName: p.name, action: p.action })),
      )
    : [];
  const visibleBindingRows = showAllBindings ? bindingRows : bindingRows.filter((r) => r.status !== 'BOUND');

  // 是否允许提交：
  //  ① 预览直接放行(canCommit)；
  //  ② 或者每一类阻断原因都被对应的显式开关覆盖 —— 缺依赖→ignoreMissing；未绑定公式→ignoreUnboundFormulas；
  //     ABORT 冲突没有覆盖开关，属硬阻断，两个勾选都救不了。
  const abortConflictBlock = !!preview && preview.conflictPolicy === 'ABORT' && preview.summary.conflicts > 0;
  const missingDepsBlock = !!preview && preview.dependencies.missingCount > 0;
  // 只统计"会真正落库"的组件（CREATE/RENAME）里的 UNRESOLVABLE——SKIP 的组件根本不会被导入，不该拖后腿。
  const unresolvableBlock = bindingRows.some((r) => r.status === 'UNRESOLVABLE' && r.action !== 'SKIP');
  const canSubmit = !!preview && (
    preview.canCommit
    || (!abortConflictBlock
        && (!missingDepsBlock || ignoreMissing)
        && (!unresolvableBlock || ignoreUnboundFormulas))
  );

  const doCommit = async () => {
    if (!targetDirId || !bundle || !preview) return;
    setCommitting(true);
    try {
      const resp: any = await componentService.importCommit(targetDirId, bundle, policy, ignoreMissing, ignoreUnboundFormulas);
      const r = (resp?.data?.data ?? resp?.data);
      if (r.unboundCount > 0) {
        notification.warning({
          message: '导入完成，但存在未绑定公式的字段',
          description: `新建 ${r.createdCount} 个组件(含 ${r.sqlViewsCreated} 个SQL视图)，跳过 ${r.skippedCount} 个；`
            + `其中 ${r.unboundCount} 处字段未绑定公式，已按「待绑定」标记，请前往组件管理逐一核对或使用「固化绑定」。`,
          duration: 8,
        });
      } else {
        message.success(`导入完成:新建 ${r.createdCount} 个组件(含 ${r.sqlViewsCreated} 个SQL视图)，跳过 ${r.skippedCount} 个`);
      }
      onImported?.();
      handleClose();
    } catch (e: any) {
      message.error(e?.message ?? '导入失败');
    } finally {
      setCommitting(false);
    }
  };

  const planColumns: ColumnsType<ComponentPlan> = [
    { title: '组件 code', dataIndex: 'code', width: 150 },
    { title: '名称', dataIndex: 'name', width: 140, render: (v) => v || '—' },
    { title: 'SQL视图', dataIndex: 'sqlViewCount', width: 80, align: 'center' },
    {
      title: '动作', dataIndex: 'action', width: 100,
      render: (a: string) => { const t = ACTION_TAG[a] || { color: 'default', text: a }; return <Tag color={t.color}>{t.text}</Tag>; },
    },
    {
      title: '导入后 code', dataIndex: 'newCode', width: 170,
      render: (nc: string, r) => nc ? <Text type="warning">{nc}</Text> : (r.action === 'SKIP' ? <Text type="secondary">—(跳过)</Text> : r.code),
    },
  ];

  const depColumns: ColumnsType<DepItem & { kind: string }> = [
    { title: '类型', dataIndex: 'kind', width: 100 },
    { title: 'code', dataIndex: 'code' },
    {
      title: '目标环境', dataIndex: 'exists', width: 120,
      render: (ok: boolean) => ok ? <Tag color="green">存在</Tag> : <Tag color="red">缺失</Tag>,
    },
  ];
  const depRows = preview ? [
    ...preview.dependencies.globalVariables.map((d) => ({ ...d, kind: '全局变量' })),
    ...preview.dependencies.datasources.map((d) => ({ ...d, kind: '数据源' })),
  ] : [];

  // task-0805 R2：逐字段绑定去向表——组件 / 字段 / 将绑到的公式 / 状态。
  const bindingColumns: ColumnsType<BindingRow> = [
    {
      title: '组件', dataIndex: 'componentCode', width: 170,
      render: (v: string, r) => <span>{v}{r.componentName ? <Text type="secondary"> ({r.componentName})</Text> : null}</span>,
    },
    { title: '字段', dataIndex: 'fieldName', width: 160 },
    {
      title: '将绑到的公式', dataIndex: 'resolvedFormulaName',
      render: (v: string | null, r) => v ?? (r.message ? <Text type="danger">{r.message}</Text> : '—'),
    },
    {
      title: '状态', dataIndex: 'status', width: 120,
      render: (s: FormulaBindingStatus) => { const t = BINDING_STATUS_TAG[s] || { color: 'default', text: s }; return <Tag color={t.color}>{t.text}</Tag>; },
    },
  ];

  return (
    <Drawer
      title={`导入组件到目录:${targetDirName ?? ''}`}
      placement="right"
      width={960}
      open={open}
      onClose={handleClose}
      destroyOnClose
      footer={
        <Space style={{ float: 'right' }}>
          <Button onClick={handleClose}>关闭</Button>
          <Button
            type="primary"
            icon={<ImportOutlined />}
            loading={committing}
            disabled={!canSubmit}
            onClick={doCommit}
          >
            确认导入
          </Button>
        </Space>
      }
    >
      <Space direction="vertical" style={{ width: '100%' }} size="middle">
        <Dragger accept=".json" beforeUpload={beforeUpload} maxCount={1} showUploadList={false}>
          <p className="ant-upload-drag-icon"><InboxOutlined /></p>
          <p className="ant-upload-text">点击或拖拽导出的 bundle JSON 到此处</p>
          <p className="ant-upload-hint">{fileName ? `已载入:${fileName}` : '仅支持组件目录导出的 .json'}</p>
        </Dragger>

        <Space>
          <span>冲突策略:</span>
          <Select<ConflictPolicy>
            value={policy}
            style={{ width: 220 }}
            onChange={(v) => { setPolicy(v); setPreview(null); }}
            options={[
              { value: 'RENAME', label: '重命名(冲突 code 加后缀,推荐)' },
              { value: 'SKIP', label: '跳过(同 code 已存在则跳过)' },
              { value: 'ABORT', label: '中止(任一冲突即整体中止)' },
            ]}
          />
          <Button type="primary" icon={<EyeOutlined />} loading={loading} disabled={!bundle} onClick={doPreview}>
            预览
          </Button>
        </Space>

        {preview && (
          <>
            <Descriptions size="small" column={2} bordered>
              <Descriptions.Item label="bundle 版本">{preview.bundleVersion}</Descriptions.Item>
              <Descriptions.Item label="checksum">
                {preview.checksumValid ? <Tag color="green">校验通过</Tag> : <Tag color="orange">不一致(可能被改动)</Tag>}
              </Descriptions.Item>
              <Descriptions.Item label="组件总数">{preview.summary.total}</Descriptions.Item>
              <Descriptions.Item label="计划">
                新建 {preview.summary.toCreate} · 重命名 {preview.summary.toRename} · 跳过 {preview.summary.toSkip} · 冲突 {preview.summary.conflicts}
              </Descriptions.Item>
            </Descriptions>

            {/* task-0805 §1.2：warnings 无条件渲染，不依附 canCommit——修既有缺陷
                (checksum 不一致过去混进 blockers 但 canCommit 仍为 true，用户永远看不到)。 */}
            {!!preview.warnings && preview.warnings.length > 0 && (
              <Alert
                type="warning"
                showIcon
                message="提醒(不阻断提交,建议核对)"
                description={<ul style={{ margin: 0, paddingLeft: 18 }}>{preview.warnings.map((w, i) => <li key={i}>{w}</li>)}</ul>}
              />
            )}

            {preview.canCommit
              ? <Alert type="success" showIcon message="校验通过,可提交导入" />
              : <Alert
                  type="error"
                  showIcon
                  message="存在阻止提交的问题"
                  description={
                    <>
                      <ul style={{ margin: 0, paddingLeft: 18 }}>{preview.blockers.map((b, i) => <li key={i}>{b}</li>)}</ul>
                      {missingDepsBlock && !abortConflictBlock && (
                        <Checkbox checked={ignoreMissing} onChange={(e) => setIgnoreMissing(e.target.checked)} style={{ marginTop: 8, display: 'block' }}>
                          依赖缺失仍然导入(相关字段运行时取数可能失败)
                        </Checkbox>
                      )}
                      {unresolvableBlock && !abortConflictBlock && (
                        <Checkbox checked={ignoreUnboundFormulas} onChange={(e) => setIgnoreUnboundFormulas(e.target.checked)} style={{ marginTop: 8, display: 'block' }}>
                          未绑定公式仍然导入(导入后需在组件管理中固化绑定)
                        </Checkbox>
                      )}
                    </>
                  }
                />}

            {bindingRows.length > 0 && (
              <div>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <Text strong>
                    公式绑定去向
                    {preview.bindingSummary && (
                      <Text type="secondary" style={{ fontWeight: 'normal', marginLeft: 8 }}>
                        (共 {preview.bindingSummary.totalFormulaRefs} 处 · 已绑定 {preview.bindingSummary.bound} · 按名称 {preview.bindingSummary.resolvedByName} · 按位置 {preview.bindingSummary.resolvedByPosition} · 无法解析 {preview.bindingSummary.unresolvable})
                      </Text>
                    )}
                  </Text>
                  <Checkbox checked={showAllBindings} onChange={(e) => setShowAllBindings(e.target.checked)}>
                    显示全部(含已绑定)
                  </Checkbox>
                </div>
                <Table
                  size="small"
                  rowKey={(r, i) => `${r.componentCode}-${r.fieldName}-${i}`}
                  columns={bindingColumns}
                  dataSource={visibleBindingRows}
                  pagination={false}
                  style={{ marginTop: 6 }}
                  scroll={{ y: 240 }}
                />
              </div>
            )}

            <div>
              <Text strong>依赖校验</Text>
              {depRows.length === 0
                ? <div style={{ color: '#888', padding: '4px 0' }}>无外部依赖(数据源/全局变量)</div>
                : <Table size="small" rowKey={(r) => r.kind + r.code} columns={depColumns} dataSource={depRows} pagination={false} style={{ marginTop: 6 }} />}
            </div>

            <div>
              <Text strong>组件动作计划</Text>
              <Table size="small" rowKey="code" columns={planColumns} dataSource={preview.components} pagination={false} style={{ marginTop: 6 }} scroll={{ y: 280 }} />
            </div>
          </>
        )}
      </Space>
    </Drawer>
  );
};

export default ComponentImportDrawer;
