import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Building2, Pencil, Plus, Trash2 } from "lucide-react";
import { api } from "@/lib/api";
import { formatDateTime } from "@/lib/utils";
import type { Tenant } from "@/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { StatusSwitch } from "@/components/ui/status-switch";
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
import { LoadingState, EmptyState, ErrorState } from "@/components/PageState";
import { ConfirmDialog } from "@/components/ConfirmDialog";

const emptyForm = { name: "", status: 1 };

export function TenantsPage() {
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<Tenant | null>(null);
  const [form, setForm] = useState(emptyForm);
  const [deleteTarget, setDeleteTarget] = useState<Tenant | null>(null);

  const { data: tenants, isLoading, isError, error, refetch } = useQuery({
    queryKey: ["tenants"],
    queryFn: ({ signal }) => api.listTenants(signal),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["tenants"] });

  const saveMutation = useMutation({
    mutationFn: () =>
      editing ? api.updateTenant(editing.id, form) : api.createTenant(form),
    onSuccess: () => {
      toast.success(editing ? "租户已更新" : "租户已创建");
      setDialogOpen(false);
      invalidate();
    },
    onError: (e) => toast.error("保存失败", { description: (e as Error).message }),
  });

  const toggleMutation = useMutation({
    mutationFn: (t: Tenant) => api.updateTenant(t.id, { status: t.status === 1 ? 0 : 1 }),
    onSuccess: () => invalidate(),
    onError: (e) => toast.error("更新失败", { description: (e as Error).message }),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => api.deleteTenant(id),
    onSuccess: () => {
      toast.success("租户已删除");
      invalidate();
    },
    onError: (e) => toast.error("删除失败", { description: (e as Error).message }),
  });

  const openCreate = () => {
    setEditing(null);
    setForm(emptyForm);
    setDialogOpen(true);
  };

  const openEdit = (t: Tenant) => {
    setEditing(t);
    setForm({ name: t.name, status: t.status });
    setDialogOpen(true);
  };

  if (isLoading) return <LoadingState label="加载租户…" />;
  if (isError) return <ErrorState message={(error as Error)?.message} onRetry={() => void refetch()} />;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-muted-foreground">
          租户（<span className="font-mono">tenant</span>），一个租户可拥有多个 MCP Server，共 {tenants?.length ?? 0} 个。
        </p>
        <Button onClick={openCreate}>
          <Plus className="h-4 w-4" />
          新增租户
        </Button>
      </div>

      <div className="rounded-xl border bg-card">
        {tenants && tenants.length === 0 ? (
          <EmptyState icon={Building2} title="暂无租户" description="点击「新增租户」创建" />
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>ID</TableHead>
                <TableHead>租户名称</TableHead>
                <TableHead>状态</TableHead>
                <TableHead>更新时间</TableHead>
                <TableHead className="w-[1%] whitespace-nowrap">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {tenants?.map((t) => (
                <TableRow key={t.id}>
                  <TableCell className="font-mono text-muted-foreground">{t.id}</TableCell>
                  <TableCell className="font-medium">{t.name}</TableCell>
                  <TableCell>
                    <StatusSwitch
                      checked={t.status === 1}
                      onCheckedChange={() => toggleMutation.mutate(t)}
                      aria-label="切换状态"
                    />
                  </TableCell>
                  <TableCell className="text-muted-foreground">{formatDateTime(t.updateTime)}</TableCell>
                  <TableCell>
                    <RowActions>
                      <Button variant="ghost" size="xs" onClick={() => openEdit(t)}>
                        <Pencil /> 编辑
                      </Button>
                      <Button variant="ghost" size="xs" className="text-destructive" onClick={() => setDeleteTarget(t)}>
                        <Trash2 /> 删除
                      </Button>
                    </RowActions>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </div>

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{editing ? "编辑租户" : "新增租户"}</DialogTitle>
            <DialogDescription>租户是 MCP Server 的归属与管理域。</DialogDescription>
          </DialogHeader>
<DialogBody>
          <div className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="t-name">租户名称</Label>
              <Input
                id="t-name"
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
                placeholder="crm-tenant"
              />
            </div>
            <div className="flex items-center gap-2">
              <Switch
                checked={form.status === 1}
                onCheckedChange={(v) => setForm({ ...form, status: v ? 1 : 0 })}
                id="t-status"
              />
              <Label htmlFor="t-status">启用</Label>
            </div>
          </div>
          </DialogBody>
<DialogFooter>
            <Button variant="outline" onClick={() => setDialogOpen(false)}>
              取消
            </Button>
            <Button onClick={() => saveMutation.mutate()} disabled={saveMutation.isPending}>
              {saveMutation.isPending ? "保存中…" : "保存"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={deleteTarget !== null}
        onOpenChange={(o) => !o && setDeleteTarget(null)}
        title="删除租户"
        description={`确认删除租户「${deleteTarget?.name ?? ""}」？删除后其下 Server 与 Tool 将失去归属，且不可恢复。请输入租户名称以确认。`}
        confirmValue={deleteTarget?.name}
        confirmValueLabel={`请输入租户名称：${deleteTarget?.name ?? ""}`}
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
