import type {
  AppServerRel,
  AppToolRel,
  EnnAppInfo,
  McpPrompt,
  McpResource,
  McpServer,
  McpTool,
  ServiceSystem,
  Tenant,
  ToolCallRecord,
} from "@/types";

// ============ 种子数据（与后端实体结构一致） ============

const now = Date.now();
const daysAgo = (d: number, h = 0, m = 0) =>
  new Date(now - d * 86400000 - h * 3600000 - m * 60000).toISOString();

export const tenants: Tenant[] = [
  { id: 1, name: "crm-tenant", status: 1, createTime: daysAgo(40), updateTime: daysAgo(2) },
  { id: 2, name: "greatgas", status: 1, createTime: daysAgo(30), updateTime: daysAgo(5) },
  { id: 3, name: "trade-mcp", status: 1, createTime: daysAgo(20), updateTime: daysAgo(8) },
];

export const servers: McpServer[] = [
  {
    id: 1,
    tenantId: 1,
    name: "crm-core",
    path: "/mcp/v1/core",
    status: 1,
    toolAccessMode: "WHITELIST",
    toolExposureMode: "DIRECT",
    hotToolCodes: "[]",
    toolWarnThreshold: 50,
    toolMaxThreshold: 200,
    capabilitiesJson: JSON.stringify({
      protocolVersion: "2024-11-05",
      capabilities: { tools: {} },
      serverInfo: { name: "mcp-transfer", version: "1.0.0", title: "CRM MCP 网关" },
    }),
    createTime: daysAgo(40),
    updateTime: daysAgo(2),
  },
  {
    id: 2,
    tenantId: 2,
    name: "greatgas-core",
    path: "/mcp/greatgas/core",
    status: 1,
    toolAccessMode: "WHITELIST",
    toolExposureMode: "META",
    hotToolCodes: "[]",
    toolWarnThreshold: 100,
    toolMaxThreshold: 500,
    capabilitiesJson: JSON.stringify({
      protocolVersion: "2024-11-05",
      capabilities: { tools: {} },
      serverInfo: { name: "mcp-transfer", version: "1.0.0", title: "Greatgas MCP 网关" },
    }),
    createTime: daysAgo(30),
    updateTime: daysAgo(5),
  },
  {
    id: 3,
    tenantId: 3,
    name: "trade-core",
    path: "/mcp/trade/core",
    status: 0,
    toolAccessMode: "WHITELIST",
    toolExposureMode: "DIRECT",
    hotToolCodes: "[]",
    capabilitiesJson: JSON.stringify({
      protocolVersion: "2024-11-05",
      capabilities: { tools: {} },
      serverInfo: { name: "mcp-transfer", version: "1.0.0", title: "Trade MCP 网关" },
    }),
    createTime: daysAgo(20),
    updateTime: daysAgo(8),
  },
];

const policySchema = (desc: string, fields: string[]) => ({
  type: "object",
  properties: Object.fromEntries(
    fields.map((f) => [f, { type: "string", description: desc }]),
  ),
  required: fields,
  additionalProperties: false,
});

