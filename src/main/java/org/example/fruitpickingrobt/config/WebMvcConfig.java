package org.example.fruitpickingrobt.config;

import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.example.fruitpickingrobt.interception.AdminInterceptor;
import org.example.fruitpickingrobt.interception.AuthorizationInterceptor;

@Configuration
public class WebMvcConfig  extends WebMvcConfigurationSupport {
    @Autowired
    private AuthorizationInterceptor authorizationInterceptor;
    @Autowired
    private SecurityProperties securityProperties;
    @Autowired
    private AdminInterceptor adminInterceptor;

    /**
     * 拦截器
     * **/
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authorizationInterceptor)
                .excludePathPatterns(securityProperties.getIgnorePaths())
                .addPathPatterns("/**");
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns(securityProperties.getAdminPaths());
    }
    /**
     * 静态资源映射
     * **/
    @Override
    protected void addResourceHandlers(ResourceHandlerRegistry registry) {
        super.addResourceHandlers(registry);
    }

    /**
     * cors相关
     **/
    @Override
    protected void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods(HttpMethod.GET.name(), HttpMethod.POST.name(), HttpMethod.PUT.name()
                        , HttpMethod.DELETE.name(), HttpMethod.OPTIONS.name())
                .maxAge(3600)
                .allowCredentials(true);
    }
    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOriginPattern("*");
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
