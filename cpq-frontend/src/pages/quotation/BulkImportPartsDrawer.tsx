/**
 * BulkImportPartsDrawer
 *
 * 报价单 Step2 — 「批量从基础数据导入产品」抽屉。
 *
 * 流程:
 *   1. 进抽屉 → 调 GET /api/cpq/quotations/customer-part-candidates 拿候选料号
 *   2. 列表显示(客户专属优先,可搜索 + 多选 + 全选)
 *   3. 用户确认 → 用 quotation.customerTemplateId 读模板 → 为每个选中料号生成 LineItem
 *   4. 回调 onConfirm 把 lineItems 数组传给父组件
 *
 * 设计依据:Phase 4(基于已绑定客户报价模板的"批量料号 + 模板展开"短路径)
 */
import React, { useEffect, useMemo, useState } from 'react';
import {
  Drawer,
  Table,
  Input,
  Tag,
  Button,
  Alert,
  message,
  Space,
  Spin,
  Empty,
  Typography,
} from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { quotationService } from '../../services/quotationService';
import { templateService } from '../../services/templateService';
import type { LineItem, ComponentDataItem, ComponentField, ComponentFormula } from './QuotationStep2';
import { genUUID } from '../../utils/uuid';

const { Text } = Typography;

interface CustomerPartCandidate {
  partNo: string;
  partName?: string;
  unitWeight?: number;
  weightUnit?: string;
  customerProductNo?: string;
  customerPartName?: string;
  customerDrawingNo?: string;
  baseCurrency?: string;
  /** 「生产料号管理」(internal_material) 视角的料号详情 */
  hfPartInfo?: {
    partNo?: string;
    partName?: string;
    specification?: string;
    sizeInfo?: string;
    statusCode?: string;
  };
  quoteCurrency?: string;
  customerSpecific: boolean;
  /** mat_customer_part_mapping.current_version — 后端 V161+ 透传, 用于初始化 LineItem.partVersionLocked */
  currentVersion?: number;
}

interface Props {
  open: boolean;
  onClose: () => void;
  customerId: string;
  /** 已绑定的客户报价模板 id;无模板时不允许进入抽屉 */
  customerTemplateId: string | undefined;
  /** 当前已加入报价单的料号集合(避免重复添加) */
  existingPartNos: string[];
  onConfirm: (lineItems: LineItem[]) => void;
}

// ─── helpers(简化版,只做必要字段映射) ──────────────────────────────────────

function parseJsonSafe<T>(v: T | string | null | undefined, fallback: T): T {
  if (v == null) return fallback;
  if (typeof v === 'string') {
    try { return JSON.parse(v) as T; } catch { return fallback; }
  }
  return v;
}

function normalizeFieldType(raw: string):
  'FIXED_VALUE' | 'DATA_SOURCE' | 'INPUT' | 'INPUT_TEXT' | 'INPUT_NUMBER' | 'FORMULA' | 'BASIC_DATA' | 'LIST_FORMULA' {
  const t = (raw || '').toUpperCase();
  if (t === 'FORMULA') return 'FORMULA';
  if (t === 'FIXED_VALUE' || t === 'FIXED') return 'FIXED_VALUE';
  if (t === 'DATA_SOURCE') return 'DATA_SOURCE';
  if (t === 'BASIC_DATA') return 'BASIC_DATA';
  if (t === 'INPUT_TEXT') return 'INPUT_TEXT';
  if (t === 'INPUT_NUMBER') return 'INPUT_NUMBER';
  if (t === 'LIST_FORMULA') return 'LIST_FORMULA';  // V203/Phase B
  return 'INPUT_TEXT';
}

function buildEmptyRow(fields: ComponentField[]): Record<string, any> {
  const row: Record<string, any> = { row_index: 0 };
  for (const f of fields) {
    if (f.field_type === 'FIXED_VALUE') {
      row[f.name] = f.content ?? '';
    } else if (f.field_type === 'FORMULA' || f.field_type === 'BASIC_DATA' || f.field_type === 'DATA_SOURCE') {
      row[f.name] = null;
    } else {
      row[f.name] = '';
    }
  }
  return row;
}