export const tools: McpTool[] = [
  { id: 1, serverId: 1, name: "政策地区查询", code: "select-policy-region-by-regin", type: 0, status: 1, serviceUrl: "PolicyServiceImpl.getPolicyRegion", requestType: "GET", needToken: 0, bodyType: 0, resJson: JSON.stringify({ name: "select-policy-region-by-regin", description: "根据地区名称查询匹配的政策 ID 列表", inputSchema: policySchema("地区名称", ["region_name"]) }), createTime: daysAgo(39), updateTime: daysAgo(3) },
  { id: 2, serverId: 1, name: "政策行业查询", code: "select-policy-industry-by-industry", type: 0, status: 1, serviceUrl: "PolicyServiceImpl.getPolicyIndustry", requestType: "GET", needToken: 0, bodyType: 0, resJson: JSON.stringify({ name: "select-policy-industry-by-industry", description: "根据行业名称查询匹配的政策 ID 列表", inputSchema: policySchema("行业名称", ["industry_name"]) }), createTime: daysAgo(39), updateTime: daysAgo(3) },
  { id: 3, serverId: 1, name: "政策基础信息查询", code: "select-policy-base-by-policy-id", type: 0, status: 1, serviceUrl: "PolicyServiceImpl.getPolicyBase", requestType: "POST", needToken: 0, bodyType: 0, resJson: JSON.stringify({ name: "select-policy-base-by-policy-id", description: "根据政策 ID 集合查询政策基础信息", inputSchema: policySchema("政策 ID 列表", ["policy_id"]) }), createTime: daysAgo(38), updateTime: daysAgo(1) },
  { id: 5, serverId: 1, name: "线索推送", code: "schedule-push-clue", type: 1, systemId: 2, status: 1, serviceUrl: "https://cm.ennenergy.cn/api/cperpDemandMgr/ennNewClueZone/getEnableZones", requestType: "GET", needToken: 1, bodyType: 0, authType: "SK_EXCHANGE", authConfig: JSON.stringify({ auth_url: "https://cm.ennenergy.cn/api/systemMgr/getEmployeeBySk" }), resJson: JSON.stringify({ name: "schedule-push-clue", description: "查询客管待推送线索并异步发送聊天消息", inputSchema: policySchema("线索区域", ["zoneCode"]) }), createTime: daysAgo(22), updateTime: daysAgo(4) },
  { id: 6, serverId: 2, name: "客户分类查询（示例）", code: "customer-category", type: 1, systemId: 1, status: 1, serviceUrl: "https://crm.example.com/api/customer/category", requestType: "POST", needToken: 1, bodyType: 0, authType: "SK_EXCHANGE", authConfig: JSON.stringify({ auth_url: "https://crm.example.com/api/auth/exchange" }), resJson: JSON.stringify({ name: "customer-category", description: "查询示例业务系统客户分类", inputSchema: policySchema("公司编码", ["companyCode"]) }), createTime: daysAgo(18), updateTime: daysAgo(9) },
  { id: 7, serverId: 3, name: "发布机构查询", code: "select-policy-dept-by-dept", type: 0, status: 0, serviceUrl: "PolicyServiceImpl.getPolicyDept", requestType: "GET", needToken: 0, bodyType: 0, resJson: JSON.stringify({ name: "select-policy-dept-by-dept", description: "根据机构信息查询机构编码", inputSchema: policySchema("机构名称", ["dept_name"]) }), createTime: daysAgo(35), updateTime: daysAgo(15) },
  { id: 8, serverId: 1, name: "政策正文查询", code: "select-policy-body-by-policy-id", type: 0, status: 1, serviceUrl: "PolicyServiceImpl.getPolicyBody", requestType: "GET", needToken: 0, bodyType: 0, resJson: JSON.stringify({ name: "select-policy-body-by-policy-id", description: "根据政策 ID 查询政策正文内容", inputSchema: policySchema("政策 ID", ["policy_id"]) }), createTime: daysAgo(33), updateTime: daysAgo(7) },
  { id: 9, serverId: 1, name: "政策分析结果查询", code: "select-policy-analysis-result-by-policy-id", type: 0, status: 1, serviceUrl: "PolicyServiceImpl.getPolicyAnalysis", requestType: "GET", needToken: 0, bodyType: 0, resJson: JSON.stringify({ name: "select-policy-analysis-result-by-policy-id", description: "根据政策 ID 查询政策分析结果", inputSchema: policySchema("政策 ID", ["policy_id"]) }), createTime: daysAgo(30), updateTime: daysAgo(11) },
  { id: 10, serverId: 1, name: "员工信息查询（客管）", code: "get-employee-by-sk", type: 1, systemId: 2, status: 1, serviceUrl: "https://cm.ennenergy.cn/api/systemMgr/getEmployeeBySk", requestType: "POST", needToken: 1, bodyType: 1, authType: "SK_EXCHANGE", authConfig: JSON.stringify({ auth_url: "https://cm.ennenergy.cn/api/systemMgr/getEmployeeBySk" }), resJson: JSON.stringify({ name: "get-employee-by-sk", description: "通过 sk 换取员工信息", inputSchema: policySchema("sk 凭证", ["sk"]) }), createTime: daysAgo(15), updateTime: daysAgo(5) },
  { id: 11, serverId: 2, name: "交易客户分类（示例）", code: "trade-customer-category", type: 1, systemId: 1, status: 1, serviceUrl: "https://crm.example.com/api/trade/customerCategory", requestType: "POST", needToken: 1, bodyType: 0, authType: "SK_EXCHANGE", authConfig: JSON.stringify({ auth_url: "https://crm.example.com/api/auth/exchange" }), resJson: JSON.stringify({ name: "trade-customer-category", description: "查询交易客户分类信息", inputSchema: policySchema("客户分类编码", ["customerCategoryCode"]) }), createTime: daysAgo(12), updateTime: daysAgo(2) },
  { id: 12, serverId: 1, name: "区域启用查询", code: "get-enable-zones", type: 1, systemId: 2, status: 1, serviceUrl: "https://cm.ennenergy.cn/api/cperpDemandMgr/ennNewClueZone/getEnableZones", requestType: "GET", needToken: 1, bodyType: 0, authType: "SK_EXCHANGE", authConfig: JSON.stringify({ auth_url: "https://cm.ennenergy.cn/api/systemMgr/getEmployeeBySk" }), resJson: JSON.stringify({ name: "get-enable-zones", description: "查询已启用的线索区域", inputSchema: { type: "object", properties: {} } }), createTime: daysAgo(10), updateTime: daysAgo(1) },
];

