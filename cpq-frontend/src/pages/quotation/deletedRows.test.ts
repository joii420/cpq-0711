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

  // ── repair-0727 改动 B：nodeId 维度（api.md §2.2） ────────────────────────────

  it('新墓碑区分同 fp 不同 nodeId：只删 nodeId 命中的那一行，另一行保留', () => {
    // 992 挂两父：S-3120014539 / S-80011，driverRow 内容假设完全相同(同 fp) —— 树行真实场景中
    // driverRow 本身可能已含 parent_no 列区分 fp（§11.2 实测），但本用例专门模拟"driverRow 相同、
    // 仅结构位置不同"的边界场景，验证 nodeId 维度独立生效。
    const fp = 'fp-992';
    const tomb: Tombstone = { effKey: 'S-3120014539/992::4', fp, nodeId: 'S-3120014539/992' };
    // 命中：fp 相同 + nodeId 相同 → 删
    expect(keepRow('any', fp, [tomb], 'S-3120014539/992')).toBe(false);
    // 不命中：fp 相同但 nodeId 不同（另一个父）→ 保留
    expect(keepRow('any', fp, [tomb], 'S-80011/992')).toBe(true);
  });

  it('旧墓碑（无 nodeId）× 树行：退化为 fp 单键，存量单据行为不变', () => {
    const fp = 'fp-legacy';
    const tomb: Tombstone = { effKey: 'K', fp }; // 无 nodeId（存量墓碑）
    // 即便调用方传了 nodeId，旧墓碑无 nodeId → 退化 fp 单键，命中即删
    expect(keepRow('K', fp, [tomb], 'S-80011/992')).toBe(false);
    expect(keepRow('K', fp, [tomb], undefined)).toBe(false);
  });

  it('任意墓碑 × 非树行（调用方不传 nodeId）：fp 单键，逐字节不变', () => {
    const fp = 'fp-flat';
    // 墓碑本身带 nodeId（例如误写），但非树行调用方不传 nodeId → 仍按 fp 单键命中
    const tomb: Tombstone = { effKey: 'K', fp, nodeId: 'S-3120014539/992' };
    expect(keepRow('K', fp, [tomb])).toBe(false); // 不传 nodeId，旧签名兼容
    expect(keepRow('K', fp, [tomb], null)).toBe(false); // 显式 null 同样退化
  });

  it('新墓碑 × 非树行：fp 命中即删（!nodeId 分支覆盖调用方未传）', () => {
    const fp = 'fp-mix';
    const tomb: Tombstone = { effKey: 'K', fp, nodeId: 'S-3120014539/992' };
    expect(keepRow('K', fp, [tomb])).toBe(false);
  });
});
