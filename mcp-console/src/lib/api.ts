import type {
  ApprovalPolicy,
  ApprovalRequest,
  EndpointVO,
  NamespaceVO,
  DashboardOverview,
  DiscoveryPrompt,
  DiscoveryResource,
  DiscoveryServer,
  DiscoveryTool,
  HotToolRecommendation,
  LogStats,
  McpPrompt,
  McpResource,
  McpServer,
  McpTool,
  OpenApiImportPlan,
  OpenApiImportResult,
  OperationLog,
  PageResult,
  ServerHealth,
  ServiceSystem,
  Tenant,
  ToolBudget,
  ToolCallRecord,
  ToolExposureMode,
} from "@/types";
import { mockApi } from "@/mocks/api";
import { getToken, logout } from "@/lib/auth";

/**
 * 数据访问层。
 * - 默认走真实网关（VITE_USE_MOCK 未设置时为 false），避免生产误用 mock 数据。
 * - 需要离线演示时显式设置 VITE_USE_MOCK=true。
 */
const useMock = (import.meta.env.VITE_USE_MOCK ?? "false") === "true";

const BASE = () => import.meta.env.VITE_API_BASE_URL ?? "/api";

/** 单次请求超时（毫秒）：后端不可达时避免页面无限转圈 */
const REQUEST_TIMEOUT_MS = 15_000;

type AbortSignalWithAny = typeof AbortSignal & {
  any?: (signals: AbortSignal[]) => AbortSignal;
};

/** 合并多个取消信号；AbortSignal.any 不可用时退化为手动转发（兼容旧 Safari） */
function combineSignals(signals: AbortSignal[]): AbortSignal {
  const anyFn = (AbortSignal as AbortSignalWithAny).any;
  if (typeof anyFn === "function") {
    return anyFn.call(AbortSignal, signals);
  }
  const controller = new AbortController();
  for (const signal of signals) {
    if (signal.aborted) {
      controller.abort(signal.reason);
      break;
    }
    signal.addEventListener("abort", () => controller.abort(signal.reason), { once: true });
  }
  return controller.signal;
}

/** 登录跳转去重：并行请求同时 401 时只跳一次，并带上来源路由便于登录后回跳 */
let loginRedirecting = false;
function handleUnauthorized(message?: string): Error {
  logout();
  if (!loginRedirecting) {
    loginRedirecting = true;
    const from = `${window.location.pathname}${window.location.search}`;
    const target = from.startsWith("/login") ? "/login" : `/login?from=${encodeURIComponent(from)}`;
    window.location.replace(target);
  }
  return new Error(message ?? "未登录或登录已过期");
}

/** 把 fetch 异常翻译成用户可读的中文错误；调用方主动取消时保持原样交给 React Query */
function translateRequestError(
  error: unknown,
  externalSignal: AbortSignal | undefined,
  timeoutSignal: AbortSignal,
): unknown {
  if (externalSignal?.aborted) return error;
  if (timeoutSignal.aborted) {
    return new Error(`请求超时（${REQUEST_TIMEOUT_MS / 1000} 秒），请稍后重试`);
  }
  return new Error("网络异常，无法连接服务，请检查网络后重试");
}

/**
 * 统一 HTTP 请求：
 * 1. 自动携带登录 token（Authorization: Bearer）；
 * 2. 默认 15 秒超时；调用方可传 AbortSignal 取消（页面切换 / 筛选变化），两者自动合并；
 * 3. 后端统一返回 Result<T> 信封 {code, message, data}，自动解包 data；
 * 4. HTTP 401 或 code=401 视为未登录，清 token 并跳登录页（带去重与来源路由）；
 * 5. code != 0 视为业务错误并抛出 message。
 */
