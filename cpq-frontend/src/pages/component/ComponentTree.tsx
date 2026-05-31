import React, { useState, useMemo } from 'react';
import { Tree, Modal, Form, Input, Select, Dropdown, Tag } from 'antd';
import { FolderOutlined, FileOutlined, PlusOutlined, SearchOutlined, StopOutlined, CalculatorOutlined } from '@ant-design/icons';
import type { DataNode } from 'antd/es/tree';
import type { DirectoryNode, ComponentItem } from './types';
import { componentService } from '../../services/componentService';
import { message } from 'antd';
import ComponentImportDrawer from './ComponentImportDrawer';
import './styles.css';

interface ComponentTreeProps {
  directories: DirectoryNode[];
  selectedComponentId: string | null;
  onSelectComponent: (comp: ComponentItem, dir: DirectoryNode) => void;
  onRenameComponent?: (id: string, oldName: string, newName: string) => void;
  onRefresh: () => void;
  loading?: boolean;
  searchKeyword?: string;
  onSearchChange?: (keyword: string) => void;
}

function findDir(dirs: DirectoryNode[], id: string): DirectoryNode | null {
  for (const d of dirs) {
    if (d.id === id) return d;
    const found = findDir(d.children, id);
    if (found) return found;
  }
  return null;
}

function findComp(dirs: DirectoryNode[], id: string): { comp: ComponentItem; dir: DirectoryNode } | null {
  for (const d of dirs) {
    for (const c of d.components) {
      if (c.id === id) return { comp: c, dir: d };
    }
    const found = findComp(d.children, id);
    if (found) return found;
  }
  return null;
}

/** Collect all components from the tree */
function collectAllComponents(dirs: DirectoryNode[]): ComponentItem[] {
  const result: ComponentItem[] = [];
  function walk(ds: DirectoryNode[]) {
    for (const d of ds) {
      result.push(...d.components);
      walk(d.children);
    }
  }
  walk(dirs);
  return result;
}

/** Build tree data for active components, separating NORMAL and SUBTOTAL */
function buildActiveTreeData(dirs: DirectoryNode[]): DataNode[] {
  return dirs.map((dir) => {
    const activeComps = dir.components.filter((comp) => comp.status === 'ACTIVE');
    const normalComps = activeComps.filter((c) => c.componentType !== 'SUBTOTAL');
    const subtotalComps = activeComps.filter((c) => c.componentType === 'SUBTOTAL');

    const children: DataNode[] = [
      ...buildActiveTreeData(dir.children),
      ...normalComps.map((comp) => ({
        key: `comp-${comp.id}`,
        title: comp.name,
        icon: <FileOutlined />,
        isLeaf: true,
      })),
    ];

    if (subtotalComps.length > 0) {
      children.push({
        key: `subtotal-group-${dir.id}`,
        title: <span style={{ color: '#d48806' }}>小计组件</span>,
        icon: <CalculatorOutlined style={{ color: '#d48806' }} />,
        isLeaf: false,
        selectable: false,
        children: subtotalComps.map((comp) => ({
          key: `comp-${comp.id}`,
          title: <span style={{ color: '#d48806' }}>{comp.name}</span>,
          icon: <CalculatorOutlined style={{ color: '#d48806' }} />,
          isLeaf: true,
        })),
      });
    }

    return {
      key: `dir-${dir.id}`,
      title: dir.name,
      icon: <FolderOutlined />,
      isLeaf: false,
      children,
    };
  });
}

