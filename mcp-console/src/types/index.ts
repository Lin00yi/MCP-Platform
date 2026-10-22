// ============ 与后端实体一一对应的领域类型（多租户：租户 → MCP Server → tool/prompt/resource） ============

/** 租户（tenant） */
export interface Tenant {
  id: number;
  name: string;
  /** 1=启用 0=禁用 */
  status: number;
  createTime: string;
  updateTime: string;
}

/** Tool 访问模式：WHITELIST=仅显式授权 / ALL=全部启用 Tool */
export type ToolAccessMode = "WHITELIST" | "ALL";

/** 工具暴露模式：DIRECT=全量 tools/list / META=元工具+热工具 */
export type ToolExposureMode = "DIRECT" | "META";

/** Prompt/Resource 访问模式 */
/** Prompt / Resource 访问模式：与 Tool 使用同一枚举 */
export type AssetAccessMode = "WHITELIST" | "ALL";

/** MCP Server（归属租户） */
export interface McpServer {
  id: number;
  tenantId: number;
  name: string;
  serverKey?: string;
  path: string;
  /** 1=启用(注册) 0=禁用(下线) */
  status: number;
  /** initialize 能力声明 JSON */
  capabilitiesJson: string;
  /** 上游类型：NONE=本地/RESTTool，MCP=代理到下游真实 MCP server */
  upstreamType?: string;
  /** 下游 MCP server 的 Streamable HTTP 地址（upstreamType=MCP 时） */
  upstreamUrl?: string;
  /** Tool 访问模式（后端空值按 WHITELIST 处理） */
  toolAccessMode?: ToolAccessMode;
  /** 工具暴露模式 */
  toolExposureMode?: ToolExposureMode;
  /** Prompt 访问模式 */
  promptAccessMode?: AssetAccessMode;
  /** Resource 访问模式 */
  resourceAccessMode?: AssetAccessMode;
  /** META 模式常驻工具 code（JSON 数组字符串） */
  hotToolCodes?: string;
  /** 工具数告警阈值（空=全局默认） */
  toolWarnThreshold?: number;
  /** 工具数硬阈值（空=全局默认） */
  toolMaxThreshold?: number;
  createTime: string;
  updateTime: string;
}

/** 单个 Server 的工具预算快照 */
export interface ToolBudget {
  serverId: number;
  serverName: string;
  accessMode: ToolAccessMode;
  exposureMode: ToolExposureMode;
  totalTools: number;
  visibleTools: number;
  hotTools: number;
  warnThreshold: number;
  maxThreshold: number;
  overWarn: boolean;
  overMax: boolean;
  estimatedTokens: number;
  promptAccessMode?: AssetAccessMode;
  resourceAccessMode?: AssetAccessMode;
  totalPrompts?: number;
  visiblePrompts?: number;
  totalResources?: number;
  visibleResources?: number;
  promptWarnThreshold?: number;
  resourceWarnThreshold?: number;
  promptOverWarn?: boolean;
  resourceOverWarn?: boolean;
  promptEstimatedTokens?: number;
  resourceEstimatedTokens?: number;
}

/** META 热工具推荐结果 */
export interface HotToolRecommendation {
  serverId: number;
  windowDays: number;
  totalCalls: number;
  hotToolCodes: string[];
  tools: Array<{ code: string; name: string; calls: number; score: number }>;
  hint?: string;
}

/** MCP Server 健康状态 */
export interface ServerHealth {
  serverId: number;
  name: string;
  path: string;
  upstreamType: string;
  /** UP / DEGRADED / DOWN / DISABLED / UNKNOWN */
  status: "UP" | "DEGRADED" | "DOWN" | "DISABLED" | "UNKNOWN";
  ok: boolean;
  latencyMs?: number;
  successRate?: number | null;
  totalCalls?: number;
  message?: string;
  checkedAt?: string;
}

/** 发现：Server 目录项 */
export interface DiscoveryServer {
  id: number;
  name: string;
  serverKey?: string;
  path: string;
  tenantId: number;
  status: number;
  upstreamType: string;
  upstreamUrl?: string;
  toolCount: number;
  totalToolCount?: number;
  toolAccessMode?: ToolAccessMode;
  toolExposureMode?: ToolExposureMode;
  hotToolCount?: number;
  warnThreshold?: number;
  maxThreshold?: number;
  overWarn?: boolean;
  overMax?: boolean;
  estimatedTokens?: number;
  /** 统一入口模式：聚合了多少个真实 Server */
  serverCount?: number;
  proxyServerCount?: number;
  servers?: { id: number; serverKey: string; name: string; path: string; toolCount: number }[];
}

/** 发现：Tool 目录项 */
export interface DiscoveryTool {
  id: number;
  code: string;
  originalCode?: string;
  name: string;
  description?: string;
  type: number;
  tags?: string;
  version?: string;
  server: { id: number; name: string; path: string; serverKey?: string };
}

