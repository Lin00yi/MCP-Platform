package com.mcp.transfer.upstream;

import com.alibaba.fastjson.JSON;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 聚合代理的上游定义（{@code mcp_server.upstream_url} 的 JSON 数组元素）。
 *
 * <p>基础字段只有 name/url；身份下传相关字段可按上游覆盖：
 * <pre>
 * [
 *   {"name":"crm","url":"http://crm:8080/mcp","audience":"crm","identityPublicKey":"-----BEGIN PUBLIC KEY-----...","identityMode":"REQUIRED"},
 *   {"name":"erp","url":"http://erp:8080/mcp"}
 * ]
 * </pre>
 * 未填写的字段回退到聚合 Server 自身的 {@code audience / identity_public_key / identity_mode}。
 */
@Data
public class UpstreamDef {

    private String name;
    private String url;

    /** 下游 audience；空则用聚合 Server 的 audience，最后回退 server-{id} */
    private String audience;

    /** 下游 JWE 公钥（JWS 模式可空）；空则用聚合 Server 的 identity_public_key */
    private String identityPublicKey;

    /** 该上游的身份模式覆盖：OFF / OPTIONAL / REQUIRED；空或 INHERIT 表示沿用聚合 Server */
    private String identityMode;

    /** 解析 JSON 数组，过滤缺少 name/url 的项；非法 JSON 返回空列表 */
    public static List<UpstreamDef> parseList(String upstreamUrl) {
        List<UpstreamDef> list = new ArrayList<>();
        if (!StringUtils.hasText(upstreamUrl)) {
            return list;
        }
        try {
            List<UpstreamDef> parsed = JSON.parseArray(upstreamUrl, UpstreamDef.class);
            if (parsed == null) {
                return list;
            }
            for (UpstreamDef u : parsed) {
                if (u != null && StringUtils.hasText(u.getName()) && StringUtils.hasText(u.getUrl())) {
                    list.add(u);
                }
            }
        } catch (Exception ignore) {
            // 由调用方决定如何提示
        }
        return list;
    }
}
