import React, { useEffect, useMemo, useState } from 'react';
import { Card, Button, Table, Form, Input, Select, Space, Tag, Popconfirm, message, Spin, Drawer, Switch } from 'antd';
import { PlusOutlined, ArrowLeftOutlined, SaveOutlined, EditOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { costingTemplateService } from '../../services/costingTemplateService';
import type { CostingTemplateColumn } from '../../services/costingTemplateService';
import { comparisonTagService } from '../../services/comparisonTagService';
import type { ComparisonTag } from '../../services/comparisonTagService';
import { templateService } from '../../services/templateService';
import PathPickerDrawer from '../component/PathPickerDrawer';
import GlobalVariablePickerDrawer from '../../components/GlobalVariablePickerDrawer';

const KIND_LABEL: Record<string, string> = { QUOTATION: '报价模板', COSTING: '核价模板' };
const KIND_COLOR: Record<string, string> = { QUOTATION: 'blue', COSTING: 'purple' };

const CostingTemplateConfig: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [template, setTemplate] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [columns, setColumns] = useState<CostingTemplateColumn[]>([]);
  const [tags, setTags] = useState<ComparisonTag[]>([]);
  const [saving, setSaving] = useState(false);
  // V73：关联「模板配置」中模板 — 抽屉式编辑
  const [linkDrawerOpen, setLinkDrawerOpen] = useState(false);
  const [linkableTemplates, setLinkableTemplates] = useState<any[]>([]);
  const [linkSelected, setLinkSelected] = useState<string | undefined>();
  const [linkSaving, setLinkSaving] = useState(false);
  // 变量路径选择 —— 直接复用「组件管理」里的 PathPickerDrawer，与组件 BASIC_DATA 字段共用同一份 BNF 路径配置体验
  const [pathPickerOpen, setPathPickerOpen] = useState(false);
  const [pathPickerColIdx, setPathPickerColIdx] = useState<number | null>(null);
  // V104: 全局变量选择抽屉 — 用户挑变量+key 后, 自动编译为 BNF 路径填到 variable_path
  const [gvPickerOpen, setGvPickerOpen] = useState(false);
  const [gvPickerColIdx, setGvPickerColIdx] = useState<number | null>(null);
  // 公式编辑抽屉（FORMULA 列单独编辑，与变量分支分开）
  const [formulaDrawerOpen, setFormulaDrawerOpen] = useState(false);
  const [formulaDrawerColIdx, setFormulaDrawerColIdx] = useState<number | null>(null);
  const [formulaDraft, setFormulaDraft] = useState<string>('');

  useEffect(() => {
    Promise.all([
      costingTemplateService.getById(id!),
      comparisonTagService.list('ACTIVE'),
    ])
      .then(([tRes, tagRes]) => {
        setTemplate(tRes.data);
        setLinkSelected(tRes.data.linkedTemplateId || undefined);
        try { setColumns(JSON.parse(tRes.data.columns || '[]')); } catch { setColumns([]); }
        setTags(tagRes.data || []);
      })
      .finally(() => setLoading(false));
  }, [id]);

  // 抽屉打开时拉模板候选（只在第一次打开拉取）
  const openLinkDrawer = async () => {
    setLinkDrawerOpen(true);
    if (linkableTemplates.length === 0) {
      try {
        const r: any = await templateService.list({ size: 500 });
        const list = (r.data || []).filter((t: any) => t.status !== 'ARCHIVED');
        setLinkableTemplates(list);
      } catch {
        setLinkableTemplates([]);
      }
    }
  };

  // 打开变量路径抽屉 —— 复用 PathPickerDrawer（BNF 路径选择器，与组件管理一致）
  const openPathPicker = (idx: number) => {
    setPathPickerColIdx(idx);
    setPathPickerOpen(true);
  };
  const handlePathPickerConfirm = (path: string) => {
    if (pathPickerColIdx != null) {
      // 与组件管理 basic_data_path 同格式：直接存 BNF 路径字符串（不再加 `{}`）
      updateCol(pathPickerColIdx, { variable_path: path });
    }
    setPathPickerOpen(false);
    setPathPickerColIdx(null);
  };

  // V104: 全局变量选择 — 编译为 BNF 路径写入 variable_path
  const openGvPicker = (idx: number) => {
    setGvPickerColIdx(idx);
    setGvPickerOpen(true);
  };
  const handleGvPick = (result: { bnfPath: string; label: string; unit?: string; def: any }) => {
    if (gvPickerColIdx != null) {
      const c = columns[gvPickerColIdx];
      // 同时更新 title (空标题或默认时) 让用户一眼看到这是哪个全局变量
      const newTitle = !c.title || c.title === '新列' ? result.label : c.title;
      updateCol(gvPickerColIdx, {
        variable_path: result.bnfPath,
        source_type: 'VARIABLE',
        title: newTitle,
      });
    }
    setGvPickerOpen(false);
    setGvPickerColIdx(null);
  };

  // 公式抽屉
  const openFormulaDrawer = (idx: number) => {
    const c = columns[idx];
    setFormulaDrawerColIdx(idx);
    setFormulaDraft(c.formula || '');
    setFormulaDrawerOpen(true);
  };

  const saveFormulaDrawer = () => {
    if (formulaDrawerColIdx == null) return;
    updateCol(formulaDrawerColIdx, { formula: formulaDraft });
    setFormulaDrawerOpen(false);
    setFormulaDrawerColIdx(null);
  };

  const insertFormulaToken = (token: string) => {
    setFormulaDraft((prev) => (prev || '') + token);
  };

  const handleSaveLink = async () => {
    setLinkSaving(true);
    try {
      const res: any = await costingTemplateService.setLinkedTemplate(id!, linkSelected || null);
      setTemplate(res.data);
      message.success(linkSelected ? '已更新关联' : '已解除关联');
      setLinkDrawerOpen(false);
    } catch (e: any) {
      message.error(e?.message ?? '更新关联失败');
    } finally {
      setLinkSaving(false);
    }
  };

  const isReadOnly = template && template.status !== 'DRAFT';

  const tagOptions = useMemo(
    () => tags.map((t) => ({ label: `${t.label} (${t.code})`, value: t.code })),
    [tags]
  );

  const updateCol = (idx: number, patch: Partial<CostingTemplateColumn>) => {
    setColumns((prev) => prev.map((c, i) => i === idx ? { ...c, ...patch } : c));
  };

  const addCol = () => {
    const nextLetter = String.fromCharCode('A'.charCodeAt(0) + columns.length);
    setColumns((prev) => [
      ...prev,
      { col_key: nextLetter, title: '新列', source_type: 'VARIABLE', variable_path: '' },
    ]);
  };

  const delCol = (idx: number) => setColumns((prev) => prev.filter((_, i) => i !== idx));

  const handleSave = async () => {
    setSaving(true);
    try {
      // Extract referenced variables from formulas/paths
      const refSet = new Set<string>();
      columns.forEach((c) => {
        if (c.source_type === 'VARIABLE' && c.variable_path) refSet.add(c.variable_path);
        if (c.source_type === 'FORMULA' && c.formula) {
          const matches = c.formula.match(/\{[^}]+\}/g) || [];
          matches.forEach((m) => refSet.add(m));
        }
      });
      await costingTemplateService.update(id!, {
        columns,
        referencedVariables: Array.from(refSet),
      });
      message.success('保存成功');
    } catch (err: any) {
      message.error(err.message);
    } finally {
      setSaving(false);
    }
  };

  if (loading) return <Spin />;
  if (!template) return <div>模板不存在</div>;

  const tableColumns = [
    {
      title: '列Key', dataIndex: 'col_key', width: 80,
      render: (_: any, _r: CostingTemplateColumn, idx: number) =>
        <Input value={columns[idx].col_key} disabled={isReadOnly} onChange={(e) => updateCol(idx, { col_key: e.target.value })} />,
    },
    {
      title: '列标题', dataIndex: 'title', width: 180,
      render: (_: any, _r: CostingTemplateColumn, idx: number) =>
        <Input value={columns[idx].title} disabled={isReadOnly} onChange={(e) => updateCol(idx, { title: e.target.value })} />,
    },
    {
      title: '数据来源', dataIndex: 'source_type', width: 130,
      render: (_: any, _r: CostingTemplateColumn, idx: number) =>
        <Select
          value={columns[idx].source_type}
          disabled={isReadOnly}
          style={{ width: '100%' }}
          options={[
            { label: '变量 VARIABLE', value: 'VARIABLE' },
            { label: '公式 FORMULA', value: 'FORMULA' },
          ]}
          onChange={(v) => updateCol(idx, { source_type: v })}
        />,
    },
    {
      title: '变量路径 / 公式',
      render: (_: any, r: CostingTemplateColumn, idx: number) => {
        const isVar = r.source_type === 'VARIABLE';
        const display = isVar ? (r.variable_path || '') : (r.formula || '');
        const open = () => isVar ? openPathPicker(idx) : openFormulaDrawer(idx);
        return (
          <Space.Compact style={{ width: '100%' }}>
            <Input
              readOnly
              value={display}
              placeholder={isVar ? '点击右侧按钮选择路径 / 全局变量' : '点击右侧按钮编辑公式'}
              style={{ background: display ? '#fff' : '#fafafa', cursor: 'pointer', fontFamily: 'Consolas, Monaco, monospace' }}
              onClick={() => !isReadOnly && open()}
            />
            <Button disabled={isReadOnly} onClick={open} icon={<EditOutlined />}>
              {isVar ? '路径' : '编辑'}
            </Button>
            {isVar && (
              <Button
                disabled={isReadOnly}
                onClick={() => openGvPicker(idx)}
                title="从全局变量配置选择 (元素核价/材料核价/汇率), 自动生成 BNF 路径"
              >
                🌐 全局变量
              </Button>
            )}
          </Space.Compact>
        );
      },
    },
    {
      title: '业务标签', width: 220,
      render: (_: any, r: CostingTemplateColumn, idx: number) =>
        <Select
          allowClear
          showSearch
          value={r.comparison_tag}
          disabled={isReadOnly}
          style={{ width: '100%' }}
          options={tagOptions}
          onChange={(v) => updateCol(idx, { comparison_tag: v })}
          optionFilterProp="label"
        />,
    },
    {
      title: '隐藏',
      width: 70,
      align: 'center' as const,
      render: (_: any, r: CostingTemplateColumn, idx: number) => (
        <Switch
          size="small"
          checked={!!r.hidden}
          disabled={isReadOnly}
          onChange={(v) => updateCol(idx, { hidden: v })}
          title="开启后该列只参与公式计算，不在核价单/报价单 Excel 视图中展示"
        />
      ),
    },
    {
      title: '操作', width: 80,
      render: (_: any, _r: CostingTemplateColumn, idx: number) =>
        !isReadOnly && (
          <Popconfirm title="删除该列？" onConfirm={() => delCol(idx)}>
            <a style={{ color: 'red' }}>删除</a>
          </Popconfirm>
        ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/costing-templates')}>返回</Button>
          <h2 style={{ margin: 0 }}>{template.name}</h2>
          <Tag color={template.status === 'DRAFT' ? 'default' : (template.status === 'PUBLISHED' ? 'green' : 'red')}>
            {template.status === 'DRAFT' ? '草稿' : (template.status === 'PUBLISHED' ? '已发布' : '已归档')}
          </Tag>
          <Tag color="blue">{template.version}</Tag>
          {template.isDefault && <Tag color="gold">默认</Tag>}
        </Space>
        {!isReadOnly && (
          <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={handleSave}>保存</Button>
        )}
      </div>

      <Card title="基本信息" style={{ marginBottom: 16 }}>
        <Form layout="vertical">
          <Form.Item label={
            <Space size={8}>
              <span>关联模板</span>
              {!isReadOnly && (
                <Button size="small" type="link" icon={<EditOutlined />} onClick={openLinkDrawer}>
                  {template.linkedTemplateId ? '更换' : '关联'}
                </Button>
              )}
            </Space>
          }>
            {template.linkedTemplateId ? (
              <Space size={8} wrap>
                {template.linkedTemplateKind && (
                  <Tag color={KIND_COLOR[template.linkedTemplateKind]}>
                    {KIND_LABEL[template.linkedTemplateKind] || template.linkedTemplateKind}
                  </Tag>
                )}
                <span>{template.linkedTemplateName || '—'}</span>
                {template.linkedTemplateVersion && <Tag>{template.linkedTemplateVersion}</Tag>}
              </Space>
            ) : (
              <span style={{ color: '#bbb' }}>未关联（仅按产品分类作为通用 Excel 模板）</span>
            )}
          </Form.Item>
          <Form.Item label="描述">{template.description || '（无）'}</Form.Item>
        </Form>
      </Card>

      {/* V73：关联模板 编辑抽屉 */}
      <Drawer
        title="关联「模板配置」中的模板"
        placement="right"
        width={520}
        open={linkDrawerOpen}
        onClose={() => setLinkDrawerOpen(false)}
        destroyOnClose
        footer={
          <div style={{ textAlign: 'right' }}>
            <Button onClick={() => setLinkDrawerOpen(false)} style={{ marginRight: 8 }} disabled={linkSaving}>
              取消
            </Button>
            <Button type="primary" loading={linkSaving} onClick={handleSaveLink}>
              保存
            </Button>
          </div>
        }
      >
        <p style={{ color: '#666', marginTop: 0 }}>
          关联后，从基础数据导入创建报价单时所选的报价模板/核价模板会反查这里的关联，
          在报价单/核价单的 Excel 视图中渲染本模板。
        </p>
        <Form layout="vertical">
          <Form.Item label="选择模板">
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder="搜索 / 选择 报价模板 或 核价模板（不选 = 解除关联）"
              value={linkSelected}
              onChange={setLinkSelected}
              options={(() => {
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
              })()}
              style={{ width: '100%' }}
            />
          </Form.Item>
        </Form>
      </Drawer>

      <Card
        title="列配置"
        extra={!isReadOnly && <Button icon={<PlusOutlined />} onClick={addCol}>添加列</Button>}
      >
        <Table
          rowKey={(_, i) => String(i)}
          dataSource={columns}
          columns={tableColumns}
          pagination={false}
          size="small"
        />
      </Card>

      {/* 变量路径选择 —— 直接复用「组件管理」的 PathPickerDrawer，与组件 BASIC_DATA 字段共用同一份 BNF 路径配置体验 */}
      <PathPickerDrawer
        open={pathPickerOpen}
        onClose={() => { setPathPickerOpen(false); setPathPickerColIdx(null); }}
        initialPath={pathPickerColIdx != null ? (columns[pathPickerColIdx]?.variable_path || '') : ''}
        onConfirm={handlePathPickerConfirm}
      />

      {/* V104: 全局变量选择 —— 从全局变量配置选 var+key, 自动编译 BNF 路径填到 variable_path
          Excel 模板列每列对应一个"具体值", 没有 driver 行上下文, 强制走静态绑定 (allowDynamic=false). */}
      <GlobalVariablePickerDrawer
        open={gvPickerOpen}
        onClose={() => { setGvPickerOpen(false); setGvPickerColIdx(null); }}
        onPick={handleGvPick}
        title="插入全局变量 — 自动生成 BNF 路径"
        allowDynamic={false}
      />

      {/* 公式编辑抽屉（与变量路径独立，FORMULA 列才会打开） */}
      <Drawer
        title={
          formulaDrawerColIdx != null
            ? `编辑公式 —— 列 ${columns[formulaDrawerColIdx]?.col_key}（${columns[formulaDrawerColIdx]?.title}）`
            : '编辑公式'
        }
        placement="right"
        width={720}
        open={formulaDrawerOpen}
        onClose={() => { setFormulaDrawerOpen(false); setFormulaDrawerColIdx(null); }}
        destroyOnClose
        footer={
          <div style={{ textAlign: 'right' }}>
            <Button onClick={() => setFormulaDrawerOpen(false)} style={{ marginRight: 8 }}>取消</Button>
            <Button type="primary" onClick={saveFormulaDrawer}>保存</Button>
          </div>
        }
      >
        <p style={{ color: '#666', marginTop: 0 }}>
          公式示例：<code>[C]*[D]+10</code>。
          方括号 <code>[X]</code> 引用本模板其他列；BNF 路径直接写不带括号即可被运行时识别（如 <code>mat_part.unit_weight</code>）。
        </p>
        <Form layout="vertical">
          <Form.Item label="公式">
            <Input.TextArea
              rows={4}
              value={formulaDraft}
              onChange={(e) => setFormulaDraft(e.target.value)}
              placeholder="如 [C]*[D]+10"
              style={{ fontFamily: 'Consolas, Monaco, monospace' }}
            />
          </Form.Item>
          <Form.Item label="快速插入 列引用">
            <Space size={[4, 4]} wrap>
              {columns
                .filter((_, i) => i !== formulaDrawerColIdx) // 不能引用自己
                .map((c) => (
                  <Tag
                    key={c.col_key}
                    style={{ cursor: 'pointer', userSelect: 'none' }}
                    onClick={() => insertFormulaToken(`[${c.col_key}]`)}
                  >
                    [{c.col_key}] {c.title}
                  </Tag>
                ))}
            </Space>
          </Form.Item>
        </Form>
      </Drawer>
    </div>
  );
};

export default CostingTemplateConfig;
