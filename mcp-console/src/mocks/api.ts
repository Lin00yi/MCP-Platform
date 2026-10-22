import type {
  ApprovalPolicy,
  ApprovalRequest,
  AppServerRel,
  AppToolRel,
  CallTypeDistItem,
  DashboardOverview,
  DashboardStats,
  EnnAppInfo,
  HotToolRecommendation,
  LogStats,
  McpPrompt,
  McpResource,
  McpServer,
  McpTool,
  OpenApiImportPlan,
  OpenApiImportResult,
  ToolDistItem,
  ToolBudget,
  PageResult,
  ServiceSystem,
  Tenant,
  ToolCallRecord,
  TrendPoint,
} from "@/types";
import {
  appServerRels,
  appToolRels,
  apps,
  prompts,
  resources,
  servers,
  serviceSystems,
  tenants,
  toolCallRecords,
  tools,
} from "./data";

// 模拟网络延迟
const delay = (ms = 260) => new Promise((r) => setTimeout(r, ms));

// 内存态数据（模拟数据库）
const db = {
  tenants: [...tenants],
  servers: [...servers],
  tools: [...tools],
  prompts: [...prompts],
  resources: [...resources],
  apps: [...apps],
  appServerRels: [...appServerRels],
  appToolRels: [...appToolRels],
  serviceSystems: [...serviceSystems],
  toolCallRecords: [...toolCallRecords],
  operationLogs: [] as import("@/types").OperationLog[],
  approvals: [] as ApprovalRequest[],
};

const nextId = (arr: { id: number }[]) =>
  arr.reduce((m, x) => Math.max(m, x.id), 0) + 1;

// ============ 概览统计 ============

function buildOverview(): DashboardOverview {
  const totalRequests = 820;
  const toolCalls = 540;
  const successCount = Math.round(toolCalls * 0.94);
  const failCount = toolCalls - successCount;
  const avgDuration = 620;

  const trend: TrendPoint[] = Array.from({ length: 14 }, (_, i) => {
    const date = new Date(Date.now() - (13 - i) * 86400000);
    const requests = 300 + Math.round(Math.random() * 500) + i * 18;
    const failed = Math.round(requests * (0.03 + Math.random() * 0.05));
    return {
      time: `${date.getMonth() + 1}/${date.getDate()}`,
      requests,
      success: requests - failed,
      failed,
      avgDuration: Math.round(420 + Math.random() * 300),
    };
  });

  const toolDist: ToolDistItem[] = [
    { name: "select-policy-region-by-regin", value: 340 },
    { name: "schedule-push-clue", value: 180 },
    { name: "customer-category", value: 150 },
    { name: "get-enable-zones", value: 120 },
    { name: "其它", value: 90 },
  ];

  const callTypeDist: CallTypeDistItem[] = [
    { name: "LOCAL", value: 380 },
    { name: "EXTERNAL", value: 620 },
  ];

  const stats: DashboardStats = {
    totalRequests,
    toolCalls,
    successCount,
    failCount,
    avgDuration,
    tenantCount: db.tenants.length,
    serverCount: db.servers.length,
    toolCount: db.tools.length,
    appCount: db.apps.length,
    activeTenantCount: db.tenants.filter((t) => t.status === 1).length,
    todayRequests: 1204,
    todayRequestsDelta: 12.6,
  };

  const budgets = db.servers.map(buildBudget);
  stats.overToolBudgetCount = budgets.filter((b) => b.overWarn).length;
  const toolBudgetWarnings = budgets
    .filter((b) => b.overWarn)
    .map((b) => ({
      serverId: b.serverId,
      serverName: b.serverName,
      path: db.servers.find((s) => s.id === b.serverId)?.path ?? "",
      toolCount: b.visibleTools,
      warnThreshold: b.warnThreshold,
      overMax: b.overMax,
      exposureMode: b.exposureMode,
    }));
  return { stats, trend, toolDist, callTypeDist, toolBudgetWarnings };
}

function parseHotCodes(server: McpServer): string[] {
  try {
    const parsed = JSON.parse(server.hotToolCodes ?? "[]");
    return Array.isArray(parsed) ? parsed.map(String) : [];
  } catch {
    return [];
  }
}

