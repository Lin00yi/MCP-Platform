package com.mcp.transfer.shared.config.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * SSRF 防护：外部 HTTP 调用前校验目标地址，默认禁止访问内网/本机/链路本地等地址。
 * 配置 mcp.security.allow-private-network=true 可放开（内部网关下游本身就是内网服务时）。
 */
@Slf4j
@Component
public class UrlSecurityValidator {

    @Value("${mcp.security.allow-private-network:false}")
    private boolean allowPrivateNetwork;

    /** 校验目标 URL，命中内网/本机等地址时抛 SecurityException */
    public void validate(String url) {
        if (allowPrivateNetwork || url == null || url.isEmpty()) {
            return;
        }
        String host;
        try {
            URI uri = new URI(url);
            host = uri.getHost();
        } catch (URISyntaxException e) {
            throw new SecurityException("目标地址非法：" + url);
        }
        if (host == null || host.isEmpty()) {
            return;
        }
        // IPv6 字面量可能带方括号
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (isBlocked(addr)) {
                    log.warn("[SSRF] 拦截内网地址访问: {} -> {}", url, addr.getHostAddress());
                    throw new SecurityException("目标地址不允许访问内网/本机：" + host);
                }
            }
        } catch (UnknownHostException e) {
            throw new SecurityException("目标地址无法解析：" + host);
        }
    }

    private boolean isBlocked(InetAddress addr) {
        if (addr.isLoopbackAddress()        // 127.0.0.0/8 / ::1
                || addr.isLinkLocalAddress() // 169.254.0.0/16（含云元数据）/ fe80::/10
                || addr.isSiteLocalAddress() // 10/8、172.16/12、192.168/16
                || addr.isAnyLocalAddress()  // 0.0.0.0 / ::
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = addr.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xff;
            int b1 = bytes[1] & 0xff;
            // 0.0.0.0/8
            if (b0 == 0) {
                return true;
            }
            // 100.64.0.0/10（CGNAT）
            if (b0 == 100 && b1 >= 64 && b1 <= 127) {
                return true;
            }
            // 192.0.0.0/24、192.0.2.0/24
            if (b0 == 192 && b1 == 0 && ((bytes[2] & 0xff) == 0 || (bytes[2] & 0xff) == 2)) {
                return true;
            }
            // 198.18.0.0/15
            if (b0 == 198 && (b1 == 18 || b1 == 19)) {
                return true;
            }
            // 198.51.100.0/24
            if (b0 == 198 && b1 == 51 && (bytes[2] & 0xff) == 100) {
                return true;
            }
            // 203.0.113.0/24
            if (b0 == 203 && b1 == 0 && (bytes[2] & 0xff) == 113) {
                return true;
            }
            // 255.255.255.255
            if (b0 == 255 && b1 == 255 && (bytes[2] & 0xff) == 255 && (bytes[3] & 0xff) == 255) {
                return true;
            }
        } else if (bytes.length == 16) {
            int b0 = bytes[0] & 0xff;
            // IPv6 唯一本地地址 fc00::/7
            if ((b0 & 0xfe) == 0xfc) {
                return true;
            }
            // IPv4-mapped IPv6 ::ffff:x.x.x.x
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff) {
                try {
                    return isBlocked(InetAddress.getByAddress(
                            new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]}));
                } catch (UnknownHostException ignore) {
                    return true;
                }
            }
        }
        return false;
    }
}
