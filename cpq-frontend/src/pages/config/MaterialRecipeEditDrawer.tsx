import React, { useEffect, useState } from 'react';
import {
  Drawer, Form, Input, Select, InputNumber, Switch, Button,
  Space, Table, Tabs, Empty, Alert, message,
} from 'antd';
import { PlusOutlined, DeleteOutlined, AppstoreOutlined, HistoryOutlined } from '@ant-design/icons';
import {
  materialRecipeService,
  type MaterialRecipeDetail,
  type MaterialRecipeUpsertRequest,
} from '../../services/materialRecipeService';
// 关联料号 Tab 本期隐藏(task-0708)：MaterialRecipePartsTab 组件保留不删，仅不挂载

interface Props {
  open: boolean;
  editingDetail: MaterialRecipeDetail | null;
  onClose: () => void;
  onSaved: () => void;
  /** 父页(MaterialRecipeManagement)的刷新回调,绑定/解绑料号后联动刷新外层 boundPartsCount 列 */
  onPartsChanged?: () => void;
}

interface ElementRow {
  elementCode: string;
  elementName: string;
  defaultPct: number;
  minPct: number | null;
  maxPct: number | null;
  isLocked: boolean;
  sortOrder: number;
}

const MaterialRecipeEditDrawer: React.FC<Props> = ({ open, editingDetail, onClose, onSaved }) => {
  const [form] = Form.useForm();
  const [recipeType, setRecipeType] = useState<'locked' | 'editable' | 'partial'>('locked');
  const [elements, setElements] = useState<ElementRow[]>([]);
  const [saving, setSaving] = useState(false);
  const [activeTab, setActiveTab] = useState<'detail' | 'log'>('detail');

  const isCreating = !editingDetail;

  useEffect(() => {
    if (open) setActiveTab('detail');
  }, [open]);

  useEffect(() => {
    if (!open) return;
    if (editingDetail) {
      form.setFieldsValue({
        code: editingDetail.code,
        symbol: editingDetail.symbol,
        recipeType: editingDetail.recipeType,
        sortOrder: editingDetail.sortOrder,
        status: editingDetail.status ?? 'ACTIVE',
      });
      setRecipeType(editingDetail.recipeType);
      setElements(editingDetail.elements.map(e => ({
        elementCode: e.elementCode,
        elementName: e.elementName,
        defaultPct: Number(e.defaultPct),
        minPct: e.minPct == null ? null : Number(e.minPct),
        maxPct: e.maxPct == null ? null : Number(e.maxPct),
        isLocked: e.isLocked,
        sortOrder: e.sortOrder,
      })));
    } else {
      form.resetFields();
      // task-0708：新建默认「标准锁定」，元素全 isLocked、无 min/max
      form.setFieldsValue({ recipeType: 'locked', sortOrder: 100, status: 'ACTIVE' });
      setRecipeType('locked');
      setElements([{
        elementCode: '', elementName: '', defaultPct: 100, minPct: null, maxPct: null,
        isLocked: true, sortOrder: 1,
      }]);
    }
  }, [open, editingDetail, form]);

  const onRecipeTypeChange = (t: 'locked' | 'editable' | 'partial') => {
    setRecipeType(t);
    setElements(prev => prev.map(e => {
      if (t === 'locked') return { ...e, isLocked: true, minPct: null, maxPct: null };
      if (t === 'editable') return {
        ...e,
        isLocked: false,
        minPct: e.minPct ?? Math.max(0, e.defaultPct - 10),
        maxPct: e.maxPct ?? Math.min(100, e.defaultPct + 10),
      };
      return e;
    }));
  };

  const addElement = () => setElements(prev => [...prev, {
    elementCode: '',
    elementName: '',
    defaultPct: 0,
    minPct: recipeType === 'editable' ? 0 : null,
    maxPct: recipeType === 'editable' ? 100 : null,
    isLocked: recipeType === 'locked',
    sortOrder: prev.length + 1,
  }]);

  const removeElement = (i: number) => setElements(prev => prev.filter((_, idx) => idx !== i));

  const updateElement = (i: number, patch: Partial<ElementRow>) => {
    setElements(prev => prev.map((e, idx) => idx === i ? { ...e, ...patch } : e));
  };

  const sumPct = elements.reduce((acc, e) => acc + (Number(e.defaultPct) || 0), 0);
  const sumOk = Math.abs(sumPct - 100) < 0.01;

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      if (!sumOk) {
        message.error(`默认含量之和必须 = 100，当前 ${sumPct.toFixed(2)}`);
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
        // task-0708：名称/配比管理 UI 已隐藏，导入/新建统一置 null(DTO 字段保留)
        name: null,
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
      title: '元素 code',
      key: 'elementCode',
      width: 100,
      render: (_: unknown, r: ElementRow, i: number) => (
        <Input
          value={r.elementCode}
          onChange={(e) => updateElement(i, { elementCode: e.target.value })}
          placeholder="Ag/Cu/Ni"
        />
      ),
    },
    {
      title: '元素名',
      key: 'elementName',
      width: 120,
      render: (_: unknown, r: ElementRow, i: number) => (
        <Input
          value={r.elementName}
          onChange={(e) => updateElement(i, { elementName: e.target.value })}
          placeholder="银/铜/镍"
        />
      ),
    },
    {
      title: '默认 %',
      key: 'defaultPct',
      width: 100,
      render: (_: unknown, r: ElementRow, i: number) => (
        <InputNumber
          value={r.defaultPct}
          min={0}
          max={100}
          step={0.1}
          onChange={(v) => updateElement(i, { defaultPct: Number(v ?? 0) })}
        />
      ),
    },
    {
      title: '最小 %',
      key: 'minPct',
      width: 100,
      render: (_: unknown, r: ElementRow, i: number) => (
        <InputNumber
          value={r.minPct ?? undefined}
          disabled={r.isLocked}
          min={0}
          max={100}
          step={0.1}
          onChange={(v) => updateElement(i, { minPct: v === null ? null : Number(v) })}
        />
      ),
    },
    {
      title: '最大 %',
      key: 'maxPct',
      width: 100,
      render: (_: unknown, r: ElementRow, i: number) => (
        <InputNumber
          value={r.maxPct ?? undefined}
          disabled={r.isLocked}
          min={0}
          max={100}
          step={0.1}
          onChange={(v) => updateElement(i, { maxPct: v === null ? null : Number(v) })}
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
            minPct: v ? null : (r.minPct ?? 0),
            maxPct: v ? null : (r.maxPct ?? 100),
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
          <Form.Item name="symbol" label="材质名称" rules={[{ required: true, message: '请填写材质名称' }]}>
            <Input placeholder="Ag / AgC3" style={{ width: 160 }} />
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
          默认含量之和: <b>{sumPct.toFixed(2)}%</b> {sumOk ? '✓' : '(需 = 100)'}
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
