import api from './api';

export interface TemplateFormula {
  name: string;
  expression: string;
  dataType: string;
  dependsOn?: string[];
  description?: string;
}

export interface EvaluateContext {
  customerId: string;
  partNo: string;
}

export interface EvaluateResult {
  value: any;
  trace?: Record<string, any>;
}

// ---- API 1: formula-completions ----
export interface CompletionField {
  name: string;
  label?: string;
  dataType?: string;
}

export interface CompletionComponent {
  code: string;
  name: string;
  fields: CompletionField[];
}

export interface CompletionGlobalVariable {
  name: string;
  code?: string;
  dataType?: string;
  currentValue?: any;
  description?: string;
}

export interface FormulaCompletionsResponse {
  templateFormulas: { name: string; dataType: string; description?: string }[];
  components: CompletionComponent[];
  globalVariables: CompletionGlobalVariable[];
}

// ---- API 2: functions ----
export interface FunctionParam {
  name: string;
  type: 'Component' | 'Expression' | 'Scalar';
  required: boolean;
  description: string;
}

export interface FunctionExample {
  expression: string;
  explanation: string;
}

export interface FunctionDef {
  name: string;
  category: '聚合' | '条件' | '算术' | '数学';
  signature: string;
  description: string;
  examples: FunctionExample[];
  params: FunctionParam[];
}

// ---- API 3 extended: structured evaluate error ----
export interface EvaluateErrorSuggestion {
  description: string;
  replacement?: string;
  at?: number;
}

export interface EvaluateError {
  line?: number;
  column?: number;
  severity: 'ERROR' | 'WARNING';
  code: 'PARSE_ERROR' | 'UNKNOWN_REF' | 'TYPE_MISMATCH' | 'CIRCULAR_DEP' | 'RUNTIME_ERROR';
  message: string;
  suggestions?: EvaluateErrorSuggestion[];
}

export interface EvaluateResultExtended {
  value?: number | string | boolean | null;
  trace?: Record<string, any>;
  error?: EvaluateError;
}

// ---- Mock data (used until real API /formulas/functions is available) ----
const MOCK_FUNCTIONS: FunctionDef[] = [
  {
    name: 'SUM_OVER',
    category: '聚合',
    signature: 'SUM_OVER([组件] WHERE 条件, 表达式)',
    description: '对组件的多行数据按条件求和',
    examples: [
      {
        expression: 'SUM_OVER([COMP-V5-RAW-BOM] WHERE input_qty > 0, unit_price * input_qty)',
        explanation: '对投料组件中数量>0的行求价值合计',
      },
    ],
    params: [
      { name: 'source', type: 'Component', required: true, description: '数据来源组件，格式 [组件CODE]' },
      { name: 'where', type: 'Expression', required: false, description: '过滤条件（可省略）' },
      { name: 'expression', type: 'Expression', required: true, description: '每行的计算表达式' },
    ],
  },
  {
    name: 'COUNT_OVER',
    category: '聚合',
    signature: 'COUNT_OVER([组件] WHERE 条件)',
    description: '对组件的多行数据按条件计数',
    examples: [
      {
        expression: 'COUNT_OVER([COMP-V5-RAW-BOM] WHERE input_qty > 0)',
        explanation: '统计投料组件中数量>0的行数',
      },
    ],
    params: [
      { name: 'source', type: 'Component', required: true, description: '数据来源组件' },
      { name: 'where', type: 'Expression', required: false, description: '过滤条件（可省略）' },
    ],
  },
  {
    name: 'AVG_OVER',
    category: '聚合',
    signature: 'AVG_OVER([组件] WHERE 条件, 表达式)',
    description: '对组件的多行数据按条件求平均值',
    examples: [
      {
        expression: 'AVG_OVER([COMP-V5-RAW-BOM], unit_price)',
        explanation: '求投料组件所有行的平均单价',
      },
    ],
    params: [
      { name: 'source', type: 'Component', required: true, description: '数据来源组件' },
      { name: 'where', type: 'Expression', required: false, description: '过滤条件（可省略）' },
      { name: 'expression', type: 'Expression', required: true, description: '每行的计算表达式' },
    ],
  },
  {
    name: 'MIN_OVER',
    category: '聚合',
    signature: 'MIN_OVER([组件] WHERE 条件, 表达式)',
    description: '对组件的多行数据按条件求最小值',
    examples: [
      { expression: 'MIN_OVER([COMP-V5-RAW-BOM], unit_price)', explanation: '求最小单价' },
    ],
    params: [
      { name: 'source', type: 'Component', required: true, description: '数据来源组件' },
      { name: 'where', type: 'Expression', required: false, description: '过滤条件（可省略）' },
      { name: 'expression', type: 'Expression', required: true, description: '每行的计算表达式' },
    ],
  },
  {
    name: 'MAX_OVER',
    category: '聚合',
    signature: 'MAX_OVER([组件] WHERE 条件, 表达式)',
    description: '对组件的多行数据按条件求最大值',
    examples: [
      { expression: 'MAX_OVER([COMP-V5-RAW-BOM], unit_price)', explanation: '求最大单价' },
    ],
    params: [
      { name: 'source', type: 'Component', required: true, description: '数据来源组件' },
      { name: 'where', type: 'Expression', required: false, description: '过滤条件（可省略）' },
      { name: 'expression', type: 'Expression', required: true, description: '每行的计算表达式' },
    ],
  },
  {
    name: 'IF',
    category: '条件',
    signature: 'IF(条件, 真值, 假值)',
    description: '条件判断，返回真值或假值',
    examples: [
      { expression: 'IF([数量] > 0, [单价] * [数量], 0)', explanation: '数量>0时计算金额，否则返回0' },
    ],
    params: [
      { name: 'condition', type: 'Expression', required: true, description: '布尔条件表达式' },
      { name: 'trueValue', type: 'Scalar', required: true, description: '条件为真时返回的值' },
      { name: 'falseValue', type: 'Scalar', required: true, description: '条件为假时返回的值' },
    ],
  },
  {
    name: 'COALESCE',
    category: '条件',
    signature: 'COALESCE(a, b, c, ...)',
    description: '返回参数列表中第一个非 null 值',
    examples: [
      { expression: 'COALESCE([折扣率], 1.0)', explanation: '折扣率为空时使用默认值 1.0' },
    ],
    params: [
      { name: 'values', type: 'Scalar', required: true, description: '候选值列表，至少一个' },
    ],
  },
  {
    name: 'NULLIF',
    category: '条件',
    signature: 'NULLIF(a, b)',
    description: '当 a == b 时返回 null，否则返回 a（常用于防除零）',
    examples: [
      { expression: 'NULLIF([分母], 0)', explanation: '分母为0时返回null，避免除零错误' },
    ],
    params: [
      { name: 'a', type: 'Scalar', required: true, description: '被比较值' },
      { name: 'b', type: 'Scalar', required: true, description: '比较基准值' },
    ],
  },
  {
    name: 'ABS',
    category: '数学',
    signature: 'ABS(x)',
    description: '返回数值的绝对值',
    examples: [
      { expression: 'ABS([差额])', explanation: '取差额绝对值' },
    ],
    params: [
      { name: 'x', type: 'Scalar', required: true, description: '数值表达式' },
    ],
  },
];

