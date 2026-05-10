import React, { useEffect, useState, useCallback, useRef } from 'react';
import {
  Card,
  Checkbox,
  Tag,
  Button,
  Space,
  Typography,
  Spin,
  message,
  Divider,
  Badge,
  Empty,
  Tooltip,
  Switch,
} from 'antd';
import {
  LockOutlined,
  DeleteOutlined,
  SaveOutlined,
  ReloadOutlined,
  HolderOutlined,
} from '@ant-design/icons';
import { processService } from '../../services/processService';

const { Text, Title } = Typography;

const CATEGORY_LIST = [
  { key: 'SURFACE_TREATMENT', label: '表面处理' },
  { key: 'MACHINING',         label: '机加'     },
  { key: 'HEAT_TREATMENT',    label: '热处理'   },
  { key: 'ASSEMBLY',          label: '装配'     },
  { key: 'INSPECTION',        label: '检测'     },
  { key: 'PACKAGING',         label: '包装'     },
];

const CATEGORY_COLOR: Record<string, string> = {
  SURFACE_TREATMENT: 'blue',
  MACHINING:         'cyan',
  HEAT_TREATMENT:    'red',
  ASSEMBLY:          'green',
  INSPECTION:        'orange',
  PACKAGING:         'purple',
};

interface ProcessItem {
  id: string;
  code: string;
  name: string;
  description: string;
  category: string;
  isRequired: boolean;
  sortOrder: number;
  status: string;
}

/** Represents a selected process with per-product required flag and order */
interface SelectedProcess {
  processId: string;
  isRequired: boolean;
  // process detail fields from allProcesses lookup
  name: string;
  code: string;
  category: string;
  description: string;
}

interface Props {
  productId: string;
}

