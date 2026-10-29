/* eslint-disable react-refresh/only-export-components -- colocate the pure VAT contract with its only UI consumer */
import { formatNumber } from '../../utils/formatNumber';
import {
  isDecimalString,
  toCalculationString,
  toDecimal,
  type DecimalString,
  type DecimalValue,
} from '../../utils/precision';

interface QuotationVatSubtotalProps {
  subtotal: DecimalValue | null | undefined;
  currency?: string;
  vatRateRatio?: DecimalString | null;
  vatRate?: DecimalString | null;
  maxDisplayDecimals: number;
}

/**
 * Resolve the immutable quotation-row VAT snapshot to its 0..1 ratio.
 * A present-but-invalid ratio is authoritative corruption and must not be
 * hidden by falling back to the percentage field.
 */
export function resolveVatRatio(
  vatRateRatio?: DecimalString | null,
  vatRate?: DecimalString | null,
): DecimalString | null {
  const usesRatioSnapshot = vatRateRatio != null;
  const raw = usesRatioSnapshot ? vatRateRatio : vatRate;
  if (!isDecimalString(raw)) return null;

  const ratio = usesRatioSnapshot ? toDecimal(raw) : toDecimal(raw).dividedBy('100');
  if (!ratio.isFinite() || ratio.isNegative() || ratio.greaterThan('1')) return null;
  return toCalculationString(ratio);
}

/** Derive a display-only VAT-inclusive subtotal at the existing 12-place work boundary. */
export function calculateVatInclusiveSubtotal(
  subtotal: DecimalValue | null | undefined,
  vatRateRatio?: DecimalString | null,
  vatRate?: DecimalString | null,
): DecimalString | null {
  const ratio = resolveVatRatio(vatRateRatio, vatRate);
  if (ratio == null) return null;
  return toCalculationString(
    toDecimal(subtotal ?? '0').times(toDecimal('1').plus(ratio)),
  );
}

function formatComputedAmount(value: DecimalValue, maxDisplayDecimals: number): string {
  return formatNumber(value, { isComputed: true, maxDisplayDecimals }) ?? '0';
}

/** Shared quotation-only subtotal presentation for edit and readonly product cards. */
export function QuotationVatSubtotal({
  subtotal,
  currency,
  vatRateRatio,
  vatRate,
  maxDisplayDecimals,
}: QuotationVatSubtotalProps) {
  const resolvedCurrency = currency?.trim() || 'CNY';
  const normalizedSubtotal = toDecimal(subtotal ?? '0').toString();
  const vatInclusiveSubtotal = calculateVatInclusiveSubtotal(
    normalizedSubtotal,
    vatRateRatio,
    vatRate,
  );
  const subtotalText = `${formatComputedAmount(normalizedSubtotal, maxDisplayDecimals)} ${resolvedCurrency}`;
  const vatText = vatInclusiveSubtotal == null
    ? '-'
    : `${formatComputedAmount(vatInclusiveSubtotal, maxDisplayDecimals)} ${resolvedCurrency}`;

  return (
    <div className="qt-subtotal-quote-lines">
      <div className="qt-subtotal-line qt-subtotal-total qt-subtotal-quote-line">
        <span className="qt-subtotal-value qt-subtotal-quote-value">{subtotalText}</span>
      </div>
      <div className="qt-subtotal-line qt-subtotal-quote-line qt-subtotal-vat-line">
        <span className="qt-subtotal-vat-content">
          <span className="qt-subtotal-vat-prefix">(VAT) </span>
          <span className="qt-subtotal-vat-value">{vatText}</span>
        </span>
      </div>
    </div>
  );
}

export default QuotationVatSubtotal;
