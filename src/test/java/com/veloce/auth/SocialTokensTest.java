package com.veloce.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.veloce.common.*;
import io.jsonwebtoken.Jwts;
import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SocialTokensTest {
    final Json json=new Json(new ObjectMapper());
    final Clock clock=Clock.fixed(Instant.parse("2026-11-19T00:00:00Z"),ZoneOffset.UTC);
    final String client="fixture-client.apps.googleusercontent.com";
    static final KeyPair signing=keyPair();

    static KeyPair keyPair() {
        try {
            var generator=KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch(GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    @SuppressWarnings("unchecked")
    HttpResponse<InputStream> response(int status,Object body) {
        HttpResponse<InputStream> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(new ByteArrayInputStream(json.write(body).getBytes(StandardCharsets.UTF_8)));
        return response;
    }

    Object keys() {
        var key=(RSAPublicKey)signing.getPublic();
        var encoder=Base64.getUrlEncoder().withoutPadding();
        return Map.of("keys",List.of(Map.of("kid","fixture","kty","RSA","alg","RS256",
            "n",encoder.encodeToString(key.getModulus().toByteArray()),"e",encoder.encodeToString(key.getPublicExponent().toByteArray()))));
    }

    String google(String issuer,String audience,boolean verified,Instant expiry) {
        return Jwts.builder().header().keyId("fixture").and().issuer(issuer).subject("google-person")
            .audience().add(audience).and().claim("email","Learner@Example.test").claim("email_verified",verified)
            .claim("name","Learner").issuedAt(Date.from(clock.instant().minusSeconds(10)))
            .expiration(expiry==null?null:Date.from(expiry)).signWith(signing.getPrivate()).compact();
    }

    SocialTokens verifier(SocialTokens.Transport transport) {
        return new SocialTokens(json,clock,client,client,"123456","fixture-server-secret","v26.0",transport);
    }

    @Test void googleRequiresProviderSignatureIssuerAudienceExpiryAndVerifiedEmail() {
        var requests=new AtomicInteger();
        var verifier=verifier(request->{
            requests.incrementAndGet();
            assertThat(request.uri().toString()).isEqualTo("https://www.googleapis.com/oauth2/v3/certs");
            assertThat(request.headers().firstValue("Authorization")).isEmpty();
            return response(200,keys());
        });
        var valid=google("https://accounts.google.com",client,true,clock.instant().plusSeconds(600));
        var profile=verifier.verify("GOOGLE",valid);
        assertThat(profile.email()).isEqualTo("learner@example.test");
        assertThat(profile.subject()).isEqualTo("google-person");
        for(String token:List.of(
            google("https://malicious.example",client,true,clock.instant().plusSeconds(600)),
            google("https://accounts.google.com","other-client",true,clock.instant().plusSeconds(600)),
            google("https://accounts.google.com",client,false,clock.instant().plusSeconds(600)),
            google("https://accounts.google.com",client,true,clock.instant().minusSeconds(90)),
            google("https://accounts.google.com",client,true,null))) {
            assertThatThrownBy(()->verifier.verify("GOOGLE",token)).isInstanceOfSatisfying(Problem.class,
                error->assertThat(error.code).isEqualTo("SOCIAL_TOKEN_INVALID"));
        }
        assertThat(requests.get()).isEqualTo(1);
        String forged=Jwts.builder().header().keyId("fixture").and().subject("google-person")
            .issuer("https://accounts.google.com").audience().add(client).and()
            .claim("email","learner@example.test").claim("email_verified",true)
            .issuedAt(Date.from(clock.instant())).expiration(Date.from(clock.instant().plusSeconds(600)))
            .signWith(keyPair().getPrivate()).compact();
        assertThatThrownBy(()->verifier.verify("GOOGLE",forged)).isInstanceOf(Problem.class);
    }

    @Test void forgedAlgorithmIsRejectedBeforeAnyProviderRequestAndSecretsStayPrivate() {
        var verifier=verifier(request->{throw new AssertionError("Untrusted token must not trigger a request");});
        String header=Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\",\"kid\":\"fixture\"}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(()->verifier.verify("GOOGLE",header+".e30.signature")).isInstanceOf(Problem.class);
        assertThat(json.write(verifier.configuration())).doesNotContain("fixture-server-secret");
    }

    Map<String,Object> googleGrant() {
        var grant=new HashMap<String,Object>();
        grant.put("aud",client); grant.put("azp",client);
        grant.put("sub","google-person"); grant.put("email","Learner@Example.test");
        grant.put("email_verified","true");
        grant.put("exp",Long.toString(clock.instant().plusSeconds(600).getEpochSecond()));
        grant.put("expires_in","600");
        grant.put("scope","openid https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile");
        return grant;
    }

    @Test void googleWebGrantIsBoundToVeloceAndMatchedToTheVerifiedProfile() {
        var requests=new AtomicInteger();
        var verifier=verifier(request->{
            if(requests.getAndIncrement()==0) {
                assertThat(request.uri().toString()).isEqualTo("https://oauth2.googleapis.com/tokeninfo?access_token=fixture-web-token");
                assertThat(request.headers().firstValue("Authorization")).isEmpty();
                return response(200,googleGrant());
            }
            assertThat(request.uri().toString()).isEqualTo("https://openidconnect.googleapis.com/v1/userinfo");
            assertThat(request.headers().firstValue("Authorization")).contains("Bearer fixture-web-token");
            return response(200,Map.of("sub","google-person","email","learner@example.test","email_verified",true,"name","Learner"));
        });
        assertThat(verifier.verifyGoogleAccessToken("fixture-web-token"))
            .isEqualTo(new SocialTokens.Profile("GOOGLE","google-person","learner@example.test","Learner"));
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test void googleWebRejectsAnotherClientExpiredGrantsUnverifiedEmailAndMissingScopes() {
        for(var change:List.of(
            Map.entry("aud","other.apps.googleusercontent.com"),Map.entry("azp","other.apps.googleusercontent.com"),
            Map.entry("exp","0"),Map.entry("exp","not-a-time"),Map.entry("expires_in","0"),
            Map.entry("email_verified","false"),Map.entry("scope","openid"),Map.entry("scope","email profile"),
            Map.entry("sub",""),Map.entry("email",""))) {
            var grant=googleGrant(); grant.put(change.getKey(),change.getValue());
            var verifier=verifier(request->{
                assertThat(request.uri().getHost()).isEqualTo("oauth2.googleapis.com");
                return response(200,grant);
            });
            assertThatThrownBy(()->verifier.verifyGoogleAccessToken("fixture-web-token"))
                .isInstanceOfSatisfying(Problem.class,error->assertThat(error.status).isEqualTo(401));
        }
    }

    @Test void googleWebRejectsProfileSubstitutionAndProviderFailures() {
        for(var detail:List.of(
            Map.of("sub","another-person","email","learner@example.test","email_verified",true),
            Map.of("sub","google-person","email","another@example.test","email_verified",true),
            Map.of("sub","google-person","email","learner@example.test","email_verified",false))) {
            var verifier=verifier(request->response(200,
                request.uri().getHost().equals("oauth2.googleapis.com")?googleGrant():detail));
            assertThatThrownBy(()->verifier.verifyGoogleAccessToken("fixture-web-token"))
                .isInstanceOfSatisfying(Problem.class,error->assertThat(error.status).isEqualTo(401));
        }
        for(int status:List.of(400,401,403,503)) {
            var verifier=verifier(request->response(status,Map.of("error","provider-error")));
            assertThatThrownBy(()->verifier.verifyGoogleAccessToken("fixture-web-token"))
                .isInstanceOfSatisfying(Problem.class,error->assertThat(error.status).isEqualTo(status==503?503:401));
        }
        var verifier=verifier(request->{throw new AssertionError("Invalid credentials must not be sent to Google");});
        for(String token:List.of(""," ","x".repeat(16385)))
            assertThatThrownBy(()->verifier.verifyGoogleAccessToken(token)).isInstanceOf(Problem.class);
    }

    @Test void facebookChecksAppExpiryUserIdentityAndUsesBackendSecretOnlyWithMeta() {
        var requests=new AtomicInteger();
        var verifier=verifier(request->{
            assertThat(request.uri().getHost()).isEqualTo("graph.facebook.com");
            assertThat(request.uri().toString()).doesNotContain("fixture-server-secret");
            if(requests.getAndIncrement()==0) {
                assertThat(request.headers().firstValue("Authorization")).contains("Bearer 123456|fixture-server-secret");
                return response(200,Map.of("data",Map.of("is_valid",true,"type","USER","app_id","123456",
                    "user_id","facebook-person","expires_at",clock.instant().plusSeconds(600).getEpochSecond())));
            }
            assertThat(request.headers().firstValue("Authorization")).contains("Bearer fixture-user-token");
            assertThat(request.uri().getQuery()).contains("appsecret_proof=");
            return response(200,Map.of("id","facebook-person","email","Learner@Example.test","name","Learner"));
        });
        assertThat(verifier.verify("FACEBOOK","fixture-user-token").email()).isEqualTo("learner@example.test");
        assertThat(requests.get()).isEqualTo(2);
        for(var data:List.of(
            Map.of("is_valid",false,"type","USER","app_id","123456","user_id","person","expires_at",clock.instant().plusSeconds(600).getEpochSecond()),
            Map.of("is_valid",true,"type","USER","app_id","wrong-app","user_id","person","expires_at",clock.instant().plusSeconds(600).getEpochSecond()),
            Map.of("is_valid",true,"type","USER","app_id","123456","user_id","person","expires_at",clock.instant().minusSeconds(1).getEpochSecond()))) {
            var invalid=verifier(request->response(200,Map.of("data",data)));
            assertThatThrownBy(()->invalid.verify("FACEBOOK","fixture-user-token"))
                .isInstanceOfSatisfying(Problem.class,error->assertThat(error.status).isEqualTo(401));
        }
    }

    @Test void facebookEmailAndProfileMustMatchTheInspectedToken() {
        for(boolean emailPresent:List.of(false,true)) {
            var verifier=verifier(request->{
                if(request.uri().getPath().endsWith("debug_token"))
                    return response(200,Map.of("data",Map.of("is_valid",true,"type","USER","app_id","123456",
                        "user_id","person","expires_at",clock.instant().plusSeconds(600).getEpochSecond())));
                return response(200,Map.of("id",emailPresent?"another-person":"person","email",emailPresent?"learner@example.test":""));
            });
            assertThatThrownBy(()->verifier.verify("FACEBOOK","fixture-user-token")).isInstanceOf(Problem.class);
        }
    }
}
