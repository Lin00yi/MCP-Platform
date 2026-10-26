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
/** 聚焦/可见性触发的探测去抖窗口（毫秒） */
const VERSION_REFRESH_DEBOUNCE_MS = 2_000;
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

/**
 * 服务端是否部署了与当前页面不同的构建。
 *
 * 以 buildId 为唯一权威（每次构建唯一）：只要不一致就提示刷新。
 * buildTime 仅用于展示，不参与判断 —— 构建时间来自构建机时钟，
 * 回滚、多构建机时钟偏差、复用旧镜像重发等场景下做时间比较会漏报更新。
 */
export function isNewerDeployment(deployed: ConsoleVersionInfo | null): deployed is ConsoleVersionInfo {
  if (!deployed) return false;
  return deployed.buildId !== runningVersion.buildId;
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

/**
 * 轮询服务端版本：首屏拉取 + 每 60s + 页面重新可见/获得焦点时。
 *
 * 可见性与聚焦是同一类信号，合并到一个入口并做 2 秒去抖，
 * 避免用户快速切换窗口时连续打 `/version.json`。
 */
export function useDeployedVersion(): ConsoleVersionInfo | null {
  const [info, setInfo] = useState<ConsoleVersionInfo | null>(null);

  useEffect(() => {
    let alive = true;
    let lastLoadedAt = 0;

    const load = async () => {
      lastLoadedAt = Date.now();
      try {
        const next = await fetchDeployedVersion();
        if (alive) setInfo(next);
      } catch {
        // 版本探测失败不阻塞控制台使用，下一轮/下次聚焦时重试
      }
    };

    void load();
    const timer = window.setInterval(() => {
      void load();
    }, VERSION_POLL_INTERVAL);

    const trigger = () => {
      if (document.visibilityState !== "visible") return;
      if (Date.now() - lastLoadedAt < VERSION_REFRESH_DEBOUNCE_MS) return;
      void load();
    };

    document.addEventListener("visibilitychange", trigger);
    window.addEventListener("focus", trigger);
    return () => {
      alive = false;
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", trigger);
      window.removeEventListener("focus", trigger);
    };
  }, []);

  return info;
}
