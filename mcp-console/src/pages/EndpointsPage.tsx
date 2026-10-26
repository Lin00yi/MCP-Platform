import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Link2, Pencil, Plus, Trash2 } from "lucide-react";
import { api } from "@/lib/api";
import type { EndpointVO, NamespaceVO } from "@/types";
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
  endpointKey: string;
  name: string;
  description: string;
  status: number;
}

const emptyForm: FormState = { endpointKey: "", name: "", description: "", status: 1 };

export function EndpointsPage() {
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<EndpointVO | null>(null);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [nsPicker, setNsPicker] = useState<{ ep: EndpointVO; ids: number[] } | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<EndpointVO | null>(null);

  const { data: endpoints, isLoading, isError, error, refetch } = useQuery({
    queryKey: ["endpoints"],
    queryFn: ({ signal }) => api.listEndpoints(signal),
  });
  const { data: namespaces } = useQuery<NamespaceVO[]>({
    queryKey: ["namespaces"],
    queryFn: ({ signal }) => api.listNamespaces(signal),
  });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ["endpoints"] });
    queryClient.invalidateQueries({ queryKey: ["discovery-servers"] });
  };

  const saveMutation = useMutation({
    mutationFn: () =>
      editing
        ? api.updateEndpoint(editing.id, {
            name: form.name.trim(),
            description: form.description || undefined,
            status: form.status,
          })
        : api.createEndpoint({
            endpointKey: form.endpointKey.trim(),
            name: form.name.trim(),
            description: form.description || undefined,
            status: form.status,
          }),
    onSuccess: () => {
      toast.success(editing ? "Endpoint 已更新" : "Endpoint 已创建");
      setDialogOpen(false);
      invalidate();
    },
    onError: (e) => toast.error("保存失败", { description: (e as Error).message }),
  });

  const nsMutation = useMutation({
    mutationFn: () => (nsPicker ? api.setEndpointNamespaces(nsPicker.ep.id, nsPicker.ids) : Promise.resolve()),
    onSuccess: () => {
      toast.success("绑定关系已更新");
      setNsPicker(null);
      invalidate();
    },
    onError: (e) => toast.error("保存失败", { description: (e as Error).message }),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => api.deleteEndpoint(id),
    onSuccess: () => {
      toast.success("Endpoint 已删除");
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
  const openEdit = (ep: EndpointVO) => {
    setEditing(ep);
    setForm({ endpointKey: ep.endpointKey, name: ep.name, description: ep.description ?? "", status: ep.status });
    setDialogOpen(true);
  };

  if (isLoading) return <LoadingState label="加载 Endpoint…" />;
  if (isError) return <ErrorState message={(error as Error)?.message} onRetry={() => void refetch()} />;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-lg font-semibold">Endpoints</h1>
          <p className="text-sm text-muted-foreground">
            对外 MCP 接入点：一个地址暴露若干 Namespace。登录用户可访问全部启用 Endpoint。
          </p>
        </div>
        <Button onClick={openCreate}>
          <Plus className="h-4 w-4" /> 新增 Endpoint
        </Button>
      </div>

      {(endpoints ?? []).length === 0 ? (
        <EmptyState icon={Link2} title="暂无 Endpoint" description="创建后客户端可用 /mcp/e/{key} 接入。" />
      ) : (
        <div className="rounded-lg border bg-card">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>名称</TableHead>
                <TableHead>接入地址</TableHead>
                <TableHead>Namespace</TableHead>
                <TableHead>Server / Tool</TableHead>
                <TableHead>状态</TableHead>
                <TableHead className="w-[1%] whitespace-nowrap">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(endpoints ?? []).map((ep) => (
                <TableRow key={ep.id}>
                  <TableCell>
                    <div className="font-medium">{ep.name}</div>
                    <div className="font-mono text-[11px] text-muted-foreground">{ep.endpointKey}</div>
                  </TableCell>
                  <TableCell>
                    <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">{ep.path}</code>
                  </TableCell>
                  <TableCell>
                    <div className="flex flex-wrap items-center gap-1">
                      {ep.namespaces.length === 0 ? (
                        <span className="text-xs text-muted-foreground">未绑定</span>
                      ) : (
                        ep.namespaces.map((ns) => (
                          <Badge key={ns.id} variant="outline">{ns.name}</Badge>
                        ))
                      )}
                    </div>
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    {ep.serverCount} / {ep.toolCount}
                  </TableCell>
                  <TableCell>
                    <Badge variant={ep.status === 1 ? "success" : "muted"}>{ep.status === 1 ? "启用" : "停用"}</Badge>
                  </TableCell>
                  <TableCell>
                    <RowActions>
                      <Button variant="ghost" size="xs" onClick={() => openEdit(ep)}>
                        <Pencil /> 编辑
                      </Button>
                      <Button
                        variant="ghost"
                        size="xs"
                        onClick={() => setNsPicker({ ep, ids: ep.namespaces.map((n) => n.id) })}
                      >
                        <Link2 /> Namespace
                      </Button>
                      <Button variant="ghost" size="xs" className="text-destructive" onClick={() => setDeleteTarget(ep)}>
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
            <DialogTitle>{editing ? "编辑 Endpoint" : "新增 Endpoint"}</DialogTitle>
            <DialogDescription>Endpoint Key 会出现在客户端地址中，创建后不可修改。</DialogDescription>
          </DialogHeader>
          <DialogBody>
            <div className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="ep-key">Endpoint Key</Label>
                <Input
                  id="ep-key"
                  value={form.endpointKey}
                  onChange={(e) => setForm({ ...form, endpointKey: e.target.value })}
                  disabled={!!editing}
                  placeholder="例如 team-pcb"
                  className="font-mono"
                />
                <p className="text-xs text-muted-foreground">
                  {form.endpointKey ? `/mcp/e/${form.endpointKey}` : "/mcp/e/{key}"}
                </p>
              </div>
              <div className="space-y-2">
                <Label htmlFor="ep-name">名称</Label>
                <Input id="ep-name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="ep-desc">说明</Label>
                <Input id="ep-desc" value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
              </div>
              <div className="flex items-center gap-2">
                <Switch checked={form.status === 1} onCheckedChange={(v) => setForm({ ...form, status: v ? 1 : 0 })} id="ep-status" />
                <Label htmlFor="ep-status">启用</Label>
              </div>
            </div>
          </DialogBody>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogOpen(false)}>取消</Button>
            <Button
              onClick={() => saveMutation.mutate()}
              disabled={!form.name.trim() || (!editing && !form.endpointKey.trim()) || saveMutation.isPending}
            >
              {saveMutation.isPending ? "保存中…" : "保存"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={!!nsPicker} onOpenChange={(o) => !o && setNsPicker(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>绑定 Namespace</DialogTitle>
            <DialogDescription>{nsPicker?.ep.name} · 选择该接入点要暴露的 Namespace。</DialogDescription>
          </DialogHeader>
          <DialogBody>
            <div className="max-h-72 space-y-1 overflow-y-auto rounded-md border p-2">
              {(namespaces ?? []).length === 0 ? (
                <p className="p-3 text-sm text-muted-foreground">还没有 Namespace，请先创建。</p>
              ) : (
                (namespaces ?? []).map((ns) => (
                  <label key={ns.id} className="flex cursor-pointer items-center gap-2 rounded px-2 py-1.5 text-sm hover:bg-accent">
                    <Checkbox
                      checked={nsPicker?.ids.includes(ns.id) ?? false}
                      onCheckedChange={(v) =>
                        setNsPicker((p) => (p ? { ...p, ids: v ? [...p.ids, ns.id] : p.ids.filter((x) => x !== ns.id) } : p))
                      }
                    />
                    <span>{ns.name}</span>
                    <code className="ml-auto font-mono text-xs text-muted-foreground">{ns.prefix}</code>
                  </label>
                ))
              )}
            </div>
          </DialogBody>
          <DialogFooter>
            <Button variant="outline" onClick={() => setNsPicker(null)}>取消</Button>
            <Button onClick={() => nsMutation.mutate()} disabled={nsMutation.isPending}>
              {nsMutation.isPending ? "保存中…" : "保存"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={!!deleteTarget}
        onOpenChange={(o) => !o && setDeleteTarget(null)}
        title="删除 Endpoint"
        description={`确认删除「${deleteTarget?.name ?? ""}」？Namespace 绑定会一并清理，且不可恢复。请输入 Endpoint 名称以确认。`}
        confirmText="删除"
        confirmValue={deleteTarget?.name}
        confirmValueLabel={`请输入 Endpoint 名称：${deleteTarget?.name ?? ""}`}
        onConfirm={() => deleteTarget && deleteMutation.mutate(deleteTarget.id)}
        loading={deleteMutation.isPending}
      />
    </div>
  );
}