async function http<T>(path: string, init?: RequestInit): Promise<T> {
  const token = getToken();
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (token) {
    headers["Authorization"] = `Bearer ${token}`;
  }
  const externalSignal = init?.signal ?? undefined;
  const timeoutSignal = AbortSignal.timeout(REQUEST_TIMEOUT_MS);
  const signal = externalSignal ? combineSignals([externalSignal, timeoutSignal]) : timeoutSignal;
  let res: Response;
  try {
    res = await fetch(`${BASE()}${path}`, { ...init, headers, signal });
  } catch (error) {
    throw translateRequestError(error, externalSignal, timeoutSignal);
  }
  const text = await res.text();
  let body: unknown = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = text;
  }
  const envelope =
    body && typeof body === "object" && "code" in body
      ? (body as { code: number; message?: string; data?: unknown })
      : null;

  if (res.status === 401 || envelope?.code === 401) {
    throw handleUnauthorized(envelope?.message);
  }
  if (!res.ok) {
    throw new Error(envelope?.message ?? `请求失败 ${res.status}: ${res.statusText}`);
  }
  if (envelope) {
    if (envelope.code !== 0) {
      throw new Error(envelope.message ?? "请求失败");
    }
    return envelope.data as T;
  }
  return body as T;
}

export interface ToolCallQuery {
  pageNum: number;
  pageSize: number;
  startTime?: string;
  endTime?: string;
  success?: number;
  serverId?: number;
  userId?: string;
}

export interface AdminUserVO {
  id: number;
  username: string;
  name: string;
  role: string;
  status: number;
  tenantIds: number[];
  createTime?: string;
}

export interface LoginResult {
  token: string;
  user: AdminUserVO;
}

export interface AdminUserRequest {
  username?: string;
  password?: string;
  name?: string;
  role?: string;
  status?: number;
  tenantIds?: number[];
}

export interface ApprovalCreateInput {
  action: string;
  serverId?: number;
  toolId?: number;
  promptId?: number;
  resourceId?: number;
  serverIds?: number[];
  relId?: number;
  userId?: number;
  payload?: Record<string, unknown>;
  reason?: string;
}

