import React, { useState, useCallback, useEffect, useRef } from 'react';
import { Input, Button, Space, Tag, Typography } from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import SelectableTable from '../../components/SelectableTable';
import { listProcesses } from '../../services/v6MasterDataService';
import type { ProcessMasterDTO } from '../../services/v6MasterDataService';
import V6ProcessDetailDrawer from './V6ProcessDetailDrawer';

const { Text } = Typography;

const PAGE_SIZE = 20;

const V6ProcessReadOnlyTab: React.FC = () => {
  const [keyword, setKeyword] = useState('');
  const [inputValue, setInputValue] = useState('');
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<ProcessMasterDTO[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);

  const [detailOpen, setDetailOpen] = useState(false);
  const [detailRecord, setDetailRecord] = useState<ProcessMasterDTO | null>(null);

  // 防抖 300ms
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

  const handleRefresh = () => {
    fetchData(keyword, page);
  };

  const openDetail = (record: ProcessMasterDTO) => {
    setDetailRecord(record);
    setDetailOpen(true);
  };

  const columns: ColumnsType<ProcessMasterDTO> = [
    {
      title: '工序编号',
      dataIndex: 'processNo',
      width: 160,
      render: (val: string, record) => (
        <Typography.Link onClick={() => openDetail(record)}>
          {val}
        </Typography.Link>
      ),
    },
    {
      title: '工序名称',
      dataIndex: 'processName',
      width: 180,
      render: (val: string) => val || '—',
    },
    {
      title: '工序分类',
      dataIndex: 'processCategory',
      width: 120,
      render: (val: string) => val || '—',
    },
    {
      title: '是否外协',
      dataIndex: 'isOutsource',
      width: 90,
      render: (val: boolean | undefined) =>
        val === true ? <Tag color="orange">外协</Tag> : val === false ? <Tag color="default">自制</Tag> : '—',
    },
    {
      title: '标准货币',
      dataIndex: 'standardCurrency',
      width: 100,
      render: (val: string) => val || '—',
    },
    {
      title: '标准单位',
      dataIndex: 'standardUnit',
      width: 90,
      render: (val: string) => val || '—',
    },
    {
      title: '默认不良率',
      dataIndex: 'defaultDefectRate',
      width: 100,
      render: (val: number) => val !== undefined && val !== null ? val : '—',
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 160,
      render: (val: string) => val || '—',
    },
  ];

  const toolbar = (
    <Space>
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
    </Space>
  );

  return (
    <>
      <SelectableTable<ProcessMasterDTO>
        rowKey="processNo"
        columns={columns}
        dataSource={data}
        loading={loading}
        toolbar={toolbar}
        actions={[]}
        locale={{
          emptyText: keyword ? `未找到匹配"${keyword}"的工序数据` : '暂无工序数据',
        }}
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
      <V6ProcessDetailDrawer
        open={detailOpen}
        record={detailRecord}
        onClose={() => setDetailOpen(false)}
      />
    </>
  );
};

export default V6ProcessReadOnlyTab;
