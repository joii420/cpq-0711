import React, {
  forwardRef, useCallback, useEffect, useImperativeHandle, useRef,
} from 'react';
import type { TabDef } from '../../../services/tabJoinFormulaService';
import { parseFormulaSegments, type SegmentColor } from '../../component/formulaSerialize';
import { scanParens, type ParenInfo } from './formulaBracketCheck';

export interface FormulaRichInputHandle {
  /** 在当前光标处插入文本;caretOffsetFromEnd 用于把光标落到 fn() 括号内 */
  insertAtCursor: (text: string, caretOffsetFromEnd?: number) => void;
}

interface Props {
  value: string;
  onChange: (next: string) => void;
  tabDefs: TabDef[];
  selfRowKeyFields?: string[];
  /** EXCEL→false(不按 match 红);NORMAL/SUBTOTAL→true */
  enforceMappable: boolean;
  placeholder?: string;
}

const BLOCK_STYLE: Record<NonNullable<SegmentColor> | 'neutral', React.CSSProperties> = {
  blue:    { background: '#e6f4ff', border: '1px solid #91caff', color: '#0958d9' },
  yellow:  { background: '#fffbe6', border: '1px solid #ffd591', color: '#d46b08' },
  green:   { background: '#f6ffed', border: '1px solid #b7eb8f', color: '#389e0d' },
  red:     { background: '#fff1f0', border: '1px solid #ffa39e', color: '#cf1322' },
  purple:  { background: '#f9f0ff', border: '1px solid #d3adf7', color: '#722ed1' },
  neutral: { background: '#f5f5f5', border: '1px solid #d9d9d9', color: '#595959' },
};

/** 括号着色/高亮样式：注入一次全局 <style>，选择器统一加 .tabjoin-formula-rich-input 前缀防污染 */
const PAREN_STYLE_ID = 'tabjoin-formula-rich-input-paren-style';
function ensureParenStyleInjected() {
  if (typeof document === 'undefined') return;
  if (document.getElementById(PAREN_STYLE_ID)) return;
  const style = document.createElement('style');
  style.id = PAREN_STYLE_ID;
  style.textContent = `
.tabjoin-formula-rich-input .par { font-weight: 800; }
.tabjoin-formula-rich-input .p0 { color: #d4820a; }
.tabjoin-formula-rich-input .p1 { color: #7d3ac1; }
.tabjoin-formula-rich-input .p2 { color: #0a9396; }
.tabjoin-formula-rich-input .p3 { color: #c2185b; }
.tabjoin-formula-rich-input .parErr { color: #cf1322; text-decoration: wavy underline #cf1322; text-underline-offset: 3px; }
.tabjoin-formula-rich-input .parHit { background: #fff3cd; border-radius: 3px; }
`;
  document.head.appendChild(style);
}

/** 读 contentEditable DOM 回字符串:文本节点取 textContent,块取 data-raw,递归兜底 wrapper(含括号 span) */
function readBack(root: HTMLElement): string {
  let out = '';
  root.childNodes.forEach((node) => {
    if (node.nodeType === Node.TEXT_NODE) {
      out += node.textContent ?? '';
    } else if (node instanceof HTMLElement) {
      const raw = node.getAttribute('data-raw');
      if (raw != null) out += raw;
      else if (node.tagName === 'BR') { /* 单行公式,忽略换行 */ }
      else out += readBack(node);
    }
  });
  return out;
}

/** 计算光标在「raw 字符串」里的偏移(块按 data-raw 长度整体计) */
function caretOffset(root: HTMLElement): number {
  const sel = window.getSelection();
  if (!sel || sel.rangeCount === 0) return root.textContent ? readBack(root).length : 0;
  const range = sel.getRangeAt(0);
  const pre = range.cloneRange();
  pre.selectNodeContents(root);
  pre.setEnd(range.endContainer, range.endOffset);
  const frag = pre.cloneContents();
  const tmp = document.createElement('div');
  tmp.appendChild(frag);
  return readBack(tmp).length;
}

