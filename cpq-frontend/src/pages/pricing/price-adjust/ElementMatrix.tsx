import React, { useCallback, useEffect, useState } from 'react';
import { Table, Input, Checkbox, Button, Space, Tag, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { priceAdjustService } from '../../../services/priceAdjustService';
import type { ElementRowDTO, VersionColumnDTO } from '../../../types/price-adjust';

const PAGE_SIZE = 20;

function formatPrice(v: number | null | undefined): string {
  if (v == null) return '—';
  return v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatRate(v: number | null | undefined): { text: string; color?: string } {
  if (v == null) return { text: '—' };
  const pct = (v * 100).toFixed(1);
  // 🔒 涨红跌绿（fronttask §1.3），不是股市反方向的西式配色
  if (v > 0) return { text: `+${pct}%`, color: '#cf1322' };
  if (v < 0) return { text: `${pct}%`, color: '#389e0d' };
  return { text: `${pct}%` };
}

export interface ElementMatrixProps {
  customerNo: string;
  /** 当前选中元素（跨页保留），受控 —— key=elementCode。 */
  selected: Map<string, ElementRowDTO>;
  onChange: (next: Map<string, ElementRowDTO>) => void;
}

/**
 * 屏 1 · 参与调价元素矩阵（fronttask §1.3 / api.md §1.5-1.6）。
 * 右侧 10 列 pivot（最近 10 版单价 + 涨跌幅），一次请求拿回，不逐元素查询（性能硬约束 §11.2.4）。
 * 🔒 两种空值必须渲染不同：NOT_IN_LIST→「—」，NO_PRICE→「无价」标签。
 * 🔒 已停用元素（elementEnabled=false）必须可见并标「已停用」，禁止前端过滤 —— 本组件从不按
 * elementEnabled 做 .filter()，includeDisabled 查询参数交给后端控制是否把它们纳入结果集。
 */
const ElementMatrix: React.FC<ElementMatrixProps> = ({ customerNo, selected, onChange }) => {
  const [loading, setLoading] = useState(false);
  const [rows, setRows] = useState<ElementRowDTO[]>([]);
  const [versionColumns, setVersionColumns] = useState<VersionColumnDTO[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);

  const [keyword, setKeyword] = useState('');
  const [includeDisabled, setIncludeDisabled] = useState(true);

  const fetchPage = useCallback(async (p: number) => {
    setLoading(true);
    try {
      const res = await priceAdjustService.getElements(customerNo, {
        page: p,
        size: PAGE_SIZE,
        keyword: keyword.trim() || undefined,
        includeDisabled,
      });
      setRows(res.content || []);
      setVersionColumns(res.versionColumns || []);
      setTotal(res.totalElements || 0);
      setPage(p);
    } catch (e: any) {
      message.error(e?.message || '加载元素矩阵失败');
      setRows([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [customerNo, keyword, includeDisabled]);

  useEffect(() => { fetchPage(1); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [customerNo, includeDisabled]);

  const handleQuery = () => fetchPage(1);
  const handleReset = () => { setKeyword(''); setIncludeDisabled(true); };

  /**
   * keys 的语义**取决于 rowSelection.preserveSelectedRowKeys**（见下方 Table 处注释），
   * 与 MaterialRangeMatrix.handleSelectionChange 完全同构：
   *  · 开（现状）：keys = 跨页全集 → 下面的 delete 循环只删真正被取消勾选的，语义正确；
   *  · 关：keys 只剩本页 → 同一段循环会把他页已选全部抹掉（#2 同款放大器）。
   * 所以这段**不是**冗余，也不能改成"只增不减"——取消勾选全靠它（且元素侧取消勾选在父层
   * 还要走二次确认，更不能悄悄失效）。开关与这段必须成对存在。
   */
  const handleSelectionChange = (keys: React.Key[]) => {
    const keySet = new Set(keys.map(String));
    const next = new Map(selected);
    for (const k of Array.from(next.keys())) {
      if (!keySet.has(k)) next.delete(k);
    }
    for (const r of rows) {
      if (keySet.has(r.elementCode) && !next.has(r.elementCode)) {
        next.set(r.elementCode, r);
      }
    }
    onChange(next);
  };

  const versionCols: ColumnsType<ElementRowDTO> = versionColumns.map((vc, idx) => ({
    title: (
      <div style={{ textAlign: 'right' }}>
        <div style={{ fontFamily: 'monospace' }}>{vc.versionNo}</div>
        <Tag color={vc.status === 'PENDING' ? 'orange' : 'default'} style={{ fontSize: 10, marginTop: 2 }}>
          {vc.status === 'PENDING' ? '待处理' : '已被取代'}
        </Tag>
      </div>
    ),
    key: vc.versionId,
    width: 108,
    align: 'right' as const,
    render: (_: unknown, record: ElementRowDTO) => {
      const cell = record.prices?.[idx];
      if (!cell || cell.priceState === 'NOT_IN_LIST') {
        return <span style={{ color: 'rgba(0,0,0,.25)' }}>—</span>;
      }
      if (cell.priceState === 'NO_PRICE') {
        return <Tag color="orange" style={{ fontSize: 11 }}>无价</Tag>;
      }
      const rate = formatRate(cell.changeRate);
      return (
        <div>
          <div>{formatPrice(cell.unitPrice)}</div>
          <div style={{ fontSize: 11, color: rate.color || 'rgba(0,0,0,.35)' }}>{rate.text}</div>
        </div>
      );
    },
  }));

  const columns: ColumnsType<ElementRowDTO> = [
    {
      title: '元素符号', dataIndex: 'elementCode', width: 130, fixed: 'left' as const,
      render: (v: string, r: ElementRowDTO) => (
        <Space size={4}>
          <b>{v}</b>
          {r.elementEnabled === false && <Tag color="default" style={{ fontSize: 10 }}>已停用</Tag>}
        </Space>
      ),
    },
    { title: '元素名称', dataIndex: 'elementName', width: 110, fixed: 'left' as const },
    ...versionCols,
  ];

  return (
    <div style={{ border: '1px solid #f0f0f0', borderRadius: 6, marginTop: 8 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 14px', borderBottom: '1px solid #f0f0f0' }}>
        <span style={{ fontWeight: 600, fontSize: 13.5 }}>参与调价元素矩阵</span>
        <span style={{ fontSize: 12.5, color: 'rgba(0,0,0,.45)' }}>已选 <b>{selected.size}</b> 项 · 共 {total} 条 · 右侧为该客户最近 10 个版本的价格</span>
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, padding: '12px 14px 10px', alignItems: 'center' }}>
        <Input placeholder="元素符号 / 名称" style={{ width: 180 }} value={keyword} onChange={(e) => setKeyword(e.target.value)} onPressEnter={handleQuery} allowClear />
        <Checkbox checked={includeDisabled} onChange={(e) => setIncludeDisabled(e.target.checked)}>
          含已停用 <Tag color="blue" style={{ fontSize: 10, marginLeft: 2 }}>新</Tag>
        </Checkbox>
        <Space>
          <Button size="small" onClick={handleQuery}>查询</Button>
          <Button size="small" onClick={handleReset}>重置</Button>
        </Space>
      </div>
      <div style={{ padding: '0 14px 8px', fontSize: 12, color: 'rgba(0,0,0,.45)' }}>
        <b>「—」</b>= 该元素当时不在调价清单里（未参与该版）；<Tag color="orange" style={{ fontSize: 11 }}>无价</Tag> = 在清单里但取价策略算不出值。
        已勾选但后来被停用的元素会保留在清单中并标「已停用」，仍照常参与调价，取消勾选需二次确认。
      </div>
      <Table<ElementRowDTO>
        size="small"
        rowKey="elementCode"
        loading={loading}
        dataSource={rows}
        columns={columns}
        scroll={{ x: 'max-content' }}
        rowSelection={{
          selectedRowKeys: Array.from(selected.keys()),
          onChange: handleSelectionChange,
          // 🔒 跨页保留选中的开关本体（与 MaterialRangeMatrix 同构修复，#2 同款缺陷）。
          // antd 6.3.5 useSelection.js#setSelectedKeys：未开该开关时用 getRecordByKey(key) 把不在
          // 当前 dataSource（本页 20 行）的 key 全部过滤掉 → 内部 state 与 onChange 回传都只剩本页，
          // 翻页后再勾一次，他页已选静默丢失 → 保存时按 selected.keys() 提交，漏掉的元素不参与调价。
          // 现网 element 表 37 行 > 每页 20，本组件**一直在分页**，该缺陷是可触达的。
          //
          // 🔑 元素侧特有的一维：includeDisabled 关掉时，已停用元素会从 dataSource 消失。
          //    没有本开关时，此后任何一次勾选都会把「已勾选但当前不可见的停用元素」一并抹掉，
          //    直接违反验收 #51「停用元素仍留在清单里、照常参与调价」。开关同时兜住这一条。
          //
          // ⚠️ 与上方 handleSelectionChange 的 delete 循环是**一对**，任何一方单独改动都会重现缺陷。
          preserveSelectedRowKeys: true,
        }}
        pagination={{
          current: page,
          pageSize: PAGE_SIZE,
          total,
          size: 'small',
          onChange: (p) => fetchPage(p),
          showTotal: (t) => `共 ${t} 条`,
        }}
      />
    </div>
  );
};

export default ElementMatrix;
