# backtask · repair-260911 连表公式匹配键宿主侧取不到取数列

> 🚫 **只按本文件做，不要自行扩范围。** AC 原文在 `问题说明.md` ⑥ 节，本文件只标编号不复制原文。
> 🚫 **不许改 `currentRowRaw` 本身**（`repair-0803` D-2 已裁决否决），只新增独立视图。

## 背景一句话

`cross_tab_ref` 的 `match` 两侧命名空间不一致：源页签行按**字段名**、宿主行按 **driver 视图列名**；只有 `INPUT_*` 三型被 `fillInputDefaultSourceByFieldName` 补过字段名键，`BASIC_DATA` / `DATA_SOURCE` 取不到 → 全行判不匹配 → `SUM` 空集返 0。

## 任务分解

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-1, AC-2, AC-3, **AC-4**, AC-5, **AC-9** | `FormulaCalculator.RowContext` 新增宿主行匹配视图字段（建议名 `hostRowForMatch`）；在 `buildRowEvalCtx`（`:1341-1378`）构造：以 `currentRowRaw` 为底，叠加 `resolveRowByFieldName(fields, driverRow, basicDataValues, editValues, null)` 的按字段名解析结果。<br>⚠️ **叠加口径 = 仅键缺失才补**（`currentRowRaw` 已有该键就不覆盖），保住「显式清空 `""` 尊重置空」。**不要照抄 `buildTreeAggPresenceView:429` 的 `merged.putAll(byFieldName)`** —— 那是整体覆盖，会破坏该语义。 |
| **B-2** | AC-1, AC-2, AC-3, **AC-4**, AC-5, **AC-9** | `evalCrossTab`（`:512-590`）两处以「宿主行」身份参与判定的取值改读 B-1 的新视图：① `hits` 过滤中 `match` 的 `b` 键（`:539`）；② `predicate` 求值传入的宿主行（`:544`）。<br>①处同时覆盖外层 SUM 族与 KSUM（`projectToHostKey`）分支 —— 两者共用同一个过滤循环，不要各写一份。 |
| **B-3** | AC-6 | 后端共享夹具 `src/test/resources/cross-tab-cases.json` + 其 harness：支持在用例里声明 `fields` / `driverRow` / `basicDataValues`（现有用例的 `currentRow` 直接以字段名为键，从未覆盖「driver 列名 ≠ 字段名」）。新增用例组见 AC-6 的三条。<br>🚫 **两端夹具内容必须逐字一致**，与前端 F-3 对齐后再各自提交。<br>✅ **先跑红**：修复前新用例必须失败，且失败表现为「匹配 0 行」而非抛错。 |
| **B-4** | AC-10 | `match` 键在宿主行取不到值时，写入现有 `outDiag` 通道（与 `crossTabError` 同源），文案指明「哪个源页签的哪个匹配键在宿主行取不到值」。🚫 不要新增异常类型、不要改变返回值（仍返 0），只加诊断。 |
| **B-5** | AC-7, AC-8, AC-11 | 回归自检：① 后端公式相关测试类全绿（`FormulaCalculator*` / `*Parity*` / `CrossTab*`）；② **报价侧零回归取证**：改动前后各跑一次同一条 SQL 导出 `quote_card_values`，`diff` 必须为空（单据 `QT-20260911-0010` / `line_item_id=b7066093-ebc9-4a78-b1e4-4ca47298b73e`，库 `cpq_db_0910`）。 |

> 📌 **AC-4 / AC-9 为什么挂在 B-1/B-2 上**：AC-4 要求「真无匹配行时仍返 0」—— 那是 `evalCrossTab` 空集出口（`:559-565`）的**既有行为**，B-1/B-2 的职责是**改匹配取值而不改空集语义**，所以它归实现方而不是只归测试。AC-9（序列）验的是同一条求值链在「编辑 → 保存 → 重载」后仍稳定，同样由 B-1/B-2 的正确性承载。

## 明确不做

| 不做项 | 理由 |
|---|---|
| 改 `b_field` 的取值链（`:242` `currentRowRaw → hostFieldValues` 回落） | `repair-0803` 已定义该语义，本次不动 |
| 往 `currentRowRaw` 里补非 INPUT 类型（方案乙） | A0 已否决：外溢到 `b_field` / KSUM `mergedCurrentRow` / 单位换算三条路径 |
| 单位换算时机、`targetRowValue` 的多 source 广播 | 与本缺陷无关 |
| 组件保存期的配置校验（方案丙） | A0 已否决 |
| 存量单据批量重算 | 本期不做（沿用 `repair-0803` D-4a「只告知不代劳」），随下次编辑自然重算 |

## 硬约束

- 🚫 **`CLAUDE.md` §3.2 不可逆操作红线**：本任务无需任何 DDL / 迁移 / 清库。遇到任何需要 `DROP` / `TRUNCATE` / 无 `WHERE` 的写操作 —— **停下来报主线**，不要自行执行，你没有批准权。
- 🚫 **不许在共享库 `cpq_db_0910` 上跑会清库或重置全局状态的测试。**
- ⚠️ 本任务**零迁移、零 DDL、零接口结构变更**。若你认为必须加迁移，那是范围变更 —— 停下来报主线。
- 工作区：见派工时给出的 worktree 路径。🚫 不要 `cd` 回主仓提交（历史事故：子代理在 worktree 开发却把提交打进主仓 master）。
