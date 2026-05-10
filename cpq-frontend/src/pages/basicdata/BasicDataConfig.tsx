import React, { useEffect, useMemo, useState } from 'react';
import {
  Layout, Tree, Button, Drawer, Form, Input, Select, InputNumber, Tabs, Table, Space, Tag, Popconfirm,
  message, Upload, Empty, Switch,
} from 'antd';
import { PlusOutlined, UploadOutlined, ImportOutlined } from '@ant-design/icons';
import { basicDataConfigService } from '../../services/basicDataConfigService';
import type {
  BasicDataSheet,
  BasicDataAttribute,
  DerivedAttribute,
  ParsedExcelStructure,
  ExtensibleTableOption,
} from '../../services/basicDataConfigService';

const { Sider, Content } = Layout;

// title 改用 React.ReactNode 以支持复合标题(显示 sheet 名 + target_table tag)
interface SheetTreeNode {
  key: string;
  title: React.ReactNode;
  raw: BasicDataSheet;
  children?: SheetTreeNode[];
}

function buildSheetTree(rows: BasicDataSheet[]): SheetTreeNode[] {
  const map = new Map<string, SheetTreeNode>();
  rows.forEach((r) => {
    // 在节点 title 里加上 target_table 标签,让用户一眼看到 sheet→物理表映射
    const title = (
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, width: '100%' }}>
        <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.sheetName}</span>
        {r.targetTable
          ? <Tag color="cyan" style={{ marginRight: 0, fontSize: 10, padding: '0 4px', lineHeight: '16px' }}>{r.targetTable}</Tag>
          : <Tag color="default" style={{ marginRight: 0, fontSize: 10, padding: '0 4px', lineHeight: '16px' }}>未关联</Tag>}
      </span>
    );
    map.set(r.id, { key: r.id, title, raw: r, children: [] });
  });
  const roots: SheetTreeNode[] = [];
  map.forEach((node) => {
    const pid = node.raw.parentConfigId;
    if (pid && map.has(pid)) {
      map.get(pid)!.children!.push(node);
    } else {
      roots.push(node);
    }
  });
  return roots;
}

/** 校验 JSON 字符串合法性 */
function validateJson(_: unknown, value: string) {
  if (!value || !value.trim()) return Promise.resolve();
  try {
    JSON.parse(value);
    return Promise.resolve();
  } catch {
    return Promise.reject(new Error('请输入合法的 JSON，例如 {"fee_type":"INCOMING_OTHER"}'));
  }
}

