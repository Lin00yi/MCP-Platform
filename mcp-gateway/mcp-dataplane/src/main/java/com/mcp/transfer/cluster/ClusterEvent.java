package com.mcp.transfer.cluster;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.Data;

/**
 * 集群事件：控制面写入资产后的「缓存失效 + SSE 广播」载荷。
 *
 * <p>可在进程内直接传递，也可序列化后经 Redis Pub/Sub 广播到所有数据面副本。
 */
@Data
public class ClusterEvent {

    public enum Type {
        /** 工具变更 */
        TOOL,
        /** Prompt 变更 */
        PROMPT,
        /** Resource 变更 */
        RESOURCE,
        /** Server 路由变更（path 新增/删除/改名） */
        ROUTES,
        /** 无法定位到具体 Server 的全局变更：清空所有缓存 + 刷路由 */
        ALL
    }

    private Type type;

    /** 关联的 Server；ROUTES 事件可为 null */
    private Integer serverId;

    /** 关联的工具；为空表示整 server 粒度 */
    private Integer toolId;

    /** 工具级操作：UPSERT / DELETE / PURGE_SERVER；为空表示整体重建 */
    private String op;

    /** 事件产生时间，便于排查乱序 */
    private long at;

    public static ClusterEvent of(Type type, Integer serverId) {
        ClusterEvent event = new ClusterEvent();
        event.setType(type);
        event.setServerId(serverId);
        event.setAt(System.currentTimeMillis());
        return event;
    }

    public static ClusterEvent tool(Integer serverId, Integer toolId, String op) {
        ClusterEvent event = of(Type.TOOL, serverId);
        event.setToolId(toolId);
        event.setOp(op);
        return event;
    }

    public String toJson() {
        JSONObject json = new JSONObject();
        json.put("type", type == null ? null : type.name());
        json.put("serverId", serverId);
        json.put("toolId", toolId);
        json.put("op", op);
        json.put("at", at);
        return json.toJSONString();
    }

    public static ClusterEvent fromJson(String body) {
        JSONObject json = JSON.parseObject(body);
        if (json == null) {
            return null;
        }
        ClusterEvent event = new ClusterEvent();
        String type = json.getString("type");
        if (type == null) {
            return null;
        }
        event.setType(Type.valueOf(type));
        event.setServerId(json.getInteger("serverId"));
        event.setToolId(json.getInteger("toolId"));
        event.setOp(json.getString("op"));
        Long at = json.getLong("at");
        event.setAt(at == null ? 0L : at);
        return event;
    }
}
