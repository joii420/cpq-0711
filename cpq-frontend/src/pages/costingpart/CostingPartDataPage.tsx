/**
 * 料号级核价数据维护页（Phase B）
 *
 * 顶部按料号过滤；主区 8 个 tab：
 *   工序级单价 / 模具工装 / 材料 BOM / 元素 BOM / 质量检验 / 电镀 / 设计成本 / 重量
 *
 * 全部按列表操作规范走 SelectableTable + Drawer 编辑。
 */
import React, { useEffect, useState } from 'react';
import {
  Tabs, Card, Input, Button, Space, Tag, Drawer, Form, InputNumber, Select, Switch, Typography,
  message, Empty, Alert, Table,
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, ImportOutlined } from '@ant-design/icons';
import {
  costingPartDataService,
  type ProcessCost, type ProcessCostType,
  type ToolingCost, type MaterialBom, type ElementBom,
  type QualityCheck, type Plating, type PlatingFee, type DesignCost, type PartWeight,
} from '../../services/costingPartDataService';
import { customerService } from '../../services/customerService';
import SelectableTable, { runBatch, type ToolbarAction } from '../../components/SelectableTable';
import BasicDataImportV5Wizard from '../quotation/BasicDataImportV5Wizard';

const { Title } = Typography;
const { Search } = Input;

// 工序级单价类型映射（与 v4 Excel 模板术语对齐：v4 用「生产/辅助」, DB 枚举名 ENERGY_DEDICATED/SHARED 不变）
const COST_TYPE_LABEL: Record<ProcessCostType, string> = {
  LABOR: '人工成本',
  DEPRECIATION: '设备折旧',
  ENERGY_DEDICATED: '生产设备能耗',  // v4: 生产设备能耗成本; DB 枚举 ENERGY_DEDICATED
  ENERGY_SHARED: '辅助设备能耗',     // v4: 辅助设备能耗成本; DB 枚举 ENERGY_SHARED
  CONSUMABLE: '耗材包装',
  MATERIAL_PROC: '材料加工费',
  SEMI_FINISHED_PROC: '半品加工/组装费',
  POST_PROC: '后道加工',
};
const COST_TYPE_COLOR: Record<ProcessCostType, string> = {
  LABOR: 'blue',
  DEPRECIATION: 'cyan',
  ENERGY_DEDICATED: 'green',
  ENERGY_SHARED: 'lime',
  CONSUMABLE: 'orange',
  MATERIAL_PROC: 'purple',
  SEMI_FINISHED_PROC: 'magenta',
  POST_PROC: 'volcano',
};

const STAGE_LABEL: Record<string, string> = { INCOMING: '进料检验', SEMI_FINISHED: '半品检验' };

const CostingPartDataPage: React.FC = () => {
  const [hfPartNo, setHfPartNo] = useState('');
  const [activeTab, setActiveTab] = useState('process-cost');
  // V90: Excel 批量导入向导
  const [importOpen, setImportOpen] = useState(false);
  const [customers, setCustomers] = useState<{ id: string; name: string }[]>([]);

  useEffect(() => {
    customerService.list({ page: 0, size: 200 })
      .then((r: any) => {
        const list: any[] = r.data?.content ?? r.data ?? [];
        setCustomers(list.map((c: any) => ({ id: c.id, name: c.name })));
      })
      .catch(() => setCustomers([]));
  }, []);

  return (
    <div>
      <div style={{ marginBottom: 12, display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <div>
          <Title level={4} style={{ margin: 0 }}>料号级核价数据</Title>
          <span style={{ color: '#8c8c8c', fontSize: 12 }}>
            维护按料号组织的核价业务数据：工序成本 / 模具 / BOM / 检验 / 电镀 / 设计 / 重量。修改基础数据不直接影响已发布的核价单（差量在核价单内独立保存）
          </span>
        </div>
        <Button
          type="primary"
          icon={<ImportOutlined />}
          onClick={() => setImportOpen(true)}
          title="按核价基础数据 Excel 模板批量导入；与报价单基础数据导入相互独立"
        >
          📥 Excel 批量导入
        </Button>
      </div>

      <Card size="small" style={{ marginBottom: 12 }}>
        <Space>
          <span>宏丰料号：</span>
          <Search
            placeholder="如 3100080003"
            value={hfPartNo}
            onChange={(e) => setHfPartNo(e.target.value)}
            onSearch={(v) => setHfPartNo(v.trim())}
            style={{ width: 280 }}
            allowClear
          />
          {!hfPartNo && <Tag color="orange">请先输入料号</Tag>}
        </Space>
      </Card>

      {/* V90+V94: 核价基础数据 Excel 批量导入向导 (复用 BasicDataImportV5Wizard, 隐藏客户选择, templateKind=COSTING) */}
      <BasicDataImportV5Wizard
        open={importOpen}
        customers={customers}
        title="核价基础数据 Excel 导入"
        hideCustomer
        templateKind="COSTING"
        onClose={() => setImportOpen(false)}
        onSuccess={(recordId) => {
          message.success(`核价基础数据导入成功，记录 ID：${recordId}`);
          setImportOpen(false);
        }}
      />


      {!hfPartNo && activeTab !== 'plating' ? (
        <Empty description="请先在上方输入料号后查看 / 维护数据" style={{ padding: 48 }} />
      ) : null}

      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          { key: 'process-cost', label: '工序级单价', children: hfPartNo ? <ProcessCostPanel hfPartNo={hfPartNo} /> : null },
          { key: 'tooling', label: '模具工装', children: hfPartNo ? <ToolingPanel hfPartNo={hfPartNo} /> : null },
          { key: 'material-bom', label: '材料 BOM', children: hfPartNo ? <MaterialBomPanel hfPartNo={hfPartNo} /> : null },
          { key: 'element-bom', label: '元素 BOM', children: <ElementBomPanel /> },
          { key: 'quality-check', label: '质量检验', children: hfPartNo ? <QualityCheckPanel hfPartNo={hfPartNo} /> : null },
          { key: 'plating', label: '电镀', children: <PlatingTabPanel hfPartNo={hfPartNo} /> },
          { key: 'design-cost', label: '设计成本', children: hfPartNo ? <DesignCostPanel hfPartNo={hfPartNo} /> : null },
          { key: 'weight', label: '重量', children: hfPartNo ? <WeightPanel hfPartNo={hfPartNo} /> : null },
        ]}
      />
    </div>
  );
};

