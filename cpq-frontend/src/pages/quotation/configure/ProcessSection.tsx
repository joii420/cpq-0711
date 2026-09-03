/**
 * ProcessSection — 工序选择器 + 有序列表（task-260902 · F-11，服务 AC-19 / AC-20）。
 *
 * 1:1 对齐 `原型图/3-新建零件与多材质.html` 状态 H（选择器）与状态 A 的工序区（有序列表），
 * 以及 `4-已有零件与工序.html` / `5-外购件与工序.html` 的同款工序区。
 *
 * **同一套组件复用四处**：新建零件工序 / 已有零件工序 / 外购件工序 / 步骤 3 组合工序。
 *
 * 🔄 **与材质选择器的唯一差别：工序允许重复加入。**
 *    面板里显示「已加 N 次」而**不是**灰显禁用 —— 「粗车 → 热处理 → 精车」是常态。
 *    所以列表项用独立 `uid` 而不是 `processNo` 做 key（同一个 processNo 会出现多次）。
 *
 * 📌 **顺序语义（两件事，别混）**：
 *    - 顺序**不进指纹**（后端 `PRC=` 是 `sorted().join(",")`）⇒ 换个次序仍复用同一个销售料号（AC-19）。
 *    - 顺序**影响** `unit_price.seq_no` 与报价单里的显示顺序 ⇒ ↑↓ 调序不能因此取消。
 *    ⇒ 提交时 `processNos` **按列表顺序原样发送，🚫 不排序**（排序是后端算指纹时的事）。
 * 🚨 **重复次数仍进指纹**（`["Z100","Z101","Z100"].sort()` → `Z100,Z100,Z101` ≠ `Z100,Z101`）⇒
 *    🚫 提交前**不许 `distinct()`**，那会把「焊两次」和「焊一次」静默算成同一个料号（AC-20）。
 */
import React, { useMemo, useState } from 'react';
import { Button, Input, Table, Tag } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { SelectedProcess } from '../../../types/configure';
import type { SelParamCandidate } from '../../../services/selParamCandidateService';
import { genUUID } from '../../../utils/uuid';
import { EmptyBlock, Ellipsis, Mono, NoteBlock, ReasonedButton, hintStyle, pickerPanelStyle, sectionTitleStyle } from './configureUi';

export interface ProcessSectionLabels {
  /** 区块标题，如「工序」「这次用哪些工序」「组合工序」 */
  title: string;
  /** 标题下的一行说明 */
  hint?: React.ReactNode;
  /** 加入按钮文案，如「+ 添加工序」 */
  addButton: string;
  /** 面板标题，如「选择工序」 */
  pickerTitle: string;
  searchPlaceholder: string;
  /** 列表为空时的标题与提示 */
  emptyTitle: string;
  emptyHint?: React.ReactNode;
  /** 数量单位，如「道」 */
  unit: string;
}

interface Props {
  value: SelectedProcess[];
  onChange: (next: SelectedProcess[]) => void;
  candidates: SelParamCandidate[];
  /** 候选还在拉 —— 只有这一个值为 true 时才允许显示「加载中」（AP-31：空 ≠ 未加载完）。 */
  loading?: boolean;
  /** 候选拉取失败的原因，非空时面板显示错误态而不是空态。 */
  loadError?: string | null;
  labels: ProcessSectionLabels;
  /** 追加在「移除」左侧的只读列（步骤 3 的「参与配件」「参数」）。 */
  extraColumns?: ColumnsType<SelectedProcess>;
  /** 整块只读（禁用新增/调序/移除），用于 Σ配件数 < 2 时的组合工序。 */
  disabledReason?: string | null;
}

