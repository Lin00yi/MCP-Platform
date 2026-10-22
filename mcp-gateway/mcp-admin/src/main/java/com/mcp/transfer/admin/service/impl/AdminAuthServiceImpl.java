package com.mcp.transfer.admin.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.mcp.transfer.admin.service.AdminAuthService;
import com.mcp.transfer.admin.service.LoginAttemptService;
import com.mcp.transfer.admin.security.AdminAuthContext;
import com.mcp.transfer.dto.AdminUserRequest;
import com.mcp.transfer.dto.AdminUserVO;
import com.mcp.transfer.dto.LoginRequest;
import com.mcp.transfer.dto.LoginResult;
import com.mcp.transfer.dto.Result;
import com.mcp.transfer.entity.AdminUser;
import com.mcp.transfer.entity.AdminUserTenant;
import com.mcp.transfer.enums.RoleEnum;
import com.mcp.transfer.mapper.AdminUserMapper;
import com.mcp.transfer.mapper.AdminUserTenantMapper;
import com.mcp.transfer.admin.security.AdminJwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 后台管理认证与用户管理实现。
 *
 * <p>安全要点：
 * <ul>
 *   <li>登录失败按「用户名 + IP」限流；</li>
 *   <li>每次请求都回查用户状态与角色，禁用/降级立即生效；</li>
 *   <li>token 内 {@code uat} 与用户 update_time 不一致即失效（改密/编辑后强制重登）；</li>
 *   <li>租户管理员不能修改/删除同级或更高级别的用户。</li>
 * </ul>
 */
@Slf4j
@Service
public class AdminAuthServiceImpl implements AdminAuthService {

    private static final int MIN_PASSWORD_LENGTH = 8;

    @Autowired
    private AdminUserMapper adminUserMapper;

    @Autowired
    private AdminUserTenantMapper adminUserTenantMapper;

    @Autowired
    private com.mcp.transfer.mapper.ApprovalRequestMapper approvalRequestMapper;

    @Autowired
    private LoginAttemptService loginAttemptService;

    @Autowired
    private org.springframework.core.env.Environment environment;