export const api = {
  // ---- 概览 ----
  getOverview: (signal?: AbortSignal): Promise<DashboardOverview> =>
    useMock ? mockApi.getOverview() : http("/mcp/admin/overview", { signal }),

  // ---- Namespace / Endpoint 访问拓扑 ----
  listNamespaces: (signal?: AbortSignal): Promise<NamespaceVO[]> =>
    http("/mcp/admin/namespaces", { signal }),
  createNamespace: (input: { name: string; prefix: string; description?: string; status?: number }): Promise<NamespaceVO> =>
    http("/mcp/admin/namespaces", { method: "POST", body: JSON.stringify(input) }),
  updateNamespace: (id: number, input: { name: string; prefix?: string; description?: string; status?: number }): Promise<NamespaceVO> =>
    http(`/mcp/admin/namespaces/${id}`, { method: "PUT", body: JSON.stringify(input) }),
  deleteNamespace: (id: number): Promise<void> =>
    http(`/mcp/admin/namespaces/${id}`, { method: "DELETE" }),
  setNamespaceServers: (id: number, serverIds: number[]): Promise<void> =>
    http(`/mcp/admin/namespaces/${id}/servers`, { method: "PUT", body: JSON.stringify({ serverIds }) }),

  listEndpoints: (signal?: AbortSignal): Promise<EndpointVO[]> =>
    http("/mcp/admin/endpoints", { signal }),
  createEndpoint: (input: { endpointKey: string; name: string; description?: string; status?: number }): Promise<EndpointVO> =>
    http("/mcp/admin/endpoints", { method: "POST", body: JSON.stringify(input) }),
  updateEndpoint: (id: number, input: { endpointKey?: string; name: string; description?: string; status?: number }): Promise<EndpointVO> =>
    http(`/mcp/admin/endpoints/${id}`, { method: "PUT", body: JSON.stringify(input) }),
  deleteEndpoint: (id: number): Promise<void> =>
    http(`/mcp/admin/endpoints/${id}`, { method: "DELETE" }),
  setEndpointNamespaces: (id: number, namespaceIds: number[]): Promise<void> =>
    http(`/mcp/admin/endpoints/${id}/namespaces`, { method: "PUT", body: JSON.stringify({ namespaceIds }) }),

  // ---- 租户 tenant ----
  listTenants: (signal?: AbortSignal): Promise<Tenant[]> =>
    useMock ? mockApi.listTenants() : http("/mcp/admin/tenants", { signal }),
  createTenant: (input: Partial<Tenant>): Promise<Tenant> =>
    useMock
      ? mockApi.createTenant(input)
      : http("/mcp/admin/tenants", { method: "POST", body: JSON.stringify(input) }),
  updateTenant: (id: number, input: Partial<Tenant>): Promise<Tenant> =>
    useMock
      ? mockApi.updateTenant(id, input)
      : http(`/mcp/admin/tenants/${id}`, { method: "PUT", body: JSON.stringify(input) }),
  deleteTenant: (id: number): Promise<void> =>
    useMock ? mockApi.deleteTenant(id) : http(`/mcp/admin/tenants/${id}`, { method: "DELETE" }),

  // ---- MCP Server ----
  listServers: (signal?: AbortSignal): Promise<McpServer[]> =>
    useMock ? mockApi.listServers() : http("/mcp/admin/servers", { signal }),
  createServer: (input: Partial<McpServer>): Promise<McpServer> =>
    useMock
      ? mockApi.createServer(input)
      : http("/mcp/admin/servers", { method: "POST", body: JSON.stringify(input) }),
  updateServer: (id: number, input: Partial<McpServer>): Promise<McpServer> =>
    useMock
      ? mockApi.updateServer(id, input)
      : http(`/mcp/admin/servers/${id}`, { method: "PUT", body: JSON.stringify(input) }),
  deleteServer: (id: number): Promise<void> =>
    useMock ? mockApi.deleteServer(id) : http(`/mcp/admin/servers/${id}`, { method: "DELETE" }),
  refreshServers: async (): Promise<{ message: string }> => {
    if (useMock) return mockApi.refreshServers();
    const msg = await http<string>("/mcp/admin/refresh-server-api", { method: "POST" });
    return { message: msg };
  },
  /** 全部 Server 的工具预算（含告警标记） */
  listServerBudgets: (signal?: AbortSignal): Promise<ToolBudget[]> =>
    useMock ? mockApi.listServerBudgets() : http("/mcp/admin/servers/budgets", { signal }),
  /** 单个 Server 的工具预算 */
  getServerToolBudget: (serverId: number): Promise<ToolBudget> =>
    useMock
      ? mockApi.getServerToolBudget(serverId)
      : http(`/mcp/admin/servers/${serverId}/tool-budget`),
  /** 设置暴露模式、热工具与阈值 */
  updateToolExposure: (
    serverId: number,
    input: {
      toolExposureMode?: ToolExposureMode;
      hotToolCodes?: string[];
      toolWarnThreshold?: number | null;
      toolMaxThreshold?: number | null;
    },
  ): Promise<McpServer> =>
    useMock
      ? mockApi.updateToolExposure(serverId, input)
      : http(`/mcp/admin/servers/${serverId}/tool-exposure`, { method: "PUT", body: JSON.stringify(input) }),
  /** 按历史成功调用推荐热工具 */
  recommendHotTools: (serverId: number, days = 30, limit = 20): Promise<HotToolRecommendation> =>
    useMock
      ? mockApi.recommendHotTools(serverId, days, limit)
      : http(`/mcp/admin/servers/${serverId}/hot-tools/recommend?days=${days}&limit=${limit}`),

  // ---- Tool mcp_tool ----
  listTools: (signal?: AbortSignal): Promise<McpTool[]> =>
    useMock ? mockApi.listTools() : http("/mcp/admin/tools", { signal }),
  createTool: (input: Partial<McpTool>): Promise<McpTool> =>
    useMock
      ? mockApi.createTool(input)
      : http("/mcp/admin/tools", { method: "POST", body: JSON.stringify(input) }),
  updateTool: (id: number, input: Partial<McpTool>): Promise<McpTool> =>
    useMock
      ? mockApi.updateTool(id, input)
      : http(`/mcp/admin/tools/${id}`, { method: "PUT", body: JSON.stringify(input) }),
  deleteTool: (id: number): Promise<void> =>
    useMock ? mockApi.deleteTool(id) : http(`/mcp/admin/tools/${id}`, { method: "DELETE" }),
  bulkMoveTools: (toolIds: number[], targetServerId: number, grantToApps = false): Promise<{ moved: number; grantsCreated: number; toolCount: number; overWarn: boolean; warning?: string }> =>
    useMock
      ? mockApi.bulkMoveTools(toolIds, targetServerId, grantToApps)
      : http("/mcp/admin/tools/bulk-move", {
          method: "POST",
          body: JSON.stringify({ toolIds, targetServerId, grantToApps }),
        }),
  testToolConnection: (input: Partial<McpTool>): Promise<{ ok: boolean; status?: number; duration?: number; body?: string; error?: string; note?: string }> =>
    useMock
      ? mockApi.testToolConnection(input)
      : http("/mcp/admin/tools/test-connect", { method: "POST", body: JSON.stringify(input) }),
  fromRest: (input: { name?: string; code?: string; description?: string; method?: string; url?: string; bodyExample?: string; authType?: string }): Promise<{ serviceUrl: string; requestType: string; authType: string; resJson: string; paramMapping: string }> =>
    useMock
      ? mockApi.fromRest(input)
      : http("/mcp/admin/tools/from-rest", { method: "POST", body: JSON.stringify(input) }),
  previewOpenApiImport: (input: { serverId: number; document: string; baseUrl?: string }): Promise<OpenApiImportPlan> =>
    useMock
      ? mockApi.previewOpenApiImport(input)
      : http("/mcp/admin/tools/openapi/preview", { method: "POST", body: JSON.stringify(input) }),
  importOpenApiTools: (input: {
    serverId: number;
    document: string;
    baseUrl?: string;
    conflictPolicy: "SKIP" | "OVERWRITE";
    codes?: string[];
  }): Promise<OpenApiImportResult> =>
    useMock
      ? mockApi.importOpenApiTools(input)
      : http("/mcp/admin/tools/openapi/import", { method: "POST", body: JSON.stringify(input) }),

  // ---- Prompt ----
  listPrompts: (signal?: AbortSignal): Promise<McpPrompt[]> =>
    useMock ? mockApi.listPrompts() : http("/mcp/admin/prompts", { signal }),
  createPrompt: (input: Partial<McpPrompt>): Promise<McpPrompt> =>
    useMock
      ? mockApi.createPrompt(input)
      : http("/mcp/admin/prompts", { method: "POST", body: JSON.stringify(input) }),
  updatePrompt: (id: number, input: Partial<McpPrompt>): Promise<McpPrompt> =>
    useMock
      ? mockApi.updatePrompt(id, input)
      : http(`/mcp/admin/prompts/${id}`, { method: "PUT", body: JSON.stringify(input) }),
  deletePrompt: (id: number): Promise<void> =>
    useMock ? mockApi.deletePrompt(id) : http(`/mcp/admin/prompts/${id}`, { method: "DELETE" }),

  // ---- Resource ----
  listResources: (signal?: AbortSignal): Promise<McpResource[]> =>
    useMock ? mockApi.listResources() : http("/mcp/admin/resources", { signal }),
  createResource: (input: Partial<McpResource>): Promise<McpResource> =>
    useMock
      ? mockApi.createResource(input)
      : http("/mcp/admin/resources", { method: "POST", body: JSON.stringify(input) }),
  updateResource: (id: number, input: Partial<McpResource>): Promise<McpResource> =>
    useMock
      ? mockApi.updateResource(id, input)
      : http(`/mcp/admin/resources/${id}`, { method: "PUT", body: JSON.stringify(input) }),
  deleteResource: (id: number): Promise<void> =>
    useMock ? mockApi.deleteResource(id) : http(`/mcp/admin/resources/${id}`, { method: "DELETE" }),

  // ---- 操作审计日志 ----
  listOperationLogs: (params: { pageNum: number; pageSize: number; action?: string; username?: string }, signal?: AbortSignal): Promise<PageResult<OperationLog>> =>
    useMock
      ? mockApi.listOperationLogs(params)
      : http(`/mcp/admin/operation-logs?${new URLSearchParams({ pageNum: String(params.pageNum), pageSize: String(params.pageSize), ...(params.action ? { action: params.action } : {}), ...(params.username ? { username: params.username } : {}) })}`, { signal }),

  // ---- 审批中心 ----
  getApprovalPolicy: (signal?: AbortSignal): Promise<ApprovalPolicy> =>
    useMock ? mockApi.getApprovalPolicy() : http("/mcp/admin/approvals/policy", { signal }),
  listApprovals: (params: {
    pageNum: number;
    pageSize: number;
    status?: string;
    scope?: "all" | "pending" | "mine";
  }, signal?: AbortSignal): Promise<PageResult<ApprovalRequest>> =>
    useMock
      ? mockApi.listApprovals(params)
      : http(
          `/mcp/admin/approvals?${new URLSearchParams({
            pageNum: String(params.pageNum),
            pageSize: String(params.pageSize),
            ...(params.status ? { status: params.status } : {}),
            ...(params.scope ? { scope: params.scope } : {}),
          })}`,
          { signal },
        ),
  createApproval: (input: ApprovalCreateInput): Promise<ApprovalRequest> =>
    useMock
      ? mockApi.createApproval(input)
      : http("/mcp/admin/approvals", { method: "POST", body: JSON.stringify(input) }),
  getApproval: (id: number, signal?: AbortSignal): Promise<ApprovalRequest> =>
    useMock ? mockApi.getApproval(id) : http(`/mcp/admin/approvals/${id}`, { signal }),
  approveApproval: (id: number, comment?: string): Promise<ApprovalRequest> =>
    useMock
      ? mockApi.approveApproval(id, comment)
      : http(`/mcp/admin/approvals/${id}/approve`, { method: "POST", body: JSON.stringify({ comment }) }),
  rejectApproval: (id: number, comment?: string): Promise<ApprovalRequest> =>
    useMock
      ? mockApi.rejectApproval(id, comment)
      : http(`/mcp/admin/approvals/${id}/reject`, { method: "POST", body: JSON.stringify({ comment }) }),
  cancelApproval: (id: number, comment?: string): Promise<ApprovalRequest> =>
    useMock
      ? mockApi.cancelApproval(id, comment)
      : http(`/mcp/admin/approvals/${id}/cancel`, { method: "POST", body: JSON.stringify({ comment }) }),

  // ---- 系统服务 ----
  listServiceSystems: (signal?: AbortSignal): Promise<ServiceSystem[]> =>
    useMock ? mockApi.listServiceSystems() : http("/mcp/admin/service-systems", { signal }),
  updateServiceSystem: (id: number, input: Partial<ServiceSystem>): Promise<ServiceSystem> =>
    useMock
      ? mockApi.updateServiceSystem(id, input)
      : http(`/mcp/admin/service-systems/${id}`, { method: "PUT", body: JSON.stringify(input) }),

  // ---- 日志 ----
  listToolCallRecords: (params: ToolCallQuery, signal?: AbortSignal): Promise<PageResult<ToolCallRecord>> => {
    if (useMock) return mockApi.listToolCallRecords(params);
    const qs = new URLSearchParams({ pageNum: String(params.pageNum), pageSize: String(params.pageSize) });
    if (params.startTime) qs.set("startTime", params.startTime);
    if (params.endTime) qs.set("endTime", params.endTime);
    if (params.success !== undefined) qs.set("success", String(params.success));
    if (params.serverId !== undefined) qs.set("serverId", String(params.serverId));
    if (params.userId) qs.set("userId", params.userId);
    return http(`/mcp/admin/tool-call-records?${qs.toString()}`, { signal });
  },
  getLogStats: (signal?: AbortSignal): Promise<LogStats> =>
    useMock ? mockApi.getLogStats() : http("/mcp/admin/tool-call-stats", { signal }),
  exportLogs: async (params: {
    startTime?: string;
    endTime?: string;
    success?: number;
    serverId?: number;
    userId?: string;
  }, signal?: AbortSignal): Promise<{ fileName: string }> => {
    if (useMock) return mockApi.exportLogs(params);
    const qs = new URLSearchParams();
    if (params.startTime) qs.set("startTime", params.startTime);
    if (params.endTime) qs.set("endTime", params.endTime);
    if (params.success !== undefined) qs.set("success", String(params.success));
    if (params.serverId !== undefined) qs.set("serverId", String(params.serverId));
    if (params.userId) qs.set("userId", params.userId);
    // 必须带 Authorization 头：改用 fetch + blob，避免 window.open 丢失登录态
    const token = getToken();
    const timeoutSignal = AbortSignal.timeout(REQUEST_TIMEOUT_MS);
    const mergedSignal = signal ? combineSignals([signal, timeoutSignal]) : timeoutSignal;
    let res: Response;
    try {
      res = await fetch(`${BASE()}/mcp/log/export?${qs.toString()}`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
        signal: mergedSignal,
      });
    } catch (error) {
      throw translateRequestError(error, signal, timeoutSignal);
    }
    if (!res.ok) {
      let message = `导出失败 ${res.status}`;
      try {
        const body = await res.json();
        if (body && body.message) message = body.message;
      } catch {
        // 非 JSON 响应
      }
      if (res.status === 401) {
        throw handleUnauthorized(message);
      }
      throw new Error(message);
    }
    const blob = await res.blob();
    const fileName = "Tool调用记录.xlsx";
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
    return { fileName };
  },

  // ---- 登录与用户管理（仅真实模式） ----
  login: (username: string, password: string): Promise<LoginResult> =>
    http("/mcp/admin/auth/login", { method: "POST", body: JSON.stringify({ username, password }) }),
  listUsers: (signal?: AbortSignal): Promise<AdminUserVO[]> =>
    http("/mcp/admin/users", { signal }),
  createUser: (input: AdminUserRequest): Promise<AdminUserVO> =>
    http("/mcp/admin/users", { method: "POST", body: JSON.stringify(input) }),
  updateUser: (id: number, input: AdminUserRequest): Promise<AdminUserVO> =>
    http(`/mcp/admin/users/${id}`, { method: "PUT", body: JSON.stringify(input) }),
  deleteUser: (id: number): Promise<void> =>
    http(`/mcp/admin/users/${id}`, { method: "DELETE" }),
  changePassword: (oldPassword: string, newPassword: string): Promise<void> =>
    http("/mcp/admin/users/change-password", { method: "POST", body: JSON.stringify({ oldPassword, newPassword }) }),

  // ---- 发现预览（管理台以「应用视角」预览，admin JWT 鉴权） ----
  adminDiscoveryServers: (signal?: AbortSignal): Promise<DiscoveryServer[]> =>
    http("/mcp/admin/discovery/servers", { signal }),
  adminDiscoveryTools: (params?: { q?: string; tag?: string; serverId?: number }, signal?: AbortSignal): Promise<DiscoveryTool[]> => {
    const query = new URLSearchParams();
    if (params?.q) query.set("q", params.q);
    if (params?.tag) query.set("tag", params.tag);
    if (params?.serverId) query.set("serverId", String(params.serverId));
    const qs = query.toString();
    return http(`/mcp/admin/discovery/tools${qs ? "?" + qs : ""}`, { signal });
  },
  adminDiscoveryPrompts: (params?: { q?: string; serverId?: number }, signal?: AbortSignal): Promise<DiscoveryPrompt[]> => {
    const query = new URLSearchParams();
    if (params?.q) query.set("q", params.q);
    if (params?.serverId) query.set("serverId", String(params.serverId));
    const qs = query.toString();
    return http(`/mcp/admin/discovery/prompts${qs ? "?" + qs : ""}`, { signal });
  },
  adminDiscoveryResources: (params?: { q?: string; serverId?: number }, signal?: AbortSignal): Promise<DiscoveryResource[]> => {
    const query = new URLSearchParams();
    if (params?.q) query.set("q", params.q);
    if (params?.serverId) query.set("serverId", String(params.serverId));
    const qs = query.toString();
    return http(`/mcp/admin/discovery/resources${qs ? "?" + qs : ""}`, { signal });
  },

  // ---- 健康检测 ----
  listServerHealth: (signal?: AbortSignal): Promise<ServerHealth[]> =>
    http("/mcp/admin/health/servers", { signal }),
  probeServer: (id: number): Promise<ServerHealth> =>
    http(`/mcp/admin/health/servers/${id}/probe`, { method: "POST" }),
};
