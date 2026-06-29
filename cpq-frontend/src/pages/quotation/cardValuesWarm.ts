// lazy-cardvalues：判定该报价单是否需要 warm 卡片值的纯函数。
// 抽成独立小模块,既便于 vitest 单测(不拉 QuotationWizard.tsx 的重依赖),
// 又由 QuotationWizard re-export 供运行时使用。

// 判定该单是否需要 warm 卡片值:有任一行缺 quote/costing 卡片值(哨兵字符串非空 → 视为已算)。
export function shouldWarmCardValues(
  items: Array<{ quoteCardValues?: string; costingCardValues?: string }>,
): boolean {
  if (!items || items.length === 0) return false;
  return items.some(li => !li.quoteCardValues || !li.costingCardValues);
}
