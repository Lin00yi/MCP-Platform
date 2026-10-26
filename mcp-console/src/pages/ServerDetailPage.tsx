import { useMemo } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { ArrowLeft } from "lucide-react";
import { api } from "@/lib/api";
import { statusLabel } from "@/lib/format";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { LoadingState, ErrorState } from "@/components/PageState";
import { ServerToolsTab } from "./ServerToolsTab";
import { ServerPromptsTab } from "./ServerPromptsTab";
import { ServerResourcesTab } from "./ServerResourcesTab";
import { ServerSettingsTab } from "./ServerSettingsTab";

export function ServerDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const serverId = Number(id);

  const { data: servers, isLoading, isError, error, refetch } = useQuery({
    queryKey: ["servers"],
    queryFn: ({ signal }) => api.listServers(signal),
  });
  const server = useMemo(() => servers?.find((s) => s.id === serverId), [servers, serverId]);

  if (isLoading) return <LoadingState label="加载 Server…" />;
  if (isError) return <ErrorState message={(error as Error)?.message} onRetry={() => void refetch()} />;
  if (!server) return <ErrorState message={`Server #${serverId} 不存在`} />;

  return (
    <div className="space-y-4">
      {/* 头部：返回 + 名称 + 路径 + 状态 */}
      <div className="flex flex-wrap items-center gap-3">
        <Button variant="ghost" size="icon" onClick={() => navigate("/servers")} title="返回列表">
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <div className="flex min-w-0 flex-1 items-center gap-2">
          <h2 className="truncate text-lg font-semibold">{server.name}</h2>
          <Badge variant={server.status === 1 ? "success" : "muted"}>{statusLabel(server.status)}</Badge>
        </div>
        <code className="rounded bg-muted px-2 py-1 font-mono text-xs text-muted-foreground">{server.path}</code>
      </div>

      <Tabs defaultValue="tools">
        <TabsList>
          <TabsTrigger value="tools">Tool</TabsTrigger>
          <TabsTrigger value="prompts">Prompt</TabsTrigger>
          <TabsTrigger value="resources">Resource</TabsTrigger>
          <TabsTrigger value="settings">设置</TabsTrigger>
        </TabsList>
        <TabsContent value="tools" className="mt-4">
          <ServerToolsTab serverId={serverId} />
        </TabsContent>
        <TabsContent value="prompts" className="mt-4">
          <ServerPromptsTab serverId={serverId} />
        </TabsContent>
        <TabsContent value="resources" className="mt-4">
          <ServerResourcesTab serverId={serverId} />
        </TabsContent>
        <TabsContent value="settings" className="mt-4">
          <ServerSettingsTab server={server} />
        </TabsContent>
      </Tabs>
    </div>
  );
}
