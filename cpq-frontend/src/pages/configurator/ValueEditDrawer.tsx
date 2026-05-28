import React, { useEffect, useState } from 'react';
import {
  Drawer, Tabs, Form, Input, InputNumber, Switch, Select, Button, Space, Tag, Table, Card, Radio, message, Popconfirm,
} from 'antd';
import { configuratorTemplateService } from '../../services/configuratorService';
import { partModelService } from '../../services/partModelService';
import { formulaService } from '../../services/formulaService';
import { VALVE_MESHES } from '../../components/ValveBabylon3D';
import type { ConfiguratorOption, ConfiguratorOptionValue } from '../../types/configurator';
import type { PartModel } from '../../types/part-model';

interface Props {
  open: boolean;
  option: ConfiguratorOption | null;
  value: ConfiguratorOptionValue | null;
  templateCategory?: string;   // 用于决定 mesh 选项（阀门 = VALVE_MESHES）
  onClose: () => void;
  onSaved: () => void;
}

/**
 * 按 category 返回可用 mesh 选项
 */
function getMeshOptions(category?: string): { value: string; label: string }[] {
  if (category === '阀门') {
    return VALVE_MESHES.map(m => ({
      value: m.name,
      label: `${m.name} — ${m.desc}`,
    }));
  }
  return []; // 其他品类后续扩展
}

const FEATURE_TYPES = [
  { value: '', label: '— 无（仅作选项）' },
  { value: 'MATERIAL', label: 'MATERIAL · 材质' },
  { value: 'THREAD', label: 'THREAD · 螺纹' },
  { value: 'WELD', label: 'WELD · 焊缝' },
  { value: 'COATING', label: 'COATING · 镀层' },
  { value: 'INTERFACE', label: 'INTERFACE · 装配接口' },
  { value: 'SLOT', label: 'SLOT · 槽' },
  { value: 'HOLE', label: 'HOLE · 通孔/盲孔' },
  { value: 'SURFACE', label: 'SURFACE · 表面区' },
  { value: 'MODEL_VARIANT', label: 'MODEL_VARIANT · 型号变体' },
  { value: 'GENERAL', label: 'GENERAL · 通用' },
];

const RULE_ACTIONS = [
  { value: 'SHOW_MESH', label: 'SHOW_MESH 显示 mesh', color: '#52c41a' },
  { value: 'HIDE_MESH', label: 'HIDE_MESH 隐藏 mesh', color: '#f5222d' },
  { value: 'REPLACE_MATERIAL', label: 'REPLACE_MATERIAL 替换材质', color: '#1890ff' },
  { value: 'SWAP_MESH', label: 'SWAP_MESH 替换 mesh', color: '#722ed1' },
  { value: 'TRANSFORM_MESH', label: 'TRANSFORM_MESH 变换', color: '#fa8c16' },
];

const PRICE_RULE_TYPES = [
  { value: 'DELTA_FIXED', label: 'DELTA_FIXED 固定加价' },
  { value: 'DELTA_PERCENT', label: 'DELTA_PERCENT 按比例加价' },
  { value: 'SET_FIXED', label: 'SET_FIXED 固定价格' },
  { value: 'FORMULA', label: 'FORMULA 公式' },
];

const REF_TYPES = [
  { value: 'MATERIAL',  color: 'cyan',    label: 'MATERIAL 材质' },
  { value: 'PROCESS',   color: 'blue',    label: 'PROCESS 工序' },
  { value: 'COMPONENT', color: 'purple',  label: 'COMPONENT 子件' },
  { value: 'COST_ITEM', color: 'magenta', label: 'COST_ITEM 成本项' },
  { value: 'GLOBAL_VAR',color: 'orange',  label: 'GLOBAL_VAR 全局变量' },
];

