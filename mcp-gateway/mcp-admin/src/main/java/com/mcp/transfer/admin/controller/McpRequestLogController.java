package com.mcp.transfer.admin.controller;

import com.mcp.transfer.admin.security.AdminAuthContext;
import com.mcp.transfer.entity.ToolCallRecord;
import com.mcp.transfer.mapper.ToolCallRecordMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * 工具调用记录导出（MCP 语义）。列表查询走 /mcp/admin/tool-call-records。
 */
@Tag(name = "调用日志")
@RestController
@RequestMapping("/mcp/log")
public class McpRequestLogController {

    /** 单次导出上限：流式写出，避免 10 万行 + autoSizeColumn 拖垮内存/CPU */
    private static final int MAX_EXPORT_ROWS = 20000;

    @Autowired
    private ToolCallRecordMapper toolCallRecordMapper;

    /** 当前用户可见租户（超管返回 null 表示不过滤） */
    private List<Integer> visibleTenantIds() {
        AdminAuthContext ctx = AdminAuthContext.get();
        if (ctx == null || ctx.isSuperAdmin()) {
            return null;
        }
        return ctx.getTenantIds() == null ? java.util.Collections.emptyList() : ctx.getTenantIds();
    }

    @Operation(summary = "调用日志导出")
    @GetMapping("/export")
    public void exportLog(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date endTime,
            @RequestParam(required = false) Integer success,
            @RequestParam(required = false) Integer serverId,
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String userId,
            HttpServletResponse response) throws Exception {

        List<Integer> tenantIds = visibleTenantIds();
        List<ToolCallRecord> list;
        if (tenantIds != null && tenantIds.isEmpty()) {
            list = java.util.Collections.emptyList();
        } else {
            list = toolCallRecordMapper.selectWithFilter(0, MAX_EXPORT_ROWS, startTime, endTime, success, serverId, appId, userId, tenantIds);
        }

        // 流式写出（SXSSF 仅保留 100 行在内存）
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        workbook.setCompressTempFiles(true);
        try {
            Sheet sheet = workbook.createSheet("工具调用记录");

            String[] headers = {"ID", "租户", "MCP Server", "应用", "用户", "工具标识(code)", "工具名", "入参", "结果",
                    "调用类型", "HTTP方法", "目标URL", "成功", "错误信息", "耗时(ms)", "时间"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
            }

            int rowNum = 1;
            for (ToolCallRecord r : list) {
                Row row = sheet.createRow(rowNum++);
                int c = 0;
                row.createCell(c++).setCellValue(r.getId() != null ? r.getId() : 0);
                row.createCell(c++).setCellValue(safe(r.getTenantName()));
                row.createCell(c++).setCellValue(safe(r.getServerName()));
                row.createCell(c++).setCellValue(safe(r.getAppId()));
                row.createCell(c++).setCellValue(safe(r.getUserName() != null ? r.getUserName() : r.getUserId()));
                row.createCell(c++).setCellValue(safe(r.getMethodCode()));
                row.createCell(c++).setCellValue(safe(r.getMethodName()));
                row.createCell(c++).setCellValue(safe(r.getRequestArgs()));
                row.createCell(c++).setCellValue(safe(r.getResponseResult()));
                row.createCell(c++).setCellValue(safe(r.getCallType()));
                row.createCell(c++).setCellValue(safe(r.getHttpMethod()));
                row.createCell(c++).setCellValue(safe(r.getTargetUrl()));
                row.createCell(c++).setCellValue(r.getSuccess() != null && r.getSuccess() == 1 ? "成功" : "失败");
                row.createCell(c++).setCellValue(safe(r.getErrorMsg()));
                row.createCell(c++).setCellValue(r.getDuration() != null ? r.getDuration() : 0);
                row.createCell(c++).setCellValue(r.getCreateTime() != null ? r.getCreateTime().toString() : "");
            }

            // 固定列宽，避免 autoSizeColumn 在大数据量下极慢
            int[] widths = {8, 16, 18, 16, 18, 22, 24, 40, 40, 10, 10, 40, 8, 30, 12, 22};
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }

            String fileName = "工具调用记录_" + System.currentTimeMillis() + ".xlsx";
            String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8.name()).replace("+", "%20");
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"tool-call-records.xlsx\"; filename*=UTF-8''" + encoded);

            OutputStream outputStream = response.getOutputStream();
            workbook.write(outputStream);
            outputStream.flush();
            outputStream.close();
        } finally {
            workbook.dispose();
            workbook.close();
        }
    }

    private String safe(String v) {
        return v == null ? "" : v;
    }
}