// ─── 1. 工序级单价 ────────────────────────────────────────────
const ProcessCostPanel: React.FC<{ hfPartNo: string }> = ({ hfPartNo }) => {
  const [data, setData] = useState<ProcessCost[]>([]);
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<ProcessCost | null>(null);
  const [form] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try {
      const res = await costingPartDataService.listProcessCost(hfPartNo);
      setData(res.data);
    } finally { setLoading(false); }
  };
  useEffect(() => { load(); }, [hfPartNo]);

  const openEdit = (r: ProcessCost | null) => {
    setEditing(r);
    form.resetFields();
    if (r) form.setFieldsValue(r);
    else form.setFieldsValue({ hfPartNo, currency: 'CNY', unit: 'KG', costType: 'LABOR', isActive: true });
    setDrawerOpen(true);
  };

  const submit = async () => {
    try {
      const values = await form.validateFields();
      await costingPartDataService.saveProcessCost({ ...editing, ...values });
      message.success('已保存');
      setDrawerOpen(false);
      load();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.message ?? '保存失败');
    }
  };

  const cols = [
    { title: '工序号', dataIndex: 'processNo', key: 'processNo', width: 100,
      render: (v: string, r: ProcessCost) => <a onClick={(e) => { e.stopPropagation(); openEdit(r); }}>{v}</a> },
    { title: '工序名称', dataIndex: 'processName', key: 'processName', width: 140 },
    { title: '成本类型', dataIndex: 'costType', key: 'costType', width: 140,
      render: (v: ProcessCostType) => <Tag color={COST_TYPE_COLOR[v]}>{COST_TYPE_LABEL[v]}</Tag> },
    { title: '单价', dataIndex: 'unitPrice', key: 'unitPrice', width: 130 },
    { title: '货币', dataIndex: 'currency', key: 'currency', width: 70 },
    { title: '单位', dataIndex: 'unit', key: 'unit', width: 70 },
    { title: '计算版本', dataIndex: 'refCalcVersion', key: 'refCalcVersion', width: 110 },
    { title: '生效', dataIndex: 'isActive', key: 'isActive', width: 60,
      render: (b: boolean) => b ? <Tag color="green">是</Tag> : <Tag>否</Tag> },
  ];

  const actions: ToolbarAction<ProcessCost>[] = [
    { key: 'edit', label: '编辑', icon: <EditOutlined />,
      enabledWhen: (rows) => rows.length === 1 ? true : '编辑一次只能选一行',
      onClick: (rows) => openEdit(rows[0]) },
    { key: 'delete', label: '删除', icon: <DeleteOutlined />, danger: true,
      enabledWhen: (rows) => rows.length > 0 ? true : false,
      needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 条工序成本？',
      onClick: async (rows) => {
        await runBatch(rows, (r) => costingPartDataService.deleteProcessCost(r.id!).then(() => undefined),
          { rowLabel: (r) => `${r.processNo} ${COST_TYPE_LABEL[r.costType]}`, successMsg: `已删除 ${rows.length} 项` });
        load();
      } },
  ];

  return (
    <>
      <SelectableTable<ProcessCost>
        rowKey="id" columns={cols as any} dataSource={data} loading={loading}
        pagination={{ pageSize: 50 }} size="small"
        toolbar={<Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => openEdit(null)}>新增</Button>}
        actions={actions}
        rowLabel={(r) => `${r.processNo} - ${COST_TYPE_LABEL[r.costType]} = ${r.unitPrice} ${r.currency}/${r.unit}`}
      />
      <Drawer title={editing ? '编辑工序成本' : '新增工序成本'} open={drawerOpen} onClose={() => setDrawerOpen(false)} width={520} destroyOnClose
        footer={<div style={{ textAlign: 'right' }}>
          <Button onClick={() => setDrawerOpen(false)} style={{ marginRight: 8 }}>取消</Button>
          <Button type="primary" onClick={submit}>保存</Button>
        </div>}>
        <Form form={form} layout="vertical">
          <Form.Item name="hfPartNo" label="宏丰料号" rules={[{ required: true }]}><Input disabled /></Form.Item>
          <Form.Item name="costType" label="成本类型" rules={[{ required: true }]}>
            <Select options={(Object.keys(COST_TYPE_LABEL) as ProcessCostType[]).map(k => ({ value: k, label: `${COST_TYPE_LABEL[k]} (${k})` }))} />
          </Form.Item>
          <Form.Item name="processNo" label="工序号" rules={[{ required: true }]}><Input placeholder="如 Z053" /></Form.Item>
          <Form.Item name="processName" label="工序名称"><Input placeholder="如 铣削" /></Form.Item>
          <Form.Item name="unitPrice" label="单价" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} precision={6} /></Form.Item>
          <Form.Item name="currency" label="货币"><Select options={['CNY','USD','EUR','JPY'].map(v=>({label:v,value:v}))}/></Form.Item>
          <Form.Item name="unit" label="计量单位"><Select options={['KG','G','PCS','M','L'].map(v=>({label:v,value:v}))}/></Form.Item>
          <Form.Item name="refCalcVersion" label="引用计算版本号"><Input placeholder="如 2026060001" /></Form.Item>
          <Form.Item name="isActive" label="是否生效" valuePropName="checked"><Switch /></Form.Item>
          <Form.Item name="notes" label="备注"><Input.TextArea rows={2} /></Form.Item>
        </Form>
      </Drawer>
    </>
  );
};

