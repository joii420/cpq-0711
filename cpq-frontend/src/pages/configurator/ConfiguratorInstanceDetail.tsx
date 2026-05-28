import React, { useEffect, useState } from 'react';
import {
  Card, Descriptions, Tag, Button, Space, Spin, Empty, message, Row, Col, Timeline, Popconfirm,
} from 'antd';
import { useParams, useNavigate } from 'react-router-dom';
import { configuratorInstanceService, configuratorTemplateService } from '../../services/configuratorService';
import type { ConfiguratorInstance, ConfiguratorTemplate, InstanceStatus } from '../../types/configurator';
import StatCard from '../../components/StatCard';
import ConfiguratorPreview from '../../components/ConfiguratorPreview';

const ConfiguratorInstanceDetail: React.FC = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const [inst, setInst] = useState<ConfiguratorInstance | null>(null);
  const [tpl, setTpl] = useState<ConfiguratorTemplate | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!id) return;
    setLoading(true);
    (async () => {
      try {
        const res: any = await configuratorInstanceService.getById(id);
        const i: ConfiguratorInstance = res.data;
        setInst(i);
        if (i.templateId) {
          const tRes: any = await configuratorTemplateService.getById(i.templateId);
          setTpl(tRes.data);
        }
      } catch (e: any) {
        message.error('加载失败：' + (e?.message || ''));
      } finally { setLoading(false); }
    })();
  }, [id]);

  const unlink = async () => {
    if (!id) return;
    try {
      await configuratorInstanceService.unlink(id);
      message.success('已解绑');
      const res: any = await configuratorInstanceService.getById(id);
      setInst(res.data);
    } catch (e: any) { message.error('解绑失败：' + (e?.message || '')); }
  };

  const newQuotation = async () => {
    if (!id) return;
    try {
      const res: any = await configuratorInstanceService.linkAction(id, 'NEW_QUOTATION');
      message.success(`✓ 已生成报价单 · 料号 ${res.data?.part_no}`);
      const r: any = await configuratorInstanceService.getById(id);
      setInst(r.data);
    } catch (e: any) { message.error('生成失败：' + (e?.message || '')); }
  };

  if (loading) return <Spin />;
  if (!inst) return <Empty description="实例不存在" />;

  const statusColor = inst.status === 'LINKED' ? 'green' : inst.status === 'SUBMITTED' ? 'blue' : inst.status === 'EXPIRED' ? 'default' : 'gold';
  const selectedTags = Object.entries(inst.selectedValues || {}).slice(0, 20);

  return (
    <div style={{ padding: 16 }}>
      <Card
        title={<><b>{inst.instanceCode}</b> · {inst.name || '未命名'}</>}
        extra={
          <Space>
            <Button onClick={() => navigate('/configurator/instances')}>← 返回列表</Button>
            {(inst.status === 'DRAFT' || inst.status === 'SUBMITTED') && (
              <Button onClick={() => navigate(`/configurator/run/${inst.templateId}?instanceId=${inst.id}`)}>
                ✏️ 继续编辑
              </Button>
            )}
            {inst.status === 'SUBMITTED' && (
              <Popconfirm title="基于当前配置生成新报价单？" onConfirm={newQuotation}>
                <Button type="primary" style={{ background: '#52c41a', borderColor: '#52c41a' }}>🆕 生成报价单</Button>
              </Popconfirm>
            )}
            {inst.status === 'LINKED' && (
              <Popconfirm title="解绑此报价单？" onConfirm={unlink}>
                <Button>🔓 解绑</Button>
              </Popconfirm>
            )}
          </Space>
        }
      >
        <Row gutter={10} style={{ marginBottom: 14 }}>
          <Col span={6}>
            <StatCard tone="primary" icon="📋" label="实例编号" value={<span style={{ fontSize: 16 }}>{inst.instanceCode}</span>} sub={`v${inst.templateVersion || '-'}`} />
          </Col>
          <Col span={6}>
            <StatCard tone="purple" icon="💰" label="总价"
              value={<span>¥{inst.computedTotalPrice ? Number(inst.computedTotalPrice).toLocaleString() : '0'}</span>}
              sub={`base ¥${inst.basePrice || 0}`} />
          </Col>
          <Col span={6}>
            <StatCard tone={inst.status === 'LINKED' ? 'success' : inst.status === 'SUBMITTED' ? 'primary' : 'gray'}
                      icon={inst.status === 'LINKED' ? '🔗' : '📤'} label="状态"
                      value={<Tag color={statusColor} style={{ fontSize: 14 }}>{inst.status}</Tag>}
                      sub={inst.expiresAt && inst.status !== 'EXPIRED' ? `${new Date(inst.expiresAt).toLocaleDateString()} 过期` : '-'} />
          </Col>
          <Col span={6}>
            <StatCard tone="orange" icon="🛒" label="模板"
                      value={<span style={{ fontSize: 14 }}>{tpl?.name || '-'}</span>}
                      sub={tpl?.code || '-'} />
          </Col>
        </Row>

        <Descriptions column={3} bordered size="small">
          <Descriptions.Item label="客户">{inst.customerId
            ? <code style={{ fontSize: 11 }}>{inst.customerId.substring(0, 8)}...</code>
            : inst.customerLeadId ? <Tag color="orange">客户线索 {inst.customerLeadId.substring(0, 8)}...</Tag>
            : <span style={{ color: '#999' }}>-</span>}</Descriptions.Item>
          <Descriptions.Item label="config_fingerprint"><code style={{ fontSize: 10.5 }}>{(inst.configFingerprint || '').substring(0, 16)}...</code></Descriptions.Item>
          <Descriptions.Item label="创建时间">{new Date(inst.createdAt).toLocaleString()}</Descriptions.Item>
          <Descriptions.Item label="生成料号" span={inst.generatedPartNo ? 1 : 3}>{inst.generatedPartNo ? <code>{inst.generatedPartNo}</code> : '-'}</Descriptions.Item>
          {inst.generatedPartNo && (
            <>
              <Descriptions.Item label="关联报价单">{inst.linkedQuotationId ? <code style={{ fontSize: 10.5 }}>{inst.linkedQuotationId.substring(0, 12)}...</code> : '-'}</Descriptions.Item>
              <Descriptions.Item label="关联时间">{inst.linkedAt ? new Date(inst.linkedAt).toLocaleString() : '-'}</Descriptions.Item>
            </>
          )}
        </Descriptions>

        <Row gutter={14} style={{ marginTop: 14 }}>
          <Col span={10}>
            <Card type="inner" title="🎬 模型预览">
              <ConfiguratorPreview
                category={tpl?.category}
                selectedValues={inst.selectedValues || {}}
                height={240}
                label={tpl?.code ? `${tpl.code} · ${inst.instanceCode}` : inst.instanceCode}
                autoRotate cameraControls showLabels
              />
            </Card>
          </Col>
          <Col span={14}>
            <Card type="inner" title="📦 配置摘要">
              <Space size={[6, 4]} wrap>
                {selectedTags.length === 0 && <span style={{ color: '#999' }}>暂无配置</span>}
                {selectedTags.map(([k, v]) => (
                  <Tag color="blue" key={k} style={{ fontSize: 12 }}>
                    {k}: {Array.isArray(v) ? v.join(',') : String(v)}
                  </Tag>
                ))}
              </Space>
            </Card>
          </Col>
        </Row>

        <Card type="inner" title="📜 历史时间线" style={{ marginTop: 14 }}>
          <Timeline
            items={[
              { color: 'gray', children: <div><b>{new Date(inst.createdAt).toLocaleString()}</b><div style={{ fontSize: 11, color: '#999' }}>实例创建 · status=DRAFT</div></div> },
              ...(inst.status !== 'DRAFT' ? [{ color: 'blue', children: <div><b>{inst.createdAt ? new Date(inst.createdAt).toLocaleString() : '-'}</b><div style={{ fontSize: 11, color: '#999' }}>提交 → SUBMITTED</div></div> }] : []),
              ...(inst.linkedAt ? [{ color: 'green', children: <div><b>{new Date(inst.linkedAt).toLocaleString()}</b><div style={{ fontSize: 11, color: '#999' }}>关联报价单 → LINKED · 生成 {inst.generatedPartNo}</div></div> }] : []),
              ...(inst.status === 'EXPIRED' && inst.expiresAt ? [{ color: 'gray', children: <div><b>{new Date(inst.expiresAt).toLocaleString()}</b><div style={{ fontSize: 11, color: '#999' }}>过期 → EXPIRED</div></div> }] : []),
            ]}
          />
          <div style={{ padding: 8, background: '#fffbe6', border: '1px solid #ffe58f', borderRadius: 4, fontSize: 11.5, color: '#876800', marginTop: 8 }}>
            ⓘ 完整变更历史接 product_config_instance_history 表（后续切片实现）
          </div>
        </Card>
      </Card>
    </div>
  );
};

export default ConfiguratorInstanceDetail;
