/**
 * 选配抽屉的共享 UI 原子（task-260902）。
 *
 * 只放**跨 3 个以上文件复用**的壳子，业务判据一律留在各自的组件里。
 */
import React from 'react';
import { Button, Tooltip } from 'antd';
import type { ButtonProps } from 'antd';

/**
 * 「禁用但可见 + hover 写明原因」的按钮（`frontend.md §1.2`）。
 *
 * 🚫 **禁止用 `if (…) return null` 把不可用的按钮藏起来** —— 用户看不到按钮就不知道这个能力存在，
 *    更不知道为什么用不了。`reason` 非空即禁用，并把 `reason` 原文挂到 tooltip。
 * ⚠️ AntD 的 disabled `<button>` 不派发鼠标事件，tooltip 必须包一层 `<span>` 才触发得了。
 */
export const ReasonedButton: React.FC<
  Omit<ButtonProps, 'disabled'> & { reason?: string | null; children?: React.ReactNode }
> = ({ reason, children, ...rest }) => {
  if (!reason) return <Button {...rest}>{children}</Button>;
  return (
    <Tooltip title={reason}>
      <span style={{ display: 'inline-block', cursor: 'not-allowed' }}>
        {/*
          `pointerEvents:'none'` 让外层 <span> 的 `cursor:not-allowed` 真正生效
          （否则 disabled <button> 自己的光标样式会盖住它）。
          ⚠️ **它不是 tooltip 能否弹出的前提** —— 2026-09-02 真机 Playwright 证伪实验：
             去掉这一行后 tooltip 文本照样是「请先填写客户产品编号」，AntD v6 的 Button
             已自行处理 disabled 态的事件透传。写在这里是防止后人误以为可以顺手删掉
             `<span>` 包裹层 —— **那一层才是必需的**。
          📌 断言 tooltip 时用 `.ant-tooltip-container`：AntD v6 已把 `.ant-tooltip-inner`
             改名，用旧类名断言会恒返回空数组（我自己先踩了一次，误判成 tooltip 没弹）。
        */}
        <Button {...rest} disabled onClick={undefined} style={{ ...rest.style, pointerEvents: 'none' }}>
          {children}
        </Button>
      </span>
    </Tooltip>
  );
};

interface EmptyBlockProps {
  icon: string;
  title: string;
  hint?: React.ReactNode;
  actions?: React.ReactNode;
}

/**
 * 空态块（原型 `.empty`）。
 *
 * 🚨 **空是空，不是"还没加载完"**：调用方必须先分清 loading 与 empty 再决定渲染谁。
 *    把空数据渲染成「加载中…」是 `docs/反模式.md` AP-31 整个族的典型病（AC-16）。
 */
export const EmptyBlock: React.FC<EmptyBlockProps> = ({ icon, title, hint, actions }) => (
  <div style={{ padding: '40px 20px', textAlign: 'center', color: '#606266' }}>
    <div style={{ fontSize: 40, lineHeight: 1, marginBottom: 12, color: '#c0c4cc' }}>{icon}</div>
    <div style={{ fontSize: 14, color: '#303133' }}>{title}</div>
    {hint ? <div style={{ fontSize: 12, color: '#909399', marginTop: 6 }}>{hint}</div> : null}
    {actions ? <div style={{ marginTop: 16, display: 'flex', gap: 8, justifyContent: 'center' }}>{actions}</div> : null}
  </div>
);

/** 灰色说明块（原型 `.note`）。 */
export const NoteBlock: React.FC<{ children: React.ReactNode; style?: React.CSSProperties }> = ({ children, style }) => (
  <div
    style={{
      marginTop: 12, padding: '10px 12px', background: '#fafafa', border: '1px solid #f0f0f0',
      borderRadius: 6, fontSize: 12, lineHeight: 1.7, color: '#606266', ...style,
    }}
  >
    {children}
  </div>
);

/** 等宽小字（原型 `.mono`），料号 / 编号一律用它，避免 0/O、1/l 看混。 */
export const Mono: React.FC<{ children: React.ReactNode; muted?: boolean }> = ({ children, muted }) => (
  <span style={{ fontFamily: 'Consolas, Menlo, monospace', color: muted ? '#909399' : undefined }}>{children}</span>
);

/** 单行省略 + hover 全文（原型状态 D「最长文案不撑破布局」）。 */
export const Ellipsis: React.FC<{ text?: string | null; fallback?: string }> = ({ text, fallback = '—' }) => {
  const value = (text ?? '').trim();
  if (!value) return <span style={{ color: '#c0c4cc' }}>{fallback}</span>;
  return (
    <Tooltip title={value}>
      <span
        style={{ display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
      >
        {value}
      </span>
    </Tooltip>
  );
};

export const sectionTitleStyle: React.CSSProperties = {
  fontSize: 14, fontWeight: 600, margin: '24px 0 4px', display: 'flex', alignItems: 'center', gap: 8,
};
export const hintStyle: React.CSSProperties = { fontSize: 12, color: '#909399', margin: '0 0 12px' };
/** 选择器展开面板（原型里那个浅蓝底框）。 */
export const pickerPanelStyle: React.CSSProperties = {
  border: '1px solid #91caff', borderRadius: 8, background: '#f0f8ff', padding: 16, marginTop: 12,
};
