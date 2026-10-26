import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { roleLevel } from "@/lib/auth";
import type { ApprovalPolicy } from "@/types";

/**
 * 审批动作元数据与前端策略判断。
 *
 * 后端 `/mcp/admin/approvals/policy` 是唯一事实来源；policy 尚未加载时，
 * 用与后端一致的角色门槛做兜底，避免按钮闪烁或误放行。
 */
export const APPROVAL_ACTION_LABEL: Record<string, string> = {
  SERVER_CREATE: "创建 MCP Server",
  SERVER_UPDATE_CONNECTION: "变更 Server 连接配置",
  SERVER_DELETE: "删除 MCP Server",
  TOOL_CREDENTIAL_UPDATE: "变更 Tool 下游凭证",
  USER_ROLE_UPDATE: "变更用户角色",
  TENANT_CREATE: "创建业务方",
  TENANT_UPDATE: "更新业务方",
  TENANT_DELETE: "删除业务方",
};

/** 动作 → 风险档位（与后端 ApprovalAction 对齐，用于 policy 未加载时的兜底） */
const ACTION_TIER: Record<string, "L1" | "L2" | "L3" | "L4"> = {
  SERVER_CREATE: "L2",
  SERVER_UPDATE_CONNECTION: "L2",
  SERVER_DELETE: "L2",
  TOOL_CREDENTIAL_UPDATE: "L2",
  USER_ROLE_UPDATE: "L3",
  TENANT_CREATE: "L4",
  TENANT_UPDATE: "L4",
  TENANT_DELETE: "L4",
};

export function useApprovalPolicy() {
  return useQuery({
    queryKey: ["approval-policy"],
    queryFn: ({ signal }) => api.getApprovalPolicy(signal),
    staleTime: 60_000,
  });
}

/** 直接执行所需的最低角色等级：L2 管理员(3)，L3/L4 超管(4) */
function directLevel(action: string): number {
  const tier = ACTION_TIER[action] ?? "L2";
  return tier === "L2" ? 3 : 4;
}

/** 能否发起申请：L2 开发者(2)，L3 管理员(3)，L4 不可发起 */
function initiateLevel(action: string): number {
  const tier = ACTION_TIER[action] ?? "L2";
  if (tier === "L4") return 99;
  return tier === "L3" ? 3 : 2;
}

export function isDirect(policy: ApprovalPolicy | undefined, action: string): boolean {
  const item = policy?.actions?.[action];
  if (item) return item.direct;
  return roleLevel() >= directLevel(action);
}

export function canInitiate(policy: ApprovalPolicy | undefined, action: string): boolean {
  const item = policy?.actions?.[action];
  if (item) return item.canInitiate;
  return roleLevel() >= initiateLevel(action);
}

/** 当前用户能否审批该动作（仅用于前端按钮展示，后端仍会强校验） */
export function canApprove(policy: ApprovalPolicy | undefined, action: string): boolean {
  const item = policy?.actions?.[action];
  if (item) return item.direct;
  return roleLevel() >= directLevel(action);
}
