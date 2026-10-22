package com.mcp.transfer.utils;

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
     * 取客户端真实 IP（兼容常见反向代理头）。
     * 注意：X-Forwarded-For 可被伪造，仅在可信代理后使用；这里仅用于登录限流分桶。
     */
    public static String clientIp(javax.servlet.http.HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.trim().isEmpty()) {
            int idx = xff.indexOf(',');
            return (idx > 0 ? xff.substring(0, idx) : xff).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.trim().isEmpty()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
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