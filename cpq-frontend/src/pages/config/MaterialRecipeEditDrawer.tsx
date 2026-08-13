import React, { useEffect, useMemo, useState } from 'react';
import {
  Drawer, Form, Input, Select, InputNumber, Switch, Button,
  Space, Table, Tabs, Empty, Alert, message, Tag,
} from 'antd';
import { PlusOutlined, DeleteOutlined, AppstoreOutlined, HistoryOutlined } from '@ant-design/icons';
import {
  materialRecipeService,
  type MaterialRecipeDetail,
  type MaterialRecipeUpsertRequest,
} from '../../services/materialRecipeService';
import { elementService, type ElementItem } from '../../services/elementService';
import Decimal from 'decimal.js';
import {
  formatDisplayDecimal,
  normalizeDecimalString,
  sumDecimal,
  toDecimal,
  type DecimalString,
} from '../../utils/precision';
// 关联料号 Tab 本期隐藏(task-0708)：MaterialRecipePartsTab 组件保留不删，仅不挂载

// task-0812：元素下拉 filterOption —— 对 elementNo / elementCode / elementName 三字段做不区分大小写包含匹配（FR-3）
interface ElementOption {
  value: string;
  label: string;
  disabled: boolean;
  elementNo: string;
  elementCode: string;
  elementName: string;
}
const filterElementOption = (input: string, option?: ElementOption): boolean => {
  if (!option) return false;
  const kw = input.trim().toLowerCase();
  if (!kw) return true;
  return (
    option.elementNo.toLowerCase().includes(kw) ||
    option.elementCode.toLowerCase().includes(kw) ||
    option.elementName.toLowerCase().includes(kw)
  );
};

interface Props {
  open: boolean;
  editingDetail: MaterialRecipeDetail | null;
  onClose: () => void;
  onSaved: () => void;
  /** 父页(MaterialRecipeManagement)的刷新回调,绑定/解绑料号后联动刷新外层 boundPartsCount 列 */
  onPartsChanged?: () => void;
}

interface ElementRow {
  elementNo: string | null;   // task-0812：Select 的 value（稳定标识，不用 elementCode/下标）
  elementCode: string;
  elementName: string;
  unmatched?: boolean;        // task-0812：老数据 elementCode 在字典中未命中（FR-7 第三分支）
  defaultPct: DecimalString;
  minPct: DecimalString | null;
  maxPct: DecimalString | null;
  isLocked: boolean;
  sortOrder: number;
}

