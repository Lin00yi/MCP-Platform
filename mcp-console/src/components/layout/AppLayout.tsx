import { useEffect, useState } from "react";
import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import {
  Menu,
  X,
  Terminal,
  LogOut,
  KeyRound,
  PanelLeftClose,
  PanelLeftOpen,
  Tag,
  CheckCircle2,
  RefreshCw,
} from "lucide-react";
import { useMutation } from "@tanstack/react-query";
import { toast } from "sonner";
import { cn } from "@/lib/utils";
import { api } from "@/lib/api";
import { navItems, NAV_GROUP_ORDER, type NavItem } from "./nav";
import { getUser, logout, roleLevel } from "@/lib/auth";
import {
  runningVersion,
  useDeployedVersion,
  isNewerDeployment,
  releaseFor,
  readDismissedBuildId,
  dismissBuild,
  formatBuildTime,
} from "@/lib/version";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import {
  Dialog,
  DialogContent,
  DialogBody,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

const SIDEBAR_COLLAPSED_KEY = "mcp_console_sidebar_collapsed";

const ROLE_NAME: Record<string, string> = {
  SUPER_ADMIN: "平台超管",
  ADMIN: "管理员",
  DEVELOPER: "开发者",
  OPERATOR: "运维",
  USER: "用户",
};

const visibleItems = () => navItems.filter((item) => roleLevel() >= item.minLevel);

/** 按分组聚合可见菜单项，保持固定顺序 */
const groupedItems = (): { title: string; items: NavItem[] }[] => {
  const visible = visibleItems();
  const groups: { title: string; items: NavItem[] }[] = [];
  for (const g of NAV_GROUP_ORDER) {
    const list = visible.filter((i) => i.group === g);
    if (list.length > 0) groups.push({ title: g, items: list });
  }
  const ungrouped = visible.filter((i) => !(NAV_GROUP_ORDER as readonly string[]).includes(i.group));
  if (ungrouped.length > 0) groups.push({ title: "其它", items: ungrouped });
  return groups;
};

function SidebarContent({
  onNavigate,
  collapsed = false,
  onToggle,
  hasUpdate = false,
  onShowVersion,
}: {
  onNavigate?: () => void;
  collapsed?: boolean;
  onToggle?: () => void;
  hasUpdate?: boolean;
  onShowVersion: () => void;
}) {
  const { pathname } = useLocation();
  return (
    <div className="flex h-full flex-col">
      <div className={cn("flex h-16 items-center border-b", collapsed ? "justify-center" : "gap-2 px-5")}>
        {collapsed ? (
          <button
            type="button"
            onClick={onToggle}
            aria-label="展开侧边栏"
            aria-expanded={false}
            title="展开侧边栏"
            className="group relative flex h-8 w-8 shrink-0 cursor-pointer items-center justify-center rounded-lg bg-primary/15 text-primary focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
          >
            <Terminal className="h-4 w-4 transition-opacity duration-150 group-hover:opacity-0 group-focus-visible:opacity-0" />
            <PanelLeftOpen className="absolute h-4 w-4 opacity-0 transition-opacity duration-150 group-hover:opacity-100 group-focus-visible:opacity-100" />
          </button>
        ) : (
          <>
            <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-primary/15 text-primary">
              <Terminal className="h-4 w-4" />
            </div>
            <div className="leading-tight">
              <div className="text-sm font-semibold tracking-tight">MCP Gateway</div>
              <div className="text-[11px] text-muted-foreground">控制台</div>
            </div>
            {onToggle && (
              <Button
                variant="ghost"
                size="icon"
                className="ml-auto h-8 w-8 text-muted-foreground"
                onClick={onToggle}
                aria-label="收起侧边栏"
                aria-expanded
                title="收起侧边栏"
              >
                <PanelLeftClose className="h-4 w-4" />
              </Button>
            )}
          </>
        )}
      </div>

      <nav className={cn("flex-1 overflow-y-auto", collapsed ? "space-y-2 p-2" : "space-y-4 p-3")}>
        {groupedItems().map((group, index) => (
          <div key={group.title} className={cn(collapsed && index > 0 && "border-t border-border/60 pt-2")}>
            {!collapsed && (
              <div className="px-3 pb-1 text-[11px] font-medium uppercase tracking-wider text-muted-foreground/70">
                {group.title}
              </div>
            )}
            <div className="space-y-1">
              {group.items.map((item) => {
                const Icon = item.icon;
                if (item.comingSoon) {
                  const node = (
                    <div
                      key={item.href}
                      title={collapsed ? undefined : item.description}
                      className={cn(
                        "flex items-center rounded-lg text-sm font-medium text-muted-foreground/50 cursor-not-allowed select-none",
                        collapsed ? "justify-center px-0 py-2.5" : "gap-3 px-3 py-2.5",
                      )}
                    >
                      <Icon className="h-4 w-4 shrink-0" />
                      {!collapsed && (
                        <>
                          <span>{item.title}</span>
                          <span className="ml-auto text-[10px] text-muted-foreground/50">规划中</span>
                        </>
                      )}
                    </div>
                  );
                  return collapsed ? (
                    <Tooltip key={item.href}>
                      <TooltipTrigger asChild>{node}</TooltipTrigger>
                      <TooltipContent side="right" sideOffset={12}>
                        {item.title}
                        <span className="ml-1 text-muted-foreground">（规划中）</span>
                      </TooltipContent>
                    </Tooltip>
                  ) : (
                    node
                  );
                }
                const active = pathname.startsWith(item.href);
                const node = (
                  <NavLink
                    key={item.href}
                    to={item.href}
                    onClick={onNavigate}
                    className={cn(
                      "group flex items-center rounded-lg text-sm font-medium transition-colors cursor-pointer",
                      collapsed ? "justify-center px-0 py-2.5" : "gap-3 px-3 py-2.5",
                      active
                        ? "bg-primary/15 text-primary"
                        : "text-muted-foreground hover:bg-accent hover:text-foreground",
                    )}
                  >
                    <Icon className="h-4 w-4 shrink-0" />
                    {!collapsed && <span>{item.title}</span>}
                  </NavLink>
                );
                return collapsed ? (
                  <Tooltip key={item.href}>
                    <TooltipTrigger asChild>{node}</TooltipTrigger>
                    <TooltipContent side="right" sideOffset={12}>
                      {item.title}
                    </TooltipContent>
                  </Tooltip>
                ) : (
                  node
                );
              })}
            </div>
          </div>
        ))}
      </nav>

      <div className={cn("border-t", collapsed ? "p-2" : "p-3")}>
        {collapsed ? (
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                type="button"
                onClick={onShowVersion}
                aria-label={`控制面板版本 v${runningVersion.version}${hasUpdate ? "，有新版本" : ""}`}
                className="relative flex w-full cursor-pointer flex-col items-center gap-1 rounded-lg border bg-card/60 px-1 py-2 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              >
                <Tag className="h-4 w-4 shrink-0" />
                <span className="max-w-full truncate text-[10px] font-medium">v{runningVersion.version}</span>
                {hasUpdate && (
                  <span className="absolute right-1.5 top-1.5 h-1.5 w-1.5 rounded-full bg-emerald-400" />
                )}
              </button>
            </TooltipTrigger>
            <TooltipContent side="right" sideOffset={12}>
              控制面板 v{runningVersion.version}
              {hasUpdate && <span className="ml-1 text-emerald-400">· 有新版本</span>}
            </TooltipContent>
          </Tooltip>
        ) : (
          <button
            type="button"
            onClick={onShowVersion}
            title="查看控制面板版本与更新内容"
            className="flex w-full cursor-pointer items-center gap-2 rounded-lg border bg-card/60 px-3 py-2.5 text-left transition-colors hover:bg-accent focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
          >
            <Tag className="h-4 w-4 shrink-0 text-muted-foreground" />
            <div className="min-w-0 flex-1">
              <div className="truncate text-xs font-medium">控制面板 v{runningVersion.version}</div>
              <div
                className={cn(
                  "truncate text-[11px]",
                  hasUpdate ? "text-emerald-400" : "text-muted-foreground",
                )}
              >
                {hasUpdate ? "有新版本，点击查看" : `构建于 ${formatBuildTime(runningVersion.buildTime)}`}
              </div>
            </div>
            {hasUpdate && <span className="h-2 w-2 shrink-0 rounded-full bg-emerald-400" aria-hidden />}
          </button>
        )}
      </div>
    </div>
  );
}

export function AppLayout() {
  const [mobileOpen, setMobileOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(() => {
    try {
      return localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === "1";
    } catch {
      return false;
    }
  });
  const [pwdOpen, setPwdOpen] = useState(false);
  const [pwdForm, setPwdForm] = useState({ oldPassword: "", newPassword: "", confirmPassword: "" });
  const [versionOpen, setVersionOpen] = useState(false);
  const [dismissedBuildId, setDismissedBuildId] = useState(readDismissedBuildId);
  const deployedVersion = useDeployedVersion();
  const update = isNewerDeployment(deployedVersion) ? deployedVersion : null;
  const targetVersion = update ?? deployedVersion ?? runningVersion;
  const release = releaseFor(update ?? runningVersion);
  const location = useLocation();
  const navigate = useNavigate();
  const user = getUser();
  const current = visibleItems().find((n) => location.pathname.startsWith(n.href));
  const initials = (user?.name || user?.username || "?").slice(0, 1);

  // 部署完成后自动弹窗展示更新内容；同一构建只提示一次
  useEffect(() => {
    if (update && update.buildId !== dismissedBuildId) {
      setVersionOpen(true);
    }
  }, [update, dismissedBuildId]);

  const handleVersionDialogChange = (open: boolean) => {
    setVersionOpen(open);
    if (!open && update) {
      dismissBuild(update.buildId);
      setDismissedBuildId(update.buildId);
    }
  };

  const refreshToLatest = () => {
    if (update) {
      dismissBuild(update.buildId);
      setDismissedBuildId(update.buildId);
    }
    window.location.reload();
  };

  const toggleCollapsed = () => {
    setCollapsed((prev) => {
      const next = !prev;
      try {
        localStorage.setItem(SIDEBAR_COLLAPSED_KEY, next ? "1" : "0");
      } catch {
        // localStorage 不可用时仅本会话生效
      }
      return next;
    });
  };

  const handleLogout = () => {
    logout();
    navigate("/login", { replace: true });
  };

  const changePwdMutation = useMutation({
    mutationFn: () => api.changePassword(pwdForm.oldPassword, pwdForm.newPassword),
    onSuccess: () => {
      // 改密后旧 token 立即失效（后端 uat 校验），需要重新登录
      toast.success("密码已修改，请重新登录");
      setPwdOpen(false);
      setPwdForm({ oldPassword: "", newPassword: "", confirmPassword: "" });
      logout();
      navigate("/login", { replace: true });
    },
    onError: (e) => toast.error("修改失败", { description: (e as Error).message }),
  });

  const submitChangePwd = () => {
    if (!pwdForm.oldPassword || !pwdForm.newPassword) {
      toast.error("请填写旧密码和新密码");
      return;
    }
    if (pwdForm.newPassword.length < 6) {
      toast.error("新密码至少 8 位");
      return;
    }
    if (pwdForm.newPassword !== pwdForm.confirmPassword) {
      toast.error("两次输入的新密码不一致");
      return;
    }
    changePwdMutation.mutate();
  };

  return (
    <div className="min-h-screen bg-background">
      {/* 桌面侧边栏 */}
      <aside
        className={cn(
          "fixed inset-y-0 left-0 z-30 hidden border-r bg-card transition-[width] duration-200 lg:block",
          collapsed ? "w-16" : "w-64",
        )}
      >
        <SidebarContent
          collapsed={collapsed}
          onToggle={toggleCollapsed}
          hasUpdate={!!update}
          onShowVersion={() => setVersionOpen(true)}
        />
      </aside>

      {/* 移动端抽屉 */}
      {mobileOpen && (
        <div className="fixed inset-0 z-50 lg:hidden">
          <div className="absolute inset-0 bg-black/60" onClick={() => setMobileOpen(false)} />
          <aside className="absolute inset-y-0 left-0 w-64 border-r bg-card">
            <button
              className="absolute right-3 top-4 rounded-md p-1 text-muted-foreground hover:text-foreground cursor-pointer"
              onClick={() => setMobileOpen(false)}
              aria-label="关闭菜单"
            >
              <X className="h-5 w-5" />
            </button>
            <SidebarContent
              onNavigate={() => setMobileOpen(false)}
              hasUpdate={!!update}
              onShowVersion={() => {
                setMobileOpen(false);
                setVersionOpen(true);
              }}
            />
          </aside>
        </div>
      )}

      <div className={cn("transition-[padding] duration-200", collapsed ? "lg:pl-16" : "lg:pl-64")}>
        {/* 顶部栏 */}
        <header className="sticky top-0 z-20 flex h-16 items-center gap-3 border-b bg-background/80 px-4 backdrop-blur md:px-6">
          <Button
            variant="ghost"
            size="icon"
            className="lg:hidden"
            onClick={() => setMobileOpen(true)}
            aria-label="打开菜单"
          >
            <Menu className="h-5 w-5" />
          </Button>

          <div className="min-w-0 flex-1">
            <h1 className="truncate text-base font-semibold md:text-lg">{current?.title ?? "概览"}</h1>
            <p className="hidden truncate text-xs text-muted-foreground md:block">
              {current?.description}
            </p>
          </div>

          {user && (
            <Badge variant="outline" className="hidden font-mono text-[11px] sm:inline-flex">
              {ROLE_NAME[user.role] ?? user.role}
            </Badge>
          )}

          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button className="cursor-pointer rounded-full focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring">
                <Avatar className="h-8 w-8">
                  <AvatarFallback className="bg-primary/15 text-primary">{initials}</AvatarFallback>
                </Avatar>
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-56">
              <DropdownMenuLabel>
                <div className="flex flex-col">
                  <span>{user?.name || user?.username}</span>
                  <span className="text-xs font-normal text-muted-foreground">
                    @{user?.username} · {ROLE_NAME[user?.role ?? ""] ?? user?.role}
                  </span>
                </div>
              </DropdownMenuLabel>
              <DropdownMenuSeparator />
              <DropdownMenuItem onClick={() => setPwdOpen(true)}>
                <KeyRound className="h-4 w-4" />
                修改密码
              </DropdownMenuItem>
              <DropdownMenuItem onClick={handleLogout} className="text-destructive focus:text-destructive">
                <LogOut className="h-4 w-4" />
                退出登录
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </header>

        <main className="mx-auto max-w-[1400px] p-4 md:p-6">
          <Outlet />
        </main>
      </div>

      <Dialog open={pwdOpen} onOpenChange={setPwdOpen}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>修改密码</DialogTitle>
            <DialogDescription>修改当前账号「{user?.username}」的登录密码。</DialogDescription>
          </DialogHeader>
<DialogBody>
          <div className="space-y-3">
            <div className="space-y-1.5">
              <Label htmlFor="pwd-old">旧密码</Label>
              <Input id="pwd-old" type="password" value={pwdForm.oldPassword} onChange={(e) => setPwdForm({ ...pwdForm, oldPassword: e.target.value })} />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="pwd-new">新密码</Label>
              <Input id="pwd-new" type="password" value={pwdForm.newPassword} onChange={(e) => setPwdForm({ ...pwdForm, newPassword: e.target.value })} placeholder="至少 8 位" />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="pwd-confirm">确认新密码</Label>
              <Input id="pwd-confirm" type="password" value={pwdForm.confirmPassword} onChange={(e) => setPwdForm({ ...pwdForm, confirmPassword: e.target.value })} />
            </div>
          </div>
          </DialogBody>
<DialogFooter>
            <Button variant="outline" onClick={() => setPwdOpen(false)}>取消</Button>
            <Button onClick={submitChangePwd} disabled={changePwdMutation.isPending}>
              {changePwdMutation.isPending ? "修改中…" : "确认修改"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={versionOpen} onOpenChange={handleVersionDialogChange}>
        <DialogContent className="max-w-xl">
          <DialogHeader>
            <DialogTitle>{update ? "控制面板已更新" : "控制面板版本"}</DialogTitle>
            <DialogDescription>
              {update
                ? `服务端已部署新版本 v${update.version}，刷新页面即可使用最新控制面板。`
                : `当前运行 v${runningVersion.version}，已是最新部署版本。`}
            </DialogDescription>
          </DialogHeader>
          <DialogBody>
            <div className="space-y-5 pb-2">
              {update && (
                <div className="flex items-start gap-2 rounded-lg border border-emerald-500/30 bg-emerald-500/10 px-3 py-2.5 text-xs text-emerald-300">
                  <RefreshCw className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                  <span>新版本已部署。刷新后生效；未刷新时仍使用当前页面，不影响正在进行的操作。</span>
                </div>
              )}

              <div className="grid gap-3 rounded-lg border bg-muted/30 p-3 text-xs sm:grid-cols-2">
                <div className="space-y-0.5">
                  <div className="text-muted-foreground">当前运行</div>
                  <div className="font-mono text-foreground">v{runningVersion.version}</div>
                  <div className="text-[11px] text-muted-foreground">
                    {formatBuildTime(runningVersion.buildTime)}
                  </div>
                </div>
                <div className="space-y-0.5">
                  <div className="text-muted-foreground">服务端部署</div>
                  <div className="font-mono text-foreground">v{targetVersion.version}</div>
                  <div className="text-[11px] text-muted-foreground">
                    {formatBuildTime(targetVersion.buildTime)}
                  </div>
                </div>
                <div className="space-y-0.5">
                  <div className="text-muted-foreground">构建提交</div>
                  <div className="font-mono text-foreground">{targetVersion.commit || "—"}</div>
                </div>
                <div className="space-y-0.5">
                  <div className="text-muted-foreground">更新检测</div>
                  <div className="text-foreground">页面聚焦或每 60 秒自动检查</div>
                </div>
              </div>

              <div className="space-y-2">
                <div className="text-sm font-medium">
                  更新内容{release ? ` · v${release.version}` : ""}
                  {release?.date ? (
                    <span className="ml-2 text-xs font-normal text-muted-foreground">{release.date}</span>
                  ) : null}
                </div>
                {release && release.highlights.length > 0 ? (
                  <ul className="space-y-2">
                    {release.highlights.map((item) => (
                      <li key={item} className="flex gap-2 text-sm text-muted-foreground">
                        <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-400" />
                        <span className="leading-relaxed">{item}</span>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="text-sm text-muted-foreground">本次构建未记录详细更新内容。</p>
                )}
              </div>
            </div>
          </DialogBody>
          <DialogFooter>
            <Button variant="outline" onClick={() => handleVersionDialogChange(false)}>
              {update ? "稍后" : "关闭"}
            </Button>
            {update && (
              <Button onClick={refreshToLatest}>
                <RefreshCw className="h-4 w-4" />
                立即刷新
              </Button>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
