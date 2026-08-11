import React, { useEffect, useState } from 'react';
import { Drawer, Form, Select, DatePicker, InputNumber, Input, Button, Space, message } from 'antd';
import dayjs from 'dayjs';
import { elementPriceStrategyService } from '../../services/elementPriceStrategyService';
import { elementService, type ElementItem } from '../../services/elementService';
import type {
  PriceSourceDTO,
  ElementPriceRowDTO,
  CreatePriceRequest,
  UpdatePriceRequest,
} from '../../types/element-price-strategy';
import { normalizeDecimalString, toDecimal } from '../../utils/precision';

/**
 * 价格编辑抽屉（480，二级） —— update-0724 · F3
 * 新建态：元素/价格源/价格日期/单价/货币/计价单位 全部可填。
 * 编辑态：元素/价格源/价格日期 置灰只读（键锁定由后端硬保证，不含在 PUT 请求体里，见 api.md §2）；
 *        只有单价/货币/计价单位可改。
 */
interface Props {
  open: boolean;
  mode: 'create' | 'edit';
  /** mode='edit' 时必填 —— 被编辑的行 */
  editing: ElementPriceRowDTO | null;
  /** 价格源下拉数据 —— 复用父抽屉 ElementPriceTableDrawer 已加载的 sources，不重复请求（api.md §7） */
  sources: PriceSourceDTO[];
  onClose: () => void;
  /** 保存成功后回调：调用方负责刷新列表当前页 */
  onSaved: () => void;
}

const PriceEditDrawer: React.FC<Props> = ({ open, mode, editing, sources, onClose, onSaved }) => {
  const [form] = Form.useForm();
  const [saving, setSaving] = useState(false);
  const [elements, setElements] = useState<ElementItem[]>([]);
  const isEdit = mode === 'edit';

  useEffect(() => {
    if (!open) return;
    // 元素下拉走 GET /api/cpq/elements（不用已下线的 v1 available-elements，api.md §7）；只列 ACTIVE
    elementService.list()
      .then((list) => setElements(list.filter((e) => e.status === 'ACTIVE')))
      .catch((e: any) => message.error(e?.message ?? '元素列表加载失败'));

    if (isEdit && editing) {
      form.setFieldsValue({
        elementCode: editing.elementCode,
        sourceId: editing.sourceId,
        priceDate: dayjs(editing.priceDate),
        price: editing.price,
        currency: editing.currency,
        priceUnit: editing.priceUnit,
      });
    } else {
      form.resetFields();
      form.setFieldsValue({ currency: 'CNY' });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, isEdit, editing, form]);

  const handleSubmit = async () => {
    let values: any;
    try {
      values = await form.validateFields();
    } catch {
      return; // 表单校验错误，antd 已高亮
    }
    setSaving(true);
    try {
      if (isEdit && editing) {
        const req: UpdatePriceRequest = {
          price: normalizeDecimalString(values.price),
          currency: values.currency,
          priceUnit: values.priceUnit,
        };
        await elementPriceStrategyService.updatePrice(editing.id, req);
        message.success('价格已更新');
      } else {
        const req: CreatePriceRequest = {
          elementCode: values.elementCode,
          sourceId: values.sourceId,
          priceDate: (values.priceDate as ReturnType<typeof dayjs>).format('YYYY-MM-DD'),
          price: normalizeDecimalString(values.price),
          currency: values.currency,
          priceUnit: values.priceUnit,
        };
        await elementPriceStrategyService.createPrice(req);
        message.success('价格已新建');
      }
      onSaved();
      onClose();
    } catch (e: any) {
      const status = e?.httpStatus;
      if (status === 404) {
        // 编辑时该行已被他人删除：提示 + 关闭抽屉 + 刷新列表（不是留在原地重试）
        message.error('该价格已被删除');
        onSaved();
        onClose();
        return;
      }
      // 409（键重复）/ 400（校验失败）：显示后端 message，抽屉不关闭，让用户改日期或改走编辑
      message.error(e?.message ?? '保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Drawer
      title={isEdit ? '编辑价格' : '新建价格'}
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
          name="elementCode"
          label="元素"
          rules={[{ required: true, message: '请选择元素' }]}
          tooltip={isEdit ? '键字段，编辑态不可改' : undefined}
        >
          <Select
            disabled={isEdit}
            showSearch
            placeholder="选择元素"
            optionFilterProp="label"
            options={elements.map((e) => ({ value: e.elementCode, label: `${e.elementCode} ${e.elementName}` }))}
          />
        </Form.Item>
        <Form.Item
          name="sourceId"
          label="价格源"
          rules={[{ required: true, message: '请选择价格源' }]}
          tooltip={isEdit ? '键字段，编辑态不可改' : undefined}
        >
          <Select
            disabled={isEdit}
            placeholder="请选择价格源"
            options={sources.filter((s) => s.status === 'ACTIVE' || isEdit).map((s) => ({ value: s.id, label: s.sourceName }))}
          />
        </Form.Item>
        <Form.Item
          name="priceDate"
          label="价格日期"
          rules={[{ required: true, message: '请选择价格日期' }]}
          tooltip={isEdit ? '键字段，编辑态不可改' : undefined}
        >
          <DatePicker disabled={isEdit} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item
          name="price"
          label="单价"
          rules={[
            { required: true, message: '请输入单价' },
            {
              validator: (_, v) => (v !== undefined && v !== null && toDecimal(v).greaterThan(0))
                ? Promise.resolve()
                : Promise.reject(new Error('单价必须大于 0')),
            },
          ]}
        >
          <InputNumber<string> stringMode style={{ width: '100%' }} min="0" precision={4} placeholder="请输入单价" />
        </Form.Item>
        <Form.Item name="currency" label="货币" rules={[{ required: true, message: '请填写货币' }]}>
          <Input placeholder="如 CNY / USD" />
        </Form.Item>
        <Form.Item name="priceUnit" label="计价单位" rules={[{ required: true, message: '请填写计价单位' }]}>
          <Input placeholder="如 kg / 吨" />
        </Form.Item>
      </Form>
    </Drawer>
  );
};

export default PriceEditDrawer;
