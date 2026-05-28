import React, { useEffect, useState } from 'react';
import {
  Card, Descriptions, Table, Tag, Button, Space, message, Spin, Drawer, Form, Input, Select, InputNumber, Switch, Popconfirm,
  Row, Col,
} from 'antd';
import { useParams, useNavigate } from 'react-router-dom';
import { featureLibraryService } from '../../services/featureLibraryService';
import type { FeatureGroup, FeatureField, FeatureValue, FieldDataType, FieldAssignMode } from '../../types/feature-library';
import StatCard from '../../components/StatCard';

/**
 * v0.4 §18A 特征群组详情 — Master-Detail（字段表 + 取值表）+ Drawer 编辑
 */
const FeatureGroupDetail: React.FC = () => {
  const { groupId } = useParams();
  const navigate = useNavigate();
  const gid = Number(groupId);

  const [group, setGroup] = useState<FeatureGroup | null>(null);
  const [fields, setFields] = useState<FeatureField[]>([]);
  const [selectedFieldId, setSelectedFieldId] = useState<number | null>(null);
  const [values, setValues] = useState<FeatureValue[]>([]);
  const [loading, setLoading] = useState(false);

  // Drawer 状态
  const [drawerMode, setDrawerMode] = useState<'field' | 'value' | null>(null);
  const [drawerEditing, setDrawerEditing] = useState<any>(null);
  const [form] = Form.useForm();

  const loadFields = async () => {
    const res: any = await featureLibraryService.listFields(gid);
    const list: FeatureField[] = res.data || [];
    setFields(list);
    if (list.length > 0 && !selectedFieldId) setSelectedFieldId(list[0].id);
  };

  const loadValues = async (fieldId: number) => {
    const res: any = await featureLibraryService.listValues(fieldId);
    setValues(res.data || []);
  };

  useEffect(() => {
    if (!gid) return;
    setLoading(true);
    featureLibraryService.getGroup(gid)
      .then((res: any) => setGroup(res.data))
      .catch((e: any) => message.error('加载失败：' + (e?.message || '')))
      .finally(() => setLoading(false));
    loadFields();
    // eslint-disable-next-line
  }, [gid]);

  useEffect(() => {
    if (selectedFieldId) loadValues(selectedFieldId);
    else setValues([]);
  }, [selectedFieldId]);

  // ===== Drawer 操作 =====
  const openFieldCreate = () => {
    setDrawerMode('field');
    setDrawerEditing(null);
    form.resetFields();
    form.setFieldsValue({ dataType: 'STRING', assignMode: 'SELECT', isRequired: false, sortOrder: fields.length + 1 });
  };
  const openFieldEdit = (f: FeatureField) => {
    setDrawerMode('field');
    setDrawerEditing(f);
    form.setFieldsValue(f);
  };
  const openValueCreate = () => {
    if (!selectedFieldId) return;
    setDrawerMode('value');
    setDrawerEditing(null);
    form.resetFields();
    form.setFieldsValue({ partnoInclude: true, isActive: true, sortOrder: values.length + 1 });
  };
  const openValueEdit = (v: FeatureValue) => {
    setDrawerMode('value');
    setDrawerEditing(v);
    form.setFieldsValue(v);
  };
  const closeDrawer = () => { setDrawerMode(null); setDrawerEditing(null); };

  const saveDrawer = async () => {
    try {
      const values_ = await form.validateFields();
      if (drawerMode === 'field') {
        if (drawerEditing) {
          await featureLibraryService.updateField(drawerEditing.id, values_);
          message.success('字段已更新');
        } else {
          await featureLibraryService.createField(gid, values_);
          message.success('字段已创建');
        }
        await loadFields();
      } else if (drawerMode === 'value') {
        if (drawerEditing) {
          await featureLibraryService.updateValue(drawerEditing.id, values_);
          message.success('取值已更新');
        } else {
          await featureLibraryService.createValue(selectedFieldId!, values_);
          message.success('取值已创建');
        }
        if (selectedFieldId) await loadValues(selectedFieldId);
      }
      closeDrawer();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error('保存失败：' + (e?.message || ''));
    }
  };

  const deleteField = async (id: number) => {
    try {
      await featureLibraryService.deleteField(id);
      message.success('已删除');
      await loadFields();
      if (selectedFieldId === id) setSelectedFieldId(null);
    } catch (e: any) { message.error('删除失败：' + (e?.message || '')); }
  };
  const deleteValue = async (id: number) => {
    try {
      await featureLibraryService.deleteValue(id);
      message.success('已删除');
      if (selectedFieldId) await loadValues(selectedFieldId);
    } catch (e: any) { message.error('删除失败：' + (e?.message || '')); }
  };

  if (loading) return <Spin />;
  const selectedField = fields.find(f => f.id === selectedFieldId);

  return (
    <div style={{ padding: 16 }}>
      <Card title={`📚 ${group?.name || '特征群组'}${group?.code ? ` (${group.code})` : ''}`}
            extra={<Button onClick={() => navigate('/system/feature-library')}>← 返回列表</Button>}>
        <Row gutter={10} style={{ marginBottom: 14 }}>
          <Col span={6}>
            <StatCard tone="primary" icon="📋" label="字段数" value={fields.length}
              sub={`必填 ${fields.filter(f => f.isRequired).length} · 可选 ${fields.filter(f => !f.isRequired).length}`} />
          </Col>
          <Col span={6}>
            <StatCard tone="purple" icon="📊" label="取值数 (当前字段)" value={values.length}
              sub={`SELECT 字段 ${fields.filter(f => f.assignMode === 'SELECT').length} 个`} />
          </Col>
          <Col span={6}>
            <StatCard tone="orange" icon="🔗" label="引用此群组的模板"
              value={'—'} sub="后续接 product_config_option.source_feature_field_id 反查" />
          </Col>
          <Col span={6}>
            <StatCard tone="success" icon="🕒" label="最后更新"
              value={<span style={{ fontSize: 14 }}>{group?.updatedAt ? new Date(group.updatedAt).toLocaleDateString() : '-'}</span>}
              sub={group?.updatedBy || '-'} />
          </Col>
        </Row>

        <Descriptions column={3} bordered size="small">
          <Descriptions.Item label="群组编号">{group?.code}</Descriptions.Item>
          <Descriptions.Item label="名称">{group?.name}</Descriptions.Item>
          <Descriptions.Item label="状态"><Tag color={group?.status === 'ACTIVE' ? 'green' : group?.status === 'DRAFT' ? 'default' : 'red'}>{group?.status}</Tag></Descriptions.Item>
          <Descriptions.Item label="品类">{group?.category}</Descriptions.Item>
          <Descriptions.Item label="描述" span={3}>{group?.description}</Descriptions.Item>
        </Descriptions>

        {/* Master: 字段列表 */}
        <Card type="inner" style={{ marginTop: 16 }}
              title={<span>📋 字段列表 <span style={{ color: '#999', fontWeight: 'normal' }}>({fields.length})</span></span>}
              extra={
                <Space>
                  <Button onClick={() => message.info('批量导入字段 — 粘贴 CSV：编号,名称,类型,赋值方式,必填\n后续切片实现')}>📥 批量导入</Button>
                  <Button type="primary" onClick={openFieldCreate}>+ 新增字段</Button>
                </Space>
              }>
          <Table<FeatureField>
            rowKey="id"
            size="small"
            dataSource={fields}
            pagination={false}
            rowClassName={r => r.id === selectedFieldId ? 'ant-table-row-selected' : ''}
            onRow={r => ({ onClick: () => setSelectedFieldId(r.id), style: { cursor: 'pointer' } })}
            columns={[
              { title: '项次', dataIndex: 'sortOrder', width: 60 },
              { title: '编号', dataIndex: 'code', width: 140, render: (v) => <code>{v}</code> },
              { title: '名称', dataIndex: 'name' },
              { title: '类型', dataIndex: 'dataType', width: 100, render: (v: FieldDataType) => {
                const colorMap: Record<FieldDataType, string> = { STRING: 'blue', NUMBER: 'purple', DATE: 'orange', BOOLEAN: 'green' };
                return <Tag color={colorMap[v]}>{v}</Tag>;
              }},
              { title: '赋值方式', dataIndex: 'assignMode', width: 110, render: (v: FieldAssignMode) => {
                const colorMap: Record<FieldAssignMode, string> = { MANUAL: 'default', SELECT: 'cyan', COMPUTED: 'magenta' };
                return <Tag color={colorMap[v]}>{v}</Tag>;
              }},
              { title: '必填', dataIndex: 'isRequired', width: 60, align: 'center',
                render: (v: boolean) => v ? <Tag color="red">✓</Tag> : <span style={{ color: '#999' }}>—</span> },
              { title: '默认值', dataIndex: 'defaultValue', width: 100 },
              { title: '子料号拼接', width: 140,
                render: (_, r) => (r.partnoPrefix || r.partnoSuffix)
                  ? <code style={{ fontSize: 10 }}>{r.partnoPrefix || ''}[值]{r.partnoSuffix || ''}</code>
                  : <span style={{ color: '#bfbfbf' }}>不参与</span> },
              { title: '操作', width: 130, render: (_, r) => (
                <Space size={4} onClick={e => e.stopPropagation()}>
                  <a onClick={() => openFieldEdit(r)}>✏️</a>
                  <Popconfirm title="删除字段及其所有取值？" onConfirm={() => deleteField(r.id)}>
                    <a style={{ color: '#f5222d' }}>🗑</a>
                  </Popconfirm>
                </Space>
              )},
            ]}
          />
        </Card>

        {/* Detail: 当前字段的取值列表 */}
        {selectedField && (
          <Card type="inner" style={{ marginTop: 14 }}
                title={<span>🎯 字段 <Tag color="blue">{selectedField.name}</Tag> 的取值列表 <span style={{ color: '#999', fontWeight: 'normal' }}>({values.length})</span></span>}
                extra={selectedField.assignMode === 'SELECT' && <Button type="primary" onClick={openValueCreate}>+ 新增取值</Button>}>
            {selectedField.assignMode !== 'SELECT' ? (
              <div style={{ padding: 20, textAlign: 'center', color: '#999' }}>
                字段赋值方式为 <Tag>{selectedField.assignMode}</Tag>，无需维护枚举取值
              </div>
            ) : (
              <Table<FeatureValue>
                rowKey="id"
                size="small"
                dataSource={values}
                pagination={false}
                columns={[
                  { title: '项序', dataIndex: 'sortOrder', width: 60 },
                  { title: '编号', dataIndex: 'code', width: 120, render: (v) => <code>{v}</code> },
                  { title: '显示名称', dataIndex: 'label' },
                  { title: '描述', dataIndex: 'description', ellipsis: true },
                  { title: '参与拼接', dataIndex: 'partnoInclude', width: 90, align: 'center',
                    render: (v: boolean) => v ? <span style={{ color: '#52c41a' }}>✓</span> : <span style={{ color: '#bfbfbf' }}>—</span> },
                  { title: '激活', dataIndex: 'isActive', width: 80, align: 'center',
                    render: (v: boolean) => <Tag color={v ? 'green' : 'default'}>{v ? '启用' : '停用'}</Tag> },
                  { title: '操作', width: 120, render: (_, r) => (
                    <Space size={4}>
                      <a onClick={() => openValueEdit(r)}>✏️</a>
                      <Popconfirm title="删除此取值？" onConfirm={() => deleteValue(r.id)}>
                        <a style={{ color: '#f5222d' }}>🗑</a>
                      </Popconfirm>
                    </Space>
                  )},
                ]}
              />
            )}
          </Card>
        )}
      </Card>

      {/* Drawer */}
      <Drawer
        title={
          drawerMode === 'field'
            ? (drawerEditing ? `✏️ 编辑字段 · ${drawerEditing.code}` : '+ 新增字段')
            : (drawerEditing ? `✏️ 编辑取值 · ${drawerEditing.code}` : '+ 新增取值')
        }
        width={560}
        open={!!drawerMode}
        onClose={closeDrawer}
        extra={
          <Space>
            <Button onClick={closeDrawer}>取消</Button>
            <Button type="primary" onClick={saveDrawer}>✓ 保存</Button>
          </Space>
        }
      >
        {drawerMode === 'field' && (
          <Form form={form} layout="vertical">
            <Form.Item label="字段编号" name="code" rules={[{ required: true, max: 40 }]}>
              <Input placeholder="如 MODEL" disabled={!!drawerEditing} />
            </Form.Item>
            <Form.Item label="字段名称" name="name" rules={[{ required: true, max: 255 }]}>
              <Input placeholder="如 型号" />
            </Form.Item>
            <Space.Compact style={{ width: '100%', gap: 10 }}>
              <Form.Item label="项次" name="sortOrder" style={{ flex: 1 }}>
                <InputNumber style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item label="数据类型" name="dataType" rules={[{ required: true }]} style={{ flex: 1 }}>
                <Select options={[
                  { value: 'STRING', label: 'STRING 字符' },
                  { value: 'NUMBER', label: 'NUMBER 数值' },
                  { value: 'DATE', label: 'DATE 日期' },
                  { value: 'BOOLEAN', label: 'BOOLEAN 布尔' },
                ]} />
              </Form.Item>
              <Form.Item label="赋值方式" name="assignMode" rules={[{ required: true }]} style={{ flex: 1 }}>
                <Select options={[
                  { value: 'MANUAL', label: 'MANUAL 手工输入' },
                  { value: 'SELECT', label: 'SELECT 枚举选择' },
                  { value: 'COMPUTED', label: 'COMPUTED 公式计算' },
                ]} />
              </Form.Item>
            </Space.Compact>
            <Space.Compact style={{ width: '100%', gap: 10 }}>
              <Form.Item label="必填" name="isRequired" valuePropName="checked" style={{ flex: 1 }}>
                <Switch />
              </Form.Item>
              <Form.Item label="默认值" name="defaultValue" style={{ flex: 1 }}>
                <Input />
              </Form.Item>
            </Space.Compact>
            <Space.Compact style={{ width: '100%', gap: 10 }}>
              <Form.Item label="最小值 (NUMBER/DATE)" name="minValue" style={{ flex: 1 }}>
                <Input />
              </Form.Item>
              <Form.Item label="最大值" name="maxValue" style={{ flex: 1 }}>
                <Input />
              </Form.Item>
              <Form.Item label="小数位 (NUMBER)" name="decimalPlaces" style={{ flex: 1 }}>
                <InputNumber style={{ width: '100%' }} />
              </Form.Item>
            </Space.Compact>
            <h4 style={{ marginTop: 10, color: '#666' }}>🔧 子料号拼接（吸收 ERP imsbb14/15）</h4>
            <Space.Compact style={{ width: '100%', gap: 10 }}>
              <Form.Item label="前缀连接符" name="partnoPrefix" style={{ flex: 1 }}>
                <Input placeholder="如 -T" />
              </Form.Item>
              <Form.Item label="后缀连接符" name="partnoSuffix" style={{ flex: 1 }}>
                <Input placeholder="如 mm" />
              </Form.Item>
            </Space.Compact>
            <Form.Item label="限定数据源 (data_source_ref)" name="dataSourceRef">
              <Input placeholder="留空 = 不限定" />
            </Form.Item>
          </Form>
        )}

        {drawerMode === 'value' && (
          <Form form={form} layout="vertical">
            <Form.Item label="取值编号" name="code" rules={[{ required: true, max: 40 }]}>
              <Input placeholder="如 STD" disabled={!!drawerEditing} />
            </Form.Item>
            <Form.Item label="显示名称" name="label" rules={[{ required: true, max: 255 }]}>
              <Input placeholder="如 标准款 STD" />
            </Form.Item>
            <Form.Item label="描述" name="description">
              <Input.TextArea rows={2} />
            </Form.Item>
            <Space.Compact style={{ width: '100%', gap: 10 }}>
              <Form.Item label="项序" name="sortOrder" style={{ flex: 1 }}>
                <InputNumber style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item label="参与子料号拼接" name="partnoInclude" valuePropName="checked" style={{ flex: 1 }}>
                <Switch />
              </Form.Item>
              <Form.Item label="激活" name="isActive" valuePropName="checked" style={{ flex: 1 }}>
                <Switch />
              </Form.Item>
            </Space.Compact>
          </Form>
        )}
      </Drawer>
    </div>
  );
};

export default FeatureGroupDetail;
