# api.md · 接口契约

## 结论：**本任务接口零改动**

不新增、不修改、不删除任何 REST 端点、请求/响应 DTO、HTTP 状态码或错误码。

## 为什么不改（判定依据，逐条）

| 交付项 | 为什么不动接口 |
|---|---|
| **批次 0** 关测试写共享库 | 只改 `application-test.properties`，不进运行期 |
| **批次 1** 退 `v_compat_*` + 改写 3 个 composite 视图 + `costing_bom_tree_config` | 全在 **DB 对象层与配置数据层**。视图是渲染取数的底层，端点形状不变、响应字段不变 |
| **批次 2** `v_ds_cost_*_all` 换桥 | 同上。`sales_material_no` 是视图**内部列**，`AC-6` 断言其取值**逐值不变** |
| **批次 3** pending 写点改 no-op（`A0-1` 乙） | 改的是**方法内部实现**，签名保留、`@Deprecated` 标注。这些方法**无一暴露为端点** |
| **批次 3b** 3 处用户可见文案 | 改的是 `message` / `suggestion` **字符串内容**，不改错误码 |

## ⚠️ 一处需要显式说明「形状不变但内容会变」

`SqlViewValidator.java:106` 的文案挂在错误码 **`SQL_VIEW_DEPRECATED_TABLE`** 上。
**错误码不变、HTTP 状态不变、响应结构不变**，只有 `message` 里推荐的表名从 V6 老表换成 `ds_*`。

⇒ 前端若对该 `message` 做过**文本匹配**，会受影响。
📌 已核：`cpq-frontend/src` 对 `SQL_VIEW_DEPRECATED_TABLE` 的处理走的是错误码分支，**未匹配文案内容**
（`fronttask.md` 的回归确认清单第 2 条会再验一次）。

## `main-api.md` 回写

🚫 **跳过**，依据 `task-docs.md §2.5` 的豁免条款：「只改了实现、没改契约（方法/路径/参数/响应/错误码全未变）的任务可跳过回写」。

⚠️ 该条同时要求：**必须在 `test-report.md` 里写明「本次无契约变更，无需回写 `main-api.md`」** —— 不写即视为漏项。

## 二期触发条件（什么情况下本文件要重写）

1. `DROP TABLE` 那个后续任务执行时 —— 届时 `/api/cpq/material-masters` 的 5 个端点（`MaterialMasterResource`）必须一并处置，**那是真的接口变更**
2. 若 `A0-1` 中途从「乙 改 no-op」升级为「甲 物理删除」，且删除范围触及任何 `@Path` 类
3. 若批次 1 的数据变化（6 个料号消失）导致某端点响应从非空变为空 —— 那是**契约语义变更**，即使形状不变也要回写
