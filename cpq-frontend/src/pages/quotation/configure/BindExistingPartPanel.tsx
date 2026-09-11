/**
 * BindExistingPartPanel — 「直接绑定已有销售料号」的料号搜索面板
 * （task-260910 · F-3，服务 **AC-18 / AC-20**）。
 *
 * 1:1 对齐 `原型图/02-绑定料号搜索面板.html` 的五个状态：
 *   默认（有结果）/ 空数据（搜不到）/ 未选客户（不发请求）/ 最长文案与极值 / 错误态。
 *   📌 「错误态」画的是**宿主抽屉确认页**上的两条 Alert（AC-19），不在本面板里 ——
 *      见 `ConfirmStep.errorGuide` 的 `CUSTOMER_PRODUCT_NO_TAKEN` /
 *      `BIND_MATERIAL_NOT_FOUND` / `BIND_AND_PARTS_EXCLUSIVE` 三个分支（F-4）。
 *
 * ── 它和 `ExistingPartPanel` 的关系 ────────────────────────────────────────────
 *  **同一个端点**（`GET /quotations/configure/search-parts`，api.md §2.1），交互形态也一样，
 *  但语义完全不同，所以是两个组件而不是加一个 flag：
 *   | | 已有零件（ExistingPartPanel） | 直接绑定（本组件） |
 *   |---|---|---|
 *   | 选中之后 | 还要选工序，产出一个 **配件** | **选中即完成**，产出一个**绑定** |
 *   | 采集材质/工序 | 工序要选 | 🚫 都不采集 |
 *   | 提交时进入 | `parts[]` | `bindExistingMaterialNo`（与 `parts` **互斥**） |
 *  ⇒ 材质列**只读展示**，复用 `ExistingPartPanel` 导出的 `renderSearchPartMaterials`（方案甲多 tag）。
 *
 * 🚨 三条纪律（照抄两个兄弟面板踩过的坑）：
 *   1. **未选客户不发请求**（后端 `customerNo` 必填，缺参 400）⇒ 渲染 `NoCustomerEmpty`。
 *   2. **空是空，不是「加载中…」**（AP-31 族）：`noCustomer` / `loadError` / `loading` /
 *      `rows.length === 0` 四个状态各有各的渲染分支，🚫 不许隐式合并。
 *   3. 空态文案必须点明「只显示**当前客户**名下的料号」—— AC-7 是**语义变更**
 *      （现状跨客户搜索），不解释用户会以为系统坏了。
 */
import React, { useEffect, useMemo, useState } from 'react';
import { Button, Input, Table } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { configureProductService } from '../../../services/configureProductService';
import type { SearchPartResult } from '../../../types/configure';
import { renderSearchPartMaterials } from './ExistingPartPanel';
import { EmptyBlock, Mono, NoCustomerEmpty, ReasonedButton } from './configureUi';

/** 绑定选中的销售料号（宿主只需要这三个字段：料号进请求，品名/规格进步骤 2 的提示条）。 */
export interface BoundMaterial {
  materialNo: string;
  partName?: string;
  specification?: string;
}

interface Props {
  /** 已选中的绑定料号（「更换料号」时回填，让用户看得见自己上次选的是哪个）。 */
  initial: BoundMaterial | null;
  /** 客户编码（`customer.code`）。`undefined` 时不发请求，渲染未选客户空态。 */
  customerNo: string | undefined;
  onConfirm: (bound: BoundMaterial) => void;
  onBack: () => void;
}

