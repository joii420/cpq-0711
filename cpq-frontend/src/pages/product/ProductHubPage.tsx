// ─────────────────────────────────────────────────────────────────────────────
// ProductHubPage —— 产品管理壳页（task-260903 · F-1，重写；task-260907 · F-1/F-2 再改）
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
//   经 props 下发给客户产品页签（`ProductCustomerPartTab`，F-3）。
//   ⚠️ **销售产品页签 `ProductSalesPartTab` 本轮未接客户过滤** ——
//      那是 F-4（依赖后端 B-2~B-4 的复合轴改造，尚未落地），本轮不做，见回报「F-4~F-6 实现方案」。
//   localStorage 持久化上次所选客户号 + 失效降级（F-2 / AC-10 / AC-12）。
// ─────────────────────────────────────────────────────────────────────────────
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Tabs, Button, Drawer, Space, Select, Alert } from 'antd';
import { AppstoreOutlined } from '@ant-design/icons';
import type { DefaultOptionType } from 'antd/es/select';
import ProductCategoryManagement from '../basicdata/ProductCategoryManagement';
import ProductCustomerPartTab from './ProductCustomerPartTab';
import ProductSalesPartTab from './ProductSalesPartTab';
import { listDatasetCustomers } from './productHubApi';
import type { CustomerCandidate } from './productHubTypes';

/**
 * 「所有客户」的哨兵值（AC-1 默认项）。
 *
 * 用空串而不是 `undefined`：antd 的 `Select` 拿到 `undefined` 会显示 placeholder 而不是选项文案，
 * 而 AC-1 断言默认值**文案就是「所有客户」**，必须是一个真正被选中的选项。
 * 发请求前再转回 `undefined`（省略该 query ＝ 所有客户）——由各页签自己的 API 调用层做。
 */
const ALL_CUSTOMERS = '';

/** localStorage key（F-2，用户 2026-09-07 裁决 D-4：localStorage 记住上次所选，失效降级「所有客户」）。 */
const CUSTOMER_STORAGE_KEY = 'productHub.customerNo';

/** 未在 `customer` 表建档的客户，后缀文案与配色（原型 `02-客户选择器-展开.html` `.warn{color:#d46b08}`）。 */
const UNREGISTERED_SUFFIX = '（未建档）';
const WARN_STYLE: React.CSSProperties = { color: '#d46b08' };
/** 下拉项内编号与名称之间用全角空格分隔（原型「CUST-0004 + 全角空格 + 正泰」）。
 *  写成转义：eslint `no-irregular-whitespace` 禁止源码里出现裸全角空格。 */
