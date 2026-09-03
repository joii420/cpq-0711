/**
 * ExistingPartPanel — 已有零件（从产品列表选 → 选工序）。
 * task-260902 · F-8，服务 AC-11。1:1 对齐 `原型图/4-已有零件与工序.html` 状态 A / B / C。
 *
 * 这条路**不重新配材质** —— 零件的材质构成沿用它自己的，只需要选这次用哪些工序。
 * 对应现状的 `partMode=existing`，是三条路径里改动最小的一条。
 *
 * 🚧 **契约缺口（已报主线）**：原型状态 A 的末行「静触头复合镶块」是多材质零件
 *    （`AgNi10 70%` + `AgZnO12/Cu 30%`），要求「材质构成」列能显示 **N 个标签**；
 *    但 `api.md §3` 把 `GET /quotations/configure/search-parts` 列为「复用、不改」，
 *    其 DTO 只有单值 `recipeCode / recipeSymbol / recipeName`。
 *    ⇒ 本组件按 `materials[]` **可选**读取：后端补上就渲染 N 个标签，没有就用单值渲染 1 个。
 *      🚫 **没有写成「只显示第一个材质」的死逻辑** —— 那正是后端补齐后会静默漏行的写法。
 */
import React, { useEffect, useMemo, useState } from 'react';
import { Button, Input, Table, Tag } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { configureProductService } from '../../../services/configureProductService';
import type { SelParamCandidate } from '../../../services/selParamCandidateService';
import type { ConfigurePart, SearchPartResult, SelectedProcess } from '../../../types/configure';
import { genUUID } from '../../../utils/uuid';
import { trimTrailingZeros } from '../../../utils/precision';
import ProcessSection from './ProcessSection';
import { EmptyBlock, Ellipsis, Mono, ReasonedButton } from './configureUi';

interface Props {
  initial: ConfigurePart | null;
  processCandidates: SelParamCandidate[];
  processLoading?: boolean;
  processError?: string | null;
  onConfirm: (part: ConfigurePart) => void;
  onBack: () => void;
  onCancel: () => void;
  /** 空态出口：改为新建零件（原型状态 B）。 */
  onSwitchToNew: () => void;
}

/** 材质构成列 —— N 个标签，不是只显示第一个。 */
function renderMaterials(row: SearchPartResult): React.ReactNode {
  const list = row.materials ?? [];
  if (list.length > 0) {
    return (
      <span>
        {list.map((m, i) => (
          <Tag key={`${m.recipeCode ?? i}`} style={{ marginBottom: 2 }}>
            {m.recipeSymbol || m.recipeName || m.recipeCode || '—'}
            {m.ratio ? ` ${trimTrailingZeros(m.ratio)}%` : ''}
          </Tag>
        ))}
      </span>
    );
  }
  const single = row.recipeSymbol || row.recipeName || row.recipeCode;
  return single ? <Tag>{single}</Tag> : <span style={{ color: '#c0c4cc' }}>—</span>;
}

