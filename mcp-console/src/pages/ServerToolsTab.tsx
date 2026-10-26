import { useMemo, useState, type ChangeEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Braces, Eye, MoveRight, Pencil, Plus, Trash2, Upload, Wand2, X } from "lucide-react";
import { api } from "@/lib/api";
import { isDirect, useApprovalPolicy } from "@/lib/approval";
import { cn } from "@/lib/utils";
import { AUTH_TYPE_LABEL, METHOD_TYPE_COLOR, METHOD_TYPE_LABEL } from "@/lib/format";
import type { McpTool, OpenApiImportPlan, OpenApiToolDraft } from "@/types";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Checkbox } from "@/components/ui/checkbox";
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

const emptyForm = {
  systemId: undefined as number | undefined,
  name: "",
  code: "",
  type: 0,
  status: 1,
  serviceUrl: "",
  requestType: "GET",
  needToken: 0,
  bodyType: 0,
  authType: "NONE",
  authConfig: "",
  paramMapping: "",
  resJson: "",
  tags: "",
  version: "1.0.0",
};

function prettyJson(raw?: string | null): string {
  if (!raw) return "";
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

function parseSchemaFields(resJson: string): string[] {
  try {
    const obj = JSON.parse(resJson);
    const props = obj?.inputSchema?.properties ?? obj?.properties ?? {};
    return Object.keys(props);
  } catch {
    return [];
  }
}

interface SchemaFieldDetail {
  name: string;
  type: string;
  description: string;
  required: boolean;
}

/** 解析 Tool res_json：返回描述 + 入参字段明细 */
function parseSchemaDetail(resJson: string): { description: string; fields: SchemaFieldDetail[] } {
  try {
    const obj = JSON.parse(resJson);
    const description = obj?.description ?? "";
    const props = obj?.inputSchema?.properties ?? obj?.properties ?? {};
    const required: string[] = obj?.inputSchema?.required ?? obj?.required ?? [];
    const fields: SchemaFieldDetail[] = Object.entries(props).map(([name, v]: [string, any]) => ({
      name,
      type: v?.type ?? (v?.enum ? "enum" : "string"),
      description: v?.description ?? "",
      required: required.includes(name),
    }));
    return { description, fields };
  } catch {
    return { description: "", fields: [] };
  }
}

interface MappingRow {
  source: string;
  target: "query" | "path" | "header" | "body";
  targetName: string;
}

function parseMapping(json: string): MappingRow[] {
  if (!json) return [];
  try {
    const obj = JSON.parse(json);
    const rows: MappingRow[] = [];
    for (const [section, map] of Object.entries(obj)) {
      if (map && typeof map === "object") {
        for (const [name, val] of Object.entries(map as Record<string, string>)) {
          const m = String(val).match(/\{\{input\.([^}]+)\}\}/);
          rows.push({ source: m ? m[1] : "", target: section as MappingRow["target"], targetName: name });
        }
      }
    }
    return rows;
  } catch {
    return [];
  }
}

function serializeMapping(rows: MappingRow[]): string {
  const obj: Record<string, Record<string, string>> = {};
  for (const r of rows) {
    if (!r.source || !r.targetName) continue;
    if (!obj[r.target]) obj[r.target] = {};
    obj[r.target][r.targetName] = `{{input.${r.source}}}`;
  }
  return Object.keys(obj).length ? JSON.stringify(obj) : "";
}

function ParamMappingEditor({
  value,
  schemaFields,
  onChange,
}: {
  value: string;
  schemaFields: string[];
  onChange: (v: string) => void;
}) {
  const rows = useMemo(() => parseMapping(value), [value]);

  const update = (next: MappingRow[]) => onChange(serializeMapping(next));

  const setRow = (i: number, patch: Partial<MappingRow>) => {
    const next = rows.map((r, idx) => (idx === i ? { ...r, ...patch } : r));
    update(next);
  };

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <Label>参数映射（可视化字段映射）</Label>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => update([...rows, { source: schemaFields[0] ?? "", target: "query", targetName: "" }])}
        >
          <Plus className="h-3.5 w-3.5" /> 添加映射
        </Button>
      </div>
      <div className="space-y-1.5">
        {rows.length === 0 && (
          <p className="rounded-md border border-dashed p-2 text-xs text-muted-foreground">
            未配置映射时：GET 默认透传入参为 query，POST 默认透传入参为 body。
          </p>
        )}
        {rows.map((r, i) => (
          <div key={i} className="flex items-center gap-2">
            <Select value={r.source} onValueChange={(v) => setRow(i, { source: v })}>
              <SelectTrigger className="h-8 flex-1 font-mono text-xs">
                <SelectValue placeholder="源字段" />
              </SelectTrigger>
              <SelectContent>
                {schemaFields.length === 0 && !r.source ? (
                  <SelectEmpty>暂无可映射字段</SelectEmpty>
                ) : (
                  <>
                    {schemaFields.map((f) => (
                      <SelectItem key={f} value={f}>{f}</SelectItem>
                    ))}
                    {r.source && !schemaFields.includes(r.source) && (
                      <SelectItem value={r.source}>{r.source}</SelectItem>
                    )}
                  </>
                )}
              </SelectContent>
            </Select>
            <span className="text-xs text-muted-foreground">→</span>
            <Select value={r.target} onValueChange={(v) => setRow(i, { target: v as MappingRow["target"] })}>
              <SelectTrigger className="h-8 w-24 text-xs">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="query">query</SelectItem>
                <SelectItem value="path">path</SelectItem>
                <SelectItem value="header">header</SelectItem>
                <SelectItem value="body">body</SelectItem>
              </SelectContent>
            </Select>
            <Input
              className="h-8 flex-1 font-mono text-xs"
              placeholder="下游参数名"
              value={r.targetName}
              onChange={(e) => setRow(i, { targetName: e.target.value })}
            />
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="h-8 w-8 text-muted-foreground hover:text-destructive"
              onClick={() => update(rows.filter((_, idx) => idx !== i))}
            >
              <X className="h-3.5 w-3.5" />
            </Button>
          </div>
        ))}
      </div>
      {rows.length > 0 && (
        <pre className="rounded-md bg-muted/40 p-2 font-mono text-[10px] leading-relaxed text-muted-foreground">
          {prettyJson(value) || "{}"}
        </pre>
      )}
    </div>
  );
}