const NBSP_WIDE = '\u3000';

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
 * `Select` 的 `options` 既可能是叶子项（「所有客户」/ 具体客户），也可能是分组标题
 * （原型 `02-客户选择器-展开.html` 的「已建档客户（48）」等）。
 * `filterOption` / `optionRender` 只会在叶子项上被调用——分组标题本身不参与筛选/自定义渲染。
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
    if (v && v !== ALL_CUSTOMERS) {
      window.localStorage.setItem(CUSTOMER_STORAGE_KEY, v);
    } else {
      window.localStorage.removeItem(CUSTOMER_STORAGE_KEY);
    }
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
  const [customerNo, setCustomerNo] = useState<string>(ALL_CUSTOMERS);
  const [candidates, setCandidates] = useState<CustomerCandidate[]>([]);
  /** 状态 C（原型 `05-空态与降级.html`）：候选端点未就绪/失败。与「选中客户无数据」的状态 A 必须可区分。 */
  const [candidatesFailed, setCandidatesFailed] = useState(false);
  /**
   * 客户上下文是否已就绪（localStorage 读取 + 候选加载 + 失效校验，三者全部完成）。
   * 🚨 两个页签的首次列表请求必须等这个变 true 才发——否则会先出一次「所有客户」的全量结果，
   *    紧接着又因为读到了记住的客户号而重新过滤一次，用户能看到全量数据一闪而过（AC-10②）。
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
        setCandidatesFailed(false);
        if (stored && stored !== ALL_CUSTOMERS && !items.some((c) => c.customerNo === stored)) {
          // AC-12：记住的客户已不在候选里 → 静默回落「所有客户」+ 清掉那条 localStorage。
          // 🚫 不弹 message、不 console.error —— 用户没做错任何事。
          setCustomerNo(ALL_CUSTOMERS);
          clearStoredCustomerNo();
        } else {
          setCustomerNo(stored ?? ALL_CUSTOMERS);
        }
      } catch {
        // 状态 C：候选端点未就绪/失败 → 降级为「候选只剩『所有客户』，列表照常可用」。
        // 🚫 不做任何 mock 兜底；🚫 不校验/清除 localStorage——端点本身没查成，不能据此判定
        //    用户记住的客户号是否有效，留着它，下次端点恢复了再校验。
        if (cancelled) return;
        setCandidates([]);
        setCandidatesFailed(true);
        setCustomerNo(ALL_CUSTOMERS);
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

  // 分组下拉（原型 `02-客户选择器-展开.html`）：所有客户 → 已建档（编号升序）→ 未建档（编号升序，置尾）。
  // 后端已按此顺序返回候选（api.md A-1），前端不再重新排序，只按 registered 拆两组加分组标题。
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
    const allOption: CustomerLeafOption = {
      value: ALL_CUSTOMERS,
      label: '所有客户',
      customerNo: ALL_CUSTOMERS,
      customerName: null,
      registered: true,
    };
    const groups: CustomerGroupOption[] = [
      { label: '全部客户', options: [allOption] },
    ];
    if (registered.length > 0) {
      groups.push({ label: `已建档客户（${registered.length}）`, options: registered.map(toLeaf) });
    }
    if (unregistered.length > 0) {
      groups.push({ label: `未在客户主数据建档（${unregistered.length}）`, options: unregistered.map(toLeaf) });
    }
    return groups;
  }, [candidates]);

  /** 当前选中客户的展示文案，供两个页签的 F-7 空态文案使用（本组件是唯一持有候选的地方）。 */
  const customerLabel = useMemo(() => {
    if (customerNo === ALL_CUSTOMERS) return '所有客户';
    const hit = candidates.find((c) => c.customerNo === customerNo);
    if (!hit) return customerNo;
    return hit.registered && hit.customerName
      ? `${hit.customerName}（${hit.customerNo}）`
      : hit.customerNo;
  }, [customerNo, candidates]);

  // AC-15：搜索匹配客户编号与客户名称两者；未建档项没有名称，靠编号也要能搜到。
  // 🚨 rc-select 对分组 `options` 结构只会把**叶子项**传进来做过滤，此处按叶子项形状读取即可。
  const filterOption = useCallback((input: string, option?: CustomerSelectOption) => {
    const leaf = option as CustomerLeafOption | undefined;
    if (!leaf) return false;
    const kw = input.trim().toLowerCase();
    if (!kw) return true;
    return (
      leaf.customerNo.toLowerCase().includes(kw)
      || (leaf.customerName ?? '').toLowerCase().includes(kw)
    );
  }, []);

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <h2 style={{ margin: 0 }}>产品管理</h2>
        <Space>
          {/* F-1：全局客户选择器（原型 `01-壳页-客户产品-默认态.html`）。
              antd Select 无原生「addonBefore」，改用「带背景色标签 + 无边框 Select」的自定义容器
              复刻原型 `.select-group`（组件库能力所限的等价实现）。 */}
          <div
            style={{
              display: 'flex', alignItems: 'center', height: 32,
              border: '1px solid #d9d9d9', borderRadius: 6, overflow: 'hidden', background: '#fff',
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
              style={{ minWidth: 220, maxWidth: 320 }}
              popupMatchSelectWidth={340}
              value={customerNo}
              onChange={onCustomerChange}
              options={groupedOptions}
              filterOption={filterOption}
              optionRender={(option) => {
                // 🚨 只有叶子项（具体客户/「所有客户」）会走到这里，分组标题由 antd 自行渲染
                const d = option.data as CustomerLeafOption;
                if (d.customerNo === ALL_CUSTOMERS) return <span>所有客户</span>;
                return (
                  <span>
                    <span>{d.customerNo}</span>
                    {d.registered && d.customerName ? <span>{NBSP_WIDE}{d.customerName}</span> : null}
                    {!d.registered ? <span style={WARN_STYLE}>{UNREGISTERED_SUFFIX}</span> : null}
                  </span>
                );
              }}
            />
          </div>
          <Button icon={<AppstoreOutlined />} onClick={() => setCategoryOpen(true)}>
            产品分类管理
          </Button>
        </Space>
      </div>

      {/* 状态 C（原型 `05-空态与降级.html`）：候选端点未就绪。可见但不阻断，列表功能不受影响。 */}
      {candidatesFailed && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="客户候选加载失败，当前只能查看全部客户的数据。列表功能不受影响。"
        />
      )}

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
              />
            ),
          },
          // ⚠️ 销售产品页签本轮未接客户过滤（F-4 依赖后端复合轴改造，尚未落地）——
          //    见回报「F-4~F-6 实现方案」，届时会同样接收 customerNo/customerLabel/ready 三个 props。
          { key: 'sales', label: '销售产品', children: <ProductSalesPartTab /> },
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
