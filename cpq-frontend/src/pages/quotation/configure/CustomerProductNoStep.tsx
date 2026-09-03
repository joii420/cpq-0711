/**
 * CustomerProductNoStep — 步骤 1「客户产品编号」（task-260902 · F-1，服务 AC-1 / AC-2）。
 * 1:1 对齐 `原型图/1-客户产品编号.html` 状态 A / B / C / D。
 *
 * **为什么把编号放在第一步**：现状选配阶段拿不到客户产品编号（代码注释原话：「客户产品号在数据导入后
 * 才存在」），导致选配生成的料号在产品库列表里**找不回来**。编号前置后这条兜底可以退役。
 *
 * 🚨 **校验请求不得阻塞输入**：`onChange` 只 `setState`，**绝不 `await` 校验完再更新 value**。
 *    校验走独立的 debounce effect（400ms），只驱动提示与「下一步」的禁用态。
 * 🚨 **网络/后端异常不当成"已占用"**：查不到就按未知处理并放行 —— 前端是体验层，
 *    后端仍会硬拦（409 `CUSTOMER_PRODUCT_NO_TAKEN`）。一次 500 把用户永久挡在第一步是更糟的故障。
 * 🚨 **丢弃过期响应**：连续打字时旧请求晚到不得覆盖新状态（`seqRef` 惯例）。
 */
import React, { useEffect, useRef } from 'react';
import { Alert, Button, Input, Spin } from 'antd';
import { configureProductService } from '../../../services/configureProductService';
import type { CheckProductNoResponse } from '../../../types/configure';
import { Mono, NoteBlock } from './configureUi';

export type ProductNoCheckStatus = 'idle' | 'checking' | 'free' | 'taken' | 'unknown';

export interface ProductNoCheckState {
  status: ProductNoCheckStatus;
  hfPartNo?: string | null;
  createdAt?: string | null;
}

export const IDLE_CHECK: ProductNoCheckState = { status: 'idle' };

interface Props {
  customerNo: string | undefined;
  customerLabel?: string;
  productNo: string;
  productName: string;
  check: ProductNoCheckState;
  onProductNoChange: (v: string) => void;
  onProductNameChange: (v: string) => void;
  onCheckChange: (next: ProductNoCheckState) => void;
  /** 「→ 打开『从产品库添加』并定位到该产品」——由宿主关抽屉 + 开产品库入口并带上编号。 */
  onOpenExistingProducts: (productNo: string) => void;
}

/** debounce 时长：`api.md §2.1` 明确 400ms。 */
const DEBOUNCE_MS = 400;

