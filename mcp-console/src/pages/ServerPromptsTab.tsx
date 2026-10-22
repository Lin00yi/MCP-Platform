import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Braces, Pencil, Plus, Trash2 } from "lucide-react";
import { api } from "@/lib/api";
import { formatDateTime } from "@/lib/utils";
import type { McpPrompt } from "@/types";
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

export function ServerPromptsTab({ serverId }: { serverId: number }) {
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<McpPrompt | null>(null);
  const [form, setForm] = useState({ name: "", status: 1, resJson: "", version: "1.0.0", tags: "" });
  const [deleteTarget, setDeleteTarget] = useState<McpPrompt | null>(null);

  const { data: allPrompts, isLoading, isError, error } = useQuery({ queryKey: ["prompts"], queryFn: api.listPrompts });
  const prompts = useMemo(() => (allPrompts ?? []).filter((p) => p.serverId === serverId), [allPrompts, serverId]);
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["prompts"] });

  const save = useMutation({
    mutationFn: () => (editing ? api.updatePrompt(editing.id, { ...form, serverId }) : api.createPrompt({ ...form, serverId })),
    onSuccess: () => { toast.success(editing ? "Prompt 已更新" : "Prompt 已创建"); setDialogOpen(false); invalidate(); },
    onError: (e) => toast.error("保存失败", { description: (e as Error).message }),
  });
  const toggle = useMutation({
    mutationFn: (p: McpPrompt) => api.updatePrompt(p.id, { status: p.status === 1 ? 0 : 1 }),
    onSuccess: () => invalidate(),
    onError: (e) => toast.error("更新失败", { description: (e as Error).message }),
  });
  const del = useMutation({
    mutationFn: (id: number) => api.deletePrompt(id),
    onSuccess: () => { toast.success("已删除"); invalidate(); },
    onError: (e) => toast.error("删除失败", { description: (e as Error).message }),
  });

  const openCreate = () => { setEditing(null); setForm({ name: "", status: 1, resJson: "", version: "1.0.0", tags: "" }); setDialogOpen(true); };
  const openEdit = (p: McpPrompt) => { setEditing(p); setForm({ name: p.name, status: p.status, resJson: p.resJson, version: p.version ?? "1.0.0", tags: p.tags ?? "" }); setDialogOpen(true); };

  if (isLoading) return <LoadingState label="加载 Prompt…" />;
  if (isError) return <ErrorState message={(error as Error)?.message} />;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-muted-foreground">MCP Prompt（prompts/list 暴露），共 {prompts.length} 个。</p>
        <Button onClick={openCreate}><Plus className="h-4 w-4" /> 新增 Prompt</Button>
      </div>
      <div className="rounded-xl border bg-card">
        {prompts.length === 0 ? (
          <EmptyState icon={Braces} title="暂无 Prompt" />
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>ID</TableHead>
                <TableHead>名称</TableHead>
                <TableHead>标签 / Token</TableHead>
                <TableHead>版本</TableHead>
                <TableHead>状态</TableHead>
                <TableHead>更新时间</TableHead>
                <TableHead className="w-[1%] whitespace-nowrap">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {prompts.map((p) => (
                <TableRow key={p.id}>
                  <TableCell className="font-mono text-muted-foreground">{p.id}</TableCell>
                  <TableCell className="font-medium">{p.name}</TableCell>
                  <TableCell>
                    <div className="flex flex-wrap gap-1">
                      {(p.tags ?? "").split(",").filter(Boolean).map((t) => <Badge key={t} variant="outline" className="text-[10px]">{t}</Badge>)}
                      {p.tokenEstimate ? <span className="text-[10px] text-muted-foreground">≈{p.tokenEstimate} tok</span> : null}
                    </div>
                  </TableCell>
                  <TableCell className="font-mono text-xs text-muted-foreground">v{p.version ?? "1.0.0"}</TableCell>
                  <TableCell>
                    <StatusSwitch
                      checked={p.status === 1}
                      onCheckedChange={() => toggle.mutate(p)}
                      aria-label="切换状态"
                    />
                  </TableCell>
                  <TableCell className="text-muted-foreground">{formatDateTime(p.updateTime)}</TableCell>
                  <TableCell>
                    <RowActions>
                      <Button variant="ghost" size="iconSm" aria-label="编辑" onClick={() => openEdit(p)}><Pencil /></Button>
                      <Button variant="ghost" size="iconSm" aria-label="删除" className="text-destructive" onClick={() => setDeleteTarget(p)}><Trash2 /></Button>
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
            <DialogTitle>{editing ? "编辑 Prompt" : "新增 Prompt"}</DialogTitle>
          </DialogHeader>
<DialogBody>
          <div className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="p-name">名称</Label>
              <Input id="p-name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="p-version">版本（编辑时不改则自动 +0.0.1，填更高版本号则用指定值）</Label>
              <Input id="p-version" value={form.version} onChange={(e) => setForm({ ...form, version: e.target.value })} className="font-mono" placeholder="1.0.0" />
            </div>
            <div className="space-y-2">
              <Label htmlFor="p-tags">标签（逗号分隔）</Label>
              <Input id="p-tags" value={form.tags} onChange={(e) => setForm({ ...form, tags: e.target.value })} placeholder="order,refund" />
            </div>
            <div className="space-y-2">
              <Label htmlFor="p-res">定义 (res_json)</Label>
              <Textarea id="p-res" rows={5} value={form.resJson} onChange={(e) => setForm({ ...form, resJson: e.target.value })} className="font-mono text-xs" />
            </div>
            <div className="flex items-center gap-2">
              <Switch checked={form.status === 1} onCheckedChange={(v) => setForm({ ...form, status: v ? 1 : 0 })} id="p-status" />
              <Label htmlFor="p-status">启用</Label>
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
        title="删除 Prompt"
        description={`确认删除 Prompt「${deleteTarget?.name ?? ""}」？此操作不可恢复。请输入 Prompt 名称以确认。`}
        confirmValue={deleteTarget?.name}
        confirmValueLabel={`请输入 Prompt 名称：${deleteTarget?.name ?? ""}`}
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
