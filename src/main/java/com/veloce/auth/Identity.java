package com.veloce.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloce.common.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.cors.CorsConfiguration;

@Service
class Identity {
    record Session(UUID id, String name, String email, String accessToken, String refreshToken) {}
    private final Accounts accounts;
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final SecretKey key;
    private final String dummy;
    private final SecureRandom random = new SecureRandom();

    Identity(Accounts accounts, JdbcTemplate db, PasswordEncoder passwords, Clock clock,
             @Value("${veloce.jwt-secret}") String secret) {
        this.accounts=accounts; this.db=db; this.passwords=passwords; this.clock=clock;
        try { key=Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret)); }
        catch (Exception e) { throw new IllegalArgumentException("VELOCE_JWT_SECRET must be base64 for at least 32 random bytes."); }
        dummy = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    Session register(String email, String name, String password) {
        if (password.length() < 10) throw Problem.invalid("Use a password of at least 10 characters.");
        lock("email:"+email);
        try {
            return issue(accounts.saveAndFlush(new Account(UUID.randomUUID(), email, name, passwords.encode(password))));
        } catch (DataIntegrityViolationException e) {
            throw Problem.conflict("An account already uses this email.");
        }
    }

    @Transactional
    Session login(String email, String password) {
        Account account = accounts.findByEmail(email).orElse(null);
        boolean valid = passwords.matches(password, account == null ? dummy : account.passwordHash);
        if (account == null || !valid) throw new Problem(401,"INVALID_CREDENTIALS","The email or password is incorrect.");
        return issue(account);
    }

    @Transactional
    Session social(SocialTokens.Profile profile,String password,UUID linkingOwner) {
        lock("social:"+profile.provider()+":"+profile.subject());
        var linked=db.query("SELECT account_id FROM social_identities WHERE provider=? AND subject=?",
            (row,index)->row.getObject(1,UUID.class),profile.provider(),profile.subject());
        if(!linked.isEmpty()) {
            UUID id=linked.getFirst();
            if(linkingOwner!=null&&!linkingOwner.equals(id)) throw linkConflict();
            return issue(accounts.findById(id).orElseThrow(Problem::unauthorized));
        }
        lock("email:"+profile.email());
        Account existing=accounts.findByEmail(profile.email()).orElse(null);
        Account account;
        if(linkingOwner!=null) {
            if(existing!=null&&!existing.id.equals(linkingOwner)) throw linkConflict();
            account=accounts.findById(linkingOwner).orElseThrow(Problem::unauthorized);
        } else if(existing!=null) {
            if(password.isEmpty()) throw new Problem(409,"SOCIAL_LINK_REQUIRED",
                "This email has a Veloce account. Confirm its Veloce password once to connect this sign-in method. You can also connect it from your signed-in profile.");
            if(!passwords.matches(password,existing.passwordHash))
                throw new Problem(401,"INVALID_LINK_PASSWORD","The Veloce password is incorrect. Try again.");
            account=existing;
        } else {
            UUID id=UUID.randomUUID();
            String hash=passwords.encode(UUID.randomUUID().toString()+UUID.randomUUID());
            int added=db.update("""
                INSERT INTO accounts(id,email,display_name,password_hash) VALUES(?,?,?,?)
                ON CONFLICT(email) DO NOTHING
                """,id,profile.email(),profile.name(),hash);
            if(added!=1) throw Problem.conflict("This email was registered in another view. Sign in to connect your provider.");
            account=accounts.findById(id).orElseThrow(Problem::unauthorized);
        }
        db.query("SELECT id FROM accounts WHERE id=? FOR UPDATE",row->{},account.id);
        var providers=db.queryForList("SELECT subject FROM social_identities WHERE provider=? AND account_id=?",
            String.class,profile.provider(),account.id);
        if(!providers.isEmpty()) throw linkConflict();
        db.update("INSERT INTO social_identities(provider,subject,account_id) VALUES(?,?,?)",
            profile.provider(),profile.subject(),account.id);
        return issue(account);
    }

    Map<String,Boolean> linked(UUID owner) {
        var linked=db.queryForList("SELECT provider FROM social_identities WHERE account_id=?",String.class,owner);
        return Map.of("GOOGLE",linked.contains("GOOGLE"),"FACEBOOK",linked.contains("FACEBOOK"));
    }

    private void lock(String key) {
        db.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",row->{},"veloce-auth/"+key);
    }

    private Problem linkConflict() {
        return new Problem(409,"SOCIAL_ALREADY_LINKED","This provider account is already connected. Use the connected account or another sign-in method.");
    }

    @Transactional
    Session refresh(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw Problem.unauthorized();
        var ids = db.query("DELETE FROM refresh_tokens WHERE token_hash=? AND expires_at>now() RETURNING account_id",
            (r,n) -> r.getObject(1, UUID.class), Json.sha256(token));
        if (ids.isEmpty()) throw Problem.unauthorized();
        return issue(accounts.findById(ids.getFirst()).orElseThrow(Problem::unauthorized));
    }

    void revoke(String token) {
        if (token != null && token.length() == 43) db.update("DELETE FROM refresh_tokens WHERE token_hash=?", Json.sha256(token));
    }

    UUID subject(String token) {
        return UUID.fromString(Jwts.parser().requireIssuer("veloce").verifyWith(key)
            .clock(() -> Date.from(clock.instant())).build().parseSignedClaims(token).getPayload().getSubject());
    }

    private Session issue(Account account) {
        Instant now = clock.instant();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("INSERT INTO refresh_tokens(token_hash,account_id,expires_at) VALUES(?,?,?)",
            Json.sha256(refresh), account.id, OffsetDateTime.ofInstant(now.plus(Duration.ofDays(14)), ZoneOffset.UTC));
        String access = Jwts.builder().issuer("veloce").subject(account.id.toString())
            .issuedAt(Date.from(now)).expiration(Date.from(now.plus(Duration.ofMinutes(15))))
            .id(UUID.randomUUID().toString()).signWith(key).compact();
        return new Session(account.id, account.displayName, account.email, access, refresh);
    }
}