    /** 生产环境必须配置管理端 JWT 密钥，避免以启动参数方式指定 prod 时仍落到默认密钥 */
    @javax.annotation.PostConstruct
    public void checkJwtSecret() {
        boolean prod = false;
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile)) {
                prod = true;
                break;
            }
        }
        if (!prod) {
            return;
        }
        String secret = environment.getProperty("mcp.admin.jwt-secret");
        if (secret == null || secret.trim().length() < 32) {
            throw new IllegalStateException(
                    "生产环境必须配置 MCP_ADMIN_JWT_SECRET（mcp.admin.jwt-secret，>=32 字符）");
        }
    }

    @Override
    public Result<LoginResult> login(LoginRequest request, String clientIp) {
        if (request == null || !StringUtils.hasText(request.getUsername()) || !StringUtils.hasText(request.getPassword())) {
            return Result.fail("用户名或密码不能为空");
        }
        String username = request.getUsername().trim();
        if (loginAttemptService.isBlocked(username, clientIp)) {
            return Result.fail("登录失败次数过多，请 15 分钟后再试");
        }

        AdminUser user = adminUserMapper.selectByUsername(username);
        if (user == null || !BCrypt.checkpw(request.getPassword(), user.getPassword())) {
            loginAttemptService.onFailure(username, clientIp);
            return Result.fail("用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() != 1) {
            return Result.fail("账号已被禁用");
        }
        loginAttemptService.onSuccess(username, clientIp);

        String token = AdminJwtUtil.sign(user.getId(), user.getUsername(), user.getRole(), user.getUpdateTime());
        List<Integer> tenantIds = adminUserTenantMapper.selectTenantIdsByUserId(user.getId());
        LoginResult result = new LoginResult();
        result.setToken(token);
        result.setUser(AdminUserVO.from(user, tenantIds));
        return Result.success(result);
    }

    @Override
    public AdminAuthContext verifyToken(String token) {
        AdminAuthContext ctx = AdminJwtUtil.parse(token);
        if (ctx == null || ctx.getUserId() == null) {
            return null;
        }
        AdminUser user = adminUserMapper.selectByPrimaryKey(ctx.getUserId());
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            return null;
        }
        // 用户记录被编辑/改密后，旧 token 立即失效
        if (ctx.getUserUpdatedAt() != null && user.getUpdateTime() != null
                && ctx.getUserUpdatedAt().longValue() != user.getUpdateTime().getTime()) {
            return null;
        }
        // 角色以数据库为准，降级立即生效
        ctx.setRole(user.getRole());
        ctx.setUsername(user.getUsername());
        if (!ctx.isSuperAdmin()) {
            ctx.setTenantIds(adminUserTenantMapper.selectTenantIdsByUserId(ctx.getUserId()));
        } else {
            ctx.setTenantIds(Collections.emptyList());
        }
        return ctx;
    }

    @Override
    public List<AdminUserVO> listUsers() {
        List<AdminUser> users = adminUserMapper.selectAll();
        List<Integer> operatorTenantIds = currentTenantIds();
        AdminAuthContext operator = AdminAuthContext.get();
        Long operatorId = operator != null ? operator.getUserId() : null;
        List<AdminUserVO> result = new ArrayList<>();
        for (AdminUser u : users) {
            // 平台超管不展示（超管之间互不可见，只管理「超管以下」的用户）
            if (RoleEnum.SUPER_ADMIN.getCode().equals(u.getRole())) {
                continue;
            }
            // 不展示自己（自己不需要管理自己）
            if (operatorId != null && operatorId.equals(u.getId())) {
                continue;
            }
            // 租户管理员：只展示归属自己租户的用户
            if (operatorTenantIds != null && !isUserInTenants(u.getId(), operatorTenantIds)) {
                continue;
            }
            List<Integer> tenantIds = adminUserTenantMapper.selectTenantIdsByUserId(u.getId());
            result.add(AdminUserVO.from(u, tenantIds));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminUserVO createUser(AdminUserRequest request) {
        if (!StringUtils.hasText(request.getUsername()) || !StringUtils.hasText(request.getPassword())) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }
        if (request.getPassword().length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        if (adminUserMapper.selectByUsername(request.getUsername()) != null) {
            throw new IllegalArgumentException("用户名已存在");
        }
        if (RoleEnum.getByCode(request.getRole()) == null) {
            throw new IllegalArgumentException("角色不合法");
        }
        // 平台超管只能由初始化脚本（AdminBootstrap）创建，控制台一律拒绝
        if (RoleEnum.SUPER_ADMIN.getCode().equals(request.getRole())) {
            throw new IllegalArgumentException("平台超管只能通过初始化脚本创建");
        }
        // 业务方归属：业务方管理员自动归属到自己业务方；平台超管需手动指定
        List<Integer> operatorTenantIds = currentTenantIds();
        List<Integer> finalTenantIds;
        if (operatorTenantIds != null) {
            // 租户管理员：只能创建自己以下角色（不能创建平台超管，也不能创建同级租户管理员）
            if (RoleEnum.SUPER_ADMIN.getCode().equals(request.getRole())) {
                throw new IllegalArgumentException("无权创建平台超管");
            }
            if (RoleEnum.ADMIN.getCode().equals(request.getRole())) {
                throw new IllegalArgumentException("无权创建租户管理员");
            }
            if (operatorTenantIds.isEmpty()) {
                throw new IllegalArgumentException("当前账号未分配业务方，无法创建用户");
            }
            finalTenantIds = operatorTenantIds;
        } else {
            if (!RoleEnum.SUPER_ADMIN.getCode().equals(request.getRole()) && CollectionUtils.isEmpty(request.getTenantIds())) {
                throw new IllegalArgumentException("非超管用户必须分配至少一个业务方");
            }
            finalTenantIds = request.getTenantIds();
        }

        AdminUser user = new AdminUser();
        user.setUsername(request.getUsername().trim());
        user.setPassword(BCrypt.hashpw(request.getPassword()));
        user.setName(request.getName());
        user.setRole(request.getRole());
        user.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        Date now = new Date();
        user.setCreateTime(now);
        user.setUpdateTime(now);
        adminUserMapper.insertSelective(user);

        saveTenantRels(user.getId(), finalTenantIds);
        return AdminUserVO.from(user, finalTenantIds == null ? Collections.emptyList() : finalTenantIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminUserVO updateUser(Long id, AdminUserRequest request) {
        AdminUser user = adminUserMapper.selectByPrimaryKey(id);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        if (StringUtils.hasText(request.getPassword()) && request.getPassword().length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        if (StringUtils.hasText(request.getRole()) && RoleEnum.getByCode(request.getRole()) == null) {
            throw new IllegalArgumentException("角色不合法");
        }
        // 平台超管角色不能通过控制台授予（仅初始化脚本可创建）
        if (StringUtils.hasText(request.getRole())
                && RoleEnum.SUPER_ADMIN.getCode().equals(request.getRole())
                && !RoleEnum.SUPER_ADMIN.getCode().equals(user.getRole())) {
            throw new IllegalArgumentException("平台超管角色不能通过控制台授予");
        }
        // 角色变更权限：自己不能改自己的角色；只有平台超管能改他人角色
        boolean roleChanged = StringUtils.hasText(request.getRole()) && !request.getRole().equals(user.getRole());
        AdminAuthContext operator = AdminAuthContext.get();
        boolean isSelf = operator != null && operator.getUserId() != null && operator.getUserId().equals(id);
        if (roleChanged) {
            if (isSelf) {
                throw new IllegalArgumentException("不能编辑自己的角色");
            }
            if (operator == null || !operator.isSuperAdmin()) {
                throw new IllegalArgumentException("只有平台超管能编辑角色");
            }
        }
        // 非超管：不能动平台超管/同级管理员、只能动自己业务方内用户、不能设为超管
        List<Integer> operatorTenantIds = currentTenantIds();
        List<Integer> finalTenantIds = null;
        if (operatorTenantIds != null) {
            if (RoleEnum.SUPER_ADMIN.getCode().equals(user.getRole())) {
                throw new IllegalArgumentException("无权操作平台超管");
            }
            // 租户管理员不能修改另一个租户管理员（防止横向改密/禁用）
            if (!isSelf && RoleEnum.ADMIN.getCode().equals(user.getRole())) {
                throw new IllegalArgumentException("无权操作同级管理员");
            }
            if (!isUserInTenants(id, operatorTenantIds)) {
                throw new IllegalArgumentException("无权操作该用户");
            }
            if (StringUtils.hasText(request.getRole()) && RoleEnum.SUPER_ADMIN.getCode().equals(request.getRole())) {
                throw new IllegalArgumentException("无权设置平台超管");
            }
        } else {
            // 平台超管：显式传了 tenantIds 才重设业务方
            finalTenantIds = request.getTenantIds();
        }

        AdminUser update = new AdminUser();
        update.setId(id);
        if (StringUtils.hasText(request.getPassword())) {
            update.setPassword(BCrypt.hashpw(request.getPassword()));
        }
        if (request.getName() != null) {
            update.setName(request.getName());
        }
        if (StringUtils.hasText(request.getRole())) {
            update.setRole(request.getRole());
        }
        if (request.getStatus() != null) {
            update.setStatus(request.getStatus());
        }
        update.setUpdateTime(new Date());
        adminUserMapper.updateByPrimaryKeySelective(update);

        if (finalTenantIds != null) {
            adminUserTenantMapper.deleteByUserId(id);
            saveTenantRels(id, finalTenantIds);
        }

        AdminUser refreshed = adminUserMapper.selectByPrimaryKey(id);
        List<Integer> tenantIds = adminUserTenantMapper.selectTenantIdsByUserId(id);
        return AdminUserVO.from(refreshed, tenantIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(Long id) {
        AdminUser user = adminUserMapper.selectByPrimaryKey(id);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        AdminAuthContext operator = AdminAuthContext.get();
        if (operator != null && operator.getUserId() != null && operator.getUserId().equals(id)) {
            throw new IllegalArgumentException("不能删除自己");
        }
        if (RoleEnum.SUPER_ADMIN.getCode().equals(user.getRole())) {
            throw new IllegalArgumentException("不能删除平台超管");
        }
        List<Integer> operatorTenantIds = currentTenantIds();
        if (operatorTenantIds != null) {
            if (RoleEnum.ADMIN.getCode().equals(user.getRole())) {
                throw new IllegalArgumentException("无权删除同级管理员");
            }
            if (!isUserInTenants(id, operatorTenantIds)) {
                throw new IllegalArgumentException("无权删除该用户");
            }
        }
        adminUserTenantMapper.deleteByUserId(id);
        approvalRequestMapper.cancelPendingByRequester(user.getUsername());
        approvalRequestMapper.deleteByUserId(id);
        adminUserMapper.deleteByPrimaryKey(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        AdminUser user = adminUserMapper.selectByPrimaryKey(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        if (!StringUtils.hasText(oldPassword) || !StringUtils.hasText(newPassword)) {
            throw new IllegalArgumentException("旧密码和新密码不能为空");
        }
        if (!BCrypt.checkpw(oldPassword, user.getPassword())) {
            throw new IllegalArgumentException("旧密码不正确");
        }
        if (newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("新密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        AdminUser update = new AdminUser();
        update.setId(userId);
        update.setPassword(BCrypt.hashpw(newPassword));
        // update_time 变化会让旧 token 的 uat 失配，实现“改密即下线”
        update.setUpdateTime(new Date());
        adminUserMapper.updateByPrimaryKeySelective(update);
    }

    /** 当前操作用户的 tenantIds（超管返回 null 表示不过滤） */
    private List<Integer> currentTenantIds() {
        AdminAuthContext ctx = AdminAuthContext.get();
        if (ctx == null || ctx.isSuperAdmin()) {
            return null;
        }
        return ctx.getTenantIds() == null ? Collections.emptyList() : ctx.getTenantIds();
    }

    /** 判断用户是否被分配到给定租户集合中的某个租户 */
    private boolean isUserInTenants(Long userId, List<Integer> tenantIds) {
        if (tenantIds == null || tenantIds.isEmpty()) {
            return false;
        }
        List<Integer> userTenantIds = adminUserTenantMapper.selectTenantIdsByUserId(userId);
        if (userTenantIds == null || userTenantIds.isEmpty()) {
            return false;
        }
        for (Integer t : userTenantIds) {
            if (tenantIds.contains(t)) {
                return true;
            }
        }
        return false;
    }

    private void saveTenantRels(Long userId, List<Integer> tenantIds) {
        if (CollectionUtils.isEmpty(tenantIds)) {
            return;
        }
        for (Integer tenantId : tenantIds) {
            AdminUserTenant rel = new AdminUserTenant();
            rel.setUserId(userId);
            rel.setTenantId(tenantId);
            rel.setCreateTime(new Date());
            adminUserTenantMapper.insert(rel);
        }
    }
}
