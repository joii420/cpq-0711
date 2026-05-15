import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    // host: true 等价于 0.0.0.0 — 监听所有网卡, 局域网其它设备/同事用主机 IP 也能访问.
    // 后端 Quarkus 已绑 0.0.0.0:8081, proxy 走 Vite 进程内的 localhost 仍指向本机后端, 不受影响.
    host: true,
    port: 5174,
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
});
