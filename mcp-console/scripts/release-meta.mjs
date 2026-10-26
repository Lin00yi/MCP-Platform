// 发布元信息生成器（供 version.mjs 使用）
//
// 版本号：package.json 的 major.minor + git 提交数，例如 1.1.37；
//        可用 CONSOLE_VERSION 覆盖。
// 更新内容：只取最近一条提交标题（每次发版一条说明）；可用 CONSOLE_RELEASES（JSON）覆盖。
//
// 用法：
//   node scripts/release-meta.mjs --field version
//   node scripts/release-meta.mjs --field releases
//   node scripts/release-meta.mjs --field json
import { execSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const REPO_ROOT = path.resolve(ROOT, "..");

function git(args, fallback = "") {
  try {
    return execSync(`git -C "${REPO_ROOT}" ${args}`, {
      stdio: ["ignore", "pipe", "ignore"],
    })
      .toString()
      .trim();
  } catch {
    return fallback;
  }
}

function readPackage() {
  return JSON.parse(fs.readFileSync(path.join(ROOT, "package.json"), "utf8"));
}

function normalizeVersion(value) {
  const parts = String(value || "0.0.0").split(".");
  const major = parts[0] || "0";
  const minor = parts[1] || "0";
  const patch = parts[2] || "0";
  return { major, minor, patch };
}

function currentVersion() {
  if (process.env.CONSOLE_VERSION?.trim()) {
    return process.env.CONSOLE_VERSION.trim();
  }
  const pkg = readPackage();
  const { major, minor } = normalizeVersion(pkg.version);
  const count = git("rev-list --count HEAD", "");
  return count ? `${major}.${minor}.${count}` : pkg.version;
}

function currentCommit() {
  if (process.env.GIT_COMMIT?.trim()) {
    return process.env.GIT_COMMIT.trim();
  }
  return git("rev-parse --short HEAD", "");
}

function highlights() {
  // 更新说明只取「最近一条」可用的提交标题：跳过 Merge / chore(release) 等无意义提交，
  // 过滤后只保留第一条，保证默认每次版本更新说明只有一条。
  const raw = git("log -n 20 --pretty=format:%s", "");
  if (!raw) {
    return [`构建 ${currentCommit() || "local"}`];
  }
  const lines = raw
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean)
    .filter((line) => !/^Merge\b/i.test(line))
    .filter((line) => !/^chore\(release\)/i.test(line))
    .map((line) => line.replace(/^\w+(\([^)]*\))?:\s*/, ""))
    .filter(Boolean);
  const latest = lines[0];
  return latest ? [latest] : [`构建 ${currentCommit() || "local"}`];
}

/** 解析外部传入的 releases（兼容数组和 {releases:[...]}） */
function envReleases() {
  const raw = process.env.CONSOLE_RELEASES?.trim();
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    const list = Array.isArray(parsed) ? parsed : parsed?.releases;
    if (!Array.isArray(list)) return null;
    return list
      .filter((item) => item && typeof item.version === "string" && Array.isArray(item.highlights))
      .map((item) => ({
        version: item.version.trim(),
        date: typeof item.date === "string" ? item.date : "",
        highlights: item.highlights.filter((line) => typeof line === "string" && line.trim()).map((line) => line.trim()),
      }));
  } catch {
    return null;
  }
}

export function buildMeta() {
  const version = currentVersion();
  const buildTime = process.env.CONSOLE_BUILD_TIME?.trim() || new Date().toISOString();
  const commit = currentCommit();
  const provided = envReleases();
  const release = {
    version,
    date: buildTime.slice(0, 10),
    highlights: provided?.find((item) => item.version === version)?.highlights ?? highlights(),
  };
  const releases = [release, ...(provided ?? []).filter((item) => item.version !== version)];
  return { version, buildTime, commit, releases };
}

const isCli = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isCli) {
  const meta = buildMeta();
  const fieldIndex = process.argv.indexOf("--field");
  const field = fieldIndex >= 0 ? process.argv[fieldIndex + 1] : "json";
  if (field === "version") {
    process.stdout.write(meta.version);
  } else if (field === "releases") {
    process.stdout.write(JSON.stringify(meta.releases));
  } else if (field === "buildTime") {
    process.stdout.write(meta.buildTime);
  } else {
    process.stdout.write(JSON.stringify(meta));
  }
}
