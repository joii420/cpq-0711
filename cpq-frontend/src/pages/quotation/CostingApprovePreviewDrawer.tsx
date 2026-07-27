/**
 * CostingApprovePreviewDrawer —— 核价通过前置预览抽屉（task-0721 F1，repair-0727 F2 渲染层重做）
 *
 * 场景：财务点「核价通过」时，不直接提交，先调 GET .../costing-approve/preview 拿到
 * 本次通过将对基础数据造成的增/删/改清单 + previewToken，抽屉展示后财务确认才真正提交
 * POST .../costing-approve（带回 previewToken）。
 *
 * 契约：dev-docs/repair-0727-回填语义与预览重做/api.md §1（基线 dev-docs/task-0721-报价升版逻辑/api.md）；
 * 交互规范：dev-docs/repair-0727-回填语义与预览重做/fronttask.md F2。
 *
 * repair-0727 决策 3：预览从「V6 物理字段串」改为「财务读得懂的产品级变更说明」——
 * 按 products 聚合成产品卡片，卡内按 categoryLabel（业务类别中文名）分节，行级用
 * rowLabel + 中文列名展示；无产品维度的表（如电镀方案）单独进 globalShared 红色警示区。
 * 本次只重做展示层，加载/错误/提交交互契约不变（见下方错误处理说明）。
 *
 * 错误处理（api.md §1.2 错误码表）：
 *   - 409（previewToken 漂移）：message.error 提示后自动重新拉 preview 刷新抽屉内容，不关闭。
 *   - 400/403：按 message 提示，不关抽屉。
 *   - 500：按 message 提示，关闭抽屉（整体失败，提示重试）。
 */
import React, { useEffect, useState, useCallback } from 'react';
import {
  Drawer, Button, Space, Card, Collapse, Tag,
  Spin, Alert, Typography, message,
} from 'antd';
import type { CollapseProps } from 'antd';
import { PlusOutlined, MinusOutlined, EditOutlined } from '@ant-design/icons';
import { costingOrderService } from '../../services/costingOrderService';
import type {
  CostingApprovePreviewResult,
  CostingApprovePreviewGroup,
  CostingApprovePreviewProduct,
  CostingApprovePreviewGlobalShared,
  CostingApprovePreviewRow,
  CostingApproveResult,
} from '../../services/costingOrderService';

const { Text } = Typography;

interface Props {
  open: boolean;
  quotationId: string | undefined;
  /** 提交时附带的审批意见（可选，沿用现有 approve 入参） */
  comment?: string;
  onClose: () => void;
  /** 提交成功回调，拿到 approve 响应（含 backfill 汇总） */
  onApproved: (result: CostingApproveResult) => void;
}

const opTagOf = (op: CostingApprovePreviewRow['op']) => {
  if (op === 'ADD') return <Tag color="green" icon={<PlusOutlined />}>新增</Tag>;
  if (op === 'DELETE') return <Tag color="red" icon={<MinusOutlined />}>删除</Tag>;
  return <Tag color="orange" icon={<EditOutlined />}>改值</Tag>;
};

/** 行级内容：CHANGE 用 changes 数组纵向排列（旧值删除线/新值加粗）；ADD/DELETE 用 values 数组逗号连接。 */
const rowContentOf = (row: CostingApprovePreviewRow): React.ReactNode => {
  if (row.op === 'CHANGE') {
    const changes = row.changes ?? [];
    if (changes.length === 0) return <Text type="secondary">—</Text>;
    return (
      <Space direction="vertical" size={2}>
        {changes.map((c) => (
          <span key={c.column}>
            {c.label}：<Text delete type="secondary">{c.oldValue ?? '—'}</Text> → <Text strong>{c.newValue ?? '—'}</Text>
          </span>
        ))}
      </Space>
    );
  }
  const values = row.values ?? [];
  const text = values.map((v) => `${v.label}: ${v.value ?? '—'}`).join('，');
  if (row.op === 'DELETE') {
    return <Text delete type="danger">{text || '—'}</Text>;
  }
  return <Text type="success">{text || '—'}</Text>;
};

/** 一组行的纵向列表：操作 Tag + 业务身份（rowLabel）+ 冲突标注 + 行内容。 */
const RowList: React.FC<{ rows: CostingApprovePreviewRow[] }> = ({ rows }) => (
  <Space direction="vertical" size={10} style={{ width: '100%' }}>
    {rows.map((r, idx) => (
      <div key={r.__v6_id ?? `row-${idx}`}>
        <Space size={6} wrap>
          {opTagOf(r.op)}
          <Text>{r.rowLabel || '（未命名行）'}</Text>
          {r.conflict && <Tag color="orange">多页签冲突，取先到值</Tag>}
        </Space>
        <div style={{ paddingLeft: 24, marginTop: 2 }}>{rowContentOf(r)}</div>
      </div>
    ))}
  </Space>
);

