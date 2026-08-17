# 测试报告 · task-0708 repair-1 材质 name/symbol 语义修补

> 测试员:开发需求测试员(QA)｜测试日期:2026-07-09｜依据:`repair-1/test.md`(TC-M/I/C/S/U/R)+ 用户 7 验收点
> 被测:`feat/material-name-symbol-repair`(RB1-RB4 + RF1-RF2 + V321 迁移)
> 环境:**隔离 B 方案** 前端 5175 + 后端 8082 + 库 `cpq_db_repair1`(主干零污染);live 端到端 + DB 断言 + Playwright 前端实跑。

---

## 一、总体结论

## ✅ 全项通过 —— 达到交付水平

7 个验收点全部满足:材质列表**化学式+名称两列并展**、重导后 **name=symbol** 且 **253/1 基线不变**、存量回填 **name IS NULL=0** 且**已有中文名不被冲**、编辑抽屉 **symbol 标签=化学式 + 名称可编辑 + 留空保存回落化学式**、**按名称(含人工中文名)可搜**、**配比仍隐藏**、下游 **ConfigureSearch 无回归**。无遗留缺陷。

| 验收点 | 结果 |
|------|------|
| ① 化学式+名称两列并展 | ✅ Playwright + 截图 |
| ② 重导 name=symbol、253/1 不变 | ✅ |
| ③ 存量回填 name IS NULL=0 | ✅ |
| ④ 编辑抽屉化学式标签 + 名称可编辑 + 留空=化学式 | ✅ Playwright + 截图 |
| ⑤ 按名称能搜到 | ✅(含人工中文名) |
| ⑥ 配比仍隐藏 | ✅ |
| ⑦ 下游选配/ConfigureSearch 无回归 | ✅ |

---

## 二、逐组结果

### TC-M 存量回填迁移（RB3 / 验收点③）— ✅ 全 PASS
| 用例 | 结果 | 证据 |
|------|:---:|------|
| M1 迁移成功 | ✅ | V321「material recipe name default symbol」success=t |
| M2 name 无 NULL + 回填=symbol | ✅ | `name IS NULL=0`(261 全回填);AgC3(00002)name=`AgC3`(=symbol) |
| **M2b ★已有中文名不被冲** | ✅✅ | `AgCu85/AgCu90=银铜合金`、`AgNi70/90/95=银镍合金`、`CuZn70=铜锌合金` **保持中文名未被冲成 symbol**(RB3 正确用 `WHERE name IS NULL`,规避 R1) |

### TC-I 导入 name=symbol（RB1 / 验收点②）— ✅ 全 PASS
| 用例 | 结果 | 证据 |
|------|:---:|------|
| I1 253/1 基线不变 | ✅ | 重导 materialsUpserted=253、skipped=1(WZHF26-25) |
| I2 导入 name=symbol | ✅ | 00001 Ag/Ag、00002 AgC3/AgC3、00075 AgSnO2/AgSnO2 |
| I3 导入无 NULL name | ✅ | 5 位编号材质 name NULL=0 |

### TC-C 新建/编辑 name 默认=symbol（RB2）— ✅ 全 PASS
| 用例 | 结果 | 证据 |
|------|:---:|------|
| C1 新建不传 name→=symbol | ✅ | RTEST1 name=XxTest(=symbol) |
| C2 新建传 name→用传值 | ✅ | RTEST2 name=自定义名 |
| C3 编辑清空(空串)→回落 symbol | ✅ | name→YyTest(=symbol) |
| C4 编辑纯空白→回落 symbol | ✅ | name「   」→YyTest(空白也回落,R2) |

### TC-S 搜索按名称（RB4 / 验收点⑤）— ✅ 全 PASS
| 用例 | 结果 | 证据 |
|------|:---:|------|
| S1 按名称(化学式)AgC3 | ✅ | 命中 00002 |
| **S2 ★按人工中文名 银铜合金** | ✅✅ | 命中 AgCu85/AgCu90(symbol=AgCu 不含「银铜合金」→ 证明 **name 维度确实生效**) |
| S3 原维度不回归 | ✅ | 编号 00002→1、元素中文名 银→142 |

### TC-U 前端（RF1/RF2 / 验收点①④⑥）— ✅ Playwright 全绿 + 截图
> `e2e/material-name-repair-ui.spec.ts`(config `repair1-ui.config.ts` 指隔离 5175)`1 passed`;截图 `r1-ui-01/02`。

| 用例 | 结果 | 证据 |
|------|:---:|------|
| **U1 化学式+名称两列** | ✅ | 列头有「化学式」+「名称」;旧「材质名称」标签已消失(count=0) |
| U2 搜索占位 | ✅ | 「搜索 材质编号 / 化学式 / 名称 / 元素」 |
| **U3 编辑抽屉化学式标签** | ✅ | 截图02:symbol 字段 label=「化学式」、回显 AgC3 |
| **U4 名称可编辑** | ✅ | 「名称」字段可编辑;改为 测试名UI 保存成功(API 复核 name=测试名UI) |
| **U5 留空保存=化学式** | ✅✅ | 清空名称保存 → API 复核 name 回落=`AgC3`(=化学式) |
| **U6 配比仍隐藏** | ✅ | 编辑抽屉无「配比」字段(count=0) |

### TC-R 下游回归（验收点⑦）— ✅ PASS
| 用例 | 结果 | 证据 |
|------|:---:|------|
| R1 ConfigureSearch 非 500 | ✅ | search-parts?q=Ag → 200(name 现非空,`COALESCE(mr.name,…)` 直接取值,语义仍是材质名,无破坏) |

---

## 三、缺陷

**无。** 初测即全项通过,无功能/视觉缺陷。R1(RB3 误伤已有 name)风险已由 M2b 证明规避。

---

## 四、达标判定

- **后端 RB1-RB4**:迁移回填(name IS NULL=0 + 已有中文名不被冲)+ 导入 name=symbol + 新建/编辑留空回落 + 搜索加 name + 下游无回归 → ✅ **达标**。
- **前端 RF1-RF2**:化学式+名称两列 + 编辑抽屉化学式标签 + 名称可编辑 + 留空保存回落 + 配比隐藏,Playwright 实跑视觉确认 → ✅ **达标**。

**结论:task-0708 repair-1 前后端全项通过,达到交付水平,可进入合并。**

---

## 五、测试留痕

- 隔离环境:5175 / 8082 / `cpq_db_repair1`(主干零污染);SYSTEM_ADMIN 会话。
- QA 测试数据(RTEST1/2、00002 临时改名)已清理还原;name IS NULL=0、00002=AgC3、AgCu85=银铜合金 完好。
- Playwright 资产:`e2e/material-name-repair-ui.spec.ts` + `e2e/repair1-ui.config.ts` + `e2e/screenshots/r1-ui-01/02.png`(worktree material-name-repair)。
