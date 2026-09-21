/**
 * task-260920 · SSR render tests (renderToStaticMarkup, no DOM — repo convention, see
 * src/pages/__tests__/task260902ExportToolbar.test.tsx). Covers F-1 (AC-1/AC-27), F-2 drawer
 * placeholders (AC-5/AC-7), F-5 blocks + READY-only submission (AC-10/AC-11).
 * Limits: SSR runs no effects and antd Tooltip/Modal/Drawer portals render nothing —
 * so tooltips and the open Modal itself are covered by the Playwright screenshots, not here.
 */
import { describe, it, expect } from 'vitest';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import ReviewStatusCell from './ReviewStatusCell';
import { FailedMaterialsBlock, MaterialAmountTable, partitionAfterCompute, failureReason } from './ApproveImpactModal';
import { DrawerComputingPlaceholder, DrawerFailedSection } from './ReviewDetailDrawer';
import type { ComputeOutcome } from './reviewCompute';
import type { BudgetStatus, ImpactMaterialDTO, ReviewRowDTO, ReviewStatus } from '../../../types/price-adjust';

function row(id: string, budgetStatus: BudgetStatus, reviewStatus: ReviewStatus = 'PENDING', over: Partial<ReviewRowDTO> = {}): ReviewRowDTO {
  return {
    reviewId: id, customerNo: 'CUST-0004', customerName: '正泰', materialNo: 'M-' + id, materialName: 'N-' + id,
    currentVersionNo: null, targetVersionNo: 'V26092003', budgetStatus, budgetError: null, reviewStatus,
    basisQuotationNo: null, basisQuotationDate: null,
    quoteCostCurrent: null, quoteCostAdjusted: null, costingCost: null, diffCurrent: null, diffAdjusted: null,
    columnCount: 1, breachedCount: 0, amberCount: 0, missingCount: 0, staleCount: 0, rowRed: false, ...over,
  };
}
const cell = (r: ReviewRowDTO) => renderToStaticMarkup(<ReviewStatusCell row={r} onCompute={() => {}} onRecompute={() => {}} />);
const text = (html: string) => html.replace(/<[^>]+>/g, '');

describe('F-1 · 比对状态单元格', () => {
  it('AC-1：待处理 + QUEUED → 静态灰标「未计算」+「计算」链接，不转圈', () => {
    const h = cell(row('1', 'QUEUED'));
    expect(text(h)).toBe('未计算计算');
    expect(h).not.toContain('ant-spin');
  });
  it('待处理 + COMPUTING → Spin +「计算中」（不是旧的「预算计算中」）', () => {
    const h = cell(row('1', 'COMPUTING'));
    expect(h).toContain('ant-spin');
    expect(text(h)).toBe('计算中');
  });
  it('待处理 + FAILED → 既有「预算失败」+「重算」保留', () => expect(text(cell(row('1', 'FAILED', 'PENDING', { budgetError: 'x' })))).toBe('预算失败重算'));
  it('待处理 + READY → 既有比对标记', () => expect(text(cell(row('1', 'READY')))).toBe('✓ 1 列全通过'));
  it.each([
    ['VOIDED', 'QUEUED'], ['VOIDED', 'COMPUTING'], ['APPROVED', 'QUEUED'], ['REJECTED', 'FAILED'],
  ] as [ReviewStatus, BudgetStatus][])('AC-27：%s + %s → 显示「—」，不渲染「未计算 / 计算 / 重算」', (rs, bs) => {
    const h = cell(row('1', bs, rs));
    expect(text(h)).toBe('—');
    expect(h).not.toContain('未计算');
    expect(h).not.toContain('<a');
  });
  it('已通过 + READY → 保留既有比对标记（非待处理行不丢信息）', () => expect(text(cell(row('1', 'READY', 'APPROVED')))).toBe('✓ 1 列全通过'));
});

