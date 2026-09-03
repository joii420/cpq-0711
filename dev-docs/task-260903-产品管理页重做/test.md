# 测试方案与 AC 追溯矩阵 · 产品管理页重做

> 任务：`task-260903-产品管理页重做`　|　日期：2026-09-03
> 阅读者：`cpq-tester` 子代理。
> 🚫 **禁止读 `cpq-frontend/src/pages/product/` 下的实现文件** —— 用例从 AC 派生，不从实现派生。
> 从实现派生的测试只能证明「代码按实现者的理解工作」，证明不了「功能符合需求」。

---

## 1. 样例数据准备（**所有 AC 的前置，必须先做**）

### 1.1 为什么必须造这份数据

`task-260902` 的源 Excel 是**建表规范文件不是数据文件**：`物料` sheet 实读 `max_row=1`（只有表头，零数据行）。
直接导入它 ⇒ `ds_quote_material` 0 行 ⇒ 销售产品页签全空 ⇒ 撞 `CLAUDE.md §4.5` 步骤 4d「空列表 / 0 行一律不算通过」，**亲验无法完成**。

用户裁决：**从旧表导出真实数据做样例**。

### 1.2 数据来源与行数（AC 断言的数字基础）

| 目标 sheet | 目标表 | 来源 SQL | 期望行数 |
|---|---|---|---|
| 物料 | `ds_quote_material` | `SELECT material_no, material_name, specification, dimension, old_material_no, unit_weight, production_no FROM material_master WHERE pending_quotation_id IS NULL` | **42** |
| 客户料号 | `ds_quote_customer_part` | `SELECT customer_no, customer_material_name, customer_product_no, customer_drawing_no, material_no FROM material_customer_map WHERE system_type='QUOTE' AND pending_quotation_id IS NULL` | **17** |
| 物料BOM | `ds_quote_material_bom` | `material_bom_item` 中 `material_no` 属于上述 42 个料号的行 | **58** |
| 物料与元素BOM | `ds_quote_element_bom` | `element_bom_item` 中同上 | **48** |
| 其余 11 个带版本 sheet | 各自表 | 有则导、无则**留空表头**（空 tab 正是 AC-12 要验的） | 视实际 |

> ⚠️ **必须过滤 `pending_quotation_id IS NULL`**。不过滤会带进 1845 / 1847 行未提交报价单的占号影子行，页面不可用。

### 1.3 🚩 合成值规则（必须逐条遵守并留痕）

真实数据有两处不满足新表约束，**只允许按下表补，其余列一律用真实值，禁止臆造**：

| 问题 | 实测 | 处理 |
|---|---|---|
| `customer_product_no` 空 | 17 行中 **16 行为空**，而新表该列是红底必填、且是主键组成 | 补 `CP-<销售料号>`（如 `CP-S-80011`）。**唯一且可辨识**，一眼能看出是合成的 |
| `customer_no` 需校验存在 | 导入器有 `客户编号未在客户档案中登记` 校验（D-19） | 只用实测存在的 5 个：`CUST-0001` `CUST-0002` `CUST-0004` `C1` `Q13CUST0617`。**导入前先跑 `SELECT customer_no FROM customer` 核对** |

**留痕要求**：与 xlsx 同目录产出 `样例-数据说明.md`，逐列标注哪些是真实值、哪些是合成值、合成规则是什么。
🚫 **不写说明文件 = 样例数据不可用** —— 后续会话会把合成值当真实业务数据去排查问题。

### 1.4 Excel 结构规范（**踩错就整份被拒收**）

实测源文件结构，两类 sheet 的行结构**不同**：

| sheet 类型 | 第 1 行 | 第 2 行 | 第 3 行起 |
|---|---|---|---|
| **免版本**（物料 / 客户料号 / 电镀方案） | 表头 | **直接是数据** | 数据 |
| **带版本**（其余 13 张） | 表头 | **轴 / 对比项标记行** | 数据 |

- 带版本 sheet 的第 2 行：轴列写 `轴`，比对项列写 `对比项`，其余留空
- 🚨 **轴列一律必填，不看底色**（`task-260902` 闸门 A0 裁决）。轴为空 ⇒ 报 `轴列不可为空`
- 客户料号 sheet **必须含「客户编号」列**（D-18），缺列会被整份拒收

### 1.5 产出与归档

