# 测试执行报告 · task-260904-页签类型收缩（第一批 19 条 AC）

> 方案与 AC 追溯矩阵见同目录 `test.md`（闸门 A 前置产物）；本文件是**执行结果**，两份不要混。
> AC 原文出处：`需求文档.md §③`；接口契约：`api.md`。

| 项 | 值 |
|---|---|
| 最终干净跑 | **run13**，2026-09-05（`mvnw clean` + `-Dmaven.compiler.useIncrementalCompilation=false` 全量重编译 + 无任何 flyway 绕过） |
| 后端结果 | **Tests run: 27，Failures: 0，Errors: 0，Skipped: 0 · BUILD SUCCESS** |
| E2E（AC-24） | ⏸ **未通过 / 未完成**，卡在选择器；根因已定位为**用例侧**，非产品缺陷（见 §4） |
| 库 | `10.177.152.12:5432/cpq_db_0724`（共享开发库，`test` profile 默认库） |
| 残留 | 自建数据 **全 0**；`semantic_tab_view` 非 ACTIVE **0**；`admin` = **ACTIVE** |

跑测试的命令：
```bash
cd cpq-backend
./mvnw -o -q clean
./mvnw -o -q test-compile -Dmaven.compiler.useIncrementalCompilation=false
./mvnw -o test -Dtest='AddLeafTypeAcTest,AddLeafGuardAcTest,AddLeafSequenceAcTest,BackfillCharacteristicAcTest,\
DualCriteriaAcTest,FormulaGateAcTest,LegacyZeroChangeAcTest,RestrictedTabAdmissionAcTest,TreeJudgementHardcodeScanAcTest'
```
> ⚠️ **必须先 `clean`**：本任务期间多次撞到「切代码后不 clean 直接跑」的幽灵 class
> （`NoClassDefFoundError: VersionFilterMacro / SemanticValidationException / BomNodeTypeResolver$MasterTypeIndex`、
> `NoSuchFieldError: CompMeta.treeTab`）。那类报错**长得和业务回归一模一样**。

---

## 1. 最终追溯矩阵（27 条用例逐条）

用例目录：`cpq-backend/src/test/java/com/cpq/task260904/`

