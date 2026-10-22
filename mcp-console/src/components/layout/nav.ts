import {
  LayoutDashboard,
  Server,
  Cable,
  ScrollText,
  Users,
  Building2,
  Search,
  History,
  Inbox,
  Boxes,
  Link2,
  type LucideIcon,
} from "lucide-react";

export interface NavItem {
  title: string;
  href: string;
  icon: LucideIcon;
  description: string;
  /** 可见所需的最低角色等级：4超管 3管理员 2开发者 1运维 0用户 */
  minLevel: number;
  /** 侧边栏分组 */
  group: string;
  /** 路线图规划中的占位项（置灰、不可点） */
  comingSoon?: boolean;
}

/** 侧边栏分组顺序 */
export const NAV_GROUP_ORDER = ["运行监控", "接入", "资产", "权限与治理"] as const;

export const navItems: NavItem[] = [
  // ---- 运行监控 ----
  { title: "概览", href: "/dashboard", icon: LayoutDashboard, description: "网关运行总览", minLevel: 0, group: "运行监控" },
  { title: "调用日志", href: "/logs", icon: ScrollText, description: "请求与 Tool 调用记录", minLevel: 0, group: "运行监控" },
  { title: "审计日志", href: "/audit", icon: History, description: "配置变更留痕", minLevel: 2, group: "运行监控" },

  // ---- 接入 ----
  { title: "Endpoints", href: "/endpoints", icon: Link2, description: "对外接入点与 Namespace 绑定", minLevel: 3, group: "接入" },
  { title: "Namespaces", href: "/namespaces", icon: Boxes, description: "Server 逻辑分组与工具前缀", minLevel: 3, group: "接入" },
  { title: "MCP Servers", href: "/servers", icon: Server, description: "上游 MCP 端点与 Tool/Prompt/Resource", minLevel: 2, group: "接入" },

  // ---- 资产 ----
  { title: "租户管理", href: "/tenants", icon: Building2, description: "租户管理", minLevel: 4, group: "资产" },
  { title: "资产目录", href: "/catalog", icon: Search, description: "按 Server 浏览与发现资产", minLevel: 0, group: "资产" },
  { title: "下游系统", href: "/systems", icon: Cable, description: "下游系统与授权地址", minLevel: 2, group: "资产" },

  // ---- 权限与治理 ----
  { title: "审批", href: "/approvals", icon: Inbox, description: "授权申请审批", minLevel: 2, group: "权限与治理" },
  { title: "用户管理", href: "/users", icon: Users, description: "后台账号与角色", minLevel: 3, group: "权限与治理" },
];
