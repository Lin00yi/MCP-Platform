/** 字段展示辅助 */

export const METHOD_TYPE_LABEL: Record<number, string> = {
  0: "本地",
  1: "HTTP转发",
  2: "HTTP转发",
};

export const METHOD_TYPE_COLOR: Record<number, string> = {
  0: "text-sky-400 bg-sky-400/10 border-sky-400/20",
  1: "text-emerald-400 bg-emerald-400/10 border-emerald-400/20",
  2: "text-emerald-400 bg-emerald-400/10 border-emerald-400/20",
};

export const AUTH_TYPE_LABEL: Record<string, string> = {
  NONE: "无鉴权",
  SK_EXCHANGE: "sk 换 token",
  API_KEY: "固定 API Key",
  BEARER: "Bearer Token",
};

export const statusLabel = (v?: number | null) => (v === 1 ? "启用" : "禁用");
