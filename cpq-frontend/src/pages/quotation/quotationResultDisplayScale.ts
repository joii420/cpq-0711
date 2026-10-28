import { useAuthStore } from '../../stores/authStore';

export type QuotationRole =
  | 'SALES_REP'
  | 'SALES_MANAGER'
  | 'PRICING_MANAGER'
  | 'SYSTEM_ADMIN';

export type QuotationResultDisplayScale = 4 | 9;
export type QuotationDisplaySurface = 'QUOTATION' | 'INDEPENDENT_COSTING';

/** Pure role policy for quotation computed-result presentation only. */
export function quotationResultDisplayScale(
  role: QuotationRole | null | undefined,
): QuotationResultDisplayScale {
  switch (role) {
    case 'SALES_REP':
    case 'SALES_MANAGER':
      return 4;
    case 'PRICING_MANAGER':
    case 'SYSTEM_ADMIN':
    default:
      return 9;
  }
}

/** Subscribe to the smallest raw auth value and derive the cap synchronously. */
export function useQuotationResultDisplayScale(): QuotationResultDisplayScale {
  const role = useAuthStore((state) => state.user?.role);
  return quotationResultDisplayScale(role);
}

/** Keep independent costing surfaces on their existing 9-place presentation. */
export function quotationSurfaceDisplayScale(
  surface: QuotationDisplaySurface,
  quotationScale: number,
): QuotationResultDisplayScale {
  if (surface === 'INDEPENDENT_COSTING') return 9;
  return quotationScale === 4 ? 4 : 9;
}
