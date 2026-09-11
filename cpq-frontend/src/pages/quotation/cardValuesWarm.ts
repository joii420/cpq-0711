// lazy-cardvalues：判定该报价单是否需要 warm 卡片值的纯函数。
// 抽成独立小模块,既便于 vitest 单测(不拉 QuotationWizard.tsx 的重依赖),
// 又由 QuotationWizard import 复用。
import { isCardValueFailed } from './cardValueFailed';

// 判定该单是否需要 warm 卡片值:任一行的 quote/costing 卡片值**缺失、或是失败哨兵**。
//
// 🔑 假设变更(task-260910 D-34 裁决,推翻本函数 2026-07 的原假设):
//   · 旧假设 =「卡片值字符串非空 ⇒ 后端已算过 ⇒ 不要再 warm」。当年它是对的:失败哨兵
//     {"tabs":[],"__cardValueFailed":true} 一落库就**永久粘死** —— 后端 ensureCardValues 的
//     missing 谓词只认 `IS NULL`,再 warm 一百次也选不中哨兵行,纯属白烧 9~12s 整单 ensure。
//   · 为何失效 = B-24 已把后端谓词改成「IS NULL **或** 含 __cardValueFailed」
//     (CardSnapshotService#sqlNeedsRecompute),哨兵行现在**能**被重选重算,即后端已具备自愈。
//     此时前端若还把哨兵读作"已算",两个自动触发点(打开报价单 QuotationWizard:748 /
//     保存后 warm :964)就压根不会调 ensure-card-values ⇒ 后端的自愈从 UI 永远看不见。
//
// 🚫 判据只认显式标记 __cardValueFailed(与后端 B-24 同口径),**不许**退化成「tabs 为空」:
//   合法的空卡片({"tabs":[]} —— 该侧模板确实没绑组件)语义是"算完了,结果就是空",重算只会
//   得到同一个空结果。拿它去 warm 属过度修复,每次打开都白付一次整单 ensure 的阻塞代价。
export function shouldWarmCardValues(
  items: Array<{ quoteCardValues?: string; costingCardValues?: string }>,
): boolean {
  if (!items || items.length === 0) return false;
  return items.some(li =>
    !li?.quoteCardValues
    || !li?.costingCardValues
    || isCardValueFailed(li.quoteCardValues)
    || isCardValueFailed(li.costingCardValues),
  );
}

export interface AsyncActivityGate {
  readonly activeCount: number;
  run<T>(task: () => Promise<T>): Promise<T>;
}

/** Keeps the UI locked until every overlapping background activity settles. */
export function createAsyncActivityGate(
  onActiveChange: (active: boolean) => void,
): AsyncActivityGate {
  let activeCount = 0;
  return {
    get activeCount() {
      return activeCount;
    },
    async run<T>(task: () => Promise<T>): Promise<T> {
      activeCount += 1;
      if (activeCount === 1) onActiveChange(true);
      try {
        return await task();
      } finally {
        activeCount = Math.max(0, activeCount - 1);
        if (activeCount === 0) onActiveChange(false);
      }
    },
  };
}
