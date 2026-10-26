import { useMemo, useState, type ReactNode } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import {
  ArrowRight,
  Braces,
  FileText,
  Link2,
  Search,
  Server as ServerIcon,
  ShieldCheck,
} from "lucide-react";
import { api } from "@/lib/api";
import { cn } from "@/lib/utils";
import { statusLabel } from "@/lib/format";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetBody,
  SheetTitle,
  SheetDescription,
} from "@/components/ui/sheet";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { LoadingState, EmptyState, ErrorState } from "@/components/PageState";

/* ============================ 类型 & Tool ============================ */

type AssetKind = "tool" | "prompt" | "resource";

const KIND_META: Record<AssetKind, { label: string; icon: typeof Braces }> = {
  tool: { label: "Tool", icon: Braces },
  prompt: { label: "Prompt", icon: FileText },
  resource: { label: "Resource", icon: Link2 },
};

function upstreamLabel(t?: string) {
  return t === "MCP" ? "代理" : t === "AGGREGATE" ? "聚合" : "本地/REST";
}

interface SheetItem {
  id: number;
  name: string;
  sub?: string;
  desc?: string;
  badge?: string;
  version?: string;
}

/** 区块前的模块提示（后续新增其他资产模块时，直接复用它） */
function ModuleSection({ icon, title, count, hint }: { icon: ReactNode; title: string; count: number; hint?: string }) {
  return (
    <div className="flex flex-wrap items-center gap-2 border-b pb-2">
      <span className="text-muted-foreground">{icon}</span>
      <span className="text-sm font-semibold">{title}</span>
      <Badge variant="outline" className="text-[10px]">模块</Badge>
      <span className="text-xs text-muted-foreground">共 {count} 个</span>
      {hint && <span className="text-xs text-muted-foreground">· {hint}</span>}
    </div>
  );
}

/** 一级卡片上的可点击计数 */
function CountButton({
  icon: Icon,
  label,
  count,
  onClick,
}: {
  icon: typeof Braces;
  label: string;
  count: number;
  onClick: () => void;
}) {
  return (
    <button
      onClick={onClick}
      disabled={count === 0}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-xs transition-colors",
        count === 0
          ? "cursor-default text-muted-foreground/60"
          : "cursor-pointer hover:border-primary/40 hover:bg-accent",
      )}
      title={count === 0 ? `暂无 ${label}` : `查看该 Server 下的 ${label}`}
    >
      <Icon className="h-3.5 w-3.5 text-muted-foreground" />
      <span className="text-muted-foreground">{label}</span>
      <span className="font-medium">{count}</span>
    </button>
  );
}

/* ============================ 页面 ============================ */

