import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Cable, Pencil, Plus, Trash2 } from "lucide-react";
import { api } from "@/lib/api";
import { formatDateTime } from "@/lib/utils";
import type { McpResource } from "@/types";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
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
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { LoadingState, EmptyState, ErrorState } from "@/components/PageState";
import { ConfirmDialog } from "@/components/ConfirmDialog";

export function ServerResourcesTab({ serverId }: { serverId: number }) {
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<McpResource | null>(null);
  const [form, setForm] = useState({ uri: "", name: "", status: 1, resJson: "", version: "1.0.0", tags: "" });
  const [deleteTarget, setDeleteTarget] = useState<McpResource | null>(null);

  const { data: allResources, isLoading, isError, error, refetch } = useQuery({
    queryKey: ["resources"],
    queryFn: ({ signal }) => api.listResources(signal),
  });
  const resources = useMemo(() => (allResources ?? []).filter((r) => r.serverId === serverId), [allResources, serverId]);
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["resources"] });

  const save = useMutation({
    mutationFn: () => (editing ? api.updateResource(editing.id, { ...form, serverId }) : api.createResource({ ...form, serverId })),
    onSuccess: () => { toast.success(editing ? "Resource 已更新" : "Resource 已创建"); setDialogOpen(false); invalidate(); },
    onError: (e) => toast.error("保存失败", { description: (e as Error).message }),
  });
  const toggle = useMutation({
    mutationFn: (r: McpResource) => api.updateResource(r.id, { status: r.status === 1 ? 0 : 1 }),
    onSuccess: () => invalidate(),
    onError: (e) => toast.error("更新失败", { description: (e as Error).message }),
  });
  const del = useMutation({
    mutationFn: (id: number) => api.deleteResource(id),
    onSuccess: () => { toast.success("已删除"); invalidate(); },
    onError: (e) => toast.error("删除失败", { description: (e as Error).message }),
  });

  const openCreate = () => { setEditing(null); setForm({ uri: "", name: "", status: 1, resJson: "", version: "1.0.0", tags: "" }); setDialogOpen(true); };
  const openEdit = (r: McpResource) => { setEditing(r); setForm({ uri: r.uri, name: r.name, status: r.status, resJson: r.resJson, version: r.version ?? "1.0.0", tags: r.tags ?? "" }); setDialogOpen(true); };

  if (isLoading) return <LoadingState label="加载 Resource…" />;
  if (isError) return <ErrorState message={(error as Error)?.message} onRetry={() => void refetch()} />;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-muted-foreground">MCP Resource（resources/list 暴露），共 {resources.length} 个。</p>
        <Button onClick={openCreate}><Plus className="h-4 w-4" /> 新增 Resource</Button>
      </div>
      <div className="rounded-xl border bg-card">
        {resources.length === 0 ? (
          <EmptyState icon={Cable} title="暂无 Resource" />
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>ID</TableHead>
                <TableHead>名称</TableHead>
                <TableHead>URI</TableHead>
                <TableHead>标签 / Token</TableHead>
                <TableHead>版本</TableHead>
                <TableHead>状态</TableHead>
                <TableHead>更新时间</TableHead>
                <TableHead className="w-[1%] whitespace-nowrap">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {resources.map((r) => (
                <TableRow key={r.id}>
                  <TableCell className="font-mono text-muted-foreground">{r.id}</TableCell>
                  <TableCell className="font-medium">{r.name}</TableCell>
                  <TableCell><code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">{r.uri}</code></TableCell>
                  <TableCell>
                    <div className="flex flex-wrap gap-1">
                      {(r.tags ?? "").split(",").filter(Boolean).map((t) => <Badge key={t} variant="outline" className="text-[10px]">{t}</Badge>)}
                      {r.tokenEstimate ? <span className="text-[10px] text-muted-foreground">≈{r.tokenEstimate} tok</span> : null}
                    </div>
                  </TableCell>
                  <TableCell className="font-mono text-xs text-muted-foreground">v{r.version ?? "1.0.0"}</TableCell>
                  <TableCell>
                    <StatusSwitch
                      checked={r.status === 1}
                      onCheckedChange={() => toggle.mutate(r)}
                      aria-label="切换状态"
                    />
                  </TableCell>
                  <TableCell className="text-muted-foreground">{formatDateTime(r.updateTime)}</TableCell>
                  <TableCell>
                    <RowActions>
                      <Button variant="ghost" size="iconSm" aria-label="编辑" onClick={() => openEdit(r)}><Pencil /></Button>
                      <Button variant="ghost" size="iconSm" aria-label="删除" className="text-destructive" onClick={() => setDeleteTarget(r)}><Trash2 /></Button>
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
            <DialogTitle>{editing ? "编辑 Resource" : "新增 Resource"}</DialogTitle>
          </DialogHeader>
<DialogBody>
          <div className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="r-uri">URI</Label>
              <Input id="r-uri" value={form.uri} onChange={(e) => setForm({ ...form, uri: e.target.value })} className="font-mono" />
            </div>
            <div className="space-y-2">
              <Label htmlFor="r-name">名称</Label>
              <Input id="r-name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="r-version">版本（编辑时不改则自动 +0.0.1，填更高版本号则用指定值）</Label>
              <Input id="r-version" value={form.version} onChange={(e) => setForm({ ...form, version: e.target.value })} className="font-mono" placeholder="1.0.0" />
            </div>
            <div className="space-y-2">
              <Label htmlFor="r-tags">标签（逗号分隔）</Label>
              <Input id="r-tags" value={form.tags} onChange={(e) => setForm({ ...form, tags: e.target.value })} placeholder="docs,faq" />
            </div>
            <div className="space-y-2">
              <Label htmlFor="r-res">定义 (res_json)</Label>
              <Textarea id="r-res" rows={5} value={form.resJson} onChange={(e) => setForm({ ...form, resJson: e.target.value })} className="font-mono text-xs" />
            </div>
            <div className="flex items-center gap-2">
              <Switch checked={form.status === 1} onCheckedChange={(v) => setForm({ ...form, status: v ? 1 : 0 })} id="r-status" />
              <Label htmlFor="r-status">启用</Label>
            </div>
          </div>
          </DialogBody>
<DialogFooter>
            <Button variant="outline" onClick={() => setDialogOpen(false)}>取消</Button>
            <Button onClick={() => save.mutate()} disabled={save.isPending}>{save.isPending ? "保存中…" : "保存"}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={deleteTarget !== null}
        onOpenChange={(o) => !o && setDeleteTarget(null)}
        title="删除 Resource"
        description={`确认删除 Resource「${deleteTarget?.name ?? ""}」（${deleteTarget?.uri ?? ""}）？此操作不可恢复。请输入 Resource URI 以确认。`}
        confirmValue={deleteTarget?.uri}
        confirmValueLabel={`请输入 Resource URI：${deleteTarget?.uri ?? ""}`}
        loading={del.isPending}
        onConfirm={() => {
          if (deleteTarget) {
            del.mutate(deleteTarget.id, { onSuccess: () => setDeleteTarget(null) });
          }
        }}
      />
    </div>
  );
}
