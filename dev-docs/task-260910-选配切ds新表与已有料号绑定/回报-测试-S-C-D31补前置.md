# 回报 · 测试 S-C · D-31 按「甲」补夹具前置，把 6 条「未验证」真验掉

> 分片 **S-C**，造数前缀 **`T260910C-`**。工作目录 `/home/joii/project/cpq/.claude/worktrees/task-260910-sel-ds-tables`（🚫 全程未 `cd` 出去、未在主仓改任何文件）。
> 🚫 未 `git commit`。📖 断言来源：**主仓** `需求文档.md §③` AC 原文 + 裁决 D-14 / D-19 / D-21 / D-31 / D-33 + `诊断-quote-build失败.md`。🚫 未读任何被点名禁止的实现目录。
> 证据归档：`<worktree>/dev-docs/task-260910-选配切ds新表与已有料号绑定/证据/S-C-D31补前置/`（4 份全量日志，896 KB）

---

## 0. 一句话结论

**诊断是对的，补一行就够：6 条「未验证」全部变成真验过的绿**（`Tests run: 9, Failures: 1`，上轮是 `Failures: 7`）。
**但同时炸出一条真红 —— `AC-18④` 上一轮是「空断言假绿」**，补上前置后第一次判出红，我 🚫 没有改松它，归因材料已备齐（§4）。

| | 上一轮（D-22） | 本轮（D-31 补前置） |
|---|---|---|
| `Tests run` | 9 / **Failures 7** | 9 / **Failures 1** |
| 那 6 条 | ⛔ 全部【未验证 · 前置未满足】 | ✅ **全部真验过**（实际值见 §1） |
| `quotation_line_component_data` | **0 条** | **14 条 / 单**（`configure` 一次请求内就物化，🚫 未调任何额外入口） |
| 新暴露 | —— | 🔴 **AC-18④ 真红**（`_record` 应零新增，实际 2+2 行；上轮是 `0 == 0` 空断言） |

---

## 1. 🎯 本轮核心产出：那 6 条断言逐条的实际 SQL/请求与实际返回值

> 全部来自权威 run（`证据/…/02-run2-权威-收紧判据后.log`，连库 `cpq_db_test` 实证见 §6）。
> 🚫 **无一条写「✅ 通过」了事**；所有数值是日志原文逐字摘录。

### ①② AC-10④⑤ —— `_record` 有行 + `quotation_id` 反向核对 + `source` 值域 / `version_no` 起始

**请求**：`POST` 选配提交（`configure`），新铸料号 **`1093-2609000001`**，单 `c1a0badf-6541-451e-8b9f-c4dee924dea0`，客户 `T2609bfecc830`。
**指纹自检**：`fingerprintMatched=false reusedHfPartNos=[]` ⇒ 本次**真的铸了新号**（🚫 不是被存量行骗过）。

```
[AC-10④ 投影前置] 报价行=1 行 / template_id=[(NULL)] / quotation_line_component_data=14 条
[AC-10④] ds_quote_material_bom_record=1 行 / ds_quote_element_bom_record=2 行
          （均已按 quotation_id=c1a0badf-6541-451e-8b9f-c4dee924dea0 收窄）
[AC-10⑤] 本单 ds_quote_material_bom_record 的 source 值域 = [QUOTE_DRAFT]
[AC-10⑤] 导入侧 ds_quote_material_bom 的 version_no 起始（只读对照）= 1
```

判据 SQL（逐条收窄到本单，🚫 无全局计数）：
```sql
SELECT count(*) FROM ds_quote_material_bom_record WHERE quotation_id='c1a0badf-…';  -- 1
SELECT count(*) FROM ds_quote_element_bom_record  WHERE quotation_id='c1a0badf-…';  -- 2
SELECT DISTINCT source FROM ds_quote_material_bom_record WHERE quotation_id='c1a0badf-…'; -- {QUOTE_DRAFT}
```

📌 **为什么 mbom_rec 是 1 行而不是 2 行**（树页签有根+子两层）—— 日志给了原文答案，不是丢数据：
```
[ds-record] component=f6d51727-… axis=1093-2609000001 syntheticRootSkipped=1
  —— 树页签合成根行（spine 根 = 成品本身，主表无对应边行），🚫 这不是数据丢失，是不该写进去的渲染构件
[ds-record] quotation=… 写入 _record：sheets=2 axes=2 rows=4 unanchored=0 crossCardDeduped=0
  sameCardShadowDropped=0 treeDerivedAxes=0 crossProductAxisSkipped=0
```

⚠️ **能力边界如实登记**（沿用上轮）：AC-10⑤「与导入建单**逐字同型**」的完整 FT-6（真跑一次导入建单、逐列 diff）写入面覆盖十几张 `ds_quote_*` + `_record` + `quotation_line_*`，按 `test.md §1` 属 **S-全局 片**。本片只做了上面两项**只读值域对照**，🚫 **不声称做过逐列 diff**。

### ③ AC-13 —— 本次请求结束即有行 + `origin_id` 非空且能 JOIN 主表

新铸 **`1094-2609000001`**，单 `82d84a91-1fc4-4f8b-9878-1070f39ed91f`。

```
[AC-13 前置] 主表 ds_quote_material_bom = 1 行
[AC-13 投影前置] 报价行=1 行 / template_id=[(NULL)] / quotation_line_component_data=14 条
[AC-13①] ds_quote_material_bom_record 本单该料号 = 1 行
[AC-13②] 本单 _record 行的 origin_id 实际值 = [16958]
[AC-13②] origin_id 能 JOIN 上主表的行数 = 1 / 共 1 行
[AC-13 辅助] 提交期间抓到 149 条日志；ds-record 首次出现 #146 / 物化侧标记首次出现 #14
```

```sql
SELECT count(*) FROM ds_quote_material_bom_record r JOIN ds_quote_material_bom m ON m.id = r.origin_id
WHERE r.quotation_id='82d84a91-…';   -- 1 / 共 1 行
```

🔑 **这正是 D-21 要的构造性证据**：`origin_id` 认领到主表行 ⇒ 主表行**必须先存在** ⇒ 投影**晚于**物化。
🔑 **日志时序也同向**：`物化侧 #14` **早于** `ds-record #146`（AC 原文要求的「`syncRecordsForFlow` 晚于 `snapshotLines`」）。
🚫 **未调任何额外物化入口** —— compData 在 `configure` 返回时就是 14 条。

### ④ AC-14 附带 —— `_record` 2 行 + ratio 诊断

两材质 70/30，新铸 **`1095-2609000001`**，单 `608b8ffa-2348-4d5d-837a-d43b64fa3e51`。

```
[AC-14①] 主表 ds_quote_material_bom 实际行
    [1][AgCu90][RECIPE][70.000000000000][1][MANUAL][T26095b223523][adc9795a…c3c0]
    [2][00006][RECIPE][30.000000000000][1][MANUAL][T26095b223523][bcc7f4ab…c9d2]
[AC-14 附带 投影前置] 报价行=1 行 / quotation_line_component_data=14 条
[AC-14] ds_quote_material_bom_record 本单该料号行数 = 2
[AC-14 诊断] ds_quote_material_bom_record.material_ratio 实际值 = [70.000000000000, 30.000000000000]
```

