package com.veloce.auth;

import com.veloce.common.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
class Security {
    @Bean PasswordEncoder passwords() { return new BCryptPasswordEncoder(12); }

    @Bean CorsConfiguration browserCors(@Value("${veloce.origins}") String origins,
                                        @Value("${veloce.loopback-web-ports:false}") boolean loopbackWebPorts,
                                        @Value("${server.address}") String bindAddress) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::strip).filter(origin -> !origin.isEmpty()).toList());
        if (loopbackWebPorts && Set.of("127.0.0.1", "localhost", "::1").contains(bindAddress)) {
            config.setAllowedOriginPatterns(List.of("http://localhost:[*]", "http://127.0.0.1:[*]", "http://[::1]:[*]"));
        }
        config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization","Content-Type","Idempotency-Key","X-Veloce-Client"));
        config.setExposedHeaders(List.of("X-Request-Id","Content-Disposition"));
        config.setAllowCredentials(true);
        config.setMaxAge(600L);
        return config;
    }

    @Bean CorsConfigurationSource corsConfigurationSource(CorsConfiguration browserCors) {
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**",browserCors);
        return source;
    }

    @Bean SecurityFilterChain chain(HttpSecurity http, Identity identity, Json json, @org.springframework.beans.factory.annotation.Qualifier("corsConfigurationSource") CorsConfigurationSource cors) throws Exception {
        return http.cors(c -> c.configurationSource(cors)).csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(f -> f.disable()).httpBasic(b -> b.disable()).logout(l -> l.disable())
            .authorizeHttpRequests(a -> a.requestMatchers("/api/v1/auth/register","/api/v1/auth/login","/api/v1/auth/refresh",
                "/api/v1/auth/providers","/api/v1/auth/google","/api/v1/auth/facebook",
                "/api/v1/auth/logout","/api/v1/google-calendar/callback","/actuator/health","/docs","/swagger-ui/**","/v3/api-docs/**","/error").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint((req,res,err) -> {
                res.setStatus(401);
                res.setContentType("application/json");
                Object requestId=req.getAttribute("requestId");
                res.getWriter().write(json.write(Errors.body(Problem.unauthorized(),
                    requestId == null ? UUID.randomUUID().toString() : requestId.toString())));
            }))
            .addFilterBefore(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res, FilterChain filter)
                    throws IOException, ServletException {
                    String id=UUID.randomUUID().toString();
                    req.setAttribute("requestId",id);
                    res.setHeader("X-Request-Id",id);
                    if (req.getRequestURI().startsWith("/api/")) res.setHeader("Cache-Control","no-store");
                    String bearer=req.getHeader("Authorization");
                    if (bearer != null && bearer.startsWith("Bearer ") && bearer.length()<4096) {
                        try {
                            UUID owner=identity.subject(bearer.substring(7));
                            SecurityContextHolder.getContext().setAuthentication(
                                UsernamePasswordAuthenticationToken.authenticated(owner,null,List.of()));
                        } catch (io.jsonwebtoken.JwtException | IllegalArgumentException ignored) { }
                    }
                    filter.doFilter(req,res);
                }
            }, UsernamePasswordAuthenticationFilter.class).build();
    }
}