type SectionItem = NonNullable<CollapseProps['items']>[number] & { defaultOpen: boolean };

/** route=FLIP 且 0 行变更 = 该组只是版本号转正，内容与基底完全一致（decision 3 前提：patch 语义下 0 变更就是真结论）。 */
const isFlipNoChange = (g: CostingApprovePreviewGroup) => g.route === 'FLIP' && (g.rows?.length ?? 0) === 0;

/** 一个 group 渲染为一个可折叠分节：类别中文名 + 版本迁移 + 行数迁移 + 轴人类可读表达。 */
const buildSectionItem = (g: CostingApprovePreviewGroup, keyPrefix: string): SectionItem => {
  const flipNoChange = isFlipNoChange(g);
  const rowCount = g.rows?.length ?? 0;
  return {
    key: `${keyPrefix}::${g.table}`,
    label: (
      <Space wrap size={6}>
        <Text strong type={flipNoChange ? 'secondary' : undefined}>
          {g.categoryLabel || g.tabName}
        </Text>
        <Text type="secondary" style={{ fontSize: 12 }}>
          {g.versionFrom ?? '首版'} → {g.versionTo}
        </Text>
        <Text type="secondary" style={{ fontSize: 12 }}>
          {g.baseRowCount ?? rowCount} 行 → {g.resultRowCount ?? rowCount} 行
        </Text>
        {!!g.axisLabels?.length && (
          <Text type="secondary" style={{ fontSize: 12 }}>
            {g.axisLabels.map((a) => a.display).join('，')}
          </Text>
        )}
        {flipNoChange && (
          <Text type="secondary" style={{ fontSize: 12 }}>（仅版本转正，内容无变化）</Text>
        )}
      </Space>
    ),
    children: rowCount > 0
      ? <RowList rows={g.rows} />
      : <Text type="secondary">仅版本转正，内容无变化</Text>,
    defaultOpen: !flipNoChange,
  };
};

const sectionCollapse = (items: SectionItem[]): React.ReactNode => (
  <Collapse
    size="small"
    bordered={false}
    defaultActiveKey={items.filter((it) => it.defaultOpen).map((it) => it.key as string)}
    items={items.map(({ defaultOpen, ...rest }) => rest)}
  />
);

/** 产品卡片：卡头 = 产品料号 + 品名 + 客户名；卡内按 group（业务类别）分节。 */
const ProductCard: React.FC<{ product: CostingApprovePreviewProduct; groups: CostingApprovePreviewGroup[] }> = ({ product, groups }) => {
  const productGroups = (product.groupIndexes ?? [])
    .map((i) => groups[i])
    .filter((g): g is CostingApprovePreviewGroup => !!g);
  if (productGroups.length === 0) return null;
  const items = productGroups.map((g) => buildSectionItem(g, `${product.productNo}-${product.customerNo}`));
  return (
    <Card
      size="small"
      style={{ marginBottom: 16 }}
      title={
        <Space wrap size={6}>
          <Tag color="blue">{product.productNo}</Tag>
          {product.productName && <Text strong>{product.productName}</Text>}
          <Text type="secondary">客户 {product.customerName ?? product.customerNo}</Text>
        </Space>
      }
    >
      {sectionCollapse(items)}
    </Card>
  );
};

/** 全局共享变更区：无产品维度的表（如电镀方案），红色警示——一改影响所有客户。 */
const GlobalSharedCard: React.FC<{ globalShared: CostingApprovePreviewGlobalShared; groups: CostingApprovePreviewGroup[] }> = ({ globalShared, groups }) => {
  const sharedGroups = (globalShared.groupIndexes ?? [])
    .map((i) => groups[i])
    .filter((g): g is CostingApprovePreviewGroup => !!g);
  if (sharedGroups.length === 0) return null;
  const items = sharedGroups.map((g) => buildSectionItem(g, 'global-shared'));
  return (
    <Card
      size="small"
      style={{ marginBottom: 16, borderColor: '#ff4d4f' }}
      title={<Tag color="red">全局共享变更（影响所有客户）</Tag>}
    >
      {sectionCollapse(items)}
    </Card>
  );
};

