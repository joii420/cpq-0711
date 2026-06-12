import { describe, it, expect } from 'vitest';
import { buildColumnPath } from './ViewColumnPickerBody';

describe('buildColumnPath', () => {
  it('拼 $视图.列', () => expect(buildColumnPath('cp_view', '品名')).toBe('$cp_view.品名'));
  it('英文列名', () => expect(buildColumnPath('v_cost', 'unit_price')).toBe('$v_cost.unit_price'));
});
