package edu.smarttraffic.dashboard.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.smarttraffic.dashboard.adapter.in.http.DashboardHomeController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(controllers = DashboardHomeController.class)
@AutoConfigureMockMvc
@Import({DashboardSecurityConfiguration.class, DashboardSecurityConfigurationTest.ClientConfig.class})
class DashboardSecurityConfigurationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void requiresLoginForDashboardAndWebSocket() throws Exception {
        mockMvc.perform(get("/dashboard.html"))
                .andExpect(status().isFound())
                .andExpect(result -> assertTrue(
                        result.getResponse().getHeader("Location").contains("/oauth2/authorization/dashboard")));

        mockMvc.perform(get("/ws/traffic"))
                .andExpect(status().isFound())
                .andExpect(result -> assertTrue(
                        result.getResponse().getHeader("Location").contains("/oauth2/authorization/dashboard")));
    }

    @Test
    void startsAuthorizationCodeFlowWithPkce() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorization/dashboard"))
                .andExpect(status().isFound())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        assertNotNull(location);
        assertTrue(location.contains("code_challenge="));
        assertTrue(location.contains("code_challenge_method=S256"));
    }

    @Test
    void authenticatedSessionCanReachDashboardAndWebSocketRoutes() throws Exception {
        mockMvc.perform(get("/dashboard.html").with(user("dashboard-viewer")))
                .andExpect(authenticated());

        mockMvc.perform(get("/ws/traffic").with(user("dashboard-viewer")))
                .andExpect(authenticated());
    }

    @Test
    void logoutRequiresCsrfAndInvalidatesTheDashboardSession() throws Exception {
        var page = mockMvc.perform(get("/oauth2/authorization/dashboard"))
                .andExpect(status().isFound())
                .andReturn();
        var csrfCookie = page.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(csrfCookie);
        assertTrue(csrfCookie.getSecure());
        assertFalse(csrfCookie.isHttpOnly());

        mockMvc.perform(post("/app/logout").with(user("dashboard-viewer")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/app/logout")
                        .with(user("dashboard-viewer"))
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()))
                .andExpect(status().isNoContent())
                .andExpect(unauthenticated());
    }

    @Configuration
    static class ClientConfig {
        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            ClientRegistration registration = ClientRegistration.withRegistrationId("dashboard")
                    .clientId("dashboard-bff")
                    .clientSecret("test-only-secret")
                    .clientName("SmartTraffic")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("http://localhost/login/oauth2/code/dashboard")
                    .scope("openid")
                    .authorizationUri("https://auth.test/oauth2/authorize")
                    .tokenUri("https://auth.test/oauth2/token")
                    .jwkSetUri("https://auth.test/oauth2/jwks")
                    .issuerUri("https://auth.test")
                    .userNameAttributeName("sub")
                    .build();
            return new InMemoryClientRegistrationRepository(registration);
        }
    }
}
