import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react-swc";
import { readVersionInfo, writeVersionInfo } from "./scripts/version.mjs";

// 构建期版本信息：与 public/version.json 保持一致，供侧边栏与更新检测对比
// 直接执行 vite（不经 package.json 脚本）时也能保证 version.json 存在
const versionInfo = writeVersionInfo(readVersionInfo());

/**
 * 把 node_modules 下的第三方依赖按用途拆成独立 chunk：
 * - react-vendor / radix / query：业务迭代时 hash 不变，可长期缓存；
 * - charts：只在概览页图表真正渲染时加载（见 DashboardPage 的懒加载）。
 */
function manualChunks(id) {
  if (!id.includes("node_modules")) return undefined;
  const normalized = id.replace(/\\/g, "/");
  // react-router 的运行时依赖（@remix-run/router）一并归入 react-vendor，
  // 否则 react-vendor -> vendor -> react-vendor 会出现循环 chunk
  if (
    /\/node_modules\/(react|react-dom|react-router|react-router-dom|scheduler|@remix-run)\//.test(
      normalized,
    ) ||
    /\/node_modules\/@remix-run\//.test(normalized)
  ) {
    return "react-vendor";
  }
  if (/\/node_modules\/@radix-ui\//.test(normalized)) {
    return "radix";
  }
  if (
    /\/node_modules\/(recharts|recharts-scale|react-smooth|victory-vendor|d3-[^/]+|decimal\.js-light|internmap|fast-equals)\//.test(
      normalized,
    )
  ) {
    return "charts";
  }
  if (/\/node_modules\/@tanstack\//.test(normalized)) {
    return "query";
  }
  return "vendor";
}

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
  build: {
    // 本机 esbuild(Go 二进制) 读盘挂起，无法使用 Vite 默认的 esbuild 压缩；
    // 改用纯 JS 实现的 terser（不依赖 esbuild），保证生产产物仍然是压缩后的。
    minify: "terser",
    terserOptions: {
      compress: {
        passes: 1,
      },
      format: {
        comments: false,
      },
    },
    // CSS 体积很小（约 48KB），继续输出未压缩样式，避免再引入原生依赖。
    cssMinify: false,
    rollupOptions: {
      output: {
        manualChunks,
      },
    },
  },
});
