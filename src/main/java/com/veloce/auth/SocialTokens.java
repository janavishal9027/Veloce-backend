package com.veloce.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloce.common.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Validates the provider-issued credential before Veloce creates or links a session. */
@Component
class SocialTokens {
    record Profile(String provider,String subject,String email,String name) {}
    interface Transport { HttpResponse<InputStream> send(HttpRequest request) throws IOException,InterruptedException; }

    private final Json json;
    private final Clock clock;
    private final String googleWebClient,facebookApp,facebookSecret,facebookVersion;
    private final Set<String> googleClients;
    private final Transport transport;
    private Map<String,RSAPublicKey> keys=Map.of();
    private Instant keysExpire=Instant.EPOCH,nextKeyRefresh=Instant.EPOCH;

    @org.springframework.beans.factory.annotation.Autowired
    SocialTokens(Json json,Clock clock,
        @Value("${veloce.auth.google-web-client-id:}") String googleWebClient,
        @Value("${veloce.auth.google-client-ids:}") String googleClients,
        @Value("${veloce.auth.facebook-app-id:}") String facebookApp,
        @Value("${veloce.auth.facebook-app-secret:}") String facebookSecret,
        @Value("${veloce.auth.facebook-graph-version:v26.0}") String facebookVersion) {
        this(json,clock,googleWebClient,googleClients,facebookApp,facebookSecret,facebookVersion,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    private SocialTokens(Json json,Clock clock,String googleWebClient,String googleClients,String facebookApp,
        String facebookSecret,String facebookVersion,HttpClient client) {
        this(json,clock,googleWebClient,googleClients,facebookApp,facebookSecret,facebookVersion,
            request->client.send(request,HttpResponse.BodyHandlers.ofInputStream()));
    }

    SocialTokens(Json json,Clock clock,String googleWebClient,String googleClients,String facebookApp,
        String facebookSecret,String facebookVersion,Transport transport) {
        this.json=json;
        this.clock=clock;
        this.googleWebClient=googleWebClient.strip();
        var clients=new HashSet<String>();
        for(String client:(googleClients+","+googleWebClient).split(","))
            if(client.strip().matches("[A-Za-z0-9.-]+\\.apps\\.googleusercontent\\.com")) clients.add(client.strip());
        this.googleClients=Set.copyOf(clients);
        this.facebookApp=facebookApp.strip();
        this.facebookSecret=facebookSecret.strip();
        if(!facebookVersion.matches("v[0-9]{1,2}\\.[0-9]")) throw new IllegalArgumentException("Choose a valid Facebook Graph version.");
        this.facebookVersion=facebookVersion;
        this.transport=transport;
    }

    Map<String,Object> configuration() {
        return Map.of("googleEnabled",!googleWebClient.isBlank()&&googleClients.contains(googleWebClient),
            "googleClientId",googleWebClient,"facebookEnabled",facebookApp.matches("[0-9]+")&&!facebookSecret.isBlank(),
            "facebookAppId",facebookApp,"facebookGraphVersion",facebookVersion);
    }

    Profile verify(String provider,String token) {
        if(token.isBlank()||token.length()>16384) throw invalid();
        return switch(provider) {
            case "GOOGLE" -> google(token);
            case "FACEBOOK" -> facebook(token);
            default -> throw Problem.invalid("Choose Google or Facebook sign-in.");
        };
    }

    private Profile google(String token) {
        if(googleClients.isEmpty()) throw setup("Google");
        try {
            var parts=token.split("\\.");
            if(parts.length!=3||parts[0].length()>2048) throw invalid();
            var header=json.mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if(!header.path("alg").asText().equals("RS256")) throw invalid();
            String kid=header.path("kid").asText();
            if(kid.isBlank()||kid.length()>200) throw invalid();
            var publicKey=key(kid);
            var claims=Jwts.parser().verifyWith(publicKey).clock(()->Date.from(clock.instant()))
                .clockSkewSeconds(60).build().parseSignedClaims(token).getPayload();
            if(!("accounts.google.com".equals(claims.getIssuer())||"https://accounts.google.com".equals(claims.getIssuer()))
                ||claims.getAudience()==null||claims.getAudience().stream().noneMatch(googleClients::contains)
                ||claims.getExpiration()==null||claims.getIssuedAt()==null
                ||claims.getIssuedAt().toInstant().isAfter(clock.instant().plusSeconds(60))
                ||!Boolean.TRUE.equals(claims.get("email_verified"))) throw invalid();
            String authorizedParty=claims.get("azp",String.class);
            if(claims.getAudience().size()>1&&(authorizedParty==null||!googleClients.contains(authorizedParty))) throw invalid();
            return profile("GOOGLE",claims.getSubject(),claims.get("email",String.class),claims.get("name",String.class));
        } catch(Problem e) {
            throw e;
        } catch(IOException|JwtException|IllegalArgumentException e) {
            throw invalid();
        }
    }

    Profile verifyGoogleAccessToken(String token) {
        if(googleWebClient.isBlank()||!googleClients.contains(googleWebClient)) throw setup("Google");
        if(token==null||token.isBlank()||token.length()>16384) throw invalid();
        // Token inspection binds this grant to Veloce, rather than accepting a
        // Google access token issued to an unrelated application.
        var grant=get("https://oauth2.googleapis.com/tokeninfo?access_token="
            +URLEncoder.encode(token,StandardCharsets.UTF_8),null);
        var scopes=new HashSet<>(Arrays.asList(grant.path("scope").asText().strip().split("\\s+")));
        if(!googleWebClient.equals(grant.path("aud").asText())
            ||!googleWebClient.equals(grant.path("azp").asText())
            ||grant.path("exp").asLong(-1)<=clock.instant().getEpochSecond()
            ||grant.path("expires_in").asLong(-1)<=0
            ||grant.path("sub").asText().isBlank()||grant.path("email").asText().isBlank()
            ||!scopes.contains("openid")
            ||!(scopes.contains("email")||scopes.contains("https://www.googleapis.com/auth/userinfo.email"))
            ||!grant.path("email_verified").asBoolean(false)) throw invalid();
        var details=get("https://openidconnect.googleapis.com/v1/userinfo",token);
        if(!details.path("email_verified").asBoolean(false)
            ||!grant.path("sub").asText().equals(details.path("sub").asText())
            ||!grant.path("email").asText().strip().equalsIgnoreCase(details.path("email").asText().strip()))
            throw invalid();
        return profile("GOOGLE",details.path("sub").asText(),details.path("email").asText(),details.path("name").asText());
    }

    private synchronized RSAPublicKey key(String kid) {
        Instant now=clock.instant();
        if((!keysExpire.isAfter(now)||!keys.containsKey(kid))&&!nextKeyRefresh.isAfter(now)) {
            nextKeyRefresh=now.plusSeconds(30);
            var document=get("https://www.googleapis.com/oauth2/v3/certs",null);
            var fresh=new HashMap<String,RSAPublicKey>();
            try {
                for(var item:document.path("keys")) {
                    if(!item.path("kty").asText().equals("RSA")||!item.path("alg").asText().equals("RS256")) continue;
                    var modulus=new BigInteger(1,Base64.getUrlDecoder().decode(item.path("n").asText()));
                    var exponent=new BigInteger(1,Base64.getUrlDecoder().decode(item.path("e").asText()));
                    if(modulus.bitLength()<2048) continue;
                    var spec=new RSAPublicKeySpec(modulus,exponent);
                    fresh.put(item.path("kid").asText(),(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(spec));
                }
            } catch(GeneralSecurityException|IllegalArgumentException e) {
                throw unavailable();
            }
            if(fresh.isEmpty()) throw unavailable();
            keys=Map.copyOf(fresh);
            keysExpire=now.plusSeconds(3600);
        }
        if(!keysExpire.isAfter(now)) throw unavailable();
        var key=keys.get(kid);
        if(key==null) throw invalid();
        return key;
    }

    private Profile facebook(String token) {
        if(facebookApp.isBlank()||facebookSecret.isBlank()) throw setup("Facebook");
        String base="https://graph.facebook.com/"+facebookVersion;
        var data=get(base+"/debug_token?input_token="+URLEncoder.encode(token,StandardCharsets.UTF_8),facebookApp+"|"+facebookSecret).path("data");
        String subject=data.path("user_id").asText();
        long now=clock.instant().getEpochSecond();
        if(!data.path("is_valid").asBoolean()||!data.path("app_id").asText().equals(facebookApp)
            ||!data.path("type").asText().equals("USER")||subject.isBlank()
            ||data.path("expires_at").asLong()<=now
            ||data.hasNonNull("data_access_expires_at")&&data.path("data_access_expires_at").asLong()>0&&data.path("data_access_expires_at").asLong()<=now)
            throw invalid();
        String proof;
        try {
            var mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(facebookSecret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            proof=HexFormat.of().formatHex(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch(GeneralSecurityException e) { throw new IllegalStateException(e); }
        var details=get(base+"/me?fields=id,name,email&appsecret_proof="+proof,token);
        if(!subject.equals(details.path("id").asText())) throw invalid();
        if(details.path("email").asText().isBlank())
            throw new Problem(400,"SOCIAL_EMAIL_REQUIRED","Facebook did not share an email address. Allow email access or choose Google or email sign-in.");
        return profile("FACEBOOK",subject,details.path("email").asText(),details.path("name").asText());
    }

    private Profile profile(String provider,String subject,String email,String name) {
        if(subject==null||subject.isBlank()||subject.length()>255||email==null) throw invalid();
        email=email.strip().toLowerCase(Locale.ROOT);
        if(email.length()>254||!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw invalid();
        if(name==null||name.isBlank()) name=email.substring(0,email.indexOf('@'));
        name=name.strip().replaceAll("[\\p{Cntrl}]","");
        if(name.isBlank()) name="Veloce member";
        if(name.length()>80) name=name.substring(0,80);
        return new Profile(provider,subject,email,name);
    }

    private JsonNode get(String endpoint,String bearer) {
        var builder=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(8)).GET();
        if(bearer!=null) builder.header("Authorization","Bearer "+bearer);
        try {
            var response=transport.send(builder.build());
            try(var body=response.body()) {
                byte[] bytes=body.readNBytes(131073);
                if(bytes.length>131072) throw unavailable();
                if(response.statusCode()==400||response.statusCode()==401||response.statusCode()==403) throw invalid();
                if(response.statusCode()!=200) throw unavailable();
                var value=json.mapper.readTree(bytes);
                if(value==null||value.hasNonNull("error")) throw invalid();
                return value;
            }
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch(IOException|IllegalArgumentException e) {
            throw unavailable();
        }
    }

    private Problem setup(String name) { return new Problem(503,"SOCIAL_SETUP_REQUIRED",name+" sign-in needs the server's provider configuration."); }
    private Problem invalid() { return new Problem(401,"SOCIAL_TOKEN_INVALID","The provider identity could not be verified. Try signing in again."); }
    private Problem unavailable() { return new Problem(503,"SOCIAL_UNAVAILABLE","The sign-in provider could not be reached. Try again shortly."); }
}
