package com.mcp.transfer.health;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

/**
 * 一次探测的快照：结果 + 探测时间。
 *
 * <p>可序列化后放进 Redis，让所有副本读到同一份探测结果
 * （避免每个副本各自打上游）。
 */
public class ProbeSnapshot {

    public ProbeResult result;
    public long checkedAt;

    public ProbeSnapshot() {
    }

    public ProbeSnapshot(ProbeResult result, long checkedAt) {
        this.result = result;
        this.checkedAt = checkedAt;
    }

    public String toJson() {
        JSONObject json = new JSONObject();
        JSONObject r = new JSONObject();
        if (result != null) {
            r.put("ok", result.ok);
            r.put("latencyMs", result.latencyMs);
            r.put("message", result.message);
        }
        json.put("result", r);
        json.put("checkedAt", checkedAt);
        return json.toJSONString();
    }

    public static ProbeSnapshot fromJson(String body) {
        JSONObject json = JSON.parseObject(body);
        if (json == null) {
            return null;
        }
        ProbeSnapshot snapshot = new ProbeSnapshot();
        JSONObject r = json.getJSONObject("result");
        if (r != null) {
            snapshot.result = new ProbeResult(
                    Boolean.TRUE.equals(r.getBoolean("ok")),
                    r.getLongValue("latencyMs"),
                    r.getString("message"));
        }
        Long at = json.getLong("checkedAt");
        snapshot.checkedAt = at == null ? 0L : at;
        return snapshot;
    }
}
