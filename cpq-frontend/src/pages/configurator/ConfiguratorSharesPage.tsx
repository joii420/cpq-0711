import React, { useEffect, useState } from 'react';
import {
  Card, Table, Tag, Button, Input, Select, Space, message, Row, Col,
  Drawer, Popconfirm, Descriptions, Timeline, Empty,
} from 'antd';
import { configuratorShareService } from '../../services/configuratorService';
import StatCard from '../../components/StatCard';

const ConfiguratorSharesPage: React.FC = () => {
  const [data, setData] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [status, setStatus] = useState<string | undefined>();
  const [shareType, setShareType] = useState<string | undefined>();
  const [keyword, setKeyword] = useState('');
  const [stats, setStats] = useState<Record<string, number>>({});

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [current, setCurrent] = useState<any>(null);
  const [accessLog, setAccessLog] = useState<any[]>([]);

  const load = async () => {
    setLoading(true);
    try {
      const res: any = await configuratorShareService.list({ page, size, status, shareType, keyword });
      setData(res.data?.content || []);
      setTotal(res.data?.totalElements || 0);
      try {
        const stRes: any = await configuratorShareService.stats();
        setStats(stRes.data || {});
      } catch { /* */ }
    } catch (e: any) {
      message.error('加载失败：' + (e?.message || ''));
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { load(); /* eslint-disable-next-line */ }, [page, size, status, shareType]);

  const openDetail = async (s: any) => {
    setCurrent(s);
    setDrawerOpen(true);
    try {
      const res: any = await configuratorShareService.accessLog(s.id);
      setAccessLog(res.data || []);
    } catch { setAccessLog([]); }
  };

  const sendReminder = (token: string) => {
    message.success(`📧 已重发提醒邮件 · token=${token.substring(0, 12)}...`);
    // TODO 后续接邮件服务
  };

  const copyLink = (token: string) => {
    const url = `https://cpq.example.com/share/configurator/${token}`;
    if (navigator.clipboard) navigator.clipboard.writeText(url);
    message.success(`已复制 URL：${url}`);
  };
  const extend = async (id: string) => {
    try {
      await configuratorShareService.extend(id, 7);
      message.success('已延期 7 天');
      load();
    } catch (e: any) { message.error('延期失败：' + (e?.message || '')); }
  };
  const revoke = async (id: string, reason: string) => {
    try {
      await configuratorShareService.revoke(id, reason);
      message.success('已吊销');
      load();
      setDrawerOpen(false);
    } catch (e: any) { message.error('吊销失败：' + (e?.message || '')); }
  };

  return (
    <div style={{ padding: 16 }}>
      <Card title="🔗 我分享的链接" extra={
        <Space>
          <Input.Search placeholder="token / 实例 / 邮箱" allowClear style={{ width: 240 }}
                        value={keyword} onChange={e => setKeyword(e.target.value)} onSearch={load} />
          <Select placeholder="类型" allowClear style={{ width: 160 }}
                  options={[
                    { value: 'CUSTOMER_SELF', label: 'CUSTOMER_SELF' },
                    { value: 'INTERNAL', label: 'INTERNAL' },
                    { value: 'PUBLIC_PRESET', label: 'PUBLIC_PRESET' },
                  ]}
                  value={shareType} onChange={setShareType} />
          <Select placeholder="状态" allowClear style={{ width: 130 }}
                  options={[
                    { value: 'ACTIVE', label: 'ACTIVE' },
                    { value: 'EXPIRED', label: 'EXPIRED' },
                    { value: 'REVOKED', label: 'REVOKED' },
                  ]}
                  value={status} onChange={setStatus} />
        </Space>
      }>
        <Row gutter={10} style={{ marginBottom: 14 }}>
          <Col span={6}><StatCard tone="success" icon="🟢" label="ACTIVE 活跃" value={stats.ACTIVE || 0} sub="未过期" onClick={() => setStatus('ACTIVE')} /></Col>
          <Col span={6}><StatCard tone="primary" icon="👁" label="已被访问" value={stats.ACCESSED || 0} sub="客户至少打开 1 次" /></Col>
          <Col span={6}><StatCard tone="gray" icon="⏰" label="EXPIRED 已过期" value={stats.EXPIRED || 0} sub="超过有效期" onClick={() => setStatus('EXPIRED')} /></Col>
          <Col span={6}><StatCard tone="red" icon="🚫" label="REVOKED 已吊销" value={stats.REVOKED || 0} sub="主动作废" onClick={() => setStatus('REVOKED')} /></Col>
        </Row>

        <Table rowKey="id" loading={loading} dataSource={data}
          pagination={{ current: page + 1, pageSize: size, total,
            onChange: (p, s) => { setPage(p - 1); setSize(s); } }}
          columns={[
            { title: 'share_token', dataIndex: 'shareToken', width: 180,
              render: (v: string) => <code style={{ fontSize: 11 }}>{v}</code> },
            { title: '接收人', width: 200, render: (_, r) => r.sharedToEmail || <span style={{ color: '#999' }}>—</span> },
            { title: '关联实例', dataIndex: 'instanceId', width: 130,
              render: (v: string) => <code style={{ fontSize: 11 }}>{v?.substring(0, 8)}</code> },
            { title: '类型', dataIndex: 'shareType', width: 140, render: (v) => <Tag color="purple">{v}</Tag> },
            { title: '过期', dataIndex: 'expiresAt', width: 150,
              render: (v: string) => v ? new Date(v).toLocaleString().slice(0, 16) : '-' },
            { title: '访问次数', dataIndex: 'accessCount', width: 90, align: 'center',
              render: (v: number) => v > 0 ? <b style={{ color: '#1890ff' }}>{v} 次</b> : <span style={{ color: '#999' }}>0 次</span> },
            { title: '状态', dataIndex: 'status', width: 100, render: (s: string) => {
              const color = s === 'ACTIVE' ? 'green' : s === 'EXPIRED' ? 'default' : 'red';
              return <Tag color={color}>● {s}</Tag>;
            }},
            { title: '操作', width: 220, render: (_, r) => (
              <Space size={4}>
                <a onClick={() => openDetail(r)}>👁 详情</a>
                <a onClick={() => copyLink(r.shareToken)}>📋 复制</a>
                {r.status === 'ACTIVE' && (
                  <Popconfirm title="延期 7 天？" onConfirm={() => extend(r.id)}>
                    <a>⏰ 延期</a>
                  </Popconfirm>
                )}
                {r.status === 'EXPIRED' && (
                  <Popconfirm title="重新激活并延期 7 天？" onConfirm={() => extend(r.id)}>
                    <a>♻ 激活</a>
                  </Popconfirm>
                )}
                {r.status === 'ACTIVE' && (
                  <Popconfirm title="吊销此链接？客户将无法再访问。" onConfirm={() => revoke(r.id, '管理员吊销')}>
                    <a style={{ color: '#f5222d' }}>🚫 吊销</a>
                  </Popconfirm>
                )}
              </Space>
            )},
          ]}
        />
      </Card>

      <Drawer title={`链接详情 · ${current?.shareToken || ''}`}
              width={580}
              open={drawerOpen} onClose={() => setDrawerOpen(false)}>
        {current && (
          <>
            <Descriptions size="small" bordered column={1}>
              <Descriptions.Item label="token"><code>{current.shareToken}</code></Descriptions.Item>
              <Descriptions.Item label="完整 URL">
                <code style={{ fontSize: 10.5 }}>https://cpq.example.com/share/configurator/{current.shareToken}</code>
              </Descriptions.Item>
              <Descriptions.Item label="类型"><Tag color="purple">{current.shareType}</Tag></Descriptions.Item>
              <Descriptions.Item label="状态"><Tag color={current.status === 'ACTIVE' ? 'green' : current.status === 'EXPIRED' ? 'default' : 'red'}>● {current.status}</Tag></Descriptions.Item>
              <Descriptions.Item label="允许修改">{current.canModify ? '✓ 可修改' : '✗ 仅查看'}</Descriptions.Item>
              <Descriptions.Item label="接收人邮箱">{current.sharedToEmail || '-'}</Descriptions.Item>
              <Descriptions.Item label="创建时间">{current.createdAt ? new Date(current.createdAt).toLocaleString() : '-'}</Descriptions.Item>
              <Descriptions.Item label="过期时间">{current.expiresAt ? new Date(current.expiresAt).toLocaleString() : '-'}</Descriptions.Item>
              <Descriptions.Item label="访问次数">{current.accessCount || 0} 次</Descriptions.Item>
              <Descriptions.Item label="最后访问">{current.lastAccessedAt ? new Date(current.lastAccessedAt).toLocaleString() : '尚未访问'}</Descriptions.Item>
              <Descriptions.Item label="关联实例 ID"><code style={{ fontSize: 11 }}>{current.instanceId}</code></Descriptions.Item>
              {current.revokedAt && (
                <Descriptions.Item label="吊销时间">{new Date(current.revokedAt).toLocaleString()}</Descriptions.Item>
              )}
              {current.revokeReason && (
                <Descriptions.Item label="吊销原因">{current.revokeReason}</Descriptions.Item>
              )}
            </Descriptions>

            <div style={{ marginTop: 16 }}>
              <Space wrap>
                <Button onClick={() => copyLink(current.shareToken)}>📋 复制 URL</Button>
                {current.status === 'ACTIVE' && (
                  <>
                    <Popconfirm title="延期 7 天？" onConfirm={() => extend(current.id)}>
                      <Button>⏰ 延期 7 天</Button>
                    </Popconfirm>
                    <Button onClick={() => sendReminder(current.shareToken)}>📧 重发提醒</Button>
                    <Popconfirm title="吊销此链接？" onConfirm={() => revoke(current.id, '管理员吊销')}>
                      <Button danger>🚫 吊销</Button>
                    </Popconfirm>
                  </>
                )}
                {current.status === 'EXPIRED' && (
                  <Popconfirm title="重新激活并延期 7 天？" onConfirm={() => extend(current.id)}>
                    <Button>♻ 重新激活</Button>
                  </Popconfirm>
                )}
              </Space>
            </div>

            <div style={{ marginTop: 18, marginBottom: 6, fontWeight: 600, fontSize: 13 }}>
              📊 访问日志 ({accessLog.length})
            </div>
            {accessLog.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
                     description={<span style={{ fontSize: 11, color: '#999' }}>尚未有访问记录</span>}
                     style={{ padding: 14 }} />
            ) : (
              <Timeline mode="left"
                items={accessLog.map((a: any) => ({
                  label: <span style={{ fontSize: 11 }}>{new Date(a.accessedAt).toLocaleString()}</span>,
                  children: (
                    <div style={{ fontSize: 12 }}>
                      <div>{a.action || '查看'}</div>
                      <div style={{ fontSize: 11, color: '#999' }}>{a.ip || '-'} · {a.userAgent ? a.userAgent.substring(0, 40) + '...' : '-'}</div>
                    </div>
                  ),
                }))}
              />
            )}
          </>
        )}
      </Drawer>
    </div>
  );
};

export default ConfiguratorSharesPage;
