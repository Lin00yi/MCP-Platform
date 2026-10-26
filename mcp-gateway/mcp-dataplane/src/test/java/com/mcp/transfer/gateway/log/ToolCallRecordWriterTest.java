package com.mcp.transfer.gateway.log;

import com.mcp.transfer.shared.entity.Tenant;
import com.mcp.transfer.shared.entity.ToolCallRecord;
import com.mcp.transfer.shared.mapper.TenantMapper;
import com.mcp.transfer.shared.mapper.ToolCallRecordMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调用记录异步批量写入：不阻塞请求、批量 flush、队列满丢弃、租户名缓存。
 */
@ExtendWith(MockitoExtension.class)
class ToolCallRecordWriterTest {

    @Mock
    private ToolCallRecordMapper toolCallRecordMapper;

    @Mock
    private TenantMapper tenantMapper;

    private ToolCallRecordWriter writer() {
        ToolCallRecordWriter writer = new ToolCallRecordWriter();
        ReflectionTestUtils.setField(writer, "toolCallRecordMapper", toolCallRecordMapper);
        ReflectionTestUtils.setField(writer, "tenantMapper", tenantMapper);
        return writer;
    }

    private ToolCallRecord record(String code) {
        ToolCallRecord record = new ToolCallRecord();
        record.setMethodCode(code);
        record.setSuccess(1);
        record.setCreateTime(new java.util.Date());
        return record;
    }

    @Test
    void flushesQueuedRecordsInBatch() {
        when(toolCallRecordMapper.insertBatch(anyList()))
                .thenAnswer(invocation -> ((java.util.List<?>) invocation.getArgument(0)).size());
        ToolCallRecordWriter writer = writer();
        writer.start();
        try {
            writer.offer(record("a"));
            writer.offer(record("b"));
            verify(toolCallRecordMapper, timeout(3000).atLeastOnce()).insertBatch(anyList());
        } finally {
            writer.shutdown();
        }
        // 停机时会把剩余记录 flush 掉：enqueued 必须等于 written（没有静默丢失）
        assertEquals(writer.stats().get("enqueued"), writer.stats().get("written"));
        assertEquals(0L, writer.stats().get("queued"));
    }

    @Test
    void queueFullDropsWithoutBlocking() {
        ToolCallRecordWriter writer = writer();
        // 不启动 worker，让队列保持满状态
        for (int i = 0; i < 10_000; i++) {
            writer.offer(record("t" + i));
        }
        writer.offer(record("overflow"));

        assertEquals(1L, writer.stats().get("dropped"));
        assertEquals(10_000L, writer.stats().get("queued"));
        writer.shutdown();
    }

    @Test
    void tenantNameIsCached() {
        ToolCallRecordWriter writer = writer();
        when(tenantMapper.selectByPrimaryKey(5)).thenReturn(new Tenant().setId(5).setName("业务方A"));

        assertEquals("业务方A", writer.tenantName(5));
        assertEquals("业务方A", writer.tenantName(5));
        verify(tenantMapper, times(1)).selectByPrimaryKey(5);
    }

    @Test
    void tenantNameNullForNullId() {
        ToolCallRecordWriter writer = writer();
        assertEquals(null, writer.tenantName(null));
        verify(tenantMapper, never()).selectByPrimaryKey(org.mockito.ArgumentMatchers.any());
    }
}
