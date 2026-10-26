package com.mcp.transfer.config.filter;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mcp.transfer.shared.enums.McpErrorCode;
import com.mcp.transfer.identity.McpIdentityContext;
import com.mcp.transfer.identity.ResolvedIdentity;
import com.mcp.transfer.identity.UserIdentity;
import com.mcp.transfer.identity.UserTokenProperties;
import com.mcp.transfer.identity.UserTokenUnavailableException;
import com.mcp.transfer.identity.UserTokenVerifier;
import com.mcp.transfer.shared.utils.SecretCrypto;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;

/**
 * 用户 token 认证：只认 {@code x-dsh-auth-token}，校验通过后写入请求属性与
 * {@link McpIdentityContext}。
 *
 * <p>这是数据面、发现、Endpoint 三个入口共用的唯一认证实现；不存在 app JWT 分支。
 */
@Slf4j
@Component
public class UserTokenAuthenticator {

    public static final String USER_ID_ATTR = "mcpUserId";
    public static final String CUSTOMER_ID_ATTR = "mcpCustomerId";
    public static final String IDENTITY_ATTR = "mcpIdentity";

    @Autowired
    private UserTokenVerifier userTokenVerifier;

    @Autowired
    private UserTokenProperties userTokenProperties;

    @Autowired
    private SecretCrypto secretCrypto;

    /**
     * 认证并写入身份上下文。
     *
     * @return 身份；失败时已写入 HTTP/JSON-RPC 错误响应，返回 null
     */
    public ResolvedIdentity authenticate(HttpServletRequest request, HttpServletResponse response) {
        String rawToken = request.getHeader(userTokenProperties.getHeader());
        if (!StringUtils.hasText(rawToken)) {
            writeError(response, 401, McpErrorCode.IDENTITY_REQUIRED,
                    "缺少 " + userTokenProperties.getHeader(), "invalid_request");
            return null;
        }

        UserIdentity user;
        try {
            user = userTokenVerifier.verify(rawToken);
        } catch (UserTokenUnavailableException e) {
            log.error("[USER_TOKEN] 校验服务不可用: {}", e.getMessage());
            writeError(response, 503, McpErrorCode.IDENTITY_UNAVAILABLE,
                    "用户身份校验服务暂不可用", null);
            return null;
        } catch (Exception e) {
            log.error("[USER_TOKEN] 校验异常: {}", e.getMessage());
            writeError(response, 503, McpErrorCode.IDENTITY_UNAVAILABLE,
                    "用户身份校验服务暂不可用", null);
            return null;
        }

        if (user == null || !StringUtils.hasText(user.getUserId())) {
            writeError(response, 401, McpErrorCode.IDENTITY_REQUIRED,
                    userTokenProperties.getHeader() + " 无效或已过期", "invalid_token");
            return null;
        }

        ResolvedIdentity identity = new ResolvedIdentity();
        identity.setUserId(user.getUserId());
        identity.setUserName(user.getUserName());
        identity.setCustomerId(user.getCustomerId());
        identity.setScopes(user.getScopes());
        identity.setUserTokenHash(secretCrypto.sha256Hex(rawToken));
        identity.setTraceId(MDC.get("traceId"));

        request.setAttribute(USER_ID_ATTR, identity.getUserId());
        request.setAttribute(CUSTOMER_ID_ATTR, identity.getCustomerId());
        request.setAttribute(IDENTITY_ATTR, identity);
        McpIdentityContext.set(identity);
        McpIdentityContext.setUserToken(rawToken);

        log.info("[USER_TOKEN] 认证通过 userId={}, tokenType={}, client={}",
                identity.getUserId(), user.getTokenType(), user.getClientLabel());
        return identity;
    }

    public void clear() {
        McpIdentityContext.clear();
    }

    private void writeError(HttpServletResponse response, int httpStatus, McpErrorCode errorCode,
                            String message, String authError) {
        try {
            response.setStatus(httpStatus);
            if (httpStatus == HttpServletResponse.SC_UNAUTHORIZED) {
                String error = StringUtils.hasText(authError) ? authError : "invalid_token";
                response.setHeader("WWW-Authenticate", "Bearer realm=\"mcp\", error=\"" + error + "\"");
            }
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            JSONObject error = new JSONObject();
            error.put("code", errorCode.getCode());
            error.put("message", message);
            JSONObject body = new JSONObject();
            body.put("jsonrpc", "2.0");
            body.put("error", error);
            PrintWriter writer = response.getWriter();
            writer.write(body.toJSONString());
            writer.flush();
            writer.close();
        } catch (Exception e) {
            log.error("[USER_TOKEN] 写认证错误响应失败: {}", e.getMessage());
        }
    }
}
