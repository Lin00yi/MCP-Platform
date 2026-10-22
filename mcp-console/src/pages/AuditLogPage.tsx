import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { History } from "lucide-react";
import { api } from "@/lib/api";
import { cn, formatDateTime } from "@/lib/utils";
import type { OperationLog } from "@/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
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
import { LoadingState, EmptyState, ErrorState } from "@/components/PageState";

const ACTION_LABEL: Record<string, string> = {
  CREATE: "创建",
  UPDATE: "更新",
  DELETE: "删除",
  GRANT: "授权",
  REVOKE: "撤销",
  APPROVE: "审批通过",
  REJECT: "审批驳回",
  CANCEL: "撤回申请",
  REQUEST: "提交申请",
  REFRESH: "刷新",
};

const ACTION_COLOR: Record<string, string> = {
  CREATE: "text-emerald-400 bg-emerald-400/10 border-emerald-400/20",
  UPDATE: "text-sky-400 bg-sky-400/10 border-sky-400/20",
  DELETE: "text-rose-400 bg-rose-400/10 border-rose-400/20",
  GRANT: "text-violet-400 bg-violet-400/10 border-violet-400/20",
  REVOKE: "text-amber-400 bg-amber-400/10 border-amber-400/20",
  APPROVE: "text-emerald-400 bg-emerald-400/10 border-emerald-400/20",
  REJECT: "text-rose-400 bg-rose-400/10 border-rose-400/20",
  CANCEL: "text-muted-foreground bg-muted border-border",
  REQUEST: "text-sky-400 bg-sky-400/10 border-sky-400/20",
};

const PAGE_SIZE = 20;

export function AuditLogPage() {
  const [pageNum, setPageNum] = useState(1);
  const [actionFilter, setActionFilter] = useState("all");

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["operation-logs", pageNum, actionFilter],
    queryFn: () => api.listOperationLogs({ pageNum, pageSize: PAGE_SIZE, action: actionFilter === "all" ? undefined : actionFilter }),
  });

  if (isLoading) return <LoadingState label="加载审计日志…" />;
  if (isError) return <ErrorState message={(error as Error)?.message} />;

  const total = data?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-muted-foreground">
          后台操作留痕：谁在什么时候对什么资源做了什么，共 {total} 条。
        </p>
        <Select value={actionFilter} onValueChange={(v) => { setActionFilter(v); setPageNum(1); }}>
          <SelectTrigger className="w-[140px]">
            <SelectValue placeholder="操作类型" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">全部操作</SelectItem>
            {Object.entries(ACTION_LABEL).map(([k, v]) => (
              <SelectItem key={k} value={k}>{v}</SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="rounded-xl border bg-card">
        {(!data || data.list.length === 0) ? (
          <EmptyState icon={History} title="暂无审计日志" />
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>时间</TableHead>
                <TableHead>操作者</TableHead>
                <TableHead>操作</TableHead>
                <TableHead>资源类型</TableHead>
                <TableHead>资源 ID</TableHead>
                <TableHead>详情</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.list.map((l: OperationLog) => (
                <TableRow key={l.id}>
                  <TableCell className="text-muted-foreground whitespace-nowrap">{formatDateTime(l.createTime)}</TableCell>
                  <TableCell className="font-mono text-xs">{l.username ?? "—"}</TableCell>
                  <TableCell>
                    <Badge variant="outline" className={cn(ACTION_COLOR[l.action] ?? "text-muted-foreground")}>
                      {ACTION_LABEL[l.action] ?? l.action}
                    </Badge>
                  </TableCell>
                  <TableCell className="font-mono text-xs text-muted-foreground">{l.resourceType}</TableCell>
                  <TableCell className="font-mono text-xs text-muted-foreground">{l.resourceId ?? "—"}</TableCell>
                  <TableCell className="max-w-[300px] truncate font-mono text-xs text-muted-foreground">{l.detail ?? "—"}</TableCell>
                </TableRow>
              ))}
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
    </div>
  );
}
