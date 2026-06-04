package org.example.fruitpickingrobt.config;
import cn.hutool.core.util.ObjectUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.example.fruitpickingrobt.utils.StringUtil;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Data
@ConfigurationProperties(prefix = "security.jwt", ignoreInvalidFields = true)
@Component
@Slf4j
public class SecurityProperties {
    /**
     * Request Headers: authorization
     * **/
    private String header;
    /**
     * 令牌前缀，最后留个空格 Bearer
     * **/
    private String tokenStartWith;
    /**
     * 必须使用最少88位的Base64对该令牌进行编码
     * **/
    private String base64Secret;
    /**
     * 令牌过期时间(秒)
     * **/
    private Long tokenValiditySeconds = TimeUnit.HOURS.toSeconds(24);

    public String getTokenStartWith() {
        return tokenStartWith + StringUtil.WHITESPACE;
    }
    /**
     * 登录拦截器放行请求路径
     * **/
    private List<String> ignorePaths;
    /**
     * 管理员专属路径
     * **/
    private List<String> adminPaths;
    /**
     * 判断当前路径是否需要认证
     * **/
    public boolean isNeedAuth(String path) {
        boolean needAuth = true;
        if(ObjectUtil.isNotEmpty(this.getIgnorePaths())){
            for (String ignorePath : this.getIgnorePaths()) {
                if (matcher.match(ignorePath, path)) {
                    needAuth = false;
                }
            }
        }
        return needAuth;
    }
    /**
     * 判断是否为管理员路径
     * **/
    public boolean isAdminPath(String path) {
        if (ObjectUtil.isNotEmpty(this.getAdminPaths())) {
            for (String adminPath : adminPaths) {
                if (matcher.match(adminPath, path)) {
                    return true;
                }
            }
        }
        return false;
    }
    private static final PathMatcher matcher = new AntPathMatcher();
}
