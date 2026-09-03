/**
 * AddPartSubDrawer — 添加/编辑配件的内层面板（task-260902 · F-3，服务 AC-5 / AC-13 / AC-14）。
 *
 * 1:1 对齐 `原型图/2-配件类型与来源.html` 状态 B（配件类型）/ 状态 C（零件来源），
 * 之后按选择分流到 `NewPartPanel`(原型3) / `ExistingPartPanel`(原型4) / `OutsourcedPartPanel`(原型5)。
 *
 * ⚠️ **整体重做**：task-0712 版本是「一行 = 一个材质料号」的两层模型（`SelDetailRow`），
 *    task-260901 刚把它的子步骤由 3 段并为 2 段。本次改成三层模型
 *    产品 → **配件（零件 / 外购件）** → 零件挂 1~N 个材质 → 每个材质选含量配置，
 *    「配件类型」这一中间层是**本次重构新增的**，现状没有这个概念。
 *
 * 📌 **两个正交维度，不是一个**：现状 `PartRequest.partMode` 只有 `existing`/`custom` 两值，
 *    是一个维度；新流程是 **配件类型（零件/外购件） × 零件来源（新建/已有）**。
 *    外购件没有「来源」这一问 —— 它只能从料号库选，所以选了外购件直接跳过第 2 步。
 *
 * 🚫 **不用嵌套 Drawer**（`frontend.md §1.1` 要求的是「别用 Modal」，不是「必须每层一个 Drawer」）：
 *    本面板是覆盖宿主抽屉正文的内层局部面板（`position:absolute; inset:0`），
 *    沿用 task-0712 的做法，避免嵌套 Drawer 的层级 / ESC 冲突。
 */
import React, { useState } from 'react';
import { Button } from 'antd';
import type { SelParamCandidate } from '../../../services/selParamCandidateService';
import type { MaterialRecipeLite } from '../../../services/materialRecipeService';
import type { ConfigurePart } from '../../../types/configure';
import NewPartPanel from './NewPartPanel';
import ExistingPartPanel from './ExistingPartPanel';
import OutsourcedPartPanel from './OutsourcedPartPanel';

type Stage = 'type' | 'source' | 'new' | 'existing' | 'outsourced';

interface Props {
  open: boolean;
  /** null = 新增；非空 = 编辑该配件（直接进对应的表单，不再问类型） */
  editing: ConfigurePart | null;
  materials: MaterialRecipeLite[];
  materialsLoading?: boolean;
  materialsError?: string | null;
  processCandidates: SelParamCandidate[];
  processLoading?: boolean;
  processError?: string | null;
  onConfirm: (part: ConfigurePart) => void;
  onCancel: () => void;
}

function initialStage(editing: ConfigurePart | null): Stage {
  if (!editing) return 'type';
  if (editing.partType === 'OUTSOURCED') return 'outsourced';
  return editing.partMode === 'existing' ? 'existing' : 'new';
}

interface PickCardProps {
  icon: string;
  title: string;
  desc: React.ReactNode;
  active: boolean;
  onClick: () => void;
}

/** 并排选择卡片（原型 `.pick`）。两个选项都常用 ⇒ 并排展示而不是塞进下拉。 */
const PickCard: React.FC<PickCardProps> = ({ icon, title, desc, active, onClick }) => (
  <div
    onClick={onClick}
    style={{
      flex: '1 1 260px', display: 'flex', gap: 12, alignItems: 'flex-start', cursor: 'pointer',
      padding: 16, borderRadius: 8,
      border: `1px solid ${active ? '#1677ff' : '#e4e7ed'}`,
      background: active ? '#f0f8ff' : '#fff',
      boxShadow: active ? '0 0 0 2px rgba(22,119,255,.08)' : undefined,
    }}
  >
    <span style={{ fontSize: 24, lineHeight: 1.2 }}>{icon}</span>
    <div style={{ flex: 1, minWidth: 0 }}>
      <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 4 }}>{title}</div>
      <div style={{ fontSize: 12, color: '#909399', lineHeight: 1.7 }}>{desc}</div>
    </div>
    <span
      style={{
        flex: 'none', width: 16, height: 16, borderRadius: '50%', marginTop: 4,
        border: active ? '5px solid #1677ff' : '1px solid #d9d9d9', background: '#fff',
      }}
    />
  </div>
);

