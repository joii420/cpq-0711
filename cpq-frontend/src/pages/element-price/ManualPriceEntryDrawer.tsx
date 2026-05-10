import React, { useEffect } from 'react';
import { Drawer, Form, Select, InputNumber, Input, Button, Space, message } from 'antd';
import { elementPriceService } from '../../services/elementPriceService';
import type { AvailableElementDTO, ManualPriceEntryRequest } from '../../types/element-price';

interface ManualPriceEntryDrawerProps {
  open: boolean;
  onClose: () => void;
  onSuccess: () => void;
  availableElements: AvailableElementDTO[];
}

const CURRENCY_OPTIONS = [
  { value: 'RMB', label: 'RMB（人民币）' },
  { value: 'USD', label: 'USD（美元）' },
  { value: 'EUR', label: 'EUR（欧元）' },
];

const UNIT_OPTIONS = [
  { value: '克', label: '克' },
  { value: '千克', label: '千克' },
  { value: '吨', label: '吨' },
  { value: '盎司', label: '盎司' },
  { value: '磅', label: '磅' },
];

const ManualPriceEntryDrawer: React.FC<ManualPriceEntryDrawerProps> = ({
  open,
  onClose,
  onSuccess,
  availableElements,
}) => {
  const [form] = Form.useForm<ManualPriceEntryRequest>();
  const [submitting, setSubmitting] = React.useState(false);

  // 每次打开时重置表单
  useEffect(() => {
    if (open) {
      form.resetFields();
      form.setFieldsValue({ currency: 'RMB', unit: '克' });
    }
  }, [open, form]);

  // 选择元素时自动填充默认单位和货币
  const handleElementChange = (val: string) => {
    const el = availableElements.find(e => e.elementName === val);
    if (el) {
      if (el.currency) form.setFieldValue('currency', el.currency);
      if (el.unit) form.setFieldValue('unit', el.unit);
    }
  };

  const handleSubmit = async () => {
    let values: ManualPriceEntryRequest;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    setSubmitting(true);
    try {
      await elementPriceService.upsertManual(values);
      message.success('参考价录入成功');
      onSuccess();
      onClose();
    } catch (e: any) {
      message.error(e?.message || '录入失败，请重试');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Drawer
      title="录入元素参考价"
      placement="right"
      width={720}
      open={open}
      onClose={onClose}
      destroyOnClose
      extra={
        <Space>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={submitting} onClick={handleSubmit}>
            提交
          </Button>
        </Space>
      }
    >
      <Form
        form={form}
        layout="vertical"
        initialValues={{ currency: 'RMB', unit: '克' }}
      >
        <Form.Item
          name="elementName"
          label="元素"
          rules={[{ required: true, message: '请选择元素' }]}
        >
          <Select
            placeholder="请选择元素"
            showSearch
            optionFilterProp="label"
            onChange={handleElementChange}
            options={availableElements.map(e => ({
              value: e.elementName,
              label: e.elementName,
            }))}
          />
        </Form.Item>

        <Form.Item
          name="price"
          label="参考价格"
          rules={[
            { required: true, message: '请输入价格' },
            { type: 'number', min: 0.0001, message: '价格必须大于 0' },
          ]}
        >
          <InputNumber
            style={{ width: '100%' }}
            min={0}
            precision={4}
            placeholder="请输入价格"
          />
        </Form.Item>

        <Form.Item
          name="currency"
          label="货币"
          rules={[{ required: true, message: '请选择货币' }]}
        >
          <Select options={CURRENCY_OPTIONS} />
        </Form.Item>

        <Form.Item
          name="unit"
          label="单位"
          rules={[{ required: true, message: '请选择单位' }]}
        >
          <Select
            options={UNIT_OPTIONS}
            showSearch
            optionFilterProp="label"
            allowClear={false}
          />
        </Form.Item>

        <Form.Item name="note" label="备注">
          <Input.TextArea
            rows={3}
            placeholder="可填写价格来源、市场说明等"
            maxLength={500}
            showCount
          />
        </Form.Item>
      </Form>
    </Drawer>
  );
};

export default ManualPriceEntryDrawer;
