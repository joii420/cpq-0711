/**
 * task-260920 · frontend component-layer tests (fronttask 自检 3), pure logic part.
 * Assertions derive from 需求文档 ③ AC-1/5/7/8/11/20/25/27 wording + api.md v2.2 shapes.
 * No jsdom/RTL in this repo (see src/pages/__tests__/task260902ExportToolbar.test.tsx header),
 * so the decision logic lives in reviewCompute.ts and is exercised here directly.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  approveEnabledWhen, approveHint, businessCodeOf, computeNowAndWait, drawerComputeMode,
  nextListPollDelay, rejectEnabledWhen, rowsNeedingCompute, runSequentialCompute,
  POLL_COMPUTING_MS, POLL_QUEUED_MS, COMPUTE_POLL_TIMEOUT_MS,
  type ComputeOutcome,
} from './reviewCompute';
import { buildApiError } from '../../../services/api';
import type { BudgetStatus, ReviewRowDTO, ReviewStatus } from '../../../types/price-adjust';

function row(id: string, budgetStatus: BudgetStatus, reviewStatus: ReviewStatus = 'PENDING', over: Partial<ReviewRowDTO> = {}): ReviewRowDTO {
  return {
    reviewId: id, customerNo: 'CUST-0004', customerName: '正泰', materialNo: 'M-' + id, materialName: 'N-' + id,
    currentVersionNo: null, targetVersionNo: 'V26092003', budgetStatus, budgetError: null, reviewStatus,
    basisQuotationNo: 'QT-20260911-0842', basisQuotationDate: '2026-09-11',
    quoteCostCurrent: null, quoteCostAdjusted: null, costingCost: null, diffCurrent: null, diffAdjusted: null,
    columnCount: 1, breachedCount: 0, amberCount: 0, missingCount: 0, staleCount: 0, rowRed: false, ...over,
  };
}

/** Build the exact error object the axios interceptor produces for a 409 ReviewNotReadyException. */
function envelope409(code: string) {
  return buildApiError({
    response: { status: 409, data: { code: 409, message: '该料号已不是待处理状态', data: { code, invalidItems: [] } } },
  });
}

// ─────────────────────────── F-4 guards (AC-8 ① / AC-25 ②) ───────────────────────────

describe('F-4 · 驳回守门 rejectEnabledWhen（D-6 / D-11 / AC-25）', () => {
  it('未选 → false（通用「请先选择行」）', () => expect(rejectEnabledWhen([])).toBe(false));
  it('全部 READY 待处理 → 可点', () => expect(rejectEnabledWhen([row('a', 'READY'), row('b', 'READY')])).toBe(true));
  it('含非待处理 → 原样文案', () =>
    expect(rejectEnabledWhen([row('a', 'READY'), row('b', 'READY', 'APPROVED')])).toBe('含 1 项非「待处理」状态，不能操作'));
  it('AC-8：3 未计算 + 2 已计算 → 「含 3 项尚未计算金额，需先计算才能驳回」', () =>
    expect(rejectEnabledWhen([row('1', 'QUEUED'), row('2', 'QUEUED'), row('3', 'QUEUED'), row('4', 'READY'), row('5', 'READY')]))
      .toBe('含 3 项尚未计算金额，需先计算才能驳回'));
  it('COMPUTING 也算「尚未计算金额」', () =>
    expect(rejectEnabledWhen([row('1', 'COMPUTING'), row('2', 'READY')])).toBe('含 1 项尚未计算金额，需先计算才能驳回'));
  it('AC-25：含计算失败 → 失败专用文案（AC 原文逐字）', () =>
    expect(rejectEnabledWhen([row('1', 'FAILED'), row('2', 'FAILED'), row('3', 'READY')]))
      .toBe('含 2 项计算失败，不能驳回；可点『重新计算』，依据单已失效的将在下一版生成时自动处理'));
  it('同时含未计算与失败 → 先报未计算（它是可以等来的，失败要人处理）', () =>
    expect(rejectEnabledWhen([row('1', 'FAILED'), row('2', 'QUEUED')])).toBe('含 1 项尚未计算金额，需先计算才能驳回'));
});

