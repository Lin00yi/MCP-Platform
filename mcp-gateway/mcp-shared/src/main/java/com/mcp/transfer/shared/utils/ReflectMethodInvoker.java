package com.mcp.transfer.shared.utils;

import com.mcp.transfer.shared.dto.mcp.req.McpRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import java.lang.reflect.Method;

/**
 * 反射调用工具类：固定参数为 McpRequest 的方法调用
 */
@Component
public class ReflectMethodInvoker {

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * 核心方法：解析 "类名.方法名" 并调用接收 McpRequest 参数的方法
     * @param methodStr 格式如 "PolicyServiceImpl.getPolicyRegion"
     * @param mcpRequest 固定入参（唯一参数）
     * @return 方法调用结果
     * @throws Exception 反射调用异常
     */
    public Object invokeMethod(String methodStr, McpRequest mcpRequest) throws Exception {
        // 1. 按.分割类名/Bean名 和 方法名
        String[] parts = methodStr.split("\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException("方法格式错误，必须是 类名.方法名，如 PolicyServiceImpl.getPolicyRegion");
        }
        String classNameOrBeanName = parts[0];
        String methodName = parts[1];

        // 2. 获取目标类实例（优先从Spring容器获取Bean）
        Object targetObject = getTargetObject(classNameOrBeanName);

        // 3. 反射获取【仅接收 McpRequest 单参数】的目标方法
        Method targetMethod = findMcpRequestMethod(targetObject.getClass(), methodName);
        if (targetMethod == null) {
            throw new NoSuchMethodException(
                "未找到方法：" + methodName + "，要求：仅接收 McpRequest 作为唯一参数"
            );
        }

        // 4. 执行方法（固定传入 McpRequest 参数）
        return targetMethod.invoke(targetObject, mcpRequest);
    }

    /**
     * 获取目标类实例（优先Spring Bean，否则反射实例化）
     */
    private Object getTargetObject(String classNameOrBeanName) throws Exception {
        // 尝试获取Spring Bean（支持首字母小写的Bean名，如 policyServiceImpl）
        Object bean = null;
        try {
            bean = applicationContext.getBean(classNameOrBeanName);
        } catch (Exception e) {
            String lowerFirstBeanName = Character.toLowerCase(classNameOrBeanName.charAt(0)) + classNameOrBeanName.substring(1);
            try {
                bean = applicationContext.getBean(lowerFirstBeanName);
            } catch (Exception ex) {
                // 非Spring Bean，反射实例化（需确保类有无参构造）
                Class<?> targetClass = Class.forName(classNameOrBeanName);
                bean = targetClass.getDeclaredConstructor().newInstance();
            }
        }
        return bean;
    }

    /**
     * 查找仅接收 McpRequest 作为唯一参数的方法
     */
    private Method findMcpRequestMethod(Class<?> clazz, String methodName) {
        // 遍历当前类的所有方法
        for (Method method : clazz.getDeclaredMethods()) {
            // 匹配条件：方法名一致 + 参数个数=1 + 参数类型=McpRequest
            if (method.getName().equals(methodName) 
                && method.getParameterCount() == 1 
                && method.getParameterTypes()[0] == McpRequest.class) {
                
                method.setAccessible(true); // 允许访问私有/受保护方法
                return method;
            }
        }
        // 递归查找父类方法
        if (clazz.getSuperclass() != null && !clazz.getSuperclass().equals(Object.class)) {
            return findMcpRequestMethod(clazz.getSuperclass(), methodName);
        }
        return null;
    }
}