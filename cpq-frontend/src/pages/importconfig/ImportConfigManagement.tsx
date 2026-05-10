import React, { useEffect, useState } from 'react';
import {
  Table, Button, Select, Space, Popconfirm, message, Typography, Divider, Empty, Tag,
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, SettingOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { excelTemplateService } from '../../services/excelTemplateService';
import { importMappingService } from '../../services/importMappingService';
import { customerService } from '../../services/customerService';
import ExcelTemplateDrawer from './ExcelTemplateDrawer';

const { Title, Text } = Typography;

const ImportConfigManagement: React.FC = () => {
  const navigate = useNavigate();

  // Left panel state
  const [templates, setTemplates] = useState<any[]>([]);
  const [templatesLoading, setTemplatesLoading] = useState(false);
  const [selectedTemplateId, setSelectedTemplateId] = useState<string | null>(null);
  const [customerFilter, setCustomerFilter] = useState<string>('');
  const [customers, setCustomers] = useState<any[]>([]);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingTemplate, setEditingTemplate] = useState<any>(null);

  // Right panel state
  const [mappings, setMappings] = useState<any[]>([]);
  const [mappingsLoading, setMappingsLoading] = useState(false);
  const [selectedTemplate, setSelectedTemplate] = useState<any>(null);

  const fetchCustomers = async () => {
    try {
      const res = await customerService.list({ size: 200 });
      setCustomers(res.data?.content || []);
    } catch {
      // silently fail
    }
  };

  const fetchTemplates = async () => {
    setTemplatesLoading(true);
    try {
      const res = await excelTemplateService.list({
        customerId: customerFilter || undefined,
        size: 100,
      });
      setTemplates(res.data?.content || res.data || []);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setTemplatesLoading(false);
    }
  };

  const fetchMappings = async (excelTemplateId: string) => {
    setMappingsLoading(true);
    try {
      const res = await importMappingService.list({ excelTemplateId });
      setMappings(res.data?.content || res.data || []);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setMappingsLoading(false);
    }
  };

  useEffect(() => {
    fetchCustomers();
  }, []);

  useEffect(() => {
    fetchTemplates();
  }, [customerFilter]);

  const handleSelectTemplate = (record: any) => {
    setSelectedTemplateId(record.id);
    setSelectedTemplate(record);
    fetchMappings(record.id);
  };

  const handleDeleteTemplate = async (id: string) => {
    try {
      await excelTemplateService.delete(id);
      message.success('删除成功');
      if (selectedTemplateId === id) {
        setSelectedTemplateId(null);
        setSelectedTemplate(null);
        setMappings([]);
      }
      fetchTemplates();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const handleDeleteMapping = async (id: string) => {
    try {
      await importMappingService.delete(id);
      message.success('删除成功');
      if (selectedTemplateId) fetchMappings(selectedTemplateId);
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const templateColumns = [
    {
      title: '模板名称', dataIndex: 'name', key: 'name',
      render: (name: string, record: any) => (
        <a
          style={{ fontWeight: selectedTemplateId === record.id ? 600 : undefined }}
          onClick={() => handleSelectTemplate(record)}
        >
          {name}
        </a>
      ),
    },
    { title: '客户', dataIndex: 'customerName', key: 'customerName', width: 120, ellipsis: true },
    {
      title: '操作', key: 'actions', width: 80,
      render: (_: any, record: any) => (
        <Space size={4}>
          <Button
            type="text" size="small" icon={<EditOutlined />}
            onClick={e => { e.stopPropagation(); setEditingTemplate(record); setDrawerOpen(true); }}
          />
          <Popconfirm title="确认删除该Excel模板？" onConfirm={() => handleDeleteTemplate(record.id)}>
            <Button type="text" size="small" danger icon={<DeleteOutlined />} onClick={e => e.stopPropagation()} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const mappingColumns = [
    { title: '映射名称', dataIndex: 'name', key: 'name' },
    { title: 'CPQ模板', dataIndex: 'cpqTemplateName', key: 'cpqTemplateName', ellipsis: true },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (v: string) => (
        <Tag color={v === 'ACTIVE' ? 'green' : 'default'}>{v === 'ACTIVE' ? '启用' : '草稿'}</Tag>
      ),
    },
    {
      title: '操作', key: 'actions', width: 120,
      render: (_: any, record: any) => (
        <Space size={4}>
          <Button
            type="text" size="small" icon={<SettingOutlined />}
            onClick={() => navigate(`/import-config/mapping/${record.id}`)}
            title="编辑映射"
          />
          <Popconfirm title="确认删除该映射配置？" onConfirm={() => handleDeleteMapping(record.id)}>
            <Button type="text" size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ display: 'flex', gap: 0, height: '100%' }}>
      {/* Left panel */}
      <div style={{ width: 380, borderRight: '1px solid #f0f0f0', paddingRight: 16 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
          <Title level={5} style={{ margin: 0 }}>客户Excel模板</Title>
          <Button
            type="primary" size="small" icon={<PlusOutlined />}
            onClick={() => { setEditingTemplate(null); setDrawerOpen(true); }}
          >
            新建
          </Button>
        </div>
        <div style={{ marginBottom: 12 }}>
          <Select
            placeholder="筛选客户"
            allowClear
            style={{ width: '100%' }}
            onChange={v => setCustomerFilter(v || '')}
            showSearch
            filterOption={(input, option) =>
              String(option?.children || '').toLowerCase().includes(input.toLowerCase())
            }
          >
            {customers.map((c: any) => (
              <Select.Option key={c.id} value={c.id}>{c.name}</Select.Option>
            ))}
          </Select>
        </div>
        <Table
          rowKey="id"
          columns={templateColumns}
          dataSource={templates}
          loading={templatesLoading}
          size="small"
          pagination={false}
          rowClassName={(record) => record.id === selectedTemplateId ? 'ant-table-row-selected' : ''}
          onRow={(record) => ({ onClick: () => handleSelectTemplate(record), style: { cursor: 'pointer' } })}
        />
      </div>

      {/* Right panel */}
      <div style={{ flex: 1, paddingLeft: 16 }}>
        {selectedTemplate ? (
          <>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
              <div>
                <Title level={5} style={{ margin: 0 }}>{selectedTemplate.name}</Title>
                <Text type="secondary" style={{ fontSize: 12 }}>映射配置列表</Text>
              </div>
              <Button
                type="primary" size="small" icon={<PlusOutlined />}
                onClick={() => navigate(`/import-config/mapping/new?excelTemplateId=${selectedTemplateId}`)}
              >
                新建映射
              </Button>
            </div>
            <Divider style={{ margin: '0 0 12px' }} />
            <Table
              rowKey="id"
              columns={mappingColumns}
              dataSource={mappings}
              loading={mappingsLoading}
              size="small"
              pagination={false}
              locale={{ emptyText: '暂无映射配置，点击"新建映射"创建' }}
            />
          </>
        ) : (
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '60%' }}>
            <Empty description="请在左侧选择一个Excel模板" />
          </div>
        )}
      </div>

      <ExcelTemplateDrawer
        open={drawerOpen}
        editingRecord={editingTemplate}
        onClose={() => { setDrawerOpen(false); setEditingTemplate(null); }}
        onSuccess={() => { fetchTemplates(); }}
      />
    </div>
  );
};

export default ImportConfigManagement;
