package com.mcp.transfer.shared.dto.mcp.res;

import com.mcp.transfer.shared.enums.McpErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class McpResponseTest {

    @Test
    void serializesErrorEnumAsJsonRpcErrorObject() {
        McpResponse response = new McpResponse();
        response.setError(McpErrorCode.METHOD_NOT_FOUND);

        Object error = response.getError();
        assertInstanceOf(Map.class, error);
        Map<?, ?> body = (Map<?, ?>) error;
        assertEquals(-32601, body.get("code"));
        assertEquals("Method not found", body.get("message"));
    }
}