const ValueEditDrawer: React.FC<Props> = ({ open, option, value, templateCategory, onClose, onSaved }) => {
  const meshOptions = getMeshOptions(templateCategory);
  const [form] = Form.useForm();
  const [activeTab, setActiveTab] = useState('basic');
  const [rules, setRules] = useState<any[]>([]);
  const [refs, setRefs] = useState<any[]>([]);  // 业务实体引用（前端 mock，后端待加）
  const [models, setModels] = useState<PartModel[]>([]);
  const [formulaExpr, setFormulaExpr] = useState('');
  const [formulaResult, setFormulaResult] = useState<any>(null);

  useEffect(() => {
    if (!open || !value) { setRules([]); setRefs([]); return; }
    form.setFieldsValue({
      ...value,
      // 解构 metadata 字段（如 isDefault / thumbnailUrl 存到 extra）
      thumbnailUrl: (value as any).thumbnailUrl || '',
      isDefault: option?.defaultValue === value.code,
      // 价格规则类型默认 DELTA_FIXED
      priceRuleType: (value as any).priceRuleType || 'DELTA_FIXED',
      currency: (value as any).currency || 'CNY',
      attributesJson: value.attributes ? JSON.stringify(value.attributes, null, 2) : '{}',
      tagsCsv: value.tags ? value.tags.join(', ') : '',
    });
    setActiveTab('basic');
    // 加载 3D 规则
    configuratorTemplateService.list3DRules(value.id)
      .then((r: any) => setRules(r.data || []))
      .catch(() => setRules([]));
    // 加载子模型选项
    partModelService.list({ page: 0, size: 50, isCurrent: true })
      .then((r: any) => setModels(r.data?.content || []))
      .catch(() => setModels([]));
    // 业务实体引用 — 真调 V245 API
    configuratorTemplateService.listRefs(value.id)
      .then((r: any) => setRefs(r.data || []))
      .catch(() => setRefs([]));
  }, [open, value, option, form]);

  // ===== Save handlers =====
  const saveBasic = async () => {
    if (!value) return;
    try {
      const v = await form.validateFields(['code', 'label', 'description', 'sortOrder', 'priceDelta',
        'partnoInclude', 'isActive', 'featureType', 'attributesJson', 'tagsCsv', 'thumbnailUrl']);
      const patch: any = { ...v };
      // 解析 JSON
      try { patch.attributes = JSON.parse(v.attributesJson || '{}'); } catch { patch.attributes = {}; }
      patch.tags = (v.tagsCsv || '').split(',').map((x: string) => x.trim()).filter(Boolean);
      delete patch.attributesJson; delete patch.tagsCsv;
      await configuratorTemplateService.updateValue(value.id, patch);
      message.success('已保存基本信息');
      onSaved();
    } catch (e: any) { if (!e?.errorFields) message.error('保存失败：' + (e?.message || '')); }
  };

  const savePriceRules = async () => {
    if (!value) return;
    try {
      const v = await form.validateFields(['priceRuleType', 'priceDelta', 'currency']);
      await configuratorTemplateService.updateValue(value.id, {
        priceDelta: v.priceDelta,
        // priceRuleType / currency 暂存 metadata（后端价格规则表后续切片）
      });
      message.success('已保存价格规则');
      onSaved();
    } catch (e: any) { if (!e?.errorFields) message.error('保存失败：' + (e?.message || '')); }
  };

  // ===== 3D Rules =====
  const addRule = (action: string) => {
    setRules([...rules, { _new: true, action, targetMesh: '', params: {}, sortOrder: rules.length + 1 }]);
  };
  const updateRule = (idx: number, field: string, val: any) => {
    const next = [...rules]; next[idx] = { ...next[idx], [field]: val }; setRules(next);
  };
  const removeRule = async (idx: number) => {
    const r = rules[idx];
    if (r.id) {
      try { await configuratorTemplateService.delete3DRule(r.id); } catch (e: any) { message.error(e?.message || ''); return; }
    }
    const next = [...rules]; next.splice(idx, 1); setRules(next);
  };
  const saveRules = async () => {
    if (!value) return;
    try {
      for (const r of rules) {
        if (r._new) {
          await configuratorTemplateService.add3DRule(value.id, {
            action: r.action, targetMesh: r.targetMesh, params: r.params || {}, sortOrder: r.sortOrder,
          });
        } else if (r.id) {
          await configuratorTemplateService.update3DRule(r.id, {
            action: r.action, targetMesh: r.targetMesh, params: r.params, sortOrder: r.sortOrder,
          });
        }
      }
      message.success(`已保存 ${rules.length} 条 3D 规则`);
      const refresh: any = await configuratorTemplateService.list3DRules(value.id);
      setRules(refresh.data || []);
    } catch (e: any) { message.error('保存失败：' + (e?.message || '')); }
  };

  // ===== Sub-model (Dimension 2) =====
  const saveSubModel = async () => {
    if (!value) return;
    try {
      const v = await form.validateFields(['subModelPartNo', 'attachMode', 'replaceBaseMesh']);
      await configuratorTemplateService.updateValue(value.id, v);
      message.success('已保存子模型挂载');
      onSaved();
    } catch (e: any) { if (!e?.errorFields) message.error('保存失败：' + (e?.message || '')); }
  };

  // ===== Refs (V245 后端实现) =====
  const addRef = async () => {
    if (!value) return;
    try {
      const res: any = await configuratorTemplateService.addRef(value.id, {
        refType: 'MATERIAL', refCode: '', qty: '', unit: '', note: '', sortOrder: refs.length + 1, isActive: true,
      });
      setRefs([...refs, res.data]);
    } catch (e: any) { message.error('新增失败：' + (e?.message || '')); }
  };
  const removeRef = async (id: string) => {
    try {
      await configuratorTemplateService.deleteRef(id);
      setRefs(refs.filter(r => r.id !== id));
      message.success('已删除');
    } catch (e: any) { message.error('删除失败：' + (e?.message || '')); }
  };
  const patchRef = async (id: string, patch: any) => {
    try {
      const res: any = await configuratorTemplateService.updateRef(id, patch);
      setRefs(refs.map(x => x.id === id ? res.data : x));
    } catch { /* 行内编辑不阻塞 */ }
  };

  if (!value || !option) return null;

  const isMulti = option.optionType === 'MULTI_SELECT';

  return (
    <Drawer
      title={
        <Space>
          <span>✏️ 编辑取值</span>
          <code style={{ fontSize: 11, color: '#999' }}>{option.code} / {value.code}</code>
          <span style={{ fontWeight: 'normal' }}>· {value.label}</span>
        </Space>
      }
      width={780}
      open={open}
      onClose={onClose}
      extra={<Button onClick={onClose}>关闭</Button>}
    >
      <Form form={form} layout="vertical">
        <Tabs activeKey={activeTab} onChange={setActiveTab}
          items={[
            // ============ 📝 基本 Tab ============
            {
              key: 'basic',
              label: '📝 基本',
              children: (
                <>
                  <Card type="inner" title="基本信息" extra={<Button type="primary" size="small" onClick={saveBasic}>✓ 保存</Button>}>
                    <Form.Item label="代码" name="code" rules={[{ required: true }]}>
                      <Input disabled />
                    </Form.Item>
                    <Form.Item label="显示名" name="label" rules={[{ required: true }]}>
                      <Input />
                    </Form.Item>
                    <Form.Item label="描述" name="description"
                               help="选项卡片上显示的说明文字">
                      <Input.TextArea rows={2} />
                    </Form.Item>
                    <Form.Item label="缩略图 URL" name="thumbnailUrl"
                               help="可选 · 卡片缩略图（如颜色样品）">
                      <Input placeholder="https://..." />
                    </Form.Item>
                    <Space.Compact style={{ width: '100%', gap: 10 }}>
                      <Form.Item label="排序" name="sortOrder" style={{ flex: 1 }}>
                        <InputNumber style={{ width: '100%' }} />
                      </Form.Item>
                      <Form.Item label="参与子料号拼接" name="partnoInclude" valuePropName="checked" style={{ flex: 1 }}>
                        <Switch />
                      </Form.Item>
                      <Form.Item label="激活" name="isActive" valuePropName="checked" style={{ flex: 1 }}>
                        <Switch />
                      </Form.Item>
                    </Space.Compact>
                    <Form.Item label="默认选中" name="isDefault" valuePropName="checked"
                               help={`勾选后该值会成为「${option.code}」的默认值（同选项内仅允许一个默认）`}>
                      <Switch />
                    </Form.Item>
                  </Card>

                  <Card type="inner" title="🔧 特征语义（v0.4 收敛 — 选项值即特征）"
                        style={{ marginTop: 12, background: 'linear-gradient(135deg,#f9f0ff,#fff)', borderColor: '#d3adf7' }}>
                    <div style={{ fontSize: 11.5, color: '#722ed1', marginBottom: 10 }}>
                      ℹ 原 mat_feature 系列表已废弃，特征语义直接配置在选项值（feature_type / attributes / tags）
                    </div>
                    <Form.Item label="特征类型" name="featureType">
                      <Select options={FEATURE_TYPES} />
                    </Form.Item>
                    <Form.Item label="属性 (JSON)" name="attributesJson"
                               help="按特征类型填规格参数，如材质类: {composition, density, conductivity}">
                      <Input.TextArea rows={5} style={{ fontFamily: 'Consolas, monospace', fontSize: 12 }}
                                      placeholder='{\n  "key": "value"\n}' />
                    </Form.Item>
                    <Form.Item label="标签（逗号分隔）" name="tagsCsv"
                               help="如: critical, high-precision, custom-spec">
                      <Input />
                    </Form.Item>
                    <div style={{ padding: 8, background: '#f0f5ff', borderRadius: 4, fontSize: 11.5, color: '#0050b3' }}>
                      📐 <b>几何信息</b>：从 STEP 自动提取，无需手填（包围盒/位置/体积）— 在 3D 上传时由特征识别自动写入
                    </div>
                  </Card>
                </>
              ),
            },
            // ============ 🎬 3D 规则 Tab ============
            {
              key: '3drule',
              label: <>🎬 3D 规则 <Tag color="blue">{rules.length}</Tag></>,
              children: (
                <>
                  <Card type="inner"
                        title={<>🎬 维度 1: base 模型 mesh 操作 <Tag color="blue">{rules.length} 条</Tag></>}
                        extra={<Button type="primary" size="small" onClick={saveRules}>✓ 保存全部规则</Button>}>
                    <div style={{ fontSize: 12, color: '#999', marginBottom: 10 }}>
                      选中此值时按顺序在 <b>base.glb</b> 内执行以下操作（颜色/显隐/变换）
                    </div>
                    {meshOptions.length > 0 && (
                      <div style={{ padding: 8, background: '#f0f5ff', border: '1px solid #91caff', borderRadius: 4,
                                    fontSize: 11.5, color: '#0050b3', marginBottom: 8 }}>
                        ℹ <b>{templateCategory}</b> 类模板可选 <b>{meshOptions.length}</b> 个 mesh：
                        {meshOptions.slice(0, 4).map(m => <code key={m.value} style={{ marginLeft: 4, fontSize: 10.5 }}>{m.value}</code>)}
                        {meshOptions.length > 4 && <span style={{ marginLeft: 4 }}>...</span>}
                      </div>
                    )}
                    {rules.length === 0 && (
                      <div style={{ padding: 20, textAlign: 'center', color: '#999', fontSize: 12 }}>暂无 3D 规则</div>
                    )}
                    {rules.map((r, idx) => (
                      <Card key={r.id || `new-${idx}`} size="small" style={{ marginBottom: 8 }}
                            extra={<a style={{ color: '#f5222d' }} onClick={() => removeRule(idx)}>🗑 删除</a>}>
                        <Space direction="vertical" style={{ width: '100%' }}>
                          <Space style={{ width: '100%' }}>
                            <Select value={r.action} options={RULE_ACTIONS} style={{ width: 220 }}
                                    onChange={v => updateRule(idx, 'action', v)} />
                            <span style={{ color: '#999' }}>项序</span>
                            <InputNumber value={r.sortOrder} onChange={v => updateRule(idx, 'sortOrder', v)} style={{ width: 80 }} />
                          </Space>
                          {meshOptions.length > 0 ? (
                            <Select
                              value={r.targetMesh || undefined}
                              placeholder="选择目标 mesh"
                              showSearch
                              allowClear
                              mode="tags"
                              maxCount={1}
                              style={{ width: '100%' }}
                              options={meshOptions}
                              onChange={(v: any) => updateRule(idx, 'targetMesh',
                                Array.isArray(v) ? (v[0] || '') : (v || ''))}
                            />
                          ) : (
                            <Input value={r.targetMesh || ''} placeholder="目标 mesh 名称（手填）"
                                   onChange={e => updateRule(idx, 'targetMesh', e.target.value)} />
                          )}
                          <Input.TextArea value={typeof r.params === 'string' ? r.params : JSON.stringify(r.params || {}, null, 2)}
                                          rows={2}
                                          style={{ fontFamily: 'Consolas, monospace', fontSize: 11 }}
                                          placeholder='params JSON 如 {"diffuse":"#E8E8EC","metallic":0.9}'
                                          onChange={e => {
                                            try { updateRule(idx, 'params', JSON.parse(e.target.value || '{}')); }
                                            catch { updateRule(idx, 'params', e.target.value); }
                                          }} />
                        </Space>
                      </Card>
                    ))}
                    <Space style={{ flexWrap: 'wrap', marginTop: 6 }}>
                      <Button size="small" style={{ borderColor: '#52c41a', color: '#52c41a' }}
                              onClick={() => addRule('SHOW_MESH')}>+ SHOW_MESH</Button>
                      <Button size="small" style={{ borderColor: '#f5222d', color: '#f5222d' }}
                              onClick={() => addRule('HIDE_MESH')}>+ HIDE_MESH</Button>
                      <Button size="small" style={{ borderColor: '#1890ff', color: '#1890ff' }}
                              onClick={() => addRule('REPLACE_MATERIAL')}>+ REPLACE_MATERIAL</Button>
                      <Button size="small" style={{ borderColor: '#fa8c16', color: '#fa8c16' }}
                              onClick={() => addRule('TRANSFORM_MESH')}>+ TRANSFORM</Button>
                    </Space>
                  </Card>

                  <Card type="inner"
                        title="📦 维度 2: 关联独立子模型（可选扩展）"
                        style={{ marginTop: 12, background: 'linear-gradient(135deg,#fff7e6,#fffbe6)', borderColor: '#ffd591' }}
                        extra={<Button type="primary" size="small" onClick={saveSubModel}>✓ 保存子模型挂载</Button>}>
                    <div style={{ padding: 10, background: '#fff', border: '1px solid #ffe58f', borderRadius: 6, marginBottom: 14 }}>
                      <div style={{ fontWeight: 600, color: '#d48806', marginBottom: 6, fontSize: 12.5 }}>🤔 什么时候用子模型？</div>
                      <div style={{ fontSize: 12, color: '#876800', lineHeight: 1.7 }}>
                        本取值的<b>几何形态跟主模型一样</b>（只是颜色/部件显隐） → <b style={{ color: '#52c41a' }}>不需要</b>，配维度 1 即可<br/>
                        <b>几何形态完全不同</b> → <b style={{ color: '#d48806' }}>配维度 2 关联独立 GLB</b><br/>
                        两个维度可<b>任意组合</b>（例如材质换色 + 加散热器 = 维度 1 REPLACE_MATERIAL + 维度 2 OVERLAY）
                      </div>
                    </div>
                    <Form.Item label="关联子模型" name="subModelPartNo"
                               help="选择已上传的 mat_part_model（在「3D 源文件管理」上传）">
                      <Select allowClear placeholder="— 不关联"
                              options={[
                                { value: '', label: '— 不关联（本取值不需要切换 3D 模型）' },
                                ...models.map(m => ({
                                  value: m.partNo,
                                  label: `${m.partNo} v${m.version} · ${m.label || ''} (${m.sizeKb ? (m.sizeKb/1024).toFixed(1)+'MB' : '-'})`
                                })),
                              ]} />
                    </Form.Item>
                    <Form.Item label="挂载模式" name="attachMode">
                      <Radio.Group>
                        <Radio.Button value="OVERLAY" style={{ width: '33.3%', textAlign: 'center' }}>📍 OVERLAY 叠加</Radio.Button>
                        <Radio.Button value="SWAP" style={{ width: '33.3%', textAlign: 'center' }}>🔄 SWAP 替换部件</Radio.Button>
                        <Radio.Button value="REPLACE_BASE" style={{ width: '33.3%', textAlign: 'center' }}>♻ REPLACE_BASE 整个替换</Radio.Button>
                      </Radio.Group>
                    </Form.Item>
                    <div style={{ background: '#fafbfc', padding: 10, borderRadius: 4, fontSize: 11.5, color: '#666', marginBottom: 10 }}>
                      <div>📍 <b>OVERLAY</b>：主模型完整保留 + 子模型叠加（如附件 / 卡扣）</div>
                      <div>🔄 <b>SWAP</b>：主模型某 mesh 消失 + 子模型填到同位置（如换轮毂）</div>
                      <div>♻ <b>REPLACE_BASE</b>：主模型整个消失 + 子模型顶替（如完全不同型号）</div>
                    </div>
                    <Form.Item label="REPLACE_BASE 模式：替换的主 mesh" name="replaceBaseMesh"
                               valuePropName="checked"
                               help="勾选 = 让 SWAP 模式失效，整体替换 base model">
                      <Switch />
                    </Form.Item>
                    <div style={{ fontSize: 11.5, color: '#876800', background: '#fff', padding: 8, borderRadius: 4, border: '1px solid #ffe58f' }}>
                      💡 性能：子模型走 LRU 缓存 + CDN，建议 GLB ≤ 3MB + Draco 压缩
                    </div>
                  </Card>
                </>
              ),
            },
            // ============ 💰 价格规则 Tab ============
            {
              key: 'price',
              label: '💰 价格规则',
              children: (
                <>
                  <Card type="inner" title="💰 价格规则"
                        extra={<Button type="primary" size="small" onClick={savePriceRules}>✓ 保存价格</Button>}>
                    <Form.Item label="规则类型" name="priceRuleType" initialValue="DELTA_FIXED">
                      <Select options={PRICE_RULE_TYPES} />
                    </Form.Item>
                    <Space.Compact style={{ width: '100%', gap: 10 }}>
                      <Form.Item label="加价金额" name="priceDelta" style={{ flex: 2 }}>
                        <InputNumber style={{ width: '100%' }} step={10}
                                     addonAfter={<Form.Item name="currency" noStyle initialValue="CNY">
                                       <Select style={{ width: 80 }} options={[
                                         { value: 'CNY', label: 'CNY ¥' },
                                         { value: 'USD', label: 'USD $' },
                                       ]} />
                                     </Form.Item>} />
                      </Form.Item>
                    </Space.Compact>
                    <div style={{ padding: 8, background: '#f6ffed', borderRadius: 4, fontSize: 11.5, color: '#389e0d', marginTop: 8 }}>
                      ✓ DELTA_FIXED 直接加 priceDelta · DELTA_PERCENT 按基础价百分比 · SET_FIXED 覆盖基础价 · FORMULA 走公式引擎
                    </div>
                  </Card>

                  <Card type="inner"
                        title={<>💡 公式价格（高级 · FORMULA 类型）</>}
                        style={{ marginTop: 12 }}
                        extra={
                          <Button size="small" type="primary"
                                  onClick={async () => {
                                    if (!formulaExpr.trim()) { message.warning('请输入公式表达式'); return; }
                                    try {
                                      const r: any = await formulaService.evaluate({ expression: formulaExpr });
                                      setFormulaResult(r.data);
                                      if (r.data?.success) message.success(`✓ 公式求值: ${r.data.result}`);
                                      else message.error(`求值失败: ${r.data?.error || ''}`);
                                    } catch (e: any) { message.error('调用失败：' + (e?.message || '')); }
                                  }}>🧮 求值测试</Button>
                        }>
                    <div style={{ fontSize: 11.5, color: '#999', marginBottom: 6 }}>
                      支持 BNF 路径 + 全局变量 token。例如：<code>{'{表面积} * @ELEM_PRICE * 1.2'}</code>
                    </div>
                    <Input.TextArea
                      rows={4}
                      value={formulaExpr}
                      onChange={e => setFormulaExpr(e.target.value)}
                      style={{ fontFamily: 'Consolas, monospace', fontSize: 12 }}
                      placeholder={'示例:\n@ELEM_PRICE * 1.2\n或\n{表面积} * @ELEM_PRICE'}
                    />
                    {formulaResult && (
                      <div style={{ marginTop: 10, padding: 10,
                                    background: formulaResult.success ? '#f6ffed' : '#fff1f0',
                                    border: '1px solid ' + (formulaResult.success ? '#b7eb8f' : '#ffa39e'),
                                    borderRadius: 4, fontSize: 12 }}>
                        {formulaResult.success
                          ? <><b style={{ color: '#389e0d' }}>✓ 结果: {String(formulaResult.result)}</b></>
                          : <><b style={{ color: '#cf1322' }}>✗ {formulaResult.errorType}: {formulaResult.error}</b></>
                        }
                      </div>
                    )}
                    <div style={{ marginTop: 8, padding: 8, background: '#f0f5ff', border: '1px solid #91caff',
                                  borderRadius: 4, fontSize: 11.5, color: '#0050b3' }}>
                      ⓘ 通过 <code>POST /api/cpq/formulas/evaluate</code> 调用现有公式引擎 — 支持 field / global_variable / operator / literal token
                    </div>
                  </Card>
                </>
              ),
            },
            // ============ 🔧 特征关联 Tab ============
            {
              key: 'feature',
              label: <>🔧 特征关联 <Tag>{refs.length}</Tag></>,
              children: (
                <Card type="inner"
                      title="🔗 业务实体引用 (product_config_value_reference)"
                      extra={<Button type="primary" size="small" onClick={addRef}>+ 新增引用</Button>}>
                  <div style={{ padding: 8, background: '#e6f4ff', border: '1px solid #91caff', borderRadius: 4,
                                fontSize: 11.5, color: '#0050b3', marginBottom: 12 }}>
                    ℹ <b>v0.4 收敛后</b>：直接关联工序 / 材质 / 子件 等业务实体，不再走 mat_feature 中间层。
                    提交报价单时自动写入对应 line_item。
                  </div>
                  <Table size="small" rowKey="id" pagination={false} dataSource={refs}
                    locale={{ emptyText: '暂无引用 — 点击右上「+ 新增引用」' }}
                    columns={[
                      { title: '引用类型', dataIndex: 'refType', width: 130,
                        render: (v: string, r: any) => (
                          <Select value={v} size="small" style={{ width: '100%' }}
                                  onChange={n => { setRefs(refs.map(x => x.id === r.id ? { ...x, refType: n } : x)); patchRef(r.id, { refType: n }); }}
                                  options={REF_TYPES.map(t => ({ value: t.value, label: <Tag color={t.color}>{t.label}</Tag> }))} />
                        )},
                      { title: '业务实体编码', dataIndex: 'refCode',
                        render: (v: string, r: any) => (
                          <Input size="small" value={v} placeholder="如 MAT-AGCU-85-15"
                                 onChange={e => setRefs(refs.map(x => x.id === r.id ? { ...x, refCode: e.target.value } : x))}
                                 onBlur={e => patchRef(r.id, { refCode: e.target.value })} />
                        )},
                      { title: '数量', dataIndex: 'qty', width: 80,
                        render: (v: string, r: any) => (
                          <Input size="small" value={v}
                                 onChange={e => setRefs(refs.map(x => x.id === r.id ? { ...x, qty: e.target.value } : x))}
                                 onBlur={e => patchRef(r.id, { qty: e.target.value })} />
                        )},
                      { title: '单位', dataIndex: 'unit', width: 60,
                        render: (v: string, r: any) => (
                          <Input size="small" value={v}
                                 onChange={e => setRefs(refs.map(x => x.id === r.id ? { ...x, unit: e.target.value } : x))}
                                 onBlur={e => patchRef(r.id, { unit: e.target.value })} />
                        )},
                      { title: '备注', dataIndex: 'note',
                        render: (v: string, r: any) => (
                          <Input size="small" value={v}
                                 onChange={e => setRefs(refs.map(x => x.id === r.id ? { ...x, note: e.target.value } : x))}
                                 onBlur={e => patchRef(r.id, { note: e.target.value })} />
                        )},
                      { title: '操作', width: 60, render: (_, r: any) => (
                        <Popconfirm title="删除？" onConfirm={() => removeRef(r.id)}>
                          <a style={{ color: '#f5222d' }}>🗑</a>
                        </Popconfirm>
                      )},
                    ]}
                  />
                  <div style={{ marginTop: 14, padding: 8, background: '#f6ffed', border: '1px solid #b7eb8f', borderRadius: 4,
                                fontSize: 11.5, color: '#389e0d' }}>
                    ✓ <b>V245 已上线</b>：编辑实时保存到 product_config_value_reference 表。
                    提交报价单时自动从此表读取并生成 line_item。
                  </div>
                </Card>
              ),
            },
          ]}
        />
      </Form>
    </Drawer>
  );
};

export default ValueEditDrawer;
