import React, { useEffect, useState, useCallback } from 'react';
import {
  Button,
  Card,
  Table,
  Drawer,
  Checkbox,
  Space,
  Tag,
  Typography,
  message,
  Skeleton,
  Empty,
} from 'antd';
import { PlusOutlined, HolderOutlined, DeleteOutlined } from '@ant-design/icons';
import {
  DndContext,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  SortableContext,
  useSortable,
  verticalListSortingStrategy,
  arrayMove,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import {
  boundGlobalVariableService,
  type TemplateGvBindingDTO,
  type BindingItem,
} from '../../services/boundGlobalVariableService';

const { Text } = Typography;

// -----------------------------------------------------------------------
// 拖拽行组件（替换 ant design Table 的 body.row）
// -----------------------------------------------------------------------

interface SortableRowProps {
  'data-row-key'?: string;
  children?: React.ReactNode;
  [key: string]: unknown;
}

const SortableRow: React.FC<SortableRowProps> = ({ 'data-row-key': rowKey, children, ...rest }) => {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: rowKey as string });

  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
  };

  return (
    <tr
      ref={setNodeRef}
      style={style}
      {...attributes}
      {...rest}
      data-row-key={rowKey}
    >
      {/* 注入拖拽监听到第一列 td 的内容区 */}
      {React.Children.map(children as React.ReactElement[], (child, idx) => {
        if (idx === 0 && React.isValidElement(child)) {
          return React.cloneElement(child as React.ReactElement<any>, {
            children: (
              <span style={{ cursor: 'grab', color: '#bbb' }} {...listeners}>
                <HolderOutlined />
              </span>
            ),
          });
        }
        return child;
      })}
    </tr>
  );
};

// -----------------------------------------------------------------------
// 主组件
// -----------------------------------------------------------------------

interface Props {
  templateId: string;
  isDraft: boolean;
}

const VAR_TYPE_LABELS: Record<string, { label: string; color: string }> = {
  LOOKUP_TABLE: { label: '查找表', color: 'blue' },
  SCALAR: { label: '标量', color: 'green' },
};