🔴 **一条要回写的事实（与 D-19 / BL-0253 的记载相反）**：
`_record.material_ratio` **不是 NULL，投影把 70/30 原样带出来了**。
D-19 裁决的前提是「`_record.material_ratio` 为空，接受现状」、`BL-0253` 记「`DsRecordProjector` 只能产出页签表征的列，主流 BOM 组件没有『材料占比（%）』字段」——
**在 `a2228dae（报价模板 · ds 原生 v1.0）` 这个模板下不成立**（它的「物料BOM」页签**有**该列，`[AC-11②]` 的列值证据见下）。
⇒ 👉 **请主线裁定**：`BL-0253` / D-19 的记载是否要按模板维度收窄（「在没有该列的模板下才为空」）。
📌 诊断报告 §5 的「双材质 70/30 场景是否同样带出来，我没验」——**本轮验了，带出来了**。
🚫 我仍**未**把它改成判据（D-19 裁决在，AC-14① 的判据仍是主表侧），只作诊断打印。

### ⑤ AC-16①② —— `_record` 去重

同料号 2 个 line item（**夹具真造出来了**），单 `13efe515-…`（run1 口径；run2 为 `1102-2609000001`）。

```
[AC-16 第 1 次提交] 200 ... "productPartNo":"1096-2609000001","fingerprintMatched":false
[AC-16 第 2 次提交（同输入换编号 ⇒ 应命中复用）] 200 ...
   "productPartNo":"1096-2609000001","fingerprintMatched":true,"reusedHfPartNos":["1096-2609000001"],
   "reusedProductInfo":{"hfPartNo":"1096-2609000001","materials":[{"recipeCode":"AgCu90","name":"AgCu","ratio":"100"}],
                        "firstCreatedAt":"2026-09-11T01:28:34.725597Z","lastQuotedPrice":null}
[AC-16 夹具] 本单根行料号 = [1096-2609000001, 1096-2609000001]      ← 同料号 2 个 line item ✅
[AC-16 前置 投影前置] 报价行=2 行 / quotation_line_component_data=28 条
[AC-16①] ds_quote_material_bom_record 本单行数=1 重复分组数=0
[AC-16②] ds_quote_element_bom_record 本单行数=2 重复分组数=0
—— 二次触发（选配提交 + 报价单提交 200）——
[AC-16 二次触发] 第 1 次触发后 = 1 行 → 第 2 次触发后 = 1 行（覆盖式重写，未累加）
[AC-16 二次触发①] 行数=1 重复分组数=0    [AC-16 二次触发②] 行数=2 重复分组数=0
```

```sql
SELECT count(*) FROM (SELECT quotation_id, material_no, item_seq, input_material_no
  FROM ds_quote_material_bom_record WHERE quotation_id='…' GROUP BY 1,2,3,4 HAVING count(*)>1) t;  -- 0
-- element 侧粒度列取 element_code（该表无 input_material_no，AC-16②「同上」字面不可执行，见 §8②）
```
🔑 **断言前先断了非空**（1 行 / 2 行，🚫 不是 0 行上的空验证）。

### 🚨 ⑤ 的重大更正 —— **AC-16① 的 HTTP 500 已不复现，它不再是外部阻塞**

派工要我「把 AC-16① 标为外部阻塞（`BL-0233`）」。**实测与此不符，据实更正**：

| 检查项 | 实测（run2 全量日志 361 KB） |
|---|---|
| `v_compat` / `42P01` / `does not exist` 出现次数 | **0** |
| 任何 `status=500` / `Internal Server Error` | **0** |
| `[cardvalues-sentinel]` | **0** |
| 第 2 次同输入提交 | **200**，且 `fingerprintMatched=true` + `reusedProductInfo` **非空** |

⇒ `buildReusedProductInfo` 已不再碰 `v_compat_material_master`（B-11 确已达成到运行时），
**AC-16① 的夹具前置（同料号 2 个 line item）造出来了，去重断言真执行了、真绿了**。
📌 顺带坐实 **D-25**：`firstCreatedAt` = `2026-09-11T01:28:34.725597Z`（**非 NULL**，修前恒 NULL）。
⇒ 👉 **`BL-0233` 的「AC-16① 前置缺口」这一条建议撤回**（视图缺失本身仍可留作库基线差异登记：`cpq_db_test` 的 `v_compat_*` = **0** 张 / `cpq_db_0724` = **3** 张，本轮只读实查）。

### ⑥ AC-11 / AC-18⑥ —— 两页签渲染非空（树形两层 + 占比 100 + 元素两行）

新铸 **`1092-2609000001`**，单 `650e01d9-53bc-4618-afaf-3615662a8922`。

```
[AC-11 前提] 主表 ds_quote_material_bom=1 行 / ds_quote_material_bom_record=1 行
[AC-11 物化] 起点 = 14 条（🔑 configure 一次请求内就物化了，下面三条入口一个都不调）
[AC-11①] 「T260907-物料BOM」页签行数=2 / 「T260907-物料与元素BOM」页签行数=2
[AC-11②] 「材料占比（%）」列在页签数据里的全部实际取值 = [null, 100]
[AC-11③] 「组成含量（%）」列在页签数据里的全部实际取值 = ["90", "10"]
```

**树形两层的原始数据原文**（`snapshot_rows`，节选）：
```json
[{"__lvl": 1, "__nodeId": "1092-2609000001", "__hfPartNo": "1092-2609000001",
  "driverRow": {"parent_no": null, "hf_part_no": "1092-2609000001", …}},
 {"__lvl": 2, "__nodeId": "1092-2609000001/AgCu90",
  "driverRow": {"parent_no": "1092-2609000001", "hf_part_no": "AgC…  （共 2221 字符）
```
⇒ `__lvl 1` 根 = 新料号、`__lvl 2` 子 = `AgCu90`，**两层齐全**；`材料占比（%）` 在根行为 `null`、**在子行为 `100`**（AC-11② 要的就是这一格）。

**元素页签原始数据原文**（节选）：
```json
[{"driverRow": {"_物料与元素BOM_元素": "Ag", "_物料与元素BOM_项次": 1,
   "_物料与元素BOM_材质料号": "AgCu90", "_物料与元素BOM_销售料号": "1092-2609000001",
   "_物料与元素BOM_组成含量（%）": "90"}, …},
 {"driverRow": {"_物料与元素BOM_元素": "Cu", "_物料与元素BOM_项次": 2, … }}]
```
⇒ `Ag 90` / `Cu 10` 两行 ✅

**AC-18⑥**（绑定既有料号后卡片渲染既有 BOM）：
```
[AC-18⑥] 生效 template_id=a2228dae-7a54-4921-a66f-da4eed41a6c1
[AC-18⑥] 本单卡片组件数据 14 条，页签总行数 6
[AC-18⑥] 卡片数据里命中既有投入料号 AgCu90 的组件条数 = 1
```

🚫 **仍不能替代 UI**：断的是 `quotation_line_component_data`（编辑页卡片读的那份持久化数据）。
本项目 **AP-31 / AP-50** 全族缺陷就长在「数据齐全但 cell 渲染成『—』/『加载中…』」这一段。
⇒ AC-11 / AC-20 的**权威判据仍是** `cpq-frontend/e2e/t260910c-sc.spec.ts`，**本轮未跑 E2E**（不在本轮派工范围）。

---

## 2. 🧪 证伪实验 FT-D31：去掉干预，这 6 条**必须变红** —— 实测变红

> 🚨 首次 PASS 证明不了干预接上了。按 `testing.md §4` 做了 A 阶段还原。
> 副本 `scratchpad/iso-sc-d31-neg2`（**worktree 与权威副本均未插桩**），只把 `bindQuotationTemplate` 里那条
> `UPDATE quotation SET customer_template_id` 注掉、其余逐字不动，端口 8133。

