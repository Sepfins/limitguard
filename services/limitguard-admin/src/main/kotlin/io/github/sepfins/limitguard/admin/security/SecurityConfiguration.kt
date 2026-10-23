package io.github.sepfins.limitguard.admin.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfiguration {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        errorWriter: AdminSecurityErrorWriter,
        oidcAuthoritiesMapper: GrantedAuthoritiesMapper,
        clientRegistrationRepository: ClientRegistrationRepository,
    ): SecurityFilterChain {
        val oidcLogoutSuccessHandler =
            OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository).apply {
                setPostLogoutRedirectUri("{baseUrl}/limitguard/admin/")
            }

        http
            .authorizeHttpRequests { requests ->
                requests
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers("/limitguard/admin/api/**").authenticated()
                    .requestMatchers(
                        "/",
                        "/error",
                        "/favicon.ico",
                        "/limitguard/admin/index.html",
                        "/limitguard/admin/*.js",
                        "/limitguard/admin/*.css",
                        "/limitguard/admin/*.ico",
                        "/oauth2/**",
                        "/login/**",
                    ).permitAll()
                    .requestMatchers(
                        "/limitguard/admin",
                        "/limitguard/admin/",
                        "/limitguard/admin/tenants",
                        "/limitguard/admin/tenants/*",
                        "/limitguard/admin/tenants/*/products",
                        "/limitguard/admin/tenants/*/channels",
                        "/limitguard/admin/tenants/*/operations",
                        "/limitguard/admin/tenants/*/configurations",
                        "/limitguard/admin/tenants/*/policies",
                    ).authenticated()
                    .anyRequest().denyAll()
            }
            .oauth2Login { login ->
                login.userInfoEndpoint { userInfo ->
                    userInfo.userAuthoritiesMapper(oidcAuthoritiesMapper)
                }
            }
            .logout { logout ->
                logout
                    .logoutUrl("/limitguard/admin/logout")
                    .logoutSuccessHandler(oidcLogoutSuccessHandler)
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID")
            }
            .csrf { csrf -> csrf.spa() }
            .exceptionHandling { exceptions ->
                exceptions
                    .defaultAuthenticationEntryPointFor(
                        { request, response, _ ->
                            errorWriter.write(
                                request,
                                response,
                                HttpStatus.UNAUTHORIZED.value(),
                                "UNAUTHORIZED",
                                "Authentication is required",
                            )
                        },
                        PathPatternRequestMatcher.pathPattern("/limitguard/admin/api/**"),
                    )
                    .defaultAuthenticationEntryPointFor(
                        LoginUrlAuthenticationEntryPoint("/oauth2/authorization/keycloak"),
                        PathPatternRequestMatcher.pathPattern("/limitguard/admin/**"),
                    )
                    .accessDeniedHandler { request, response, _ ->
                        errorWriter.write(
                            request,
                            response,
                            HttpStatus.FORBIDDEN.value(),
                            "FORBIDDEN",
                            "The requested operation is not allowed",
                        )
                    }
            }
        return http.build()
    }

    @Bean
    fun oidcAuthoritiesMapper(): GrantedAuthoritiesMapper = GrantedAuthoritiesMapper { authorities ->
        buildSet<GrantedAuthority> {
            addAll(authorities)
            authorities.filterIsInstance<OidcUserAuthority>()
                .flatMap { authority -> realmRoles(authority.idToken.claims[REALM_ACCESS_CLAIM]) }
                .filter { role -> AdminRole.entries.any { it.name == role } }
                .forEach { role -> add(SimpleGrantedAuthority("ROLE_$role")) }
        }
    }

    private fun realmRoles(claim: Any?): List<String> {
        val realmAccess = claim as? Map<*, *> ?: return emptyList()
        val roles = realmAccess[ROLES_CLAIM] as? Collection<*> ?: return emptyList()
        return roles.mapNotNull { it?.toString() }
    }

    private companion object {
        const val REALM_ACCESS_CLAIM = "realm_access"
        const val ROLES_CLAIM = "roles"
    }
}
