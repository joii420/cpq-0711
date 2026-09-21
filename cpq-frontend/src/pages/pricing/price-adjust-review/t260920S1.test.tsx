/**
 * task-260920 · 测试片 S-1 · 前端组件测试（AC-7 ②、AC-11 ②、AC-25 ②、AC-27 ②）。
 * 断言取自 需求文档 ③ 的 AC 原文；被测符号只按导出签名使用（签名级读取，未读渲染逻辑 / 方法体）。
 * 环境：仓库无 jsdom / testing-library ⇒ 与既有 reviewUi.test.tsx 同一约定，用 renderToStaticMarkup（SSR）+ 纯函数。
 * SSR 局限：antd Tooltip / Modal / Drawer 的浮层内容不输出 ⇒「悬停可见原因」「确认框最上方」这类位置 / 浮层断言本文件覆盖不到，
 * 在回报里列为未覆盖项。每条都带阳性对照（证明观测手段能看到被断言「没有」的东西）。
 */
import { describe, it, expect } from 'vitest';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import ReviewStatusCell from './ReviewStatusCell';
import { FailedMaterialsBlock, partitionAfterCompute } from './ApproveImpactModal';
import { DrawerFailedSection } from './ReviewDetailDrawer';
import {
  approveEnabledWhen, computeNowAndWait, drawerComputeMode, nextListPollDelay, rejectEnabledWhen, runSequentialCompute,
  type ComputeOutcome,
} from './reviewCompute';
import type { BudgetStatus, ReviewRowDTO, ReviewStatus } from '../../../types/price-adjust';

const REASON = '判断依据单已不在活单范围内';

function row(id: string, budgetStatus: BudgetStatus, reviewStatus: ReviewStatus = 'PENDING', over: Partial<ReviewRowDTO> = {}): ReviewRowDTO {
  return {
    reviewId: id, customerNo: 'T260920-C', customerName: 'T260920', materialNo: 'T260920-M-' + id, materialName: 'N-' + id,
    currentVersionNo: null, targetVersionNo: 'T920V', budgetStatus, budgetError: null, reviewStatus,
    basisQuotationNo: null, basisQuotationDate: null,
    quoteCostCurrent: null, quoteCostAdjusted: null, costingCost: null, diffCurrent: null, diffAdjusted: null,
    columnCount: 1, breachedCount: 0, amberCount: 0, missingCount: 0, staleCount: 0, rowRed: false, ...over,
  };
}
const text = (html: string) => html.replace(/<[^>]+>/g, '');
const cellText = (r: ReviewRowDTO) => text(renderToStaticMarkup(<ReviewStatusCell row={r} onCompute={() => {}} onRecompute={() => {}} />));

/** 模拟后端：compute-now 202 + GET row 依序返回给定终态行；不 sleep（注入即时 sleep）。 */
function fakeBackend(finalRows: Record<string, ReviewRowDTO>) {
  const calls: string[] = [];
  let inFlight = 0;
  let maxInFlight = 0;
  let clock = 0;
  const deps = {
    computeNow: async (id: string) => {
      calls.push('compute:' + id);
      inFlight++;
      maxInFlight = Math.max(maxInFlight, inFlight);
      return { reviewId: id, budgetStatus: 'COMPUTING' as BudgetStatus };
    },
    getReviewRow: async (id: string) => {
      calls.push('row:' + id);
      await Promise.resolve();
      const r = finalRows[id];
      if (r.budgetStatus === 'READY' || r.budgetStatus === 'FAILED') inFlight--;
      return r;
    },
    sleep: async () => { clock += 1000; },
    now: () => clock,
  };
  return { deps, calls, stats: () => ({ maxInFlight }) };
}

