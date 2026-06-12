import React, {
  forwardRef, useCallback, useEffect, useImperativeHandle, useRef,
} from 'react';
import type { TabDef } from '../../../services/tabJoinFormulaService';
import { parseFormulaSegments, type SegmentColor } from '../../component/formulaSerialize';

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
  neutral: { background: '#f5f5f5', border: '1px solid #d9d9d9', color: '#595959' },
};

/** 读 contentEditable DOM 回字符串:文本节点取 textContent,块取 data-raw,递归兜底 wrapper */
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

/** 重建 DOM 后把光标恢复到 raw 偏移 offset(块原子:落到块边界) */
function restoreCaret(root: HTMLElement, offset: number) {
  const sel = window.getSelection();
  if (!sel) return;
  let acc = 0;
  for (const node of Array.from(root.childNodes)) {
    if (node.nodeType === Node.TEXT_NODE) {
      const len = (node.textContent ?? '').length;
      if (offset <= acc + len) {
        const r = document.createRange();
        r.setStart(node, Math.max(0, offset - acc));
        r.collapse(true);
        sel.removeAllRanges();
        sel.addRange(r);
        return;
      }
      acc += len;
    } else if (node instanceof HTMLElement) {
      const raw = node.getAttribute('data-raw') ?? '';
      const len = raw.length;
      if (offset <= acc) {
        const r = document.createRange();
        r.setStartBefore(node);
        r.collapse(true);
        sel.removeAllRanges();
        sel.addRange(r);
        return;
      }
      acc += len;
    }
  }
  const r = document.createRange();
  r.selectNodeContents(root);
  r.collapse(false);
  sel.removeAllRanges();
  sel.addRange(r);
}

const FormulaRichInput = forwardRef<FormulaRichInputHandle, Props>(function FormulaRichInput(
  { value, onChange, tabDefs, selfRowKeyFields, enforceMappable, placeholder }, ref,
) {
  const editorRef = useRef<HTMLDivElement>(null);
  const composingRef = useRef(false);
  /** 最近一次由本组件 emit 的字符串,用来判断 value 是否外部变更(避免无谓重建打断光标) */
  const lastEmittedRef = useRef<string | null>(null);

  /** 把 value 渲染进编辑器 DOM(块 + 文本节点),可选恢复光标偏移 */
  const renderInto = useCallback((str: string, caret?: number) => {
    const el = editorRef.current;
    if (!el) return;
    const segs = parseFormulaSegments(str, tabDefs, selfRowKeyFields, enforceMappable);
    el.innerHTML = '';
    for (const s of segs) {
      if (!s.isBlock) {
        el.appendChild(document.createTextNode(s.raw));
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
      contentEditable
      suppressContentEditableWarning
      onInput={handleInput}
      onCompositionStart={() => { composingRef.current = true; }}
      onCompositionEnd={handleCompositionEnd}
      onKeyDown={handleKeyDown}
      onPaste={handlePaste}
      data-placeholder={placeholder}
      style={{
        minHeight: 52, border: '1px solid #d9d9d9', borderRadius: 6,
        padding: '8px 11px', marginTop: 4, lineHeight: '24px',
        fontFamily: 'SF Mono, Consolas, Monaco, monospace', fontSize: 13,
        outline: 'none', overflowWrap: 'anywhere', background: '#fff',
      }}
    />
  );
});

export default FormulaRichInput;