// ─── 2. 模具工装 ─────────────────────────────────────────────
const ToolingPanel: React.FC<{ hfPartNo: string }> = ({ hfPartNo }) => {
  const [data, setData] = useState<ToolingCost[]>([]);
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<ToolingCost | null>(null);
  const [form] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try { setData((await costingPartDataService.listTooling(hfPartNo)).data); }
    finally { setLoading(false); }
  };
  useEffect(() => { load(); }, [hfPartNo]);

  const openEdit = (r: ToolingCost | null) => {
    setEditing(r);
    form.resetFields();
    if (r) form.setFieldsValue(r);
    else form.setFieldsValue({ hfPartNo, currency: 'CNY', unit: 'PCS', seqNo: 1, isActive: true });
    setDrawerOpen(true);
  };
  const submit = async () => {
    try {
      const values = await form.validateFields();
      await costingPartDataService.saveTooling({ ...editing, ...values });
      message.success('已保存');
      setDrawerOpen(false);
      load();
    } catch (e: any) { if (e?.errorFields) return; message.error(e?.message ?? '保存失败'); }
  };
  const cols = [
    { title: '工序号', dataIndex: 'processNo', width: 100,
      render: (v: string, r: ToolingCost) => <a onClick={(e) => { e.stopPropagation(); openEdit(r); }}>{v}</a> },
    { title: '工序名称', dataIndex: 'processName', width: 130 },
    { title: '序号', dataIndex: 'seqNo', width: 70 },
    { title: '模具台号', dataIndex: 'toolingNo', width: 130 },
    { title: '单套成本', dataIndex: 'toolingUnitCost', width: 110 },
    { title: '工艺次数', dataIndex: 'processCount', width: 90 },
    { title: '可循环次数', dataIndex: 'cycleCount', width: 100 },
    { title: '单价 = I/J/K', dataIndex: 'unitPrice', width: 130, render: (v: number) => v != null ? Number(v).toFixed(6) : '-' },
    { title: '货币', dataIndex: 'currency', width: 70 },
    { title: '单位', dataIndex: 'unit', width: 70 },
  ];
  const actions: ToolbarAction<ToolingCost>[] = [
    { key: 'edit', label: '编辑', icon: <EditOutlined />, enabledWhen: (rows) => rows.length === 1 ? true : '一次只能选一行',
      onClick: (rows) => openEdit(rows[0]) },
    { key: 'delete', label: '删除', icon: <DeleteOutlined />, danger: true,
      enabledWhen: (rows) => rows.length > 0,
      needsConfirm: true, confirmTitle: '确认删除选中的 {N} 条模具记录？',
      onClick: async (rows) => {
        await runBatch(rows, (r) => costingPartDataService.deleteTooling(r.id!).then(() => undefined),
          { rowLabel: (r) => `${r.processNo}#${r.seqNo} ${r.toolingNo ?? ''}` });
        load();
      } },
  ];
  return <>
    <SelectableTable<ToolingCost> rowKey="id" columns={cols as any} dataSource={data} loading={loading} pagination={false} size="small"
      toolbar={<Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => openEdit(null)}>新增</Button>}
      actions={actions} rowLabel={(r) => `${r.processNo}#${r.seqNo} ${r.toolingNo ?? ''}`}
    />
    <Drawer title={editing ? '编辑模具工装' : '新增模具工装'} open={drawerOpen} onClose={() => setDrawerOpen(false)} width={520} destroyOnClose
      footer={<div style={{ textAlign: 'right' }}>
        <Button onClick={() => setDrawerOpen(false)} style={{ marginRight: 8 }}>取消</Button>
        <Button type="primary" onClick={submit}>保存</Button>
      </div>}>
      <Alert type="info" showIcon style={{ marginBottom: 12 }}
        message="保存时自动计算单价 = 单套成本 / 工艺次数 / 可循环次数（如三者均填）" />
      <Form form={form} layout="vertical">
        <Form.Item name="hfPartNo" label="宏丰料号" rules={[{ required: true }]}><Input disabled /></Form.Item>
        <Form.Item name="processNo" label="工序号" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="processName" label="工序名称"><Input /></Form.Item>
        <Form.Item name="seqNo" label="序号" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} min={1} /></Form.Item>
        <Form.Item name="toolingNo" label="模具台号 / 工装编号"><Input /></Form.Item>
        <Form.Item name="toolingUnitCost" label="单套模具/工装成本 (I)" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} precision={4} /></Form.Item>
        <Form.Item name="processCount" label="工艺次数 (J)"><InputNumber style={{ width: '100%' }} min={1} /></Form.Item>
        <Form.Item name="cycleCount" label="可循环次数 (K)"><InputNumber style={{ width: '100%' }} min={1} /></Form.Item>
        <Form.Item name="currency" label="货币"><Select options={['CNY','USD','EUR','JPY'].map(v=>({label:v,value:v}))}/></Form.Item>
        <Form.Item name="unit" label="计量单位"><Select options={['PCS','KG','M'].map(v=>({label:v,value:v}))}/></Form.Item>
        <Form.Item name="isActive" label="是否生效" valuePropName="checked"><Switch /></Form.Item>
        <Form.Item name="notes" label="备注"><Input.TextArea rows={2} /></Form.Item>
      </Form>
    </Drawer>
  </>;
};