describe('F-4 · 通过守门 approveEnabledWhen + 旁边提示（AC-8 ① / AC-25「此时通过仍可点」）', () => {
  it('未选 → false', () => expect(approveEnabledWhen([])).toBe(false));
  it('含非待处理 → 置灰原样', () =>
    expect(approveEnabledWhen([row('a', 'READY', 'VOIDED')])).toBe('含 1 项非「待处理」状态，不能操作'));
  it('含 COMPUTING → 置灰「有 n 项正在计算，请稍候」', () =>
    expect(approveEnabledWhen([row('a', 'COMPUTING'), row('b', 'QUEUED')])).toBe('有 1 项正在计算，请稍候'));
  it('AC-8：3 QUEUED + 2 READY → 可点，提示 n=3', () => {
    const sel = [row('1', 'QUEUED'), row('2', 'QUEUED'), row('3', 'QUEUED'), row('4', 'READY'), row('5', 'READY')];
    expect(approveEnabledWhen(sel)).toBe(true);
    expect(approveHint(sel)).toBe(3);
  });
  it('AC-25：含 FAILED → 通过仍可点（驳回此时置灰），FAILED 也计入「先计算」', () => {
    const sel = [row('1', 'FAILED'), row('2', 'READY')];
    expect(approveEnabledWhen(sel)).toBe(true);
    expect(rejectEnabledWhen(sel)).not.toBe(true);
    expect(approveHint(sel)).toBe(1);
    expect(rowsNeedingCompute(sel).map((r) => r.reviewId)).toEqual(['1']);
  });
  it('全 READY → 可点且无提示', () => expect(approveHint([row('1', 'READY')])).toBeNull());
  it('按钮置灰时不出提示', () => expect(approveHint([row('1', 'COMPUTING'), row('2', 'QUEUED')])).toBeNull());
});

// ─────────────────────────── F-4 sequential (J-2) ───────────────────────────

describe('F-4 · 批量先算是逐条串行（J-2，🚫 一次全发）', () => {
  it('第 n+1 条的 compute-now 只在第 n 条结束后才发出', async () => {
    const log: string[] = [];
    const resolvers: Record<string, (o: ComputeOutcome) => void> = {};
    let inFlight = 0;
    let maxInFlight = 0;
    const rows = [row('1', 'QUEUED'), row('2', 'QUEUED'), row('3', 'FAILED')];
    const compute = (r: ReviewRowDTO) => new Promise<ComputeOutcome>((res) => {
      inFlight++; maxInFlight = Math.max(maxInFlight, inFlight);
      log.push('start:' + r.reviewId);
      resolvers[r.reviewId] = (o) => { inFlight--; log.push('end:' + r.reviewId); res(o); };
    });
    const states: string[] = [];
    const p = runSequentialCompute(rows, compute, (id, st) => states.push(`${id}:${st}`));
    await Promise.resolve();
    expect(log).toEqual(['start:1']); // 2 and 3 not sent yet
    resolvers['1']({ kind: 'DONE', row: row('1', 'READY') });
    await new Promise((r) => setTimeout(r, 0));
    expect(log).toEqual(['start:1', 'end:1', 'start:2']);
    resolvers['2']({ kind: 'DONE', row: row('2', 'FAILED', 'PENDING', { budgetError: '预算试算超时（超过 60 秒）' }) });
    await new Promise((r) => setTimeout(r, 0));
    resolvers['3']({ kind: 'DONE', row: row('3', 'READY') });
    const out = await p;
    expect(maxInFlight).toBe(1);
    expect(log).toEqual(['start:1', 'end:1', 'start:2', 'end:2', 'start:3', 'end:3']);
    expect(states).toEqual(['1:COMPUTING', '1:DONE', '2:COMPUTING', '2:FAILED', '3:COMPUTING', '3:DONE']);
    expect(out!.size).toBe(3);
  });
  it('取消后不再发下一条', async () => {
    const signal = { aborted: false };
    const calls: string[] = [];
    const out = await runSequentialCompute([row('1', 'QUEUED'), row('2', 'QUEUED')], async (r) => {
      calls.push(r.reviewId);
      signal.aborted = true;
      return { kind: 'DONE', row: row(r.reviewId, 'READY') };
    }, () => {}, signal);
    expect(out).toBeNull();
    expect(calls).toEqual(['1']);
  });
});

// ─────────────────────────── F-2 compute-now + poll ───────────────────────────

function fakeClock() {
  let t = 0;
  return { now: () => t, sleep: async (ms: number) => { t += ms; } };
}

