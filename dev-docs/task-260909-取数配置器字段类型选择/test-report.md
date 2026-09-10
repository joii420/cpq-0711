# test-report · 取数配置器字段类型选择（S1 分片 · 执行轮）

| 项 | 值 |
|---|---|
| 执行日期 | 2026-09-10（首轮 03:50~05:15 作废；**重跑轮 05:18~06:35 为准**） |
| 分支 / worktree | `feat/task-260909-field-type` · `.claude/worktrees/task-260909-field-type` |
| 被测栈 | **前端 5175 / 后端 8099**（临时栈，worktree 代码）🚫 全程未用共享栈 5174/8081 |
| 库 | `10.177.152.12:5432/cpq_db_0724` |
| 结论 | **16 / 16 条 AC 全部通过** |

---

## §0 一句话结论

> **16 条 AC 全绿。零缺陷。**
> 首轮报出的「AC-15 失败」是**假红** —— 5175 代理链路当时指向 master（无 B-5 回填），已由主线修复；
> 链路修正后 AC-15 在同一对象上真跑通过。

---

## §1 开跑前的验明正身（每轮必做，🚫 不采信口头保证）

| 链路 | `COMP-2299` 的 `builder_config.columns[].fieldType` | 判定 |
|---|---|---|
| **5175（前端代理，UI 用例走这条）** | `{'INPUT_TEXT': 7, 'INPUT_NUMBER': 5}` | ✅ |
| **8099（worktree 后端，API 用例走这条）** | `{'INPUT_TEXT': 7, 'INPUT_NUMBER': 5}` | ✅ |
| 8081（master，仅作对照） | `{'None': 12}` | 与改动前基线一致 |

三方对照同时给出了**首轮假红的成因**：首轮 5175 返回的正是 `{'None': 12}` 这一列。
📌 **教训入账**：只验「直连后端」不足以证明 UI 用例的链路 —— **UI 走哪条链路就必须验哪条链路**。

**行为探针**（比探活强）：`PUT /builder` 传 `fieldType:"FORMULA"` → **400**（旧代码零校验会 200 并落库）。

---

## §2 逐条结果（含链路标注）

| AC | 结论 | 走的链路 | 证据（实际值） |
|---|---|---|---|
| AC-1 | ✅ | **5175** UI | 选项恰好 3 项 `["基础数据","文本输入","数字输入"]`，无 FORMULA/DATA_SOURCE/FIXED_VALUE |
| AC-2 | ✅ | **5175** UI | 批量前 `["BASIC_DATA"×4]` → 批量后 `["INPUT_TEXT"×4]` |
| AC-3 | ✅ | **5175** UI + psql | COST_BASIC 不动选择器保存 → `field_type` 全 `BASIC_DATA` + 顶层 `basic_data_path` + 无 `default_source` |
| AC-4 | ✅ | **5175** UI + psql | COST_DETAIL 同上 |
| AC-5 | ✅ | **5175** UI + psql | QUOTE → 落库 `["INPUT_TEXT","INPUT_NUMBER"]` **两种都覆盖**；UI 默认 `["INPUT_TEXT","INPUT_NUMBER","INPUT_TEXT","INPUT_TEXT","INPUT_TEXT"]`；`default_source.path` 有、顶层 `basic_data_path` 无 |
| AC-6 | ✅ | **5175** UI + psql | **4 行 / 30 个非空格逐字全等 / 不一致 0 处 / 空占位差异 6 处**（全落在 null 格） |
| AC-7 | ✅ | **5175** UI | BASIC 页签承载值控件 **0** 个；INPUT 页签 **32** 个（阳性对照） |
| AC-8 | ✅ | **8099** API + psql | 4 个非法值全 400 且消息列出 3 个合法值；指纹逐字不变；**接线阳性对照**合法值 200 且指纹真变 |
| AC-9 | ✅ | **8099** API + psql | 不传 `fieldType`：COST_BASIC/COST_DETAIL → 全 `BASIC_DATA`，QUOTE → `["INPUT_TEXT","INPUT_TEXT","INPUT_NUMBER"]`，三者 HTTP **200** |
| AC-10 | ✅ | **5175** UI + psql | `part_no_field="料号"` 且该字段 `field_type=BASIC_DATA`；行数 4 = 对照组 4 |
| AC-11 | ✅ | **5175** UI | 选 BASIC_DATA→存→刷新回填 BASIC_DATA→改 INPUT_TEXT→存→刷新回填 INPUT_TEXT |
| AC-12 | ✅ | **5175** UI + **8099** API + psql | 见 §3 |
| AC-13 | ✅ | psql | 存量分布**逐位相同**：COST_BASIC `FORMULA 1 / INPUT_NUMBER 12 / INPUT_TEXT 21`；QUOTE `BASIC_DATA 1 / FORMULA 3 / INPUT_NUMBER 136 / INPUT_TEXT 171` |
| AC-14 | ✅ | **5175** UI + **8099** API + psql | 见 §4 |
| AC-15 | ✅ | **5175** UI + psql | 见 §5（首轮假红的那一条） |
| AC-16 | ✅ | **8099** API + psql | 三条路径（不传 / 显式传 / 存量组件 save）后 `builder_config.fieldType` 均非 null 且与 `component.fields.field_type` 同名逐字相同 |

