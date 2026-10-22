import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Pencil, Plus, Trash2, Users } from "lucide-react";
import { api, type AdminUserRequest, type AdminUserVO } from "@/lib/api";
import { getUser, isSuperAdmin } from "@/lib/auth";
import { cn, formatDateTime } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { StatusSwitch } from "@/components/ui/status-switch";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { RowActions } from "@/components/ui/row-actions";
import {
  Dialog,
  DialogContent,
  DialogBody,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { LoadingState, EmptyState } from "@/components/PageState";
import { ConfirmDialog } from "@/components/ConfirmDialog";

/** 控制台可分配的角色；平台超管只能由初始化脚本创建，不在此列 */
const ROLE_OPTIONS = [
  { value: "ADMIN", label: "管理员" },
  { value: "DEVELOPER", label: "开发者" },
  { value: "OPERATOR", label: "运维" },
  { value: "USER", label: "用户" },
];

const ROLE_LABEL: Record<string, string> = {
  SUPER_ADMIN: "平台超管",
  ...Object.fromEntries(ROLE_OPTIONS.map((r) => [r.value, r.label])),
};

const ROLE_COLOR: Record<string, string> = {
  SUPER_ADMIN: "text-rose-400 bg-rose-400/10 border-rose-400/20",
  ADMIN: "text-amber-400 bg-amber-400/10 border-amber-400/20",
  DEVELOPER: "text-sky-400 bg-sky-400/10 border-sky-400/20",
  OPERATOR: "text-violet-400 bg-violet-400/10 border-violet-400/20",
  USER: "text-muted-foreground bg-muted border-border",
};

const emptyForm = { username: "", password: "", name: "", role: "USER", status: 1, tenantIds: [] as number[] };

export function UsersPage() {
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<AdminUserVO | null>(null);
  const [form, setForm] = useState(emptyForm);
  const [deleteTarget, setDeleteTarget] = useState<AdminUserVO | null>(null);

  const { data: users, isLoading } = useQuery({ queryKey: ["users"], queryFn: api.listUsers });
  const { data: tenants } = useQuery({ queryKey: ["tenants"], queryFn: api.listTenants });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["users"] });

  const saveMutation = useMutation({
    mutationFn: async () => {
      const payload: AdminUserRequest = {
        name: form.name,
        status: form.status,
      };
      // 角色：超管或新建用户时直接下发；管理员编辑他人角色走审批
      if (isSuperAdmin() || !editing) {
        payload.role = form.role;
      }
      // 仅平台超管需要手动分配租户；租户管理员由后端自动归属
      if (isSuperAdmin()) {
        payload.tenantIds = form.tenantIds;
      }
      if (editing) {
        if (form.password) payload.password = form.password;
        await api.updateUser(editing.id, payload);
        const roleChangePending = !isSuperAdmin() && form.role !== editing.role;
        if (roleChangePending) {
          await api.createApproval({
            action: "USER_ROLE_UPDATE",
            userId: editing.id,
            payload: { role: form.role },
            reason: `将用户 ${editing.username} 的角色调整为 ${ROLE_LABEL[form.role] ?? form.role}`,
          });
        }
        return { pendingRole: roleChangePending };
      }
      payload.username = form.username;
      payload.password = form.password;
      await api.createUser(payload);
      return { pendingRole: false };
    },
    onSuccess: ({ pendingRole }) => {
      if (pendingRole) {
        toast.success("用户资料已更新；角色变更已提交平台超管审批");
      } else {
        toast.success(editing ? "用户已更新" : "用户已创建");
      }
      setDialogOpen(false);
      invalidate();
      queryClient.invalidateQueries({ queryKey: ["approvals"] });
    },
    onError: (e) => toast.error("保存失败", { description: (e as Error).message }),
  });

  const toggleStatusMutation = useMutation({
    mutationFn: (u: AdminUserVO) => api.updateUser(u.id, { status: u.status === 1 ? 0 : 1 }),
    onSuccess: (_data, u) => {
      toast.success(u.status === 1 ? "用户已停用" : "用户已启用");
      invalidate();
    },
    onError: (e) => toast.error("操作失败", { description: (e as Error).message }),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => api.deleteUser(id),
    onSuccess: () => {
      toast.success("用户已删除");
      invalidate();
    },
    onError: (e) => toast.error("删除失败", { description: (e as Error).message }),
  });

  const openCreate = () => {
    setEditing(null);
    setForm(emptyForm);
    setDialogOpen(true);
  };

  const openEdit = (u: AdminUserVO) => {
    setEditing(u);
    setForm({ username: u.username, password: "", name: u.name, role: u.role, status: u.status, tenantIds: u.tenantIds ?? [] });
    setDialogOpen(true);
  };

  const toggleTenant = (id: number, checked: boolean) => {
    setForm((f) => ({
      ...f,
      tenantIds: checked ? [...f.tenantIds, id] : f.tenantIds.filter((x) => x !== id),
    }));
  };

  if (isLoading) return <LoadingState label="加载用户…" />;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-muted-foreground">
          后台账号与角色：平台超管（全局）、管理员/开发者/运维/用户（按租户分配）。
        </p>
        <Button onClick={openCreate}>
          <Plus className="h-4 w-4" />
          新增用户
        </Button>
      </div>

      <div className="rounded-xl border bg-card">
        {users && users.length === 0 ? (
          <EmptyState icon={Users} title="暂无用户" />
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>ID</TableHead>
                <TableHead>用户名</TableHead>
                <TableHead>姓名</TableHead>
                <TableHead>角色</TableHead>
                <TableHead>租户</TableHead>
                <TableHead>状态</TableHead>
                <TableHead>创建时间</TableHead>
                <TableHead className="w-[1%] whitespace-nowrap">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {users?.map((u) => (
                <TableRow key={u.id}>
                  <TableCell className="font-mono text-muted-foreground">{u.id}</TableCell>
                  <TableCell className="font-mono">{u.username}</TableCell>
                  <TableCell className="font-medium">{u.name}</TableCell>
                  <TableCell>
                    <Badge variant="outline" className={cn(ROLE_COLOR[u.role] ?? ROLE_COLOR.USER)}>
                      {ROLE_LABEL[u.role] ?? u.role}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-xs text-muted-foreground">
                    {u.role === "SUPER_ADMIN"
                      ? "全部租户"
                      : (u.tenantIds ?? []).length > 0
                        ? (u.tenantIds ?? []).map((t) => tenants?.find((x) => x.id === t)?.name ?? t).join(", ")
                        : "未分配"}
                  </TableCell>
                  <TableCell>
                    <StatusSwitch
                      checked={u.status === 1}
                      onCheckedChange={() => toggleStatusMutation.mutate(u)}
                      disabled={u.id === getUser()?.id || (toggleStatusMutation.isPending && toggleStatusMutation.variables?.id === u.id)}
                      title={u.id === getUser()?.id ? "不能停用当前登录账号" : undefined}
                      aria-label="切换状态"
                    />
                  </TableCell>
                  <TableCell className="text-muted-foreground">{formatDateTime(u.createTime)}</TableCell>
                  <TableCell>
                    <RowActions>
                      <Button variant="ghost" size="xs" onClick={() => openEdit(u)}>
                        <Pencil /> 编辑
                      </Button>
                      {u.id !== getUser()?.id && (
                        <Button variant="ghost" size="xs" className="text-destructive" onClick={() => setDeleteTarget(u)}>
                          <Trash2 /> 删除
                        </Button>
                      )}
                    </RowActions>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </div>

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>{editing ? "编辑用户" : "新增用户"}</DialogTitle>
            <DialogDescription>分配角色与租户（可多选）。</DialogDescription>
          </DialogHeader>
<DialogBody>
          <div className="space-y-4">
            {!editing && (
              <div className="space-y-2">
                <Label htmlFor="u-username">用户名</Label>
                <Input id="u-username" value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} placeholder="登录名" />
              </div>
            )}
            <div className="space-y-2">
              <Label htmlFor="u-password">{editing ? "新密码（留空不修改）" : "密码"}</Label>
              <Input id="u-password" type="password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} placeholder="••••••" />
            </div>
            <div className="space-y-2">
              <Label htmlFor="u-name">姓名</Label>
              <Input id="u-name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="显示名" />
            </div>
            <div className="space-y-2">
              <Label>角色</Label>
              <Select
                value={form.role}
                onValueChange={(v) => setForm({ ...form, role: v })}
                disabled={
                  editing !== null &&
                  (editing.id === getUser()?.id ||
                    (editing.role === "SUPER_ADMIN" && !isSuperAdmin()) ||
                    (editing.role === "ADMIN" && !isSuperAdmin()))
                }
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {ROLE_OPTIONS.filter(
                    (r) => isSuperAdmin() || editing !== null || r.value !== "ADMIN",
                  ).map((r) => (
                    <SelectItem key={r.value} value={r.value}>{r.label}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {editing !== null && editing.id === getUser()?.id && (
                <p className="text-xs text-muted-foreground">不能编辑自己的角色</p>
              )}
              {editing !== null && !isSuperAdmin() && editing.id !== getUser()?.id && form.role !== editing.role && (
                <p className="text-xs text-amber-400">角色变更将提交平台超管审批，通过后生效</p>
              )}
              {editing !== null && !isSuperAdmin() && form.role === editing.role && (
                <p className="text-xs text-muted-foreground">其他资料可直接保存；角色变更需超管审批</p>
              )}
              <p className="text-xs text-muted-foreground">
                平台超管仅能通过初始化脚本创建，控制台不支持新增或授予。
              </p>
            </div>
            <div className="flex items-center gap-2">
              <Switch checked={form.status === 1} onCheckedChange={(v) => setForm({ ...form, status: v ? 1 : 0 })} id="u-status" />
              <Label htmlFor="u-status">启用</Label>
            </div>
            {isSuperAdmin() && form.role !== "SUPER_ADMIN" && (
              <div className="space-y-2">
                <Label>分配租户（可多选）</Label>
                <div className="max-h-40 space-y-1 overflow-y-auto rounded-md border p-2">
                  {tenants?.map((t) => (
                    <label key={t.id} className="flex cursor-pointer items-center gap-2 rounded px-2 py-1.5 text-sm hover:bg-accent">
                      <Checkbox checked={form.tenantIds.includes(t.id)} onCheckedChange={(c) => toggleTenant(t.id, c === true)} />
                      <span>{t.name}</span>
                      <span className="ml-auto font-mono text-xs text-muted-foreground">#{t.id}</span>
                    </label>
                  ))}
                </div>
              </div>
            )}
          </div>
          </DialogBody>
<DialogFooter>
            <Button variant="outline" onClick={() => setDialogOpen(false)}>取消</Button>
            <Button onClick={() => saveMutation.mutate()} disabled={saveMutation.isPending}>
              {saveMutation.isPending ? "保存中…" : "保存"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={deleteTarget !== null}
        onOpenChange={(o) => !o && setDeleteTarget(null)}
        title="删除用户"
        description={`删除后不可恢复。请输入用户名以确认。`}
        confirmValue={deleteTarget?.username}
        confirmValueLabel={`请输入用户名：${deleteTarget?.username ?? ""}`}
        loading={deleteMutation.isPending}
        onConfirm={() => {
          if (deleteTarget) {
            deleteMutation.mutate(deleteTarget.id, { onSuccess: () => setDeleteTarget(null) });
          }
        }}
      />
    </div>
  );
}
