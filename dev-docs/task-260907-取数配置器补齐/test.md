# 测试方案 · task-260907 取数配置器补齐

> **闸门 A 的前置产物**，开工前写完。执行结果另出 `test-report.md`。
> 🚫 测试代理**不许读实现目录**（`cpq-backend/src/main/java/com/cpq/builder/` · `cpq-backend/src/main/java/com/cpq/semanticgraph/` · `cpq-frontend/src/pages/component/`）——
> 用例要按 AC 的**可观测断言**写，不是按实现细节写。

---

## 0. 三条会让整轮测试作废的前提

**① 方言入参名是 `dialect`，不是 `dataset`**
JAX-RS **静默忽略未知查询参数** —— 传错不报错，三方言全部返回缺省 QUOTE 的同一份结果。
🔑 判据：若三方言返回**逐格相同**，先怀疑参数名，再怀疑被测对象。（主线 2026-09-06 因此误判过一次范围。）

**② 隔离副本跑测试，别和 dev server 抢 `target/`**
worktree 里跑 `quarkus:dev` 与在同目录跑 `mvnw` **共用 `cpq-backend/target/`**，会互相踢出**假红**：
```
NoClassDefFoundError: com/cpq/configure/service/ConfigureSnapshotService$DriverComp
PreconditionViolationException: Could not load class with name: XxxTest
```
🚨 **这类红不是代码问题**，据此改实现 = 把环境问题修成代码改动。
⇒ 拷 `src/ pom.xml mvnw .mvn` 到 scratch 跑，**拷完必须 `diff -rq` 确认与源一致**（否则测的是漂移源码，更隐蔽）。

**③ 🚫 绝不 cd 回主仓 `/home/joii/project/cpq` 跑 `mvnw`** —— 会测到另一棵树，报假绿。`mvnw` 在 `cpq-backend/` 下。

---

## 1. AC 可追溯矩阵

| AC | 覆盖项 | 用例类型 | 认领 | 判据要点 |
|---|---|---|---|---|
| **AC-1** | B-1 | 单点 | 后端接口 | `field-tree` 的 `groups` 由 1 组变 2 组；4 张不进语义图的表提示消失 |
| **AC-2** | B-1 | 单点 + 边界 | 后端接口 | 预览 **47** 行（非 19、非 >47）；SQL 含 `LEFT JOIN` |
| **AC-3** | B-4 / F-2 | 单点 | 后端接口 + 前端单测 | `dataSourceLabel` 有值/为 null；徽章渲染；**N+1 自检** |
| **AC-4** | B-3 | 单点 | 后端接口 | SQL 产出 `parent_no`；过滤在子件列；有 `UNION ALL` 根分支；**孙级行出现** |
| **AC-5** | F-1~F-4 | **序列** | E2E | 配置→保存→切走再切回→刷新，内容顺序逐字不变 + 0 JS error + **分组展开态不被重置** |
| **AC-6** | B-1 | **边界** | 后端接口 | 无客户料号的物料行照常出现、客户列为空；客户料号表为空时仍 47 行 |
| **AC-7** | B-3 | **边界** | 后端接口 | 同一子件挂多父件 → 出多个节点，不去重不报错 |
| **AC-8** | B-3 | 反向 | 后端接口 | 非树数据源 SQL 与改动前**逐字相同**（A/B diff） |
| **AC-9** | 全部 | 反向 | 后端接口 + E2E | `task-260904` 四项成果不被改坏 |
| **AC-10** | 全部 | 反向 | 后端 SQL | 202 个未绑组件配置**逐行 md5 不变**；21 个存量树组件渲染不变 |
| **AC-11** | B-2 | 单点 + 边界 | 后端接口 | 下拉 11→14；各源出全部业务列；**选「物料」时不出现年降三表** |

**三类覆盖自检**：单点 ✅（AC-1/2/3/4/11）· 序列 ✅（AC-5）· 边界 ✅（AC-6/7/11 空态）· 反向 ✅（AC-8/9/10）

---

## 2. A 侧基线（已采，🚫 不重采）

`证据/baseline/`，采于 `master = a81f2c40`（三条并发线均只有文档提交、零代码改动）：