| # | AC | 用例（类·方法） | 结果 | 关键实测值 |
|---|---|---|---|---|
| 1 | AC-4 材质表命中 | `AddLeafTypeAcTest.ac4_recipeHit` | ✅ PASS | `00001` → `__nodeType=材质`；阴性前置：该单据无任何页签渲染过它 |
| 2 | AC-5 material_type 判定 | `.ac5_materialTypeDrivesNodeType` | ✅ PASS | `S0003`→外购件、`S0001`→零件；当次分布 零件=3 / 外购件=1 / NULL=43 |
| 3 | AC-12 空值兜底 | `.ac12_nullMaterialTypeDefaultsToPart` | ✅ PASS | 判零件 + 可再作宿主 + `materialTypeFallback:true`；**含阴性对照**（有类型的行 `false`） |
| 4 | AC-26 成品拦截 | `.ac26_finishedGoodsStillRejected` | ✅ PASS | 跨行成品 `COMP2` → 400「COMP2 是成品料号，不能作为子件挂入」 |
| 5 | AC-6 存在性 | `AddLeafGuardAcTest.ac6_partNotInMasterRejected` | ✅ PASS | 400 + 点名料号 +「在物料表与材质库中都不存在」；`snapshot_rows` 行数前后不变 |
| 6 | AC-7 环检测 | `.ac7_cycleRejected` | ✅ PASS | 真环给 `A → B → A`、自环给 `A → A`（**响应真带环路径**）；行数不变 |
| 7 | AC-8 既有护栏 | `.ac8_materialAndOutsourcedHostStillRejected` | ✅ PASS | 「材质节点不可再添加下级」/「外购件节点不可再添加下级」 |
| 8 | AC-11 序列 | `AddLeafSequenceAcTest.ac11_nodeTypeSurvivesReopen` | ✅ PASS | `PUT /draft baseVersion=0 → 200`；`__nodeType 加叶子时=材质 保存并重开后=材质` |
| 9 | AC-9 不再回填类型 | `BackfillCharacteristicAcTest.ac9_backfillDoesNotWriteNodeTypeIntoCharacteristic` | ✅ PASS | `addedRows=1` 且新行 `characteristic=null`（喂进去的是中文「材质」） |
| 10 | AC-27① / AC-21① | `DualCriteriaAcTest.ac27_branchOne_newComponentBoundToMaterialBom` | ✅ PASS | `builder_version=1`、`tab_type=null`、`bom_recursive_expand=true` |
| 11 | AC-27② 分支② | `.ac27_branchTwo_legacyComponent` | ✅ PASS | 存量 `422fd880…` tab_type=BOM / builder_version=NULL / expand=true |
| 12 | AC-27③ 分支①优先 | `.ac27_branchOneWinsOverLegacyTabType` | ✅ PASS | 先埋 `tab_type='BOM'+expand=true` 污染源，存「自制加工费」后被重算为 false |
| 13 | AC-27 前置（A 的 tab_type 为空） | `.ac27_newComponentShouldNotWriteTabType` | ✅ PASS | 经配置器真实保存后 `component.tab_type = null` |
| 14 | AC-21③ 阴性对照 | `.ac21_feeSourceIsNotTree` | ✅ PASS | 自制加工费 → `bom_recursive_expand=false` |
| 15 | AC-21② 下游认它 | `.ac21_newTreeComponentGetsUnionDriver` | ✅ PASS | 见 §2 的三行树骨架 |
| 16 | AC-18①②③ 公式闸门 | `FormulaGateAcTest.ac18_formulaTokenGateFollowsDataSourceSemantic` | ✅ PASS | ① B+`tree_ref`→400 且点名公式；② A+`tree_ref`→**200**；③ A+「上一行」→400 且点名公式 |
| 17 | AC-13 在途单零回归 | `LegacyZeroChangeAcTest.ac13_inflightQuotationsUnchanged` | ✅ PASS | 9225 条报价行；`quote/costing_card_values` md5 差异 **0**，缺行 **0** |
| 18 | AC-14 27 模板路由不变 | `.ac14_templatesWithTreeTabUnchanged` | ✅ PASS | 基线 27 个含树模板；9259 行树序 md5 差异 **0**；冻结快照 tab_type 0 改写 |
| 19 | AC-15 未配组件不受影响 | `.ac15_unconfiguredComponentsUnaffected` | ✅ PASS | 9225 行（tab_type 空）差异 0；新组件不带 tabType 保存 200 且不被补 tab_type |
| 20 | AC-25① 都能打开 | `.ac25_allLegacyComponentsStillOpenable` | ✅ PASS | 107 个已配组件详情全 200；`semantic_tab_view` 45 行 / 非 ACTIVE **0**；13 个 QUOTE 取数入口全通 |
| 21 | AC-25② 树逐字不变 | `.ac25_legacyTreeComponentsUnchanged` | ✅ PASS | 9259 行 树行数 + 树序 md5 + 内容 md5 差异 **0** |
| 22 | AC-25② 补强 | `.ac25_legacyComponentsStillOnBranchTwo` | ✅ PASS | 真·存量 107 个，其中 BOM 树 **21** 个，分流违例 **0**；全库 `builder_version` 非空**现场探测**（只打印不断言） |
| 23 | AC-25③ 零件/外购件 | `.ac25_legacyPartAndOutsourcedUnchanged` | ✅ PASS | 零件 22 行 / 外购件 22 行，差异 0 |
| 24 | AC-25④ tab_type 未改写 + **未被删除** | `.ac25_legacyTabTypeNotRewritten` | ✅ PASS | 168 个存量组件差异 **0**；「基线里有 tab_type 而现已消失」**0**（豁免名单见 §5） |
| 25 | AC-17① 受限页签拒绝 | `RestrictedTabAdmissionAcTest.ac17_partWithChildrenStillRejected` | ✅ PASS | 文案逐字 =「该料号在 BOM 树上已有下级，不能添加到「材质元素」页签」 |
| 26 | AC-17② 阴性对照 | `.ac17_leafPartStillAllowed` | ✅ PASS | 叶子放行 + 真落库（锁住「判据是树结构不是类型」） |
| 27 | AC-22 硬编码收编 | `TreeJudgementHardcodeScanAcTest.ac22_hardcodedTreeJudgementCollapsedToOnePlace` | ✅ PASS | 扫 962 个源文件；收口点以外 **0** 处、收口点内部 **0** 处（上限 0） |
| — | **AC-24 前端语义闸门** | `cpq-frontend/e2e/task260904-semantic-gate.spec.ts` ×2 | ⏸ **未完成** | 见 §4 |