/** 把 customer-quote 模板 + 单个料号信息 → LineItem(导出供 QuotationStep2 自动展开复用) */
/**
 * 从模板的 componentsSnapshot 构建初始 componentData(每组件 1 行空 / preset_rows).
 *
 * <p>抽出此函数让"选配创建的 lineItem"(QuotationWizard.enrichComponentData) 在
 * savedCompData=[] 时复用,而不是返回空数组 → 卡片渲染无组件结构的 bug.
 *
 * @param tmpl 完整模板对象(GET /templates/{id} 返回的 data)
 */
export function buildComponentDataFromTemplate(tmpl: any): ComponentDataItem[] {
  const componentsSnapshot: any[] = parseJsonSafe(tmpl.componentsSnapshot, []);
  return componentsSnapshot.map((comp: any) => {
    const fields: ComponentField[] = (comp.fields || []).map((f: any) => ({
      name: f.name || f.key || f.fieldKey || '',
      field_type: normalizeFieldType(f.field_type || f.type || f.fieldType || ''),
      content: f.content,
      is_amount: f.is_amount,
      is_subtotal: f.is_subtotal,
      formula_name: f.formula_name,
      datasource_binding: f.datasource_binding,
      basic_data_path: f.basic_data_path,
      // V109: 全局变量徽章; V190 default_source 统一默认值来源
      global_variable_code: f.global_variable_code,
      default_source: f.default_source,
      // V203/Phase B: LIST_FORMULA 字段的配置
      list_formula_config: f.list_formula_config,
      sort_order: f.sort_order,
      // 单位换算：透传 unit_source_field，供 computeAllFormulas 换算时按同行单位归一
      unit_source_field: f.unit_source_field,
      label: f.label || f.fieldLabel || f.name || '',
      key: f.name || f.key || f.fieldKey || '',
    }));
    const formulas: ComponentFormula[] = (comp.formulas || []).map((fm: any) => ({
      name: fm.name || fm.fieldKey || fm.key || '',
      expression: Array.isArray(fm.expression) ? fm.expression : [],
      result_type: fm.result_type,
    }));
    const presetRows: any[] = comp.preset_rows || comp.presetRows || [];
    const initialRows = presetRows.length > 0
      ? presetRows.map((pr: any, ri: number) => ({
          ...buildEmptyRow(fields),
          ...pr,
          _preset: true,
          row_index: ri,
        }))
      : [buildEmptyRow(fields)];
    const rawFa = comp.formula_assignments || comp.formulaAssignments || {};
    const formulaAssignments: Record<string, string> = typeof rawFa === 'string'
      ? JSON.parse(rawFa) : rawFa;
    const compType = comp.component_type || comp.componentType || 'NORMAL';
    return {
      componentId: comp.component_id || comp.componentId || '',
      componentCode: comp.component_code || comp.componentCode || '',
      componentType: compType,
      tabName: comp.tab_name || comp.tabName || comp.name || 'Tab',
      fields,
      formulas,
      formulaAssignments,
      dataDriverPath: comp.data_driver_path || comp.dataDriverPath || undefined,
      rows: compType !== 'NORMAL' ? [] : initialRows,
      subtotal: 0,
    };
  });
}

