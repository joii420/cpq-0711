import { describe, it, expect } from 'vitest';
import { evalCondTree } from './condTree';
import cases from './__fixtures__/condtree-cases.json';

describe('evalCondTree 对账样本', () => {
  for (const c of (cases as any).cases) {
    it(c.name, () => {
      const lookup = (col: string) => {
        const value = (c.values as any)[col];
        return typeof value === 'number' ? String(value) : value;
      };
      expect(evalCondTree(c.tree, lookup)).toBe(c.expected);
    });
  }
});
