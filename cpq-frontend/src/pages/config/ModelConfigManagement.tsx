import React, { useCallback, useEffect, useState } from 'react';
import { Card, Tabs, Drawer, Button, Upload, AutoComplete, Input, Tag, message, Tooltip, Empty } from 'antd';
import { UploadOutlined, CheckCircleOutlined, HistoryOutlined, DeleteOutlined, InboxOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import SelectableTable, { runBatch, type ToolbarAction } from '../../components/SelectableTable';
import { modelConfigService } from '../../services/modelConfigService';
import type { ModelConfigDTO, ModelSubjectType } from '../../types/modelConfig';
import { materialMasterService } from '../../services/materialMasterService';
import { materialRecipeService, type MaterialRecipeLite } from '../../services/materialRecipeService';

// 1:1 对齐 dev-docs/task-0712-选配模板和报价单选配功能/prototypes/原型-配置中心-3D模型配置.html
// 缩略图渐变色板（无 thumbnailUrl 时按 subjectKey 哈希取一个稳定渐变占位色块，对齐原型 GRADIENTS/hashVariant）
const GRADIENTS = [
  'linear-gradient(135deg,#e6f0ff,#dfe7f5)',
  'linear-gradient(135deg,#fff1e6,#f5e4df)',
  'linear-gradient(135deg,#e6fff2,#dff5ec)',
  'linear-gradient(135deg,#f5e6ff,#ece0f5)',
  'linear-gradient(135deg,#fff9e6,#f5efdf)',
];
function hashVariant(key: string): number {
  let h = 0;
  for (let i = 0; i < key.length; i++) h = (h * 31 + key.charCodeAt(i)) >>> 0;
  return h % GRADIENTS.length;
}
const fmtTime = (v?: string | null) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '—');
const fmtSize = (kb?: number | null) => {
  if (kb == null) return '—';
  return kb >= 1024 ? `${(kb / 1024).toFixed(1)}MB` : `${kb}KB`;
};
const fmtFileSize = (bytes: number) => `${(bytes / 1024 / 1024).toFixed(1)}MB`;
const formatRowLabel = (r: ModelConfigDTO) =>
  `${r.subjectKey}${r.subjectLabel ? '／' + r.subjectLabel : ''} · ${r.label || '未命名'} · v${r.version}${r.isCurrent ? '（当前版本）' : ''}`;

/** 40x40 缩略图：有 thumbnailUrl 用真图，否则按 subjectKey 哈希取渐变占位块 + 🧊。 */
const ThumbMini: React.FC<{ url?: string | null; subjectKey: string }> = ({ url, subjectKey }) => (
  <div
    style={{
      width: 40,
      height: 40,
      borderRadius: 6,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      fontSize: 18,
      color: '#7a8aa8',
      background: url ? `url(${url}) center/cover no-repeat` : GRADIENTS[hashVariant(subjectKey)],
    }}
  >
    {!url && '🧊'}
  </div>
);

interface HistorySubject {
  subjectType: ModelSubjectType;
  subjectKey: string;
  subjectLabel?: string | null;
}

