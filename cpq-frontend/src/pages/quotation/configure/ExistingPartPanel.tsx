/**
 * ExistingPartPanel — 已有零件（从产品列表选 → 选工序）。
 * task-260902 · F-8，服务 AC-11。1:1 对齐 `原型图/4-已有零件与工序.html` 状态 A / B / C。
 *
 * 这条路**不重新配材质** —— 零件的材质构成沿用它自己的，只需要选这次用哪些工序。
 * 对应现状的 `partMode=existing`，是三条路径里改动最小的一条。
 *
 * ── task-260910 · F-1 / F-2（视觉基准 `原型图/03-已有零件-材质列多值.html` 方案甲）──────────
 *  F-1 **按客户过滤**（AC-7）：`search-parts` 的 `customerNo` 已是必填 ⇒ 本面板必须把它传下去；
 *      ⚠️ 拿不到客户号时**不发请求**（后端会 400），渲染 `NoCustomerEmpty` 空态。
 *      📌 这是**语义变更**：现状跨客户搜索，改后只搜当前客户名下的料号 ⇒ 空态文案必须点明
 *         「只显示当前客户名下的料号」，否则用户会以为系统坏了。
 *  F-2 **材质单值 → 多值**（AC-8）：单值字段 `recipeSymbol / recipeName / …` 已从契约删除，
 *      改读 `materials[]` 渲染 N 个 tag（**方案甲**）。
 *      🚫 **不用方案乙（`A / B` 单串）**：实测 `material_recipe.symbol` 含 `/` 的有 74 条
 *         （如 `AgZnO12/Cu`）⇒ 「一个材质」与「两个材质」渲染出同一串，肉眼无法区分。
 *      🚨 **空数组是正常业务状态**（AC-9）：外购件与组合父料号本来就没有材质行 ⇒ 渲染「—」，
 *         🚫 不是「加载中…」（AP-31 族）。
 */
import React, { useEffect, useMemo, useState } from 'react';
import { Button, Input, Table, Tag, Tooltip } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { configureProductService } from '../../../services/configureProductService';
import type { SelParamCandidate } from '../../../services/selParamCandidateService';
import type {
  ConfigurePart, SearchPartMaterial, SearchPartResult, SelectedProcess,
} from '../../../types/configure';
import { genUUID } from '../../../utils/uuid';
import { trimTrailingZeros } from '../../../utils/precision';
import ProcessSection from './ProcessSection';
import { EmptyBlock, Ellipsis, Mono, NoCustomerEmpty, ReasonedButton } from './configureUi';

interface Props {
  initial: ConfigurePart | null;
  /**
   * 客户编码（`customer.code`）。**必须透传** —— `search-parts` 已按客户维度隔离（AC-7）。
   * `undefined` 时本面板**不发请求**，渲染「请先为报价单选择客户」空态（F-1 的边界）。
   */
  customerNo: string | undefined;
  processCandidates: SelParamCandidate[];
  processLoading?: boolean;
  processError?: string | null;
  onConfirm: (part: ConfigurePart) => void;
  onBack: () => void;
  onCancel: () => void;
  /** 空态出口：改为新建零件（原型状态 B）。 */
  onSwitchToNew: () => void;
}

/** 单个材质 tag 的 hover 提示：`材质编号 · 材质名称 · 规格`（原型 03 方案甲的注解）。 */
function materialTooltip(m: SearchPartMaterial): string {
  const parts = [m.recipeCode, m.recipeName, m.recipeSpec].map((v) => (v ?? '').trim()).filter(Boolean);
  return parts.length > 0 ? parts.join(' · ') : '该材质只有符号，没有编号/名称/规格';
}

/**
 * 材质构成列 —— **方案甲：N 个 tag**（task-260910 · AC-8，原型 03）。
 *
 * 🚫 **不做单值兜底**：契约里已经没有 `recipeSymbol` 这类单值字段了（api.md §2.1 明列删除），
 *    加回兜底分支只会在后端多材质正常返回时静默只显示一个。
 * 🚨 `materials` 空/缺 ⇒ 「—」。**这是正常业务状态**（AC-9：外购件、组合父料号，
 *    以及 `input_material_no` JOIN `material_recipe` 落空的料号），🚫 不是「加载中…」。
 * 📌 tag 自动折行（表格外层有 `overflow-x:auto`），🚫 不许横向撑破表格 —— 实测 4 材质料号存在。
 */
export function renderSearchPartMaterials(row: SearchPartResult): React.ReactNode {
  const list = row.materials ?? [];
  if (list.length === 0) return <span style={{ color: '#c0c4cc' }}>—</span>;
  return (
    <span>
      {list.map((m, i) => (
        <Tooltip key={`${m.recipeCode ?? ''}#${i}`} title={materialTooltip(m)}>
          {/* 蓝色 tag 对齐原型 `_base.css` 的 `.tag2`（#f0f5ff / #adc6ff / #2f54eb ≈ AntD blue） */}
          <Tag color="blue" style={{ marginBottom: 2 }}>
            {m.recipeSymbol || m.recipeName || m.recipeCode || '—'}
            {m.ratio ? ` ${trimTrailingZeros(m.ratio)}%` : ''}
          </Tag>
        </Tooltip>
      ))}
    </span>
  );
}

