export type CurrencyCode = string;

export interface CurrencyDTO {
  code: CurrencyCode;
  name: string;
  symbol?: string | null;
  enabled: boolean;
  isBase: boolean;
  sortOrder?: number;
  latestRate?: string | null;
  latestRateDate?: string | null;
}

export interface ExchangeRateDTO {
  id?: string;
  rate: string;
  displayRate?: string;
  effectiveDate: string;
  source?: string | null;
  status?: string;
  fetchedAt?: string | null;
  updatedAt?: string | null;
}

export type ExchangeRateMethod =
  | 'LATEST'
  | 'AVG'
  | 'MAX'
  | 'MIN'
  | 'SPECIFIC_DATE'
  | 'PREVIOUS_MONTH_DAY'
  | 'MONTH_DAY'
  | 'PREVIOUS_MONTH_END';

export type ExchangeRateWindowUnit = 'DAY' | 'WEEK' | 'MONTH' | 'YEAR';

export interface ExchangeRateStrategyDTO {
  id?: string | null;
  customerNo: string;
  method: ExchangeRateMethod;
  windowNum?: number | null;
  windowUnit?: ExchangeRateWindowUnit | null;
  specificDate?: string | null;
  dayOfMonth?: number | null;
  version?: number | null;
  updatedAt?: string | null;
  updatedByName?: string | null;
}

export interface ExchangeRateSimulationResult {
  currency?: string;
  method: ExchangeRateMethod;
  baseDate?: string;
  effectiveDate?: string | null;
  rate?: string | null;
  targetDate?: string | null;
  skippedMonths?: string[];
  dailyRates?: Array<{ effectiveDate: string; rate: string }>;
  matchedRate?: string | null;
  participantCount?: number;
  finalRate?: string | null;
  error?: string | null;
}
