import React, { useEffect, useState } from 'react';
import {
  Button, Modal, Form, Input, Select, Space, message, Card, Tag, Alert, TreeSelect,
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { approvalRuleService } from '../../services/approvalRuleService';
import { userService } from '../../services/userService';
import { regionService } from '../../services/regionService';
import { departmentService } from '../../services/departmentService';
import SelectableTable, { runBatch, type ToolbarAction } from '../../components/SelectableTable';

const ruleTypeOptions = [
  { label: '固定审批人', value: 'FIXED' },
  { label: '动态规则', value: 'DYNAMIC' },
];

const matchFieldOptions = [
  { label: '区域', value: 'REGION' },
  { label: '部门', value: 'DEPARTMENT' },
];

const ApprovalRuleManagement: React.FC = () => {
  const [data, setData] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingRule, setEditingRule] = useState<any>(null);
  const [form] = Form.useForm();
  const [ruleType, setRuleType] = useState<string>('');
  const [matchField, setMatchField] = useState<string>('');

  const [managers, setManagers] = useState<any[]>([]);
  const [regions, setRegions] = useState<any[]>([]);
  const [departments, setDepartments] = useState<any[]>([]);
  const [fallbackAdmin, setFallbackAdmin] = useState<string>('');

  const fetchData = async () => {
    setLoading(true);
    try {
      const res = await approvalRuleService.list();
      setData(res.data || []);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  const fetchLookups = async () => {
    try {
      const [mgrRes, regRes, deptRes, adminRes] = await Promise.all([
        userService.list({ role: 'SALES_MANAGER', status: 'ACTIVE', size: 1000 }),
        regionService.list({ page: 0, size: 1000 }),
        departmentService.list({ page: 0, size: 1000 }),
        userService.list({ role: 'SYSTEM_ADMIN', status: 'ACTIVE', size: 1 }),
      ]);
      setManagers(mgrRes.data?.content || []);
      setRegions((regRes.data?.content || []).filter((r: any) => r.status === 'ACTIVE'));
      setDepartments((deptRes.data?.content || []).filter((d: any) => d.status === 'ACTIVE'));
      const admins = adminRes.data?.content || [];
      setFallbackAdmin(admins.length > 0 ? admins[0].fullName : '无');
    } catch (e: any) {
      message.error('加载配置数据失败: ' + e.message);
    }
  };

  useEffect(() => { fetchData(); fetchLookups(); }, []);

  const handleOpen = (rule?: any) => {
    setEditingRule(rule || null);
    if (rule) {
      form.setFieldsValue(rule);
      setRuleType(rule.ruleType || '');
      setMatchField(rule.matchField || '');
    } else {
      form.resetFields();
      form.setFieldsValue({ priority: 100 });
      setRuleType('');
      setMatchField('');
    }
    setModalOpen(true);
  };

  const handleSave = async (values: any) => {
    const payload = { ...values };
    if (payload.ruleType === 'FIXED') {
      payload.matchField = null;
      payload.matchValueId = null;
    }
    try {
      if (editingRule) {
        await approvalRuleService.update(editingRule.id, payload);
        message.success('更新成功');
      } else {
        await approvalRuleService.create(payload);
        message.success('创建成功');
      }
      setModalOpen(false);
      form.resetFields();
      setEditingRule(null);
      fetchData();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const getManagerName = (id: string) => managers.find(m => m.id === id)?.fullName || (id ? id.substring(0, 8) + '...' : '-');
  const getMatchValueName = (field: string, id: string) => {
    if (field === 'REGION') return regions.find(r => r.id === id)?.name || id;
    if (field === 'DEPARTMENT') return departments.find(d => d.id === id)?.name || id;
    return id || '-';
  };

  const deptTreeData = React.useMemo(() => {
    const buildTree = (list: any[], parentId: string | null = null): any[] => {
      return list
        .filter(d => (d.parentId || null) === parentId)
        .sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
        .map(d => ({
          title: d.name,
          value: d.id,
          key: d.id,
          children: buildTree(list, d.id),
        }));
    };
    return buildTree(departments);
  }, [departments]);

  const matchValueOptions = matchField === 'REGION'
    ? regions.map(r => ({ label: r.name, value: r.id }))
    : matchField === 'DEPARTMENT'
      ? departments.map(d => ({ label: d.name, value: d.id }))
      : [];

  const columns = [
    {
      title: '优先级', dataIndex: 'priority', key: 'priority', width: 80,
      sorter: (a: any, b: any) => a.priority - b.priority,
      defaultSortOrder: 'ascend' as const,
    },
    {
      title: '规则类型', dataIndex: 'ruleType', key: 'ruleType', width: 120,
      render: (v: string) => {
        const opt = ruleTypeOptions.find(o => o.value === v);
        return <Tag color={v === 'FIXED' ? 'blue' : 'green'}>{opt?.label || v}</Tag>;
      },
    },
    {
      title: '审批人', dataIndex: 'approverId', key: 'approver',
      render: (v: string, r: any) => (
        <a onClick={(e) => { e.stopPropagation(); handleOpen(r); }}>{getManagerName(v)}</a>
      ),
    },
    {
      title: '匹配条件', key: 'matchCondition',
      render: (_: any, record: any) => {
        if (record.ruleType === 'FIXED') return <Tag>所有报价单</Tag>;
        const fieldLabel = matchFieldOptions.find(o => o.value === record.matchField)?.label || record.matchField;
        const valueName = getMatchValueName(record.matchField, record.matchValueId);
        return <span>{fieldLabel} = {valueName}</span>;
      },
    },
  ];

  const actions: ToolbarAction<any>[] = [
    {
      key: 'edit', label: '编辑', icon: <EditOutlined />,
      enabledWhen: (rows) => rows.length === 1 ? true : '编辑一次只能选一行',
      onClick: (rows) => handleOpen(rows[0]),
    },
    {
      key: 'delete', label: '删除', icon: <DeleteOutlined />, danger: true,
      enabledWhen: (rows) => rows.length > 0 ? true : false,
      needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 个审批规则？',
      onClick: async (rows) => {
        await runBatch(rows, (r: any) => approvalRuleService.delete(r.id).then(() => undefined), {
          rowLabel: (r: any) => `优先级 ${r.priority} - ${getManagerName(r.approverId)}`,
          successMsg: `已删除 ${rows.length} 项`,
        });
        fetchData();
      },
    },
  ];

  const toolbar = (
    <Space>
      <Button type="primary" icon={<PlusOutlined />} onClick={() => handleOpen()}>新增规则</Button>
    </Space>
  );

  return (
    <Card title="审批规则管理">
      <Alert
        message={`兜底审批人：${fallbackAdmin}（无规则匹配时自动路由给系统管理员）`}
        type="info" showIcon style={{ marginBottom: 16 }}
      />
      <SelectableTable<any>
        rowKey="id"
        columns={columns}
        dataSource={data}
        loading={loading}
        pagination={false}
        toolbar={toolbar}
        actions={actions}
        rowLabel={(r: any) => `优先级 ${r.priority} - ${getManagerName(r.approverId)}`}
      />

      <Modal
        title={editingRule ? '编辑审批规则' : '新增审批规则'}
        open={modalOpen}
        onCancel={() => { setModalOpen(false); form.resetFields(); setEditingRule(null); }}
        onOk={() => form.submit()}
        destroyOnClose
      >
        <Form form={form} layout="vertical" onFinish={handleSave}>
          <Form.Item name="ruleType" label="规则类型" rules={[{ required: true, message: '请选择规则类型' }]}>
            <Select options={ruleTypeOptions} placeholder="请选择规则类型" onChange={(v: string) => { setRuleType(v); setMatchField(''); form.setFieldsValue({ matchField: undefined, matchValueId: undefined }); }} />
          </Form.Item>
          <Form.Item name="approverId" label="审批人" rules={[{ required: true, message: '请选择审批人' }]}>
            <Select placeholder="请选择销售经理" showSearch optionFilterProp="children">
              {managers.map(m => <Select.Option key={m.id} value={m.id}>{m.fullName}（{m.username}）</Select.Option>)}
            </Select>
          </Form.Item>
          {ruleType === 'DYNAMIC' && (
            <>
              <Form.Item name="matchField" label="匹配维度" rules={[{ required: true, message: '请选择匹配维度' }]}>
                <Select options={matchFieldOptions} placeholder="请选择匹配维度" onChange={(v: string) => { setMatchField(v); form.setFieldsValue({ matchValueId: undefined }); }} />
              </Form.Item>
              <Form.Item name="matchValueId" label="匹配值" rules={[{ required: true, message: '请选择匹配值' }]}>
                {matchField === 'DEPARTMENT' ? (
                  <TreeSelect
                    treeData={deptTreeData}
                    placeholder="请选择部门"
                    allowClear
                    treeDefaultExpandAll
                    showSearch
                    treeNodeFilterProp="title"
                  />
                ) : (
                  <Select options={matchValueOptions} placeholder="请选择匹配值" showSearch optionFilterProp="label" />
                )}
              </Form.Item>
            </>
          )}
          <Form.Item name="priority" label="优先级（数值越小越优先）" rules={[{ required: true, message: '请输入优先级' }]}>
            <Input type="number" placeholder="100" />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
};

export default ApprovalRuleManagement;
