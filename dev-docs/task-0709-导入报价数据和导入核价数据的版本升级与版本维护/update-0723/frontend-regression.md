# 前端回归验证清单 · update-0723 报价导入模板 0723 适配

> 关联：`fronttask.md`（结论：前端零代码改动）· `api.md`（契约变化：`status` 移除 `PARTIAL`）· `需求说明.md`（U0~U14）
> 本文档是 `fronttask.md` §2「5 项回归清单」的细化版，供**后端合入本次分支后**在共享 dev server（前端 5174 / 后端 8081）上真实走一遍并留痕。
> 执行人：前端工程师 · 验收人：技术总监

---

## 0. 核实结论：前端确实零代码改动（PARTIAL 依赖排查）

用 codegraph 定位「报价单管理 → 从基础数据导入」链路，命中文件与逐处核实如下：

| 文件 | PARTIAL 命中位置 | 性质 | 结论 |
|------|------------------|------|------|
| `cpq-frontend/src/services/basicDataImportV6Service.ts:15` | `status: 'SUCCESS' \| 'PARTIAL' \| 'FAILED'`（TS 类型声明） | 纯类型层面联合类型，无 `switch` 穷尽检查、无按此类型做逻辑分派 | 后端不再产出 `PARTIAL` 时，该字面量只是类型里一个永不出现的合法值，不报编译错、不影响运行时 |
| `cpq-frontend/src/pages/quotation/QuoteBasicDataImportV6Drawer.tsx`（报价「从基础数据导入」抽屉，本次改动的唯一入口） | L159-160 `else if (r.status==='PARTIAL') message.warning(...)`；L229 `statusTag` 颜色三元 `SUCCESS→green : PARTIAL→orange : red`；L355 `Alert type` 三元 `SUCCESS→success : PARTIAL→warning : error` | 均为 **if-else-if 链尾部 / 三元表达式兜底**结构，非独占分支、非必经路径 | `PARTIAL` 不再出现 → 直接落入 else 分支（红色/error/`message.error`），与 `FAILED` 视觉表现一致，不会白屏、不会漏提示、不会残留橙色状态 |
| 同文件 L250 `canEnterStep2 = result && result.status !== 'FAILED'` | 用排除法（`!== 'FAILED'`），未枚举 `PARTIAL` | 天然兼容，`PARTIAL` 消失不影响这行逻辑 |
| `cpq-frontend/src/pages/master-data/PricingBasicDataImportDrawer.tsx`（核价「从基础数据导入」，**非本次改动范围**，但共用同一 `basicDataImportV6Service` / `ImportResultDTO` 类型） | 同款三元兜底模式（L61-62 / L73 / L130） | 同上 | 同样零风险；且后端本次未碰 `/basic-data-import/v6/pricing` 端点，双重保险 |
| `cpq-frontend/src/pages/importconfig/ImportHistoryList.tsx`（`/import-history` 页，「导入历史」按钮跳转） | `statusMap` 含 `PARTIAL: {label:'部分成功', color:'orange'}` | 该页走的是**完全不同的旧版** `importService`（`/imports/...`、`/quotations/import-excel` 端点），不是本次改动的 `BasicDataImportV6Resource`（`/basic-data-import/v6/...`） | **与本次需求无关**，后端未触碰这些端点，其 `PARTIAL` 选项继续存在也不受影响，不纳入本次回归范围 |

**结论：未发现任何「专属功能依赖」PARTIAL 的硬编码分支**（如 switch-case 必经分支、只有 PARTIAL 才触发的 UI 元素/路由跳转、按 PARTIAL 做数据过滤等）。所有命中都是「三元/else-if 兜底」写法，移除 PARTIAL 后代码优雅退化到 FAILED 的展示路径，与 fronttask.md §0 预判一致。**确认无需修改任何 `.tsx`/`.ts` 文件。**

---

## 1. 前提条件

- 后端已将 update-0723 分支合入共享 dev server 8081（两阶段全量校验 + 整单回滚已生效）
- 前端复用共享 5174（本次未改动，无需重启/重装 `node_modules`）
- 测试文件：
  - 正常文件：`dev-docs/task-0709-导入报价数据和导入核价数据的版本升级与版本维护/update-0723/报价系统模板0723.xlsx`（或按最新字段填好的合法副本）
  - 错误文件：基于上表人工构造 1~2 处「类型冲突」/「材质缺库」的错误副本，例如：
    - 让「组成件其他费用」的某组成件料号同时出现在「物料与元素BOM」的材质料号列（触发 U1 类型冲突报错）
    - 「物料与元素BOM」材质名称列填一个 `material_recipe` 里不存在的名称（触发 U2「未找到材质」报错）
