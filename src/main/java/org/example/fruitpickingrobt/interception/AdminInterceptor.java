package org.example.fruitpickingrobt.interception;

import lombok.extern.slf4j.Slf4j;
import org.example.fruitpickingrobt.config.SecurityProperties;
import org.example.fruitpickingrobt.constant.AdminConstant;
import org.example.fruitpickingrobt.constant.RoleConstant;
import org.example.fruitpickingrobt.dao.LoginDAO;
import org.example.fruitpickingrobt.entity.Result;
import org.example.fruitpickingrobt.entity.User;
import org.example.fruitpickingrobt.utils.BaseContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * 拦截管理员相关端口的拦截器
 * **/
@Component
@Slf4j(topic = "AdminInterceptor")
public class AdminInterceptor implements HandlerInterceptor {
    @Autowired
    private SecurityProperties properties;
    @Autowired
    private LoginDAO loginDAO;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 当前路径是否为管理员路径
        if (!properties.isAdminPath(request.getServletPath())) {
            return true;
        }
        // 验证管理员权限
        User user = loginDAO.selectUserById(BaseContext.getCurrentUserId());
        if (user == null) {
            return false;
        }
        boolean r = RoleConstant.ROLE_ADMIN.equals(user.getRole());
        if (!r) {
            try (PrintWriter writer = response.getWriter()) {
                writer.write(Result.forbidden(AdminConstant.DEFAULT_AUTH_ERROR).asJsonString());
                return false;
            } catch (IOException e) {
                log.error("response error", e);
            }
        }
        return r;
    }
}
