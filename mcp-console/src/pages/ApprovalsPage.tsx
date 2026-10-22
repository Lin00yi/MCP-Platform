import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Check, Copy, Inbox, KeyRound, ShieldCheck, Undo2, X } from "lucide-react";
import { api } from "@/lib/api";
import { APPROVAL_ACTION_LABEL, canApprove, useApprovalPolicy } from "@/lib/approval";
import { getUser, roleLevel } from "@/lib/auth";
import { formatDateTime } from "@/lib/utils";
import type { ApprovalRequest, ApprovalStatus } from "@/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
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
import { LoadingState, EmptyState, ErrorState } from "@/components/PageState";

const STATUS_META: Record<ApprovalStatus, { label: string; cls: string }> = {
  PENDING: { label: "待审批", cls: "text-amber-400 bg-amber-400/10 border-amber-400/20" },
  APPROVED: { label: "已通过", cls: "text-emerald-400 bg-emerald-400/10 border-emerald-400/20" },
  REJECTED: { label: "已驳回", cls: "text-rose-400 bg-rose-400/10 border-rose-400/20" },
  CANCELLED: { label: "已撤回", cls: "text-muted-foreground bg-muted border-border" },
};

const PAGE_SIZE = 20;

export function ApprovalsPage() {
  const queryClient = useQueryClient();
  const me = getUser();
  const level = roleLevel();
  const canReview = level >= 3;

  const [scope, setScope] = useState<"pending" | "mine" | "all">(canReview ? "pending" : "mine");
  const [statusFilter, setStatusFilter] = useState("ALL");
  const [pageNum, setPageNum] = useState(1);


  const [detailId, setDetailId] = useState<number | null>(null);
  const [comment, setComment] = useState("");

  const [secret, setSecret] = useState<{ id: number; value: string } | null>(null);

  const { data: policy } = useApprovalPolicy();

  const { data: page, isLoading, isError, error } = useQuery({
    queryKey: ["approvals", scope, statusFilter, pageNum],
    queryFn: () =>
      api.listApprovals({
        pageNum,
        pageSize: PAGE_SIZE,
        scope,
        status: scope === "pending" || statusFilter === "ALL" ? undefined : statusFilter,
      }),
  });

  const { data: servers } = useQuery({ queryKey: ["servers"], queryFn: api.listServers });
  const { data: tools } = useQuery({ queryKey: ["tools"], queryFn: api.listTools });
  const { data: prompts } = useQuery({ queryKey: ["prompts"], queryFn: api.listPrompts });
  const { data: resources } = useQuery({ queryKey: ["resources"], queryFn: api.listResources });

  const { data: detail } = useQuery({
    queryKey: ["approval-detail", detailId],
    queryFn: () => api.getApproval(detailId as number),
    enabled: detailId !== null,
  });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ["approvals"] });
    queryClient.invalidateQueries({ queryKey: ["approval-detail"] });
    queryClient.invalidateQueries({ queryKey: ["app-server-rels"] });
    queryClient.invalidateQueries({ queryKey: ["app-tool-rels"] });
    queryClient.invalidateQueries({ queryKey: ["app-prompt-rels"] });
    queryClient.invalidateQueries({ queryKey: ["app-resource-rels"] });
    queryClient.invalidateQueries({ queryKey: ["servers"] });
    queryClient.invalidateQueries({ queryKey: ["tools"] });
    queryClient.invalidateQueries({ queryKey: ["apps"] });
    queryClient.invalidateQueries({ queryKey: ["users"] });
    queryClient.invalidateQueries({ queryKey: ["operation-logs"] });
  };

  const decideMutation = useMutation({
    mutationFn: ({ id, kind, text }: { id: number; kind: "approve" | "reject" | "cancel"; text?: string }) => {
      if (kind === "approve") return api.approveApproval(id, text);
      if (kind === "reject") return api.rejectApproval(id, text);
      return api.cancelApproval(id, text);
    },
    onSuccess: (_r, vars) => {
      toast.success(vars.kind === "approve" ? "已通过并执行" : vars.kind === "reject" ? "已驳回" : "已撤回");
      setDetailId(null);
      setComment("");
      invalidate();
    },
    onError: (e) => toast.error("操作失败", { description: (e as Error).message }),
  });

  const secretMutation = useMutation({
    mutationFn: (id: number) => api.revealApprovalSecret(id),
    onSuccess: (res, id) => setSecret({ id, value: res.secret }),
    onError: (e) => toast.error("获取密钥失败", { description: (e as Error).message }),
  });

  const serverName = (id?: number) => servers?.find((s) => s.id === id)?.name ?? (id ? `server#${id}` : "—");
  const toolName = (id?: number) => tools?.find((t) => t.id === id)?.code ?? (id ? `tool#${id}` : "—");
  const promptName = (id?: number) => prompts?.find((p) => p.id === id)?.name ?? (id ? `prompt#${id}` : "—");
  const resourceName = (id?: number) => resources?.find((r) => r.id === id)?.name ?? (id ? `resource#${id}` : "—");

  const targetText = (r: ApprovalRequest) => {
    const parts: string[] = [];
    if (r.appId) parts.push(`应用 ${r.appId}`);
    if (r.serverId) parts.push(`Server ${serverName(r.serverId)}`);
    if (r.toolId) parts.push(`Tool ${toolName(r.toolId)}`);
    if (r.promptId) parts.push(`Prompt ${promptName(r.promptId)}`);
    if (r.resourceId) parts.push(`Resource ${resourceName(r.resourceId)}`);
    if (r.userId) parts.push(`用户 #${r.userId}`);
    return parts.length > 0 ? parts.join(" · ") : "—";
  };

  if (isLoading) return <LoadingState label="加载审批…" />;
  if (isError) return <ErrorState message={(error as Error)?.message} />;

  const total = page?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-muted-foreground">
          审批中心：授权、连接配置、应用凭证、用户提权统一在此流转；通过后由后端按申请快照执行。
        </p>
        <div className="flex gap-2">
          <Select
            value={scope}
            onValueChange={(v) => {
              setScope(v as typeof scope);
              setPageNum(1);
              setStatusFilter("ALL");
            }}
          >
            <SelectTrigger className="w-[130px]">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {canReview && <SelectItem value="pending">待我审批</SelectItem>}
              <SelectItem value="mine">我发起的</SelectItem>
              {canReview && <SelectItem value="all">全部</SelectItem>}
            </SelectContent>
          </Select>
          {scope !== "pending" && (
            <Select value={statusFilter} onValueChange={(v) => { setStatusFilter(v); setPageNum(1); }}>
              <SelectTrigger className="w-[120px]">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">全部状态</SelectItem>
                <SelectItem value="PENDING">待审批</SelectItem>
                <SelectItem value="APPROVED">已通过</SelectItem>
                <SelectItem value="REJECTED">已驳回</SelectItem>
                <SelectItem value="CANCELLED">已撤回</SelectItem>
              </SelectContent>
            </Select>
          )}
        </div>
      </div>

      {scope === "pending" && (
        <p className="rounded-md border border-border/60 bg-muted/30 px-3 py-2 text-xs text-muted-foreground">
          <ShieldCheck className="mr-1 inline h-3.5 w-3.5" />
          审批人必须高于申请人且不能审批自己的申请；审批通过后立即执行，执行失败会自动回滚并保持待审批。
        </p>
      )}

      <div className="rounded-xl border bg-card">
        {!page || page.list.length === 0 ? (
          <EmptyState
            icon={Inbox}
            title={scope === "pending" ? "暂无待审批" : "暂无申请"}
            description={scope === "pending" ? "当前没有需要你处理的申请" : "可在权限页或各资源页发起申请"}
          />
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>ID</TableHead>
                <TableHead>动作</TableHead>
                <TableHead>对象</TableHead>
                <TableHead>申请人</TableHead>
                <TableHead>审批人</TableHead>
                <TableHead>状态</TableHead>
                <TableHead>时间</TableHead>
                <TableHead className="w-[1%] whitespace-nowrap">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {page.list.map((r) => {
                const isMine = r.requester === me?.username;
                const canDecide = r.status === "PENDING" && !isMine && canApprove(policy, r.action);
                const canCancel = r.status === "PENDING" && isMine;
                const canReveal = r.status === "APPROVED" && isMine && (r.action === "APP_CREATE" || r.action === "APP_RESET_SECRET");
                return (
                  <TableRow key={r.id}>
                    <TableCell className="font-mono text-muted-foreground">{r.id}</TableCell>
                    <TableCell className="text-xs font-medium">
                      {APPROVAL_ACTION_LABEL[r.action] ?? r.action}
                    </TableCell>
                    <TableCell className="max-w-[260px] truncate text-xs text-muted-foreground" title={targetText(r)}>
                      {targetText(r)}
                    </TableCell>
                    <TableCell className="text-xs">
                      {r.requester ?? "—"}
                      {r.requesterRole && <span className="ml-1 text-muted-foreground">({r.requesterRole})</span>}
                    </TableCell>
                    <TableCell className="text-xs">
                      {r.approver ?? "—"}
                      {r.approverRole && <span className="ml-1 text-muted-foreground">({r.approverRole})</span>}
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline" className={STATUS_META[r.status]?.cls}>
                        {STATUS_META[r.status]?.label ?? r.status}
                      </Badge>
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">{formatDateTime(r.createTime)}</TableCell>
                    <TableCell>
                      <RowActions>
                        {canDecide && (
                          <>
                            <Button variant="ghost" size="xs" className="text-emerald-400" onClick={() => { setDetailId(r.id); setComment(""); }}>
                              <Check className="h-3.5 w-3.5" /> 通过
                            </Button>
                            <Button variant="ghost" size="xs" className="text-rose-400" onClick={() => { setDetailId(r.id); setComment(""); }}>
                              <X className="h-3.5 w-3.5" /> 驳回
                            </Button>
                          </>
                        )}
                        {canCancel && (
                          <Button variant="ghost" size="xs" onClick={() => { setDetailId(r.id); setComment(""); }}>
                            <Undo2 className="h-3.5 w-3.5" /> 撤回
                          </Button>
                        )}
                        {canReveal && (
                          <Button variant="ghost" size="xs" onClick={() => secretMutation.mutate(r.id)} disabled={secretMutation.isPending}>
                            <KeyRound className="h-3.5 w-3.5" /> 密钥
                          </Button>
                        )}
                        <Button variant="ghost" size="xs" onClick={() => { setDetailId(r.id); setComment(""); }}>
                          详情
                        </Button>
                      </RowActions>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </div>

      {totalPages > 1 && (
        <div className="flex items-center justify-end gap-2">
          <Button variant="outline" size="sm" disabled={pageNum <= 1} onClick={() => setPageNum((p) => p - 1)}>上一页</Button>
          <span className="text-xs text-muted-foreground">{pageNum} / {totalPages}</span>
          <Button variant="outline" size="sm" disabled={pageNum >= totalPages} onClick={() => setPageNum((p) => p + 1)}>下一页</Button>
        </div>
      )}

      {/* 详情 / 审批 */}
      <Dialog open={detailId !== null} onOpenChange={(o) => { if (!o) { setDetailId(null); setComment(""); } }}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>审批详情</DialogTitle>
            <DialogDescription>{detail ? `${APPROVAL_ACTION_LABEL[detail.action] ?? detail.action} · #${detail.id}` : "加载中…"}</DialogDescription>
          </DialogHeader>
          <DialogBody>
            {detail ? (
              <div className="space-y-4 text-sm">
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                  <div><span className="text-muted-foreground">对象：</span>{targetText(detail)}</div>
                  <div><span className="text-muted-foreground">状态：</span>{STATUS_META[detail.status]?.label ?? detail.status}</div>
                  <div><span className="text-muted-foreground">申请人：</span>{detail.requester ?? "—"} {detail.requesterRole ? `(${detail.requesterRole})` : ""}</div>
                  <div><span className="text-muted-foreground">审批人：</span>{detail.approver ?? "—"} {detail.approverRole ? `(${detail.approverRole})` : ""}</div>
                  <div><span className="text-muted-foreground">申请时间：</span>{formatDateTime(detail.createTime)}</div>
                  <div><span className="text-muted-foreground">处理时间：</span>{formatDateTime(detail.updateTime)}</div>
                </div>
                <div>
                  <div className="mb-1 text-muted-foreground">申请理由</div>
                  <div className="rounded-md border bg-muted/30 px-3 py-2 text-xs">{detail.reason || "—"}</div>
                </div>
                {detail.payload && (
                  <div>
                    <div className="mb-1 text-muted-foreground">执行快照（凭证字段已脱敏）</div>
                    <pre className="max-h-56 overflow-auto rounded-md border bg-muted/30 p-3 text-[11px] leading-relaxed">{detail.payload}</pre>
                  </div>
                )}
                {detail.comment && (
                  <div>
                    <div className="mb-1 text-muted-foreground">审批意见</div>
                    <div className="rounded-md border bg-muted/30 px-3 py-2 text-xs">{detail.comment}</div>
                  </div>
                )}
                {detail.status === "PENDING" && (
                  <div className="space-y-2">
                    <Label htmlFor="approval-comment">审批意见（可选）</Label>
                    <Textarea id="approval-comment" rows={3} value={comment} onChange={(e) => setComment(e.target.value)} placeholder="说明通过/驳回/撤回原因" />
                  </div>
                )}
              </div>
            ) : (
              <LoadingState label="加载详情…" />
            )}
          </DialogBody>
          <DialogFooter>
            <Button variant="outline" onClick={() => { setDetailId(null); setComment(""); }}>关闭</Button>
            {detail && detail.status === "PENDING" && detail.requester === me?.username && (
              <Button
                variant="outline"
                disabled={decideMutation.isPending}
                onClick={() => decideMutation.mutate({ id: detail.id, kind: "cancel", text: comment })}
              >
                撤回申请
              </Button>
            )}
            {detail && detail.status === "PENDING" && detail.requester !== me?.username && canApprove(policy, detail.action) && (
              <>
                <Button
                  variant="outline"
                  className="text-rose-400"
                  disabled={decideMutation.isPending}
                  onClick={() => decideMutation.mutate({ id: detail.id, kind: "reject", text: comment })}
                >
                  驳回
                </Button>
                <Button
                  disabled={decideMutation.isPending}
                  onClick={() => decideMutation.mutate({ id: detail.id, kind: "approve", text: comment })}
                >
                  {decideMutation.isPending ? "执行中…" : "通过并执行"}
                </Button>
              </>
            )}
            {detail && detail.status === "APPROVED" && detail.requester === me?.username && (detail.action === "APP_CREATE" || detail.action === "APP_RESET_SECRET") && (
              <Button onClick={() => secretMutation.mutate(detail.id)} disabled={secretMutation.isPending}>
                <KeyRound className="h-4 w-4" /> 查看应用密钥
              </Button>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 密钥一次性展示 */}
      <Dialog open={secret !== null} onOpenChange={(o) => !o && setSecret(null)}>
        <DialogContent className="max-w-lg">
          <DialogHeader>
            <DialogTitle>应用密钥</DialogTitle>
            <DialogDescription>审批通过后生成，仅申请人可查看；请立即保存，后续如需重置需再次审批。</DialogDescription>
          </DialogHeader>
          <DialogBody>
            <div className="flex items-center gap-2 rounded-md border bg-muted/40 p-3">
              <code className="flex-1 break-all font-mono text-xs">{secret?.value}</code>
              <Button
                variant="outline"
                size="icon"
                onClick={() => {
                  if (secret) {
                    navigator.clipboard?.writeText(secret.value);
                    toast.success("已复制");
                  }
                }}
              >
                <Copy className="h-4 w-4" />
              </Button>
            </div>
          </DialogBody>
          <DialogFooter>
            <Button onClick={() => setSecret(null)}>关闭</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