const MaterialRecipeEditDrawer: React.FC<Props> = ({ open, editingDetail, onClose, onSaved }) => {
  const [form] = Form.useForm();
  const [recipeType, setRecipeType] = useState<'locked' | 'editable' | 'partial'>('locked');
  const [elements, setElements] = useState<ElementRow[]>([]);
  const [saving, setSaving] = useState(false);
  const [activeTab, setActiveTab] = useState<'detail' | 'log'>('detail');

  // task-0812：元素字典（D11 抽屉打开时一次性全量拉取，前端本地过滤）
  const [elementDict, setElementDict] = useState<ElementItem[]>([]);
  const [dictLoading, setDictLoading] = useState(false);
  const [dictError, setDictError] = useState(false);

  const isCreating = !editingDetail;

  useEffect(() => {
    if (open) setActiveTab('detail');
  }, [open]);

  // task-0812：字典加载（AC-10 恰好 1 次；destroyOnClose 已保证关闭即卸载，不做跨抽屉缓存）
  useEffect(() => {
    if (!open) return;
    setDictLoading(true);
    setDictError(false);
    elementService.list()
      .then(setElementDict)
      .catch(() => {
        setDictError(true);
        message.error('元素字典加载失败，请刷新重试');
      })
      .finally(() => setDictLoading(false));
  }, [open]);

  const byNo = useMemo(() => new Map(elementDict.map(e => [e.elementNo, e])), [elementDict]);
  const byCode = useMemo(() => new Map(elementDict.map(e => [e.elementCode, e])), [elementDict]);

  // 表单基础字段（与字典无关，独立于元素行回显）
  useEffect(() => {
    if (!open) return;
    if (editingDetail) {
      form.setFieldsValue({
        code: editingDetail.code,
        symbol: editingDetail.symbol,
        name: editingDetail.name,
        recipeType: editingDetail.recipeType,
        sortOrder: editingDetail.sortOrder,
        status: editingDetail.status ?? 'ACTIVE',
      });
      setRecipeType(editingDetail.recipeType);
    } else {
      form.resetFields();
      // task-0708：新建默认「标准锁定」，元素全 isLocked、无 min/max
      form.setFieldsValue({ recipeType: 'locked', sortOrder: 100, status: 'ACTIVE' });
      setRecipeType('locked');
    }
  }, [open, editingDetail, form]);

  // task-0812：元素行回显 —— 必须等字典就绪（dictLoading=false）才能做「字典外脏值」判定（FR-7），
  // 否则字典未到时会把所有行误判为 unmatched。
  useEffect(() => {
    if (!open) return;
    if (editingDetail) {
      if (dictLoading) return;
      setElements(editingDetail.elements.map(e => {
        const hit = byCode.get(e.elementCode);
        if (hit) {
          // D8：符号/中文名完全跟随字典当前值，不回显历史手填值
          return {
            elementNo: hit.elementNo,
            elementCode: hit.elementCode,
            elementName: hit.elementName,
            unmatched: false,
            defaultPct: normalizeDecimalString(e.defaultPct),
            minPct: e.minPct == null ? null : normalizeDecimalString(e.minPct),
            maxPct: e.maxPct == null ? null : normalizeDecimalString(e.maxPct),
            isLocked: e.isLocked,
            sortOrder: e.sortOrder,
          };
        }
        return {
          elementNo: null,
          elementCode: e.elementCode,   // 保留原文，供红字提示「原值「X」」展示
          elementName: e.elementName,
          unmatched: true,
          defaultPct: normalizeDecimalString(e.defaultPct),
          minPct: e.minPct == null ? null : normalizeDecimalString(e.minPct),
          maxPct: e.maxPct == null ? null : normalizeDecimalString(e.maxPct),
          isLocked: e.isLocked,
          sortOrder: e.sortOrder,
        };
      }));
    } else {
      setElements([{
        elementNo: null, elementCode: '', elementName: '', unmatched: false,
        defaultPct: '100', minPct: null, maxPct: null,
        isLocked: true, sortOrder: 1,
      }]);
    }
  }, [open, editingDetail, dictLoading, byCode]);

  const onRecipeTypeChange = (t: 'locked' | 'editable' | 'partial') => {
    setRecipeType(t);
    setElements(prev => prev.map(e => {
      if (t === 'locked') return { ...e, isLocked: true, minPct: null, maxPct: null };
      if (t === 'editable') return {
        ...e,
        isLocked: false,
        minPct: e.minPct ?? normalizeDecimalString(Decimal.max('0', toDecimal(e.defaultPct).minus('10'))),
        maxPct: e.maxPct ?? normalizeDecimalString(Decimal.min('100', toDecimal(e.defaultPct).plus('10'))),
      };
      return e;
    }));
  };

  const addElement = () => setElements(prev => [...prev, {
    elementNo: null,
    elementCode: '',
    elementName: '',
    unmatched: false,
    defaultPct: '0',
    minPct: recipeType === 'editable' ? '0' : null,
    maxPct: recipeType === 'editable' ? '100' : null,
    isLocked: recipeType === 'locked',
    sortOrder: prev.length + 1,
  }]);

  const removeElement = (i: number) => setElements(prev => prev.filter((_, idx) => idx !== i));

  const updateElement = (i: number, patch: Partial<ElementRow>) => {
    setElements(prev => prev.map((e, idx) => idx === i ? { ...e, ...patch } : e));
  };

  // task-0812：下拉选中后按 elementNo 查字典写回 elementCode/elementName（FR-8）
  const handleElementChange = (i: number, no: string) => {
    const hit = byNo.get(no);
    if (!hit) return;
    updateElement(i, { elementNo: hit.elementNo, elementCode: hit.elementCode, elementName: hit.elementName, unmatched: false });
  };

  const sumPct = sumDecimal(elements.map(e => e.defaultPct));
  const sumPctText = formatDisplayDecimal(sumPct, 2);
  const sumOk = sumPct.minus('100').abs().lessThan('0.01');

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      // task-0812 FR-9：未选元素
      const emptyIdx = elements.findIndex(e => !e.elementNo);
      if (emptyIdx >= 0) {
        message.error(`请为第 ${emptyIdx + 1} 行选择元素`);
        return;
      }
      // task-0812 FR-7 / D4：字典外脏值阻断保存
      const badIdx = elements.findIndex(e => e.unmatched);
      if (badIdx >= 0) {
        message.error(`第 ${badIdx + 1} 行的元素不在元素字典中，请重新选择`);
        return;
      }
      if (!sumOk) {
        message.error(`默认含量之和必须 = 100，当前 ${sumPctText}`);
        return;
      }
      if (recipeType === 'partial') {
        for (const e of elements) {
          if (!e.isLocked && (e.minPct == null || e.maxPct == null)) {
            message.error(`部分可调时，未锁定元素必须填 min/max: ${e.elementCode}`);
            return;
          }
        }
      }

      const req: MaterialRecipeUpsertRequest = {
        code: values.code,
        symbol: values.symbol,
        // repair-1：名称可编辑，留空由后端默认=化学式(symbol)；配比仍隐藏置 null
        name: values.name?.trim() || null,
        specLabel: null,
        recipeType,
        sortOrder: values.sortOrder ?? 100,
        status: values.status ?? 'ACTIVE',
        elements: elements.map(e => ({
          elementCode: e.elementCode,
          elementName: e.elementName,
          defaultPct: e.defaultPct,
          minPct: e.isLocked ? undefined : (e.minPct ?? undefined),
          maxPct: e.isLocked ? undefined : (e.maxPct ?? undefined),
          isLocked: e.isLocked,
          sortOrder: e.sortOrder,
        })),
      };

      setSaving(true);
      if (editingDetail) {
        await materialRecipeService.update(editingDetail.id, req);
        message.success('材质已更新');
      } else {
        await materialRecipeService.create(req);
        message.success('材质已创建');
      }
      onSaved();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.response?.data?.message ?? e?.message ?? '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const elementCols = [
    {
      title: '元素',
      key: 'element',
      width: 260,
      render: (_: unknown, r: ElementRow, i: number) => {
        const selectedItem = r.elementNo ? byNo.get(r.elementNo) : undefined;
        const selectedNos = new Set(elements.map(e => e.elementNo).filter((v): v is string => !!v));
        const options: ElementOption[] = elementDict
          .filter(e => e.status === 'ACTIVE' || e.elementNo === r.elementNo)
          .map(e => ({
            value: e.elementNo,
            label: `${e.elementNo} / ${e.elementCode} / ${e.elementName}`,
            disabled: selectedNos.has(e.elementNo) && e.elementNo !== r.elementNo,
            elementNo: e.elementNo,
            elementCode: e.elementCode,
            elementName: e.elementName,
          }));
        return (
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <Select
                showSearch
                style={{ flex: 1, minWidth: 0 }}
                value={r.elementNo ?? undefined}
                placeholder="请选择元素"
                loading={dictLoading}
                status={r.unmatched ? 'error' : undefined}
                options={options}
                filterOption={filterElementOption as any}
                notFoundContent={dictError ? '元素字典加载失败' : '未找到该元素，请先到「主数据维护 → 元素」维护后再选择'}
                onChange={(no: string) => handleElementChange(i, no)}
              />
              {selectedItem?.status === 'INACTIVE' && <Tag color="default">已停用</Tag>}
            </div>
            {r.unmatched && (
              <div style={{ color: '#ff4d4f', fontSize: 12, marginTop: 4 }}>
                原值「{r.elementCode || '(空)'}」不在元素字典中，请重新选择
              </div>
            )}
          </div>
        );
      },
    },
    {
      title: '默认 %',
      key: 'defaultPct',
      width: 100,
      render: (_: unknown, r: ElementRow, i: number) => (
        <InputNumber<string>
          stringMode
          value={r.defaultPct}
          min="0"
          max="100"
          step={0.1}
          onChange={(v) => updateElement(i, { defaultPct: normalizeDecimalString(v ?? '0') })}
        />
      ),
    },
    {
      title: '最小 %',
      key: 'minPct',
      width: 100,
      render: (_: unknown, r: ElementRow, i: number) => (
        <InputNumber<string>
          stringMode
          value={r.minPct ?? undefined}
          disabled={r.isLocked}
          min="0"
          max="100"
          step={0.1}
          onChange={(v) => updateElement(i, { minPct: v === null ? null : normalizeDecimalString(v) })}
        />
      ),
    },
    {
      title: '最大 %',
      key: 'maxPct',
      width: 100,
      render: (_: unknown, r: ElementRow, i: number) => (
        <InputNumber<string>
          stringMode
          value={r.maxPct ?? undefined}
          disabled={r.isLocked}
          min="0"
          max="100"
          step={0.1}
          onChange={(v) => updateElement(i, { maxPct: v === null ? null : normalizeDecimalString(v) })}
        />
      ),
    },
    {
      title: '锁定',
      key: 'isLocked',
      width: 80,
      render: (_: unknown, r: ElementRow, i: number) => (
        <Switch
          checked={r.isLocked}
          disabled={recipeType === 'locked' || recipeType === 'editable'}
          onChange={(v) => updateElement(i, {
            isLocked: v,
            minPct: v ? null : (r.minPct ?? '0'),
            maxPct: v ? null : (r.maxPct ?? '100'),
          })}
        />
      ),
    },
    {
      title: '操作',
      key: 'op',
      width: 60,
      render: (_: unknown, _r: ElementRow, i: number) => (
        <Button type="text" danger icon={<DeleteOutlined />} onClick={() => removeElement(i)} />
      ),
    },
  ];

  const detailTab = (
    <div>
      <Form form={form} layout="vertical">
        <Space size="large" wrap>
          <Form.Item name="code" label="材质编号" rules={[{ required: true, message: '请填写材质编号' }]}>
            <Input placeholder="00300" style={{ width: 160 }} disabled={!!editingDetail} />
          </Form.Item>
          <Form.Item name="symbol" label="化学式" rules={[{ required: true, message: '请填写化学式' }]}>
            <Input placeholder="Ag / AgC3" style={{ width: 160 }} />
          </Form.Item>
          <Form.Item name="name" label="名称">
            <Input placeholder="留空默认=化学式" style={{ width: 180 }} />
          </Form.Item>
          <Form.Item name="recipeType" label="类型" rules={[{ required: true }]}>
            <Select
              style={{ width: 140 }}
              onChange={onRecipeTypeChange}
              options={[
                { value: 'locked',   label: '标准锁定' },
                { value: 'editable', label: '含量可调' },
                { value: 'partial',  label: '部分可调' },
              ]}
            />
          </Form.Item>
          <Form.Item name="sortOrder" label="排序">
            <InputNumber min={0} style={{ width: 100 }} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select
              style={{ width: 100 }}
              options={[
                { value: 'ACTIVE',   label: '启用' },
                { value: 'INACTIVE', label: '停用' },
              ]}
            />
          </Form.Item>
        </Space>
      </Form>

      <div style={{ marginTop: 16 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
          <b>元素组成</b>
          <Button size="small" icon={<PlusOutlined />} onClick={addElement}>
            添加元素
          </Button>
        </div>
        <Table
          rowKey={(_r, i) => String(i)}
          dataSource={elements}
          columns={elementCols as any}
          pagination={false}
          size="small"
        />
        <div style={{ marginTop: 8, color: sumOk ? '#52c41a' : '#ff7875' }}>
          默认含量之和: <b>{sumPctText}%</b> {sumOk ? '✓' : '(需 = 100)'}
        </div>
      </div>
    </div>
  );

  const tabs = [
    {
      key: 'detail',
      label: <><AppstoreOutlined /> 材质详情</>,
      children: detailTab,
    },
    // 关联料号 tab 本期隐藏(task-0708)，仅保留变更日志占位
    ...(isCreating ? [] : [
      {
        key: 'log',
        label: <><HistoryOutlined /> 变更日志</>,
        children: (
          <Empty
            description={
              <Alert
                type="info"
                showIcon
                message="变更日志接入待开发"
                description="未来接入 change_log 表展示该材质的字段级变更历史(谁、何时、改了什么)。"
                style={{ maxWidth: 480, margin: '0 auto' }}
              />
            }
          />
        ),
      },
    ]),
  ];

  return (
    <Drawer
      title={editingDetail ? `编辑材质: ${editingDetail.code}` : '新建材质'}
      open={open}
      onClose={onClose}
      width={1080}
      placement="right"
      maskClosable={false}
      destroyOnClose
      footer={
        activeTab === 'detail' ? (
          <div style={{ textAlign: 'right' }}>
            <Space>
              <Button onClick={onClose}>取消</Button>
              <Button type="primary" loading={saving} onClick={handleSubmit}>
                保存
              </Button>
            </Space>
          </div>
        ) : null
      }
    >
      <Tabs
        activeKey={activeTab}
        onChange={(k) => setActiveTab(k as 'detail' | 'log')}
        items={tabs}
      />
    </Drawer>
  );
};

export default MaterialRecipeEditDrawer;
