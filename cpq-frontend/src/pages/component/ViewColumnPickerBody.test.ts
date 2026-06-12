import { describe, it, expect } from 'vitest';
import { buildColumnPath, buildViewColumnPath } from './ViewColumnPickerBody';
import type { ComponentSqlView } from '../../services/componentSqlViewService';

describe('buildColumnPath', () => {
  it('拼 $视图.列', () => expect(buildColumnPath('cp_view', '品名')).toBe('$cp_view.品名'));
  it('英文列名', () => expect(buildColumnPath('v_cost', 'unit_price')).toBe('$v_cost.unit_price'));
});

const mkView = (over: Partial<ComponentSqlView>): ComponentSqlView => ({
  id: 'v1', componentId: 'cA', componentCode: 'CODE_A', sqlViewName: 'cp_view',
  sqlTemplate: '', declaredColumns: [], requiredVariables: [], scope: 'COMPONENT',
  status: 'ACTIVE', updatedAt: '', ...over,
});

describe('buildViewColumnPath', () => {
  it('本组件 COMPONENT 视图 → 单 $', () => {
    const v = mkView({ scope: 'COMPONENT', componentId: 'cA' });
    expect(buildViewColumnPath(v, '品名', 'cA')).toBe('$cp_view.品名');
  });
  it('跨组件 GLOBAL 视图（不属当前组件）→ $$componentCode.view.col', () => {
    const v = mkView({ scope: 'GLOBAL', componentId: 'cB', componentCode: 'CODE_B' });
    expect(buildViewColumnPath(v, '单价', 'cA')).toBe('$$CODE_B.cp_view.单价');
  });
  it('GLOBAL 但就是当前组件自己 → 单 $（非跨组件）', () => {
    const v = mkView({ scope: 'GLOBAL', componentId: 'cA' });
    expect(buildViewColumnPath(v, 'x', 'cA')).toBe('$cp_view.x');
  });
  it('effectiveComponentId 缺省时 GLOBAL 视图按跨组件处理（$$）', () => {
    const v = mkView({ scope: 'GLOBAL', componentId: 'cB', componentCode: 'CODE_B' });
    expect(buildViewColumnPath(v, 'x', undefined)).toBe('$$CODE_B.cp_view.x');
  });
  it('GLOBAL 缺 componentCode 回退 componentId', () => {
    const v = mkView({ scope: 'GLOBAL', componentId: 'cB', componentCode: undefined });
    expect(buildViewColumnPath(v, 'x', 'cA')).toBe('$$cB.cp_view.x');
  });
});
