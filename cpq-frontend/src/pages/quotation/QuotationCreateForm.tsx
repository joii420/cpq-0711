// V6 导入向导 Step 3 — 创建报价单表单（复用组件，无 Drawer 壳）
// 从 BasicDataImportV5ToQuotation.tsx 的 CreateQuotationDrawer 表单部分抽出
import React, { useEffect, useState } from 'react';
import {
  Alert,
  Form,
  Input,
  Select,
  Spin,
  Tag,
} from 'antd';
import { productCategoryService, type ProductCategory } from '../../services/productCategoryService';
import { templateService } from '../../services/templateService';
import api from '../../services/api';

// 核价模板类型
interface CostingCardTemplate {
  id: string;
  name: string;
  version?: string;
  status?: string;
  templateKind?: string;
  customerId?: string | null;
  customerName?: string | null;
}

// 2026-05-14: 后端新增 MIXED 状态表示同时命中客户专属 + 通用模板
type MatchType = 'CUSTOMER_SPECIFIC' | 'GENERAL_FALLBACK' | 'MIXED' | 'NONE';
interface MatchedTemplate {
  id: string;
  name: string;
  version?: string;
  categoryName?: string;
  /** customerId 决定每条 template 的来源:= 当前客户 → 客户专属;null/undefined → 通用 */
  customerId?: string | null;
  customerName?: string;
}
interface MatchResult {
  matchType: MatchType;
  templates: MatchedTemplate[];
}

// 表单值结构（导出供父组件使用）
export interface QuotationFormValue {
  name: string;
  categoryId: string | undefined;
  customerTemplateId: string | undefined;
  costingTemplateId: string | undefined;
}

interface Props {
  customerId: string;
  customerName: string;
  value: QuotationFormValue;
  onChange: (v: QuotationFormValue) => void;
  /** 当表单是否可提交状态变化时通知父组件 */
  onValidityChange?: (isValid: boolean) => void;
  /**
   * 报价单已生成态: 锁定产品分类 / 报价模板 / 核价模板 字段不可改.
   * 父组件按 !!quotationId 传入. 名称字段允许改, 联系人在父组件管理.
   */
  readOnly?: boolean;
  /** 报价模板自动带出来源提示(如「上次使用 · 最新版 v3」);readOnly 时不显示 */
  customerTemplateHint?: string;
  /** 核价模板自动带出来源提示 */
  costingTemplateHint?: string;
  /**
   * task-0712 update-071501: 产品分类改由客户绑定带出，不再手选（D3/D4）。
   * 父组件从所选客户的 `productCategoryId` 传入；传入后分类下拉只读展示、不可改。
   */
  lockedCategoryId?: string;
}

