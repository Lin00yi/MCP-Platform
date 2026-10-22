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
  XCircle,
} from "lucide-react";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip as RechartsTooltip,
  XAxis,
  YAxis,
} from "recharts";
import { api } from "@/lib/api";
import { formatDuration } from "@/lib/utils";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { StatCard, LoadingState, ErrorState } from "@/components/PageState";

const PIE_COLORS = ["hsl(142 71% 45%)", "hsl(199 89% 48%)", "hsl(262 83% 58%)", "hsl(45 93% 47%)", "hsl(0 72% 51%)"];

export function DashboardPage() {
  const navigate = useNavigate();
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["overview"],
    queryFn: api.getOverview,
  });

  if (isLoading) return <LoadingState label="加载概览数据…" />;
  if (isError || !data) return <ErrorState message={(error as Error)?.message ?? "未知错误"} />;

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

      {/* 趋势图 */}
      <Card>
        <CardHeader>
          <CardTitle>请求趋势</CardTitle>
          <CardDescription>近 14 天请求量与成功/失败分布</CardDescription>
        </CardHeader>
        <CardContent>
          <div className="h-[300px] w-full">
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={trend} margin={{ top: 8, right: 8, left: -12, bottom: 0 }}>
                <defs>
                  <linearGradient id="fillRequests" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="hsl(142 71% 45%)" stopOpacity={0.35} />
                    <stop offset="95%" stopColor="hsl(142 71% 45%)" stopOpacity={0} />
                  </linearGradient>
                  <linearGradient id="fillFailed" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="hsl(0 72% 51%)" stopOpacity={0.3} />
                    <stop offset="95%" stopColor="hsl(0 72% 51%)" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" stroke="hsl(217 33% 20%)" />
                <XAxis dataKey="time" tick={{ fontSize: 12, fill: "hsl(215 20% 65%)" }} stroke="hsl(217 33% 20%)" />
                <YAxis tick={{ fontSize: 12, fill: "hsl(215 20% 65%)" }} stroke="hsl(217 33% 20%)" />
                <RechartsTooltip
                  contentStyle={{
                    background: "hsl(217 33% 13%)",
                    border: "1px solid hsl(217 33% 20%)",
                    borderRadius: 8,
                    fontSize: 12,
                  }}
                />
                <Area type="monotone" dataKey="requests" name="请求量" stroke="hsl(142 71% 45%)" fill="url(#fillRequests)" strokeWidth={2} />
                <Area type="monotone" dataKey="failed" name="失败" stroke="hsl(0 72% 51%)" fill="url(#fillFailed)" strokeWidth={2} />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </CardContent>
      </Card>

      {/* 分布图 */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Tool 调用分布</CardTitle>
            <CardDescription>按 Tool 统计调用次数</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="h-[300px] w-full">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={toolDist} layout="vertical" margin={{ top: 0, right: 16, left: 8, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="hsl(217 33% 20%)" horizontal={false} />
                  <XAxis type="number" tick={{ fontSize: 12, fill: "hsl(215 20% 65%)" }} stroke="hsl(217 33% 20%)" />
                  <YAxis
                    type="category"
                    dataKey="name"
                    width={190}
                    tick={{ fontSize: 11, fill: "hsl(215 20% 65%)" }}
                    stroke="hsl(217 33% 20%)"
                  />
                  <RechartsTooltip
                    contentStyle={{
                      background: "hsl(217 33% 13%)",
                      border: "1px solid hsl(217 33% 20%)",
                      borderRadius: 8,
                      fontSize: 12,
                    }}
                  />
                  <Bar dataKey="value" name="调用次数" fill="hsl(142 71% 45%)" radius={[0, 4, 4, 0]} barSize={16} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>调用类型分布</CardTitle>
            <CardDescription>本地反射 vs 外部 HTTP 转发</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="flex h-[300px] items-center">
              <div className="h-full flex-1">
                <ResponsiveContainer width="100%" height="100%">
                  <PieChart>
                    <Pie
                      data={callTypeDist}
                      dataKey="value"
                      nameKey="name"
                      innerRadius={60}
                      outerRadius={90}
                      paddingAngle={4}
                    >
                      {callTypeDist.map((_, i) => (
                        <Cell key={i} fill={PIE_COLORS[i % PIE_COLORS.length]} />
                      ))}
                    </Pie>
                    <RechartsTooltip
                      contentStyle={{
                        background: "hsl(217 33% 13%)",
                        border: "1px solid hsl(217 33% 20%)",
                        borderRadius: 8,
                        fontSize: 12,
                      }}
                    />
                    <Legend verticalAlign="bottom" iconType="circle" formatter={(v) => <span style={{ fontSize: 12 }}>{v}</span>} />
                  </PieChart>
                </ResponsiveContainer>
              </div>
              <div className="space-y-3 pr-2 text-sm">
                <div className="flex items-center gap-2">
                  <CheckCircle2 className="h-4 w-4 text-emerald-400" />
                  <span className="text-muted-foreground">成功</span>
                  <span className="ml-auto font-mono text-foreground">{stats.successCount.toLocaleString()}</span>
                </div>
                <div className="flex items-center gap-2">
                  <XCircle className="h-4 w-4 text-destructive" />
                  <span className="text-muted-foreground">失败</span>
                  <span className="ml-auto font-mono text-foreground">{stats.failCount.toLocaleString()}</span>
                </div>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