const GvBindingPanel: React.FC<Props> = ({ templateId, isDraft }) => {
  const [bindings, setBindings] = useState<TemplateGvBindingDTO[]>([]);
  const [loadingBindings, setLoadingBindings] = useState(false);
  const [saving, setSaving] = useState(false);

  // 添加弹窗
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [candidates, setCandidates] = useState<
    Array<{ code: string; name: string; varType: string; unit?: string; isActive?: boolean }>
  >([]);
  const [loadingCandidates, setLoadingCandidates] = useState(false);
  const [selectedCodes, setSelectedCodes] = useState<string[]>([]);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
  );

  // ---- 拉取已绑定列表 ----
  const loadBindings = useCallback(async () => {
    if (!templateId) return;
    setLoadingBindings(true);
    try {
      const data = await boundGlobalVariableService.getTemplateBindings(templateId);
      const sorted = [...(data || [])].sort((a, b) => a.displayOrder - b.displayOrder);
      setBindings(sorted);
    } catch (e: any) {
      message.error(e.message || '加载绑定关系失败');
    } finally {
      setLoadingBindings(false);
    }
  }, [templateId]);

  useEffect(() => {
    loadBindings();
  }, [loadBindings]);

  // ---- 保存（全量替换） ----
  const doSave = async (items: TemplateGvBindingDTO[]) => {
    if (!templateId || !isDraft) return;
    setSaving(true);
    try {
      const payload: BindingItem[] = items.map((b, idx) => ({
        globalVariableCode: b.globalVariableCode,
        displayOrder: idx,
      }));
      const updated = await boundGlobalVariableService.updateTemplateBindings(templateId, payload);
      const sorted = [...(updated || [])].sort((a, b) => a.displayOrder - b.displayOrder);
      setBindings(sorted);
      message.success('关联全局变量已保存');
    } catch (e: any) {
      message.error(e.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  // ---- 拖拽排序结束 ----
  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    if (!over || active.id === over.id) return;
    const oldIdx = bindings.findIndex((b) => b.globalVariableCode === active.id);
    const newIdx = bindings.findIndex((b) => b.globalVariableCode === over.id);
    if (oldIdx === -1 || newIdx === -1) return;
    const next = arrayMove(bindings, oldIdx, newIdx);
    setBindings(next);
    doSave(next);
  };

  // ---- 移除一条绑定 ----
  const handleRemove = (code: string) => {
    const next = bindings.filter((b) => b.globalVariableCode !== code);
    setBindings(next);
    doSave(next);
  };

  // ---- 打开添加抽屉 ----
  const openAddDrawer = async () => {
    setDrawerOpen(true);
    setSelectedCodes([]);
    setLoadingCandidates(true);
    try {
      const data = await boundGlobalVariableService.listGlobalVariableDefinitions();
      // 过滤掉已绑定的和 isActive=false 的
      const boundCodes = new Set(bindings.map((b) => b.globalVariableCode));
      setCandidates((data || []).filter((c) => c.isActive !== false && !boundCodes.has(c.code)));
    } catch (e: any) {
      message.error(e.message || '加载候选变量失败');
    } finally {
      setLoadingCandidates(false);
    }
  };

  // ---- 确认添加 ----
  const handleConfirmAdd = () => {
    if (selectedCodes.length === 0) {
      message.warning('请至少选择一个全局变量');
      return;
    }
    const newItems: TemplateGvBindingDTO[] = selectedCodes.map((code, i) => {
      const def = candidates.find((c) => c.code === code);
      return {
        id: '', // 后端生成
        templateId,
        globalVariableCode: code,
        globalVariableName: def?.name || code,
        varType: (def?.varType as 'LOOKUP_TABLE' | 'SCALAR') || 'SCALAR',
        unit: def?.unit,
        isActive: true,
        displayOrder: bindings.length + i,
      };
    });
    const next = [...bindings, ...newItems];
    setBindings(next);
    setDrawerOpen(false);
    doSave(next);
  };

  // ---- DRAFT 专属拖拽柄列（SortableRow 会替换 td 内容为 HolderOutlined） ----
  const dragColumn = {
    title: '',
    key: '__drag__',
    width: 32,
    render: () => null, // 实际内容被 SortableRow 替换
  };

  // ---- 表格列定义 ----
  const baseColumns = [
    {
      title: '名称',
      dataIndex: 'globalVariableName',
      key: 'name',
      render: (_: string, record: TemplateGvBindingDTO) => (
        <Space size={6}>
          <Text strong>{record.globalVariableName}</Text>
          {record.isActive === false && (
            <Tag color="default" style={{ fontSize: 10, lineHeight: '14px', padding: '0 4px' }}>
              已停用
            </Tag>
          )}
        </Space>
      ),
    },
    {
      title: 'Code',
      dataIndex: 'globalVariableCode',
      key: 'code',
      render: (v: string) => <Text code style={{ fontSize: 12 }}>{v}</Text>,
    },
    {
      title: '类型',
      dataIndex: 'varType',
      key: 'varType',
      width: 90,
      render: (v: string) => {
        const cfg = VAR_TYPE_LABELS[v] || { label: v, color: 'default' };
        return <Tag color={cfg.color}>{cfg.label}</Tag>;
      },
    },
    {
      title: '单位',
      dataIndex: 'unit',
      key: 'unit',
      width: 80,
      render: (v?: string) => v || '—',
    },
    {
      title: '操作',
      key: 'action',
      width: 64,
      render: (_: unknown, record: TemplateGvBindingDTO) => (
        <Button
          type="link"
          danger
          size="small"
          icon={<DeleteOutlined />}
          onClick={() => handleRemove(record.globalVariableCode)}
        >
          移除
        </Button>
      ),
    },
  ];

  // DRAFT：加拖拽柄列（第一列）+ 移除列
  // 只读：不含操作列，不含拖拽柄
  const draftColumns = [dragColumn, ...baseColumns];
  const readonlyColumns = baseColumns.slice(0, -1); // 去掉最后的"操作"列

  return (
    <Card
      title="关联全局变量"
      size="small"
      style={{ marginBottom: 12 }}
      extra={
        isDraft ? (
          <Button
            type="dashed"
            size="small"
            icon={<PlusOutlined />}
            onClick={openAddDrawer}
            loading={saving}
          >
            添加全局变量
          </Button>
        ) : null
      }
    >
      {loadingBindings ? (
        <Skeleton active paragraph={{ rows: 3 }} />
      ) : bindings.length === 0 ? (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="暂无关联全局变量"
          style={{ padding: '16px 0' }}
        />
      ) : isDraft ? (
        // 可拖拽版本（DRAFT）
        <DndContext sensors={sensors} onDragEnd={handleDragEnd}>
          <SortableContext
            items={bindings.map((b) => b.globalVariableCode)}
            strategy={verticalListSortingStrategy}
          >
            <Table
              dataSource={bindings}
              columns={draftColumns}
              rowKey="globalVariableCode"
              size="small"
              pagination={false}
              bordered
              components={{
                body: {
                  row: (props: SortableRowProps) => <SortableRow {...props} />,
                },
              }}
            />
          </SortableContext>
        </DndContext>
      ) : (
        // 只读版本（PUBLISHED / ARCHIVED）
        <Table
          dataSource={bindings}
          columns={readonlyColumns}
          rowKey="globalVariableCode"
          size="small"
          pagination={false}
          bordered
        />
      )}

      {/* 添加全局变量 Drawer */}
      <Drawer
        title="选择全局变量"
        placement="right"
        width={480}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        footer={
          <Space style={{ float: 'right' }}>
            <Button onClick={() => setDrawerOpen(false)}>取消</Button>
            <Button type="primary" onClick={handleConfirmAdd}>
              确认添加（{selectedCodes.length}）
            </Button>
          </Space>
        }
      >
        {loadingCandidates ? (
          <Skeleton active paragraph={{ rows: 6 }} />
        ) : candidates.length === 0 ? (
          <Empty description="没有可添加的全局变量（已全部绑定或无活跃变量）" />
        ) : (
          <Checkbox.Group
            style={{ width: '100%', display: 'flex', flexDirection: 'column', gap: 8 }}
            value={selectedCodes}
            onChange={(vals) => setSelectedCodes(vals as string[])}
          >
            {candidates.map((c) => {
              const cfg = VAR_TYPE_LABELS[c.varType] || { label: c.varType, color: 'default' };
              return (
                <div
                  key={c.code}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    padding: '8px 12px',
                    border: '1px solid #f0f0f0',
                    borderRadius: 6,
                    background: selectedCodes.includes(c.code) ? '#f0f5ff' : '#fff',
                  }}
                >
                  <Checkbox value={c.code} style={{ flex: 1 }}>
                    <Space size={8}>
                      <Text strong style={{ fontSize: 13 }}>{c.name}</Text>
                      <Text code style={{ fontSize: 11 }}>{c.code}</Text>
                      <Tag color={cfg.color} style={{ fontSize: 11 }}>{cfg.label}</Tag>
                      {c.unit && <Text type="secondary" style={{ fontSize: 11 }}>{c.unit}</Text>}
                    </Space>
                  </Checkbox>
                </div>
              );
            })}
          </Checkbox.Group>
        )}
      </Drawer>
    </Card>
  );
};

export default GvBindingPanel;
