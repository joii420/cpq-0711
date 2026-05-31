import React, { useState } from 'react';
import {
  Drawer, Upload, Button, Select, Space, Alert, Table, Tag, Typography, message, Descriptions, Checkbox,
} from 'antd';
import { InboxOutlined, EyeOutlined, ImportOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { componentService } from '../../services/componentService';

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
}

const ACTION_TAG: Record<string, { color: string; text: string }> = {
  CREATE: { color: 'green', text: '新建' },
  RENAME: { color: 'blue', text: '重命名' },
  SKIP: { color: 'default', text: '跳过' },
  ABORT: { color: 'red', text: '冲突中止' },
};

const ComponentImportDrawer: React.FC<Props> = ({ open, targetDirId, targetDirName, onClose, onImported }) => {
  const [bundle, setBundle] = useState<any>(null);
  const [fileName, setFileName] = useState<string>('');
  const [policy, setPolicy] = useState<ConflictPolicy>('RENAME');
  const [loading, setLoading] = useState(false);
  const [preview, setPreview] = useState<PreviewResult | null>(null);
  const [ignoreMissing, setIgnoreMissing] = useState(false);
  const [committing, setCommitting] = useState(false);

  const reset = () => { setBundle(null); setFileName(''); setPreview(null); setPolicy('RENAME'); setIgnoreMissing(false); };

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
    try {
      const resp: any = await componentService.importPreview(targetDirId, bundle, policy);
      setPreview((resp?.data?.data ?? resp?.data) as PreviewResult);
    } catch (e: any) {
      message.error(e?.message ?? '预览失败');
    } finally {
      setLoading(false);
    }
  };

  // 是否允许提交:预览通过(canCommit) 或 仅缺依赖且勾选了"仍然导入"(且非 ABORT 冲突场景)
  const missingOnly = !!preview
    && preview.dependencies.missingCount > 0
    && !(preview.conflictPolicy === 'ABORT' && preview.summary.conflicts > 0);
  const canSubmit = !!preview && (preview.canCommit || (missingOnly && ignoreMissing));

  const doCommit = async () => {
    if (!targetDirId || !bundle || !preview) return;
    setCommitting(true);
    try {
      const resp: any = await componentService.importCommit(targetDirId, bundle, policy, ignoreMissing);
      const r = (resp?.data?.data ?? resp?.data);
      message.success(`导入完成:新建 ${r.createdCount} 个组件(含 ${r.sqlViewsCreated} 个SQL视图)，跳过 ${r.skippedCount} 个`);
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

  return (
    <Drawer
      title={`导入组件到目录:${targetDirName ?? ''}`}
      placement="right"
      width={760}
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

            {preview.canCommit
              ? <Alert type="success" showIcon message="校验通过,可提交导入" />
              : <Alert
                  type="error"
                  showIcon
                  message="存在阻止提交的问题"
                  description={
                    <>
                      <ul style={{ margin: 0, paddingLeft: 18 }}>{preview.blockers.map((b, i) => <li key={i}>{b}</li>)}</ul>
                      {missingOnly && (
                        <Checkbox checked={ignoreMissing} onChange={(e) => setIgnoreMissing(e.target.checked)} style={{ marginTop: 8 }}>
                          依赖缺失仍然导入(相关字段运行时取数可能失败)
                        </Checkbox>
                      )}
                    </>
                  }
                />}

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