describe('F-2 · 抽屉占位（原型 审核抽屉-触发计算.html 状态 1 / 3）', () => {
  it('状态 1：正在计算该料号的影响…', () => {
    const t = text(renderToStaticMarkup(<DrawerComputingPlaceholder />));
    expect(t).toContain('正在计算该料号的影响…');
    expect(t).toContain('后台正在计算同一张单时，需要先等它算完当前这一个料号（通常不到 1 秒）');
  });
  it('状态 3（AC-7）：计算未完成占位 +「重新计算」按钮', () => {
    const h = renderToStaticMarkup(<DrawerFailedSection onRecompute={() => {}} />);
    expect(text(h)).toContain('计算未完成，暂无法判断是否可接受');
    expect(text(h)).toContain('重新计算');
    expect(h).not.toContain('disabled');
  });
});

describe('F-5 · 影响面确认框新增两块', () => {
  it('AC-11 ②：1 条失败 → 顶部「1 个料号计算失败，不参与本次升版」+ 料号与原因', () => {
    const t = text(renderToStaticMarkup(
      <FailedMaterialsBlock failures={[{ reviewId: 'x', materialNo: 'PERF600-B00432', reason: '判断依据单已不在活单范围内' }]} selectedCount={5} readyCount={4} />,
    ));
    expect(t).toContain('1 个料号计算失败，不参与本次升版');
    expect(t).toContain('PERF600-B00432 —— 判断依据单已不在活单范围内');
    expect(t).toContain('本次勾选 5 个，实际升版 4 个');
  });
  it('AC-11 ②：点确认提交的 reviewIds 不含失败行（partitionAfterCompute 是唯一来源）', () => {
    const rows = [row('ok0', 'READY'), row('q1', 'QUEUED'), row('q2', 'QUEUED'), row('f1', 'FAILED'), row('t1', 'QUEUED')];
    const outcomes = new Map<string, ComputeOutcome>([
      ['q1', { kind: 'DONE', row: row('q1', 'READY') }],
      ['q2', { kind: 'DONE', row: row('q2', 'FAILED', 'PENDING', { budgetError: '预算试算超时（超过 60 秒）' }) }],
      ['f1', { kind: 'NOT_PENDING', superseded: true }],
      ['t1', { kind: 'TIMEOUT' }],
    ]);
    const { ready, failures } = partitionAfterCompute(rows, outcomes);
    expect(ready.map((r) => r.reviewId)).toEqual(['ok0', 'q1']);
    expect(failures.map((f) => `${f.reviewId}:${f.reason}`)).toEqual([
      'q2:预算试算超时（超过 60 秒）', 'f1:该价格版本已被新版本取代', 't1:计算较慢，可稍后刷新',
    ]);
  });
  it('failureReason：FAILED 无原因时兜底「计算失败」', () =>
    expect(failureReason({ kind: 'DONE', row: row('a', 'FAILED') })).toBe('计算失败'));
  it('各料号金额：缺核价侧时报价侧两值照常显示，差异写「—（缺数据：核价侧）」；值不二次运算、9 位去尾零', () => {
    const materials: ImpactMaterialDTO[] = [
      { materialNo: 'S3120011203', quoteCostCurrent: '0.324083231000', quoteCostAdjusted: '1.717061326000', diffAdjusted: '1.717061326000', status: 'NORMAL', missingSide: null },
      { materialNo: 'T260907T-B01678', quoteCostCurrent: '0.500000000000', quoteCostAdjusted: '9118.200000000000', diffAdjusted: null, status: 'MISSING', missingSide: 'COSTING' },
    ];
    const t = text(renderToStaticMarkup(<MaterialAmountTable materials={materials} breachedNos={new Set(['S3120011203'])} />));
    expect(t).toContain('各料号金额（判断依据单 · 报价侧）');
    expect(t).toContain('S31200112030.3240832311.7170613261.717061326');
    expect(t).toContain('T260907T-B016780.59118.2—（缺数据：核价侧）');
  });
});
