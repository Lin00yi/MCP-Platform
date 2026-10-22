package com.mcp.transfer;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// 扫描 Mapper 接口所在的包
@MapperScan("com.mcp.transfer.mapper")
@SpringBootApplication
@EnableScheduling
public class McpTransferApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpTransferApplication.class, args);
    }
}