| 文件 | 内容 | 非空证据 |
|---|---|---|
| `components.txt` | 全部组件的 `tab_type`/`data_driver_path`/`bom_recursive_expand`/`fields` md5 | **222 行**，md5 `aa5598667c0f0219fc296aa29ba0a782` |
| `tree-components.txt` | 21 个树组件的视图 md5 | **21 行** |
| `field-tree.json` | 3 方言 × 5 坐标的分组/字段数/数据源清单 | **15 坐标，字段数为 0 的 = 0**，md5 `d78b9b71c91512e3c3bfa892d78439a4` |

🚫 **不重采 A 侧** —— 重采 = 拿当前值当基线 = 断言退化成恒真。
（`task-260904` 的 AC-13 / AC-25② 就是因基线失效而声明作废、明确不重采的先例。）

⚠️ diff 出差异时 **🚫 不许直接归因「本次引入」** —— 共享 dev 库有三条线并发写入，必须先对照干净 master 做 A/B。

---

## 3. 🚨 强制：证伪实验（不做等于没测）

**每条用例写完后，把被测的那行实现改坏（或注释掉），重跑，确认用例变红，然后改回。**

本任务线**真实踩过的三个坑**，逐条避开：

**① 先证明干预真的生效了，再看颜色**
曾把 `decideTree` 改坏但用例断言的是另一条路径，没变红，差点记成「用例假绿」。
⇒ 干预后先确认 md5 已变 / 走到了那行，再看结果。

**② 变红了也要看它红的是不是那件事**
曾出现「变红」实为 30s 超时 + 选择器写错（真实类名 `.svb-grp-h`，被猜成 `.svb-grp-head` 并用 `[class*="head"]` 兜底，点到了页面别处）。

**③ 判据要取状态本身，不要取它的副作用**
验「分组是否被折回去」时，首版数「可见字段条目数」—— 旧实现下**也通过**，因为折叠是 CSS 驱动
（`.svb-grp.collapsed .svb-grp-b{display:none}`），**DOM 节点还在，`count()` 数不出来**。
⇒ 改数 `.svb-grp.collapsed` 的**个数**才变红。

**④ 守卫不能在被守卫对象的下游**
主线采基线时，脚本因 `.get('data', {})` 回退成空字典而产出**空基线**，
而守卫写的是「收集不合格项，列表为空即通过」—— 全都没采到时列表自然为空，**恒真通过**。
⇒ 顺序必须是：**先断言集合非空，再断言集合内元素合格**。

---

## 4. 数据纪律

- 🚨 共享 dev 库**不许跑清库型测试**（`CLAUDE.md` §3.2 环境销毁红线），哪怕写在 `beforeAll` 里
- 夹具用完**按主键精确删净**，回报里给出「残留 = 0」证据
- **AC-11 的三张年降表实测均 0 行** ⇒ 🚫 **不写「预览返回 0 行」这类断言**（恒真，掩盖「数据链路从没验过」）。
  本条只验结构层，**已在需求文档标为缺口**
- 🚫 不动 `semantic_tab_view` / `semantic_node` 等语义图表的**存量数据**（B-1/B-2 的新增行除外）
- 🚫 不改 V413 种子

---

## 5. 环境

| | |
|---|---|
| 后端 | worktree 内 `quarkus:dev` 临时端口；**不要占用主仓 8081** |
| 前端 | 临时 vite（`VITE_PORT` + `VITE_API_TARGET` 指向上面的临时后端）；**不要抢共享的 5174** |
| 登录 | `POST /api/cpq/auth/login {"username":"admin","password":"Admin@2026"}`，**会话 Cookie 鉴权**（不是 Bearer），curl 用 `-c/-b` cookie jar |
| curl | 打本机一律加 `--noproxy '*'`（本机 shell 有 `http_proxy`，不加走代理返 502） |
| Playwright | 必须 `channel:'chrome'`（Ubuntu 26.04 装不上自带 chromium，不设 channel 会**全部倒在启动**，长得像业务回归但一个断言都没执行） |

---

## 6. 已知红（**非本任务引入，判失败时别误归因**）