const ExistingPartPanel: React.FC<Props> = ({
  initial, processCandidates, processLoading, processError, onConfirm, onBack, onCancel, onSwitchToNew,
}) => {
  const [keyword, setKeyword] = useState('');
  /** 已提交的搜索词 —— 空态文案要用它，不能用输入框里的实时值（用户改了词但没搜时会对不上）。 */
  const [appliedKeyword, setAppliedKeyword] = useState('');
  const [rows, setRows] = useState<SearchPartResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selected, setSelected] = useState<SearchPartResult | null>(
    initial?.existingHfPartNo
      ? { hfPartNo: initial.existingHfPartNo, partName: initial.existingPartName, specification: initial.existingSpec }
      : null,
  );
  const [processes, setProcesses] = useState<SelectedProcess[]>(initial?.processes ?? []);

  const search = (q: string) => {
    setLoading(true);
    setLoadError(null);
    setAppliedKeyword(q);
    configureProductService.searchParts(q)
      .then((res) => setRows(res ?? []))
      .catch((e: any) => { setRows([]); setLoadError(e?.message || '搜索零件失败'); })
      .finally(() => setLoading(false));
  };

  // 打开即列一批，不用先输入。空依赖是有意的：只在挂载时拉一次，之后由「搜索」按钮驱动。
  useEffect(() => { search(''); }, []);

  const columns: ColumnsType<SearchPartResult> = [
    {
      title: '',
      key: 'radio',
      width: 40,
      render: (_v, row) => (
        <input
          type="radio"
          name="existing-part"
          checked={selected?.hfPartNo === row.hfPartNo}
          onChange={() => setSelected(row)}
        />
      ),
    },
    { title: '销售料号', dataIndex: 'hfPartNo', key: 'hfPartNo', width: 190, render: (v: string) => <Mono>{v}</Mono> },
    { title: '品名', dataIndex: 'partName', key: 'partName', ellipsis: true, render: (v?: string) => <Ellipsis text={v} /> },
    { title: '规格', dataIndex: 'specification', key: 'specification', width: 140, render: (v?: string) => <Ellipsis text={v} /> },
    {
      title: '单重',
      key: 'unitWeight',
      width: 90,
      align: 'right',
      render: (_v, row) => (row.unitWeight ? <span>{trimTrailingZeros(row.unitWeight)} g</span> : <span style={{ color: '#c0c4cc' }}>—</span>),
    },
    { title: '材质构成', key: 'materials', width: 220, render: (_v, row) => renderMaterials(row) },
  ];

  const confirmReason = selected ? null : '请先选择一个零件';   // 原型状态 C
  const confirm = () => {
    if (!selected) return;
    onConfirm({
      uid: initial?.uid ?? genUUID(),
      partType: 'PART',
      partMode: 'existing',
      name: selected.partName || selected.hfPartNo,
      spec: selected.specification ?? '',
      dimension: selected.sizeInfo ?? '',
      unitWeightGrams: selected.unitWeight ? trimTrailingZeros(selected.unitWeight) : '',
      materials: [],
      existingHfPartNo: selected.hfPartNo,
      existingPartName: selected.partName ?? '',
      existingSpec: selected.specification ?? '',
      existingMaterialSummary: (selected.materials ?? []).length > 0
        ? (selected.materials ?? []).map((m) => `${m.recipeSymbol || m.recipeName || m.recipeCode}${m.ratio ? ` ${trimTrailingZeros(m.ratio)}%` : ''}`).join(' + ')
        : (selected.recipeSymbol || selected.recipeName || ''),
      processes,
    });
  };

  const total = rows.length;
  const body = useMemo(() => {
    if (loadError) return <EmptyBlock icon="⚠" title="零件列表加载失败" hint={loadError} actions={<Button size="small" onClick={() => search(appliedKeyword)}>重试</Button>} />;
    if (loading) return <Table<SearchPartResult> rowKey="hfPartNo" size="small" loading pagination={false} dataSource={[]} columns={columns} />;
    if (total === 0) {
      // 🚨 空是空 —— 不是「加载中…」（AP-31）。区分「没搜到」与「库里就没有」两种文案。
      return appliedKeyword.trim()
        ? (
          <EmptyBlock
            icon="🔍"
            title={`没有找到匹配「${appliedKeyword.trim()}」的零件`}
            hint="换个关键词，或改用「新建零件」"
            actions={<Button onClick={onSwitchToNew}>← 改为新建零件</Button>}
          />
        ) : (
          <EmptyBlock
            icon="📚"
            title="产品库里还没有可引用的零件"
            hint="先用「新建零件」配一个，之后就能在这里引用它"
            actions={<Button onClick={onSwitchToNew}>← 改为新建零件</Button>}
          />
        );
    }
    return (
      <Table<SearchPartResult>
        rowKey="hfPartNo"
        size="small"
        dataSource={rows}
        columns={columns}
        pagination={total > 10 ? { pageSize: 10, size: 'small', showSizeChanger: false } : false}
        rowClassName={(row) => (selected?.hfPartNo === row.hfPartNo ? 'cfg-row-selected' : '')}
        onRow={(row) => ({ onClick: () => setSelected(row), style: { cursor: 'pointer' } })}
      />
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loading, loadError, rows, selected, appliedKeyword, total]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div style={{ flex: 1, overflow: 'auto', padding: '16px 20px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
          <Input
            prefix={<SearchOutlined />}
            allowClear
            placeholder="搜索销售料号或品名"
            value={keyword}
            style={{ maxWidth: 280 }}
            onChange={(e) => setKeyword(e.target.value)}
            onPressEnter={() => search(keyword)}
          />
          <Button onClick={() => search(keyword)}>搜索</Button>
          <div style={{ flex: 1 }} />
          <span style={{ fontSize: 12, color: '#909399' }}>共 {total} 条</span>
        </div>

        {body}

        <ProcessSection
          value={processes}
          onChange={setProcesses}
          candidates={processCandidates}
          loading={processLoading}
          loadError={processError}
          labels={{
            title: '这次用哪些工序',
            hint: <>与材质、零件工序<b>同一套交互</b>：选择器选中 → 加入有序列表。</>,
            addButton: '+ 添加工序',
            pickerTitle: '选择工序',
            searchPlaceholder: '输入工序编号或工序名过滤，如 Z100 / 焊接',
            emptyTitle: '还没有工序',
            emptyHint: '工序不是必填 —— 没有工序也可以直接确定',
            unit: '道',
          }}
        />
      </div>

      <div style={{ padding: '12px 20px', borderTop: '1px solid #f0f0f0', display: 'flex', gap: 8, justifyContent: 'flex-end', flexShrink: 0 }}>
        <Button onClick={onCancel}>取消</Button>
        <Button onClick={onBack}>上一步</Button>
        <ReasonedButton type="primary" reason={confirmReason} onClick={confirm}>确定</ReasonedButton>
      </div>
    </div>
  );
};

export default ExistingPartPanel;
