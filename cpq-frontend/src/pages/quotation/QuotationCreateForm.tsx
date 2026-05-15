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
}

const QuotationCreateForm: React.FC<Props> = ({
  customerId,
  customerName,
  value,
  onChange,
  onValidityChange,
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

  // 拉取产品分类列表
  useEffect(() => {
    productCategoryService.list('ACTIVE')
      .then((res) => {
        const list: ProductCategory[] = res.data || [];
        setCategories(list);
        // 默认选中"默认分类"
        if (!value.categoryId) {
          const defaultCat = list.find((c) => c.name === '默认分类');
          if (defaultCat) {
            onChange({ ...value, categoryId: defaultCat.id });
          }
        }
      })
      .catch(() => setCategories([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
        if (data.templates.length === 1) {
          onChange({ ...value, customerTemplateId: data.templates[0].id });
        } else {
          onChange({ ...value, customerTemplateId: undefined });
        }
      })
      .catch(() => {
        setMatchResult({ matchType: 'NONE', templates: [] });
        onChange({ ...value, customerTemplateId: undefined });
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
        onChange({ ...value, costingTemplateId: filtered[0]?.id });
      })
      .catch(() => {
        setCostingTemplates([]);
        onChange({ ...value, costingTemplateId: undefined });
      })
      .finally(() => setLoadingCosting(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value.categoryId, customerId]);

  // 通知父组件表单合法性
  useEffect(() => {
    const isValid = !!(value.name?.trim() && value.categoryId && value.customerTemplateId);
    onValidityChange?.(isValid);
  }, [value.name, value.categoryId, value.customerTemplateId, onValidityChange]);

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

      {/* 产品分类 */}
      <Form.Item
        label="产品分类"
        required
        tooltip="选择后系统自动匹配「客户专属模板」(如有) → 「通用模板」(兜底)"
        validateStatus={!value.categoryId ? 'error' : ''}
        help={!value.categoryId ? '请选择产品分类' : undefined}
      >
        <Select
          options={categories.map((c) => ({ value: c.id, label: c.name }))}
          placeholder="请选择产品分类"
          value={value.categoryId}
          onChange={(v) => onChange({ ...value, categoryId: v })}
          allowClear
          showSearch
          optionFilterProp="label"
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
              return {
                value: t.id,
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
            />
          )}
        </Form.Item>
      )}
    </Form>
  );
};

export default QuotationCreateForm;
