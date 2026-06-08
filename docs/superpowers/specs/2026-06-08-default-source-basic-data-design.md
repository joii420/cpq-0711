# 默认值来源支持「基础数据」类型(可编辑快照)— 设计文档

- 日期:2026-06-08
- 状态:设计待评审
- 关联反模式:AP-44(字段类型联动协议)、AP-53(V6 表禁用 + `$<sql_view>` 引用规则)
- 关联记忆:`cpq-chinese-identifiers-need-ascii-alias`、`cpq-sqlview-cache-key-needs-component-dim`

---

## 1. 背景与根因(为什么要做这个)

### 1.1 触发场景

组件 `COMP-0027`(产品)给 品名 / 规格 / 尺寸 / 材质 / 汇率 / 单重 6 个字段都配置了
「默认值来源」`default_source = { type: BNF_PATH, path: "$cp_view.品名" }`,期望在报价单里
按产品料号从 SQL 视图 `cp_view` 自动带出值。结果**单元格全空**,默认值没有填进去。

### 1.2 根因(已代码级证实,4 个独立缺陷)

| # | 缺陷 | 证据 |
|---|---|---|
| 1(最硬)| `$cp_view.品名` 作为**单列路径**求值时被拒 | `SqlViewExecutor` 的 `PATH_PATTERN`(列段 `\.([a-z_][a-z0-9_]*)$`,line 76)与 `SQL_IDENT`(`^[A-Za-z_][A-Za-z0-9_]{0,79}$`,line 96)**只收 ASCII**;中文列 `品名` 匹配失败 → 抛 `IllegalArgumentException` → `DataLoader`(line 208-212)吞掉 → pathCache 写 null → 单元格空 |
| 2 | 渲染层只对 INPUT_NUMBER 接 default_source | `ComponentCell.tsx:596` 可编辑分支 `if (isEmpty && (isNumber \|\| INPUT_NUMBER))`,INPUT_TEXT 不进 |
| 3 | `default_source` 语义是"空值软默认",不写入行数据 | `types.ts:88-94` 注释;`FormulaCalculator.java:501/570` |
| 4 | 组件无 `data_driver_path` | 查库为空;无 driver → 无整行 Map 可短路取列 |

### 1.3 关键发现:两条取值通路本质不同

- **driver 整行通路(中文安全)**:组件配 `data_driver_path=$view` → `executeAllRows` 取整行(走
  `DRIVER_PATH_PATTERN`,**无列后缀,不校验列名 ASCII**),每行是 Map,key 即视图输出别名(含中文)。
  `ComponentDriverService.evaluatePath`(line 920-922)**短路** `driverRow.get("品名")` —— Java Map 按
  字符串 key 取值,中文随便,**根本不过 SqlViewExecutor 的 ASCII 校验**。全库可用的 BASIC_DATA 字段
  (COMP-0019 / COMP-V5-* 等)都是这条路。
- **单列路径通路(只收 ASCII)**:无 driver 时 `default_source.$view.列` / BASIC_DATA 单列只能走
  `formulaEngine.evaluate → SqlViewExecutor.execute → PATH_PATTERN`,中文列被拒。

> 结论:中文列**不是不能用**,而是只在"整行展开后按 key 取列"时安全。本设计据此让 default_source
> 也走整行通路。

---

## 2. 目标

让「默认值来源」(`default_source`)支持配置**基础数据(BASIC_DATA)**类型的源:
把基础数据(SQL 视图)查出来的结果作为**默认值**填进单元格,且用户**可再次编辑**。

- 取值语义:**快照** —— 首次展开即把解析值写入单元格行数据并落库,之后不随基础数据变化;用户改写即覆盖。
- 中文视图列**原生支持**(无需把视图列名改 ASCII)。
- 组件**无需配 `data_driver_path`**(单字段级配置即可生效)。
- 字段类型保持 INPUT_TEXT / INPUT_NUMBER(保证可编辑)。

### 2.1 非目标(YAGNI)

- 不放开 `PATH_PATTERN/SQL_IDENT` 的 ASCII 白名单(安全边界保留,防注入)。
- 不改既有 BASIC_DATA 字段类型的行为(只读语义不变)。
- 不为 HTTP_API 默认值做基础数据化。
- 不引入"实时跟随基础数据变化"的软默认模式(本期只做快照;如需软默认另立项)。

---

## 3. 设计

### 3.1 数据模型

`DefaultSource`(`cpq-frontend/src/pages/component/types.ts`)新增类型值,**不新增 field_type**:

```ts
export interface DefaultSource {
  type: 'GLOBAL_VARIABLE' | 'BNF_PATH' | 'HTTP_API' | 'BASIC_DATA'; // ← 新增 BASIC_DATA
  code?: string;
  key_values?: Record<string, any>;
  key_field_refs?: Record<string, string>;
  /** BNF_PATH / BASIC_DATA 复用此字段:BASIC_DATA 时为 "$cp_view.品名" 形态 */
  path?: string;
  api_config?: Record<string, any>;
}
```

字段类型仍是 INPUT_TEXT / INPUT_NUMBER。

### 3.2 配置 UI

`DefaultSourceEditor.tsx` 增加「基础数据」单选项:

- 选中后展示与 BASIC_DATA 字段同款的 `$view.列` 路径输入(复用现有路径选择器 / `$view` 引用提示)。
- 复用现有「测试解析」按钮(测试解析需走 3.3 的整行通路,见下文测试接口说明)。
- 提交结果:`{ type: 'BASIC_DATA', path: '$cp_view.品名' }`。

### 3.3 解析机制(中文安全的核心)

新增 BASIC_DATA default_source 解析路径,**不走单列路径**,而是"整行取数 + 按列名取值":

1. 从 `$cp_view.品名` 拆出视图名 `$cp_view` 与叶列名 `品名`(复用 `extractLeafField` + `extractSqlViewName`)。
2. `dataLoader.loadByPath("$cp_view", null, partNo, customerId)` 取整行(`DRIVER_PATH_PATTERN`,无列后缀,
   不过 ASCII 校验,返回含中文 key 的 Map)。
3. 从行 Map 按 `品名` 取值(中文 key 安全)。

实现落点(最干净、零特例分支):在 `ComponentDriverService.expand()` 的**无-driver 虚拟单行分支**
(line 296-317)里,把字段 default_source 引用到的各 `$view` 整行列 **merge 进 virtualRow**,使
`evaluatePath` 的短路 `virtualRow.containsKey("品名")` 自然命中,复用现有 BASIC_DATA 取值逻辑。

> 注:同组件多个 default_source 可能引用不同 `$view`;实现时按视图名分组,各取一次整行后合并到 virtualRow。
> `$view` 解析仍受 `SqlViewRuntimeContext`(componentId/templateId)隔离约束(见记忆
> `cpq-sqlview-cache-key-needs-component-dim`)。

### 3.4 快照写入 + 可编辑

- **后端**(expand 虚拟行):对每个 `default_source.type=BASIC_DATA` 的 INPUT 字段,用合并后的行解出值,
  放入该行 `basicDataValues`,key 用 `bnfDriverLookupKey(path)`(与现有 BNF_PATH 对齐)。
- **前端**:在 `QuotationStep2` 的 effect(driver expansion 结果到达后)里,对每个 INPUT 单元格——若
  **行值为空**且字段有 `default_source.type=BASIC_DATA`——从 expansion 的 basicDataValues 取解析值,
  **写入可编辑行值(editRows)状态**(`onUpdateLineItem`/handleRowChange 同款写路径)→ 显示为可编辑的真实值,
  随报价单保存落库(快照)。
  - ⚠️ 写入是**状态副作用**,必须放在 effect(state 所在层),**不得在 `ComponentCell` 渲染中改 state**。
  - 触发一次:仅在"未保存且单元格空"时回填;用户改写即覆盖;存过一次后即普通行数据,不再重解析。
- 副带好处:值落在 rowData 后,`ComponentCell` 渲染走正常 `row[key]` 路径(line 541/546),**渲染层基本无需
  改动**,`ComponentCell:596` 那个 INPUT_TEXT 软默认缺口对快照语义不构成阻碍(值是真实行数据,不是占位)。

---

## 4. 协议传播点(default_source 消费方清单)

凡现在特判 `default_source` 的 `GLOBAL_VARIABLE`/`BNF_PATH` 处,都要补 `BASIC_DATA` 分支或确认透传。