export const prompts: McpPrompt[] = [
  { id: 1, serverId: 1, name: "政策解读", status: 1, resJson: JSON.stringify({ name: "政策解读", description: "解读一条政策的核心要点", arguments: [{ name: "policy_id", description: "政策 ID", required: true }] }), createTime: daysAgo(12), updateTime: daysAgo(1) },
  { id: 2, serverId: 2, name: "客户分类提示", status: 1, resJson: JSON.stringify({ name: "客户分类提示", description: "引导选择客户分类", arguments: [{ name: "companyCode", description: "公司编码", required: true }] }), createTime: daysAgo(10), updateTime: daysAgo(2) },
];

export const resources: McpResource[] = [
  { id: 1, serverId: 1, uri: "crm://policies/{policy_id}", name: "政策详情", status: 1, resJson: JSON.stringify({ uri: "crm://policies/{policy_id}", name: "政策详情", description: "政策基础信息", mimeType: "application/json" }), createTime: daysAgo(12), updateTime: daysAgo(1) },
  { id: 2, serverId: 2, uri: "greatgas://customer/{company_code}", name: "客户信息", status: 1, resJson: JSON.stringify({ uri: "greatgas://customer/{company_code}", name: "客户信息", description: "客户基础档案", mimeType: "application/json" }), createTime: daysAgo(10), updateTime: daysAgo(2) },
];

export const apps: EnnAppInfo[] = [
  { id: 1, appId: "crm", appSecret: "mock-secret-crm", key: "mock-key-crm", deleted: 0, createTime: daysAgo(40), updateTime: daysAgo(2) },
  { id: 2, appId: "greatgas", appSecret: "mock-secret-greatgas", key: "mock-key-greatgas", deleted: 0, createTime: daysAgo(28), updateTime: daysAgo(5) },
  { id: 3, appId: "trade", appSecret: "mock-secret-trade", key: "mock-key-trade", deleted: 0, createTime: daysAgo(14), updateTime: daysAgo(1) },
];

export const appServerRels: AppServerRel[] = [
  { id: 1, appId: "crm", serverId: 1, status: 1 },
];

export const appToolRels: AppToolRel[] = [
  { id: 1, appId: "greatgas", toolId: 12, status: 1 },
  { id: 2, appId: "trade", toolId: 6, status: 1 },
  { id: 3, appId: "trade", toolId: 11, status: 1 },
];

export const serviceSystems: ServiceSystem[] = [
  { id: 1, name: "示例业务系统", authUrl: "https://crm.example.com/api/auth/exchange", status: 1, createTime: daysAgo(40), updateTime: daysAgo(2) },
  { id: 2, name: "客管系统", authUrl: "https://cm.ennenergy.cn/api/systemMgr/getEmployeeBySk", status: 1, createTime: daysAgo(40), updateTime: daysAgo(3) },
];

// ============ Tool 调用记录 ============

const methodNames = [
  "select-policy-region-by-regin",
  "select-policy-base-by-policy-id",
  "schedule-push-clue",
  "customer-category",
  "get-enable-zones",
];

const mockCallUsers = [
  { userId: "u-1001", userName: "张伟" },
  { userId: "u-1002", userName: "李娜" },
  { userId: "u-1003", userName: "王强" },
];

export const toolCallRecords: ToolCallRecord[] = Array.from({ length: 60 }, (_, i) => {
  const code = methodNames[i % methodNames.length];
  const success = i % 7 !== 0 ? 1 : 0;
  const user = mockCallUsers[i % mockCallUsers.length];
  const greatgas = i % 5 === 0;
  const appId = ["crm", "greatgas", "dsh-pcb"][i % 3];
  return {
    id: 1000 - i,
    tenantId: greatgas ? 2 : 1,
    tenantName: greatgas ? "greatgas" : "crm-tenant",
    serverId: greatgas ? 2 : 1,
    serverName: greatgas ? "greatgas-core" : "crm-core",
    appId,
    ...user,
    methodDefId: (i % 12) + 1,
    methodCode: code,
    methodName: code,
    requestArgs: JSON.stringify({ region_name: "北京", industry_name: "能源" }),
    responseResult: success
      ? JSON.stringify({ code: 0, data: { count: 12 } })
      : JSON.stringify({ code: -32603, message: "调用下游服务超时" }),
    callType: i % 3 === 0 ? "LOCAL" : "EXTERNAL",
    httpMethod: i % 3 === 0 ? undefined : i % 2 === 0 ? "GET" : "POST",
    targetUrl: i % 3 === 0 ? undefined : "https://cm.ennenergy.cn/api/...",
    success,
    errorMsg: success ? undefined : "调用下游服务超时",
    duration: Math.round(30 + Math.random() * 1800),
    createTime: daysAgo(Math.floor(i / 8), (i * 7) % 24, (i * 13) % 60),
  };
});