describe('F-2 · 抽屉按状态分支 drawerComputeMode（F-2 ③ / AC-7 / AC-27）', () => {
  it('QUEUED → 自动算', () => expect(drawerComputeMode('PENDING', 'QUEUED')).toBe('compute'));
  it('COMPUTING → 只轮询等待', () => expect(drawerComputeMode('PENDING', 'COMPUTING')).toBe('waitOnly'));
  it('FAILED → 不自动算（AC-7）', () => expect(drawerComputeMode('PENDING', 'FAILED')).toBe('none'));
  it('READY → 直接显示', () => expect(drawerComputeMode('PENDING', 'READY')).toBe('none'));
  it('已作废 + QUEUED → 不算（AC-27）', () => expect(drawerComputeMode('VOIDED', 'QUEUED')).toBe('none'));
});

describe('F-2 · computeNowAndWait', () => {
  it('QUEUED：POST compute-now 一次 → 每秒轮询 row → READY 结束，返回的行用于就地替换', async () => {
    const c = fakeClock();
    const computeNow = vi.fn().mockResolvedValue({ reviewId: 'a', budgetStatus: 'COMPUTING' });
    const seq: BudgetStatus[] = ['COMPUTING', 'COMPUTING', 'READY'];
    const getReviewRow = vi.fn().mockImplementation(async () => row('a', seq.shift()!, 'PENDING', { quoteCostAdjusted: '1.717061326000' }));
    const o = await computeNowAndWait('a', { computeNow, getReviewRow, ...c }, { mode: 'compute' });
    expect(computeNow).toHaveBeenCalledTimes(1);
    expect(getReviewRow).toHaveBeenCalledTimes(3);
    expect(c.now()).toBe(2000); // 1 s cadence
    expect(o.kind).toBe('DONE');
    expect(o.kind === 'DONE' && o.row.quoteCostAdjusted).toBe('1.717061326000');
  });
  it('202 {budgetStatus:READY}：第一次取 row 即结束', async () => {
    const c = fakeClock();
    const getReviewRow = vi.fn().mockResolvedValue(row('a', 'READY'));
    const o = await computeNowAndWait('a', { computeNow: vi.fn().mockResolvedValue({ reviewId: 'a', budgetStatus: 'READY' }), getReviewRow, ...c }, { mode: 'compute' });
    expect(o.kind).toBe('DONE');
    expect(getReviewRow).toHaveBeenCalledTimes(1);
  });
  it('COMPUTING（waitOnly）：只轮询，从不 POST', async () => {
    const c = fakeClock();
    const computeNow = vi.fn();
    const seq: BudgetStatus[] = ['COMPUTING', 'READY'];
    const o = await computeNowAndWait('a', { computeNow, getReviewRow: async () => row('a', seq.shift()!), ...c }, { mode: 'waitOnly' });
    expect(computeNow).not.toHaveBeenCalled();
    expect(o.kind).toBe('DONE');
  });
  it('FAILED 结束：带回 budgetError（AC-7 抽屉原因来源之一）', async () => {
    const c = fakeClock();
    const o = await computeNowAndWait('a', {
      computeNow: vi.fn().mockResolvedValue({}),
      getReviewRow: async () => row('a', 'FAILED', 'PENDING', { budgetError: '判断依据单已不在活单范围内' }), ...c,
    }, { mode: 'compute' });
    expect(o.kind === 'DONE' && o.row.budgetStatus).toBe('FAILED');
    expect(o.kind === 'DONE' && o.row.budgetError).toBe('判断依据单已不在活单范围内');
  });
  it('AC-20：compute-now 409 且 data.code=REVIEW_NOT_PENDING → 「已被取代」分支，不再轮询', async () => {
    const err = envelope409('REVIEW_NOT_PENDING');
    // guard against the trap: the envelope's top-level code is the INT 409, the business code is in payload
    expect(err.code as unknown).toBe(409);
    expect(businessCodeOf(err)).toBe('REVIEW_NOT_PENDING');
    const getReviewRow = vi.fn();
    const o = await computeNowAndWait('a', { computeNow: vi.fn().mockRejectedValue(err), getReviewRow, ...fakeClock() }, { mode: 'compute' });
    expect(o).toEqual({ kind: 'NOT_PENDING', superseded: true });
    expect(getReviewRow).not.toHaveBeenCalled();
  });
  it('其它 409（REVIEW_BUDGET_NOT_READY）不会被误判成「已被取代」', async () => {
    const o = await computeNowAndWait('a', {
      computeNow: vi.fn().mockRejectedValue(envelope409('REVIEW_BUDGET_NOT_READY')), getReviewRow: vi.fn(), ...fakeClock(),
    }, { mode: 'compute' });
    expect(o.kind).toBe('ERROR');
  });
  it('轮询中看到 reviewStatus=VOIDED → 「已被取代」', async () => {
    const seq: ReviewStatus[] = ['PENDING', 'VOIDED'];
    const o = await computeNowAndWait('a', {
      computeNow: vi.fn().mockResolvedValue({}), getReviewRow: async () => row('a', 'COMPUTING', seq.shift()!), ...fakeClock(),
    }, { mode: 'compute' });
    expect(o.kind === 'NOT_PENDING' && o.superseded).toBe(true);
  });
  it('90 秒未结束 → 停止轮询（TIMEOUT，提示「计算较慢，可稍后刷新」）', async () => {
    const c = fakeClock();
    const getReviewRow = vi.fn().mockImplementation(async () => row('a', 'COMPUTING'));
    const o = await computeNowAndWait('a', { computeNow: vi.fn().mockResolvedValue({}), getReviewRow, ...c }, { mode: 'compute' });
    expect(o.kind).toBe('TIMEOUT');
    expect(c.now()).toBe(COMPUTE_POLL_TIMEOUT_MS);
    expect(getReviewRow).toHaveBeenCalledTimes(91);
  });
});