**第一批 19 条 AC 全部有用例；除 AC-24 外全部 PASS。**

---

## 2. AC-21② 的硬证据（后端无夹具、由本 harness 补验）

配置器产出的树组件（`builder_version=1`、`tab_type=null`、`bom_recursive_expand=true`）挂进
真实模板 + 真实报价行 + `snapshotQuotation` 物化后，`snapshot_rows` 是**树骨架**：

```
行数 = 3，每行 7 个系统列齐全
  __nodeId=COMP1                                 __parentId=None                   __lvl=1  __hfPartNo=COMP1            __nodeType=None
  __nodeId=COMP1/0526-2609000004                 __parentId=COMP1                  __lvl=2  __hfPartNo=0526-2609000004  __nodeType=零件
  __nodeId=COMP1/0526-2609000004/0526-2609000005 __parentId=COMP1/0526-2609000004  __lvl=3  __hfPartNo=0526-2609000005  __nodeType=零件
```

**分辨力**：同一用例、同一夹具，在 `TabSemanticResolver` 修复前拿到的是
`[{"driverRow":{"hf_part_no":"…","_物料BOM_投入料号":"00006","_物料BOM_组成数量":null},…}]`
—— 平铺业务列、无一个 `__` 系统列。⇒ 这条不是恒绿。

---

## 3. 证伪实验（6 条，主线执行；第 7、8 条由测试自行执行）

| # | 注入 | 结果 | 失败信息说的是那件事吗 |
|---|---|---|---|
| 1 · AC-6 存在性 | 注释掉守卫 | ✅ 变红 | `应返回 400，实际=500` |
| 2 · AC-7 环检测 | `cyclePath` 恒 null | ✅ 变红 | `真环应被拒 400，实际=200`，响应体里能看到环真被建出来 |
| 3 · AC-26 成品拦截 | 规则五恒不命中 | ✅ 变红 | `成品料号作子件挂入应被拒，实际=200` |
| 4 · AC-9 回填 | 改回写 `characteristic` | ✅ 变红 | `实际写入了「材质」` |
| 5 · AC-27③ 双判据优先级 | 改成分支②优先 | ✅ 变红 | `实际仍为 true`、`写反了` |
| 6 · AC-22 硬编码扫描 | 还原一处 `"BOM".equals` | ❌ **未变红 → 判据已收紧，见下** | — |
| 7 · AC-22（收紧后复验） | 同上，注入 `PublishedTemplateReader.java:165` | ✅ 变红 | `越界位置：template/service/PublishedTemplateReader.java 165: if ("BOM".equals(s.tabType))…` |
| 8 · AC-25④ 删除守卫 | 把一个 id 移出豁免名单 | ✅ 变红 | `基线里的存量已配组件有 1 个在库里消失了 —— **删除也是变化**：component COMP-0785…` |

### 3.1 🚨 AC-22 的假绿：根因与修法

原判据（`TreeJudgementHardcodeScanAcTest`）：
```java
if (hitsByFile.size() > 1) { fail(...); }   // 只在「散落 >1 个文件」时失败
```
**只数个数、不看是哪个文件**。主线把一处 `"BOM".equals` 注入 `PublishedTemplateReader.java:165` 后，
它恰好成了「唯一的那个文件」⇒ 用例**检测到了、打印了、然后通过了**：
```
[AC-22] 非注释命中 1 处 / 1 个文件：PublishedTemplateReader.java:165
[INFO] Tests run: 1, Failures: 0
```
而 AC-22① 原文要求的是「**仅允许留在 §1.35 的双判据方法内部**」。