const ProcessSelection: React.FC<Props> = ({ productId }) => {
  const [processes, setProcesses] = useState<ProcessItem[]>([]);
  const [selectedList, setSelectedList] = useState<SelectedProcess[]>([]);
  const [savedList, setSavedList] = useState<SelectedProcess[]>([]);
  const [activeCategory, setActiveCategory] = useState<string>('SURFACE_TREATMENT');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  // Drag state
  const dragIndex = useRef<number | null>(null);
  const dragOverIndex = useRef<number | null>(null);

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [allRes, boundRes] = await Promise.all([
        processService.listAll(),
        processService.getProductProcesses(productId),
      ]);
      const all: ProcessItem[] = allRes.data || [];
      const bound: any[] = boundRes.data || [];

      setProcesses(all);

      // Build selected list from bound data (which now includes sort_order and is_required)
      const processMap = new Map(all.map((p) => [p.id, p]));
      const selected: SelectedProcess[] = bound
        .map((bp: any) => {
          const proc = processMap.get(bp.processId);
          if (!proc) return null;
          return {
            processId: bp.processId,
            isRequired: bp.isRequired ?? false,
            name: proc.name,
            code: proc.code,
            category: proc.category,
            description: proc.description,
          };
        })
        .filter(Boolean) as SelectedProcess[];

      setSelectedList(selected);
      setSavedList(JSON.parse(JSON.stringify(selected)));
    } catch (err: any) {
      message.error(err?.response?.data?.message || '加载失败');
    } finally {
      setLoading(false);
    }
  }, [productId]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const selectedIds = new Set(selectedList.map((s) => s.processId));

  const toggleProcess = (proc: ProcessItem) => {
    if (selectedIds.has(proc.id)) {
      // Remove
      setSelectedList((prev) => prev.filter((s) => s.processId !== proc.id));
    } else {
      // Add to end
      setSelectedList((prev) => [
        ...prev,
        {
          processId: proc.id,
          isRequired: false,
          name: proc.name,
          code: proc.code,
          category: proc.category,
          description: proc.description,
        },
      ]);
    }
  };

  const removeProcess = (processId: string) => {
    setSelectedList((prev) => prev.filter((s) => s.processId !== processId));
  };

  const toggleRequired = (processId: string) => {
    setSelectedList((prev) =>
      prev.map((s) =>
        s.processId === processId ? { ...s, isRequired: !s.isRequired } : s
      )
    );
  };

  // Drag handlers for reordering
  const handleDragStart = (index: number) => {
    dragIndex.current = index;
  };

  const handleDragOver = (e: React.DragEvent, index: number) => {
    e.preventDefault();
    dragOverIndex.current = index;
  };

  const handleDrop = () => {
    if (dragIndex.current === null || dragOverIndex.current === null) return;
    if (dragIndex.current === dragOverIndex.current) return;

    setSelectedList((prev) => {
      const next = [...prev];
      const [removed] = next.splice(dragIndex.current!, 1);
      next.splice(dragOverIndex.current!, 0, removed);
      return next;
    });
    dragIndex.current = null;
    dragOverIndex.current = null;
  };

  const handleSave = async () => {
    setSaving(true);
    try {
      const items = selectedList.map((s, i) => ({
        processId: s.processId,
        sortOrder: i,
        isRequired: s.isRequired,
      }));
      await processService.bindProcesses(productId, items);
      setSavedList(JSON.parse(JSON.stringify(selectedList)));
      message.success('保存成功');
    } catch (err: any) {
      message.error(err?.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const handleReset = () => {
    setSelectedList(JSON.parse(JSON.stringify(savedList)));
  };

  const visibleProcesses = processes.filter((p) => p.category === activeCategory);

  const isDirty = JSON.stringify(selectedList) !== JSON.stringify(savedList);

  return (
    <Spin spinning={loading}>
      <div style={{ display: 'flex', gap: 16, height: 'calc(100vh - 200px)', minHeight: 500 }}>
        {/* Left: Category Tabs */}
        <div
          style={{
            width: 120,
            flexShrink: 0,
            background: '#fafafa',
            border: '1px solid #f0f0f0',
            borderRadius: 8,
            overflow: 'auto',
          }}
        >
          {CATEGORY_LIST.map((cat) => {
            const countInCat = selectedList.filter(
              (s) => s.category === cat.key
            ).length;
            return (
              <div
                key={cat.key}
                onClick={() => setActiveCategory(cat.key)}
                style={{
                  padding: '12px 8px',
                  cursor: 'pointer',
                  background: activeCategory === cat.key ? '#e6f4ff' : 'transparent',
                  borderLeft: activeCategory === cat.key ? '3px solid #1677ff' : '3px solid transparent',
                  textAlign: 'center',
                  transition: 'all 0.2s',
                }}
              >
                <Badge count={countInCat} size="small" offset={[4, 0]}>
                  <Text
                    strong={activeCategory === cat.key}
                    style={{
                      fontSize: 13,
                      color: activeCategory === cat.key ? '#1677ff' : '#333',
                    }}
                  >
                    {cat.label}
                  </Text>
                </Badge>
              </div>
            );
          })}
        </div>

        {/* Center: Process Cards */}
        <div style={{ flex: 1, overflow: 'auto' }}>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))',
              gap: 12,
              padding: 4,
            }}
          >
            {visibleProcesses.length === 0 ? (
              <Empty description="暂无工序" />
            ) : (
              visibleProcesses.map((proc) => {
                const isSelected = selectedIds.has(proc.id);
                return (
                  <Card
                    key={proc.id}
                    size="small"
                    hoverable
                    onClick={() => toggleProcess(proc)}
                    style={{
                      cursor: 'pointer',
                      border: isSelected ? '2px solid #1677ff' : '1px solid #f0f0f0',
                      background: isSelected ? '#f0f7ff' : '#fff',
                      transition: 'all 0.2s',
                    }}
                    styles={{ body: { padding: '10px 12px' } }}
                  >
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 6 }}>
                      <Checkbox
                        checked={isSelected}
                        onChange={() => toggleProcess(proc)}
                        onClick={(e) => e.stopPropagation()}
                      />
                    </div>
                    <div style={{ fontWeight: 600, fontSize: 14, marginBottom: 2 }}>{proc.name}</div>
                    <div style={{ color: '#888', fontSize: 11, marginBottom: 4 }}>{proc.code}</div>
                    {proc.description && (
                      <Text type="secondary" style={{ fontSize: 12 }} ellipsis={{ tooltip: proc.description }}>
                        {proc.description}
                      </Text>
                    )}
                  </Card>
                );
              })
            )}
          </div>
        </div>

        {/* Right: Selected Panel with drag reorder */}
        <div
          style={{
            width: 280,
            flexShrink: 0,
            border: '1px solid #f0f0f0',
            borderRadius: 8,
            background: '#fff',
            display: 'flex',
            flexDirection: 'column',
          }}
        >
          <div style={{ padding: '12px 16px', borderBottom: '1px solid #f0f0f0' }}>
            <Title level={5} style={{ margin: 0 }}>
              已选工序
              <Badge
                count={selectedList.length}
                style={{ marginLeft: 8, backgroundColor: '#1677ff' }}
                showZero
              />
            </Title>
            <Text type="secondary" style={{ fontSize: 11 }}>拖拽调整顺序，开关设置必选</Text>
          </div>

          <div style={{ flex: 1, overflow: 'auto', padding: '8px 12px' }}>
            {selectedList.length === 0 ? (
              <Empty description="未选择工序" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              selectedList.map((item, index) => (
                <div
                  key={item.processId}
                  draggable
                  onDragStart={() => handleDragStart(index)}
                  onDragOver={(e) => handleDragOver(e, index)}
                  onDrop={handleDrop}
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    padding: '6px 8px',
                    borderRadius: 6,
                    background: '#fafafa',
                    marginBottom: 4,
                    border: '1px solid #f0f0f0',
                    cursor: 'grab',
                    transition: 'background 0.15s',
                  }}
                  onMouseOver={(e) => (e.currentTarget.style.background = '#f0f7ff')}
                  onMouseOut={(e) => (e.currentTarget.style.background = '#fafafa')}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, flex: 1, minWidth: 0 }}>
                    <HolderOutlined style={{ color: '#bbb', cursor: 'grab', flexShrink: 0 }} />
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontSize: 13, fontWeight: 500 }}>{item.name}</div>
                      <div style={{ fontSize: 11, color: '#999' }}>
                        <Tag color={CATEGORY_COLOR[item.category]} style={{ fontSize: 10, lineHeight: '16px', padding: '0 4px' }}>
                          {CATEGORY_LIST.find((c) => c.key === item.category)?.label}
                        </Tag>
                      </div>
                    </div>
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0 }}>
                    <Tooltip title={item.isRequired ? '必选工序' : '设为必选'}>
                      <Switch
                        size="small"
                        checked={item.isRequired}
                        onChange={() => toggleRequired(item.processId)}
                        checkedChildren={<LockOutlined />}
                      />
                    </Tooltip>
                    <Button
                      type="text"
                      size="small"
                      danger
                      icon={<DeleteOutlined />}
                      onClick={(e) => { e.stopPropagation(); removeProcess(item.processId); }}
                      style={{ padding: '0 4px' }}
                    />
                  </div>
                </div>
              ))
            )}
          </div>

          <div style={{ padding: '12px 16px', borderTop: '1px solid #f0f0f0' }}>
            <Space style={{ width: '100%' }} direction="vertical">
              <Button
                type="primary"
                icon={<SaveOutlined />}
                loading={saving}
                onClick={handleSave}
                block
                disabled={!isDirty}
              >
                保存
              </Button>
              <Button
                icon={<ReloadOutlined />}
                onClick={handleReset}
                block
                disabled={!isDirty}
              >
                重置
              </Button>
            </Space>
          </div>
        </div>
      </div>
    </Spin>
  );
};

export default ProcessSelection;
