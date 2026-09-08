// ─────────────────────────────────────────────────────────────────────────────
// ProductHubPage —— 产品管理壳页（task-260903 · F-1，重写；task-260907 · F-1/F-2/F-4/F-5/F-6 再改）
//
// 两个页签 [客户产品][销售产品]，默认选中**客户产品**（AC-1）。
// 路由 `/products-hub` 不变（书签 / 直链 / E2E 不挂）。
//
// 摘除说明（需求文档 ④ R-5，用户在闸门 A 已确认接受 R-1）：
//   旧的「产品主数据」(InternalMaterialManagement) / 「客户对应主数据」(ProductManagement)
//   两个页签**只摘 UI 入口**，两个组件文件 + 后端端点 + 表**全部保留不删** ——
//   `product` 被 quotation_line_item / product_template_binding / product_process 三张表外键引用，
//   `material_master` 仍被 V6 导入链路写入。删表属 CLAUDE.md §3.2 红线，须单独立项。
//
// 🚫 **不许顺手删「产品分类管理」** —— `product_category` 被 `customer.product_category_id` 引用，
//    报价 / 核价 / 选配三套模板按客户产品分类匹配（task-0712），删则断链。
//
// 🆕 task-260907-产品管理客户过滤 · F-1 / F-2（本次改动）：
//   标题行新增**全局客户选择器**（AC-1），候选取自 `GET /dataset/quote/customers`（A-1，
//   `customer` 主数据表全集 ∪ 报价业务表未建档客户号，AC-2）。客户上下文是壳页 state，
//   经 props 下发给客户产品页签（`ProductCustomerPartTab`，F-3）与销售产品页签
//   （`ProductSalesPartTab`，F-4/F-5/F-6——本轮已接入，依赖的后端 B-2~B-4 复合轴改造已合并 master）。
//   localStorage 持久化上次所选客户号 + 失效降级（F-2 / AC-10 / AC-12）。
//
// 🔄 2026-09-07 第二轮裁决（D-7~D-12，用户在第一批交付后提出）—— **客户改为必选**：
//   · 🚫 下拉里**没有「所有客户」选项**了，`customerNo` 恒为一个真实客户号；
//     原先「空串哨兵表示所有客户」那套已作废，改用「候选未就绪」这个独立布尔态表达"暂不可用"。
//   · **默认选中候选列表第一项**（`items[0]`，排序是后端契约，前端不重排）。
//   · **localStorage 失效降级目标**从「所有客户」改成「候选列表第一个客户」。
//   · **候选端点失败 / 候选为空**：客户是必传的 ⇒ 没有任何客户可选 ⇒ 选择器**禁用**（可见 +
//     tooltip 说明原因，`frontend.md §1.2`）+ 错误提示条；两个页签的列表区域都不发请求、
//     直接显示「客户候选加载失败，无法展示数据」，与 AC-13 的"业务性空态"必须长得不一样。
// ─────────────────────────────────────────────────────────────────────────────
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Tabs, Button, Drawer, Space, Select, Tooltip } from 'antd';
import { AppstoreOutlined } from '@ant-design/icons';
import type { DefaultOptionType } from 'antd/es/select';
import ProductCategoryManagement from '../basicdata/ProductCategoryManagement';
import ProductCustomerPartTab from './ProductCustomerPartTab';
import ProductSalesPartTab from './ProductSalesPartTab';
import { listDatasetCustomers } from './productHubApi';
import type { CustomerCandidate } from './productHubTypes';

/** localStorage key（F-2，用户 2026-09-07 裁决 D-4：localStorage 记住上次所选）。 */
const CUSTOMER_STORAGE_KEY = 'productHub.customerNo';

/** 未在 `customer` 表建档的客户，后缀文案与配色（原型 `02-客户选择器-展开.html` `.warn{color:#d46b08}`）。 */
const UNREGISTERED_SUFFIX = '（未建档）';
const WARN_STYLE: React.CSSProperties = { color: '#d46b08' };
/** 下拉项内编号与名称之间用全角空格分隔（原型「CUST-0004 + 全角空格 + 正泰」）。
 *  写成转义：eslint `no-irregular-whitespace` 禁止源码里出现裸全角空格。 */
const NBSP_WIDE = '\u3000';

