/**
 * FormulaCycleDrawer —— 公式循环引用链路抽屉（repair-0803 FR-10/FR-11）。
 *
 * 组件保存 / 模板发布检出「公式存在循环引用」时弹出，逐个环展示完整链路 + 每条边出自哪条
 * 公式，全程用组件名称 / 公式名称 / 字段名称，不出现任何 UUID 或字段 id（AC-11）。
 *
 * 本期只读展示：节点不可点击、不跳转（跳转增强已登记 BACKLOG）。
 *
 * 数据形态与后端 {@code FormulaCycleException.Node} / {@code Edge} / {@code Cycle} 逐字对齐
 * （字段名为 `from`/`to`/`col`/`colType`/`viaFormulaName`/`viaDesc`，不是 api.md 草案文档里的
 * `fromField`/`toField`/`fromComponentName`/`toComponentName` —— 那是立项时的方案草稿，实现时
 * 未采用；本文件按后端实际落地的 record 字段名对齐，见 FormulaCycleException.java）。
 */
import React from 'react';
import { Drawer, Button, Collapse, Tag, Alert } from 'antd';
import type { CollapseProps } from 'antd';

/** 环上的一个节点。scope=FIELD 时 fieldName 有值；scope=TAB 时为 undefined。 */
export interface FormulaCycleNode {
  componentName: string;
  fieldName?: string;
  formulaName?: string;
}

/**
 * 环上的一条边：`from` 因为 `viaFormulaName`/`viaDesc` 这条引用而依赖 `to`。
 * - scope=FIELD：from/to 为字段名，viaDesc 是完整人话描述（如「公式「X」」「条件规则2命中的公式「Y」」
 *   「条件规则1的判断条件」），已由后端拼好，直接展示，不再拼接 viaFormulaName。
 * - scope=TAB：from/to 为组件（页签）名，col/colType 为被引用列名与列类型说明，viaFormulaName 为来源公式名。
 */
export interface FormulaCycleEdge {
  from: string;
  to: string;
  col?: string;
  colType?: string;
  viaFormulaName?: string;
  viaDesc: string;
}

export interface FormulaCycle {
  /** FIELD=同组件内字段环 | TAB=跨页签组件环 */
  scope: 'FIELD' | 'TAB';
  /** 环所在组件名称（scope=FIELD 时有值） */
  componentName?: string;
  /** 按链路顺序排列，首尾不重复（本组件渲染时自行闭合回首节点） */
  nodes: FormulaCycleNode[];
  /** 边数 = 节点数（含闭合边） */
  edges: FormulaCycleEdge[];
}

interface Props {
  open: boolean;
  onClose: () => void;
  cycles: FormulaCycle[];
}

const nodeLabel = (cy: FormulaCycle, n: FormulaCycleNode): React.ReactNode =>
  cy.scope === 'TAB'
    ? <>页签「{n.componentName}」</>
    : <>「{n.fieldName}」</>;

const renderEdgeText = (cy: FormulaCycle, e: FormulaCycleEdge): React.ReactNode => {
  if (cy.scope === 'TAB') {
    return (
      <>
        页签「{e.from}」的{' '}
        <span style={{ color: '#1677ff', fontWeight: 500 }}>公式「{e.viaFormulaName || '未命名公式'}」</span>
        {' '}中引用了 页签「{e.to}」的{' '}
        <span style={{ color: '#d46b08', fontWeight: 500 }}>[{e.col || '—'}]</span>
        {e.colType && <span style={{ color: 'rgba(0,0,0,.45)', fontSize: 12 }}>（{e.colType}）</span>}
      </>
    );
  }
  return (
    <>
      「{e.from}」的 {e.viaDesc} 中引用了{' '}
      <span style={{ color: '#d46b08', fontWeight: 500 }}>[{e.to}]</span>
    </>
  );
};

const chainStyle: React.CSSProperties = {
  display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 6, padding: 12,
  background: '#fff2f0', border: '1px solid #ffccc7', borderRadius: 6, marginBottom: 14,
};
const nodeChipStyle: React.CSSProperties = {
  display: 'inline-flex', background: '#fff', border: '1px solid #ffccc7',
  borderRadius: 6, padding: '5px 11px', fontSize: 13, fontWeight: 500, whiteSpace: 'nowrap',
};
const loopChipStyle: React.CSSProperties = { ...nodeChipStyle, border: '1px dashed #ffccc7', opacity: 0.75 };

