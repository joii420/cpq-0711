import React, { useEffect, useState } from 'react';
import { Table, Button, Tag, Card, message } from 'antd';
import { CheckOutlined } from '@ant-design/icons';
import { notificationService } from '../../services/notificationService';

const typeMap: Record<string, { label: string; color: string }> = {
  APPROVAL_SUBMITTED: { label: '审批提交', color: 'blue' },
  APPROVAL_APPROVED: { label: '审批通过', color: 'success' },
  APPROVAL_REJECTED: { label: '审批驳回', color: 'error' },
  APPROVAL_REMINDER: { label: '审批提醒', color: 'warning' },
  PASSWORD_RESET: { label: '密码重置', color: 'purple' },
  ROLE_CHANGED: { label: '角色变更', color: 'orange' },
  SYSTEM: { label: '系统通知', color: 'default' },
};

const NotificationList: React.FC = () => {
  const [data, setData] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(0);
  const [total, setTotal] = useState(0);
  const size = 20;

  const fetchData = async (p = page) => {
    setLoading(true);
    try {
      const res = await notificationService.list({ page: p, size });
      setData(res.data || []);
      setTotal(res.data?.length || 0);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, [page]);

  const handleMarkRead = async (id: string) => {
    try {
      await notificationService.markRead(id);
      message.success('已标记为已读');
      fetchData();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const handleMarkAllRead = async () => {
    try {
      await notificationService.markAllRead();
      message.success('全部已标记为已读');
      fetchData();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const columns = [
    {
      title: '类型',
      dataIndex: 'type',
      key: 'type',
      width: 120,
      render: (v: string) => {
        const m = typeMap[v] || { label: v, color: 'default' };
        return <Tag color={m.color}>{m.label}</Tag>;
      },
    },
    { title: '标题', dataIndex: 'title', key: 'title', ellipsis: true },
    { title: '内容', dataIndex: 'content', key: 'content', ellipsis: true, render: (v: string) => v || '-' },
    {
      title: '状态',
      dataIndex: 'isRead',
      key: 'isRead',
      width: 80,
      render: (v: boolean) => <Tag color={v ? 'default' : 'blue'}>{v ? '已读' : '未读'}</Tag>,
    },
    { title: '时间', dataIndex: 'createdAt', key: 'createdAt', width: 180, render: (v: string) => v ? new Date(v).toLocaleString() : '-' },
    {
      title: '操作',
      key: 'actions',
      width: 100,
      render: (_: any, record: any) => (
        !record.isRead ? (
          <Button size="small" icon={<CheckOutlined />} onClick={() => handleMarkRead(record.id)}>
            标记已读
          </Button>
        ) : null
      ),
    },
  ];

  return (
    <Card
      title="通知列表"
      extra={
        <Button onClick={handleMarkAllRead} icon={<CheckOutlined />}>
          全部已读
        </Button>
      }
    >
      <Table
        rowKey="id"
        columns={columns}
        dataSource={data}
        loading={loading}
        pagination={{
          current: page + 1,
          pageSize: size,
          total,
          onChange: (p) => setPage(p - 1),
          showTotal: (t) => `共 ${t} 条`,
        }}
      />
    </Card>
  );
};

export default NotificationList;
