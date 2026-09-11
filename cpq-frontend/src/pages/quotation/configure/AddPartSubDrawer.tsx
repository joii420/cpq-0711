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
 *
 * ── task-260910 · F-3：第三张类型卡「直接绑定已有销售料号」（服务 AC-18 / AC-20）────────
 *  视觉基准 `原型图/01-配件类型选择-加第三张卡.html`：虚线边框 + 绿色 badge，
 *  与前两张卡的区别不是「另一种配件」而是**另一条路**——
 *    选中它 ⇒ 进料号搜索（`BindExistingPartPanel`）⇒ **选中即完成**，
 *    🚫 不采集材质、不采集工序，产出的不是 `parts[]` 里的一项，而是宿主的一个**绑定**。
 *
 *  🚫 **与「加配件」双向互斥**（`api.md §2.3`：两者同时非空 → 400 `BIND_AND_PARTS_EXCLUSIVE`）：
 *    ① 已加配件 ⇒ 本卡**禁用但可见** + tooltip 写明原因（§1.2），见 `bindDisabledReason`；
 *    ② 已选绑定 ⇒ 宿主禁用「+ 添加配件」，且本面板由「更换料号」直接进 `bind` 阶段
 *       （`initialStageOverride`）—— 用户要改成加配件必须先在宿主那里「移除绑定」。
 *    ⇒ 两个方向都在 UI 层拦住，**那个 400 从界面上走不到**。
 */
import React, { useState } from 'react';
import { Button, Tooltip } from 'antd';
import type { SelParamCandidate } from '../../../services/selParamCandidateService';
import type { MaterialRecipeLite } from '../../../services/materialRecipeService';
import type { ConfigurePart } from '../../../types/configure';
import NewPartPanel from './NewPartPanel';
import ExistingPartPanel from './ExistingPartPanel';
import OutsourcedPartPanel from './OutsourcedPartPanel';
import BindExistingPartPanel, { type BoundMaterial } from './BindExistingPartPanel';

type Stage = 'type' | 'source' | 'new' | 'existing' | 'outsourced' | 'bind';

interface Props {
  open: boolean;
  /** null = 新增；非空 = 编辑该配件（直接进对应的表单，不再问类型） */
  editing: ConfigurePart | null;
  /**
   * 客户编码（`customer.code`）。两个候选端点已把它列为**必填**（AC-6 / AC-7）⇒ 必须透传到
   * `ExistingPartPanel` / `OutsourcedPartPanel` / `BindExistingPartPanel` 三个面板。
   */
  customerNo: string | undefined;
  /**
   * 已选中的绑定料号（非空 = 宿主处于绑定态）。用于「更换料号」时回填选中行。
   */
  boundMaterial: BoundMaterial | null;
  /**
   * 第三张卡的禁用原因（`null` = 可选）。由宿主按「是否已加配件」给出 —— 判据留在宿主，
   * 因为只有它知道 `parts` 有几个（§1.2：禁用但可见 + 写明原因）。
   */
  bindDisabledReason: string | null;
  /**
   * 非空时**跳过类型选择直接进该阶段**。宿主的「更换料号」用 `'bind'` 走这条路。
   * 🚫 不要用它做别的事 —— 它绕过的是「选类型」这一问，绕多了就说不清用户到底选了什么。
   */
  initialStageOverride?: Stage;
  materials: MaterialRecipeLite[];
  materialsLoading?: boolean;
  materialsError?: string | null;
  processCandidates: SelParamCandidate[];
  processLoading?: boolean;
  processError?: string | null;
  onConfirm: (part: ConfigurePart) => void;
  /** 绑定路径的出口：选中即完成，产出一个绑定而不是一个配件（AC-18）。 */
  onBind: (bound: BoundMaterial) => void;
  onCancel: () => void;
}

