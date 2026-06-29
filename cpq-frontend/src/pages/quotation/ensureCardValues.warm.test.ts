import { describe, it, expect } from 'vitest';
// 从抽出的小模块导入,避免 vitest 拉起 QuotationWizard.tsx 的重依赖(antd / 全套 service)。
// QuotationWizard 再 re-export 同一函数供运行时使用。
import { shouldWarmCardValues } from './cardValuesWarm';

describe('shouldWarmCardValues', () => {
  it('有行缺 quoteCardValues → true', () => {
    expect(shouldWarmCardValues([{ quoteCardValues: undefined, costingCardValues: '{}' }] as any)).toBe(true);
  });
  it('有行缺 costingCardValues → true', () => {
    expect(shouldWarmCardValues([{ quoteCardValues: '{"tabs":[]}', costingCardValues: undefined }] as any)).toBe(true);
  });
  it('全部已算(含哨兵字符串视为已算) → false', () => {
    expect(shouldWarmCardValues([{ quoteCardValues: '{"tabs":[],"__cardValueFailed":true}', costingCardValues: '{"tabs":[]}' }] as any)).toBe(false);
  });
  it('空集 → false', () => {
    expect(shouldWarmCardValues([] as any)).toBe(false);
  });
});