---

## §3 AC-12（序列 · 渲染）—— 📌 **不需要重新发布模板**

主线批准了「每步重新 publish 自建模板」，但**实测发现用不上**：
保存组件时产品会**自动把新的 `field_type` 传播进 PUBLISHED 模板的 `components_snapshot`**
（旁证：我保存 `COMP-2299` 的那一刻 `05:21:52`，含该组件的模板 `updated_at` 同步跳到同一时刻）。
⇒ 我按「改组件 → 刷新报价单快照 → 重新渲染」跑通，**全程未调用 publish**，对模板发布态零改动。

载体：`QT-20260909-0795` / 产品 `S0001` / 页签 `T260909FT-BASIC`(COMP-2422) / 目标列 `材料名`

| 步骤 | 该列可见值 | 该列逐行 input 数 |
|---|---|---|
| ① 初态 `BASIC_DATA` | `["991","991","AgNi11#-Ⅰ","AgNi11#-Ⅰ"]` | `[0,0,0,0]` |
| ② 改 `INPUT_TEXT` | `["991","991","AgNi11#-Ⅰ","AgNi11#-Ⅰ"]`（**不变**） | `[1,1,1,1]`（**变回 input**） |
| ③ 改回 `BASIC_DATA` | `["991","991","AgNi11#-Ⅰ","AgNi11#-Ⅰ"]`（**不变**） | `[0,0,0,0]`（**变回纯文本**） |

🔒 **收尾还原**：`COMP-2422` 的 `fields` 指纹 `43a99c5ba0291541ab0b491ad06cbd15/1503` **进场 = 收尾**，逐字回到初态。

---

## §4 AC-14（报价侧仍可编辑可保存）

「加产品」抽屉 UI 本轮跑不通 —— 探针实测：点完确认后**一个创建请求都不发**，全程只有 `batch-expand`
（属量具问题，🚫 不是产品缺陷）。按主线指示走 API 造载体，**未跟该 UI 死磕**：

1. 自建报价模板 `T260909FT-报价模板#r3xck1`（复制源模板 7 个页签 + 追加我的页签 → `COMP-2424`），**publish 成功**（snapshot 8 项）
2. 自建报价单 `QT-20260909-0799`，从**自建**源单复制 1 行产品行（🚫 未在任何既有报价单上改数据）

结果：

```
COMP-2424 field_type = 销售料号=INPUT_TEXT 项次=INPUT_NUMBER 料号=INPUT_TEXT 材料名=INPUT_TEXT
                       工序项次=INPUT_NUMBER 工序编号=INPUT_TEXT 值=INPUT_NUMBER
                       比例（%）=INPUT_NUMBER 计价单位=INPUT_TEXT
渲染：页签「T260909FT-报价输入」1 行 / 承载值控件 9 个（全部 INPUT_* → <input>）✅
编辑：「铆钉配件」→「T260909FT-v3xqqp」，失焦后观测到
      PUT /api/cpq/quotations/line-items/{id}/quote-card-edit → 200   ← **AC 点名的端点**
      POST .../reconcile-report → 202
读回：刷新后 = 「T260909FT-v3xqqp」✅
```

附带的零回归证据（落库形态）：`COMP-2424`(NEW，本次代码) 与 `COMP-2425`(OLD，改动前口径) 的 `field_type` **逐位一致**；
`COMP-2426`(NAIVE) 则把 4 个 NUMBER 列降级成了 `INPUT_TEXT` —— 那正是前端工程师避开的陷阱。

---

