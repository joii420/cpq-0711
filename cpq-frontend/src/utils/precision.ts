import Decimal from 'decimal.js';

/**
 * 精度基础设施 — task-0801「公式计算精度优化」。
 *
 * 背景（详见 dev-docs/task-0801-公式计算精度优化/需求说明.md §4.3 + api.md §1）：
 * 区分「计算精度」与「呈现精度」两个概念 —— 计算过程中不做任何中间截断（十进制精确，
 * 除法保留 DIVISION_SCALE 位中间精度），只在 4 个边界（落库/API返回/界面显示/导出）规整到
 * DISPLAY_SCALE 位。前后端 DISPLAY_SCALE / DIVISION_SCALE 必须同值（后端见
 * `com/cpq/common/PrecisionPolicy.java`），由双方各自的单测锁定。
 *
 * 本任务**推翻**了 2026-06-21 拍板的旧口径（计算列/列小计/页签合计=4位；产品小计+对外导出=2位，
 * 见 docs/RECORD.md 2026-06-21 两条记录 + docs/反模式.md 相关注释）。该旧口径自本任务起作废，
 * 全部改为「至多 6 位、去尾零」的统一呈现精度。
 */

/** 呈现精度：显示 / 落库 payload 边界统一 6 位。与后端 PrecisionPolicy.DISPLAY_SCALE 对齐（api.md §5.1）。 */
export const DISPLAY_SCALE = 6;

/**
 * 除法中间精度：公式内部四则运算求值时，遇到无限小数（如 1/3）的除法在此位截断，
 * 避免除法产生的无限小数扩散污染后续运算；加减乘不受此约束（十进制精确，无需截断）。
 * 与后端 PrecisionPolicy.DIVISION_SCALE 对齐（api.md §5.1）。
 */
export const DIVISION_SCALE = 12;

/**
 * payload 规范化位数 —— 按「有效数字」而非「小数位数」规整（2026-08-01 二次修订，技术总监补充指令）。
 *
 * ## 为什么不是 fronttask.md 原定的 `PAYLOAD_NORMALIZE_SCALE = 10`（按小数位数 toFixed）
 * 实测发现取数列真实精度：
 *   - tooling_cost.tooling_unit_price  列声明 scale=8；现网有真实 8 位样本
 *     （0.01333333，material_no = S-3120014539 / S-3120018220）
 *   - production_energy.unit_price    列声明 **scale=12** ← 10 位会压坏它，违反 AC-8
 *     （现网 16 行实测第 7 位起全是 0，尚无真实 12 位样本，但**按列声明的 scale 设计，不能只按
 *     现网样本设计** —— 否则将来一旦写入真 12 位数据就会被静默压坏，且难以复现排查）
 *   - unit_price.pricing_price        最大 6 位小数
 *
 * 即：现网实际有效精度目前最高只到 8 位，但列结构声明到 12 位；`toPrecision(15)` 方案对两者
 * 都稳健覆盖（无论未来是否真的写入 12 位数据都不会被压），15 位不是拍脑袋定的，而是留出比
 * 已知最深声明精度（12 位小数，通常伴随个位数到两位数的整数部分）更宽的安全边际。
 *
 * ## 为什么也不是简单调高到 14 位（仍按小数位数 toFixed）
 * `normalizeDraftPayloadNumbers` 原实现用 `Number(v.toFixed(N))` —— 按**小数位数**规整。
 * 位数调高在大数值上会把 double 表示噪声**暴露**出来而不是消除：
 * ```js
 * (98765431.2).toFixed(14)   // "98765431.19999999552965"  ← 噪声被暴露，不是被消除
 * (0.07).toFixed(14)         // "0.07000000000000"          ← 小值没问题
 * ```
 * 根因：「小值高精度取数列（12 位小数）」与「大值金额（亿级整数位）」在同一份 payload 里并存，
 * 按小数位数一刀切是根本矛盾的 —— 位数低了压坏取数列，位数高了在大数值上暴露噪声。
 *
 * ## 正确改法：按有效数字规整（不按小数位数）
 * `toPrecision(15)`（double 可靠的有效数字上限，15~17 位中取更保守的 15）。
 * 有效数字与数值的整数位/小数位无关，因此同时满足：
 *   - 消除浮点尾差：`Number((0.1+0.2).toPrecision(15))` → `0.3`（尾差在第 16~17 位有效数字，15 位足以截掉）
 *   - 不压坏取数列：`Number((0.070000000000123).toPrecision(15))` 12 位小数原样保留（整数位为 0，
 *     有效数字远小于 15 位）
 *   - 不在大数值上暴露噪声：`Number((98765431.2).toPrecision(15))` → `98765431.2`（只有 9 位有效数字，
 *     15 位精度下原样保留，不会像 toFixed(14) 那样把 double 内部表示噪声暴露出来）
 *
 * 计算值（公式结果）不会因发 15 位有效数字而"变相不规整"——后端在落库边界统一
 * `PrecisionPolicy.round()` 到 6 位（backtask.md Task B5），前端发 15 位有效数字、
 * 后端落 6 位，结果一致；这是约定好的不对称，不是 bug（同 fronttask.md §3「与后端的协作点」）。
 *
 * ⚠️ 下一个人如果想"顺手统一成按小数位数 toFixed(N)"，请先看上面这段反例 —— 按小数位数
 * 规整在本场景（小值高精度 + 大值低精度并存）是无法同时满足 AC-8 与"消噪声"两个目标的。
 */