// ═══════════════════════════ AC-7 ② ═══════════════════════════
describe('AC-7 ② 依据单失效 → 计算失败', () => {
  it('列表行显示「预算失败」；不显示「未计算」', () => {
    const t = cellText(row('1', 'FAILED', 'PENDING', { budgetError: REASON }));
    expect(t).toContain('预算失败');
    expect(t).not.toContain('未计算');
  });
  it('抽屉对 FAILED 行不自动重算（打开时不发 compute-now）；阳性对照：QUEUED 行自动算', () => {
    expect(drawerComputeMode('PENDING', 'FAILED')).not.toBe('compute');
    expect(drawerComputeMode('PENDING', 'FAILED')).not.toBe('waitOnly');
    expect(drawerComputeMode('PENDING', 'QUEUED')).toBe('compute');
  });
  it('抽屉失败区块含「重新计算」按钮', () => {
    const t = text(renderToStaticMarkup(<DrawerFailedSection onRecompute={() => {}} />));
    expect(t).toContain('重新计算');
  });
  it('点「重新计算」后走 compute-now，结果为 FAILED 行时不被当成成功（原因原样带回）', async () => {
    const be = fakeBackend({ '1': row('1', 'FAILED', 'PENDING', { budgetError: REASON }) });
    const o = await computeNowAndWait('1', be.deps, { mode: 'compute' });
    expect(be.calls[0]).toBe('compute:1');
    expect(o.kind).toBe('DONE');
    if (o.kind === 'DONE') {
      expect(o.row.budgetStatus).toBe('FAILED');
      expect(o.row.budgetError).toBe(REASON);
    }
  });
});

// ═══════════════════════════ AC-11 ② ═══════════════════════════
describe('AC-11 ② 批量计算中 1 条失败', () => {
  const rows = [row('1', 'QUEUED'), row('2', 'QUEUED'), row('3', 'QUEUED')];
  const finals = {
    '1': row('1', 'READY'),
    '2': row('2', 'FAILED', 'PENDING', { budgetError: REASON }),
    '3': row('3', 'READY'),
  };

  it('逐条串行计算（在途 ≤1，调用次序 1→2→3）；失败行进「计算失败」块，提交集合不含失败行', async () => {
    const be = fakeBackend(finals);
    const states: string[] = [];
    const outcomes = await runSequentialCompute(rows, (r) => computeNowAndWait(r.reviewId, be.deps, { mode: 'compute' }),
      (id, st) => states.push(id + ':' + st));
    expect(outcomes).not.toBeNull();
    expect(be.stats().maxInFlight).toBe(1);
    expect(be.calls.filter((c) => c.startsWith('compute:'))).toEqual(['compute:1', 'compute:2', 'compute:3']);
    const { ready, failures } = partitionAfterCompute(rows, outcomes as Map<string, ComputeOutcome>);
    expect(ready.map((r) => r.reviewId).sort()).toEqual(['1', '3']);         // 点确认时提交的 reviewIds 的唯一来源
    expect(failures.length).toBe(1);
    expect(failures[0].materialNo).toBe('T260920-M-2');
    expect(failures[0].reason).toContain(REASON);
    const t = text(renderToStaticMarkup(<FailedMaterialsBlock failures={failures} selectedCount={3} readyCount={ready.length} />));
    expect(t).toContain('1 个料号计算失败，不参与本次升版');
    expect(t).toContain('T260920-M-2');
    expect(t).toContain(REASON);
  });

  it('阳性对照①：全部成功 ⇒ 无失败、三行都进提交集合（证明分区不是恒剔除）', async () => {
    const be = fakeBackend({ '1': row('1', 'READY'), '2': row('2', 'READY'), '3': row('3', 'READY') });
    const outcomes = await runSequentialCompute(rows, (r) => computeNowAndWait(r.reviewId, be.deps, { mode: 'compute' }), () => {});
    const { ready, failures } = partitionAfterCompute(rows, outcomes as Map<string, ComputeOutcome>);
    expect(failures).toEqual([]);
    expect(ready.length).toBe(3);
  });

  it('阳性对照②：并发一次全发时在途计数能看到 >1（证明「在途 ≤1」的观测手段有效）', async () => {
    const be = fakeBackend(finals);
    await Promise.all(rows.map((r) => computeNowAndWait(r.reviewId, be.deps, { mode: 'compute' })));
    expect(be.stats().maxInFlight).toBeGreaterThan(1);
  });
});