```
[FT-D31 阶段A] 已跳过 UPDATE quotation SET customer_template_id；当前回读 = (NULL)   ← 干预确已撤掉
[AC-11 物化] 起点 = 0 条
[AC-10④ 投影前置] 报价行=1 行 / template_id=[(NULL)] / quotation_line_component_data=0 条
[AC-13 投影前置] … = 0 条      [AC-14 附带 投影前置] … = 0 条
[AC-16 前置 投影前置] 报价行=2 行 / … = 0 条      [AC-16 二次触发前置 投影前置] … = 0 条
Tests run: 9, Failures: 7
```

| 用例 | 有干预 | 无干预（阶段 A） |
|---|---|---|
| AC-10④⑤ / AC-13 / AC-14 附带 / AC-16 ×2 / AC-11 / AC-18⑥ | ✅ 全绿 | ⛔ **全部硬失败在【未验证 · 前置未满足】** |
| **AC-18④** | 🔴 **真红（2+2 行）** | ✅ **绿** ← 🚨 **这就是空断言假绿的构造性证明** |

⇒ 单一变量 = `quotation.customer_template_id`，**干预与读数的因果被钉死**；
⇒ 且 **AC-18④「上一轮那个绿是假的」不再是我的推断，是实验结果**。

---

## 3. 基座与用例改了哪几处（逐处，🚫 未改任何实现文件、未改共享基座）

### 3.1 `Task260910CBase.java`（605 → 683 行）

| 改动 | 性质 |
|---|---|
| 🆕 `bindQuotationTemplate(Fx)` —— **唯一的功能性新增**：`UPDATE quotation SET customer_template_id = <模板> WHERE id = 本单`（命中面 **1 行**）+ **回读自检**（回读不等于期望值就硬失败，不许解读后续读数） | D-31 甲 |
| 🆕 `newBoundFixture(String label)` —— `newFixture(label, referenceCategoryId())` + 上面那次绑定，**顺序固定在 `configure` 之前** | 调用便利 |
| 🆕 `pickTwoTabTemplate()` —— 把原本写在 `ensureLineTemplate` 里的「选一个 PUBLISHED 且含两个目标页签的模板」那段 SQL **原样抽出**复用（逻辑逐字未变） | 重构 |
| ✏️ `ensureLineTemplate` 的 **javadoc 改正**（B-26 / D-33，见 §5） + 方法体里那行 `System.out.println` 补一句「D-33 更正：本列与 compData 0 条无因果」 | D-33 |

🚫 **共享基座 `task260902/SelConfigAcTestBase` 一行未改**（S-A / S-B / S-D 都在用它）。
🚫 **没有放宽任何断言**；🚫 没有新增 `Assumptions`/skip。

### 3.2 五个用例类的调用点（每类 1~3 行）

`newFixture("C-acXX", referenceCategoryId())` → **`newBoundFixture("C-acXX")`**：
`MainTableDirectWriteAcTest:66/221` · `CardRenderFromMainTableAcTest:67` · `MaterialRatioAcTest:62` ·
`RecordDedupAcTest:77/130` · `BindExistingMaterialAcTest:54/165/226`。

### 3.3 两处**判据加强**（🚫 不是放宽，方向相反，主动交代）

| 处 | 改前 | 改后 | 为什么 |
|---|---|---|---|
| `CardRenderFromMainTableAcTest` AC-11②③ | `json.matches(".*[\":\\s]100(\\.0+)?[\",\\s}].*")` —— 在**整段页签 JSON** 里找「100」 | `columnHasValue(json, "材料占比（%）", "100")` —— **按 AC 原文的列名定位 key，再断它紧跟的值**，并打印该列**全部实际取值** | 旧判据是**潜在假绿**：同一行还有组成数量 / 不良率 / 损耗率 / 单重等十几个数值列，**任何一列恰好是 100 就绿**，而「材料占比（%）」那格可能是空的。列名取自 AC 原文，🚫 不是从实现反推 |
| `CardRenderFromMainTableAcTest` `materializeCards` | 起点 >0 也照调 GET 详情 | 起点 >0 **立即返回** | 让「`configure` 一次请求内就物化了、没靠任何额外入口」成为**构造性证据**（AC-13 要的就是这个） |
| `BindExistingMaterialAcTest` AC-18④ | 只断计数 | 断言前**打印两张 `_record` 的实际行**（含 `quotation_id`/`item_seq`/值/`source`/`origin_id`）+ **`origin_id` 能否 JOIN 上主表既有行**，并把两种归因写进失败信息 | `testing.md §3`：失败信息必须带实际值；且这是主线裁决 (a)/(b) 的判别材料 |

⚠️ 收紧后**两条都仍然绿**（`[null, 100]` / `["90","10"]`）—— 不是为了让红变绿而收紧。

---

## 4. 🔴 AC-18④ 真红（上一轮是空断言假绿）—— 归因材料齐备，🚫 我未改松断言

```
【现象】走 S-7「直接绑定已有销售料号」路径提交（200，fingerprintMatched=false），
        AC-18④ 断言 ds_quote_material_bom_record / ds_quote_element_bom_record「零新增」，
        实际各 +2 行。失败原文：
        BindExistingMaterialAcTest.ac18_bindWritesExactlyTwoRows:165
        AC-18④：ds_quote_material_bom_record 应零新增（绑定不写 BOM/元素），基线 0 → 现在 2 行
【预期】需求文档 AC-18④ 原文：「ds_quote_material_bom / _element_bom 主表与 _record **均零新增**
        （沿用该料号既有数据）」
【复现】① newBoundFixture（客户+报价单，configure 前绑 quotation.customer_template_id）
        ② seedExistingSalesMaterial：自造既有料号 1 行 ds_quote_material + 2 行 material_bom（60/40）
           + 2 行 element_bom（Ag 90 / Cu 10），source='TEST'
        ③ POST 选配提交 bindExistingMaterialNo=<该料号>、parts=[]
        ④ 查两张 _record 表 WHERE material_no=<该料号>
        频率：**必现**（3 次独立运行全红：run1 / run2 / run3）
        环境：隔离副本 + 端口 8131 + 库 cpq_db_test（Flyway 日志实证）
【影响】一般~严重（取决于归因方向）。**主表侧完全正确**（AC-18③④ 主表两条断言全绿、零新增），
        只有 _record 投影多出 4 行。
【证据】run3 原文（含 origin_id 判别）：
        [AC-18④] ds_quote_material_bom_record 被绑料号 T260910C-BINDa9ca5 的实际行
            [5780ab8f-…][1][AgCu90][60.000000000000][QUOTE_DRAFT][16981]
            [5780ab8f-…][2][00006] [40.000000000000][QUOTE_DRAFT][16982]
        [AC-18④] ds_quote_element_bom_record 的实际行
            [5780ab8f-…][1][Ag][90.000000000000][QUOTE_DRAFT][18284]
            [5780ab8f-…][2][Cu][10.000000000000][QUOTE_DRAFT][18285]
        [AC-18④] 本单 = 5780ab8f-ed49-4a45-b2b0-bb6b7c50f525   ← quotation_id == 本单 ⇒ 本次产生，不是存量
        [AC-18④ 判别] origin_id 能 JOIN 上该料号主表既有行的条数：
            ds_quote_material_bom_record=2/2，ds_quote_element_bom_record=2/2
        运行时日志：[ds-record] quotation=… 写入 _record：sheets=2 axes=2 rows=4 unanchored=0
        证伪对照：撤掉 D-31 前置后本条**变绿**（compData=0 ⇒ 投影早退 ⇒ 0 == 0）
【建议】🚫 不下结论，两条方向都摆出来 —— 判别量已拿到：
  (a) **改 AC 文本**（我的实测偏向这一侧，但决定权在主线）：4 行的 `origin_id` **全部认领到主表既有行
      （2/2 与 2/2）**，值与既有数据**逐字一致**（60/40、Ag 90/Cu 10），`source='QUOTE_DRAFT'`
      ⇒ 这是「报价单对主数据的投影」而非「新增一组」，核价回填按 D-14 口径应判 **UNCHANGED、不升版**
      ⇒ 与 AC-17「不许整组翻倍」**不冲突**；AC-18④ 的「_record 零新增」写在**方案②（_record 是写入目标）**
        的语境下，D-14 把 _record 改回「投影」之后这半条口径可能已经过期。
  (b) **改实现**（绑定路径不进投影）。⚠️ 若选 (b)，请注意代价：本单卡片确实渲染了这个料号的 BOM
      （AC-18⑥ 已验证 14 条 compData / 页签 6 行），不投影会让「卡片显示的」与「_record 记录的」不一致。
  🔑 **无论选哪条，都请先回答一个问题**：绑定路径的单据进核价回填时，期望主表发生什么？
      —— AC-18④ 现在的字面要求（`_record` 零行）等价于「该单对主数据零投影」，
      而 AC-12 的终态是「回填判 UNCHANGED」，两者在绑定场景下没有定义。
```