| # | 文件 | 现状 | 改动 |
|---|---|---|---|
| 1 | `component/types.ts` + `DefaultSourceEditor.tsx` | 仅 3 类型 | **新增** `BASIC_DATA` 枚举 + 配置项 |
| 2 | `enrichComponentData.ts`(146/254) | 泛型透传 default_source | **验证**透传,无需特判 |
| 3 | `CardSnapshotService.java`(267-268) | 泛型搬运 default_source | **验证** |
| 4 | `useDriverExpansions.ts#fieldsOverrideHash`(85-87) | 已 hash `def.type/def.path` | **验证**(泛型已覆盖) |
| 5 | `usePathFormulaCache.ts`(99-101/169-171) | 收集 default_source.BNF_PATH | **显式不收集** BASIC_DATA(避免把 `$view.中文列` 发 batchEvaluate 撞 ASCII);BASIC_DATA 只走 expand 整行通路 |
| 6 | `ComponentDriverService.java`(expand 虚拟行 296-317)| 仅 GLOBAL_VARIABLE gvarTask + BASIC_DATA 字段路径 | **核心新增**:$view 整行 merge + default_source.BASIC_DATA 解析(§3.3/§3.4) |
| 7 | `FormulaCalculator.java`(500-524 / 569-589)| 仅 GLOBAL_VARIABLE/BNF_PATH 分支 | **加 BASIC_DATA 分支**(从 basicDataValues 读)→ 后端公式/Excel/核价能用快照值(汇率/单重要喂公式) |
| 8 | `QuotationStep2.tsx`(531-558 公式输入链 + 新增快照回填 effect)| 仅 GLOBAL_VARIABLE/BNF_PATH | **加 BASIC_DATA 分支**(从 basicDataValues 读)+ **新增 effect**:expansion 到达后把 BASIC_DATA 默认值回填空单元格的 editRows(§3.4 写路径) |
| 9 | `ComponentCell.tsx`(551-620)| 仅 INPUT_NUMBER/GV/BNF 软默认 | **基本无需改动**(值落 rowData 后走正常 `row[key]` 渲染);**禁止**在渲染中改 state |

> AP-44 说明:本特性是新增 `default_source` **子类型**(非新增 `field_type`),影响面是 AP-44 的子集,
> 集中在上述 default_source 消费方。但仍按 AP-44 SOP 三步走(写前 grep 清单 / 写中对照勾掉 / 写后跑双视图 + E2E)。

---

## 5. 测试

### 5.1 单元测试(后端)

- `ComponentDriverService`:无 driver 组件,字段 `default_source={type:BASIC_DATA, path:"$cp_view.品名"}`,
  expand 后该 INPUT 字段在 basicDataValues 里解出**中文列值**(品名 / 汇率),验证整行通路绕过 ASCII 校验。
- `FormulaCalculator`:INPUT_NUMBER(汇率)行值空 + default_source.BASIC_DATA → 公式输入取到快照值。
- 边界:`$view` 返 0 行 → 解出 null,单元格留空(不报错)。

### 5.2 E2E(CLAUDE.md 强制)

改动 `ComponentCell / useDriverExpansions / usePathFormulaCache / QuotationStep2 / ComponentDriverService /
FormulaCalculator` 均为协议级文件 → 必须:

```
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```

期望:`1 passed`、`'加载中' final count = 0`、全 Tab `'加载中'=0`。必要时加 `composite-product-flow.spec.ts`。

### 5.3 手动验收(COMP-0027 真实报价单)

1. 把 COMP-0027 的 6 字段 default_source 改为 `{type:BASIC_DATA, path:"$cp_view.列"}`(中文列直接用)。
2. 报价单选定产品料号后:品名/规格/尺寸/材质/汇率/单重 **自动带出 cp_view 的值**。
3. 单元格**可手工改写**;改写后保存。
4. 刷新报价单:已带出/已改写的值**稳定不变**(快照语义)。
5. 汇率额外要求该料号在该客户下有 `material_customer_map` 映射,否则汇率列空(属正常)。

### 5.4 自检声明(完成时必附)

- TS 0 错误 ✅
- 改动的 `.tsx` → Vite 200 ✅
- 后端 endpoint → 200/401 ✅
- E2E `1 passed` + `'加载中'=0` ✅
- COMP-0027 三视图(报价/核价/详情)关键 Tab 截图 ✅

---

## 6. 风险与回滚

- **风险:同组件多 `$view` 整行取数的性能**。缓解:按视图名分组,各取一次(N 视图 = N 次,非 N 字段次);
  expand 结果仍走 `expandCache`。
- **风险:快照写入时机导致"清空后又被回填"**。约定窗口:仅在"未保存且单元格为空"时回填;保存后值在
  rowData 即不再触发。文档化此行为。
- **风险:遗漏某个 default_source 消费点(AP-44 类静默失败)**。缓解:§4 清单 + 写前 grep + E2E。
- **回滚**:`default_source.type` 改回 BNF_PATH/GLOBAL_VARIABLE 即可;无 schema 破坏性变更(纯 JSON 配置 +
  解析分支),代码改动可单 PR revert。
