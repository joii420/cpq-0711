import React, { useState, useCallback, useEffect, useRef } from 'react';
import {
  Input, Button, Space, Tag, Typography, Drawer, Form, Switch, InputNumber, message,
} from 'antd';
import { ReloadOutlined, SearchOutlined, PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import SelectableTable, { runBatch } from '../../components/SelectableTable';
import {
  listProcesses, createProcess, updateProcess, deleteProcess,
} from '../../services/v6MasterDataService';
import type { ProcessMasterDTO, ProcessMasterUpsert } from '../../services/v6MasterDataService';
import V6ProcessDetailDrawer from './V6ProcessDetailDrawer';

const PAGE_SIZE = 20;

const V6ProcessCrudTab: React.FC = () => {
  const [keyword, setKeyword] = useState('');
  const [inputValue, setInputValue] = useState('');
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<ProcessMasterDTO[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);

  // 详情(只读)抽屉
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailRecord, setDetailRecord] = useState<ProcessMasterDTO | null>(null);

  // 新建/编辑 表单抽屉
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<ProcessMasterDTO | null>(null); // null = 新建
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<ProcessMasterUpsert>();

  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const fetchData = useCallback(async (kw: string, pg: number) => {
    setLoading(true);
    try {
      const result = await listProcesses({ keyword: kw || undefined, page: pg - 1, size: PAGE_SIZE });
      setData(result.content);
      setTotal(result.totalElements);
    } catch {
      setData([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData(keyword, page);
  }, [keyword, page, fetchData]);

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = e.target.value;
    setInputValue(val);
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
    debounceTimer.current = setTimeout(() => {
      setKeyword(val);
      setPage(1);
    }, 300);
  };

  const handleRefresh = () => fetchData(keyword, page);

  const openDetail = (record: ProcessMasterDTO) => {
    setDetailRecord(record);
    setDetailOpen(true);
  };

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ isOutsource: false });
    setFormOpen(true);
  };

  const openEdit = (record: ProcessMasterDTO) => {
    setEditing(record);
    form.setFieldsValue({
      processNo: record.processNo,
      processName: record.processName,
      processCategory: record.processCategory,
      isOutsource: record.isOutsource ?? false,
      standardCurrency: record.standardCurrency,
      standardUnit: record.standardUnit,
      defaultDefectRate: record.defaultDefectRate,
    });
    setFormOpen(true);
  };

  const handleSubmit = async () => {
    let values: ProcessMasterUpsert;
    try {
      values = await form.validateFields();
    } catch {
      return; // 校验失败, AntD 已高亮
    }
    setSaving(true);
    try {
      if (editing) {
        await updateProcess(editing.id, values);
        message.success('工序已更新');
      } else {
        await createProcess(values);
        message.success('工序已新建');
      }
      setFormOpen(false);
      fetchData(keyword, page);
    } catch (e: any) {
      message.error(e?.message ?? (editing ? '更新失败' : '新建失败'));
    } finally {
      setSaving(false);
    }
  };

  const columns: ColumnsType<ProcessMasterDTO> = [
    {
      title: '工序编号',
      dataIndex: 'processNo',
      width: 160,
      render: (val: string, record) => (
        <Typography.Link onClick={() => openDetail(record)}>{val}</Typography.Link>
      ),
    },
    { title: '工序名称', dataIndex: 'processName', width: 180, render: (v: string) => v || '—' },
    { title: '工序分类', dataIndex: 'processCategory', width: 120, render: (v: string) => v || '—' },
    {
      title: '是否外协',
      dataIndex: 'isOutsource',
      width: 90,
      render: (v: boolean | undefined) =>
        v === true ? <Tag color="orange">外协</Tag> : v === false ? <Tag color="default">自制</Tag> : '—',
    },
    { title: '标准货币', dataIndex: 'standardCurrency', width: 100, render: (v: string) => v || '—' },
    { title: '标准单位', dataIndex: 'standardUnit', width: 90, render: (v: string) => v || '—' },
    {
      title: '默认不良率',
      dataIndex: 'defaultDefectRate',
      width: 100,
      render: (v: number) => (v !== undefined && v !== null ? v : '—'),
    },
    { title: '更新时间', dataIndex: 'updatedAt', width: 160, render: (v: string) => v || '—' },
  ];

  const toolbar = (
    <Space wrap>
      <Input
        prefix={<SearchOutlined />}
        placeholder="搜索工序编号 / 名称"
        value={inputValue}
        onChange={handleInputChange}
        allowClear
        style={{ width: 240 }}
        onClear={() => { setInputValue(''); setKeyword(''); setPage(1); }}
      />
      <Button icon={<ReloadOutlined />} onClick={handleRefresh}>刷新</Button>
      <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增工序</Button>
    </Space>
  );

  return (
    <>
      <SelectableTable<ProcessMasterDTO>
        rowKey="id"
        columns={columns}
        dataSource={data}
        loading={loading}
        rowLabel={(r) => `${r.processNo} ${r.processName}`}
        toolbar={toolbar}
        actions={[
          {
            key: 'edit',
            label: '编辑',
            icon: <EditOutlined />,
            enabledWhen: (rows) => (rows.length === 1 ? true : '编辑一次只能选一行'),
            onClick: (rows) => openEdit(rows[0]),
          },
          {
            key: 'delete',
            label: '删除',
            icon: <DeleteOutlined />,
            danger: true,
            needsConfirm: true,
            confirmTitle: '确认删除选中的 {N} 个工序?',
            confirmDescription: '硬删除不可恢复。若被报价单工序引用, 引用处会回退显示工序代码(不影响报价单本身)。',
            enabledWhen: (rows) => (rows.length >= 1 ? true : '请先选择要删除的工序'),
            onClick: async (rows) => {
              await runBatch(
                rows,
                (r) => deleteProcess(r.id),
                { rowLabel: (r) => `${r.processNo} ${r.processName}`, successMsg: `已删除 ${rows.length} 个工序` },
              );
              fetchData(keyword, page);
            },
          },
        ]}
        locale={{ emptyText: keyword ? `未找到匹配"${keyword}"的工序数据` : '暂无工序数据' }}
        pagination={{
          current: page,
          pageSize: PAGE_SIZE,
          total,
          showSizeChanger: false,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p) => setPage(p),
        }}
        scroll={{ x: 900 }}
      />

      <V6ProcessDetailDrawer open={detailOpen} record={detailRecord} onClose={() => setDetailOpen(false)} />

      <Drawer
        title={editing ? `编辑工序 · ${editing.processNo}` : '新建工序'}
        placement="right"
        width={480}
        open={formOpen}
        onClose={() => setFormOpen(false)}
        destroyOnClose
        footer={
          <Space style={{ float: 'right' }}>
            <Button onClick={() => setFormOpen(false)}>取消</Button>
            <Button type="primary" loading={saving} onClick={handleSubmit}>保存</Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical" initialValues={{ isOutsource: false }}>
          <Form.Item
            label="工序编号"
            name="processNo"
            rules={[{ required: true, message: '请输入工序编号' }, { max: 20, message: '不超过 20 字符' }]}
            extra={editing ? '工序编号为业务主键, 编辑时不可修改' : undefined}
          >
            <Input placeholder="如 OP-001" disabled={!!editing} />
          </Form.Item>
          <Form.Item
            label="工序名称"
            name="processName"
            rules={[{ required: true, message: '请输入工序名称' }, { max: 50, message: '不超过 50 字符' }]}
          >
            <Input placeholder="如 冲压" />
          </Form.Item>
          <Form.Item label="工序分类" name="processCategory">
            <Input placeholder="如 制造 / 组装 / 电镀 / 包装 / 清洗" />
          </Form.Item>
          <Form.Item label="是否外协" name="isOutsource" valuePropName="checked">
            <Switch checkedChildren="外协" unCheckedChildren="自制" />
          </Form.Item>
          <Form.Item label="标准货币" name="standardCurrency">
            <Input placeholder="如 CNY" />
          </Form.Item>
          <Form.Item label="标准单位" name="standardUnit">
            <Input placeholder="如 PCS / KG" />
          </Form.Item>
          <Form.Item label="默认不良率" name="defaultDefectRate">
            <InputNumber min={0} step={0.01} style={{ width: '100%' }} placeholder="如 0.02 表示 2%" />
          </Form.Item>
        </Form>
      </Drawer>
    </>
  );
};

export default V6ProcessCrudTab;
