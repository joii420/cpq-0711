// task-261030 · 亲验辅助截图配置：复用 S-FLOW 配置（同一 Vite 端口 / 守卫 / 浏览器），只换匹配的 spec。
import flowConfig from './task261030-costing-subtotal-flow.config';

export default {
  ...flowConfig,
  testMatch: /(?:^|\/)task261030-costing-subtotal-shots\.spec\.ts$/,
  timeout: 120_000,
};
