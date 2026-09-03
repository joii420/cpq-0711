/**
 * OutsourcedPartPanel — 外购件（从料号库选 → 选工序）。
 * task-260902 · F-7，服务 AC-5 / AC-16。1:1 对齐 `原型图/5-外购件与工序.html` 状态 A / B / C。
 *
 * 🆕 全新能力：外购件**不含材质构成**，只选料号 + 工序。
 *    实测 `material_bom_item.characteristic='OUTSOURCED'` **零行** —— 这是从未落地过的能力，
 *    不是「已有但没接」。
 *
 * 🚨🚨 **空态必须是空态，绝不能显示「加载中…」永久占位**（`docs/反模式.md` AP-31 整个族的典型病：
 *      把「空」误判成「还没加载完」）。这不是理论边界 —— 实测 dev 库
 *      `material_master.material_type='外购件'` **只命中 1 条**，**返回 0 条是正常业务状态**，
 *      是上线第一天大概率看到的画面。所以本组件把三种状态**显式分开**：
 *        `loadError`（出错）/ `loading`（真的在请求）/ `items.length === 0`（空）
 *      三者各有各的渲染分支，🚫 不许用「没数据 ⇒ 还在加载」这种隐式判断把它们混成一个。
 *
 * ⚠️ 唯一那条外购件 `TEST-Q13-CODE / 组成件1` 的**规格与单重都是空的** ⇒ 列宽与空值处理按它设计，
 *    🚫 不得假设这两列有值（`fixture基线.md §3.1`）。
 */
import React, { useEffect, useMemo, useState } from 'react';
import { Button, Input, Table } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { configureProductService } from '../../../services/configureProductService';
import type { SelParamCandidate } from '../../../services/selParamCandidateService';
import type { ConfigurePart, OutsourcedPartDTO, SelectedProcess } from '../../../types/configure';
import { genUUID } from '../../../utils/uuid';
import { trimTrailingZeros } from '../../../utils/precision';
import ProcessSection from './ProcessSection';
import { EmptyBlock, Ellipsis, Mono, NoteBlock, ReasonedButton } from './configureUi';

interface Props {
  initial: ConfigurePart | null;
  processCandidates: SelParamCandidate[];
  processLoading?: boolean;
  processError?: string | null;
  onConfirm: (part: ConfigurePart) => void;
  onBack: () => void;
  onCancel: () => void;
  /** 空态出口：改为添加零件（原型状态 B）。 */
  onSwitchToPart: () => void;
}

const PAGE_SIZE = 20;

