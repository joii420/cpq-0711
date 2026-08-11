import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { CardValuesWarmBoundary } from './CardValuesWarmBoundary';
import { createAsyncActivityGate } from './cardValuesWarm';

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

describe('card values warm UI gate', () => {
  it('keeps the page loading contract observable for the full pending activity', async () => {
    const changes: boolean[] = [];
    const pending = deferred<string>();
    const gate = createAsyncActivityGate((active) => changes.push(active));

    const result = gate.run(() => pending.promise);
    expect(gate.activeCount).toBe(1);
    expect(changes).toEqual([true]);

    const pendingMarkup = renderToStaticMarkup(
      <CardValuesWarmBoundary loading={false} warming={true}>
        <input aria-label="precision-input" />
      </CardValuesWarmBoundary>,
    );
    expect(pendingMarkup).toContain('data-card-values-warming="true"');
    expect(pendingMarkup).toContain('ant-spin-spinning');

    pending.resolve('done');
    await expect(result).resolves.toBe('done');
    expect(gate.activeCount).toBe(0);
    expect(changes).toEqual([true, false]);

    const settledMarkup = renderToStaticMarkup(
      <CardValuesWarmBoundary loading={false} warming={false}>
        <input aria-label="precision-input" />
      </CardValuesWarmBoundary>,
    );
    expect(settledMarkup).toContain('data-card-values-warming="false"');
    expect(settledMarkup).not.toContain('ant-spin-spinning');
  });

  it('does not unlock while another overlapping warm is still pending', async () => {
    const changes: boolean[] = [];
    const first = deferred<void>();
    const second = deferred<void>();
    const gate = createAsyncActivityGate((active) => changes.push(active));

    const firstRun = gate.run(() => first.promise);
    const secondRun = gate.run(() => second.promise);
    expect(gate.activeCount).toBe(2);
    expect(changes).toEqual([true]);

    first.resolve();
    await firstRun;
    expect(gate.activeCount).toBe(1);
    expect(changes).toEqual([true]);

    second.resolve();
    await secondRun;
    expect(gate.activeCount).toBe(0);
    expect(changes).toEqual([true, false]);
  });

  it('releases the UI after ensure failure so a later retry can run', async () => {
    const changes: boolean[] = [];
    const failure = new Error('ensure failed');
    const onFailure = vi.fn();
    const gate = createAsyncActivityGate((active) => changes.push(active));

    await gate.run(() => Promise.reject(failure)).catch(onFailure);
    expect(onFailure).toHaveBeenCalledWith(failure);
    expect(gate.activeCount).toBe(0);
    expect(changes).toEqual([true, false]);

    await expect(gate.run(() => Promise.resolve('retried'))).resolves.toBe('retried');
    expect(gate.activeCount).toBe(0);
    expect(changes).toEqual([true, false, true, false]);
  });
});