@RestController
@RequestMapping("/api/v1/auth")
class AuthApi {
    static final String COOKIE = "veloce_refresh";
    private final Identity identity;
    private final SocialTokens social;
    private final Limits limits;
    private final boolean secureCookie;
    private final CorsConfiguration browserCors;

    AuthApi(Identity identity, SocialTokens social, Limits limits, @Value("${veloce.secure-cookie}") boolean secureCookie,
            CorsConfiguration browserCors) {
        this.identity=identity; this.social=social; this.limits=limits; this.secureCookie=secureCookie; this.browserCors=browserCors;
    }

    @GetMapping("/providers")
    Map<String,Object> providers() { return social.configuration(); }

    @GetMapping("/linked-providers")
    Map<String,Boolean> linked(@AuthenticationPrincipal UUID owner) { return identity.linked(owner); }

    @PostMapping("/google")
    ResponseEntity<?> google(@RequestBody JsonNode data,HttpServletRequest request,@AuthenticationPrincipal UUID owner) {
        boolean accessToken=data.hasNonNull("accessToken");
        if(accessToken==data.hasNonNull("idToken")) throw Problem.invalid("Provide one Google sign-in credential.");
        return provider("GOOGLE",Json.text(data,accessToken?"accessToken":"idToken",16384,true),data,request,owner,accessToken);
    }

    @PostMapping("/facebook")
    ResponseEntity<?> facebook(@RequestBody JsonNode data,HttpServletRequest request,@AuthenticationPrincipal UUID owner) {
        return provider("FACEBOOK",Json.text(data,"accessToken",16384,true),data,request,owner,false);
    }

