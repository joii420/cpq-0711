import React, { useEffect, useState } from 'react';
import {
  Card, Spin, Empty, Row, Col, Tag, Space, Radio, Input, InputNumber, Checkbox,
  Modal, message, Button,
} from 'antd';
import { useParams } from 'react-router-dom';
import {
  configuratorTemplateService, configuratorInstanceService, configuratorShareService,
} from '../../services/configuratorService';
import { customerLeadService } from '../../services/customerLeadService';
import type {
  ConfiguratorTemplate, ConfiguratorOption, ConfiguratorOptionValue,
} from '../../types/configurator';
import ConfiguratorPreview from '../../components/ConfiguratorPreview';

/**
 * 客户自助公网选配页 — 路由 /share/configurator/:token（公网，无 AuthGuard）
 * - 通过 share_token 取实例 + 模板
 * - 简化选项 UI（卡片网格）
 * - 提交时填联系方式 → 生成 customer_lead + 关联实例
 */
const PublicConfigurator: React.FC = () => {
  const { token } = useParams();
  const [share, setShare] = useState<any>(null);
  const [tpl, setTpl] = useState<ConfiguratorTemplate | null>(null);
  const [options, setOptions] = useState<ConfiguratorOption[]>([]);
  const [valuesByOpt, setValuesByOpt] = useState<Record<string, ConfiguratorOptionValue[]>>({});
  const [selectedValues, setSelectedValues] = useState<Record<string, any>>({});
  const [evalResult, setEvalResult] = useState<any>(null);
  const [loading, setLoading] = useState(false);

  // 提交对话框
  const [submitOpen, setSubmitOpen] = useState(false);
  const [contact, setContact] = useState({ name: '', phone: '', email: '', company: '', note: '' });
  const [submitting, setSubmitting] = useState(false);
  const [doneInstance, setDoneInstance] = useState<any>(null);

  useEffect(() => {
    if (!token) return;
    setLoading(true);
    (async () => {
      try {
        const sRes: any = await configuratorShareService.getByToken(token);
        const s = sRes.data;
        setShare(s);
        if (s.status !== 'ACTIVE') {
          message.warning(`链接已 ${s.status}，无法使用`);
          return;
        }
        // 取实例 → 取模板
        const iRes: any = await configuratorInstanceService.getById(s.instanceId);
        const tplId = iRes.data.templateId;
        const tRes: any = await configuratorTemplateService.getById(tplId);
        setTpl(tRes.data);
        const optRes: any = await configuratorTemplateService.listOptions(tplId);
        const opts: ConfiguratorOption[] = optRes.data || [];
        setOptions(opts);
        const map: Record<string, ConfiguratorOptionValue[]> = {};
        for (const o of opts) {
          const vRes: any = await configuratorTemplateService.listValues(o.id);
          map[o.id] = vRes.data || [];
        }
        setValuesByOpt(map);
        // 用实例已有 selectedValues 初始化
        const sv = iRes.data.selectedValues || {};
        const init: Record<string, any> = {};
        for (const o of opts) {
          const v = sv[o.code];
          if (v == null) {
            if (o.defaultValue) init[o.code] = o.optionType === 'MULTI_SELECT' ? [o.defaultValue] : o.defaultValue;
            continue;
          }
          init[o.code] = o.optionType === 'MULTI_SELECT' && typeof v === 'string'
            ? v.split(',').filter(Boolean) : v;
        }
        setSelectedValues(init);
      } catch (e: any) {
        message.error('加载分享链接失败：' + (e?.message || ''));
      } finally { setLoading(false); }
    })();
  }, [token]);

  useEffect(() => {
    if (!tpl || options.length === 0) return;
    const flat: any = {};
    for (const [k, v] of Object.entries(selectedValues)) flat[k] = Array.isArray(v) ? v : v;
    configuratorInstanceService.evaluateByTemplate(tpl.id, flat)
      .then((res: any) => setEvalResult(res.data))
      .catch(() => { /* */ });
  }, [selectedValues, tpl, options.length]);

  const requiredOpts = options.filter(o => o.isRequired);
  const filledRequired = requiredOpts.filter(o => {
    const v = selectedValues[o.code];
    return v !== undefined && v !== null && v !== '' && (!Array.isArray(v) || v.length > 0);
  }).length;
  const isComplete = filledRequired === requiredOpts.length;

  const submit = async () => {
    if (!contact.phone.trim() || !contact.name.trim()) {
      message.warning('请填写姓名和电话');
      return;
    }
    if (!share || !tpl) return;
    setSubmitting(true);
    try {
      // 1. 创建客户线索
      const leadRes: any = await customerLeadService.create({
        sourceType: 'CUSTOMER_SELF',
        shareToken: token,
        contactName: contact.name,
        contactPhone: contact.phone,
        contactEmail: contact.email,
        companyName: contact.company,
        note: contact.note,
        status: 'PENDING_REVIEW',
      });
      const lead = leadRes.data;
      // 2. 创建实例
      const flat: any = {};
      for (const [k, v] of Object.entries(selectedValues)) flat[k] = Array.isArray(v) ? v.join(',') : v;
      const instRes: any = await configuratorInstanceService.create({
        templateId: tpl.id, customerLeadId: lead.id, selectedValues: flat,
        configFingerprint: evalResult?.fingerprint,
        computedTotalPrice: evalResult?.totalPrice,
        basePrice: evalResult?.basePrice,
        status: 'SUBMITTED', shareToken: token,
      });
      setDoneInstance({ instance: instRes.data, lead });
      message.success(`✓ 已提交，销售将在 24h 内联系您`);
    } catch (e: any) {
      message.error('提交失败：' + (e?.message || ''));
    } finally { setSubmitting(false); }
  };

  if (loading) return <div style={{ padding: 40, textAlign: 'center' }}><Spin /></div>;
  if (!share || share.status !== 'ACTIVE') {
    return (
      <div style={{ padding: 60, textAlign: 'center', minHeight: '100vh', background: '#f0f2f5' }}>
        <Card>
          <Empty description={`链接已 ${share?.status || '失效'}`}>
            <p style={{ color: '#999', fontSize: 12 }}>如有疑问请联系您的销售顾问</p>
          </Empty>
        </Card>
      </div>
    );
  }
  if (!tpl) return <Empty description="模板不可用" />;

  return (
    <div style={{ minHeight: '100vh', background: '#f0f2f5' }}>
      {/* 品牌头 */}
      <div style={{ background: 'linear-gradient(90deg,#001529,#1f2d3d)', color: '#fff',
                    padding: '14px 24px', display: 'flex', alignItems: 'center', gap: 14 }}>
        <div style={{ fontSize: 18, fontWeight: 600 }}>🏢 CPQ 产品选配</div>
        <div style={{ fontSize: 12, color: '#a6adb4' }}>由 销售顾问 张工 为您准备 · 138 0001 0001</div>
        <div style={{ flex: 1 }} />
        <Tag color="green">🟢 ACTIVE</Tag>
      </div>

      {/* 信任栏 */}
      <div style={{ background: '#fff', padding: '8px 24px', borderBottom: '1px solid #e0e0e0',
                    fontSize: 11.5, color: '#666', display: 'flex', gap: 18 }}>
        <span>🔒 HTTPS 加密传输</span>
        <span>🛡️ 隐私保护</span>
        <span>⏰ 链接有效期至 {share.expiresAt ? new Date(share.expiresAt).toLocaleDateString() : '-'}</span>
        <span>📞 销售支持随时在线</span>
      </div>

      <div style={{ maxWidth: 1200, margin: '20px auto', padding: '0 16px' }}>
        {/* 欢迎横幅 */}
        <Card style={{ marginBottom: 14, background: 'linear-gradient(135deg,#e6f7ff,#f0f5ff)', border: '1px solid #91d5ff' }}>
          <div style={{ fontSize: 16, fontWeight: 600, color: '#0050b3' }}>👋 您好！我们为您准备了 {tpl.name}</div>
          <div style={{ fontSize: 12, color: '#0050b3', marginTop: 4 }}>
            模板代码 <code>{tpl.code}</code> · {tpl.description}
          </div>
        </Card>

        <Row gutter={16}>
          <Col span={12}>
            <Card title="🎨 产品预览">
              <ConfiguratorPreview
                category={tpl.category}
                selectedValues={(() => {
                  const flat: Record<string, any> = {};
                  for (const [k, v] of Object.entries(selectedValues)) flat[k] = Array.isArray(v) ? v[0] : v;
                  return flat;
                })()}
                height={380}
                label={`${tpl.code} · ${tpl.baseModelId ? `v${tpl.baseModelVersion}` : '默认演示'}`}
                autoRotate cameraControls showLabels
              />
              <div style={{ fontSize: 11, color: '#999', marginTop: 8, textAlign: 'center' }}>
                {tpl.category === '阀门' ? '⚙️ 选项变化时图形实时联动' : '⚙️ 拖拽旋转查看 · 滚轮缩放'}
              </div>
            </Card>
          </Col>
          <Col span={12}>
            <Card title={`⚙️ 配置选项 (${options.length})`}>
              <div style={{ maxHeight: 380, overflowY: 'auto' }}>
                {options.map(o => {
                  const vals = (valuesByOpt[o.id] || []).filter(v => v.isActive);
                  const sel = selectedValues[o.code];
                  const isMulti = o.optionType === 'MULTI_SELECT';
                  return (
                    <Card key={o.id} size="small" style={{ marginBottom: 10 }}
                          title={<>{o.isRequired && <Tag color="red">必选</Tag>} {o.label}</>}>
                      {vals.length > 0 ? (
                        <Row gutter={[6, 6]}>
                          {vals.map(v => {
                            const selected = isMulti
                              ? Array.isArray(sel) && sel.includes(v.code)
                              : sel === v.code;
                            return (
                              <Col span={12} key={v.id}>
                                <Card hoverable size="small"
                                      onClick={() => {
                                        if (isMulti) {
                                          const arr = Array.isArray(sel) ? [...sel] : [];
                                          const i = arr.indexOf(v.code);
                                          if (i >= 0) arr.splice(i, 1); else arr.push(v.code);
                                          setSelectedValues({ ...selectedValues, [o.code]: arr });
                                        } else {
                                          setSelectedValues({ ...selectedValues, [o.code]: v.code });
                                        }
                                      }}
                                      style={{ border: selected ? '2px solid #1890ff' : '1px solid #e0e0e0',
                                               background: selected ? '#e6f7ff' : '#fff', cursor: 'pointer' }}
                                      styles={{ body: { padding: '6px 8px' } }}>
                                  <Space>
                                    {isMulti && <Checkbox checked={selected} />}
                                    {selected && !isMulti && <span style={{ color: '#1890ff' }}>✓</span>}
                                    <span style={{ fontSize: 12, fontWeight: selected ? 600 : 'normal' }}>{v.label}</span>
                                  </Space>
                                </Card>
                              </Col>
                            );
                          })}
                        </Row>
                      ) : o.assignMode === 'MANUAL' ? (
                        o.dataType === 'NUMBER' ? (
                          <InputNumber value={sel ? Number(sel) : undefined}
                                       min={o.minValue ? Number(o.minValue) : undefined}
                                       max={o.maxValue ? Number(o.maxValue) : undefined}
                                       onChange={v => setSelectedValues({ ...selectedValues, [o.code]: String(v ?? '') })} />
                        ) : (
                          <Input value={sel}
                                 onChange={e => setSelectedValues({ ...selectedValues, [o.code]: e.target.value })} />
                        )
                      ) : <div style={{ color: '#999', fontSize: 12 }}>暂无可选值</div>}
                    </Card>
                  );
                })}
              </div>
            </Card>
          </Col>
        </Row>

        <Card style={{ marginTop: 14 }}>
          {tpl.showPrice && evalResult && (
            <div style={{ marginBottom: 12, padding: 10, background: '#fff7e6', borderRadius: 6 }}>
              <div style={{ fontSize: 12, color: '#876800' }}>参考报价</div>
              <div style={{ fontSize: 28, fontWeight: 600, color: '#cf1322' }}>¥{evalResult.totalPrice ? Number(evalResult.totalPrice).toLocaleString() : '0'}</div>
              <div style={{ fontSize: 11, color: '#876800' }}>含基础价 ¥{evalResult.basePrice}（待销售确认最终价格）</div>
            </div>
          )}
          {!tpl.showPrice && (
            <div style={{ marginBottom: 12, padding: 14, background: '#f0f5ff', border: '1px dashed #91caff',
                          borderRadius: 6, textAlign: 'center' }}>
              <div style={{ fontSize: 16, color: '#0050b3' }}>💬 请联系销售获取报价</div>
              <div style={{ fontSize: 11, color: '#0050b3', marginTop: 4 }}>
                此模板配置为不公开展示价格，提交后销售将在 24h 内为您准备正式报价
              </div>
            </div>
          )}
          <Button block size="large" type="primary" disabled={!isComplete}
                  style={{ background: '#52c41a', borderColor: '#52c41a' }}
                  onClick={() => setSubmitOpen(true)}>
            📨 提交并联系我
          </Button>
          {!isComplete && (
            <div style={{ marginTop: 6, color: '#d48806', fontSize: 12, textAlign: 'center' }}>
              ⚠ 还有 {requiredOpts.length - filledRequired} 个必选项未选完
            </div>
          )}
        </Card>

        <div style={{ marginTop: 18, padding: 14, textAlign: 'center', fontSize: 11, color: '#999' }}>
          © 2026 CPQ 系统 · 由 CPQ Configurator 驱动
        </div>
      </div>

      {/* 提交表单 Modal */}
      <Modal open={submitOpen} title="📨 提交您的配置"
             onCancel={() => !doneInstance && setSubmitOpen(false)} footer={null} width={520}>
        {!doneInstance ? (
          <>
            <div style={{ fontSize: 13, marginBottom: 12 }}>填写联系方式，销售顾问将在 <b>24 小时内</b> 与您联系</div>
            <Space direction="vertical" style={{ width: '100%' }}>
              <Input placeholder="姓名 *" value={contact.name} onChange={e => setContact({ ...contact, name: e.target.value })} />
              <Input placeholder="电话 *" value={contact.phone} onChange={e => setContact({ ...contact, phone: e.target.value })} />
              <Input placeholder="邮箱（选填）" value={contact.email} onChange={e => setContact({ ...contact, email: e.target.value })} />
              <Input placeholder="公司名称（选填）" value={contact.company} onChange={e => setContact({ ...contact, company: e.target.value })} />
              <Input.TextArea rows={3} placeholder="留言（数量 / 交期 / 其他需求）"
                              value={contact.note} onChange={e => setContact({ ...contact, note: e.target.value })} />
            </Space>
            <Space style={{ marginTop: 14, float: 'right' }}>
              <Button onClick={() => setSubmitOpen(false)}>取消</Button>
              <Button type="primary" loading={submitting} onClick={submit}>✓ 提交</Button>
            </Space>
          </>
        ) : (
          <div style={{ textAlign: 'center', padding: 14 }}>
            <div style={{ fontSize: 48, color: '#52c41a' }}>✓</div>
            <div style={{ fontSize: 16, fontWeight: 600, marginTop: 8 }}>已收到您的配置请求</div>
            <div style={{ fontSize: 12, color: '#666', marginTop: 6 }}>
              实例编号: <b>{doneInstance.instance.instanceCode}</b><br/>
              客户线索: {doneInstance.lead.leadCode}
            </div>
            <div style={{ fontSize: 12, color: '#666', marginTop: 10 }}>
              销售顾问 张工 将在 24h 内致电 <b>{contact.phone}</b>
            </div>
            <Button type="primary" style={{ marginTop: 16 }} onClick={() => setSubmitOpen(false)}>关闭</Button>
          </div>
        )}
      </Modal>
    </div>
  );
};

export default PublicConfigurator;
