/**
 * BasicDataImportV5ToQuotation
 *
 * 报价单页面专用包装层：
 *  1. 内嵌 BasicDataImportV5Wizard，走完 V5 基础数据导入流程
 *  2. 导入 DONE 后，打开"创建报价单"Drawer（置于最右层）
 *  3. 用户填写报价单名称 → 调 quotationService.create → 跳转至报价单编辑页
 *
 * V5 端点：
 *  POST /api/cpq/import/basic-data/v5/preview
 *  POST /api/cpq/import/basic-data/v5/confirm
 *
 * 报价单端点：
 *  POST /api/cpq/quotations
 */
import React, { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Drawer,
  Form,
  Input,
  Select,
  Tag,
  message,
  Result,
  Spin,
} from 'antd';
import { useNavigate } from 'react-router-dom';
import BasicDataImportV5Wizard from './BasicDataImportV5Wizard';
import { customerService } from '../../services/customerService';
import { quotationService } from '../../services/quotationService';
import { productCategoryService, type ProductCategory } from '../../services/productCategoryService';
import { templateService } from '../../services/templateService';
import api from '../../services/api';

// 「核价模板」（V72 起）改为来自 template 表里 template_kind='COSTING' 的模板
interface CostingCardTemplate {
  id: string;
  name: string;
  version?: string;
  status?: string;
  templateKind?: string;
  customerId?: string | null;
  customerName?: string | null;
}

type MatchType = 'CUSTOMER_SPECIFIC' | 'GENERAL_FALLBACK' | 'NONE';
interface MatchedTemplate { id: string; name: string; version?: string; categoryName?: string; customerName?: string }
interface MatchResult { matchType: MatchType; templates: MatchedTemplate[] }

// ────────────────────────────────────────────────────────────────────────────
// Types
// ────────────────────────────────────────────────────────────────────────────

interface Customer {
  id: string;
  name: string;
}

interface Props {
  open: boolean;
  onClose: () => void;
}

// ────────────────────────────────────────────────────────────────────────────
// 创建报价单 Drawer（第二阶段，V5 导入完成后叠加显示）
// ────────────────────────────────────────────────────────────────────────────

interface CreateQuotationDrawerProps {
  open: boolean;
  customerId: string;
  customerName: string;
  importRecordId: string;
  onClose: () => void;
  onCreated: (quotationId: string) => void;
}

