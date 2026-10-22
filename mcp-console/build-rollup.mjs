// 自定义前端构建：Rollup（纯 JS 读文件）+ SWC（Rust 转译），绕开 esbuild(Go) 读盘挂起问题
import { rollup } from "rollup";
import { nodeResolve } from "@rollup/plugin-node-resolve";
import commonjs from "@rollup/plugin-commonjs";
import replace from "@rollup/plugin-replace";
import swc from "@swc/core";
import path from "node:path";
import fs from "node:fs";
import { fileURLToPath } from "node:url";
import { execSync } from "node:child_process";
import { readVersionInfo, writeVersionInfo } from "./scripts/version.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = __dirname;
const versionInfo = readVersionInfo();

// 1) SWC 转译插件（TS/TSX -> ES2020 JS，JSX 用 react automatic runtime）
const swcPlugin = {
  name: "swc-transform",
  async transform(code, id) {
    if (!/\.(ts|tsx|js|jsx|mjs)$/.test(id)) return null;
    const isTs = /\.(ts|tsx)$/.test(id);
    const isJsx = /\.(jsx|tsx)$/.test(id);
    const result = await swc.transform(code, {
      filename: id,
      jsc: {
        parser: isTs
          ? { syntax: "typescript", tsx: isJsx }
          : { syntax: "ecmascript", jsx: isJsx },
        transform: isJsx ? { react: { runtime: "automatic" } } : undefined,
        target: "es2020",
      },
      module: { type: "es6" },
    });
    return { code: result.code, map: null };
  },
};

// 2) 别名插件：@/ -> src/（自动补扩展名 / index）
const aliasPlugin = {
  name: "alias",
  resolveId(source) {
    if (!source.startsWith("@/")) return null;
    const base = path.resolve(ROOT, "src", source.slice(2));
    if (fs.existsSync(base) && fs.statSync(base).isFile()) return base;
    for (const ext of [".ts", ".tsx", ".js", ".jsx", ".json", ".css"]) {
      if (fs.existsSync(base + ext)) return base + ext;
    }
    if (fs.existsSync(base) && fs.statSync(base).isDirectory()) {
      for (const f of ["index.ts", "index.tsx", "index.js"]) {
        const full = path.join(base, f);
        if (fs.existsSync(full)) return full;
      }
    }
    return null;
  },
};

// 3) CSS 导入当作空模块（CSS 复用已有产物，不参与 JS 打包）
const cssPlugin = {
  name: "ignore-css",
  resolveId(source) {
    if (source.endsWith(".css")) return source;
    return null;
  },
  load(id) {
    if (id.endsWith(".css")) return "";
    return null;
  },
};

async function build() {
  // 清空旧产物目录，避免 Rollup 复用旧 chunk 导致改动不生效
  const assetsDir = path.resolve(ROOT, "dist/assets");
  fs.rmSync(assetsDir, { recursive: true, force: true });
  fs.mkdirSync(assetsDir, { recursive: true });

  const bundle = await rollup({
    input: path.resolve(ROOT, "src/main.tsx"),
    plugins: [
      aliasPlugin,
      cssPlugin,
      replace({
        "import.meta.env.VITE_USE_MOCK": '"false"',
        "import.meta.env.VITE_API_BASE_URL": '"/api"',
        "process.env.NODE_ENV": '"production"',
        __CONSOLE_VERSION__: JSON.stringify(versionInfo.version),
        __CONSOLE_BUILD_ID__: JSON.stringify(versionInfo.buildId),
        __CONSOLE_BUILD_TIME__: JSON.stringify(versionInfo.buildTime),
        __CONSOLE_COMMIT__: JSON.stringify(versionInfo.commit || ""),
        __CONSOLE_RELEASES__: JSON.stringify(versionInfo.releases),
        preventAssignment: true,
        delimiters: ["", ""],
      }),
      swcPlugin,
      nodeResolve({ extensions: [".ts", ".tsx", ".js", ".jsx", ".mjs", ".json"] }),
      commonjs(),
    ],
    onwarn(warning, warn) {
      if (warning.code === "CIRCULAR_DEPENDENCY" || warning.code === "THIS_IS_UNDEFINED") return;
      warn(warning);
    },
  });

  await bundle.write({
    dir: path.resolve(ROOT, "dist/assets"),
    format: "es",
    entryFileNames: "index-[hash].js",
    inlineDynamicImports: true,
    sourcemap: false,
  });
  await bundle.close();

  // 版本信息：public 与 dist 同步（Rollup 构建不经过 vite 的 public 拷贝）
  writeVersionInfo(versionInfo);

  // 4) 用 Tailwind CLI 重新生成 CSS（样式与源码保持一致，绕开 esbuild 读盘问题）
  const jsName = fs.readdirSync(assetsDir).find((f) => f.startsWith("index-") && f.endsWith(".js"));
  if (!jsName) {
    throw new Error("未找到生成的 JS 产物");
  }
  const hash = jsName.slice("index-".length, -".js".length);
  const cssName = `index-${hash}.css`;
  const tailwindBin = path.resolve(ROOT, "node_modules", ".bin", "tailwindcss");
  execSync(
    `"${tailwindBin}" -i src/index.css -o "${path.join(assetsDir, cssName)}" --minify`,
    { cwd: ROOT, stdio: "inherit" },
  );

  // 清理旧的 JS/CSS 产物，避免残留
  for (const f of fs.readdirSync(assetsDir)) {
    const isStale =
      (f.endsWith(".css") && f !== cssName) || (f.endsWith(".js") && f !== jsName);
    if (isStale) {
      fs.unlinkSync(path.join(assetsDir, f));
    }
  }

  // 更新 index.html 引用（JS + CSS 同 hash，天然防缓存）
  const htmlPath = path.resolve(ROOT, "dist/index.html");
  let html = fs.readFileSync(htmlPath, "utf8");
  html = html.replace(/src="\/assets\/index-[^"]*\.js"/, `src="/assets/${jsName}"`);
  html = html.replace(/href="\/assets\/index-[^"]*\.css"/, `href="/assets/${cssName}"`);
  fs.writeFileSync(htmlPath, html);

  console.log(`✅ Rollup+SWC 构建完成 -> dist/assets/${jsName}`);
  console.log(`✅ Tailwind CSS 已重建 -> dist/assets/${cssName}`);
}

build().catch((e) => {
  console.error("❌ 构建失败:", e.message);
  process.exit(1);
});
