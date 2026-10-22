package com.mcp.transfer.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 环境工具类：判断当前运行环境
 */
@Component
public class EnvUtils {

    @Autowired
    private Environment environment;

    // 环境标识常量
    public static final String ENV_DEV = "dev";
    public static final String ENV_TEST = "test";
    public static final String ENV_PROD = "prod";

    /**
     * 判断是否为本地环境
     */
    public boolean ieDev() {
        return containsEnv(ENV_DEV);
    }

    /**
     * 判断是否为测试环境
     */
    public boolean isTest() {
        return containsEnv(ENV_TEST);
    }

    /**
     * 判断是否为生产环境
     */
    public boolean isProd() {
        return containsEnv(ENV_PROD);
    }

    /**
     * 获取当前激活的环境名称
     */
    public String getCurrentEnv() {
        String[] activeProfiles = environment.getActiveProfiles();
        return activeProfiles.length > 0 ? activeProfiles[0] : ENV_DEV;
    }

    /**
     * 检查当前环境是否包含指定标识
     */
    private boolean containsEnv(String env) {
        String[] activeProfiles = environment.getActiveProfiles();
        for (String profile : activeProfiles) {
            if (env.equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }
}