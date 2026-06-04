package org.example.fruitpickingrobt.interception;

import org.example.fruitpickingrobt.service.login.LoginService;
import org.example.fruitpickingrobt.config.SecurityProperties;
import org.example.fruitpickingrobt.constant.ErrorConstant;
import org.example.fruitpickingrobt.constant.LoginConstant;
import org.example.fruitpickingrobt.entity.Result;
import org.example.fruitpickingrobt.utils.BaseContext;
import org.example.fruitpickingrobt.utils.JwtUtil;
import org.example.fruitpickingrobt.utils.StringUtil;
import org.example.fruitpickingrobt.constant.HttpConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

@Component
@Slf4j(topic = "AuthorizationInterceptor")
public class AuthorizationInterceptor implements HandlerInterceptor {
    @Autowired
    private SecurityProperties properties;
    @Autowired
    private JwtUtil jwtUtil;      // 如果不用可以删除，但保留用于解析
    @Autowired
    private LoginService loginService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Result<?> result;
        PrintWriter writer = null;
        // 设置跨域
        setHeader(request, response);

        // 使用字符串设置字符编码，而不是 Charset 对象
        response.setCharacterEncoding("UTF-8");
        response.setContentType(HttpConstant.MIME_JSON);

        String token = request.getHeader(properties.getHeader());
        log.info("token: {}", token);
        if (!StringUtil.isEmpty(token) && StringUtil.JS_UNDEFINED.equals(token)) {
            token = StringUtil.EMPTY;
        }

        // 判断当前请求是否要认证，不需要验证就不用解析jwt什么的了
        String path = request.getServletPath();
        log.info("uri: {}", path);
        if (!properties.isNeedAuth(path)) {
            return true;
        }

        // token为空或者token非法
        if (StringUtil.isEmpty(token) || !token.startsWith(properties.getTokenStartWith())) {
            try {
                writer = response.getWriter();
                result = Result.unauthorized();
                writer.write(result.asJsonString());
                return false;
            } catch (IOException e) {
                log.error("response error", e);
            } finally {
                if (writer != null) writer.close();
            }
            return false;
        }

        String jwt = token.substring(properties.getTokenStartWith().length());
        // token不为空，开始解析
        String s = stringRedisTemplate.opsForValue().get(LoginConstant.TOKEN_CACHE_PREFIX + jwt);
        Long userId = null;

        if (s != null && !s.trim().isEmpty()) {
            try {
                userId = Long.valueOf(s.trim());
            } catch (NumberFormatException e) {
                log.error("Redis中存储的token值不是有效数字，已清除: {}", s);
                // 清除无效的缓存
                stringRedisTemplate.delete(LoginConstant.TOKEN_CACHE_PREFIX + jwt);
            }
        }
        if (userId == null) {
            try {
                writer = response.getWriter();
                result = Result.failure(ErrorConstant.TOKEN_INVALID);
                writer.write(result.asJsonString());
                return false;
            } catch (IOException e) {
                log.error("response error", e);
            }
            return false;
        }
        BaseContext.setCurrentUserId(userId);
        BaseContext.setCurrentJwt(jwt);
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
        BaseContext.clear();
    }

    /**
     * 为response设置header，实现跨域
     */
    private void setHeader(HttpServletRequest request, HttpServletResponse response) {
        // 跨域的header设置
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isEmpty()) {
            response.setHeader("Access-control-Allow-Origin", origin);
        }
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Credentials", "true");
        response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Requested-With");
        response.setHeader("Access-Control-Max-Age", "3600");
        // 处理预检请求
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_OK);
        }
    }
}