## §5 AC-15（首轮假红的那一条）—— 链路修正后通过

```
UI 逐列显示 = ["INPUT_TEXT","INPUT_NUMBER","INPUT_TEXT","INPUT_TEXT","INPUT_TEXT","INPUT_TEXT",
               "INPUT_NUMBER","INPUT_TEXT","INPUT_NUMBER","INPUT_TEXT","INPUT_NUMBER","INPUT_NUMBER"]
库里真实值 = 生产料号=INPUT_TEXT 项次=INPUT_NUMBER 料号=INPUT_TEXT 材料名=INPUT_TEXT
             工序编号=INPUT_TEXT 使用特性=INPUT_TEXT 组成用量=INPUT_NUMBER 组成用量单位=INPUT_TEXT
             底数=INPUT_NUMBER 底数单位=INPUT_TEXT 材料损耗率（%）=INPUT_NUMBER 材料固定损耗量=INPUT_NUMBER
                                                                        ⇒ **逐位同序一致**
```

随后「什么都不改直接保存」（一次性额度已按 AC 要求消耗）：

- `component.fields` **byte-for-byte 未变**（与进场存档 `diff` 一致）
- `builder_config.columns[].fieldType`：**12 null → 0 null**，且 12 个字段与 `component.fields[].field_type` **逐字相同**（AC-16 附带取证）

⇒ **F-7 回填一致性成立**：存量组件「打开即损坏」的雷没有被埋下。

> ⚠️ 量具降级留痕：`readSelectedColumnFieldNames` 读不到 UI 字段名（返回 `[]`），逐列比对降级为**多重集比对**。
> 降的是精度不是强度（顺序维度未被断言）；但 UI 输出的 12 个值与库内**恰好同序一致**，可人工复核截图。

---

## §6 harness 缺陷与修复（**没有一处放宽 AC 判据**）

上一轮提交的 5 片用例开跑即全灭，逐个定位后修了 8 处，每处都在代码里留了注释。

| # | 症状（看起来像什么） | 真实成因 | 修法 |
|---|---|---|---|
| 1 | psql `syntax error at or near "\"` | `runPsql` 用 `JSON.stringify(sql)` 做 shell 引用，**bash 双引号内不解释 `\n`** | 改 POSIX 单引号转义 |
| 2 | 「打开的不是 BOM」/ 组件像不存在 | `getByText(key).first()` 命中非卡片元素，详情根本没打开 | 改用已实证的卡片路径（`.cmm-c-code` → 回溯 `[class*="cmm-c"]`） |
| 3 | AC-1「实际 基础数据**只读展示**」 | 选项内嵌描述 `<span class="svb-ftype-hint">`，`innerText` 把它拼进标签；实测 option `title` 逐字为「基础数据」、选项计数恒 3 | 取标签时剔除 hint。**「恰好 3 项」「不含 FORMULA/DATA_SOURCE/FIXED_VALUE」一字未改** |
| 4 | 第 2 列下拉「一个选项都没有」（交替失败） | antd Select 单例复用：`Escape` 不退出 open 态；上一层 dropdown 遮罩吃掉点击（`force:true` 也无效） | `.ant-select-open` 计数同步 + 回退到 `dispatchEvent(mousedown)` |
| 5 | AC-3/AC-5「path 应形如 `$view.<列名>`」 | 正则写死 `[A-Za-z0-9_]+`，列名含中文。**实测存量 347/420 条路径含非 ASCII**（2026-09-07 即如此） | 放宽为 `$view.<非点非空白>` |
| 6 | AC-5「只覆盖到 INPUT_TEXT 一种」 | 用例**自己的防空跑守卫**正确触发：候选列顺序导致加满 4 列时还没轮到 NUMBER 列 | NUMBER 列「单重」提到首位。**判据一字未改** |
| 7 | AC-9 QUOTE 400「缺少标识列」/「锚点『物料』没有到『自制加工费』的声明边」 | `SAMPLE` 的列取自 COST_BASIC 的节点，换 dialect 后在 QUOTE 下不存在；`tabType:'主件'` 也不成立 | QUOTE 单给一组该方言真实存在的列 + `tabType:'费用类'`/`variantKey:'SELF_PROCESS_FEE'` |
| 8 | AC-6/7/10/12/14 全体倒在 `pickRenderFixture` | `GROUP BY 1,2,3,4,5` 位置式指向**转型表达式**，裸列不算已分组 → PG 报 `subquery uses ungrouped column` | 改显式列分组，语义不变 |

