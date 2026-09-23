import React, { useEffect, useState } from 'react';
import {
  Button, Input, Space, Tag, Card, message, Tabs, Tooltip, Select,
} from 'antd';
import {
  PlusOutlined, EditOutlined, DeleteOutlined, CopyOutlined,
  SendOutlined, CheckOutlined, CloseOutlined,
  RollbackOutlined,
  ImportOutlined, HistoryOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { quotationService } from '../../services/quotationService';
import { quotationSnapshotService } from '../../services/quotationSnapshotService';
// task-260914 · F-3（AC-12）：两个筛选下拉的字典源 —— 均为**既有**接口，本任务不新增端点。
import { productCategoryService, type ProductCategory } from '../../services/productCategoryService';
import { templateService } from '../../services/templateService';
import { useAuthStore } from '../../stores/authStore';
// task-260907 · F-1（AC-13）：旧「从基础数据导入」入口已下线 —— 它调的
// `POST /basic-data-import/v6/quote/create-quotation` 已被 B-10 摘除（实测返 410），
// 按钮留着只会把用户带进死路。
// 🪦 配套的抽屉组件 `QuoteBasicDataImportV6Drawer.tsx` 与 `services/basicDataImportV6Service.ts`
//    已于 2026-09-07 一并删除（用户批准），**仓库里不再有这两个文件**；需要参照请查 git history。
import CopyQuotationDrawer from './CopyQuotationDrawer';
import SelectableTable, { runBatch, type ToolbarAction } from '../../components/SelectableTable';
// task-260907 · F-2（AC-13 / AC-19）：「导入报价数据」改开**建单专用**抽屉（选客户 → 上传 → 建单）。
// 🚫 不再是 `master-data/dataset/DatasetImportDrawer`（那个被【基础资料维护】共用，本任务一个字节不碰）。
import QuotationDatasetImportDrawer from './QuotationDatasetImportDrawer';
import { QUOTE_IMPORT_ROLES, QUOTE_IMPORT_NO_PERMISSION_TIP } from './quoteDatasetImportConfig';
import { formatNumber } from '../../utils/formatNumber';
// task-260914 · F-4（C-4）：创建日期按**浏览器本地时区**换算。dayjs 是项目既有依赖
// （package.json:24 `^1.11.20`，master-data / config 等多处在用），🚫 不另造日期工具。
import dayjs from 'dayjs';

const { Search } = Input;

const statusMap: Record<string, { label: string; color: string }> = {
  DRAFT: { label: '草稿', color: 'default' },
  SUBMITTED: { label: '待核价', color: 'processing' },
  APPROVED: { label: '已审核', color: 'success' },
  SENT: { label: '已发送', color: 'cyan' },
  ACCEPTED: { label: '已接受', color: 'green' },
  REJECTED: { label: '客户已拒绝', color: 'error' },
  EXPIRED: { label: '已过期', color: 'warning' },
  COSTING_REJECTED: { label: '已驳回', color: 'error' },
};

const statusTabs = [
  { key: '', label: '全部' },
  { key: 'DRAFT', label: '草稿' },
  { key: 'SUBMITTED', label: '待核价' },
  { key: 'COSTING_REJECTED', label: '已驳回' },
  { key: 'APPROVED', label: '已审核' },
  { key: 'SENT', label: '已发送' },
  { key: 'ACCEPTED', label: '已接受' },
  { key: 'REJECTED', label: '客户已拒绝' },
  { key: 'EXPIRED', label: '已过期' },
];

/**
 * task-260914 · F-3（C-3）：模板版本号比较 —— 只用于「同一系列内各版本**名字不同**时取哪个名字」的消歧，
 * 不参与过滤逻辑（过滤走 template_series_id，命中该系列全部版本）。
 *
 * 版本形如 `1.0` / `v1.2`：去掉前导 `v`，按 `.` 分段逐段**数值**比较，
 * 🚫 不能用字符串比较 —— 那会判出 `v1.10 < v1.2`。无法解析的段按 0 处理；完全相等返回 0。
 */
function compareVersion(a?: string, b?: string): number {
  const seg = (x?: string) => String(x ?? '').replace(/^v/i, '').split('.').map((n) => {
    const p = parseInt(n, 10);
    return Number.isNaN(p) ? 0 : p;
  });
  const sa = seg(a); const sb = seg(b);
  for (let i = 0; i < Math.max(sa.length, sb.length); i++) {
    const d = (sa[i] ?? 0) - (sb[i] ?? 0);
    if (d !== 0) return d > 0 ? 1 : -1;
  }
  return 0;
}

const QuotationList: React.FC = () => {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const [data, setData] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size] = useState(20);
  const [keyword, setKeyword] = useState('');
  // task-260914 · F-2（AC-1~7）：独立料号搜索条件，与 keyword 是两个互不干扰的 state（两者 AND）。
  const [partNo, setPartNo] = useState('');
  // task-260914 · F-3（AC-12~17）：分类 / 模板筛选条件。categoryId 取字面量 'NONE' 表示「未分类」。
  const [categoryId, setCategoryId] = useState<string | undefined>(undefined);
  // 🚫 不是 templateId —— C-3 裁决：模板筛选按**系列**，命中该系列全部版本（AC-15）。
  const [templateSeriesId, setTemplateSeriesId] = useState<string | undefined>(undefined);
  const [categories, setCategories] = useState<ProductCategory[]>([]);
  const [templates, setTemplates] = useState<Array<{ id: string; name: string; templateSeriesId?: string; version?: string }>>([]);
  const [statusFilter, setStatusFilter] = useState<string>('');
  const [loading, setLoading] = useState(false);
  // task-260907 · F-2：建单专用导入抽屉
  const [quoteDatasetImportOpen, setQuoteDatasetImportOpen] = useState(false);
  // task-260907 · F-2（AC-19）：权限判据改按 api.md §1 的角色白名单（销售/销售经理/管理员），
  // 🚫 不再用 `DATASET_EDIT_ROLES`（PRICING_MANAGER/SYSTEM_ADMIN）—— 那是维护页写端点的口径。
  const canImportQuoteDataset = !!user && QUOTE_IMPORT_ROLES.includes(user.role);
  const [copySource, setCopySource] = useState<{ id: string; templateId?: string } | null>(null);

  const loadData = async () => {
    setLoading(true);
    try {
      const salesRepFilter = user?.role === 'SALES_REP' ? user.id : undefined;
      const res = await quotationService.list({
        page,
        size,
        status: statusFilter || undefined,
        salesRepId: salesRepFilter,
        keyword: keyword || undefined,
        // task-260914 · F-2 / F-3：三个新条件全部与既有 status / salesRepId / keyword 是 AND；
        // 空值一律传 undefined（= 不过滤），不要传空串——空串会被序列化进 query 变成「搜空料号」。
        partNo: partNo || undefined,
        categoryId: categoryId || undefined,
        templateSeriesId: templateSeriesId || undefined,
      });
      setData(res.data?.content || []);
      setTotal(res.data?.totalElements || 0);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadData(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [page, statusFilter, keyword, partNo, categoryId, templateSeriesId]);

  // task-260914 · F-3（AC-12）：两个筛选下拉的字典，挂载时各拉一次（分类 5 条 / 报价模板 21 条，
  // 规模小，一次性拉全量做本地下拉可行 —— 见任务.md §3 的规模前提）。
  // 🚫 字典拉取失败不得抛未捕获异常（AC-5 的「控制台无未捕获异常」在整页都成立），只提示不中断列表。
  useEffect(() => {
    (async () => {
      try {
        const res = await productCategoryService.list('ACTIVE');
        setCategories(res.data || []);
      } catch (e: any) {
        message.error(e?.message || '加载产品分类失败');
      }
    })();
    (async () => {
      try {
        const res = await templateService.list({ templateKind: 'QUOTATION', status: 'PUBLISHED', size: 200 });
        const d = res?.data;
        const list: Array<{ id: string; name: string; templateSeriesId?: string; version?: string }> =
          Array.isArray(d) ? d : (d?.content || []);
        setTemplates(list);
      } catch (e: any) {
        message.error(e?.message || '加载报价模板失败');
      }
    })();
  }, []);

  // 分类下拉 = 5 个 ACTIVE 分类 + 一个「未分类」（D-10 / AC-12 / AC-14）。
  // 「未分类」排在最前，与 原型图/列表页-筛选展开.html 的选项顺序一致；🚫 选项不显示计数（原型里的
  // 「117 单 / 62 单」是给实现看的说明标注，不是功能）。
  const categoryOptions = [
    { value: 'NONE', label: '未分类' },
    ...categories.map((c) => ({ value: c.id, label: c.name })),
  ];
  // task-260914 · F-3（C-3 裁决 / AC-12 / AC-15）：模板下拉**按模板系列聚合，每个系列一条**。
  // 🚫 不是把 21 条 PUBLISHED 模板原样塞进去 —— 同一系列的多个版本**同名**，叠加 D-8「不带版本号」后
  //    会出现 5 个文字完全相同的「正泰测试模板2」，用户无从选起；而且按单个模板 ID 过滤只筛得到那一版
  //    （选「正泰测试模板1」只得 32 单，另外 27 单静默消失）。聚合后预期 13 条，零重名。
  // label 取该系列**版本号最大**那条的名字（同系列各版本通常同名，这只是不同名时的消歧规则）。
  const templateOptions = (() => {
    const bySeries = new Map<string, { id: string; name: string; version?: string }>();
    for (const t of templates) {
      // templateSeriesId 在后端是 nullable=false（TemplateDTO.java:18 暴露），理论上恒有值；
      // 万一缺失就退回用自身 id 兜底，保证该条目不会从下拉里静默消失。
      const sid = t.templateSeriesId || t.id;
      if (!sid) continue;
      const prev = bySeries.get(sid);
      if (!prev || compareVersion(t.version, prev.version) > 0) {
        bySeries.set(sid, { id: sid, name: t.name, version: t.version });
      }
    }
    return [...bySeries.values()]
      // 排序口径钉死为 localeCompare('zh-Hans-CN')（C-5：之前未定义，三种口径结果可能不同）。
      .sort((a, b) => (a.name || '').localeCompare(b.name || '', 'zh-Hans-CN'))
      .map((t) => ({ value: t.id, label: t.name }));
  })();

  // 列定义 —— 报价单号点击进详情；不再有"操作"列
  const columns = [
    {
      title: '报价单号', dataIndex: 'quotationNumber', key: 'quotationNumber', width: 180,
      render: (v: string, record: any) => (
        <a onClick={(e) => { e.stopPropagation(); navigate(`/quotations/${record.id}`); }}>{v}</a>
      ),
    },
    { title: '名称', dataIndex: 'name', key: 'name', ellipsis: true },
    { title: '客户', dataIndex: 'snapshotCustomerName', key: 'customer' },
    // task-260914 · F-4（AC-8 / AC-9）：产品分类列插在「客户」之后、「状态」之前，不是追加到最右。
    // 空值一律 '—'（U+2014），🚫 不是空白 / undefined / UUID（D-6：不从模板反查兜底）。
    // ⚠️ 用 `||` 不是 `??`（2026-09-14 主线裁决）：AC-9 断言的是**用户可见结果**不能是空白，
    //    而 `??` 只兜 null/undefined —— 后端一旦返回空字符串 `''` 就会渲染成空白且不报错。
    //    不依赖「后端一定返回 null」这个前提，在渲染层自己兜住。title 同理，空串时不挂空 title。
    // ellipsis + title：极值文案单行截断 + 悬停出全文（原型图/列表页-极值与禁用态.html）。
    // 🚫 表头**不出**筛选图标 —— 筛选入口只在工具栏的两个下拉（2026-09-14 用户裁决：两处入口
    //    会产生选中状态不同步）。故此处不配 AntD `filters` / `filterDropdown`。
    {
      title: '产品分类', dataIndex: 'categoryName', key: 'categoryName', width: 140, ellipsis: true,
      render: (v: string | null | undefined) => <span title={v || undefined}>{v || '—'}</span>,
    },
    // task-260914 · F-4（AC-8 / AC-10）：报价模板列，只显示模板名，不带版本号（D-8）。
    {
      title: '报价模板', dataIndex: 'templateName', key: 'templateName', width: 180, ellipsis: true,
      render: (v: string | null | undefined) => <span title={v || undefined}>{v || '—'}</span>,
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 100,
      render: (s: string) => {
        const m = statusMap[s] || { label: s, color: 'default' };
        return <Tag color={m.color}>{m.label}</Tag>;
      },
    },
    {
      title: '总金额', dataIndex: 'totalAmount', key: 'totalAmount', width: 130,
      // task-0801（AC-5）：原 toLocaleString() 默认最多 3 位小数，与详情页 2/4 位不一致，
      // 是"列表与详情对不上"里属于精度的那部分——只改精度、不改列/不改名，改走 formatNumber
      // （DISPLAY_SCALE=6 兜底），保留 ¥ 前缀与 '-' 空值兜底。
      render: (v: string | null | undefined) => v != null ? `¥${formatNumber(v, { isComputed: true }) ?? '0'}` : '-',
    },
    // task-260914 · F-4（AC-8 / AC-11 / C-4）：创建日期列插在「总金额」之后、「到期日」之前。
    // 🕐 **按浏览器本地时区换算**后再格式化成 YYYY-MM-DD（D-9：不带时分秒）。
    // 🚫 不许 `String(v).slice(0, 10)` —— 那取的是 **UTC 口径**：库里 created_at 存 UTC
    //    （服务器 Etc/UTC），例如 QT-20260914-0867 = `2026-09-15 01:47:58+00`，本机 PDT(-0700) 下
    //    本地日其实是 09-14，而单号 `QT-20260914-` 也是按服务器本地时间生成的 —— 截 UTC 串会显示
    //    `2026-09-15`，比单号晚一天。dayjs 默认按本地时区解析+格式化，正好是要的口径。
    {
      title: '创建日期', dataIndex: 'createdAt', key: 'createdAt', width: 120, ellipsis: true,
      render: (v: string | null | undefined) => {
        const m = v ? dayjs(v) : null;
        const d = m && m.isValid() ? m.format('YYYY-MM-DD') : null;
        return <span title={d || undefined}>{d || '—'}</span>;
      },
    },
    { title: '到期日', dataIndex: 'expiryDate', key: 'expiryDate', width: 120 },
  ];

  // PRICING_MANAGER 角色不参与动作，直接给空 actions
  const isPricingManager = user?.role === 'PRICING_MANAGER';

  const actions: ToolbarAction<any>[] = isPricingManager ? [] : [
    {
      key: 'edit',
      label: '编辑',
      icon: <EditOutlined />,
      enabledWhen: (rows) => {
        if (rows.length !== 1) return '编辑一次只能选一行';
        const s = rows[0].status;
        if (['SUBMITTED', 'APPROVED'].includes(s)) return '请先撤回再编辑';
        if (!['DRAFT', 'COSTING_REJECTED'].includes(s)) return '当前状态不可编辑';
        return true;
      },
      onClick: async (rows) => {
        const row = rows[0];
        if (row.status === 'COSTING_REJECTED') {
          try {
            await quotationService.beginEdit(row.id);
          } catch (e: any) {
            message.error(e.message || '转草稿失败');
            return;
          }
        }
        navigate(`/quotations/${row.id}/edit`);
      },
    },
    {
      key: 'copy',
      label: '复制',
      icon: <CopyOutlined />,
      enabledWhen: (rows) => rows.length === 1 ? true : '复制一次只能选一行',
      onClick: (rows) => {
        const r: any = rows[0];
        setCopySource({ id: r.id, templateId: r.customerTemplateId ?? r.templateId });
      },
    },
    {
      key: 'submit',
      label: '提交审批',
      icon: <SendOutlined />,
      enabledWhen: (rows) => {
        if (rows.length === 0) return false;
        if (rows.some((r: any) => r.status !== 'DRAFT')) return '仅草稿可提交审批';
        return true;
      },
      needsConfirm: true,
      confirmTitle: '确认提交选中的 {N} 个报价单审批？',
      onClick: async (rows) => {
        await runBatch(rows, (r: any) => quotationSnapshotService.submit(r.id).then(() => undefined), {
          rowLabel: (r: any) => `${r.quotationNumber} ${r.name}`,
          successMsg: `已提交 ${rows.length} 项`,
        });
        loadData();
      },
    },
    {
      key: 'withdraw',
      label: '撤回',
      icon: <RollbackOutlined />,
      enabledWhen: (rows) => {
        if (rows.length === 0) return false;
        if (rows.some((r: any) => !['SUBMITTED', 'COSTING_REJECTED', 'APPROVED'].includes(r.status)))
          return '仅待核价/已驳回/已审核可撤回';
        if (rows.some((r: any) => r.salesRepId !== user?.id)) return '只能撤回自己提交的报价单';
        return true;
      },
      needsConfirm: true,
      confirmTitle: '撤回 {N} 个报价单？',
      // task-0721（F3）：批量撤回可能混选「已审核」项，静态文案补一句规则七提示（SelectableTable
      // 的 confirmDescription 是静态字符串，不按行区分状态，故合并为一句通用说明）。
      confirmDescription: '撤回后报价单回到草稿状态，需重新提交审批；若所选项中含「已审核」状态，其已回填生效的基础数据不会回退。',
      onClick: async (rows) => {
        await runBatch(rows, (r: any) => quotationService.withdraw(r.id).then(() => undefined), {
          rowLabel: (r: any) => `${r.quotationNumber} ${r.name}`,
          successMsg: `已撤回 ${rows.length} 项`,
        });
        loadData();
      },
    },
    {
      key: 'send',
      label: '发送给客户',
      icon: <SendOutlined />,
      enabledWhen: (rows) => {
        if (rows.length !== 1) return '发送一次只能选一行';
        if (rows[0].status !== 'APPROVED') return '仅已批准状态可发送';
        return true;
      },
      onClick: (rows) => navigate(`/quotations/${rows[0].id}`),
    },
    {
      key: 'accept',
      label: '客户接受',
      icon: <CheckOutlined />,
      enabledWhen: (rows) => {
        if (rows.length === 0) return false;
        if (rows.some((r: any) => r.status !== 'SENT')) return '仅已发送状态可标记接受';
        return true;
      },
      needsConfirm: true,
      confirmTitle: '确认客户已接受 {N} 个报价单？',
      onClick: async (rows) => {
        await runBatch(rows, (r: any) => quotationService.accept(r.id).then(() => undefined), {
          rowLabel: (r: any) => `${r.quotationNumber} ${r.name}`,
          successMsg: `已标记接受 ${rows.length} 项`,
        });
        loadData();
      },
    },
    {
      key: 'reject-customer',
      label: '客户拒绝',
      icon: <CloseOutlined />,
      danger: true,
      enabledWhen: (rows) => {
        if (rows.length === 0) return false;
        if (rows.some((r: any) => r.status !== 'SENT')) return '仅已发送状态可标记拒绝';
        return true;
      },
      needsConfirm: true,
      confirmTitle: '确认客户已拒绝 {N} 个报价单？',
      onClick: async (rows) => {
        await runBatch(rows, (r: any) => quotationService.rejectByCustomer(r.id, '客户拒绝').then(() => undefined), {
          rowLabel: (r: any) => `${r.quotationNumber} ${r.name}`,
          successMsg: `已标记拒绝 ${rows.length} 项`,
        });
        loadData();
      },
    },
    {
      key: 'delete',
      label: '删除',
      icon: <DeleteOutlined />,
      danger: true,
      enabledWhen: (rows) => {
        if (rows.length === 0) return false;
        if (rows.some((r: any) => r.status !== 'DRAFT')) return '仅草稿状态可删除';
        return true;
      },
      needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 个报价单？',
      confirmDescription: '⚠️ 此操作不可撤销。',
      onClick: async (rows) => {
        await runBatch(rows, (r: any) => quotationService.delete(r.id).then(() => undefined), {
          rowLabel: (r: any) => `${r.quotationNumber} ${r.name}`,
          successMsg: `已删除 ${rows.length} 项`,
        });
        loadData();
      },
    },
  ];

  // task-260914 · C-8（AC-20 改写）：三个操作按钮从工具栏**移到 Card 标题栏右侧**（AntD `Card` 的
  // `extra`），与标题「报价单管理」同一行最右端 —— 见 原型图/列表页-*.html 的 `.card-head .head-actions`。
  // 起因：四个条件控件 300+240+160+200 = 900px，加按钮组 388px = 1288px，而 1280 视口下卡片内宽只有
  // 962px —— **数学上放不下**，`flex-wrap` 只能让按钮整体掉到第二行（1280/1366/1440/1600 四档全中，
  // 仅 1920 幸免）。上一轮的 `<Space wrap>` 解决的是「被挤出可视区」，解决不了「换行」本身。
  // 🚫 按钮组代码**一个字节未改**（含 canImportQuoteDataset 判据、QUOTE_IMPORT_NO_PERMISSION_TIP 文案、
  //    Tooltip 包裹结构、三个按钮的顺序与缩进）—— 只是换了挂载位置，不是重写。
  const headerActions = (
    <>
      <Space>
        <Button icon={<HistoryOutlined />} onClick={() => navigate('/import-history')}>
          导入历史
        </Button>
        {/* task-260907 · F-2（AC-13 / AC-19）：位置固定在「新建报价单」之前。
            ✅ F-1 已执行：旧「从基础数据导入」按钮已摘除（S-5 模板已落地，新链路已通）。
            无权限时**禁用但可见** + hover 出原因（frontend.md §1.2）。 */}
        <Tooltip title={canImportQuoteDataset ? undefined : QUOTE_IMPORT_NO_PERMISSION_TIP}>
          <Button
            type="primary"
            icon={<ImportOutlined />}
            disabled={!canImportQuoteDataset}
            onClick={() => setQuoteDatasetImportOpen(true)}
          >
            导入报价数据
          </Button>
        </Tooltip>
        <Button icon={<PlusOutlined />} onClick={() => navigate('/quotations/new')}>
          新建报价单
        </Button>
      </Space>
    </>
  );

  // 工具栏现在只承载四个**条件控件**，独占一整行（原型：按钮区已从 toolbar 搬到 card-head）。
  const toolbar = (
    <Space wrap>
      <Search
        placeholder="搜索报价单号/名称/客户"
        onSearch={(v) => { setKeyword(v); setPage(0); }}
        allowClear
        style={{ width: 300 }}
      />
      {/* task-260914 · F-2（AC-1）：独立料号搜索框，紧跟在既有搜索框之后。与左侧框是 AND，
          与状态页签也是 AND；切页签时本条件保留不清空（AC-7 —— statusFilter 变化不动 partNo）。
          task-260922 · F-1（AC-1）：后端 partNo 加上生产料号一路，提示文字改为「搜索销售/客户/生产料号」；
          框宽 240 不变 —— 内容区仅 169px，旧文案 214px 已被截断，新文案 148px 可完整显示（任务.md D-4）。 */}
      <Search
        placeholder="搜索销售/客户/生产料号"
        onSearch={(v) => { setPartNo(v); setPage(0); }}
        allowClear
        style={{ width: 240 }}
      />
      {/* task-260914 · F-3（AC-12~17）：服务端筛选。任何条件变化都重置到第 1 页，
          否则停在越界页会看到空列表（AC-17 的页码重置断言）。 */}
      <Select
        placeholder="产品分类"
        allowClear
        style={{ width: 160 }}
        value={categoryId}
        onChange={(v) => { setCategoryId(v); setPage(0); }}
        options={categoryOptions}
      />
      {/* 选项 value = template_series_id（C-3），不是单个模板 id。仍不显示版本号（D-8）、不显示计数。 */}
      <Select
        placeholder="报价模板"
        allowClear
        style={{ width: 200 }}
        value={templateSeriesId}
        onChange={(v) => { setTemplateSeriesId(v); setPage(0); }}
        options={templateOptions}
      />
    </Space>
  );

  return (
    <Card title="报价单管理" extra={headerActions}>
      <Tabs
        items={statusTabs.map(t => ({ key: t.key, label: t.label }))}
        activeKey={statusFilter}
        onChange={(k) => { setStatusFilter(k); setPage(0); }}
        style={{ marginBottom: 8 }}
      />

      <SelectableTable<any>
        rowKey="id"
        columns={columns}
        dataSource={data}
        loading={loading}
        pagination={{
          current: page + 1,
          pageSize: size,
          total,
          onChange: (p) => setPage(p - 1),
          showTotal: (t) => `共 ${t} 条`,
        }}
        toolbar={toolbar}
        actions={actions}
        rowLabel={(r: any) => `${r.quotationNumber} ${r.name}${r.snapshotCustomerName ? ' · ' + r.snapshotCustomerName : ''}`}
      />

      <QuotationDatasetImportDrawer
        open={quoteDatasetImportOpen}
        onClose={() => setQuoteDatasetImportOpen(false)}
        onCreated={loadData}
      />
      <CopyQuotationDrawer
        open={!!copySource}
        defaultTemplateId={copySource?.templateId}
        onClose={() => setCopySource(null)}
        onConfirm={async (templateId) => {
          try {
            const res = await quotationService.copy(copySource!.id, templateId);
            message.success('复制成功');
            setCopySource(null);
            navigate(`/quotations/${res.data.id}/edit`);
          } catch (e: any) { message.error(e.message); }
        }}
      />
    </Card>
  );
};

export default QuotationList;
