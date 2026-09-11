# fronttask · repair-260910 核价树版本切换

## F-1 · 叶子节点不渲染版本下拉（服务 AC-2）

文件：`cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx`（`VersionSelectDropdown` 的两处调用点 `:889` / `:908`）

判据：该行的 `bom_version` 为空 / `null` ⇒ 渲染纯文本占位 `—`，**不挂载 `VersionSelectDropdown`**。

- 🔑 判据只有「**有没有自己那张 BOM**」这一条 —— 后端改动 1 之后，叶子节点的 `bom_version` 自然为 `NULL`，前端直接消费即可，🚫 **不要**自己去推断层级或叶子性。
- 🚫 **不许**因为「候选只有一个版本」就隐藏下拉（`E-7`：只有 1 版时照常显示该单项）。
- 🚫 **不改** `VersionSelectDropdown.tsx` 内部逻辑 —— 它的 `options` 拉取、`repair-0590` 的身份维度重置、antd v6 适配都是既有正确行为。

## 🚫 本次不做

- 不动版本列宽 / 样式（`repair-071501` 已定稿）
- 不动非树页签的版本下拉

---

## F-2 · 编辑页只读壳同款判空（服务 AC-16）

> 🔄 **开工后并入（2026-09-10 用户裁决扩范围）**。原 `fronttask.md`「🚫 本次不做」里写着「不动非树页签的版本下拉」——
> 该条**不受影响**：F-2 动的是**树行**在编辑页的只读壳，不是非树页签。

文件：`cpq-frontend/src/pages/quotation/QuotationStep2.tsx`（`:3666` / `:3669` 附近）

- 判空口径与 F-1 **完全一致**：`bomVersion != null && String(bomVersion).trim() !== ''`
  🚫 不许用 `!!bomVersion` —— 会把合法版本 `"0"` 当空值吞掉
- 为空 ⇒ 渲染 **`—` 纯文本**，🚫 不渲染 `<select disabled>` 空壳
- 非空 ⇒ 维持现有只读壳与版本号显示（**阳性对照**：AC-16 要求同表非叶子行仍有壳）
- 🚫 **不要**把 Step2 的只读壳换成可交互的 `VersionSelectDropdown` —— 编辑页只读是既有设计，不在本次范围
