package com.veloce.auth;

import com.veloce.common.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.cors.CorsConfiguration;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SocialApiTest {
    final Identity identity=mock(Identity.class);
    final SocialTokens tokens=mock(SocialTokens.class);
    final SocialTokens.Profile profile=new SocialTokens.Profile("GOOGLE","person","learner@example.test","Learner");
    MockMvc mvc;

    @BeforeEach void setup() {
        var cors=new CorsConfiguration();
        cors.setAllowedOrigins(List.of("http://localhost:8091"));
        mvc=MockMvcBuilders.standaloneSetup(new AuthApi(identity,tokens,new Limits(Clock.systemUTC()),false,cors))
            .setControllerAdvice(new Errors()).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        when(tokens.verify("GOOGLE","fixture-token")).thenReturn(profile);
        when(identity.social(profile,"",null)).thenReturn(new Identity.Session(UUID.randomUUID(),"Learner","learner@example.test","fixture-access","fixture-refresh"));
    }

    @Test void browserSocialLoginUsesTheExistingHttpOnlySessionCookie() throws Exception {
        mvc.perform(post("/api/v1/auth/google").header("Origin","http://localhost:8091").header("X-Veloce-Client","web")
            .contentType("application/json").content("{\"idToken\":\"fixture-token\",\"email\":\"spoof@example.test\"}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(cookie().httpOnly("veloce_refresh",true)).andExpect(jsonPath("$.refreshToken").doesNotExist())
            .andExpect(jsonPath("$.email").value("learner@example.test"));
        verify(identity).social(profile,"",null);
    }

    @Test void nativeSocialLoginReceivesRefreshTokenAndCrossOriginLoginIsRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/google").header("X-Veloce-Client","native").contentType("application/json")
            .content("{\"idToken\":\"fixture-token\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.refreshToken").value("fixture-refresh"));
        mvc.perform(post("/api/v1/auth/google").header("Origin","https://untrusted.example").header("X-Veloce-Client","web")
            .contentType("application/json").content("{\"idToken\":\"fixture-token\"}"))
            .andExpect(status().isForbidden());
    }

    @Test void connectingAProviderRequiresAnExistingAuthenticatedSession() throws Exception {
        mvc.perform(post("/api/v1/auth/google").header("X-Veloce-Client","native").contentType("application/json")
            .content("{\"idToken\":\"fixture-token\",\"link\":true}"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(tokens,identity);
    }

    @Test void customGoogleWebButtonUsesTheSameSessionAndChecksOriginBeforeTheProvider() throws Exception {
        when(tokens.verifyGoogleAccessToken("fixture-web-token")).thenReturn(profile);
        mvc.perform(post("/api/v1/auth/google").header("Origin","http://localhost:8091").header("X-Veloce-Client","web")
            .contentType("application/json").content("{\"accessToken\":\"fixture-web-token\"}"))
            .andExpect(status().isOk()).andExpect(cookie().httpOnly("veloce_refresh",true))
            .andExpect(jsonPath("$.refreshToken").doesNotExist()).andExpect(jsonPath("$.email").value("learner@example.test"));
        verify(tokens).verifyGoogleAccessToken("fixture-web-token");
        verify(tokens,never()).verify(anyString(),anyString());
        clearInvocations(tokens,identity);
        mvc.perform(post("/api/v1/auth/google").header("Origin","https://untrusted.example").header("X-Veloce-Client","web")
            .contentType("application/json").content("{\"accessToken\":\"fixture-web-token\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(tokens,identity);
    }

    @Test void googleRejectsMixedOrMissingCredentialsBeforeVerification() throws Exception {
        for(String content:List.of("{}","{\"idToken\":\"fixture-token\",\"accessToken\":\"fixture-web-token\"}"))
            mvc.perform(post("/api/v1/auth/google").header("Origin","http://localhost:8091").header("X-Veloce-Client","web")
                .contentType("application/json").content(content)).andExpect(status().isBadRequest());
        verifyNoInteractions(tokens,identity);
    }
}
