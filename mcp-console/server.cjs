// MCP Console 静态服务器（纯 Node，不依赖 esbuild/vite）
// - 服务 dist/ 静态资源（SPA 回退到 index.html）
// - 把 /api/* 代理到控制面（S2 mcp-admin，默认 http://localhost:8082）
const http = require("http");
const fs = require("fs");
const path = require("path");

const DIST = path.resolve(__dirname, "dist");
const PORT = Number(process.env.PORT || 5173);
const HOST = process.env.HOST || "127.0.0.1";
const BACKEND = process.env.BACKEND || process.env.MCP_ADMIN_URL || "http://localhost:8082";

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "application/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".ico": "image/x-icon",
  ".woff2": "font/woff2",
  ".map": "application/json",
};

function sendJson(res, status, body) {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(body));
}

http
  .createServer((req, res) => {
    let pathname;
    try {
      const url = new URL(req.url, "http://localhost");
      pathname = decodeURIComponent(url.pathname);
      req.__search = url.search;
    } catch {
      sendJson(res, 400, { code: 400, message: "非法 URL" });
      return;
    }

    // /api 代理到后端
    if (pathname.startsWith("/api/")) {
      const targetPath = pathname.replace(/^\/api/, "") + (req.__search || "");
      const proxyReq = http.request(
        `${BACKEND}${targetPath}`,
        { method: req.method, headers: { ...req.headers, host: new URL(BACKEND).host } },
        (proxyRes) => {
          res.writeHead(proxyRes.statusCode || 500, proxyRes.headers);
          proxyRes.pipe(res);
        },
      );
      proxyReq.on("error", () => {
        sendJson(res, 502, { code: 502, message: "后端未连接" });
      });
      req.pipe(proxyReq);
      return;
    }

    // 静态资源 + SPA 回退：禁止越出 dist 目录
    const relative = pathname === "/" ? "index.html" : pathname.replace(/^\/+/, "");
    let filePath = path.resolve(DIST, relative);
    if (!filePath.startsWith(DIST + path.sep) && filePath !== path.join(DIST, "index.html")) {
      sendJson(res, 403, { code: 403, message: "Forbidden" });
      return;
    }
    if (!fs.existsSync(filePath) || fs.statSync(filePath).isDirectory()) {
      filePath = path.join(DIST, "index.html");
    }
    const ext = path.extname(filePath).toLowerCase();
    const headers = {
      "Content-Type": MIME[ext] || "application/octet-stream",
      "X-Content-Type-Options": "nosniff",
    };
    // 入口页与版本信息禁止缓存：部署后刷新即可加载新资源，更新检测也能立即发现新版本
    if (pathname === "/" || filePath.endsWith(`${path.sep}index.html`) || pathname === "/version.json") {
      headers["Cache-Control"] = "no-store, max-age=0";
    }
    res.writeHead(200, headers);
    fs.createReadStream(filePath).pipe(res);
  })
  .listen(PORT, HOST, () => {
    console.log(`MCP Console -> http://${HOST}:${PORT} (代理 /api -> ${BACKEND})`);
  });
