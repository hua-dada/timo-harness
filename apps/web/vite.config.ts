import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
// vitest/config 的 defineConfig 才带 test 字段（vite 的不带）
import { defineConfig } from "vitest/config";
import { fileURLToPath, URL } from "node:url";

// dev 5173：/api 与 /files 全部代理到本仓 Spring Boot（8080）。
// /files 走双前缀里的短前缀（后端同时挂 /files 与 /api/files），与源项目前端一致。
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    // 与 tsconfig paths 对齐（Vite 不读 tsconfig）
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      "/api": { target: "http://127.0.0.1:8080", changeOrigin: false },
      "/files": { target: "http://127.0.0.1:8080", changeOrigin: false },
    },
  },
  // SSE（EventSource）在 proxy 下默认不缓冲；如遇代理攒事件，可加
  // configure(proxy) 设 proxy.on("proxyRes", res => res.headers["x-accel-buffering"] = "no")。
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