- 账号角色：`SALES_REP` 或 `SALES_MANAGER`（`QUOTATION_MGMT_ROLES`，报价单管理路由守卫要求）

---

## 2. 回归项目（对应 fronttask.md §2 五项，逐项细化）

### 回归 1：正常导入 → 全链路走通

- **页面路径**：`http://localhost:5174/quotations`（报价单管理列表）
- **操作步骤**：
  1. 打开 `/quotations`，顶部工具栏点击【从基础数据导入】按钮
  2. 抽屉打开，标题「报价基础数据导入 (V6 · 19 Sheet)」，Steps 显示第 1 步「上传 + 入库」
  3. Step1：下拉选择客户（建议用 E2E 常用客户，如西门子）
  4. 拖拽/点击上传 `报价系统模板0723.xlsx`
  5. 点击【开始导入】
  6. 观察 Alert「后台导入处理中…」+ `Progress` 进度条递增 + 「正在处理：xxx（done/total Sheet）」文案变化
  7. 等待轮询结束（后端 U8 验收 <2s；前端轮询间隔 1.5s，实际观感 1~2 次 tick 内完成）
  8. 结果 Alert 变绿色（`type=success`），文案「导入完成 `SUCCESS`（绿色 Tag）成功 N 行」
  9. 下方 Sheet 结果表格展示各 sheet 行数；展开无失败行的 sheet 应显示「无错误」Empty 占位
  10. 点击【下一步：选模板 + 建报价单】（应可点击，非 disabled）
  11. Step2：选客户报价模板 + 核价模板 + 填报价单名称，点击【创建报价单】
  12. 应跳转 `/quotations/{id}/edit?autoPopulate=1&importRecordId=...`，编辑页正常打开
- **预期结果**：
  - 全程无红色 overlay，F12 Console 无报错
  - 终态 `status = SUCCESS`；**不应看到橙色 `PARTIAL` Tag**（后端已不再产出该状态）
  - `message.success('导入成功 N 行')` 正确弹出

### 回归 2：错误文件导入 → `FAILED` + 全量错误清单，不创建报价单

- **页面路径**：同上
- **操作步骤**：
  1. 同上打开抽屉、选客户，上传构造好的错误文件
  2. 点击【开始导入】，等待处理完成
- **预期结果**：
  - 结果 Alert 变红色（`type=error`），`statusTag` 显示红色 `FAILED`
  - Sheet 结果表格中 `failedRows > 0` 的行可展开，展开显示「行号 / 列 / 错误」子表
  - **重点核实 U6/U7（全量校验，非遇错即停）**：若文件里构造了多处错误（跨 sheet），应能同时看到**全部**错误行，而不是只报第一条就中断
  - 【下一步：选模板 + 建报价单】按钮保持 disabled（`canEnterStep2 = status !== 'FAILED'` → false）
  - 不创建报价单，不发生页面跳转
  - `message.error('导入失败 N 行')` 正确弹出
  - **反向确认（回滚验证）**：错误文件里"能校验通过的部分行"不应残留写入 DB（可让后端/测试 agent 用 SQL 抽查涉及的料号未落库；纯前端层面确认不出现"部分成功"提示即可）

### 回归 3：进度条正常推进，无卡死、无残留

- 沿用回归 1 第 6 步，重点验证：
  - `Progress` 组件 `percent` 值随 `done/total` 递增，不恒为 0 也不瞬间跳 100 不动
  - `current` 文案随 sheet 切换更新（不同 sheet 名称依次出现，如「自制加工费」→「组成件其他费用」…）
  - 中途不出现「加载中…」文案卡死不消失的情况
  - 关闭抽屉再重新打开（不刷新整页）：状态应被完全重置（`useEffect [open]` 依赖，`QuoteBasicDataImportV6Drawer.tsx:79-100`），不残留上一次的进度条/结果表格

### 回归 4：创建报价单后编辑页各 Tab 正常渲染（AP-53/AP-38/AP-31 视图链路）

