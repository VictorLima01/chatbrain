package com.chat.cloudability.ibm.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.chat.cloudability.ibm.adapter.in.web.AdminAccessInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties props;
    private final AdminAccessInterceptor adminAccess;

    public WebConfig(AppProperties props, AdminAccessInterceptor adminAccess) {
        this.props = props;
        this.adminAccess = adminAccess;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(props.cors().allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    /** A alcada de administrador vale para tudo que esta sob /api/admin. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAccess).addPathPatterns("/api/admin/**");
    }

    /** Client HTTP compartilhado (usado pelo servico de embeddings). */
    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }
}