/** 发现：Prompt 目录项 */
export interface DiscoveryPrompt {
  id: number;
  name: string;
  /** 描述（后端从 res_json.description 提取） */
  description?: string;
  version?: string;
  server: { id: number; name: string; path: string };
}

/** 发现：Resource 目录项 */
export interface DiscoveryResource {
  id: number;
  name: string;
  uri: string;
  /** 后端返回 resJson 原文（可能包含 description） */
  description?: string;
  version?: string;
  server: { id: number; name: string; path: string };
}

/** Tool 执行器：0=本地 1=HTTP转发 */
export type ToolType = 0 | 1;

/** MCP Tool（归属 server） */
export interface McpTool {
  id: number;
  serverId: number;
  name: string;
  /** 唯一标识（即 MCP Tool 名） */
  code: string;
  type: ToolType;
  /** 关联 service_system（SK_EXCHANGE 兜底 auth_url） */
  systemId?: number;
  /** 1=启用 0=禁用 */
  status: number;
  /** 本地写 class.method，远程写请求地址 */
  serviceUrl: string;
  /** GET/POST */
  requestType: string;
  /** 是否需要 token 0:否 1:是 */
  needToken: number;
  /** POST 参数形式 0:JSON 1:form */
  bodyType: number;
  /** 鉴权方式 NONE/SK_EXCHANGE/API_KEY/BEARER */
  authType?: string;
  /** 鉴权配置 JSON */
  authConfig?: string;
  /** 参数映射 JSON */
  paramMapping?: string;
  /** Tool 描述 + inputSchema */
  resJson: string;
  /** 标签（逗号分隔，资产目录检索） */
  tags?: string;
  /** 负责人/归属团队 */
  owner?: string;
  /** 版本号 */
  version?: string;
  /** 预计算 token 估算 */
  tokenEstimate?: number;
  /** tokenizer 标识 */
  tokenizer?: string;
  createTime: string;
  updateTime: string;
}

/** MCP Prompt（归属 server） */
export interface McpPrompt {
  id: number;
  serverId: number;
  name: string;
  status: number;
  resJson: string;
  tags?: string;
  version?: string;
  tokenEstimate?: number;
  tokenizer?: string;
  createTime: string;
  updateTime: string;
}

/** MCP Resource（归属 server） */
export interface McpResource {
  id: number;
  serverId: number;
  uri: string;
  name: string;
  status: number;
  resJson: string;
  tags?: string;
  version?: string;
  tokenEstimate?: number;
  tokenizer?: string;
  createTime: string;
  updateTime: string;
}

/** 应用-方法权限（app_server_rel：应用 × server 授权） */
export interface AppServerRel {
  id: number;
  appId: string;
  serverId: number;
  status?: number;
  /** INHERIT=沿用 Server 模式 / ALL=全部工具 / WHITELIST=仅工具授权 */
  toolAccess?: "INHERIT" | "ALL" | "WHITELIST";
  /** 应用级工具画像标签，逗号分隔 */
  profileTags?: string;
  createTime?: string;
  updateTime?: string;
}

/** 应用-Tool 细粒度授权（app_tool_rel，可选） */
export interface AppToolRel {
  id: number;
  appId: string;
  toolId: number;
  status?: number;
  createTime?: string;
  updateTime?: string;
}

/** 应用-Prompt 细粒度授权（app_prompt_rel，可选） */
export interface AppPromptRel {
  id: number;
  appId: string;
  promptId: number;
  status?: number;
  createTime?: string;
  updateTime?: string;
}

/** 应用-Resource 细粒度授权（app_resource_rel，可选） */
export interface AppResourceRel {
  id: number;
  appId: string;
  resourceId: number;
  status?: number;
  createTime?: string;
  updateTime?: string;
}

/** 应用信息（enn_app_info） */
export interface EnnAppInfo {
  id: number;
  appId: string;
  appSecret: string;
  key?: string;
  deleted?: number;
  createTime: string;
  updateTime: string;
}

/** 系统服务（service_system） */
export interface ServiceSystem {
  id: number;
  name: string;
  authUrl: string;
  status: number;
  createTime: string;
  updateTime: string;
}

/** Tool 调用记录（tool_call_record） */
export interface ToolCallRecord {
  id: number;
  tenantId: number;
  tenantName?: string;
  serverId?: number;
  serverName?: string;
  appId?: string;
  userId?: string;
  userName?: string;
  methodDefId: number;
  methodCode: string;
  methodName: string;
  requestArgs?: string;
  responseResult?: string;
  callType: "LOCAL" | "EXTERNAL";
  httpMethod?: string;
  targetUrl?: string;
  success: number;
  errorMsg?: string;
  duration?: number;
  createTime: string;
}

/** 分页结果 */
export interface PageResult<T> {
  list: T[];
  total: number;
  pageNum: number;
  pageSize: number;
}

// ============ 概览统计 ============

