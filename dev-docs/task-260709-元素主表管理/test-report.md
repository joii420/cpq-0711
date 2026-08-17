# 测试报告 · task-0709 元素主表管理（BL-0040）

> 测试员:开发需求测试员(QA)｜测试日期:2026-07-09｜依据:`test.md`(TC-PRE/M/A/B/C/D/E/U/R)
> 被测:`feat/element-master-ui`(`2675084` 后端 B1-B6 + 前端 F1-F5 + V319/V320 迁移)
> 环境:**隔离 B 方案** 后端 8082 + 前端 5175 + 库 `cpq_db_elemtest`(主干零污染);live 端到端 + DB 逐项断言 + Playwright 前端实跑。

---

## 一、总体结论

## ✅ 前后端均达到交付水平（初测 1 处 LOW 视觉缺陷已修复复验,全项通过）

**B 模型完整正确落地**:element_no=不可改业务主键、element_code=被引用即锁(142 引用的 Ag 改符号 409)、element_name 随时可改;material_recipe_element 加 element_no 权威链且全回填;导入按 element_no upsert 不覆盖人工维护;253/1 基线零回归;CRUD + 符号锁 + 软删全过;前端元素页签 + 符号锁 UI Playwright 实跑视觉确认。初测发现的**锁定符号输入框不回显当前值(纯视觉)开发已修复,QA 复验通过**(现回显「Ag」+ 保存无回归)。

| 维度 | 结果 |
|------|------|
| 迁移(B1 element_no 业务主键 + 补号 / B2 mre.element_no 回填) | ✅ 全 PASS |
| 列表(referencedCount/codeLocked/搜索/排序/时间) | ✅ PASS |
| 新建(编号+符号唯一,撞→409) | ✅ PASS |
| **符号锁(核心一票否决)** | ✅ PASS |
| 软删(INACTIVE/幂等/被引用可停用不断链) | ✅ PASS |
| **导入回归(253/1 + 按编号 upsert 不覆盖,一票否决)** | ✅ PASS |
| 不动边界回归(快照/定价/ConfigureSearch) | ✅ PASS |
| 前端 UI(页签/列/搜索/新建/符号锁/停用确认) | ✅ Playwright 实跑 + 6 截图 |
| 视觉缺陷(锁符号输入不回显值) | ✅ **已修复复验**(现回显「Ag」,Playwright toHaveValue('Ag') 通过) |

---

## 二、逐组结果

### TC-PRE / TC-M 迁移 — ✅ 全 PASS
| 用例 | 结果 | 证据 |
|------|:---:|------|
| PRE1 锚点 | ✅ | element=39;Ag(10001)引用=142 |
| M2 element_no 业务主键 | ✅ | element_no `is_nullable=NO`;`uq_element_no` UNIQUE;NULL=0 |
| **M3 Au/CdO 补号** | ✅ | `Au→90001`、`CdO→90002`(90000+ 段,按 code 排序,精确) |
| **M4 mre.element_no 回填** | ✅ | 628 行 **element_no NULL=0** 全回填;Ag 行=10001 |

### TC-A 列表 — ✅ 全 PASS
| 用例 | 结果 | 证据 |
|------|:---:|------|
| A1 referencedCount/codeLocked | ✅ | Ag:referencedCount=142、codeLocked=true、中文=银、时间非空 |
| A2 未引用 codeLocked=false | ✅ | Au(90001)referencedCount=0、codeLocked=false |
| A4/A5/A6 搜索 | ✅ | 编号 10001 / 符号 Ag / 中文名 银 均命中 |

### TC-B 新建 — ✅ 全 PASS
| 用例 | 结果 | 证据 |
|------|:---:|------|
| B1 新建 | ✅ | 90100 Mo 钼:status=ACTIVE、referencedCount=0、codeLocked=false |
| B2 编号撞号 | ✅ | 409「元素编号已存在: 10001」 |
| B3 符号撞号 | ✅ | 409「符号已存在: Ag」 |
| B4 必填缺失 | ✅ | 400「elementCode 必填」 |

### TC-C 符号锁(核心·一票否决) — ✅ 全 PASS
| 用例 | 结果 | 证据 |
|------|:---:|------|
| **C1 被引用改符号 409** | ✅✅ | Ag(引用142)改符号 → **409「符号已被 142 个材质引用，不可修改」**;DB 仍 Ag |
| C2 被引用改中文名 200 | ✅ | 银→银QA 成功(中文随时可改) |
| C4 element_no 不可改 | ✅ | 体传 99999 无效;90100 不变;无 99999 |
| C5 未引用可改符号 | ✅ | 90100 Mo→Mo2 成功 |
| C6 改符号撞他人 409 | ✅ | 90100 改 Ag → 409「符号已存在: Ag」 |
| C7 不存在 404 | ✅ | PUT /elements/00000 → 404 |

