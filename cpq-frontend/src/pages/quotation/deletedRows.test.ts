import { describe, it, expect } from 'vitest';
import { rowFingerprint, keepRow, type Tombstone } from './deletedRows';

describe('deletedRows', () => {
  it('fingerprint 键序无关', () => {
    const a = rowFingerprint(['料件'], { 料件: 'P1', 单价: 7.12, 启用: true });
    const b = rowFingerprint(['料件'], { 启用: true, 单价: 7.12, 料件: 'P1' });
    expect(a).toBe(b);
  });

  it('number 去尾零', () => {
    expect(rowFingerprint([], { x: 7.10 })).toBe(rowFingerprint([], { x: 7.1 }));
  });

  it('与后端对拍向量一致', () => {
    // 后端 rowFingerprint(["料件"], {"料件":"P1","单价":7.12})：
    // 升序键 [单价, 料件]（单 U+5355 < 料 U+6599）→ parts = ["P1"(keyName段), "7.12"(单价), "P1"(料件)]
    expect(rowFingerprint(['料件'], { 料件: 'P1', 单价: 7.12 }))
      .toBe(['P1', '7.12', 'P1'].join(''));
  });

  it('keepRow 双命中才删', () => {
    const del: Tombstone[] = [{ effKey: 'K2', fp: 'fpB' }];
    expect(keepRow('K2', 'fpA', del)).toBe(true);   // effKey 命中 fp 不命中
    expect(keepRow('K2', 'fpB', del)).toBe(false);  // 双命中
    expect(keepRow('K1', 'fpB', del)).toBe(true);
  });

  // 额外夹具 1：撞键删中间剩余键不变
  it('撞键删中间，其余行不受影响', () => {
    // 每个 effKey 配一个独立的 fp（简化为 effKey 直接当 fp）
    const tombstones: Tombstone[] = [{ effKey: 'P1#2', fp: 'fp-P1#2' }];
    const keys = ['P1#1', 'P1#2', 'P1#3', 'P2', 'P3'];
    const results = keys.map((k) => keepRow(k, `fp-${k}`, tombstones));
    // 只有 P1#2 被删（keepRow=false），其余全 true
    expect(results).toEqual([true, false, true, true, true]);
  });

  // 额外夹具 2：删后源集增 1 行 fp 不同，墓碑不误命中
  it('新行 fp 与被删行不同，墓碑不误命中', () => {
    const oldRow = { 料件: 'P1', 单价: 100.0 };
    const newRow = { 料件: 'P1', 单价: 200.0 };
    const oldFp = rowFingerprint(['料件'], oldRow);
    const newFp = rowFingerprint(['料件'], newRow);
    const tombstones: Tombstone[] = [{ effKey: 'P1', fp: oldFp }];

    expect(oldFp).not.toBe(newFp);
    expect(keepRow('P1', newFp, tombstones)).toBe(true);
  });
});