// ─── 3. 材料 BOM ──────────────────────────────────────────────
const MaterialBomPanel: React.FC<{ hfPartNo: string }> = ({ hfPartNo }) => {
  const [data, setData] = useState<MaterialBom[]>([]);
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<MaterialBom | null>(null);
  const [form] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try { setData((await costingPartDataService.listMaterialBom(hfPartNo)).data); }
    finally { setLoading(false); }
  };
  useEffect(() => { load(); }, [hfPartNo]);

  const openEdit = (r: MaterialBom | null) => {
    setEditing(r);
    form.resetFields();
    if (r) form.setFieldsValue(r);
    else form.setFieldsValue({ hfPartNo, seqNo: 10, isActive: true });
    setDrawerOpen(true);
  };
  const submit = async () => {
    try {
      const values = await form.validateFields();
      await costingPartDataService.saveMaterialBom({ ...editing, ...values });
      message.success('已保存'); setDrawerOpen(false); load();
    } catch (e: any) { if (e?.errorFields) return; message.error(e?.message ?? '保存失败'); }
  };
  const cols = [
    { title: '序号', dataIndex: 'seqNo', width: 70,
      render: (v: number, r: MaterialBom) => <a onClick={(e) => { e.stopPropagation(); openEdit(r); }}>{v}</a> },
    { title: '输入料号', dataIndex: 'inputMaterialNo', width: 130 },
    { title: '工序号', dataIndex: 'processNo', width: 90 },
    { title: '工序名称', dataIndex: 'processName', width: 110 },
    { title: '输入数量', dataIndex: 'inputQty', width: 100 },
    { title: '输入单位', dataIndex: 'inputUnit', width: 80 },
    { title: '产出', dataIndex: 'outputQty', width: 90 },
    { title: '产出单位', dataIndex: 'outputUnit', width: 80 },
    { title: '产出损耗率%', dataIndex: 'outputLossRate', width: 110 },
    { title: '固定损耗', dataIndex: 'fixedLossQty', width: 100 },
    { title: '损耗率%', dataIndex: 'lossRate', width: 90 },
  ];
  const actions: ToolbarAction<MaterialBom>[] = [
    { key: 'edit', label: '编辑', icon: <EditOutlined />, enabledWhen: (r) => r.length === 1 ? true : '一次只能选一行', onClick: (r) => openEdit(r[0]) },
    { key: 'delete', label: '删除', icon: <DeleteOutlined />, danger: true, enabledWhen: (r) => r.length > 0, needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 条 BOM？',
      onClick: async (rows) => {
        await runBatch(rows, (r) => costingPartDataService.deleteMaterialBom(r.id!).then(() => undefined),
          { rowLabel: (r) => `序号 ${r.seqNo} ${r.inputMaterialNo ?? ''}` });
        load();
      } },
  ];
  return <>
    <SelectableTable<MaterialBom> rowKey="id" columns={cols as any} dataSource={data} loading={loading} pagination={false} size="small"
      toolbar={<Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => openEdit(null)}>新增</Button>}
      actions={actions} rowLabel={(r) => `序号 ${r.seqNo} ${r.inputMaterialNo ?? ''}`} />
    <Drawer title={editing ? '编辑材料 BOM' : '新增材料 BOM'} open={drawerOpen} onClose={() => setDrawerOpen(false)} width={560} destroyOnClose
      footer={<div style={{ textAlign: 'right' }}>
        <Button onClick={() => setDrawerOpen(false)} style={{ marginRight: 8 }}>取消</Button>
        <Button type="primary" onClick={submit}>保存</Button>
      </div>}>
      <Form form={form} layout="vertical">
        <Form.Item name="hfPartNo" label="宏丰料号" rules={[{ required: true }]}><Input disabled /></Form.Item>
        <Form.Item name="seqNo" label="序号" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} min={1} /></Form.Item>
        <Form.Item name="inputMaterialNo" label="输入料号"><Input /></Form.Item>
        <Form.Item name="processNo" label="工序号"><Input /></Form.Item>
        <Form.Item name="processName" label="工序名称"><Input /></Form.Item>
        <Form.Item name="inputQty" label="输入数量"><InputNumber style={{ width: '100%' }} precision={6} /></Form.Item>
        <Form.Item name="inputUnit" label="输入单位"><Input /></Form.Item>
        <Form.Item name="outputQty" label="产出"><InputNumber style={{ width: '100%' }} precision={6} /></Form.Item>
        <Form.Item name="outputUnit" label="产出单位"><Input /></Form.Item>
        <Form.Item name="outputLossRate" label="产出损耗率%"><InputNumber style={{ width: '100%' }} precision={4} /></Form.Item>
        <Form.Item name="fixedLossQty" label="材料固定损耗量"><InputNumber style={{ width: '100%' }} precision={6} /></Form.Item>
        <Form.Item name="lossRate" label="损耗率%"><InputNumber style={{ width: '100%' }} precision={4} /></Form.Item>
        <Form.Item name="isActive" label="是否生效" valuePropName="checked"><Switch /></Form.Item>
        <Form.Item name="notes" label="备注"><Input.TextArea rows={2} /></Form.Item>
      </Form>
    </Drawer>
  </>;
};