/**
 * 递归定位 raw 偏移 offset 对应的 DOM 位置并落下光标。规则与 readBack 完全对齐:
 *   TEXT_NODE            → 按 textContent.length 计;offset 落区间内则 setStart 返回
 *   ELEMENT 且有 data-raw → 【原子块】按 data-raw.length 整体跳过,不进入内部;
 *                           offset <= acc 时 setStartBefore(node) 返回
 *   ELEMENT 且无 data-raw → 【括号 span / 其他 wrapper】递归下降处理其子节点
 *   BR                   → 忽略(与 readBack 一致)
 * 返回 true = 已在本次调用内设置了 selection range。
 */
function locateCaretOffset(root: Node, offset: number, cursor: { acc: number }): boolean {
  const sel = window.getSelection();
  for (const node of Array.from(root.childNodes)) {
    if (node.nodeType === Node.TEXT_NODE) {
      const len = (node.textContent ?? '').length;
      if (offset <= cursor.acc + len) {
        const r = document.createRange();
        r.setStart(node, Math.max(0, offset - cursor.acc));
        r.collapse(true);
        if (sel) {
          sel.removeAllRanges();
          sel.addRange(r);
        }
        return true;
      }
      cursor.acc += len;
      continue;
    }
    if (node instanceof HTMLElement) {
      if (node.tagName === 'BR') continue;
      const raw = node.getAttribute('data-raw');
      if (raw != null) {
        // 原子块:整体跳过,不下降;offset 落在块前则把光标停在块边界
        if (offset <= cursor.acc) {
          const r = document.createRange();
          r.setStartBefore(node);
          r.collapse(true);
          if (sel) {
            sel.removeAllRanges();
            sel.addRange(r);
          }
          return true;
        }
        cursor.acc += raw.length;
        continue;
      }
      // 括号 span / 其他 wrapper:无 data-raw,递归下降处理其子节点
      if (locateCaretOffset(node, offset, cursor)) return true;
      continue;
    }
  }
  return false;
}

/**
 * 重建 DOM 后把光标恢复到 raw 偏移 offset。用 locateCaretOffset 递归遍历,
 * 全部遍历完仍未命中(如 offset 落在末尾)→ 落到内容末尾(沿用现状兜底逻辑)。
 */
function restoreCaret(root: HTMLElement, offset: number) {
  const sel = window.getSelection();
  if (!sel) return;
  const cursor = { acc: 0 };
  if (locateCaretOffset(root, offset, cursor)) return;
  const r = document.createRange();
  r.selectNodeContents(root);
  r.collapse(false);
  sel.removeAllRanges();
  sel.addRange(r);
}

/** 把非块文本段逐字符输出,命中 parenByIndex 的字符包 <span class="par pN [parErr]"> */
function appendTextWithParens(
  el: HTMLElement,
  text: string,
  baseOffset: number,
  parenByIndex: Map<number, ParenInfo>,
) {
  let buf = '';
  const flushBuf = () => {
    if (buf) {
      el.appendChild(document.createTextNode(buf));
      buf = '';
    }
  };
  for (let i = 0; i < text.length; i++) {
    const info = parenByIndex.get(baseOffset + i);
    if (info) {
      flushBuf();
      const span = document.createElement('span');
      span.className = `par p${info.depth % 4}${info.error ? ' parErr' : ''}`;
      span.setAttribute('data-paren-idx', String(info.index));
      span.textContent = text[i];
      el.appendChild(span);
    } else {
      buf += text[i];
    }
  }
  flushBuf();
}

/** 是否是带 data-paren-idx 的括号 span */
function isParenSpan(node: Node | null): node is HTMLElement {
  return !!node && node instanceof HTMLElement && node.hasAttribute('data-paren-idx');
}

