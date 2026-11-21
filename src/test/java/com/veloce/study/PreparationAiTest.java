package com.veloce.study;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloce.common.*;
import com.veloce.roadmap.Roadmap.Resource;
import com.veloce.workspace.WorkspaceStore;
import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreparationAiTest {
    final Json json=new Json(new ObjectMapper());
    final Clock clock=Clock.fixed(Instant.parse("2026-11-19T08:00:00Z"),ZoneOffset.UTC);
    final StudyStore store=new StudyStore(new WorkspaceStore(new JdbcTemplate(),json),clock);
    final ObjectNode settings=json.object();
    final UUID workspace=UUID.randomUUID(),owner=UUID.randomUUID();
    final StudyPreferences preferences=new StudyPreferences(store,"fixture-only-credential-key") {
        @Override public ObjectNode get(UUID id) { return settings.deepCopy(); }
        @Override public String credential(UUID id,String provider) { return "fixture-key-"+provider; }
    };
    final PreparationGuide.Chart outline=new PreparationGuide.Chart("SQL transactions","Practice safely",List.of("Database basics"),
        List.of(new PreparationGuide.Step("Rollback",30,List.of("Atomicity"),"Roll back a failed insert","Row is absent")),
        List.of(new Resource("PostgreSQL","https://www.postgresql.org/docs/")),30,30,"ROADMAP");
    final String chart="""
        {"title":"SQL chart","summary":"Test a transaction","prerequisites":["Database basics"],
         "steps":[{"title":"Transaction practice","minutes":30,"concepts":["Atomicity"],
         "instructions":"Insert, roll back, then verify the result.","deliverable":"The row is absent."}]}
        """;

    void enable(String... providers) {
        settings.setAll(preferences.defaults());
        settings.putObject("savedKeys");
        for(String id:providers) {
            ((ObjectNode)settings.path("savedKeys")).put(id,true);
            ((ObjectNode)settings.path("enhancement").path("providers").path(id))
                .put("enabled",true).put("freeTierConfirmed",true);
        }
    }

    @SuppressWarnings("unchecked")
    HttpResponse<InputStream> response(int code,String body,Map<String,List<String>> headers) {
        HttpResponse<InputStream> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(code);
        when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(response.headers()).thenReturn(HttpHeaders.of(headers,(a,b)->true));
        return response;
    }

    HttpResponse<InputStream> response(int code,String body) { return response(code,body,Map.of()); }

    String chat(String text) {
        return json.write(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",text)))));
    }

    String catalog(HttpRequest request) {
        return request.uri().getHost().equals("generativelanguage.googleapis.com")
            ? "{\"models\":[{\"name\":\"models/gemini-3.8-flash\",\"supportedGenerationMethods\":[\"generateContent\"]}]}"
            : "{\"data\":[{\"id\":\"openai/gpt-oss-120b\"},{\"id\":\"nvidia/nemotron-3-nano-30b-a3b\"}]}";
    }

    PreparationAi ai(EnhancementHttp.Transport transport) {
        var http=new EnhancementHttp(json,transport);
        return new PreparationAi(preferences,json,new FreeAiModels(http,clock),http,clock);
    }

    @Test void zeroPriceCatalogExcludesPaidMissingPriceAndNonTextModels() {
        var response=json.object();
        var rows=response.putArray("data");
        var row=rows.addObject().put("id","vendor/valid:free").put("context_length",16000);
        row.putObject("architecture").put("modality","text->text");
        row.putObject("pricing").put("prompt","0").put("completion","0").put("request","0");
        var paid=row.deepCopy().put("id","vendor/paid:free");
        ((ObjectNode)paid.path("pricing")).put("completion","0.0001");
        rows.add(paid);
        var unknown=row.deepCopy().put("id","vendor/unknown:free");
        ((ObjectNode)unknown.path("pricing")).remove("prompt");
        rows.add(unknown);
        var image=row.deepCopy().put("id","vendor/image:free");
        ((ObjectNode)image.path("architecture")).put("modality","text->image");
        rows.add(image);
        assertThat(FreeAiModels.openRouter(response).stream().map(FreeAiModels.Model::id))
            .containsExactlyInAnyOrder("openrouter/free","vendor/valid:free");
    }

    @Test void rateLimitedKeyCoolsDownAcrossRequestsAndFallsBackToAnotherProvider() {
        enable("OPENROUTER","GROQ");
        ((ObjectNode)settings.path("enhancement")).put("preferredProvider","OPENROUTER").put("preferredModel","openrouter/free");
        var calls=new AtomicInteger();
        var router=ai(request->{
            if(request.method().equals("GET")) return response(200,catalog(request));
            if(request.uri().getHost().equals("openrouter.ai")) {
                calls.incrementAndGet();
                var body=json.read(new StudyCoachTest().body(request));
                assertThat(body.path("provider").path("max_price").path("prompt").asInt(-1)).isZero();
                assertThat(body.path("provider").path("max_price").path("completion").asInt(-1)).isZero();
                return response(429,"PRIVATE ERROR fixture-key-OPENROUTER",Map.of("Retry-After",List.of("120")));
            }
            assertThat(request.uri().getHost()).isEqualTo("api.groq.com");
            return response(200,chat(chart));
        });
        var result=router.enhance(owner,workspace,outline,null);
        assertThat(result.generation().provider()).isEqualTo("GROQ");
        assertThat(result.generation().attempts()).isEqualTo(2);
        assertThat(router.enhance(owner,workspace,outline,result).generation().provider()).isEqualTo("GROQ");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(json.write(result)).doesNotContain("fixture-key","PRIVATE ERROR");
    }

    @Test void adaptersUseTheMatchingCredentialAndValidateOnlyTheSelectedDay() {
        for(String provider:List.of("NVIDIA","GEMINI","ZHIPU","GROQ")) {
            enable(provider);
            var router=ai(request->{
                if(request.method().equals("GET")) return response(200,catalog(request));
                String raw=new StudyCoachTest().body(request);
                assertThat(raw).contains("SQL transactions","Database basics").doesNotContain("fixture-key");
                assertThat(request.uri().getScheme()).isEqualTo("https");
                assertThat(request.uri().getQuery()).isNull();
                String header=provider.equals("GEMINI")?"x-goog-api-key":"Authorization";
                assertThat(request.headers().firstValue(header).orElseThrow()).endsWith("fixture-key-"+provider);
                if(provider.equals("GEMINI")) {
                    assertThat(request.uri().getHost()).isEqualTo("generativelanguage.googleapis.com");
                    return response(200,json.write(Map.of("candidates",List.of(Map.of("finishReason","STOP",
                        "content",Map.of("parts",List.of(Map.of("thought",true,"text","not the answer"),Map.of("text",chart))))))));
                }
                assertThat(request.uri().getHost()).isEqualTo(switch(provider) {
                    case "NVIDIA" -> "integrate.api.nvidia.com";
                    case "ZHIPU" -> "api.z.ai";
                    default -> "api.groq.com";
                });
                return response(200,chat(chart));
            });
            var result=router.enhance(owner,workspace,outline,null);
            assertThat(result.minutes()).isEqualTo(30);
            assertThat(result.sources()).isEqualTo(outline.sources());
            assertThat(result.generation().provider()).isEqualTo(provider);
        }
    }

    @Test void incompleteOrMalformedOutputUsesTheNextFreeModelAndFailureIsRedacted() {
        enable("OPENROUTER","ZHIPU");
        ((ObjectNode)settings.path("enhancement")).put("preferredProvider","OPENROUTER").put("preferredModel","openrouter/free");
        var router=ai(request->{
            if(request.method().equals("GET")) return response(200,catalog(request));
            return response(200,chat(request.uri().getHost().equals("openrouter.ai")?chart.replace("\"minutes\":30","\"minutes\":90"):chart));
        });
        assertThat(router.enhance(owner,workspace,outline,null).generation().provider()).isEqualTo("ZHIPU");
        var broken=ai(request->response(401,"PRIVATE ERROR fixture-key-OPENROUTER"));
        assertThatThrownBy(()->broken.enhance(owner,workspace,outline,null)).isInstanceOfSatisfying(Problem.class,p->{
            assertThat(p.code).isEqualTo("FREE_AI_UNAVAILABLE");
            assertThat(p.getMessage()).doesNotContain("PRIVATE ERROR","fixture-key");
        });
    }

    @Test void unavailablePreferredModelWithFallbackDisabledDoesNotUseAnotherRoute() {
        enable("GROQ","ZHIPU");
        ((ObjectNode)settings.path("enhancement")).put("fallback",false).put("preferredProvider","GROQ").put("preferredModel","retired-model");
        var router=ai(request->{
            assertThat(request.method()).isEqualTo("GET");
            return response(200,catalog(request));
        });
        assertThatThrownBy(()->router.enhance(owner,workspace,outline,null)).isInstanceOfSatisfying(Problem.class,
            p->assertThat(p.code).isEqualTo("SELECTED_FREE_MODEL_UNAVAILABLE"));
    }

    @Test void freeAccountConfirmationAndUnsupportedProvidersCannotBeBypassed() {
        enable("GROQ","MISTRAL","HUGGINGFACE");
        ((ObjectNode)settings.path("enhancement").path("providers").path("GROQ")).put("freeTierConfirmed",false);
        var router=ai(request->{throw new AssertionError("No eligible route should use a key or send day details");});
        assertThat(router.configured(workspace)).isFalse();
        assertThatThrownBy(()->router.enhance(owner,workspace,outline,null)).isInstanceOfSatisfying(Problem.class,
            p->assertThat(p.code).isEqualTo("FREE_AI_SETUP_REQUIRED"));
    }

    @Test void simultaneousRequestsDistributeAcrossModelsAndBoundPerUserWork() throws Exception {
        enable("GROQ","ZHIPU");
        var arrived=new CountDownLatch(2);
        var release=new CountDownLatch(1);
        var router=ai(request->{
            if(request.method().equals("GET")) return response(200,catalog(request));
            arrived.countDown();
            if(!release.await(8,TimeUnit.SECONDS)) throw new IOException("Fixture timeout");
            return response(200,chat(chart));
        });
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->router.enhance(owner,workspace,outline,null));
            var second=executor.submit(()->router.enhance(owner,workspace,outline,null));
            try {
                assertThat(arrived.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->router.enhance(owner,workspace,outline,null))
                    .isInstanceOfSatisfying(Problem.class,p->assertThat(p.code).isEqualTo("AI_BUSY"));
            } finally {
                release.countDown();
            }
            assertThat(first.get().generation().provider()).isNotEqualTo(second.get().generation().provider());
        }
    }
}
