// ──────────────────────────────────────────────────────────────────────────────
// EditableProfitCell —— 销售产品列表「利润」专用单元格（task-261026 · F-2/F-3）
//
// 🚫 本组件只服务 profit，不与 EditableProductionNoCell 泛化合并。两列的校验、
//    空值语义和失败文案均不同，分开可避免扩大生产料号的回归面。
//
// 数值契约：
//   · InputNumber<string> + stringMode，不经 JavaScript number；
//   · 允许负数、零和小数，对齐 numeric(26,12) 的 14 位整数 + 12 位小数边界；
//   · 页面显式清空发送 null；非数字留在编辑态，不发请求。
// ────────────────────────────────────────────────────────────────────────────
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { InputNumber, message } from 'antd';
import { CloseCircleOutlined, EditOutlined, LoadingOutlined } from '@ant-design/icons';
import type { InputNumberRef } from '@rc-component/input-number';
import { updateDatasetPart } from './productHubApi';
import { DASH } from './productHubCells';
import type { ApiError } from '../../services/api';

const PROFIT_SCALE = 12;
const PROFIT_INTEGER_DIGITS = 14;
const FAIL_HINT_MS = 5000;

const MUTED: React.CSSProperties = { color: 'rgba(0, 0, 0, 0.25)' };
const STATE_BASE: React.CSSProperties = {
  fontSize: 12,
  marginInlineStart: 6,
  whiteSpace: 'nowrap',
};
const STATE_SAVING: React.CSSProperties = { ...STATE_BASE, color: '#1677ff' };
const STATE_FAILED: React.CSSProperties = { ...STATE_BASE, color: '#cf1322' };

type CellState = 'view' | 'editing' | 'saving';

interface NormalizedProfit {
  value: string | null;
  error: string | null;
}

/**
 * 只接受普通十进制数，不把科学计数法、Infinity 或本地化分组符当成利润。
 * 这里只做字符串检查，避免 Number()/parseFloat() 造成精度丢失。
 */
function normalizeProfit(raw: string): NormalizedProfit {
  const value = raw.trim();
  if (value === '') return { value: null, error: null };

  if (!/^[+-]?(?:\d+|\d+\.\d+|\.\d+)$/.test(value)) {
    return { value: null, error: '利润必须是数字' };
  }

  const unsigned = value.replace(/^[+-]/, '');
  const [integerPart = '', fractionPart = ''] = unsigned.split('.');
  const significantIntegerDigits = (integerPart.replace(/^0+/, '') || '0').length;
  if (significantIntegerDigits > PROFIT_INTEGER_DIGITS) {
    return { value: null, error: `利润整数部分最多 ${PROFIT_INTEGER_DIGITS} 位` };
  }
  if (fractionPart.length > PROFIT_SCALE) {
    return { value: null, error: `利润最多支持 ${PROFIT_SCALE} 位小数` };
  }

  return { value, error: null };
}

function readableError(error: unknown): string {
  const apiError = error as ApiError | undefined;
  const status = apiError?.httpStatus;
  const detail = (apiError?.message ?? '').trim();
  if (status === 401) return '登录已失效，请重新登录后再试';
  if (status === 403) return '当前账号无权修改利润';
  if (status === 400) return detail || '利润不合法，请检查后重试';
  if (status === 404) return '该销售料号不存在或已被删除，请刷新列表';
  if (status === 409) return detail || '目标销售料号匹配到多个客户，请刷新后重试';
  return detail && detail !== 'Network error' ? detail : '保存失败，请稍后重试';
}

export interface EditableProfitCellProps {
  axisValue: string;
  /** 必须传被编辑行的客户号，与销售料号组成行身份。 */
  customerNo: string;
  /** GET 列表按十进制字符串返回；null 显示为破折号。 */
  value: string | null;
  onSaved: (next: string | null) => void;
}