export function ServerToolsTab({ serverId }: { serverId: number }) {
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [viewTarget, setViewTarget] = useState<McpTool | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<McpTool | null>(null);
  const [editing, setEditing] = useState<McpTool | null>(null);
  const [form, setForm] = useState(emptyForm);
  const [testResult, setTestResult] = useState<{ ok: boolean; status?: number; duration?: number; body?: string; error?: string; note?: string } | null>(null);
  const [restOpen, setRestOpen] = useState(false);
  const [restForm, setRestForm] = useState({ name: "", code: "", description: "", method: "GET", url: "", bodyExample: "", authType: "NONE" });
  const [openApiOpen, setOpenApiOpen] = useState(false);
  const [openApiDoc, setOpenApiDoc] = useState("");
  const [openApiFileName, setOpenApiFileName] = useState("");
  const [openApiBaseUrl, setOpenApiBaseUrl] = useState("");
  const [openApiPlan, setOpenApiPlan] = useState<OpenApiImportPlan | null>(null);
  const [openApiPolicy, setOpenApiPolicy] = useState<"SKIP" | "OVERWRITE">("SKIP");
  const [selectedCodes, setSelectedCodes] = useState<Set<string>>(new Set());
  const [selectedToolIds, setSelectedToolIds] = useState<number[]>([]);
  const [moveOpen, setMoveOpen] = useState(false);
  const [moveTargetId, setMoveTargetId] = useState<number | undefined>(undefined);
  const [moveGrantToApps, setMoveGrantToApps] = useState(true);

  const { data: allTools, isLoading, isError, error, refetch } = useQuery({
    queryKey: ["tools"],
    queryFn: ({ signal }) => api.listTools(signal),
  });
  const { data: systems } = useQuery({
    queryKey: ["service-systems"],
    queryFn: ({ signal }) => api.listServiceSystems(signal),
  });
  const { data: allServers } = useQuery({ queryKey: ["servers"], queryFn: ({ signal }) => api.listServers(signal) });

  const tools = useMemo(
    () => (allTools ?? []).filter((t) => t.serverId === serverId),
    [allTools, serverId],
  );

  const schemaFields = useMemo(() => parseSchemaFields(form.resJson), [form.resJson]);

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["tools"] });

  const testMutation = useMutation({
    mutationFn: () =>
      api.testToolConnection({
        ...form,
        id: editing?.id,
        serverId,
        type: form.type as McpTool["type"],
      }),
    onSuccess: (r) => setTestResult(r),
    onError: (e) => setTestResult({ ok: false, error: (e as Error).message }),
  });

  const restMutation = useMutation({
    mutationFn: () => api.fromRest(restForm),
    onSuccess: (r) => {
      // REST 生成的是 HTTP 转发 Tool，type 固定为 1；并回填 name/code/serviceUrl/鉴权/inputSchema/参数映射
      setForm((f) => ({
        ...f,
        name: restForm.name,
        code: restForm.code,
        type: 1,
        serviceUrl: r.serviceUrl,
        requestType: r.requestType,
        authType: r.authType,
        resJson: r.resJson,
        paramMapping: r.paramMapping,
      }));
      setRestOpen(false);
      // 等 REST 弹窗关闭动画结束后再打开 Tool 弹窗，避免两个 Dialog 叠加干扰
      setTimeout(() => setDialogOpen(true), 120);
      toast.success("已生成 Tool 草稿，请确认后保存");
    },
    onError: (e) => toast.error("生成失败", { description: (e as Error).message }),
  });

  const openApiPreview = useMutation({
    mutationFn: () =>
      api.previewOpenApiImport({
        serverId,
        document: openApiDoc,
        baseUrl: openApiBaseUrl.trim() || undefined,
      }),
    onSuccess: (plan) => {
      setOpenApiPlan(plan);
      setSelectedCodes(new Set(plan.tools.filter((t) => t.status === "READY").map((t) => t.code)));
    },
    onError: (e) => toast.error("校验失败", { description: (e as Error).message }),
  });

  const openApiImport = useMutation({
    mutationFn: () =>
      api.importOpenApiTools({
        serverId,
        document: openApiDoc,
        baseUrl: openApiBaseUrl.trim() || undefined,
        conflictPolicy: openApiPolicy,
        codes: Array.from(selectedCodes),
      }),
    onSuccess: (r) => {
      const summary = `新增 ${r.created} / 更新 ${r.updated} / 跳过 ${r.skipped}`;
      if (r.failed > 0) {
        toast.error(`导入完成，${r.failed} 个失败`, {
          description: r.failures.slice(0, 3).map((f) => `${f.code}：${f.message}`).join("；"),
        });
      } else {
        toast.success("OpenAPI 导入完成", { description: summary });
      }
      invalidate();
      setOpenApiOpen(false);
      setOpenApiPlan(null);
      setOpenApiDoc("");
      setOpenApiFileName("");
      setOpenApiBaseUrl("");
      setSelectedCodes(new Set());
    },
    onError: (e) => toast.error("导入失败", { description: (e as Error).message }),
  });

  const { data: policy } = useApprovalPolicy();
  const directCredential = isDirect(policy, "TOOL_CREDENTIAL_UPDATE");
  /** 用户是否输入了新的鉴权配置（后端不回传明文，无法比对旧值：有输入即视为变更） */
  const credentialProvided = form.authConfig.trim() !== "";

  const saveMutation = useMutation({
    mutationFn: async () => {
      // 鉴权配置留空表示「保持原凭证不变」：不把该字段放进请求体，避免覆盖服务端已配置的凭证
      const { authConfig, ...rest } = form;
      const payload = {
        ...rest,
        serverId,
        type: form.type as McpTool["type"],
        ...(authConfig.trim() !== "" ? { authConfig } : {}),
      };
      // 下游凭证变更属于 L2：开发者提交审批，管理员及以上直接保存
      if (editing && !directCredential && credentialProvided) {
        await api.createApproval({ action: "TOOL_CREDENTIAL_UPDATE", toolId: editing.id, payload });
        return { pending: true };
      }
      if (editing) {
        await api.updateTool(editing.id, payload);
      } else {
        await api.createTool(payload);
      }
      return { pending: false };
    },
    onSuccess: ({ pending }) => {
      toast.success(pending ? "凭证变更已提交审批，通过后生效" : editing ? "Tool 已更新" : "Tool 已创建");
      setDialogOpen(false);
      invalidate();
    },
    onError: (e) => toast.error("保存失败", { description: (e as Error).message }),
  });

  const toggleMutation = useMutation({
    mutationFn: (m: McpTool) => api.updateTool(m.id, { status: m.status === 1 ? 0 : 1 }),
    onSuccess: () => invalidate(),
    onError: (e) => toast.error("更新失败", { description: (e as Error).message }),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => api.deleteTool(id),
    onSuccess: () => {
      toast.success("Tool 已删除");
      invalidate();
    },
    onError: (e) => toast.error("删除失败", { description: (e as Error).message }),
  });

  const moveMutation = useMutation({
    mutationFn: () => {
      if (!moveTargetId) throw new Error("请选择目标 Server");
      return api.bulkMoveTools(selectedToolIds, moveTargetId, moveGrantToApps);
    },
    onSuccess: (res) => {
      toast.success(`已移动 ${res.moved} 个 Tool`, {
        description: [
          res.grantsCreated > 0 ? `补齐 ${res.grantsCreated} 条 Tool 授权` : null,
          res.warning ?? null,
        ].filter(Boolean).join(" · ") || undefined,
      });
      setMoveOpen(false);
      setSelectedToolIds([]);
      setMoveTargetId(undefined);
      invalidate();
    },
    onError: (e) => toast.error("移动失败", { description: (e as Error).message }),
  });

  const openCreate = () => {
    setEditing(null);
    setForm(emptyForm);
    setDialogOpen(true);
  };

  const openRest = () => {
    setEditing(null);
    setForm(emptyForm);
    setRestForm({ name: "", code: "", description: "", method: "GET", url: "", bodyExample: "", authType: "NONE" });
    setRestOpen(true);
  };

  const openOpenApi = () => {
    setOpenApiDoc("");
    setOpenApiFileName("");
    setOpenApiBaseUrl("");
    setOpenApiPlan(null);
    setOpenApiPolicy("SKIP");
    setSelectedCodes(new Set());
    setOpenApiOpen(true);
  };

  const onOpenApiFile = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setOpenApiFileName(file.name);
    setOpenApiPlan(null);
    setSelectedCodes(new Set());
    const reader = new FileReader();
    reader.onload = () => setOpenApiDoc(String(reader.result ?? ""));
    reader.readAsText(file);
  };

  const selectableDraft = (t: OpenApiToolDraft) =>
    t.status === "READY" || (openApiPolicy === "OVERWRITE" && t.status === "CONFLICT");

  const toggleCode = (code: string) => {
    setSelectedCodes((prev) => {
      const next = new Set(prev);
      if (next.has(code)) next.delete(code);
      else next.add(code);
      return next;
    });
  };

  const changeOpenApiPolicy = (v: "SKIP" | "OVERWRITE") => {
    setOpenApiPolicy(v);
    if (!openApiPlan) return;
    setSelectedCodes((prev) => {
      const next = new Set(prev);
      for (const t of openApiPlan.tools) {
        if (t.status !== "CONFLICT") continue;
        if (v === "OVERWRITE") next.add(t.code);
        else next.delete(t.code);
      }
      return next;
    });
  };

  const selectAllDrafts = (checked: boolean) => {
    if (!openApiPlan) return;
    setSelectedCodes(
      checked ? new Set(openApiPlan.tools.filter(selectableDraft).map((t) => t.code)) : new Set(),
    );
  };

  const openEdit = (m: McpTool) => {
    setEditing(m);
    setForm({
      systemId: m.systemId,
      name: m.name,
      code: m.code,
      type: m.type,
      status: m.status,
      serviceUrl: m.serviceUrl,
      requestType: m.requestType,
      needToken: m.needToken,
      bodyType: m.bodyType,
      authType: m.authType ?? "NONE",
      // 后端不再回传鉴权配置明文，编辑时一律从空开始；留空 = 保持原凭证不变
      authConfig: "",
      paramMapping: m.paramMapping ?? "",
      resJson: m.resJson,
      tags: m.tags ?? "",
      version: m.version ?? "",
    });
    setDialogOpen(true);
  };

  if (isLoading) return <LoadingState label="加载 Tool…" />;
  if (isError) return <ErrorState message={(error as Error)?.message} onRetry={() => void refetch()} />;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-muted-foreground">
          本地执行器或 HTTP 转发（可配置下游地址、鉴权与参数映射），共 {tools.length} 个。
        </p>
        <div className="flex items-center gap-2">
          <Button variant="outline" onClick={openOpenApi}>
            <Upload className="h-4 w-4" />
            从 OpenAPI 导入
          </Button>
          <Button variant="outline" onClick={openRest}>
            <Wand2 className="h-4 w-4" />
            从 REST 生成
          </Button>
          <Button onClick={openCreate}>
            <Plus className="h-4 w-4" />
            新增 Tool
          </Button>
        </div>
      </div>

      {selectedToolIds.length > 0 && (
        <div className="flex flex-wrap items-center gap-2 rounded-lg border border-primary/30 bg-primary/5 px-3 py-2 text-sm">
          <span className="font-medium">已选 {selectedToolIds.length} 个 Tool</span>
          <Button size="sm" onClick={() => setMoveOpen(true)}>
            <MoveRight className="h-4 w-4" /> 移动到其他 Server
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setSelectedToolIds([])}>取消选择</Button>
        </div>
      )}

      <div className="rounded-xl border bg-card">
        {tools.length === 0 ? (
          <EmptyState icon={Braces} title="暂无 Tool" description="点击「新增 Tool」为该 Server 添加 MCP Tool" />
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-[32px]">
                  <Checkbox
                    checked={tools.length > 0 && selectedToolIds.length === tools.length}
                    onCheckedChange={(v) => setSelectedToolIds(v === true ? tools.map((t) => t.id) : [])}
                    aria-label="全选"
                  />
                </TableHead>
                <TableHead>ID</TableHead>
                <TableHead>名称</TableHead>
                <TableHead>标识 (code)</TableHead>
                <TableHead>类型</TableHead>
                <TableHead>鉴权</TableHead>
                <TableHead>版本</TableHead>
                <TableHead>状态</TableHead>
                <TableHead className="w-[1%] whitespace-nowrap">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {tools.map((m) => (
                <TableRow key={m.id}>
                  <TableCell>
                    <Checkbox
                      checked={selectedToolIds.includes(m.id)}
                      onCheckedChange={(v) =>
                        setSelectedToolIds((prev) =>
                          v === true ? Array.from(new Set([...prev, m.id])) : prev.filter((id) => id !== m.id),
                        )
                      }
                      aria-label={"选择 " + m.code}
                    />
                  </TableCell>
                  <TableCell className="font-mono text-muted-foreground">{m.id}</TableCell>
                  <TableCell className="font-medium">{m.name}</TableCell>
                  <TableCell>
                    <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">{m.code}</code>
                  </TableCell>
                  <TableCell>
                    <Badge variant="outline" className={cn(METHOD_TYPE_COLOR[m.type] ?? METHOD_TYPE_COLOR[0])}>
                      {METHOD_TYPE_LABEL[m.type] ?? m.type}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-xs text-muted-foreground">
                    {AUTH_TYPE_LABEL[m.authType ?? "NONE"] ?? m.authType ?? "无"}
                  </TableCell>
                  <TableCell className="font-mono text-xs text-muted-foreground">
                    v{m.version ?? "1.0.0"}
                    {m.tokenEstimate ? (
                      <div className="text-[10px] text-muted-foreground/70">~{m.tokenEstimate} tok</div>
                    ) : null}
                  </TableCell>
                  <TableCell>
                    <StatusSwitch
                      checked={m.status === 1}
                      onCheckedChange={() => toggleMutation.mutate(m)}
                      aria-label="切换状态"
                    />
                  </TableCell>
                  <TableCell>
                    <RowActions>
                      <Button variant="ghost" size="xs" onClick={() => setViewTarget(m)}>
                        <Eye /> 查看
                      </Button>
                      <Button variant="ghost" size="xs" onClick={() => openEdit(m)}>
                        <Pencil /> 编辑
                      </Button>
                      <Button variant="ghost" size="xs" className="text-destructive" onClick={() => setDeleteTarget(m)}>
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

      {/* 编辑/新增弹窗 */}
      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>{editing ? "编辑 Tool" : "新增 Tool"}</DialogTitle>
            <DialogDescription>定义 MCP Tool：执行器、参数 Schema、下游地址、鉴权与参数映射。</DialogDescription>
          </DialogHeader>
<DialogBody>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div className="space-y-2">
              <Label htmlFor="m-name">名称</Label>
              <Input id="m-name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="m-code">标识 (code)</Label>
              <Input id="m-code" value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} className="font-mono" />
            </div>
            <div className="space-y-2">
              <Label>执行器</Label>
              <Select value={String(form.type)} onValueChange={(v) => setForm({ ...form, type: Number(v) })}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="0">本地 (反射调用)</SelectItem>
                  <SelectItem value="1">HTTP 转发</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label>请求方式</Label>
              <Select value={form.requestType} onValueChange={(v) => setForm({ ...form, requestType: v })}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="GET">GET</SelectItem>
                  <SelectItem value="POST">POST</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2 sm:col-span-2">
              <Label htmlFor="m-url">服务地址 (本地=类名.方法名 / 远程=URL)</Label>
              <Input id="m-url" value={form.serviceUrl} onChange={(e) => setForm({ ...form, serviceUrl: e.target.value })} className="font-mono" />
            </div>
            <div className="space-y-2">
              <Label htmlFor="m-tags">标签（逗号分隔，用于资产目录检索）</Label>
              <Input id="m-tags" value={form.tags} onChange={(e) => setForm({ ...form, tags: e.target.value })} placeholder="crm, 客户" />
            </div>
            <div className="space-y-2">
              <Label htmlFor="m-version">版本（编辑时不改则自动 +0.0.1，填更高版本号则用指定值）</Label>
              <Input id="m-version" value={form.version} onChange={(e) => setForm({ ...form, version: e.target.value })} placeholder="1.0.0" className="font-mono" />
            </div>

            {form.type === 1 && (
              <>
                <div className="space-y-2">
                  <Label>关联系统服务（SK_EXCHANGE 兜底 auth_url）</Label>
                  <Select
                    value={form.systemId ? String(form.systemId) : ""}
                    onValueChange={(v) => setForm({ ...form, systemId: v ? Number(v) : undefined })}
                  >
                    <SelectTrigger>
                      <SelectValue placeholder="可选" />
                    </SelectTrigger>
                    <SelectContent>
                      {systems && systems.length > 0 ? (
                        systems.map((s) => (
                          <SelectItem key={s.id} value={String(s.id)}>{s.name}</SelectItem>
                        ))
                      ) : (
                        <SelectEmpty>暂无系统服务</SelectEmpty>
                      )}
                    </SelectContent>
                  </Select>
                </div>
                <div className="space-y-2">
                  <Label>鉴权方式</Label>
                  <Select value={form.authType} onValueChange={(v) => setForm({ ...form, authType: v })}>
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="NONE">无鉴权</SelectItem>
                      <SelectItem value="SK_EXCHANGE">sk 换 token</SelectItem>
                      <SelectItem value="API_KEY">固定 API Key</SelectItem>
                      <SelectItem value="BEARER">Bearer Token</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
                <div className="space-y-2">
                  <div className="flex items-center justify-between gap-1">
                    <Label htmlFor="m-authcfg">鉴权配置 (JSON)</Label>
                    {editing && (
                      <Badge
                        variant="outline"
                        className={cn(
                          "text-[10px]",
                          editing.authConfigSet
                            ? "border-emerald-500/40 text-emerald-400"
                            : "text-muted-foreground",
                        )}
                      >
                        {editing.authConfigSet ? "已配置" : "未配置"}
                      </Badge>
                    )}
                  </div>
                  <Textarea
                    id="m-authcfg"
                    rows={3}
                    value={form.authConfig}
                    onChange={(e) => setForm({ ...form, authConfig: e.target.value })}
                    className="font-mono text-xs"
                    placeholder={
                      editing?.authConfigSet && form.authConfig.trim() === ""
                        ? "已配置（留空表示不修改；输入新值将覆盖，可能触发审批）"
                        : form.authType === "SK_EXCHANGE"
                          ? '{"auth_url":"https://..."}'
                          : form.authType === "API_KEY"
                            ? '{"header_name":"X-Api-Key","api_key":"..."}'
                            : form.authType === "BEARER"
                              ? '{"token":"..."}'
                              : "{}"
                    }
                  />
                  {editing && (
                    <p className="text-[11px] text-muted-foreground">
                      凭证不在页面回显；如需更换请直接输入新的 JSON。
                    </p>
                  )}
                </div>
                <div className="space-y-2 sm:col-span-2">
                  <ParamMappingEditor
                    value={form.paramMapping}
                    schemaFields={schemaFields}
                    onChange={(v) => setForm({ ...form, paramMapping: v })}
                  />
                </div>
              </>
            )}

            <div className="flex items-center gap-2">
              <Switch checked={form.needToken === 1} onCheckedChange={(v) => setForm({ ...form, needToken: v ? 1 : 0 })} id="m-token" />
              <Label htmlFor="m-token">需要 Token</Label>
            </div>
            <div className="flex items-center gap-2">
              <Switch checked={form.bodyType === 1} onCheckedChange={(v) => setForm({ ...form, bodyType: v ? 1 : 0 })} id="m-form" />
              <Label htmlFor="m-form">表单编码 (form)</Label>
            </div>
            <div className="space-y-2 sm:col-span-2">
              <Label htmlFor="m-res">Tool 定义 (res_json，含 inputSchema)</Label>
              <Textarea
                id="m-res"
                rows={6}
                value={form.resJson}
                onChange={(e) => setForm({ ...form, resJson: e.target.value })}
                className="font-mono text-xs"
              />
              {schemaFields.length > 0 && (
                <p className="text-xs text-muted-foreground">
                  已识别入参字段：{schemaFields.join("、")}
                </p>
              )}
            </div>
          </div>
          {form.type === 1 && testResult && (
            <div className={cn(
              "rounded-md border p-3 text-xs",
              testResult.ok ? "border-emerald-500/30 bg-emerald-500/5 text-emerald-300" : "border-rose-500/30 bg-rose-500/5 text-rose-300",
            )}>
              {testResult.ok ? (
                <div className="space-y-1">
                  <p className="font-medium">测试连接成功{testResult.status ? `（HTTP ${testResult.status}）` : ""}，耗时 {testResult.duration ?? 0}ms</p>
                  {testResult.note && <p className="text-muted-foreground">{testResult.note}</p>}
                  {testResult.body && <p className="line-clamp-3 break-all font-mono text-muted-foreground">响应：{testResult.body}</p>}
                </div>
              ) : (
                <p className="font-medium">测试失败：{testResult.error}</p>
              )}
            </div>
          )}
          </DialogBody>
<DialogFooter>
            <Button variant="outline" onClick={() => setDialogOpen(false)}>
              取消
            </Button>
            {form.type === 1 && (
              <Button variant="outline" onClick={() => { setTestResult(null); testMutation.mutate(); }} disabled={testMutation.isPending}>
                {testMutation.isPending ? "测试中…" : "测试连接"}
              </Button>
            )}
            <Button onClick={() => saveMutation.mutate()} disabled={saveMutation.isPending}>
              {saveMutation.isPending
                ? "提交中…"
                : editing && !directCredential && credentialProvided
                  ? "提交凭证变更审批"
                  : "保存"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* REST → MCP 生成弹窗 */}
      <Dialog open={restOpen} onOpenChange={setRestOpen}>
        <DialogContent className="max-w-lg">
          <DialogHeader>
            <DialogTitle>从 REST 生成 Tool</DialogTitle>
            <DialogDescription>
              粘贴 REST 请求示例，自动生成 inputSchema + 参数映射。路径/query 用 <code className="font-mono">{"{参数}"}</code> 占位。
            </DialogDescription>
          </DialogHeader>
<DialogBody>
          <div className="space-y-3">
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-2">
                <Label htmlFor="r-name">Tool 名称</Label>
                <Input id="r-name" value={restForm.name} onChange={(e) => setRestForm({ ...restForm, name: e.target.value })} placeholder="查询客户" />
              </div>
              <div className="space-y-2">
                <Label htmlFor="r-code">Tool 标识 code</Label>
                <Input id="r-code" value={restForm.code} onChange={(e) => setRestForm({ ...restForm, code: e.target.value })} placeholder="query-customer" className="font-mono" />
              </div>
            </div>
            <div className="space-y-2">
              <Label htmlFor="r-desc">描述</Label>
              <Input id="r-desc" value={restForm.description} onChange={(e) => setRestForm({ ...restForm, description: e.target.value })} placeholder="查询客户信息" />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-2">
                <Label>请求方式</Label>
                <Select value={restForm.method} onValueChange={(v) => setRestForm({ ...restForm, method: v })}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value="GET">GET</SelectItem>
                    <SelectItem value="POST">POST</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-2">
                <Label>鉴权方式</Label>
                <Select value={restForm.authType} onValueChange={(v) => setRestForm({ ...restForm, authType: v })}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value="NONE">NONE</SelectItem>
                    <SelectItem value="API_KEY">API_KEY</SelectItem>
                    <SelectItem value="BEARER">BEARER</SelectItem>
                    <SelectItem value="SK_EXCHANGE">SK_EXCHANGE</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>
            <div className="space-y-2">
              <Label htmlFor="r-url">REST 地址（用 {"{参数}"} 表示入参）</Label>
              <Input id="r-url" value={restForm.url} onChange={(e) => setRestForm({ ...restForm, url: e.target.value })} placeholder="https://api.example.com/users/{id}?region={region}" className="font-mono" />
            </div>
            {restForm.method === "POST" && (
              <div className="space-y-2">
                <Label htmlFor="r-body">请求体示例 JSON</Label>
                <Textarea id="r-body" rows={4} value={restForm.bodyExample} onChange={(e) => setRestForm({ ...restForm, bodyExample: e.target.value })} placeholder='{"name":"张三","age":18}' className="font-mono text-xs" />
              </div>
            )}
          </div>
          </DialogBody>
<DialogFooter>
            <Button variant="outline" onClick={() => setRestOpen(false)}>取消</Button>
            <Button onClick={() => restMutation.mutate()} disabled={!restForm.url || restMutation.isPending}>
              {restMutation.isPending ? "生成中…" : "生成草稿"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 查看 Schema 弹窗 */}
      <Dialog open={!!viewTarget} onOpenChange={(o) => !o && setViewTarget(null)}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>{viewTarget?.name}</DialogTitle>
            <DialogDescription className="font-mono">{viewTarget?.code}</DialogDescription>
          </DialogHeader>
<DialogBody>
          {viewTarget && (() => {
            const detail = parseSchemaDetail(viewTarget.resJson);
            const mappings = parseMapping(viewTarget.paramMapping ?? "");
            return (
              <div className="space-y-4">
                <div className="text-sm">
                  <span className="text-muted-foreground">服务地址：</span>
                  <code className="font-mono text-xs">{viewTarget.serviceUrl || "—"}</code>
                </div>
                {detail.description && (
                  <div className="text-sm">
                    <span className="text-muted-foreground">描述：</span>
                    <span>{detail.description}</span>
                  </div>
                )}

                <div>
                  <div className="mb-1.5 text-sm font-medium">入参字段（inputSchema）</div>
                  {detail.fields.length === 0 ? (
                    <p className="text-xs text-muted-foreground">无入参</p>
                  ) : (
                    <div className="overflow-hidden rounded-lg border">
                      <Table>
                        <TableHeader>
                          <TableRow>
                            <TableHead>字段</TableHead>
                            <TableHead>类型</TableHead>
                            <TableHead>必填</TableHead>
                            <TableHead>说明</TableHead>
                          </TableRow>
                        </TableHeader>
                        <TableBody>
                          {detail.fields.map((f) => (
                            <TableRow key={f.name}>
                              <TableCell className="font-mono text-xs">{f.name}</TableCell>
                              <TableCell className="font-mono text-xs">{f.type}</TableCell>
                              <TableCell className="text-xs">{f.required ? "是" : "否"}</TableCell>
                              <TableCell className="whitespace-normal text-xs text-muted-foreground">{f.description || "—"}</TableCell>
                            </TableRow>
                          ))}
                        </TableBody>
                      </Table>
                    </div>
                  )}
                </div>

                <div>
                  <div className="mb-1.5 text-sm font-medium">参数映射</div>
                  {mappings.length === 0 ? (
                    <p className="text-xs text-muted-foreground">无参数映射（默认透传）</p>
                  ) : (
                    <div className="flex flex-wrap gap-1.5">
                      {mappings.map((m, i) => (
                        <Badge key={i} variant="secondary" className="font-mono text-[11px]">
                          input.{m.source} → {m.target}.{m.targetName}
                        </Badge>
                      ))}
                    </div>
                  )}
                </div>

                <details className="text-sm">
                  <summary className="cursor-pointer text-muted-foreground">查看原始 resJson</summary>
                  <pre className="mt-2 max-h-60 overflow-auto rounded-lg border bg-muted/40 p-3 font-mono text-xs">
                    {prettyJson(viewTarget.resJson)}
                  </pre>
                </details>
              </div>
            );
          })()}
</DialogBody>
        </DialogContent>
      </Dialog>

      {/* OpenAPI 批量导入弹窗 */}
      <Dialog open={openApiOpen} onOpenChange={setOpenApiOpen}>
        <DialogContent className="max-w-5xl">
          <DialogHeader>
            <DialogTitle>从 OpenAPI 导入 Tool</DialogTitle>
            <DialogDescription>
              上传或粘贴 OpenAPI 3.x 文档，先做前置校验并预览全部 Tool，再批量导入；不支持的接口会标注原因。
            </DialogDescription>
          </DialogHeader>
          <DialogBody className="space-y-4">
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <div className="space-y-2">
                <Label htmlFor="oa-file">openapi.json</Label>
                <Input
                  id="oa-file"
                  type="file"
                  accept=".json,application/json"
                  onChange={onOpenApiFile}
                  className="cursor-pointer text-xs file:mr-3 file:rounded file:border-0 file:bg-muted file:px-2 file:py-1 file:text-xs"
                />
                <p className="text-xs text-muted-foreground">
                  {openApiFileName
                    ? `已加载 ${openApiFileName}（${(openApiDoc.length / 1024).toFixed(1)} KB）`
                    : "也可以直接在下方粘贴 JSON"}
                </p>
              </div>
              <div className="space-y-2">
                <Label htmlFor="oa-base">baseUrl（可选，覆盖 servers[0].url）</Label>
                <Input
                  id="oa-base"
                  value={openApiBaseUrl}
                  onChange={(e) => setOpenApiBaseUrl(e.target.value)}
                  placeholder="https://api.example.com"
                  className="font-mono"
                />
              </div>
            </div>

            <div className="space-y-2">
              <Label htmlFor="oa-doc">OpenAPI 文档</Label>
              <Textarea
                id="oa-doc"
                rows={5}
                value={openApiDoc}
                onChange={(e) => {
                  setOpenApiDoc(e.target.value);
                  setOpenApiPlan(null);
                  setSelectedCodes(new Set());
                }}
                placeholder='{"openapi":"3.0.3","info":{...},"paths":{...}}'
                className="font-mono text-xs"
              />
              <div className="flex flex-wrap items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => openApiPreview.mutate()}
                  disabled={!openApiDoc.trim() || openApiPreview.isPending}
                >
                  {openApiPreview.isPending ? "校验中…" : "校验并预览"}
                </Button>
                {openApiPlan && (
                  <span className="text-xs text-muted-foreground">
                    共 {openApiPlan.totalOperations} 个接口：可导入 {openApiPlan.ready}，冲突 {openApiPlan.conflicts}，
                    错误 {openApiPlan.errors}，警告 {openApiPlan.warnings}
                  </span>
                )}
              </div>
            </div>

            {openApiPlan && (
              <div className="space-y-3">
                <div className="flex flex-wrap items-center gap-2 text-xs">
                  {openApiPlan.openapiVersion && (
                    <Badge variant="outline">OpenAPI {openApiPlan.openapiVersion}</Badge>
                  )}
                  {openApiPlan.specTitle && (
                    <Badge variant="muted">
                      {openApiPlan.specTitle}
                      {openApiPlan.specVersion ? ` v${openApiPlan.specVersion}` : ""}
                    </Badge>
                  )}
                  {openApiPlan.baseUrl && (
                    <Badge variant="secondary" className="font-mono">{openApiPlan.baseUrl}</Badge>
                  )}
                </div>

                {openApiPlan.issues.length > 0 && (
                  <div className="space-y-1 rounded-lg border border-amber-500/20 bg-amber-500/5 p-3 text-xs">
                    {openApiPlan.issues.map((i, idx) => (
                      <p key={idx} className={i.level === "ERROR" ? "text-rose-400" : "text-amber-300"}>
                        [{i.level}] {i.message}
                      </p>
                    ))}
                  </div>
                )}

                {openApiPlan.tools.length > 0 && (
                  <>
                    <div className="flex items-center justify-between text-xs text-muted-foreground">
                      <label className="flex cursor-pointer items-center gap-2">
                        <Checkbox
                          checked={
                            selectedCodes.size > 0 &&
                            selectedCodes.size === openApiPlan.tools.filter(selectableDraft).length
                          }
                          onCheckedChange={(v) => selectAllDrafts(!!v)}
                        />
                        全选可导入项
                      </label>
                      <span>已选 {selectedCodes.size} 个</span>
                    </div>
                    <div className="overflow-hidden rounded-lg border">
                      <Table containerClassName="max-h-[38vh]">
                        <TableHeader className="sticky top-0 z-10 bg-card">
                          <TableRow>
                            <TableHead className="w-8" />
                            <TableHead className="w-16">方法</TableHead>
                            <TableHead>路径</TableHead>
                            <TableHead>code</TableHead>
                            <TableHead className="w-20">状态</TableHead>
                            <TableHead>问题</TableHead>
                          </TableRow>
                        </TableHeader>
                        <TableBody>
                          {openApiPlan.tools.map((t) => (
                            <TableRow key={t.code} className={cn(!selectableDraft(t) && "opacity-60")}>
                              <TableCell>
                                <Checkbox
                                  checked={selectedCodes.has(t.code)}
                                  disabled={!selectableDraft(t)}
                                  onCheckedChange={() => toggleCode(t.code)}
                                />
                              </TableCell>
                              <TableCell>
                                <Badge variant={t.method === "GET" ? "info" : "success"} className="font-mono text-[10px]">
                                  {t.method}
                                </Badge>
                              </TableCell>
                              <TableCell className="font-mono text-xs">{t.path}</TableCell>
                              <TableCell className="font-mono text-xs">{t.code}</TableCell>
                              <TableCell>
                                <Badge
                                  variant={t.status === "READY" ? "success" : t.status === "CONFLICT" ? "warning" : "destructive"}
                                  className="text-[10px]"
                                >
                                  {t.status === "READY" ? "可导入" : t.status === "CONFLICT" ? "已存在" : "不可导入"}
                                </Badge>
                              </TableCell>
                              <TableCell className="max-w-[320px] whitespace-normal text-[11px] text-muted-foreground">
                                <span className="line-clamp-2" title={t.issues.map((i) => i.message).join("\n")}>
                                  {t.issues.length > 0 ? t.issues.map((i) => i.message).join("；") : "—"}
                                </span>
                              </TableCell>
                            </TableRow>
                          ))}
                        </TableBody>
                      </Table>
                    </div>
                  </>
                )}
              </div>
            )}
          </DialogBody>
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpenApiOpen(false)}>取消</Button>
            <div className="flex items-center gap-2">
              <Label htmlFor="oa-policy" className="text-xs text-muted-foreground">code 冲突</Label>
              <Select value={openApiPolicy} onValueChange={(v) => changeOpenApiPolicy(v as "SKIP" | "OVERWRITE")}>
                <SelectTrigger id="oa-policy" className="h-9 w-36 text-xs">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="SKIP">跳过</SelectItem>
                  <SelectItem value="OVERWRITE">覆盖</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <Button
              onClick={() => openApiImport.mutate()}
              disabled={!openApiPlan || selectedCodes.size === 0 || openApiImport.isPending}
            >
              {openApiImport.isPending ? "导入中…" : `导入选中 ${selectedCodes.size} 个`}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={moveOpen} onOpenChange={setMoveOpen}>
        <DialogContent className="max-w-lg">
          <DialogHeader>
            <DialogTitle>移动 {selectedToolIds.length} 个 Tool</DialogTitle>
            <DialogDescription>
              只能移动到同一业务方下的非代理 Server。迁移后原 Server 的 Tool 级授权会被清理。
            </DialogDescription>
          </DialogHeader>
          <DialogBody>
            <div className="space-y-4">
              <div className="space-y-2">
                <Label>目标 Server</Label>
                <Select
                  value={moveTargetId ? String(moveTargetId) : ""}
                  onValueChange={(v) => setMoveTargetId(Number(v))}
                >
                  <SelectTrigger>
                    <SelectValue placeholder="选择目标 Server" />
                  </SelectTrigger>
                  <SelectContent>
                    {(allServers ?? [])
                      .filter((sv) => {
                        const current = (allServers ?? []).find((x) => x.id === serverId);
                        return sv.id !== serverId
                          && sv.tenantId === current?.tenantId
                          && sv.upstreamType !== "MCP"
                          && sv.upstreamType !== "AGGREGATE";
                      })
                      .map((sv) => (
                        <SelectItem key={sv.id} value={String(sv.id)}>
                          {sv.name}（{sv.path}）
                        </SelectItem>
                      ))}
                  </SelectContent>
                </Select>
              </div>
              <label className="flex cursor-pointer items-center gap-2 text-sm">
                <Checkbox
                  checked={moveGrantToApps}
                  onCheckedChange={(v) => setMoveGrantToApps(v === true)}
                />
                为原 Server 已授权的应用补齐目标 Server 及被迁移 Tool 的授权
              </label>
              {!moveGrantToApps && (
                <p className="text-xs text-amber-400">
                  未勾选时，应用需要重新授权才能继续调用被迁移的 Tool。
                </p>
              )}
            </div>
          </DialogBody>
          <DialogFooter>
            <Button variant="outline" onClick={() => setMoveOpen(false)}>取消</Button>
            <Button
              onClick={() => moveMutation.mutate()}
              disabled={!moveTargetId || moveMutation.isPending}
            >
              {moveMutation.isPending ? "移动中…" : "确认移动"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={deleteTarget !== null}
        onOpenChange={(o) => !o && setDeleteTarget(null)}
        title="删除 Tool"
        description={`确认删除 Tool「${deleteTarget?.code ?? ""}」？删除后 MCP 客户端将无法再调用该 Tool，且不可恢复。请输入 Tool 标识以确认。`}
        confirmValue={deleteTarget?.code}
        confirmValueLabel={`请输入 Tool 标识：${deleteTarget?.code ?? ""}`}
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
