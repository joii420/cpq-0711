/**
 * SqlViewListPanel — 组件 SQL 视图列表 Tab 内容
 *
 * 规范：
 *   - 使用 SelectableTable + 工具栏动作（CLAUDE.md 列表操作规范）
 *   - 行内不放变更动作，只有"主入口"（点击名称进编辑）
 *   - 删除若后端返 409 + 引用清单 → Modal 展示受影响字段
 */
import React, { useCallback, useEffect, useState } from 'react';
import { Button, Modal, Tag, Typography, message } from 'antd';
import { PlusOutlined, ExperimentOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import SelectableTable, { runBatch } from '../../components/SelectableTable';
import {
  componentSqlViewService,
  type ComponentSqlView,
} from '../../services/componentSqlViewService';
import SqlViewConfigDrawer from './SqlViewConfigDrawer';

const { Text } = Typography;

interface Props {
  componentId: string;
}

const SqlViewListPanel: React.FC<Props> = ({ componentId }) => {
  const [views, setViews] = useState<ComponentSqlView[]>([]);
  const [loading, setLoading] = useState(false);

  // 编辑抽屉
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingView, setEditingView] = useState<ComponentSqlView | null>(null);

  // 删除引用冲突 Modal
  const [conflictModal, setConflictModal] = useState<{
    open: boolean;
    viewName: string;
    affectedFields: string[];
    onConfirm: () => void;
  }>({ open: false, viewName: '', affectedFields: [], onConfirm: () => {} });

  const loadViews = useCallback(async () => {
    if (!componentId) return;
    setLoading(true);
    try {
      const res = await componentSqlViewService.list(componentId);
      setViews(res.data ?? []);
    } catch (e: any) {
      message.error('加载 SQL 视图失败: ' + (e?.message ?? ''));
    } finally {
      setLoading(false);
    }
  }, [componentId]);

  useEffect(() => {
    loadViews();
  }, [loadViews]);

  const handleOpenCreate = () => {
    setEditingView(null);
    setDrawerOpen(true);
  };

  const handleOpenEdit = (view: ComponentSqlView) => {
    setEditingView(view);
    setDrawerOpen(true);
  };

  const handleDelete = async (rows: ComponentSqlView[]) => {
    await runBatch(
      rows,
      async (row) => {
        try {
          await componentSqlViewService.delete(componentId, row.id);
        } catch (e: any) {
          // 409 = 存在引用
          if (e?.response?.status === 409) {
            const affectedFields: string[] =
              e?.response?.data?.affectedFields ?? [];
            await new Promise<void>((resolve, reject) => {
              setConflictModal({
                open: true,
                viewName: row.sqlViewName,
                affectedFields,
                onConfirm: async () => {
                  setConflictModal((prev) => ({ ...prev, open: false }));
                  try {
                    await componentSqlViewService.delete(componentId, row.id);
                    resolve();
                  } catch (e2: any) {
                    reject(e2);
                  }
                },
              });
            });
          } else {
            throw e;
          }
        }
      },
      {
        rowLabel: (r) => r.sqlViewName,
        successMsg: '已删除',
      },
    );
    loadViews();
  };

  const handleDryRun = async (rows: ComponentSqlView[]) => {
    const view = rows[0];
    try {
      message.loading({ content: '正在 Dry-Run...', key: 'dry-run' });
      const res = await componentSqlViewService.dryRun(componentId, view.sqlTemplate);
      const result = res.data;
      if (result.success) {
        const cols = result.declaredColumns ?? [];
        message.success({
          content: `Dry-Run 通过 — ${cols.length} 列: ${cols.map((c) => c.name).join(', ')}`,
          key: 'dry-run',
          duration: 5,
        });
      } else {
        message.error({ content: `Dry-Run 失败: ${result.error ?? '未知'}`, key: 'dry-run', duration: 8 });
      }
    } catch (e: any) {
      message.error({ content: `Dry-Run 异常: ${e?.message ?? ''}`, key: 'dry-run', duration: 8 });
    }
  };

  const columns: ColumnsType<ComponentSqlView> = [
    {
      title: '视图名称',
      dataIndex: 'sqlViewName',
      key: 'sqlViewName',
      render: (name: string, record) => (
        // 主入口：点击名称进编辑
        <a onClick={() => handleOpenEdit(record)} style={{ fontFamily: 'Consolas, Monaco, monospace' }}>
          {name}
        </a>
      ),
    },
    {
      title: '作用域',
      dataIndex: 'scope',
      key: 'scope',
      width: 120,
      render: (scope: string) =>
        scope === 'GLOBAL' ? (
          <Tag color="purple">GLOBAL</Tag>
        ) : (
          <Tag color="default">COMPONENT</Tag>
        ),
    },
    {
      title: '列签名',
      key: 'columns',
      width: 100,
      render: (_: unknown, record: ComponentSqlView) => {
        const count = record.declaredColumns?.length ?? 0;
        return <Text type="secondary">{count} 列</Text>;
      },
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (status: string) =>
        status === 'ACTIVE' ? (
          <Tag color="success">启用</Tag>
        ) : (
          <Tag color="default">停用</Tag>
        ),
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      width: 180,
      render: (v: string) =>
        v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '—',
    },
  ];

  return (
    <>
      <SelectableTable<ComponentSqlView>
        rowKey="id"
        columns={columns}
        dataSource={views}
        loading={loading}
        rowLabel={(r) => r.sqlViewName}
        toolbar={
          <Button icon={<PlusOutlined />} type="primary" size="small" onClick={handleOpenCreate}>
            新建 SQL 视图
          </Button>
        }
        actions={[
          {
            key: 'edit',
            label: '编辑',
            enabledWhen: (rows) =>
              rows.length === 1 ? true : rows.length === 0 ? false : '只能单选编辑',
            onClick: (rows) => handleOpenEdit(rows[0]),
          },
          {
            key: 'dry-run',
            label: 'Dry-Run 校验',
            icon: <ExperimentOutlined />,
            enabledWhen: (rows) =>
              rows.length === 1 ? true : rows.length === 0 ? false : '只能单选校验',
            onClick: handleDryRun,
          },
          {
            key: 'delete',
            label: '删除',
            danger: true,
            enabledWhen: (rows) => (rows.length > 0 ? true : false),
            needsConfirm: true,
            confirmTitle: '确认删除选中的 {N} 个 SQL 视图？',
            confirmDescription:
              '删除后字段配置中引用此视图的 BNF path 将失效。如有引用将列出受影响字段供确认。',
            onClick: handleDelete,
          },
        ]}
        pagination={{ pageSize: 10, size: 'small', showTotal: (t) => `共 ${t} 条` }}
        size="small"
      />

      {/* SQL 视图编辑抽屉 */}
      <SqlViewConfigDrawer
        open={drawerOpen}
        componentId={componentId}
        editingView={editingView}
        onClose={() => setDrawerOpen(false)}
        onSaved={loadViews}
      />

      {/* 删除引用冲突确认 Modal */}
      <Modal
        title={`"${conflictModal.viewName}" 存在字段引用，确认强制删除？`}
        open={conflictModal.open}
        onCancel={() => setConflictModal((prev) => ({ ...prev, open: false }))}
        onOk={conflictModal.onConfirm}
        okText="确认删除"
        okButtonProps={{ danger: true }}
        cancelText="取消"
        destroyOnClose
      >
        <p style={{ color: '#d46b08', marginBottom: 8 }}>
          以下字段的 BNF path 引用了此 SQL 视图，删除后这些字段的数据源将失效：
        </p>
        {conflictModal.affectedFields.length > 0 ? (
          <ul style={{ paddingLeft: 18, margin: 0 }}>
            {conflictModal.affectedFields.map((f) => (
              <li key={f} style={{ fontFamily: 'Consolas, Monaco, monospace', fontSize: 13 }}>
                {f}
              </li>
            ))}
          </ul>
        ) : (
          <p style={{ color: '#999' }}>（引用详情获取失败，建议先检查后再删除）</p>
        )}
      </Modal>
    </>
  );
};

export default SqlViewListPanel;
