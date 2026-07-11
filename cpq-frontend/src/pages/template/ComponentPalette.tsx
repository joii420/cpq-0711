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
  /** 所选组件目录变化时上报给父层，供 Excel 视图下拉按同一目录过滤副本（Bug1）。 */
  onDirectoryChange?: (dirId: string) => void;
}

const DraggableComponentCard = ({ comp, type }: { comp: CompItem; type: 'normal' | 'subtotal' | 'excel' }) => {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: `component-${comp.id}`,
    data: { type: 'component', componentId: comp.id, componentName: comp.name, componentType: comp.componentType },
  });

  // Don't apply transform to the original element — DragOverlay handles the moving copy
  const style: React.CSSProperties = {
    opacity: isDragging ? 0.3 : 1,
    cursor: isDragging ? 'grabbing' : 'grab',
  };

  const typeClass = type === 'subtotal' ? ' subtotal' : type === 'excel' ? ' excel' : '';
  const prefix = type === 'subtotal' ? '💰 ' : type === 'excel' ? '📊 ' : '';

  return (
    <div
      ref={setNodeRef}
      style={style}
      {...listeners}
      {...attributes}
      className={`tm-component-item${typeClass}`}
    >
      <div style={{ fontWeight: 600, fontSize: 12 }}>
        {prefix}{comp.name}
      </div>
      <div className="tm-component-item-sub">{comp.code}</div>
    </div>
  );
};

const ComponentPalette: React.FC<ComponentPaletteProps> = ({ onAddComponent: _onAdd, onDirectoryChange }) => {
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

  // Bug1：所选目录变化时上报父层，Excel 视图下拉据此只列本目录的 EXCEL 副本。
  useEffect(() => { if (selectedDirId) onDirectoryChange?.(selectedDirId); }, [selectedDirId]);

  const normalComps = components.filter(c => c.componentType === 'NORMAL');
  const excelComps = components.filter(c => c.componentType === 'EXCEL');
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

            {/* EXCEL components */}
            <div className="tm-panel-section">
              <div className="tm-panel-title" style={{ borderBottomColor: '#08979c' }}>EXCEL 组件</div>
              {excelComps.length === 0 ? (
                <div style={{ color: '#999', fontSize: 12 }}>暂无 EXCEL 组件</div>
              ) : (
                excelComps.map(comp => (
                  <DraggableComponentCard key={comp.id} comp={comp} type="excel" />
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
