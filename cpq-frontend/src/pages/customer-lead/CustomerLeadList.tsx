import React, { useEffect, useState } from 'react';
import {
  Card, Table, Tag, Button, Select, Space, message, Row, Col,
  Modal, Input, Descriptions,
} from 'antd';
import { customerLeadService } from '../../services/customerLeadService';
import { customerService } from '../../services/customerService';
import StatCard from '../../components/StatCard';
import type { CustomerLead, LeadStatus, LeadReviewAction } from '../../types/customer-lead';

const CustomerLeadList: React.FC = () => {
  const [data, setData] = useState<CustomerLead[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [status, setStatus] = useState<string | undefined>('PENDING_REVIEW');
  const [statusCounts, setStatusCounts] = useState<Record<string, number>>({});

  const [reviewOpen, setReviewOpen] = useState(false);
  const [reviewAction, setReviewAction] = useState<LeadReviewAction>('BIND_EXISTING');
  const [currentLead, setCurrentLead] = useState<CustomerLead | null>(null);
  const [boundCustomerId, setBoundCustomerId] = useState('');
  const [reviewNote, setReviewNote] = useState('');
  const [customerOptions, setCustomerOptions] = useState<{ label: string; value: string }[]>([]);
  const [customerSearchLoading, setCustomerSearchLoading] = useState(false);

  const searchCustomers = async (kw: string) => {
    setCustomerSearchLoading(true);
    try {
      const res: any = await customerService.list({ page: 0, size: 30, keyword: kw });
      const rows = res.data?.content || res.data || [];
      setCustomerOptions(rows.map((c: any) => ({
        label: `${c.name} · ${c.code || ''} · ${c.level || c.tier || 'STANDARD'}`,
        value: c.id,
      })));
    } catch { setCustomerOptions([]); }
    finally { setCustomerSearchLoading(false); }
  };

  const load = async () => {
    setLoading(true);
    try {
      const res: any = await customerLeadService.list({ page, size, status });
      setData(res.data?.content || []);
      setTotal(res.data?.totalElements || 0);
      // 统计
      const counts: Record<string, number> = { PENDING_REVIEW: 0, CONVERTED: 0, REJECTED: 0 };
      for (const s of Object.keys(counts)) {
        try {
          const r: any = await customerLeadService.list({ page: 0, size: 1, status: s });
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

  const openReview = (lead: CustomerLead, action: LeadReviewAction) => {
    setCurrentLead(lead);
    setReviewAction(action);
    setBoundCustomerId('');
    setReviewNote('');
    setReviewOpen(true);
    // 预加载客户列表（按 lead.phone 搜索同号）
    if (action === 'BIND_EXISTING') searchCustomers(lead.contactPhone || '');
  };

  const confirmReview = async () => {
    if (!currentLead) return;
    if (reviewAction === 'BIND_EXISTING' && !boundCustomerId.trim()) {
      message.warning('请填写客户 UUID');
      return;
    }
    try {
      const res: any = await customerLeadService.review(currentLead.id, reviewAction, {
        bound_customer_id: boundCustomerId || undefined,
        review_note: reviewNote,
      });
      const r = res.data || {};
      let msg = `已审核 · 状态变为 ${r.status}`;
      if (r.updated_instances) msg += ` · 同步更新 ${r.updated_instances} 个关联实例`;
      if (r.expired_instances) msg += ` · ${r.expired_instances} 个关联实例已 EXPIRED`;
      message.success(msg);
      setReviewOpen(false);
      load();
    } catch (e: any) { message.error('审核失败：' + (e?.message || '')); }
  };

  return (
    <div style={{ padding: 16 }}>
      <Card title="📩 客户线索" extra={
        <Space>
          <Select placeholder="状态" allowClear style={{ width: 200 }}
                  options={[
                    { value: 'PENDING_REVIEW', label: 'PENDING_REVIEW 待审核' },
                    { value: 'CONVERTED', label: 'CONVERTED 已转客户' },
                    { value: 'REJECTED', label: 'REJECTED 已拒绝' },
                  ]}
                  value={status} onChange={setStatus} />
        </Space>
      }>
        {/* 状态统计 */}
        <Row gutter={10} style={{ marginBottom: 14 }}>
          <Col span={8}><StatCard tone="orange" icon="⏳" label="PENDING_REVIEW 待审核" value={statusCounts.PENDING_REVIEW || 0} sub="等销售对接" onClick={() => setStatus('PENDING_REVIEW')} /></Col>
          <Col span={8}><StatCard tone="success" icon="✅" label="CONVERTED 已转客户" value={statusCounts.CONVERTED || 0} sub="已绑定/新建" onClick={() => setStatus('CONVERTED')} /></Col>
          <Col span={8}><StatCard tone="red" icon="🚫" label="REJECTED 已拒绝" value={statusCounts.REJECTED || 0} sub="不符合标准" onClick={() => setStatus('REJECTED')} /></Col>
        </Row>

        <Table<CustomerLead> rowKey="id"
          loading={loading}
          dataSource={data}
          pagination={{ current: page + 1, pageSize: size, total,
            onChange: (p, s) => { setPage(p - 1); setSize(s); } }}
          columns={[
            { title: '线索编号', dataIndex: 'leadCode', width: 170 },
            { title: '来源', dataIndex: 'sourceType', width: 130, render: (v: string) => <Tag>{v}</Tag> },
            { title: '联系人', dataIndex: 'contactName' },
            { title: '电话', dataIndex: 'contactPhone', width: 140 },
            { title: '邮箱', dataIndex: 'contactEmail', width: 180, ellipsis: true },
            { title: '公司', dataIndex: 'companyName' },
            { title: '状态', dataIndex: 'status', width: 140, render: (s: LeadStatus) => {
              const color = s === 'PENDING_REVIEW' ? 'gold' : s === 'CONVERTED' ? 'green' : 'red';
              return <Tag color={color}>{s}</Tag>;
            }},
            { title: '提交时间', dataIndex: 'createdAt', width: 150,
              render: (v: string) => v ? new Date(v).toLocaleString().slice(0, 16) : '-' },
            { title: '操作', width: 200, render: (_, r) => r.status === 'PENDING_REVIEW' ? (
              <Space size={4}>
                <a onClick={() => openReview(r, 'BIND_EXISTING')}>🔗 绑定</a>
                <a onClick={() => openReview(r, 'CREATE_NEW')}>+ 新建客户</a>
                <a style={{ color: '#f5222d' }} onClick={() => openReview(r, 'REJECT')}>🚫 拒绝</a>
              </Space>
            ) : <span style={{ color: '#999', fontSize: 11 }}>已处理</span> },
          ]}
        />
      </Card>

      <Modal
        title={
          reviewAction === 'BIND_EXISTING' ? '🔗 绑定到已有客户'
          : reviewAction === 'CREATE_NEW' ? '+ 新建客户'
          : '🚫 拒绝此线索'
        }
        open={reviewOpen}
        onCancel={() => setReviewOpen(false)}
        onOk={confirmReview}
        okText="✓ 确认审核"
        width={520}
      >
        {currentLead && (
          <Descriptions size="small" bordered column={1} style={{ marginBottom: 14 }}>
            <Descriptions.Item label="线索编号">{currentLead.leadCode}</Descriptions.Item>
            <Descriptions.Item label="联系人">{currentLead.contactName}</Descriptions.Item>
            <Descriptions.Item label="电话">{currentLead.contactPhone}</Descriptions.Item>
            <Descriptions.Item label="邮箱">{currentLead.contactEmail || '-'}</Descriptions.Item>
            <Descriptions.Item label="公司">{currentLead.companyName || '-'}</Descriptions.Item>
            <Descriptions.Item label="留言">{currentLead.note || '-'}</Descriptions.Item>
          </Descriptions>
        )}

        {reviewAction === 'BIND_EXISTING' && (
          <div>
            <div style={{ marginBottom: 8, fontWeight: 500 }}>搜索并绑定到现有客户</div>
            <Select showSearch placeholder="按名称 / 编号 / 电话搜索"
                    value={boundCustomerId || undefined}
                    onChange={setBoundCustomerId}
                    onSearch={searchCustomers}
                    loading={customerSearchLoading}
                    filterOption={false}
                    options={customerOptions}
                    style={{ width: '100%' }} />
            <div style={{ marginTop: 4, fontSize: 11, color: '#999' }}>
              ⓘ 自动按线索的电话 {currentLead?.contactPhone} 预填 — 可重新搜索其他客户
            </div>
          </div>
        )}

        {reviewAction === 'CREATE_NEW' && (
          <div style={{ padding: 10, background: '#fff7e6', border: '1px solid #ffd591', borderRadius: 4, fontSize: 12, color: '#876800' }}>
            ⚠ 新建客户功能尚未集成 customer 模块（后续切片）。<br/>
            临时方案：先在「客户管理」手工新建 customer → 复制 ID → 回到此页用「🔗 绑定」动作。
          </div>
        )}

        {reviewAction === 'REJECT' && (
          <div>
            <div style={{ marginBottom: 8, fontWeight: 500 }}>拒绝原因</div>
            <Input.TextArea rows={3} value={reviewNote} onChange={e => setReviewNote(e.target.value)}
                            placeholder="如 重复提交 / 不真实 / 测试单" />
            <div style={{ marginTop: 4, fontSize: 11, color: '#999' }}>
              ⓘ 拒绝后关联的所有 SUBMITTED 实例会同步置 EXPIRED
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
};

export default CustomerLeadList;
