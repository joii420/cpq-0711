/**
 * CompositeProcessStep — 步骤 3「组合工序」（task-260902 · F-9）。
 * 1:1 对齐 `原型图/6-组合工序与指纹结果.html` 状态 A。
 *
 * 组合工序作用于**多个配件之间**（如把动触头和支架焊在一起），与零件工序共用同一份
 * `process_master` 主数据，添加方式也**与材质、零件工序完全一致**：选择器选中 → 加入有序列表
 * ⇒ 直接复用 `ProcessSection`（F-11），只追加「参与配件」「参数」两个只读列。
 *
 * 📌 **不是必填** —— 单配件产品可以没有，为空时直接进下一步。
 * 📌 「参与配件」「参数」两列**沿用 task-0712 的既有语义**（全部配件 / 空参数），本次不改契约
 *    （`api.md §5`：`CompositeProcessRequest` 的三个字段语义未变，仅前端交互改为选择器 + 有序列表）。
 *    ⇒ 两列是**只读展示**，没有编辑器。这是契约现状，不是漏做。
 *
 * ⚠️ 候选源与零件工序**不同**：走 `GET /composite-processes`
 *    （数据源 `process_master WHERE process_category='ASSEMBLY'`），不是 `sel-param-types/PROCESS`。
 */
import React, { useEffect, useMemo, useState } from 'react';
import { Tag } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { compositeProcessService } from '../../../services/compositeProcessService';
import type { SelParamCandidate } from '../../../services/selParamCandidateService';
import type { CompositeProcessItem, ConfigurePart, SelectedProcess } from '../../../types/configure';
import { partDisplayName } from './configurePartsRequest';
import ProcessSection from './ProcessSection';
import { NoteBlock } from './configureUi';

interface Props {
  parts: ConfigurePart[];
  value: CompositeProcessItem[];
  onChange: (next: CompositeProcessItem[]) => void;
}

const CompositeProcessStep: React.FC<Props> = ({ parts, value, onChange }) => {
  const [candidates, setCandidates] = useState<SelParamCandidate[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    compositeProcessService.list()
      .then((defs) => { if (!cancelled) setCandidates(defs.map((d) => ({ key: d.code, label: d.name }))); })
      .catch((e: any) => { if (!cancelled) { setCandidates([]); setLoadError(e?.message || '加载组合工序候选失败'); } })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  /** `ProcessSection` 说的是 `SelectedProcess`，这里做一次同构映射（两者字段一一对应）。 */
  const asProcesses: SelectedProcess[] = useMemo(
    () => value.map((c) => ({ uid: c.uid, processNo: c.defCode, name: c.name })),
    [value],
  );
  const fromProcesses = (next: SelectedProcess[]) =>
    onChange(next.map((p) => ({ uid: p.uid, defCode: p.processNo, name: p.name })));

  const partNames = useMemo(() => parts.map(partDisplayName), [parts]);

  const extraColumns: ColumnsType<SelectedProcess> = [
    {
      title: '参与配件',
      key: 'participants',
      width: 220,
      render: () => (
        partNames.length === 0
          ? <span style={{ color: '#c0c4cc' }}>—</span>
          : <>{partNames.map((n, i) => <Tag key={`${n}-${i}`} style={{ marginBottom: 2 }}>{n}</Tag>)}</>
      ),
    },
    {
      title: '参数',
      key: 'params',
      width: 120,
      // 契约现状：`params` 恒为空对象，没有参数编辑器（api.md §5「本次不改的契约」）
      render: () => <span style={{ color: '#c0c4cc' }}>—</span>,
    },
  ];

  const disabledReason = parts.length < 2 ? '配件数 ≥ 2 时才需要组合工序' : null;

  return (
    <div>
      <ProcessSection
        value={asProcesses}
        onChange={fromProcesses}
        candidates={candidates}
        loading={loading}
        loadError={loadError}
        disabledReason={disabledReason}
        extraColumns={extraColumns}
        labels={{
          title: '组合工序',
          hint: <>组合工序作用于<b>多个配件之间</b>，添加方式与材质、零件工序完全一致：选择器选中 → 加入有序列表。</>,
          addButton: '+ 添加组合工序',
          pickerTitle: '选择组合工序',
          searchPlaceholder: '输入工序编号或工序名过滤，如 Z101 / 铆接',
          emptyTitle: parts.length < 2 ? '单配件产品不需要组合工序' : '还没有组合工序',
          emptyHint: <b>组合工序不是必填 —— 为空时直接进下一步。</b>,
          unit: '道',
        }}
      />
      <NoteBlock>
        组合工序与零件工序共用同一份 <code>process_master</code> 主数据。
        「参与配件」当前恒为<b>全部配件</b>、「参数」当前恒为空 —— 这是 <code>CompositeProcessRequest</code>
        的既有契约语义，本次重构不改（<code>api.md §5</code>）。
      </NoteBlock>
    </div>
  );
};

export default CompositeProcessStep;