**修法（两条并列，缺一不可，🚫 不许退化成「命中数 ≤ N」）**：
1. 非注释命中的文件集合 ⊆ `{component/service/TabSemanticResolver.java}`（**点名允许的文件**）；
2. 命中总数 ≤ `MAX_HITS_IN_COLLAPSE_POINT`（= **0**，实测：收口点用的是常量 `TAB_TYPE_TREE.equals(...)`，
   不是字面量）—— 防止有人把新的散落判断塞进收口点文件本身。

失败信息点名 **文件 : 行号 : 原文**。收紧后已自行证伪（表中第 7 行），注入文件 md5 逐字节还原、`FALSIFY-INJECT` 残留 0。

### 3.2 证伪实验过程中沉淀的两条方法论（建议进 `docs/反模式.md`）

1. **「注入违规看它是否失败」不能只看退出码，还要看它说了什么。**
   `task-260819` 的一版代码无条件打「自检通过」再抛异常，注入后日志里两者同时出现。
2. **证伪的前提是先证明干预真的生效**，否则「没变红」既可能是用例没测到、也可能是根本没改到。本任务实证两例：
   - **AC-6 的守卫有两道**（`QuotationTreeService.addLeaf` ⑤ 步 + `BomNodeTypeResolver:302` 的 `resolveStrict`）。
     只注释一道时用例仍绿 —— 那个绿**是有效的**，干预没让守卫失效。同时注释两道才变红。⇒ 纵深防御，不是缺陷。
   - **AC-27③ 第一次注错了位置**：改 `decideTree`（渲染路由/公式闸门）不影响它 ——
     它断言的 `bom_recursive_expand` 走的是 `builderTreeFlag`（`applyTabType` 内），**两条不同路径**。改对位置后立刻变红。

---

## 4. AC-24（前端语义闸门）—— 未完成，卡在用例侧

### 4.1 环境（已验证可用，非阻塞点）

| 项 | 状态 |
|---|---|
| 临时后端 | `mvnw quarkus:dev -Dquarkus.http.port=8085` → 业务端点 **401**（存活判据），`Current version of schema "public": 417` |
| 临时前端 | `VITE_PORT=5177 VITE_API_TARGET=http://localhost:8085 npm run dev` → **200** |
| `admin` | **ACTIVE** |
| 收尾 | 两个服务已停，8085 / 5177 已释放；主线的 5174 全程未碰 |

### 4.2 已跑通的部分

- 经真实配置器保存路径建出组件：`builder_version=1`、`tab_type=(null)` ✅
- 在 UI 里**找到并打开**了新组件与存量 BOM 树组件 ✅
- 进入「字段配置」Tab 并展开字段类型下拉 ✅（截图已归档）

### 4.3 卡住的地方与**为什么判定是用例侧**

两条用例**在完全相同的位置、以完全相同的方式**失败：
```
公式 Tab 里找不到「父子取值 / 父取值 / 子件汇总」任一文案
```
🚨 **失败的不只是新组件，存量 BOM 树组件（AC-24② 的对照组，`tab_type='BOM'`，行为应与改动前一致）也一样失败。**
⇒ 若真是「前端把树语义闸门灰掉了」，存量组件不该受影响。两侧同时失败 ⇒ 是**选择器找错了层级**
（「父子取值」很可能在公式编辑器抽屉里，需要先点某个「编辑公式」入口），不是产品缺陷。

> 依据 `docs/rules/testing.md §4.1.5`：**「确定性复现」只证明「不是 flaky」，不证明「是缺陷」**；
> 且报错的代价不对称 —— 把「选择器错」报成「产品缺陷」会让人去改本来是对的代码。
> ⇒ 本条**不作为缺陷上报**，作为**用例未完成**上报。

### 4.4 已沉淀进 spec 的三条实测（下一次接手不用重走）

1. `/components` 是「**组件目录树 + 右侧详情**」布局，`table` / `.ant-table-row` / `.ant-list-item` / `.ant-card`
   计数**全为 0**；搜索框 placeholder = `🔍 搜索组件名 / 编码`。
2. **经 API 建的组件必须带 `directoryId`**，否则搜索框搜不到（实测 `HIT_COUNT=0`）——
   那不是产品缺陷，是夹具没落进 UI 能看见的地方。spec 已改为取「存量 BOM 树组件所在目录」。
