import React, { useEffect, useMemo, useState } from 'react';
import {
  Card, Button, Spin, Tag, message, Row, Col, Empty, Modal, Space,
  Descriptions, InputNumber, Input, Switch, Progress, Checkbox,
} from 'antd';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { configuratorTemplateService, configuratorInstanceService, configuratorShareService } from '../../services/configuratorService';
import type {
  ConfiguratorTemplate, ConfiguratorOption, ConfiguratorOptionValue,
} from '../../types/configurator';
import ConfiguratorPreview from '../../components/ConfiguratorPreview';

/**
 * v0.4 全屏 3D 选配页（Babylon 集成 = 期 3 大工程）
 * 期 2 改造：客户切换 + 隐藏价格 + 进度叠加 + 配置摘要 Tags + 卡片网格 + MULTI_SELECT
 */
const MOCK_CUSTOMERS = [
  { id: 'cus-rockwell-001', name: '罗克韦尔', tier: 'VIP', color: 'orange' },
  { id: 'cus-siemens-002',  name: '西门子',  tier: 'STD', color: 'default' },
  { id: 'cus-trial-099',    name: '试用客户', tier: 'TRIAL', color: 'red' },
];

const ConfiguratorPage: React.FC = () => {
  const { templateId } = useParams();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const initialCust = searchParams.get('customer') || 'cus-rockwell-001';
  const editingInstanceId = searchParams.get('instanceId') || undefined;

  const [tpl, setTpl] = useState<ConfiguratorTemplate | null>(null);
  const [options, setOptions] = useState<ConfiguratorOption[]>([]);
  const [valuesByOpt, setValuesByOpt] = useState<Record<string, ConfiguratorOptionValue[]>>({});
  const [selectedValues, setSelectedValues] = useState<Record<string, any>>({});
  const [evalResult, setEvalResult] = useState<any>(null);
  const [loading, setLoading] = useState(false);
  const [customerId, setCustomerId] = useState(initialCust);
  const [showPrice, setShowPrice] = useState(true);
  const [demoMode, setDemoMode] = useState(false);  // 演示模式 — 隐藏内部菜单干扰

  // 提交对话框
  const [submitOpen, setSubmitOpen] = useState(false);
  const [submitStep, setSubmitStep] = useState<'review' | 'linkExisting' | 'done'>('review');
  const [createdInstance, setCreatedInstance] = useState<any>(null);
  const [actionResult, setActionResult] = useState<any>(null);
  const [existingQuotationId, setExistingQuotationId] = useState('');

  // 演示预设（阀门示例）
  const PRESETS: Record<string, { label: string; emoji: string; values: Record<string, any> }[]> = {
    '阀门': [
      { label: '标准套餐', emoji: '🟢', values: { DN: '25', PN: '16', MATERIAL: 'WCB', CONNECTION: 'FLANGE', DRIVE: 'HANDLE', TEMP_RANGE: '80' } },
      { label: '增强套餐', emoji: '🔵', values: { DN: '50', PN: '25', MATERIAL: '304', CONNECTION: 'FLANGE', DRIVE: 'ELECTRIC', TEMP_RANGE: '120' } },
      { label: '高端套餐', emoji: '🟠', values: { DN: '80', PN: '40', MATERIAL: '316', CONNECTION: 'WELD', DRIVE: 'PNEUMATIC', TEMP_RANGE: '180' } },
    ],
  };
  const applyPreset = (preset: { label: string; values: Record<string, any> }) => {
    setSelectedValues(preset.values);
    message.success(`✓ 已应用「${preset.label}」预设`);
  };

  // 分享对话框
  const [shareOpen, setShareOpen] = useState(false);
  const [shareEmail, setShareEmail] = useState('');
  const [shareDays, setShareDays] = useState(7);
  const [shareCanModify, setShareCanModify] = useState(false);
  const [shareResult, setShareResult] = useState<any>(null);

  useEffect(() => {
    if (!templateId) return;
    setLoading(true);
    (async () => {
      try {
        const tplRes: any = await configuratorTemplateService.getById(templateId);
        const t: ConfiguratorTemplate = tplRes.data;
        setTpl(t);
        setShowPrice(t.showPrice);
        const optRes: any = await configuratorTemplateService.listOptions(templateId);
        const opts: ConfiguratorOption[] = optRes.data || [];
        setOptions(opts);
        const map: Record<string, ConfiguratorOptionValue[]> = {};
        const init: Record<string, any> = {};
        for (const o of opts) {
          const vRes: any = await configuratorTemplateService.listValues(o.id);
          map[o.id] = vRes.data || [];
          if (o.defaultValue) init[o.code] = o.optionType === 'MULTI_SELECT' ? [o.defaultValue] : o.defaultValue;
        }
        setValuesByOpt(map);

        // 续编：加载已有实例的 selectedValues 覆盖默认值
        if (editingInstanceId) {
          try {
            const instRes: any = await configuratorInstanceService.getById(editingInstanceId);
            const sv = instRes.data?.selectedValues || {};
            // MULTI_SELECT 字段拆回数组
            const restored: Record<string, any> = {};
            for (const o of opts) {
              const v = sv[o.code];
              if (v == null) { if (init[o.code] !== undefined) restored[o.code] = init[o.code]; continue; }
              restored[o.code] = o.optionType === 'MULTI_SELECT' && typeof v === 'string'
                ? v.split(',').filter(Boolean) : v;
            }
            setSelectedValues(restored);
            message.info(`已恢复实例 ${instRes.data?.instanceCode} 的配置`);
          } catch { setSelectedValues(init); }
        } else {
          setSelectedValues(init);
        }
      } catch (e: any) {
        message.error('加载失败：' + (e?.message || ''));
      } finally {
        setLoading(false);
      }
    })();
  }, [templateId]);

  useEffect(() => {
    if (!templateId || options.length === 0) return;
    // MULTI_SELECT 数组值传后端时取第一个（简化），后端 evaluate 暂仅支持单值
    const flat: any = {};
    for (const [k, v] of Object.entries(selectedValues)) {
      flat[k] = Array.isArray(v) ? v[0] : v;
    }
    configuratorInstanceService.evaluateByTemplate(templateId, flat)
      .then((res: any) => setEvalResult(res.data))
      .catch(() => { /* eval 失败不阻断 */ });
  }, [selectedValues, options.length, templateId]);

  // 必选项完成度
  const requiredOpts = options.filter(o => o.isRequired);
  const filledRequired = requiredOpts.filter(o => {
    const v = selectedValues[o.code];
    return v !== undefined && v !== null && v !== '' && (!Array.isArray(v) || v.length > 0);
  }).length;
  const progressPct = requiredOpts.length === 0 ? 100 : Math.round((filledRequired / requiredOpts.length) * 100);
  const isComplete = filledRequired === requiredOpts.length;

  // 配置摘要 Tags
  const summaryTags = useMemo(() => {
    const tags: { code: string; label: string }[] = [];
    for (const o of options) {
      const sel = selectedValues[o.code];
      if (!sel || (Array.isArray(sel) && sel.length === 0)) continue;
      const vals = Array.isArray(sel) ? sel : [sel];
      for (const code of vals) {
        const v = (valuesByOpt[o.id] || []).find(x => x.code === code);
        tags.push({ code: o.code, label: v?.label || String(code) });
      }
    }
    return tags;
  }, [selectedValues, options, valuesByOpt]);

  const submit = async () => {
    if (!templateId) return;
    try {
      const flat: any = {};
      for (const [k, v] of Object.entries(selectedValues)) {
        flat[k] = Array.isArray(v) ? v.join(',') : v;
      }
      const res: any = await configuratorInstanceService.create({
        templateId, customerId, selectedValues: flat,
        configFingerprint: evalResult?.fingerprint,
        computedTotalPrice: evalResult?.totalPrice,
        basePrice: evalResult?.basePrice,
        status: 'SUBMITTED',
      });
      setCreatedInstance(res.data);
      setSubmitStep('review');
      setSubmitOpen(true);
    } catch (e: any) { message.error('提交失败：' + (e?.message || '')); }
  };

  const chooseAction = async (action: 'NEW_QUOTATION' | 'SAVE_DRAFT' | 'LINK_EXISTING') => {
    if (!createdInstance) return;
    if (action === 'SAVE_DRAFT') {
      message.success(`实例 ${createdInstance.instanceCode} 已保存为草稿`);
      setSubmitOpen(false);
      navigate(`/configurator/instances`);
      return;
    }
    if (action === 'LINK_EXISTING') { setSubmitStep('linkExisting'); return; }
    try {
      const res: any = await configuratorInstanceService.linkAction(createdInstance.id, 'NEW_QUOTATION');
      setActionResult(res.data);
      setSubmitStep('done');
    } catch (e: any) { message.error('操作失败：' + (e?.message || '')); }
  };

  const openShareDialog = async () => {
    // 必须先有实例 ID（创建草稿）
    if (!createdInstance) {
      if (!templateId) return;
      try {
        const flat: any = {};
        for (const [k, v] of Object.entries(selectedValues)) flat[k] = Array.isArray(v) ? v.join(',') : v;
        const res: any = await configuratorInstanceService.create({
          templateId, customerId, selectedValues: flat,
          configFingerprint: evalResult?.fingerprint,
          computedTotalPrice: evalResult?.totalPrice,
          basePrice: evalResult?.basePrice,
          status: 'DRAFT',
        });
        setCreatedInstance(res.data);
      } catch (e: any) { message.error('创建实例失败：' + (e?.message || '')); return; }
    }
    setShareResult(null);
    setShareOpen(true);
  };
  const confirmShare = async () => {
    if (!createdInstance) return;
    if (!shareEmail.trim()) { message.warning('请填写邮箱'); return; }
    try {
      const res: any = await configuratorShareService.create(createdInstance.id, {
        email: shareEmail, days: shareDays, can_modify: shareCanModify,
      });
      setShareResult(res.data);
    } catch (e: any) { message.error('生成失败：' + (e?.message || '')); }
  };
  const copyShareUrl = () => {
    const url = `https://cpq.example.com/share/configurator/${shareResult?.shareToken}`;
    if (navigator.clipboard) navigator.clipboard.writeText(url);
    message.success(`已复制：${url}`);
  };

  const confirmLinkExisting = async () => {
    if (!createdInstance || !existingQuotationId) { message.warning('请输入报价单 ID'); return; }
    try {
      const res: any = await configuratorInstanceService.linkAction(createdInstance.id, 'LINK_EXISTING', existingQuotationId);
      setActionResult(res.data);
      setSubmitStep('done');
    } catch (e: any) { message.error('绑定失败：' + (e?.message || '')); }
  };

  if (loading) return <Spin />;
  if (!tpl) return <Empty description="模板不存在" />;

  const cust = MOCK_CUSTOMERS.find(c => c.id === customerId)!;

  return (
    <div style={{ padding: 16 }}>
      <Card
        title={`🛒 选配 — ${tpl.name}`}
        extra={
          <Space>
            {/* 客户切换 */}
            <span style={{ fontSize: 12, color: '#876800' }}>客户视角：</span>
            {MOCK_CUSTOMERS.map(c => (
              <Tag.CheckableTag key={c.id} checked={customerId === c.id} onChange={() => setCustomerId(c.id)}
                                style={{ fontSize: 12 }}>
                {c.name} <Tag color={c.color as any}>{c.tier}</Tag>
              </Tag.CheckableTag>
            ))}
            <span style={{ borderLeft: '1px solid #e0e0e0', paddingLeft: 10, marginLeft: 6, fontSize: 12 }}>
              <Switch size="small" checked={!showPrice} onChange={v => setShowPrice(!v)} /> 隐藏价格
              <Switch size="small" checked={demoMode} onChange={setDemoMode} style={{ marginLeft: 10 }} /> 演示模式
            </span>
            <Button onClick={openShareDialog}>🔗 分享给客户</Button>
            <Button onClick={() => navigate('/configurator/start')}>← 返回</Button>
          </Space>
        }
      >
        {demoMode && (
          <div style={{ background: 'linear-gradient(90deg,#001529,#1f2d3d)', color: '#fff',
                        padding: '8px 14px', borderRadius: 6, marginBottom: 10, fontSize: 12 }}>
            🎭 <b>演示模式</b> — 已隐藏内部菜单（仅本页面演示用，正式分享请用「🔗 分享给客户」生成 token）
          </div>
        )}

        {/* 顶部条信息 */}
        <div style={{ background: 'linear-gradient(90deg,#fffbe6,#fff7e6)', border: '1px solid #ffd591',
                      padding: '8px 14px', borderRadius: 6, marginBottom: 14,
                      display: 'flex', alignItems: 'center', gap: 14 }}>
          <span style={{ fontSize: 12, color: '#876800' }}>
            📋 <b>{cust.name}</b> ({cust.tier}) · 模板 <code>{tpl.code}</code>
            {cust.tier === 'VIP' && <Tag color="orange" style={{ marginLeft: 8 }}>🎁 享受 VIP 8 折</Tag>}
            {cust.tier === 'TRIAL' && <Tag color="red" style={{ marginLeft: 8 }}>⚠ 试用账户：仅基础款</Tag>}
          </span>
          <div style={{ flex: 1 }} />
          <span style={{ fontSize: 12, color: '#876800' }}>配置进度：</span>
          <Progress percent={progressPct} size="small" style={{ width: 160 }}
                    strokeColor={progressPct === 100 ? '#52c41a' : '#1890ff'} />
          <span style={{ fontSize: 12 }}>{filledRequired} / {requiredOpts.length} 必选</span>
        </div>

        <Row gutter={16}>
          {/* 左侧 3D 渲染（model-viewer） */}
          <Col span={12}>
            <ConfiguratorPreview
              category={tpl.category}
              selectedValues={(() => {
                const flat: Record<string, any> = {};
                for (const [k, v] of Object.entries(selectedValues)) flat[k] = Array.isArray(v) ? v[0] : v;
                return flat;
              })()}
              height={460}
              label={tpl.baseModelId ? `${tpl.code} · v${tpl.baseModelVersion}` : `${tpl.code} (未绑模型)`}
              autoRotate cameraControls showLabels
            />
            <div style={{ fontSize: 11, color: '#999', marginTop: 8, textAlign: 'center' }}>
              {tpl.category === '阀门'
                ? '⚙️ 选项变化时图形实时联动（材质 / 连接器 / 驱动器 / 高温警告）'
                : '⚙️ 拖拽旋转 · 滚轮缩放 · 双击重置'}
            </div>
          </Col>
          {/* 右侧选项面板 */}
          <Col span={12}>
            {tpl.category && PRESETS[tpl.category] && (
              <div style={{ padding: '8px 10px', background: '#fff7e6', border: '1px solid #ffd591',
                            borderRadius: 6, marginBottom: 10, display: 'flex', alignItems: 'center', gap: 8 }}>
                <span style={{ fontSize: 11.5, color: '#876800' }}>🎁 演示预设：</span>
                {PRESETS[tpl.category].map(p => (
                  <Button key={p.label} size="small" onClick={() => applyPreset(p)}>
                    {p.emoji} {p.label}
                  </Button>
                ))}
              </div>
            )}
            <div style={{ maxHeight: 460, overflowY: 'auto', paddingRight: 4 }}>
              {options.length === 0 ? <Empty description="此模板尚无选项" /> :
                options.map(o => {
                  const vals = (valuesByOpt[o.id] || []).filter(v => v.isActive);
                  const sel = selectedValues[o.code];
                  const isMulti = o.optionType === 'MULTI_SELECT';
                  return (
                    <Card key={o.id} type="inner" size="small" style={{ marginBottom: 10 }}
                          title={
                            <Space>
                              {o.isRequired && <Tag color="red">必选</Tag>}
                              <span>{o.label}</span>
                              <code style={{ fontSize: 11, color: '#999' }}>{o.code}</code>
                              {sel && (Array.isArray(sel) ? sel.length > 0 : true) && <span style={{ color: '#52c41a' }}>✓</span>}
                            </Space>
                          }>
                      {vals.length > 0 ? (
                        // 卡片网格
                        <Row gutter={[8, 8]}>
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
                                      style={{
                                        border: selected ? '2px solid #1890ff' : '1px solid #e0e0e0',
                                        background: selected ? '#e6f7ff' : '#fff',
                                        cursor: 'pointer',
                                      }}
                                      styles={{ body: { padding: '8px 10px' } }}>
                                  <Space>
                                    {isMulti && <Checkbox checked={selected} />}
                                    {selected && !isMulti && <span style={{ color: '#1890ff' }}>✓</span>}
                                    <span style={{ fontSize: 13, fontWeight: selected ? 600 : 'normal' }}>{v.label}</span>
                                  </Space>
                                  {showPrice && Number(v.priceDelta) > 0 && (
                                    <div style={{ fontSize: 11, color: '#d48806', marginTop: 4 }}>+¥{v.priceDelta}</div>
                                  )}
                                  {showPrice && Number(v.priceDelta) < 0 && (
                                    <div style={{ fontSize: 11, color: '#52c41a', marginTop: 4 }}>¥{v.priceDelta}</div>
                                  )}
                                </Card>
                              </Col>
                            );
                          })}
                        </Row>
                      ) : o.assignMode === 'MANUAL' ? (
                        o.dataType === 'NUMBER' ? (
                          <InputNumber
                            value={sel ? Number(sel) : undefined} placeholder={o.defaultValue}
                            min={o.minValue ? Number(o.minValue) : undefined}
                            max={o.maxValue ? Number(o.maxValue) : undefined}
                            onChange={v => setSelectedValues({ ...selectedValues, [o.code]: String(v ?? '') })}
                          />
                        ) : (
                          <Input value={sel} placeholder={o.defaultValue}
                                 onChange={e => setSelectedValues({ ...selectedValues, [o.code]: e.target.value })} />
                        )
                      ) : <div style={{ color: '#999', fontSize: 12 }}>暂无可选值</div>}
                    </Card>
                  );
                })
              }
            </div>
          </Col>
        </Row>

        {/* 配置摘要 Tags */}
        {summaryTags.length > 0 && (
          <div style={{ marginTop: 14, padding: '10px 14px', background: '#fafbfc',
                        border: '1px solid #f0f0f0', borderRadius: 6 }}>
            <div style={{ fontSize: 11, color: '#999', marginBottom: 6 }}>📦 当前配置摘要</div>
            <Space wrap size={[6, 4]}>
              {summaryTags.map((t, i) => <Tag key={i} color="blue">{t.label}</Tag>)}
            </Space>
          </div>
        )}

        {/* 底部价格 + 提交 */}
        {showPrice && (
          <div style={{ marginTop: 14, padding: '14px 18px', background: '#fafbfc',
                        border: '1px solid #e0e0e0', borderRadius: 6, display: 'flex',
                        alignItems: 'center', gap: 14 }}>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 12, color: '#999' }}>当前估价</div>
              <div style={{ fontSize: 24, fontWeight: 600, color: '#cf1322' }}>
                ¥{evalResult?.totalPrice ? Number(evalResult.totalPrice).toLocaleString() : '0'}
              </div>
              {evalResult?.priceBreakdown && evalResult.priceBreakdown.length > 0 && (
                <div style={{ fontSize: 11, color: '#666', marginTop: 4 }}>
                  基础 ¥{evalResult.basePrice}
                  {evalResult.priceBreakdown.map((b: any, i: number) =>
                    <span key={i}> + {b.value_label} {Number(b.delta) > 0 ? `+¥${b.delta}` : Number(b.delta) < 0 ? `¥${b.delta}` : ''}</span>
                  )}
                </div>
              )}
            </div>
            <Button size="large" type="primary" disabled={!isComplete} onClick={submit}
                    style={{ background: '#52c41a', borderColor: '#52c41a' }}>
              🎯 提交配置 →
            </Button>
          </div>
        )}
        {!showPrice && (
          <div style={{ marginTop: 14, textAlign: 'right' }}>
            <Button size="large" type="primary" disabled={!isComplete} onClick={submit}
                    style={{ background: '#52c41a', borderColor: '#52c41a' }}>
              🎯 提交配置 →
            </Button>
          </div>
        )}
        {!isComplete && (
          <div style={{ marginTop: 10, color: '#d48806', fontSize: 12 }}>
            ⚠ 还有 {requiredOpts.length - filledRequired} 个必选项未选完，无法提交
          </div>
        )}
      </Card>

      {/* 提交对话框 — 复用 P0-3 逻辑 */}
      <Modal open={submitOpen}
             title={submitStep === 'review' ? '✓ 配置已保存为选配实例'
                   : submitStep === 'linkExisting' ? '🔗 选择要绑定的报价单' : '✓ 已完成'}
             onCancel={() => setSubmitOpen(false)} footer={null} width={560}>
        {submitStep === 'review' && createdInstance && (
          <>
            <Descriptions size="small" bordered column={1}>
              <Descriptions.Item label="实例编号"><b>{createdInstance.instanceCode}</b></Descriptions.Item>
              <Descriptions.Item label="客户">{cust.name} ({cust.tier})</Descriptions.Item>
              <Descriptions.Item label="状态"><Tag color="blue">SUBMITTED</Tag></Descriptions.Item>
              <Descriptions.Item label="总价">¥{evalResult?.totalPrice ? Number(evalResult.totalPrice).toLocaleString() : '0'}</Descriptions.Item>
            </Descriptions>
            <div style={{ marginTop: 14, fontSize: 13 }}>请选择下一步：</div>
            <Space direction="vertical" style={{ width: '100%', marginTop: 10 }}>
              <Button block size="large" type="primary" style={{ background: '#52c41a', borderColor: '#52c41a' }}
                      onClick={() => chooseAction('NEW_QUOTATION')}>🎯 直接生成新报价单</Button>
              <Button block onClick={() => chooseAction('SAVE_DRAFT')}>💾 保存为草稿（稍后处理）</Button>
              <Button block onClick={() => chooseAction('LINK_EXISTING')}>🔗 绑到已有报价单</Button>
            </Space>
          </>
        )}
        {submitStep === 'linkExisting' && (
          <>
            <div style={{ fontSize: 13, marginBottom: 10 }}>请输入报价单 UUID：</div>
            <Input.TextArea rows={2} value={existingQuotationId}
                            onChange={e => setExistingQuotationId(e.target.value)}
                            placeholder="如 a3f2e7b4-..." />
            <Space style={{ marginTop: 14, float: 'right' }}>
              <Button onClick={() => setSubmitStep('review')}>← 返回</Button>
              <Button type="primary" onClick={confirmLinkExisting}>✓ 确认绑定</Button>
            </Space>
          </>
        )}
        {submitStep === 'done' && actionResult && (
          <>
            <Descriptions size="small" bordered column={1}>
              <Descriptions.Item label="实例状态"><Tag color="green">LINKED</Tag></Descriptions.Item>
              {actionResult.quotation_id && (
                <Descriptions.Item label="报价单 ID">
                  <code style={{ fontSize: 10.5 }}>{actionResult.quotation_id}</code>
                </Descriptions.Item>
              )}
              {actionResult.part_no && (
                <Descriptions.Item label="生成料号"><code>{actionResult.part_no}</code></Descriptions.Item>
              )}
            </Descriptions>
            {actionResult.note && (
              <div style={{ marginTop: 10, padding: 8, background: '#fffbe6',
                            border: '1px solid #ffe58f', borderRadius: 4, fontSize: 11.5, color: '#876800' }}>
                ⓘ {actionResult.note}
              </div>
            )}
            <div style={{ marginTop: 14, textAlign: 'right' }}>
              <Space>
                <Button onClick={() => { setSubmitOpen(false); navigate('/configurator/instances'); }}>查看实例列表</Button>
                <Button type="primary" onClick={() => setSubmitOpen(false)}>关闭</Button>
              </Space>
            </div>
          </>
        )}
      </Modal>

      {/* 分享对话框 */}
      <Modal open={shareOpen} title="🔗 分享给客户"
             onCancel={() => setShareOpen(false)} footer={null} width={520}>
        {!shareResult ? (
          <>
            <div style={{ marginBottom: 12, fontSize: 13 }}>
              生成公网可访问的分享链接，客户填联系方式后提交（生成 customer_lead 待销售审核）
            </div>
            <div style={{ marginBottom: 10 }}>
              <div style={{ marginBottom: 6, fontSize: 12 }}>客户邮箱</div>
              <Input value={shareEmail} onChange={e => setShareEmail(e.target.value)}
                     placeholder="customer@example.com" />
            </div>
            <Row gutter={10}>
              <Col span={12}>
                <div style={{ marginBottom: 6, fontSize: 12 }}>有效期（天）</div>
                <InputNumber value={shareDays} min={1} max={30} onChange={v => setShareDays(v || 7)} style={{ width: '100%' }} />
              </Col>
              <Col span={12}>
                <div style={{ marginBottom: 6, fontSize: 12 }}>允许客户修改</div>
                <Switch checked={shareCanModify} onChange={setShareCanModify} />
              </Col>
            </Row>
            <Space style={{ marginTop: 14, float: 'right' }}>
              <Button onClick={() => setShareOpen(false)}>取消</Button>
              <Button type="primary" onClick={confirmShare}>✓ 生成链接</Button>
            </Space>
          </>
        ) : (
          <>
            <div style={{ padding: 12, background: '#f6ffed', border: '1px solid #b7eb8f', borderRadius: 4, marginBottom: 12 }}>
              <div style={{ fontSize: 13, fontWeight: 600, color: '#389e0d', marginBottom: 6 }}>✓ 分享链接已生成</div>
              <div style={{ fontSize: 11.5, color: '#666' }}>
                token: <code>{shareResult.shareToken}</code><br/>
                邮箱: {shareResult.sharedToEmail}<br/>
                有效期: 至 {new Date(shareResult.expiresAt).toLocaleDateString()}
              </div>
            </div>
            <div style={{ marginBottom: 8, fontSize: 12 }}>完整 URL：</div>
            <Input value={`https://cpq.example.com/share/configurator/${shareResult.shareToken}`} readOnly />
            <Space style={{ marginTop: 14, float: 'right' }}>
              <Button onClick={copyShareUrl}>📋 复制 URL</Button>
              <Button onClick={() => message.success('📧 邮件已发送（mock）')}>📧 发送邮件</Button>
              <Button type="primary" onClick={() => setShareOpen(false)}>关闭</Button>
            </Space>
          </>
        )}
      </Modal>
    </div>
  );
};

export default ConfiguratorPage;