- **页面路径**：`/quotations/{id}/edit?autoPopulate=1&importRecordId=...`（回归 1 第 12 步跳转后）
- **操作步骤**：逐个切换编辑页所有 Tab（物料BOM / 自制加工费 / 组成件其他费用 / 成品其他费用 / 组装加工费 / 来料固定加工费 / 来料其他费用 / 来料回收折扣 / 物料与元素BOM 等）
- **预期结果**：
  - 各 Tab 子件料号 / 名称 / 单位 / 工序 / 数量列均正常显示数值，**不出现「—（共 N 项）」、不出现空白列、不出现「加载中…」常驻**（AP-31/AP-38/AP-53 已知反模式）
  - **U0#4 专项核实**：「组成件其他费用」Tab 的「要素项次」列取值正确（新模板从"第 2 个『项次』"读，不应为空或错位）
  - **U5 专项核实**：「物料BOM」Tab 里零件类型行的「工序编号」列应有值（由「自制加工费」反填，不应为空——这是本次修复的断供点）
  - **U1 类型推断专项核实**：抽查几行，物料BOM 的投入料号分类（材质/零件/外购件）与原始 Excel 语义一致

### 回归 5：强刷验证 + Console/Network 复核

- 若任一步骤出现红色 overlay，先 `Ctrl+Shift+R` 强刷；overlay 消失且行为符合预期 = HMR 缓存问题非真实 bug；若强刷后仍异常 = 真实 bug，需记录复现步骤反馈
- F12 Console 全流程无红色报错（尤其关注 `TypeError: Cannot read property of undefined`、axios 拦截器异常）
- F12 Network 确认：
  - `POST /api/cpq/basic-data-import/v6/quote` 返回 200，`data.status = PROCESSING`
  - `GET /api/cpq/basic-data-import/v6/{recordId}` 多次轮询，**终态 `status` 只应为 `SUCCESS` 或 `FAILED`**——若观察到 `PARTIAL`，说明后端未按本次需求完成改造（U7 整单回滚未生效），需打回后端复核，不是前端问题

---

## 3. 补充（非必测）：核价「从基础数据导入」抽查

- 本次后端仅改造报价侧 13 个 handler（`/basic-data-import/v6/quote`），**未触碰** `/basic-data-import/v6/pricing`
- `PricingBasicDataImportDrawer.tsx`（核价导入入口，位于主数据相关页面）与报价共用同一 `basicDataImportV6Service`，理论零回归风险
- **非必测**；仅当怀疑后端改动有跨端点串扰时，抽查一次核价导入是否仍正常（SUCCESS/FAILED 二态，不应受影响）

---

## 4. 自检声明（执行人回填）

```
curl -s -o /dev/null -w '%{http_code}' --noproxy '*' http://localhost:5174/               → 期望 200，实测：____
curl -s -o /dev/null -w '%{http_code}' --noproxy '*' http://localhost:8081/api/cpq/components → 期望 401，实测：____
```

| # | 回归项 | 结果 PASS/FAIL | 备注 |
|---|--------|----------------|------|
| 1 | 正常导入全链路 | | |
| 2 | 错误文件 FAILED + 全量错误清单 | | |
| 3 | 进度条推进 | | |
| 4 | 编辑页各 Tab 渲染 | | |
| 5 | 强刷 + Console/Network | | |

截图（沿用 fronttask.md §3 要求，至少 3 张）：
- [ ] SUCCESS 结果页 1 张
- [ ] FAILED 错误清单（展开态）1 张
- [ ] 创建后编辑页任意 2~3 个 Tab 渲染正常 1 张（覆盖回归 4 的 U0#4/U5 专项点最佳）

---

## 5. 已知限制 / 不在本清单覆盖范围

- 千行级客户大文件性能验收（U8「大文件不卡 <2s 指标」）由后端/测试 agent 专项覆盖，非前端回归项
- 发号序列空洞（U6 副作用备案：`quote_material_no_seq` 回滚后不回退）不影响前端 UI 展示，无需前端验证
- `phase` 可选字段（api.md §5，区分 VALIDATE/WRITE 阶段）本次**默认不加**，若后续双方确认要加，前端再补充一行展示，不在本清单范围
- 本清单不覆盖 `/import-history`（`ImportHistoryList.tsx`）页面——该页面走独立的旧版 `importService`，与本次改动的 V6 端点无关