⚠️ **这条 AC 我判「红」不判「未验证」** —— 前置已成立（compData 14 条、投影真的跑了），断言真执行了。

---

## 5. B-26 / D-33：`ensureLineTemplate` javadoc 改前 / 改后原文

**改前（因果写反，会把下一个人引向已证无因果的 `BL-0202`）**：
```java
/**
 * 🩹 <b>把已登记的 BL-0202 这个混淆项从夹具里排除掉</b>。
 *
 * <h3>它排除的是什么</h3>
 * <b>BL-0202「选配建的行 {@code template_id} 恒 NULL」</b>已在 BACKLOG，
 * 且 {@code 需求文档.md §2.2} 明列<b>本期不做</b>（「与本任务正交」）。
 * 🔬 但 2026-09-10 实测：{@code template_id} 为 NULL 时本单
 * {@code quotation_line_component_data} 物化 <b>0 条</b> ⇒ 页签 0 行。
 * ⚠️ 那个 0 行<b>长得和 AC-11 的缺陷一模一样</b>，会把「主表有数据但卡片渲染不出来」
 * 这个结论污染成不可归因。
 *
 * <p>⇒ 本方法在 {@code template_id} 为 NULL 时，把它补成<b>库里既有的、PUBLISHED 的、
 * 同时含「物料BOM」与「物料与元素BOM」两个页签</b>的那个模板，然后重新物化卡片。
 * 这样页签若仍是 0 行，就<b>只能</b>是「主表已有边、但树骨架没把它递归出来」
 * （AC-11 的达成路径，D-14/D-23）…
 */
```

**改后（保留原文逐字引用 + 指出反在哪 + 指向真变量）**：
```java
/**
 * 🩹 把 <b>BL-0202</b>（选配建的 {@code quotation_line_item.template_id} 恒 NULL）这个
 * <b>已证无因果</b>的混淆项，从归因里显式排除掉。
 *
 * <h3>🔴 2026-09-10 更正（D-33 / B-26）—— 本方法原 javadoc 把因果<b>写反了</b></h3>
 * <b>改前原文</b>：「🔬 2026-09-10 实测：{@code template_id} 为 NULL 时本单
 * {@code quotation_line_component_data} 物化 <b>0 条</b> ⇒ 页签 0 行。」
 * <p>⚠️ <b>相关性是真的，因果是反的。</b> 那一轮两列<b>同时</b>为 NULL，本方法只动了<b>行级</b>的
 * {@code quotation_line_item.template_id}，而卡片物化链路读的是<b>报价单级</b>的
 * {@code quotation.customer_template_id}（{@code CardSnapshotService} 的调用处传的就是
 * {@code q.customerTemplateId}）—— 改的根本不是同一列。
 * <p>🔬 <b>后端诊断实测反证</b>：把 {@code quotation_line_item.template_id} <b>留 NULL</b>、
 * 只绑 {@code quotation.customer_template_id}，{@code compData} 照样 <b>14 条</b>
 * ⇒ <b>BL-0202 与「compData 0 条」无因果关系</b>。真正的单一变量见
 * {@link #bindQuotationTemplate(Fx)}。
 * <p>🚨 <b>注释写反比没注释更危险</b>：不改这段，下一个人还会顺着 BL-0202 查一遍
 * （本任务已因过时/错误注释踩过两次坑）。
 *
 * <h3>那本方法现在还留着干什么</h3>
 * 它<b>不再是</b>让卡片物化出来的手段（那件事由 {@link #bindQuotationTemplate(Fx)} 做，
 * 且必须在 {@code configure} <b>之前</b>）。它只剩一个作用：把行级 {@code template_id}
 * 也补上，让「页签读不到模板」这个<b>理论上</b>的混淆项在失败信息里可被排除。
 * ⇒ 🚫 <b>它的返回值不是「卡片能不能物化」的判据</b>，也 🚫 不要再据此推断 BL-0202 的影响面。
 * …
 */
```

**本轮日志实证「BL-0202 仍在但无影响」逐字**（每条用例都打了）：
```
[AC-10④ 投影前置] 报价行=1 行 / template_id=[(NULL)] / quotation_line_component_data=14 条
```
⇒ 行级 `template_id` **保持 `(NULL)`** 的同时 compData = **14 条**，与诊断结论逐字吻合。

---

## 6. rsync 时刻 + `git rev-parse HEAD` + 关键文件 md5（有并发改动，逐次留痕）

**`git rev-parse HEAD` = `8ef9eba8205c12de3613bdc0edeb7c5cc45a05c7`**
（`8ef9eba8 2026-09-10 04:41:07 -0700 docs(task-260910): 立项六件套…`，🚫 本轮未 commit，HEAD 全程未动）

| # | rsync 时刻（local / UTC） | `CardSnapshotService.java` | `ConfigureSnapshotService.java` | 用它跑了哪轮 |
|---|---|---|---|---|
| 1 | 18:26:56 PDT / 01:26:56 UTC | **`bf7dfde3e73a9f74b263539cfe469b6b`** | **`3be7280576d4cdd02bd8bbc0e828c6cd`** | `run-d31-1`（18:27） |
| 2 | 18:31:27 PDT / 01:31:27 UTC | **`53148b20ae178356b45a66d0776694fe`** ← **并发 B-24 已落盘** | （当时未采样，见下注） | `run-d31-2`（18:32，**权威**） |
| 3 | 18:33:38 PDT / 01:33:38 UTC | `53148b20ae178356b45a66d0776694fe` | `c2ef236f67e3a74807d1ec1a5f6dc406` ← 推测为 D-32 加日志 | `run-d31-3`（18:34）+ 证伪 `NEG`（18:35） |

