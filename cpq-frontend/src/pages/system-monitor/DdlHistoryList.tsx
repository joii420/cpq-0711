import React, { useEffect, useState } from 'react';
import {
  Table,
  Tag,
  Button,
  Select,
  Space,
  Typography,
  Tooltip,
  message,
} from 'antd';
import { CopyOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { DdlOperationDTO, DdlStatus } from '../../types/ddl-extension';
import { ddlExtensionService } from '../../services/ddlExtensionService';
import dayjs from 'dayjs';

const { Text } = Typography;
const { Option } = Select;

interface Props {
  refreshTrigger?: number;
}

const importanceLabel: Record<string, string> = {
  CRITICAL: '关键',
  IMPORTANT: '重要',
  NORMAL: '普通',
};

const importanceColor: Record<string, string> = {
  CRITICAL: 'red',
  IMPORTANT: 'orange',
  NORMAL: 'default',
};

const DdlHistoryList: React.FC<Props> = ({ refreshTrigger }) => {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<DdlOperationDTO[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize] = useState(10);
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);

  const fetchData = async (p = page, status = statusFilter) => {
    setLoading(true);
    try {
      const res = await ddlExtensionService.history({
        page: p - 1,
        size: pageSize,
        status: status || undefined,
      });
      setData(res.data.content);
      setTotal(res.data.totalElements);
    } catch (err: any) {
      message.error(err.message || '加载历史记录失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData(1, statusFilter);
    setPage(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refreshTrigger]);

  const handleStatusChange = (val: string | undefined) => {
    setStatusFilter(val);
    setPage(1);
    fetchData(1, val);
  };

  const handlePageChange = (p: number) => {
    setPage(p);
    fetchData(p, statusFilter);
  };

  const handleCopyMigration = async (record: DdlOperationDTO) => {
    try {
      await navigator.clipboard.writeText(record.migrationContent);
      message.success('已复制 Migration SQL');
    } catch {
      message.error('复制失败，请手动复制');
    }
  };

  const columns: ColumnsType<DdlOperationDTO> = [
    {
      title: '目标表',
      dataIndex: 'tableName',
      key: 'tableName',
      width: 160,
      render: (val) => <Text code>{val}</Text>,
    },
    {
      title: '字段名',
      dataIndex: 'columnName',
      key: 'columnName',
      width: 160,
      render: (val) => <Text code>{val}</Text>,
    },
    {
      title: '数据类型',
      dataIndex: 'dataType',
      key: 'dataType',
      width: 140,
      render: (val) => <Text type="secondary">{val}</Text>,
    },
    {
      title: '重要性',
      dataIndex: 'importance',
      key: 'importance',
      width: 90,
      render: (val: string) => (
        <Tag color={importanceColor[val]}>{importanceLabel[val] || val}</Tag>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (val: DdlStatus) =>
        val === 'SUCCESS' ? (
          <Tag color="success">成功</Tag>
        ) : (
          <Tag color="error">失败</Tag>
        ),
    },
    {
      title: '失败原因',
      dataIndex: 'errorMessage',
      key: 'errorMessage',
      ellipsis: true,
      render: (val) =>
        val ? (
          <Tooltip title={val}>
            <Text type="danger" ellipsis style={{ maxWidth: 200 }}>
              {val}
            </Text>
          </Tooltip>
        ) : (
          <Text type="secondary">—</Text>
        ),
    },
    {
      title: '操作人',
      dataIndex: 'createdByName',
      key: 'createdByName',
      width: 100,
    },
    {
      title: '执行时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
      render: (val) => dayjs(val).format('YYYY-MM-DD HH:mm'),
    },
    {
      title: '操作',
      key: 'actions',
      width: 110,
      fixed: 'right',
      render: (_, record) => (
        <Button
          type="link"
          size="small"
          icon={<CopyOutlined />}
          onClick={() => handleCopyMigration(record)}
        >
          复制 Migration
        </Button>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Space>
          <span style={{ fontWeight: 500 }}>状态筛选：</span>
          <Select
            placeholder="全部状态"
            style={{ width: 120 }}
            allowClear
            value={statusFilter}
            onChange={handleStatusChange}
          >
            <Option value="SUCCESS">成功</Option>
            <Option value="FAILED">失败</Option>
          </Select>
        </Space>
        <Button
          icon={<ReloadOutlined />}
          onClick={() => fetchData(page, statusFilter)}
          loading={loading}
        >
          刷新
        </Button>
      </div>
      <Table<DdlOperationDTO>
        rowKey="id"
        loading={loading}
        dataSource={data}
        columns={columns}
        scroll={{ x: 1100 }}
        pagination={{
          current: page,
          pageSize,
          total,
          showSizeChanger: false,
          showTotal: (t) => `共 ${t} 条`,
          onChange: handlePageChange,
        }}
        locale={{ emptyText: '暂无扩列记录' }}
      />
    </div>
  );
};

export default DdlHistoryList;
