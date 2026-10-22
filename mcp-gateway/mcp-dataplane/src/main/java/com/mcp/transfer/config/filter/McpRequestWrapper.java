package com.mcp.transfer.config.filter;

import lombok.extern.slf4j.Slf4j;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * HTTP 请求体包装类：支持重复读取，并限制最大请求体大小，避免大 body 打爆堆内存。
 */
@Slf4j
public class McpRequestWrapper extends HttpServletRequestWrapper {

    /** 默认最大请求体 2MB（MCP JSON-RPC 足够；可通过系统属性覆盖） */
    private static final int MAX_BODY_BYTES = Integer.getInteger("mcp.request.max-body-bytes", 2 * 1024 * 1024);

    private final byte[] body;

    public McpRequestWrapper(HttpServletRequest request) throws IOException {
        super(request);
        this.body = readInputStreamToBytes(request.getInputStream());
    }

    private byte[] readInputStreamToBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8 * 1024];
        int len;
        int total = 0;
        while ((len = inputStream.read(buffer)) != -1) {
            total += len;
            if (total > MAX_BODY_BYTES) {
                throw new PayloadTooLargeException("请求体超过上限 " + MAX_BODY_BYTES + " 字节");
            }
            outputStream.write(buffer, 0, len);
        }
        byte[] bytes = outputStream.toByteArray();
        outputStream.close();
        inputStream.close();
        return bytes;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream inputStream = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return inputStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                // 同步 Servlet 无需实现
            }

            @Override
            public int read() throws IOException {
                return inputStream.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    /** 获取请求体字符串 */
    public String getRequestBody() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** 请求体过大异常：由 Filter 转成 413 */
    public static class PayloadTooLargeException extends IOException {
        public PayloadTooLargeException(String message) {
            super(message);
        }
    }
}
