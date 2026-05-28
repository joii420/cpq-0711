import React, { useEffect, useState } from 'react';
import {
  Card, Tabs, Descriptions, Tag, Button, Spin, message, Row, Col, Table,
  Drawer, Form, Input, Switch, Select, Space, Popconfirm, Empty, Checkbox, InputNumber, Statistic,
} from 'antd';
import { useParams, useNavigate } from 'react-router-dom';
import { configuratorTemplateService, configuratorInstanceService } from '../../services/configuratorService';
import { featureLibraryService } from '../../services/featureLibraryService';
import { partModelService } from '../../services/partModelService';
import type { ConfiguratorTemplate, ConfiguratorOption, ConfiguratorOptionValue } from '../../types/configurator';
import type { FeatureGroup, FeatureField } from '../../types/feature-library';
import type { PartModel } from '../../types/part-model';
import StatCard from '../../components/StatCard';
import TemplateSidePanel from './TemplateSidePanel';
import ConfiguratorPreview from '../../components/ConfiguratorPreview';
import ValueEditDrawer from './ValueEditDrawer';

type DrawerMode =
  | { type: 'editTemplate' }
  | { type: 'editOption'; optionId: string }
  | { type: 'newOption' }
  | { type: 'editValue'; valueId: string; optionId: string }
  | { type: 'newValue'; optionId: string }
  | { type: 'rules'; valueId: string; valueLabel: string }
  | { type: 'importer' }
  | { type: 'baseModel' }
  | null;

const RULE_ACTIONS = [
  { value: 'SHOW_MESH', label: 'SHOW_MESH 显示 mesh' },
  { value: 'HIDE_MESH', label: 'HIDE_MESH 隐藏 mesh' },
  { value: 'REPLACE_MATERIAL', label: 'REPLACE_MATERIAL 替换材质' },
  { value: 'SWAP_MESH', label: 'SWAP_MESH 替换为另一 mesh' },
  { value: 'TRANSFORM_MESH', label: 'TRANSFORM_MESH 变换' },
];

