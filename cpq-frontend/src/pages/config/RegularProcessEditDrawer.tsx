import React, { useEffect, useState } from 'react';
import {
  Drawer, Form, Input, Select, InputNumber, Switch, Button, Space, message,
} from 'antd';
import {
  processService,
  PROCESS_CATEGORIES,
  type Process,
  type ProcessUpsertRequest,
} from '../../services/processService';

interface Props {
  open: boolean;
  editingDetail: Process | null;
  onClose: () => void;
  onSaved: () => void;
}

const RegularProcessEditDrawer: React.FC<Props> = ({ open, editingDetail, onClose, onSaved }) => {
  const [form] = Form.useForm();
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!open) return;
    if (editingDetail) {
      form.setFieldsValue({
        code:        editingDetail.code,
        name:        editingDetail.name,
        category:    editingDetail.category,
        description: editingDetail.description ?? '',
        isRequired:  editingDetail.isRequired ?? false,
        sortOrder:   editingDetail.sortOrder ?? 100,
        status:      editingDetail.status ?? 'ACTIVE',
      });
    } else {
      form.resetFields();
      form.setFieldsValue({ isRequired: false, sortOrder: 100, status: 'ACTIVE' });
    }
  }, [open, editingDetail, form]);

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      const req: ProcessUpsertRequest = {
        code:        values.code,
        name:        values.name,
        category:    values.category,
        description: values.description || undefined,
        isRequired:  values.isRequired ?? false,
        sortOrder:   values.sortOrder ?? 100,
        status:      values.status ?? 'ACTIVE',
      };
      setSaving(true);
      if (editingDetail) {
        await processService.update(editingDetail.id, req);
        message.success('工序已更新');
      } else {
        await processService.create(req);
        message.success('工序已创建');
      }
      onSaved();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.response?.data?.message ?? e?.message ?? '保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Drawer
      title={editingDetail ? `编辑工序: ${editingDetail.code}` : '新建工序'}
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
              placeholder="MACH-01"
              style={{ width: 160 }}
              disabled={!!editingDetail}
            />
          </Form.Item>

          <Form.Item
            name="name"
            label="工序名称"
            rules={[{ required: true, message: '请填写工序名称' }]}
          >
            <Input placeholder="机加工" style={{ width: 200 }} />
          </Form.Item>

          <Form.Item
            name="category"
            label="工序分类"
            rules={[{ required: true, message: '请选择工序分类' }]}
          >
            <Select
              style={{ width: 160 }}
              placeholder="请选择"
              options={PROCESS_CATEGORIES}
            />
          </Form.Item>

          <Form.Item name="isRequired" label="是否必选" valuePropName="checked">
            <Switch checkedChildren="必选" unCheckedChildren="可选" />
          </Form.Item>

          <Form.Item name="sortOrder" label="排序">
            <InputNumber min={0} style={{ width: 100 }} />
          </Form.Item>

          <Form.Item name="status" label="状态">
            <Select
              style={{ width: 100 }}
              options={[
                { value: 'ACTIVE',   label: '启用' },
                { value: 'DISABLED', label: '停用' },
              ]}
            />
          </Form.Item>
        </Space>

        <Form.Item name="description" label="描述" style={{ marginTop: 8 }}>
          <Input.TextArea rows={3} placeholder="工序说明（可选）" style={{ maxWidth: 600 }} />
        </Form.Item>
      </Form>
    </Drawer>
  );
};

export default RegularProcessEditDrawer;
