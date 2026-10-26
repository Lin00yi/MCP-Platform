import { CheckCircle2, XCircle } from "lucide-react";
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
import type { CallTypeDistItem, ToolDistItem, TrendPoint } from "@/types";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

/**
 * 概览页图表区块。
 *
 * 单独成文件并被 DashboardPage 以 React.lazy 加载：recharts 及 d3 依赖体积较大，
 * 首屏先渲染指标卡，图表 chunk 在数据返回后再按需下载，避免阻塞登录后的首屏。
 */
const PIE_COLORS = ["hsl(142 71% 45%)", "hsl(199 89% 48%)", "hsl(262 83% 58%)", "hsl(45 93% 47%)", "hsl(0 72% 51%)"];

const TOOLTIP_STYLE = {
  background: "hsl(217 33% 13%)",
  border: "1px solid hsl(217 33% 20%)",
  borderRadius: 8,
  fontSize: 12,
} as const;

export function DashboardCharts({
  trend,
  toolDist,
  callTypeDist,
  successCount,
  failCount,
}: {
  trend: TrendPoint[];
  toolDist: ToolDistItem[];
  callTypeDist: CallTypeDistItem[];
  successCount: number;
  failCount: number;
}) {
  return (
    <>
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
                <RechartsTooltip contentStyle={TOOLTIP_STYLE} />
                <Area
                  type="monotone"
                  dataKey="requests"
                  name="请求量"
                  stroke="hsl(142 71% 45%)"
                  fill="url(#fillRequests)"
                  strokeWidth={2}
                />
                <Area
                  type="monotone"
                  dataKey="failed"
                  name="失败"
                  stroke="hsl(0 72% 51%)"
                  fill="url(#fillFailed)"
                  strokeWidth={2}
                />
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
                  <RechartsTooltip contentStyle={TOOLTIP_STYLE} />
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
                    <RechartsTooltip contentStyle={TOOLTIP_STYLE} />
                    <Legend
                      verticalAlign="bottom"
                      iconType="circle"
                      formatter={(v) => <span style={{ fontSize: 12 }}>{v}</span>}
                    />
                  </PieChart>
                </ResponsiveContainer>
              </div>
              <div className="space-y-3 pr-2 text-sm">
                <div className="flex items-center gap-2">
                  <CheckCircle2 className="h-4 w-4 text-emerald-400" />
                  <span className="text-muted-foreground">成功</span>
                  <span className="ml-auto font-mono text-foreground">{successCount.toLocaleString()}</span>
                </div>
                <div className="flex items-center gap-2">
                  <XCircle className="h-4 w-4 text-destructive" />
                  <span className="text-muted-foreground">失败</span>
                  <span className="ml-auto font-mono text-foreground">{failCount.toLocaleString()}</span>
                </div>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>
    </>
  );
}
