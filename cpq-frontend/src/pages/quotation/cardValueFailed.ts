// lazy-cardvalues：识别失败哨兵卡片值的纯函数。
// 后端 build 确定性失败时落 {"tabs":[],"__cardValueFailed":true}（Task 2），
// 前端命中则该卡片本侧渲染显式『数据待重算』占位，
// 防 AP-38 静默降级为"加载中"幽灵行 / AP-50 僵尸数据。
// 抽成独立小模块，便于 vitest 单测（不拉 QuotationStep2.tsx 的重依赖），
// 同 Task 5 cardValuesWarm.ts 模式；由 QuotationStep2 import 复用并 re-export。
export function isCardValueFailed(json?: string): boolean {
  if (!json) return false;
  try { return JSON.parse(json)?.__cardValueFailed === true; } catch { return false; }
}
