# fronttask · 元素价格支持 9 位小数

> **只按本文件实现前端。** AC 原文在 `需求文档.md §③`，本文件只标编号 —— **两者有出入以 AC 原文为准并向主线报告**。
> 显示口径（用户裁决 D-3）：**最多 9 位，去掉末尾 0**。录入口径：输入框最多 9 位，失焦时 `HALF_UP` 舍入。

---

## 原型图：本任务不出

`frontend.md §1.3` 触发条件逐条对照：不新增页面 / 抽屉 / 弹层，不改布局、控件类型与顺序、交互流程、表格列与工具栏动作 —— **只改既有数字的显示位数与输入框精度**，属于「纯逻辑 / 单点微调」。
⇒ 不出原型；视觉基准就是**现有页面本身**，改动后除数字位数外**逐屏不应有任何变化**（亲验时比对）。

---

## 任务清单

| 编号 | 服务的 AC | 位置 | 任务内容 |
|---|---|---|---|
| **F-0** | AC-7, AC-9, AC-12~AC-19 | `src/utils/precision.ts` | 新增导出常量 `ELEMENT_PRICE_SCALE = 9`（与后端 `PrecisionPolicy.ELEMENT_PRICE_SCALE` 同名同值，见 `backtask.md B-2`）。下面 F-1~F-13 **一律引用这个常量**，🚫 不许再写字面量 `9`，也不要直接用 `DISPLAY_SCALE` 代替（那是显示边界，含义不同） |
| **F-1** | AC-12, AC-18 | `pages/element-price/PriceDetailTab.tsx` | 明细表「单价」列：`formatDisplayDecimal(v, 4)` → 按 F-0 |
| **F-2** | AC-13 | `pages/element-price/PriceMatrixTab.tsx` | 矩阵格子单价：`formatDisplayDecimal(v, 2)` → 按 F-0 |
| **F-3** | AC-18 | `pages/element-price/PriceHistoryTab.tsx` | 变更历史的快照摘要单价：`formatDisplayDecimal(snap.price, 4)` → 按 F-0（「A → B」变更文本由后端 `B-5` 生成，前端原样显示） |
| **F-4** | AC-9, AC-18 | `pages/element-price/PriceEditDrawer.tsx` | 「单价」输入框 `precision={4}` → 按 F-0 |
| **F-5** | AC-7, AC-8, AC-18 | `pages/element-price/PriceImportDrawer.tsx` | 导入结果表「单价」列：`formatDisplayDecimal(v, 4)` → 按 F-0（「说明」列文本由后端 `B-3` 生成，前端原样显示） |
| **F-6** | AC-14 | `pages/config/ElementEditDrawer.tsx` | 「各价格源最新价」单价：`fmtPrice` 的 4 → 按 F-0 |
| **F-7** | AC-19 | `pages/pricing/element-strategy/ElementPriceStrategyTab.tsx` | ① 例外列表「系数」「加价」列：`formatDisplayDecimal(v, 2)` → 按 F-0 ② 默认策略表单「系数」「加价」输入框加 `precision` = F-0 |
| **F-8** | AC-19 | `pages/pricing/element-strategy/StrategyExceptionEditDrawer.tsx` | 「新增 / 编辑元素例外」抽屉的「系数」「加价」输入框加 `precision` = F-0 |
| **F-9** | AC-19 | `pages/pricing/element-strategy/StrategyHistoryDrawer.tsx` | 新增记录摘要与删除前摘要里的系数、加价：`formatDisplayDecimal(…, 2)` → 按 F-0（修改记录的「A → B」文本由后端 `B-7` 生成） |
| **F-10** | AC-15 | `pages/pricing/element-strategy/StrategySimulateDrawer.tsx` | 「取值结果」「最终单价」（`num()` 的 4）与「× 系数」「+ 加价」（2）→ 按 F-0 |
| **F-11** | AC-16 | `pages/pricing/price-adjust/ElementMatrix.tsx` | 元素矩阵格子单价 `formatPrice` 的 `decimals: 2` → 按 F-0。🚫 同文件的涨跌幅百分比（`formatRate`）不动 |
| **F-12** | AC-16 | `pages/pricing/price-adjust/VersionTrailPanel.tsx` | 版本明细抽屉「本期价」「上期价」`formatPrice` 的 `decimals: 2` → 按 F-0。🚫 涨跌百分比不动 |
| **F-13** | AC-17 | `pages/pricing/price-adjust-review/ReviewDetailDrawer.tsx` | **只改**元素表「上版价」「本版价」两列 → 按 F-0。🚫 同文件 `fmt()` 的默认 2 位**不改**，其余列（对单价影响 / 阈值 / 报价·现 / 报价·调整后 / 核价·现 / 核价·调整后 / 现小计等金额）保持原样 —— 给这两列单独传位数，不要改 `fmt` 的默认值 |
| **F-14** | AC-20 | 单测 | 为 F-0 补单测：`ELEMENT_PRICE_SCALE === 9`；`formatDisplayDecimal('101.139210000000', ELEMENT_PRICE_SCALE) === '101.13921'`；`'105.000000000000' → '105'`；`'2.0000000005' → '2.000000001'`。跑改动文件相关的既有前端单测，受口径影响需改断言的逐个列出理由 |

---

## 强联动检查点清单（写完后逐项勾）

| # | 检查点 | 预期 | 勾 |
|---|---|---|---|
| FK-1 | 全前端「元素单价 / 系数 / 加价」相关的写死位数 | 用 `/usr/bin/grep -a -rn "formatDisplayDecimal([^)]*, *[0-8])\|decimals: *[0-8]\b\|precision={[0-8]}" cpq-frontend/src --include=*.tsx --include=*.ts` 复扫；**剩余命中逐条说明为何不是元素单价**（主线立项时实查：报价总额、审核页成本 / 阈值、调价阈值输入、涨跌幅百分比属于保留项），输出贴回报 | ☐ |
| FK-2 | 金额类列未被连带改动 | F-13 同文件其他列、`PriceAdjustReviewPage.tsx`、`QuotationPriceRevisionsDrawer.tsx`、`ComparisonColumnPanel.tsx`、`PriceAdjustStrategyTab.tsx` 的阈值输入 **0 改动** | ☐ |
| FK-3 | 接口调用形状 | 不改任何请求 / 响应字段；精度值仍按字符串收发（`stringMode`） | ☐ |

---

## 不在本文件范围（前端视角）

- 🚫 不改报价单 / 核价单页签里的「元素单价」列（按字段默认显示，自动带出 9 位）
- 🚫 不改导出、不改任何金额类列、不改涨跌幅百分比
- 🚫 不顺手迁移遗留 `Modal`（`PricingStrategy.tsx` 里有一个，**只报位置不改**）