3. 组件详情右侧是 **Tabs**，不是按钮组：
   `["组件","数据源","全局变量","核价模板","字段配置","取数配置","公式","SQL 视图"]`。
   命中项 `click()` 会 30s 超时，需 `click({ force: true })`。

### 4.5 还需要什么

只差**「公式」Tab 内进入公式编辑器的入口选择器**。建议下一次用一个一次性 probe spec 打印
「公式」Tab 内的 `button` / `.ant-collapse-item` / `.ant-radio-button-wrapper` 文本再定位。
🚫 **改选择器，不改断言**（「三处均可用/不置灰」直接来自 AC-24 原文）。

截图归档（**任务目录内，不会被下一轮清空**）：`证据/e2e/ac24-0*.png`（8 张）。

---

## 5. 环境事实（会影响任何人读这份报告的判断）

### 5.1 `V416` / `V417`：同型复发两次的迁移文件缺失

| 迁移 | 应用到共享库 | 文件何时进 master |
|---|---|---|
| `V416__task260819_v9_bridge_narrow_form` | 2026-09-04 17:58 | `e70635d6`（本任务发现后补入） |
| `V417__task260819_v9_tab_type_key_value_fix` | 2026-09-05 02:04 | `648c9485`（本任务再次发现后补入） |

两次症状相同：`FlywayValidateException: Detected applied migration not resolved locally` ⇒
**worktree 里任何 `@QuarkusTest` 都起不来**（27 条里 26 条 Skipped，1 条 Error）。
期间的临时规避是命令行 `-Dquarkus.flyway.validate-on-migrate=false`（不改文件、不改库、🚫 不跑 `flyway repair`）；
文件补入 master 后已撤掉，最终跑不带任何绕过。

> 📌 `V417` 顺带改了**内部坐标**：`semantic_tab_view.tab_type` 由显示名 `'BOM 树'` 纠正为键值 `'BOM'`（D-39）。
> ⚠️ **`api.md §0 / §1.2` 的示例仍写着 `"tabType": "BOM 树"`，已是过期契约，需回写。**
> 用例侧已同步（`Task260904Base.CFG_MATERIAL_BOM` 与 E2E spec），断言一个字未动。

### 5.2 共享库计数漂移（🚫 全部只作现场探测，不写进任何断言）

| 指标 | 变化 | 原因 |
|---|---|---|
| `component` 总数 | 223 → 168（基线内仍存活） | `task-260819` 清理 `SQLVB-TEST-*` 残留，**连带删掉 55 个在本任务基线里的组件** |
| 存量已配组件（有 `tab_type`） | 109 → 119 → 143 → **107** | 同上；中途的 119/143 是各会话在途自建组件 |
| `component_sql_view.builder_version` 非空 | 0 → 29 → **0** | 同上 |
| `ds_quote_material` | 立项 45 → 半日后 71 | 别的会话在写 |

⇒ 本套用例**所有数量一律执行期现场探测**，断言只落在「该分支确实被执行过」「分流结果正确」上。

### 5.3 被删掉的 2 个「存量已配组件」及其豁免依据

`AC-25④` 新增的「删除也是变化」守卫，登记了 2 个已查明来源的豁免（`KNOWN_RESIDUE_DELETIONS`）：

| id / code / 基线 tab_type | 证据强度 |
|---|---|
| `46b02fae…` / `COMP-0785` / 材质元素 | **直接确证**：删除前实测 name = `SQLVB-TEST-fee-846f3056-…`（2026-09-03 建） |
| `2214b8f7…` / `COMP-0347` / 材质元素 | ⚠️ **未能直接确证**（删除时已不在库，读不回 name）。旁证：在 `template_component` / `template_component_snapshot` / `quotation_line_component_data` 三表引用数**均为 0**，与 COMP-0785 完全同型 |

🚫 该名单只装「已查明来源的 id」，不是放宽判据；任何**别的**存量组件消失仍硬失败。

### 5.4 `SQLVB-TEST-*` 残留是重复发生的问题