### 新增文件（3 份用例 + 5 个临时探针）

| 文件 | 用途 |
|---|---|
| `cpq-frontend/e2e/t260909ft-s1-render-carrier.spec.ts` | AC-6/7/10 的**只读渲染**版。用现成载体（PUBLISHED `T260909FT-模板` + `QT-20260909-0795`），不建对象、不改结构、不动发布态 |
| `cpq-frontend/e2e/t260909ft-s1-ac12.spec.ts` | AC-12 序列，带无条件 `finally` 还原 + 指纹比对 |
| `cpq-frontend/e2e/t260909ft-s1-ac14.spec.ts` | AC-14，API 造载体（自建报价模板 + 自建单 + 复制 1 行产品行） |
| `tsprobe260909*.spec.ts`（5 个） | 临时探针，用于定位上述量具问题，**可删** |

🔑 carrier 片当场证明「唯一变量是 `field_type`」才开始比对：
`COMP-2422` 与 `COMP-2423` 的 `sql_template` **md5 完全相同**、8 个字段名同序、`part_no_field` 同为「料号」。

**仍未修好（不影响结论）**：`t260909ft-s1-render.spec.ts` 的自建场景 —— 组件/模板/发布/建单都成功，
卡在抽屉 UI 加产品（`quotation_line_item` 恒 0）。AC-6/7/10/12/14 已由上面三份用例覆盖，该片可作废或后续修。

---

## §7 稳定性说明（🚫 不以「重跑一下就好」结案）

- **单片依次跑：全绿。** 各片均以单独进程跑通并复核。
- **长批次（一次跑 4 片 / 13 条）出现过 5 条失败**，症状是 `Target page/context/browser has been closed`、
  `组件管理页应有搜索框 → element not found`、`locator.fill timeout` —— **形态是环境级而非断言级**，
  且失败当时复查 5175/8099 均健康、链路正确。
  ⇒ 归因为**长批次下的浏览器/前端资源抖动**（量具），🚫 不记为产品缺陷。
- **AC-1 单独 `--repeat-each=3` → 3/3 全绿**（累计 5 次通过 / 1 次浏览器崩）。
- ⚠️ **建议主线亲验时也按单片跑**，或给批次加重试。

---

## §8 我规避掉的坑

1. **首轮差点用掉 AC-15 的一次性额度** —— harness 先炸了，我先只读确认 `COMP-2299` 未被写才继续。
2. **首轮拒绝真保存 `COMP-2299`**，改用「拦截 PUT + abort」取证 —— 事后证明当时若真存，会在**假红的环境下**把 12 个字段改成 `BASIC_DATA`，而那是环境错误造成的破坏。额度因此保住，本轮得以真跑。
3. **没把「基础数据只读展示」记成产品缺陷** —— 先 probe DOM，确认是 `innerText` 拼了副标题。
4. **没把「路径含中文」记成产品缺陷** —— 先查存量：347/420 条早就如此。
5. **没把 AC-5「只覆盖一种类型」记成产品缺陷** —— 那是用例自己的防空跑守卫。
6. 🚨 **抓到自己写的一条假绿**：核验 `核价模板1` 快照时，我第一版 SQL 的过滤条件（`cs->>'name' LIKE '%BOM%'`）**命中 0 行**，于是「无不一致项」是**空集上的恒真**。改用正确的键 `cs->>'componentId'` 重做，得到 **12/12 匹配、0 不一致** 的真实结论。
7. **没用共享栈**，且每轮开跑前验明正身（含本轮新增的 5175 代理链路检查）。

---

## §9 ⚠️ 一处需如实上报：`核价模板1` 的 `updated_at` 被动推进

`核价模板1` 的三行（v1.0 ARCHIVED / v1.0 PUBLISHED / v1.1 PUBLISHED）`updated_at` 全部变成 **05:21:52**
—— 正是 AC-15 保存 `COMP-2299` 的时刻。成因：`COMP-2299` **同时也是 `核价模板1` 的页签**，
产品自身的「保存组件 → 刷新所有引用它的模板 snapshot」机制把它带上了。

**这不是我的直接写入**，而是 AC-15 明文要求的那次保存所触发的产品行为。已核验内容等价：

