import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Boxes, Pencil, Plus, Server, Trash2, UserCog } from "lucide-react";
import { api } from "@/lib/api";
import type { NamespaceVO } from "@/types";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Checkbox } from "@/components/ui/checkbox";
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
  DialogBody,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { LoadingState, EmptyState, ErrorState } from "@/components/PageState";
import { ConfirmDialog } from "@/components/ConfirmDialog";

interface FormState {
  name: string;
  prefix: string;
  description: string;
  status: number;
}

const emptyForm: FormState = { name: "", prefix: "", description: "", status: 1 };

export function NamespacesPage() {
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<NamespaceVO | null>(null);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [members, setMembers] = useState<{ ns: NamespaceVO; ids: number[] } | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<NamespaceVO | null>(null);

  const { data: namespaces, isLoading, isError, error } = useQuery({
    queryKey: ["namespaces"],
    queryFn: api.listNamespaces,
  });
  const { data: servers } = useQuery({ queryKey: ["servers"], queryFn: api.listServers });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ["namespaces"] });
    queryClient.invalidateQueries({ queryKey: ["endpoints"] });
  };

  const saveMutation = useMutation({
    mutationFn: () =>
      editing
        ? api.updateNamespace(editing.id, {
            name: form.name.trim(),
            description: form.description || undefined,
            status: form.status,
          })
        : api.createNamespace({
            name: form.name.trim(),
            prefix: form.prefix.trim(),
            description: form.description || undefined,
            status: form.status,
          }),
    onSuccess: () => {
      toast.success(editing ? "Namespace 已更新" : "Namespace 已创建");
      setDialogOpen(false);
      invalidate();
    },
    onError: (e) => toast.error("保存失败", { description: (e as Error).message }),
  });

  const membersMutation = useMutation({
    mutationFn: () => (members ? api.setNamespaceServers(members.ns.id, members.ids) : Promise.resolve()),
    onSuccess: () => {
      toast.success("成员已更新");
      setMembers(null);
      invalidate();
    },
    onError: (e) => toast.error("保存失败", { description: (e as Error).message }),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => api.deleteNamespace(id),
    onSuccess: () => {
      toast.success("Namespace 已删除");
      setDeleteTarget(null);
      invalidate();
    },
    onError: (e) => toast.error("删除失败", { description: (e as Error).message }),
  });

  const openCreate = () => {
    setEditing(null);
    setForm(emptyForm);
    setDialogOpen(true);
  };
  const openEdit = (ns: NamespaceVO) => {
    setEditing(ns);
    setForm({ name: ns.name, prefix: ns.prefix, description: ns.description ?? "", status: ns.status });
    setDialogOpen(true);
  };
  const openMembers = (ns: NamespaceVO) => {
    setMembers({ ns, ids: ns.servers.map((s) => s.id) });
  };

  if (isLoading) return <LoadingState label="加载 Namespace…" />;
  if (isError) return <ErrorState message={(error as Error)?.message} />;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-lg font-semibold">Namespaces</h1>
          <p className="text-sm text-muted-foreground">
            把一组 MCP Server 归到同一命名空间，工具名统一为 <code className="font-mono">{"{前缀}__{tool}"}</code>；前缀创建后不可修改。
          </p>
        </div>
        <Button onClick={openCreate}>
          <Plus className="h-4 w-4" /> 新增 Namespace
        </Button>
      </div>

      {(namespaces ?? []).length === 0 ? (
        <EmptyState icon={Boxes} title="暂无 Namespace" />
      ) : (
        <div className="rounded-lg border bg-card">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>名称</TableHead>
                <TableHead>前缀</TableHead>
                <TableHead>成员 Server</TableHead>
                <TableHead>Tool 数</TableHead>
                <TableHead>状态</TableHead>
                <TableHead>更新时间</TableHead>
                <TableHead className="w-[1%] whitespace-nowrap">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(namespaces ?? []).map((ns) => (
                <TableRow key={ns.id}>
                  <TableCell>
                    <div className="font-medium">{ns.name}</div>
                    {ns.description && <div className="text-xs text-muted-foreground">{ns.description}</div>}
                  </TableCell>
                  <TableCell>
                    <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">{ns.prefix}</code>
                  </TableCell>
                  <TableCell>
                    <div className="flex flex-wrap items-center gap-1">
                      {ns.servers.length === 0 ? (
                        <span className="text-xs text-muted-foreground">未配置</span>
                      ) : (
                        ns.servers.map((s) => (
                          <Badge key={s.id} variant="outline" className="gap-1">
                            <Server className="h-3 w-3" /> {s.name}
                          </Badge>
                        ))
                      )}
                    </div>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{ns.toolCount}</TableCell>
                  <TableCell>
                    <Badge variant={ns.status === 1 ? "success" : "muted"}>{ns.status === 1 ? "启用" : "停用"}</Badge>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{ns.updateTime ? String(ns.updateTime).slice(0, 19).replace("T", " ") : "—"}</TableCell>
                  <TableCell>
                    <RowActions>
                      <Button variant="ghost" size="xs" onClick={() => openEdit(ns)}>
                        <Pencil /> 编辑
                      </Button>
                      <Button variant="ghost" size="xs" onClick={() => openMembers(ns)}>
                        <UserCog /> 成员
                      </Button>
                      <Button variant="ghost" size="xs" className="text-destructive" onClick={() => setDeleteTarget(ns)}>
                        <Trash2 /> 删除
                      </Button>
                    </RowActions>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{editing ? "编辑 Namespace" : "新增 Namespace"}</DialogTitle>
            <DialogDescription>前缀创建后不可修改；同一 Namespace 内 Tool code 不能重复。</DialogDescription>
          </DialogHeader>
          <DialogBody>
            <div className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="ns-name">名称</Label>
                <Input id="ns-name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="ns-prefix">前缀</Label>
                <Input
                  id="ns-prefix"
                  value={form.prefix}
                  onChange={(e) => setForm({ ...form, prefix: e.target.value })}
                  disabled={!!editing}
                  placeholder="例如 pcb"
                  className="font-mono"
                />
                <p className="text-xs text-muted-foreground">
                  工具名会变成 <code className="font-mono">{form.prefix || "prefix"}__orders_get</code>，客户端依赖它，务必稳定。
                </p>
              </div>
              <div className="space-y-2">
                <Label htmlFor="ns-desc">说明</Label>
                <Input id="ns-desc" value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
              </div>
              <div className="flex items-center gap-2">
                <Switch checked={form.status === 1} onCheckedChange={(v) => setForm({ ...form, status: v ? 1 : 0 })} id="ns-status" />
                <Label htmlFor="ns-status">启用</Label>
              </div>
            </div>
          </DialogBody>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogOpen(false)}>取消</Button>
            <Button onClick={() => saveMutation.mutate()} disabled={!form.name.trim() || (!editing && !form.prefix.trim()) || saveMutation.isPending}>
              {saveMutation.isPending ? "保存中…" : "保存"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={!!members} onOpenChange={(o) => !o && setMembers(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>成员 Server</DialogTitle>
            <DialogDescription>{members?.ns.name} · 选择要归入该 Namespace 的 Server。</DialogDescription>
          </DialogHeader>
          <DialogBody>
            <div className="max-h-72 space-y-1 overflow-y-auto rounded-md border p-2">
              {(servers ?? []).map((s) => (
                <label key={s.id} className="flex cursor-pointer items-center gap-2 rounded px-2 py-1.5 text-sm hover:bg-accent">
                  <Checkbox
                    checked={members?.ids.includes(s.id) ?? false}
                    onCheckedChange={(v) =>
                      setMembers((m) =>
                        m ? { ...m, ids: v ? [...m.ids, s.id] : m.ids.filter((x) => x !== s.id) } : m,
                      )
                    }
                  />
                  <span className="truncate">{s.name}</span>
                  <code className="ml-auto font-mono text-xs text-muted-foreground">{s.serverKey}</code>
                </label>
              ))}
            </div>
          </DialogBody>
          <DialogFooter>
            <Button variant="outline" onClick={() => setMembers(null)}>取消</Button>
            <Button onClick={() => membersMutation.mutate()} disabled={membersMutation.isPending}>
              {membersMutation.isPending ? "保存中…" : "保存成员"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={!!deleteTarget}
        onOpenChange={(o) => !o && setDeleteTarget(null)}
        title="删除 Namespace"
        description={`确认删除「${deleteTarget?.name ?? ""}」？绑定到 Endpoint 的关系会一并清理，且不可恢复。请输入 Namespace 名称以确认。`}
        confirmText="删除"
        confirmValue={deleteTarget?.name}
        confirmValueLabel={`请输入 Namespace 名称：${deleteTarget?.name ?? ""}`}
        onConfirm={() => deleteTarget && deleteMutation.mutate(deleteTarget.id)}
        loading={deleteMutation.isPending}
      />
    </div>
  );
}