`需求文档 §1.35` 记过「原有 21 行 `SQLVB-TEST-*` 是测试残留、用户批准后已删除」——**同一类残留又长回来了**，
且这次连带删掉了别的任务的基线对照物。建议向 `task-260819` 提「测试套件自清理」的要求。

---

## 6. 测试自身的纪律与已知局限

### 6.1 共享库卫生

- 🚫 全套用例**不出现** `TRUNCATE` / `DROP` / 无 WHERE 的 `DELETE` / 清库 / 全局配置重置。
- 🚫 **不借道全局 active 配置**：既有 `QuoteBomTreeEndToEndTest` 造合成树时会临时把生产的
  `costing_bom_tree_config(usage='QUOTE')` 置 `is_active=false` 再恢复 —— 那个窗口期里
  **别的会话打开任何报价单都会渲染出合成树**。本套改为往 `material_bom_item` 插自己的边
  （`customer_no` 是自建唯一值），生产配置一个字节不动。
- **登记的唯一一处全局状态触碰**：`adminSession()` 执行
  `UPDATE "user" SET failed_login_attempts=0, locked_until=NULL WHERE username='admin'`
  （沿用既有 `SelConfigAcTestBase`，只解锁，不改密码/状态/角色）。
- 跑完实测残留：`component / quotation / customer / material_bom_item / template / ds_quote_material` 的
  `t260904_`·`T260904` 前缀命中 **全 0**；`semantic_tab_view` 非 ACTIVE **0**；`admin` = **ACTIVE**。

### 6.2 改动前基线

`证据/baseline/`（含 `README.md`）。采集于 2026-09-05 05:57 UTC，当时
`git rev-list --count master..HEAD` = 0、工作区干净 ⇒ **实现一行都还没写**。🚫 不许重采。

### 6.3 🚨 仍未验证 / 未覆盖的项（不含糊列全）

| 项 | 状态 | 说明 |
|---|---|---|
| **AC-24（两条 E2E）** | **未完成** | 卡在「公式」Tab 内的入口选择器；见 §4。**这一条 AC 目前没有任何通过的验证** |
| AC-1 / 2 / 3 / 10 / 19 / 20 | **不在本批** | 第二批，依赖 `task-260819` 合并（`需求文档 §①bis`） |
| AC-12③ 的「服务端**日志**」侧 | 未验证 | AC 原文是「日志**或**响应」二选一；本套只验了响应侧的 `materialTypeFallback`，日志侧未断言 |
| AC-14「各取一张单打开」的**真机 UI** | 未验证 | 本套是服务端逐行 md5 比对（27 模板 / 9259 行），**没有从 UI 打开过那 27 张单** |
| AC-25①「不白屏」 | 部分验证 | 服务端验的是「详情端点 200 + 取数入口不 404」；**「白屏」是前端现象，未从 UI 验** |
| AC-22 的**前端侧** | 未覆盖 | AC-22 只约束后端；前端 `tabType === 'BOM'` 现网 10 处，本套未扫（属 AC-24 的行为面） |
| `mvnw test` **全量回归**（`test.md` R-9） | 未跑 | 本套只跑了 task260904 的 9 个类。全量回归与失败项 A/B 归因**仍需主线执行** |
| 冷启动验证（`testing.md §5`） | 未跑 | 闸门 B 之前需补 |

> 🚫 上述任何一项都**不能**用「后端 27/27 全绿」代替。
> 测试证明「代码按实现者的理解工作」，AC 证明「功能符合需求文档」—— 两者不可互换。

---

# 附录 · 主线亲验（2026-09-06，`CLAUDE.md` §4.5 步骤 4）

> 🚫 **测试全绿不构成 AC 达成的证据** —— 本节是主线在真实服务 + 共享 dev 库真实数据上、按用户视角复验的独立记录。
> **环境**：worktree 代码 + 临时服务（后端 8086/8087、前端 5178），已确证服务的是本分支的树
> （`curl /src/utils/tabSemantic.ts` 命中 10 处**运行时**标识符 —— ⚠️ 不能用 TS 类型声明确证，Vite 转译时类型会被擦除）。

## A. 主线亲手验到的（每条附可复核证据）