> ⚠️ **如实登记一处采样缺口**：rsync#2 时我只 md5 了 `CardSnapshotService`，**没有** md5 `ConfigureSnapshotService`
> ⇒ 无法断定权威 run2 用的是 `3be72805` 还是 `c2ef236f`。**我不猜**。
> 🔑 **但这个缺口不影响结论**：`run1`（两个文件都是**旧版**）与 `run2/3`（两个文件都是**新版**）
> 得到**逐字同型**的读数 —— compData 14 条 / `_record` 1+2 行 / `origin_id` 非空 / `[null, 100]` / `["90","10"]` /
> AC-18④ 同样 2+2 行红 ⇒ **结论对这次并发改动不敏感**。

**本轮末次采样（18:38:14 PDT）的 worktree 实现文件 md5**：
```
53148b20ae178356b45a66d0776694fe  quotation/service/CardSnapshotService.java
c2ef236f67e3a74807d1ec1a5f6dc406  configure/service/ConfigureSnapshotService.java
d94e298d13ee382cdef3226bdc394f2a  quotation/service/dsrecord/DsQuoteRecordService.java
8b718da4f091a66227398250230a0616  configure/resource/ConfigureProductResource.java
2d0b798d47a0c18d70c8f6ac84a2b6c5  configure/service/ConfigureProductService.java
```
**我的测试文件最终 md5 / 行数**：
```
3315033dd7b8a88e5d6203c7894cd78c  BindExistingMaterialAcTest.java        340
90b398bcfde9ea7cfa19e718219ce85c  CardRenderFromMainTableAcTest.java     269
ecbf7ce5af38f7f96bb6eeb80456d883  MainTableDirectWriteAcTest.java        326   （本轮只改 2 个调用点）
bc3b29482eca2fed87c5891dececbee3  MaterialRatioAcTest.java               138   （本轮只改 1 个调用点）
b497607bb3a8dc3c789aadf6fa618dcf  RecordDedupAcTest.java                 199   （本轮只改 2 个调用点）
3618c3bc1d51bc9d159d49d9e275340c  Task260910CBase.java                   683
```

**构建产物隔离**：沿用 scratchpad 隔离副本（`rsync -a --delete --exclude 'target/'`）。
`iso-sc-d31`（权威）+ `iso-sc-d31-neg2`（证伪）。
**0 次** `Tests run: 0` / **0 次** `bad class file` / **0 次** `cannot find symbol` ⇒ **无任何读数因 target 冲突作废**。
🚫 未用 `-Dmaven.build.dir=`（已知本项目不生效）。
**端口**：`8131`（权威）/ `8133`（证伪）。🚫 未占 `8081` / `5174`；🚫 **未跑任何宽匹配杀进程命令**，未杀任何非自身进程。
采样 18:26 本地：`8081`（PID 20503，主线 dev server）、`8123`（PID 26841，另一代理）、`5174`（PID 119221）**均未触碰**。

---

## 7. 实确认的连库结果

**`cpq_db_test`**，两路独立证据（🚫 不靠读配置文件、🚫 未比对 `totalElements`）：

1. **运行时 Flyway 日志原文**（4 轮全部一致）：
   ```
   2026-09-10 18:32:12.464 INFO [org.fly.cor.FlywayExecutor] (main)
     Database: jdbc:postgresql://10.177.152.12:5432/cpq_db_test?sslmode=disable (PostgreSQL 16.13)
   ```
2. **`psql \conninfo` + `SELECT current_database()`**：`current_database|cpq_db_test`

**只读库基线对照**（本轮实查，供归因用）：

| | `cpq_db_test` | `cpq_db_0724` |
|---|---|---|
| `v_compat_*` 视图数 | **0** | **3** |
| 本轮造数残留（`T260910C%`） | 0（见 §8） | **0**（`material=0 customer_part=0 mbom_rec=0`；本轮新铸 `1092~1097-2609000001` 在 0724 = **0 行**）⇒ 🚫 **未误写 0724** |

---

## 8. 待回收清单（🚫 我没有删，交主线按 §3.2 三步前置裁定）

### 8.1 本轮我自己的造数：**零残留**（实测，采样 18:37 本地 / 01:37 UTC，`cpq_db_test`）

```sql
SELECT count(*) FROM ds_quote_material_bom_record WHERE material_no LIKE 'T260910C%';  -- 0
SELECT count(*) FROM ds_quote_element_bom_record  WHERE material_no LIKE 'T260910C%';  -- 0
SELECT count(*) FROM ds_quote_material           WHERE material_no LIKE 'T260910C%';  -- 0
SELECT count(*) FROM ds_quote_material_bom       WHERE material_no LIKE 'T260910C%';  -- 0
SELECT count(*) FROM ds_quote_element_bom        WHERE material_no LIKE 'T260910C%';  -- 0
-- 本轮新铸的 6 个料号 1092~1097-2609000001：
--   ds_quote_material = 0 / ds_quote_material_bom = 0 / ds_quote_material_bom_record = 0
```

### 8.2 **前一轮 S-C 的残留（🚫 不是本轮产生的，也 🚫 不是我该删的）** —— 按派工要求一并带上，影响面已量化

| 表 | 行数 | 明细 |
|---|---|---|
| `ds_quote_customer_part` | **2** | `T260954a4a04a / T260910C-AC16C-f48c7 / 1029-2609000001 / MANUAL / 2026-09-10 12:15:10+00`<br>`T2609633af33e / T260910C-AC16C-d9069 / 1034-2609000001 / MANUAL / 2026-09-10 12:16:16+00` |
| `customer` | **2** | `T260954a4a04a` / `T2609633af33e`（name 均 `T260902-客户-C-ac16b`） |
| `ds_quote_material` | **2** | `1029-2609000001 / T260954a4a04a / MANUAL`、`1034-2609000001 / T2609633af33e / MANUAL` |
| `sel_part_signature` | **2** | `T260954a4a04a → 1029-2609000001`、`T2609633af33e → 1034-2609000001` |
| `quotation` | **2** | `T260902-QT-49a59ab5`、`T260902-QT-6b2d5ec1`（name 均 `T260902-报价单-C-ac16b`） |
| `quotation_line_item` | **2** | 挂在上面那两单下 |
| `ds_quote_material_bom` / `_element_bom` / 两张 `_record` 对 `1029` / `1034` | **0** | 已实查，无需回收 |

**合计 12 行 / 6 个对象**。成因已定位：`C-ac16b` 那条会 `submit` 的用例 ⇒ `costing_order` ⇒ 父类 `DELETE FROM quotation` 撞 FK ⇒ 清理事务整段回滚（基座已加 `clearSubmitArtifacts()`，**本轮没再产生同型残留**）。
**可恢复性：不可恢复**（纯测试造数，无业务价值）。
🚦 **回收命令只呈报，🚫 我未执行**；删除顺序需按 FK（`quotation_line_item` → `sel_part_signature` / `ds_quote_customer_part` / `ds_quote_material` → `quotation` → `customer`）。

### 8.3 非本次产生、仅登记不动
`ds_quote_element_bom` 19 行 `source='TEST'`（全属 `T260907R-*` 料号，task260907r 夹具）；
`cpq_db_test` 的 `quotation WHERE quotation_number LIKE 'T260902-QT%'` 共 **20 行**（跨多片多轮，🚫 我不认领、不动）。

### 8.4 scratchpad 隔离副本（会话结束即可弃，无需人工清）
`…/scratchpad/iso-sc-d31/`（权威）· `…/scratchpad/iso-sc-d31-neg2/`（证伪）· `run-d31-{1,2,3,NEG}.log`

---