// ─── 4. 元素 BOM（按 input_material_no 维度） ─────────────────
const ElementBomPanel: React.FC = () => {
  const [inputMaterialNo, setInputMaterialNo] = useState('');
  const [data, setData] = useState<ElementBom[]>([]);
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<ElementBom | null>(null);
  const [form] = Form.useForm();

  const load = async () => {
    if (!inputMaterialNo) { setData([]); return; }
    setLoading(true);
    try { setData((await costingPartDataService.listElementBom(inputMaterialNo)).data); }
    finally { setLoading(false); }
  };
  useEffect(() => { load(); }, [inputMaterialNo]);

  const openEdit = (r: ElementBom | null) => {
    setEditing(r);
    form.resetFields();
    if (r) form.setFieldsValue(r);
    else form.setFieldsValue({ inputMaterialNo, seqNo: 1, isActive: true });
    setDrawerOpen(true);
  };
  const submit = async () => {
    try {
      const values = await form.validateFields();
      await costingPartDataService.saveElementBom({ ...editing, ...values });
      message.success('已保存'); setDrawerOpen(false); load();
    } catch (e: any) { if (e?.errorFields) return; message.error(e?.message ?? '保存失败'); }
  };
  const cols = [
    { title: '序号', dataIndex: 'seqNo', width: 70,
      render: (v: number, r: ElementBom) => <a onClick={(e) => { e.stopPropagation(); openEdit(r); }}>{v}</a> },
    { title: '元素代码', dataIndex: 'elementCode', width: 100 },
    { title: '组成含量%', dataIndex: 'compositionPct', width: 110 },
    { title: '损耗率%', dataIndex: 'lossRate', width: 100 },
    { title: '备注', dataIndex: 'notes', ellipsis: true },
  ];
  const actions: ToolbarAction<ElementBom>[] = [
    { key: 'edit', label: '编辑', icon: <EditOutlined />, enabledWhen: (r) => r.length === 1 ? true : '一次只能选一行', onClick: (r) => openEdit(r[0]) },
    { key: 'delete', label: '删除', icon: <DeleteOutlined />, danger: true, enabledWhen: (r) => r.length > 0, needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 条元素 BOM？',
      onClick: async (rows) => {
        await runBatch(rows, (r) => costingPartDataService.deleteElementBom(r.id!).then(() => undefined),
          { rowLabel: (r) => `${r.elementCode} ${r.compositionPct}%` });
        load();
      } },
  ];
  return <>
    <Card size="small" style={{ marginBottom: 8 }}>
      <Space>
        <span>输入料号：</span>
        <Search placeholder="如 2101620029" value={inputMaterialNo}
          onChange={(e) => setInputMaterialNo(e.target.value)}
          onSearch={(v) => setInputMaterialNo(v.trim())}
          style={{ width: 280 }} allowClear />
      </Space>
    </Card>
    {!inputMaterialNo ? <Empty description='请先输入"输入料号"' style={{ padding: 32 }} /> : (
      <SelectableTable<ElementBom> rowKey="id" columns={cols as any} dataSource={data} loading={loading} pagination={false} size="small"
        toolbar={<Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => openEdit(null)}>新增</Button>}
        actions={actions} rowLabel={(r) => `${r.elementCode} ${r.compositionPct}%`} />
    )}
    <Drawer title={editing ? '编辑元素 BOM' : '新增元素 BOM'} open={drawerOpen} onClose={() => setDrawerOpen(false)} width={480} destroyOnClose
      footer={<div style={{ textAlign: 'right' }}>
        <Button onClick={() => setDrawerOpen(false)} style={{ marginRight: 8 }}>取消</Button>
        <Button type="primary" onClick={submit}>保存</Button>
      </div>}>
      <Form form={form} layout="vertical">
        <Form.Item name="inputMaterialNo" label="输入料号" rules={[{ required: true }]}><Input disabled /></Form.Item>
        <Form.Item name="seqNo" label="序号" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} min={1} /></Form.Item>
        <Form.Item name="elementCode" label="元素代码" rules={[{ required: true }]}><Input placeholder="Ag / Cu / Ni / C ..." /></Form.Item>
        <Form.Item name="compositionPct" label="组成含量%" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} min={0} max={100} precision={4} /></Form.Item>
        <Form.Item name="lossRate" label="损耗率%"><InputNumber style={{ width: '100%' }} min={0} precision={4} /></Form.Item>
        <Form.Item name="isActive" label="是否生效" valuePropName="checked"><Switch /></Form.Item>
        <Form.Item name="notes" label="备注"><Input.TextArea rows={2} /></Form.Item>
      </Form>
    </Drawer>
  </>;
};

