import React, { useEffect, useState } from 'react';
import {
  Card, Table, Tag, Button, Select, Space, message, Row, Col, Popconfirm,
  Modal, Input, Descriptions, DatePicker,
} from 'antd';
import { useNavigate } from 'react-router-dom';
import { configuratorInstanceService } from '../../services/configuratorService';
import type { ConfiguratorInstance, InstanceStatus } from '../../types/configurator';
import StatCard from '../../components/StatCard';

const ConfiguratorInstanceList: React.FC = () => {
  const navigate = useNavigate();
  const [data, setData] = useState<ConfiguratorInstance[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [status, setStatus] = useState<string | undefined>();
  const [statusCounts, setStatusCounts] = useState<Record<string, number>>({});

  // 绑定到已有报价单 Modal
  const [linkOpen, setLinkOpen] = useState(false);
  const [linkInstance, setLinkInstance] = useState<ConfiguratorInstance | null>(null);
  const [linkQuotationId, setLinkQuotationId] = useState('');

  // 批量操作
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);

  const load = async () => {
    setLoading(true);
    try {
      const res: any = await configuratorInstanceService.list({ page, size, status });
      setData(res.data?.content || []);
      setTotal(res.data?.totalElements || 0);
      const counts: Record<string, number> = { DRAFT: 0, SUBMITTED: 0, LINKED: 0, EXPIRED: 0 };
      for (const s of ['DRAFT', 'SUBMITTED', 'LINKED', 'EXPIRED']) {
        try {
          const r: any = await configuratorInstanceService.list({ page: 0, size: 1, status: s });
          counts[s] = r.data?.totalElements || 0;
        } catch { /* */ }
      }
      setStatusCounts(counts);
    } catch (e: any) {
      message.error('加载失败：' + (e?.message || ''));
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { load(); /* eslint-disable-next-line */ }, [page, size, status]);

  const unlink = async (id: string) => {
    try {
      await configuratorInstanceService.unlink(id);
      message.success('已解绑');
      load();
    } catch (e: any) { message.error('解绑失败：' + (e?.message || '')); }
  };
  const del = async (id: string) => {
    try {
      await configuratorInstanceService.delete(id);
      message.success('已删除');
      load();
    } catch (e: any) { message.error('删除失败：' + (e?.message || '')); }
  };
  const newQuotation = async (inst: ConfiguratorInstance) => {
    try {
      const res: any = await configuratorInstanceService.linkAction(inst.id, 'NEW_QUOTATION');
      const r = res.data || {};
      message.success(`✓ 已生成报价单<br/>料号 ${r.part_no} · quotation_id ${(r.quotation_id || '').toString().substring(0, 8)}...`);
      load();
    } catch (e: any) { message.error('生成失败：' + (e?.message || '')); }
  };
  const openLinkExisting = (inst: ConfiguratorInstance) => {
    setLinkInstance(inst);
    setLinkQuotationId('');
    setLinkOpen(true);
  };
  const confirmLink = async () => {
    if (!linkInstance || !linkQuotationId.trim()) {
      message.warning('请输入报价单 UUID');
      return;
    }
    try {
      await configuratorInstanceService.linkAction(linkInstance.id, 'LINK_EXISTING', linkQuotationId);
      message.success('已绑定到报价单');
      setLinkOpen(false);
      load();
    } catch (e: any) { message.error('绑定失败：' + (e?.message || '')); }
  };

  return (
    <div style={{ padding: 16 }}>
      <Card title="📋 选配实例列表" extra={
        <Space>
          <Select placeholder="状态" allowClear style={{ width: 160 }}
                  options={[
                    { value: 'DRAFT', label: 'DRAFT 草稿' },
                    { value: 'SUBMITTED', label: 'SUBMITTED 已提交' },
                    { value: 'LINKED', label: 'LINKED 已关联报价单' },
                    { value: 'EXPIRED', label: 'EXPIRED 已过期' },
                  ]}
                  value={status} onChange={setStatus} />
          <DatePicker.RangePicker placeholder={['创建起', '创建止']} style={{ width: 220 }}
                                  onChange={() => message.info('日期筛选 — 后端待实现')} />
          {selectedRowKeys.length > 0 && (
            <>
              <span style={{ fontSize: 12, color: '#666' }}>已选 {selectedRowKeys.length}</span>
              <Button onClick={() => message.info(`批量导出 ${selectedRowKeys.length} 个实例`)}>📤 批量导出</Button>
              <Popconfirm title={`确认批量删除 ${selectedRowKeys.length} 个实例？`}
                          onConfirm={() => { message.success('已批量删除（mock）'); setSelectedRowKeys([]); load(); }}>
                <Button danger>🗑 批量删除</Button>
              </Popconfirm>
            </>
          )}
          <Button type="primary" onClick={() => navigate('/configurator/start')}>+ 新建选配</Button>
        </Space>
      }>
        <Row gutter={10} style={{ marginBottom: 14 }}>
          <Col span={6}><StatCard tone="primary" icon="📝" label="DRAFT 草稿" value={statusCounts.DRAFT || 0} sub="待提交" onClick={() => setStatus('DRAFT')} /></Col>
          <Col span={6}><StatCard tone="purple" icon="📤" label="SUBMITTED 待关联" value={statusCounts.SUBMITTED || 0} sub="可绑/新建报价单" onClick={() => setStatus('SUBMITTED')} /></Col>
          <Col span={6}><StatCard tone="success" icon="🔗" label="LINKED 已关联" value={statusCounts.LINKED || 0} sub="已生成 line_item" onClick={() => setStatus('LINKED')} /></Col>
          <Col span={6}><StatCard tone="gray" icon="⌛" label="EXPIRED 已过期" value={statusCounts.EXPIRED || 0} sub="30 天未操作" onClick={() => setStatus('EXPIRED')} /></Col>
        </Row>

        <Table<ConfiguratorInstance> rowKey="id"
          loading={loading}
          dataSource={data}
          rowSelection={{ selectedRowKeys, onChange: setSelectedRowKeys }}
          pagination={{ current: page + 1, pageSize: size, total,
            onChange: (p, s) => { setPage(p - 1); setSize(s); } }}
          columns={[
            { title: '实例编号', dataIndex: 'instanceCode', width: 170,
              render: (v, r) => <a onClick={() => navigate(`/configurator/instances/${r.id}`)}><b>{v}</b></a> },
            { title: '名称', dataIndex: 'name', render: v => v || <span style={{ color: '#999' }}>-</span> },
            { title: '模板', dataIndex: 'templateId', width: 130, ellipsis: true,
              render: (v: string) => <code style={{ fontSize: 11 }}>{v?.substring(0, 8)}</code> },
            { title: '客户', width: 130, render: (_, r) => r.customerId
              ? <code style={{ fontSize: 11 }}>{r.customerId.substring(0, 8)}</code>
              : r.customerLeadId ? <Tag color="orange">客户线索</Tag> : <span style={{ color: '#999' }}>-</span> },
            { title: '配置摘要', dataIndex: 'selectedValues', width: 280,
              render: (sv: any) => {
                if (!sv || typeof sv !== 'object') return <span style={{ color: '#999' }}>-</span>;
                const entries = Object.entries(sv).slice(0, 6);
                return (
                  <Space size={[4, 4]} wrap>
                    {entries.map(([k, v]) => (
                      <Tag key={k} color="blue" style={{ marginRight: 0, fontSize: 11 }}>
                        {Array.isArray(v) ? v.join(',') : String(v)}
                      </Tag>
                    ))}
                  </Space>
                );
              }},
            { title: '总价', dataIndex: 'computedTotalPrice', width: 110, align: 'right',
              render: (v: number) => v ? <b style={{ color: '#cf1322' }}>¥{Number(v).toLocaleString()}</b> : '-' },
            { title: '状态', dataIndex: 'status', width: 140, render: (s: InstanceStatus) => {
              const color = s === 'LINKED' ? 'green' : s === 'SUBMITTED' ? 'blue' : s === 'EXPIRED' ? 'default' : 'gold';
              return <Tag color={color}>● {s}</Tag>;
            }},
            { title: '关联报价单', dataIndex: 'linkedQuotationId', width: 200,
              render: (v: string, r) => v
                ? <code style={{ fontSize: 11 }}>{v.substring(0, 12)}...</code>
                : r.status === 'SUBMITTED'
                  ? <Space size={4}>
                      <a onClick={() => openLinkExisting(r)}>🔗 绑到已有</a>
                      <a style={{ color: '#52c41a' }} onClick={() => newQuotation(r)}>🆕 新建</a>
                    </Space>
                  : <span style={{ color: '#999' }}>—</span> },
            { title: '创建时间', dataIndex: 'createdAt', width: 150,
              render: (v: string) => v ? new Date(v).toLocaleString().slice(0, 16) : '-' },
            { title: '操作', width: 180, render: (_, r) => (
              <Space size={4}>
                <a onClick={() => navigate(`/configurator/instances/${r.id}`)}>👁</a>
                <a onClick={() => navigate(`/configurator/run/${r.templateId}?instanceId=${r.id}`)}>📋</a>
                {r.status === 'LINKED' && (
                  <Popconfirm title="解绑此报价单？line_item 保留在报价单内（§17 弱关联）" onConfirm={() => unlink(r.id)}>
                    <a>🔓 解绑</a>
                  </Popconfirm>
                )}
                {r.status !== 'LINKED' && (
                  <Popconfirm title="删除此实例？" onConfirm={() => del(r.id)}>
                    <a style={{ color: '#f5222d' }}>🗑</a>
                  </Popconfirm>
                )}
              </Space>
            )},
          ]}
        />
      </Card>

      <Modal title={`🔗 绑定到已有报价单 · ${linkInstance?.instanceCode || ''}`}
             open={linkOpen} onCancel={() => setLinkOpen(false)} onOk={confirmLink} okText="✓ 确认绑定">
        {linkInstance && (
          <>
            <Descriptions size="small" bordered column={1} style={{ marginBottom: 14 }}>
              <Descriptions.Item label="实例">{linkInstance.instanceCode}</Descriptions.Item>
              <Descriptions.Item label="名称">{linkInstance.name || '-'}</Descriptions.Item>
              <Descriptions.Item label="总价">¥{linkInstance.computedTotalPrice}</Descriptions.Item>
            </Descriptions>
            <div style={{ marginBottom: 8 }}>要绑定的报价单 UUID：</div>
            <Input value={linkQuotationId} onChange={e => setLinkQuotationId(e.target.value)}
                   placeholder="如 a3f2e7b4-... (从报价单列表复制)" />
            <div style={{ marginTop: 8, padding: 8, background: '#fff7e6', border: '1px solid #ffd591', borderRadius: 4, fontSize: 11.5, color: '#876800' }}>
              ⓘ 后续切片：接 quotation 模块的搜索 + 客户匹配校验。当前仅写 linked_quotation_id（line_item 不真实插入到 quotation）。
            </div>
          </>
        )}
      </Modal>
    </div>
  );
};

export default ConfiguratorInstanceList;