## 9. 🚨 hook 拦截一次，我未绕路重试（按派工要求上报）

**动作**：建证伪副本时我写了 `rm -rf $NEG`（`$NEG` = scratchpad 下一个**尚不存在**的目录）。
**被拦**：hook 按 `CLAUDE.md §3.2【文件销毁】` 直接拒绝。
**我的处置**：🚫 **没有换写法重试删除**（没改成 `find -delete`、没写脚本、没提权）。
改为**换一个全新目录名 `iso-sc-d31-neg2`**，先 `ls -d` 确认它不存在（输出：「目标目录不存在 ⇒ 全新创建，无需任何删除」），
⇒ **本轮全程 0 次删除文件的操作**。
📌 登记原因：这条拦截本身无害（那是个空路径），但按纪律它属于「被拒后必须上报」的情形。

---

## 10. 其它发现（🚫 未改任何实现，只报）

### 10.1 🟡 新暴露的「理论不达」ERROR —— 每条用例各 1 次，共 10 次

```
ERROR [com.cpq.quo.ser.CardSnapshotService] [card-snapshot] rowKeyFieldsMapFromScope:
  TemplateRenderScope 未打开，无法从冻结快照取 rowKeyFields（理论不达，检查调用链是否漏 open；
  本次降级为无行键）
```
- **为什么现在才看得见**：上一轮 compData 恒 0，卡片物化链路**压根没跑到这里**。D-31 补上前置后它 100% 出现。
- **它自称「理论不达」**，且降级结果是**无行键**。本项目行键有既有教训（行键撞键 ⇒ `editRows` 串行、末值×行数塌缩），
  「无行键」不是零风险的降级形态。
- ⚠️ 我**没有**据此下「缺陷」结论（本片不读实现），也**没有**为它加断言（无对应 AC）。
  👉 **建议主线派后端看一眼这条是不是 D-30 / B-24 那条路径上的同族问题。**

### 10.2 🟢 顺带坐实的三条（都不是本片的 AC，只作交叉证据）
| 项 | 证据 |
|---|---|
| **D-25**（`reusedProductInfo.firstCreatedAt` 恒 NULL）**已修** | `"firstCreatedAt":"2026-09-11T01:28:34.725597Z"` |
| **C-2**（复用未核价料号时材质返空）**已修** | `"materials":[{"recipeCode":"AgCu90","name":"AgCu","ratio":"100"}]` |
| **B-11**（选配侧 `v_compat_*` 引用归零）**运行时达成** | 全量日志 `v_compat` / `42P01` / `500` **各 0 次**（见 §1⑤） |

---

## 11. 「AC 补了前置仍过不了 / AC 本身不可执行」—— 逐条上报，🚫 未改松任何断言

1. 🔴 **AC-18④ 真红** —— 见 §4（唯一一条真红；判别材料齐备，归因需主线裁决）。
2. 🟡 **AC-16② 的「同上」字面不可执行**（第三轮复核仍成立）：`ds_quote_element_bom_record` **没有
   `input_material_no` 列**（粒度列是 `element_code`，另有 `material_part_no`；只读查 `information_schema.columns`）。
   基座 `grainColumn()` 按该表**实际行粒度**取 `element_code`，已在代码注释 + 本节双重登记，🚫 不沉默解释。
3. 🔴 **AC-14 的 `_record.material_ratio` 记载要回写** —— 见 §1④：实测 **`[70.000000000000, 30.000000000000]`
   非 NULL**，与 D-19 / `BL-0253` 的「为空、接受现状」记载相反（至少在 `a2228dae` 模板下）。
4. 🟡 **AC-10⑤「逐字同型」本片只做了只读值域对照**，完整 FT-6 属 S-全局 片（见 §1②）。
5. 🟡 **AC-11 / AC-18⑥ / AC-20 的 UI 层未跑**（E2E 不在本轮派工范围）；后端面已验，
   但 🚫 **不替代 UI**（AP-31 / AP-50 族）。
6. ⚪ **`BL-0233` 的「AC-16① 前置缺口」建议撤回** —— 见 §1⑤ 的更正。

---

## 12. 自检声明

- **编译**：隔离副本 `./mvnw -o -q test-compile` → **exit 0**，6 个 `.class` 全部产出（权威副本 + 证伪副本各一次）✅
- **执行**：4 轮（run1 / run2 权威 / run3 / NEG 证伪），**逐条贴实际返回值原文**，🚫 无「✅ 通过」式空结论 ✅
- **证伪实验**：FT-D31 已做，撤掉干预 → 6 条闸门全红 **且 AC-18④ 反而变绿** ⇒ 干预因果钉死 + 假绿构造性证明 ✅
- **干预生效自检**：每次 `UPDATE quotation SET customer_template_id` 后**回读并硬断言**，回读值逐条打印在日志里 ✅
- **指纹复用假绿**：4 条断「新铸」的用例全部先断 `fingerprintMatched=false`（实测全 `false`），
  零件品名带本轮唯一 `RUN_C`；AC-16 那条**刻意**要 `true`，也已逐字校验 `reusedHfPartNos` ✅
- **断言前先断非空**：所有 `_record` / 页签断言前先打印并断言行数 > 0；空列表 / 0 行 / 「—」一律未当通过 ✅
- **分片纪律**：全部断言按 `material_no=本片自铸料号` 或 `quotation_id=本片自造单` 收窄，
  **无一条全局计数断言**；🚫 未读未改别片数据（`T260910A-` / `B-` / `G-`）；🚫 未碰 `CUST-0004`（只读它的
  `product_category_id`）；🚫 未改共享基座 `SelConfigAcTestBase` ✅
- **连库**：`cpq_db_test`（Flyway 运行时日志 + `current_database()` 双证据）；🚫 未用 `totalElements` 辨库 ✅
- **N+1**：本轮为纯测试改动，未新增/改动任何生产代码循环体 ⇒ 无 N+1 面 ✅
- **红线**：🚫 无 `DROP` / `TRUNCATE` / 无 `WHERE` 的 `DELETE`/`UPDATE`；所有 `UPDATE` 命中面 = `WHERE id=本单`（1 行）；
  所有 `DELETE` 由既有 `@AfterEach` 按 `quotation_id` / `material_no + source='TEST'` 收窄；
  **本轮 0 次删除文件的操作**（§9）✅
- 🚫 未 `git commit`；🚫 未 `cd` 出 worktree；🚫 未在主仓改任何文件；🚫 未跑宽匹配杀进程；🚫 未占 8081 / 5174 ✅
- 🚫 **未为让测试变绿放宽任何一条断言** —— 反而**主动收紧了两处**（§3.3）；唯一的真红（AC-18④）原样上报 ✅

---

# 📌 追加节（2026-09-11 01:50~01:55 UTC）· AC-18④ 按裁决改断言 + 复跑

> 主线裁决：**用户选「甲 = 改 AC」，我的判断对 —— 是 AC 写错了，不是实现错**。
> 本节 = 改断言代码 + 复跑确认 + **新断言的证伪实验**。🚫 未 `git commit`；🚫 未改任何实现文件。

## A. 复跑结果：**9 / 9 全绿**

```
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
  AC-18/19 · 直接绑定已有销售料号                     Tests run: 3, Failures: 0
  AC-11 · 主表有边 ⇒ 两页签渲染非空                    Tests run: 1, Failures: 0
  AC-10/13 · 带版本表直写主表 + _record 建单末尾投影    Tests run: 2, Failures: 0
  AC-14 · 两材质 70/30                                Tests run: 1, Failures: 0
  AC-16 · _record 同内容不重复                        Tests run: 2, Failures: 0
```
证据：`证据/S-C-D31补前置/05-run4-AC18④新口径-9条全绿.log`

