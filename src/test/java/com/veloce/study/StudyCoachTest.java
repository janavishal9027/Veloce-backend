package com.veloce.study;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.veloce.common.*;
import com.veloce.workspace.WorkspaceStore;
import java.io.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import javax.net.ssl.SSLSession;

class StudyCoachTest {
    final Json json=new Json(new ObjectMapper());
    final StudyStore store=new StudyStore(new WorkspaceStore(new JdbcTemplate(),json),Clock.systemUTC());
    boolean credentialAvailable=true;
    final StudyPreferences preferences=new StudyPreferences(store,"test-only-encryption-key") {
        @Override public String credential(UUID w,String provider) {
            if (!credentialAvailable) throw new Problem(412,"PROVIDER_KEY_REQUIRED","Save a key first.");
            return "test-key-only";
        }
    };
    final UUID workspace=UUID.randomUUID();
    final String grade="{\"question\":\"How would you validate this?\",\"feedback\":\"Clear reasoning\",\"improvement\":\"Describe a boundary case\",\"correctness\":3,\"reasoning\":3,\"clarity\":4,\"coverage\":2}";
    HttpResponse<InputStream> response(int status,String body) {
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public InputStream body() { return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
            public HttpRequest request() { return null; }
            public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(Map.of(),(a,b)->true); }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return URI.create("https://example.test"); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
    String body(HttpRequest request) {
        var output=new ByteArrayOutputStream();var done=new CompletableFuture<Void>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) { byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);output.writeBytes(bytes); }
            public void onError(Throwable error) { done.completeExceptionally(error); }
            public void onComplete() { done.complete(null); }
        });
        done.join();return output.toString(StandardCharsets.UTF_8);
    }
    @Test void adaptersParseEachProviderResponseAndKeepCredentialsOutOfPrompts() {
        for (String provider:List.of("OPENAI","OPENROUTER","GEMINI")) {
            var encoded=json.mapper.valueToTree(grade).toString();
            String raw=switch(provider) {
                case "OPENAI" -> "{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":"+encoded+"}]}]}";
                case "OPENROUTER" -> "{\"choices\":[{\"message\":{\"content\":"+encoded+"}}]}";
                default -> "{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"not answer\"},{\"text\":"+encoded+"}]}}]}";
            };
            var coach=new StudyCoach(store,preferences,request->{
                assertThat(request.method()).isEqualTo("POST");
                assertThat(request.uri().getScheme()).isEqualTo("https");
                assertThat(request.uri().getHost()).isEqualTo(switch(provider) {
                    case "OPENAI" -> "api.openai.com";
                    case "OPENROUTER" -> "openrouter.ai";
                    default -> "generativelanguage.googleapis.com";
                });
                assertThat(request.timeout()).contains(Duration.ofSeconds(45));
                assertThat(request.headers().firstValue(provider.equals("GEMINI")?"x-goog-api-key":"Authorization"))
                    .contains(provider.equals("GEMINI")?"test-key-only":"Bearer test-key-only");
                var prompt=body(request);
                assertThat(prompt).contains("Research synthesis").doesNotContain("test-key-only");
                if (provider.equals("OPENAI")) assertThat(json.read(prompt).path("store").asBoolean()).isFalse();
                return response(200,raw);
            });
            var result=coach.turn(workspace,provider,"test-model",json.object().put("topic","Research synthesis"),"ORAL");
            assertThat(result.path("question").asText()).isEqualTo("How would you validate this?");
            assertThat(result.path("coverage").asInt()).isEqualTo(2);
        }
    }
    @Test void unusableResponsesStayBoundedAndDoNotExposeProviderMessages() {
        for (String raw:List.of("not JSON","x".repeat(131073),"{\"status\":\"incomplete\"}")) {
            var coach=new StudyCoach(store,preferences,request->response(200,raw));
            assertThatThrownBy(()->coach.turn(workspace,"OPENAI","test-model",json.object(),"ORAL"))
                .isInstanceOfSatisfying(Problem.class,error->{
                    assertThat(error.status).isEqualTo(502);
                    assertThat(error.getMessage()).doesNotContain("test-key-only");
                });
        }
        var coach=new StudyCoach(store,preferences,request->response(429,"PRIVATE PROVIDER MESSAGE test-key-only"));
        assertThatThrownBy(()->coach.turn(workspace,"OPENAI","test-model",json.object(),"ORAL"))
            .isInstanceOfSatisfying(Problem.class,error->assertThat(error.getMessage()).contains("HTTP 429").doesNotContain("PRIVATE PROVIDER MESSAGE","test-key-only"));
    }
    @Test void missingCredentialPreventsNetworkRequest() {
        credentialAvailable=false;
        var coach=new StudyCoach(store,preferences,request->{throw new AssertionError("No request without a key");});
        assertThatThrownBy(()->coach.turn(workspace,"OPENAI","test-model",json.object(),"ORAL"))
            .isInstanceOfSatisfying(Problem.class,error->assertThat(error.status).isEqualTo(412));
    }
}