const AddPartSubDrawer: React.FC<Props> = ({
  open, editing, materials, materialsLoading, materialsError,
  processCandidates, processLoading, processError, onConfirm, onCancel,
}) => {
  const [stage, setStage] = useState<Stage>(() => initialStage(editing));
  const [partType, setPartType] = useState<'PART' | 'OUTSOURCED'>(editing?.partType ?? 'PART');
  const [partSource, setPartSource] = useState<'new' | 'existing'>(
    editing?.partMode === 'existing' ? 'existing' : 'new',
  );
  /** 每次打开重置（`key` 由宿主控制时不会走到这里，留作双保险）。 */
  const [openedFor, setOpenedFor] = useState<string | null>(null);
  if (open && openedFor !== (editing?.uid ?? '__new__')) {
    setOpenedFor(editing?.uid ?? '__new__');
    setStage(initialStage(editing));
    setPartType(editing?.partType ?? 'PART');
    setPartSource(editing?.partMode === 'existing' ? 'existing' : 'new');
  }

  if (!open) return null;

  const title = (() => {
    if (stage === 'type') return '添加配件 · 第 1 步：选择类型';
    if (stage === 'source') return '添加配件 · 第 2 步：零件来源';
    if (stage === 'new') return editing ? '编辑配件 · 新建零件' : '添加配件 · 新建零件';
    if (stage === 'existing') return editing ? '编辑配件 · 已有零件' : '添加配件 · 选择已有零件';
    return editing ? '编辑配件 · 外购件' : '添加配件 · 选择外购件';
  })();

  /** 从表单往回退：编辑态直接关闭（没有"上一步"可退），新增态退回类型/来源选择。 */
  const backFromForm = () => {
    if (editing) { onCancel(); return; }
    setStage(partType === 'OUTSOURCED' ? 'type' : 'source');
  };

  const body = (() => {
    if (stage === 'type') {
      return (
        <div style={{ padding: '16px 20px' }}>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
            <PickCard
              icon="🔩" title="零件" active={partType === 'PART'} onClick={() => setPartType('PART')}
              desc={<>本厂加工的零件。可以新建，也可以引用已有零件。<br />零件下面挂 1~N 个材质，每个材质填占比。</>}
            />
            <PickCard
              icon="🛒" title="外购件" active={partType === 'OUTSOURCED'} onClick={() => setPartType('OUTSOURCED')}
              desc={<>从供应商采购的成品件，不含材质构成。<br />从现有料号库里选，再选它的工序。</>}
            />
          </div>
        </div>
      );
    }
    if (stage === 'source') {
      return (
        <div style={{ padding: '16px 20px' }}>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
            <PickCard
              icon="✨" title="新建零件" active={partSource === 'new'} onClick={() => setPartSource('new')}
              desc={<>填品名 / 规格 / 尺寸 / 总重，再挂材质。<br />适用于这个客户产品特有的零件。</>}
            />
            <PickCard
              icon="📚" title="已有零件" active={partSource === 'existing'} onClick={() => setPartSource('existing')}
              desc={<>从产品列表里选一个已存在的零件，只需再选工序。<br />材质构成沿用它自己的，不重新配。</>}
            />
          </div>
        </div>
      );
    }
    if (stage === 'new') {
      return (
        <NewPartPanel
          initial={editing}
          materials={materials}
          materialsLoading={materialsLoading}
          materialsError={materialsError}
          processCandidates={processCandidates}
          processLoading={processLoading}
          processError={processError}
          onConfirm={onConfirm}
          onBack={backFromForm}
          onCancel={onCancel}
        />
      );
    }
    if (stage === 'existing') {
      return (
        <ExistingPartPanel
          initial={editing}
          processCandidates={processCandidates}
          processLoading={processLoading}
          processError={processError}
          onConfirm={onConfirm}
          onBack={backFromForm}
          onCancel={onCancel}
          onSwitchToNew={() => { setPartSource('new'); setStage('new'); }}
        />
      );
    }
    return (
      <OutsourcedPartPanel
        initial={editing}
        processCandidates={processCandidates}
        processLoading={processLoading}
        processError={processError}
        onConfirm={onConfirm}
        onBack={backFromForm}
        onCancel={onCancel}
        onSwitchToPart={() => { setPartType('PART'); setPartSource('new'); setStage('new'); }}
      />
    );
  })();

  /** 只有前两个选择步骤需要本组件自己出 footer；三个表单各自带 footer。 */
  const showOwnFooter = stage === 'type' || stage === 'source';

  return (
    <div
      style={{
        position: 'absolute', inset: 0, zIndex: 5, background: '#fff', display: 'flex',
        flexDirection: 'column', boxShadow: '-6px 0 16px rgba(0,0,0,.06)',
      }}
    >
      <div style={{ padding: '14px 20px 12px', borderBottom: '1px solid #f0f0f0', display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexShrink: 0 }}>
        <span style={{ fontSize: 14, fontWeight: 600 }}>{title}</span>
        <span style={{ cursor: 'pointer', color: '#909399', fontSize: 18, lineHeight: 1 }} onClick={onCancel}>✕</span>
      </div>

      <div style={{ flex: 1, overflow: 'auto', display: 'flex', flexDirection: 'column' }}>
        {body}
      </div>

      {showOwnFooter && (
        <div style={{ padding: '12px 20px', borderTop: '1px solid #f0f0f0', display: 'flex', gap: 8, justifyContent: 'flex-end', flexShrink: 0 }}>
          <Button onClick={onCancel}>取消</Button>
          {stage === 'source' ? <Button onClick={() => setStage('type')}>上一步</Button> : null}
          <Button
            type="primary"
            onClick={() => {
              if (stage === 'type') {
                // 外购件没有「来源」这一问 —— 直接进料号选择
                setStage(partType === 'OUTSOURCED' ? 'outsourced' : 'source');
              } else {
                setStage(partSource === 'existing' ? 'existing' : 'new');
              }
            }}
          >
            下一步
          </Button>
        </div>
      )}
    </div>
  );
};

export default AddPartSubDrawer;
