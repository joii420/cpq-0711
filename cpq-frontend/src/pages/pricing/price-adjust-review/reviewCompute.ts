/**
 * task-260920 · review list "compute on demand" — pure logic, no React.
 *
 * Kept free of React / antd so every decision here (guards, polling cadence, the compute-now
 * state machine) is unit-testable under plain vitest (the repo has no jsdom / testing-library).
 * The page, the drawer and the batch-approve flow all call into this ONE module — there is
 * intentionally no second implementation of "compute one row and wait".
 */
import type { ApiError } from '../../../services/api';
import type { BudgetStatus, ComputeNowResponse, ReviewRowDTO, ReviewStatus } from '../../../types/price-adjust';

// ───────────────────────────── compute-now + poll (F-2) ─────────────────────────────

export const COMPUTE_POLL_INTERVAL_MS = 1000;
export const COMPUTE_POLL_TIMEOUT_MS = 90_000;

/** Fixed copy (fronttask F-2 ① / ⑥) — shared by page, drawer and batch flow. */
export const MSG_COMPUTE_SLOW = '计算较慢，可稍后刷新';
export const MSG_SUPERSEDED = '该价格版本已被新版本取代';
export const MSG_NOT_PENDING = '该料号已不是待处理状态';

export type ComputeOutcome =
  /** finished: row.budgetStatus is READY or FAILED, row.reviewStatus is PENDING */
  | { kind: 'DONE'; row: ReviewRowDTO }
  /**
   * the row left PENDING. superseded=true when the version was voided (409 REVIEW_NOT_PENDING,
   * or polling saw reviewStatus=VOIDED) → UI says MSG_SUPERSEDED.
   */
  | { kind: 'NOT_PENDING'; superseded: boolean; row?: ReviewRowDTO }
  /** still not finished after COMPUTE_POLL_TIMEOUT_MS; polling stopped */
  | { kind: 'TIMEOUT'; row?: ReviewRowDTO }
  /** aborted by the caller (drawer closed / batch cancelled) */
  | { kind: 'ABORTED' }
  | { kind: 'ERROR'; message: string };

export interface ComputeDeps {
  computeNow: (reviewId: string) => Promise<ComputeNowResponse>;
  getReviewRow: (reviewId: string) => Promise<ReviewRowDTO>;
  /** injectable for tests (fake timers) */
  sleep?: (ms: number) => Promise<void>;
  now?: () => number;
}

export interface ComputeOptions {
  /**
   * 'compute'  → POST compute-now first (QUEUED rows, or FAILED rows after an explicit
   *              「重新计算」 click), then poll.
   * 'waitOnly' → the row is already COMPUTING: only poll, never POST (F-2 ③).
   */
  mode: 'compute' | 'waitOnly';
  signal?: { aborted: boolean };
  intervalMs?: number;
  timeoutMs?: number;
}

/**
 * 🔒 Branch on the envelope's string code, never on message text (api.md header, F-2 ⑥).
 * The backend envelope is ApiResponse{code:409(int), message, data:{code:'REVIEW_NOT_PENDING', invalidItems}}.
 * buildApiError maps data.data → err.payload, and response.data.code (the INT 409) → err.code —
 * so the business code lives in err.payload.code, NOT err.code.
 */
export function businessCodeOf(err: unknown): string | undefined {
  const payload = (err as ApiError | undefined)?.payload as { code?: unknown } | null | undefined;
  return typeof payload?.code === 'string' ? payload.code : undefined;
}

const defaultSleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

function isFinished(s: BudgetStatus) {
  return s === 'READY' || s === 'FAILED';
}

/**
 * The single "compute one row and wait" routine (fronttask F-2 ①): POST compute-now (always 202),
 * then GET /{id}/row every second until READY/FAILED or reviewStatus≠PENDING; give up after 90 s.
 */
export async function computeNowAndWait(reviewId: string, deps: ComputeDeps, opts: ComputeOptions): Promise<ComputeOutcome> {
  const sleep = deps.sleep ?? defaultSleep;
  const now = deps.now ?? Date.now;
  const interval = opts.intervalMs ?? COMPUTE_POLL_INTERVAL_MS;
  const timeout = opts.timeoutMs ?? COMPUTE_POLL_TIMEOUT_MS;
  const started = now();

  if (opts.mode === 'compute') {
    try {
      await deps.computeNow(reviewId);
    } catch (e) {
      if (businessCodeOf(e) === 'REVIEW_NOT_PENDING') return { kind: 'NOT_PENDING', superseded: true };
      return { kind: 'ERROR', message: (e as Error)?.message || '触发计算失败' };
    }
  }

  let last: ReviewRowDTO | undefined;
  // First probe immediately: a 202 {budgetStatus:'READY'} row is served straight away.
  for (;;) {
    if (opts.signal?.aborted) return { kind: 'ABORTED' };
    try {
      last = await deps.getReviewRow(reviewId);
    } catch (e) {
      return { kind: 'ERROR', message: (e as Error)?.message || '查询计算结果失败' };
    }
    if (last.reviewStatus !== 'PENDING') {
      return { kind: 'NOT_PENDING', superseded: last.reviewStatus === 'VOIDED', row: last };
    }
    if (isFinished(last.budgetStatus)) return { kind: 'DONE', row: last };
    if (now() - started >= timeout) return { kind: 'TIMEOUT', row: last };
    await sleep(interval);
    if (opts.signal?.aborted) return { kind: 'ABORTED' };
  }
}