/** 候选端点失败 / 候选为空时，选择器禁用态的 tooltip 文案（`frontend.md §1.2`：禁用必须可见+说明原因）。 */
const CANDIDATES_FAILED_TOOLTIP = '客户候选加载失败，无法选择客户';

interface CustomerLeafOption extends DefaultOptionType {
  value: string;
  label: string;
  customerNo: string;
  customerName: string | null;
  registered: boolean;
}

interface CustomerGroupOption extends DefaultOptionType {
  label: string;
  options: CustomerLeafOption[];
}

/**
 * `Select` 的 `options` 既可能是叶子项（具体客户），也可能是分组标题
 * （原型 `02-客户选择器-展开.html` 的「已建档客户（48）」等）。
 * 🚨 **不要假设回调只在叶子项上被调用。** `filterOption` 实测**会**收到分组节点
 * （antd `useFilterOptions` 遍历整棵 `options`，分组节点也在内），而分组节点**没有** `customerNo`
 * ⇒ 直接 `option.customerNo.toLowerCase()` 会 `undefined.toLowerCase()` 让整页崩。
 * 2026-09-07 真机验收实证：`tsc` / `eslint` / Playwright 截图**三者都没抓到** ——
 * `tsc` 被 `as` 断言绕过，截图没触发「展开下拉并输入」这个交互。
 * ⇒ 下面两个回调**一律先做叶子判定**，不靠假设。
 */
type CustomerSelectOption = CustomerLeafOption | CustomerGroupOption;

/** try/catch 包裹的 localStorage 访问器（F-2 硬约束：隐私窗口/禁用存储时访问器本身会抛）。 */
function readStoredCustomerNo(): string | null {
  try {
    return window.localStorage.getItem(CUSTOMER_STORAGE_KEY);
  } catch {
    return null;
  }
}
function writeStoredCustomerNo(v: string): void {
  try {
    window.localStorage.setItem(CUSTOMER_STORAGE_KEY, v);
  } catch {
    // 隐私窗口 / 站点数据被清 / 浏览器禁用存储：静默放弃持久化，不影响本次交互
  }
}
function clearStoredCustomerNo(): void {
  try {
    window.localStorage.removeItem(CUSTOMER_STORAGE_KEY);
  } catch {
    // 同上，静默
  }
}

