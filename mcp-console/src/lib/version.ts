import { useEffect, useState } from "react";

/** 控制面板版本信息与部署更新检测。 */

export interface ReleaseNote {
  version: string;
  date?: string;
  highlights: string[];
}

export interface ConsoleVersionInfo {
  version: string;
  /** 每次构建唯一，用于判断运行中的页面是否已过期 */
  buildId: string;
  buildTime: string;
  commit?: string;
  releases: ReleaseNote[];
}

/** 当前页面构建时写入的版本信息 */
export const runningVersion: ConsoleVersionInfo = {
  version: __CONSOLE_VERSION__,
  buildId: __CONSOLE_BUILD_ID__,
  buildTime: __CONSOLE_BUILD_TIME__,
  commit: __CONSOLE_COMMIT__ || undefined,
  releases: __CONSOLE_RELEASES__,
};

/** 版本探测间隔：部署完成后最多 1 分钟内自动发现新版本 */
const VERSION_POLL_INTERVAL = 60_000;
const DISMISSED_KEY = "mcp_console_version_dismissed";
/** 控制台始终部署在站点根路径（与 index.html 的绝对资源路径一致） */
const VERSION_URL = "/version.json";

/** 拉取服务端当前部署的版本信息（禁止缓存，保证部署后立即生效） */
export async function fetchDeployedVersion(): Promise<ConsoleVersionInfo> {
  const res = await fetch(`${VERSION_URL}?t=${Date.now()}`, {
    cache: "no-store",
    headers: { Accept: "application/json" },
  });
  if (!res.ok) {
    throw new Error(`版本信息请求失败：${res.status}`);
  }
  const raw: unknown = await res.json();
  return normalizeVersionInfo(raw);
}

function isReleaseNote(value: unknown): value is ReleaseNote {
  if (!value || typeof value !== "object") return false;
  const note = value as Partial<ReleaseNote>;
  return typeof note.version === "string" && Array.isArray(note.highlights);
}

function normalizeVersionInfo(raw: unknown): ConsoleVersionInfo {
  if (!raw || typeof raw !== "object") {
    throw new Error("版本信息格式不正确");
  }
  const data = raw as Partial<ConsoleVersionInfo>;
  if (typeof data.version !== "string" || typeof data.buildId !== "string") {
    throw new Error("版本信息格式不正确");
  }
  return {
    version: data.version,
    buildId: data.buildId,
    buildTime: typeof data.buildTime === "string" ? data.buildTime : "",
    commit: typeof data.commit === "string" && data.commit ? data.commit : undefined,
    releases: Array.isArray(data.releases) ? data.releases.filter(isReleaseNote) : [],
  };
}

/** 服务端构建是否比当前运行页面更新 */
export function isNewerDeployment(deployed: ConsoleVersionInfo | null): deployed is ConsoleVersionInfo {
  if (!deployed || deployed.buildId === runningVersion.buildId) return false;
  const deployedAt = Date.parse(deployed.buildTime);
  const runningAt = Date.parse(runningVersion.buildTime);
  if (!Number.isFinite(deployedAt) || !Number.isFinite(runningAt)) return true;
  return deployedAt >= runningAt;
}

/** 取指定版本的更新内容；没有记录时返回 undefined */
export function releaseFor(info: ConsoleVersionInfo | null): ReleaseNote | undefined {
  if (!info) return undefined;
  return info.releases.find((release) => release.version === info.version);
}

/** 用户已关闭过哪个构建的更新提示（避免每次轮询都弹窗） */
export function readDismissedBuildId(): string {
  try {
    return localStorage.getItem(DISMISSED_KEY) ?? "";
  } catch {
    return "";
  }
}

export function dismissBuild(buildId: string) {
  try {
    localStorage.setItem(DISMISSED_KEY, buildId);
  } catch {
    // localStorage 不可用时本次会话仍会记住状态
  }
}

/** 构建时间格式化：2026-10-20 15:04 */
export function formatBuildTime(value?: string): string {
  if (!value) return "未知";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(date);
}

/** 轮询服务端版本：首屏拉取 + 每 60s + 页面重新可见/获得焦点时 */
export function useDeployedVersion(): ConsoleVersionInfo | null {
  const [info, setInfo] = useState<ConsoleVersionInfo | null>(null);

  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const next = await fetchDeployedVersion();
        if (alive) setInfo(next);
      } catch {
        // 版本探测失败不阻塞控制台使用，下一轮/下次聚焦时重试
      }
    };
    void load();
    const timer = window.setInterval(load, VERSION_POLL_INTERVAL);
    const onVisible = () => {
      if (document.visibilityState === "visible") void load();
    };
    document.addEventListener("visibilitychange", onVisible);
    window.addEventListener("focus", onVisible);
    return () => {
      alive = false;
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
      window.removeEventListener("focus", onVisible);
    };
  }, []);

  return info;
}
