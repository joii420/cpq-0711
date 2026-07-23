import React, { useEffect, useState } from 'react';
import { Drawer, Form, Input, Select, Button, Space, message } from 'antd';
import { elementPriceStrategyService } from '../../services/elementPriceStrategyService';
import type { PriceSourceDTO, PriceSourceUpsertRequest } from '../../types/element-price-strategy';

/**
 * 价格源 新建/编辑 —— 第二层抽屉（480），挂在 PriceSourceManagerDrawer(720) 之上（task-0722 · F3）
 */
interface Props {
  open: boolean;
  editing: PriceSourceDTO | null;
  onClose: () => void;
  onSaved: () => void;
}

const PriceSourceEditDrawer: React.FC<Props> = ({ open, editing, onClose, onSaved }) => {
  const [form] = Form.useForm();
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!open) return;
    if (editing) {
      form.setFieldsValue({
        sourceName: editing.sourceName,
        sourceUrl: editing.sourceUrl,
        description: editing.description,
        status: editing.status,
      });
    } else {
      form.resetFields();
      form.setFieldsValue({ status: 'ACTIVE' });
    }
  }, [open, editing, form]);

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      const req: PriceSourceUpsertRequest = {
        sourceName: values.sourceName,
        sourceUrl: values.sourceUrl,
        description: values.description,
        status: values.status,
      };
      setSaving(true);
      if (editing) {
        await elementPriceStrategyService.updateSource(editing.id, req);
        message.success('价格源已更新');
      } else {
        await elementPriceStrategyService.createSource(req);
        message.success('价格源已创建');
      }
      onSaved();
    } catch (e: any) {
      if (e?.errorFields) return; // 表单校验错误，antd 已高亮
      // 409：源名称 + 网址 已存在
      message.error(e?.message ?? '保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Drawer
      title={editing ? '编辑价格源' : '新建价格源'}
      open={open}
      onClose={onClose}
      width={480}
      placement="right"
      maskClosable={false}
      destroyOnClose
      footer={
        <div style={{ textAlign: 'right' }}>
          <Space>
            <Button onClick={onClose}>取消</Button>
            <Button type="primary" loading={saving} onClick={handleSubmit}>保存</Button>
          </Space>
        </div>
      }
    >
      <Form form={form} layout="vertical">
        <Form.Item
          name="sourceName"
          label="源名称"
          rules={[{ required: true, message: '请填写源名称' }]}
          tooltip="同名 + 同网址不可重复"
        >
          <Input placeholder="如：上海有色网" />
        </Form.Item>
        <Form.Item name="sourceUrl" label="网址" extra="仅作参考记录，系统不会自动访问">
          <Input placeholder="https://" />
        </Form.Item>
        <Form.Item name="description" label="备注">
          <Input placeholder="如：现货均价 / 含税" />
        </Form.Item>
        <Form.Item name="status" label="状态">
          <Select
            style={{ width: 160 }}
            options={[
              { value: 'ACTIVE', label: '启用' },
              { value: 'DISABLED', label: '停用' },
            ]}
          />
        </Form.Item>
      </Form>
    </Drawer>
  );
};

export default PriceSourceEditDrawer;
