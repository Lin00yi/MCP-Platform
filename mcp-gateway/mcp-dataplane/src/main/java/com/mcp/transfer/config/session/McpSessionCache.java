package com.mcp.transfer.config.session;

import com.mcp.transfer.identity.ResolvedIdentity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * MCP Session 门面：生成/校验/绑定身份。
 *
 * <p>真正的存储在 {@link SessionStore}：
 * 单副本部署用进程内 Map，多副本部署由 {@code mcp.redis.enabled=true} 切到 Redis，
 * 本类的方法签名与语义保持不变。
 */
@Slf4j
@Component
public class McpSessionCache {

    /** Session 过期时间：30 分钟 */
    private static final long SESSION_EXPIRE_TIME = 30 * 60 * 1000L;

    @Autowired
    private SessionStore sessionStore;

    /**
     * 生成新的 Session-ID 并落存储；容量控制由存储实现负责。
     */
    public String generateSessionId() {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        long expireTime = System.currentTimeMillis() + SESSION_EXPIRE_TIME;
        sessionStore.save(sessionId, new SessionInfo(expireTime), SESSION_EXPIRE_TIME);
        return sessionId;
    }

    /** 校验 Session-ID 是否有效（存在且未过期），并续期 */
    public boolean validateSessionId(String sessionId) {
        SessionInfo info = getSession(sessionId);
        if (info == null) {
            return false;
        }
        info.setExpiresAt(System.currentTimeMillis() + SESSION_EXPIRE_TIME);
        sessionStore.save(sessionId, info, SESSION_EXPIRE_TIME);
        return true;
    }

    /** 获取有效会话；不存在/已过期返回 null（不续期） */
    public SessionInfo getSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }
        return sessionStore.find(sessionId);
    }

    /** initialize 时记录客户端名称/版本（分页能力判断用） */
    public void bindClient(String sessionId, String clientName, String clientVersion) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        SessionInfo info = getSession(sessionId);
        if (info == null) {
            return;
        }
        info.setClientName(clientName);
        info.setClientVersion(clientVersion);
        sessionStore.save(sessionId, info, SESSION_EXPIRE_TIME);
    }

    /** initialize 完成后把身份绑定到会话 */
    public void bind(String sessionId, ResolvedIdentity identity) {
        if (sessionId == null || identity == null) {
            return;
        }
        SessionInfo info = sessionStore.find(sessionId);
        if (info == null) {
            info = new SessionInfo(System.currentTimeMillis() + SESSION_EXPIRE_TIME);
        }
        info.bind(identity);
        sessionStore.save(sessionId, info, SESSION_EXPIRE_TIME);
    }

    public void removeSessionId(String sessionId) {
        if (sessionId != null) {
            sessionStore.remove(sessionId);
        }
    }

    /** 会话总数；共享存储下为 -1（未知） */
    public int size() {
        return sessionStore.size();
    }
}
