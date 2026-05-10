import React, { useEffect, useState } from 'react';
import {
  Button, Modal, Form, Input, Space, message, Upload, Select,
} from 'antd';
import { PlusOutlined, DeleteOutlined, UploadOutlined } from '@ant-design/icons';
import { materialMappingService } from '../../services/materialMappingService';
import { internalMaterialService } from '../../services/internalMaterialService';
import SelectableTable, { runBatch, type ToolbarAction } from '../../components/SelectableTable';

interface CustomerMaterialMappingTabProps {
  customerId: string;
}

const CustomerMaterialMappingTab: React.FC<CustomerMaterialMappingTabProps> = ({ customerId }) => {
  const [data, setData] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [form] = Form.useForm();
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const [importModalOpen, setImportModalOpen] = useState(false);
  const [importFile, setImportFile] = useState<File | null>(null);
  const [importLoading, setImportLoading] = useState(false);
  const [materialOptions, setMaterialOptions] = useState<any[]>([]);
  const [materialSearchLoading, setMaterialSearchLoading] = useState(false);

  const fetchData = async () => {
    setLoading(true);
    try {
      const res = await materialMappingService.list(customerId, {
        page, size: 20, keyword: keyword || undefined,
      });
      setData(res.data?.content || res.data || []);
      setTotal(res.data?.totalElements || 0);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchData(); }, [customerId, page, keyword]);

  const searchMaterials = async (value: string) => {
    if (!value) return;
    setMaterialSearchLoading(true);
    try {
      const res = await internalMaterialService.list({ keyword: value, size: 20 });
      setMaterialOptions(res.data?.content || res.data || []);
    } catch {
      // silently fail
    } finally {
      setMaterialSearchLoading(false);
    }
  };

  const handleCreate = async (values: any) => {
    try {
      await materialMappingService.create(customerId, values);
      message.success('添加成功');
      setModalOpen(false);
      form.resetFields();
      setMaterialOptions([]);
      fetchData();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const handleImport = async () => {
    if (!importFile) {
      message.warning('请选择文件');
      return;
    }
    setImportLoading(true);
    try {
      await materialMappingService.importExcel(customerId, importFile);
      message.success('导入成功');
      setImportModalOpen(false);
      setImportFile(null);
      fetchData();
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setImportLoading(false);
    }
  };

  const columns = [
    { title: '客户料号', dataIndex: 'customerPartNo', key: 'customerPartNo', width: 160 },
    { title: '内部料号', dataIndex: 'materialNo', key: 'materialNo', width: 160 },
    { title: '物料名称', dataIndex: 'materialName', key: 'materialName' },
    { title: '规格', dataIndex: 'specification', key: 'specification', ellipsis: true },
  ];

  const actions: ToolbarAction<any>[] = [
    {
      key: 'delete', label: '删除映射', icon: <DeleteOutlined />, danger: true,
      enabledWhen: (rows) => rows.length > 0 ? true : false,
      needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 条映射关系？',
      onClick: async (rows) => {
        await runBatch(rows, (r: any) => materialMappingService.delete(customerId, r.id).then(() => undefined), {
          rowLabel: (r: any) => `${r.customerPartNo} → ${r.materialNo}`,
          successMsg: `已删除 ${rows.length} 项`,
        });
        fetchData();
      },
    },
  ];

  const toolbar = (
    <>
      <Input.Search
        placeholder="搜索客户料号/内部料号"
        onSearch={v => { setKeyword(v); setPage(0); }}
        allowClear
        style={{ width: 260 }}
      />
      <Space>
        <Button icon={<UploadOutlined />} onClick={() => setImportModalOpen(true)}>Excel导入</Button>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => { form.resetFields(); setMaterialOptions([]); setModalOpen(true); }}
        >
          添加映射
        </Button>
      </Space>
    </>
  );

  return (
    <div>
      <SelectableTable<any>
        rowKey="id"
        columns={columns}
        dataSource={data}
        loading={loading}
        size="small"
        pagination={{
          current: page + 1,
          pageSize: 20,
          total,
          showTotal: t => `共 ${t} 条`,
          onChange: p => setPage(p - 1),
        }}
        toolbar={toolbar}
        actions={actions}
        rowLabel={(r: any) => `${r.customerPartNo} → ${r.materialNo}（${r.materialName ?? ''}）`}
      />

      <Modal
        title="添加客户料号映射"
        open={modalOpen}
        onCancel={() => { setModalOpen(false); form.resetFields(); }}
        onOk={() => form.submit()}
        okText="保存"
        destroyOnClose
      >
        <Form form={form} layout="vertical" onFinish={handleCreate}>
          <Form.Item name="customerPartNo" label="客户料号" rules={[{ required: true, message: '请输入客户料号' }]}>
            <Input placeholder="请输入客户使用的料号" />
          </Form.Item>
          <Form.Item name="internalMaterialId" label="内部物料" rules={[{ required: true, message: '请选择内部物料' }]}>
            <Select
              showSearch placeholder="输入关键词搜索内部物料"
              filterOption={false}
              onSearch={searchMaterials}
              loading={materialSearchLoading}
              notFoundContent={materialSearchLoading ? '搜索中...' : '暂无数据'}
            >
              {materialOptions.map((m: any) => (
                <Select.Option key={m.id} value={m.id}>{m.materialNo} - {m.name}</Select.Option>
              ))}
            </Select>
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="Excel批量导入映射"
        open={importModalOpen}
        onCancel={() => { setImportModalOpen(false); setImportFile(null); }}
        onOk={handleImport}
        confirmLoading={importLoading}
        okText="开始导入"
      >
        <Upload
          accept=".xlsx,.xls"
          maxCount={1}
          beforeUpload={file => { setImportFile(file); return false; }}
          onRemove={() => setImportFile(null)}
        >
          <Button icon={<UploadOutlined />}>选择Excel文件</Button>
        </Upload>
        <div style={{ marginTop: 8, color: '#888', fontSize: 12 }}>
          支持 .xlsx / .xls 格式，文件需包含：客户料号、内部料号 两列
        </div>
      </Modal>
    </div>
  );
};

export default CustomerMaterialMappingTab;
