package info.trizub.clamav.webclient.config;

import info.trizub.clamav.webclient.service.EndpointService;
import info.trizub.clamav.webclient.service.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationSuccessHandler authenticationSuccessHandler(UserService userService) {
        return (request, response, authentication) -> {
            userService.markLogin(authentication.getName());
            response.sendRedirect("/dashboard");
        };
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AuthenticationSuccessHandler successHandler,
                                    EndpointService endpointService) throws Exception {
        http
            .addFilterBefore(new AgentAuthenticationFilter(endpointService), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/css/**", "/js/**", "/images/**", "/icons/**", "/webfonts/**", "/flags/**").permitAll()
                .requestMatchers("/h2/**").hasRole("ADMIN")
                // Enrollment agent: la chiave dell'endpoint basta a scaricare il proprio
                // installer e a inviare i report, e non da' accesso a nient'altro.
                .requestMatchers("/agent/**").hasAnyRole("AGENT","ADMIN")
                .requestMatchers("/api/agent/**").hasAnyRole("AGENT","ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/scan/report").hasAnyRole("AGENT","OPERATOR","ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/health").hasAnyRole("VIEWER","OPERATOR","ADMIN")
                .requestMatchers("/api/**").hasAnyRole("OPERATOR","ADMIN")
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/jobs/**", "/scan/**").hasAnyRole("OPERATOR","ADMIN")
                .requestMatchers(HttpMethod.POST, "/alerts/*/ack", "/alerts/ack-all").hasAnyRole("OPERATOR","ADMIN")
                .requestMatchers(HttpMethod.GET, "/alerts", "/alerts/**").hasAnyRole("VIEWER","OPERATOR","ADMIN")
                .requestMatchers("/", "/dashboard", "/settings", "/main", "/ping", "/version", "/stats").hasAnyRole("VIEWER","OPERATOR","ADMIN")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .permitAll()
                .successHandler(successHandler)
            )
            .httpBasic(Customizer.withDefaults())
            .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login?logout").permitAll())
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/agent/**", "/h2/**"))
            .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin())); // H2 console

        return http.build();
    }
}
