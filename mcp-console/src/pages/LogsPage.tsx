import { useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { toast } from "sonner";
import {
  Activity,
  Braces,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Download,
  FileClock,
  ListFilter,
  RefreshCw,
  RotateCcw,
  Search,
  Timer,
} from "lucide-react";
import { api } from "@/lib/api";
import { cn, formatDateTime, formatDuration } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
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
import { LoadingState, EmptyState, ErrorState, StatCard } from "@/components/PageState";

function toBackendTime(v: string): string | undefined {
  return v ? v.replace("T", " ") : undefined;
}

function formatTime(ts?: number): string {
  if (!ts) return "—";
  const d = new Date(ts);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

const AUTO_REFRESH_KEY = "mcp_console_logs_auto_refresh";

function readAutoRefresh(): boolean {
  try {
    return localStorage.getItem(AUTO_REFRESH_KEY) === "1";
  } catch {
    return false;
  }
}

const BLANK_FILTERS = {
  startTime: "",
  endTime: "",
  success: "all",
  serverId: "all",
  userId: "",
};

function Pagination({
  page,
  pageSize,
  total,
  onPageChange,
}: {
  page: number;
  pageSize: number;
  total: number;
  onPageChange: (p: number) => void;
}) {
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  return (
    <div className="flex items-center justify-between gap-3 border-t px-4 py-3">
      <span className="text-xs text-muted-foreground">
        共 {total} 条 · 第 {page}/{totalPages} 页
      </span>
      <div className="flex items-center gap-2">
        <Button variant="outline" size="sm" disabled={page <= 1} onClick={() => onPageChange(page - 1)}>
          <ChevronLeft className="h-4 w-4" />
          上一页
        </Button>
        <Button variant="outline" size="sm" disabled={page >= totalPages} onClick={() => onPageChange(page + 1)}>
          下一页
          <ChevronRight className="h-4 w-4" />
        </Button>
      </div>
    </div>
  );
}

function ToolCallRecords({
  autoRefresh,
  onAutoRefreshChange,
}: {
  autoRefresh: boolean;
  onAutoRefreshChange: (v: boolean) => void;
}) {
  const [filters, setFilters] = useState({ ...BLANK_FILTERS });
  const [applied, setApplied] = useState({ ...BLANK_FILTERS });
  const [page, setPage] = useState(1);
  const pageSize = 10;

  const { data: servers } = useQuery({ queryKey: ["servers"], queryFn: ({ signal }) => api.listServers(signal) });
  const { data: tenants } = useQuery({ queryKey: ["tenants"], queryFn: ({ signal }) => api.listTenants(signal) });

  const tenantNameOf = (id?: number) => tenants?.find((t) => t.id === id)?.name;
  const userIdFilter = applied.userId.trim();

  const { data, isLoading, isError, error, isFetching, refetch, dataUpdatedAt } = useQuery({
    queryKey: ["tool-call-records", applied, page],
    queryFn: ({ signal }) =>
      api.listToolCallRecords(
        {
          pageNum: page,
          pageSize,
          startTime: toBackendTime(applied.startTime),
          endTime: toBackendTime(applied.endTime),
          success: applied.success === "all" ? undefined : Number(applied.success),
          serverId: applied.serverId === "all" ? undefined : Number(applied.serverId),
          userId: userIdFilter || undefined,
        },
        signal,
      ),
    placeholderData: keepPreviousData,
    refetchInterval: autoRefresh ? 5000 : false,
    // 后台标签页不轮询：自动刷新只在页面可见时生效
    refetchIntervalInBackground: false,
  });

  const handleExport = async () => {
    try {
      const res = await api.exportLogs({
        startTime: toBackendTime(applied.startTime),
        endTime: toBackendTime(applied.endTime),
        success: applied.success === "all" ? undefined : Number(applied.success),
        serverId: applied.serverId === "all" ? undefined : Number(applied.serverId),
        userId: userIdFilter || undefined,
      });
      toast.success("导出已触发", { description: res.fileName });
    } catch (e) {
      toast.error("导出失败", { description: (e as Error).message });
    }
  };

  const applyFilters = () => {
    setApplied(filters);
    setPage(1);
  };

  const reset = () => {
    setFilters({ ...BLANK_FILTERS });
    setApplied({ ...BLANK_FILTERS });
    setPage(1);
  };

  return (
    <div className="space-y-4">
      <div className="rounded-xl border bg-card">
        <div className="flex flex-wrap items-center gap-2 border-b px-4 py-3">
          <ListFilter className="h-4 w-4 text-muted-foreground" />
          <span className="text-sm font-medium">筛选</span>
          <div className="ml-auto flex items-center gap-2">
            <span className="hidden text-[11px] tabular-nums text-muted-foreground sm:inline">
              最近更新 {formatTime(dataUpdatedAt)}
            </span>
            <Button
              variant="outline"
              size="icon"
              className="h-9 w-9"
              onClick={() => refetch()}
              title="立即刷新"
              aria-label="立即刷新"
            >
              <RefreshCw className={cn("h-4 w-4", isFetching && "animate-spin")} />
            </Button>
            <div className="flex h-9 items-center gap-2 rounded-md border px-3">
              <RefreshCw className={cn("h-3.5 w-3.5 text-muted-foreground", autoRefresh && "animate-spin text-primary")} />
              <span className="text-xs text-muted-foreground">自动刷新 5s</span>
              <Switch checked={autoRefresh} onCheckedChange={onAutoRefreshChange} aria-label="自动刷新" />
            </div>
            <Button variant="outline" onClick={handleExport}>
              <Download className="h-4 w-4" />
              导出 Excel
            </Button>
          </div>
        </div>
        <div className="flex flex-wrap items-end gap-3 p-4">
          <div className="space-y-1.5">
            <label className="text-xs text-muted-foreground">开始时间</label>
            <Input
              type="datetime-local"
              value={filters.startTime}
              onChange={(e) => setFilters({ ...filters, startTime: e.target.value })}
              className="w-48"
            />
          </div>
          <div className="space-y-1.5">
            <label className="text-xs text-muted-foreground">结束时间</label>
            <Input
              type="datetime-local"
              value={filters.endTime}
              onChange={(e) => setFilters({ ...filters, endTime: e.target.value })}
              className="w-48"
            />
          </div>
          <div className="space-y-1.5">
            <label className="text-xs text-muted-foreground">用户</label>
            <Input
              value={filters.userId}
              onChange={(e) => setFilters({ ...filters, userId: e.target.value })}
              onKeyDown={(e) => {
                if (e.key === "Enter") applyFilters();
              }}
              placeholder="用户名 / userId"
              className="w-44"
            />
          </div>
          <div className="space-y-1.5">
            <label className="text-xs text-muted-foreground">MCP Server/租户</label>
            <Select value={filters.serverId} onValueChange={(v) => setFilters({ ...filters, serverId: v })}>
              <SelectTrigger className="w-56">
                <SelectValue placeholder="全部 MCP Server" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">全部 MCP Server</SelectItem>
                {servers?.map((s) => (
                  <SelectItem key={s.id} value={String(s.id)}>
                    {`${s.name} / ${tenantNameOf(s.tenantId) ?? "—"}`}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-1.5">
            <label className="text-xs text-muted-foreground">结果</label>
            <Select value={filters.success} onValueChange={(v) => setFilters({ ...filters, success: v })}>
              <SelectTrigger className="w-28">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">全部</SelectItem>
                <SelectItem value="1">成功</SelectItem>
                <SelectItem value="0">失败</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="flex items-center gap-2">
            <Button onClick={applyFilters}>
              <Search className="h-4 w-4" />
              查询
            </Button>
            <Button variant="outline" onClick={reset}>
              <RotateCcw className="h-4 w-4" />
              重置
            </Button>
          </div>
        </div>
      </div>

      <div className="rounded-xl border bg-card">
        <div className={cn("transition-opacity", isFetching && "opacity-60")}>
          {isLoading ? (
            <LoadingState label="加载 Tool 调用记录…" />
          ) : isError ? (
            <ErrorState message={(error as Error)?.message} onRetry={() => void refetch()} />
          ) : !data || data.list.length === 0 ? (
            <EmptyState icon={FileClock} title="暂无 Tool 调用记录" />
          ) : (
            <>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>ID</TableHead>
                    <TableHead>用户</TableHead>
                    <TableHead>MCP Server/租户</TableHead>
                    <TableHead>Tool (code)</TableHead>
                    <TableHead>类型</TableHead>
                    <TableHead>结果</TableHead>
                    <TableHead>耗时</TableHead>
                    <TableHead>调用时间</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {data.list.map((r) => (
                    <TableRow key={r.id}>
                      <TableCell className="font-mono text-muted-foreground">{r.id}</TableCell>
                      <TableCell>
                        {r.userName || r.userId ? (
                          <div className="leading-tight">
                            <div className="text-xs">{r.userName || r.userId}</div>
                            {r.userName && r.userId && (
                              <div className="font-mono text-[11px] text-muted-foreground">{r.userId}</div>
                            )}
                          </div>
                        ) : (
                          <span className="text-xs text-muted-foreground">—</span>
                        )}
                      </TableCell>
                      <TableCell className="text-xs">
                        <span>{r.serverName ?? "—"}</span>
                        <span className="px-1 text-muted-foreground">/</span>
                        <span className="font-mono">{r.tenantName ?? "—"}</span>
                      </TableCell>
                      <TableCell>
                        <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">{r.methodCode}</code>
                      </TableCell>
                      <TableCell>
                        <Badge variant={r.callType === "LOCAL" ? "info" : "warning"}>{r.callType}</Badge>
                      </TableCell>
                      <TableCell>
                        <Badge variant={r.success === 1 ? "success" : "destructive"}>
                          {r.success === 1 ? "成功" : "失败"}
                        </Badge>
                      </TableCell>
                      <TableCell className="font-mono text-xs">{formatDuration(r.duration)}</TableCell>
                      <TableCell className="text-muted-foreground">{formatDateTime(r.createTime)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              <Pagination page={data.pageNum} pageSize={data.pageSize} total={data.total} onPageChange={setPage} />
            </>
          )}
        </div>
      </div>
    </div>
  );
}

export function LogsPage() {
  const [autoRefresh, setAutoRefresh] = useState(readAutoRefresh);

  const handleAutoRefreshChange = (v: boolean) => {
    setAutoRefresh(v);
    try {
      localStorage.setItem(AUTO_REFRESH_KEY, v ? "1" : "0");
    } catch {
      // localStorage 不可用时仅本会话生效
    }
  };

  const { data: stats } = useQuery({
    queryKey: ["log-stats"],
    queryFn: ({ signal }) => api.getLogStats(signal),
    refetchInterval: autoRefresh ? 5000 : false,
    refetchIntervalInBackground: false,
  });

  const successRate = stats && stats.total > 0 ? ((stats.successCount / stats.total) * 100).toFixed(1) : "0";

  return (
    <div className="space-y-5">
      {/* 顶部统计 */}
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <StatCard
          title="Tool 调用总数"
          value={stats ? stats.total.toLocaleString() : "—"}
          icon={Braces}
          accent="bg-sky-500/15 text-sky-400"
        />
        <StatCard
          title="今日调用"
          value={stats ? stats.today.toLocaleString() : "—"}
          icon={Activity}
          accent="bg-primary/15 text-primary"
        />
        <StatCard
          title="成功率"
          value={stats ? `${successRate}%` : "—"}
          icon={CheckCircle2}
          accent="bg-emerald-500/15 text-emerald-400"
          hint={stats ? `失败 ${stats.failCount} 次` : undefined}
        />
        <StatCard
          title="平均耗时"
          value={stats ? formatDuration(stats.avgDuration) : "—"}
          icon={Timer}
          accent="bg-violet-500/15 text-violet-400"
        />
      </div>

      <ToolCallRecords autoRefresh={autoRefresh} onAutoRefreshChange={handleAutoRefreshChange} />
    </div>
  );
}
