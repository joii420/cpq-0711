import React, { useEffect, useState } from 'react';
import { Button, Modal, Form, Input, Select, Switch, Space, Tag, message } from 'antd';
import {
  PlusOutlined,
  CheckCircleOutlined,
  InboxOutlined,
  CopyOutlined,
  DeleteOutlined,
  EditOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { costingTemplateService } from '../../services/costingTemplateService';
import type { CostingTemplate } from '../../services/costingTemplateService';
import { templateService } from '../../services/templateService';
import SelectableTable, { runBatch, type ToolbarAction } from '../../components/SelectableTable';

const statusColor: Record<string, string> = { DRAFT: 'default', PUBLISHED: 'green', ARCHIVED: 'red' };
const statusLabel: Record<string, string> = { DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '已归档' };
const kindLabel: Record<string, string> = { QUOTATION: '报价模板', COSTING: '核价模板' };
const kindColor: Record<string, string> = { QUOTATION: 'blue', COSTING: 'purple' };

interface LinkableTemplate {
  id: string;
  name: string;
  version?: string;
  status?: string;
  templateKind?: 'QUOTATION' | 'COSTING';
  customerName?: string | null;
}

const CostingTemplateList: React.FC = () => {
  const navigate = useNavigate();
  const [data, setData] = useState<CostingTemplate[]>([]);
  const [loading, setLoading] = useState(false);
  const [filterStatus, setFilterStatus] = useState<string | undefined>(undefined);
  const [filterLinkedTemplateId, setFilterLinkedTemplateId] = useState<string | undefined>(undefined);

  const [createOpen, setCreateOpen] = useState(false);
  const [form] = Form.useForm();
  const [linkableTemplates, setLinkableTemplates] = useState<LinkableTemplate[]>([]);

  const fetchList = async () => {
    setLoading(true);
    try {
      const res = await costingTemplateService.list({
        status: filterStatus,
        linkedTemplateId: filterLinkedTemplateId,
      });
      setData(res.data || []);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    templateService.list({ size: 500 } as any)
      .then((r: any) => {
        const list: LinkableTemplate[] = (r.data || []).filter((t: any) => t.status !== 'ARCHIVED');
        setLinkableTemplates(list);
      })
      .catch(() => setLinkableTemplates([]));
  }, []);

  useEffect(() => {
    fetchList();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterStatus, filterLinkedTemplateId]);

  // 候选关联模板下拉的分组 options（"报价模板" / "核价模板"）
  const linkableOptions = (() => {
    const groups: Record<string, any[]> = { QUOTATION: [], COSTING: [] };
    for (const t of linkableTemplates) {
      const k = t.templateKind || 'QUOTATION';
      if (!groups[k]) groups[k] = [];
      groups[k].push({
        value: t.id,
        label: `${t.name}${t.version ? ' ' + t.version : ''}${t.customerName ? ' [' + t.customerName + ']' : ''}${t.status === 'DRAFT' ? ' (草稿)' : ''}`,
      });
    }
    const out: any[] = [];
    if (groups.QUOTATION.length > 0) out.push({ label: '报价模板', options: groups.QUOTATION });
    if (groups.COSTING.length > 0) out.push({ label: '核价模板', options: groups.COSTING });
    return out;
  })();

  const handleCreate = async (values: any) => {
    try {
      const res = await costingTemplateService.create({ ...values, columns: [], referencedVariables: [] });
      message.success('创建成功');
      setCreateOpen(false);
      form.resetFields();
      navigate(`/costing-templates/${res.data.id}`);
    } catch (err: any) {
      message.error(err.message);
    }
  };

  const renderLinkedTemplate = (r: CostingTemplate) => {
    if (!r.linkedTemplateId) return <span style={{ color: '#bbb' }}>未关联</span>;
    const kind = r.linkedTemplateKind;
    return (
      <Space size={4} wrap>
        {kind && <Tag color={kindColor[kind]}>{kindLabel[kind] || kind}</Tag>}
        <span>{r.linkedTemplateName || '—'}</span>
        {r.linkedTemplateVersion && <Tag>{r.linkedTemplateVersion}</Tag>}
      </Space>
    );
  };

  // 列定义 —— 不再有"操作"列；保留一个"主入口"链接列做高频导航
  const columns = [
    {
      title: '名称',
      dataIndex: 'name',
      key: 'name',
      render: (v: string, r: CostingTemplate) => (
        <a onClick={(e) => { e.stopPropagation(); navigate(`/costing-templates/${r.id}`); }}>{v}</a>
      ),
    },
    {
      title: '关联模板', key: 'linkedTemplate', width: 320,
      render: (_: any, r: CostingTemplate) => renderLinkedTemplate(r),
    },
    {
      title: '默认', dataIndex: 'isDefault', key: 'isDefault', width: 70,
      render: (b: boolean) => (b ? <Tag color="gold">默认</Tag> : '-'),
    },
    { title: '版本', dataIndex: 'version', key: 'version', width: 80 },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 90,
      render: (s: string) => <Tag color={statusColor[s]}>{statusLabel[s] || s}</Tag>,
    },
  ];

  // 工具栏动作 —— 启用/禁用按 selectedRows 谓词决定
  const actions: ToolbarAction<CostingTemplate>[] = [
    {
      key: 'edit',
      label: '配置',
      icon: <EditOutlined />,
      enabledWhen: (rows) => {
        if (rows.length !== 1) return '配置一次只能选一行';
        return true;
      },
      onClick: (rows) => navigate(`/costing-templates/${rows[0].id}`),
    },
    {
      key: 'publish',
      label: '发布',
      icon: <CheckCircleOutlined />,
      enabledWhen: (rows) => {
        if (rows.length === 0) return false;
        if (rows.some((r) => r.status !== 'DRAFT')) return '仅草稿状态可发布';
        return true;
      },
      needsConfirm: true,
      confirmTitle: '确认发布选中的 {N} 个 Excel 模板？',
      confirmDescription: '发布后版本号自动递增，状态变为「已发布」。',
      onClick: async (rows) => {
        await runBatch(rows, (r) => costingTemplateService.publish(r.id).then(() => undefined), {
          rowLabel: (r) => r.name,
          successMsg: `已发布 ${rows.length} 项`,
        });
        fetchList();
      },
    },
    {
      key: 'archive',
      label: '归档',
      icon: <InboxOutlined />,
      enabledWhen: (rows) => {
        if (rows.length === 0) return false;
        if (rows.some((r) => r.status !== 'PUBLISHED')) return '仅已发布的可归档';
        return true;
      },
      needsConfirm: true,
      confirmTitle: '确认归档选中的 {N} 个 Excel 模板？',
      confirmDescription: '归档后该模板不再被新报价单引用，但已绑定的历史报价单仍可读。',
      onClick: async (rows) => {
        await runBatch(rows, (r) => costingTemplateService.archive(r.id).then(() => undefined), {
          rowLabel: (r) => r.name,
          successMsg: `已归档 ${rows.length} 项`,
        });
        fetchList();
      },
    },
    {
      key: 'newDraft',
      label: '创建新草稿',
      icon: <CopyOutlined />,
      enabledWhen: (rows) => {
        if (rows.length !== 1) return '创建新草稿一次只能基于一个版本';
        if (rows[0].status === 'DRAFT') return '草稿无需再派生';
        return true;
      },
      onClick: async (rows) => {
        try {
          const res = await costingTemplateService.createNewDraft(rows[0].id);
          message.success('已派生新草稿');
          navigate(`/costing-templates/${res.data.id}`);
        } catch (e: any) {
          message.error(e?.message ?? '派生失败');
        }
      },
    },
    {
      key: 'delete',
      label: '删除',
      icon: <DeleteOutlined />,
      danger: true,
      enabledWhen: (rows) => {
        if (rows.length === 0) return false;
        if (rows.some((r) => r.status !== 'DRAFT')) return '仅草稿可删除（已发布请先归档）';
        return true;
      },
      needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 项？',
      confirmDescription: '⚠️ 此操作不可撤销。已发布过的版本请改用「归档」。',
      onClick: async (rows) => {
        await runBatch(rows, (r) => costingTemplateService.delete(r.id).then(() => undefined), {
          rowLabel: (r) => r.name,
          successMsg: `已删除 ${rows.length} 项`,
        });
        fetchList();
      },
    },
  ];

  const toolbar = (
    <>
      <div>
        <h2 style={{ margin: 0 }}>Excel 模板配置</h2>
        <span style={{ color: '#8c8c8c', fontSize: 12 }}>
          配置报价模板与核价模板的 Excel 视图列结构（按关联模板组织，关联模板内最多设一个默认）
        </span>
      </div>
      <Space wrap>
        <Select
          allowClear
          showSearch
          optionFilterProp="label"
          placeholder="按关联模板筛选"
          style={{ width: 280 }}
          value={filterLinkedTemplateId}
          onChange={setFilterLinkedTemplateId}
          options={linkableOptions}
        />
        <Select
          allowClear
          placeholder="按状态筛选"
          style={{ width: 140 }}
          value={filterStatus}
          onChange={setFilterStatus}
          options={[
            { label: '草稿', value: 'DRAFT' },
            { label: '已发布', value: 'PUBLISHED' },
            { label: '已归档', value: 'ARCHIVED' },
          ]}
        />
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
          新建模板
        </Button>
      </Space>
    </>
  );

  return (
    <div>
      <SelectableTable<CostingTemplate>
        rowKey="id"
        columns={columns}
        dataSource={data}
        loading={loading}
        pagination={{ pageSize: 20 }}
        toolbar={toolbar}
        actions={actions}
        rowLabel={(r) => `${r.name}${r.version ? ' ' + r.version : ''}${r.linkedTemplateName ? ' → ' + r.linkedTemplateName : ''}`}
      />

      <Modal title="新建 Excel 模板" open={createOpen} onCancel={() => setCreateOpen(false)} onOk={() => form.submit()} destroyOnClose>
        <Form form={form} layout="vertical" onFinish={handleCreate}>
          <Form.Item name="name" label="模板名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item
            name="linkedTemplateId"
            label="关联模板"
            tooltip="关联到「模板配置」中的某个具体模板（报价模板或核价模板）。报价单/核价单的 Excel 视图会按所选模板反查这里的关联，渲染对应 Excel 模板。"
          >
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder="可选 —— 留空则后续在配置页再关联"
              options={linkableOptions}
            />
          </Form.Item>
          <Form.Item
            name="isDefault"
            label="是否为该关联模板的默认 Excel 模板"
            valuePropName="checked"
            tooltip="同一关联模板下最多一个默认 Excel 模板。当报价单/核价单视图反查时优先使用默认。"
          >
            <Switch />
          </Form.Item>
          <Form.Item name="description" label="描述"><Input.TextArea rows={2} /></Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default CostingTemplateList;
