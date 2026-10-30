import { formatNumber } from '../../utils/formatNumber';
import {
  toCalculationString,
  toDecimal,
  type DecimalString,
  type DecimalValue,
} from '../../utils/precision';
import { calculateVatInclusiveSubtotal } from './QuotationVatSubtotal';

interface CostingSubtotalSummaryProps {
  /** Costing product subtotal in CNY; `null` means the card has no costing data. */
  subtotal: DecimalValue | null;
  /** Quotation currency; blank (after trim) is treated as CNY. */
  currency?: string;
  /** Quotation exchange rate; missing / non-numeric / zero falls back to 1. */
  exchangeRate?: DecimalValue | null;
  vatRateRatio?: DecimalString | null;
  vatRate?: DecimalString | null;
  maxDisplayDecimals: number;
}

interface CostingColumn {
  currency: string;
  mainText: string;
  vatText: string;
  empty: boolean;
}

const BASE_CURRENCY = 'CNY';
const EMPTY_TEXT = '-';

function formatAmount(value: DecimalValue, currency: string, maxDisplayDecimals: number): string {
  const text = formatNumber(value, { isComputed: true, maxDisplayDecimals }) ?? '0';
  return `${text} ${currency}`;
}

function resolveExchangeRate(exchangeRate: DecimalValue | null | undefined): DecimalValue {
  const rate = toDecimal(exchangeRate ?? null);
  return rate.isZero() ? '1' : rate;
}

function buildColumns({
  subtotal,
  currency,
  exchangeRate,
  vatRateRatio,
  vatRate,
  maxDisplayDecimals,
}: CostingSubtotalSummaryProps): CostingColumn[] {
  if (subtotal == null) {
    return [{ currency: '', mainText: EMPTY_TEXT, vatText: EMPTY_TEXT, empty: true }];
  }

  // Display-only derivations at the 12-place work boundary; nothing here is written back.
  const cnySubtotal = toCalculationString(subtotal);
  const cnyVatInclusive = calculateVatInclusiveSubtotal(cnySubtotal, vatRateRatio, vatRate);
  const columns: CostingColumn[] = [{
    currency: BASE_CURRENCY,
    mainText: formatAmount(cnySubtotal, BASE_CURRENCY, maxDisplayDecimals),
    vatText: cnyVatInclusive == null
      ? EMPTY_TEXT
      : formatAmount(cnyVatInclusive, BASE_CURRENCY, maxDisplayDecimals),
    empty: false,
  }];

  const quoteCurrency = currency?.trim() || BASE_CURRENCY;
  if (quoteCurrency === BASE_CURRENCY) return columns;

  const rate = resolveExchangeRate(exchangeRate);
  const foreignSubtotal = toCalculationString(toDecimal(cnySubtotal).dividedBy(rate));
  const foreignVatInclusive = cnyVatInclusive == null
    ? null
    : toCalculationString(toDecimal(cnyVatInclusive).dividedBy(rate));
  columns.push({
    currency: quoteCurrency,
    mainText: formatAmount(foreignSubtotal, quoteCurrency, maxDisplayDecimals),
    vatText: foreignVatInclusive == null
      ? EMPTY_TEXT
      : formatAmount(foreignVatInclusive, quoteCurrency, maxDisplayDecimals),
    empty: false,
  });
  return columns;
}

/**
 * Costing-only product card amount area shared by the edit card (QuotationStep2) and the
 * readonly card (ReadonlyProductCard): columns by currency (CNY first), rows by tax.
 * Pure presentation: no hooks, effects or network.
 */
export function CostingSubtotalSummary(props: CostingSubtotalSummaryProps) {
  const columns = buildColumns(props);
  return (
    <div className="qt-subtotal-costing">
      {columns.map((col, index) => (
        <div
          key={col.empty ? 'empty' : `${index}-${col.currency}`}
          className="qt-subtotal-costing-col"
          data-currency={col.currency}
          data-empty={col.empty ? 'true' : undefined}
        >
          <div className="qt-subtotal-costing-main">{col.mainText}</div>
          <div className="qt-subtotal-costing-vat">
            <span className="qt-subtotal-vat-prefix">(VAT) </span>
            <span className="qt-subtotal-vat-value">{col.vatText}</span>
          </div>
        </div>
      ))}
    </div>
  );
}

export default CostingSubtotalSummary;
