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
  ToolAccessMode,
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

/**
 * 统一 HTTP 请求：
 * 1. 自动携带登录 token（Authorization: Bearer）；
 * 2. 后端统一返回 Result<T> 信封 {code, message, data}，自动解包 data；
 * 3. HTTP 401 或 code=401 视为未登录，清 token 并跳登录页；
 * 4. code != 0 视为业务错误并抛出 message。
 */
async function http<T>(path: string, init?: RequestInit): Promise<T> {
  const token = getToken();
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (token) {
    headers["Authorization"] = `Bearer ${token}`;
  }
  const res = await fetch(`${BASE()}${path}`, {
    ...init,
    headers,
  });
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
    logout();
    if (window.location.pathname !== "/login") {
      window.location.href = "/login";
    }
    throw new Error(envelope?.message ?? "未登录或登录已过期");
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
  appId?: string;
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
  appId?: string;
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
  getOverview: (): Promise<DashboardOverview> =>
    useMock ? mockApi.getOverview() : http("/mcp/admin/overview"),

  // ---- Namespace / Endpoint 访问拓扑 ----
  listNamespaces: (): Promise<NamespaceVO[]> => http("/mcp/admin/namespaces"),
  createNamespace: (input: { name: string; prefix: string; description?: string; status?: number }): Promise<NamespaceVO> =>
    http("/mcp/admin/namespaces", { method: "POST", body: JSON.stringify(input) }),
  updateNamespace: (id: number, input: { name: string; prefix?: string; description?: string; status?: number }): Promise<NamespaceVO> =>
    http(`/mcp/admin/namespaces/${id}`, { method: "PUT", body: JSON.stringify(input) }),
  deleteNamespace: (id: number): Promise<void> =>
    http(`/mcp/admin/namespaces/${id}`, { method: "DELETE" }),
  setNamespaceServers: (id: number, serverIds: number[]): Promise<void> =>
    http(`/mcp/admin/namespaces/${id}/servers`, { method: "PUT", body: JSON.stringify({ serverIds }) }),

  listEndpoints: (): Promise<EndpointVO[]> => http("/mcp/admin/endpoints"),
  createEndpoint: (input: { endpointKey: string; name: string; description?: string; status?: number }): Promise<EndpointVO> =>
    http("/mcp/admin/endpoints", { method: "POST", body: JSON.stringify(input) }),
  updateEndpoint: (id: number, input: { endpointKey?: string; name: string; description?: string; status?: number }): Promise<EndpointVO> =>
    http(`/mcp/admin/endpoints/${id}`, { method: "PUT", body: JSON.stringify(input) }),
  deleteEndpoint: (id: number): Promise<void> =>
    http(`/mcp/admin/endpoints/${id}`, { method: "DELETE" }),
  setEndpointNamespaces: (id: number, namespaceIds: number[]): Promise<void> =>
    http(`/mcp/admin/endpoints/${id}/namespaces`, { method: "PUT", body: JSON.stringify({ namespaceIds }) }),

  // ---- 租户 tenant ----
  listTenants: (): Promise<Tenant[]> =>
    useMock ? mockApi.listTenants() : http("/mcp/admin/tenants"),
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
  listServers: (): Promise<McpServer[]> =>
    useMock ? mockApi.listServers() : http("/mcp/admin/servers"),
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
  listServerBudgets: (): Promise<ToolBudget[]> =>
    useMock ? mockApi.listServerBudgets() : http("/mcp/admin/servers/budgets"),
  /** 单个 Server 的工具预算 */
  getServerToolBudget: (serverId: number): Promise<ToolBudget> =>
    useMock
      ? mockApi.getServerToolBudget(serverId)
      : http(`/mcp/admin/servers/${serverId}/tool-budget`),
  /** 设置访问/暴露模式、热工具与阈值 */
  updateToolExposure: (
    serverId: number,
    input: {
      toolAccessMode?: ToolAccessMode;
      toolExposureMode?: ToolExposureMode;
      promptAccessMode?: "WHITELIST" | "ALL";
      resourceAccessMode?: "WHITELIST" | "ALL";
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
  listTools: (): Promise<McpTool[]> =>
    useMock ? mockApi.listTools() : http("/mcp/admin/tools"),
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
  listPrompts: (): Promise<McpPrompt[]> =>
    useMock ? mockApi.listPrompts() : http("/mcp/admin/prompts"),
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
  listResources: (): Promise<McpResource[]> =>
    useMock ? mockApi.listResources() : http("/mcp/admin/resources"),
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
  listOperationLogs: (params: { pageNum: number; pageSize: number; action?: string; username?: string }): Promise<PageResult<OperationLog>> =>
    useMock
      ? mockApi.listOperationLogs(params)
      : http(`/mcp/admin/operation-logs?${new URLSearchParams({ pageNum: String(params.pageNum), pageSize: String(params.pageSize), ...(params.action ? { action: params.action } : {}), ...(params.username ? { username: params.username } : {}) })}`),

  // ---- 审批中心 ----
  getApprovalPolicy: (): Promise<ApprovalPolicy> =>
    useMock ? mockApi.getApprovalPolicy() : http("/mcp/admin/approvals/policy"),
  listApprovals: (params: {
    pageNum: number;
    pageSize: number;
    status?: string;
    scope?: "all" | "pending" | "mine";
  }): Promise<PageResult<ApprovalRequest>> =>
    useMock
      ? mockApi.listApprovals(params)
      : http(
          `/mcp/admin/approvals?${new URLSearchParams({
            pageNum: String(params.pageNum),
            pageSize: String(params.pageSize),
            ...(params.status ? { status: params.status } : {}),
            ...(params.scope ? { scope: params.scope } : {}),
          })}`,
        ),
  createApproval: (input: ApprovalCreateInput): Promise<ApprovalRequest> =>
    useMock
      ? mockApi.createApproval(input)
      : http("/mcp/admin/approvals", { method: "POST", body: JSON.stringify(input) }),
  getApproval: (id: number): Promise<ApprovalRequest> =>
    useMock ? mockApi.getApproval(id) : http(`/mcp/admin/approvals/${id}`),
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
  revealApprovalSecret: (id: number): Promise<{ secret: string }> =>
    useMock ? mockApi.revealApprovalSecret(id) : http(`/mcp/admin/approvals/${id}/secret`),

  // ---- 系统服务 ----
  listServiceSystems: (): Promise<ServiceSystem[]> =>
    useMock ? mockApi.listServiceSystems() : http("/mcp/admin/service-systems"),
  updateServiceSystem: (id: number, input: Partial<ServiceSystem>): Promise<ServiceSystem> =>
    useMock
      ? mockApi.updateServiceSystem(id, input)
      : http(`/mcp/admin/service-systems/${id}`, { method: "PUT", body: JSON.stringify(input) }),

  // ---- 日志 ----
  listToolCallRecords: (params: ToolCallQuery): Promise<PageResult<ToolCallRecord>> => {
    if (useMock) return mockApi.listToolCallRecords(params);
    const qs = new URLSearchParams({ pageNum: String(params.pageNum), pageSize: String(params.pageSize) });
    if (params.startTime) qs.set("startTime", params.startTime);
    if (params.endTime) qs.set("endTime", params.endTime);
    if (params.success !== undefined) qs.set("success", String(params.success));
    if (params.serverId !== undefined) qs.set("serverId", String(params.serverId));
    if (params.appId) qs.set("appId", params.appId);
    if (params.userId) qs.set("userId", params.userId);
    return http(`/mcp/admin/tool-call-records?${qs.toString()}`);
  },
  getLogStats: (): Promise<LogStats> =>
    useMock ? mockApi.getLogStats() : http("/mcp/admin/tool-call-stats"),
  exportLogs: async (params: {
    startTime?: string;
    endTime?: string;
    success?: number;
    serverId?: number;
    appId?: string;
    userId?: string;
  }): Promise<{ fileName: string }> => {
    if (useMock) return mockApi.exportLogs(params);
    const qs = new URLSearchParams();
    if (params.startTime) qs.set("startTime", params.startTime);
    if (params.endTime) qs.set("endTime", params.endTime);
    if (params.success !== undefined) qs.set("success", String(params.success));
    if (params.serverId !== undefined) qs.set("serverId", String(params.serverId));
    if (params.appId) qs.set("appId", params.appId);
    if (params.userId) qs.set("userId", params.userId);
    // 必须带 Authorization 头：改用 fetch + blob，避免 window.open 丢失登录态
    const token = getToken();
    const res = await fetch(`${BASE()}/mcp/log/export?${qs.toString()}`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (!res.ok) {
      let message = `导出失败 ${res.status}`;
      try {
        const body = await res.json();
        if (body && body.message) message = body.message;
      } catch {
        // 非 JSON 响应
      }
      if (res.status === 401) {
        logout();
        window.location.href = "/login";
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
  listUsers: (): Promise<AdminUserVO[]> => http("/mcp/admin/users"),
  createUser: (input: AdminUserRequest): Promise<AdminUserVO> =>
    http("/mcp/admin/users", { method: "POST", body: JSON.stringify(input) }),
  updateUser: (id: number, input: AdminUserRequest): Promise<AdminUserVO> =>
    http(`/mcp/admin/users/${id}`, { method: "PUT", body: JSON.stringify(input) }),
  deleteUser: (id: number): Promise<void> =>
    http(`/mcp/admin/users/${id}`, { method: "DELETE" }),
  changePassword: (oldPassword: string, newPassword: string): Promise<void> =>
    http("/mcp/admin/users/change-password", { method: "POST", body: JSON.stringify({ oldPassword, newPassword }) }),

  // ---- 发现预览（管理台以「应用视角」预览，admin JWT 鉴权） ----
  adminDiscoveryServers: (appId?: string): Promise<DiscoveryServer[]> =>
    http(`/mcp/admin/discovery/servers${appId ? `?appId=${encodeURIComponent(appId)}` : ""}`),
  adminDiscoveryTools: (params?: { appId?: string; q?: string; tag?: string; serverId?: number }): Promise<DiscoveryTool[]> => {
    const query = new URLSearchParams();
    if (params?.appId) query.set("appId", params.appId);
    if (params?.q) query.set("q", params.q);
    if (params?.tag) query.set("tag", params.tag);
    if (params?.serverId) query.set("serverId", String(params.serverId));
    const qs = query.toString();
    return http(`/mcp/admin/discovery/tools${qs ? "?" + qs : ""}`);
  },
  adminDiscoveryPrompts: (params?: { appId?: string; q?: string; serverId?: number }): Promise<DiscoveryPrompt[]> => {
    const query = new URLSearchParams();
    if (params?.appId) query.set("appId", params.appId);
    if (params?.q) query.set("q", params.q);
    if (params?.serverId) query.set("serverId", String(params.serverId));
    const qs = query.toString();
    return http(`/mcp/admin/discovery/prompts${qs ? "?" + qs : ""}`);
  },
  adminDiscoveryResources: (params?: { appId?: string; q?: string; serverId?: number }): Promise<DiscoveryResource[]> => {
    const query = new URLSearchParams();
    if (params?.appId) query.set("appId", params.appId);
    if (params?.q) query.set("q", params.q);
    if (params?.serverId) query.set("serverId", String(params.serverId));
    const qs = query.toString();
    return http(`/mcp/admin/discovery/resources${qs ? "?" + qs : ""}`);
  },

  // ---- 健康检测 ----
  listServerHealth: (): Promise<ServerHealth[]> => http("/mcp/admin/health/servers"),
  probeServer: (id: number): Promise<ServerHealth> =>
    http(`/mcp/admin/health/servers/${id}/probe`, { method: "POST" }),
};
