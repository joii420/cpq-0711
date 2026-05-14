import React, { useEffect, useState } from 'react';
import {
  Drawer, Form, Input, Select, InputNumber, Button, Space, Table, message,
} from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import {
  compositeProcessService,
  type CompositeProcessDef,
  type CompositeProcessParamDef,
  type CompositeProcessDefUpsertRequest,
} from '../../services/compositeProcessService';

interface Props {
  open: boolean;
  editingDetail: CompositeProcessDef | null;
  onClose: () => void;
  onSaved: () => void;
}

const emptyParam = (): CompositeProcessParamDef => ({
  id: '', label: '', unit: '', type: 'number', placeholder: '',
});

const CompositeProcessEditDrawer: React.FC<Props> = ({
  open, editingDetail, onClose, onSaved,
}) => {
  const [form] = Form.useForm();
  const [params, setParams] = useState<CompositeProcessParamDef[]>([]);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!open) return;
    if (editingDetail) {
      form.setFieldsValue({
        code:        editingDetail.code,
        name:        editingDetail.name,
        icon:        editingDetail.icon ?? '',
        description: editingDetail.description ?? '',
        sortOrder:   editingDetail.sortOrder ?? 100,
        status:      editingDetail.status ?? 'ACTIVE',
      });
      setParams(compositeProcessService.parseParamSchema(editingDetail.paramSchema));
    } else {
      form.resetFields();
      form.setFieldsValue({ sortOrder: 100, status: 'ACTIVE' });
      setParams([]);
    }
  }, [open, editingDetail, form]);

  const addParam = () => setParams((prev) => [...prev, emptyParam()]);
  const removeParam = (i: number) => setParams((prev) => prev.filter((_, idx) => idx !== i));
  const updateParam = (i: number, patch: Partial<CompositeProcessParamDef>) =>
    setParams((prev) => prev.map((p, idx) => (idx === i ? { ...p, ...patch } : p)));

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      const req: CompositeProcessDefUpsertRequest = {
        code:        values.code,
        name:        values.name,
        icon:        values.icon || undefined,
        description: values.description || undefined,
        paramSchema: params,
        sortOrder:   values.sortOrder ?? 100,
        status:      values.status ?? 'ACTIVE',
      };
      setSaving(true);
      if (editingDetail) {
        await compositeProcessService.update(editingDetail.id, req);
        message.success('组合工序已更新');
      } else {
        await compositeProcessService.create(req);
        message.success('组合工序已创建');
      }
      onSaved();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.response?.data?.message ?? e?.message ?? '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const paramCols = [
    {
      title: 'ID（英文）',
      key: 'id',
      width: 130,
      render: (_: unknown, r: CompositeProcessParamDef, i: number) => (
        <Input
          value={r.id}
          placeholder="pressure"
          onChange={(e) => updateParam(i, { id: e.target.value })}
        />
      ),
    },
    {
      title: '标签',
      key: 'label',
      width: 130,
      render: (_: unknown, r: CompositeProcessParamDef, i: number) => (
        <Input
          value={r.label}
          placeholder="铆接压力"
          onChange={(e) => updateParam(i, { label: e.target.value })}
        />
      ),
    },
    {
      title: '单位',
      key: 'unit',
      width: 90,
      render: (_: unknown, r: CompositeProcessParamDef, i: number) => (
        <Input
          value={r.unit}
          placeholder="kN"
          onChange={(e) => updateParam(i, { unit: e.target.value })}
        />
      ),
    },
    {
      title: '类型',
      key: 'type',
      width: 100,
      render: (_: unknown, r: CompositeProcessParamDef, i: number) => (
        <Select
          value={r.type}
          style={{ width: 90 }}
          onChange={(v) => updateParam(i, { type: v })}
          options={[
            { value: 'number', label: '数值' },
            { value: 'text',   label: '文本' },
          ]}
        />
      ),
    },
    {
      title: '占位符',
      key: 'placeholder',
      render: (_: unknown, r: CompositeProcessParamDef, i: number) => (
        <Input
          value={r.placeholder ?? ''}
          placeholder="请输入"
          onChange={(e) => updateParam(i, { placeholder: e.target.value })}
        />
      ),
    },
    {
      title: '操作',
      key: 'op',
      width: 60,
      render: (_: unknown, _r: CompositeProcessParamDef, i: number) => (
        <Button
          type="text"
          danger
          icon={<DeleteOutlined />}
          onClick={() => removeParam(i)}
        />
      ),
    },
  ];

  return (
    <Drawer
      title={editingDetail ? `编辑组合工序: ${editingDetail.code}` : '新建组合工序'}
      open={open}
      onClose={onClose}
      width={960}
      placement="right"
      maskClosable={false}
      destroyOnClose
      footer={
        <div style={{ textAlign: 'right' }}>
          <Space>
            <Button onClick={onClose}>取消</Button>
            <Button type="primary" loading={saving} onClick={handleSubmit}>
              保存
            </Button>
          </Space>
        </div>
      }
    >
      <Form form={form} layout="vertical">
        <Space size="large" wrap>
          <Form.Item
            name="code"
            label="工序代码"
            rules={[{ required: true, message: '请填写工序代码' }]}
          >
            <Input
              placeholder="RIVET-01"
              style={{ width: 160 }}
              disabled={!!editingDetail}
            />
          </Form.Item>

          <Form.Item
            name="name"
            label="工序名称"
            rules={[{ required: true, message: '请填写工序名称' }]}
          >
            <Input placeholder="铆接" style={{ width: 180 }} />
          </Form.Item>

          <Form.Item name="icon" label="图标（Emoji）">
            <Input placeholder="🔩" style={{ width: 80 }} maxLength={4} />
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

        <Form.Item name="description" label="描述" style={{ marginTop: 4 }}>
          <Input.TextArea rows={2} placeholder="组合工序说明（可选）" style={{ maxWidth: 600 }} />
        </Form.Item>
      </Form>

      <div style={{ marginTop: 16 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
          <b>参数定义</b>
          <Button size="small" icon={<PlusOutlined />} onClick={addParam}>
            添加参数
          </Button>
        </div>
        <Table
          rowKey={(_r, i) => String(i)}
          dataSource={params}
          columns={paramCols as any}
          pagination={false}
          size="small"
          locale={{ emptyText: '暂无参数，点击"添加参数"新增' }}
        />
      </div>
    </Drawer>
  );
};

export default CompositeProcessEditDrawer;