| 产物 | 路径 |
|---|---|
| 样例数据 | `dev-docs/task-260903-产品管理页重做/样例-报价数据.xlsx` |
| 数据说明 | `dev-docs/task-260903-产品管理页重做/样例-数据说明.md` |
| 生成脚本 | 放 scratchpad，**不进仓库**（一次性工具） |

### 1.6 导入验证（做完样例先自证）

```sql
SELECT count(*) FROM ds_quote_material;        -- 期望 42
SELECT count(*) FROM ds_quote_customer_part;   -- 期望 17
SELECT count(*) FROM ds_quote_material_bom;    -- 期望 58
SELECT count(*) FROM ds_quote_element_bom;     -- 期望 48
SELECT count(*) FROM ds_quote_material_bom WHERE material_no='S-3120014539';  -- 期望 9
```

🚨 **导入必须在非共享库或独立事务中验证**。`CLAUDE.md §3.2`：共享库上不许跑会清库的测试。
⚠️ `mvnw test` 默认 profile 直连 `cpq_db_0724`（**就是共享开发库**），跑清库型测试会打掉他人开发数据。

---

## 2. 测试分层

| 层 | 工具 | 覆盖 | 备注 |
|---|---|---|---|
| L1 组件测试 | Vitest + RTL | 只读渲染分支、`page` 0-based 换算、`—` 兜底 | 不依赖后端 |
| L2 E2E | Playwright | AC-1~AC-16 主路径 | **本任务的主力**（UI 改动强制 E2E） |
| L3 静态核对 | 人工 + grep | AC-17 文档回写 | 查文件内容即可 |

### E2E 文件

`cpq-frontend/e2e/product-hub-readonly.spec.ts`（新建）

**选择器纪律**（`cpq-playwright-selector-pitfalls` 教训，四个坑都表现为 timeout、极易误判成产品 bug）：
- 两字按钮 antd 会插空格 ⇒ 用 `getByRole('button', { name: /保\s*存/ })` 而非精确文本
- 下拉是虚拟滚动 ⇒ 选项要先滚动到视野
- antd 类名不稳 ⇒ 优先 `getByRole` / `data-testid`，慎用 `.ant-*`
- 中文断言注意 UTF-8 编码

---

## 3. AC 可追溯矩阵

| AC | 层 | 用例 ID | 关键断言（摘要，原文以 `需求文档.md` 为准） |
|---|---|---|---|
| AC-1 | L2 | `E2E-01` | 页签恰好 2 个、文案与顺序、默认选中、无「产品主数据」文案 |
| AC-2 | L2 | `E2E-02` | 6 列顺序、总数 17、`客户名称` 空值渲染 `—` |
| AC-3 | L2 | `E2E-03` | 点行后 DOM 无 `.ant-drawer` |
| AC-4 | L2 | `E2E-04` | 7 列顺序、总数 42 |
| AC-5 | L2 | `E2E-05` | Drawer 出现、标题含 `S-3120014539` |
| AC-6 | L2 | `E2E-06` | tab 恰好 13、文案与顺序、不含免版本 3 张 |
| AC-7 | L2 | `E2E-07` | 物料BOM 9 行、含指定列、不渲染轴列 |
| AC-8 | L2 | `E2E-08` | 无保存/新增/删除按钮、无 input/select/textarea、双击不进编辑态 |
| AC-9 | L2 | `E2E-09` | `PRICING_MANAGER` 与 `SYSTEM_ADMIN` 结果一致；**反向**：核价侧仍可编辑 |
| AC-10 | L2 | `E2E-10` | 版本切换后内容变化且仍只读 |
| AC-11 | L2 | `E2E-11` | 八步序列，中间态与最终态都断言，console 无 error |
| AC-12 | L2 | `E2E-12` | 空 tab 显示 `暂无数据`，**不显示 `加载中…`** |
| AC-13 | L1+L2 | `UT-01` `E2E-13` | 0 行时 `Empty` + 总数 0，不白屏不转圈 |
| AC-14 | L2 | `E2E-14` | 搜 `S-3120014539` 得 1 行；搜 `CUST-0004` 得 12 行；清空恢复 |
| AC-15 | L2 | `E2E-15` | ≥60 字符品名省略号截断、无横向滚动条撑破 |
| AC-16 | L2 | `E2E-16` | `SALES_REP` 可查看、无 403、无红色遮罩 |
| AC-17 | L3 | `ST-01` | grep `docs/列表操作规范.md` 与 `RECORD.md` 命中新增条目 |

