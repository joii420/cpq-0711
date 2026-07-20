/**
 * LinkConfigDrawer —— "新增比对列 · 连线配置" 抽屉（task-0717）。
 * 左列报价单页签树 / 右列核价单页签树，点击式连线（非拖拽）配对；中间 SVG 画布画贝塞尔连线。
 * 严格对齐 dev-docs/task-0717-比对视图/prototype-比对视图.html 的连线 JS + fronttask.md §5。
 *
 * 交互要点：
 *   - 点左侧节点 → pending；再点右侧节点 → 生成配对（反之亦然）；同侧再点 → 更新 pending（允许改主意）。
 *   - 一个节点可连多条（一对多允许）。
 *   - 页签分组可折叠；折叠时节点隐藏，已连线端点锚到分组标题内侧边缘、显示虚线（连线不丢）。
 *   - 重绘时机：新增/删除配对、左右列各自滚动、窗口 resize、抽屉滑入动画结束。
 *   - 确定：清单为空 → 阻止关闭 + 提示；非空 → 把配对交给父层（父层负责生成 ColumnDef + PUT + toast）。
 *   - 取消/关闭/点遮罩：丢弃配对，交给父层处理。destroyOnHidden 保证每次打开都是全新状态。
 */
import React, { useCallback, useMemo, useRef, useState } from 'react';
import { Drawer, Button, InputNumber, message } from 'antd';
import { CloseOutlined } from '@ant-design/icons';
import { genUUID } from '../../utils/uuid';
import { formatMetricLabel, buildTabPairLabel } from './comparisonMapping';
import type { LinkPairInput } from './comparisonMapping';
import type { ComparisonMetaDTO, ComparisonTabMeta, ComparisonMetricMeta } from '../../services/comparisonViewService';

type Side = 'quote' | 'costing';

interface PendingEnd {
  side: Side;
  componentId: string;
  metric: string;
  tabName: string;
  metricLabel: string;
}

interface PairRow {
  id: string;
  quoteComponentId: string;
  quoteMetric: string;
  quoteTabName: string;
  quoteMetricLabel: string;
  costingComponentId: string;
  costingMetric: string;
  costingTabName: string;
  costingMetricLabel: string;
  threshold: number;
}

interface PathDatum {
  id: string;
  d: string;
  dashed: boolean;
}

export interface LinkConfigDrawerProps {
  open: boolean;
  meta: ComparisonMetaDTO | null;
  onClose: () => void;
  onConfirm: (pairs: LinkPairInput[]) => void;
}

const nodeKey = (side: Side, componentId: string, metric: string) => `${side}::${componentId}::${metric}`;
const groupKey = (side: Side, componentId: string) => `${side}::${componentId}`;

const colTitleStyle: React.CSSProperties = {
  padding: '8px 12px', fontSize: 12.5, fontWeight: 600, color: 'rgba(0,0,0,.65)',
  background: '#fafafa', borderBottom: '1px solid #f0f0f0', flexShrink: 0,
};
const groupTitleStyle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 6,
  margin: '7px 8px 3px', padding: '5px 10px', fontSize: 12, fontWeight: 600,
  color: '#1554ad', background: '#f0f5ff', borderLeft: '3px solid #1677ff', borderRadius: 4,
  cursor: 'pointer', userSelect: 'none',
};
const emptyColStyle: React.CSSProperties = { textAlign: 'center', color: '#bbb', padding: '16px 0', fontSize: 12.5 };
const emptyHintStyle: React.CSSProperties = {
  textAlign: 'center', color: '#bbb', padding: '16px 0', fontSize: 12.5, border: '1px dashed #e0e0e0', borderRadius: 6,
};
const sectionTitleStyle: React.CSSProperties = {
  fontSize: 13, fontWeight: 600, color: 'rgba(0,0,0,.85)', margin: '0 0 10px', paddingBottom: 6, borderBottom: '1px solid #f5f5f5',
};