| AC | 证据 | 数据来源 |
|---|---|---|
| **AC-6** 主数据不存在→拒绝 | `400 {"message":"料号「VERIFY-NOT-EXIST-9Z」在物料表(ds_quote_material)与材质库(material_recipe)中都不存在…","data":{"code":"LEAF_PART_NOT_IN_MASTER"}}` | **真实单 QT-20260901-0233** |
| **AC-6** 不落任何行 | 三次拒绝后树行数**仍是 2**（基线 2） | 同上 |
| **AC-4** 材质判定 | `nodeType=材质`、树行数 2→3；料号 `992` 命中 `material_recipe` | 同上（**从主数据判，非页签命中**） |
| **AC-21 + S-8(c)** | 经真实配置器保存：`tab_type` 为空 · `bom_recursive_expand=t` · `builder_version=t` · 内部坐标 `BOM` | 新建组件 |
| **AC-21③** 阴性对照 | 绑「自制加工费」→ `bom_recursive_expand=f` | 证明非无脑置 true |
| **AC-27③** 分支①优先 | 埋入 `tab_type='BOM'`+`expand=true` 污染源 → 重存后被重算为 **`f`** | 写反成分支②优先时此处会是 `t` |
| **AC-25①** 存量可打开 | 随机 20 个存量组件详情 → **200: 20 / 非200: 0** | 真实存量 |
| **AC-25④** 存量未被改写 | 21 个 `tab_type='BOM'` 组件全部 `expand=t`，**一个未改写** | |
| **AC-22** 硬编码收编 | 主线独立扫描：非注释 `"BOM".equals` **全工程为空**；收口点内部用的是常量 `TAB_TYPE_TREE`，非字面量 | 不采信测试的扫描结果 |
| **冷启动** | `Successfully validated 400 migrations` · `Current version: 417` · `started in 10.736s` · 业务端点 **401** | 干净冷启动 |

## B. 证伪实验（主线执行，6 条）

| # | 注入 | 结果 | 失败信息是否点名那件事 |
|---|---|---|---|
| AC-6 存在性 | 注释掉守卫 | ✅ 变红 | `应返回 400，实际=500` |
| AC-7 环检测 | `cyclePath` 恒 null | ✅ 变红 | `真环应被拒 400，实际=200`，响应体可见环被建出 |
| AC-26 成品拦截 | 规则五恒不命中 | ✅ 变红 | `成品料号作子件挂入应被拒，实际=200` |
| AC-9 回填 | 改回写 `characteristic` | ✅ 变红 | `实际写入了「材质」` |
| AC-27③ 优先级 | 改成分支②优先 | ✅ 变红 | `实际仍为 true`、`写反了` |
| **AC-22 扫描** | 还原一处 `"BOM".equals` | ❌ **未变红 → 已修** | 判据 `hitsByFile.size() > 1` 比 AC 原文宽 |

**两条过程教训**：
1. **AC-6 的守卫有两道**（`addLeaf` ⑤ 步 + `BomNodeTypeResolver:302`）。只注释一道时用例仍绿，**那个绿是有效的** —— 干预没让守卫失效。同时注释两道才变红。
2. **AC-27③ 首次注错位置**：改 `decideTree`（服务渲染路由/公式闸门）不影响它 —— 它断言的 `bom_recursive_expand` 走 `builderTreeFlag`，**两条不同路径**。
🔑 共同印证：**证伪实验的前提是先证明干预真的生效**，否则「没变红」既可能是用例没测到，也可能是没改到。

## C. 亲验期间的两项发现（均非本次引入）

**① 删除叶子不生效**（不在本次 AC 范围，未归因）
```
POST tree/delete  mode=PRUNE + previewToken → HTTP 200
实际：节点仍在 snapshot_rows；deleted_row_keys = []
```
删除链路**未被本次改动触碰**（`QuotationTreeService` 的 192 行改动中，唯一提及 delete 的是一行注释）。**也可能是主线 `mode` 用法不对**。污染由主线用精确 SQL 清除（改前量化「1 行 1 元素」+ 落盘备份，`UPDATE 1`，清理后 `nodeId=["202601010023","202601010023/992"]` 回到原状）。