// ─────────────────────────── F-3 polling cadence (AC-1 / AC-27) ───────────────────────────

describe('F-3 · nextListPollDelay 判定表', () => {
  const base = { statusFilter: 'PENDING' as ReviewStatus, notComputedTotal: 0, hidden: false };
  it('① 本页有 PENDING+COMPUTING → 5 s', () =>
    expect(nextListPollDelay({ ...base, rows: [row('1', 'COMPUTING'), row('2', 'QUEUED')] })).toBe(POLL_COMPUTING_MS));
  it('② 只有 QUEUED → 30 s', () => expect(nextListPollDelay({ ...base, rows: [row('1', 'QUEUED')] })).toBe(POLL_QUEUED_MS));
  it('② 本页全 READY 但 notComputedTotal>0 → 30 s（只为更新 M）', () =>
    expect(nextListPollDelay({ ...base, rows: [row('1', 'READY')], notComputedTotal: 12 })).toBe(POLL_QUEUED_MS));
  it('全部算完 → 不轮询', () => expect(nextListPollDelay({ ...base, rows: [row('1', 'READY')] })).toBeNull());
  it('③ 页面不可见 → 暂停', () => expect(nextListPollDelay({ ...base, hidden: true, rows: [row('1', 'COMPUTING')] })).toBeNull());
  it.each(['APPROVED', 'REJECTED', 'VOIDED'] as ReviewStatus[])('④ 筛选=%s → 不轮询（即便行的 budgetStatus 仍是 QUEUED/COMPUTING）', (st) =>
    expect(nextListPollDelay({ ...base, statusFilter: st, rows: [row('1', 'QUEUED', st), row('2', 'COMPUTING', st)], notComputedTotal: 99 })).toBeNull());
});

/**
 * F-3 「15 分钟内的列表请求次数」统计（vitest fake timers）：
 * 复刻页面 useEffect 的调度方式 —— 每次列表刷新后按 nextListPollDelay 重新挂一个 setTimeout，
 * 服务端 15 分钟内一直返回「20 行全 QUEUED、notComputedTotal=4510」（生成后的最坏开局）。
 * 真实浏览器口径（page.clock 驱动真实组件）另见回报 F-3 证据。
 */
function simulate15min(statusFilter: ReviewStatus, rowStatus: BudgetStatus) {
  vi.useFakeTimers();
  let requests = 0;
  const serve = () => {
    requests++;
    const rows = Array.from({ length: 20 }, (_, i) => row(String(i), rowStatus, statusFilter));
    const delay = nextListPollDelay({ statusFilter, rows, notComputedTotal: statusFilter === 'PENDING' ? 4510 : 0, hidden: false });
    if (delay != null) setTimeout(serve, delay);
  };
  serve(); // initial load
  vi.advanceTimersByTime(15 * 60 * 1000);
  return requests - 1; // requests during the 15 minutes, excluding the initial load
}

describe('F-3 · 15 分钟列表请求次数（fake timers）', () => {
  afterEach(() => vi.useRealTimers());
  it('全 QUEUED：15 分钟 30 次（改动前为每 5 秒一次 = 180 次）', () => expect(simulate15min('PENDING', 'QUEUED')).toBe(30));
  it('AC-27：筛选=已作废（行仍是 QUEUED）→ 0 次', () => expect(simulate15min('VOIDED', 'QUEUED')).toBe(0));
});
