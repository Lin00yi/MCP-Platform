package com.mcp.transfer.shared.utils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * IP工具类（JDK1.8兼容）
 * 专注处理本机IP获取、IP校验等逻辑，便于全局复用
 */
public class IpUtils {

    /**
     * 取客户端真实 IP。
     *
     * <p>只信任「反向代理覆写」的单值头 {@code X-Real-IP}（必须是合法 IP 字面量），
     * 否则回退到 socket 地址 {@code getRemoteAddr()}。
     *
     * <p><b>不再使用 {@code X-Forwarded-For} 首段</b>：客户端可自行伪造该值，代理通常只是追加，
     * 用它做登录限流分桶可被稳定绕过（见安全审查）。dev compose 直接暴露控制面端口时同理。
     */
    public static String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String realIp = request.getHeader("X-Real-IP");
        if (isValidIpLiteral(realIp)) {
            return realIp.trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote.trim();
    }

    /**
     * 判断字符串是否为合法 IP 字面量（IPv4 点分 / IPv6 冒号形式），不接受主机名，避免 DNS 解析。
     */
    public static boolean isValidIpLiteral(String value) {
        if (value == null) {
            return false;
        }
        String ip = value.trim();
        if (ip.isEmpty() || ip.length() > 64) {
            return false;
        }
        // 去掉 IPv6 可能的方括号与 zone id
        if (ip.startsWith("[")) {
            int end = ip.indexOf(']');
            if (end < 0) {
                return false;
            }
            ip = ip.substring(1, end);
        }
        int zone = ip.indexOf('%');
        if (zone > 0) {
            ip = ip.substring(0, zone);
        }
        if (ip.indexOf(':') >= 0) {
            return isValidIpv6(ip);
        }
        return isValidIpv4(ip);
    }

    private static boolean isValidIpv6(String ip) {
        if (ip.length() > 45 || !ip.matches("[0-9a-fA-F:.]+")) {
            return false;
        }
        // 至少包含一个冒号；用 JDK 解析但输入已限定为十六进制/冒号，不存在 DNS 解析风险
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return addr instanceof java.net.Inet6Address;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取本机有效IPv4地址（非回环地址、非虚拟网卡）
     * @return 本机对外可访问的IP，兜底返回localhost
     */
    public static String getLocalIpAddress() {
        try {
            // 遍历所有网络接口
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface ni = networkInterfaces.nextElement();
                // 过滤虚拟网卡、禁用的网卡
                if (ni.isLoopback() || ni.isVirtual() || !ni.isUp()) {
                    continue;
                }
                // 遍历当前网卡下的所有IP
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    // 仅返回IPv4、非回环地址
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
            // 兜底：无有效IP时返回localhost
            return "localhost";
        } catch (Exception e) {
            // 异常时返回localhost，避免启动失败
            return "localhost";
        }
    }

    /**
     * 校验IP是否为有效IPv4地址
     * @param ip 待校验IP
     * @return true=有效，false=无效
     */
    public static boolean isValidIpv4(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            try {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }
}