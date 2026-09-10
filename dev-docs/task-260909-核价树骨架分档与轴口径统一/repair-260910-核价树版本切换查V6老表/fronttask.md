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
