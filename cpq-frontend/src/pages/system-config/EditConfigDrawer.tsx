import React, { useEffect } from 'react';
import {
  Drawer,
  Form,
  Input,
  Select,
  Button,
  Space,
  message,
  Switch,
} from 'antd';
import type {
  SystemConfigDTO,
  CreateSystemConfigRequest,
  UpdateSystemConfigRequest,
  DataType,
  ConfigCategory,
  ModifiableBy,
} from '../../types/system-config';
import { systemConfigService } from '../../services/systemConfigService';

const { TextArea } = Input;
const { Option } = Select;

interface Props {
  open: boolean;
  mode: 'create' | 'edit';
  record?: SystemConfigDTO | null;
  onClose: () => void;
  onSuccess: () => void;
}

const DATA_TYPE_OPTIONS: { value: DataType; label: string }[] = [
  { value: 'STRING', label: 'STRING（字符串）' },
  { value: 'NUMBER', label: 'NUMBER（数字）' },
  { value: 'BOOLEAN', label: 'BOOLEAN（布尔）' },
  { value: 'JSON', label: 'JSON（对象）' },
];

const CATEGORY_OPTIONS: { value: ConfigCategory; label: string }[] = [
  { value: 'validation', label: '校验规则' },
  { value: 'import', label: '导入配置' },
  { value: 'retention', label: '数据保留' },
  { value: 'element_price', label: '元素价格' },
  { value: 'business', label: '业务配置' },
];

const MODIFIABLE_BY_OPTIONS: { value: ModifiableBy; label: string }[] = [
  { value: 'SYSTEM_ADMIN', label: '仅系统管理员' },
  { value: 'SALES_MANAGER', label: '销售经理及以上' },
  { value: 'ANYONE', label: '所有用户' },
];

const EditConfigDrawer: React.FC<Props> = ({ open, mode, record, onClose, onSuccess }) => {
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = React.useState(false);

  useEffect(() => {
    if (open) {
      if (mode === 'edit' && record) {
        form.setFieldsValue({
          configKey: record.configKey,
          configValue: record.configValue,
          dataType: record.dataType,
          category: record.category,
          description: record.description || '',
          modifiableBy: record.modifiableBy,
        });
      } else {
        form.resetFields();
        form.setFieldsValue({ dataType: 'STRING', category: 'business', modifiableBy: 'SYSTEM_ADMIN' });
      }
    }
  }, [open, mode, record, form]);

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      if (mode === 'create') {
        const req: CreateSystemConfigRequest = {
          configKey: values.configKey,
          configValue: values.configValue,
          dataType: values.dataType,
          category: values.category,
          description: values.description,
          modifiableBy: values.modifiableBy,
        };
        await systemConfigService.create(req);
        message.success('配置项创建成功');
      } else {
        const req: UpdateSystemConfigRequest = {
          configValue: values.configValue,
          description: values.description,
          modifiableBy: values.modifiableBy,
        };
        await systemConfigService.update(record!.configKey, req);
        message.success('配置项更新成功');
      }
      onSuccess();
      onClose();
    } catch (err: any) {
      if (err?.errorFields) return; // 表单校验错误，不提示
      message.error(err?.message || '操作失败');
    } finally {
      setSubmitting(false);
    }
  };

  const title = mode === 'create' ? '新建配置项' : `编辑配置项：${record?.configKey}`;

  return (
    <Drawer
      title={title}
      placement="right"
      width={720}
      open={open}
      onClose={onClose}
      destroyOnClose
      footer={
        <div style={{ textAlign: 'right' }}>
          <Space>
            <Button onClick={onClose}>取消</Button>
            <Button type="primary" loading={submitting} onClick={handleSubmit}>
              {mode === 'create' ? '创建' : '保存'}
            </Button>
          </Space>
        </div>
      }
    >
      <Form form={form} layout="vertical" requiredMark>
        <Form.Item
          name="configKey"
          label="配置键（config_key）"
          rules={[
            { required: true, message: '请输入配置键' },
            { pattern: /^[a-z][a-z0-9._-]{0,99}$/, message: '只允许小写字母、数字、点、下划线、连字符，以字母开头' },
          ]}
        >
          <Input
            disabled={mode === 'edit'}
            placeholder="例：quote.validity.days"
            style={{ fontFamily: 'monospace' }}
          />
        </Form.Item>

        <Form.Item
          name="configValue"
          label="配置值（config_value）"
          rules={[{ required: true, message: '请输入配置值' }]}
        >
          <TextArea rows={3} placeholder="输入配置值" />
        </Form.Item>

        <Form.Item
          name="dataType"
          label="数据类型"
          rules={[{ required: true, message: '请选择数据类型' }]}
        >
          <Select placeholder="请选择数据类型">
            {DATA_TYPE_OPTIONS.map((o) => (
              <Option key={o.value} value={o.value}>{o.label}</Option>
            ))}
          </Select>
        </Form.Item>

        <Form.Item
          name="category"
          label="分类"
          rules={[{ required: true, message: '请选择分类' }]}
        >
          <Select placeholder="请选择分类">
            {CATEGORY_OPTIONS.map((o) => (
              <Option key={o.value} value={o.value}>{o.label}</Option>
            ))}
          </Select>
        </Form.Item>

        <Form.Item name="description" label="说明">
          <TextArea rows={2} placeholder="可选，配置项的业务含义说明" />
        </Form.Item>

        <Form.Item
          name="modifiableBy"
          label="可修改权限"
          rules={[{ required: true, message: '请选择可修改权限' }]}
        >
          <Select placeholder="请选择可修改权限">
            {MODIFIABLE_BY_OPTIONS.map((o) => (
              <Option key={o.value} value={o.value}>{o.label}</Option>
            ))}
          </Select>
        </Form.Item>
      </Form>
    </Drawer>
  );
};

export default EditConfigDrawer;