const renderChain = (cy: FormulaCycle): React.ReactNode => (
  <div style={chainStyle}>
    {cy.nodes.map((n, idx) => (
      <React.Fragment key={idx}>
        {idx > 0 && <span style={{ color: '#ff4d4f', fontSize: 15, fontWeight: 700 }}>→</span>}
        <span style={nodeChipStyle}>{nodeLabel(cy, n)}</span>
      </React.Fragment>
    ))}
    {cy.nodes.length > 0 && (
      <>
        <span style={{ color: '#ff4d4f', fontSize: 15, fontWeight: 700 }}>→</span>
        <span style={loopChipStyle}>{nodeLabel(cy, cy.nodes[0])}</span>
      </>
    )}
  </div>
);

const renderCycleBody = (cy: FormulaCycle): React.ReactNode => {
  const hint = cy.scope === 'FIELD'
    ? '这些字段的公式互相引用。系统按「被引用者先算」推导顺序，成环后无法定序 —— 保存已阻止。'
    : '页签之间也需要先后顺序：被引用的页签必须先算完，成环后整张卡片无法渲染。判定口径：只有引用公式列才产生这种等待关系（该列的值要算过才有）；引用输入列/取数列（如手工填写的列）不建立依赖 —— 两个页签互相引用但引用的都是输入列时，不算环。';

  return (
    <div>
      {renderChain(cy)}
      <div style={{ fontSize: 12, color: 'rgba(0,0,0,.45)', marginBottom: 8 }}>
        每一条引用（打断其中任意一条即可解环）
      </div>
      <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
        {cy.edges.map((e, idx) => (
          <li
            key={idx}
            style={{
              display: 'flex', gap: 9, padding: '7px 0',
              borderTop: idx === 0 ? 'none' : '1px dashed #f0f0f0',
              fontSize: 13, color: 'rgba(0,0,0,.65)', lineHeight: 1.7,
            }}
          >
            <span style={{ color: 'rgba(0,0,0,.45)', flexShrink: 0 }}>·</span>
            <div>{renderEdgeText(cy, e)}</div>
          </li>
        ))}
      </ul>
      <div
        style={{
          marginTop: 14, padding: '10px 12px', background: '#fffbe6', border: '1px solid #ffe58f',
          borderRadius: 6, fontSize: 12, color: 'rgba(0,0,0,.65)', lineHeight: 1.8,
        }}
      >
        💡 {hint}
      </div>
    </div>
  );
};

const FormulaCycleDrawer: React.FC<Props> = ({ open, onClose, cycles }) => {
  const safeCycles = cycles ?? [];

  const items: CollapseProps['items'] = safeCycles.map((cy, i) => {
    const where = cy.scope === 'FIELD'
      ? `组件「${cy.componentName ?? ''}」内的字段之间`
      : '产品卡片内的页签之间';
    return {
      key: String(i),
      label: (
        <span style={{ display: 'flex', alignItems: 'center', gap: 10, width: '100%' }}>
          <b>环 {i + 1}</b>
          <Tag color={cy.scope === 'FIELD' ? 'orange' : 'blue'}>
            {cy.scope === 'FIELD' ? '字段环' : '页签环'}
          </Tag>
          <span style={{ color: 'rgba(0,0,0,.45)', fontSize: 12, marginLeft: 'auto' }}>{where}</span>
        </span>
      ),
      children: renderCycleBody(cy),
    };
  });

  // ≥3 个环时默认只展开第 1 个，其余折叠；否则默认全展开。
  const defaultActiveKey = safeCycles.length >= 3 ? ['0'] : items.map((it) => String(it.key));

  return (
    <Drawer
      title={
        <div>
          公式存在循环引用（{safeCycles.length} 处）
          <div style={{ fontSize: 13, color: 'rgba(0,0,0,.45)', marginTop: 4, fontWeight: 400 }}>
            保存已中止。请按下方链路逐个修改后重新保存。
          </div>
        </div>
      }
      placement="right"
      width={720}
      open={open}
      onClose={onClose}
      footer={
        <div style={{ textAlign: 'right' }}>
          <Button type="primary" onClick={onClose}>知道了</Button>
        </div>
      }
    >
      <Alert
        type="error"
        showIcon
        message={
          <>
            公式之间形成了<b>首尾相接的引用</b>，系统无法确定谁先计算。请打断其中<b>任意一条</b>引用即可解除。
          </>
        }
        style={{ marginBottom: 16 }}
      />
      <Collapse items={items} defaultActiveKey={defaultActiveKey} />
    </Drawer>
  );
};

export default FormulaCycleDrawer;
