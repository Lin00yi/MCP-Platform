package com.mcp.transfer.exposure;

import com.mcp.transfer.shared.entity.McpTool;
import com.mcp.transfer.shared.entity.ToolEmbedding;
import com.mcp.transfer.shared.mapper.ToolEmbeddingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolEmbeddingServiceTest {

    @Mock
    private ToolEmbeddingMapper mapper;

    private ToolEmbeddingService service;

    private final EmbeddingProperties properties = new EmbeddingProperties();

    @BeforeEach
    void setUp() {
        service = new ToolEmbeddingService();
        ReflectionTestUtils.setField(service, "toolEmbeddingMapper", mapper);
        ReflectionTestUtils.setField(service, "embeddingProperties", properties);
        ReflectionTestUtils.setField(service, "embeddingClient", new FakeClient());
    }

    @Test
    void indexesToolAndLoadsVector() {
        McpTool tool = new McpTool().setId(1).setServerId(10).setCode("order_query")
                .setName("查询订单").setTags("order").setResJson("{\"description\":\"按订单号查询\"}");

        service.indexTool(tool);

        verify(mapper).upsert(any(ToolEmbedding.class));
        ToolEmbedding row = new ToolEmbedding();
        row.setToolId(1);
        row.setServerId(10);
        row.setVector("[1.0,0.0]");
        when(mapper.selectByServerId(10)).thenReturn(Collections.singletonList(row));

        assertTrue(service.hasIndex(10));
        assertNotNull(service.vector(10, 1));
        assertEquals(2, service.vector(10, 1).length);
    }

    @Test
    void queryVectorAndCosine() {
        float[] q = service.queryVector("订单");
        assertNotNull(q);
        assertEquals(1.0, ToolEmbeddingService.cosine(new float[]{1, 0}, new float[]{1, 0}), 0.0001);
        assertEquals(0.0, ToolEmbeddingService.cosine(new float[]{1, 0}, new float[]{0, 1}), 0.0001);
        assertEquals(0.0, ToolEmbeddingService.cosine(null, new float[]{1}), 0.0001);
    }

    @Test
    void disabledWithoutClient() {
        ReflectionTestUtils.setField(service, "embeddingClient", null);
        assertFalse(service.enabled());
        assertEquals(0L, service.stats().get("indexed"));
    }

    private static class FakeClient implements EmbeddingClient {
        @Override
        public float[] embed(String text) {
            if (text != null && text.contains("订单")) {
                return new float[]{1, 0};
            }
            return new float[]{0, 1};
        }

        @Override
        public boolean enabled() {
            return true;
        }
    }
}