function initialStage(editing: ConfigurePart | null, override?: Stage): Stage {
  // 宿主的「更换料号」直接落到 bind 阶段（绕过「选类型」这一问）
  if (override) return override;
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
  /**
   * `'shortcut'` = 第三张卡的「捷径」外观：**虚线边框 + 绿色 badge**（原型 01）。
   * 它和前两张不是同一层语义（那两张选的是「配件类型」，这张选的是「不配配件」），
   * 用不同的边框形态把这件事讲出来，而不是靠用户读文案发现。
   */
  variant?: 'default' | 'shortcut';
  badge?: string;
  /** 非空 = 禁用，并把原文挂到 hover tooltip（§1.2：禁用但可见 + 写明原因）。 */
  disabledReason?: string | null;
}

/** 并排选择卡片（原型 `.pick` / `.card`）。选项都常用 ⇒ 并排展示而不是塞进下拉。 */
const PickCard: React.FC<PickCardProps> = ({
  icon, title, desc, active, onClick, variant = 'default', badge, disabledReason,
}) => {
  const disabled = !!disabledReason;
  const shortcut = variant === 'shortcut';
  // 边框色对齐原型 `_base.css`：`.card` #d9d9d9 / `.card.sel` #1890ff / `.card.new` 只改虚实不改颜色
  //（🚫 绿色只出现在 badge 上 —— 整条边都绿会把它读成「已完成」而不是「捷径」）
  const borderColor = disabled ? '#d9d9d9' : (active ? '#1677ff' : (shortcut ? '#d9d9d9' : '#e4e7ed'));
  const card = (
    <div
      onClick={disabled ? undefined : onClick}
      style={{
        flex: '1 1 260px', display: 'flex', gap: 12, alignItems: 'flex-start',
        cursor: disabled ? 'not-allowed' : 'pointer',
        padding: 16, borderRadius: 8, position: 'relative',
        border: `1px ${shortcut ? 'dashed' : 'solid'} ${borderColor}`,
        background: disabled ? '#f5f5f5' : (active ? '#f0f8ff' : '#fff'),
        color: disabled ? 'rgba(0,0,0,.25)' : undefined,
        boxShadow: active && !disabled ? '0 0 0 2px rgba(22,119,255,.08)' : undefined,
      }}
    >
      <span style={{ fontSize: 24, lineHeight: 1.2 }}>{icon}</span>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 4 }}>{title}</div>
        <div style={{ fontSize: 12, color: disabled ? 'rgba(0,0,0,.25)' : '#909399', lineHeight: 1.7 }}>{desc}</div>
        {badge ? (
          <div
            style={{
              display: 'inline-block', marginTop: 8, padding: '0 4px', borderRadius: 2, fontSize: 11,
              background: disabled ? '#f0f0f0' : '#f6ffed',
              border: `1px solid ${disabled ? '#d9d9d9' : '#b7eb8f'}`,
              color: disabled ? 'rgba(0,0,0,.25)' : '#389e0d',
            }}
          >
            {badge}
          </div>
        ) : null}
      </div>
      <span
        style={{
          flex: 'none', width: 16, height: 16, borderRadius: '50%', marginTop: 4,
          border: active && !disabled ? '5px solid #1677ff' : '1px solid #d9d9d9',
          background: '#fff',
        }}
      />
    </div>
  );
  // 🚫 不用 `return null` 把不可用的卡藏起来 —— 用户看不到就不知道有这个能力，
  //    更不知道为什么用不了（§1.2）。禁用 + tooltip 写明原因。
  if (!disabled) return card;
  return (
    <Tooltip title={disabledReason}>
      <div style={{ flex: '1 1 260px', display: 'flex' }}>{card}</div>
    </Tooltip>
  );
};

