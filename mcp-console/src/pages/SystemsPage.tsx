import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Cable, Pencil } from "lucide-react";
import { api } from "@/lib/api";
import { formatDateTime } from "@/lib/utils";
import { statusLabel } from "@/lib/format";
import type { ServiceSystem } from "@/types";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
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

export function SystemsPage() {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<ServiceSystem | null>(null);
  const [authUrl, setAuthUrl] = useState("");

  const { data: systems, isLoading, isError, error } = useQuery({
    queryKey: ["service-systems"],
    queryFn: api.listServiceSystems,
  });

  const saveMutation = useMutation({
    mutationFn: () => api.updateServiceSystem(editing!.id, { authUrl }),
    onSuccess: () => {
      toast.success("系统服务已更新");
      setEditing(null);
      queryClient.invalidateQueries({ queryKey: ["service-systems"] });
    },
    onError: (e) => toast.error("更新失败", { description: (e as Error).message }),
  });

  const openEdit = (s: ServiceSystem) => {
    setEditing(s);
    setAuthUrl(s.authUrl);
  };

  if (isLoading) return <LoadingState label="加载系统服务…" />;
  if (isError) return <ErrorState message={(error as Error)?.message} />;

  return (
    <div className="space-y-4">
      <p className="text-sm text-muted-foreground">
        对应 <span className="font-mono">service_system</span> 表，<span className="font-mono">id</span> 与{" "}
        <span className="font-mono">mcp_tool.system_id</span> 关联，<span className="font-mono">auth_url</span> 用于 sk 换 token。
      </p>

      <div className="rounded-xl border bg-card">
        {systems && systems.length === 0 ? (
          <EmptyState icon={Cable} title="暂无系统服务" />
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>ID</TableHead>
                <TableHead>系统名称</TableHead>
                <TableHead>授权 URL (auth_url)</TableHead>
                <TableHead>状态</TableHead>
                <TableHead>更新时间</TableHead>
                <TableHead className="w-[1%] whitespace-nowrap">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {systems?.map((s) => (
                <TableRow key={s.id}>
                  <TableCell className="font-mono text-muted-foreground">{s.id}</TableCell>
                  <TableCell className="font-medium">{s.name}</TableCell>
                  <TableCell>
                    <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">{s.authUrl}</code>
                  </TableCell>
                  <TableCell>
                    <Badge variant={s.status === 1 ? "success" : "muted"}>{statusLabel(s.status)}</Badge>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{formatDateTime(s.updateTime)}</TableCell>
                  <TableCell>
                    <RowActions>
                      <Button variant="ghost" size="iconSm" aria-label="编辑" onClick={() => openEdit(s)}>
                        <Pencil />
                      </Button>
                    </RowActions>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </div>

      <Dialog open={!!editing} onOpenChange={(o) => !o && setEditing(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>编辑系统服务</DialogTitle>
            <DialogDescription>修改 {editing?.name} 的授权地址。</DialogDescription>
          </DialogHeader>
<DialogBody>
          <div className="space-y-2">
            <Label htmlFor="s-auth">授权 URL</Label>
            <Input
              id="s-auth"
              value={authUrl}
              onChange={(e) => setAuthUrl(e.target.value)}
              className="font-mono"
            />
          </div>
          </DialogBody>
<DialogFooter>
            <Button variant="outline" onClick={() => setEditing(null)}>
              取消
            </Button>
            <Button onClick={() => saveMutation.mutate()} disabled={saveMutation.isPending}>
              {saveMutation.isPending ? "保存中…" : "保存"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