| 项 | 状态 |
|---|---|
| `e2e/sql-view-builder.spec.ts` | master 上 **8 失败 / 0 通过**（共享 helper 选择器与真实 UI 对不上，9 个用例从未执行过自己的断言）；`task-260904` 修好 helper 后为 **6 失败 / 2 通过**。剩余 6 条属 `task-260819` |
| `Sec35FeeTabPreviewInspectTest.ac25_expenseTabAsSixthType` | master 同型失败 |
| `Sec36aSemanticGraphDbTest.tombstone_retiredSemanticGraphAcs_premiseStillHolds` | master 同型失败 |
| `LegacyZeroChangeAcTest` 的 AC-13 / AC-25② | `task-260904` 已声明静态基线失效，🚫 不重采 |

**本任务的判据是：不新增红。** 上述项在改动前后应**逐字相同**。

---

## 7. 前端选择器速查（本项目已实证的坑）

| 坑 | 正确做法 |
|---|---|
| antd Select **虚拟滚动** | 选项一多 `allInnerTexts()` 会漏，要滚动累加 |
| 两字按钮带空格 | 「新 建」「创 建」「保 存」⇒ 用 `/^新\s*建$/` |
| 组件管理页**不是表格** | 是**目录卡片列表**（勾选框 + ▶ + 目录名 + 统计徽章 `页签6`/`XLS1`/`小计1` + 右侧 5 个图标）；`.ant-table-tbody tr` 取不到东西 |
| 分组标题类名 | `.svb-grp-h`（不是 `.svb-grp-head`）；分组容器 `.svb-grp`，折叠态 `.svb-grp.collapsed` |
| 数据源下拉 | `[data-role="builder-source"]`；语义回显 `[data-role="builder-source-semantic"]` |
| 默认 30s 超时不够 | 本页前置等待就要 18s+，用例需 `test.setTimeout(150_000)` |

---

## 8. 🚨 主线复验必查项 · 孤儿迁移（每次收到代理回报就跑）

**2026-09-07 同型第四次事故后新增**（V416/V417 属 `task-260819`，V418/V419 属本线）。

### 为什么放在这里，而不是放在 `backtask.md`

原先我把「迁移落库就推 master」写进了 `backtask.md` 的红线段 —— **那条纪律根本没机会生效**：
`backtask.md` 是**派工时读一次**的文档，而落迁移这个动作发生在**代理跑起来之后的某一刻**。
把规则放在只读一次的地方，等于指望它在几小时后被想起来。

> 该判断由 `报价数据导入切ds新表` 会话指出：「或许更该绑在**你复验代理产出时的必查项**上」。**采纳。**

⇒ **判据要绑在会重复发生的动作上，不是绑在只发生一次的动作上。**

### 一条命令

```bash
export PGPASSWORD=joii5231
psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -tA \
  -c "SELECT version FROM flyway_schema_history WHERE success ORDER BY version::int DESC LIMIT 8;" | sort > /tmp/db_v.txt
git ls-tree master --name-only cpq-backend/src/main/resources/db/migration/ \
  | grep -o 'V[0-9]*' | tr -d 'V' | sort -n | tail -8 | sort > /tmp/master_v.txt
comm -23 /tmp/db_v.txt /tmp/master_v.txt   # 期望：空。非空 = 孤儿迁移，立即推 master
```

### 失败形态（为什么必须主动查）

- **Flyway 只在启动时校验** ⇒ **落库的人自己看不见问题**
- 共享 8081 若启动早于该迁移，是**侥幸活着** —— 任何一次重启（谁的都算）都会
  `FlywayValidateException: Detected applied migration not resolved locally: <N>`，
  而报价/核价/选配**所有会话都连它**
- 新检出 / 新 worktree 的后端**启动即挂**

🚫 **撞到这个错时不要用 `-Dquarkus.flyway.validate-on-migrate=false` 长期绕过** —— 那关掉的正是发现同型事故的唯一信号。
🚫 **更不要对共享库跑 `flyway repair`** —— 那会打掉别人的记录，属 `CLAUDE.md` §3.2。

### 夹具前缀命名空间

⚠️ 多线并发时，**夹具前缀必须各线错开**。危害不是脏数据，是**互删**：
两边都按 `LIKE 'T260907%'` 清理，谁先跑谁把对方的删了，**症状是随机挂且极像业务回归**。

| 线 | 前缀 |
|---|---|
| 本线后端 | `T260907B-` |
| 本线测试 | `T260907Q-` |
| `报价导入切ds新表` 测试 | `T260907T-`（对方已改） |