const ConfiguratorTemplateEditor: React.FC = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const [tpl, setTpl] = useState<ConfiguratorTemplate | null>(null);
  const [options, setOptions] = useState<ConfiguratorOption[]>([]);
  const [valuesByOpt, setValuesByOpt] = useState<Record<string, ConfiguratorOptionValue[]>>({});
  const [expandedOptionIds, setExpandedOptionIds] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [refStats, setRefStats] = useState<{ instances?: number; quotations?: number; relatedParts?: number }>({});

  // §18A.5 重新拉取差异
  const [refreshOpen, setRefreshOpen] = useState(false);
  const [refreshDiffs, setRefreshDiffs] = useState<any[]>([]);
  const [refreshLoading, setRefreshLoading] = useState(false);
  const [diffDecisions, setDiffDecisions] = useState<Record<string, 'KEEP' | 'TAKE'>>({});

  // 版本管理
  const [versions, setVersions] = useState<any[]>([]);
  const loadVersions = async () => {
    if (!id) return;
    try {
      const r: any = await configuratorTemplateService.listVersions(id);
      setVersions(r.data || []);
    } catch { setVersions([]); }
  };
  useEffect(() => { loadVersions(); /* eslint-disable-next-line */ }, [id]);

  const createSnapshotNow = async () => {
    if (!id) return;
    try {
      const v: any = await configuratorTemplateService.createSnapshot(id, undefined, '手工触发快照');
      message.success(`✓ 已创建快照 v${v.data?.version}`);
      loadVersions();
    } catch (e: any) { message.error('创建失败：' + (e?.message || '')); }
  };
  const doRollback = async (versionId: string) => {
    if (!id) return;
    try {
      const r: any = await configuratorTemplateService.rollbackVersion(id, versionId);
      message.success(`✓ 已回滚 · ${r.data?.note || ''}`);
      loadTemplate();
      loadVersions();
    } catch (e: any) { message.error('回滚失败：' + (e?.message || '')); }
  };
  const [diffOpen, setDiffOpen] = useState(false);
  const [diffResult, setDiffResult] = useState<any>(null);
  const compareTwo = async () => {
    if (versions.length < 2) { message.warning('至少需要 2 个版本才能对比'); return; }
    try {
      const r: any = await configuratorTemplateService.diffVersions(versions[1].id, versions[0].id);
      setDiffResult(r.data);
      setDiffOpen(true);
    } catch (e: any) { message.error('对比失败：' + (e?.message || '')); }
  };

  // 取值 4 Tab 编辑抽屉
  const [valueDrawerOpen, setValueDrawerOpen] = useState(false);
  const [valueDrawerOption, setValueDrawerOption] = useState<ConfiguratorOption | null>(null);
  const [valueDrawerValue, setValueDrawerValue] = useState<ConfiguratorOptionValue | null>(null);

  const openValueEditDrawer = (opt: ConfiguratorOption, v: ConfiguratorOptionValue) => {
    setValueDrawerOption(opt);
    setValueDrawerValue(v);
    setValueDrawerOpen(true);
  };

  const triggerRefresh = async () => {
    if (!id) return;
    setRefreshLoading(true);
    setRefreshOpen(true);
    try {
      const res: any = await featureLibraryService.refreshDiff(id);
      const diffs = res.data || [];
      setRefreshDiffs(diffs);
      const init: Record<string, 'KEEP' | 'TAKE'> = {};
      diffs.forEach((d: any) => { init[d.option_id] = 'TAKE'; });
      setDiffDecisions(init);
    } catch (e: any) { message.error('拉取失败：' + (e?.message || '')); }
    finally { setRefreshLoading(false); }
  };
  const applyRefresh = async () => {
    if (!id) return;
    const taken = Object.values(diffDecisions).filter(v => v === 'TAKE').length;
    if (taken === 0) { message.warning('没有勾选「采用源」的项 — 关闭即可'); return; }
    // TODO 后续切片：真实创建草稿版本 + 按决策合并
    message.success(`✓ 已采用 ${taken} 个变化 → 模拟创建新草稿版本 v${(tpl?.version || 1) + 1}-rc1（实际写入后端待集成）`);
    setRefreshOpen(false);
  };

  // Drawer 状态
  const [drawerMode, setDrawerMode] = useState<DrawerMode>(null);
  const [form] = Form.useForm();
  const [rulesList, setRulesList] = useState<any[]>([]);

  // 特征库
  const [groups, setGroups] = useState<FeatureGroup[]>([]);
  const [allFields, setAllFields] = useState<(FeatureField & { groupCode?: string; groupName?: string })[]>([]);
  const [filterGroupId, setFilterGroupId] = useState<number | undefined>();
  const [selectedFieldIds, setSelectedFieldIds] = useState<Set<number>>(new Set());

  // base 模型
  const [models, setModels] = useState<PartModel[]>([]);
  const [selectedModelId, setSelectedModelId] = useState<string | undefined>();

  const loadTemplate = async () => {
    if (!id) return;
    setLoading(true);
    try {
      const res: any = await configuratorTemplateService.getById(id);
      setTpl(res.data);
      const optRes: any = await configuratorTemplateService.listOptions(id);
      const opts: ConfiguratorOption[] = optRes.data || [];
      setOptions(opts);
      const map: Record<string, ConfiguratorOptionValue[]> = {};
      for (const o of opts) {
        const vRes: any = await configuratorTemplateService.listValues(o.id);
        map[o.id] = vRes.data || [];
      }
      setValuesByOpt(map);
      setExpandedOptionIds(opts.slice(0, 2).map(o => o.id));

      // 引用统计（仅 instances，其它 mock 0）
      try {
        const instRes: any = await configuratorInstanceService.list({ page: 0, size: 1, templateId: id });
        setRefStats({
          instances: instRes.data?.totalElements ?? 0,
          quotations: 0,        // TODO 待 quotation 模块集成
          relatedParts: 0,      // TODO 后续切片
        });
      } catch { /* ignore */ }
    } catch (e: any) {
      message.error('加载失败：' + (e?.message || ''));
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { loadTemplate(); /* eslint-disable-next-line */ }, [id]);

  const closeDrawer = () => { setDrawerMode(null); form.resetFields(); setRulesList([]); };

  // ===== 模板顶部操作 =====
  const openEditTemplate = () => {
    if (!tpl) return;
    form.setFieldsValue({
      name: tpl.name, basePartNo: tpl.basePartNo, category: tpl.category,
      description: tpl.description, showPrice: tpl.showPrice,
      basePrice: tpl.metadata?.base_price ?? 0,
    });
    setDrawerMode({ type: 'editTemplate' });
  };
  const saveTemplate = async () => {
    if (!id) return;
    try {
      const v = await form.validateFields();
      const patch: any = {
        name: v.name, basePartNo: v.basePartNo, category: v.category,
        description: v.description, showPrice: v.showPrice,
        metadata: { ...(tpl?.metadata || {}), base_price: v.basePrice ?? 0 },
      };
      await configuratorTemplateService.update(id, patch);
      message.success('已保存');
      closeDrawer();
      loadTemplate();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error('保存失败：' + (e?.message || ''));
    }
  };

  const publish = async () => {
    if (!id) return;
    try {
      await configuratorTemplateService.publish(id);
      message.success('已发布 → PUBLISHED');
      loadTemplate();
    } catch (e: any) { message.error('发布失败：' + (e?.message || '')); }
  };
  const archive = async () => {
    if (!id) return;
    try {
      await configuratorTemplateService.archive(id);
      message.success('已归档 → ARCHIVED');
      loadTemplate();
    } catch (e: any) { message.error('归档失败：' + (e?.message || '')); }
  };

  // ===== 选项 CRUD =====
  const openNewOption = () => {
    form.setFieldsValue({ optionType: 'EXCLUSIVE', dataType: 'STRING', assignMode: 'SELECT',
                          isRequired: true, sortOrder: options.length + 1 });
    setDrawerMode({ type: 'newOption' });
  };
  const openEditOption = (o: ConfiguratorOption) => {
    form.setFieldsValue(o);
    setDrawerMode({ type: 'editOption', optionId: o.id });
  };
  const saveOption = async () => {
    if (!id || !drawerMode || (drawerMode.type !== 'newOption' && drawerMode.type !== 'editOption')) return;
    try {
      const v = await form.validateFields();
      if (drawerMode.type === 'newOption') {
        await configuratorTemplateService.addOption(id, v);
        message.success('选项已新建');
      } else {
        await configuratorTemplateService.updateOption(drawerMode.optionId, v);
        message.success('选项已更新');
      }
      closeDrawer();
      loadTemplate();
    } catch (e: any) { if (!e?.errorFields) message.error('保存失败：' + (e?.message || '')); }
  };
  const deleteOption = async (optionId: string) => {
    try {
      await configuratorTemplateService.deleteOption(optionId);
      message.success('已删除');
      loadTemplate();
    } catch (e: any) { message.error('删除失败：' + (e?.message || '')); }
  };

  // ===== 取值 CRUD =====
  const openNewValue = (optionId: string) => {
    form.setFieldsValue({ priceDelta: 0, partnoInclude: true, isActive: true,
                          sortOrder: (valuesByOpt[optionId]?.length ?? 0) + 1 });
    setDrawerMode({ type: 'newValue', optionId });
  };
  const openEditValue = (v: ConfiguratorOptionValue) => {
    form.setFieldsValue(v);
    setDrawerMode({ type: 'editValue', valueId: v.id, optionId: v.optionId });
  };
  const saveValue = async () => {
    if (!drawerMode || (drawerMode.type !== 'newValue' && drawerMode.type !== 'editValue')) return;
    try {
      const v = await form.validateFields();
      if (drawerMode.type === 'newValue') {
        await configuratorTemplateService.addValue(drawerMode.optionId, v);
        message.success('取值已新建');
      } else {
        await configuratorTemplateService.updateValue(drawerMode.valueId, v);
        message.success('取值已更新');
      }
      closeDrawer();
      loadTemplate();
    } catch (e: any) { if (!e?.errorFields) message.error('保存失败：' + (e?.message || '')); }
  };
  const deleteValue = async (valueId: string) => {
    try {
      await configuratorTemplateService.deleteValue(valueId);
      message.success('已删除');
      loadTemplate();
    } catch (e: any) { message.error('删除失败：' + (e?.message || '')); }
  };

  // ===== 3D 规则 =====
  const openRules = async (v: ConfiguratorOptionValue) => {
    setDrawerMode({ type: 'rules', valueId: v.id, valueLabel: v.label });
    try {
      const res: any = await configuratorTemplateService.list3DRules(v.id);
      setRulesList(res.data || []);
    } catch { setRulesList([]); }
  };
  const addRuleRow = () => {
    setRulesList([...rulesList, { action: 'SHOW_MESH', targetMesh: '', params: {}, sortOrder: rulesList.length + 1, _new: true }]);
  };
  const updateRuleRow = (idx: number, field: string, val: any) => {
    const next = [...rulesList];
    next[idx] = { ...next[idx], [field]: val };
    setRulesList(next);
  };
  const removeRuleRow = async (idx: number) => {
    const r = rulesList[idx];
    if (r.id) {
      try { await configuratorTemplateService.delete3DRule(r.id); }
      catch (e: any) { message.error('删除失败：' + (e?.message || '')); return; }
    }
    const next = [...rulesList]; next.splice(idx, 1); setRulesList(next);
  };
  const saveRules = async () => {
    if (!drawerMode || drawerMode.type !== 'rules') return;
    try {
      for (const r of rulesList) {
        if (r._new) {
          await configuratorTemplateService.add3DRule(drawerMode.valueId, {
            action: r.action, targetMesh: r.targetMesh, params: r.params || {}, sortOrder: r.sortOrder,
          });
        } else if (r.id) {
          await configuratorTemplateService.update3DRule(r.id, {
            action: r.action, targetMesh: r.targetMesh, params: r.params, sortOrder: r.sortOrder,
          });
        }
      }
      message.success(`已保存 ${rulesList.length} 条 3D 规则`);
      closeDrawer();
    } catch (e: any) { message.error('保存规则失败：' + (e?.message || '')); }
  };

  // ===== 从特征库导入 =====
  const openImporter = async () => {
    setSelectedFieldIds(new Set());
    setFilterGroupId(undefined);
    try {
      const gRes: any = await featureLibraryService.listGroups({ page: 0, size: 100, status: 'ACTIVE' });
      const gList: FeatureGroup[] = gRes.data?.content || [];
      setGroups(gList);
      const flat: any[] = [];
      for (const g of gList) {
        const fRes: any = await featureLibraryService.listFields(g.id);
        (fRes.data || []).forEach((f: FeatureField) => flat.push({ ...f, groupCode: g.code, groupName: g.name }));
      }
      setAllFields(flat);
      setDrawerMode({ type: 'importer' });
    } catch (e: any) { message.error('加载特征库失败：' + (e?.message || '')); }
  };
  const doImport = async () => {
    if (!id || selectedFieldIds.size === 0) { message.warning('请至少选择 1 个字段'); return; }
    try {
      const res: any = await configuratorTemplateService.importFeatures(id, Array.from(selectedFieldIds));
      message.success(`已导入 ${res.data?.imported_options ?? 0} 个选项 / ${res.data?.imported_values ?? 0} 个取值（跳过 ${res.data?.skipped ?? 0}）`);
      closeDrawer();
      loadTemplate();
    } catch (e: any) { message.error('导入失败：' + (e?.message || '')); }
  };

  // ===== base 模型 =====
  const openModelPicker = async () => {
    setSelectedModelId(tpl?.baseModelId);
    try {
      const res: any = await partModelService.list({ page: 0, size: 100, isCurrent: true });
      setModels(res.data?.content || []);
      setDrawerMode({ type: 'baseModel' });
    } catch (e: any) { message.error('加载模型失败：' + (e?.message || '')); }
  };
  const confirmModel = async () => {
    if (!id || !selectedModelId) { message.warning('请选择模型'); return; }
    try {
      await configuratorTemplateService.setBaseModel(id, selectedModelId, 1);
      message.success('base 模型已设置');
      closeDrawer();
      loadTemplate();
    } catch (e: any) { message.error('设置失败：' + (e?.message || '')); }
  };

  if (loading) return <Spin />;
  if (!tpl) return <Empty description="模板不存在" />;

  const totalValues = Object.values(valuesByOpt).reduce((s, vs) => s + vs.length, 0);
  const visibleFields = filterGroupId ? allFields.filter(f => f.groupId === filterGroupId) : allFields;
  const existingCodes = new Set(options.map(o => o.code));
  const isPublished = tpl.status === 'PUBLISHED';

  const tabItems = [
    {
      key: 'basic', label: '📋 基本信息',
      children: (
        <>
          <Card type="inner" title="模板基础信息" extra={<Button onClick={openEditTemplate}>✏️ 编辑</Button>}>
            <Descriptions column={3} bordered size="small">
              <Descriptions.Item label="模板代码">{tpl.code}</Descriptions.Item>
              <Descriptions.Item label="名称">{tpl.name}</Descriptions.Item>
              <Descriptions.Item label="状态"><Tag color={isPublished ? 'green' : tpl.status === 'DRAFT' ? 'default' : 'red'}>{tpl.status}</Tag></Descriptions.Item>
              <Descriptions.Item label="品类">{tpl.category || '-'}</Descriptions.Item>
              <Descriptions.Item label="基础料号前缀">{tpl.basePartNo || '-'}</Descriptions.Item>
              <Descriptions.Item label="基础价">¥{tpl.metadata?.base_price ?? 0}</Descriptions.Item>
              <Descriptions.Item label="展示价格">{tpl.showPrice ? '是' : '否'}</Descriptions.Item>
              <Descriptions.Item label="版本">v{tpl.version}</Descriptions.Item>
              <Descriptions.Item label="描述" span={3}>{tpl.description || '-'}</Descriptions.Item>
            </Descriptions>
          </Card>

          <Card type="inner" title="🎯 适用品类与受众" style={{ marginTop: 14 }}>
            <Descriptions column={3} bordered size="small">
              <Descriptions.Item label="产品品类">{tpl.category || '-'}</Descriptions.Item>
              <Descriptions.Item label="应用领域">{tpl.metadata?.industry || '工业 · 通用'}</Descriptions.Item>
              <Descriptions.Item label="销售渠道">{tpl.metadata?.channel || '直销 · 经销'}</Descriptions.Item>
              <Descriptions.Item label="默认审批人">{tpl.metadata?.default_approver || '销售总监'}</Descriptions.Item>
              <Descriptions.Item label="默认折扣权限" span={2}>{tpl.metadata?.discount_policy || '≤ 8 折自动通过 · ＞8 折走审批'}</Descriptions.Item>
            </Descriptions>
            <div style={{ marginTop: 8, fontSize: 11.5, color: '#999' }}>
              ⓘ 这些字段保存在 template.metadata 内，编辑时在「✏️ 编辑」抽屉调整
            </div>
          </Card>

          <Card type="inner" title="🎬 base 模型" style={{ marginTop: 14 }}
                extra={<Button onClick={openModelPicker}>切换模型 →</Button>}>
            {tpl.baseModelId ? (
              <Descriptions column={3} size="small">
                <Descriptions.Item label="model_id"><code style={{ fontSize: 11 }}>{tpl.baseModelId.substring(0, 8)}...</code></Descriptions.Item>
                <Descriptions.Item label="version">v{tpl.baseModelVersion}</Descriptions.Item>
                <Descriptions.Item label="snapshot 时间">{tpl.baseModelSnapshotAt ? new Date(tpl.baseModelSnapshotAt).toLocaleString() : '-'}</Descriptions.Item>
              </Descriptions>
            ) : <Empty description="未选定 base 模型（选配页将无 3D 渲染）" />}
          </Card>
        </>
      )
    },
    {
      key: 'options', label: `⚙️ 选项配置 (${options.length})`,
      children: (
        <>
          <Space style={{ marginBottom: 12 }}>
            <Button type="primary" style={{ background: '#52c41a', borderColor: '#52c41a' }} onClick={openImporter}>📥 从特征库选择</Button>
            <Button onClick={openNewOption}>+ 新建手工选项</Button>
            <span style={{ color: '#999', fontSize: 12 }}>
              来自特征库 <b style={{ color: '#1890ff' }}>{options.filter(o => o.sourceFeatureFieldId).length}</b> · 手工 <b>{options.filter(o => !o.sourceFeatureFieldId).length}</b>
            </span>
          </Space>

          <Table<ConfiguratorOption>
            rowKey="id" dataSource={options} pagination={false}
            expandable={{
              expandedRowKeys: expandedOptionIds,
              onExpandedRowsChange: keys => setExpandedOptionIds(keys as string[]),
              expandedRowRender: (record) => {
                const vals = valuesByOpt[record.id] || [];
                return (
                  <>
                    <Space style={{ marginBottom: 8 }}>
                      <Button size="small" type="primary" onClick={() => openNewValue(record.id)}>+ 新增取值</Button>
                      {record.sourceFeatureFieldId && (
                        <span style={{ fontSize: 11, color: '#0050b3' }}>📚 此选项来自特征库 · 本地新增取值会标记为 local-only</span>
                      )}
                    </Space>
                    <Table<ConfiguratorOptionValue> rowKey="id" size="small" pagination={false} dataSource={vals}
                      locale={{ emptyText: '暂无取值' }}
                      columns={[
                        { title: '项序', dataIndex: 'sortOrder', width: 60 },
                        { title: '编号', dataIndex: 'code', width: 130, render: v => <code>{v}</code> },
                        { title: '显示名称', dataIndex: 'label' },
                        { title: '差价', dataIndex: 'priceDelta', width: 100,
                          render: (v: number) => Number(v) > 0 ? <Tag color="orange">+¥{v}</Tag>
                            : Number(v) < 0 ? <Tag color="green">¥{v}</Tag>
                            : <span style={{ color: '#999' }}>¥0</span> },
                        { title: '标签', width: 200, render: (_, v) => (
                          <Space size={[2, 2]} wrap>
                            {v.featureType && <Tag color="purple">📝 {v.featureType}</Tag>}
                            {v.subModelPartNo && <Tag color="blue">📦 子模型</Tag>}
                          </Space>
                        )},
                        { title: '参与拼接', dataIndex: 'partnoInclude', width: 100, align: 'center',
                          render: (v: boolean) => v ? '✓' : <span style={{ color: '#bfbfbf' }}>—</span> },
                        { title: '激活', dataIndex: 'isActive', width: 70, align: 'center',
                          render: (v: boolean) => <Tag color={v ? 'green' : 'default'}>{v ? '启用' : '停用'}</Tag> },
                        { title: '来源', width: 110, render: (_, v) => v.sourceFeatureValueId
                          ? <Tag color="blue">📚 特征库</Tag>
                          : v.localOnly ? <Tag>✋ 本地</Tag> : <Tag>✋ 手工</Tag> },
                        { title: '操作', width: 160, render: (_, v) => (
                          <Space size={4}>
                            <a onClick={() => openValueEditDrawer(record, v)}>✏️ 编辑</a>
                            <Popconfirm title="删除此取值？" onConfirm={() => deleteValue(v.id)}>
                              <a style={{ color: '#f5222d' }}>🗑</a>
                            </Popconfirm>
                          </Space>
                        )},
                      ]}
                    />
                  </>
                );
              },
            }}
            columns={[
              { title: '序', dataIndex: 'sortOrder', width: 50 },
              { title: '编号', dataIndex: 'code', width: 160, render: (v) => <code>{v}</code> },
              { title: '名称', dataIndex: 'label' },
              { title: '必选', dataIndex: 'isRequired', width: 70, align: 'center',
                render: (v: boolean) => v ? <Tag color="red">必选</Tag> : <Tag>可选</Tag> },
              { title: '类型', dataIndex: 'optionType', width: 110 },
              { title: '取值数', width: 70, align: 'center', render: (_, r) => valuesByOpt[r.id]?.length ?? 0 },
              { title: '来源', width: 150, render: (_, r) => r.sourceFeatureFieldId
                ? <Tag color="blue">📚 特征库</Tag>
                : <Tag>✋ 手工创建</Tag> },
              { title: '默认值', dataIndex: 'defaultValue', width: 110 },
              { title: '子料号', width: 130, render: (_, r) => (r.partnoPrefix || r.partnoSuffix)
                ? <code style={{ fontSize: 10.5 }}>{r.partnoPrefix || ''}[值]{r.partnoSuffix || ''}</code>
                : <span style={{ color: '#bfbfbf' }}>—</span> },
              { title: '操作', width: 110, render: (_, r) => (
                <Space size={4}>
                  <a onClick={() => openEditOption(r)}>✏️</a>
                  <Popconfirm title="删除此选项及所有取值？" onConfirm={() => deleteOption(r.id)}>
                    <a style={{ color: '#f5222d' }}>🗑</a>
                  </Popconfirm>
                </Space>
              )},
            ]}
          />
        </>
      )
    },
    { key: 'versions', label: <>📜 版本历史 <Tag>{versions.length}</Tag></>,
      children: (
        <>
          <Space style={{ marginBottom: 14 }}>
            <Button type="primary" onClick={createSnapshotNow}>📸 创建当前快照</Button>
            <Button onClick={triggerRefresh}>📋 重新拉取差异</Button>
            <Button onClick={compareTwo}>🔀 对比最近两个版本</Button>
          </Space>
          <Card>
            {versions.length === 0 ? (
              <Empty description="尚无历史快照。点「📸 创建当前快照」生成第一份。" />
            ) : (
              <div style={{ position: 'relative', paddingLeft: 24 }}>
                <div style={{ position: 'absolute', left: 8, top: 8, bottom: 8, width: 2, background: '#e4e7ed' }} />
                {versions.map((v, i) => (
                  <VersionItem
                    key={v.id}
                    dot={i === 0 ? '#52c41a' : '#909399'}
                    current={i === 0}
                    label={`v${v.version} ${v.label ? `(${v.label})` : ''}`}
                    tag={v.status}
                    meta={`${new Date(v.createdAt).toLocaleString()}${v.changeSummary ? ' · ' + v.changeSummary : ''}`}
                    changes={`选项 ${(v.snapshot?.options || []).length} 个 · 名称 "${v.snapshot?.name || '-'}"`}
                    onRollback={i > 0 ? () => doRollback(v.id) : undefined}
                  />
                ))}
              </div>
            )}
          </Card>
        </>
      ) },
  ];

  return (
    <div style={{ padding: 16 }}>
      <Card
        title={`🛒 ${tpl.name} · ${tpl.code}`}
        extra={
          <Space>
            <Button onClick={() => navigate('/system/configurator-templates')}>← 返回列表</Button>
            <Button onClick={openEditTemplate}>✏️ 编辑</Button>
            {tpl.status === 'DRAFT' && (
              <Popconfirm title="发布此模板？发布后销售可在「开始选配」中使用。" onConfirm={publish}>
                <Button type="primary" style={{ background: '#52c41a', borderColor: '#52c41a' }}>🚀 发布</Button>
              </Popconfirm>
            )}
            {tpl.status === 'PUBLISHED' && (
              <Popconfirm title="归档此模板？销售将无法新建选配，已生成实例不受影响。" onConfirm={archive}>
                <Button danger>🗄 归档</Button>
              </Popconfirm>
            )}
            <Button onClick={triggerRefresh}>📋 重新拉取</Button>
          </Space>
        }
      >
        <Row gutter={10} style={{ marginBottom: 14 }}>
          <Col span={6}>
            <StatCard tone="primary" icon="⚙️" label="配置选项" value={options.length}
              sub={`必选 ${options.filter(o => o.isRequired).length} · 可选 ${options.filter(o => !o.isRequired).length}`} />
          </Col>
          <Col span={6}>
            <StatCard tone="purple" icon="📊" label="取值总数" value={totalValues}
              sub={`${options.filter(o => o.sourceFeatureFieldId).length} 个来自特征库`} />
          </Col>
          <Col span={6}>
            <StatCard tone="orange" icon="🎬" label="3D 规则 / base 价"
              value={<span>¥{(tpl.metadata?.base_price ?? 0).toLocaleString()}</span>}
              sub={`含 ${Object.values(valuesByOpt).flat().filter(v => v.subModelPartNo).length} 个子模型挂载`} />
          </Col>
          <Col span={6}>
            <StatCard tone="success" icon="📋" label="已生成实例"
              value={refStats.instances ?? 0}
              sub={tpl.baseModelId ? `v${tpl.baseModelVersion} base 已绑定` : '⚠ 未绑 base 模型'} />
          </Col>
        </Row>

        {/* 主区 + 右侧浮动面板 */}
        <div style={{ display: 'flex', gap: 14, alignItems: 'flex-start' }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <Tabs items={tabItems} />
          </div>
          <TemplateSidePanel
            tpl={tpl} options={options} valuesByOpt={valuesByOpt} refStats={refStats}
            onOpenBaseModelPicker={openModelPicker}
            onTriggerRefresh={triggerRefresh}
            onExportJson={() => message.info('导出 JSON — 后续切片实现')}
          />
        </div>
      </Card>

      <Drawer
        title={
          drawerMode?.type === 'editTemplate' ? '✏️ 编辑模板属性'
          : drawerMode?.type === 'newOption' ? '+ 新建选项'
          : drawerMode?.type === 'editOption' ? '✏️ 编辑选项'
          : drawerMode?.type === 'newValue' ? '+ 新增取值'
          : drawerMode?.type === 'editValue' ? '✏️ 编辑取值'
          : drawerMode?.type === 'rules' ? `🎬 3D 规则 · ${drawerMode.valueLabel}`
          : drawerMode?.type === 'importer' ? '📥 从特征库选择'
          : drawerMode?.type === 'baseModel' ? '🎬 选择 base 模型'
          : ''
        }
        width={drawerMode?.type === 'importer' ? 960 : drawerMode?.type === 'rules' ? 720 : drawerMode?.type === 'baseModel' ? 680 : 560}
        open={!!drawerMode}
        onClose={closeDrawer}
        extra={
          drawerMode?.type === 'editTemplate' ? <Space><Button onClick={closeDrawer}>取消</Button><Button type="primary" onClick={saveTemplate}>✓ 保存</Button></Space>
          : drawerMode?.type === 'newOption' || drawerMode?.type === 'editOption' ? <Space><Button onClick={closeDrawer}>取消</Button><Button type="primary" onClick={saveOption}>✓ 保存</Button></Space>
          : drawerMode?.type === 'newValue' || drawerMode?.type === 'editValue' ? <Space><Button onClick={closeDrawer}>取消</Button><Button type="primary" onClick={saveValue}>✓ 保存</Button></Space>
          : drawerMode?.type === 'rules' ? <Space><Button onClick={closeDrawer}>取消</Button><Button type="primary" onClick={saveRules}>✓ 保存全部规则</Button></Space>
          : drawerMode?.type === 'importer' ? <Space><span style={{ fontSize: 12 }}>已选 <b style={{ color: '#52c41a' }}>{selectedFieldIds.size}</b></span><Button onClick={closeDrawer}>取消</Button><Button type="primary" style={{ background: '#52c41a', borderColor: '#52c41a' }} onClick={doImport}>✓ 导入</Button></Space>
          : drawerMode?.type === 'baseModel' ? <Space><Button onClick={closeDrawer}>取消</Button><Button type="primary" onClick={confirmModel}>✓ 确认</Button></Space>
          : null
        }
        styles={drawerMode?.type === 'importer' ? { body: { padding: 0, display: 'flex', flexDirection: 'row', overflow: 'hidden' } } : {}}
      >
        {drawerMode?.type === 'editTemplate' && (
          <Form form={form} layout="vertical">
            <Form.Item label="模板名称" name="name" rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item label="基础料号前缀" name="basePartNo"><Input placeholder="如 VBV" /></Form.Item>
            <Form.Item label="品类" name="category"><Input /></Form.Item>
            <Form.Item label="描述" name="description"><Input.TextArea rows={3} /></Form.Item>
            <Form.Item label="基础价 (¥)" name="basePrice" help="evaluate 时作为 base + Σ priceDelta 累加">
              <InputNumber style={{ width: '100%' }} min={0} step={100} />
            </Form.Item>
            <Form.Item label="展示价格" name="showPrice" valuePropName="checked"><Switch /></Form.Item>
          </Form>
        )}

        {(drawerMode?.type === 'newOption' || drawerMode?.type === 'editOption') && (
          <Form form={form} layout="vertical">
            <Form.Item label="选项编号" name="code" rules={[{ required: true, max: 64 }]}>
              <Input placeholder="如 OPT-DN" disabled={drawerMode?.type === 'editOption'} />
            </Form.Item>
            <Form.Item label="显示名称" name="label" rules={[{ required: true }]}><Input placeholder="如 公称通径" /></Form.Item>
            <Form.Item label="选项类型" name="optionType" rules={[{ required: true }]}>
              <Select options={[
                { value: 'EXCLUSIVE', label: 'EXCLUSIVE 单选' },
                { value: 'MULTI_SELECT', label: 'MULTI_SELECT 多选' },
                { value: 'NUMERIC', label: 'NUMERIC 数值' },
                { value: 'TEXT', label: 'TEXT 文本' },
                { value: 'COLOR', label: 'COLOR 颜色' },
              ]} />
            </Form.Item>
            <Space.Compact style={{ width: '100%', gap: 10 }}>
              <Form.Item label="数据类型" name="dataType" style={{ flex: 1 }}>
                <Select options={[
                  { value: 'STRING', label: 'STRING' }, { value: 'NUMBER', label: 'NUMBER' },
                  { value: 'DATE', label: 'DATE' }, { value: 'BOOLEAN', label: 'BOOLEAN' },
                ]} />
              </Form.Item>
              <Form.Item label="赋值方式" name="assignMode" style={{ flex: 1 }}>
                <Select options={[
                  { value: 'MANUAL', label: 'MANUAL' }, { value: 'SELECT', label: 'SELECT' }, { value: 'COMPUTED', label: 'COMPUTED' },
                ]} />
              </Form.Item>
              <Form.Item label="项序" name="sortOrder" style={{ flex: 1 }}>
                <InputNumber style={{ width: '100%' }} />
              </Form.Item>
            </Space.Compact>
            <Space.Compact style={{ width: '100%', gap: 10 }}>
              <Form.Item label="必选" name="isRequired" valuePropName="checked" style={{ flex: 1 }}>
                <Switch />
              </Form.Item>
              <Form.Item label="默认值" name="defaultValue" style={{ flex: 1 }}><Input /></Form.Item>
            </Space.Compact>
            <Space.Compact style={{ width: '100%', gap: 10 }}>
              <Form.Item label="最小值" name="minValue" style={{ flex: 1 }}><Input /></Form.Item>
              <Form.Item label="最大值" name="maxValue" style={{ flex: 1 }}><Input /></Form.Item>
            </Space.Compact>
            <Space.Compact style={{ width: '100%', gap: 10 }}>
              <Form.Item label="子料号前缀" name="partnoPrefix" style={{ flex: 1 }}><Input placeholder="如 -DN" /></Form.Item>
              <Form.Item label="子料号后缀" name="partnoSuffix" style={{ flex: 1 }}><Input placeholder="如 mm" /></Form.Item>
            </Space.Compact>
          </Form>
        )}

        {(drawerMode?.type === 'newValue' || drawerMode?.type === 'editValue') && (
          <Form form={form} layout="vertical">
            <Form.Item label="取值编号" name="code" rules={[{ required: true, max: 64 }]}>
              <Input disabled={drawerMode?.type === 'editValue'} />
            </Form.Item>
            <Form.Item label="显示名称" name="label" rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item label="描述" name="description"><Input.TextArea rows={2} /></Form.Item>
            <Space.Compact style={{ width: '100%', gap: 10 }}>
              <Form.Item label="项序" name="sortOrder" style={{ flex: 1 }}>
                <InputNumber style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item label="差价 (¥)" name="priceDelta" style={{ flex: 1 }}>
                <InputNumber style={{ width: '100%' }} step={10} />
              </Form.Item>
            </Space.Compact>
            <Space.Compact style={{ width: '100%', gap: 10 }}>
              <Form.Item label="参与子料号拼接" name="partnoInclude" valuePropName="checked" style={{ flex: 1 }}>
                <Switch />
              </Form.Item>
              <Form.Item label="激活" name="isActive" valuePropName="checked" style={{ flex: 1 }}>
                <Switch />
              </Form.Item>
            </Space.Compact>
          </Form>
        )}

        {drawerMode?.type === 'rules' && (
          <div>
            <div style={{ padding: 10, background: '#fff7e6', border: '1px solid #ffd591', borderRadius: 4, fontSize: 12, marginBottom: 14, color: '#876800' }}>
              ⓘ 选中此值时按顺序在 base.glb 内执行规则（SHOW_MESH 显隐 / REPLACE_MATERIAL 改色 / SWAP_MESH 替换 / TRANSFORM_MESH 变换）
            </div>
            {rulesList.length === 0 ? <Empty description="暂无 3D 规则" /> : rulesList.map((r, idx) => (
              <Card key={r.id || `new-${idx}`} size="small" style={{ marginBottom: 8 }}
                    extra={<a style={{ color: '#f5222d' }} onClick={() => removeRuleRow(idx)}>🗑 删除</a>}>
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Space style={{ width: '100%' }}>
                    <Select value={r.action} options={RULE_ACTIONS} style={{ width: 220 }}
                            onChange={v => updateRuleRow(idx, 'action', v)} />
                    <span style={{ color: '#999' }}>项序</span>
                    <InputNumber value={r.sortOrder} onChange={v => updateRuleRow(idx, 'sortOrder', v)} style={{ width: 80 }} />
                  </Space>
                  <Input value={r.targetMesh || ''} placeholder="目标 mesh（如 mesh_body_304）"
                         onChange={e => updateRuleRow(idx, 'targetMesh', e.target.value)} />
                  <Input.TextArea value={JSON.stringify(r.params || {}, null, 2)} rows={2}
                                  placeholder="params JSON（如 {&quot;diffuse&quot;:&quot;#E8E8EC&quot;,&quot;metallic&quot;:0.9}）"
                                  onChange={e => {
                                    try { updateRuleRow(idx, 'params', JSON.parse(e.target.value || '{}')); }
                                    catch { /* 等用户输完 */ updateRuleRow(idx, 'params', e.target.value); }
                                  }} />
                </Space>
              </Card>
            ))}
            <Button block onClick={addRuleRow}>+ 新增 3D 规则</Button>
          </div>
        )}

        {drawerMode?.type === 'importer' && (
          <>
            <div style={{ width: '30%', borderRight: '1px solid #f0f0f0', padding: 16, background: '#fafbfc', overflowY: 'auto' }}>
              <h4>🔍 筛选条件</h4>
              <Form.Item label="特征群组" style={{ marginTop: 12 }}>
                <Select placeholder="全部群组" allowClear value={filterGroupId} onChange={setFilterGroupId}
                        options={groups.map(g => ({ value: g.id, label: `${g.code} · ${g.name}` }))} />
              </Form.Item>
              <div style={{ marginTop: 16, padding: 10, background: '#fff7e6', border: '1px solid #ffd591', borderRadius: 4, fontSize: 11.5, color: '#876800' }}>
                ⓘ <b>方案 B 快照复制</b>：导入后字段是模板独立副本，特征库后续改动不会自动影响。
              </div>
            </div>
            <div style={{ width: '70%', overflowY: 'auto' }}>
              <Table size="small" rowKey="id" pagination={false} dataSource={visibleFields}
                columns={[
                  { title: '', width: 36, render: (_, f: any) => {
                    const dup = existingCodes.has(f.code);
                    return (
                      <Checkbox disabled={dup} checked={selectedFieldIds.has(f.id)}
                                onChange={e => {
                                  const ns = new Set(selectedFieldIds);
                                  if (e.target.checked) ns.add(f.id); else ns.delete(f.id);
                                  setSelectedFieldIds(ns);
                                }} />
                    );
                  }},
                  { title: '群组', width: 220, render: (_, f: any) => (
                    <div>
                      <div style={{ fontSize: 10.5, color: '#999' }}>{f.groupCode}</div>
                      <code style={{ fontSize: 11 }}>{f.code}</code> {f.name}
                    </div>
                  )},
                  { title: '类型', dataIndex: 'dataType', width: 80 },
                  { title: '赋值', dataIndex: 'assignMode', width: 80 },
                  { title: '必填', dataIndex: 'isRequired', width: 60, align: 'center',
                    render: (v: boolean) => v ? '✓' : '—' },
                  { title: '状态', width: 90, render: (_, f: any) => existingCodes.has(f.code)
                    ? <Tag color="red">已存在</Tag>
                    : <Tag color="green">可导入</Tag> },
                ]}
              />
            </div>
          </>
        )}

        {/* baseModel rendered below */}
        {drawerMode?.type === 'baseModel' && (
          <Row gutter={[10, 10]}>
            {models.map(m => (
              <Col span={12} key={m.id}>
                <Card hoverable onClick={() => setSelectedModelId(m.id)}
                      cover={
                        <ConfiguratorPreview partNo={m.partNo} glbUrl={m.glbUrl} height={140}
                                             autoRotate cameraControls={false}
                                             label={`v${m.version}`} />
                      }
                      style={{ border: m.id === selectedModelId ? '2px solid #1890ff' : '1px solid #e0e0e0',
                               background: m.id === selectedModelId ? '#e6f7ff' : '#fff' }}>
                  <div style={{ fontWeight: 600 }}>{m.label || m.partNo}</div>
                  <div style={{ fontSize: 11, color: '#999', marginTop: 4 }}>
                    <code>{m.partNo}</code> · v{m.version} {m.isCurrent && <Tag color="green">current</Tag>}
                  </div>
                  {(m.meshCount || m.vertices) && (
                    <div style={{ fontSize: 11, marginTop: 6, color: '#666' }}>
                      {m.meshCount && `${m.meshCount} mesh · `}
                      {m.vertices && `${m.vertices.toLocaleString()} 顶点`}
                    </div>
                  )}
                </Card>
              </Col>
            ))}
            {models.length === 0 && <Empty description="暂无可用模型 — 请先在「3D 源文件管理」上传" />}
          </Row>
        )}
      </Drawer>

      <ValueEditDrawer
        open={valueDrawerOpen}
        option={valueDrawerOption}
        value={valueDrawerValue}
        templateCategory={tpl?.category}
        onClose={() => setValueDrawerOpen(false)}
        onSaved={loadTemplate}
      />

      {/* 版本对比 Modal */}
      <Drawer title="🔀 版本对比 diff" width={680} open={diffOpen} onClose={() => setDiffOpen(false)}>
        {diffResult && (
          <>
            <Descriptions size="small" bordered column={2}>
              <Descriptions.Item label="v1">v{diffResult.v1?.version} {diffResult.v1?.label && `· ${diffResult.v1.label}`}</Descriptions.Item>
              <Descriptions.Item label="v2">v{diffResult.v2?.version} {diffResult.v2?.label && `· ${diffResult.v2.label}`}</Descriptions.Item>
              <Descriptions.Item label="变化数" span={2}>{diffResult.changeCount} 处</Descriptions.Item>
            </Descriptions>
            <Table size="small" rowKey={(r, i) => String(i)} dataSource={diffResult.changes} style={{ marginTop: 14 }}
              columns={[
                { title: '层级', dataIndex: 'level', width: 80,
                  render: (v: string) => <Tag color={v === 'template' ? 'orange' : 'blue'}>{v}</Tag> },
                { title: '类型', dataIndex: 'type', width: 90 },
                { title: '字段', dataIndex: 'field', width: 110, render: (v, r: any) => v || r.code },
                { title: 'v1', dataIndex: 'v1', render: (v: any) => <code style={{ fontSize: 10.5 }}>{String(v ?? '-').substring(0, 30)}</code> },
                { title: 'v2', dataIndex: 'v2', render: (v: any) => <code style={{ fontSize: 10.5 }}>{String(v ?? '-').substring(0, 30)}</code> },
              ]}
            />
          </>
        )}
      </Drawer>

      {/* §18A.5 重新拉取差异 Drawer */}
      <Drawer title="📋 重新拉取 — 特征库差异审核" width={720} open={refreshOpen} onClose={() => setRefreshOpen(false)}
              extra={
                <Space>
                  <Button onClick={() => setRefreshOpen(false)}>取消</Button>
                  <Button type="primary" onClick={applyRefresh}>✓ 应用 → 创建新草稿版本</Button>
                </Space>
              }>
        {refreshLoading ? <Spin /> : refreshDiffs.length === 0 ? (
          <div style={{ padding: 40, textAlign: 'center' }}>
            <div style={{ fontSize: 48, color: '#52c41a' }}>✅</div>
            <div style={{ marginTop: 12, fontSize: 14 }}>当前模板与特征库一致</div>
            <div style={{ marginTop: 6, fontSize: 12, color: '#999' }}>所有快照字段与源版本无差异，无需更新</div>
          </div>
        ) : (
          <>
            <div style={{ padding: 10, background: '#fff7e6', border: '1px solid #ffd591', borderRadius: 4, fontSize: 12, marginBottom: 14, color: '#876800' }}>
              ⓘ 共 <b>{refreshDiffs.length}</b> 个字段有差异。逐项选「保留模板」/「采用源」。提交后自动创建模板新草稿版本 v{(tpl.version || 1) + 1}-rc1（不污染当前 {tpl.status}）。
            </div>
            {refreshDiffs.map((d: any) => (
              <Card key={d.option_id} size="small" style={{ marginBottom: 10 }}
                    title={<><b>{d.option_code}</b> · {d.source_field_name}</>}>
                {d.type === 'SOURCE_DELETED' && (
                  <Tag color="red">⚠ 源已删除</Tag>
                )}
                {d.field_diffs && d.field_diffs.length > 0 && (
                  <div style={{ marginBottom: 8 }}>
                    <div style={{ fontSize: 11, color: '#666', marginBottom: 4 }}>字段属性差异</div>
                    {d.field_diffs.map((fd: any, i: number) => (
                      <div key={i} style={{ fontSize: 11.5, marginBottom: 3 }}>
                        <code>{fd.attr}</code>: <span style={{ color: '#999' }}>"{fd.tpl}"</span> → <b style={{ color: '#1890ff' }}>"{fd.src}"</b>
                      </div>
                    ))}
                  </div>
                )}
                {d.value_diffs && d.value_diffs.length > 0 && (
                  <div style={{ marginBottom: 8 }}>
                    <div style={{ fontSize: 11, color: '#666', marginBottom: 4 }}>取值差异</div>
                    {d.value_diffs.map((vd: any, i: number) => (
                      <Tag key={i} color={vd.type === 'NEW_IN_SOURCE' ? 'green' : vd.type === 'DELETED_FROM_SOURCE' ? 'red' : 'orange'}
                           style={{ marginBottom: 4 }}>
                        {vd.type === 'NEW_IN_SOURCE' ? '➕ 新增' : vd.type === 'DELETED_FROM_SOURCE' ? '➖ 删除' : '🏷 改名'}
                        : <code>{vd.code}</code> {vd.src_label && `→ "${vd.src_label}"`}
                      </Tag>
                    ))}
                  </div>
                )}
                <div style={{ display: 'flex', gap: 6, marginTop: 6 }}>
                  <Button size="small" type={diffDecisions[d.option_id] === 'KEEP' ? 'primary' : 'default'}
                          onClick={() => setDiffDecisions({ ...diffDecisions, [d.option_id]: 'KEEP' })}>
                    🔒 保留模板版本
                  </Button>
                  <Button size="small" type={diffDecisions[d.option_id] === 'TAKE' ? 'primary' : 'default'}
                          style={diffDecisions[d.option_id] === 'TAKE' ? { background: '#52c41a', borderColor: '#52c41a' } : {}}
                          onClick={() => setDiffDecisions({ ...diffDecisions, [d.option_id]: 'TAKE' })}>
                    📥 采用源版本
                  </Button>
                </div>
              </Card>
            ))}
          </>
        )}
      </Drawer>
    </div>
  );
};

const VersionItem: React.FC<{ dot: string; current?: boolean; archived?: boolean; label: string; tag: string; meta: string; changes: string; onRollback?: () => void }> = ({ dot, current, archived, label, tag, meta, changes, onRollback }) => (
  <div style={{ position: 'relative', padding: '10px 14px 10px 12px', marginBottom: 10,
                background: '#fff', border: '1px solid #e4e7ed', borderRadius: 6 }}>
    <div style={{ position: 'absolute', left: -22, top: 14, width: 12, height: 12, borderRadius: '50%',
                  background: dot, border: '2px solid #fff', boxShadow: `0 0 0 1px ${dot}66` }} />
    <div style={{ fontSize: 13, fontWeight: 500, display: 'flex', alignItems: 'center', gap: 8 }}>
      <Tag color={current ? 'green' : archived ? 'default' : 'blue'}>● {tag}</Tag>
      <span style={{ flex: 1 }}>{label}</span>
      {onRollback && (
        <Popconfirm title="回滚到此版本？当前状态会自动备份为新快照。" onConfirm={onRollback}>
          <Button size="small" danger>↩ 回滚</Button>
        </Popconfirm>
      )}
    </div>
    <div style={{ fontSize: 11.5, color: '#909399', marginBottom: 5, marginTop: 3 }}>{meta}</div>
    <div style={{ fontSize: 11.5, color: '#606266', lineHeight: 1.6 }}>{changes}</div>
  </div>
);

export default ConfiguratorTemplateEditor;