// In-memory cache for completions and functions
const completionsCache: Map<string, FormulaCompletionsResponse> = new Map();
let functionsCache: FunctionDef[] | null = null;

export const templateFormulaService = {
  list: (templateId: string): Promise<any> =>
    api.get(`/templates/${templateId}/formulas`),

  add: (templateId: string, data: TemplateFormula): Promise<any> =>
    api.post(`/templates/${templateId}/formulas`, data),

  update: (templateId: string, name: string, data: TemplateFormula): Promise<any> =>
    api.put(`/templates/${templateId}/formulas/${encodeURIComponent(name)}`, data),

  delete: (templateId: string, name: string): Promise<any> =>
    api.delete(`/templates/${templateId}/formulas/${encodeURIComponent(name)}`),

  evaluate: (templateId: string, name: string, ctx: EvaluateContext): Promise<any> =>
    api.post(
      `/templates/${templateId}/formulas/${encodeURIComponent(name)}/evaluate`,
      ctx
    ),

  /** API 1: 获取自动补全候选列表（含 in-memory cache） */
  getCompletions: async (templateId: string): Promise<FormulaCompletionsResponse> => {
    if (completionsCache.has(templateId)) {
      return completionsCache.get(templateId)!;
    }
    try {
      // api.get 返回的是 ApiResponse 信封 { code, message, data },需要解包 .data
      const result = (await api.get(`/templates/${templateId}/formula-completions`)) as unknown as { data: FormulaCompletionsResponse };
      const data: FormulaCompletionsResponse = result?.data ?? {
        templateFormulas: [],
        components: [],
        globalVariables: [],
      };
      completionsCache.set(templateId, data);
      return data;
    } catch {
      // 后端 API 未就绪时返回空结构，避免 UI 崩溃
      const fallback: FormulaCompletionsResponse = {
        templateFormulas: [],
        components: [],
        globalVariables: [],
      };
      return fallback;
    }
  },

  /** 清除 completions 缓存（切换模板时调用） */
  clearCompletionsCache: (templateId?: string) => {
    if (templateId) {
      completionsCache.delete(templateId);
    } else {
      completionsCache.clear();
    }
  },

  /** API 2: 获取函数定义列表（含 in-memory cache，后端未就绪时使用 mock） */
  getFunctions: async (): Promise<FunctionDef[]> => {
    if (functionsCache !== null) {
      return functionsCache;
    }
    try {
      // api.get 返回的是 ApiResponse 信封 { code, message, data },需要解包 .data
      const result = (await api.get('/formulas/functions')) as unknown as { data: FunctionDef[] };
      const data: FunctionDef[] = Array.isArray(result?.data) ? result.data : [];
      if (data.length === 0) {
        // 后端有响应但 data 为空,退化到 mock 保证函数选择器可用
        functionsCache = MOCK_FUNCTIONS;
        return MOCK_FUNCTIONS;
      }
      functionsCache = data;
      return data;
    } catch {
      // 后端 API 未就绪时使用 mock 数据
      functionsCache = MOCK_FUNCTIONS;
      return MOCK_FUNCTIONS;
    }
  },

  /** 清除函数缓存（仅调试用） */
  clearFunctionsCache: () => {
    functionsCache = null;
  },
};
