# 测试用例文档 · task-0708 repair-1 材质 name/symbol 语义修补

> 测试负责人:开发需求测试员(QA)｜优先级:P2｜编写日期:2026-07-09
> 依据:`repair-1/backtask.md`(RB1-RB4)+ `repair-1/api.md` + `repair-1/fronttask.md`(RF1-RF2)+ 用户 7 验收点。
> 背景:修正 task-0708 决策#2——`symbol`=**化学式**(标签从「材质名称」改回「化学式」)、`name`=**名称**(从隐藏置NULL 改为展示可编辑、默认=symbol)、`spec_label`(配比)**仍隐藏**。DB 列结构不变,只改取值 + UI 展示 + 搜索。
> 判定原则:两列并展 + 存量回填 name 无 NULL + 导入/新建 name 默认=symbol + 名称可编辑可留空回落 + 按名称可搜 + 配比仍隐藏 + 下游零回归。

---

## 1. 测试环境与前置

| 项 | 值 |
|----|----|
| 前端 | `http://localhost:5175`(隔离,代理指 8082;主数据维护→材质) |
| 后端 | `http://localhost:8082`(隔离实例;探活 `GET /api/cpq/material-recipes` 期望 200/401) |
| DB | **`cpq_db_repair1`**(隔离库,主干零污染;严禁对共享 `cpq_db` 跑 RB3 迁移) |
| 鉴权 | 写=SYSTEM_ADMIN(`admin/Admin@2026`) |
| 测试文件 | `dev-docs/task-0708-材质库规范澄清/材质库.xlsx`(253/1 基线) |
| SQL 前缀 | `PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_repair1 -A -F'|' -c "..."` |

**执行前置**:后端合并 RB1/RB2/RB4 + RB3 迁移就位(`flyway_schema_history` 含 RB3 且 success=t);前端合并 RF1/RF2 + Vite 200。

---

## 2. ⚠️ 测试数据事实基线(实测 cpq_db_repair1;决定断言预期值)

| # | 事实 | 对断言影响 |
|---|------|-----------|
| **D1** | material_recipe **261 行**;**name IS NULL = 255**(task-0708 导入置 NULL 的存量);253 个 5 位编号材质 | RB3 回填后 name IS NULL 应 = **0** |
| **D2** | `AgC3(00002)`:symbol=`AgC3`、**name=NULL**、spec_label=NULL | 回填/重导后 name=`AgC3`(=symbol) |
| **D3** 🔒 | **6 条已有 name 的 demo**(name 是有意义中文名 ≠ symbol):`AgCu85/AgCu90→银铜合金`、`AgNi70/AgNi90/AgNi95→银镍合金`、`CuZn70→铜锌合金` | RB3 **只回填 NULL、不动已有 name** → 这 6 条 name 必须**保持中文名**(如 AgCu85 仍=银铜合金,**不得**被冲成 symbol「AgCu」) |
| **D4** | 导入材质 symbol=化学式(Ag/AgC3/AgSnO2…) | RB1 重导后这些材质 name=symbol |

---

## 3. 已识别测试风险

- **R1(RB3 误伤已有 name)**:D3 的 6 条 demo 有人工中文名。若 RB3 写成 `SET name=symbol`(无 `WHERE name IS NULL`)→ 把 银铜合金 冲成 AgCu。§TC-M2 专项校验这 6 条不变。
- **R2(留空回落 symbol 的边界)**:RB2「name 为空/空白→symbol」。须验空串与纯空白都回落(非只 null)。§TC-C。
- **R3(下游 COALESCE 语义)**:ConfigureSearch `COALESCE(mr.name,…)` 原 name=NULL 走 fallback、现 name 有值直接取。语义仍是材质名,须验非 500 且结果合理。§TC-R。
- **R4(隔离纪律)**:RB3 迁移只能落 `cpq_db_repair1`,误落共享 `cpq_db` 冲垮主干 8081 Flyway。

---

## 4. 测试用例

### TC-M 存量回填迁移（RB3 / 验收点③）

| 用例 | 断言(SQL) | 预期 | 判定 |
|------|----------|------|------|
| **TC-M1** 迁移成功 | `SELECT version,success FROM flyway_schema_history WHERE description ILIKE '%name%default%symbol%' OR version='<RB3>';` | success=t;后端启动无 checksum mismatch | |
| **TC-M2** name 无 NULL + 回填=symbol | `SELECT count(*) FILTER(WHERE name IS NULL) n_null FROM material_recipe;` + `SELECT code,symbol,name FROM material_recipe WHERE code='00002';` | `n_null=0`;AgC3 name=`AgC3`(=symbol) | |
| **TC-M2b** 🔒 已有 name 不被冲 | `SELECT code,symbol,name FROM material_recipe WHERE code IN ('AgCu85','AgNi70','CuZn70') ORDER BY code;` | `AgCu85`→name=`银铜合金`(≠symbol AgCu)、`AgNi70`→`银镍合金`、`CuZn70`→`铜锌合金`(**保持中文名,RB3 只回填 NULL**) | |

### TC-I 导入 name=symbol（RB1 / 验收点②）

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-I1** 253/1 基线不变 | 重导 `材质库.xlsx` 报告 | materialsUpserted=**253**、skipped=**1**(WZHF26-25) | |
| **TC-I2** 导入 name=symbol | `SELECT code,symbol,name FROM material_recipe WHERE code IN ('00001','00002','00075');` | 各行 `name=symbol`(00001 Ag/Ag、00002 AgC3/AgC3、00075 AgSnO2/AgSnO2) | |
| **TC-I3** 导入后无 NULL name | `SELECT count(*) FILTER(WHERE name IS NULL) FROM material_recipe WHERE code ~ '^[0-9]{5}$';` | 0(导入材质 name 全有值) | |