/** 已有零件的材质摘要文案（配件卡片上那一串），与上面的 tag 同一份数据。 */
export function searchPartMaterialSummary(row: SearchPartResult): string {
  return (row.materials ?? [])
    .map((m) => `${m.recipeSymbol || m.recipeName || m.recipeCode || ''}${m.ratio ? ` ${trimTrailingZeros(m.ratio)}%` : ''}`)
    .filter((t) => t.trim().length > 0)
    .join(' + ');
}

const ExistingPartPanel: React.FC<Props> = ({
  initial, customerNo, processCandidates, processLoading, processError,
  onConfirm, onBack, onCancel, onSwitchToNew,
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
    // 🚨 F-1 边界：没有客户号就**不发请求** —— 后端会 400，发出去只会把「先选客户」
    //    这个明确状态渲染成一条无意义的「加载失败」。
    if (!customerNo) { setRows([]); setLoading(false); setLoadError(null); setAppliedKeyword(q); return; }
    setLoading(true);
    setLoadError(null);
    setAppliedKeyword(q);
    configureProductService.searchParts(customerNo, q)
      .then((res) => setRows(res ?? []))
      .catch((e: any) => { setRows([]); setLoadError(e?.message || '搜索零件失败'); })
      .finally(() => setLoading(false));
  };

  // 打开即列一批，不用先输入。依赖只有 customerNo：客户变了要重查，其余由「搜索」按钮驱动。
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { search(''); }, [customerNo]);

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
    { title: '材质构成', key: 'materials', width: 220, render: (_v, row) => renderSearchPartMaterials(row) },
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
      // 🚫 不再回落到已删除的单值字段（api.md §2.1）；无材质就是空串，卡片上显示
      //    「材质构成沿用该料号自身」而不带括号 —— 那是正常状态，不是缺数据。
      existingMaterialSummary: searchPartMaterialSummary(selected),
      processes,
    });
  };

  const total = rows.length;
  const body = useMemo(() => {
    // 顺序即优先级：未选客户 > 出错 > 真的在请求 > 空 > 有数据。
    // 🚫 四个状态各有各的分支，不许用「没数据 ⇒ 还在加载」这种隐式判断把它们混成一个（AP-31）。
    if (!customerNo) return <NoCustomerEmpty />;
    if (loadError) return <EmptyBlock icon="⚠" title="零件列表加载失败" hint={loadError} actions={<Button size="small" onClick={() => search(appliedKeyword)}>重试</Button>} />;
    if (loading) return <Table<SearchPartResult> rowKey="hfPartNo" size="small" loading pagination={false} dataSource={[]} columns={columns} />;
    if (total === 0) {
      // 🚨 空是空 —— 不是「加载中…」（AP-31）。区分「没搜到」与「库里就没有」两种文案。
      return appliedKeyword.trim()
        ? (
          <EmptyBlock
            icon="🔍"
            title={`没有找到匹配「${appliedKeyword.trim()}」的零件`}
            /* 🔑 AC-7 的语义变更：现状是跨客户搜索，改后只搜当前客户 —— 不解释用户会以为系统坏了 */
            hint="该料号可能属于其它客户 —— 本列表只显示当前客户名下的料号。换个关键词，或改用「新建零件」"
            actions={<Button onClick={onSwitchToNew}>← 改为新建零件</Button>}
          />
        ) : (
          <EmptyBlock
            icon="📚"
            title="该客户名下还没有可引用的零件"
            hint="本列表只显示当前客户名下的料号。先用「新建零件」配一个，之后就能在这里引用它"
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
  }, [loading, loadError, rows, selected, appliedKeyword, total, customerNo]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div style={{ flex: 1, overflow: 'auto', padding: '16px 20px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
          <Input
            prefix={<SearchOutlined />}
            allowClear
            placeholder="搜索销售料号或品名"
            value={keyword}
            /* 未选客户时检索无从谈起 —— 禁用但可见（§1.2），原因写在下面的空态里 */
            disabled={!customerNo}
            style={{ maxWidth: 280 }}
            onChange={(e) => setKeyword(e.target.value)}
            onPressEnter={() => search(keyword)}
          />
          <ReasonedButton
            reason={customerNo ? null : '请先为报价单选择客户 —— 料号按客户隔离'}
            onClick={() => search(keyword)}
          >
            搜索
          </ReasonedButton>
          <div style={{ flex: 1 }} />
          {/* 原型 03 方案甲：计数前点明「哪个客户名下」—— AC-7 按客户过滤的可观测出口 */}
          <span style={{ fontSize: 12, color: '#909399', whiteSpace: 'nowrap' }}>
            {customerNo ? <>客户 <Mono muted>{customerNo}</Mono> 名下 · </> : null}共 {total} 条
          </span>
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