// ─── 5. 质量检验 ──────────────────────────────────────────────
const QualityCheckPanel: React.FC<{ hfPartNo: string }> = ({ hfPartNo }) => {
  const [data, setData] = useState<QualityCheck[]>([]);
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<QualityCheck | null>(null);
  const [form] = Form.useForm();
  const [stageFilter, setStageFilter] = useState<QualityCheck['stage'] | undefined>();

  const load = async () => {
    setLoading(true);
    try { setData((await costingPartDataService.listQualityCheck(hfPartNo, stageFilter)).data); }
    finally { setLoading(false); }
  };
  useEffect(() => { load(); }, [hfPartNo, stageFilter]);

  const openEdit = (r: QualityCheck | null) => {
    setEditing(r);
    form.resetFields();
    if (r) form.setFieldsValue(r);
    else form.setFieldsValue({ hfPartNo, stage: 'INCOMING', primarySeqNo: 1, seqNo: 1, isActive: true });
    setDrawerOpen(true);
  };
  const submit = async () => {
    try {
      const values = await form.validateFields();
      await costingPartDataService.saveQualityCheck({ ...editing, ...values });
      message.success('已保存'); setDrawerOpen(false); load();
    } catch (e: any) { if (e?.errorFields) return; message.error(e?.message ?? '保存失败'); }
  };
  const cols = [
    { title: '阶段', dataIndex: 'stage', width: 100,
      render: (v: string, r: QualityCheck) => (
        <a onClick={(e) => { e.stopPropagation(); openEdit(r); }}>
          <Tag color={v === 'INCOMING' ? 'blue' : 'green'}>{STAGE_LABEL[v]}</Tag>
        </a>
      ) },
    { title: '一级序号', dataIndex: 'primarySeqNo', width: 90 },
    { title: '序号', dataIndex: 'seqNo', width: 70 },
    { title: '要件编号', dataIndex: 'requirementCode', width: 130 },
    { title: '要件描述', dataIndex: 'requirementDesc', ellipsis: true },
    { title: '报废率%', dataIndex: 'scrapRate', width: 100 },
  ];
  const actions: ToolbarAction<QualityCheck>[] = [
    { key: 'edit', label: '编辑', icon: <EditOutlined />, enabledWhen: (r) => r.length === 1 ? true : '一次只能选一行', onClick: (r) => openEdit(r[0]) },
    { key: 'delete', label: '删除', icon: <DeleteOutlined />, danger: true, enabledWhen: (r) => r.length > 0, needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 条质量检验？',
      onClick: async (rows) => {
        await runBatch(rows, (r) => costingPartDataService.deleteQualityCheck(r.id!).then(() => undefined),
          { rowLabel: (r) => `${STAGE_LABEL[r.stage]} 序号 ${r.seqNo}` });
        load();
      } },
  ];
  return <>
    <Card size="small" style={{ marginBottom: 8 }}>
      <Space><span>阶段：</span>
        <Select allowClear style={{ width: 180 }} placeholder="全部" value={stageFilter} onChange={setStageFilter}
          options={[{value:'INCOMING',label:'进料检验'},{value:'SEMI_FINISHED',label:'半品检验'}]} />
      </Space>
    </Card>
    <SelectableTable<QualityCheck> rowKey="id" columns={cols as any} dataSource={data} loading={loading} pagination={false} size="small"
      toolbar={<Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => openEdit(null)}>新增</Button>}
      actions={actions} rowLabel={(r) => `${STAGE_LABEL[r.stage]} 序号 ${r.seqNo}`} />
    <Drawer title={editing ? '编辑质量检验' : '新增质量检验'} open={drawerOpen} onClose={() => setDrawerOpen(false)} width={480} destroyOnClose
      footer={<div style={{ textAlign: 'right' }}>
        <Button onClick={() => setDrawerOpen(false)} style={{ marginRight: 8 }}>取消</Button>
        <Button type="primary" onClick={submit}>保存</Button>
      </div>}>
      <Form form={form} layout="vertical">
        <Form.Item name="hfPartNo" label="宏丰料号" rules={[{ required: true }]}><Input disabled /></Form.Item>
        <Form.Item name="stage" label="检验阶段" rules={[{ required: true }]}>
          <Select options={[{value:'INCOMING',label:'进料检验'},{value:'SEMI_FINISHED',label:'半品检验'}]} />
        </Form.Item>
        <Form.Item name="primarySeqNo" label="一级序号"><InputNumber style={{ width: '100%' }} min={1} /></Form.Item>
        <Form.Item name="seqNo" label="序号" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} min={1} /></Form.Item>
        <Form.Item name="requirementCode" label="要件编号"><Input /></Form.Item>
        <Form.Item name="requirementDesc" label="要件描述"><Input.TextArea rows={2} /></Form.Item>
        <Form.Item name="scrapRate" label="报废率%"><InputNumber style={{ width: '100%' }} min={0} precision={4} /></Form.Item>
        <Form.Item name="isActive" label="是否生效" valuePropName="checked"><Switch /></Form.Item>
        <Form.Item name="notes" label="备注"><Input.TextArea rows={2} /></Form.Item>
      </Form>
    </Drawer>
  </>;
};

// ─── 6. 电镀: 拆为两子页 ──────────────────────────────────────
//    - 电镀方案库 (按 plating_no, 全局方案库, 现有 PlatingPanel)
//    - 电镀费用   (按 hfPartNo, 共享物理表 plating_fee, 只读)
const PlatingTabPanel: React.FC<{ hfPartNo: string }> = ({ hfPartNo }) => {
  const [sub, setSub] = useState('plan');
  return (
    <Tabs
      activeKey={sub}
      onChange={setSub}
      items={[
        { key: 'plan', label: '电镀方案库', children: <PlatingPanel /> },
        { key: 'fee',  label: '电镀费用',
          children: hfPartNo ? <PlatingFeePanel hfPartNo={hfPartNo} />
                             : <Empty description="请先在顶部输入宏丰料号查看电镀费用" style={{ padding: 32 }} /> },
      ]}
    />
  );
};

// ─── 6.b 电镀费用 (核价侧 costing_part_plating_fee, 按 partNo, 只读) ───
//    V125: 与报价侧 mat_plating_fee 双侧分流, 核价 V5 import 写本表.
const PlatingFeePanel: React.FC<{ hfPartNo: string }> = ({ hfPartNo }) => {
  const [data, setData] = useState<PlatingFee[]>([]);
  const [loading, setLoading] = useState(false);

  const load = async () => {
    setLoading(true);
    try { setData((await costingPartDataService.listPlatingFee(hfPartNo)).data); }
    finally { setLoading(false); }
  };
  useEffect(() => { load(); }, [hfPartNo]);

  const cols = [
    { title: '电镀方案编号', dataIndex: 'platingPlanCode', width: 130 },
    { title: '版本编号', dataIndex: 'planVersion', width: 100 },
    { title: '电镀加工费', dataIndex: 'platingProcessFee', width: 120,
      render: (v: number | null) => v == null ? '-' : Number(v).toFixed(4) },
    { title: '电镀材料费', dataIndex: 'platingMaterialFee', width: 120,
      render: (v: number | null) => v == null ? '-' : Number(v).toFixed(4) },
    { title: '货币', dataIndex: 'currency', width: 70 },
    { title: '计价单位', dataIndex: 'priceUnit', width: 90 },
    { title: '不良率(%)', dataIndex: 'defectRate', width: 110,
      render: (v: number | null) => v == null ? '-' : Number(v) },
    { title: '生效', dataIndex: 'isActive', width: 70,
      render: (b: boolean) => b ? <Tag color="green">是</Tag> : <Tag>否</Tag> },
  ];

  return <>
    <Alert
      type="info"
      showIcon
      style={{ marginBottom: 8 }}
      message="只读视图 (核价侧)"
      description={<>
        数据源: <code>costing_part_plating_fee</code> — V125 核价侧电镀费用表,与报价侧 <code>mat_plating_fee</code> 独立。
        写入由"核价基础数据 Excel 批量导入"承担。报价侧数据见报价产品卡片「电镀费用」tab。
      </>}
    />
    <Table<PlatingFee>
      rowKey="id"
      columns={cols as any}
      dataSource={data}
      loading={loading}
      pagination={false}
      size="small"
      locale={{ emptyText: <Empty description={`料号 ${hfPartNo} 暂无电镀费用记录`} /> }}
    />
  </>;
};