const BindExistingPartPanel: React.FC<Props> = ({ initial, customerNo, onConfirm, onBack }) => {
  const [keyword, setKeyword] = useState('');
  /** 已提交的搜索词 —— 空态文案要用它，不能用输入框里的实时值（用户改了词但没搜时会对不上）。 */
  const [appliedKeyword, setAppliedKeyword] = useState('');
  const [rows, setRows] = useState<SearchPartResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selected, setSelected] = useState<SearchPartResult | null>(
    initial
      ? { hfPartNo: initial.materialNo, partName: initial.partName, specification: initial.specification }
      : null,
  );

  const search = (q: string) => {
    // 🚨 没有客户号就**不发请求** —— 后端会 400，发出去只会把「先选客户」渲染成「加载失败」。
    if (!customerNo) { setRows([]); setLoading(false); setLoadError(null); setAppliedKeyword(q); return; }
    setLoading(true);
    setLoadError(null);
    setAppliedKeyword(q);
    configureProductService.searchParts(customerNo, q)
      .then((res) => setRows(res ?? []))
      .catch((e: any) => { setRows([]); setLoadError(e?.message || '搜索销售料号失败'); })
      // 🚨 finally 保证**任何**结局都关掉 loading —— 少了它，一次异常就变成永久「加载中…」
      .finally(() => setLoading(false));
  };

  // 打开即列一批，不用先输入。依赖只有 customerNo：客户变了要重查。
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { search(''); }, [customerNo]);

  /*
   * 列顺序与列宽 1:1 对齐原型 02：销售料号 / 品名 / 规格 / 尺寸 / 材质。
   * 🚫 **没有 radio 列**（原型 02 靠整行高亮表达选中），也没有「单重」列 —— 绑定路径不看单重。
   * 🔑 极值处理（原型 02「最长文案 / 极值」态）：超长料号**允许换行**（等宽体 nowrap 会撑破表），
   *    超长品名允许两行，材质 4 个 tag 自动折行。
   */
  const columns: ColumnsType<SearchPartResult> = [
    {
      title: '销售料号',
      dataIndex: 'hfPartNo',
      key: 'hfPartNo',
      width: 190,
      render: (v: string) => <Mono><span style={{ wordBreak: 'break-all' }}>{v}</span></Mono>,
    },
    {
      title: '品名',
      dataIndex: 'partName',
      key: 'partName',
      render: (v?: string) => (
        (v ?? '').trim()
          ? <span style={{ display: 'block', wordBreak: 'break-word' }}>{v}</span>
          : <span style={{ color: '#c0c4cc' }}>—</span>
      ),
    },
    {
      title: '规格',
      dataIndex: 'specification',
      key: 'specification',
      width: 110,
      render: (v?: string) => ((v ?? '').trim() ? <span>{v}</span> : <span style={{ color: '#c0c4cc' }}>—</span>),
    },
    {
      title: '尺寸',
      dataIndex: 'sizeInfo',
      key: 'sizeInfo',
      width: 120,
      render: (v?: string) => ((v ?? '').trim() ? <span>{v}</span> : <span style={{ color: '#c0c4cc' }}>—</span>),
    },
    // 只读展示，不可编辑（原型 02 注解）；空数组 → 「—」，这是正常状态（AC-9）
    { title: '材质', key: 'materials', width: 200, render: (_v, row) => renderSearchPartMaterials(row) },
  ];

  const total = rows.length;
  const body = useMemo(() => {
    // 顺序即优先级：未选客户 > 出错 > 真的在请求 > 空 > 有数据。
    if (!customerNo) return <NoCustomerEmpty />;
    if (loadError) {
      return (
        <EmptyBlock
          icon="⚠"
          title="销售料号列表加载失败"
          hint={loadError}
          actions={<Button size="small" onClick={() => search(appliedKeyword)}>重试</Button>}
        />
      );
    }
    if (loading) {
      // 只有**真的在请求**时才允许出现 loading 外观
      return <Table<SearchPartResult> rowKey="hfPartNo" size="small" loading pagination={false} dataSource={[]} columns={columns} />;
    }
    if (total === 0) {
      // 🚨 原型 02 第二态：这是 0 条时的**空态**，不是错误、更不是「加载中…」
      return appliedKeyword.trim()
        ? (
          <EmptyBlock
            icon="🔍"
            title={`没有匹配「${appliedKeyword.trim()}」的销售料号`}
            hint={<>该料号可能属于其它客户 —— 本列表只显示<b>当前客户</b>名下的料号</>}
            actions={<Button onClick={() => { setKeyword(''); search(''); }}>清空搜索</Button>}
          />
        ) : (
          <EmptyBlock
            icon="📚"
            title="该客户名下还没有可绑定的销售料号"
            hint={<>本列表只显示<b>当前客户</b>名下的料号。可以改用「零件 / 外购件」从头配一个产品</>}
            actions={<Button onClick={onBack}>← 换一种配件类型</Button>}
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

  const confirmReason = selected ? null : '请先选择一个销售料号';
  const confirm = () => {
    if (!selected) return;
    onConfirm({
      materialNo: selected.hfPartNo,
      partName: selected.partName ?? '',
      specification: selected.specification ?? '',
    });
  };

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
          <span style={{ fontSize: 12, color: '#909399', whiteSpace: 'nowrap' }}>
            {customerNo ? <>客户 <Mono muted>{customerNo}</Mono> 名下 · </> : null}共 {total} 条
          </span>
        </div>

        {/* 表格自己带横向滚动兜底，🚫 不许把抽屉正文撑出横向滚动条 */}
        <div style={{ overflowX: 'auto' }}>{body}</div>
      </div>

      <div style={{ padding: '12px 20px', borderTop: '1px solid #f0f0f0', display: 'flex', gap: 8, justifyContent: 'flex-end', flexShrink: 0 }}>
        <Button onClick={onBack}>上一步</Button>
        {/* 🔑 选中即完成：这颗按钮之后**不再有**材质/工序步骤（原型 02 注解） */}
        <ReasonedButton type="primary" reason={confirmReason} onClick={confirm}>
          {selected ? `确定绑定 ${selected.hfPartNo}` : '确定绑定'}
        </ReasonedButton>
      </div>
    </div>
  );
};

export default BindExistingPartPanel;
