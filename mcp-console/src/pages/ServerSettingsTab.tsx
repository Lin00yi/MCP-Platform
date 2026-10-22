import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Save, Sparkles } from "lucide-react";
import { api } from "@/lib/api";
import { isDirect, useApprovalPolicy } from "@/lib/approval";
import { truncate } from "@/lib/utils";
import { accessMode } from "@/lib/format";
import type { McpServer, ToolAccessMode, ToolExposureMode } from "@/types";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Switch } from "@/components/ui/switch";
import {
  Select,
  SelectContent,
  SelectEmpty,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

function parseHotCodes(raw?: string): string[] {
  try {
    const parsed = JSON.parse(raw ?? "[]");
    return Array.isArray(parsed) ? parsed.map(String) : [];
  } catch {
    return [];
  }
}

interface ExposureForm {
  toolAccessMode: ToolAccessMode;
  toolExposureMode: ToolExposureMode;
  promptAccessMode: "WHITELIST" | "ALL";
  resourceAccessMode: "WHITELIST" | "ALL";
  hotToolCodes: string[];
  toolWarnThreshold?: number;
  toolMaxThreshold?: number;
}

export function ServerSettingsTab({ server }: { server: McpServer }) {
  const queryClient = useQueryClient();
  const { data: tenants } = useQuery({ queryKey: ["tenants"], queryFn: api.listTenants });
  const { data: policy } = useApprovalPolicy();
  const directConnection = isDirect(policy, "SERVER_UPDATE_CONNECTION");

  const [form, setForm] = useState({
    tenantId: server.tenantId,
    name: server.name,
    path: server.path,
    capabilitiesJson: server.capabilitiesJson,
    status: server.status,
  });

  const [exposure, setExposure] = useState<ExposureForm>({
    toolAccessMode: accessMode(server.toolAccessMode),
    toolExposureMode: server.toolExposureMode ?? "DIRECT",
    promptAccessMode: accessMode(server.promptAccessMode),
    resourceAccessMode: accessMode(server.resourceAccessMode),
    hotToolCodes: parseHotCodes(server.hotToolCodes),
    toolWarnThreshold: server.toolWarnThreshold,
    toolMaxThreshold: server.toolMaxThreshold,
  });
  const [hotSearch, setHotSearch] = useState("");

  // server 变化时同步表单（切换 server 详情时）
  useEffect(() => {
    setForm({
      tenantId: server.tenantId,
      name: server.name,
      path: server.path,
      capabilitiesJson: server.capabilitiesJson,
      status: server.status,
    });
    setExposure({
      toolAccessMode: accessMode(server.toolAccessMode),
      toolExposureMode: server.toolExposureMode ?? "DIRECT",
      promptAccessMode: accessMode(server.promptAccessMode),
      resourceAccessMode: accessMode(server.resourceAccessMode),
      hotToolCodes: parseHotCodes(server.hotToolCodes),
      toolWarnThreshold: server.toolWarnThreshold,
      toolMaxThreshold: server.toolMaxThreshold,
    });
  }, [server]);

  const { data: tools } = useQuery({ queryKey: ["tools"], queryFn: api.listTools });
  const { data: budget } = useQuery({
    queryKey: ["server-tool-budget", server.id],
    queryFn: () => api.getServerToolBudget(server.id),
  });

  const serverTools = useMemo(
    () => (tools ?? []).filter((t) => t.serverId === server.id).sort((a, b) => a.code.localeCompare(b.code)),
    [tools, server.id],
  );
  const filteredTools = useMemo(() => {
    const q = hotSearch.trim().toLowerCase();
    if (!q) return serverTools;
    return serverTools.filter(
      (t) => t.code.toLowerCase().includes(q) || (t.name ?? "").toLowerCase().includes(q) || (t.tags ?? "").toLowerCase().includes(q),
    );
  }, [serverTools, hotSearch]);

  const pathChanged = (form.path ?? "") !== (server.path ?? "");

  const saveMutation = useMutation({
    mutationFn: async () => {
      // path 属于连接层配置：开发者变更需审批
      if (pathChanged && !directConnection) {
        await api.createApproval({
          action: "SERVER_UPDATE_CONNECTION",
          serverId: server.id,
          payload: { ...form },
        });
        return true;
      }
      await api.updateServer(server.id, form);
      return false;
    },
    onSuccess: (pending) => {
      toast.success(pending ? "路径变更已提交审批，通过后生效" : "Server 设置已更新");
      queryClient.invalidateQueries({ queryKey: ["servers"] });
    },
    onError: (e) => toast.error("保存失败", { description: (e as Error).message }),
  });

  const exposureMutation = useMutation({
    mutationFn: () =>
      api.updateToolExposure(server.id, {
        toolAccessMode: exposure.toolAccessMode,
        toolExposureMode: exposure.toolExposureMode,
        promptAccessMode: exposure.promptAccessMode,
        resourceAccessMode: exposure.resourceAccessMode,
        hotToolCodes: exposure.hotToolCodes,
        // 0 表示清空，后端会回落全局默认
        toolWarnThreshold: exposure.toolWarnThreshold ?? 0,
        toolMaxThreshold: exposure.toolMaxThreshold ?? 0,
      }),
    onSuccess: () => {
      toast.success("Tool 暴露策略已更新", {
        description:
          exposure.toolExposureMode === "META"
            ? "已通知已连接的客户端重新拉取 tools/list。"
            : "tools/list 将按新配置返回。",
      });
      queryClient.invalidateQueries({ queryKey: ["servers"] });
      queryClient.invalidateQueries({ queryKey: ["server-budgets"] });
      queryClient.invalidateQueries({ queryKey: ["server-tool-budget", server.id] });
    },
    onError: (e) => toast.error("保存失败", { description: (e as Error).message }),
  });


  const recommendMutation = useMutation({
    mutationFn: () => api.recommendHotTools(server.id, 30, 20),
    onSuccess: (rec) => {
      setExposure((prev) => ({ ...prev, hotToolCodes: rec.hotToolCodes }));
      toast.success(`已按近 ${rec.windowDays} 天调用推荐 ${rec.hotToolCodes.length} 个热 Tool`, {
        description: rec.hint ?? `共 ${rec.totalCalls} 次成功调用；可继续手动增减。`,
      });
    },
    onError: (e) => toast.error("推荐失败", { description: (e as Error).message }),
  });

  const toggleHot = (code: string, checked: boolean) => {
    setExposure((prev) => ({
      ...prev,
      hotToolCodes: checked
        ? Array.from(new Set([...prev.hotToolCodes, code]))
        : prev.hotToolCodes.filter((c) => c !== code),
    }));
  };

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>端点设置</CardTitle>
          <CardDescription>修改 path 或 capabilities 后需要点击「刷新端点」才会重新注册。</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
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
              <Input id="s-name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="s-path">路径 (path)</Label>
              <Input id="s-path" value={form.path} onChange={(e) => setForm({ ...form, path: e.target.value })} className="font-mono" />
            </div>
            <div className="flex items-center gap-2 pt-6">
              <Switch
                checked={form.status === 1}
                onCheckedChange={(v) => setForm({ ...form, status: v ? 1 : 0 })}
                id="s-status"
              />
              <Label htmlFor="s-status">启用</Label>
            </div>
            <div className="space-y-2 sm:col-span-2">
              <Label htmlFor="s-cap">initialize 能力声明 (capabilities_json)</Label>
              <Textarea
                id="s-cap"
                rows={6}
                value={form.capabilitiesJson}
                onChange={(e) => setForm({ ...form, capabilitiesJson: e.target.value })}
                placeholder='{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"serverInfo":{...}}'
                className="font-mono text-xs"
              />
              <p className="text-xs text-muted-foreground">{truncate(form.capabilitiesJson, 60)}</p>
            </div>
          </div>
          <div className="flex justify-end">
            <Button onClick={() => saveMutation.mutate()} disabled={saveMutation.isPending}>
              <Save className="h-4 w-4" />
              {saveMutation.isPending
                ? "提交中…"
                : pathChanged && !directConnection ? "提交审批" : "保存设置"}
            </Button>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Tool 暴露策略</CardTitle>
          <CardDescription>
            控制应用能看到哪些 Tool（访问模式），以及 tools/list 一次返回多少（暴露模式）。
            超过阈值时管理台会告警，避免客户端上下文被 Tool 定义占满。
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {budget && (
            <div className="flex flex-wrap items-center gap-3 rounded-lg border bg-muted/30 px-3 py-2 text-xs">
              <span>
                Tool 可见 <span className="font-medium text-foreground">{budget.visibleTools}</span> / {budget.totalTools}
              </span>
              <span className="text-muted-foreground">预估 tools/list ≈ {budget.estimatedTokens.toLocaleString()} tokens</span>
              <span>Prompt 可见 <span className="font-medium text-foreground">{budget.visiblePrompts ?? 0}</span> / {budget.totalPrompts ?? 0}</span>
              <span>Resource 可见 <span className="font-medium text-foreground">{budget.visibleResources ?? 0}</span> / {budget.totalResources ?? 0}</span>
              <span className="text-muted-foreground">阈值 {budget.warnThreshold} / {budget.maxThreshold}</span>
              {budget.overWarn ? (
                <Badge variant={budget.overMax ? "destructive" : "warning"}>
                  {budget.overMax ? "超过硬阈值" : "超过告警阈值"}
                </Badge>
              ) : (
                <Badge variant="success">预算正常</Badge>
              )}
            </div>
          )}

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div className="space-y-2">
              <Label>Tool 访问模式</Label>
              <Select
                value={exposure.toolAccessMode}
                onValueChange={(v) => setExposure({ ...exposure, toolAccessMode: v as ToolAccessMode })}
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="WHITELIST">白名单（推荐）</SelectItem>
                  <SelectItem value="ALL">全部启用 Tool</SelectItem>
                </SelectContent>
              </Select>
              <p className="text-xs text-muted-foreground">
                白名单：只有 app_tool_rel 显式授权的 Tool 可见；未配置=看不到。
                全部：Server 下所有启用 Tool 都可见，忽略细粒度授权。
              </p>
            </div>
            <div className="space-y-2">
              <Label>Tool 暴露模式</Label>
              <Select
                value={exposure.toolExposureMode}
                onValueChange={(v) => setExposure({ ...exposure, toolExposureMode: v as ToolExposureMode })}
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
                META：tools/list 只返回热 Tool + search_tools / get_tool_schema / call_tool，
                适合 Tool 数 &gt; 100 或长尾明显的 Server。
              </p>
            </div>
            <div className="space-y-2">
              <Label>Prompt 访问模式</Label>
              <Select
                value={exposure.promptAccessMode}
                onValueChange={(v) => setExposure({ ...exposure, promptAccessMode: v as "WHITELIST" | "ALL" })}
              >
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="WHITELIST">白名单（推荐）</SelectItem>
                  <SelectItem value="ALL">全部启用 Prompt</SelectItem>
                </SelectContent>
              </Select>
              <p className="text-xs text-muted-foreground">白名单：仅 app_prompt_rel 显式授权的 Prompt 可见；全部：所有启用 Prompt 可见。</p>
            </div>
            <div className="space-y-2">
              <Label>Resource 访问模式</Label>
              <Select
                value={exposure.resourceAccessMode}
                onValueChange={(v) => setExposure({ ...exposure, resourceAccessMode: v as "WHITELIST" | "ALL" })}
              >
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="WHITELIST">白名单（推荐）</SelectItem>
                  <SelectItem value="ALL">全部启用 Resource</SelectItem>
                </SelectContent>
              </Select>
              <p className="text-xs text-muted-foreground">白名单：仅 app_resource_rel 显式授权的 Resource 可读；全部：所有启用 Resource 可见。</p>
            </div>
            <div className="space-y-2">
              <Label htmlFor="s-warn">告警阈值</Label>
              <Input
                id="s-warn"
                type="number"
                min={1}
                placeholder="默认 100"
                value={exposure.toolWarnThreshold ?? ""}
                onChange={(e) =>
                  setExposure({
                    ...exposure,
                    toolWarnThreshold: e.target.value ? Number(e.target.value) : undefined,
                  })
                }
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="s-max">硬阈值</Label>
              <Input
                id="s-max"
                type="number"
                min={1}
                placeholder="默认 500"
                value={exposure.toolMaxThreshold ?? ""}
                onChange={(e) =>
                  setExposure({
                    ...exposure,
                    toolMaxThreshold: e.target.value ? Number(e.target.value) : undefined,
                  })
                }
              />
            </div>
          </div>

          {exposure.toolExposureMode === "META" && (
            <div className="space-y-2 rounded-lg border p-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <Label>常驻热 Tool（{exposure.hotToolCodes.length}）</Label>
                  <p className="text-xs text-muted-foreground">
                    这些 Tool 会直接出现在 tools/list，不需要先 search；建议保留 5–20 个高频 Tool。
                  </p>
                </div>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => recommendMutation.mutate()}
                  disabled={recommendMutation.isPending}
                >
                  <Sparkles className="h-4 w-4" />
                  {recommendMutation.isPending ? "分析中…" : "按调用记录推荐"}
                </Button>
              </div>
              <Input
                placeholder="搜索 Tool code / 名称 / 标签"
                value={hotSearch}
                onChange={(e) => setHotSearch(e.target.value)}
              />
              <div className="max-h-64 space-y-1 overflow-y-auto rounded-md border p-2">
                {filteredTools.length === 0 ? (
                  <p className="py-3 text-center text-xs text-muted-foreground">没有匹配的 Tool</p>
                ) : (
                  filteredTools.map((t) => (
                    <label
                      key={t.id}
                      className="flex cursor-pointer items-center gap-2 rounded px-1 py-1 hover:bg-muted/50"
                    >
                      <Checkbox
                        checked={exposure.hotToolCodes.includes(t.code)}
                        onCheckedChange={(v) => toggleHot(t.code, v === true)}
                      />
                      <code className="font-mono text-xs">{t.code}</code>
                      <span className="truncate text-xs text-muted-foreground">{t.name}</span>
                    </label>
                  ))
                )}
              </div>
              {exposure.hotToolCodes.length === 0 && (
                <p className="text-xs text-amber-400">
                  未配置热 Tool：所有 Tool 都需要先调用 search_tools 检索，会增加一轮模型推理。
                </p>
              )}
            </div>
          )}

          <div className="flex justify-end gap-2">
            <Button onClick={() => exposureMutation.mutate()} disabled={exposureMutation.isPending}>
              <Save className="h-4 w-4" />
              {exposureMutation.isPending ? "保存中…" : "保存暴露策略"}
            </Button>
          </div>
        </CardContent>
      </Card>

    </div>
  );
}