// ─── 6.a 电镀方案库（按 plating_no 维度, 全局方案表） ─────────
const PlatingPanel: React.FC = () => {
  const [platingNo, setPlatingNo] = useState('');
  const [data, setData] = useState<Plating[]>([]);
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<Plating | null>(null);
  const [form] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try { setData((await costingPartDataService.listPlating(platingNo || undefined)).data); }
    finally { setLoading(false); }
  };
  useEffect(() => { load(); }, [platingNo]);

  const openEdit = (r: Plating | null) => {
    setEditing(r); form.resetFields();
    if (r) form.setFieldsValue(r);
    else form.setFieldsValue({ versionNumber: '2000', seqNo: 1, isActive: true });
    setDrawerOpen(true);
  };
  const submit = async () => {
    try {
      const values = await form.validateFields();
      await costingPartDataService.savePlating({ ...editing, ...values });
      message.success('已保存'); setDrawerOpen(false); load();
    } catch (e: any) { if (e?.errorFields) return; message.error(e?.message ?? '保存失败'); }
  };
  const cols = [
    { title: '电镀编号', dataIndex: 'platingNo', width: 110,
      render: (v: string, r: Plating) => <a onClick={(e) => { e.stopPropagation(); openEdit(r); }}>{v}</a> },
    { title: '版本', dataIndex: 'versionNumber', width: 80 },
    { title: '序号', dataIndex: 'seqNo', width: 70 },
    { title: '镀层元素', dataIndex: 'elementAttr', width: 100 },
    { title: '面积 cm²', dataIndex: 'platingAreaCm2', width: 110 },
    { title: '厚度 μm', dataIndex: 'layerThicknessUm', width: 110 },
    { title: '要求', dataIndex: 'requirement', ellipsis: true },
  ];
  const actions: ToolbarAction<Plating>[] = [
    { key: 'edit', label: '编辑', icon: <EditOutlined />, enabledWhen: (r) => r.length === 1 ? true : '一次只能选一行', onClick: (r) => openEdit(r[0]) },
    { key: 'delete', label: '删除', icon: <DeleteOutlined />, danger: true, enabledWhen: (r) => r.length > 0, needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 条电镀记录？',
      onClick: async (rows) => {
        await runBatch(rows, (r) => costingPartDataService.deletePlating(r.id!).then(() => undefined),
          { rowLabel: (r) => `${r.platingNo} v${r.versionNumber} #${r.seqNo}` });
        load();
      } },
  ];
  return <>
    <Card size="small" style={{ marginBottom: 8 }}>
      <Space><span>电镀编号：</span>
        <Search placeholder="如 A0001（留空查全部）" value={platingNo}
          onChange={(e) => setPlatingNo(e.target.value)}
          onSearch={(v) => setPlatingNo(v.trim())}
          style={{ width: 280 }} allowClear />
      </Space>
    </Card>
    <SelectableTable<Plating> rowKey="id" columns={cols as any} dataSource={data} loading={loading} pagination={{ pageSize: 50 }} size="small"
      toolbar={<Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => openEdit(null)}>新增</Button>}
      actions={actions} rowLabel={(r) => `${r.platingNo} v${r.versionNumber} #${r.seqNo}`} />
    <Drawer title={editing ? '编辑电镀' : '新增电镀'} open={drawerOpen} onClose={() => setDrawerOpen(false)} width={480} destroyOnClose
      footer={<div style={{ textAlign: 'right' }}>
        <Button onClick={() => setDrawerOpen(false)} style={{ marginRight: 8 }}>取消</Button>
        <Button type="primary" onClick={submit}>保存</Button>
      </div>}>
      <Form form={form} layout="vertical">
        <Form.Item name="platingNo" label="电镀编号" rules={[{ required: true }]}><Input placeholder="如 A0001" /></Form.Item>
        <Form.Item name="versionNumber" label="版本" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="seqNo" label="序号" rules={[{ required: true }]}><InputNumber style={{ width: '100%' }} min={1} /></Form.Item>
        <Form.Item name="elementAttr" label="镀层元素属性"><Input placeholder="Ni / Au ..." /></Form.Item>
        <Form.Item name="platingAreaCm2" label="电镀面积 (cm²)"><InputNumber style={{ width: '100%' }} precision={6} /></Form.Item>
        <Form.Item name="layerThicknessUm" label="镀层厚度 (μm)"><InputNumber style={{ width: '100%' }} precision={6} /></Form.Item>
        <Form.Item name="requirement" label="镀层要求"><Input.TextArea rows={2} /></Form.Item>
        <Form.Item name="isActive" label="是否生效" valuePropName="checked"><Switch /></Form.Item>
      </Form>
    </Drawer>
  </>;
};