export const LinkConfigDrawer: React.FC<LinkConfigDrawerProps> = ({ open, meta, onClose, onConfirm }) => {
  const [pairs, setPairs] = useState<PairRow[]>([]);
  const [pending, setPending] = useState<PendingEnd | null>(null);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [hoveredPairId, setHoveredPairId] = useState<string | null>(null);
  const [flashPairId, setFlashPairId] = useState<string | null>(null);
  const [paths, setPaths] = useState<PathDatum[]>([]);

  const portRefs = useRef<Record<string, HTMLSpanElement | null>>({});
  const groupRefs = useRef<Record<string, HTMLDivElement | null>>({});
  const pairRowRefs = useRef<Record<string, HTMLDivElement | null>>({});
  const svgRef = useRef<SVGSVGElement | null>(null);

  const resolveAnchor = useCallback(
    (side: Side, componentId: string, metric: string, svgRect: DOMRect): { x: number; y: number; hidden: boolean } | null => {
      const el = portRefs.current[nodeKey(side, componentId, metric)];
      if (el) {
        const r = el.getBoundingClientRect();
        if (r.width > 0 && r.height > 0) {
          return { x: r.left + r.width / 2 - svgRect.left, y: r.top + r.height / 2 - svgRect.top, hidden: false };
        }
      }
      const g = groupRefs.current[groupKey(side, componentId)];
      if (g) {
        const r = g.getBoundingClientRect();
        const edgeX = side === 'quote' ? r.right - 6 : r.left + 6;
        return { x: edgeX - svgRect.left, y: r.top + r.height / 2 - svgRect.top, hidden: true };
      }
      return null;
    },
    [],
  );

  const redraw = useCallback(() => {
    const svg = svgRef.current;
    if (!svg) return;
    const svgRect = svg.getBoundingClientRect();
    if (!svgRect.width || !svgRect.height) return;
    const next: PathDatum[] = [];
    for (const p of pairs) {
      const a = resolveAnchor('quote', p.quoteComponentId, p.quoteMetric, svgRect);
      const b = resolveAnchor('costing', p.costingComponentId, p.costingMetric, svgRect);
      if (!a || !b) continue;
      const dx = b.x - a.x;
      const c1x = a.x + dx * 0.4;
      const c1y = a.y;
      const c2x = b.x - dx * 0.4;
      const c2y = b.y;
      next.push({
        id: p.id,
        d: `M ${a.x} ${a.y} C ${c1x} ${c1y}, ${c2x} ${c2y}, ${b.x} ${b.y}`,
        dashed: a.hidden || b.hidden,
      });
    }
    setPaths(next);
  }, [pairs, resolveAnchor]);

  // 重绘时机 1：配对 / 折叠状态变化后（DOM 更新完再测量，故 rAF 一帧）
  React.useEffect(() => {
    if (!open) return;
    const raf = requestAnimationFrame(() => redraw());
    return () => cancelAnimationFrame(raf);
  }, [open, pairs, collapsed, redraw]);

  // 重绘时机 2：窗口 resize
  React.useEffect(() => {
    if (!open) return;
    const handler = () => redraw();
    window.addEventListener('resize', handler);
    return () => window.removeEventListener('resize', handler);
  }, [open, redraw]);

  const handleNodeClick = (side: Side, componentId: string, metric: string, tabName: string, metricLabel: string) => {
    if (!pending) {
      setPending({ side, componentId, metric, tabName, metricLabel });
      return;
    }
    if (pending.side === side) {
      setPending({ side, componentId, metric, tabName, metricLabel }); // 同侧再点：允许改主意
      return;
    }
    const quoteEnd = side === 'quote' ? { componentId, metric, tabName, metricLabel } : pending;
    const costingEnd = side === 'costing' ? { componentId, metric, tabName, metricLabel } : pending;
    const newPair: PairRow = {
      id: genUUID(),
      quoteComponentId: quoteEnd.componentId, quoteMetric: quoteEnd.metric,
      quoteTabName: quoteEnd.tabName, quoteMetricLabel: quoteEnd.metricLabel,
      costingComponentId: costingEnd.componentId, costingMetric: costingEnd.metric,
      costingTabName: costingEnd.tabName, costingMetricLabel: costingEnd.metricLabel,
      threshold: 0,
    };
    setPairs((prev) => [...prev, newPair]);
    setPending(null);
  };

  const toggleCollapse = (gkey: string) => {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(gkey)) next.delete(gkey); else next.add(gkey);
      return next;
    });
  };

  const removePair = (id: string) => setPairs((prev) => prev.filter((p) => p.id !== id));
  const updateThreshold = (id: string, threshold: number) =>
    setPairs((prev) => prev.map((p) => (p.id === id ? { ...p, threshold } : p)));

  const scrollAndFlash = (id: string) => {
    pairRowRefs.current[id]?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    setFlashPairId(id);
    window.setTimeout(() => setFlashPairId((cur) => (cur === id ? null : cur)), 900);
  };

  const connectedKeys = useMemo(() => {
    const s = new Set<string>();
    pairs.forEach((p) => {
      s.add(nodeKey('quote', p.quoteComponentId, p.quoteMetric));
      s.add(nodeKey('costing', p.costingComponentId, p.costingMetric));
    });
    return s;
  }, [pairs]);
  const pendingKey = pending ? nodeKey(pending.side, pending.componentId, pending.metric) : null;

  const renderNode = (side: Side, componentId: string, tabName: string, m: ComparisonMetricMeta) => {
    const key = nodeKey(side, componentId, m.key);
    const isTotal = m.type === 'TAB_TOTAL';
    const connected = connectedKeys.has(key);
    const isPending = pendingKey === key;
    const portEl = (
      <span
        ref={(el) => { portRefs.current[key] = el; }}
        style={{
          width: 10, height: 10, borderRadius: '50%', border: '2px solid #1677ff',
          background: connected || isPending ? '#1677ff' : '#fff',
          transform: isPending ? 'scale(1.4)' : undefined,
          boxShadow: isPending ? '0 0 0 3px rgba(22,119,255,.2)' : undefined,
          flexShrink: 0, boxSizing: 'border-box', transition: 'all .15s', display: 'inline-block',
        }}
      />
    );
    const labelEl = (
      <span
        style={{
          overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
          color: isTotal ? '#d46b08' : 'inherit', fontWeight: isTotal ? 600 : undefined,
          textAlign: side === 'costing' ? 'right' : undefined,
        }}
      >
        <span
          style={{
            display: 'inline-block', width: 6, height: 6, borderRadius: '50%',
            background: isTotal ? '#fa8c16' : '#52c41a', marginRight: 6, flexShrink: 0,
          }}
        />
        {formatMetricLabel(m.label)}
      </span>
    );
    return (
      <div
        key={m.key}
        onClick={() => handleNodeClick(side, componentId, m.key, tabName, m.label)}
        style={{
          display: 'flex', alignItems: 'center', gap: 8, justifyContent: 'space-between',
          padding: side === 'quote' ? '5px 12px 5px 22px' : '5px 22px 5px 12px',
          fontSize: 12.5, color: 'rgba(0,0,0,.88)', cursor: 'pointer', userSelect: 'none',
        }}
      >
        {side === 'quote' ? (<>{labelEl}{portEl}</>) : (<>{portEl}{labelEl}</>)}
      </div>
    );
  };

  const renderGroup = (side: Side, tab: ComparisonTabMeta) => {
    const gkey = groupKey(side, tab.componentId);
    const isCollapsed = collapsed.has(gkey);
    return (
      <div key={tab.componentId}>
        <div
          ref={(el) => { groupRefs.current[gkey] = el; }}
          onClick={() => toggleCollapse(gkey)}
          style={groupTitleStyle}
        >
          <span
            style={{
              display: 'inline-block', transition: 'transform .15s', fontSize: 10, flexShrink: 0,
              transform: isCollapsed ? 'rotate(-90deg)' : undefined,
            }}
          >
            ▾
          </span>
          <span>{tab.tabName}</span>
        </div>
        {!isCollapsed && <div style={{ padding: '4px 0' }}>{tab.metrics.map((m) => renderNode(side, tab.componentId, tab.tabName, m))}</div>}
      </div>
    );
  };

  const handleConfirm = () => {
    if (!pairs.length) {
      message.warning('请先连线配置至少一对');
      return;
    }
    const input: LinkPairInput[] = pairs.map((p) => ({
      quoteComponentId: p.quoteComponentId, quoteMetric: p.quoteMetric,
      quoteTabName: p.quoteTabName, quoteMetricLabel: p.quoteMetricLabel,
      costingComponentId: p.costingComponentId, costingMetric: p.costingMetric,
      costingTabName: p.costingTabName, costingMetricLabel: p.costingMetricLabel,
      threshold: p.threshold,
    }));
    onConfirm(input);
  };

  return (
    <Drawer
      title="新增比对列 · 连线配置"
      placement="right"
      width={960}
      styles={{ body: { padding: '20px 24px' } }}
      open={open}
      onClose={onClose}
      destroyOnHidden
      afterOpenChange={(isOpen) => { if (isOpen) requestAnimationFrame(() => redraw()); }}
      footer={
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" onClick={handleConfirm}>确定</Button>
        </div>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <div style={{ position: 'relative', display: 'flex', border: '1px solid #f0f0f0', borderRadius: 6, height: 380, flexShrink: 0 }}>
          <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', borderRight: '1px solid #f0f0f0' }}>
            <div style={colTitleStyle}>报价单页签</div>
            <div onScroll={() => redraw()} style={{ flex: 1, overflowY: 'auto', padding: '4px 0' }}>
              {meta?.quoteTabs?.length
                ? meta.quoteTabs.map((t) => renderGroup('quote', t))
                : <div style={emptyColStyle}>暂无报价侧页签</div>}
            </div>
          </div>
          <div style={{ width: 72, flexShrink: 0 }} />
          <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', borderLeft: '1px solid #f0f0f0' }}>
            <div style={colTitleStyle}>核价单页签</div>
            <div onScroll={() => redraw()} style={{ flex: 1, overflowY: 'auto', padding: '4px 0' }}>
              {meta?.costingTabs?.length
                ? meta.costingTabs.map((t) => renderGroup('costing', t))
                : <div style={emptyColStyle}>暂无核价侧页签</div>}
            </div>
          </div>
          <svg ref={svgRef} style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', pointerEvents: 'none' }}>
            {paths.map((p) => (
              <path
                key={p.id}
                d={p.d}
                fill="none"
                stroke={hoveredPairId === p.id ? '#fa8c16' : '#1677ff'}
                strokeWidth={hoveredPairId === p.id ? 3 : 2}
                strokeDasharray={p.dashed ? '5 4' : undefined}
                opacity={hoveredPairId === p.id ? 1 : (p.dashed ? 0.45 : 0.7)}
                style={{ pointerEvents: 'stroke', cursor: 'pointer' }}
                onMouseEnter={() => setHoveredPairId(p.id)}
                onMouseLeave={() => setHoveredPairId(null)}
                onClick={() => scrollAndFlash(p.id)}
              />
            ))}
          </svg>
        </div>

        <div style={{ flexShrink: 0 }}>
          <div style={sectionTitleStyle}>已配对清单</div>
          {pairs.length ? (
            <div style={{ maxHeight: 160, overflowY: 'auto', border: '1px solid #f0f0f0', borderRadius: 6 }}>
              {pairs.map((p) => (
                <div
                  key={p.id}
                  ref={(el) => { pairRowRefs.current[p.id] = el; }}
                  onMouseEnter={() => setHoveredPairId(p.id)}
                  onMouseLeave={() => setHoveredPairId(null)}
                  style={{
                    display: 'flex', alignItems: 'center', gap: 10, padding: '6px 12px', fontSize: 12.5,
                    color: 'rgba(0,0,0,.88)', borderBottom: '1px solid #f5f5f5',
                    background: hoveredPairId === p.id || flashPairId === p.id ? '#fff7e6' : undefined,
                    transition: 'background .15s',
                  }}
                >
                  <span style={{ flex: 1, minWidth: 0 }}>
                    报价：{buildTabPairLabel(p.quoteTabName, p.quoteMetricLabel)} ↔ 核价：{buildTabPairLabel(p.costingTabName, p.costingMetricLabel)}
                  </span>
                  <span style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 12, color: 'rgba(0,0,0,.45)', flexShrink: 0 }}>
                    阈值
                    <InputNumber
                      size="small"
                      style={{ width: 64 }}
                      value={p.threshold}
                      onChange={(v) => updateThreshold(p.id, v ?? 0)}
                      onClick={(e) => e.stopPropagation()}
                    />
                  </span>
                  <CloseOutlined
                    style={{ cursor: 'pointer', color: 'rgba(0,0,0,.35)', fontSize: 13, flexShrink: 0 }}
                    title="删除该配对"
                    onClick={(e) => { e.stopPropagation(); removePair(p.id); }}
                  />
                </div>
              ))}
            </div>
          ) : (
            <div style={emptyHintStyle}>点击左侧报价节点，再点击右侧核价节点，完成一对连线</div>
          )}
        </div>

        <div style={{ fontSize: 12, color: '#8c8c8c', lineHeight: 1.6 }}>
          差异值(报价 − 核价) &lt; 阈值 标橙；&lt; 0 固定标红（红线全局固定，不可配置）。每条配对可单独设置阈值，默认 0。
        </div>
      </div>
    </Drawer>
  );
};

export default LinkConfigDrawer;