const ProcessSection: React.FC<Props> = ({
  value, onChange, candidates, loading, loadError, labels, extraColumns, disabledReason,
}) => {
  const [pickerOpen, setPickerOpen] = useState(false);
  const [keyword, setKeyword] = useState('');

  const filtered = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    if (!kw) return candidates;
    // 工序编号与工序名都参与过滤，大小写不敏感（与材质选择器同口径）
    return candidates.filter(
      (c) => c.key.toLowerCase().includes(kw) || (c.label ?? '').toLowerCase().includes(kw),
    );
  }, [candidates, keyword]);

  /** processNo → 已加入次数（面板里显示「已加 N 次」，🚫 不禁用） */
  const addedCount = useMemo(() => {
    const map = new Map<string, number>();
    value.forEach((p) => map.set(p.processNo, (map.get(p.processNo) ?? 0) + 1));
    return map;
  }, [value]);

  const add = (c: SelParamCandidate) => {
    onChange([...value, {
      uid: genUUID(),
      processNo: c.key,
      name: c.label ?? c.key,
      category: c.category ?? null,
      processType: c.processType ?? null,
    }]);
  };
  const remove = (uid: string) => onChange(value.filter((p) => p.uid !== uid));
  const move = (index: number, delta: number) => {
    const target = index + delta;
    if (target < 0 || target >= value.length) return;
    const next = [...value];
    const [item] = next.splice(index, 1);
    next.splice(target, 0, item);
    onChange(next);
  };

  const listColumns: ColumnsType<SelectedProcess> = [
    { title: '#', key: 'seq', width: 48, align: 'right', render: (_v, _r, i) => <span style={{ color: '#909399' }}>{i + 1}</span> },
    { title: '工序编号', dataIndex: 'processNo', key: 'processNo', width: 110, render: (v: string) => <Mono>{v}</Mono> },
    { title: '工序名', dataIndex: 'name', key: 'name', ellipsis: true, render: (v: string) => <Ellipsis text={v} /> },
    { title: '分类', dataIndex: 'category', key: 'category', width: 100, render: (v?: string | null) => (v ? <span style={{ fontSize: 12, color: '#909399' }}>{v}</span> : <span style={{ color: '#c0c4cc' }}>—</span>) },
    { title: '加工方式', dataIndex: 'processType', key: 'processType', width: 100, render: (v?: string | null) => (v ? <Tag>{v}</Tag> : <span style={{ color: '#c0c4cc' }}>—</span>) },
    ...(extraColumns ?? []),
    {
      title: '',
      key: 'ops',
      width: 170,
      align: 'right',
      render: (_v, row, index) => (
        <span style={{ whiteSpace: 'nowrap' }}>
          {/* 🚫 首行的 ↑ / 末行的 ↓ 禁用但可见 + 写明原因（§1.2） */}
          <ReasonedButton
            type="link" size="small"
            reason={disabledReason ?? (index === 0 ? '已是第一道' : null)}
            onClick={() => move(index, -1)}
          >↑</ReasonedButton>
          <ReasonedButton
            type="link" size="small"
            reason={disabledReason ?? (index === value.length - 1 ? '已是最后一道' : null)}
            onClick={() => move(index, 1)}
          >↓</ReasonedButton>
          <ReasonedButton type="link" size="small" danger reason={disabledReason} onClick={() => remove(row.uid)}>
            移除
          </ReasonedButton>
        </span>
      ),
    },
  ];

  const pickerColumns: ColumnsType<SelParamCandidate> = [
    { title: '工序编号', dataIndex: 'key', key: 'key', width: 120, render: (v: string) => <Mono muted>{v}</Mono> },
    {
      title: '工序名',
      dataIndex: 'label',
      key: 'label',
      render: (v: string, row) => {
        const n = addedCount.get(row.key) ?? 0;
        return (
          <span>
            {v || row.key}
            {/* 🔄 与材质选择器的唯一差别：重复不禁用，只提示已加几次 */}
            {n > 0 ? <Tag style={{ marginLeft: 6 }}>已加 {n} 次</Tag> : null}
          </span>
        );
      },
    },
    { title: '分类', dataIndex: 'category', key: 'category', width: 110, render: (v?: string | null) => (v ? <span style={{ fontSize: 12, color: '#909399' }}>{v}</span> : <span style={{ color: '#c0c4cc' }}>—</span>) },
    { title: '加工方式', dataIndex: 'processType', key: 'processType', width: 110, render: (v?: string | null) => (v ? <Tag>{v}</Tag> : <span style={{ color: '#c0c4cc' }}>—</span>) },
    {
      title: '',
      key: 'add',
      width: 90,
      align: 'right',
      render: (_v, row) => (
        <Button size="small" type="primary" onClick={() => add(row)}>加入</Button>
      ),
    },
  ];

  return (
    <div>
      <h3 style={sectionTitleStyle}>
        {labels.title}
        {value.length > 0 ? <Tag color="blue">{value.length} {labels.unit}</Tag> : null}
      </h3>
      {labels.hint ? <p style={hintStyle}>{labels.hint}</p> : null}

      {value.length === 0 ? (
        <div style={{ border: '1px solid #f0f0f0', borderRadius: 8 }}>
          <EmptyBlock icon="🧰" title={labels.emptyTitle} hint={labels.emptyHint} />
        </div>
      ) : (
        <Table<SelectedProcess>
          rowKey="uid"
          size="small"
          pagination={false}
          dataSource={value}
          columns={listColumns}
        />
      )}

      <div style={{ marginTop: 12 }}>
        <ReasonedButton reason={disabledReason} onClick={() => setPickerOpen((v) => !v)}>
          {pickerOpen ? '收起选择器' : labels.addButton}
        </ReasonedButton>
      </div>

      {pickerOpen && !disabledReason && (
        <div className="cfg-process-picker" style={pickerPanelStyle}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12, flexWrap: 'wrap' }}>
            <b>{labels.pickerTitle}</b>
            <Input
              prefix={<SearchOutlined />}
              allowClear
              placeholder={labels.searchPlaceholder}
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              style={{ maxWidth: 300 }}
            />
            <span style={{ fontSize: 12, color: '#909399' }}>{filtered.length} / {candidates.length} 条</span>
            <div style={{ flex: 1 }} />
            <Button size="small" onClick={() => setPickerOpen(false)}>取消</Button>
          </div>

          <div style={{ background: '#fff', borderRadius: 6 }}>
            {loadError ? (
              <EmptyBlock icon="⚠" title="工序候选加载失败" hint={loadError} />
            ) : loading ? (
              <Table<SelParamCandidate> rowKey="key" size="small" loading pagination={false} dataSource={[]} columns={pickerColumns} />
            ) : candidates.length === 0 ? (
              /* 🚨 空是空 —— 绝不显示「加载中…」永久占位（AP-31） */
              <EmptyBlock
                icon="🧰"
                title="工序库里还没有工序"
                hint={<>工序需要先在<b>主数据维护 → 工序</b>里录入</>}
                actions={<Button size="small" onClick={() => window.open('/master-data-hub', '_blank')}>→ 打开主数据维护</Button>}
              />
            ) : filtered.length === 0 ? (
              <EmptyBlock
                icon="🔍"
                title={`没有匹配「${keyword.trim()}」的工序`}
                hint="试试工序编号（Z100）或工序名（焊接）"
              />
            ) : (
              <Table<SelParamCandidate>
                rowKey="key"
                size="small"
                pagination={filtered.length > 10 ? { pageSize: 10, size: 'small', showSizeChanger: false } : false}
                dataSource={filtered}
                columns={pickerColumns}
              />
            )}
          </div>

          <NoteBlock style={{ background: '#fff' }}>
            🔄 <b>工序可以重复加入</b>（「粗车 → 热处理 → 精车」是常态），所以这里显示「已加 N 次」而不是灰显禁用。
            <br />
            📌 列表顺序 = 工艺顺序，影响报价单显示顺序与 <code>unit_price.seq_no</code>；
            但**顺序不影响料号复用判定**（后端算指纹时会排序）。这是两件事。
          </NoteBlock>
        </div>
      )}
    </div>
  );
};

export default ProcessSection;