const CreateQuotationDrawer: React.FC<CreateQuotationDrawerProps> = ({
  open,
  customerId,
  customerName,
  importRecordId,
  onClose,
  onCreated,
}) => {
  const [form] = Form.useForm();
  const [creating, setCreating] = useState(false);
  const [categories, setCategories] = useState<ProductCategory[]>([]);
  const [categoryId, setCategoryId] = useState<string | undefined>();
  const [matchResult, setMatchResult] = useState<MatchResult | null>(null);
  const [matching, setMatching] = useState(false);
  const [selectedTemplateId, setSelectedTemplateId] = useState<string | undefined>();
  // 核价模板：V72 改为查 template 表（templateKind='COSTING' + categoryId + status='PUBLISHED'）
  // 客户匹配优先级：customerId 命中 > customer_id 留空（通用兜底） > 仅按分类
  const [costingTemplates, setCostingTemplates] = useState<CostingCardTemplate[]>([]);
  const [selectedCostingTemplateId, setSelectedCostingTemplateId] = useState<string | undefined>();
  const [loadingCosting, setLoadingCosting] = useState(false);

  useEffect(() => {
    if (open) {
      form.setFieldsValue({ name: `${customerName} 报价单` });
      setCategoryId(undefined);
      setMatchResult(null);
      setSelectedTemplateId(undefined);
      productCategoryService.list('ACTIVE')
        .then((res) => {
          const list = res.data || [];
          setCategories(list);
          // 默认选中名为"默认分类"的产品分类
          const defaultCat = list.find((c: ProductCategory) => c.name === '默认分类');
          if (defaultCat) setCategoryId(defaultCat.id);
        })
        .catch(() => setCategories([]));
    }
  }, [open, customerName, form]);

  // 选择分类后自动调 match API
  useEffect(() => {
    if (!categoryId || !customerId) {
      setMatchResult(null);
      setSelectedTemplateId(undefined);
      return;
    }
    setMatching(true);
    api.get('/templates/match-customer-quote', { params: { customerId, categoryId } })
      .then((res: any) => {
        const data: MatchResult = res.data ?? res;
        setMatchResult(data);
        // 单个匹配 → 自动选中
        if (data.templates.length === 1) {
          setSelectedTemplateId(data.templates[0].id);
        } else {
          setSelectedTemplateId(undefined);
        }
      })
      .catch(() => {
        setMatchResult({ matchType: 'NONE', templates: [] });
        setSelectedTemplateId(undefined);
      })
      .finally(() => setMatching(false));
  }, [categoryId, customerId]);

  // 选择分类后拉「核价模板」列表 —— V72 起来自 template 表（templateKind='COSTING'）。
  // 不直接传 customerId（避免后端 = 严格相等过滤掉 customer_id IS NULL 的通用模板），
  // 拿全量后在前端按 (客户专属 → 通用兜底) 排序，默认选第一条。
  useEffect(() => {
    if (!categoryId) {
      setCostingTemplates([]);
      setSelectedCostingTemplateId(undefined);
      return;
    }
    setLoadingCosting(true);
    templateService
      .list({
        categoryId,
        status: 'PUBLISHED',
        templateKind: 'COSTING',
        size: 200,
      })
      .then((res: any) => {
        const raw: CostingCardTemplate[] = res.data || [];
        // 仅保留：通用模板（customerId 留空） 或 命中当前客户 的模板
        const filtered = raw.filter(
          (t) => !t.customerId || t.customerId === customerId
        );
        // 客户专属优先排在前
        filtered.sort((a, b) => {
          const aSpec = a.customerId === customerId ? 0 : 1;
          const bSpec = b.customerId === customerId ? 0 : 1;
          return aSpec - bSpec;
        });
        setCostingTemplates(filtered);
        setSelectedCostingTemplateId(filtered[0]?.id);
      })
      .catch(() => {
        setCostingTemplates([]);
        setSelectedCostingTemplateId(undefined);
      })
      .finally(() => setLoadingCosting(false));
  }, [categoryId, customerId]);

  const handleCreate = async () => {
    try {
      const values = await form.validateFields();
      if (!selectedTemplateId) {
        message.warning('请先选择客户报价模板');
        return;
      }
      setCreating(true);
      const res = await quotationService.create({
        customerId,
        name: values.name,
        customerTemplateId: selectedTemplateId,
        // 双模板体系：把核价模板一并送给后端，create 时同步生成空的 costing_sheet
        costingTemplateId: selectedCostingTemplateId,
      });
      const newId: string = res.data?.id ?? res.id;
      message.success('报价单已创建，正在跳转…');
      onCreated(newId);
    } catch (err: any) {
      if (err.errorFields) return; // 表单校验失败，不弹错误
      message.error(err?.message ?? '创建报价单失败，请重试');
    } finally {
      setCreating(false);
    }
  };

  // 渲染模板匹配状态
  const renderMatchHint = () => {
    if (!categoryId) return null;
    if (matching) return <Spin size="small" tip="匹配模板中…" />;
    if (!matchResult) return null;
    if (matchResult.matchType === 'NONE') {
      return (
        <Alert
          type="warning"
          showIcon
          message="未找到适用的报价模板"
          description={`该客户在此产品分类下没有客户专属模板,系统中也没有通用模板。建议先去「模板配置」配置一个模板,或继续创建报价单后在 Step2 手工选择。`}
          style={{ marginBottom: 12 }}
        />
      );
    }
    const isFallback = matchResult.matchType === 'GENERAL_FALLBACK';
    return (
      <Alert
        type={isFallback ? 'info' : 'success'}
        showIcon
        message={
          isFallback
            ? '使用通用模板(无客户专属)'
            : '已匹配客户专属模板'
        }
        description={
          isFallback
            ? '该客户在此分类下未配置专属模板,系统将使用通用模板。后续可在「模板配置」为该客户创建专属版本。'
            : undefined
        }
        style={{ marginBottom: 12 }}
      />
    );
  };


  return (
    <Drawer
      title="创建报价单"
      placement="right"
      width={480}
      open={open}
      onClose={onClose}
      destroyOnClose
      maskClosable={false}
      keyboard={false}
      footer={
        <div style={{ textAlign: 'right' }}>
          <Button style={{ marginRight: 8 }} onClick={onClose} disabled={creating}>
            稍后创建
          </Button>
          <Button
            type="primary"
            loading={creating}
            disabled={!selectedTemplateId}
            onClick={handleCreate}
          >
            确认创建
          </Button>
        </div>
      }
    >
      <Result
        status="success"
        title="基础数据已成功导入"
        subTitle={
          importRecordId
            ? `导入记录 ID：${importRecordId}，数据已写入系统。`
            : '数据已成功写入系统。'
        }
        style={{ paddingBottom: 8 }}
      />

      <Form form={form} layout="vertical" style={{ marginTop: 8 }}>
        <Form.Item label="客户">
          <Input value={customerName} disabled />
        </Form.Item>
        <Form.Item
          label="报价单名称"
          name="name"
          rules={[{ required: true, message: '请填写报价单名称' }]}
        >
          <Input placeholder="请填写报价单名称" maxLength={100} showCount />
        </Form.Item>
        <Form.Item
          label="产品分类"
          required
          tooltip="选择后系统自动匹配「客户专属模板」(如有)→ 「通用模板」(兜底)"
        >
          <Select
            options={categories.map(c => ({ value: c.id, label: c.name }))}
            placeholder="请选择产品分类"
            value={categoryId}
            onChange={setCategoryId}
            allowClear
            showSearch
            optionFilterProp="label"
          />
        </Form.Item>

        {renderMatchHint()}

        {matchResult && matchResult.templates.length > 0 && (
          <Form.Item
            required
            label={
              <span>
                报价模板{' '}
                {matchResult.matchType === 'CUSTOMER_SPECIFIC'
                  ? <Tag color="purple">客户专属</Tag>
                  : <Tag color="cyan">通用兜底</Tag>}
              </span>
            }
            tooltip={matchResult.templates.length > 1 ? '匹配到多个版本,请选择一个' : undefined}
          >
            <Select
              value={selectedTemplateId}
              onChange={setSelectedTemplateId}
              options={matchResult.templates.map(t => ({
                value: t.id,
                label: `${t.name}${t.version ? ' ' + t.version : ''}`,
              }))}
              placeholder="请选择模板"
            />
          </Form.Item>
        )}

        {categoryId && (
          <Form.Item
            label={
              <span>
                核价模板{' '}
                {(() => {
                  const cur = costingTemplates.find(t => t.id === selectedCostingTemplateId);
                  if (!cur) return null;
                  return cur.customerId === customerId
                    ? <Tag color="purple">客户专属</Tag>
                    : <Tag color="cyan">通用兜底</Tag>;
                })()}
              </span>
            }
            tooltip="数据来源：「模板配置」中模板类型=核价模板、状态=已发布、分类匹配的模板；customer_id 留空表示对所有客户可用"
          >
            {loadingCosting ? (
              <Spin size="small" />
            ) : costingTemplates.length === 0 ? (
              <Alert
                type="warning"
                showIcon
                message="未匹配到已发布的核价模板"
                description="请前往「模板配置」菜单：模板类型=核价模板、产品分类=当前分类、状态=已发布；customer_id 留空可对所有客户生效。当前留空，报价单仍可创建。"
                style={{ marginBottom: 0 }}
              />
            ) : (
              <Select
                value={selectedCostingTemplateId}
                onChange={setSelectedCostingTemplateId}
                allowClear
                options={costingTemplates.map(t => ({
                  value: t.id,
                  label: `${t.name}${t.version ? ' ' + t.version : ''}${
                    t.customerId === customerId
                      ? ' (客户专属)'
                      : !t.customerId ? ' (通用)' : ''
                  }`,
                }))}
                placeholder="请选择核价模板"
              />
            )}
          </Form.Item>
        )}
      </Form>
    </Drawer>
  );
};