const QuotationCreateForm: React.FC<Props> = ({
  customerId,
  customerName,
  value,
  onChange,
  onValidityChange,
  readOnly = false,
  customerTemplateHint,
  costingTemplateHint,
  lockedCategoryId,
}) => {
  const [categories, setCategories] = useState<ProductCategory[]>([]);
  const [matchResult, setMatchResult] = useState<MatchResult | null>(null);
  const [matching, setMatching] = useState(false);
  const [costingTemplates, setCostingTemplates] = useState<CostingCardTemplate[]>([]);
  const [loadingCosting, setLoadingCosting] = useState(false);

  // 初始化：默认报价单名称
  useEffect(() => {
    if (customerName && !value.name) {
      onChange({ ...value, name: `${customerName} 报价单` });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [customerName]);

  // 拉取产品分类列表（仅用于把 categoryId 显示成分类名；task-0712 起分类不再前端手选/自动预选，
  // 一律由父组件按客户绑定值通过 lockedCategoryId 带出）
  useEffect(() => {
    productCategoryService.list('ACTIVE')
      .then((res) => {
        const list: ProductCategory[] = res.data || [];
        setCategories(list);
      })
      .catch(() => setCategories([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // task-0712: 产品分类由客户绑定带出并锁定 —— lockedCategoryId 变化时同步进 categoryId。
  // readOnly(编辑已有报价单)时不生效：已有单的分类要从"已存模板反查"(见上面的 effect)得到，
  // 不能被客户的*当前*绑定值覆盖(D4：客户改绑分类不追溯已有报价单)。
  useEffect(() => {
    if (!readOnly && lockedCategoryId && value.categoryId !== lockedCategoryId) {
      onChange({ ...value, categoryId: lockedCategoryId });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lockedCategoryId, readOnly]);

  // 编辑态回填产品分类:quotation 表头不持久化 category_id(实体/DTO 均无该列),
  // 但 customer_template_id 已存,且模板本身带 categoryId(模板就是按 category 匹配出来的)→
  // 从已存模板反查把分类显示回来。否则只读分类下拉空白 + 红色"请选择产品分类",看着像"分类丢了"。
  // readOnly 守卫确保只在编辑态触发;下游 match/costing effect 在 readOnly 下不会覆盖已锁定的模板。
  useEffect(() => {
    if (!readOnly || value.categoryId || !value.customerTemplateId) return;
    let cancelled = false;
    templateService
      .getByIdCached(value.customerTemplateId)
      .then((tpl: any) => {
        // getByIdCached 解析后是响应信封,模板在 .data(与全工程 getById().data 取法一致);
        // 兼容潜在直返模板的形状,两层都试。
        const catId = (tpl?.data ?? tpl)?.categoryId;
        if (!cancelled && catId) {
          onChange({ ...value, categoryId: catId });
        }
      })
      .catch(() => { /* 反查失败则保持空,不影响编辑(校验已不强求 categoryId) */ });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [readOnly, value.customerTemplateId, value.categoryId]);

  // 分类变更后自动匹配报价模板
  useEffect(() => {
    if (!value.categoryId || !customerId) {
      setMatchResult(null);
      return;
    }
    setMatching(true);
    api.get('/templates/match-customer-quote', {
      params: { customerId, categoryId: value.categoryId },
    })
      .then((res: any) => {
        const data: MatchResult = res.data ?? res;
        setMatchResult(data);
        // 编辑态(已有报价单): 模板已锁定, 只更新 matchResult 供 Select 显示, 绝不改/清已加载的 customerTemplateId
        // (否则自动默认分类的匹配结果若不含该模板 → 误清空 → Step1 校验失败/Step2 拿不到模板)
        if (readOnly) return;
        // hotfix: loadQuotation 已带 customerTemplateId 时, 若 ID 在匹配结果里就保留,
        // 不要被 useEffect 覆盖成 undefined (MIXED 多模板场景刷新页面后报价模板丢失)
        const currentId = value.customerTemplateId;
        if (currentId && data.templates.some((t) => t.id === currentId)) {
          return;  // 保留已选, 不调 onChange 覆盖
        }
        if (data.templates.length === 1) {
          onChange({ ...value, customerTemplateId: data.templates[0].id });
        } else {
          onChange({ ...value, customerTemplateId: undefined });
        }
      })
      .catch(() => {
        setMatchResult({ matchType: 'NONE', templates: [] });
        // catch 也保留已有选择 (网络失败时不该清空)
        if (!value.customerTemplateId) {
          onChange({ ...value, customerTemplateId: undefined });
        }
      })
      .finally(() => setMatching(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value.categoryId, customerId]);

  // 分类变更后拉取核价模板
  useEffect(() => {
    if (!value.categoryId) {
      setCostingTemplates([]);
      return;
    }
    setLoadingCosting(true);
    templateService
      .list({ categoryId: value.categoryId, status: 'PUBLISHED', templateKind: 'COSTING', size: 200 })
      .then((res: any) => {
        const raw: CostingCardTemplate[] = res.data || [];
        const filtered = raw.filter(
          (t) => !t.customerId || t.customerId === customerId
        );
        filtered.sort((a, b) => {
          const aSpec = a.customerId === customerId ? 0 : 1;
          const bSpec = b.customerId === customerId ? 0 : 1;
          return aSpec - bSpec;
        });
        setCostingTemplates(filtered);
        // 编辑态: 核价模板已锁定, 不重选/不覆盖
        if (readOnly) return;
        // hotfix: loadQuotation 已带 costingTemplateId 时, 若 ID 在筛选结果里就保留,
        // 否则才默认选第一个 (同 customerTemplateId 修法对称)
        const currentId = value.costingTemplateId;
        if (currentId && filtered.some((t) => t.id === currentId)) {
          return;  // 保留已选
        }
        onChange({ ...value, costingTemplateId: filtered[0]?.id });
      })
      .catch(() => {
        setCostingTemplates([]);
        if (!value.costingTemplateId) {
          onChange({ ...value, costingTemplateId: undefined });
        }
      })
      .finally(() => setLoadingCosting(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value.categoryId, customerId]);

  // 通知父组件表单合法性
  useEffect(() => {
    // 编辑态(已有报价单): 产品分类不持久化(quotation 表无 category_id), 模板已锁定 →
    // 校验只需 name + customerTemplateId, 不再强求 categoryId(否则编辑已有单 Step1 "下一步"永久禁用)。
    const isValid = !!(value.name?.trim() && value.customerTemplateId && (readOnly || value.categoryId));
    onValidityChange?.(isValid);
  }, [value.name, value.categoryId, value.customerTemplateId, onValidityChange, readOnly]);

  // 渲染模板匹配状态提示
  const renderMatchHint = () => {
    if (!value.categoryId) return null;
    if (matching) return <Spin size="small" tip="匹配模板中…" />;
    if (!matchResult) return null;
    if (matchResult.matchType === 'NONE') {
      return (
        <Alert
          type="warning"
          showIcon
          message="未找到适用的报价模板"
          description="该客户在此产品分类下没有客户专属模板，系统中也没有通用模板。建议先去「模板配置」配置一个模板。"
          style={{ marginBottom: 12 }}
        />
      );
    }
    // 2026-05-14: 新增 MIXED 分支 — 客户专属 + 通用同时存在
    if (matchResult.matchType === 'MIXED') {
      const specificCount = matchResult.templates.filter((t) => t.customerId === customerId).length;
      const generalCount = matchResult.templates.length - specificCount;
      return (
        <Alert
          type="success"
          showIcon
          message={`客户专属 ${specificCount} 个 + 通用 ${generalCount} 个可选`}
          description="客户专属模板已为该客户定制,优先推荐使用;通用模板作为补充选择。"
          style={{ marginBottom: 12 }}
        />
      );
    }
    const isFallback = matchResult.matchType === 'GENERAL_FALLBACK';
    return (
      <Alert
        type={isFallback ? 'info' : 'success'}
        showIcon
        message={isFallback ? '使用通用模板(无客户专属)' : '已匹配客户专属模板'}
        description={
          isFallback
            ? '该客户在此分类下未配置专属模板，系统将使用通用模板。'
            : undefined
        }
        style={{ marginBottom: 12 }}
      />
    );
  };

  return (
    <Form layout="vertical">
      {/* 客户（只读展示） */}
      <Form.Item label="客户">
        <Input value={customerName} disabled />
      </Form.Item>

      {/* 报价单名称 */}
      <Form.Item
        label="报价单名称"
        required
        validateStatus={!value.name?.trim() ? 'error' : ''}
        help={!value.name?.trim() ? '请填写报价单名称' : undefined}
      >
        <Input
          value={value.name}
          onChange={(e) => onChange({ ...value, name: e.target.value })}
          placeholder="请填写报价单名称"
          maxLength={100}
          showCount
        />
      </Form.Item>

      {/* 报价单已生成提示 (readOnly=true 时) */}
      {readOnly && (
        <Alert
          type="info"
          showIcon
          message="报价单已生成 — 客户、产品分类、报价模板、核价模板 已锁定,不可修改"
          description="如需更换,请新建报价单。联系人、报价单名称等仍可编辑。"
          style={{ marginBottom: 12 }}
        />
      )}

      {/* 产品分类 — task-0712: 由客户绑定带出，lockedCategoryId 存在时只读展示，不可手选 */}
      <Form.Item
        label="产品分类"
        required
        tooltip={lockedCategoryId
          ? '产品分类由客户绑定决定，如需变更请到客户管理修改客户所属产品分类'
          : '选择后系统自动匹配「客户专属模板」(如有) → 「通用模板」(兜底)'}
        validateStatus={!value.categoryId ? 'error' : ''}
        help={!value.categoryId ? '请选择产品分类' : undefined}
      >
        <Select
          options={categories.map((c) => ({ value: c.id, label: c.name }))}
          placeholder="请选择产品分类"
          value={value.categoryId}
          onChange={lockedCategoryId ? undefined : (v) => onChange({ ...value, categoryId: v })}
          allowClear={!lockedCategoryId}
          showSearch={!lockedCategoryId}
          optionFilterProp="label"
          disabled={readOnly || !!lockedCategoryId}
        />
      </Form.Item>

      {renderMatchHint()}

      {/* 报价模板 — 2026-05-14: 按 customerId 给每个 option 加 Tag 区分客户专属 / 通用 */}
      {matchResult && matchResult.templates.length > 0 && (
        <Form.Item
          required
          label={
            <span>
              报价模板{' '}
              {matchResult.matchType === 'CUSTOMER_SPECIFIC' && <Tag color="purple">客户专属</Tag>}
              {matchResult.matchType === 'GENERAL_FALLBACK' && <Tag color="cyan">通用兜底</Tag>}
              {matchResult.matchType === 'MIXED' && <Tag color="blue">客户专属 + 通用 可选</Tag>}
              {!readOnly && customerTemplateHint && (
                <span style={{ marginLeft: 8, fontWeight: 400, fontSize: 12, color: '#999' }}>
                  {customerTemplateHint}
                </span>
              )}
            </span>
          }
          validateStatus={!value.customerTemplateId ? 'error' : ''}
          help={!value.customerTemplateId ? '请选择报价模板' : undefined}
          tooltip={matchResult.templates.length > 1 ? '已按"客户专属优先 → 通用"排序,请选择一个' : undefined}
        >
          <Select
            value={value.customerTemplateId}
            onChange={(v) => onChange({ ...value, customerTemplateId: v })}
            options={matchResult.templates.map((t) => {
              const isSpecific = !!t.customerId && t.customerId === customerId;
              const searchText = `${t.name}${t.version ? ' ' + t.version : ''}`;
              return {
                value: t.id,
                searchText,
                label: (
                  <span>
                    {t.name}{t.version ? ' ' + t.version : ''}{' '}
                    {isSpecific
                      ? <Tag color="purple" style={{ marginLeft: 4 }}>客户专属</Tag>
                      : <Tag color="cyan" style={{ marginLeft: 4 }}>通用</Tag>}
                  </span>
                ),
              };
            })}
            placeholder="请选择模板"
            optionLabelProp="label"
            disabled={readOnly}
            showSearch
            filterOption={(input, option) => {
              const txt = (option as { searchText?: string } | null)?.searchText ?? '';
              return txt.toLowerCase().includes(input.toLowerCase());
            }}
          />
        </Form.Item>
      )}

      {/* 核价模板 */}
      {value.categoryId && (
        <Form.Item
          label={
            <span>
              核价模板{' '}
              {(() => {
                const cur = costingTemplates.find((t) => t.id === value.costingTemplateId);
                if (!cur) return null;
                return cur.customerId === customerId
                  ? <Tag color="purple">客户专属</Tag>
                  : <Tag color="cyan">通用兜底</Tag>;
              })()}
              {!readOnly && costingTemplateHint && (
                <span style={{ marginLeft: 8, fontWeight: 400, fontSize: 12, color: '#999' }}>
                  {costingTemplateHint}
                </span>
              )}
            </span>
          }
          tooltip="模板配置中模板类型=核价模板、状态=已发布、分类匹配；customer_id 留空对所有客户可用"
        >
          {loadingCosting ? (
            <Spin size="small" />
          ) : costingTemplates.length === 0 ? (
            <Alert
              type="warning"
              showIcon
              message="未匹配到已发布的核价模板"
              description="请前往「模板配置」配置核价模板。当前留空，报价单仍可创建。"
              style={{ marginBottom: 0 }}
            />
          ) : (
            <Select
              value={value.costingTemplateId}
              onChange={(v) => onChange({ ...value, costingTemplateId: v })}
              allowClear
              options={costingTemplates.map((t) => ({
                value: t.id,
                label: `${t.name}${t.version ? ' ' + t.version : ''}${
                  t.customerId === customerId ? ' (客户专属)' : !t.customerId ? ' (通用)' : ''
                }`,
              }))}
              placeholder="请选择核价模板"
              disabled={readOnly}
            />
          )}
        </Form.Item>
      )}
    </Form>
  );
};

export default QuotationCreateForm;