export function buildLineItemFromTemplate(tmpl: any, part: CustomerPartCandidate): LineItem {
  const productAttrs: any[] = parseJsonSafe(tmpl.productAttributes, []);

  const productAttributes: LineItem['productAttributes'] = productAttrs.map((attr: any) => ({
    name: attr.name || attr.key || attr.fieldKey || '',
    field_type: attr.field_type || attr.fieldType || 'TEXT',
    required: attr.required ?? false,
    default_value: attr.default_value ?? attr.defaultValue,
    source: attr.source,
  }));

  const productAttributeValues: Record<string, any> = {};
  for (const attr of productAttributes) {
    if (!attr.name) continue;
    productAttributeValues[attr.name] = attr.default_value ?? '';
  }
  // PRD：产品图号属性绑定客户料号映射的 customer_drawing_no——
  // 命名约定上模板里有"图号"字段时自动用 customerDrawingNo 兜底，避免新建报价单还要用户手填一遍。
  // 用户后续若手工修改也会被 productAttributeValues 持久化覆盖。
  if (part.customerDrawingNo
      && Object.prototype.hasOwnProperty.call(productAttributeValues, '图号')
      && !productAttributeValues['图号']) {
    productAttributeValues['图号'] = part.customerDrawingNo;
  }

  const componentData: ComponentDataItem[] = buildComponentDataFromTemplate(tmpl);

  const subtotalFormula: any[] = parseJsonSafe(tmpl.subtotalFormula || tmpl.subtotal_formula, []);

  return {
    // Bug B (2026-05-20): 新建 lineItem 时生成 tempId，用于 driverExpansionKey lineItemId 维度。
    // 保证同报价单内两条相同料号的行各自独立缓存 driver 展开结果，不相互污染。
    tempId: genUUID(),
    productId: '',  // V5 流程不依赖 v3 product 表
    productName: part.partName || part.customerPartName || part.partNo,
    productPartNo: part.partNo,
    // V161+ 修复: 后端 listCandidates 透传 mapping.current_version, 这里直接写入 LineItem.
    // 避免首次从 import 跳转过来 partVersion 缺省 → ImplicitJoinRewriter 不注入版本过滤 → 多版本叠加.
    // 缺省 2000 作为兜底(新料号或 mapping 缺失时 driver 也能查到默认版本数据).
    partVersionLocked: part.currentVersion ?? 2000,
    // PRD：客户视角的产品卡片 — 候选数据里就有 customerPartName / customerProductNo / customerDrawingNo，
    // 这里直接装进 LineItem 字段，让导入后第一次进入编辑页就以"客户料号名称"为主显示。
    // 否则要等保存草稿+刷新走 loadLineItems 路径才能从 mat_customer_part_mapping 反查回来。
    customerPartName: part.customerPartName || '',
    customerProductNo: part.customerProductNo || '',
    customerDrawingNo: part.customerDrawingNo || '',
    // 生产料号管理 视角详情（卡片右侧 popover 用）—— 候选 API 已 LEFT JOIN internal_material 返回
    hfPartInfo: part.hfPartInfo,
    templateId: tmpl.id,
    templateName: tmpl.name + (tmpl.version ? ` ${tmpl.version}` : ''),
    productAttributeValues,
    productAttributes,
    componentData,
    subtotal: 0,
    subtotalFormula,
    // 导入来源标记:saveDraft 据此从该料号基础工序 seed 本行 quotation_line_process,
    // 使 [选配-工序列表] 与选配产品渲染一致(选配路径不设此标记,保持"没选工序=空")。
    seedProcessesFromBase: true,
  };
}

// ─── 主组件 ───────────────────────────────────────────────────────────────

