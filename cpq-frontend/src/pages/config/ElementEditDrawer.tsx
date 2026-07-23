import React, { useEffect, useState } from 'react';
import { Drawer, Form, Input, Select, Button, Space, message, Table, Tag, Divider, Alert, Spin } from 'antd';
import {
  elementService,
  type ElementItem,
  type ElementUpsertRequest,
} from '../../services/elementService';
import { elementPriceService } from '../../services/elementPriceService';
import type { ElementLatestPriceDTO } from '../../types/element-price-strategy';

interface Props {
  open: boolean;
  editing: ElementItem | null;
  onClose: () => void;
  onSaved: () => void;
}

/** 数字 4 位小数展示（对齐 cpq-decimal-display-policy：计算/取数列 4 位） */
const fmtPrice = (v: number) => v.toFixed(4);

const ElementEditDrawer: React.FC<Props> = ({ open, editing, onClose, onSaved }) => {
  const [form] = Form.useForm();
  const [saving, setSaving] = useState(false);
  const isCreating = !editing;
  // 被引用元素的符号锁定：禁用输入 + tooltip 说明
  const codeLocked = !!editing && editing.codeLocked;

  // task-0722 · F2：各源最新价格（仅编辑态展示，只读）
  const [latestPrices, setLatestPrices] = useState<ElementLatestPriceDTO[]>([]);
  const [latestLoading, setLatestLoading] = useState(false);

  useEffect(() => {
    if (!open) return;
    if (editing) {
      form.setFieldsValue({
        elementNo: editing.elementNo,
        elementCode: editing.elementCode,
        elementName: editing.elementName,
        status: editing.status ?? 'ACTIVE',
      });
      setLatestLoading(true);
      elementPriceService.listLatestBySource(editing.elementCode)
        .then(setLatestPrices)
        .catch((e: any) => message.error(e?.message ?? '各源最新价格加载失败'))
        .finally(() => setLatestLoading(false));
    } else {
      form.resetFields();
      form.setFieldsValue({ status: 'ACTIVE' });
      setLatestPrices([]);
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
      width={640}
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

      {/* task-0722 · F2：各源最新价格 —— 仅编辑态展示，只读，不在此录价（录价统一走「价格导入」） */}
      {editing && (
        <>
          <Divider orientation="left" orientationMargin={0} style={{ margin: '22px 0 12px', fontSize: 14 }}>
            各源最新价格
          </Divider>
          {latestLoading ? (
            <div style={{ textAlign: 'center', padding: '16px 0' }}><Spin /></div>
          ) : latestPrices.length === 0 ? (
            <Alert type="warning" showIcon message="该元素暂无任何价格记录，请通过『价格导入』录入" />
          ) : (
            <>
              <Table<ElementLatestPriceDTO>
                size="small"
                rowKey="sourceId"
                pagination={false}
                dataSource={latestPrices}
                columns={[
                  {
                    title: '价格源',
                    dataIndex: 'sourceName',
                    render: (v: string, r) => (
                      <Space size={4}>
                        <span>{v}</span>
                        {r.sourceStatus === 'DISABLED' && <Tag color="default">源已停用</Tag>}
                      </Space>
                    ),
                  },
                  {
                    title: '最新价',
                    dataIndex: 'price',
                    align: 'right' as const,
                    render: (v: number) => <b>{fmtPrice(v)}</b>,
                  },
                  { title: '货币', dataIndex: 'currency' },
                  { title: '计价单位', dataIndex: 'priceUnit' },
                  { title: '价格日期', dataIndex: 'priceDate' },
                ]}
                onRow={(r) => (r.sourceStatus === 'DISABLED' ? { style: { color: 'rgba(0,0,0,.45)' } } : {})}
              />
              <div style={{ marginTop: 10, fontSize: 12, color: 'rgba(0,0,0,.45)' }}>
                该元素在 {latestPrices.length} 个源下有价格记录。价格录入统一走「价格导入」，此处只读。
              </div>
            </>
          )}
        </>
      )}
    </Drawer>
  );
};

export default ElementEditDrawer;