/** 判断光标是否紧邻(左侧/右侧/落在其内部文本首尾)某个括号 span,是则返回该 span */
function findAdjacentParenSpan(root: HTMLElement): HTMLElement | null {
  const sel = window.getSelection();
  if (!sel || sel.rangeCount === 0 || !sel.isCollapsed) return null;
  const range = sel.getRangeAt(0);
  const container = range.startContainer;
  const offset = range.startOffset;
  if (!root.contains(container)) return null;

  if (container.nodeType === Node.TEXT_NODE) {
    const text = container.textContent ?? '';
    const parent = container.parentElement;
    if (isParenSpan(parent)) {
      // 光标落在括号字符文本节点内部(起/止均可,单字符节点)
      return parent;
    }
    if (offset === 0 && isParenSpan(container.previousSibling)) {
      return container.previousSibling as HTMLElement;
    }
    if (offset === text.length && isParenSpan(container.nextSibling)) {
      return container.nextSibling as HTMLElement;
    }
    return null;
  }

  if (container.nodeType === Node.ELEMENT_NODE) {
    const el = container as HTMLElement;
    const before = el.childNodes[offset - 1] ?? null;
    const after = el.childNodes[offset] ?? null;
    if (isParenSpan(before)) return before as HTMLElement;
    if (isParenSpan(after)) return after as HTMLElement;
    return null;
  }

  return null;
}