// ─── 7. 设计成本 ──────────────────────────────────────────────
const DesignCostPanel: React.FC<{ hfPartNo: string }> = ({ hfPartNo }) => {
  const [data, setData] = useState<DesignCost[]>([]);
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<DesignCost | null>(null);
  const [form] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try { setData((await costingPartDataService.listDesignCost(hfPartNo)).data); }
    finally { setLoading(false); }
  };
  useEffect(() => { load(); }, [hfPartNo]);

  const openEdit = (r: DesignCost | null) => {
    setEditing(r); form.resetFields();
    if (r) form.setFieldsValue(r);
    else form.setFieldsValue({ hfPartNo, currency: 'CNY', unit: 'KG', isActive: true });
    setDrawerOpen(true);
  };
  const submit = async () => {
    try {
      const values = await form.validateFields();
      await costingPartDataService.saveDesignCost({ ...editing, ...values });
      message.success('已保存'); setDrawerOpen(false); load();
    } catch (e: any) { if (e?.errorFields) return; message.error(e?.message ?? '保存失败'); }
  };
  const cols = [
    { title: '设计图编号', dataIndex: 'designDrawingNo', width: 130,
      render: (v: string, r: DesignCost) => <a onClick={(e) => { e.stopPropagation(); openEdit(r); }}>{v}</a> },
    { title: '版本', dataIndex: 'versionNumber', width: 80 },
    { title: '设计加工费', dataIndex: 'designProcFee', width: 120 },
    { title: '设计材料费', dataIndex: 'designMaterialFee', width: 120 },
    { title: '货币', dataIndex: 'currency', width: 70 },
    { title: '单位', dataIndex: 'unit', width: 70 },
    { title: '损耗率%', dataIndex: 'lossRate', width: 90 },
  ];
  const actions: ToolbarAction<DesignCost>[] = [
    { key: 'edit', label: '编辑', icon: <EditOutlined />, enabledWhen: (r) => r.length === 1 ? true : '一次只能选一行', onClick: (r) => openEdit(r[0]) },
    { key: 'delete', label: '删除', icon: <DeleteOutlined />, danger: true, enabledWhen: (r) => r.length > 0, needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 条设计成本？',
      onClick: async (rows) => {
        await runBatch(rows, (r) => costingPartDataService.deleteDesignCost(r.id!).then(() => undefined),
          { rowLabel: (r) => `${r.designDrawingNo} v${r.versionNumber}` });
        load();
      } },
  ];
  return <>
    <SelectableTable<DesignCost> rowKey="id" columns={cols as any} dataSource={data} loading={loading} pagination={false} size="small"
      toolbar={<Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => openEdit(null)}>新增</Button>}
      actions={actions} rowLabel={(r) => `${r.designDrawingNo} v${r.versionNumber}`} />
    <Drawer title={editing ? '编辑设计成本' : '新增设计成本'} open={drawerOpen} onClose={() => setDrawerOpen(false)} width={480} destroyOnClose
      footer={<div style={{ textAlign: 'right' }}>
        <Button onClick={() => setDrawerOpen(false)} style={{ marginRight: 8 }}>取消</Button>
        <Button type="primary" onClick={submit}>保存</Button>
      </div>}>
      <Form form={form} layout="vertical">
        <Form.Item name="hfPartNo" label="宏丰料号" rules={[{ required: true }]}><Input disabled /></Form.Item>
        <Form.Item name="designDrawingNo" label="设计图编号"><Input /></Form.Item>
        <Form.Item name="versionNumber" label="版本"><Input /></Form.Item>
        <Form.Item name="designProcFee" label="设计加工费"><InputNumber style={{ width: '100%' }} precision={4} /></Form.Item>
        <Form.Item name="designMaterialFee" label="设计材料费"><InputNumber style={{ width: '100%' }} precision={4} /></Form.Item>
        <Form.Item name="currency" label="货币"><Select options={['CNY','USD','EUR','JPY'].map(v=>({label:v,value:v}))}/></Form.Item>
        <Form.Item name="unit" label="计量单位"><Select options={['KG','PCS','M'].map(v=>({label:v,value:v}))}/></Form.Item>
        <Form.Item name="lossRate" label="损耗率%"><InputNumber style={{ width: '100%' }} min={0} precision={4} /></Form.Item>
        <Form.Item name="isActive" label="是否生效" valuePropName="checked"><Switch /></Form.Item>
        <Form.Item name="notes" label="备注"><Input.TextArea rows={2} /></Form.Item>
      </Form>
    </Drawer>
  </>;
};

// ─── 8. 重量（一料号一行 upsert） ──────────────────────────────
const WeightPanel: React.FC<{ hfPartNo: string }> = ({ hfPartNo }) => {
  const [data, setData] = useState<PartWeight | null>(null);
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try {
      const res = await costingPartDataService.getWeight(hfPartNo);
      setData(res.data);
      if (res.data) form.setFieldsValue(res.data);
      else { form.resetFields(); form.setFieldsValue({ hfPartNo, isActive: true }); }
    } finally { setLoading(false); }
  };
  useEffect(() => { load(); }, [hfPartNo]);

  const submit = async () => {
    try {
      const values = await form.validateFields();
      await costingPartDataService.saveWeight({ ...data, ...values });
      message.success('已保存'); load();
    } catch (e: any) { if (e?.errorFields) return; message.error(e?.message ?? '保存失败'); }
  };

  return (
    <Card size="small" loading={loading} title={<Space>料号 <Tag>{hfPartNo}</Tag> 的重量记录</Space>}>
      <Form form={form} layout="vertical" style={{ maxWidth: 480 }}>
        <Form.Item name="hfPartNo" label="宏丰料号" rules={[{ required: true }]}><Input disabled /></Form.Item>
        <Form.Item name="weightGPerPcs" label="重量 (g/pcs)" rules={[{ required: true }]}>
          <InputNumber style={{ width: '100%' }} precision={6} />
        </Form.Item>
        <Form.Item name="isActive" label="是否生效" valuePropName="checked"><Switch /></Form.Item>
        <Form.Item name="notes" label="备注"><Input.TextArea rows={2} /></Form.Item>
        <Button type="primary" onClick={submit}>{data ? '更新' : '新增'}</Button>
      </Form>
    </Card>
  );
};

export default CostingPartDataPage;
