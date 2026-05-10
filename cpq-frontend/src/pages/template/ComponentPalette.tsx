import React, { useState, useEffect, useCallback } from 'react';
import { Input, Select } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useDraggable } from '@dnd-kit/core';
import { componentService } from '../../services/componentService';
import './styles.css';

interface CompItem {
  id: string;
  name: string;
  code: string;
  componentType: string;
  formulas?: any[];
}

interface DirOption {
  id: string;
  name: string;
}

interface ComponentPaletteProps {
  onAddComponent: (componentId: string, componentName: string) => void;
}

const DraggableComponentCard = ({ comp, type }: { comp: CompItem; type: 'normal' | 'subtotal' }) => {
  const { attributes, listeners, setNodeRef, transform, isDragging } = useDraggable({
    id: `component-${comp.id}`,
    data: { type: 'component', componentId: comp.id, componentName: comp.name, componentType: comp.componentType },
  });

  // Don't apply transform to the original element — DragOverlay handles the moving copy
  const style: React.CSSProperties = {
    opacity: isDragging ? 0.3 : 1,
    cursor: isDragging ? 'grabbing' : 'grab',
  };

  const isSubtotal = type === 'subtotal';

  return (
    <div
      ref={setNodeRef}
      style={style}
      {...listeners}
      {...attributes}
      className={`tm-component-item${isSubtotal ? ' subtotal' : ''}`}
    >
      <div style={{ fontWeight: 600, fontSize: 12 }}>
        {isSubtotal ? '💰 ' : ''}{comp.name}
      </div>
      <div className="tm-component-item-sub">{comp.code}</div>
    </div>
  );
};

const ComponentPalette: React.FC<ComponentPaletteProps> = ({ onAddComponent: _onAdd }) => {
  const [directories, setDirectories] = useState<DirOption[]>([]);
  const [selectedDirId, setSelectedDirId] = useState<string>('');
  const [search, setSearch] = useState('');
  const [components, setComponents] = useState<CompItem[]>([]);
  const [loading, setLoading] = useState(false);

  // Load directories
  useEffect(() => {
    componentService.listDirectories().then((res: any) => {
      const dirs: any[] = res.data || [];
      const flat: DirOption[] = [];
      function walk(ds: any[]) {
        for (const d of ds) {
          flat.push({ id: d.id, name: d.name });
          if (d.children) walk(d.children);
        }
      }
      walk(dirs);
      setDirectories(flat);
      if (flat.length > 0) setSelectedDirId(flat[0].id);
    }).catch(() => {});
  }, []);

  // Load components for selected directory
  const loadComponents = useCallback(async () => {
    if (!selectedDirId) { setComponents([]); return; }
    setLoading(true);
    try {
      const res = await componentService.list({ directoryId: selectedDirId, keyword: search || undefined });
      const all: CompItem[] = (res.data || []).filter((c: any) => c.status === 'ACTIVE');
      setComponents(all);
    } catch {
      setComponents([]);
    } finally {
      setLoading(false);
    }
  }, [selectedDirId, search]);

  useEffect(() => { loadComponents(); }, [loadComponents]);

  const normalComps = components.filter(c => c.componentType !== 'SUBTOTAL');
  const subtotalComps = components.filter(c => c.componentType === 'SUBTOTAL');

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      {/* Directory selector */}
      <div style={{ padding: '12px 16px 8px', flexShrink: 0 }}>
        <Select
          size="small"
          style={{ width: '100%', marginBottom: 8 }}
          placeholder="选择组件目录"
          value={selectedDirId || undefined}
          onChange={setSelectedDirId}
          showSearch
          filterOption={(input, option) =>
            (option?.label as string ?? '').toLowerCase().includes(input.toLowerCase())
          }
          options={directories.map(d => ({ value: d.id, label: d.name }))}
        />
        <Input
          size="small"
          placeholder="搜索组件"
          prefix={<SearchOutlined style={{ color: '#bbb' }} />}
          allowClear
          value={search}
          onChange={e => setSearch(e.target.value)}
        />
      </div>

      {/* Component lists */}
      <div style={{ flex: 1, overflowY: 'auto', overflowX: 'hidden', padding: '0 16px 16px' }}>
        {loading ? (
          <div style={{ color: '#999', fontSize: 12, textAlign: 'center', padding: 20 }}>加载中...</div>
        ) : (
          <>
            {/* Normal components */}
            <div className="tm-panel-section">
              <div className="tm-panel-title">页签组件</div>
              {normalComps.length === 0 ? (
                <div style={{ color: '#999', fontSize: 12 }}>暂无页签组件</div>
              ) : (
                normalComps.map(comp => (
                  <DraggableComponentCard key={comp.id} comp={comp} type="normal" />
                ))
              )}
            </div>

            {/* Subtotal components */}
            <div className="tm-panel-section">
              <div className="tm-panel-title" style={{ borderBottomColor: '#d48806' }}>小计组件</div>
              {subtotalComps.length === 0 ? (
                <div style={{ color: '#999', fontSize: 12 }}>暂无小计组件</div>
              ) : (
                subtotalComps.map(comp => (
                  <DraggableComponentCard key={comp.id} comp={comp} type="subtotal" />
                ))
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
};

export default ComponentPalette;