export interface DashboardStats {
  totalRequests: number;
  toolCalls: number;
  successCount: number;
  failCount: number;
  avgDuration: number;
  tenantCount: number;
  serverCount: number;
  toolCount: number;
  appCount: number;
  activeTenantCount: number;
  todayRequests: number;
  todayRequestsDelta: number;
  /** 工具数超过告警阈值的 Server 数 */
  overToolBudgetCount?: number;
}

/** 首页工具预算告警项 */
export interface ToolBudgetWarning {
  serverId: number;
  serverName: string;
  path: string;
  toolCount: number;
  warnThreshold: number;
  overMax: boolean;
  exposureMode: ToolExposureMode;
}

export interface TrendPoint {
  time: string;
  requests: number;
  success: number;
  failed: number;
  avgDuration: number;
}

export interface ToolDistItem {
  name: string;
  value: number;
}

export interface CallTypeDistItem {
  name: string;
  value: number;
}

export interface DashboardOverview {
  stats: DashboardStats;
  trend: TrendPoint[];
  toolDist: ToolDistItem[];
  callTypeDist: CallTypeDistItem[];
  toolBudgetWarnings?: ToolBudgetWarning[];
}

/** Tool 调用统计（日志页顶部统计卡片） */
export interface LogStats {
  total: number;
  today: number;
  successCount: number;
  failCount: number;
  avgDuration: number;
}

/** 操作审计日志（谁在什么时候对什么资源做了什么） */
export interface OperationLog {
  id: number;
  username?: string;
  action: string;
  resourceType: string;
  resourceId?: string;
  detail?: string;
  createTime: string;
}

export type ApprovalStatus = "PENDING" | "APPROVED" | "REJECTED" | "CANCELLED";

/** 通用审批申请单 */
export interface ApprovalRequest {
  id: number;
  tenantId?: number;
  action: string;
  status: ApprovalStatus;
  appId?: string;
  serverId?: number;
  toolId?: number;
  promptId?: number;
  resourceId?: number;
  userId?: number;
  /** 执行快照（详情接口返回脱敏后的 JSON 字符串） */
  payload?: string;
  reason?: string;
  requester?: string;
  requesterRole?: string;
  approver?: string;
  approverRole?: string;
  comment?: string;
  createTime: string;
  updateTime: string;
}

export interface ApprovalPolicyItem {
  tier: "L1" | "L2" | "L3" | "L4";
  tierLabel: string;
  label: string;
  /** 当前角色能否直接执行（不需要审批） */
  direct: boolean;
  /** 当前角色能否发起审批申请 */
  canInitiate: boolean;
  /** 审批所需最低角色 */
  approverRole: string;
}

export interface ApprovalPolicy {
  role: string;
  actions: Record<string, ApprovalPolicyItem>;
}

// ============ OpenAPI 导入 ============

export interface OpenApiIssue {
  level: "ERROR" | "WARN";
  scope?: string;
  message: string;
}

/** 单个 operation 转换出的 Tool 草稿（后端只返回计划，不落库） */
export interface OpenApiToolDraft {
  code: string;
  name: string;
  description?: string;
  method: string;
  path: string;
  serviceUrl: string;
  tags?: string;
  version?: string;
  authType?: string;
  authConfig?: Record<string, unknown>;
  inputSchema: Record<string, any>;
  paramMapping: Record<string, any>;
  /** READY 可导入 / CONFLICT 已存在同 code / ERROR 校验不通过 */
  status: "READY" | "CONFLICT" | "ERROR";
  importable: boolean;
  issues: OpenApiIssue[];
}

export interface OpenApiImportPlan {
  specTitle?: string;
  specVersion?: string;
  openapiVersion?: string;
  baseUrl?: string;
  totalOperations: number;
  ready: number;
  conflicts: number;
  errors: number;
  warnings: number;
  tools: OpenApiToolDraft[];
  issues: OpenApiIssue[];
}

export interface OpenApiImportResult {
  created: number;
  updated: number;
  skipped: number;
  failed: number;
  failures: { code: string; message: string }[];
  baseUrl?: string;
}

/** Namespace：一组 Server 的逻辑分组，带工具名前缀 */
export interface NamespaceVO {
  id: number;
  name: string;
  prefix: string;
  description?: string;
  status: number;
  serverCount: number;
  toolCount: number;
  servers: { id: number; name: string; serverKey: string; path: string; status: number; toolCount: number; totalToolCount: number; upstreamType?: string }[];
  createTime?: string;
  updateTime?: string;
}

/** Endpoint：对外接入点 /mcp/e/{endpointKey} */
export interface EndpointVO {
  id: number;
  endpointKey: string;
  name: string;
  description?: string;
  status: number;
  path: string;
  url: string;
  namespaceCount: number;
  serverCount: number;
  toolCount: number;
  namespaces: { id: number; name: string; prefix: string; status: number }[];
  apps: { appId: string; status: number }[];
  createTime?: string;
  updateTime?: string;
}
