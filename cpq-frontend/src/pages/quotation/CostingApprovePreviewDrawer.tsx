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
 * task-260907 第二段（F-1~F-6）：本抽屉承接「核价通过 —— 基础数据升版确认」。
 *   - 打开时只调 GET .../costing-approve/preview（只读），🚫 不在打开前调 POST costing-approve；
 *   - 新增 dsBackfill 段由 CostingDsBackfillPanel 渲染（逐表逐组九列比对 / 对不上的行 / 空态）；
 *   - 老回填（products / globalShared / groups）保持原样：dsBackfill.applicable=true 且老回填 0 组时
 *     不再重复渲染其「无变更」提示（新单老回填恒 0/0/0/0，两条提示并列会自相矛盾）；
 *     老单（applicable≠true）或老回填仍有组时，老回填区逐字照旧渲染（AC-15）。
 *
 * 错误处理（api.md §1.2 错误码表 + 本段 fronttask F-6）：
 *   - 409（previewToken 漂移）：抽屉内红色错误条 + 「重新预览」按钮，主按钮禁用并给出原因，
 *     🚫 不关抽屉、🚫 不用全局 message 一闪而过；
 *   - 400/403：抽屉内红色错误条，文案取后端原文，不关抽屉；
 *   - 500：按 message 提示，关闭抽屉（整体失败，提示重试）—— 沿用既有行为，原型未改动此路径。
 */
