import { describe, it, expect } from 'vitest';
import { extractSourceRefs, topoOrderComponents } from './crossTabOrder';

describe('crossTabOrder', () => {
  it('extract refs', () => {
    const formulas = [{ expression: [{ type: 'cross_tab_ref', source: 'A' }] }];
    expect(extractSourceRefs(formulas)).toEqual(['A']);
  });
  it('extract refs dedupes and ignores non-cross_tab tokens', () => {
    const formulas = [
      { expression: [{ type: 'field', value: 'x' }, { type: 'cross_tab_ref', source: 'A' }] },
      { expression: [{ type: 'cross_tab_ref', source: 'A' }, { type: 'cross_tab_ref', source: 'B' }] },
    ];
    expect(extractSourceRefs(formulas).sort()).toEqual(['A', 'B']);
  });
  it('extract refs handles empty/undefined', () => {
    expect(extractSourceRefs(undefined)).toEqual([]);
    expect(extractSourceRefs([])).toEqual([]);
  });
  it('topo A before B', () => {
    const order = topoOrderComponents(['B', 'A'], { B: ['A'] });
    expect(order.indexOf('A')).toBeLessThan(order.indexOf('B'));
  });
  it('topo no deps keeps input order', () => {
    expect(topoOrderComponents(['A', 'B', 'C'], {})).toEqual(['A', 'B', 'C']);
  });
  it('topo chain C->B->A yields A,B,C', () => {
    expect(topoOrderComponents(['C', 'B', 'A'], { C: ['B'], B: ['A'] })).toEqual(['A', 'B', 'C']);
  });
  it('cycle throws', () => {
    expect(() => topoOrderComponents(['A', 'B'], { A: ['B'], B: ['A'] })).toThrow();
  });
  it('dep not in ids is ignored (no constraint, no crash)', () => {
    expect(topoOrderComponents(['A'], { A: ['Z'] })).toEqual(['A']);
  });
});
