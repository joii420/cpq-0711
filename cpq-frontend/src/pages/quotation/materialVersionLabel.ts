import type { MaterialVersionRowDTO } from '../../types/price-adjust';

/**
 * 屏 7 · 料号级价格版本表的纯函数渲染层（fronttask §6.1 / api.md §4.1）。
 * 🔒 核心断言点：state=NOT_UPDATED 时 `showsVersionNo` 必须为 false 且 `text` 不得包含
 * currentVersionNo —— 指针已推进但单未更新成功时，直接显示新版本号会让这张"单内混合价"
 * 证据表说谎（§11.6.3.1 后果 2）。抽成纯函数是为了让这条规则有单测锁住，不埋在 JSX 里。
 */
export interface MaterialVersionLabel {
  text: string;
  /** true = 文案里含真实版本号（UPGRADED/REJECTED）；false = 不含（NOT_UPDATED/NOT_PARTICIPATING） */
  showsVersionNo: boolean;
}

export function buildMaterialVersionLabel(
  row: Pick<MaterialVersionRowDTO, 'state' | 'currentVersionNo'>,
): MaterialVersionLabel {
  switch (row.state) {
    case 'UPGRADED':
      return { text: row.currentVersionNo ?? '—', showsVersionNo: true };
    case 'REJECTED':
      return { text: `${row.currentVersionNo ?? '—'}（未升版）`, showsVersionNo: true };
    case 'NOT_UPDATED':
      // 🔒 绝不拼入 currentVersionNo，哪怕它有值（指针已推进到的目标版本）
      return { text: '尚未更新', showsVersionNo: false };
    case 'NOT_PARTICIPATING':
      return { text: '未参与调价', showsVersionNo: false };
    default:
      return { text: String(row.state), showsVersionNo: false };
  }
}
