import React, { useState } from 'react';
import {
  Drawer, Steps, Button, Form, Input, Select, InputNumber, Upload, Table,
  Space, message, Descriptions,
} from 'antd';
import { UploadOutlined } from '@ant-design/icons';
import { excelTemplateService } from '../../services/excelTemplateService';
import { customerService } from '../../services/customerService';

interface ExcelTemplateDrawerProps {
  open: boolean;
  editingRecord?: any;
  onClose: () => void;
  onSuccess: () => void;
}

const ExcelTemplateDrawer: React.FC<ExcelTemplateDrawerProps> = ({
  open,
  editingRecord,
  onClose,
  onSuccess,
}) => {
  const [currentStep, setCurrentStep] = useState(0);
  const [form] = Form.useForm();
  const [basicInfo, setBasicInfo] = useState<any>(null);
  const [sampleFile, setSampleFile] = useState<File | null>(null);
  const [parsedHeaders, setParsedHeaders] = useState<string[]>([]);
  const [selectedPartNoColumn, setSelectedPartNoColumn] = useState<string>('');
  const [parsing, setParsing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [customers, setCustomers] = useState<any[]>([]);
  const [customerLoading, setCustomerLoading] = useState(false);

  const searchCustomers = async (value: string) => {
    if (!value) return;
    setCustomerLoading(true);
    try {
      const res = await customerService.list({ keyword: value, size: 20 });
      setCustomers(res.data?.content || []);
    } catch {
      // silently fail
    } finally {
      setCustomerLoading(false);
    }
  };

  const handleStep1 = async () => {
    try {
      const values = await form.validateFields();
      setBasicInfo(values);
      setCurrentStep(1);
    } catch {
      // validation errors shown inline
    }
  };

  const handleStep2ParseHeaders = async () => {
    if (!sampleFile) {
      message.warning('请先上传样例Excel文件');
      return;
    }
    setParsing(true);
    try {
      const sheetIndex = basicInfo?.sheetIndex ?? 1;
      const headerRowIndex = basicInfo?.headerRowIndex ?? 2;
      const res = await excelTemplateService.parseHeaders(sampleFile, sheetIndex, headerRowIndex);
      const headers: string[] = res.data || res || [];
      setParsedHeaders(headers);
      setCurrentStep(2);
    } catch (e: any) {
      message.error('解析失败: ' + e.message);
    } finally {
      setParsing(false);
    }
  };

  const handleStep3 = () => {
    if (!selectedPartNoColumn) {
      message.warning('请选择料号列');
      return;
    }
    setCurrentStep(3);
  };

  const handleSave = async () => {
    setSaving(true);
    try {
      const payload = { ...basicInfo, partNoColumn: selectedPartNoColumn };
      if (editingRecord) {
        await excelTemplateService.update(editingRecord.id, payload);
        message.success('更新成功');
      } else {
        await excelTemplateService.create(payload);
        message.success('创建成功');
      }
      handleClose();
      onSuccess();
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setSaving(false);
    }
  };

  const handleClose = () => {
    setCurrentStep(0);
    setBasicInfo(null);
    setSampleFile(null);
    setParsedHeaders([]);
    setSelectedPartNoColumn('');
    form.resetFields();
    onClose();
  };

  const steps = [
    { title: '基本信息' },
    { title: '上传样例' },
    { title: '选择料号列' },
    { title: '确认保存' },
  ];

  const headerColumns = [
    { title: '列序号', dataIndex: 'index', key: 'index', width: 80 },
    { title: '列名', dataIndex: 'name', key: 'name' },
    {
      title: '选为料号列', key: 'select', width: 120,
      render: (_: any, record: any) => (
        <Button
          type={selectedPartNoColumn === record.name ? 'primary' : 'default'}
          size="small"
          onClick={() => setSelectedPartNoColumn(record.name)}
        >
          {selectedPartNoColumn === record.name ? '已选' : '选择'}
        </Button>
      ),
    },
  ];

  return (
    <Drawer
      title={editingRecord ? '编辑Excel模板' : '注册Excel模板'}
      open={open}
      onClose={handleClose}
      width={600}
      destroyOnClose
    >
      <Steps current={currentStep} items={steps} style={{ marginBottom: 24 }} />

      {currentStep === 0 && (
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="模板名称" rules={[{ required: true, message: '请输入模板名称' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="customerId" label="所属客户" rules={[{ required: true, message: '请选择客户' }]}>
            <Select
              showSearch
              placeholder="输入关键词搜索客户"
              filterOption={false}
              onSearch={searchCustomers}
              loading={customerLoading}
              notFoundContent={customerLoading ? '搜索中...' : '请输入关键词搜索'}
            >
              {customers.map((c: any) => (
                <Select.Option key={c.id} value={c.id}>{c.name}</Select.Option>
              ))}
            </Select>
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item name="sheetIndex" label="工作表序号" initialValue={1} tooltip="Excel底部的Sheet标签序号，从1开始">
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="headerRowIndex" label="表头所在行号" initialValue={2} tooltip="实际列名所在的Excel行号，从1开始（注意跳过合并标题行）">
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="dataStartRowIndex" label="数据起始行号" initialValue={3} tooltip="第一行数据所在的Excel行号，从1开始">
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Space>
            <Button type="primary" onClick={handleStep1}>下一步</Button>
          </Space>
        </Form>
      )}

      {currentStep === 1 && (
        <div>
          <Upload
            accept=".xlsx,.xls"
            maxCount={1}
            beforeUpload={file => { setSampleFile(file); return false; }}
            onRemove={() => setSampleFile(null)}
          >
            <Button icon={<UploadOutlined />}>上传样例Excel</Button>
          </Upload>
          <div style={{ color: '#888', fontSize: 12, marginTop: 8, marginBottom: 24 }}>
            上传后将自动解析表头列名
          </div>
          <Space>
            <Button onClick={() => setCurrentStep(0)}>上一步</Button>
            <Button type="primary" loading={parsing} onClick={handleStep2ParseHeaders}>
              解析表头
            </Button>
          </Space>
        </div>
      )}

      {currentStep === 2 && (
        <div>
          <div style={{ marginBottom: 12, color: '#555' }}>
            共解析到 <strong>{parsedHeaders.length}</strong> 列，请选择料号列：
          </div>
          <Table
            size="small"
            rowKey="name"
            columns={headerColumns}
            dataSource={parsedHeaders.map((name, index) => ({ index: index + 1, name }))}
            pagination={false}
            style={{ marginBottom: 16 }}
          />
          <Space>
            <Button onClick={() => setCurrentStep(1)}>上一步</Button>
            <Button type="primary" onClick={handleStep3}>下一步</Button>
          </Space>
        </div>
      )}

      {currentStep === 3 && basicInfo && (
        <div>
          <Descriptions bordered column={1} size="small" style={{ marginBottom: 24 }}>
            <Descriptions.Item label="模板名称">{basicInfo.name}</Descriptions.Item>
            <Descriptions.Item label="工作表序号">第 {basicInfo.sheetIndex ?? 1} 个</Descriptions.Item>
            <Descriptions.Item label="表头行">第 {basicInfo.headerRowIndex ?? 2} 行</Descriptions.Item>
            <Descriptions.Item label="数据起始行">第 {basicInfo.dataStartRowIndex ?? 3} 行</Descriptions.Item>
            <Descriptions.Item label="料号列">{selectedPartNoColumn}</Descriptions.Item>
          </Descriptions>
          <Space>
            <Button onClick={() => setCurrentStep(2)}>上一步</Button>
            <Button type="primary" loading={saving} onClick={handleSave}>
              确认保存
            </Button>
          </Space>
        </div>
      )}
    </Drawer>
  );
};

export default ExcelTemplateDrawer;
