import { describe, it, expect } from 'vitest';
// 从抽出的小模块导入,避免 vitest 拉起 QuotationWizard.tsx 的重依赖(antd / 全套 service)。
// QuotationWizard 再 re-export 同一函数供运行时使用。
import { shouldWarmCardValues } from './cardValuesWarm';

describe('shouldWarmCardValues', () => {
  // ① 缺值
  it('有行缺 quoteCardValues → true', () => {
    expect(shouldWarmCardValues([{ quoteCardValues: undefined, costingCardValues: '{}' }] as any)).toBe(true);
  });
  it('有行缺 costingCardValues → true', () => {
    expect(shouldWarmCardValues([{ quoteCardValues: '{"tabs":[]}', costingCardValues: undefined }] as any)).toBe(true);
  });

  // ② 失败哨兵 —— task-260910 D-34:本用例的期望值由 false 翻成 true。
  //   旧语义「哨兵字符串非空 ⇒ 视为已算 ⇒ 不 warm」在 B-24(后端 missing 谓词已能重选哨兵行
  //   自愈)之后失效:再按旧语义,两个自动触发点都不会调 ensure-card-values,后端的自愈从 UI 永
  //   远看不见。⇒ 哨兵必须判定为"需要 warm"。
  it('quote 侧是失败哨兵 → true(D-34:哨兵不再算已算)', () => {
    expect(shouldWarmCardValues([
      { quoteCardValues: '{"tabs":[],"__cardValueFailed":true}', costingCardValues: '{"tabs":[]}' },
    ] as any)).toBe(true);
  });
  it('costing 侧是失败哨兵 → true(两侧都要查,不能只查 quote)', () => {
    expect(shouldWarmCardValues([
      { quoteCardValues: '{"tabs":[{"name":"投料"}]}', costingCardValues: '{"tabs":[],"__cardValueFailed":true}' },
    ] as any)).toBe(true);
  });
  it('哨兵带 __errorMsg 原文(BL-0030 形态)同样 → true', () => {
    expect(shouldWarmCardValues([
      { quoteCardValues: '{"tabs":[],"__cardValueFailed":true,"__errorMsg":"核价树渲染失败:递归 SQL"}', costingCardValues: '{"tabs":[]}' },
    ] as any)).toBe(true);
  });

  // ③ 合法空卡片 —— 防过度修复的那道判据。
  //   {"tabs":[]} 不带 __cardValueFailed ⇒ 语义是"算完了,该侧模板就是没绑组件",重算只会再得
  //   到同一个空结果。🚫 判据绝不能退化成「tabs 为空就 warm」,否则每次打开都白付一次整单 ensure。
  it('两侧都是合法空卡片(tabs 空但无 __cardValueFailed) → false(不许拿 tabs 空当判据)', () => {
    expect(shouldWarmCardValues([
      { quoteCardValues: '{"tabs":[]}', costingCardValues: '{"tabs":[]}' },
    ] as any)).toBe(false);
  });
  it('__cardValueFailed 为 false / 非 true 值 → 不算哨兵 → false', () => {
    expect(shouldWarmCardValues([
      { quoteCardValues: '{"tabs":[],"__cardValueFailed":false}', costingCardValues: '{"tabs":[]}' },
    ] as any)).toBe(false);
  });
  it('业务字段里出现同名子串但不在顶层(不该被误判为哨兵) → false', () => {
    expect(shouldWarmCardValues([
      { quoteCardValues: '{"tabs":[{"name":"__cardValueFailed"}]}', costingCardValues: '{"tabs":[]}' },
    ] as any)).toBe(false);
  });

  // ④ 正常真值 / 边界
  it('两侧都是有内容的真值 → false', () => {
    expect(shouldWarmCardValues([
      { quoteCardValues: '{"tabs":[{"name":"投料","rows":[{"单价":"1.5"}]}]}', costingCardValues: '{"tabs":[{"name":"材料","rows":[{"金额":"12.00"}]}]}' },
    ] as any)).toBe(false);
  });
  it('空集 → false', () => {
    expect(shouldWarmCardValues([] as any)).toBe(false);
  });
  it('多行:首行齐全但后续行缺一 → true(钉死 .some 语义,非 .every)', () => {
    expect(shouldWarmCardValues([
      { quoteCardValues: '{"tabs":[]}', costingCardValues: '{"tabs":[]}' },
      { quoteCardValues: '{"tabs":[]}', costingCardValues: undefined },
    ] as any)).toBe(true);
  });
  it('多行:首行齐全但后续行是哨兵 → true(.some 也要覆盖哨兵维度)', () => {
    expect(shouldWarmCardValues([
      { quoteCardValues: '{"tabs":[]}', costingCardValues: '{"tabs":[]}' },
      { quoteCardValues: '{"tabs":[],"__cardValueFailed":true}', costingCardValues: '{"tabs":[]}' },
    ] as any)).toBe(true);
  });
});