export const PAYLOAD_SIGNIFICANT_DIGITS = 15;

/** 任意值 → Decimal（null/空/非数字 → 0），唯一转换入口。 */
export function toDecimal(v: unknown): Decimal {
  if (v == null || v === '') return new Decimal(0);
  if (v instanceof Decimal) return v;
  try {
    const d = typeof v === 'number'
      ? new Decimal(Number.isFinite(v) ? v : 0)
      : new Decimal(String(v).trim());
    return d.isFinite() ? d : new Decimal(0);
  } catch {
    return new Decimal(0);
  }
}

/** 精确累加：Σ toDecimal(v)。非数字 / 空值按 0 参与（与 toDecimal 语义一致）。 */
export function sumDecimal(values: Array<unknown>): Decimal {
  let sum = new Decimal(0);
  for (const v of values) sum = sum.plus(toDecimal(v));
  return sum;
}

/** 规整到呈现精度（DISPLAY_SCALE，HALF_UP）并转 number —— 仅在离开计算链路（要显示/落 payload）时用。 */
export function roundToDisplay(v: Decimal | number): number {
  const d = v instanceof Decimal ? v : toDecimal(v);
  return d.toDecimalPlaces(DISPLAY_SCALE, Decimal.ROUND_HALF_UP).toNumber();
}

/**
 * payload 数值规范化 —— 按有效数字消除浮点尾差，供 `QuotationWizard.normalizeDraftPayloadNumbers`
 * 调用。不是 `formatNumber` / `roundToDisplay` 的替代品：那两者服务"呈现精度"（DISPLAY_SCALE=6，
 * 会砍掉取数列的真实精度）；这个函数只做"发给后端前抹掉 double 表示噪声"，不改变数值的有效位数。
 * 详见上方 PAYLOAD_SIGNIFICANT_DIGITS 注释。
 */
export function normalizeNumber(v: number): number {
  if (!Number.isFinite(v) || v === 0) return v;
  return Number(v.toPrecision(PAYLOAD_SIGNIFICANT_DIGITS));
}

// ═══════════════════════════════════════════════════════════════════════════
// evaluateArithmetic —— 十进制精确表达式求值器（递归下降）
// ═══════════════════════════════════════════════════════════════════════════
//
// 不使用 eval / new Function（安全 + 十进制精确）。语义与后端
// `FormulaCalculator.ArithParser`（cpq-backend/src/main/java/com/cpq/quotation/service/
// FormulaCalculator.java:1961-2030）逐条对齐：
//   - 运算符 + - * /、括号，标准优先级（* / 高于 + -）                        (G-13)
//   - 一元正负号，如 -(2+3)*2 = -10                                          (G-12)
//   - 除法走 DIVISION_SCALE(12) 位中间精度截断                                (G-3/G-4)
//   - 除以 0 → 返回 0（不抛异常，与既有 catch→0 行为一致）                    (G-9)
//   - 非法表达式（含空表达式）→ 返回 null（保持 formulaEngine.ts:703 现有约定）
//   - 空值参与运算按 0（由调用方在拼表达式字符串阶段已处理，与本解析器无关）    (G-8)
//   - 全角运算符 × ÷ 在入口处转换（不依赖调用方预转换）                       (G-14)
//
// 额外兼容（非语义变化）：数字字面量支持科学计数法（如 "1e-7"）—— 调用方
// （formulaEngine.ts 的 token 拼接逻辑）用 `(n).toString()` 生成数值字面量时，
// 极小/极大的 n 会被 JS 原生转成科学计数法字符串；旧引擎用 `new Function` 真实执行 JS，
// 天然认识这种写法，若本解析器不识别会导致「本应能算的表达式退化成非法表达式」的静默回归。
// 后端 ArithParser 没有这个分支是因为其数字字面量来自公式文本本身（不会被程序拼接出科学计数法）。