function buildBudget(server: McpServer): ToolBudget {
  const all = db.tools.filter((t) => t.serverId === server.id && t.status === 1);
  const warnThreshold = server.toolWarnThreshold ?? 100;
  const maxThreshold = server.toolMaxThreshold ?? 500;
  return {
    serverId: server.id,
    serverName: server.name,
    accessMode: server.toolAccessMode ?? "WHITELIST",
    exposureMode: server.toolExposureMode ?? "DIRECT",
    totalTools: all.length,
    visibleTools: all.length,
    hotTools: parseHotCodes(server).length,
    warnThreshold,
    maxThreshold,
    overWarn: all.length > warnThreshold,
    overMax: all.length > maxThreshold,
    estimatedTokens: all.reduce((sum, t) => sum + Math.round((t.resJson?.length ?? 0) / 4), 0),
  };
}

// ============ Mock API 实现（与真实网关接口对齐） ============

export const mockApi = {
  async getOverview(): Promise<DashboardOverview> {
    await delay(320);
    return buildOverview();
  },

  // ---- 租户 ----
  async listTenants(): Promise<Tenant[]> {
    await delay();
    return [...db.tenants].sort((a, b) => b.id - a.id);
  },
  async createTenant(input: Partial<Tenant>): Promise<Tenant> {
    await delay();
    const record: Tenant = {
      id: nextId(db.tenants),
      name: input.name ?? "",
      status: input.status ?? 1,
      createTime: new Date().toISOString(),
      updateTime: new Date().toISOString(),
    };
    db.tenants.push(record);
    return record;
  },
  async updateTenant(id: number, input: Partial<Tenant>): Promise<Tenant> {
    await delay();
    const idx = db.tenants.findIndex((t) => t.id === id);
    if (idx === -1) throw new Error("租户不存在");
    db.tenants[idx] = { ...db.tenants[idx], ...input, id, updateTime: new Date().toISOString() };
    return db.tenants[idx];
  },
  async deleteTenant(id: number): Promise<void> {
    await delay();
    db.tenants = db.tenants.filter((t) => t.id !== id);
  },

  // ---- MCP Server ----
  async listServers(): Promise<McpServer[]> {
    await delay();
    return [...db.servers].sort((a, b) => b.id - a.id);
  },
  async createServer(input: Partial<McpServer>): Promise<McpServer> {
    await delay();
    const record: McpServer = {
      id: nextId(db.servers),
      tenantId: input.tenantId ?? 1,
      name: input.name ?? "",
      path: input.path ?? "",
      status: input.status ?? 1,
      capabilitiesJson: input.capabilitiesJson ?? "{}",
      createTime: new Date().toISOString(),
      updateTime: new Date().toISOString(),
    };
    db.servers.push(record);
    return record;
  },
  async updateServer(id: number, input: Partial<McpServer>): Promise<McpServer> {
    await delay();
    const idx = db.servers.findIndex((s) => s.id === id);
    if (idx === -1) throw new Error("Server 不存在");
    db.servers[idx] = { ...db.servers[idx], ...input, id, updateTime: new Date().toISOString() };
    return db.servers[idx];
  },
  async deleteServer(id: number): Promise<void> {
    await delay();
    db.servers = db.servers.filter((s) => s.id !== id);
  },
  async refreshServers(): Promise<{ message: string }> {
    await delay(600);
    const count = db.servers.filter((s) => s.status === 1).length;
    return { message: `刷新成功：共读取${db.servers.length}条配置，成功注册${count}个动态 McpController` };
  },
  async listServerBudgets(): Promise<ToolBudget[]> {
    await delay();
    return db.servers.map(buildBudget);
  },
  async getServerToolBudget(serverId: number, appId?: string): Promise<ToolBudget> {
    await delay();
    const server = db.servers.find((s) => s.id === serverId);
    if (!server) throw new Error("Server 不存在");
    void appId;
    return buildBudget(server);
  },
  async updateToolExposure(
    serverId: number,
    input: {
      toolAccessMode?: McpServer["toolAccessMode"];
      toolExposureMode?: McpServer["toolExposureMode"];
      promptAccessMode?: McpServer["promptAccessMode"];
      resourceAccessMode?: McpServer["resourceAccessMode"];
      hotToolCodes?: string[];
      toolWarnThreshold?: number | null;
      toolMaxThreshold?: number | null;
      backfillExistingApps?: boolean;
    },
  ): Promise<McpServer> {
    await delay();
    const idx = db.servers.findIndex((s) => s.id === serverId);
    if (idx === -1) throw new Error("Server 不存在");
    db.servers[idx] = {
      ...db.servers[idx],
      toolAccessMode: input.toolAccessMode ?? db.servers[idx].toolAccessMode,
      toolExposureMode: input.toolExposureMode ?? db.servers[idx].toolExposureMode,
      promptAccessMode: input.promptAccessMode ?? db.servers[idx].promptAccessMode,
      resourceAccessMode: input.resourceAccessMode ?? db.servers[idx].resourceAccessMode,
      hotToolCodes: input.hotToolCodes ? JSON.stringify(input.hotToolCodes) : db.servers[idx].hotToolCodes,
      toolWarnThreshold: input.toolWarnThreshold ?? undefined,
      toolMaxThreshold: input.toolMaxThreshold ?? undefined,
      updateTime: new Date().toISOString(),
    };
    return db.servers[idx];
  },
  async getExposureImpact(serverId: number, targetAccessMode: McpServer["toolAccessMode"]) {
    await delay();
    const server = db.servers.find((s) => s.id === serverId);
    if (!server) throw new Error("Server 不存在");
    const tools = db.tools.filter((t) => t.serverId === serverId && t.status === 1);
    const rels = db.appServerRels.filter((r) => r.serverId === serverId && (r.status == null || r.status === 1));
    const apps = rels.map((r) => {
      const grants = db.appToolRels.filter(
        (t) => t.appId === r.appId && tools.some((x) => x.id === t.toolId),
      ).length;
      return {
        appId: r.appId,
        serverGranted: true,
        toolGrants: grants,
        willLoseTools: targetAccessMode === "WHITELIST" && grants === 0 && tools.length > 0,
      };
    });
    return {
      serverId,
      serverName: server.name,
      currentAccessMode: server.toolAccessMode ?? "WHITELIST",
      targetAccessMode: targetAccessMode ?? "WHITELIST",
      toolCount: tools.length,
      affectedApps: apps.filter((a) => a.willLoseTools).length,
      apps,
    };
  },
  async backfillServerToolGrants(serverId: number, appIds?: string[]) {
    await delay();
    const tools = db.tools.filter((t) => t.serverId === serverId && t.status === 1);
    const rels = db.appServerRels.filter(
      (r) => r.serverId === serverId && (!appIds || appIds.length === 0 || appIds.includes(r.appId)),
    );
    let created = 0;
    for (const rel of rels) {
      for (const tool of tools) {
        if (db.appToolRels.some((t) => t.appId === rel.appId && t.toolId === tool.id)) continue;
        db.appToolRels.push({ id: nextId(db.appToolRels), appId: rel.appId, toolId: tool.id, status: 1 });
        created++;
      }
    }
    return { grantsCreated: created, toolCount: tools.length };
  },
  async recommendHotTools(serverId: number, days = 30, limit = 20): Promise<HotToolRecommendation> {
    await delay(400);
    const server = db.servers.find((s) => s.id === serverId);
    if (!server) throw new Error("Server 不存在");
    const rows = db.tools
      .filter((t) => t.serverId === serverId && t.status === 1)
      .slice(0, limit)
      .map((t, i) => ({ code: t.code, name: t.name, calls: 120 - i * 7, score: 120 - i * 7 }));
    return {
      serverId,
      windowDays: days,
      totalCalls: rows.reduce((sum, r) => sum + r.calls, 0),
      hotToolCodes: rows.map((r) => r.code),
      tools: rows,
    };
  },

  // ---- Tool ----
  async listTools(): Promise<McpTool[]> {
    await delay();
    return [...db.tools].sort((a, b) => b.id - a.id);
  },
  async createTool(input: Partial<McpTool>): Promise<McpTool> {
    await delay();
    const record: McpTool = {
      id: nextId(db.tools),
      serverId: input.serverId ?? 1,
      name: input.name ?? "",
      code: input.code ?? "",
      type: (input.type ?? 0) as McpTool["type"],
      status: input.status ?? 1,
      serviceUrl: input.serviceUrl ?? "",
      requestType: input.requestType ?? "GET",
      needToken: input.needToken ?? 0,
      bodyType: input.bodyType ?? 0,
      resJson: input.resJson ?? "{}",
      tags: input.tags ?? "",
      owner: input.owner ?? "",
      version: input.version ?? "",
      createTime: new Date().toISOString(),
      updateTime: new Date().toISOString(),
    };
    db.tools.push(record);
    return record;
  },
  async updateTool(id: number, input: Partial<McpTool>): Promise<McpTool> {
    await delay();
    const idx = db.tools.findIndex((m) => m.id === id);
    if (idx === -1) throw new Error("Tool 不存在");
    db.tools[idx] = { ...db.tools[idx], ...input, id, updateTime: new Date().toISOString() };
    return db.tools[idx];
  },
  async bulkMoveTools(toolIds: number[], targetServerId: number, grantToApps = false) {
    await delay();
    const target = db.servers.find((s) => s.id === targetServerId);
    if (!target) throw new Error("目标 Server 不存在");
    const moving = db.tools.filter((t) => toolIds.includes(t.id));
    if (moving.length === 0) throw new Error("没有需要迁移的工具");
    const sourceIds = Array.from(new Set(moving.map((t) => t.serverId)));
    const apps = db.appServerRels.filter((r) => sourceIds.includes(r.serverId)).map((r) => r.appId);
    for (const tool of moving) {
      tool.serverId = targetServerId;
      db.appToolRels = db.appToolRels.filter((r) => r.toolId !== tool.id);
    }
    let grantsCreated = 0;
    if (grantToApps) {
      for (const appId of Array.from(new Set(apps))) {
        if (!db.appServerRels.some((r) => r.appId === appId && r.serverId === targetServerId)) {
          db.appServerRels.push({ id: nextId(db.appServerRels), appId, serverId: targetServerId, status: 1 });
        }
        for (const tool of moving) {
          db.appToolRels.push({ id: nextId(db.appToolRels), appId, toolId: tool.id, status: 1 });
          grantsCreated++;
        }
      }
    }
    const count = db.tools.filter((t) => t.serverId === targetServerId && t.status === 1).length;
    return { moved: moving.length, grantsCreated, toolCount: count, overWarn: false };
  },
  async deleteTool(id: number): Promise<void> {
    await delay();
    db.tools = db.tools.filter((m) => m.id !== id);
  },
  async testToolConnection(_input: Partial<McpTool>): Promise<{ ok: boolean; status?: number; duration?: number; body?: string; error?: string; note?: string }> {
    await delay(500);
    return { ok: true, note: "Mock 模式：跳过真实连通测试", duration: 0 };
  },
  async fromRest(input: { name?: string; code?: string; description?: string; method?: string; url?: string; bodyExample?: string; authType?: string }): Promise<{ serviceUrl: string; requestType: string; authType: string; resJson: string; paramMapping: string }> {
    await delay(400);
    const code = input.code ?? input.name ?? "rest-tool";
    return {
      serviceUrl: input.url ?? "",
      requestType: input.method ?? "GET",
      authType: input.authType ?? "NONE",
      resJson: JSON.stringify({ name: code, description: input.description ?? "", inputSchema: { type: "object", properties: {} } }),
      paramMapping: "{}",
    };
  },

  async previewOpenApiImport(_input: { serverId: number; document: string; baseUrl?: string }): Promise<OpenApiImportPlan> {
    await delay();
    return {
      totalOperations: 0,
      ready: 0,
      conflicts: 0,
      errors: 0,
      warnings: 0,
      tools: [],
      issues: [{ level: "WARN", scope: "document", message: "Mock 模式：不解析 OpenAPI 文档" }],
    };
  },
  async importOpenApiTools(_input: {
    serverId: number;
    document: string;
    baseUrl?: string;
    conflictPolicy: "SKIP" | "OVERWRITE";
    codes?: string[];
  }): Promise<OpenApiImportResult> {
    await delay();
    return { created: 0, updated: 0, skipped: 0, failed: 0, failures: [] };
  },

  // ---- Prompt ----
  async listPrompts(): Promise<McpPrompt[]> {
    await delay();
    return [...db.prompts].sort((a, b) => b.id - a.id);
  },
  async createPrompt(input: Partial<McpPrompt>): Promise<McpPrompt> {
    await delay();
    const record: McpPrompt = {
      id: nextId(db.prompts),
      serverId: input.serverId ?? 1,
      name: input.name ?? "",
      status: input.status ?? 1,
      resJson: input.resJson ?? "{}",
      createTime: new Date().toISOString(),
      updateTime: new Date().toISOString(),
    };
    db.prompts.push(record);
    return record;
  },
  async updatePrompt(id: number, input: Partial<McpPrompt>): Promise<McpPrompt> {
    await delay();
    const idx = db.prompts.findIndex((p) => p.id === id);
    if (idx === -1) throw new Error("Prompt 不存在");
    db.prompts[idx] = { ...db.prompts[idx], ...input, id, updateTime: new Date().toISOString() };
    return db.prompts[idx];
  },
  async deletePrompt(id: number): Promise<void> {
    await delay();
    db.prompts = db.prompts.filter((p) => p.id !== id);
  },

  // ---- Resource ----
  async listResources(): Promise<McpResource[]> {
    await delay();
    return [...db.resources].sort((a, b) => b.id - a.id);
  },
  async createResource(input: Partial<McpResource>): Promise<McpResource> {
    await delay();
    const record: McpResource = {
      id: nextId(db.resources),
      serverId: input.serverId ?? 1,
      uri: input.uri ?? "",
      name: input.name ?? "",
      status: input.status ?? 1,
      resJson: input.resJson ?? "{}",
      createTime: new Date().toISOString(),
      updateTime: new Date().toISOString(),
    };
    db.resources.push(record);
    return record;
  },
  async updateResource(id: number, input: Partial<McpResource>): Promise<McpResource> {
    await delay();
    const idx = db.resources.findIndex((r) => r.id === id);
    if (idx === -1) throw new Error("Resource 不存在");
    db.resources[idx] = { ...db.resources[idx], ...input, id, updateTime: new Date().toISOString() };
    return db.resources[idx];
  },
  async deleteResource(id: number): Promise<void> {
    await delay();
    db.resources = db.resources.filter((r) => r.id !== id);
  },

  // ---- 应用 ----
  async listApps(): Promise<EnnAppInfo[]> {
    await delay();
    return [...db.apps];
  },

  // ---- 应用 × server 授权 ----
  async listAppServerRels(): Promise<AppServerRel[]> {
    await delay();
    return [...db.appServerRels];
  },
  async grantServer(appId: string, serverId: number): Promise<AppServerRel> {
    await delay();
    if (db.appServerRels.some((r) => r.appId === appId && r.serverId === serverId)) {
      throw new Error("该授权已存在");
    }
    const rel: AppServerRel = { id: nextId(db.appServerRels), appId, serverId, status: 1 };
    db.appServerRels.push(rel);
    return rel;
  },
  async updateAppServerAccess(
    id: number,
    input: { toolAccess: "INHERIT" | "ALL" | "WHITELIST"; profileTags?: string },
  ): Promise<AppServerRel> {
    await delay();
    const idx = db.appServerRels.findIndex((r) => r.id === id);
    if (idx === -1) throw new Error("授权不存在");
    db.appServerRels[idx] = {
      ...db.appServerRels[idx],
      toolAccess: input.toolAccess,
      profileTags: input.profileTags,
    };
    return db.appServerRels[idx];
  },
  async grantServers(appId: string, serverIds: number[]): Promise<AppServerRel[]> {
    await delay();
    const created: AppServerRel[] = [];
    for (const serverId of serverIds) {
      if (db.appServerRels.some((r) => r.appId === appId && r.serverId === serverId)) continue;
      const rel: AppServerRel = { id: nextId(db.appServerRels), appId, serverId, status: 1 };
      db.appServerRels.push(rel);
      created.push(rel);
    }
    return created;
  },
  async revokeServer(relId: number): Promise<void> {
    await delay();
    db.appServerRels = db.appServerRels.filter((r) => r.id !== relId);
  },

  // ---- 应用 × Tool 细粒度授权 ----
  async listAppToolRels(): Promise<AppToolRel[]> {
    await delay();
    return [...db.appToolRels];
  },
  async grantTool(appId: string, toolId: number): Promise<AppToolRel> {
    await delay();
    if (db.appToolRels.some((r) => r.appId === appId && r.toolId === toolId)) {
      throw new Error("该授权已存在");
    }
    const rel: AppToolRel = { id: nextId(db.appToolRels), appId, toolId, status: 1 };
    db.appToolRels.push(rel);
    return rel;
  },
  async revokeTool(relId: number): Promise<void> {
    await delay();
    db.appToolRels = db.appToolRels.filter((r) => r.id !== relId);
  },

  // ---- 系统服务 ----
  async listServiceSystems(): Promise<ServiceSystem[]> {
    await delay();
    return [...db.serviceSystems];
  },
  async updateServiceSystem(id: number, input: Partial<ServiceSystem>): Promise<ServiceSystem> {
    await delay();
    const idx = db.serviceSystems.findIndex((s) => s.id === id);
    if (idx === -1) throw new Error("系统不存在");
    db.serviceSystems[idx] = { ...db.serviceSystems[idx], ...input, id, updateTime: new Date().toISOString() };
    return db.serviceSystems[idx];
  },

  // ---- 日志 ----
  async listToolCallRecords(params: {
    pageNum: number;
    pageSize: number;
    startTime?: string;
    endTime?: string;
    success?: number;
    serverId?: number;
    appId?: string;
    userId?: string;
  }): Promise<PageResult<ToolCallRecord>> {
    await delay(340);
    let filtered = [...db.toolCallRecords];
    if (params.success !== undefined) {
      filtered = filtered.filter((r) => r.success === params.success);
    }
    if (params.serverId !== undefined) {
      filtered = filtered.filter((r) => r.serverId === params.serverId);
    }
    if (params.appId) {
      filtered = filtered.filter((r) => r.appId === params.appId);
    }
    const user = params.userId?.trim().toLowerCase();
    if (user) {
      filtered = filtered.filter(
        (r) =>
          (r.userId ?? "").toLowerCase().includes(user) ||
          (r.userName ?? "").toLowerCase().includes(user),
      );
    }
    if (params.startTime) {
      const from = new Date(params.startTime.replace(" ", "T")).getTime();
      filtered = filtered.filter((r) => new Date(r.createTime).getTime() >= from);
    }
    if (params.endTime) {
      const to = new Date(params.endTime.replace(" ", "T")).getTime();
      filtered = filtered.filter((r) => new Date(r.createTime).getTime() <= to);
    }
    filtered.sort((a, b) => b.id - a.id);
    const total = filtered.length;
    const start = (params.pageNum - 1) * params.pageSize;
    return {
      list: filtered.slice(start, start + params.pageSize),
      total,
      pageNum: params.pageNum,
      pageSize: params.pageSize,
    };
  },

  async getLogStats(): Promise<LogStats> {
    await delay(260);
    const records = db.toolCallRecords;
    const total = records.length;
    const successCount = records.filter((r) => r.success === 1).length;
    const failCount = total - successCount;
    const sumDuration = records.reduce((s, r) => s + (r.duration ?? 0), 0);
    const todayStart = new Date();
    todayStart.setHours(0, 0, 0, 0);
    const today = records.filter((r) => new Date(r.createTime) >= todayStart).length;
    return {
      total,
      today,
      successCount,
      failCount,
      avgDuration: total ? Math.round(sumDuration / total) : 0,
    };
  },

  async exportLogs(_params: { startTime?: string; endTime?: string; success?: number; serverId?: number; appId?: string; userId?: string }): Promise<{ fileName: string }> {
    await delay(600);
    return { fileName: `Tool 调用记录_${Date.now()}.xlsx` };
  },

  // ---- 操作审计日志（mock） ----
  async listOperationLogs(params: { pageNum: number; pageSize: number }): Promise<PageResult<import("@/types").OperationLog>> {
    await delay();
    const list = db.operationLogs ?? [];
    const start = (params.pageNum - 1) * params.pageSize;
    return { list: list.slice(start, start + params.pageSize), total: list.length, pageNum: params.pageNum, pageSize: params.pageSize };
  },

  // ---- 审批中心（mock） ----
  async getApprovalPolicy(): Promise<ApprovalPolicy> {
    const actions: ApprovalPolicy["actions"] = {};
    for (const action of [
      "GRANT_SERVER", "GRANT_SERVER_BATCH", "GRANT_TOOL", "GRANT_PROMPT", "GRANT_RESOURCE",
      "REVOKE_SERVER", "REVOKE_TOOL", "REVOKE_PROMPT", "REVOKE_RESOURCE",
      "SERVER_CREATE", "SERVER_UPDATE_CONNECTION", "SERVER_DELETE", "TOOL_CREDENTIAL_UPDATE",
      "APP_CREATE", "APP_RESET_SECRET", "APP_DELETE", "USER_ROLE_UPDATE",
      "TENANT_CREATE", "TENANT_UPDATE", "TENANT_DELETE",
    ]) {
      actions[action] = {
        tier: "L2",
        tierLabel: "租户内高影响",
        label: action,
        direct: true,
        canInitiate: true,
        approverRole: "ADMIN",
      };
    }
    return { role: "SUPER_ADMIN", actions };
  },
  async listApprovals(params: {
    pageNum: number;
    pageSize: number;
    status?: string;
    scope?: "all" | "pending" | "mine";
  }): Promise<PageResult<ApprovalRequest>> {
    await delay();
    let list = [...(db.approvals ?? [])];
    if (params.status) list = list.filter((r) => r.status === params.status);
    if (params.scope === "mine") list = list.filter((r) => r.requester === "admin");
    if (params.scope === "pending") list = list.filter((r) => r.status === "PENDING");
    list.sort((a, b) => b.id - a.id);
    const start = (params.pageNum - 1) * params.pageSize;
    return {
      list: list.slice(start, start + params.pageSize),
      total: list.length,
      pageNum: params.pageNum,
      pageSize: params.pageSize,
    };
  },
  async createApproval(input: {
    action: string;
    appId?: string;
    serverId?: number;
    toolId?: number;
    promptId?: number;
    resourceId?: number;
    userId?: number;
    payload?: Record<string, unknown>;
    reason?: string;
  }): Promise<ApprovalRequest> {
    await delay();
    const rec: ApprovalRequest = {
      id: (db.approvals ?? []).reduce((m, x) => Math.max(m, x.id), 0) + 1,
      action: input.action,
      status: "PENDING",
      appId: input.appId,
      serverId: input.serverId,
      toolId: input.toolId,
      promptId: input.promptId,
      resourceId: input.resourceId,
      userId: input.userId,
      payload: input.payload ? JSON.stringify(input.payload) : undefined,
      reason: input.reason ?? "",
      requester: "admin",
      requesterRole: "SUPER_ADMIN",
      createTime: new Date().toISOString(),
      updateTime: new Date().toISOString(),
    };
    db.approvals = [...(db.approvals ?? []), rec];
    return rec;
  },
  async getApproval(id: number): Promise<ApprovalRequest> {
    await delay();
    const rec = (db.approvals ?? []).find((r) => r.id === id);
    if (!rec) throw new Error("申请单不存在");
    return rec;
  },
  async approveApproval(id: number, comment?: string): Promise<ApprovalRequest> {
    await delay();
    const idx = (db.approvals ?? []).findIndex((r) => r.id === id);
    if (idx === -1) throw new Error("申请单不存在");
    db.approvals[idx] = {
      ...db.approvals[idx],
      status: "APPROVED",
      approver: "admin",
      approverRole: "SUPER_ADMIN",
      comment,
      updateTime: new Date().toISOString(),
    };
    return db.approvals[idx];
  },
  async rejectApproval(id: number, comment?: string): Promise<ApprovalRequest> {
    await delay();
    const idx = (db.approvals ?? []).findIndex((r) => r.id === id);
    if (idx === -1) throw new Error("申请单不存在");
    db.approvals[idx] = {
      ...db.approvals[idx],
      status: "REJECTED",
      approver: "admin",
      approverRole: "SUPER_ADMIN",
      comment,
      updateTime: new Date().toISOString(),
    };
    return db.approvals[idx];
  },
  async cancelApproval(id: number, comment?: string): Promise<ApprovalRequest> {
    await delay();
    const idx = (db.approvals ?? []).findIndex((r) => r.id === id);
    if (idx === -1) throw new Error("申请单不存在");
    db.approvals[idx] = {
      ...db.approvals[idx],
      status: "CANCELLED",
      comment,
      updateTime: new Date().toISOString(),
    };
    return db.approvals[idx];
  },
  async revealApprovalSecret(_id: number): Promise<{ secret: string }> {
    await delay();
    return { secret: "mock-app-secret-0123456789" };
  },
};