const OutsourcedPartPanel: React.FC<Props> = ({
  initial, processCandidates, processLoading, processError, onConfirm, onBack, onCancel, onSwitchToPart,
}) => {
  const [keyword, setKeyword] = useState('');
  const [appliedKeyword, setAppliedKeyword] = useState('');
  const [items, setItems] = useState<OutsourcedPartDTO[]>([]);
  const [total, setTotal] = useState(0);
  /** 🚨 初值 true 只在**首次真的在请求**时成立；请求一结束就必须落到 false，否则空态永远出不来。 */
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selected, setSelected] = useState<OutsourcedPartDTO | null>(
    initial?.outsourcedPartNo
      ? { materialNo: initial.outsourcedPartNo, materialName: initial.outsourcedName, specification: initial.outsourcedSpec }
      : null,
  );
  const [processes, setProcesses] = useState<SelectedProcess[]>(initial?.processes ?? []);

  const load = (q: string) => {
    setLoading(true);
    setLoadError(null);
    setAppliedKeyword(q);
    configureProductService.listOutsourcedParts({ keyword: q, page: 1, size: PAGE_SIZE })
      .then((res) => { setItems(res.items); setTotal(res.total); })
      .catch((e: any) => { setItems([]); setTotal(0); setLoadError(e?.message || '加载外购件列表失败'); })
      // 🚨 finally 保证**任何**结局都关掉 loading —— 少了它，一次异常就变成永久「加载中…」
      .finally(() => setLoading(false));
  };

  // 空依赖是有意的：只在挂载时拉一次，之后由「搜索」按钮驱动。
  useEffect(() => { load(''); }, []);

  const columns: ColumnsType<OutsourcedPartDTO> = [
    {
      title: '',
      key: 'radio',
      width: 40,
      render: (_v, row) => (
        <input
          type="radio"
          name="outsourced-part"
          checked={selected?.materialNo === row.materialNo}
          onChange={() => setSelected(row)}
        />
      ),
    },
    { title: '料号', dataIndex: 'materialNo', key: 'materialNo', width: 200, render: (v: string) => <Mono>{v}</Mono> },
    { title: '品名', dataIndex: 'materialName', key: 'materialName', ellipsis: true, render: (v?: string | null) => <Ellipsis text={v} /> },
    // ⚠️ 规格 / 单重在唯一那条实测数据里都是空的 —— 空值一律渲染「—」，不留空白单元格
    { title: '规格', dataIndex: 'specification', key: 'specification', width: 180, render: (v?: string | null) => <Ellipsis text={v} /> },
    {
      title: '单重',
      key: 'unitWeight',
      width: 90,
      align: 'right',
      render: (_v, row) => (row.unitWeight ? <span>{trimTrailingZeros(row.unitWeight)} g</span> : <span style={{ color: '#c0c4cc' }}>—</span>),
    },
  ];

  const body = useMemo(() => {
    if (loadError) {
      return (
        <EmptyBlock
          icon="⚠"
          title="外购件列表加载失败"
          hint={loadError}
          actions={<Button size="small" onClick={() => load(appliedKeyword)}>重试</Button>}
        />
      );
    }
    if (loading) {
      // 只有**真的在请求**时才允许出现 loading 外观
      return <Table<OutsourcedPartDTO> rowKey="materialNo" size="small" loading pagination={false} dataSource={[]} columns={columns} />;
    }
    if (items.length === 0) {
      // 🚨 原型状态 B：这是 0 条时的**空态**，不是错误、更不是「加载中…」
      return appliedKeyword.trim()
        ? (
          <EmptyBlock
            icon="🔍"
            title={`没有匹配「${appliedKeyword.trim()}」的外购件`}
            hint="换个关键词，或清空搜索看全部外购件"
            actions={<Button onClick={() => { setKeyword(''); load(''); }}>清空搜索</Button>}
          />
        ) : (
          <EmptyBlock
            icon="🛒"
            title="料号库里还没有外购件"
            hint={<>外购件需要先在<b>料号维护</b>里录入，并把「料号类型」设为<b>外购件</b></>}
            actions={(
              <>
                <Button onClick={() => window.open('/materials', '_blank')}>→ 打开料号维护</Button>
                <Button onClick={onSwitchToPart}>← 改为添加零件</Button>
              </>
            )}
          />
        );
    }
    return (
      <Table<OutsourcedPartDTO>
        rowKey="materialNo"
        size="small"
        dataSource={items}
        columns={columns}
        pagination={false}
        rowClassName={(row) => (selected?.materialNo === row.materialNo ? 'cfg-row-selected' : '')}
        onRow={(row) => ({ onClick: () => setSelected(row), style: { cursor: 'pointer' } })}
      />
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loading, loadError, items, selected, appliedKeyword]);

  const confirmReason = selected ? null : '请先选择一个外购件';
  const confirm = () => {
    if (!selected) return;
    onConfirm({
      uid: initial?.uid ?? genUUID(),
      partType: 'OUTSOURCED',
      partMode: 'new',
      name: selected.materialName || selected.materialNo,
      spec: selected.specification ?? '',
      dimension: '',
      unitWeightGrams: selected.unitWeight ? trimTrailingZeros(selected.unitWeight) : '',
      materials: [],
      outsourcedPartNo: selected.materialNo,
      outsourcedName: selected.materialName ?? '',
      outsourcedSpec: selected.specification ?? '',
      processes,
    });
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div style={{ flex: 1, overflow: 'auto', padding: '16px 20px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
          <Input
            prefix={<SearchOutlined />}
            allowClear
            placeholder="搜索料号或品名"
            value={keyword}
            style={{ maxWidth: 280 }}
            onChange={(e) => setKeyword(e.target.value)}
            onPressEnter={() => load(keyword)}
          />
          <Button onClick={() => load(keyword)}>搜索</Button>
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
            hint: <>与材质、零件工序<b>同一套交互</b>：选择器选中 → 加入有序列表。外购件同样可以有工序（到货后的装配、检验等）。</>,
            addButton: '+ 添加工序',
            pickerTitle: '选择工序',
            searchPlaceholder: '输入工序编号或工序名过滤，如 Z100 / 焊接',
            emptyTitle: '还没有工序',
            emptyHint: '工序不是必填 —— 没有工序也可以直接确定',
            unit: '道',
          }}
        />

        <NoteBlock>
          列表只列 <code>material_master.material_type = &apos;外购件&apos;</code> 的料号（闸门 A0 裁决）。
          该列语义是<b>料号类型</b>，现网取值分布 = 零件 / NULL / 外购件（共享库会漂移）。
        </NoteBlock>
      </div>

      <div style={{ padding: '12px 20px', borderTop: '1px solid #f0f0f0', display: 'flex', gap: 8, justifyContent: 'flex-end', flexShrink: 0 }}>
        <Button onClick={onCancel}>取消</Button>
        <Button onClick={onBack}>上一步</Button>
        <ReasonedButton type="primary" reason={confirmReason} onClick={confirm}>确定</ReasonedButton>
      </div>
    </div>
  );
};

export default OutsourcedPartPanel;
