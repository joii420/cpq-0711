# task-260911 · 接口契约

> 🚨 **本文件是前后端并行开发唯一的协调物**；变更必须显式通知在跑的子代理。

## 1. 变更总览

| # | 端点 | 变更类型 | 服务的 AC |
|---|---|---|---|
| §2.1 | `POST /configure-product/quotations/{quotationId}` | **响应语义变化**（`lineItems[].productPartNo` 的含义） | AC-1 / AC-2 / AC-5 |
| §2.2 | `POST /configure-product/lookup-fingerprint` | 🚦 **待 A0** —— 指纹口径下移到零件层后，预览返回什么 | AC-4 |

🚫 **不变更**：`GET /quotations/configure/search-parts` · `GET /quotations/configure/outsourced-parts` · 绑定路径的 `bindExistingMaterialNo` 契约（`task-260910` AC-18 已交付且亲验通过）。

## 2. 逐个契约

### 2.1 选配提交

**响应结构不变，但 `productPartNo` 的含义变了**：

| | 现在 | 三层后 |
|---|---|---|
| `lineItems[].productPartNo` | 那个唯一的料号（既是产品又是零件） | **产品料号 P** |
| 零件料号 C | （不存在这个概念） | ✅ **要回传** —— 见下方 `§2.1.1` 契约定稿 |

#### 2.1.1 🆕 `configuredPartNos` 契约定稿（2026-09-13）

🚦 **定稿依据**：前后端**各自独立**落地后字段**逐字一致**，主线核对后定稿。
> 后端 `ConfiguredPartNoDTO` · 前端 `types/configure.ts` 的 `ConfiguredPartNo`。
> 📌 这不是「谁抄了谁」—— 两边都读了同一份原型图（`原型图/01`、`02`）并按它推导，**这本身就是原型定稿有效的一个证据**。

`ConfigureProductResponse.configuredPartNos?: ConfiguredPartNo[]`

| 字段 | 类型 | 说明 |
|---|---|---|
| `partNo` | `String` | 料号本身 |
| `role` | `String` | 值域 **`"PRODUCT"` / `"PART"`**（后端常量 `ROLE_PRODUCT` / `ROLE_PART`） |
| `roleLabel` | `String` | 中文标签，值域 **「产品料号」/「零件料号」**（后端常量 `LABEL_PRODUCT` / `LABEL_PART`）。<br>🔑 **前端给了就优先渲染它，🚫 不许按 `role` 另编一套叫法** —— 叫法变更只改后端一处 |
| `reused` | `Boolean` | `true` = 复用已有；`false` = 本次新建。<br>后端注明 🚫 **不允许为 null**（「本 DTO 只在能说清时才下发」） |

**何时非空**：🚨 **只在 SIMPLE 三层路径**。COMPOSITE / 绑定已有销售料号 / 外购件一律 `null` ⇒ 前端走 legacy 回落分支（已按此实现）。

🚫 **前端不许推导任何一项** —— 哪个是产品号、哪个是零件号、复用没复用，判据全在后端。
⚠️ **前端对 `role`/`roleLabel`/`reused` 缺省的处理是「不标」而不是「猜一个」**（防御性写法）；后端契约上必发，两者不冲突。

⚠️ **前端影响**：确认页现在显示一个料号。三层后**至少要能说清「客户产品编号绑的是 P、材质挂在 C 上」**，否则用户看不懂为什么冒出两个号。

### 2.2 指纹预览

🔴 **2026-09-13 部分定稿** —— 前端实测在途后端代码：`reusedHfPartNos` 现在装的是**零件号**（`resolvePart` 指纹命中）。🚨 **但后端尚未在回报里确认这一点** ⇒ 前端 legacy 回落分支暂用**中性标签**「本次复用的料号」，🚫 不写死「产品/零件」。**后端回报确认后，本节补定稿并让前端改成明确标签。**

🚦 原「待 A0」内容 —— `D-2` 把复用判定下移到零件层后：
- 「命中复用」的含义从「整个产品复用」变成「**零件复用、产品仍新铸**」
- 确认页的提示文案（`task-260910` 亲验时是「换个次序仍是同一个产品。**不会新建料号**」）**不再准确** —— 产品料号仍然会新建
- ⇒ 文案与返回字段都要重定，**A0 一并裁决**

## 3. 错误码

沿用 `task-260910` 全部错误码，**本期不新增**（若 A0 的方案需要新增，回写本节并通知双方）。
