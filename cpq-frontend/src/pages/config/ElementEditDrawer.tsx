import React, { useEffect, useState } from 'react';
import { Drawer, Form, Input, Select, Button, Space, message } from 'antd';
import {
  elementService,
  type ElementItem,
  type ElementUpsertRequest,
} from '../../services/elementService';

interface Props {
  open: boolean;
  editing: ElementItem | null;
  onClose: () => void;
  onSaved: () => void;
}

const ElementEditDrawer: React.FC<Props> = ({ open, editing, onClose, onSaved }) => {
  const [form] = Form.useForm();
  const [saving, setSaving] = useState(false);
  const isCreating = !editing;
  // 被引用元素的符号锁定：禁用输入 + tooltip 说明
  const codeLocked = !!editing && editing.codeLocked;

  useEffect(() => {
    if (!open) return;
    if (editing) {
      form.setFieldsValue({
        elementNo: editing.elementNo,
        elementCode: editing.elementCode,
        elementName: editing.elementName,
        status: editing.status ?? 'ACTIVE',
      });
    } else {
      form.resetFields();
      form.setFieldsValue({ status: 'ACTIVE' });
    }
  }, [open, editing, form]);

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      const req: ElementUpsertRequest = {
        elementNo: values.elementNo,
        elementCode: values.elementCode,
        elementName: values.elementName,
      };
      setSaving(true);
      if (editing) {
        // 编辑：路径主键定位，body 带状态（唯一可重新启用入口）
        await elementService.update(editing.elementNo, { ...req, status: values.status });
        message.success('元素已更新');
      } else {
        await elementService.create(req);
        message.success('元素已创建');
      }
      onSaved();
    } catch (e: any) {
      if (e?.errorFields) return; // 表单校验错误，antd 已高亮
      // 409：编号/符号撞号、被引用改符号 → 展示后端 message
      message.error(e?.message ?? '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const symbolInput = (
    <Input placeholder="Ag / Cu / Mo" disabled={codeLocked} />
  );

  return (
    <Drawer
      title={editing ? `编辑元素: ${editing.elementNo}` : '新建元素'}
      open={open}
      onClose={onClose}
      width={520}
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
          name="elementNo"
          label="元素编号"
          rules={[{ required: true, message: '请填写元素编号' }]}
          tooltip={editing ? '业务主键，不可修改' : '业务主键，创建后不可修改'}
        >
          <Input placeholder="10100" disabled={!isCreating} />
        </Form.Item>

        <Form.Item
          name="elementCode"
          label="符号"
          rules={[{ required: true, message: '请填写符号' }]}
          tooltip={codeLocked ? `已被 ${editing!.referencedCount} 个材质引用，符号不可修改` : undefined}
        >
          {/* 直接渲染 Input，禁用态由 symbolInput 内 disabled={codeLocked} 控制。
              不用 <Tooltip> 包裹：Form.Item 只向直接子节点注入 value/id，包 Tooltip 会吞掉回显。
              锁定说明由上方 Form.Item tooltip(标签旁 ? 图标)承载。 */}
          {symbolInput}
        </Form.Item>

        <Form.Item
          name="elementName"
          label="中文名"
          rules={[{ required: true, message: '请填写中文名' }]}
        >
          <Input placeholder="钼" />
        </Form.Item>

        {editing && (
          <Form.Item name="status" label="状态">
            <Select
              style={{ width: 160 }}
              options={[
                { value: 'ACTIVE', label: '启用' },
                { value: 'INACTIVE', label: '停用' },
              ]}
            />
          </Form.Item>
        )}
      </Form>
    </Drawer>
  );
};

export default ElementEditDrawer;