const AddPartSubDrawer: React.FC<Props> = ({
  open, editing, customerNo, boundMaterial, bindDisabledReason, initialStageOverride,
  materials, materialsLoading, materialsError,
  processCandidates, processLoading, processError, onConfirm, onBind, onCancel,
}) => {
  const [stage, setStage] = useState<Stage>(() => initialStage(editing, initialStageOverride));
  /**
   * 第一步选中的**路**。前两个值是「配件类型」，`BIND` 是第三条路（不产配件）——
   * 三者在这一步是同一个单选，所以放同一个 state；分流发生在「下一步」。
   */
  const [pick, setPick] = useState<'PART' | 'OUTSOURCED' | 'BIND'>(
    initialStageOverride === 'bind' || boundMaterial ? 'BIND' : (editing?.partType ?? 'PART'),
  );
  const partType: 'PART' | 'OUTSOURCED' = pick === 'OUTSOURCED' ? 'OUTSOURCED' : 'PART';
  const [partSource, setPartSource] = useState<'new' | 'existing'>(
    editing?.partMode === 'existing' ? 'existing' : 'new',
  );
  /**
   * 🚨 本组件**没有**「打开时重置」的逻辑，是有意的：宿主用
   *    `key={`${editingUid ?? '__new__'}#${subSession}`}` 保证**每次打开都重新挂载**，
   *    上面三个 `useState` 的初始值即是本次会话的正确起点。
   * 🚫 不要改成「在组件内部靠比较 uid 来重置」—— 连续两次新增时 uid 都是 null，
   *    比较不出差异，第二次就会停在上一次留下的表单上（真机实测过）。
   */
  if (!open) return null;

  const title = (() => {
    if (stage === 'type') return '添加配件 · 第 1 步：选择类型';
    if (stage === 'source') return '添加配件 · 第 2 步：零件来源';
    if (stage === 'new') return editing ? '编辑配件 · 新建零件' : '添加配件 · 新建零件';
    if (stage === 'existing') return editing ? '编辑配件 · 已有零件' : '添加配件 · 选择已有零件';
    // 绑定路径：第 2 步就是终点（选中即完成），标题里直接写出来
    if (stage === 'bind') return '添加配件 · 第 2 步：直接绑定已有销售料号';
    return editing ? '编辑配件 · 外购件' : '添加配件 · 选择外购件';
  })();

  /** 从表单往回退：编辑态直接关闭（没有"上一步"可退），新增态退回类型/来源选择。 */
  const backFromForm = () => {
    if (editing) { onCancel(); return; }
    if (stage === 'bind') { setStage('type'); return; }
    setStage(partType === 'OUTSOURCED' ? 'type' : 'source');
  };

  const body = (() => {
    if (stage === 'type') {
      return (
        <div style={{ padding: '16px 20px' }}>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
            <PickCard
              icon="🔩" title="零件" active={pick === 'PART'} onClick={() => setPick('PART')}
              desc={<>本厂加工的零件。可以新建，也可以引用已有零件。<br />零件下面挂 1~N 个材质，每个材质填占比。</>}
            />
            <PickCard
              icon="🛒" title="外购件" active={pick === 'OUTSOURCED'} onClick={() => setPick('OUTSOURCED')}
              desc={<>从供应商采购的成品件，不含材质构成。<br />从现有料号库里选，再选它的工序。</>}
            />
            {/* 🆕 task-260910 · F-3（AC-18 / AC-20）：第三条路 —— 虚线边框 + 绿色 badge（原型 01） */}
            <PickCard
              icon="🔗" title="直接绑定已有销售料号"
              variant="shortcut"
              badge="最简：无需配材质/工序"
              active={pick === 'BIND'}
              disabledReason={bindDisabledReason}
              onClick={() => setPick('BIND')}
              desc={<>客户产品编号绑到<br />一个已有销售料号</>}
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
    if (stage === 'bind') {
      return (
        <BindExistingPartPanel
          initial={boundMaterial}
          customerNo={customerNo}
          onConfirm={onBind}
          onBack={backFromForm}
        />
      );
    }
    if (stage === 'existing') {
      return (
        <ExistingPartPanel
          initial={editing}
          customerNo={customerNo}
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
        customerNo={customerNo}
        processCandidates={processCandidates}
        processLoading={processLoading}
        processError={processError}
        onConfirm={onConfirm}
        onBack={backFromForm}
        onCancel={onCancel}
        onSwitchToPart={() => { setPick('PART'); setPartSource('new'); setStage('new'); }}
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
                // 外购件没有「来源」这一问 —— 直接进料号选择；绑定同理，直接进料号搜索
                if (pick === 'BIND') { setStage('bind'); return; }
                setStage(pick === 'OUTSOURCED' ? 'outsourced' : 'source');
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
