// 控制台版本信息生成器
//
// 产物：public/version.json（vite build 时复制到 dist，运行期以 /version.json 提供）
//
// 数据来源：
//   - 版本号：package.json 的 major.minor + git 提交数（可用 CONSOLE_VERSION 覆盖）
//   - 更新内容：当前构建自动生成（最近一条提交标题）+ changelog.json 历史版本
//   - 构建时间：本次生成时间（可用 CONSOLE_BUILD_TIME 覆盖）
//   - 提交号：GIT_COMMIT 环境变量，或本地 git
//
// 直接执行：node scripts/version.mjs
import { execSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { buildMeta } from "./release-meta.mjs";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const VERSION_FILE = path.join(ROOT, "public", "version.json");

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, "utf8"));
}

function readCommit() {
  if (process.env.GIT_COMMIT) return process.env.GIT_COMMIT.trim();
  try {
    return execSync("git rev-parse --short HEAD", { cwd: ROOT, stdio: ["ignore", "pipe", "ignore"] })
      .toString()
      .trim();
  } catch {
    // 发布构建上下文可能不包含 .git，取不到提交号时留空
    return "";
  }
}

/** 读取 changelog.json，兼容 { releases: [...] } 与裸数组两种写法 */
function readReleases() {
  const file = path.join(ROOT, "changelog.json");
  if (!fs.existsSync(file)) return [];
  try {
    const raw = readJson(file);
    const list = Array.isArray(raw) ? raw : Array.isArray(raw?.releases) ? raw.releases : [];
    return list
      .filter((item) => item && typeof item.version === "string" && Array.isArray(item.highlights))
      .map((item) => ({
        version: item.version.trim(),
        date: typeof item.date === "string" ? item.date : "",
        highlights: item.highlights.filter((line) => typeof line === "string" && line.trim()).map((line) => line.trim()),
      }));
  } catch (error) {
    console.warn(`[version] 读取 changelog.json 失败：${error.message}`);
    return [];
  }
}

/** 生成当前构建的版本信息 */
export function buildVersionInfo() {
  const meta = buildMeta();
  const version = meta.version;
  const buildTime = meta.buildTime;
  const commit = meta.commit;
  const staticReleases = readReleases();

  // 当前构建的 release 放最前；同版本以本次生成的内容为准，历史版本去重保留
  const releases = [];
  const seen = new Set();
  for (const release of [...meta.releases, ...staticReleases]) {
    if (!release || seen.has(release.version)) continue;
    seen.add(release.version);
    releases.push(release);
  }

  if (releases.length === 0) {
    releases.push({
      version,
      date: buildTime.slice(0, 10),
      highlights: [`构建 ${commit || "local"}`],
    });
  }

  return {
    name: "mcp-console",
    version,
    buildId: process.env.CONSOLE_BUILD_ID?.trim() || `${version}+${commit || "local"}.${buildTime}`,
    buildTime,
    commit,
    releases,
  };
}

/** 读取已生成的版本信息；不存在时现场生成（不落盘） */
export function readVersionInfo() {
  if (fs.existsSync(VERSION_FILE)) {
    try {
      const info = readJson(VERSION_FILE);
      if (info && typeof info.version === "string" && typeof info.buildId === "string") return info;
    } catch {
      // 文件损坏时回退到重新生成
    }
  }
  return buildVersionInfo();
}

/** 写入 public/version.json，并在 dist 已存在时同步一份（build-rollup 直接用 dist） */
export function writeVersionInfo(info = buildVersionInfo()) {
  const json = `${JSON.stringify(info, null, 2)}\n`;
  fs.mkdirSync(path.dirname(VERSION_FILE), { recursive: true });
  fs.writeFileSync(VERSION_FILE, json);
  const distDir = path.join(ROOT, "dist");
  if (fs.existsSync(distDir) && fs.statSync(distDir).isDirectory()) {
    fs.writeFileSync(path.join(distDir, "version.json"), json);
  }
  return info;
}

const isCli = process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url;
if (isCli) {
  const info = writeVersionInfo();
  console.log(`✅ version.json -> v${info.version} (${info.commit || "no-commit"}) @ ${info.buildTime}`);
}