const EditableProfitCell: React.FC<EditableProfitCellProps> = ({
  axisValue,
  customerNo,
  value,
  onSaved,
}) => {
  const [state, setState] = useState<CellState>('view');
  const [draft, setDraft] = useState('');
  const [validationError, setValidationError] = useState<string | null>(null);
  const [failHint, setFailHint] = useState(false);
  const inputRef = useRef<InputNumberRef | null>(null);
  const cancelledRef = useRef(false);
  const committingRef = useRef(false);
  const aliveRef = useRef(true);
  const failTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    aliveRef.current = true;
    return () => {
      aliveRef.current = false;
      if (failTimer.current) clearTimeout(failTimer.current);
    };
  }, []);

  const enterEdit = useCallback(() => {
    if (state !== 'view') return;
    cancelledRef.current = false;
    committingRef.current = false;
    setFailHint(false);
    setValidationError(null);
    setDraft(value ?? '');
    setState('editing');
    setTimeout(() => {
      inputRef.current?.focus();
      inputRef.current?.select();
    }, 0);
  }, [state, value]);

  const cancelEdit = useCallback(() => {
    cancelledRef.current = true;
    committingRef.current = false;
    setValidationError(null);
    setState('view');
  }, []);

  const commit = useCallback(async () => {
    if (cancelledRef.current) {
      cancelledRef.current = false;
      return;
    }
    if (committingRef.current) return;

    const normalized = normalizeProfit(draft);
    if (normalized.error) {
      setValidationError(normalized.error);
      setTimeout(() => inputRef.current?.focus(), 0);
      return;
    }

    const next = normalized.value;
    if (next === value) {
      setValidationError(null);
      setState('view');
      return;
    }

    committingRef.current = true;
    setValidationError(null);
    setState('saving');
    try {
      // 只传利润一列；显式清空保留 null，不转空字符串。
      await updateDatasetPart(axisValue, { profit: next }, customerNo);
      if (!aliveRef.current) return;
      onSaved(next);
      setState('view');
      message.success('保存成功');
    } catch (error) {
      if (!aliveRef.current) return;
      // 回到 view 后继续显示 props.value，因此失败草稿不会假成功地留在页面。
      setDraft(value ?? '');
      setState('view');
      setFailHint(true);
      if (failTimer.current) clearTimeout(failTimer.current);
      failTimer.current = setTimeout(() => {
        if (aliveRef.current) setFailHint(false);
      }, FAIL_HINT_MS);
      message.error(readableError(error));
    } finally {
      committingRef.current = false;
    }
  }, [axisValue, customerNo, draft, onSaved, value]);

  const swallow = (event: React.SyntheticEvent) => { event.stopPropagation(); };

  if (state === 'editing') {
    return (
      <div onClick={swallow} onDoubleClick={swallow} style={{ width: '100%' }}>
        <InputNumber<string>
          ref={inputRef}
          stringMode
          controls={false}
          changeOnBlur={false}
          precision={PROFIT_SCALE}
          size="small"
          status={validationError ? 'error' : undefined}
          value={draft === '' ? null : draft}
          placeholder="利润（选填）"
          aria-label="利润"
          inputMode="decimal"
          style={{ width: '100%' }}
          styles={{ input: { textAlign: 'right' } }}
          onInput={(text) => {
            setDraft(text);
            if (validationError) setValidationError(null);
          }}
          onChange={(next) => {
            // 步进键等非直接输入路径只触发 onChange；普通键入的原文由 onInput 最终覆盖。
            setDraft(next ?? '');
            if (validationError) setValidationError(null);
          }}
          onPressEnter={() => { void commit(); }}
          onBlur={() => { void commit(); }}
          onKeyDown={(event) => {
            if (event.key === 'Escape') cancelEdit();
          }}
        />
        {validationError && (
          <div style={{ color: '#cf1322', fontSize: 12, marginTop: 4, textAlign: 'left' }}>
            {validationError}
          </div>
        )}
      </div>
    );
  }

  const text = value === '' ? null : value;

  return (
    <div
      onClick={swallow}
      onDoubleClick={(event) => { swallow(event); enterEdit(); }}
      style={{
        alignItems: 'center',
        display: 'flex',
        gap: 6,
        justifyContent: 'flex-end',
        minHeight: 22,
        minWidth: 0,
        width: '100%',
      }}
    >
      {text === null
        ? <span style={MUTED}>{DASH}</span>
        : (
          <span
            title={text}
            style={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
          >
            {text}
          </span>
        )}

      {state === 'saving' && (
        <span style={STATE_SAVING}><LoadingOutlined /> 保存中…</span>
      )}

      {state === 'view' && failHint && (
        <span style={STATE_FAILED}><CloseCircleOutlined /> 保存失败，已还原</span>
      )}

      {state === 'view' && !failHint && (
        <EditOutlined
          className="ph-edit-pen"
          title="编辑利润"
          onClick={(event) => { swallow(event); enterEdit(); }}
        />
      )}
    </div>
  );
};

export default EditableProfitCell;