**② AC-26 在现网数据下被 AC-6 遮蔽**：成品料号 `202601010023` **本身不在 `ds_quote_material` 里**，存在性校验先拦，成品拦截走不到。两道都拦住不是缺陷，但 AC-26 的现网可达性依赖「成品进主数据」这个前提。

## D. 🚨 AC-14 的字面要求在现网不可执行

AC-14 原文要求「27 个含树模板各取一张单打开」。实测：

```
tab_type='BOM' 的冻结快照行 = 27，分属 27 个模板      ← 「27」这个数字是对的
其中真被报价行引用的 = 1 个模板（5 张单 / 9225 行）    ← 另外 26 个没有任何单据
```

⇒ 「各取一张单」只能做到 1/27。测试代理的服务端 md5 比对（覆盖 9259 行、差异 0）**实际覆盖面反而更大**，是更有效的证据。
📌 **AC-14 的措辞应在结案时修正**，避免下一个人按字面执行后误判为「验不过」。

## E. 全量回归（`test.md` R-9）—— 主线执行

```
mvnw clean → mvnw test（全量，无任何绕过）
共 180+ 个测试类；失败类 83 个
```

🔬 **A/B 归因结论：83 个失败全部非本次引入**，双向对照逐字一致：

| 对照 | 干净 master（主工作区） | 本分支 |
|---|---|---|
| `BasicDataAttributeImportanceTest` | `Tests run: 5, Failures: 5` · `Expected 200 but was 404` | 同左 |
| `PartTypeInferenceServiceTest` | `Tests run: 10, Failures: 1` | **逐字相同** |

**根因分布**（PG 异常 325 条）：
- **197 条 = `relation "mat_part" does not exist`** —— V44 老表，已随 V6 迁移停用（`AP-53`），测试仍在读它
- 165 条 = `current transaction is aborted` —— 上面那条的级联
- 16 条 = 测试**故意**造的不存在表（`this_table_definitely_does_not_exist_bf_test` / `__table_that_does_not_exist_repair0803__`），属负向用例，正常
- 3 条 = `lock timeout`（共享库并发）

⇒ 本任务改动**未引入任何新的测试失败**。这批既有失败属 `mat_*` 废弃表断供故障族（`BL-0069`），不在本任务范围。

## F. 冷启动验证（主线执行）

```
Successfully validated 400 migrations
Current version of schema "public": 417
cpq-backend started in 10.736s
GET /api/cpq/components → 401
```
唯一 ERROR：`Failed to validate Schema: missing table [mat_composite_process]`
⇒ **已 A/B 归因非本次引入**：`MatCompositeProcess` 实体在 master 上就有，本分支对该文件 **0 diff**；该表随 V6 迁移已不存在。不阻止启动。

## G. AC-14 真机复核（主线执行）

5 张真实单（`QT-20260830-0210/0211/0213`、`QT-20260901-0218/0233`）共 **9225 个树页签**：

| 判据 | 结果 |
|---|---|
| 树页签行数 | 1845 × 5 = 9225 |
| 有根节点 `__nodeId` | **9225 / 9225** |
| 有层级列 `__lvl` | **9225 / 9225** |
| 有父指针 `__parentId` | **9225 / 9225** |
| 子节点 `__nodeType` 已判定 | **9225 / 9225** |

树骨架系统列 100% 完整，无一行降级。

---

# 主线亲验结论

**第一批 19 条可验 AC 全部有证据支撑**：主线亲手复验 9 条（附原始输出）· 证伪实验 6 条（5 通过、1 抓到假绿并已修）· 全量回归 A/B 归因完成 · 冷启动通过。

**明确未完成的**：
- **AC-24**（两条 E2E）—— 卡在「公式」Tab 内进入公式编辑器的入口选择器，判定为用例侧（新组件与存量树组件在同一位置同一方式失败，若是产品缺陷则存量侧不该受影响）
- **F-4** 组件管理展示位 —— 半完成，缺后端在组件列表 DTO 上暴露数据源字段（用户裁决推到第二批）
- **第二批全部**（B-1/B-2/B-3 + F-1/F-2/F-3/F-6）—— 等 `task-260819` 合并 master