### TC-D 软删 — ✅ 全 PASS
| 用例 | 结果 | 证据 |
|------|:---:|------|
| D1 软删 | ✅ | 90100 DELETE→204、status=INACTIVE(非物理删) |
| D2 幂等 | ✅ | 再删→204 |
| **D3 被引用可停用不断链** | ✅ | Ag DELETE→204、INACTIVE;**material_recipe_element Ag 142 行不受影响**;已还原 ACTIVE |

### TC-E 导入回归(一票否决) — ✅ 全 PASS
| 用例 | 结果 | 证据 |
|------|:---:|------|
| **E1 253/1 基线不变** | ✅ | 重导 materialsUpserted=253、skipped=1(WZHF26-25) |
| **E3 按编号 upsert 不覆盖人工** | ✅✅ | 先改 element 10001 中文=`银-QA手改` → 重导后仍=`银-QA手改`(未被还原成「银」) |
| E2 mre.element_no 全回填 | ✅ | 导入后 628 行 NULL=0;AgC3(00002)元素 Ag→10001/C→10012 |

### TC-R 不动边界回归 — ✅ 全 PASS
| 用例 | 结果 | 证据 |
|------|:---:|------|
| R2 ConfigureSearch 非 500 | ✅ | search-parts?q=Ag → 200 |
| R3 快照+权威链并存 | ✅ | material_recipe_element:element_code=Ag/element_name=银/element_no=10001 并存 |
| R4 定价 join 通 | ✅ | element JOIN costing_element_price(Ag/Cu/Ni)=3 |

### TC-U 前端 UI（Playwright 实跑 5175 + 6 截图）— ✅ 全 PASS
> `e2e/element-management-ui.spec.ts`(config `element-ui.config.ts` 指隔离 5175)`1 passed`;截图 `elem-ui-01~06`。

| 用例 | 结果 | 证据 |
|------|:---:|------|
| U1 元素页签 + 列 | ✅ | 主数据维护「元素」页签;列 元素编号/符号/中文名/被引用材质数/状态/创建时间/修改时间;40 行 |
| U3 搜索 | ✅ | 10001→Ag、银 均过滤 |
| U4 新建抽屉 | ✅ | 「新建元素」抽屉,元素编号可填 |
| **U5 编辑符号锁** | ✅ | 截图 04:「编辑元素:10001」,元素编号只读、**符号锁定(disabled)**、中文名可改 |
| U6 未引用可改符号 | ✅ | 截图 05:Au(90001)符号输入可编辑 |
| U7 停用二次确认 | ✅ | 截图 06:SelectableTable 停用弹确认 Modal |
| 补验:锁元素改中文名保存 | ✅ | UI 改 Ag 中文名保存成功(error toast=0)、符号仍 Ag(锁符号显示缺陷不影响功能) |

---

## 三、缺陷

| 级别 | 项 | 说明 / 建议 |
|------|----|------|
| ✅ **已修复复验** | ~~锁定符号输入框不回显当前值~~ | 初测:被引用元素编辑「符号」禁用框显 placeholder 非「Ag」(根因 `ElementEditDrawer.tsx` 符号锁时 `<Tooltip>{Input}</Tooltip>` 破坏 Form.Item 值注入)。**开发已修**(去掉内层 Tooltip 包裹,tooltip 走 Form.Item label)。**QA 复验**:编辑 Ag → 符号框回显「Ag」且禁用(Playwright `toHaveValue('Ag')` 通过 + 截图确认)、标签 (?) tooltip 在、改中文名保存无回归。**闭环** |

---

## 四、达标判定

- **后端(B1-B6)**:迁移 + element_no 业务主键 + 补号 + mre 回填 + CRUD + **符号锁(一票否决)** + 软删 + **导入回归(一票否决)** + 不动边界 → ✅ **达标**。
- **前端(F1-F5)**:元素页签 + 列 + 搜索 + 新建 + 编辑符号锁 + 停用确认,Playwright 实跑视觉确认 → ✅ **达标**。
- **视觉缺陷(锁符号不回显)**:✅ **已修复复验通过**(2026-07-09 二轮,现回显「Ag」+ 保存无回归)。

**结论:前后端功能均达到交付水平,初测唯一 LOW 视觉缺陷已修复闭环,全项通过,可进入收尾合并。**

---

## 五、测试留痕

- 隔离环境:8082 / 5175 / `cpq_db_elemtest`;SYSTEM_ADMIN 会话。
- QA 测试数据(元素 90100 Mo2、Ag 临时改名)已清理还原;element 39、mre.element_no NULL=0、Ag 引用 142 完好。
- Playwright 资产:`e2e/element-management-ui.spec.ts` + `e2e/element-ui.config.ts` + `e2e/element-namesave-check.spec.ts` + `e2e/screenshots/elem-ui-01~06.png`。
