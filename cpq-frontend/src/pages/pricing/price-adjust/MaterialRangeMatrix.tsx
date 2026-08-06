import React, { useCallback, useEffect, useState } from 'react';
import { Table, Input, Checkbox, Button, Space, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { priceAdjustService } from '../../../services/priceAdjustService';
import type { MaterialRowDTO } from '../../../types/price-adjust';

const PAGE_SIZE = 20;

const emptyDash = <span style={{ color: 'rgba(0,0,0,.25)' }}>—</span>;

export interface MaterialRangeMatrixProps {
  customerNo: string;
  /** 当前选中料号（跨页保留），受控 —— key=materialNo。父层在 Save 时读取 keys 组装 materialNos。 */
  selected: Map<string, MaterialRowDTO>;
  onChange: (next: Map<string, MaterialRowDTO>) => void;
}

/**
 * 屏 1 · 指定料号矩阵（fronttask §1.2 / api.md §1.3-1.4）。
 * 四筛选框可组合模糊匹配；勾选跨页保留（受控 Map，由父层持久化维护，seed 见父层 useEffect）；
 * 「只看已选」为纯前端复核视图（直接渲染 selected Map，不请求后端 —— 避免刚勾选还未保存时
 * 后端 selectedOnly 查询读到的是保存前的旧状态，见 errorPayload.ts 同思路的"信封不确定性"注释旁的
 * 设计取舍：这里是"数据一致性"取舍，父层已注释）。
 * 客户料号（customerPartNo）现网大量为空 → 显示「—」但照常可勾（策略记录的是销售料号）。
 */
const MaterialRangeMatrix: React.FC<MaterialRangeMatrixProps> = ({ customerNo, selected, onChange }) => {
  const [loading, setLoading] = useState(false);
  const [rows, setRows] = useState<MaterialRowDTO[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);

  const [customerPartNo, setCustomerPartNo] = useState('');
  const [customerMaterialName, setCustomerMaterialName] = useState('');
  const [materialNo, setMaterialNo] = useState('');
  const [materialName, setMaterialName] = useState('');
  const [onlySelected, setOnlySelected] = useState(false);

  const fetchPage = useCallback(async (p: number) => {
    setLoading(true);
    try {
      const res = await priceAdjustService.getMaterials(customerNo, {
        page: p,
        size: PAGE_SIZE,
        customerPartNo: customerPartNo.trim() || undefined,
        customerMaterialName: customerMaterialName.trim() || undefined,
        materialNo: materialNo.trim() || undefined,
        materialName: materialName.trim() || undefined,
      });
      setRows(res.content || []);
      setTotal(res.totalElements || 0);
      setPage(p);
    } catch (e: any) {
      message.error(e?.message || '加载料号矩阵失败');
      setRows([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [customerNo, customerPartNo, customerMaterialName, materialNo, materialName]);

  useEffect(() => {
    if (!onlySelected) fetchPage(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [customerNo, onlySelected]);

  const handleQuery = () => fetchPage(1);
  const handleReset = () => {
    setCustomerPartNo(''); setCustomerMaterialName(''); setMaterialNo(''); setMaterialName('');
    setOnlySelected(false);
  };

  // 「只看已选」= 直接渲染当前 selected Map（提交前复核已勾选的全集，不受分页/筛选影响）
  const selectedRowsArr = React.useMemo(() => Array.from(selected.values()), [selected]);
  const displayRows = onlySelected ? selectedRowsArr : rows;
  const displayTotal = onlySelected ? selectedRowsArr.length : total;

  /**
   * keys 的语义**取决于 rowSelection.preserveSelectedRowKeys**（见下方 Table 处注释）：
   *  · 开（现状）：keys = 跨页全集 → 下面的 delete 循环只删真正被取消勾选的，语义正确；
   *  · 关：keys 只剩本页 → 同一段 delete 循环会把他页已选全部抹掉（#2 的放大器）。
   * 所以这段**不是**冗余，也不能改成"只增不减"——取消勾选、以及「只看已选」视图里的移除，
   * 全靠它。删掉它 = 勾了就取消不掉；关掉开关 = 翻页即丢。两者必须成对存在。
   */
  const handleSelectionChange = (keys: React.Key[]) => {
    const keySet = new Set(keys.map(String));
    const next = new Map(selected);
    for (const k of Array.from(next.keys())) {
      if (!keySet.has(k)) next.delete(k);
    }
    for (const r of displayRows) {
      if (keySet.has(r.materialNo) && !next.has(r.materialNo)) {
        next.set(r.materialNo, r);
      }
    }
    onChange(next);
  };

  const columns: ColumnsType<MaterialRowDTO> = [
    { title: '客户料号', dataIndex: 'customerPartNo', width: 160, render: (v: string | null) => v ? <span style={{ fontFamily: 'monospace' }}>{v}</span> : emptyDash },
    { title: '客户料号名称', dataIndex: 'customerMaterialName', width: 180, render: (v: string | null) => v || emptyDash },
    { title: '对应销售料号', dataIndex: 'materialNo', width: 160, render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span> },
    { title: '对应销售料号名称', dataIndex: 'materialName', render: (v: string | null) => v || emptyDash },
  ];

  return (
    <div style={{ border: '1px solid #f0f0f0', borderRadius: 6, marginTop: 8 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 14px', borderBottom: '1px solid #f0f0f0' }}>
        <span style={{ fontWeight: 600, fontSize: 13.5 }}>指定料号矩阵</span>
        <span style={{ fontSize: 12.5, color: 'rgba(0,0,0,.45)' }}>已选 <b>{selected.size}</b> 项 · 共 {total} 条（跨页保留选中）</span>
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, padding: '12px 14px 10px', alignItems: 'center' }}>
        <Input placeholder="客户料号" style={{ width: 140 }} value={customerPartNo} onChange={(e) => setCustomerPartNo(e.target.value)} onPressEnter={handleQuery} allowClear />
        <Input placeholder="客户料号名称" style={{ width: 150 }} value={customerMaterialName} onChange={(e) => setCustomerMaterialName(e.target.value)} onPressEnter={handleQuery} allowClear />
        <Input placeholder="销售料号" style={{ width: 140 }} value={materialNo} onChange={(e) => setMaterialNo(e.target.value)} onPressEnter={handleQuery} allowClear />
        <Input placeholder="销售料号名称" style={{ width: 150 }} value={materialName} onChange={(e) => setMaterialName(e.target.value)} onPressEnter={handleQuery} allowClear />
        <Checkbox checked={onlySelected} onChange={(e) => setOnlySelected(e.target.checked)}>只看已选</Checkbox>
        <Space>
          <Button size="small" onClick={handleQuery} disabled={onlySelected}>查询</Button>
          <Button size="small" onClick={handleReset}>重置</Button>
        </Space>
      </div>
      <Table<MaterialRowDTO>
        size="small"
        rowKey="materialNo"
        loading={loading}
        dataSource={displayRows}
        columns={columns}
        rowSelection={{
          selectedRowKeys: Array.from(selected.keys()),
          onChange: handleSelectionChange,
          // 🔒 跨页保留选中的**开关本体**，缺它则本组件顶部「（跨页保留选中）」是句谎话。
          // antd 6.3.5 useSelection.js#setSelectedKeys：未开该开关时走 else 分支，用
          // getRecordByKey(key) 把不在当前 dataSource（本页 20 行）的 key **全部过滤掉** ——
          // 内部 mergedSelectedKeys 与 onChange 回传的 keys 都只剩本页，他页已勾选静默丢失，
          // 保存时按 selected.keys() 提交 → 用户以为存了 5 个料号，实际只存了翻页后勾的那几个。
          // ⚠️ 与下方 handleSelectionChange 的「不在 keys 里就 delete」是**一对**：
          //    开关保证 keys 是全集，delete 循环才只删真正被取消勾选的；关掉开关那段就会变成
          //    "翻页即清空他页"。两者任何一方单独改动都会重现本 bug（#2）。
          preserveSelectedRowKeys: true,
        }}
        pagination={onlySelected ? { pageSize: PAGE_SIZE, size: 'small' } : {
          current: page,
          pageSize: PAGE_SIZE,
          total,
          size: 'small',
          onChange: (p) => fetchPage(p),
          showTotal: (t) => `共 ${t} 条`,
        }}
      />
      <div style={{ padding: '8px 14px', fontSize: 12, color: 'rgba(0,0,0,.45)', borderTop: '1px solid #f5f5f5' }}>
        客户料号为空的行以销售料号为准（该客户尚未维护客户侧编号），显示「—」不影响勾选。
      </div>
    </div>
  );
};

export default MaterialRangeMatrix;
