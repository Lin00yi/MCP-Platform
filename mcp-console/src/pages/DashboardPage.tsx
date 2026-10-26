import { lazy, Suspense } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import {
  Activity,
  AlertTriangle,
  Braces,
  CheckCircle2,
  Clock,
  Server,
  Timer,
  TrendingUp,
} from "lucide-react";
import { api } from "@/lib/api";
import { formatDuration } from "@/lib/utils";
import { StatCard, LoadingState, ErrorState } from "@/components/PageState";

/**
 * 图表区块单独成 chunk 懒加载：recharts + d3 依赖体积大（原产物约 876KB），
 * 而登录后的默认落地页就是概览页，首屏先渲染指标卡更划算。
 */
const DashboardCharts = lazy(() =>
  import("@/pages/DashboardCharts").then((m) => ({ default: m.DashboardCharts })),
);

/** 图表 chunk 下载期间的结构骨架，避免内容出现时布局跳动 */
function ChartsSkeleton() {
  return (
    <div className="space-y-6" aria-busy="true" aria-live="polite">
      <div className="rounded-xl border bg-card">
        <div className="space-y-2 p-6">
          <div className="h-4 w-24 animate-pulse rounded bg-muted" />
          <div className="h-3 w-48 animate-pulse rounded bg-muted" />
        </div>
        <div className="px-6 pb-6">
          <div className="h-[300px] w-full animate-pulse rounded-lg bg-muted/60" />
        </div>
      </div>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        {[0, 1].map((i) => (
          <div key={i} className="rounded-xl border bg-card">
            <div className="space-y-2 p-6">
              <div className="h-4 w-28 animate-pulse rounded bg-muted" />
              <div className="h-3 w-40 animate-pulse rounded bg-muted" />
            </div>
            <div className="px-6 pb-6">
              <div className="h-[300px] w-full animate-pulse rounded-lg bg-muted/60" />
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

export function DashboardPage() {
  const navigate = useNavigate();
  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ["overview"],
    queryFn: ({ signal }) => api.getOverview(signal),
  });

  if (isLoading) return <LoadingState label="加载概览数据…" />;
  if (isError || !data) {
    return <ErrorState message={(error as Error)?.message ?? "未知错误"} onRetry={() => void refetch()} />;
  }

  const { stats, trend, toolDist, callTypeDist } = data;
  const successRate = stats.toolCalls ? ((stats.successCount / stats.toolCalls) * 100).toFixed(1) : "0";

  return (
    <div className="space-y-6">
      {/* 主指标 */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          title="今日请求数"
          value={stats.todayRequests.toLocaleString()}
          icon={Activity}
          accent="bg-primary/15 text-primary"
          hint={
            <span className="inline-flex items-center gap-1 text-emerald-400">
              <TrendingUp className="h-3 w-3" /> 较昨日 +{stats.todayRequestsDelta}%
            </span>
          }
        />
        <StatCard
          title="Tool 调用总数"
          value={stats.toolCalls.toLocaleString()}
          icon={Braces}
          accent="bg-sky-500/15 text-sky-400"
          hint={`${stats.totalRequests.toLocaleString()} 次 MCP 请求`}
        />
        <StatCard
          title="成功率"
          value={`${successRate}%`}
          icon={CheckCircle2}
          accent="bg-emerald-500/15 text-emerald-400"
          hint={`${stats.failCount.toLocaleString()} 次失败`}
        />
        <StatCard
          title="平均耗时"
          value={formatDuration(stats.avgDuration)}
          icon={Timer}
          accent="bg-violet-500/15 text-violet-400"
          hint="tools/call 平均响应时长"
        />
      </div>

      {data.toolBudgetWarnings && data.toolBudgetWarnings.length > 0 && (
        <div className="flex items-start gap-2 rounded-lg border border-amber-500/30 bg-amber-500/10 p-3">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-400" />
          <div className="min-w-0 flex-1">
            <p className="text-sm font-medium text-amber-300">
              {data.toolBudgetWarnings.length} 个 Server 的 Tool 数超过告警阈值
            </p>
            <p className="mt-1 text-xs text-muted-foreground">
              Tool 定义会占用每轮模型上下文；建议启用 META 暴露模式、收紧白名单，或拆分 Server。
            </p>
            <div className="mt-2 flex flex-wrap gap-2">
              {data.toolBudgetWarnings.map((w) => (
                <button
                  key={w.serverId}
                  type="button"
                  onClick={() => navigate(`/servers/${w.serverId}`)}
                  className="rounded border border-amber-500/30 px-2 py-1 text-xs text-amber-200 hover:bg-amber-500/10"
                >
                  {w.serverName} · {w.toolCount} 个{w.overMax ? "（超硬阈值）" : ""}
                </button>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* 次级指标 */}
      <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
        <StatCard title="租户总数" value={stats.tenantCount} icon={Server} hint={`${stats.activeTenantCount} 个启用`} />
        <StatCard title="Tool 总数" value={stats.toolCount} icon={Braces} />
        <StatCard title="请求总量" value={stats.totalRequests.toLocaleString()} icon={Clock} />
      </div>

      {/* 图表区块：懒加载，加载期间展示骨架 */}
      <Suspense fallback={<ChartsSkeleton />}>
        <DashboardCharts
          trend={trend}
          toolDist={toolDist}
          callTypeDist={callTypeDist}
          successCount={stats.successCount}
          failCount={stats.failCount}
        />
      </Suspense>
    </div>
  );
}