const CostingApprovePreviewDrawer: React.FC<Props> = ({ open, quotationId, comment, onClose, onApproved }) => {
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [preview, setPreview] = useState<CostingApprovePreviewResult | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const loadPreview = useCallback(async () => {
    if (!quotationId) return;
    setLoading(true);
    setLoadError(null);
    try {
      const res = await costingOrderService.previewApprove(quotationId);
      setPreview(res.data);
    } catch (e: any) {
      setPreview(null);
      setLoadError(e?.message || '加载预览失败');
    } finally {
      setLoading(false);
    }
  }, [quotationId]);

  useEffect(() => {
    if (open) {
      setPreview(null);
      setLoadError(null);
      loadPreview();
    }
  }, [open, loadPreview]);

  const handleConfirm = async () => {
    if (!quotationId || !preview) return;
    setSubmitting(true);
    try {
      const res = await costingOrderService.approve(quotationId, preview.previewToken, comment);
      message.success('核价通过');
      onApproved(res.data);
      onClose();
    } catch (e: any) {
      if (e?.httpStatus === 409) {
        message.error('报价数据在预览后发生变化，请重新预览');
        await loadPreview();
      } else if (e?.httpStatus === 500) {
        message.error(e?.message || '核价通过失败，请重试');
        onClose();
      } else {
        message.error(e?.message || '操作失败');
      }
    } finally {
      setSubmitting(false);
    }
  };

  const summary = preview?.summary;
  const noImpact = !!summary
    && summary.versionedGroups === 0 && summary.addedRows === 0
    && summary.deletedRows === 0 && summary.changedRows === 0;

  const groups = preview?.groups ?? [];
  const products = preview?.products ?? [];
  const globalShared = preview?.globalShared;

  // 防御性兜底：若某个 group 既不在任何 product.groupIndexes 也不在 globalShared.groupIndexes 里
  // （contract 未预期的分区缺口），不静默丢弃，单独归到「未归类变更」区，保证「预览 ≡ 执行」不因
  // 前端分区逻辑漏判而失真（AC-R4）。
  const coveredIndexes = new Set<number>([
    ...products.flatMap((p) => p.groupIndexes ?? []),
    ...(globalShared?.groupIndexes ?? []),
  ]);
  const orphanGroups = groups.filter((_, idx) => !coveredIndexes.has(idx));

  return (
    <Drawer
      title="核价通过预览"
      placement="right"
      width={1000}
      open={open}
      onClose={onClose}
      destroyOnClose
      extra={
        <Space>
          <Button onClick={onClose}>取消</Button>
          <Button
            type="primary"
            loading={submitting}
            disabled={loading || !preview}
            onClick={handleConfirm}
          >
            确认通过
          </Button>
        </Space>
      }
    >
      {loading && (
        <div style={{ textAlign: 'center', padding: 60 }}>
          <Spin size="large" tip="正在计算本次通过将造成的基础数据变更…" />
        </div>
      )}

      {!loading && loadError && (
        <Alert
          type="error"
          showIcon
          message="预览加载失败"
          description={loadError}
          action={<Button size="small" onClick={loadPreview}>重试</Button>}
        />
      )}

      {!loading && !loadError && preview && (
        <>
          <div style={{ marginBottom: 16, fontSize: 14 }}>
            <Space split={<Text type="secondary">·</Text>} wrap>
              <Text>影响 <Text strong>{summary?.affectedProducts ?? 0}</Text> 个产品</Text>
              <Text>新增 <Text strong style={{ color: '#52c41a' }}>{summary?.addedRows ?? 0}</Text> 行</Text>
              <Text>删除 <Text strong style={{ color: '#ff4d4f' }}>{summary?.deletedRows ?? 0}</Text> 行</Text>
              <Text>改值 <Text strong style={{ color: '#fa8c16' }}>{summary?.changedRows ?? 0}</Text> 行</Text>
            </Space>
          </div>

          {noImpact ? (
            <Alert
              type="info"
              showIcon
              message="本次通过无基础数据变更，仅完成审核状态流转"
              style={{ marginBottom: 16 }}
            />
          ) : (
            <>
              {products.map((p) => (
                <ProductCard key={`${p.productNo}-${p.customerNo}`} product={p} groups={groups} />
              ))}
              {globalShared && <GlobalSharedCard globalShared={globalShared} groups={groups} />}
              {orphanGroups.length > 0 && (
                <Card
                  size="small"
                  style={{ marginBottom: 16, borderColor: '#faad14' }}
                  title={<Tag color="orange">未归类变更</Tag>}
                >
                  {sectionCollapse(orphanGroups.map((g) => buildSectionItem(g, 'orphan')))}
                </Card>
              )}
            </>
          )}
        </>
      )}
    </Drawer>
  );
};

export default CostingApprovePreviewDrawer;
