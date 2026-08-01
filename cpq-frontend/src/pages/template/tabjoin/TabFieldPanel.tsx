import React, { useMemo, useState } from 'react';
import { Input } from 'antd';
import type { TabDef } from '../../../services/tabJoinFormulaService';
import TabFieldMatrix from './TabFieldMatrix';

// ──────────────────────────────────────────────
// Props
// ──────────────────────────────────────────────
interface Props {
  tabDefs: TabDef[];
  selfRowKeyFields?: string[];
  onInsert: (token: string) => void;
  /** F4 批次先透传给 TabFieldMatrix；F5 会把「清空表达式」按钮挪到右栏后改走此 prop */
  onClearExpression?: () => void;
}

// ──────────────────────────────────────────────
// 纯前端搜索过滤（AC-16 / AC-17，需求说明 §4.2.5）
// ──────────────────────────────────────────────
/**
 * 关键词 trim + 小写后与 componentName / alias / 各明细&小计 chip 名做 includes：
 *   ① 命中页签名（componentName 或 alias）→ 整卡保留，所有 chip 都在
 *   ② 仅命中字段名 → 该卡保留，但 detailFields / subtotalCols 只留命中项
 *      （「页签总计」chip 与 detailFields/subtotalCols 无关，TabFieldMatrix 只要卡片保留就始终渲染该 chip，
 *      故此函数无需为它做任何特殊处理即可满足"始终保留"的要求）
 *   ③ 都不命中 → 整卡剔除
 * 纯函数、不产生副作用，不修改入参 tabDefs 数组或其内部对象（返回全新数组 + 全新 def 副本）。
 */
function filterTabDefs(tabDefs: TabDef[], keyword: string): TabDef[] {
  const kw = keyword.trim().toLowerCase();
  if (!kw) {
    return tabDefs.map((def) => ({ ...def }));
  }

  const result: TabDef[] = [];
  for (const def of tabDefs) {
    const nameHit =
      (def.componentName ?? '').toLowerCase().includes(kw) ||
      (def.alias ?? '').toLowerCase().includes(kw);
    if (nameHit) {
      result.push({ ...def });
      continue;
    }

    const detailFields = (def.detailFields ?? []).filter((f) => f.toLowerCase().includes(kw));
    const subtotalCols = (def.subtotalCols ?? []).filter((f) => f.toLowerCase().includes(kw));
    if (detailFields.length > 0 || subtotalCols.length > 0) {
      result.push({ ...def, detailFields, subtotalCols });
    }
  }
  return result;
}

// ──────────────────────────────────────────────
// 主组件：左栏整体 = 标题行 + 搜索框 + 宿主行键状态条（由 TabFieldMatrix 内部渲染）+ 页签卡片列表
// ──────────────────────────────────────────────
const TabFieldPanel: React.FC<Props> = ({ tabDefs, selfRowKeyFields, onInsert, onClearExpression }) => {
  const [keyword, setKeyword] = useState('');

  const filtered = useMemo(() => filterTabDefs(tabDefs, keyword), [tabDefs, keyword]);
  const isSearching = keyword.trim().length > 0;
  const showNoMatch = isSearching && filtered.length === 0;

  return (
    <div>
      {/* 标题行 */}
      <div style={{ marginBottom: 8 }}>
        <div style={{ fontSize: 13, fontWeight: 600, color: '#1f2329' }}>页签组件与可选字段</div>
        <div style={{ fontSize: 12, color: '#8a909a', marginTop: 2 }}>
          点击插入到右侧公式 · 行键不可比的明细置灰
        </div>
      </div>

      {/* 搜索框 */}
      <Input
        allowClear
        size="small"
        placeholder="搜索页签或字段名"
        value={keyword}
        onChange={(e) => setKeyword(e.target.value)}
        style={{ marginBottom: 10 }}
      />

      {showNoMatch ? (
        <div style={{ padding: '12px 0', color: '#8a909a', fontSize: 12 }}>
          无匹配的页签或字段
        </div>
      ) : (
        <TabFieldMatrix
          tabDefs={filtered}
          selfRowKeyFields={selfRowKeyFields}
          onInsert={onInsert}
          onClearExpression={onClearExpression}
        />
      )}
    </div>
  );
};

export default TabFieldPanel;