const BasicDataConfig: React.FC = () => {
  const [sheets, setSheets] = useState<BasicDataSheet[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const [attrs, setAttrs] = useState<BasicDataAttribute[]>([]);
  const [derived, setDerived] = useState<DerivedAttribute[]>([]);

  // Sheet 编辑 Drawer
  const [sheetDrawer, setSheetDrawer] = useState(false);
  const [editingSheet, setEditingSheet] = useState<BasicDataSheet | null>(null);
  const [sheetForm] = Form.useForm();
  const [sheetSubmitting, setSheetSubmitting] = useState(false);

  // 属性编辑 Drawer
  const [attrDrawer, setAttrDrawer] = useState(false);
  const [editingAttr, setEditingAttr] = useState<BasicDataAttribute | null>(null);
  const [attrForm] = Form.useForm();
  const [attrSubmitting, setAttrSubmitting] = useState(false);

  // 衍生字段编辑 Drawer
  const [derivedDrawer, setDerivedDrawer] = useState(false);
  const [editingDerived, setEditingDerived] = useState<DerivedAttribute | null>(null);
  const [derivedForm] = Form.useForm();
  const [derivedSubmitting, setDerivedSubmitting] = useState(false);

  // Excel 导入 Drawer
  const [importDrawer, setImportDrawer] = useState(false);
  const [parsed, setParsed] = useState<ParsedExcelStructure | null>(null);

  // V58: 可选物理表清单
  const [extensibleTables, setExtensibleTables] = useState<ExtensibleTableOption[]>([]);

  const fetchSheets = async () => {
    const res = await basicDataConfigService.listSheets();
    setSheets(res.data || []);
  };

  const fetchAttrs = async (id: string) => {
    const res = await basicDataConfigService.listAttributes(id);
    setAttrs(res.data || []);
  };

  const fetchDerived = async (id: string) => {
    const res = await basicDataConfigService.listDerived(id);
    setDerived(res.data || []);
  };

  useEffect(() => {
    fetchSheets();
    // 加载可选物理表（后端未就绪时 fallback mock）
    basicDataConfigService.listExtensibleTables().then((res) => {
      setExtensibleTables(res.data || []);
    });
  }, []);

  useEffect(() => {
    if (selectedId) {
      fetchAttrs(selectedId);
      fetchDerived(selectedId);
    } else {
      setAttrs([]);
      setDerived([]);
    }
  }, [selectedId]);

  const tree = useMemo(() => buildSheetTree(sheets), [sheets]);
  const selectedSheet = sheets.find((s) => s.id === selectedId) || null;

  const sheetSelectOptions = useMemo(
    () => sheets.filter((s) => s.id !== editingSheet?.id).map((s) => ({ label: s.sheetName, value: s.id })),
    [sheets, editingSheet]
  );

  const extensibleTableOptions = useMemo(
    () => extensibleTables.map((t) => ({
      label: `${t.displayName} (${t.tableName})`,
      value: t.tableName,
    })),
    [extensibleTables]
  );

  // ---- Sheet 保存 ----
  const handleSaveSheet = async () => {
    try {
      const values = await sheetForm.validateFields();
      // 解析 joinColumns
      const joinColumns = typeof values.joinColumns === 'string'
        ? values.joinColumns.split(',').map((s: string) => s.trim()).filter(Boolean)
        : (values.joinColumns || []);
      // 解析 targetDiscriminator JSON 字符串
      let targetDiscriminator: Record<string, unknown> | null = null;
      if (values.targetDiscriminator && values.targetDiscriminator.trim()) {
        try {
          targetDiscriminator = JSON.parse(values.targetDiscriminator);
        } catch {
          message.error('targetDiscriminator 必须是合法 JSON');
          return;
        }
      }
      const payload = {
        ...values,
        joinColumns,
        targetTable: values.targetTable || null,
        targetDiscriminator,
      };
      setSheetSubmitting(true);
      if (editingSheet) {
        await basicDataConfigService.updateSheet(editingSheet.id, payload);
      } else {
        await basicDataConfigService.createSheet(payload);
      }
      message.success('保存成功');
      setSheetDrawer(false);
      sheetForm.resetFields();
      setEditingSheet(null);
      fetchSheets();
    } catch (err: any) {
      if (err?.errorFields) return;
      message.error(err.message);
    } finally {
      setSheetSubmitting(false);
    }
  };

  const handleDeleteSheet = async (id: string) => {
    try {
      await basicDataConfigService.deleteSheet(id);
      message.success('已删除');
      if (selectedId === id) setSelectedId(null);
      fetchSheets();
    } catch (err: any) {
      message.error(err.message);
    }
  };

  // ---- 属性保存 ----
  const handleSaveAttr = async () => {
    try {
      const values = await attrForm.validateFields();
      setAttrSubmitting(true);
      if (editingAttr) {
        await basicDataConfigService.updateAttribute(editingAttr.id, values);
      } else {
        await basicDataConfigService.createAttribute({ ...values, configId: selectedId! });
      }
      message.success('保存成功');
      setAttrDrawer(false);
      attrForm.resetFields();
      setEditingAttr(null);
      if (selectedId) fetchAttrs(selectedId);
    } catch (err: any) {
      if (err?.errorFields) return;
      message.error(err.message);
    } finally {
      setAttrSubmitting(false);
    }
  };

  // ---- 衍生字段保存 ----
  const handleSaveDerived = async () => {
    try {
      const values = await derivedForm.validateFields();
      let computation: any = values.computation;
      if (typeof computation === 'string' && computation.trim()) {
        try { computation = JSON.parse(computation); }
        catch { message.error('computation 必须是合法 JSON'); return; }
      }
      const payload = { ...values, computation };
      setDerivedSubmitting(true);
      if (editingDerived) {
        await basicDataConfigService.updateDerived(editingDerived.id, payload);
      } else {
        await basicDataConfigService.createDerived({ ...payload, hostSheetId: selectedId! });
      }
      message.success('保存成功');
      setDerivedDrawer(false);
      derivedForm.resetFields();
      setEditingDerived(null);
      if (selectedId) fetchDerived(selectedId);
    } catch (err: any) {
      if (err?.errorFields) return;
      message.error(err.message);
    } finally {
      setDerivedSubmitting(false);
    }
  };

  const handleParseExcel = async (file: File) => {
    try {
      const res = await basicDataConfigService.parseExcel(file);
      setParsed(res.data);
      message.success(`已解析 ${res.data.sheets.length} 个 Sheet`);
    } catch (err: any) {
      message.error(err.message);
    }
    return false;
  };

  const handleImportSheet = async (s: ParsedExcelStructure['sheets'][0]) => {
    try {
      const created = await basicDataConfigService.createSheet({
        sheetName: s.sheetName,
        sheetIndex: s.sheetIndex,
        headerRowIndex: s.headerRowIndex,
        dataStartRowIndex: s.headerRowIndex + 1,
        joinColumns: [],
      });
      const newSheet = created.data;
      for (const col of s.columns) {
        const code = `VAR_${newSheet.id.slice(0, 8)}_${col.columnLetter}`;
        try {
          await basicDataConfigService.createAttribute({
            configId: newSheet.id,
            columnLetter: col.columnLetter,
            columnTitle: col.columnTitle,
            variableCode: code,
            variableLabel: col.columnTitle,
            dataType: 'VALUE',
            sortOrder: col.columnIndex,
          });
        } catch {
          // 跳过重复
        }
      }
      message.success(`已导入 Sheet [${s.sheetName}]`);
      fetchSheets();
    } catch (err: any) {
      message.error(err.message);
    }
  };

  // 拼当前 sheet + attr 的 BNF 路径预览(供组件管理拷贝使用)
  const buildAttrBnfPath = (attr: BasicDataAttribute): string => {
    if (!selectedSheet?.targetTable) return '';
    const disc = selectedSheet.targetDiscriminator;
    const preds: string[] = [];
    if (disc && typeof disc === 'object') {
      for (const [k, v] of Object.entries(disc)) {
        preds.push(`${k}='${String(v)}'`);
      }
    }
    const predStr = preds.length > 0 ? `[${preds.join(', ')}]` : '';
    return `${selectedSheet.targetTable}${predStr}.${attr.variableCode}`;
  };

  const attrColumns = [
    { title: '列', dataIndex: 'columnLetter', key: 'columnLetter', width: 60 },
    { title: '表头', dataIndex: 'columnTitle', key: 'columnTitle' },
    { title: '变量编码', dataIndex: 'variableCode', key: 'variableCode' },
    { title: '变量标签', dataIndex: 'variableLabel', key: 'variableLabel' },
    {
      title: '类型', dataIndex: 'dataType', key: 'dataType', width: 100,
      render: (t: string) => <Tag color={t === 'IDENTIFIER' ? 'orange' : 'blue'}>{t}</Tag>,
    },
    {
      title: 'BNF 路径(组件公式可引用)',
      key: 'bnfPath',
      width: 320,
      render: (_: any, r: BasicDataAttribute) => {
        const bnf = buildAttrBnfPath(r);
        if (!bnf) {
          return <Tag color="default">未配 target_table</Tag>;
        }
        return (
          <Space size={4}>
            <Tag
              color="cyan"
              style={{ fontFamily: 'Consolas, Monaco, monospace', cursor: 'pointer', fontSize: 11 }}
              title="点击复制"
              onClick={() => {
                navigator.clipboard.writeText(bnf);
                message.success('已复制路径');
              }}
            >
              {bnf}
            </Tag>
          </Space>
        );
      },
    },
    {
      title: '导入必填', dataIndex: 'isRequired', key: 'isRequired', width: 90,
      render: (v: boolean | undefined) => (
        <Switch checked={!!v} size="small" disabled checkedChildren="必填" unCheckedChildren="可选" />
      ),
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 100,
      render: (s: string) => <Tag color={s === 'ACTIVE' ? 'green' : 'default'}>{s === 'ACTIVE' ? '启用' : '禁用'}</Tag>,
    },
    {
      title: '操作', key: 'actions', width: 140,
      render: (_: any, r: BasicDataAttribute) => (
        <Space>
          <a onClick={() => {
            setEditingAttr(r);
            attrForm.setFieldsValue({ ...r, isRequired: r.isRequired ?? false });
            setAttrDrawer(true);
          }}>编辑</a>
          {r.status === 'ACTIVE' && (
            <Popconfirm title="禁用此变量？" onConfirm={async () => { await basicDataConfigService.disableAttribute(r.id); fetchAttrs(selectedId!); }}>
              <a style={{ color: 'red' }}>禁用</a>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  const derivedColumns = [
    { title: '变量编码', dataIndex: 'variableCode', key: 'variableCode' },
    { title: '变量标签', dataIndex: 'variableLabel', key: 'variableLabel' },
    {
      title: '计算类型', dataIndex: 'computationType', key: 'computationType', width: 120,
      render: (t: string) => <Tag color="purple">{t}</Tag>,
    },
    {
      title: '类型', dataIndex: 'dataType', key: 'dataType', width: 100,
      render: (t: string) => <Tag color={t === 'IDENTIFIER' ? 'orange' : 'blue'}>{t}</Tag>,
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 100,
      render: (s: string) => <Tag color={s === 'ACTIVE' ? 'green' : 'default'}>{s === 'ACTIVE' ? '启用' : '禁用'}</Tag>,
    },
    {
      title: '操作', key: 'actions', width: 140,
      render: (_: any, r: DerivedAttribute) => (
        <Space>
          <a onClick={() => {
            setEditingDerived(r);
            derivedForm.setFieldsValue({ ...r, computation: typeof r.computation === 'object' ? JSON.stringify(r.computation, null, 2) : r.computation });
            setDerivedDrawer(true);
          }}>编辑</a>
          {r.status === 'ACTIVE' && (
            <Popconfirm title="禁用此衍生字段？" onConfirm={async () => { await basicDataConfigService.disableDerived(r.id); fetchDerived(selectedId!); }}>
              <a style={{ color: 'red' }}>禁用</a>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <Layout style={{ background: 'transparent' }}>
      <Sider width={280} theme="light" style={{ background: '#fff', padding: 12, borderRadius: 8, marginRight: 12 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
          <strong>Sheet 配置</strong>
          <Space>
            <Button size="small" icon={<ImportOutlined />} onClick={() => { setParsed(null); setImportDrawer(true); }}>导入</Button>
            <Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => {
              setEditingSheet(null);
              sheetForm.resetFields();
              sheetForm.setFieldsValue({ headerRowIndex: 1, dataStartRowIndex: 2 });
              setSheetDrawer(true);
            }} />
          </Space>
        </div>
        {tree.length > 0 ? (
          <Tree
            treeData={tree}
            defaultExpandAll
            selectedKeys={selectedId ? [selectedId] : []}
            onSelect={(keys) => setSelectedId(keys[0] as string)}
          />
        ) : (
          <Empty description="暂无 Sheet 配置" />
        )}
      </Sider>

      <Content style={{ background: '#fff', padding: 16, borderRadius: 8, minHeight: 400 }}>
        {!selectedSheet ? (
          <Empty description="请从左侧选择一个 Sheet" />
        ) : (
          <>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <h2 style={{ margin: 0 }}>{selectedSheet.sheetName}</h2>
              <Space>
                <Button onClick={() => {
                  setEditingSheet(selectedSheet);
                  sheetForm.setFieldsValue({
                    ...selectedSheet,
                    joinColumns: (selectedSheet.joinColumns || []).join(','),
                    targetTable: selectedSheet.targetTable || undefined,
                    targetDiscriminator: selectedSheet.targetDiscriminator
                      ? JSON.stringify(selectedSheet.targetDiscriminator)
                      : '',
                  });
                  setSheetDrawer(true);
                }}>编辑 Sheet</Button>
                <Popconfirm title="确认删除该 Sheet？" onConfirm={() => handleDeleteSheet(selectedSheet.id)}>
                  <Button danger>删除 Sheet</Button>
                </Popconfirm>
              </Space>
            </div>
            <div style={{ marginBottom: 12, color: '#666' }}>
              {selectedSheet.description || '（无描述）'} | 关联列: {selectedSheet.joinColumns.join(',') || '-'}
              {selectedSheet.targetTable && (
                <> | 目标表: <Tag color="cyan">{selectedSheet.targetTable}</Tag></>
              )}
            </div>
            <Tabs
              items={[
                {
                  key: 'attrs',
                  label: `属性配置 (${attrs.length})`,
                  children: (
                    <>
                      <div style={{ marginBottom: 12 }}>
                        <Button type="primary" icon={<PlusOutlined />} onClick={() => {
                          setEditingAttr(null);
                          attrForm.resetFields();
                          attrForm.setFieldsValue({ dataType: 'VALUE', isRequired: false });
                          setAttrDrawer(true);
                        }}>新增属性</Button>
                      </div>
                      <Table rowKey="id" columns={attrColumns} dataSource={attrs} pagination={false} size="small" />
                    </>
                  ),
                },
                {
                  key: 'derived',
                  label: `衍生字段 (${derived.length})`,
                  children: (
                    <>
                      <div style={{ marginBottom: 12 }}>
                        <Button type="primary" icon={<PlusOutlined />} onClick={() => {
                          setEditingDerived(null);
                          derivedForm.resetFields();
                          derivedForm.setFieldsValue({ dataType: 'VALUE', computationType: 'EXPRESSION' });
                          setDerivedDrawer(true);
                        }}>新增衍生字段</Button>
                      </div>
                      <Table rowKey="id" columns={derivedColumns} dataSource={derived} pagination={false} size="small" />
                    </>
                  ),
                },
              ]}
            />
          </>
        )}
      </Content>

      {/* ============ Sheet 编辑 Drawer ============ */}
      <Drawer
        title={editingSheet ? '编辑 Sheet' : '新增 Sheet'}
        placement="right"
        width={720}
        open={sheetDrawer}
        onClose={() => { setSheetDrawer(false); sheetForm.resetFields(); setEditingSheet(null); }}
        destroyOnClose
        footer={
          <div style={{ textAlign: 'right' }}>
            <Space>
              <Button onClick={() => { setSheetDrawer(false); sheetForm.resetFields(); setEditingSheet(null); }}>取消</Button>
              <Button type="primary" loading={sheetSubmitting} onClick={handleSaveSheet}>保存</Button>
            </Space>
          </div>
        }
      >
        <Form form={sheetForm} layout="vertical">
          <Form.Item name="sheetName" label="Sheet 名称" rules={[{ required: true, message: '请输入 Sheet 名称' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="parentConfigId" label="父级 Sheet">
            <Select allowClear options={sheetSelectOptions} placeholder="无父级（根 Sheet）" />
          </Form.Item>
          <Form.Item name="joinColumns" label="关联列（变量编码，逗号分隔）" tooltip="例如 HF_PART_NO,SEQ_NO">
            <Input placeholder="HF_PART_NO,SEQ_NO" />
          </Form.Item>
          <Form.Item name="headerRowIndex" label="表头行号">
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="dataStartRowIndex" label="数据起始行号">
            <InputNumber min={2} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item name="sortOrder" label="排序">
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>

          {/* V58 新字段 */}
          <Form.Item
            name="targetTable"
            label="目标物理表"
            tooltip="该 Sheet 导入时写入的物理表，留空表示不参与导入"
          >
            <Select
              allowClear
              showSearch
              placeholder="选择目标表（可清空）"
              options={extensibleTableOptions}
              filterOption={(input, option) =>
                String(option?.label ?? '').toLowerCase().includes(input.toLowerCase())
              }
            />
          </Form.Item>
          <Form.Item
            name="targetDiscriminator"
            label="行级鉴别器 (JSON)"
            tooltip='限制写入目标表时的筛选条件，如 {"fee_type":"INCOMING_OTHER"}'
            rules={[{ validator: validateJson }]}
          >
            <Input.TextArea
              rows={3}
              placeholder='{"fee_type":"INCOMING_OTHER"}'
            />
          </Form.Item>
        </Form>
      </Drawer>

      {/* ============ 属性编辑 Drawer ============ */}
      <Drawer
        title={editingAttr ? '编辑属性' : '新增属性'}
        placement="right"
        width={480}
        open={attrDrawer}
        onClose={() => { setAttrDrawer(false); attrForm.resetFields(); setEditingAttr(null); }}
        destroyOnClose
        footer={
          <div style={{ textAlign: 'right' }}>
            <Space>
              <Button onClick={() => { setAttrDrawer(false); attrForm.resetFields(); setEditingAttr(null); }}>取消</Button>
              <Button type="primary" loading={attrSubmitting} onClick={handleSaveAttr}>保存</Button>
            </Space>
          </div>
        }
      >
        <Form form={attrForm} layout="vertical">
          <Form.Item name="columnLetter" label="Excel 列字母" rules={[{ required: true }]}>
            <Input placeholder="如 A" />
          </Form.Item>
          <Form.Item name="columnTitle" label="表头原文" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="variableCode" label="变量编码" rules={[{ required: true }]}>
            <Input disabled={!!editingAttr} placeholder="如 HF_PART_NO" />
          </Form.Item>
          <Form.Item name="variableLabel" label="变量标签" rules={[{ required: true }]}>
            <Input placeholder="如 宏丰料号" />
          </Form.Item>
          <Form.Item name="dataType" label="字段类型" rules={[{ required: true }]}>
            <Select options={[
              { label: '标识字段 IDENTIFIER', value: 'IDENTIFIER' },
              { label: '数值字段 VALUE', value: 'VALUE' },
            ]} />
          </Form.Item>
          {/* V58: is_required */}
          <Form.Item
            name="isRequired"
            label="导入必填"
            valuePropName="checked"
            tooltip="开启后，导入 Excel 时该字段不可为空"
          >
            <Switch checkedChildren="必填" unCheckedChildren="可选" />
          </Form.Item>
          <Form.Item name="sortOrder" label="排序">
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Drawer>

      {/* ============ 衍生字段编辑 Drawer ============ */}
      <Drawer
        title={editingDerived ? '编辑衍生字段' : '新增衍生字段'}
        placement="right"
        width={720}
        open={derivedDrawer}
        onClose={() => { setDerivedDrawer(false); derivedForm.resetFields(); setEditingDerived(null); }}
        destroyOnClose
        footer={
          <div style={{ textAlign: 'right' }}>
            <Space>
              <Button onClick={() => { setDerivedDrawer(false); derivedForm.resetFields(); setEditingDerived(null); }}>取消</Button>
              <Button type="primary" loading={derivedSubmitting} onClick={handleSaveDerived}>保存</Button>
            </Space>
          </div>
        }
      >
        <Form form={derivedForm} layout="vertical">
          <Form.Item name="variableCode" label="变量编码" rules={[{ required: true }]}>
            <Input disabled={!!editingDerived} />
          </Form.Item>
          <Form.Item name="variableLabel" label="变量标签" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="dataType" label="字段类型" rules={[{ required: true }]}>
            <Select options={[
              { label: '标识字段 IDENTIFIER', value: 'IDENTIFIER' },
              { label: '数值字段 VALUE', value: 'VALUE' },
            ]} />
          </Form.Item>
          <Form.Item name="computationType" label="计算类型" rules={[{ required: true }]}>
            <Select options={[
              { label: 'LOOKUP（跨 Sheet 取值）', value: 'LOOKUP' },
              { label: 'EXPRESSION（本行字段运算）', value: 'EXPRESSION' },
              { label: 'AGGREGATE（跨行聚合）', value: 'AGGREGATE' },
            ]} />
          </Form.Item>
          <Form.Item
            name="computation"
            label="computation 配置（JSON）"
            rules={[{ required: true }]}
            tooltip='例如 EXPRESSION: {"type":"EXPRESSION","formula":"[组成含量(%)] * [元素单价] / 100"}'
          >
            <Input.TextArea rows={8} />
          </Form.Item>
          <Form.Item name="sortOrder" label="排序">
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Drawer>

      {/* ============ Excel 导入向导 Drawer ============ */}
      <Drawer
        title="导入新版 Excel 模板"
        placement="right"
        width={720}
        open={importDrawer}
        onClose={() => setImportDrawer(false)}
        destroyOnClose
        footer={
          <div style={{ textAlign: 'right' }}>
            <Button onClick={() => setImportDrawer(false)}>关闭</Button>
          </div>
        }
      >
        {!parsed ? (
          <Upload.Dragger accept=".xlsx,.xls" beforeUpload={handleParseExcel} maxCount={1} showUploadList={false}>
            <p className="ant-upload-drag-icon"><UploadOutlined /></p>
            <p className="ant-upload-text">点击或拖拽 Excel 文件</p>
            <p className="ant-upload-hint">系统自动识别所有 Sheet 与列结构</p>
          </Upload.Dragger>
        ) : (
          <div>
            <p>共解析 {parsed.sheets.length} 个 Sheet，点击"导入"创建对应的 Sheet 配置 + 属性：</p>
            <Table
              rowKey="sheetName"
              size="small"
              pagination={false}
              dataSource={parsed.sheets}
              columns={[
                { title: 'Sheet', dataIndex: 'sheetName' },
                { title: '列数', render: (_, r: any) => r.columns.length },
                {
                  title: '操作',
                  render: (_, r: any) => {
                    const exists = sheets.some((s) => s.sheetName === r.sheetName);
                    return exists
                      ? <Tag color="default">已存在</Tag>
                      : <Button size="small" type="primary" onClick={() => handleImportSheet(r)}>导入</Button>;
                  },
                },
              ]}
            />
          </div>
        )}
      </Drawer>
    </Layout>
  );
};

export default BasicDataConfig;
