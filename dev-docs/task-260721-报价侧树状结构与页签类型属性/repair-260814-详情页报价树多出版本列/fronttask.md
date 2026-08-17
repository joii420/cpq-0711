# fronttask · repair-0814 详情页报价树多出「版本」列

> 本返修 **100% 是前端渲染改动**，无后端、无接口、无数据变更。

## F0 范围与不变量

| 项 | 结论 |
|---|---|
| 改动性质 | 纯渲染层：给已有的「版本」系统列补 `isCosting` 闸门 |
| 不触碰 | 取数（driver expand）、公式求值、快照结构、落库、任何 API |
| 判据来源 | 与编辑页同源：`ReadonlyProductCard` 的 `isCosting`（= `side === 'COSTING'`）↔ `QuotationStep2` 的 `cardSide === 'COSTING'` |
| 值不变 | 小计 / 合计 / 产品小计的计算入参与函数一行未动 |

## F1 `ReadonlyProductCard.tsx`（详情页 / 只读卡片）—— 5 处

| # | 位置 | 改法 |
|---|---|---|
| F1-a | 表头 `<thead>` BOM 系统列 | `<th>版本</th>` → `{isCosting && <th …>版本</th>}`；`<th>料号</th>` 保持无条件 |
| F1-b | 表体行内 BOM 系统列 | 版本 `<td>`（含 `VersionSelectDropdown` / 纯文本二分支）整块包 `{isCosting && ( … )}`；**分支内部逻辑一字不改**，`canSwitchTreeVersion` 判定保持原样 |
| F1-c | 「暂无数据」占位行 `colSpan` | `(activeComponentBomTree ? 2 : 0)` → `(activeComponentBomTree ? (isCosting ? 2 : 1) : 0)` |
| F1-d | `<tfoot>` 小计行占位 | `{activeComponentBomTree && (<><td /><td /></>)}` → `{activeComponentBomTree && (<><td />{isCosting && <td />}</>)}` |
| F1-e | `<tfoot>` 合计行占位 | 同 F1-d |

**不动**：
- `activeComponentBomTree` 判定 —— 保持 task-0721 F1 的纯数据驱动（只看 `__sys.nodeId`），报价侧树仍要出「料号」列与树缩进/折叠。
- `activeComponentVersionable` —— 该开关自带 `isCosting &&`，报价侧恒 false，非树页签版本列（task-0713 F2）行为不受影响。
- `canSwitchTreeVersion` / `VersionSelectDropdown` / `onVersionSwitched` —— 核价侧版本切换链路（task-0713 F3/F4）逐字保留。

## F2 `QuotationStep2.tsx`（编辑页）—— 1 处（用户同批裁决，顺带补漏）

| # | 位置 | 改法 |
|---|---|---|
| F2-a | `<tfoot>` **合计**行占位 | `<><td /><td /></>` → `<><td />{cardSide === 'COSTING' && <td />}</>` |

**背景**：`7fadf5e8` 落「报价侧不出版本列」时改了表头 / 行内 / tfoot **小计** 三处，**漏了 tfoot 合计行**。报价侧树页签且该页签含 `is_amount` 列时，合计行比表头多一个占位格。

## F3 E2E（`e2e/quotation-bom-tree.spec.ts`）

新增 1 个 test：**「报价侧详情页 BOM 树：表头不含『版本』列 + 表体/表尾与表头列数对齐」**
- 路由 `/quotations/{id}`（详情页默认 `mainTab='quote'` + `viewType='card'`，直接渲染 `ReadonlyProductCard`）→ 点 `.qt-tab-btn` 中的「BOM树」；
- 断言：① 表头 `[0] === '料号'` 且不含「版本」；② 每个 tbody 行 `td` 数 == 表头列数；③ 每个 tfoot 行 `Σ colSpan` == 表头列数；④ 「加载中」= 0。
- **为什么必须加**：原 spec 只走编辑页，是 `7fadf5e8` 漏改能溜过去的直接原因（见 `问题说明.md` ④「为什么测试没拦住」）。

## F4 自检

1. `npx tsc --noEmit -p tsconfig.json` → 0 错误
2. 临时 Vite（worktree 内 5199，`node_modules` 软链主仓）transform：
   - `/src/pages/quotation/ReadonlyProductCard.tsx` → 200
   - `/src/pages/quotation/QuotationStep2.tsx` → 200
3. E2E：`PW_BASE_URL=http://127.0.0.1:5199 npx playwright test --config=e2e/playwright.config.ts e2e/quotation-bom-tree.spec.ts`
   （两个文件均在 `CLAUDE.md` E2E 强制清单内）

## F5 AP-50 声明

本次是 AP-50（详情页/编辑页各存一份渲染副本）的**又一次实例**：修法仍是「两处各改一次」，未做结构性收敛。收敛方案（抽 `bomSysColumns(side, isBomTree)` 共享 helper）已登记 **`BL-0174`**，不在本次范围 —— 理由见 `问题说明.md` ⑤ 被否方案 B。
