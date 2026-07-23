import React, { useEffect, useState } from 'react';
import { Drawer, Form, Select, InputNumber, Button, Space, message } from 'antd';
import { elementPriceStrategyService } from '../../../services/elementPriceStrategyService';
import { elementService, type ElementItem } from '../../../services/elementService';
import {
  METHOD_LABEL, UNIT_LABEL,
  type PriceSourceDTO, type StrategyDTO, type StrategyUpsertRequest, type PriceMethod, type WindowUnit,
} from '../../../types/element-price-strategy';

/**
 * 元素级例外 新建/编辑抽屉（480） —— task-0722 · F6
 * 字段 = 默认策略 5 项 + 元素*（编辑态锁定，避免把这条例外"改绑"到另一元素上）
 */
interface Props {
  open: boolean;
  customerNo: string;
  editing: StrategyDTO | null;
  sources: PriceSourceDTO[];
  onClose: () => void;
  onSaved: () => void;
}

const StrategyExceptionEditDrawer: React.FC<Props> = ({ open, customerNo, editing, sources, onClose, onSaved }) => {
  const [form] = Form.useForm();
  const [saving, setSaving] = useState(false);
  const [elements, setElements] = useState<ElementItem[]>([]);
  const method: PriceMethod | undefined = Form.useWatch('method', form);

  useEffect(() => {
    if (!open) return;
    elementService.list().then((list) => setElements(list.filter((e) => e.status === 'ACTIVE')))
      .catch((e: any) => message.error(e?.message ?? '元素列表加载失败'));
    if (editing) {
      form.setFieldsValue({
        elementCode: editing.elementCode,
        sourceId: editing.sourceId,
        method: editing.method,
        windowNum: editing.windowNum,
        windowUnit: editing.windowUnit ?? 'DAY',
        factor: editing.factor ?? 1,
        premium: editing.premium ?? 0,
      });
    } else {
      form.resetFields();
      form.setFieldsValue({ method: 'AVG', windowUnit: 'DAY', factor: 1, premium: 0 });
    }
  }, [open, editing, form]);

  // 取值方式='LATEST' 时窗口两项灰置并清空
  useEffect(() => {
    if (method === 'LATEST') {
      form.setFieldsValue({ windowNum: undefined, windowUnit: undefined });
    }
  }, [method, form]);

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      const req: StrategyUpsertRequest = {
        customerNo,
        elementCode: editing ? editing.elementCode! : values.elementCode,
        sourceId: values.sourceId,
        method: values.method,
        factor: values.factor ?? 1,
        premium: values.premium ?? 0,
      };
      if (values.method !== 'LATEST') {
        req.windowNum = values.windowNum;
        req.windowUnit = values.windowUnit;
      }
      setSaving(true);
      if (editing) {
        await elementPriceStrategyService.updateException(editing.id, req);
        message.success('元素例外已更新');
      } else {
        await elementPriceStrategyService.createException(req);
        message.success('元素例外已创建');
      }
      onSaved();
    } catch (e: any) {
      if (e?.errorFields) return;
      // 409：该客户的元素「Ag」已存在例外配置
      message.error(e?.message ?? '保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Drawer
      title={editing ? '编辑元素例外' : '新增元素例外'}
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
        <Form.Item name="elementCode" label="元素" rules={[{ required: true, message: '请选择元素' }]}>
          <Select
            disabled={!!editing}
            showSearch
            placeholder="选择元素"
            optionFilterProp="label"
            options={elements.map((e) => ({ value: e.elementCode, label: `${e.elementCode} ${e.elementName}` }))}
          />
        </Form.Item>
        <Form.Item name="sourceId" label="价格源" rules={[{ required: true, message: '请选择价格源' }]} tooltip="必填，只能选一个">
          <Select
            placeholder="请选择价格源"
            options={sources.map((s) => ({ value: s.id, label: s.sourceName }))}
          />
        </Form.Item>
        <Form.Item name="method" label="取值方式" rules={[{ required: true, message: '请选择取值方式' }]}>
          <Select options={(Object.keys(METHOD_LABEL) as PriceMethod[]).map((m) => ({ value: m, label: METHOD_LABEL[m] }))} />
        </Form.Item>
        <Form.Item label="窗口" required={method !== 'LATEST'} tooltip="取值方式为「最新一条价」时本项灰置">
          <Space.Compact style={{ width: '100%' }}>
            <span style={{ lineHeight: '32px', padding: '0 8px', color: 'rgba(0,0,0,.65)' }}>最近</span>
            <Form.Item
              name="windowNum"
              noStyle
              rules={[{ required: method !== 'LATEST', message: '请填写窗口数值' }]}
            >
              <InputNumber min={1} disabled={method === 'LATEST'} style={{ width: '45%' }} />
            </Form.Item>
            <Form.Item
              name="windowUnit"
              noStyle
              rules={[{ required: method !== 'LATEST', message: '请选择窗口单位' }]}
            >
              <Select<WindowUnit>
                disabled={method === 'LATEST'}
                style={{ width: '35%' }}
                options={(Object.keys(UNIT_LABEL) as WindowUnit[]).map((u) => ({ value: u, label: UNIT_LABEL[u] }))}
              />
            </Form.Item>
          </Space.Compact>
        </Form.Item>
        <Form.Item name="factor" label="系数" tooltip="默认 1">
          <InputNumber style={{ width: '100%' }} min={0} />
        </Form.Item>
        <Form.Item name="premium" label="加价" tooltip="默认 0">
          <InputNumber style={{ width: '100%' }} />
        </Form.Item>
      </Form>
    </Drawer>
  );
};

export default StrategyExceptionEditDrawer;