    private ResponseEntity<?> provider(String provider,String token,JsonNode data,HttpServletRequest request,UUID owner,boolean googleAccessToken) {
        limits.check("social-login:"+request.getRemoteAddr(),40,300);
        boolean browser=web(request);
        if(browser) checkOrigin(request);
        boolean link=data.path("link").asBoolean(false);
        if(link&&owner==null) throw Problem.unauthorized();
        String confirmation=data.hasNonNull("password")?password(data):"";
        var profile=googleAccessToken?social.verifyGoogleAccessToken(token):social.verify(provider,token);
        return response(identity.social(profile,confirmation,link?owner:null),browser);
    }

    @PostMapping("/register")
    ResponseEntity<?> register(@RequestBody JsonNode data, HttpServletRequest req) {
        limits.check("register:"+req.getRemoteAddr(), 15, 600);
        return response(identity.register(email(data), Json.text(data,"name",80,true), password(data)), web(req));
    }
    @PostMapping("/login")
    ResponseEntity<?> login(@RequestBody JsonNode data, HttpServletRequest req) {
        String email = email(data);
        limits.check("login-ip:"+req.getRemoteAddr(), 40, 300);
        limits.check("login-email:"+email, 10, 300);
        return response(identity.login(email, password(data)), web(req));
    }
    @PostMapping("/refresh")
    ResponseEntity<?> refresh(@RequestBody(required=false) JsonNode data, HttpServletRequest req,
                              @CookieValue(value=COOKIE,required=false) String cookie) {
        limits.check("refresh:"+req.getRemoteAddr(), 100, 60);
        boolean web = web(req) || cookie != null;
        if (web) checkOrigin(req);
        return response(identity.refresh(web ? cookie : data == null ? null : Json.text(data,"refreshToken",100,false)), web);
    }
    @PostMapping("/logout")
    ResponseEntity<?> logout(@RequestBody(required=false) JsonNode data, HttpServletRequest req,
                             @CookieValue(value=COOKIE,required=false) String cookie) {
        boolean web = web(req) || cookie != null;
        if (web) checkOrigin(req);
        identity.revoke(web ? cookie : data == null ? null : Json.text(data,"refreshToken",100,false));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO)).build();
    }

    private ResponseEntity<?> response(Identity.Session session, boolean browser) {
        Map<String,Object> body = new HashMap<>(Map.of("id",session.id(),"name",session.name(),"email",session.email(),
            "accessToken",session.accessToken()));
        var result=ResponseEntity.ok().cacheControl(CacheControl.noStore());
        if (browser) result.header(HttpHeaders.SET_COOKIE, cookie(session.refreshToken(), Duration.ofDays(14)));
        else body.put("refreshToken",session.refreshToken());
        return result.body(body);
    }
    private String cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE, value).httpOnly(true).secure(secureCookie).sameSite("Strict")
            .path("/api/v1/auth").maxAge(maxAge).build().toString();
    }
    private boolean web(HttpServletRequest req) {
        return req.getHeader("Origin") != null || "web".equals(req.getHeader("X-Veloce-Client"));
    }
    private void checkOrigin(HttpServletRequest req) {
        if (!"web".equals(req.getHeader("X-Veloce-Client")) || browserCors.checkOrigin(req.getHeader("Origin")) == null)
            throw new Problem(403,"ORIGIN_REQUIRED","The browser origin could not be verified.");
    }
    private String email(JsonNode data) {
        String email=Json.text(data,"email",254,true).toLowerCase(Locale.ROOT);
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw Problem.invalid("Enter a valid email address.");
        return email;
    }
    private String password(JsonNode data) {
        JsonNode field=data.path("password");
        if (!field.isTextual() || field.asText().isBlank() || field.asText().indexOf(0)>=0
            || field.asText().getBytes(StandardCharsets.UTF_8).length>72)
            throw Problem.invalid("Use a password between 10 and 72 bytes.");
        return field.asText();
    }
}
