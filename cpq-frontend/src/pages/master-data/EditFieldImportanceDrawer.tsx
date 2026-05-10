import React, { useEffect } from 'react';
import {
  Drawer,
  Form,
  Select,
  Switch,
  Input,
  Button,
  Space,
  message,
  Typography,
  Descriptions,
  Tag,
} from 'antd';
import type { FieldImportanceItem, ImportanceLevel, UpdateFieldImportanceRequest } from '../../types/field-importance';
import { fieldImportanceService } from '../../services/fieldImportanceService';

const { Option } = Select;
const { TextArea } = Input;
const { Text } = Typography;

interface Props {
  open: boolean;
  record: FieldImportanceItem | null;
  onClose: () => void;
  onSuccess: () => void;
}

const IMPORTANCE_OPTIONS: { value: ImportanceLevel; label: string; color: string }[] = [
  { value: 'CRITICAL', label: '关键', color: 'red' },
  { value: 'IMPORTANT', label: '重要', color: 'orange' },
  { value: 'NORMAL', label: '普通', color: 'default' },
];

const EditFieldImportanceDrawer: React.FC<Props> = ({ open, record, onClose, onSuccess }) => {
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = React.useState(false);

  useEffect(() => {
    if (open && record) {
      form.setFieldsValue({
        importanceLevel: record.importanceLevel,
        affectsCalculation: record.affectsCalculation,
        isRequired: record.isRequired ?? false,
        remark: record.remark || '',
      });
    }
    if (!open) {
      form.resetFields();
    }
  }, [open, record, form]);

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      const req: UpdateFieldImportanceRequest = {
        importanceLevel: values.importanceLevel,
        affectsCalculation: values.affectsCalculation,
        isRequired: values.isRequired,
        remark: values.remark,
      };
      await fieldImportanceService.updateImportance(record!.id, req);
      message.success('字段重要性已更新');
      onSuccess();
      onClose();
    } catch (err: any) {
      if (err?.errorFields) return;
      message.error(err?.message || '更新失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Drawer
      title={`编辑字段重要性：${record?.variableLabel || ''}`}
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
              保存
            </Button>
          </Space>
        </div>
      }
    >
      {record && (
        <Descriptions
          bordered
          size="small"
          column={2}
          style={{ marginBottom: 24 }}
        >
          <Descriptions.Item label="字段代码">
            <Text code>{record.variableCode}</Text>
          </Descriptions.Item>
          <Descriptions.Item label="字段标签">{record.variableLabel}</Descriptions.Item>
          <Descriptions.Item label="列标题">{record.columnTitle}</Descriptions.Item>
          <Descriptions.Item label="列字母">
            <Text code>{record.columnLetter}</Text>
          </Descriptions.Item>
          <Descriptions.Item label="数据类型">
            <Tag color={record.dataType === 'VALUE' ? 'green' : 'blue'}>{record.dataType}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="排序">{record.sortOrder}</Descriptions.Item>
        </Descriptions>
      )}

      <Form form={form} layout="vertical" requiredMark>
        <Form.Item
          name="importanceLevel"
          label="重要性级别"
          rules={[{ required: true, message: '请选择重要性级别' }]}
        >
          <Select placeholder="请选择重要性级别">
            {IMPORTANCE_OPTIONS.map((o) => (
              <Option key={o.value} value={o.value}>
                <Tag color={o.color}>{o.label}</Tag> {o.value}
              </Option>
            ))}
          </Select>
        </Form.Item>

        <Form.Item
          name="affectsCalculation"
          label="影响计算"
          valuePropName="checked"
        >
          <Switch checkedChildren="是" unCheckedChildren="否" />
        </Form.Item>

        <Form.Item
          name="isRequired"
          label="导入必填"
          valuePropName="checked"
          tooltip="开启后，导入 Excel 时该字段不可为空"
        >
          <Switch checkedChildren="必填" unCheckedChildren="可选" />
        </Form.Item>

        <Form.Item name="remark" label="备注">
          <TextArea rows={3} placeholder="可选，填写该字段重要性配置的说明" />
        </Form.Item>
      </Form>
    </Drawer>
  );
};

export default EditFieldImportanceDrawer;