**三轮口径演进**：`Failures 7`（D-22，6 条未验证 + AC-18⑥）→ `Failures 1`（D-31 补前置，AC-18④ 真红）→ **`Failures 0`**（AC-18④ 按裁决改口径）。

## B. AC-18④ 改前 / 改后断言（逐字）

### 改前（旧口径，4 条纯计数断言）
```java
// ── ④ BOM 主表与 _record 均零新增 ───────────────────────────────
assertEquals(mbomBefore, count("… FROM ds_quote_material_bom       WHERE material_no='…'"), "AC-18④：主表应零新增…");
assertEquals(ebomBefore, count("… FROM ds_quote_element_bom        WHERE material_no='…'"), "AC-18④：主表应零新增…");
assertEquals(recMBefore, count("… FROM ds_quote_material_bom_record WHERE material_no='…'"), "AC-18④：_record 应零新增…");
assertEquals(recEBefore, count("… FROM ds_quote_element_bom_record  WHERE material_no='…'"), "AC-18④：_record 应零新增…");
```

### 改后（拆成 ④-a / ④-b，共 **13 条**断言 + 3 处实际值打印）
```java
// ── ④-a 主表零新增（这一半原样不变）────────────────────────
assertEquals(mbomBefore, count("… ds_quote_material_bom  WHERE material_no='…'"), "AC-18④-a：主表应零新增…");
assertEquals(ebomBefore, count("… ds_quote_element_bom   WHERE material_no='…'"), "AC-18④-a：主表应零新增…");

// ── ④-b _record 应有投影行，且每行「认领既有行 + 值逐字一致 + source=QUOTE_DRAFT」──
// ⓵ 先断「有行」—— 🚨 不可省：0 行时下面三步全部空转成立，整条 ④-b 会退化成上一轮那种假绿
assertNonEmpty(recMRows.size(), "AC-18④-b：ds_quote_material_bom_record 的投影行数");
assertNonEmpty(recERows.size(), "AC-18④-b：ds_quote_element_bom_record 的投影行数");
// ⓶ 每行 quotation_id 必须 == 本单（排除「读到存量行」这个假绿来源）  —— 逐行 assertEquals
// ⓷ origin_id 全部认领到该料号主表既有行 —— NULL 或认领不上必须红
assertEquals((long) recMRows.size(), claimedM, "…每一行都必须把 origin_id 认领到主表既有行…");
assertEquals((long) recERows.size(), claimedE, "…");
// ⓸ 被认领行与投影行的值**逐字一致** —— 防「认领对了行、却改了值」
assertEquals(0, diffM.size(), "…投影值必须与它认领的主表行逐字一致（🚫 不许改写）…");
assertEquals(0, diffE.size(), "…");
// ⓹ source 值域只能是 QUOTE_DRAFT（D-18）
assertEquals(List.of("QUOTE_DRAFT"), srcM, "…");
assertEquals(List.of("QUOTE_DRAFT"), srcE, "…");
```
⓷ / ⓸ 的判据 SQL（逐条按 `material_no` = 本轮自造既有料号收窄）：
```sql
-- ⓷ 认领
SELECT count(*) FROM ds_quote_material_bom_record r JOIN ds_quote_material_bom m ON m.id = r.origin_id
WHERE r.material_no='<既有料号>' AND m.material_no='<既有料号>';
-- ⓸ 逐字一致（返 0 行才算过）
SELECT r.item_seq, r.input_material_no||' vs '||m.input_material_no, r.material_ratio||' vs '||m.material_ratio
FROM ds_quote_material_bom_record r JOIN ds_quote_material_bom m ON m.id = r.origin_id
WHERE r.material_no='<既有料号>'
  AND (r.input_material_no IS DISTINCT FROM m.input_material_no
    OR r.material_ratio     IS DISTINCT FROM m.material_ratio);
-- element 侧同型，比对列为 element_code / content_pct
```

### 改后的实际返回值（run4 日志原文，被绑料号 `T260910C-BINDb2e75`，单 `f3937bbd-…`）
```
[AC-18④-b] ds_quote_material_bom_record 实际行 (quotation_id/item_seq/input_material_no/material_ratio/source/origin_id) =
    [f3937bbd-dd34-44e2-afe7-37720e2156e3][1][AgCu90][60.000000000000][QUOTE_DRAFT][17004]
    [f3937bbd-dd34-44e2-afe7-37720e2156e3][2][00006] [40.000000000000][QUOTE_DRAFT][17005]
[AC-18④-b] ds_quote_element_bom_record 实际行 (quotation_id/item_seq/element_code/content_pct/source/origin_id) =
    [f3937bbd-dd34-44e2-afe7-37720e2156e3][1][Ag][90.000000000000][QUOTE_DRAFT][18318]
    [f3937bbd-dd34-44e2-afe7-37720e2156e3][2][Cu][10.000000000000][QUOTE_DRAFT][18319]
[AC-18④-b] 本单 = f3937bbd-dd34-44e2-afe7-37720e2156e3
[AC-18④-b 认领] origin_id 能 JOIN 上该料号主表既有行的条数：mbom_rec=2/2，ebom_rec=2/2
[AC-18④-b 逐字一致] 值有差异的行数：mbom_rec=0（差异明细 = (空)），ebom_rec=0（差异明细 = (空)）
[AC-18④-b source] mbom_rec=[QUOTE_DRAFT] / ebom_rec=[QUOTE_DRAFT]
[AC-18] ①~③ / ④-a / ④-b / ⑤ 全部落库断言完成（⑥ 卡片渲染见 ac18_6 用例）
```
（夹具造的既有主表数据是 `AgCu90 60 / 00006 40` + `Ag 90 / Cu 10` ⇒ 投影值与它**逐字一致** ✅）

## C. 四条理由已写进用例注释（原文照抄进代码，防被改回去）

写在 `BindExistingMaterialAcTest#ac18_bindWritesExactlyTwoRows()` 的 ④ 段（27 行注释）+ 类注释。
1️⃣ **D-14 已定 `_record` 的角色** = 报价单对主数据的**投影** / 核价回填的**数据来源** ⇒ 绑定的单卡片确实渲染了该料号 BOM（AC-18⑥ 实测 compData 14 条 / 页签 6 行）⇒ 投影**必然**产生行；「零新增」只在方案② 语境下成立，那个语境随 D-14 消失。
2️⃣ **新断言更强不是更松**：原断言只要求「没有行」，**挡不住「投影凭空造一组新 BOM」**；新断言要求 `origin_id` 认领 + 值逐字一致 ⇒ `origin_id` 为 NULL / 认领不上 / 值不一致**必须红**。
3️⃣ 🚨 **证伪实验是最有力的反证**（已逐字写进注释）：撤掉 D-31 那条 `UPDATE` 后本条在旧口径下**反而变绿** —— 那时 compData=0、投影被静默跳过、`_record` 真零新增 ⇒ **「环境坏了所以碰巧符合一条错的断言」**，不是功能正确的证据。
4️⃣ **「乙 = 改实现让绑定路径不投影」已被否决**：那会让绑定的单**无法参与核价回填**（回填的数据来源就是 `_record`），违反 D-14「两侧功能保持一致」。