const BulkImportPartsDrawer: React.FC<Props> = ({
  open,
  onClose,
  customerId,
  customerTemplateId,
  existingPartNos,
  onConfirm,
}) => {
  const [candidates, setCandidates] = useState<CustomerPartCandidate[]>([]);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [selectedKeys, setSelectedKeys] = useState<string[]>([]);
  const [importing, setImporting] = useState(false);

  const existingSet = useMemo(() => new Set(existingPartNos), [existingPartNos]);

  useEffect(() => {
    if (!open || !customerId) return;
    setLoading(true);
    setSelectedKeys([]);
    setSearch('');
    quotationService.listCustomerPartCandidates(customerId)
      .then((res) => setCandidates(res.data || []))
      .catch((e: any) => {
        message.error(e?.message || '加载料号候选失败');
        setCandidates([]);
      })
      .finally(() => setLoading(false));
  }, [open, customerId]);

  const filtered = useMemo(() => {
    if (!search.trim()) return candidates;
    const kw = search.trim().toLowerCase();
    return candidates.filter((c) =>
      (c.partNo && c.partNo.toLowerCase().includes(kw)) ||
      (c.partName && c.partName.toLowerCase().includes(kw)) ||
      (c.customerProductNo && c.customerProductNo.toLowerCase().includes(kw)) ||
      (c.customerPartName && c.customerPartName.toLowerCase().includes(kw))
    );
  }, [candidates, search]);

  const handleConfirm = async () => {
    if (!customerTemplateId) {
      message.error('当前报价单未绑定客户报价模板,无法批量加产品');
      return;
    }
    if (selectedKeys.length === 0) {
      message.warning('请至少选择 1 个料号');
      return;
    }
    setImporting(true);
    try {
      const tplRes = await templateService.getById(customerTemplateId);
      const tmpl = tplRes.data;
      const selectedParts = candidates.filter((c) => selectedKeys.includes(c.partNo));
      const lineItems = selectedParts.map((p) => buildLineItemFromTemplate(tmpl, p));
      onConfirm(lineItems);
      message.success(`已添加 ${lineItems.length} 个产品`);
      onClose();
    } catch (e: any) {
      message.error(e?.message || '加载模板失败');
    } finally {
      setImporting(false);
    }
  };

  const columns: ColumnsType<CustomerPartCandidate> = [
    {
      title: '料号',
      dataIndex: 'partNo',
      key: 'partNo',
      width: 140,
      render: (v: string, row) => (
        <Space size={4}>
          <Text style={{ fontFamily: 'monospace' }}>{v}</Text>
          {row.customerSpecific && <Tag color="purple" style={{ fontSize: 10, padding: '0 4px' }}>专属</Tag>}
          {existingSet.has(v) && <Tag color="default" style={{ fontSize: 10, padding: '0 4px' }}>已添加</Tag>}
        </Space>
      ),
    },
    { title: '料号名称', dataIndex: 'partName', key: 'partName', ellipsis: true },
    {
      title: '单重',
      dataIndex: 'unitWeight',
      key: 'unitWeight',
      width: 100,
      render: (v, row) => v != null ? `${v} ${row.weightUnit ?? ''}` : '—',
    },
    {
      title: '客户产品编号',
      dataIndex: 'customerProductNo',
      key: 'customerProductNo',
      width: 140,
      render: (v) => v ? <Text style={{ fontFamily: 'monospace', fontSize: 12 }}>{v}</Text> : <Text type="secondary">—</Text>,
    },
    {
      title: '客户图号',
      dataIndex: 'customerDrawingNo',
      key: 'customerDrawingNo',
      width: 120,
      render: (v) => v || <Text type="secondary">—</Text>,
    },
  ];

  return (
    <Drawer
      title="批量从基础数据导入产品"
      placement="right"
      width={1100}
      open={open}
      onClose={onClose}
      footer={
        <div style={{ textAlign: 'right' }}>
          <Button onClick={onClose} style={{ marginRight: 8 }} disabled={importing}>取消</Button>
          <Button
            type="primary"
            loading={importing}
            disabled={selectedKeys.length === 0 || !customerTemplateId}
            onClick={handleConfirm}
          >
            添加 {selectedKeys.length} 个产品
          </Button>
        </div>
      }
    >
      {!customerTemplateId && (
        <Alert
          type="warning"
          showIcon
          message="未绑定客户报价模板"
          description="该报价单创建时没有自动匹配到客户报价模板,无法批量导入。请先去「模板配置」配置一个适用模板,或用「+添加产品」按钮单独添加。"
          style={{ marginBottom: 12 }}
        />
      )}

      {customerTemplateId && (
        <Alert
          type="info"
          showIcon
          message="基于已绑定模板批量生成产品行"
          description="选中的每个料号都会按当前报价单已匹配的「客户报价模板」生成一行产品(含组件/字段/公式结构),后续可在产品卡片视图编辑。"
          style={{ marginBottom: 12 }}
        />
      )}

      <Input
        placeholder="搜索料号 / 名称 / 客户产品编号"
        prefix={<SearchOutlined />}
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        style={{ marginBottom: 12 }}
        allowClear
      />

      {loading ? (
        <div style={{ textAlign: 'center', padding: '60px 0' }}>
          <Spin size="large" tip="加载候选料号..." />
        </div>
      ) : filtered.length === 0 ? (
        <Empty description={candidates.length === 0 ? '该客户暂无关联料号(需先导入基础数据)' : '无匹配料号'} />
      ) : (
        <Table
          rowKey="partNo"
          columns={columns}
          dataSource={filtered}
          size="small"
          pagination={{ pageSize: 20, showSizeChanger: true, pageSizeOptions: ['20', '50', '100'] }}
          rowSelection={{
            selectedRowKeys: selectedKeys,
            onChange: (keys) => setSelectedKeys(keys as string[]),
            getCheckboxProps: (r) => ({ disabled: existingSet.has(r.partNo) }),
          }}
          scroll={{ x: 'max-content' }}
        />
      )}

      <div style={{ marginTop: 8, color: '#8c8c8c', fontSize: 12 }}>
        已选 {selectedKeys.length} / 共 {candidates.length} 个候选料号
        {candidates.filter((c) => c.customerSpecific).length > 0 &&
          `(其中 ${candidates.filter((c) => c.customerSpecific).length} 个客户专属)`}
      </div>
    </Drawer>
  );
};

export default BulkImportPartsDrawer;