const FormulaRichInput = forwardRef<FormulaRichInputHandle, Props>(function FormulaRichInput(
  { value, onChange, tabDefs, selfRowKeyFields, enforceMappable, placeholder }, ref,
) {
  const editorRef = useRef<HTMLDivElement>(null);
  const composingRef = useRef(false);
  /**
   * 最近一次由本组件 emit 的字符串,用来判断 value 是否外部变更(避免无谓重建打断光标)。
   * 契约:父组件的 onChange 回调**不可**对值做 trim/normalize 等规整 —— 否则 value 会
   * 与 lastEmittedRef 不等,useEffect 触发无 caret 参数的重建,打字中光标会丢。
   * 当前 TabJoinFormulaDrawer 的 onChange = setExpression(原样回写),满足该契约。
   */
  const lastEmittedRef = useRef<string | null>(null);
  /** scanParens 结果缓存(随 renderInto 更新),配对高亮时直接查,避免每次移动光标重扫 */
  const parensCacheRef = useRef<{ str: string; parenByIndex: Map<number, ParenInfo> } | null>(null);

  useEffect(() => {
    ensureParenStyleInjected();
  }, []);

  /** 把 value 渲染进编辑器 DOM(块 + 文本节点 + 括号 span),可选恢复光标偏移 */
  const renderInto = useCallback((str: string, caret?: number) => {
    const el = editorRef.current;
    if (!el) return;
    const segs = parseFormulaSegments(str, tabDefs, selfRowKeyFields, enforceMappable);
    const parens = scanParens(str);
    const parenByIndex = new Map<number, ParenInfo>();
    for (const p of parens) parenByIndex.set(p.index, p);
    parensCacheRef.current = { str, parenByIndex };

    el.innerHTML = '';
    let offset = 0;
    for (const s of segs) {
      if (!s.isBlock) {
        appendTextWithParens(el, s.raw, offset, parenByIndex);
      } else {
        const span = document.createElement('span');
        span.setAttribute('contenteditable', 'false');
        span.setAttribute('data-raw', s.raw);
        const sty = BLOCK_STYLE[(s.color ?? 'neutral') as keyof typeof BLOCK_STYLE];
        Object.assign(span.style, {
          ...sty, borderRadius: '4px', padding: '0 5px', margin: '0 1px',
          fontSize: '13px', whiteSpace: 'nowrap', userSelect: 'none', cursor: 'default',
        } as Partial<CSSStyleDeclaration>);
        span.textContent = s.display;
        el.appendChild(span);
      }
      offset += s.raw.length;
    }
    if (caret != null) restoreCaret(el, caret);
  }, [tabDefs, selfRowKeyFields, enforceMappable]);

  useEffect(() => {
    if (value === lastEmittedRef.current) return;
    renderInto(value);
    lastEmittedRef.current = value;
  }, [value, renderInto]);

  const handleInput = useCallback(() => {
    if (composingRef.current) return;
    const el = editorRef.current;
    if (!el) return;
    const offset = caretOffset(el);
    const str = readBack(el);
    lastEmittedRef.current = str;
    onChange(str);
    renderInto(str, offset);
  }, [onChange, renderInto]);

  const handleCompositionEnd = useCallback(() => {
    composingRef.current = false;
    handleInput();
  }, [handleInput]);

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key !== 'Backspace') return;
    const sel = window.getSelection();
    if (!sel || sel.rangeCount === 0 || !sel.isCollapsed) return;
    const range = sel.getRangeAt(0);
    let prev: Node | null = null;
    if (range.startContainer.nodeType === Node.TEXT_NODE && range.startOffset === 0) {
      prev = range.startContainer.previousSibling;
    } else if (range.startContainer === editorRef.current) {
      prev = editorRef.current?.childNodes[range.startOffset - 1] ?? null;
    }
    if (prev instanceof HTMLElement && prev.getAttribute('data-raw') != null) {
      e.preventDefault();
      prev.remove();
      handleInput();
    }
  }, [handleInput]);

  const handlePaste = useCallback((e: React.ClipboardEvent) => {
    e.preventDefault();
    const text = e.clipboardData.getData('text/plain');
    document.execCommand('insertText', false, text);
  }, []);

  /** 光标驱动的配对高亮:只操作 class,绝不重建 DOM / 不触发 onChange。AC-13:composition 中直接 return */
  const updateParenHighlight = useCallback(() => {
    if (composingRef.current) return;
    const root = editorRef.current;
    if (!root) return;
    root.querySelectorAll('.parHit').forEach((elx) => elx.classList.remove('parHit'));
    const hit = findAdjacentParenSpan(root);
    if (!hit) return;
    hit.classList.add('parHit');
    const idxAttr = hit.getAttribute('data-paren-idx');
    const idx = idxAttr != null ? Number(idxAttr) : NaN;
    const cache = parensCacheRef.current;
    if (!Number.isNaN(idx) && cache) {
      const info = cache.parenByIndex.get(idx);
      const matchIdx = info?.matchIndex;
      if (matchIdx != null) {
        const matchEl = root.querySelector(`[data-paren-idx="${matchIdx}"]`);
        if (matchEl) matchEl.classList.add('parHit');
      }
    }
  }, []);

  useEffect(() => {
    const handler = () => updateParenHighlight();
    document.addEventListener('selectionchange', handler);
    return () => document.removeEventListener('selectionchange', handler);
  }, [updateParenHighlight]);

  useImperativeHandle(ref, () => ({
    insertAtCursor: (text: string, caretOffsetFromEnd = 0) => {
      const el = editorRef.current;
      if (!el) return;
      el.focus();
      const sel = window.getSelection();
      if (!sel || sel.rangeCount === 0 || !el.contains(sel.getRangeAt(0).startContainer)) {
        const r = document.createRange();
        r.selectNodeContents(el);
        r.collapse(false);
        sel?.removeAllRanges();
        sel?.addRange(r);
      }
      document.execCommand('insertText', false, text);
      const el2 = editorRef.current;
      if (el2) {
        const off = Math.max(0, caretOffset(el2) - caretOffsetFromEnd);
        const str = readBack(el2);
        lastEmittedRef.current = str;
        onChange(str);
        renderInto(str, off);
      }
    },
  }), [onChange, renderInto]);

  return (
    <div
      ref={editorRef}
      className="tabjoin-formula-rich-input"
      contentEditable
      suppressContentEditableWarning
      onInput={handleInput}
      onCompositionStart={() => { composingRef.current = true; }}
      onCompositionEnd={handleCompositionEnd}
      onKeyDown={handleKeyDown}
      onKeyUp={updateParenHighlight}
      onClick={updateParenHighlight}
      onFocus={updateParenHighlight}
      onPaste={handlePaste}
      data-placeholder={placeholder}
      style={{
        minHeight: 170, maxHeight: 340, overflowY: 'auto',
        border: '1px solid #d9d9d9', borderRadius: 6,
        padding: '8px 11px', marginTop: 4, lineHeight: '24px',
        fontFamily: 'SF Mono, Consolas, Monaco, monospace', fontSize: 13,
        outline: 'none', overflowWrap: 'anywhere', background: '#fff',
      }}
    />
  );
});

export default FormulaRichInput;