import React, { useEffect, useState, useCallback } from 'react';
import {
  Drawer, Button, Space, Card, Collapse, Tag,
  Spin, Alert, Tooltip, Typography, message,
} from 'antd';
import type { CollapseProps } from 'antd';
import { PlusOutlined, MinusOutlined, EditOutlined } from '@ant-design/icons';
import { costingOrderService } from '../../services/costingOrderService';
import { useAuthStore } from '../../stores/authStore';
import CostingDsBackfillPanel, { deriveDsFlags } from './CostingDsBackfillPanel';
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
  /** task-260907 F-1：抽屉副标题用（原型 01 头部「报价单 QT-… · 客户 …」）。缺失时该段不渲染。 */
  quotationNumber?: string | null;
  /**
   * task-260907 F-1：客户名。⚠️ 当前 CostingOrderDetailDTO / QuotationDTO 都不含 customerName，
   * 调用方拿不到 → 副标题的「客户 …」段暂缺（见回报「与原型图的偏差清单」）。
   */
  customerName?: string | null;
  customerNo?: string | null;
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
const buildSectionItem = (
  g: CostingApprovePreviewGroup,
  keyPrefix: string,
  groupIndex: number,
): SectionItem => {
  const flipNoChange = isFlipNoChange(g);
  const rowCount = g.rows?.length ?? 0;
  return {
    // ⚠️ key 必须带全局组下标：同一产品卡里完全可能有多个同表组（例如一个产品下
    //    两条不同材质料号的 element_bom_item），只用 `前缀::表名` 会撞 key，
    //    React 报 "two children with the same key"、折叠态还会互相串。
    key: `${keyPrefix}::${groupIndex}`,
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
            {/* 带上中文 label：display 有时只是裸代码（资源组 QUOTE_ASSEMBLY、供应商 1），
                只 join display 会让财务看到一串不知所云的 token。label 已由后端给全。 */}
            {g.axisLabels.map((a) => (a.label ? `${a.label}：${a.display}` : a.display)).join('　')}
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
    .map((i) => ({ g: groups[i], i }))
    .filter((x): x is { g: CostingApprovePreviewGroup; i: number } => !!x.g);
  if (productGroups.length === 0) return null;
  const items = productGroups.map(({ g, i }) => buildSectionItem(g, `p-${product.productNo}`, i));
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
    .map((i) => ({ g: groups[i], i }))
    .filter((x): x is { g: CostingApprovePreviewGroup; i: number } => !!x.g);
  if (sharedGroups.length === 0) return null;
  const items = sharedGroups.map(({ g, i }) => buildSectionItem(g, 'global-shared', i));
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

const CostingApprovePreviewDrawer: React.FC<Props> = ({
  open, quotationId, comment, quotationNumber, customerName, customerNo, onClose, onApproved,
}) => {
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [preview, setPreview] = useState<CostingApprovePreviewResult | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  /** task-260907 F-6：提交失败在抽屉内展示，🚫 不用全局 message 一闪而过 */
  const [submitError, setSubmitError] = useState<string | null>(null);
  /** task-260907 F-6：撞 409 后比对结果已过期 —— 主按钮禁用 + tooltip 说明，须先「重新预览」 */
  const [stale, setStale] = useState(false);
  const role = useAuthStore((st) => st.user?.role);

  const loadPreview = useCallback(async () => {
    if (!quotationId) return;
    setLoading(true);
    setLoadError(null);
    setSubmitError(null);
    setStale(false);
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
      setSubmitError(null);
      setStale(false);
      loadPreview();
    }
  }, [open, loadPreview]);

  const handleConfirm = async () => {
    if (!quotationId || !preview) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      const res = await costingOrderService.approve(quotationId, preview.previewToken, comment);
      message.success('核价通过');
      onApproved(res.data);
      onClose();
    } catch (e: any) {
      if (e?.httpStatus === 409) {
        // F-6：抽屉内红条 + 「重新预览」出口，🚫 不关抽屉、🚫 不自动重拉（自动重拉会让财务
        // 看不见「刚才那次被拒了、基础数据一个字节没动」这句结论）
        setSubmitError(e?.message || '报价数据在预览后发生变化，请重新预览');
        setStale(true);
      } else if (e?.httpStatus === 500) {
        // 沿用既有行为：整体失败，关抽屉提示重试（原型未画此路径，不自行改动）
        message.error(e?.message || '核价通过失败，请重试');
        onClose();
      } else {
        // 400 / 403：文案取后端原文，抽屉内展示
        setSubmitError(e?.message || '操作失败');
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
  const orphanGroups = groups
    .map((g, idx) => ({ g, idx }))
    .filter(({ idx }) => !coveredIndexes.has(idx));

  // ── task-260907 第二段：ds_quote_* 新回填段（F-1~F-6）──────────────────────
  const ds = preview?.dsBackfill;
  const dsFlags = deriveDsFlags(ds);

  /**
   * 客户号：调用方拿不到时退到 dsBackfill 里的 customerNo（api.md §4：string = customer.code）。
   * ⚠️ 上游 DDL 未落库前后端可能返 null —— 必须容忍，渲染成「—」，🚫 不报错、🚫 不空白。
   */
  const dsCustomerNo =
    (ds?.tables ?? []).flatMap((t) => t.groups ?? []).find((g) => !!g.customerNo)?.customerNo ?? null;
  const effectiveCustomerNo = customerNo ?? dsCustomerNo;

  /** 抽屉标题（原型 01/03/04 头部）。后端未下发 dsBackfill 时保持既有标题，行为不变。 */
  const subtitleParts: string[] = [];
  if (quotationNumber) subtitleParts.push(`报价单 ${quotationNumber}`);
  if (customerName) {
    subtitleParts.push(`客户 ${customerName}${effectiveCustomerNo ? `（${effectiveCustomerNo}）` : ''}`);
  } else if (dsFlags.applicable) {
    subtitleParts.push(`客户 ${effectiveCustomerNo ?? '—'}`);
  }
  // 原型 03 上半屏（全部无变更）的副标题只有「报价单 … · 客户 …」两段，不带尾段。
  if (!dsFlags.applicable) {
    subtitleParts.push('该单建于基础数据切换之前');
  } else if (dsFlags.unanchoredRows > 0) {
    subtitleParts.push('⚠️ 部分料号在本单提交后已被其他报价单改动');
  } else if (dsFlags.hasTables && !dsFlags.allUnchanged) {
    subtitleParts.push('确认后将按下表把报价单数据写回基础数据并升版');
  }
  // hasTables=false（applicable=true 但一张表都没有，只有 D-33 告警要说）时不带尾段 ——
  // 说「按下表…升版」会与「下面根本没有表」自相矛盾。
  const titleNode: React.ReactNode = dsFlags.present ? (
    <div>
      <div style={{ fontSize: 16, fontWeight: 600 }}>
        {dsFlags.applicable ? '核价通过 —— 确认基础数据升版' : '核价通过'}
      </div>
      <div style={{ fontSize: 13, fontWeight: 400, color: 'rgba(0,0,0,.45)', marginTop: 2 }}>
        {subtitleParts.join('　·　')}
      </div>
    </div>
  ) : (
    '核价通过预览'
  );

  /**
   * 主按钮禁用原因 —— 必须可见 + hover tooltip 说明原因（frontend.md §1.2）。
   * 🚫 禁止 `if (...) return null` 把按钮藏掉。
   */
  const canApproveRole = ['PRICING_MANAGER', 'SYSTEM_ADMIN'].includes(role ?? '');
  const disabledReason: string | null = !canApproveRole
    ? '当前登录角色不是财务或管理员，无权核价'
    : stale
      ? '比对结果已过期，请先点「重新预览」'
      : loading
        ? '正在计算比对结果，请稍候'
        : loadError
          ? '预览加载失败，请先点「重试」'
          : !preview
            ? '预览尚未加载，请稍候'
            : null;

  const dangerConfirm = dsFlags.unanchoredRows > 0;
  const confirmLabel = dangerConfirm ? '仍然确认并核价通过' : '确认并核价通过';

  const confirmBtn = (
    <Button
      type="primary"
      danger={dangerConfirm}
      loading={submitting}
      disabled={!!disabledReason}
      style={disabledReason ? { pointerEvents: 'none' } : undefined}
      onClick={handleConfirm}
    >
      {confirmLabel}
    </Button>
  );

  /** 页脚左侧提示（原型 01/02/03 footer-note）。 */
  let footerNote: React.ReactNode = null;
  let footerDanger = false;
  if (dsFlags.present) {
    if (!dsFlags.applicable) {
      footerNote = '—';
    } else if (dsFlags.unanchoredRows > 0) {
      footerNote = `⚠️ 有 ${dsFlags.unanchoredRows} 行对不上，确认前请先看上方明细。`;
      footerDanger = true;
    } else if (dsFlags.allUnchanged || dsFlags.upgradedGroups === 0) {
      // upgradedGroups=0 含两种：全 UNCHANGED，以及「一张表都没有（组件全不参与）」——
      // 两种都是「一个字节不写基础数据」，🚫 不能落到下面那句「N 个料号组将升版」。
      footerNote = '本次确认不写入任何基础数据，仅将报价单状态置为「已核准」。';
    } else {
      footerNote = `确认后不可撤销：${dsFlags.upgradedGroups} 个料号组将升版，旧版本进入历史可查。`;
    }
  }

  return (
    <Drawer
      title={titleNode}
      placement="right"
      width={1200}
      open={open}
      onClose={onClose}
      destroyOnClose
      footer={
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span
            style={{
              marginRight: 'auto',
              fontSize: 13,
              color: footerDanger ? '#d4380d' : 'rgba(0,0,0,.45)',
            }}
          >
            {footerNote}
          </span>
          <Button onClick={onClose}>取消</Button>
          {disabledReason ? (
            <Tooltip title={disabledReason}>
              <span style={{ display: 'inline-block', cursor: 'not-allowed' }}>{confirmBtn}</span>
            </Tooltip>
          ) : (
            confirmBtn
          )}
        </div>
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
          {/* ── F-6：提交失败在抽屉内展示（409 带「重新预览」出口），🚫 不关抽屉 ── */}
          {submitError && (
            <Alert
              type="error"
              showIcon
              style={{ marginBottom: 16 }}
              title={<b>提交失败：{submitError}</b>}
              description={
                stale ? (
                  <>
                    <div style={{ fontSize: 13 }}>
                      你打开本页之后，该报价单或其引用的基础数据又被改动了（可能是另一位财务同时在处理这张单）。
                      为避免按过期的比对结果写入，本次提交已被拒绝，<b>基础数据一个字节都没有改动</b>。
                    </div>
                    <div style={{ marginTop: 10 }}>
                      <Button size="small" onClick={loadPreview}>重新预览</Button>
                    </div>
                  </>
                ) : undefined
              }
            />
          )}

          {/* ── task-260907 第二段：ds_quote_* 基础数据升版比对（F-1~F-5）── */}
          {ds && <CostingDsBackfillPanel ds={ds} />}

          {/* ── 老回填链路（task-0721 / repair-0727）：行为原样保留（AC-15）。
                 唯一差异：ds 新回填已接管本单时（applicable=true）且老回填 0 组，不再重复渲染其
                 「无变更」提示——新单老回填恒 0/0/0/0，两条提示并列会自相矛盾。 ── */}
          {(!dsFlags.applicable || groups.length > 0) && (
            <>
              {dsFlags.applicable && groups.length > 0 && (
                <div style={{ fontSize: 15, fontWeight: 600, margin: '4px 0 10px' }}>老回填链路变更（V6 旧表）</div>
              )}
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
                      {sectionCollapse(orphanGroups.map(({ g, idx }) => buildSectionItem(g, 'orphan', idx)))}
                    </Card>
                  )}
                </>
              )}
            </>
          )}
        </>
      )}
    </Drawer>
  );
};

export default CostingApprovePreviewDrawer;
