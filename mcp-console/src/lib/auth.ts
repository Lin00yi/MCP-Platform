// 后台登录态管理（token + 当前用户，存 localStorage）

export interface CurrentUser {
  id: number;
  username: string;
  name: string;
  role: string; // SUPER_ADMIN / ADMIN / DEVELOPER / OPERATOR / USER
  status: number;
  tenantIds: number[];
}

const TOKEN_KEY = "mcp_console_token";
const USER_KEY = "mcp_console_user";

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token);
}

export function getUser(): CurrentUser | null {
  const s = localStorage.getItem(USER_KEY);
  if (!s) return null;
  try {
    return JSON.parse(s) as CurrentUser;
  } catch {
    return null;
  }
}

export function setUser(user: CurrentUser) {
  localStorage.setItem(USER_KEY, JSON.stringify(user));
}

export function logout() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

export function isLoggedIn(): boolean {
  return !!getToken();
}

export function isSuperAdmin(): boolean {
  return getUser()?.role === "SUPER_ADMIN";
}

export function canManageUser(): boolean {
  const role = getUser()?.role;
  return role === "SUPER_ADMIN" || role === "ADMIN";
}

export function canManageTenant(): boolean {
  return getUser()?.role === "SUPER_ADMIN";
}

const ROLE_LEVEL: Record<string, number> = {
  SUPER_ADMIN: 4,
  ADMIN: 3,
  DEVELOPER: 2,
  OPERATOR: 1,
  USER: 0,
};

/** 当前用户角色等级（用于菜单/按钮可见性） */
export function roleLevel(): number {
  const role = getUser()?.role;
  return ROLE_LEVEL[role ?? "USER"] ?? 0;
}