const ProductHubPage: React.FC = () => {
  // AC-1：默认选中「客户产品」
  const [activeTab, setActiveTab] = useState<string>('customer');
  const [categoryOpen, setCategoryOpen] = useState(false);

  // ── F-1/F-2：全局客户选择器状态 ──────────────────────────────────────────
  /** 当前选中的客户号。🔄 D-7 起客户必选——只有在 `candidatesAvailable` 为 true 时才有意义。 */
  const [customerNo, setCustomerNo] = useState<string>('');
  const [candidates, setCandidates] = useState<CustomerCandidate[]>([]);
  /**
   * 状态 C（原型 `05-空态与降级.html`）：候选端点失败，或候选加载成功但**恰好 0 项**
   * （fronttask F-1 第 9 条：理论上不会发生，但仍要按契约处理，🚫 不许崩溃/静默回退旧的「所有客户」）。
   * 与「选中客户无数据」的状态 A 必须可区分——两者含义完全不同。
   */
  const [candidatesFailed, setCandidatesFailed] = useState(false);
  /**
   * 客户上下文是否已就绪（localStorage 读取 + 候选加载 + 默认值/失效校验，全部完成）。
   * 🚨 两个页签的首次列表请求必须等这个变 true 才发——AC-10②断言的形态是
   *    「刷新后第一个 `/parts` 请求就带 customerNo」，不是靠肉眼看闪不闪烁。
   */
  const [customerContextReady, setCustomerContextReady] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const stored = readStoredCustomerNo();
      try {
        const r = await listDatasetCustomers();
        if (cancelled) return;
        const items = r.items ?? [];
        setCandidates(items);
        if (items.length === 0) {
          // fronttask F-1 第 9 条：理论不会发生的边界，仍按"没有可选客户"处理
          setCandidatesFailed(true);
          setCustomerNo('');
        } else {
          setCandidatesFailed(false);
          // 🔄 D-7：默认值 = 候选列表第一项，不是「所有客户」（该选项已不存在）
          const fallback = items[0].customerNo;
          if (stored && items.some((c) => c.customerNo === stored)) {
            setCustomerNo(stored);
          } else {
            // AC-12：记住的客户已不在候选里（含 stored 为 null 的首次进入）→ 静默落到候选第一项。
            // 🚫 不弹 message、不 console.error —— 用户没做错任何事。
            setCustomerNo(fallback);
            if (stored) clearStoredCustomerNo();
          }
        }
      } catch {
        // 状态 C：候选端点失败 → 客户必传但没有任何客户可选 ⇒ 选择器禁用 + 错误提示，
        // 列表区不展示数据。🚫 不做任何 mock 兜底；🚫 不校验/清除 localStorage——
        // 端点本身没查成，不能据此判定用户记住的客户号是否有效，留着它，下次端点恢复了再校验。
        if (cancelled) return;
        setCandidates([]);
        setCandidatesFailed(true);
        setCustomerNo('');
      } finally {
        if (!cancelled) setCustomerContextReady(true);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  /** 用户手动切换客户（AC-9 跨页签保持 / 切换后两个页签都回到第 1 页——由各页签自己监听 customerNo 变化重置）。 */
  const onCustomerChange = useCallback((v: string) => {
    setCustomerNo(v);
    writeStoredCustomerNo(v);
  }, []);

  // 分组下拉（原型 `02-客户选择器-展开.html`）：已建档（编号升序）→ 未建档（编号升序，置尾）。
  // 🔄 D-7：不再有「所有客户」/「全部客户」这一档。后端已按此顺序返回候选（api.md A-1），
  // 前端不再重新排序，只按 registered 拆两组加分组标题。
  const groupedOptions = useMemo(() => {
    const registered = candidates.filter((c) => c.registered);
    const unregistered = candidates.filter((c) => !c.registered);
    const toLeaf = (c: CustomerCandidate): CustomerLeafOption => ({
      value: c.customerNo,
      label: c.registered
        ? (c.customerName ? `${c.customerNo}${NBSP_WIDE}${c.customerName}` : c.customerNo)
        : `${c.customerNo}${UNREGISTERED_SUFFIX}`,
      customerNo: c.customerNo,
      customerName: c.customerName ?? null,
      registered: c.registered,
    });
    const groups: CustomerGroupOption[] = [];
    if (registered.length > 0) {
      groups.push({ label: `已建档客户（${registered.length}）`, options: registered.map(toLeaf) });
    }
    if (unregistered.length > 0) {
      groups.push({ label: `未在客户主数据建档（${unregistered.length}）`, options: unregistered.map(toLeaf) });
    }
    return groups;
  }, [candidates]);

  /** 候选列表第一项的客户号——默认值 / 降级目标 / 下拉里「默认选中」标记的判据（原型 02）。 */
  const defaultCandidateNo = candidates.length > 0 ? candidates[0].customerNo : null;

  /** 当前选中客户的展示文案，供两个页签的 F-7 空态文案使用（本组件是唯一持有候选的地方）。 */
  const customerLabel = useMemo(() => {
    const hit = candidates.find((c) => c.customerNo === customerNo);
    if (!hit) return customerNo;
    return hit.registered && hit.customerName
      ? `${hit.customerName}（${hit.customerNo}）`
      : hit.customerNo;
  }, [customerNo, candidates]);

  // AC-15：搜索匹配客户编号与客户名称两者；未建档项没有名称，靠编号也要能搜到。
  // 🚨 rc-select 对分组 `options` 结构只会把**叶子项**传进来做过滤，此处按叶子项形状读取即可。
  const filterOption = useCallback((input: string, option?: CustomerSelectOption) => {
    // 🚨 分组节点也会进来（见文件头注释）——用 in 做类型守卫，TS 会把联合类型收窄到叶子。
    if (!option || !('customerNo' in option) || typeof option.customerNo !== 'string') return false;
    const leaf = option;
    const kw = input.trim().toLowerCase();
    if (!kw) return true;
    return (
      leaf.customerNo.toLowerCase().includes(kw)
      || (leaf.customerName ?? '').toLowerCase().includes(kw)
    );
  }, []);

  // 🔄 D-7：客户必选后，「候选没查成/候选为空」= 没有任何客户可选，选择器必须禁用
  // （frontend.md §1.2：禁用但可见 + tooltip 说明原因，🚫 不许直接隐藏选择器）。
  const selectorDisabled = !customerContextReady || candidatesFailed;

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <h2 style={{ margin: 0 }}>产品管理</h2>
        <Space>
          {/* F-1：全局客户选择器（原型 `01-壳页-客户产品-默认态.html`）。
              antd Select 无原生「addonBefore」，改用「带背景色标签 + 无边框 Select」的自定义容器
              复刻原型 `.select-group`（组件库能力所限的等价实现）。 */}
          <Tooltip title={selectorDisabled && candidatesFailed ? CANDIDATES_FAILED_TOOLTIP : undefined}>
            <div
              style={{
                display: 'flex', alignItems: 'center', height: 32,
                border: '1px solid #d9d9d9', borderRadius: 6, overflow: 'hidden',
                background: selectorDisabled ? 'rgba(0, 0, 0, 0.04)' : '#fff',
              }}
            >
              <span
                style={{
                  padding: '0 11px', height: '100%', display: 'flex', alignItems: 'center',
                  background: '#fafafa', borderRight: '1px solid #d9d9d9',
                  color: 'rgba(0, 0, 0, 0.65)', whiteSpace: 'nowrap', fontSize: 14,
                }}
              >
                客户
              </span>
              <Select<string, CustomerSelectOption>
                variant="borderless"
                showSearch
                disabled={selectorDisabled}
                placeholder={candidatesFailed ? '加载失败' : '加载中…'}
                style={{ minWidth: 220, maxWidth: 320 }}
                popupMatchSelectWidth={340}
                value={customerNo || undefined}
                onChange={onCustomerChange}
                options={groupedOptions}
                filterOption={filterOption}
                optionRender={(option) => {
                  // 🚨 同 filterOption：不假设「只在叶子项上被调用」，先判定再用。
                  const raw = option.data as Partial<CustomerLeafOption> | undefined;
                  if (!raw || typeof raw.customerNo !== 'string') return option.label ?? null;
                  const d = raw as CustomerLeafOption;
                  return (
                    <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <span>
                        <span>{d.customerNo}</span>
                        {d.registered && d.customerName ? <span>{NBSP_WIDE}{d.customerName}</span> : null}
                        {!d.registered ? <span style={WARN_STYLE}>{UNREGISTERED_SUFFIX}</span> : null}
                      </span>
                      {/* 原型 02：候选第一项标「默认选中」，帮用户理解"为什么打开页面看到的是它" */}
                      {d.customerNo === defaultCandidateNo && (
                        <span style={{ marginLeft: 'auto', fontSize: 12, color: 'rgba(0, 0, 0, 0.45)' }}>默认选中</span>
                      )}
                    </span>
                  );
                }}
              />
            </div>
          </Tooltip>
          <Button icon={<AppstoreOutlined />} onClick={() => setCategoryOpen(true)}>
            产品分类管理
          </Button>
        </Space>
      </div>

      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        // 销毁非活动页签：两个列表各自服务端分页，同时挂载会重复发请求
        // （antd 6 已把 destroyInactiveTabPane 标为 deprecated，用等价的 destroyOnHidden，
        //   否则 dev 下 antd 会打一条 console.error 级弃用告警，违反 AC-11「console 无 error」）
        destroyOnHidden
        items={[
          {
            key: 'customer',
            label: '客户产品',
            children: (
              <ProductCustomerPartTab
                customerNo={customerNo}
                customerLabel={customerLabel}
                ready={customerContextReady}
                available={!candidatesFailed}
              />
            ),
          },
          // 🆕 task-260907 · F-4/F-5/F-6（本次改动）：销售产品页签接客户过滤，
          //    与客户产品页签同一套四个 props（customerNo/customerLabel/ready/available）。
          {
            key: 'sales',
            label: '销售产品',
            children: (
              <ProductSalesPartTab
                customerNo={customerNo}
                customerLabel={customerLabel}
                ready={customerContextReady}
                available={!candidatesFailed}
              />
            ),
          },
        ]}
      />
      <Drawer
        title="产品分类管理"
        placement="right"
        // antd 6：width 已弃用，改用等价的 size（数值语义不变，仍是 960px）
        size={960}
        open={categoryOpen}
        onClose={() => setCategoryOpen(false)}
        destroyOnHidden
      >
        <ProductCategoryManagement />
      </Drawer>
    </div>
  );
};

export default ProductHubPage;
