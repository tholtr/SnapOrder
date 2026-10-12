package com.campusdash.presentation;

import com.campusdash.presentation.auth.AuthInterceptor;
import com.campusdash.presentation.risk.RiskContextInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final RiskContextInterceptor riskContextInterceptor;
    private final List<String> corsOrigins;

    public WebConfig(AuthInterceptor authInterceptor,
                     RiskContextInterceptor riskContextInterceptor,
                     @Value("${dash.web.cors-origins:http://localhost:5173}") String corsOrigins) {
        this.authInterceptor = authInterceptor;
        this.riskContextInterceptor = riskContextInterceptor;
        this.corsOrigins = List.of(corsOrigins.split(","));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // 登录/登出本身不需要认证；健康检查供前端探测
                .excludePathPatterns("/api/auth/login", "/api/auth/refresh", "/api/health")
                // 内部观测端点：仅供本机压测/运维，生产由网关屏蔽外部访问
                .excludePathPatterns("/api/internal/**");

        // 风控上下文解析。**注册顺序在 AuthInterceptor 之后**：它的前置条件是
        // 已认证用户（AuthInterceptor 往 request attribute 里放的 userId），
        // 顺序反了会解析出一个没有身份的上下文。
        //
        // 排除路径与上面保持一致。其实不排除也不会出错——解析器在拿不到 userId 时
        // 直接跳过，不做任何事；显式列出来是为了省掉一次无用解析，
        // 也让「哪些路径没有风控上下文」在配置里一眼可见。
        registry.addInterceptor(riskContextInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/auth/refresh", "/api/health")
                .excludePathPatterns("/api/internal/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(corsOrigins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