const CustomerProductNoStep: React.FC<Props> = ({
  customerNo, customerLabel, productNo, productName, check,
  onProductNoChange, onProductNameChange, onCheckChange, onOpenExistingProducts,
}) => {
  const seqRef = useRef(0);

  useEffect(() => {
    const trimmed = productNo.trim();
    if (!trimmed || !customerNo) {
      onCheckChange(IDLE_CHECK);
      return;
    }
    const seq = ++seqRef.current;
    onCheckChange({ status: 'checking' });
    const timer = window.setTimeout(() => {
      configureProductService.checkProductNo(customerNo, trimmed)
        .then((res: CheckProductNoResponse) => {
          if (seqRef.current !== seq) return;           // 已被更新的输入取代，丢弃过期响应
          onCheckChange(res.taken
            ? { status: 'taken', hfPartNo: res.hfPartNo, createdAt: res.createdAt }
            : { status: 'free' });
        })
        .catch(() => {
          if (seqRef.current !== seq) return;
          // 🚨 查不到 ≠ 已占用。放行，交给后端硬拦。
          onCheckChange({ status: 'unknown' });
        });
    }, DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [productNo, customerNo]);

  const status = check.status;
  const inputStatus = status === 'taken' ? 'error' : undefined;

  return (
    <div>
      {customerLabel ? (
        <div style={{ fontSize: 12, color: '#909399', marginBottom: 14 }}>客户：{customerLabel}</div>
      ) : null}

      <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', alignItems: 'flex-start' }}>
        <div style={{ flex: '0 1 360px', minWidth: 260 }}>
          <label style={{ fontSize: 13, display: 'block', marginBottom: 4 }}>
            <span style={{ color: '#ff4d4f', marginRight: 4 }}>*</span>客户产品编号
          </label>
          <Input
            value={productNo}
            status={inputStatus}
            placeholder="请输入客户产品编号"
            /* 状态 D：超长编号不换行、不撑高抽屉；hover 看全文 */
            title={productNo || undefined}
            style={{
              textOverflow: 'ellipsis',
              // 未占用时给绿边框（原型状态 A 的 `.inp.ok`）；AntD 没有 success 状态，走内联样式
              ...(status === 'free' ? { borderColor: '#52c41a' } : {}),
            }}
            /* 🚫 这里只 setState —— 校验在上面的 effect 里异步跑，绝不阻塞输入 */
            onChange={(e) => onProductNoChange(e.target.value)}
          />
          <div style={{ marginTop: 6, fontSize: 12, minHeight: 20 }}>
            {status === 'checking' ? <span style={{ color: '#909399' }}><Spin size="small" /> 正在校验编号是否已被占用…</span> : null}
            {status === 'free' ? <span style={{ color: '#52c41a' }}>✓ 该编号未被占用，可以继续</span> : null}
            {status === 'taken' ? <span style={{ color: '#ff4d4f' }}>该编号已存在，请从产品库添加</span> : null}
            {status === 'unknown' ? <span style={{ color: '#d48806' }}>编号占用校验暂时不可用，可以继续 —— 提交时后端仍会校验</span> : null}
          </div>
        </div>

        <div style={{ flex: '1 1 260px', minWidth: 240 }}>
          <label style={{ fontSize: 13, display: 'block', marginBottom: 4 }}>
            客户产品名称<span style={{ color: '#909399', marginLeft: 4 }}>（选填）</span>
          </label>
          <Input
            value={productName}
            placeholder="请输入"
            title={productName || undefined}
            style={{ textOverflow: 'ellipsis' }}
            onChange={(e) => onProductNameChange(e.target.value)}
          />
        </div>
      </div>

      {status === 'taken' && (
        <Alert
          type="error"
          showIcon
          style={{ marginTop: 16 }}
          message={<b>客户产品编号 {productNo.trim()} 已存在</b>}
          description={(
            <div>
              它已对应销售料号 <Mono>{check.hfPartNo || '（未知）'}</Mono>
              {check.createdAt ? `（创建于 ${String(check.createdAt).slice(0, 10)}）` : ''}。
              同一个客户产品编号只能对应一个销售料号，因此不能在选配里重新配置它。
              <div style={{ marginTop: 10, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                <Button type="primary" size="small" onClick={() => onOpenExistingProducts(productNo.trim())}>
                  → 打开「从产品库添加」并定位到该产品
                </Button>
                <Button size="small" onClick={() => onProductNoChange('')}>换一个编号</Button>
              </div>
            </div>
          )}
        />
      )}

      <NoteBlock>
        <b>为什么把编号放在第一步：</b>现状选配阶段拿不到客户产品编号，导致选配生成的料号在
        「从产品库添加」列表里<b>找不回它</b>。编号前置后，选配产品从一开始就带着客户产品编号。
      </NoteBlock>
    </div>
  );
};

/** 「下一步」的禁用原因（null = 放行）。步骤 1 的判据集中在这里，宿主直接用。 */
export function productNoStepReason(productNo: string, check: ProductNoCheckState): string | null {
  if (!productNo.trim()) return '请先填写客户产品编号';                    // 原型状态 C
  if (check.status === 'checking') return '正在校验客户产品编号，请稍候';
  if (check.status === 'taken') return '客户产品编号已存在，请从产品库添加'; // AC-2
  return null;
}

export default CustomerProductNoStep;
