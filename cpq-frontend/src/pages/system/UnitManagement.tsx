import React, { useCallback, useEffect, useState } from 'react';
import { Button, Card, Drawer, Form, Input, InputNumber, message, Popconfirm, Select, Space, Table, Tag } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { EditOutlined, PlusOutlined, ReloadOutlined, SwapOutlined } from '@ant-design/icons';
import { createUnit, disableUnit, enableUnit, getUnitConversions, listUnits, saveUnitConversions, updateUnit } from '../product/productHubApi';
import type { UnitItem, UnitConversionsResult } from '../product/productHubApi';

const UnitManagement: React.FC = () => {
  const [items, setItems] = useState<UnitItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [editing, setEditing] = useState<UnitItem | null>(null);
  const [creating, setCreating] = useState(false);
  const [relation, setRelation] = useState<UnitConversionsResult | null>(null);
  const [form] = Form.useForm();
  const [relationForm] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try { const r = await listUnits({ size: 100 }); setItems(r.items ?? []); }
    catch (e) { message.error((e as Error)?.message ?? '单位加载失败'); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const openCreate = () => { setCreating(true); form.resetFields(); form.setFieldsValue({ category: 'QUANTITY', sortOrder: 0 }); };
  const openEdit = (row: UnitItem) => { setEditing(row); form.setFieldsValue({ code: row.code, category: row.category, sortOrder: row.sortOrder }); };
  const saveDefinition = async () => {
    const v = await form.validateFields();
    try {
      if (creating) await createUnit(v);
      else if (editing) await updateUnit(editing.id, { code: v.code, sortOrder: v.sortOrder ?? 0, version: editing.version });
      message.success('单位已保存'); setCreating(false); setEditing(null); await load();
    } catch (e) { message.error((e as Error)?.message ?? '保存失败'); }
  };
  const openRelations = async (row: UnitItem) => {
    try { const r = await getUnitConversions(row.id); setRelation(r); relationForm.setFieldsValue({ targets: r.targets.map((t) => ({ targetUnitId: t.targetUnitId, factor: t.factor })) }); }
    catch (e) { message.error((e as Error)?.message ?? '关系加载失败'); }
  };
  const saveRelations = async () => {
    if (!relation) return;
    const v = await relationForm.validateFields();
    try { await saveUnitConversions(relation.sourceUnit.id, { sourceVersion: relation.sourceUnit.version, targets: v.targets ?? [] }); message.success('换算关系已保存'); setRelation(null); await load(); }
    catch (e) { message.error((e as Error)?.message ?? '换算关系保存失败'); }
  };
  const columns: ColumnsType<UnitItem> = [
    { title: '单位代码', dataIndex: 'code', key: 'code' },
    { title: '单位类型', dataIndex: 'category', key: 'category', render: (v: string) => v === 'QUANTITY' ? '数量单位' : '重量单位' },
    { title: '换算关系', dataIndex: 'conversionCount', key: 'conversionCount', render: (v: number, row) => `${v}/${row.category === 'QUANTITY' ? 2 : 3}` },
    { title: '状态', dataIndex: 'enabled', key: 'enabled', render: (v: boolean) => <Tag color={v ? 'green' : 'default'}>{v ? '启用' : '停用'}</Tag> },
    { title: '排序', dataIndex: 'sortOrder', key: 'sortOrder' },
    { title: '操作', key: 'actions', render: (_: unknown, row) => <Space>
      <Button type="link" icon={<EditOutlined />} onClick={() => openEdit(row)}>编辑</Button>
      <Button type="link" icon={<SwapOutlined />} onClick={() => void openRelations(row)}>换算关系</Button>
      {row.enabled ? <Popconfirm title="停用后新数据不可选择，历史数据保留。" onConfirm={async () => { try { await disableUnit(row.id, row.version); message.success('单位已停用'); await load(); } catch (e) { message.error((e as Error)?.message ?? '停用失败'); } }}><Button type="link" danger>停用</Button></Popconfirm>
        : <Popconfirm title="启用前必须补齐全部同类型有向关系。" onConfirm={async () => { try { await enableUnit(row.id, row.version); message.success('单位已启用'); await load(); } catch (e) { message.error((e as Error)?.message ?? '启用失败'); } }}><Button type="link">启用</Button></Popconfirm>}
    </Space> },
  ];
  const targets = relation?.targets ?? [];
  const relationIncomplete = targets.some((target) => target.factor == null || Number(target.factor) <= 0);
  return <Card title="单位管理" extra={<Space><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button><Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建单位</Button></Space>}>
    <Table rowKey="id" loading={loading} columns={columns} dataSource={items} locale={{ emptyText: '暂无单位，请先新建或检查权限' }} pagination={false} />
    <Drawer title={creating ? '新建单位' : '编辑单位'} open={creating || !!editing} onClose={() => { setCreating(false); setEditing(null); }} extra={<Button type="primary" onClick={() => void saveDefinition()}>保存</Button>}>
      <Form form={form} layout="vertical"><Form.Item name="code" label="单位代码" rules={[{ required: true, message: '请输入单位代码' }]}><Input placeholder="如 EA、kg、盎司" /></Form.Item><Form.Item name="category" label="单位类型" rules={[{ required: true }]}><Select disabled={!!editing} options={[{ value: 'QUANTITY', label: '数量单位' }, { value: 'WEIGHT', label: '重量单位' }]} /></Form.Item><Form.Item name="sortOrder" label="排序"><InputNumber min={0} /></Form.Item></Form>
    </Drawer>
    <Drawer title={relation ? `维护换算关系：${relation.sourceUnit.code}` : '维护换算关系'} open={!!relation} onClose={() => setRelation(null)} width={520} extra={<Button type="primary" disabled={relationIncomplete} onClick={() => void saveRelations()}>保存</Button>}>
      <p>每行表示：1 {relation?.sourceUnit.code} = 系数 × 目标单位。正向与反向关系独立维护。</p>
      {relationIncomplete && <p style={{ color: '#d46b08' }}>同类型每个目标单位都必须补齐大于 0 的系数后才能保存或启用。</p>}
      <Form form={relationForm} layout="vertical">
        {targets.map((target, index) => <Space key={target.targetUnitId} align="baseline" style={{ display: 'flex', marginBottom: 12 }}><span style={{ width: 180 }}>1 {relation?.sourceUnit.code} =</span><Form.Item name={['targets', index, 'factor']} rules={[{ required: true, message: '请输入系数' }, { validator: (_, v) => Number(v) > 0 ? Promise.resolve() : Promise.reject(new Error('系数必须大于 0')) }]}><InputNumber stringMode min={0} /></Form.Item><span>{target.targetUnitCode}</span><Form.Item name={['targets', index, 'targetUnitId']} hidden><Input /></Form.Item></Space>)}
      </Form>
    </Drawer>
  </Card>;
};
export default UnitManagement;