class ArithDecimalParser {
  private readonly s: string;
  private i = 0;

  constructor(s: string) {
    this.s = s;
  }

  parse(): Decimal {
    const v = this.expr();
    this.skip();
    if (this.i < this.s.length) throw new Error(`trailing: ${this.s.slice(this.i)}`);
    return v;
  }

  private skip(): void {
    while (this.i < this.s.length && this.s[this.i] === ' ') this.i++;
  }

  private expr(): Decimal {
    let v = this.term();
    for (;;) {
      this.skip();
      const c = this.s[this.i];
      if (c === '+' || c === '-') {
        this.i++;
        const r = this.term();
        v = c === '+' ? v.plus(r) : v.minus(r);
      } else break;
    }
    return v;
  }

  private term(): Decimal {
    let v = this.factor();
    for (;;) {
      this.skip();
      const c = this.s[this.i];
      if (c === '*' || c === '/') {
        this.i++;
        const r = this.factor();
        if (c === '*') {
          v = v.times(r);
        } else {
          // 除以 0 → 0（不抛异常，G-9）；否则按 DIVISION_SCALE 中间精度截断（G-3/G-4）。
          v = r.isZero()
            ? new Decimal(0)
            : v.dividedBy(r).toDecimalPlaces(DIVISION_SCALE, Decimal.ROUND_HALF_UP);
        }
      } else break;
    }
    return v;
  }

  private factor(): Decimal {
    this.skip();
    if (this.i >= this.s.length) throw new Error('unexpected eof');
    const c = this.s[this.i];
    if (c === '+') { this.i++; return this.factor(); }
    if (c === '-') { this.i++; return this.factor().negated(); }
    if (c === '(') {
      this.i++;
      const v = this.expr();
      this.skip();
      if (this.s[this.i] !== ')') throw new Error('missing )');
      this.i++;
      return v;
    }
    return this.number();
  }

  private number(): Decimal {
    this.skip();
    const start = this.i;
    while (this.i < this.s.length && (this.isDigit(this.s[this.i]) || this.s[this.i] === '.')) this.i++;
    if (this.i === start) throw new Error(`expected number at ${this.i}`);
    // 科学计数法兼容（见文件头注释）：数字部分之后紧跟 e/E [+-]? 数字+ 才消费，否则回退。
    if (this.s[this.i] === 'e' || this.s[this.i] === 'E') {
      const mark = this.i;
      this.i++;
      if (this.s[this.i] === '+' || this.s[this.i] === '-') this.i++;
      const expStart = this.i;
      while (this.i < this.s.length && this.isDigit(this.s[this.i])) this.i++;
      if (this.i === expStart) this.i = mark; // 非法指数部分：回退，交由外层判非法表达式
    }
    return new Decimal(this.s.slice(start, this.i));
  }

  private isDigit(ch: string): boolean {
    return ch >= '0' && ch <= '9';
  }
}

/**
 * 十进制精确表达式求值。expr 只应包含数字/运算符/括号/空格（与后端 ArithParser 同源约束）。
 * @returns 求值结果 Decimal；表达式为空/格式非法 → null。除以 0 在内部按 0 处理（不返回 null）。
 */
export function evaluateArithmetic(expr: string | null | undefined): Decimal | null {
  if (typeof expr !== 'string') return null;
  const normalized = expr.replace(/×/g, '*').replace(/÷/g, '/');
  if (!normalized.trim()) return null;
  try {
    const v = new ArithDecimalParser(normalized).parse();
    return v.isFinite() ? v : new Decimal(0);
  } catch {
    return null;
  }
}
