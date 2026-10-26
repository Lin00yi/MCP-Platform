package com.mcp.transfer.gateway.tools;

import com.alibaba.fastjson.JSONObject;
import com.mcp.transfer.shared.dto.mcp.req.McpRequest;
import com.mcp.transfer.shared.dto.mcp.result.data.McpResData;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 本地反射执行器示例工具（type=0）。
 * 每个方法只接收一个 McpRequest 参数，serviceUrl 写成「LocalToolsService.方法名」即可被反射调用。
 */
@Service
public class LocalToolsService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 回显入参：验证参数透传链路 */
    public McpResData echo(McpRequest mcpRequest) {
        JSONObject args = mcpRequest.getParams() != null ? mcpRequest.getParams().getArguments() : null;
        return text("echo 回显：" + (args != null ? args.toJSONString() : "(无参数)"));
    }

    /** 返回网关服务器当前时间 */
    public McpResData currentTime(McpRequest mcpRequest) {
        return text("网关服务器当前时间：" + LocalDateTime.now().format(FMT));
    }

    /** 两数相加：入参 {a, b} */
    public McpResData add(McpRequest mcpRequest) {
        JSONObject args = mcpRequest.getParams() != null ? mcpRequest.getParams().getArguments() : null;
        if (args == null || !args.containsKey("a") || !args.containsKey("b")) {
            return text("参数缺失：需要 a 和 b 两个数字");
        }
        try {
            double a = args.getDoubleValue("a");
            double b = args.getDoubleValue("b");
            return text(a + " + " + b + " = " + (a + b));
        } catch (Exception e) {
            return text("计算失败：a 和 b 必须是数字");
        }
    }

    /** 生成 UUID */
    public McpResData uuid(McpRequest mcpRequest) {
        return text("UUID：" + UUID.randomUUID().toString());
    }

    /** 简单加密摘要：入参 {text}，返回 SHA-256 前 16 位 */
    public McpResData sha256(McpRequest mcpRequest) {
        JSONObject args = mcpRequest.getParams() != null ? mcpRequest.getParams().getArguments() : null;
        String text = args != null ? args.getString("text") : null;
        if (text == null || text.isEmpty()) {
            return text("参数缺失：需要 text");
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return text("SHA-256(" + text + ") = " + sb.substring(0, 16) + "...");
        } catch (Exception e) {
            return text("摘要计算失败：" + e.getMessage());
        }
    }

    private McpResData text(String text) {
        McpResData resData = new McpResData();
        List<JSONObject> content = new ArrayList<>();
        JSONObject item = new JSONObject();
        item.put("type", "text");
        item.put("text", text);
        content.add(item);
        resData.setContent(content);
        return resData;
    }
}
