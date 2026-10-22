import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Activity, AlertTriangle, ArrowRight, Pencil, Plus, RefreshCw, Server, Trash2 } from "lucide-react";
import { api } from "@/lib/api";
import { isDirect, useApprovalPolicy } from "@/lib/approval";
import { cn, formatDateTime } from "@/lib/utils";
import { accessMode } from "@/lib/format";
import type { McpServer, ServerHealth, ToolAccessMode, ToolBudget, ToolExposureMode } from "@/types";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Switch } from "@/components/ui/switch";
import { StatusSwitch } from "@/components/ui/status-switch";
import {
  Select,
  SelectContent,
  SelectEmpty,
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
import { RowActions } from "@/components/ui/row-actions";

const HEALTH_META: Record<string, { label: string; dot: string; text: string }> = {
  UP: { label: "正常", dot: "bg-emerald-400", text: "text-emerald-400" },
  DEGRADED: { label: "降级", dot: "bg-amber-400", text: "text-amber-400" },
  DOWN: { label: "不可用", dot: "bg-rose-500", text: "text-rose-400" },
  DISABLED: { label: "已下线", dot: "bg-muted-foreground/50", text: "text-muted-foreground" },
  UNKNOWN: { label: "未知", dot: "bg-muted-foreground/40", text: "text-muted-foreground" },
};

const emptyForm = {
  tenantId: undefined as number | undefined,
  name: "",
  path: "",
  capabilitiesJson: "",
  upstreamType: "NONE",
  upstreamUrl: "",
  status: 1,
  toolAccessMode: "WHITELIST" as ToolAccessMode,
  toolExposureMode: "DIRECT" as ToolExposureMode,
};

const ACCESS_MODE_LABEL: Record<ToolAccessMode, string> = {
  WHITELIST: "白名单",
  ALL: "全部",
};

export function ServersPage() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<McpServer | null>(null);
  const [form, setForm] = useState(emptyForm);
  const [deleteTarget, setDeleteTarget] = useState<McpServer | null>(null);

  const { data: policy } = useApprovalPolicy();
  const directCreate = isDirect(policy, "SERVER_CREATE");
  const directDelete = isDirect(policy, "SERVER_DELETE");
  const directConnection = isDirect(policy, "SERVER_UPDATE_CONNECTION");

  /** 连接层字段（path / 上游地址）是否变化：变化时开发者需要走审批 */
  const connectionChanged = (oldServer: McpServer) =>
    (form.path ?? "") !== (oldServer.path ?? "") ||
    (form.upstreamType ?? "NONE") !== (oldServer.upstreamType ?? "NONE") ||
    (form.upstreamUrl ?? "") !== (oldServer.upstreamUrl ?? "");

  const { data: servers, isLoading, isError, error } = useQuery({
    queryKey: ["servers"],
    queryFn: api.listServers,
  });
  const { data: budgets } = useQuery({ queryKey: ["server-budgets"], queryFn: api.listServerBudgets });
  const { data: tenants } = useQuery({ queryKey: ["tenants"], queryFn: api.listTenants });
  const budgetMap = new Map<number, ToolBudget>((budgets ?? []).map((b) => [b.serverId, b]));

  const { data: healthList } = useQuery({
    queryKey: ["server-health"],
    queryFn: api.listServerHealth,
    refetchInterval: 30_000,
  });
  const healthMap = new Map<number, ServerHealth>((healthList ?? []).map((h) => [h.serverId, h]));

  const probeMutation = useMutation({
    mutationFn: (id: number) => api.probeServer(id),
    onSuccess: (h) => {
      toast.success(`${h.name} 探测完成`, { description: `${h.status}${h.latencyMs != null ? ` · ${h.latencyMs}ms` : ""}${h.message ? ` · ${h.message}` : ""}` });
      queryClient.invalidateQueries({ queryKey: ["server-health"] });
    },
    onError: (e) => toast.error("探测失败", { description: (e as Error).message }),
  });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ["servers"] });
    queryClient.invalidateQueries({ queryKey: ["server-budgets"] });
  };

  const refreshMutation = useMutation({
    mutationFn: api.refreshServers,
    onSuccess: (res) => toast.success(res.message),
    onError: (e) => toast.error("刷新失败", { description: (e as Error).message }),
  });

  const saveMutation = useMutation({
    mutationFn: async () => {
      if (editing) {
        if (connectionChanged(editing) && !directConnection) {
          await api.createApproval({
            action: "SERVER_UPDATE_CONNECTION",
            serverId: editing.id,
            payload: { ...form },
          });
          return { pending: true, editing: true };
        }
        await api.updateServer(editing.id, form);
        return { pending: false, editing: true };
      }
      if (!directCreate) {
        await api.createApproval({ action: "SERVER_CREATE", payload: { ...form } });
        return { pending: true, editing: false };
      }
      await api.createServer(form);
      return { pending: false, editing: false };
    },
    onSuccess: ({ pending, editing: isEdit }) => {
      toast.success(
        pending
          ? "已提交审批申请，等待管理员处理"
          : isEdit ? "Server 已更新" : "Server 已创建",
      );
      setDialogOpen(false);
      invalidate();
    },
    onError: (e) => toast.error("保存失败", { description: (e as Error).message }),
  });

  const toggleMutation = useMutation({
    mutationFn: (s: McpServer) => api.updateServer(s.id, { status: s.status === 1 ? 0 : 1 }),
    onSuccess: () => invalidate(),
    onError: (e) => toast.error("更新失败", { description: (e as Error).message }),
  });

  const deleteMutation = useMutation({
    mutationFn: async (server: McpServer) => {
      if (directDelete) {
        await api.deleteServer(server.id);
        return false;
      }
      await api.createApproval({ action: "SERVER_DELETE", serverId: server.id });
      return true;
    },
    onSuccess: (pending) => {
      toast.success(pending ? "删除申请已提交，等待管理员审批" : "Server 已删除");
      invalidate();
    },
    onError: (e) => toast.error("删除失败", { description: (e as Error).message }),
  });

  const openCreate = () => {
    setEditing(null);
    setForm(emptyForm);
    setDialogOpen(true);
  };

  const openEdit = (s: McpServer) => {
    setEditing(s);
    setForm({
      tenantId: s.tenantId,
      name: s.name,
      path: s.path,
      capabilitiesJson: s.capabilitiesJson,
      upstreamType: s.upstreamType ?? "NONE",
      upstreamUrl: s.upstreamUrl ?? "",
      status: s.status,
      toolAccessMode: accessMode(s.toolAccessMode),
      toolExposureMode: s.toolExposureMode ?? "DIRECT",
    });
    setDialogOpen(true);
  };

  if (isLoading) return <LoadingState label="加载 MCP Server…" />;
  if (isError) return <ErrorState message={(error as Error)?.message} />;

  const tenantName = (id?: number) => tenants?.find((t) => t.id === id)?.name ?? (id ? `#${id}` : "—");
  const overBudget = (budgets ?? []).filter(
    (b) => b.overWarn || b.promptOverWarn || b.resourceOverWarn,
  );

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-muted-foreground">
          基于 <span className="font-mono">mcp_server</span> 表动态注册的 MCP 端点，共 {servers?.length ?? 0} 个。
          {(healthList ?? []).length > 0 && (() => {
            const list = healthList ?? [];
            const c = (st: string) => list.filter((h) => h.status === st).length;
            return (
              <span className="ml-2">
                · 正常 <span className="text-emerald-400">{c("UP")}</span>
                {" · "}降级 <span className="text-amber-400">{c("DEGRADED")}</span>
                {" · "}不可用 <span className="text-rose-400">{c("DOWN")}</span>
                {" · "}未知 <span className="text-muted-foreground">{c("UNKNOWN") + c("DISABLED")}</span>
              </span>
            );
          })()}
        </p>
        <div className="flex gap-2">
          <Button
            variant="outline"
            onClick={() => refreshMutation.mutate()}
            disabled={refreshMutation.isPending}
          >
            <RefreshCw className={cn("h-4 w-4", refreshMutation.isPending && "animate-spin")} />
            刷新端点
          </Button>
          <Button onClick={openCreate}>
            <Plus className="h-4 w-4" />
            {directCreate ? "新增 Server" : "申请创建 Server"}
          </Button>
        </div>
      </div>

      {overBudget.length > 0 && (
        <div className="flex items-start gap-2 rounded-lg border border-amber-500/30 bg-amber-500/10 p-3">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-400" />
          <div className="min-w-0 flex-1">
            <p className="text-sm font-medium text-amber-300">
              {overBudget.length} 个 Server 的 Tool / Prompt / Resource 数量超过告警阈值
            </p>
            <p className="mt-1 text-xs text-muted-foreground">
              {overBudget
                .map(
                  (b) =>
                    `${b.serverName}(Tool ${b.visibleTools} / Prompt ${b.visiblePrompts ?? 0} / Resource ${b.visibleResources ?? 0}，阈值 ${b.warnThreshold})`,
                )
                .join(" · ")}
              。建议启用 META 模式、收紧应用白名单，或按业务域拆分 Server。
            </p>
          </div>
          <Button variant="ghost" size="sm" onClick={() => navigate(`/servers/${overBudget[0].serverId}`)}>
            去处理
          </Button>
        </div>
      )}

      <div className="rounded-xl border bg-card">
        {servers && servers.length === 0 ? (
          <EmptyState icon={Server} title="暂无 Server" description="点击「新增 Server」创建第一个 MCP 端点" />
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>ID</TableHead>
                <TableHead>名称</TableHead>
                <TableHead>租户</TableHead>
                <TableHead>路径 (path)</TableHead>
                <TableHead>资产 / 模式</TableHead>
                <TableHead>健康</TableHead>
                <TableHead>状态</TableHead>
                <TableHead>更新时间</TableHead>
                <TableHead className="w-[1%] whitespace-nowrap">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {servers?.map((s) => (
                <TableRow key={s.id}>
                  <TableCell className="font-mono text-muted-foreground">{s.id}</TableCell>
                  <TableCell className="font-medium">
                    <button
                      className="hover:text-primary hover:underline cursor-pointer"
                      onClick={() => navigate(`/servers/${s.id}`)}
                    >
                      {s.name}
                    </button>
                  </TableCell>
                  <TableCell className="text-xs text-muted-foreground">{tenantName(s.tenantId)}</TableCell>
                  <TableCell>
                    <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">{s.path}</code>
                  </TableCell>
                  <TableCell>
                    {(() => {
                      const b = budgetMap.get(s.id);
                      if (!b) return <span className="text-xs text-muted-foreground">—</span>;
                      const toolTone = b.overMax ? "text-rose-400" : b.overWarn ? "text-amber-400" : "text-muted-foreground";
                      const promptMode = accessMode(b.promptAccessMode);
                      const resourceMode = accessMode(b.resourceAccessMode);
                      return (
                        <div className="grid grid-cols-[auto_1fr] items-center gap-x-2 gap-y-1 text-xs">
                          <span className="text-[10px] uppercase tracking-wide text-muted-foreground">Tool</span>
                          <div
                            className="flex flex-wrap items-center gap-1"
                            title={`预估 tools/list ${b.estimatedTokens.toLocaleString()} tokens`}
                          >
                            <span className={cn("font-medium", toolTone)}>{b.visibleTools} 个</span>
                            <Badge variant="outline">{ACCESS_MODE_LABEL[accessMode(b.accessMode)]}</Badge>
                            <Badge variant={b.exposureMode === "META" ? "success" : "outline"}>
                              {b.exposureMode}
                            </Badge>
                            {b.overWarn && (
                              <Badge variant={b.overMax ? "destructive" : "warning"}>
                                {b.overMax ? "超硬阈值" : "超告警"}
                              </Badge>
                            )}
                          </div>

                          <span className="text-[10px] uppercase tracking-wide text-muted-foreground">Prompt</span>
                          <div className="flex flex-wrap items-center gap-1">
                            <span className={cn(b.promptOverWarn ? "text-amber-400" : "text-muted-foreground")}>
                              {b.visiblePrompts ?? 0}/{b.totalPrompts ?? 0}
                            </span>
                            <Badge variant={promptMode === "WHITELIST" ? "outline" : "muted"}>
                              {ACCESS_MODE_LABEL[promptMode]}
                            </Badge>
                          </div>

                          <span className="text-[10px] uppercase tracking-wide text-muted-foreground">Resource</span>
                          <div className="flex flex-wrap items-center gap-1">
                            <span className={cn(b.resourceOverWarn ? "text-amber-400" : "text-muted-foreground")}>
                              {b.visibleResources ?? 0}/{b.totalResources ?? 0}
                            </span>
                            <Badge variant={resourceMode === "WHITELIST" ? "outline" : "muted"}>
                              {ACCESS_MODE_LABEL[resourceMode]}
                            </Badge>
                          </div>
                        </div>
                      );
                    })()}
                  </TableCell>
                  <TableCell>
                    {(() => {
                      const h = healthMap.get(s.id);
                      const meta = HEALTH_META[h?.status ?? "UNKNOWN"] ?? HEALTH_META.UNKNOWN;
                      const tip = h
                        ? [
                            h.message,
                            h.latencyMs != null ? `${h.latencyMs}ms` : null,
                            h.successRate != null ? `成功率 ${(h.successRate * 100).toFixed(0)}%（${h.totalCalls ?? 0} 次）` : null,
                            h.checkedAt ? `检测于 ${String(h.checkedAt).replace("T", " ").slice(0, 19)}` : null,
                          ].filter(Boolean).join(" · ")
                        : "尚未探测";
                      return (
                        <div className="flex items-center gap-2" title={tip}>
                          <span className={cn("h-2 w-2 shrink-0 rounded-full", meta.dot)} />
                          <span className={cn("text-xs", meta.text)}>{meta.label}</span>
                          {h?.latencyMs != null && (
                            <span className="text-[11px] text-muted-foreground">{h.latencyMs}ms</span>
                          )}
                        </div>
                      );
                    })()}
                  </TableCell>
                  <TableCell>
                    <StatusSwitch
                      checked={s.status === 1}
                      onCheckedChange={() => toggleMutation.mutate(s)}
                      aria-label="切换状态"
                    />
                  </TableCell>
                  <TableCell className="text-muted-foreground">{formatDateTime(s.updateTime)}</TableCell>
                  <TableCell>
                    <RowActions>
                      <Button variant="ghost" size="xs" onClick={() => navigate(`/servers/${s.id}`)}>
                        <ArrowRight /> 详情
                      </Button>
                      <Button
                        variant="ghost"
                        size="xs"
                        onClick={() => probeMutation.mutate(s.id)}
                        disabled={probeMutation.isPending}
                      >
                        <Activity /> 探测
                      </Button>
                      <Button variant="ghost" size="xs" onClick={() => openEdit(s)}>
                        <Pencil /> 编辑
                      </Button>
                      <Button variant="ghost" size="xs" className="text-destructive" onClick={() => setDeleteTarget(s)}>
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
        <DialogContent className="max-w-xl">
          <DialogHeader>
            <DialogTitle>{editing ? "编辑 Server" : directCreate ? "新增 Server" : "申请创建 Server"}</DialogTitle>
            <DialogDescription>
              Server 路径将作为动态 MCP 端点注册到网关。
              {!directCreate && " 当前角色创建 Server 需管理员审批，通过后自动注册。"}
              {editing && !directConnection && " 修改路径或上游地址将提交审批。"}
            </DialogDescription>
          </DialogHeader>
<DialogBody>
          <div className="space-y-4">
            <div className="space-y-2">
              <Label>归属租户</Label>
              <Select
                value={form.tenantId ? String(form.tenantId) : ""}
                onValueChange={(v) => setForm({ ...form, tenantId: Number(v) })}
              >
                <SelectTrigger>
                  <SelectValue placeholder="选择租户" />
                </SelectTrigger>
                <SelectContent>
                  {tenants && tenants.length > 0 ? (
                    tenants.map((t) => (
                      <SelectItem key={t.id} value={String(t.id)}>{t.name}</SelectItem>
                    ))
                  ) : (
                    <SelectEmpty>暂无租户</SelectEmpty>
                  )}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label htmlFor="s-name">Server 名称</Label>
              <Input
                id="s-name"
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
                placeholder="crm-core"
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="s-path">路径 (path)</Label>
              <Input
                id="s-path"
                value={form.path}
                onChange={(e) => setForm({ ...form, path: e.target.value })}
                placeholder="/mcp/v1/core"
                className="font-mono"
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="s-cap">initialize 响应 (capabilities_json)</Label>
              <Textarea
                id="s-cap"
                rows={6}
                value={form.capabilitiesJson}
                onChange={(e) => setForm({ ...form, capabilitiesJson: e.target.value })}
                placeholder='{"protocolVersion":"2024-11-05","serverInfo":{"name":"mcp-transfer","version":"1.0.0"}}'
              />
              <p className="text-xs text-muted-foreground">
                tools / resources / prompts 能力会按实际注册内容自动派生，这里只需填 protocolVersion 和 serverInfo；留空则自动用默认值。
              </p>
            </div>
            <div className="space-y-2">
              <Label>上游类型</Label>
              <Select
                value={form.upstreamType}
                onValueChange={(v) => setForm({ ...form, upstreamType: v })}
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="NONE">本地 / REST Tool（默认）</SelectItem>
                  <SelectItem value="MCP">代理到单个下游 MCP server</SelectItem>
                  <SelectItem value="AGGREGATE">聚合多个下游 MCP server</SelectItem>
                </SelectContent>
              </Select>
            </div>
            {(form.upstreamType === "MCP" || form.upstreamType === "AGGREGATE") && (
              <div className="space-y-2">
                <Label htmlFor="s-upstream-url">
                  {form.upstreamType === "AGGREGATE" ? "下游 MCP server 列表（JSON 数组）" : "下游 MCP server 地址 (upstream_url)"}
                </Label>
                <Input
                  id="s-upstream-url"
                  value={form.upstreamUrl}
                  onChange={(e) => setForm({ ...form, upstreamUrl: e.target.value })}
                  placeholder={form.upstreamType === "AGGREGATE"
                    ? '[{"name":"crm","url":"http://crm:8080/mcp"},{"name":"erp","url":"http://erp:8080/mcp"}]'
                    : "http://downstream-mcp-server:8080/mcp"}
                  className="font-mono"
                />
                <p className="text-xs text-muted-foreground">
                  {form.upstreamType === "AGGREGATE"
                    ? "聚合模式：Tool 名加 {name}__ 前缀合并，调用按前缀路由到对应下游。"
                    : "该 server 将作为 MCP 代理，把 initialize / tools/list / tools/call 等透传到下游真实 MCP server（Streamable HTTP）。"}
                </p>
              </div>
            )}
            {editing ? (
              <p className="rounded-md border border-dashed p-2 text-xs text-muted-foreground">
                Tool 访问 / 暴露模式、热 Tool 与阈值请在「详情 → 设置」中调整。
              </p>
            ) : (
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <div className="space-y-2">
                  <Label>Tool 访问模式</Label>
                  <Select
                    value={form.toolAccessMode}
                    onValueChange={(v) => setForm({ ...form, toolAccessMode: v as ToolAccessMode })}
                  >
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="WHITELIST">白名单（推荐，新建默认）</SelectItem>
                      <SelectItem value="ALL">全部启用 Tool</SelectItem>
                    </SelectContent>
                  </Select>
                  <p className="text-xs text-muted-foreground">
                    白名单模式下，应用只能看到 app_tool_rel 显式授权的 Tool。
                  </p>
                </div>
                <div className="space-y-2">
                  <Label>Tool 暴露模式</Label>
                  <Select
                    value={form.toolExposureMode}
                    onValueChange={(v) => setForm({ ...form, toolExposureMode: v as ToolExposureMode })}
                  >
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="DIRECT">DIRECT（全量 tools/list）</SelectItem>
                      <SelectItem value="META">META（元工具 + 热 Tool）</SelectItem>
                    </SelectContent>
                  </Select>
                  <p className="text-xs text-muted-foreground">
                    Tool 数超过 100 时建议 META，避免上下文被 Tool 定义占满。
                  </p>
                </div>
              </div>
            )}
            <div className="flex items-center gap-2">
              <Switch
                checked={form.status === 1}
                onCheckedChange={(v) => setForm({ ...form, status: v ? 1 : 0 })}
                id="s-status"
              />
              <Label htmlFor="s-status">启用</Label>
            </div>
          </div>
          </DialogBody>
<DialogFooter>
            <Button variant="outline" onClick={() => setDialogOpen(false)}>
              取消
            </Button>
            <Button onClick={() => saveMutation.mutate()} disabled={saveMutation.isPending}>
              {saveMutation.isPending
                ? "提交中…"
                : editing
                  ? (!directConnection && connectionChanged(editing) ? "提交审批" : "保存")
                  : directCreate ? "创建" : "提交创建申请"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={deleteTarget !== null}
        onOpenChange={(o) => !o && setDeleteTarget(null)}
        title="删除 MCP Server"
        description={directDelete
          ? `删除后其下 Tool 将无法访问，且不可恢复。请输入 Server 名称以确认。`
          : `提交后需管理员审批；通过后其下 Tool 将无法访问，且不可恢复。请输入 Server 名称以确认。`}
        confirmValue={deleteTarget?.name}
        confirmValueLabel={`请输入 Server 名称：${deleteTarget?.name ?? ""}`}
        loading={deleteMutation.isPending}
        onConfirm={() => {
          if (deleteTarget) {
            deleteMutation.mutate(deleteTarget, { onSuccess: () => setDeleteTarget(null) });
          }
        }}
      />
    </div>
  );
}