const ComponentTree: React.FC<ComponentTreeProps> = ({
  directories,
  selectedComponentId,
  onSelectComponent,
  onRefresh,
  loading: _loading,
  searchKeyword = '',
  onRenameComponent,
  onSearchChange,
}) => {
  const [dirModalVisible, setDirModalVisible] = useState(false);
  const [dirModalMode, setDirModalMode] = useState<'create' | 'rename'>('create');
  const [dirForm] = Form.useForm();
  const [editingDirId, setEditingDirId] = useState<string | null>(null);
  const [selectedParentId, setSelectedParentId] = useState<string | null>(null);

  const [compModalVisible, setCompModalVisible] = useState(false);
  const [compForm] = Form.useForm();

  // Component rename
  const [compRenameVisible, setCompRenameVisible] = useState(false);
  const [renamingCompId, setRenamingCompId] = useState<string | null>(null);
  const [renamingCompOldName, setRenamingCompOldName] = useState('');
  const [compRenameForm] = Form.useForm();
  const [newCompDirId, setNewCompDirId] = useState<string | null>(null);

  // Disabled components list
  const disabledComponents = useMemo(() => {
    return collectAllComponents(directories).filter((c) => c.status === 'DISABLED');
  }, [directories]);

  const openCreateDir = (parentId?: string) => {
    setDirModalMode('create');
    setSelectedParentId(parentId ?? null);
    dirForm.resetFields();
    setDirModalVisible(true);
  };

  const openRenameDir = (id: string) => {
    const dir = findDir(directories, id);
    if (!dir) return;
    setDirModalMode('rename');
    setEditingDirId(id);
    dirForm.setFieldsValue({ name: dir.name });
    setDirModalVisible(true);
  };

  const handleDirModalOk = async () => {
    try {
      const values = await dirForm.validateFields();
      if (dirModalMode === 'create') {
        await componentService.createDirectory({
          name: values.name,
          parentId: selectedParentId,
          sortOrder: 0,
        });
        message.success('目录已创建');
      } else if (editingDirId) {
        await componentService.updateDirectory(editingDirId, { name: values.name });
        message.success('目录已更新');
      }
      setDirModalVisible(false);
      onRefresh();
    } catch (e: unknown) {
      const err = e as { message?: string };
      if (err.message) message.error(err.message);
    }
  };

  const handleDeleteDir = (id: string) => {
    Modal.confirm({
      title: '确认删除目录?',
      content: '该目录下不能有子目录或组件',
      okText: '删除',
      okType: 'danger',
      onOk: async () => {
        try {
          await componentService.deleteDirectory(id);
          message.success('目录已删除');
          onRefresh();
        } catch (e: unknown) {
          const err = e as { message?: string };
          message.error(err.message ?? '删除失败');
        }
      },
    });
  };

  const handleExportDir = async (id: string) => {
    try {
      await componentService.exportDirectory(id);
      message.success('已导出该目录的组件');
    } catch (e: unknown) {
      const err = e as { message?: string };
      message.error(err.message ?? '导出失败');
    }
  };

  const [importTarget, setImportTarget] = useState<{ id: string; name: string } | null>(null);
  const findDirName = (nodes: DirectoryNode[], id: string): string => {
    for (const n of nodes) {
      if (n.id === id) return n.name;
      if (n.children?.length) {
        const sub = findDirName(n.children, id);
        if (sub) return sub;
      }
    }
    return '';
  };
  const openImportDir = (id: string) => {
    setImportTarget({ id, name: findDirName(directories, id) });
  };

  const openCreateComp = (dirId?: string) => {
    setNewCompDirId(dirId ?? null);
    compForm.resetFields();
    setCompModalVisible(true);
  };

  const handleCompModalOk = async () => {
    try {
      const values = await compForm.validateFields();
      await componentService.create({
        name: values.name,
        directoryId: newCompDirId || values.directoryId || null,
        componentType: values.componentType || 'NORMAL',
        fields: [],
        formulas: [],
      });
      message.success('组件已创建');
      setCompModalVisible(false);
      onRefresh();
    } catch (e: unknown) {
      const err = e as { message?: string };
      if (err.message) message.error(err.message);
    }
  };

  const handleDeleteComp = (id: string) => {
    Modal.confirm({
      title: '确认删除组件?',
      okText: '删除',
      okType: 'danger',
      onOk: async () => {
        try {
          await componentService.delete(id);
          message.success('组件已删除');
          onRefresh();
        } catch (e: unknown) {
          const err = e as { message?: string };
          message.error(err.message ?? '删除失败');
        }
      },
    });
  };

  const openRenameComp = (id: string) => {
    const found = findComp(directories, id);
    if (!found) return;
    setRenamingCompId(id);
    setRenamingCompOldName(found.comp.name);
    compRenameForm.setFieldsValue({ name: found.comp.name });
    setCompRenameVisible(true);
  };

  const handleRenameCompOk = async () => {
    try {
      const values = await compRenameForm.validateFields();
      const newName = values.name?.trim();
      if (!newName || !renamingCompId) return;
      await componentService.update(renamingCompId, { name: newName });
      message.success('组件已重命名');
      setCompRenameVisible(false);
      onRenameComponent?.(renamingCompId, renamingCompOldName, newName);
      onRefresh();
    } catch (e: unknown) {
      const err = e as { message?: string };
      if (err.message) message.error(err.message);
    }
  };

  const handleToggleStatus = async (id: string) => {
    try {
      const res = await componentService.toggleStatus(id);
      const newStatus = res.data?.status;
      message.success(newStatus === 'DISABLED' ? '组件已停用' : '组件已启用');
      onRefresh();
    } catch (e: unknown) {
      const err = e as { message?: string };
      message.error(err.message ?? '操作失败');
    }
  };

  const getContextMenu = (key: string) => {
    const isDirKey = key.startsWith('dir-');
    const id = isDirKey ? key.replace('dir-', '') : key.replace('comp-', '');
    if (isDirKey) {
      return {
        items: [
          { key: 'new-dir', label: '新建子目录' },
          { key: 'new-comp', label: '新建组件' },
          { key: 'export', label: '导出目录' },
          { key: 'import', label: '导入到此目录' },
          { key: 'rename', label: '重命名' },
          { key: 'delete', label: '删除', danger: true },
        ],
        onClick: ({ key: action }: { key: string }) => {
          if (action === 'new-dir') openCreateDir(id);
          else if (action === 'new-comp') openCreateComp(id);
          else if (action === 'export') handleExportDir(id);
          else if (action === 'import') openImportDir(id);
          else if (action === 'rename') openRenameDir(id);
          else if (action === 'delete') handleDeleteDir(id);
        },
      };
    } else {
      // Find the component to check its status
      const found = findComp(directories, id);
      const isDisabled = found?.comp?.status === 'DISABLED';
      return {
        items: [
          { key: 'rename', label: '重命名' },
          { key: 'toggle-status', label: isDisabled ? '启用' : '停用' },
          { key: 'delete', label: '删除', danger: true },
        ],
        onClick: ({ key: action }: { key: string }) => {
          if (action === 'rename') openRenameComp(id);
          else if (action === 'toggle-status') handleToggleStatus(id);
          else if (action === 'delete') handleDeleteComp(id);
        },
      };
    }
  };

  const treeData = buildActiveTreeData(directories);

  // Build disabled components tree node
  const disabledTreeData: DataNode[] = disabledComponents.length > 0 ? [{
    key: 'group-disabled',
    title: <span style={{ color: '#999' }}>已停用组件 ({disabledComponents.length})</span>,
    icon: <StopOutlined style={{ color: '#999' }} />,
    isLeaf: false,
    selectable: false,
    children: disabledComponents.map((comp) => ({
      key: `comp-${comp.id}`,
      title: <span style={{ color: '#999' }}>{comp.name}</span>,
      icon: <FileOutlined style={{ color: '#999' }} />,
      isLeaf: true,
    })),
  }] : [];

  const allTreeData = [...treeData, ...disabledTreeData];

  const renderTreeNodes = (nodes: DataNode[]): DataNode[] =>
    nodes.map((node) => {
      const key = node.key as string;
      if (key === 'group-disabled') {
        return {
          ...node,
          children: node.children ? renderTreeNodes(node.children) : undefined,
        };
      }
      return {
        ...node,
        title: (
          <Dropdown menu={getContextMenu(key)} trigger={['contextMenu']}>
            <span>{node.title as React.ReactNode}</span>
          </Dropdown>
        ),
        children: node.children ? renderTreeNodes(node.children) : undefined,
      };
    });

  const handleSelect = (selectedKeys: React.Key[]) => {
    if (!selectedKeys.length) return;
    const key = selectedKeys[0] as string;
    if (key.startsWith('comp-')) {
      const compId = key.replace('comp-', '');
      const found = findComp(directories, compId);
      if (found) {
        onSelectComponent(found.comp, found.dir);
      }
    }
  };

  const selectedKeys = selectedComponentId ? [`comp-${selectedComponentId}`] : [];

  return (
    <div className="cm-left-panel">
      <div className="cm-left-header">
        <h3>组件列表</h3>
        <div style={{ display: 'flex', gap: 4 }}>
          <button
            className="cm-btn-icon"
            onClick={() => openCreateDir()}
            title="新建目录"
          >
            📁
          </button>
          <button
            className="cm-btn-icon"
            onClick={() => openCreateComp()}
            title="新建组件"
          >
            <PlusOutlined style={{ fontSize: 14 }} />
          </button>
        </div>
      </div>

      {/* Search input */}
      <div style={{ padding: '0 8px 8px' }}>
        <Input
          placeholder="搜索组件名称或编码"
          prefix={<SearchOutlined style={{ color: '#bbb' }} />}
          size="small"
          allowClear
          value={searchKeyword}
          onChange={(e) => onSearchChange?.(e.target.value)}
        />
      </div>

      <div className="cm-tree-container">
        <Tree
          showIcon
          treeData={renderTreeNodes(allTreeData)}
          selectedKeys={selectedKeys}
          onSelect={handleSelect}
          defaultExpandAll
          blockNode
        />
      </div>

      {/* Directory Modal */}
      <Modal
        title={dirModalMode === 'create' ? '新建目录' : '重命名目录'}
        open={dirModalVisible}
        onOk={handleDirModalOk}
        onCancel={() => setDirModalVisible(false)}
        okText="确定"
        cancelText="取消"
      >
        <Form form={dirForm} layout="vertical">
          <Form.Item
            name="name"
            label="目录名称"
            rules={[{ required: true, message: '请输入目录名称' }]}
          >
            <Input placeholder="请输入目录名称" />
          </Form.Item>
        </Form>
      </Modal>

      {/* Component Create Modal */}
      <Modal
        title="新建组件"
        open={compModalVisible}
        onOk={handleCompModalOk}
        onCancel={() => setCompModalVisible(false)}
        okText="创建"
        cancelText="取消"
      >
        <Form form={compForm} layout="vertical">
          <Form.Item
            name="componentType"
            label="组件类型"
            initialValue="NORMAL"
          >
            <Select>
              <Select.Option value="NORMAL">普通组件</Select.Option>
              <Select.Option value="SUBTOTAL">小计组件</Select.Option>
            </Select>
          </Form.Item>
          <Form.Item
            name="name"
            label="组件名称"
            rules={[{ required: true, message: '请输入组件名称' }]}
          >
            <Input placeholder="例：投料成本表" />
          </Form.Item>
          {!newCompDirId && directories.length > 0 && (
            <Form.Item name="directoryId" label="所属目录">
              <Select placeholder="选择目录（可选）" allowClear>
                {directories.map((d) => (
                  <Select.Option key={d.id} value={d.id}>{d.name}</Select.Option>
                ))}
              </Select>
            </Form.Item>
          )}
        </Form>
      </Modal>

      {/* Component Rename Modal */}
      <Modal
        title="重命名组件"
        open={compRenameVisible}
        onOk={handleRenameCompOk}
        onCancel={() => setCompRenameVisible(false)}
        okText="确定"
        cancelText="取消"
      >
        <Form form={compRenameForm} layout="vertical">
          <Form.Item
            name="name"
            label="组件名称"
            rules={[{ required: true, message: '请输入组件名称' }]}
          >
            <Input placeholder="输入新的组件名称" />
          </Form.Item>
        </Form>
      </Modal>

      <ComponentImportDrawer
        open={!!importTarget}
        targetDirId={importTarget?.id ?? null}
        targetDirName={importTarget?.name}
        onClose={() => setImportTarget(null)}
        onImported={onRefresh}
      />
    </div>
  );
};

export default ComponentTree;
