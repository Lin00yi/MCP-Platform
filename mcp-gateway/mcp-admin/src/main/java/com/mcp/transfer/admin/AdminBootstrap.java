package com.mcp.transfer.admin;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.mcp.transfer.shared.entity.AdminUser;
import com.mcp.transfer.shared.mapper.AdminUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;

/**
 * 管理员账号引导：
 * <ul>
 *   <li>库中没有任何管理员时，创建初始超管；密码取 {@code MCP_ADMIN_INIT_PASSWORD}，
 *       开发环境未配置则随机生成并打印到日志；生产环境强制要求配置，避免默认口令。</li>
 *   <li>若检测到旧的 {@code admin/admin123} 默认口令仍未修改，打印醒目告警。</li>
 * </ul>
 */
@Component
public class AdminBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    @Autowired
    private AdminUserMapper adminUserMapper;

    @Autowired
    private Environment environment;

    @org.springframework.beans.factory.annotation.Value("${mcp.admin.init-password:}")
    private String initPassword;

    @Override
    public void run(String... args) {
        try {
            List<AdminUser> users = adminUserMapper.selectAll();
            if (users == null || users.isEmpty()) {
                bootstrap();
            } else {
                warnDefaultPassword();
            }
        } catch (Exception e) {
            log.error("[ADMIN_BOOTSTRAP] 初始化管理员账号失败", e);
            if (isProd()) {
                // 生产环境没有可用管理员属于致命配置错误，直接阻止启动
                throw new IllegalStateException("初始化管理员账号失败，请检查 MCP_ADMIN_INIT_PASSWORD 与数据库连接", e);
            }
        }
    }

    private void bootstrap() {
        boolean prod = isProd();
        String password = initPassword;
        boolean generated = false;
        if (!StringUtils.hasText(password)) {
            if (prod) {
                throw new IllegalStateException(
                        "生产环境首次启动必须配置 MCP_ADMIN_INIT_PASSWORD（初始超管密码，至少 8 位）");
            }
            password = IdUtil.simpleUUID();
            generated = true;
        }
        if (password.length() < 8) {
            throw new IllegalStateException("MCP_ADMIN_INIT_PASSWORD 长度必须 >= 8");
        }

        AdminUser admin = new AdminUser();
        admin.setUsername("admin");
        admin.setPassword(BCrypt.hashpw(password));
        admin.setName("平台超管");
        admin.setRole("SUPER_ADMIN");
        admin.setStatus(1);
        Date now = new Date();
        admin.setCreateTime(now);
        admin.setUpdateTime(now);
        adminUserMapper.insertSelective(admin);

        if (generated) {
            log.warn("======================================================================");
            log.warn("[ADMIN_BOOTSTRAP] 已创建初始超管 admin，随机密码：{}", password);
            log.warn("[ADMIN_BOOTSTRAP] 请立即登录并修改密码；生产环境请改用 MCP_ADMIN_INIT_PASSWORD");
            log.warn("======================================================================");
        } else {
            log.info("[ADMIN_BOOTSTRAP] 已创建初始超管 admin（密码来自 MCP_ADMIN_INIT_PASSWORD）");
        }
    }

    private void warnDefaultPassword() {
        try {
            AdminUser admin = adminUserMapper.selectByUsername("admin");
            if (admin != null && admin.getPassword() != null && BCrypt.checkpw("admin123", admin.getPassword())) {
                log.error("======================================================================");
                log.error("[ADMIN_BOOTSTRAP] 检测到 admin 仍在使用默认口令 admin123，存在极高安全风险！");
                log.error("[ADMIN_BOOTSTRAP] 请立即登录控制台修改密码。");
                log.error("======================================================================");
            }
        } catch (Exception ignore) {
            // 仅告警，不影响启动
        }
    }

    private boolean isProd() {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }
}