### TC-C 新建/编辑 name 默认=symbol（RB2 / 验收点④后端侧）

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-C1** 新建不传 name → =symbol | `POST /material-recipes {code:RTEST1,symbol:XxTest,elements:[...=100]}`(不带 name) | 200;落库 `name=XxTest`(=symbol) | |
| **TC-C2** 新建传 name → 用传值 | `POST {code:RTEST2,symbol:YyTest,name:自定义名,...}` | 200;落库 `name=自定义名` | |
| **TC-C3** 编辑清空 name → 回落 symbol | `PUT /{RTEST2 id} {symbol:YyTest,name:"",...}` | 200;落库 `name=YyTest`(=symbol,空串回落) | |
| **TC-C4** 编辑纯空白 name → 回落 symbol | `PUT {name:"   ",...}` | name=symbol(空白也回落,R2) | |
| (清理)删除 RTEST1/RTEST2 | | | |

### TC-S 搜索按名称（RB4 / 验收点⑤）

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-S1** 按名称(=化学式) | `GET /material-recipes?keyword=AgC3` | 命中 00002(name 命中,原 task-0708 已可按 symbol 中,此处确认 name 维度也在) | |
| **TC-S2** 按人工中文名 | `GET /material-recipes?keyword=银铜合金` | 命中 AgCu85/AgCu90(仅 name=银铜合金 命中,symbol=AgCu 不含「银铜合金」→ 证明 name 维度生效) | |
| **TC-S3** 编号/化学式/元素仍可搜 | `keyword=00002` / `keyword=Ag` / `keyword=银` | 分别命中(原有维度不回归) | |

### TC-U 前端（RF1/RF2 / 验收点①④⑥;Playwright/人工）

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-U1** 列表两列并展 | 材质页列 | 列顺序:材质编号 / **化学式**(symbol) / **名称**(name) / 类型 / 状态 / 创建时间 / 修改时间 / 排序;导入数据两列相同值(AgC3/AgC3) | |
| **TC-U2** 搜索占位 | 搜索框 placeholder | 「搜索 材质编号 / 化学式 / 名称 / 元素」 | |
| **TC-U3** 编辑抽屉化学式标签 | 编辑 00002 抽屉 | symbol 字段 label=**「化学式」**(非「材质名称」);回显 AgC3 | |
| **TC-U4** 名称可编辑 | 编辑抽屉 | 有「名称」输入框(name),可编辑;回显当前 name(=AgC3) | |
| **TC-U5** 留空保存=化学式 | 编辑抽屉清空名称 → 保存 → 重开 | 名称回显=化学式(symbol);列表名称列=symbol | |
| **TC-U6** 配比仍隐藏 | 编辑抽屉 | 无「配比/spec_label」字段(R:仍隐藏) | |
| **TC-U7** 自检 | tsc + 两 tsx Vite 200 | 0 错误;均 200 | |

### TC-R 下游回归（验收点⑦）

| 用例 | 断言 | 预期 | 判定 |
|------|------|------|------|
| **TC-R1** ConfigureSearch 非 500 | `GET /quotations/configure/search-parts?q=Ag&size=3` | 200,`COALESCE(mr.name,…)` 现拿非空 name,结果合理(recipeName 有值) | |
| **TC-R2** 选配/材质渲染无回归 | 材质页元素明细 / 选配材质 | 正常(name 有值不破坏渲染;symbol/元素读取不变) | |

---

## 5. 达标判定标准

**达到交付水平(PASS)** 需同时满足:
1. **TC-M**:RB3 迁移成功、**name IS NULL=0**、导入材质 name=symbol、**已有 6 条中文名 name 不被冲**(一票否决:误伤已有 name = FAIL)。
2. **TC-I**:重导 253/1 不变、导入 name=symbol。
3. **TC-C**:新建/编辑 name 空→symbol、传值→用传值、清空/空白→回落 symbol。
4. **TC-S**:按名称(含人工中文名 银铜合金)可搜;原维度不回归。
5. **TC-U**:列表化学式+名称两列;编辑抽屉化学式标签 + 名称可编辑 + 留空保存=化学式;**配比仍隐藏**。
6. **TC-R**:ConfigureSearch 非 500、选配/渲染零回归。

**不达标(FAIL)** 任一:
- RB3 把已有 name(银铜合金等)冲成 symbol(误伤存量);
- name IS NULL 未清零 / 导入 name≠symbol / 253-1 基线被破;
- 编辑抽屉化学式标签未改 / 名称不可编辑 / 留空不回落 symbol / 配比被恢复展示;
- 按名称搜不到 / 下游 ConfigureSearch 500 或渲染回归;
- RB3 误落共享 cpq_db 污染主干(R4)。

---

## 附录 A:实测锚点速查
- material_recipe 261;name NULL=255 → 回填后 0。
- AgC3(00002)symbol=AgC3、name NULL→AgC3。
- 6 条已有 name(RB3 不动):AgCu85/AgCu90=银铜合金、AgNi70/90/95=银镍合金、CuZn70=铜锌合金。
- 导入基线 253/1(WZHF26-25 跳)。

## 附录 B:执行记录(测试时填)
| 用例组 | 判定 | 实测值/证据 | 备注 |
|--------|------|------------|------|
| TC-M1~M2b | | | ★已有 name 不被冲 |
| TC-I1~I3 | | | 253/1 + name=symbol |
| TC-C1~C4 | | | 留空回落 |
| TC-S1~S3 | | | 按名称搜 |
| TC-U1~U7 | | | Playwright/人工 |
| TC-R1~R2 | | | 下游回归 |
