import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 端口 / 后端代理目标可由环境变量覆盖：
//   VITE_PORT         前端端口（默认 5174；task-0712 测试服务用 5175）
//   VITE_API_TARGET   /api 代理目标（默认 http://localhost:8081；测试后端用 http://localhost:8082）
const PORT = Number(process.env.VITE_PORT) || 5174;
const API_TARGET = process.env.VITE_API_TARGET || 'http://localhost:8081';

export default defineConfig({
  plugins: [react()],
  server: {
    // host: true 等价于 0.0.0.0 — 监听所有网卡, 局域网其它设备/同事用主机 IP 也能访问.
    // 后端 Quarkus 已绑 0.0.0.0:8081, proxy 走 Vite 进程内的 localhost 仍指向本机后端, 不受影响.
    host: true,
    port: PORT,
    proxy: {
      '/api': {
        target: API_TARGET,
        changeOrigin: true,
      },
    },
  },
});