const ModelConfigManagement: React.FC = () => {
  const [activeTab, setActiveTab] = useState<ModelSubjectType>('SALES_PART');
  const [list, setList] = useState<ModelConfigDTO[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(0); // 0-indexed，对齐后端 ModelConfigResource#list 默认值
  const [pageSize, setPageSize] = useState(10);

  // 详情/预览抽屉
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailRow, setDetailRow] = useState<ModelConfigDTO | null>(null);
  const [zoomHint, setZoomHint] = useState(false);

  // 历史版本抽屉
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historySubject, setHistorySubject] = useState<HistorySubject | null>(null);
  const [historyList, setHistoryList] = useState<ModelConfigDTO[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);

  // 上传抽屉
  const [uploadOpen, setUploadOpen] = useState(false);
  const [bindValue, setBindValue] = useState('');
  const [glbFile, setGlbFile] = useState<File | null>(null);
  const [thumbFile, setThumbFile] = useState<File | null>(null);
  const [thumbPreviewUrl, setThumbPreviewUrl] = useState<string | null>(null);
  const [modelName, setModelName] = useState('');
  const [submitting, setSubmitting] = useState<'current' | 'history' | null>(null);

  // 绑定对象候选（D14 可输入过滤，AutoComplete = HTML 原型 <input list=datalist> 的等价物）
  const [salesPartCandidates, setSalesPartCandidates] = useState<string[]>([]);
  const [materialCandidates, setMaterialCandidates] = useState<MaterialRecipeLite[]>([]);
  const [candidatesLoading, setCandidatesLoading] = useState(false);

  const fetchList = useCallback(async () => {
    setLoading(true);
    try {
      const res = await modelConfigService.list({ subjectType: activeTab, page, size: pageSize });
      setList(res.content ?? []);
      setTotal(res.totalElements ?? 0);
    } catch (e: any) {
      message.error(e.message ?? '加载失败');
    } finally {
      setLoading(false);
    }
  }, [activeTab, page, pageSize]);

  useEffect(() => { fetchList(); }, [fetchList]);

  const handleTabChange = (key: string) => {
    setActiveTab(key as ModelSubjectType);
    setPage(0);
  };

  // ── 详情/预览抽屉 ──
  const openDetail = (row: ModelConfigDTO) => {
    setDetailRow(row);
    setZoomHint(false);
    setDetailOpen(true);
  };

  // ── 历史版本抽屉 ──
  const openHistoryFor = async (subject: HistorySubject) => {
    setHistorySubject(subject);
    setHistoryOpen(true);
    setHistoryLoading(true);
    try {
      const rows = await modelConfigService.versions({ subjectType: subject.subjectType, subjectKey: subject.subjectKey });
      setHistoryList([...rows].sort((a, b) => b.version - a.version));
    } catch (e: any) {
      message.error(e.message ?? '历史版本加载失败');
    } finally {
      setHistoryLoading(false);
    }
  };

  const setCurrentFromHistory = async (row: ModelConfigDTO) => {
    if (row.isCurrent) return;
    try {
      await modelConfigService.setCurrent(row.id);
      message.success(`已将 ${row.subjectKey} v${row.version} 设为当前版本`);
      if (historySubject) await openHistoryFor(historySubject);
      fetchList();
    } catch (e: any) {
      message.error(e.message ?? '操作失败');
    }
  };

  // ── 上传抽屉 ──
  const ensureCandidates = useCallback(async (tab: ModelSubjectType) => {
    if (tab === 'SALES_PART' && salesPartCandidates.length > 0) return;
    if (tab === 'MATERIAL' && materialCandidates.length > 0) return;
    setCandidatesLoading(true);
    try {
      if (tab === 'SALES_PART') {
        const res: any = await materialMasterService.list({ page: 0, size: 200 });
        const rows: Array<{ materialNo: string }> = res?.data?.content ?? [];
        setSalesPartCandidates(Array.from(new Set(rows.map((r) => r.materialNo))));
      } else {
        const rows = await materialRecipeService.list();
        setMaterialCandidates(rows ?? []);
      }
    } catch (e: any) {
      message.error(e.message ?? '候选加载失败');
    } finally {
      setCandidatesLoading(false);
    }
  }, [salesPartCandidates.length, materialCandidates.length]);

  const resetUploadForm = () => {
    setBindValue('');
    setGlbFile(null);
    setThumbFile(null);
    setThumbPreviewUrl(null);
    setModelName('');
  };

  const openUploadDrawer = () => {
    resetUploadForm();
    setUploadOpen(true);
    ensureCandidates(activeTab);
  };

  const resolveSubjectKey = (): string | null => {
    const raw = bindValue.trim();
    if (!raw) return null;
    if (activeTab === 'MATERIAL') {
      const matched = materialCandidates.find(
        (m) => raw === `${m.name}（${m.code}）` || raw === m.code || raw === m.name,
      );
      return matched ? matched.code : raw;
    }
    return raw;
  };

  const handleGlbSelect = (file: File): boolean => {
    if (!/\.glb$/i.test(file.name)) {
      message.error('仅支持 .glb 格式的模型文件');
      return false;
    }
    setGlbFile(file);
    if (!modelName.trim()) setModelName(file.name.replace(/\.glb$/i, ''));
    return false; // 阻止 AntD Upload 自动上传，提交时由 submitUpload 统一走 modelConfigService.upload
  };

  const handleThumbSelect = (file: File): boolean => {
    setThumbFile(file);
    setThumbPreviewUrl(URL.createObjectURL(file));
    return false;
  };

  const doSubmit = async (setCurrent: boolean) => {
    const subjectKey = resolveSubjectKey();
    if (!subjectKey) {
      message.error(activeTab === 'SALES_PART' ? '请填写绑定的销售料号' : '请填写绑定的材质配方');
      return;
    }
    if (!glbFile) {
      message.error('请先选择 .glb 模型文件');
      return;
    }
    const trimmedName = modelName.trim();
    if (!trimmedName) {
      message.error('请填写模型名');
      return;
    }
    setSubmitting(setCurrent ? 'current' : 'history');
    try {
      const created = await modelConfigService.upload({
        subjectType: activeTab,
        subjectKey,
        label: trimmedName,
        glbFile,
        thumbnailFile: thumbFile ?? undefined,
        setCurrent,
      });
      message.success(
        `✓ 上传成功：${created.subjectKey} · ${created.label ?? trimmedName} v${created.version}，已保存为${setCurrent ? '当前版本' : '历史版本'}`,
      );
      setUploadOpen(false);
      setPage(0);
      fetchList();
    } catch (e: any) {
      message.error(e.message ?? '上传失败');
    } finally {
      setSubmitting(null);
    }
  };

  // ── 列表工具栏动作（选择驱动，对齐 docs/列表操作规范.md） ──
  const actions: ToolbarAction<ModelConfigDTO>[] = [
    {
      key: 'set-current',
      label: '设为当前版本',
      icon: <CheckCircleOutlined />,
      enabledWhen: (sel) => {
        if (sel.length !== 1) return '请先选择 1 条记录';
        return sel[0].isCurrent ? '该版本已是当前版本' : true;
      },
      onClick: async (sel) => {
        const row = sel[0];
        await modelConfigService.setCurrent(row.id);
        message.success(`已将 ${row.subjectKey} v${row.version} 设为当前版本`);
        fetchList();
      },
    },
    {
      key: 'history',
      label: '查看历史版本',
      icon: <HistoryOutlined />,
      enabledWhen: (sel) => (sel.length === 1 ? true : '请先选择 1 条记录查看历史版本'),
      onClick: async (sel) => {
        const row = sel[0];
        await openHistoryFor({ subjectType: activeTab, subjectKey: row.subjectKey, subjectLabel: row.subjectLabel });
      },
    },
    {
      key: 'delete',
      label: '删除',
      icon: <DeleteOutlined />,
      danger: true,
      enabledWhen: (sel) => (sel.length > 0 ? true : '请先选择要删除的记录'),
      needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 条模型版本记录？',
      confirmDescription: '删除后不可恢复。',
      onClick: async (sel) => {
        await runBatch(sel, (r) => modelConfigService.remove(r.id), {
          rowLabel: formatRowLabel,
          successMsg: `已删除 ${sel.length} 条模型版本记录`,
        });
        fetchList();
      },
    },
  ];

  const statusTag = (row: ModelConfigDTO) => (
    <Tag color={row.isCurrent ? 'success' : 'default'}>{row.isCurrent ? '当前' : '历史'}</Tag>
  );

  const columnsSales: ColumnsType<ModelConfigDTO> = [
    {
      title: '销售料号',
      dataIndex: 'subjectKey',
      key: 'subjectKey',
      render: (v: string, r) => <a onClick={(e) => { e.stopPropagation(); openDetail(r); }}>{v}</a>,
    },
    { title: '模型名', dataIndex: 'label', key: 'label', render: (v: string | null) => v || '—' },
    { title: '当前版本', dataIndex: 'version', key: 'version', render: (v: number) => `v${v}` },
    { title: '缩略图', key: 'thumb', render: (_: any, r) => <ThumbMini url={r.thumbnailUrl} subjectKey={r.subjectKey} /> },
    { title: '大小', dataIndex: 'sizeKb', key: 'sizeKb', render: (v: number | null) => fmtSize(v) },
    { title: '上传时间', dataIndex: 'uploadedAt', key: 'uploadedAt', render: (v: string | null) => fmtTime(v) },
    { title: '状态', key: 'status', render: (_: any, r) => statusTag(r) },
  ];

  const columnsMaterial: ColumnsType<ModelConfigDTO> = [
    {
      title: '材质配方码',
      dataIndex: 'subjectKey',
      key: 'subjectKey',
      render: (v: string, r) => <a onClick={(e) => { e.stopPropagation(); openDetail(r); }}>{v}</a>,
    },
    { title: '材质名', dataIndex: 'subjectLabel', key: 'subjectLabel', render: (v: string | null) => v || '—' },
    { title: '模型名', dataIndex: 'label', key: 'label', render: (v: string | null) => v || '—' },
    { title: '当前版本', dataIndex: 'version', key: 'version', render: (v: number) => `v${v}` },
    { title: '缩略图', key: 'thumb', render: (_: any, r) => <ThumbMini url={r.thumbnailUrl} subjectKey={r.subjectKey} /> },
    { title: '大小', dataIndex: 'sizeKb', key: 'sizeKb', render: (v: number | null) => fmtSize(v) },
    { title: '上传时间', dataIndex: 'uploadedAt', key: 'uploadedAt', render: (v: string | null) => fmtTime(v) },
    { title: '状态', key: 'status', render: (_: any, r) => statusTag(r) },
  ];

  const emptyText = `暂无${activeTab === 'SALES_PART' ? '销售料号' : '材质'}模型，点击左上「上传模型」新增`;

  return (
    <Card title="3D 模型配置">
      <div style={{ color: '#909399', fontSize: 12.5, marginTop: -8, marginBottom: 14 }}>
        按销售料号 / 材质配方维护 3D 模型文件（.glb + 预览图），供报价单「从已有产品添加」「选配添加」两个抽屉实时带出 3D 预览使用。
      </div>

      <Tabs
        activeKey={activeTab}
        onChange={handleTabChange}
        items={[
          { key: 'SALES_PART', label: '销售料号模型' },
          { key: 'MATERIAL', label: '材质模型' },
        ]}
      />

      <SelectableTable<ModelConfigDTO>
        key={activeTab}
        rowKey="id"
        columns={activeTab === 'SALES_PART' ? columnsSales : columnsMaterial}
        dataSource={list}
        loading={loading}
        pagination={{
          current: page + 1,
          pageSize,
          total,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, s) => { setPage(p - 1); setPageSize(s); },
        }}
        toolbar={<Button type="primary" icon={<UploadOutlined />} onClick={openUploadDrawer}>上传模型</Button>}
        actions={actions}
        rowLabel={formatRowLabel}
        locale={{
          emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={emptyText} style={{ padding: '32px 0' }} />,
        }}
      />

      {/* 上传抽屉（720，对齐原型 §4.3） */}
      <Drawer
        title="上传 3D 模型"
        width={720}
        open={uploadOpen}
        onClose={() => setUploadOpen(false)}
        destroyOnClose
        footer={
          <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
            <Button onClick={() => setUploadOpen(false)}>取消</Button>
            <Button loading={submitting === 'history'} disabled={submitting === 'current'} onClick={() => doSubmit(false)}>
              仅上传为历史版本
            </Button>
            <Button type="primary" loading={submitting === 'current'} disabled={submitting === 'history'} onClick={() => doSubmit(true)}>
              上传并设为当前
            </Button>
          </div>
        }
      >
        <div style={{ marginBottom: 16 }}>
          <div style={{ marginBottom: 6, color: '#606266', fontSize: 12.5 }}>
            {activeTab === 'SALES_PART' ? <span>绑定销售料号 <span style={{ color: '#f56c6c' }}>*</span></span> : <span>绑定材质配方 <span style={{ color: '#f56c6c' }}>*</span></span>}
          </div>
          {activeTab === 'SALES_PART' ? (
            <AutoComplete
              style={{ width: '100%' }}
              value={bindValue}
              onChange={setBindValue}
              options={salesPartCandidates.map((c) => ({ value: c }))}
              filterOption={(input, option) => (option?.value as string ?? '').toLowerCase().includes(input.toLowerCase())}
              placeholder="选择或输入销售料号，如 SP-10086"
            />
          ) : (
            <AutoComplete
              style={{ width: '100%' }}
              value={bindValue}
              onChange={setBindValue}
              options={materialCandidates.map((m) => ({ value: `${m.name}（${m.code}）` }))}
              filterOption={(input, option) => (option?.value as string ?? '').toLowerCase().includes(input.toLowerCase())}
              placeholder="选择或输入材质配方，如 不锈钢304"
            />
          )}
          <div style={{ color: '#909399', fontSize: 12, marginTop: 4 }}>
            {activeTab === 'SALES_PART'
              ? '选择已有销售料号将追加新版本；输入未出现过的料号即为该料号首次上传'
              : '来自材质库 MATERIAL_RECIPE；选择已有材质配方将追加新版本，也可输入新配方名称/编码首次上传'}
          </div>
          {candidatesLoading && <div style={{ color: '#909399', fontSize: 12, marginTop: 2 }}>候选加载中…</div>}
        </div>

        <div style={{ marginBottom: 16 }}>
          <div style={{ marginBottom: 6, color: '#606266', fontSize: 12.5 }}>模型文件（.glb） <span style={{ color: '#f56c6c' }}>*</span></div>
          {glbFile ? (
            <div style={{ border: '1px solid #e4e7ed', borderRadius: 8, padding: '12px 14px', display: 'flex', alignItems: 'center', gap: 12, background: '#fafcff' }}>
              <div style={{ fontSize: 26 }}>📦</div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 500, wordBreak: 'break-all' }}>已选 {glbFile.name}</div>
                <div style={{ color: '#909399', fontSize: 12, marginTop: 2 }}>{fmtFileSize(glbFile.size)} · 上传后由服务端解析 mesh / 顶点数</div>
              </div>
              <Button size="small" onClick={() => setGlbFile(null)}>重新选择</Button>
            </div>
          ) : (
            <Upload.Dragger accept=".glb" multiple={false} showUploadList={false} beforeUpload={handleGlbSelect}>
              <p style={{ fontSize: 26, marginBottom: 4 }}><InboxOutlined /></p>
              <p>点击选择 或 拖拽 .glb 文件到此处</p>
              <p style={{ color: '#909399', fontSize: 12 }}>支持 .glb 格式，建议 &lt; 20MB</p>
            </Upload.Dragger>
          )}
        </div>

        <div style={{ marginBottom: 16 }}>
          <div style={{ marginBottom: 6, color: '#606266', fontSize: 12.5 }}>预览图（可选）</div>
          <div style={{ display: 'flex', gap: 8, marginBottom: 10, flexWrap: 'wrap' }}>
            <Upload accept="image/*" showUploadList={false} beforeUpload={handleThumbSelect}>
              <Button>📷 选择预览图</Button>
            </Upload>
            <Tooltip title="该功能暂未开放">
              <Button disabled>🪄 从模型自动截图</Button>
            </Tooltip>
          </div>
          <div style={{ maxWidth: 220, border: '1px solid #e4e7ed', borderRadius: 8, overflow: 'hidden' }}>
            <div
              style={{
                aspectRatio: '1/1',
                background: thumbPreviewUrl ? `url(${thumbPreviewUrl}) center/cover no-repeat` : '#fafafa',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: '#c0c4cc',
                fontSize: 12,
              }}
            >
              {!thumbPreviewUrl && '未设置预览图'}
            </div>
            <div style={{ padding: '8px 10px', fontSize: 12.5, color: '#606266', borderTop: '1px solid #f0f0f0' }}>
              {thumbPreviewUrl ? `已选择预览图：${thumbFile?.name}` : '可选择图片，或点击「从模型自动截图」生成（暂未开放）'}
            </div>
          </div>
        </div>

        <div>
          <div style={{ marginBottom: 6, color: '#606266', fontSize: 12.5 }}>模型名 <span style={{ color: '#f56c6c' }}>*</span></div>
          <Input value={modelName} onChange={(e) => setModelName(e.target.value)} placeholder="如 阀体_标准型" maxLength={100} />
        </div>
      </Drawer>

      {/* 历史版本抽屉（480，对齐原型 openHistoryFor/renderHistoryList） */}
      <Drawer
        title={historySubject ? `历史版本 — ${historySubject.subjectLabel ? `${historySubject.subjectKey} / ${historySubject.subjectLabel}` : historySubject.subjectKey}` : '历史版本'}
        width={480}
        open={historyOpen}
        onClose={() => setHistoryOpen(false)}
        destroyOnClose
        footer={<Button block onClick={() => setHistoryOpen(false)}>关闭</Button>}
      >
        {historyLoading ? (
          <div style={{ padding: '40px 0', textAlign: 'center', color: '#909399' }}>加载中…</div>
        ) : historyList.length === 0 ? (
          <Empty description="暂无历史版本" />
        ) : (
          historyList.map((r) => (
            <div key={r.id} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '10px 0', borderBottom: '1px solid #f0f0f0' }}>
              <ThumbMini url={r.thumbnailUrl} subjectKey={r.subjectKey} />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 600, display: 'flex', alignItems: 'center', gap: 6 }}>
                  v{r.version} {statusTag(r)}
                </div>
                <div style={{ color: '#909399', fontSize: 12, marginTop: 3 }}>
                  {r.label || '未命名'} · {fmtSize(r.sizeKb)} · {fmtTime(r.uploadedAt)}
                </div>
              </div>
              {r.isCurrent ? (
                <Tooltip title="该版本已是当前版本">
                  <Button size="small" disabled>设为当前</Button>
                </Tooltip>
              ) : (
                <Button size="small" onClick={() => setCurrentFromHistory(r)}>设为当前</Button>
              )}
            </div>
          ))
        )}
      </Drawer>

      {/* 详情/预览抽屉（480，对齐原型 openDetail） */}
      <Drawer
        title="模型详情"
        width={480}
        open={detailOpen}
        onClose={() => setDetailOpen(false)}
        destroyOnClose
        footer={
          <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
            <Button
              onClick={() => {
                if (!detailRow) return;
                setDetailOpen(false);
                openHistoryFor({ subjectType: activeTab, subjectKey: detailRow.subjectKey, subjectLabel: detailRow.subjectLabel });
              }}
            >
              查看该对象全部版本
            </Button>
            <Button type="primary" onClick={() => setDetailOpen(false)}>关闭</Button>
          </div>
        }
      >
        {detailRow && (
          <>
            <div style={{ border: '1px solid #e4e7ed', borderRadius: 8, overflow: 'hidden' }}>
              <div
                style={{
                  aspectRatio: '1/1',
                  background: detailRow.thumbnailUrl ? `url(${detailRow.thumbnailUrl}) center/cover no-repeat` : GRADIENTS[hashVariant(detailRow.subjectKey)],
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  color: '#7a8aa8',
                  fontSize: 34,
                  position: 'relative',
                }}
              >
                {!detailRow.thumbnailUrl && '🧊'}
                <span
                  onClick={(e) => { e.stopPropagation(); setZoomHint((v) => !v); }}
                  style={{ position: 'absolute', top: 8, right: 8, background: '#fff', border: '1px solid #dcdfe6', borderRadius: 4, padding: '2px 8px', fontSize: 12, cursor: 'pointer', color: '#606266' }}
                >
                  ⤢ 交互查看
                </span>
                {zoomHint && (
                  <div
                    onClick={(e) => { e.stopPropagation(); setZoomHint(false); }}
                    style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,.55)', color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 12.5, textAlign: 'center', padding: 10, cursor: 'pointer' }}
                  >
                    （可旋转 3D，增强项）
                  </div>
                )}
              </div>
              <div style={{ padding: '8px 10px', fontSize: 12.5, color: '#606266', borderTop: '1px solid #f0f0f0' }}>
                {detailRow.label || '未命名'}
              </div>
            </div>
            <div style={{ marginTop: 14 }}>
              <div style={{ marginTop: 10, fontSize: 13.5, fontWeight: 500, color: '#303133' }}>
                {detailRow.subjectLabel ? `${detailRow.subjectKey} · ${detailRow.subjectLabel}` : detailRow.subjectKey}
              </div>
              {[
                ['对象类型', activeTab === 'SALES_PART' ? '销售料号' : '材质配方'],
                ['对象键', detailRow.subjectKey],
                ...(detailRow.subjectLabel ? [['材质名', detailRow.subjectLabel]] : []),
                ['版本', `v${detailRow.version}`],
                ['文件大小', fmtSize(detailRow.sizeKb)],
                ['上传时间', fmtTime(detailRow.uploadedAt)],
              ].map(([k, v]) => (
                <div key={k as string} style={{ display: 'flex', justifyContent: 'space-between', padding: '8px 0', borderBottom: '1px solid #f5f5f5', fontSize: 12.5 }}>
                  <span style={{ color: '#909399' }}>{k}</span>
                  <span style={{ color: '#303133' }}>{v}</span>
                </div>
              ))}
              <div style={{ display: 'flex', justifyContent: 'space-between', padding: '8px 0', borderBottom: '1px solid #f5f5f5', fontSize: 12.5 }}>
                <span style={{ color: '#909399' }}>状态</span>
                <span>{statusTag(detailRow)}</span>
              </div>
            </div>
          </>
        )}
      </Drawer>
    </Card>
  );
};

export default ModelConfigManagement;