✅ **17 条 AC 全部有用例覆盖，矩阵无缺行。**

---

## 4. 四条证伪实验（🚫 不做不算测完）

「测试全绿」本身可能是假的。每条实验都要**先证明干预生效**，再看结论。

| # | 实验 | 做法 | 判据 |
|---|---|---|---|
| **FS-1** | **只读是真的吗** | 临时把 `editable={false}` 改成 `true`，重跑 `E2E-08` | **必须变红**。不变红 ⇒ 用例根本没在查编辑控件，是空验证 |
| **FS-2** | **数字是真的吗** | 临时把样例中 `S-3120014539` 的物料BOM 删掉 1 行重导，重跑 `E2E-07` | **必须从 9 变 8 并失败**。不变 ⇒ 用例读的是缓存或写死值 |
| **FS-3** | **pending 过滤是真的吗** | 临时往 `ds_quote_material` 插 1 行，重跑 `E2E-04` | 总数 **必须 43**。仍是 42 ⇒ 页面读的不是这张表 |
| **FS-4** | **空态是真的吗** | 临时清空某 tab 数据重跑 `E2E-12` | 必须显示 `暂无数据` 而非 `加载中…`。显示「加载中」⇒ 撞 AP-31「加载中永久占位族」 |

> 🔬 **还原实验纪律**（记忆条目 `cpq-agent-tests-stale-server-false-positive`）：
> 自己写的验证脚本**首次 PASS 也可能是空验证**。把修复改回去重跑，**不变红 = 白测**。

---

## 5. 回归清单（本任务摘除了两个页签，必须确认没伤到别处）

| # | 回归项 | 期望 |
|---|---|---|
| RG-1 | 核价侧「料号核价」页签 | 逐屏与改动前一致，**仍可编辑**（本任务不得把核价侧改成只读） |
| RG-2 | `/master-data-hub` 全部页签 | 正常，未被本任务波及 |
| RG-3 | 报价单管理「从基础数据导入」 | 行为零变化（写旧 V6 表，喂 107 个组件视图） |
| RG-4 | 报价单渲染主链路 | 打开一张既有报价单，组件与金额与改动前一致 |
| RG-5 | 「产品分类管理」抽屉 | 仍能打开、增删改分类正常 |
| RG-6 | 既有 E2E | `quotation-flow.spec.ts` / `composite-product-flow.spec.ts` |

> ⚠️ **RG-6 的已知噪声**：`quotation-flow` 在干净 master 上**恒 3 条失败**（夹具单缺产品分类 → Step1 下一步禁用，记忆条目 `task0712-update071501-category-axis`）。
> 判断是否回归**必须 A/B 同型对比**：同一时刻在主仓 master 跑一遍作对照，**不许拿绝对失败数下结论**。

---

## 6. 缺陷上报路径（**不要自己修**）

| 缺陷类型 | 上报给谁 |
|---|---|
| `part-costing/` 公共件的只读渲染缺陷（`role=NAME` / `DECIMAL` 异常） | **报主线 → 转 `task-260902`**。🚫 不得自行修改 `part-costing/` 下任何文件 |
| `ds_quote_*` 表结构 / 索引问题 | 报主线 → 转 `task-260902` 走迁移。🚫 不得自建索引或改列 |
| 端点契约不符 | 报主线。🚫 **不得自行改 `api.md`**（对方有三个后端代理正照它实现） |
| 本任务页面自身缺陷 | 正常回流给 `cpq-frontend` |

---

## 7. 测试环境

- 前端 `5174` / 后端 `8081`，**全会话共享，先探端口，已在跑就复用**
- 探活（两个坑）：
  ```bash
  curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/                  # 期望 200
  curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components # 期望 401
  ```
  ⚠️ 本机 shell 常设 `http_proxy=127.0.0.1:7890`，探本机服务**必须加 `--noproxy '*'`**，否则走代理返 502
  ⚠️ `/q/health` 返 404 是正常的（未装 smallrye-health），**它不是健康探针**
- 账号 `admin` / `Admin@2026`
  ⚠️ E2E 反复跑可能把 admin 置成 `INACTIVE`，需 SQL 改回 `ACTIVE`（记忆条目 `quote-element-delete-wrong-row-root`）