// ═══════════════════════════ AC-25 ② ═══════════════════════════
describe('AC-25 ② 驳回置灰与按原因区分的提示；通过并升版仍可点', () => {
  it('含未计算（QUEUED + COMPUTING 共 2 项）', () => {
    const sel = [row('1', 'READY'), row('2', 'QUEUED'), row('3', 'COMPUTING')];
    expect(rejectEnabledWhen(sel)).toBe('含 2 项尚未计算金额，需先计算才能驳回');
  });
  it('含未计算（只有 QUEUED）⇒ 驳回置灰，「通过并升版」可点', () => {
    const sel = [row('1', 'READY'), row('2', 'QUEUED')];
    expect(rejectEnabledWhen(sel)).toBe('含 1 项尚未计算金额，需先计算才能驳回');
    expect(approveEnabledWhen(sel)).toBe(true);
  });
  it('含计算失败 ⇒ 驳回置灰 + 失败提示，「通过并升版」可点', () => {
    const sel = [row('1', 'READY'), row('2', 'FAILED', 'PENDING', { budgetError: REASON })];
    expect(rejectEnabledWhen(sel)).toBe('含 1 项计算失败，不能驳回；可点『重新计算』，依据单已失效的将在下一版生成时自动处理');
    expect(approveEnabledWhen(sel)).toBe(true);
  });
  it('阳性对照：全部 READY ⇒ 驳回可点（证明置灰不是恒真）', () => {
    expect(rejectEnabledWhen([row('1', 'READY'), row('2', 'READY')])).toBe(true);
  });
});

// ═══════════════════════════ AC-27 ② ═══════════════════════════
describe('AC-27 ② 已作废 + QUEUED 的行', () => {
  it('不渲染「未计算 / 计算」「计算中」', () => {
    for (const b of ['QUEUED', 'COMPUTING'] as BudgetStatus[]) {
      const t = cellText(row('1', b, 'VOIDED'));
      expect(t).not.toContain('未计算');
      expect(t).not.toContain('计算');
    }
  });
  it('阳性对照：同样的 QUEUED 行在待处理时渲染「未计算」「计算」', () => {
    const t = cellText(row('1', 'QUEUED', 'PENDING'));
    expect(t).toContain('未计算');
    expect(t).toContain('计算');
  });

  /** 按 nextListPollDelay 模拟 15 分钟的调度，数列表请求次数（不计首次加载）。 */
  function listRequestsIn(ms: number, statusFilter: ReviewStatus, rows: ReviewRowDTO[], notComputedTotal: number) {
    let t = 0;
    let n = 0;
    for (;;) {
      const d = nextListPollDelay({ statusFilter, rows, notComputedTotal, hidden: false });
      if (d == null || t + d > ms) return n;
      t += d;
      n++;
    }
  }
  it('筛选「已作废」时 F-3 轮询不发请求（15 分钟内 0 次）', () => {
    const rows = [row('1', 'QUEUED', 'VOIDED'), row('2', 'COMPUTING', 'VOIDED')];
    expect(nextListPollDelay({ statusFilter: 'VOIDED', rows, notComputedTotal: 5, hidden: false })).toBeNull();
    expect(listRequestsIn(15 * 60_000, 'VOIDED', rows, 5)).toBe(0);
  });
  it('阳性对照：待处理 + COMPUTING 时确实轮询（15 分钟内 > 0 次）', () => {
    const rows = [row('1', 'COMPUTING', 'PENDING')];
    expect(nextListPollDelay({ statusFilter: 'PENDING', rows, notComputedTotal: 1, hidden: false })).not.toBeNull();
    expect(listRequestsIn(15 * 60_000, 'PENDING', rows, 1)).toBeGreaterThan(0);
  });
});
