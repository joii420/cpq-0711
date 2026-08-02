import { describe, it, expect } from 'vitest';
import { buildMaterialVersionLabel } from './materialVersionLabel';

describe('buildMaterialVersionLabel（屏7 料号级价格版本表，api.md §4.1 / fronttask §6.1）', () => {
  it('UPGRADED：显示当前版本号', () => {
    const r = buildMaterialVersionLabel({ state: 'UPGRADED', currentVersionNo: 'V26080501' });
    expect(r.text).toBe('V26080501');
    expect(r.showsVersionNo).toBe(true);
  });

  it('REJECTED：显示停留版本号 +「未升版」', () => {
    const r = buildMaterialVersionLabel({ state: 'REJECTED', currentVersionNo: 'V26070501' });
    expect(r.text).toBe('V26070501（未升版）');
    expect(r.showsVersionNo).toBe(true);
  });

  it('🔒 NOT_UPDATED：必须显式标「尚未更新」，绝不直接显示已推进的新版本号——即使 currentVersionNo 有值', () => {
    const r = buildMaterialVersionLabel({ state: 'NOT_UPDATED', currentVersionNo: 'V26080501' });
    expect(r.text).toBe('尚未更新');
    expect(r.showsVersionNo).toBe(false);
    // 关键反向断言：文案里绝不能出现版本号本身
    expect(r.text).not.toContain('V26080501');
  });

  it('NOT_PARTICIPATING：显示「未参与调价」', () => {
    const r = buildMaterialVersionLabel({ state: 'NOT_PARTICIPATING', currentVersionNo: null });
    expect(r.text).toBe('未参与调价');
    expect(r.showsVersionNo).toBe(false);
  });
});
