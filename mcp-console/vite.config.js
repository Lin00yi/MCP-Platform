import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react-swc";
import { readVersionInfo, writeVersionInfo } from "./scripts/version.mjs";

// 构建期版本信息：与 public/version.json 保持一致，供侧边栏与更新检测对比
// 直接执行 vite（不经 package.json 脚本）时也能保证 version.json 存在
const versionInfo = writeVersionInfo(readVersionInfo());

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  define: {
    __CONSOLE_VERSION__: JSON.stringify(versionInfo.version),
    __CONSOLE_BUILD_ID__: JSON.stringify(versionInfo.buildId),
    __CONSOLE_BUILD_TIME__: JSON.stringify(versionInfo.buildTime),
    __CONSOLE_COMMIT__: JSON.stringify(versionInfo.commit || ""),
    __CONSOLE_RELEASES__: JSON.stringify(versionInfo.releases),
  },
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: {
    port: 5173,
    host: true,
    // 允许通过 ngrok / 内网穿透域名访问（本地 dev 使用）
    allowedHosts: true,
    proxy: {
      // 本地联调时把 /api 代理到控制面（S2 mcp-admin，默认 8082）
      "/api": {
        target: process.env.MCP_ADMIN_URL || "http://localhost:8082",
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/api/, ""),
      },
    },
  },
  preview: {
    port: 5173,
    host: true,
    proxy: {
      // preview 模式同样代理到控制面，便于直接跑生产构建联调
      "/api": {
        target: process.env.MCP_ADMIN_URL || "http://localhost:8082",
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/api/, ""),
      },
    },
  },
  // 本机 esbuild(Go 二进制) 读盘挂起，禁用 esbuild 相关步骤：JS/CSS 均不压缩
  build: {
    minify: false,
    cssMinify: false,
  },
});