/** F-2 ③: which action opening a row implies. Non-PENDING rows are never computed (AC-27 / R-2). */
export function drawerComputeMode(reviewStatus: ReviewStatus | undefined, budgetStatus: BudgetStatus | undefined):
  'compute' | 'waitOnly' | 'none' {
  if (reviewStatus !== 'PENDING') return 'none';
  if (budgetStatus === 'QUEUED') return 'compute';
  if (budgetStatus === 'COMPUTING') return 'waitOnly';
  return 'none'; // READY → show directly; FAILED → no auto recompute, drawer offers「重新计算」
}

// ───────────────────────────── toolbar guards (F-4) ─────────────────────────────

const isPending = (r: ReviewRowDTO) => r.reviewStatus === 'PENDING';

/**
 * 「驳回」: unchanged "all PENDING and all READY" (D-6 / D-11), but the disabled reason now
 * differs by cause (AC-25 wording, verbatim from 需求文档 ③).
 */
export function rejectEnabledWhen(selected: ReviewRowDTO[]): boolean | string {
  if (selected.length === 0) return false;
  const notPending = selected.filter((r) => !isPending(r));
  if (notPending.length > 0) return `含 ${notPending.length} 项非「待处理」状态，不能操作`;
  const notComputed = selected.filter((r) => r.budgetStatus === 'QUEUED' || r.budgetStatus === 'COMPUTING');
  if (notComputed.length > 0) return `含 ${notComputed.length} 项尚未计算金额，需先计算才能驳回`;
  const failed = selected.filter((r) => r.budgetStatus === 'FAILED');
  if (failed.length > 0) return `含 ${failed.length} 项计算失败，不能驳回；可点『重新计算』，依据单已失效的将在下一版生成时自动处理`;
  return true;
}

/** 「通过并升版」: non-PENDING / COMPUTING block; QUEUED / FAILED are allowed (computed first). */
export function approveEnabledWhen(selected: ReviewRowDTO[]): boolean | string {
  if (selected.length === 0) return false;
  const notPending = selected.filter((r) => !isPending(r));
  if (notPending.length > 0) return `含 ${notPending.length} 项非「待处理」状态，不能操作`;
  const computing = selected.filter((r) => r.budgetStatus === 'COMPUTING');
  if (computing.length > 0) return `有 ${computing.length} 项正在计算，请稍候`;
  return true;
}

/** Rows the approve flow must compute first (F-4: QUEUED / FAILED). */
export function rowsNeedingCompute(selected: ReviewRowDTO[]): ReviewRowDTO[] {
  return selected.filter((r) => isPending(r) && (r.budgetStatus === 'QUEUED' || r.budgetStatus === 'FAILED'));
}

/** Hint next to the approve button; null when not applicable (button disabled, or nothing to compute). */
export function approveHint(selected: ReviewRowDTO[]): number | null {
  if (approveEnabledWhen(selected) !== true) return null;
  const n = rowsNeedingCompute(selected).length;
  return n > 0 ? n : null;
}

// ───────────────────────────── list polling cadence (F-3) ─────────────────────────────

export const POLL_COMPUTING_MS = 5_000;
export const POLL_QUEUED_MS = 30_000;

/**
 * F-3: how long until the next silent list refresh; null = don't poll.
 *  ④ filter is not PENDING (已通过/已驳回/已作废) → never (AC-27)
 *  ③ page hidden → paused
 *  ① a PENDING+COMPUTING row on this page → 5 s
 *  ② otherwise PENDING+QUEUED rows on this page, or notComputedTotal>0 → 30 s (only to refresh M)
 */
export function nextListPollDelay(input: {
  statusFilter: ReviewStatus;
  rows: ReviewRowDTO[];
  notComputedTotal: number;
  hidden: boolean;
}): number | null {
  if (input.statusFilter !== 'PENDING') return null;
  if (input.hidden) return null;
  const pending = input.rows.filter(isPending);
  if (pending.some((r) => r.budgetStatus === 'COMPUTING')) return POLL_COMPUTING_MS;
  if (pending.some((r) => r.budgetStatus === 'QUEUED') || input.notComputedTotal > 0) return POLL_QUEUED_MS;
  return null;
}

// ───────────────────────────── batch approve: sequential compute (F-4) ─────────────────────────────

/**
 * 🔒 J-2: compute the rows ONE AT A TIME — the next compute-now is only sent after the previous row
 * reached a final outcome. (SelectableTable's runBatch defaults to concurrent=true; not used here.)
 * onState reports per-row progress (COMPUTING before the call, DONE / FAILED after).
 */
export async function runSequentialCompute<R extends { reviewId: string }>(
  rows: R[],
  compute: (row: R) => Promise<ComputeOutcome>,
  onState: (reviewId: string, state: 'COMPUTING' | 'DONE' | 'FAILED', outcome?: ComputeOutcome) => void,
  signal?: { aborted: boolean },
): Promise<Map<string, ComputeOutcome> | null> {
  const outcomes = new Map<string, ComputeOutcome>();
  for (const r of rows) {
    if (signal?.aborted) return null;
    onState(r.reviewId, 'COMPUTING');
    const o = await compute(r);
    if (signal?.aborted) return null;
    outcomes.set(r.reviewId, o);
    onState(r.reviewId, o.kind === 'DONE' && o.row.budgetStatus === 'READY' ? 'DONE' : 'FAILED', o);
  }
  return outcomes;
}
