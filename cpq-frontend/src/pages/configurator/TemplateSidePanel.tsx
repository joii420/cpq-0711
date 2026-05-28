import React from 'react';
import { Card, Button, Progress, Space, Tag } from 'antd';
import { useNavigate } from 'react-router-dom';
import type { ConfiguratorTemplate, ConfiguratorOption, ConfiguratorOptionValue } from '../../types/configurator';
import ConfiguratorPreview from '../../components/ConfiguratorPreview';

interface Props {
  tpl: ConfiguratorTemplate;
  options: ConfiguratorOption[];
  valuesByOpt: Record<string, ConfiguratorOptionValue[]>;
  refStats?: {
    instances?: number;
    quotations?: number;
    relatedParts?: number;
  };
  onOpenBaseModelPicker: () => void;
  onTriggerRefresh: () => void;
  onExportJson?: () => void;
}

/**
 * 模板编辑器右侧浮动面板 — 按原型 v0.4-3D选配模板管理端原型.html
 * - 🎬 base.glb 迷你预览
 * - 📊 模板健康度（4 进度条）
 * - 🔗 引用统计（跳转链接）
 * - ⚡ 快捷操作
 */
const TemplateSidePanel: React.FC<Props> = ({
  tpl, options, valuesByOpt, refStats, onOpenBaseModelPicker, onTriggerRefresh, onExportJson,
}) => {
  const navigate = useNavigate();

  const totalValues = Object.values(valuesByOpt).reduce((s, vs) => s + vs.length, 0);
  const requiredCount = options.filter(o => o.isRequired).length;
  const optionsWithValues = options.filter(o => o.assignMode !== 'MANUAL' && (valuesByOpt[o.id]?.length ?? 0) > 0).length;

  // 健康度计算
  const requiredFillPct = requiredCount === 0 ? 100 : Math.round(
    (options.filter(o => o.isRequired && (o.defaultValue || (valuesByOpt[o.id]?.length ?? 0) > 0)).length / requiredCount) * 100
  );
  const selectOptions = options.filter(o => o.assignMode === 'SELECT');
  const ruleCoverPct = selectOptions.length === 0 ? 0 : Math.round((optionsWithValues / selectOptions.length) * 100);
  const valuesWithDelta = Object.values(valuesByOpt).flat().filter(v => Number(v.priceDelta) !== 0).length;
  const priceFillPct = totalValues === 0 ? 0 : Math.round((valuesWithDelta / totalValues) * 100);
  const featureSourced = options.filter(o => o.sourceFeatureFieldId).length;
  const featureFillPct = options.length === 0 ? 0 : Math.round((featureSourced / options.length) * 100);

  const baseModelMeta = tpl.baseModelId
    ? `v${tpl.baseModelVersion} · snapshot ${tpl.baseModelSnapshotAt ? new Date(tpl.baseModelSnapshotAt).toLocaleDateString() : '-'}`
    : '未绑定';

  return (
    <div style={{ width: 280, flexShrink: 0 }}>
      {/* base.glb 迷你预览 */}
      <Card size="small" title={
        <Space style={{ width: '100%', justifyContent: 'space-between' }}>
          <span>🎬 base 模型预览</span>
          <a style={{ fontSize: 11, fontWeight: 'normal' }} onClick={onOpenBaseModelPicker}>切换 →</a>
        </Space>
      } style={{ marginBottom: 12 }}>
        {tpl.baseModelId ? (
          <ConfiguratorPreview height={180} category={tpl.category} partNo={tpl.basePartNo}
                               label={`${tpl.code} · ${baseModelMeta.split(' · ')[0]}`}
                               autoRotate cameraControls showLabels />
        ) : (
          <div style={{
            height: 180, borderRadius: 6, background: '#fafafa',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            flexDirection: 'column', color: 'rgba(0,80,179,.4)',
            border: '1px dashed #d9d9d9',
          }}>
            <div style={{ fontSize: 48, lineHeight: 1 }}>⊕</div>
            <div style={{ fontSize: 11, marginTop: 6 }}>未绑定 base 模型</div>
          </div>
        )}
        <div style={{ fontSize: 11, color: '#909399', marginTop: 8, textAlign: 'center' }}>
          {tpl.baseModelId ? baseModelMeta : '点击「切换 →」选择已上传的 mat_part_model'}
        </div>
      </Card>

      {/* 模板健康度 */}
      <Card size="small" title="📊 模板健康度" style={{ marginBottom: 12 }}>
        <div style={{ fontSize: 12 }}>
          <Row label="必选项完成" pct={requiredFillPct} good />
          <Row label="3D 规则覆盖" pct={ruleCoverPct} />
          <Row label="价格规则填充" pct={priceFillPct} />
          <Row label="特征语义来源" pct={featureFillPct} warning />
        </div>
      </Card>

      {/* 引用统计 */}
      <Card size="small" title="🔗 引用此模板的" style={{ marginBottom: 12 }}>
        <RefRow
          icon="📋" count={refStats?.instances ?? 0} label="选配实例"
          onClick={() => navigate(`/configurator/instances?templateId=${tpl.id}`)}
        />
        <RefRow icon="📝" count={refStats?.quotations ?? 0} label="报价单 line_item" />
        <RefRow icon="📦" count={refStats?.relatedParts ?? 0} label="产品族基础料号" />
        <RefRow
          icon="🎬" count={tpl.baseModelId ? 1 : 0} label="base.glb 共享"
          onClick={tpl.baseModelId ? () => navigate(`/system/part-models`) : undefined}
        />
      </Card>

      {/* 快捷操作 */}
      <Card size="small" title="⚡ 快捷操作">
        <Space direction="vertical" style={{ width: '100%' }} size={6}>
          <Button block size="small" onClick={onTriggerRefresh}>📋 从特征库重新拉取</Button>
          <Button block size="small" onClick={onExportJson}>📥 导出 JSON</Button>
          <Button block size="small">📋 复制为新模板</Button>
        </Space>
      </Card>
    </div>
  );
};

const Row: React.FC<{ label: string; pct: number; good?: boolean; warning?: boolean }> = ({ label, pct, good, warning }) => {
  const strokeColor = pct === 100 ? '#52c41a' : pct >= 80 ? '#1890ff' : pct >= 50 ? '#faad14' : '#f5222d';
  return (
    <div style={{ marginBottom: 8 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11.5 }}>
        <span style={{ color: '#606266' }}>{label}</span>
        <span style={{ color: warning && pct < 80 ? '#d48806' : good && pct === 100 ? '#52c41a' : '#303133' }}>{pct}%</span>
      </div>
      <Progress percent={pct} showInfo={false} strokeColor={strokeColor} size="small" />
    </div>
  );
};

const RefRow: React.FC<{ icon: string; count: number; label: string; onClick?: () => void }> = ({ icon, count, label, onClick }) => (
  <div style={{
    padding: '6px 8px', fontSize: 12, color: count > 0 ? '#606266' : '#bfbfbf',
    borderRadius: 4, marginBottom: 3, display: 'flex', alignItems: 'center',
  }}>
    {icon} <b style={{ margin: '0 4px', color: count > 0 ? '#303133' : '#bfbfbf' }}>{count}</b> 个 {label}
    {onClick && count > 0 && (
      <a onClick={onClick} style={{ marginLeft: 'auto', fontSize: 10.5 }}>查看 →</a>
    )}
  </div>
);

export default TemplateSidePanel;
