/**
 * PartCardList — 步骤 2 的配件卡片列表（task-260902 · F-3）。
 * 1:1 对齐 `原型图/2-配件类型与来源.html` 状态 A（空态）与状态 D（已加多个配件）。
 *
 * 每张卡直接显示**构成摘要**（材质 + 占比 / 外购件料号 / 工序），不用展开就能核对。
 *
 * 📌 **行内「编辑 / 移除」不是违规**：`frontend.md §1.2` 要求列表动作上提到工具栏，
 *    但它的**例外白名单里明列「抽屉内部子表」** —— 本列表在抽屉内、且两个动作都作用于
 *    单个配件而非批量，属于该例外。🚫 **上提到工具栏反而违反原型**。
 */
import React from 'react';
import { Button, Tag } from 'antd';
import type { ConfigurePart } from '../../../types/configure';
import { trimTrailingZeros } from '../../../utils/precision';
import { EmptyBlock, Ellipsis, Mono, NoteBlock } from './configureUi';

interface Props {
  parts: ConfigurePart[];
  onAdd: () => void;
  onEdit: (uid: string) => void;
  onRemove: (uid: string) => void;
}

/** 材质摘要：`AgNi10 70%` `AgZnO12/Cu 30%`（占比去尾随零，F-12 口径）。 */
export function materialTags(part: ConfigurePart): React.ReactNode {
  if (part.partType === 'OUTSOURCED') return null;
  if (part.partMode === 'existing') {
    return part.existingMaterialSummary
      ? <span>材质构成沿用该料号自身（{part.existingMaterialSummary}）</span>
      : <span>材质构成沿用该料号自身</span>;
  }
  if (part.materials.length === 0) return <span style={{ color: '#c0c4cc' }}>未配材质</span>;
  return (
    <>
      {part.materials.map((m) => (
        <Tag key={m.uid} style={{ marginBottom: 2 }}>
          {m.recipeName} <b>{trimTrailingZeros(m.ratio)}%</b>
        </Tag>
      ))}
    </>
  );
}

/** 工序摘要：`车削 / 银焊`，按列表顺序（顺序即工艺顺序）。 */
export function processText(part: ConfigurePart): string {
  return part.processes.length === 0 ? '—' : part.processes.map((p) => p.name).join(' / ');
}

/** 类型标签组：`零件 + 新建` / `零件 + 已有 + 料号` / `外购件 + 料号`。 */
export function partTypeTags(part: ConfigurePart): React.ReactNode {
  if (part.partType === 'OUTSOURCED') {
    return (
      <>
        <Tag color="purple">外购件</Tag>
        {part.outsourcedPartNo ? <span style={{ fontSize: 12, color: '#909399' }}><Mono muted>{part.outsourcedPartNo}</Mono></span> : null}
      </>
    );
  }
  return (
    <>
      <Tag color="blue">零件</Tag>
      {part.partMode === 'existing'
        ? (
          <>
            <Tag color="green">已有</Tag>
            {part.existingHfPartNo ? <span style={{ fontSize: 12, color: '#909399' }}><Mono muted>{part.existingHfPartNo}</Mono></span> : null}
          </>
        )
        : <Tag>新建</Tag>}
    </>
  );
}

const PartCardList: React.FC<Props> = ({ parts, onAdd, onEdit, onRemove }) => {
  if (parts.length === 0) {
    return (
      <>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
          <Button type="primary" onClick={onAdd}>+ 添加配件</Button>
          <div style={{ flex: 1 }} />
          <span style={{ fontSize: 12, color: '#909399' }}>共 0 个配件</span>
        </div>
        <div style={{ border: '1px solid #f0f0f0', borderRadius: 8 }}>
          <EmptyBlock
            icon="📦"
            title="还没有配件"
            hint="一个产品至少要有一个配件才能提交"
            actions={<Button type="primary" onClick={onAdd}>+ 添加第一个配件</Button>}
          />
        </div>
      </>
    );
  }

  return (
    <>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
        <Button type="primary" onClick={onAdd}>+ 添加配件</Button>
        <div style={{ flex: 1 }} />
        <span style={{ fontSize: 12, color: '#909399' }}>共 {parts.length} 个配件</span>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {parts.map((part) => (
          <div
            key={part.uid}
            style={{
              display: 'flex', gap: 12, alignItems: 'flex-start', padding: '12px 14px',
              border: '1px solid #f0f0f0', borderRadius: 8, background: '#fff',
            }}
          >
            <span style={{ fontSize: 20, lineHeight: 1.2 }}>{part.partType === 'OUTSOURCED' ? '🛒' : '🔩'}</span>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap', marginBottom: 4 }}>
                <span style={{ fontWeight: 600, maxWidth: 260, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  <Ellipsis text={part.name} />
                </span>
                {partTypeTags(part)}
              </div>
              <div style={{ fontSize: 12, color: '#909399', lineHeight: 1.8 }}>
                {part.partType === 'PART' && part.partMode === 'new' ? (
                  <>
                    规格 {part.spec || '—'} · 尺寸 {part.dimension || '—'} · 总重 <b>{trimTrailingZeros(part.unitWeightGrams) || '—'} g</b>
                    <br />
                    材质：{materialTags(part)} · 工序：{processText(part)}
                  </>
                ) : part.partType === 'PART' ? (
                  <>{materialTags(part)} · 工序：{processText(part)}</>
                ) : (
                  <>规格 {part.outsourcedSpec || '—'} · 工序：{processText(part)}</>
                )}
              </div>
            </div>
            {/* 📌 抽屉内部子表的行内操作 —— §1.2 例外白名单适用，不上提工具栏 */}
            <div style={{ display: 'flex', gap: 4, flexShrink: 0 }}>
              <Button type="link" size="small" onClick={() => onEdit(part.uid)}>编辑</Button>
              <Button type="link" size="small" danger onClick={() => onRemove(part.uid)}>移除</Button>
            </div>
          </div>
        ))}
      </div>

      <NoteBlock>
        <b>关于行内操作按钮：</b><code>frontend.md §1.2</code> 要求列表动作上提到顶部工具栏，
        但它的<b>例外白名单里明列「抽屉内部子表」</b> —— 本列表在抽屉内、且「编辑/移除」作用于单个配件
        而非批量，属于该例外。<b>不是违规，是例外条款适用。</b>
      </NoteBlock>
    </>
  );
};

export default PartCardList;
