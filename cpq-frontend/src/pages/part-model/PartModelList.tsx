import React, { useEffect, useState } from 'react';
import {
  Card, Table, Tag, Button, Space, message, Drawer, Form, Input, InputNumber,
  Steps, Radio, Select, Switch, Popconfirm, Progress, Tooltip,
} from 'antd';
import { partModelService } from '../../services/partModelService';
import { configuratorTemplateService } from '../../services/configuratorService';
import type { PartModel } from '../../types/part-model';
import type { ConfiguratorTemplate } from '../../types/configurator';
import ConfiguratorPreview from '../../components/ConfiguratorPreview';

/**
 * v0.4 §6 3D 模型管理 — 列表 + 4 步上传向导
 *
 * 转换流水线（UG NX .prt + .stp → FreeCAD → Blender → GLB Draco）仍是 TODO（后端 mock）
 */
const PartModelList: React.FC = () => {
  const [data, setData] = useState<PartModel[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [step, setStep] = useState(0);
  const [mode, setMode] = useState<'UGNX' | 'GLB'>('GLB');
  const [form] = Form.useForm();
  const [registered, setRegistered] = useState<PartModel | null>(null);

  // 5 阶段转换动画状态（仅 UGNX 模式）
  const [conversionStage, setConversionStage] = useState<number>(-1); // -1 未开始, 0~4 进行中, 5 完成
  const [tutorialOpen, setTutorialOpen] = useState(false);

  // 特征自动识别（UGNX 模式 Step 3 后展示）
  const [features, setFeatures] = useState<any[]>([]);

  // 关联模板
  const [templates, setTemplates] = useState<ConfiguratorTemplate[]>([]);
  const [selectedTplId, setSelectedTplId] = useState<string | undefined>();

  const load = async () => {
    setLoading(true);
    try {
      const res: any = await partModelService.list({ page, size });
      setData(res.data?.content || []);
      setTotal(res.data?.totalElements || 0);
    } catch (e: any) {
      message.error('加载失败：' + (e?.message || ''));
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { load(); /* eslint-disable-next-line */ }, [page, size]);

  const openWizard = async () => {
    setStep(0);
    setMode('GLB');
    setRegistered(null);
    form.resetFields();
    form.setFieldsValue({ version: 1, isCurrent: true });
    setDrawerOpen(true);

    // 预加载可关联的模板
    try {
      const res: any = await configuratorTemplateService.list({ page: 0, size: 50 });
      setTemplates(res.data?.content || []);
    } catch { /* ignore */ }
  };

  const next = async () => {
    if (step === 0) { setStep(1); return; }
    if (step === 1) {
      try {
        await form.validateFields(['partNo', 'version', 'label']);
        setStep(2);
      } catch { /* validation */ }
      return;
    }
    if (step === 2) {
      try {
        const v = await form.validateFields();
        v.glbUrl = v.glbUrl || `cpq-3d-glb://mock/${v.partNo}/v${v.version}/model.glb`;

        // UGNX 模式：先播 5 阶段动画再注册
        if (mode === 'UGNX') {
          setConversionStage(0);
          const stages = [
            { name: 'FreeCAD 解析 STEP', ms: 1200 },
            { name: 'Blender 转换 GLB', ms: 1500 },
            { name: '生成缩略图', ms: 600 },
            { name: '特征识别', ms: 800 },
            { name: '入库注册', ms: 400 },
          ];
          for (let i = 0; i < stages.length; i++) {
            setConversionStage(i);
            await new Promise(r => setTimeout(r, stages[i].ms));
          }
          setConversionStage(5);
          // 模拟自动识别 5 个特征
          setFeatures([
            { id: 1, type: 'HOLE',    code: 'FEAT-HOLE-D8',  attrs: '{"diameter_mm":8.0,"depth_mm":15.2,"through":true}', bbox: '[10,20,30]', confirmed: true },
            { id: 2, type: 'HOLE',    code: 'FEAT-HOLE-D12', attrs: '{"diameter_mm":12.0,"depth_mm":18.0,"through":false}', bbox: '[15,22,35]', confirmed: true },
            { id: 3, type: 'THREAD',  code: 'FEAT-THREAD-M8', attrs: '{"pitch_mm":1.25,"diameter_mm":8.0,"length_mm":18}', bbox: '[20,25,40]', confirmed: true },
            { id: 4, type: 'SURFACE', code: 'FEAT-SURF-OUTER', attrs: '{"area_mm2":2480,"roughness_um":1.6}', bbox: '[50,50,5]', confirmed: false },
            { id: 5, type: 'WELD',    code: 'FEAT-WELD-001', attrs: '{"length_mm":120,"type":"FILLET"}', bbox: '[100,5,3]', confirmed: false },
          ]);
        }

        const res: any = await partModelService.register(v);
        setRegistered(res.data);
        message.success(`模型已注册 · ${res.data?.partNo} v${res.data?.version}`);
        setStep(3);
        setConversionStage(-1);
        load();
      } catch (e: any) {
        setConversionStage(-1);
        if (e?.errorFields) return;
        message.error('注册失败：' + (e?.message || ''));
      }
      return;
    }
    if (step === 3) {
      // 关联到模板
      if (selectedTplId && registered) {
        try {
          await configuratorTemplateService.setBaseModel(selectedTplId, registered.id, registered.version);
          message.success(`已关联到模板 · 写入 base_model_id`);
        } catch (e: any) {
          message.error('关联失败：' + (e?.message || ''));
        }
      }
      setDrawerOpen(false);
    }
  };

  const setCurrent = async (id: string) => {
    try {
      await partModelService.setCurrent(id);
      message.success('已设为当前版本');
      load();
    } catch (e: any) { message.error('失败：' + (e?.message || '')); }
  };

  return (
    <div style={{ padding: 16 }}>
      <Card title="📦 3D 源文件管理" extra={
        <Space>
          <Button onClick={() => setTutorialOpen(true)}>📖 UG NX 导出教程</Button>
          <Button type="primary" onClick={openWizard}>+ 上传新模型</Button>
        </Space>
      }>
        <Table<PartModel> rowKey="id"
          loading={loading}
          dataSource={data}
          pagination={{ current: page + 1, pageSize: size, total,
            onChange: (p, s) => { setPage(p - 1); setSize(s); } }}
          columns={[
            { title: '预览', width: 100, render: (_, r) => (
              <ConfiguratorPreview partNo={r.partNo} glbUrl={r.glbUrl} height={70}
                                   autoRotate={false} cameraControls={false} showLabels={false} />
            )},
            { title: '料号', dataIndex: 'partNo', width: 180 },
            { title: '版本', dataIndex: 'version', width: 70 },
            { title: '当前', dataIndex: 'isCurrent', width: 90,
              render: (v: boolean) => v ? <Tag color="green">● current</Tag> : <Tag>历史</Tag> },
            { title: '名称', dataIndex: 'label' },
            { title: 'Mesh', dataIndex: 'meshCount', width: 80, align: 'right' },
            { title: '顶点', dataIndex: 'vertices', width: 110, align: 'right',
              render: (v: number) => v ? v.toLocaleString() : '-' },
            { title: '大小', dataIndex: 'sizeKb', width: 100, align: 'right',
              render: (v: number) => v ? `${(v/1024).toFixed(2)} MB` : '-' },
            { title: '上传时间', dataIndex: 'uploadedAt', width: 170,
              render: (v: string) => v ? new Date(v).toLocaleString() : '-' },
            { title: '操作', width: 130, render: (_, r) => (
              <Space>
                {!r.isCurrent && (
                  <Popconfirm title="设为当前版本？同 partNo 其他版本会变历史。" onConfirm={() => setCurrent(r.id)}>
                    <a>设为当前</a>
                  </Popconfirm>
                )}
              </Space>
            )},
          ]}
        />
      </Card>

      {/* 上传向导 Drawer */}
      <Drawer
        title="+ 上传 3D 模型 — 向导"
        width={720}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        extra={
          <Space>
            {step > 0 && step < 3 && <Button onClick={() => setStep(step - 1)}>← 上一步</Button>}
            <Button onClick={() => setDrawerOpen(false)}>取消</Button>
            <Button type="primary" onClick={next}>
              {step === 0 ? '下一步 →' : step === 1 ? '下一步 →' : step === 2 ? '✓ 注册模型' : '完成'}
            </Button>
          </Space>
        }
      >
        <Steps current={step} size="small" style={{ marginBottom: 24 }}
               items={[
                 { title: '选择模式' },
                 { title: '基本信息' },
                 { title: '上传/注册' },
                 { title: '关联模板' },
               ]} />

        {step === 0 && (
          <div>
            <Radio.Group value={mode} onChange={e => setMode(e.target.value)} style={{ width: '100%' }}>
              <Space direction="vertical" style={{ width: '100%' }}>
                <Radio value="UGNX" style={{ padding: 16, border: '1px solid #e0e0e0', borderRadius: 6, width: '100%' }}>
                  <div style={{ fontWeight: 600 }}>📐 UG NX 工作流（.prt + .stp 双文件）</div>
                  <div style={{ fontSize: 12, color: '#666' }}>
                    上传 UG 源文件 + STEP 中性格式 → 后端 FreeCAD + Blender 流水线转 GLB
                    <div style={{ marginTop: 4, color: '#d48806' }}>
                      ⚠ 转换流水线尚未集成（后端 mock）— 当前仅做注册，不真实转换
                    </div>
                  </div>
                </Radio>
                <Radio value="GLB" style={{ padding: 16, border: '1px solid #e0e0e0', borderRadius: 6, width: '100%' }}>
                  <div style={{ fontWeight: 600 }}>🎬 直接上传 GLB</div>
                  <div style={{ fontSize: 12, color: '#666' }}>
                    已有 GLB 文件（工程师在 Blender / SolidWorks 自行导出）→ 直接入库可用
                  </div>
                </Radio>
              </Space>
            </Radio.Group>
          </div>
        )}

        {step === 1 && (
          <Form form={form} layout="vertical">
            <Form.Item label="料号 (part_no)" name="partNo" rules={[{ required: true, max: 64 }]}>
              <Input placeholder="如 CFG-CONTACT-STRIP-BASE" />
            </Form.Item>
            <Form.Item label="版本" name="version" rules={[{ required: true }]} initialValue={1}>
              <InputNumber min={1} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item label="名称 (label)" name="label" rules={[{ max: 255 }]}>
              <Input placeholder="如 CFG-CONTACT-STRIP v3" />
            </Form.Item>
            <Form.Item label="设为当前版本" name="isCurrent" valuePropName="checked"
                       help="同 partNo 其他版本会变历史；未设置则不影响其他版本">
              <Switch defaultChecked />
            </Form.Item>
          </Form>
        )}

        {step === 2 && (
          <Form form={form} layout="vertical">
            {mode === 'UGNX' && (
              <Card type="inner" size="small" title="🔐 文件 MD5 校验" style={{ marginBottom: 12 }}>
                <div style={{ fontSize: 11.5, color: '#666', display: 'grid', gridTemplateColumns: '120px 1fr', gap: 4 }}>
                  <div>.prt 源文件:</div><code>md5: a3f2e7b4c8d1...e9f2c (4.4 MB · UG NX 12.0)</code>
                  <div>.stp 中性文件:</div><code>md5: b7c4d8a2e9f1...f3a8d (3.1 MB · AP214)</code>
                  <div style={{ color: '#52c41a' }}>✓ 一致性:</div><span style={{ color: '#52c41a' }}>双文件时间戳一致 + 哈希匹配（可入库）</span>
                </div>
              </Card>
            )}
            {mode === 'UGNX' && conversionStage >= 0 && conversionStage < 5 && (
              <div style={{ padding: 16, background: '#f0f9ff', border: '1px solid #91d5ff', borderRadius: 6, marginBottom: 14 }}>
                <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 10 }}>🔄 转换流水线进行中...</div>
                {['FreeCAD 解析 STEP', 'Blender 转换 GLB', '生成缩略图', '特征识别', '入库注册'].map((name, i) => (
                  <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6, fontSize: 12 }}>
                    <span style={{ width: 24, textAlign: 'center' }}>
                      {i < conversionStage ? '✅' : i === conversionStage ? '⚙️' : '⚪'}
                    </span>
                    <span style={{ width: 180, color: i <= conversionStage ? '#303133' : '#999' }}>{name}</span>
                    <div style={{ flex: 1 }}>
                      <Progress percent={i < conversionStage ? 100 : i === conversionStage ? 50 : 0}
                                showInfo={false} size="small"
                                strokeColor={i < conversionStage ? '#52c41a' : '#1890ff'} />
                    </div>
                  </div>
                ))}
              </div>
            )}
            <div style={{ padding: 12, background: '#fffbe6', border: '1px solid #ffe58f', borderRadius: 4, fontSize: 12, marginBottom: 16, color: '#876800' }}>
              ⓘ 当前为骨架版 — multipart 文件上传集成待后续切片。
              {mode === 'UGNX' ? '请填写 GLB URL（实际由转换流水线生成）。点「✓ 注册模型」会播放 5 阶段转换动画 mock。' : '请填写 GLB URL。'}
            </div>
            <Form.Item label="GLB URL" name="glbUrl" rules={[{ required: false }]}
                       help="留空则自动生成 mock URL，实际场景由后端 multipart 上传后返回">
              <Input placeholder="cpq-3d-glb://..." />
            </Form.Item>
            <Form.Item label="缩略图 URL" name="thumbnailUrl">
              <Input placeholder="可选" />
            </Form.Item>
            <Space.Compact style={{ width: '100%', gap: 10 }}>
              <Form.Item label="Mesh 数" name="meshCount" style={{ flex: 1 }}>
                <InputNumber style={{ width: '100%' }} placeholder="如 11" />
              </Form.Item>
              <Form.Item label="顶点数" name="vertices" style={{ flex: 1 }}>
                <InputNumber style={{ width: '100%' }} placeholder="如 12840" />
              </Form.Item>
              <Form.Item label="大小 (KB)" name="sizeKb" style={{ flex: 1 }}>
                <InputNumber style={{ width: '100%' }} placeholder="如 1432" />
              </Form.Item>
            </Space.Compact>
          </Form>
        )}

        {step === 3 && registered && (
          <div>
            <div style={{ padding: 14, background: '#f6ffed', border: '1px solid #b7eb8f', borderRadius: 6, marginBottom: 16 }}>
              <div style={{ fontSize: 14, fontWeight: 600, color: '#389e0d' }}>
                ✓ 模型已注册 · {registered.partNo} v{registered.version}
              </div>
              <div style={{ fontSize: 11.5, color: '#666', marginTop: 6 }}>
                ID: <code>{registered.id}</code>
              </div>
            </div>
            <div style={{ marginBottom: 16 }}>
              <div style={{ fontSize: 12, color: '#666', marginBottom: 6 }}>🎬 模型预览</div>
              <ConfiguratorPreview partNo={registered.partNo} glbUrl={registered.glbUrl} height={220}
                                   autoRotate cameraControls showLabels
                                   label={`${registered.partNo} v${registered.version}`} />
            </div>

            {/* 特征自动识别审核表（仅 UGNX 模式） */}
            {mode === 'UGNX' && features.length > 0 && (
              <Card type="inner" size="small" title={<>🔍 自动识别 {features.length} 个特征 — 请审核后入库</>} style={{ marginBottom: 16 }}>
                <div style={{ fontSize: 11.5, color: '#876800', marginBottom: 10, padding: 6, background: '#fffbe6', borderRadius: 4 }}>
                  ⚠ <b>特征不自动入库</b>，必须勾选 + 确认 feature_type 后写入 mat_part_mesh_feature（避免特征字典污染）
                </div>
                <Table size="small" rowKey="id" pagination={false} dataSource={features}
                  columns={[
                    { title: '', width: 30, render: (_, f: any) => (
                      <input type="checkbox" checked={f.confirmed}
                             onChange={() => setFeatures(features.map(x => x.id === f.id ? { ...x, confirmed: !x.confirmed } : x))} />
                    )},
                    { title: '类型', dataIndex: 'type', width: 90, render: (v: string) => {
                      const colors: Record<string, string> = {
                        HOLE: 'cyan', THREAD: 'purple', SURFACE: 'orange', WELD: 'red', SLOT: 'green', GENERAL: 'default',
                      };
                      return <Tag color={colors[v] || 'default'}>{v}</Tag>;
                    }},
                    { title: '建议代码', dataIndex: 'code', width: 150, render: (v: string) => <code>{v}</code> },
                    { title: '属性 (从 STEP 提取)', dataIndex: 'attrs', ellipsis: true,
                      render: (v: string) => <code style={{ fontSize: 10.5 }}>{v}</code> },
                    { title: '包围盒', dataIndex: 'bbox', width: 90, render: (v: string) => <code style={{ fontSize: 10.5 }}>{v}</code> },
                    { title: '操作', width: 70, render: (_, f: any) => (
                      <Space size={4}>
                        <a onClick={() => message.info(`编辑特征 ${f.code}`)}>✏️</a>
                        <a style={{ color: '#f5222d' }}
                           onClick={() => setFeatures(features.filter(x => x.id !== f.id))}>🗑</a>
                      </Space>
                    )},
                  ]}
                />
                <div style={{ marginTop: 8, padding: 8, background: '#f6ffed', border: '1px solid #b7eb8f', borderRadius: 4, fontSize: 11.5, color: '#389e0d' }}>
                  ✓ 已勾选 <b>{features.filter(f => f.confirmed).length}</b> 个特征待入库 ·
                  将作为 <code>product_config_option_value</code> 的特征语义（feature_type / attributes / geometry_ref）使用
                </div>
              </Card>
            )}
            <Form layout="vertical">
              <Form.Item label="（可选）立即关联到选配模板">
                <Select placeholder="选择模板（可跳过）" allowClear
                        value={selectedTplId} onChange={setSelectedTplId}
                        options={templates.map(t => ({
                          value: t.id,
                          label: `${t.code} · ${t.name}${t.baseModelId ? ' (已绑定，将覆盖)' : ''}`,
                        }))} />
              </Form.Item>
              <div style={{ padding: 10, background: '#fff7e6', border: '1px solid #ffd591', borderRadius: 4, fontSize: 11.5, color: '#876800' }}>
                ⓘ 关联后写入 <code>product_config_template.base_model_id + version + snapshot_at</code>。
                如所选模板已 PUBLISHED 且生成过实例，建议先在模板编辑器里新建草稿版本（§13）。
              </div>
            </Form>
          </div>
        )}
      </Drawer>

      {/* UG NX 导出教程抽屉 */}
      <Drawer title="📖 从 UG NX 导出 STEP 文件 — 操作指南" width={640}
              open={tutorialOpen} onClose={() => setTutorialOpen(false)}>
        <div style={{ fontSize: 13, lineHeight: 1.8 }}>
          <h3>🎯 操作目标</h3>
          <p>在 UG NX 打开产品 .prt → File → Export → STEP 导出标准 STEP 格式（AP214 / AP242）→ 同时保留原 .prt + 新 .stp → 上传到 CPQ。</p>

          <h3 style={{ marginTop: 16 }}>📋 详细步骤</h3>
          <div style={{ background: '#fafbfc', padding: 12, borderRadius: 4, marginBottom: 12 }}>
            <b>Step 1.</b> 打开 UG NX，加载 .prt 装配体或单件
          </div>
          <div style={{ background: '#fafbfc', padding: 12, borderRadius: 4, marginBottom: 12 }}>
            <b>Step 2.</b> 主菜单 <code>File → Export → STEP203 / STEP214 / STEP242</code>
            <div style={{ fontSize: 11, color: '#999', marginTop: 4 }}>💡 推荐 STEP214（兼容性最好），新版 UG 可用 STEP242（含 PMI 信息）</div>
          </div>
          <div style={{ background: '#fafbfc', padding: 12, borderRadius: 4, marginBottom: 12 }}>
            <b>Step 3.</b> 导出对话框设置：
            <ul style={{ margin: '6px 0 0 18px', fontSize: 12 }}>
              <li>勾选 <b>Save Solid Models</b> + <b>Save Surface Models</b></li>
              <li>勾选 <b>Single File Mode</b>（便于上传）</li>
              <li>单位：<b>Millimeters</b>（与 CPQ 一致）</li>
              <li>命名：<code>{`{partNo}-v{N}.stp`}</code></li>
            </ul>
          </div>
          <div style={{ background: '#fafbfc', padding: 12, borderRadius: 4, marginBottom: 12 }}>
            <b>Step 4.</b> 点 OK 等待导出（小件 5s / 大装配 60s+）→ 生成 .stp
          </div>
          <div style={{ background: '#fafbfc', padding: 12, borderRadius: 4, marginBottom: 12 }}>
            <b>Step 5.</b> 同时**保留 .prt 源文件**（不要删！工程师返工需要）
          </div>
          <div style={{ background: '#fafbfc', padding: 12, borderRadius: 4, marginBottom: 12 }}>
            <b>Step 6.</b> 回到 CPQ「+ 上传新模型」→ 选 UG NX 模式 → 同时上传 .prt + .stp
          </div>

          <h3 style={{ marginTop: 16 }}>⚠️ 常见问题</h3>
          <div style={{ background: '#fff7e6', border: '1px solid #ffd591', padding: 12, borderRadius: 4, fontSize: 12, color: '#876800' }}>
            <b>导出 .stp 损坏？</b><br/>① 用 STEP214 替代 STEP242 试试；② 用 UG NX File → Save All 后再导出
            <br/><br/>
            <b>装配体特征丢失？</b><br/>勾选 <b>Save PMI</b>，但仅 AP242 支持
            <br/><br/>
            <b>大装配体导出超时？</b><br/>分子组件分别导出 + 在 CPQ 内组合（后续切片支持装配体）
          </div>

          <h3 style={{ marginTop: 16 }}>📚 其他 CAD 软件</h3>
          <p>SolidWorks: File → Save As → STEP <br/>
             Blender: 不支持原生 STEP，需先导出 OBJ/STL → 用 FreeCAD 转 STEP <br/>
             Inventor: File → Export → CAD Format → STEP</p>
        </div>
      </Drawer>
    </div>
  );
};

export default PartModelList;
