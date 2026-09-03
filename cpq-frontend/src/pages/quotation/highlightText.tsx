/**
 * highlightText —— task-260825 料号模糊查询命中高亮。
 *
 * 视觉基准：`原型图/02-编辑页-卡片视图-料号查询命中.html` 的 `.mark`（黄底）。
 * 大小写不敏感子串高亮，与 usePagedSearch 的匹配口径一致（避免"命中了但没高亮"或"高亮了但没命中"）。
 */
import React from 'react';

const MARK_STYLE: React.CSSProperties = {
  background: '#ffe58f',
  padding: '0 2px',
  borderRadius: 2,
};

/**
 * 把 text 中命中 term（大小写不敏感）的片段用 <mark> 包起来。
 * term 为空/text 为空时原样返回，不产生额外 DOM（翻页性能考量，AC-19）。
 */
export function highlightText(text: string | null | undefined, term: string | null | undefined): React.ReactNode {
  if (text == null) return text;
  const str = String(text);
  if (!term) return str;
  const idx = str.toLowerCase().indexOf(term.toLowerCase());
  if (idx < 0) return str;
  const before = str.slice(0, idx);
  const hit = str.slice(idx, idx + term.length);
  const after = str.slice(idx + term.length);
  return (
    <>
      {before}
      <mark style={MARK_STYLE}>{hit}</mark>
      {after}
    </>
  );
}