export function CatalogPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const tab = params.get("tab") === "servers" ? "servers" : "discover";

  const [keyword, setKeyword] = useState("");
  const [serverKeyword, setServerKeyword] = useState("");
  const [sheet, setSheet] = useState<{ serverId: number; serverName: string; kind: AssetKind } | null>(null);
  const [sheetKeyword, setSheetKeyword] = useState("");

  /* ---------- 数据源：两个 Tab 统一走 discovery 接口（与客户端发现一致） ---------- */
  const {
    data: dServers,
    isLoading,
    isError,
    error,
    refetch,
  } = useQuery({
    queryKey: ["disc-servers"],
    queryFn: ({ signal }) => api.adminDiscoveryServers(signal),
  });
  const { data: dTools } = useQuery({
    queryKey: ["disc-tools"],
    queryFn: ({ signal }) => api.adminDiscoveryTools(undefined, signal),
  });
  const { data: dPrompts } = useQuery({
    queryKey: ["disc-prompts"],
    queryFn: ({ signal }) => api.adminDiscoveryPrompts(undefined, signal),
  });
  const { data: dResources } = useQuery({
    queryKey: ["disc-resources"],
    queryFn: ({ signal }) => api.adminDiscoveryResources(undefined, signal),
  });
  const { data: tenants } = useQuery({
    queryKey: ["tenants"],
    queryFn: ({ signal }) => api.listTenants(signal),
  });

  const tenantNameOf = (id?: number) =>
    (tenants ?? []).find((t) => t.id === id)?.name ?? (id ? `租户#${id}` : "—");

  /** Server → 二级资产（全部来自 discovery） */
  const tree = useMemo(
    () =>
      (dServers ?? []).map((s) => ({
        ...s,
        tools: (dTools ?? []).filter((t) => t.server.id === s.id),
        prompts: (dPrompts ?? []).filter((p) => p.server.id === s.id),
        resources: (dResources ?? []).filter((r) => r.server.id === s.id),
      })),
    [dServers, dTools, dPrompts, dResources],
  );

  const filterBy = (word: string) => {
    const kw = word.trim().toLowerCase();
    if (!kw) return tree;
    return tree.filter((s) => {
      const own = [s.name, s.path, tenantNameOf(s.tenantId), s.upstreamType].filter(Boolean).join(" ").toLowerCase();
      if (own.includes(kw)) return true;
      const nested = [
        ...s.tools.map((t) => `${t.code} ${t.name} ${t.tags ?? ""}`),
        ...s.prompts.map((p) => `${p.name} ${p.description ?? ""}`),
        ...s.resources.map((r) => `${r.name} ${r.uri}`),
      ].join(" ").toLowerCase();
      return nested.includes(kw);
    });
  };

  const discoverList = useMemo(() => filterBy(keyword), [tree, keyword, tenants]);
  const serversList = useMemo(() => filterBy(serverKeyword), [tree, serverKeyword, tenants]);

  /* ---------- Sheet：当前 server + kind 的列表（来自 discovery，天然吻合） ---------- */
  const sheetItems = useMemo<SheetItem[]>(() => {
    if (!sheet) return [];
    if (sheet.kind === "tool") {
      return (dTools ?? [])
        .filter((t) => t.server.id === sheet.serverId)
        .map((t) => ({ id: t.id, name: t.code, sub: t.name, desc: t.description, badge: t.type === 0 ? "本地执行" : "HTTP 转发", version: t.version }));
    }
    if (sheet.kind === "prompt") {
      return (dPrompts ?? [])
        .filter((p) => p.server.id === sheet.serverId)
        .map((p) => ({ id: p.id, name: p.name, desc: p.description, version: p.version }));
    }
    return (dResources ?? [])
      .filter((r) => r.server.id === sheet.serverId)
      .map((r) => ({ id: r.id, name: r.name, sub: r.uri, desc: r.description, version: r.version }));
  }, [sheet, dTools, dPrompts, dResources]);

  const sheetFiltered = useMemo(() => {
    const kw = sheetKeyword.trim().toLowerCase();
    if (!kw) return sheetItems;
    return sheetItems.filter((it) => [it.name, it.sub, it.desc, it.badge].filter(Boolean).join(" ").toLowerCase().includes(kw));
  }, [sheetItems, sheetKeyword]);

  const setTab = (v: string) => {
    const next = new URLSearchParams();
    next.set("tab", v);
    setParams(next, { replace: true });
  };
  const openSheet = (serverId: number, serverName: string, kind: AssetKind) => {
    setSheetKeyword("");
    setSheet({ serverId, serverName, kind });
  };

  /** 统一卡片渲染（两个 Tab 共用） */
  const renderServers = (list: typeof tree, word: string, setWord: (v: string) => void) => (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative min-w-[240px] flex-1">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={word}
            onChange={(e) => setWord(e.target.value)}
            placeholder="搜索 Server / Tool / Prompt / Resource…"
            className="pl-9"
          />
        </div>
      </div>

      <ModuleSection
        icon={<ServerIcon className="h-4 w-4" />}
        title="MCP Server"
        count={list.length}
        hint="全部启用资产"
      />

      {list.length === 0 ? (
        <EmptyState icon={ServerIcon} title="未找到匹配项" description="调整搜索关键词" />
      ) : (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
          {list.map((s) => (
            <Card key={s.id} className="flex flex-col">
              <CardHeader className="pb-2">
                <div className="flex items-start justify-between gap-2">
                  <div className="flex min-w-0 items-center gap-3">
                    <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-sky-400/10 text-sky-400">
                      <ServerIcon className="h-5 w-5" />
                    </div>
                    <div className="min-w-0">
                      <CardTitle className="truncate text-base">{s.name}</CardTitle>
                      <CardDescription className="truncate font-mono text-xs">{s.path}</CardDescription>
                    </div>
                  </div>
                  <Badge variant={s.status === 1 ? "success" : "muted"}>{statusLabel(s.status)}</Badge>
                </div>
              </CardHeader>
              <CardContent className="flex flex-1 flex-col gap-3">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge variant="secondary" className="text-[11px]">{upstreamLabel(s.upstreamType)}</Badge>
                  <span className="text-xs text-muted-foreground">{tenantNameOf(s.tenantId)}</span>
                </div>

                <div className="flex flex-wrap gap-1.5">
                  <CountButton icon={Braces} label="Tool" count={s.tools.length} onClick={() => openSheet(s.id, s.name, "tool")} />
                  <CountButton icon={FileText} label="Prompt" count={s.prompts.length} onClick={() => openSheet(s.id, s.name, "prompt")} />
                  <CountButton icon={Link2} label="Resource" count={s.resources.length} onClick={() => openSheet(s.id, s.name, "resource")} />
                </div>

                <div className="mt-auto flex items-center justify-between border-t pt-3">
                  <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
                    <ShieldCheck className="h-3.5 w-3.5" /> MCP 端点
                  </span>
                  <Button variant="ghost" size="sm" onClick={() => navigate(`/servers/${s.id}`)}>
                    进入 Server <ArrowRight className="h-3.5 w-3.5" />
                  </Button>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );

  if (isLoading) return <LoadingState label="加载资产目录…" />;
  if (isError) return <ErrorState message={(error as Error)?.message} onRetry={() => void refetch()} />;

  return (
    <>
      <Tabs value={tab} onValueChange={setTab} className="space-y-4">
        <TabsList>
          <TabsTrigger value="discover">发现</TabsTrigger>
          <TabsTrigger value="servers">MCP Servers</TabsTrigger>
        </TabsList>

        <TabsContent value="discover">
          {renderServers(discoverList, keyword, setKeyword)}
        </TabsContent>
        <TabsContent value="servers">{renderServers(serversList, serverKeyword, setServerKeyword)}</TabsContent>
      </Tabs>

      {/* ================= 右侧 Sheet：某 Server 的 Tool / Prompt / Resource 列表 ================= */}
      <Sheet open={!!sheet} onOpenChange={(o) => !o && setSheet(null)}>
        <SheetContent side="right" className="w-full sm:max-w-lg">
          <SheetHeader>
            <SheetTitle>
              {sheet?.serverName} · {sheet ? KIND_META[sheet.kind].label : ""}（{sheetItems.length}）
            </SheetTitle>
            <SheetDescription>
              {"该 Server 下全部启用资产"}
            </SheetDescription>
          </SheetHeader>

          {/* 搜索框放在滚动区之外，列表滚动时始终固定在顶部 */}
          <div className="shrink-0 px-6">
            <div className="relative">
              <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                value={sheetKeyword}
                onChange={(e) => setSheetKeyword(e.target.value)}
                placeholder="在列表内搜索…"
                className="pl-9"
              />
            </div>
          </div>

          <SheetBody className="space-y-2">
            {sheetFiltered.length === 0 ? (
              <p className="py-8 text-center text-sm text-muted-foreground">
                {sheetItems.length === 0 ? "暂无数据" : "没有匹配项"}
              </p>
            ) : (
              <div className="space-y-1">
                {sheetFiltered.map((it) => (
                  <div key={it.id} className="rounded-lg border px-3 py-2 hover:bg-accent/50">
                    <div className="flex items-center gap-2">
                      <span className="truncate font-mono text-sm">{it.name}</span>
                      {it.badge && <Badge variant="outline" className="shrink-0 text-[10px]">{it.badge}</Badge>}
                      {it.version && <span className="shrink-0 text-[10px] text-muted-foreground">v{it.version}</span>}
                    </div>
                    {it.sub && <div className="truncate text-xs text-muted-foreground">{it.sub}</div>}
                    {it.desc && <div className="truncate text-xs text-muted-foreground">{it.desc}</div>}
                  </div>
                ))}
              </div>
            )}
          </SheetBody>
        </SheetContent>
      </Sheet>
    </>
  );
}
