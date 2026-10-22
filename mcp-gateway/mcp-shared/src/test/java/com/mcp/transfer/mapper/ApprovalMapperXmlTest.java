package com.mcp.transfer.mapper;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批 Mapper XML 解析回归：不连数据库，只校验 XML 合法、statement 齐全。
 * 数据库连接在运行时才建立，这里能提前发现 SQL 片段/resultMap 拼写错误。
 */
class ApprovalMapperXmlTest {

    @Test
    void approvalMapperXmlLoadsAllStatements() throws Exception {
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(new UnpooledDataSource());
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:mapper/ApprovalRequestMapper.xml");
        bean.setMapperLocations(resources);

        SqlSessionFactory factory = bean.getObject();
        assertNotNull(factory);

        String ns = "com.mcp.transfer.mapper.ApprovalRequestMapper.";
        assertTrue(factory.getConfiguration().hasStatement(ns + "insertSelective"));
        assertTrue(factory.getConfiguration().hasStatement(ns + "selectByPrimaryKey"));
        assertTrue(factory.getConfiguration().hasStatement(ns + "selectByPrimaryKeyForUpdate"));
        assertTrue(factory.getConfiguration().hasStatement(ns + "selectWithPage"));
        assertTrue(factory.getConfiguration().hasStatement(ns + "count"));
        assertTrue(factory.getConfiguration().hasStatement(ns + "countPendingSame"));
        assertTrue(factory.getConfiguration().hasStatement(ns + "updateStatusIfPending"));
        assertTrue(factory.getConfiguration().hasStatement(ns + "updateResultSecret"));
        assertTrue(factory.getConfiguration().hasStatement(ns + "cancelPendingByRequester"));
        assertTrue(factory.getConfiguration().hasStatement(ns + "deleteByServerId"));
        assertTrue(factory.getConfiguration().hasStatement(ns + "deleteByUserId"));
    }
}