// ────────────────────────────────────────────────────────────────────────────
// 主组件
// ────────────────────────────────────────────────────────────────────────────

const BasicDataImportV5ToQuotation: React.FC<Props> = ({ open, onClose }) => {
  const navigate = useNavigate();
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [customersLoading, setCustomersLoading] = useState(false);

  // 第二阶段状态
  const [createDrawerOpen, setCreateDrawerOpen] = useState(false);
  const [doneImportRecordId, setDoneImportRecordId] = useState('');
  const [doneCustomerId, setDoneCustomerId] = useState('');

  // 每次打开时拉取客户列表
  useEffect(() => {
    if (!open) return;
    setCustomersLoading(true);
    setCreateDrawerOpen(false);
    setDoneImportRecordId('');
    setDoneCustomerId('');
    customerService
      .list({ page: 0, size: 200 })
      .then((r: any) => {
        const list: any[] = r.data?.content ?? r.data ?? [];
        setCustomers(list.map((c: any) => ({ id: c.id, name: c.name })));
      })
      .catch(() => {
        message.error('加载客户列表失败');
      })
      .finally(() => setCustomersLoading(false));
  }, [open]);

  // V5 Wizard DONE 回调：importRecordId + customerId（已扩展签名）
  const handleWizardSuccess = (importRecordId: string, customerId: string) => {
    setDoneImportRecordId(importRecordId);
    setDoneCustomerId(customerId);
    setCreateDrawerOpen(true);
  };

  const doneCustomer = customers.find((c) => c.id === doneCustomerId);

  const handleQuotationCreated = (quotationId: string) => {
    setCreateDrawerOpen(false);
    onClose();
    // ?autoPopulate=1 让 Step2 自动按这次导入涉及的料号 + 已绑定模板填充产品行
    // importRecordId 让后端按 import_record_id 精确过滤,排除该客户历史 mat_part
    const qs = new URLSearchParams({ autoPopulate: '1' });
    if (doneImportRecordId) qs.set('importRecordId', doneImportRecordId);
    navigate(`/quotations/${quotationId}/edit?${qs.toString()}`);
  };

  if (customersLoading && open) {
    // 在 Wizard Drawer 还没打开时短暂显示加载中状态
    return (
      <Drawer
        title="从基础数据导入（V5）"
        placement="right"
        width={720}
        open={open}
        onClose={onClose}
      >
        <div style={{ textAlign: 'center', padding: '80px 0' }}>
          <Spin size="large" tip="加载客户列表…" />
        </div>
      </Drawer>
    );
  }

  return (
    <>
      {/* 第一阶段：V5 基础数据导入向导 */}
      <BasicDataImportV5Wizard
        open={open}
        customers={customers}
        onClose={onClose}
        onSuccess={handleWizardSuccess}
      />

      {/* 第二阶段：导入成功后创建报价单 */}
      <CreateQuotationDrawer
        open={createDrawerOpen}
        customerId={doneCustomer?.id ?? doneCustomerId}
        customerName={doneCustomer?.name ?? ''}
        importRecordId={doneImportRecordId}
        onClose={() => setCreateDrawerOpen(false)}
        onCreated={handleQuotationCreated}
      />
    </>
  );
};

export default BasicDataImportV5ToQuotation;