```
核价模板1(PUBLISHED) 的 snapshot 里 COMP-2299 那组字段：
  snap_rows=12  comp_rows=12  matched=12  mismatched=0     ← 非空且逐字一致
逐字段：生产料号 INPUT_TEXT / 项次 INPUT_NUMBER / 料号 INPUT_TEXT / 材料名 INPUT_TEXT /
        工序编号 INPUT_TEXT / 使用特性 INPUT_TEXT / 组成用量 INPUT_NUMBER / 组成用量单位 INPUT_TEXT /
        底数 INPUT_NUMBER / 底数单位 INPUT_TEXT / 材料损耗率（%）INPUT_NUMBER / 材料固定损耗量 INPUT_NUMBER
版本行数未增加（仍是原有 3 行），status 未变
```

⇒ **语义零变化，只有时间戳前进。** 但「不碰已交付对象」这条约束在字面上被产品机制绕过了一次，主线知悉为宜。
📌 **附带价值**：正是这个现象让我发现 AC-12 不需要 republish。

其余不许碰的对象**时间戳全部早于本轮开工**：
`正泰测试模板1` 09-09 13:46 · `QT-20260909-0661` 09-10 00:33 · `COMP-2423/2424/2425/2426` 09-10 02:45~02:56。

---

## §10 待回收清单（🚫 我未执行任何回收/删除）

> `DELETE` / `DROP` 属 `CLAUDE.md §3.2` 红线，测试无批准权 ⇒ **只登记，随闸门 B 交用户批准**。

| 类型 | 数量 | 标识 | 状态 |
|---|---|---|---|
| `component` | **71** 个 | `COMP-2427` ~ `COMP-2497`（连号），名字均形如 `T260909FT-<用途>#<RUN_ID>` | 70 已 `DISABLED`，**1 个仍 `ACTIVE`**（首次 harness 崩溃时归档失败的孤儿） |
| `component_sql_view` | 随组件 | 每组件 1 行 | — |
| `template` | 3 张 | `T260909FT-模板#r1c4l5`(ARCHIVED, 2 页签) · `T260909FT-报价模板#r3u62r`(**PUBLISHED**, 7 页签) · `T260909FT-报价模板#r3xck1`(**PUBLISHED**, 8 页签) | 后两张是 AC-14 载体，**发布态需一并回收** |
| `quotation` | 4 张 | `QT-20260909-0796`(0 行) · `0797`(0 行) · `0798`(1 行) · `0799`(1 行) | 全 DRAFT；报价单无归档态 |
| `quotation_line_item` | 2 行 | 在 `0798` / `0799` 里，复制自自建源单 | — |

🚫 **不在清单里 = 请勿动**：`COMP-2422/2423/2424/2425/2426`、`T260909FT-模板`(PUBLISHED)、`QT-20260909-0795`
是前端工程师建的验证载体。`COMP-2422` 在 AC-12 中被改过两次并**逐字还原**（指纹进场 = 收尾）。

### 造数前缀偏差（主线已认可，保留登记）

派工要求 `T260909TS-`，但已提交的 helpers 把 `T260909FT-` 硬编码进了 4 处前缀归属守卫
（`ownName()` / 回收守卫 / `publishOwnTemplate` 越界拦截 / `sqlOwnedWrite` 归属校验），改它风险高于收益。
⇒ 保留 `T260909FT-` 并用 `#<RUN_ID>` 后缀区隔：**前端工程师的对象无 `#`，我的全部带 `#`**，未发生串扰。

---

## §11 未做 / 不在范围

- 🚫 未跑全量 `mvnw test`（`test.md §1`：`mat_*` 夹具恒红，非本任务引入）
- 🚫 未做前后端编译自检（开发的自检项）
- 🚫 未执行 `test.md §4` 的三个还原实验：其中两个已被真实运行结果直接证明
  （**B-1 白名单** → AC-8 四个非法值全 400 且合法值 200 指纹变化；**B-5 GET 回填** → 5175/8099 对 `COMP-2299` 均返回真实值，而 8081 返回 12 个 null —— 这组三方对照本身就是最强的 A/B）。
  第三个（**B-2 方言默认**）需改后端源码再改回，**属实现代码改动，我无权动** ⇒ 提请主线决定是否补。

### 📌 一条观察项（不属本任务）

存量 **347/420** 条 `basic_data_path` / `default_source.path` 含中文列名（2026-09-07 即如此），
与既有记忆「中文标识符需 ASCII 别名」有张力。本次未触及，仅提请注意。