**另外改正了一处已被实测推翻的旧注释**（同 D-33 纪律，注释写反比没注释更危险）：
类注释里那段「**📌 D-22 复核结论：本类断言不受 D-14 影响，逐条保持原样** …『主表与 `_record` 均零新增』在两种设计下**都成立** … 本类只加了复核说明，**没有改动任何断言**」——
已改为「🔴 2026-09-11 更正：**前半句对、后半句错**」，并写明 D-22 那轮为什么没看出来（compData=0 ⇒ 0 == 0 恒成立）。
同步更新：`@DisplayName`（原「其余四项零新增」→「主表/指纹零新增，`_record` 投影认领既有行」）、AC 原文清单第 ④ 项、收尾打印。

## D. 🧪 新断言的证伪实验 FT-④b —— 三种破坏，三次**必须红**，三次都红了

> 🚨 首次 PASS 证明不了新断言接上了（`testing.md §4`）。副本 `scratchpad/iso-sc-d31-ft4`，
> 按 `-Dft4.mode` 在 ④-b 之前破坏它保护的条件；**命中面全部限死 `quotation_id = 本用例自造的那一单`**，
> 🚫 不碰任何别人的行。端口 8133。🚫 worktree 与权威副本**均无此插桩**（已 grep 复核，见 §F）。

| 模式 | 破坏动作（命中行数） | 期望 | 实际 |
|---|---|---|---|
| `nullorigin` | `UPDATE ds_quote_material_bom_record SET origin_id=NULL WHERE quotation_id=本单 AND item_seq=1`（**1 行**） | ⓷ 认领 必红 | ✅ **红** `:248 AC-18④-b：…每一行都必须把 origin_id 认领到该料号主表的既有行，实际只认领到 1/2 行` |
| `valuedrift` | `UPDATE … SET material_ratio=99 WHERE quotation_id=本单 AND item_seq=1`（**1 行**） | ⓸ 逐字一致 必红 | ✅ **红** `:276 AC-18④-b：…投影值必须与它认领的主表行**逐字一致**…，实际有 1 行不一致` |
| `empty` | `DELETE FROM ds_quote_material_bom_record WHERE quotation_id=本单`（**2 行**） | ⓵ 非空 必红 | ✅ **红** `:226 → assertNonEmpty:679 🚨 AC-18④-b：…投影行数 为 0 ⇒ 断言从未真正执行（假绿，testing.md §3）` |

⇒ **三条子断言各自被独立证伪一次，且红在对的那一条上**（不是互相遮蔽）。
⇒ 特别地 `empty` 那一枪证明了：④-b 的「先断非空」这道防线**真的接上了** ——
没有它，`_record` 被清空时「全部认领到（0/0）」「无值差异（0 行）」会**空转成立**，整条 ④-b 会退化成上一轮那种假绿。
证据：`证据/S-C-D31补前置/06~08-证伪FT-④b-*.log`

## E. 你另外两条处置我已对齐（无异议，仅登记一句）

| 项 | 主线处置 | 我这边 |
|---|---|---|
| **AC-16① 的 500** | 采纳更正；`BL-0233` 删掉「AC-16① 被它阻塞」这个**错误因果**，保留 `ExistingProductService:91` 的真实 SQL 作退役前置 | ✅ 认同「代码事实成立 ≠ 因果断言成立」。📌 我的日志证据（`v_compat`/`42P01`/`500` 各 **0** 次）只覆盖**选配提交这条链路**，🚫 **不构成对 `ExistingProductService` 那条 SQL 的任何结论** —— 我没走过它 |
| **AC-14 `material_ratio` 取决于模板** | `a2228dae` 挂 `f6d51727 T260907-物料BOM`（**有**该字段 ⇒ 非 NULL）；D-19 用 `7f9a5bbf BOM`（**无** ⇒ NULL）。三处记载已精确化，并写明**不能当通过判据** | ✅ 与我的实测吻合（`[70.000000000000, 30.000000000000]`）。本片代码里它**本来就只是诊断打印、不是判据**（`[AC-14 诊断] …`）⇒ 无需改动，**也正好避免了「用例随模板选择随机红绿」**那个坑 |
| **`BL-0263`**（`rowKeyFieldsMapFromScope … 降级为无行键`） | 已登记，本期不做 | ✅ 无异议。📌 补一条量化：run4 里它出现 **10 次 / 9 条用例**，且**只在卡片物化真的跑起来之后出现** ⇒ 谁要复现它，前置就是 D-31 这条绑模板 |

## F. 本节的自检与留痕

- **rsync#4**：`2026-09-10 18:50:10 PDT / 2026-09-11 01:50:10 UTC`。`git rev-parse HEAD` 仍 **`8ef9eba8205c12de3613bdc0edeb7c5cc45a05c7`**（全程未 commit）。
  实现文件 md5（与 §6 的 rsync#3 **逐字相同**，本节期间无并发改动）：
  ```
  53148b20ae178356b45a66d0776694fe  CardSnapshotService.java
  c2ef236f67e3a74807d1ec1a5f6dc406  ConfigureSnapshotService.java
  d94e298d13ee382cdef3226bdc394f2a  DsQuoteRecordService.java
  ```
- **本节只改 1 个文件**：`BindExistingMaterialAcTest.java`（341 → **424 行**，md5 `7853280a59702e7a9ccc16888454a7e3`）。
  其余 5 个测试文件 md5 与 §6 **逐字未变**（`Task260910CBase` 仍 `3618c3bc…`）⇒ **基座本节零改动**。
- **编译**：权威副本与 FT 副本各 `./mvnw -o -q test-compile` → **exit 0**；0 次 target 冲突假故障。
- **插桩复核**：`/usr/bin/grep -arn "ft4.mode|FT-④b|FT-D31 阶段A" <worktree>/cpq-backend/src/` → **空**；
  权威副本 `iso-sc-d31` 同样 → **空**。插桩只存在于 `iso-sc-d31-ft4` / `iso-sc-d31-neg2`。
- **端口**：8131（权威 run4）/ 8133（FT 三轮）。🚫 未占 8081 / 5174；🚫 未跑宽匹配杀进程；🚫 未杀任何非自身进程。
- **连库**：`cpq_db_test`（run4 Flyway 日志原文 + `SELECT current_database()` = `cpq_db_test`）。
- **残留复核**（采样 `2026-09-10 18:54:37 PDT`）：`T260910C%` 在 `mbom_rec` / `ebom_rec` / `ds_quote_material` /
  `ds_quote_material_bom` / `ds_quote_element_bom` **全部 0**；`ds_quote_customer_part` = **2**，
  经比对**就是 §8.2 登记的前一轮那两行**（`T260910C-AC16C-f48c7` / `-d9069`，12:15 / 12:16 UTC），
  ⇒ **本节新增残留 = 0，待回收清单与 §8.2 逐字不变（12 行 / 6 个对象）**。
- **红线**：本节的 `UPDATE` / `DELETE` 只出现在 **FT 隔离副本**里，且 `WHERE quotation_id = 本用例自造的那一单`（命中 1~2 行，
  与 `@AfterEach` 本来就会删的是同一批行）；🚫 无 `DROP` / `TRUNCATE` / 无 `WHERE` 的写操作；**本节 0 次删除文件的操作**。
- 🚫 **未为让测试变绿放宽任何一条断言**：AC-18④ 由 **4 条计数断言 → 13 条断言**（认领 + 逐字一致 + source 值域 + 非空 + 逐行 quotation_id），
  并已逐条证伪。**判据变强，方向与「改松」相反。**
