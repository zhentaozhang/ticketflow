package com.ticketflow.config;

import de.codecentric.boot.admin.server.config.AdminServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * 安全配置。Spring Security配置，定义管理端接口的认证和授权规则。
 */
@Configuration
public class SecurityConfig {
 
    private final String adminContextPath;
 
    public SecurityConfig(AdminServerProperties adminServerProperties) {
        this.adminContextPath = adminServerProperties.getContextPath();
    }
 
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
 
        SavedRequestAwareAuthenticationSuccessHandler successHandler = new SavedRequestAwareAuthenticationSuccessHandler();
        successHandler.setTargetUrlParameter("redirectTo");
        successHandler.setDefaultTargetUrl(adminContextPath + "/");

        
        http
            .authorizeHttpRequests(authorize -> authorize
                    .requestMatchers(adminContextPath + "/assets/**").permitAll()
                    .requestMatchers(adminContextPath + "/login", "/css/**", "/js/**", "/image/*").permitAll()
                    .requestMatchers(adminContextPath + "/v3/api-docs/**").permitAll()
                    .anyRequest().authenticated())
            .formLogin(form -> form
                    .loginPage(adminContextPath + "/login")
                    .successHandler(successHandler))
            .logout(logout -> logout
                    .logoutUrl(adminContextPath + "/logout"))
            .httpBasic(withDefaults())
            .csrf(csrf -> csrf.ignoringRequestMatchers("/instances", "/actuator/**","/v3/api-docs/**",
                    adminContextPath + "/instances/**", adminContextPath + "/actuator/**", adminContextPath + "/v3/api-docs/**"));    
        return http.build();
    }
}