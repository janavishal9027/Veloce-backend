package com.veloce.study;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import com.veloce.common.Json;

@Component
class FreeAiModels {
    record Model(String id, String name, String basis) {}
    record Catalog(List<Model> models, String message, Instant checkedAt) {}
    private record Cached(Catalog catalog, Instant expiresAt) {}

    private final EnhancementHttp http;
    private final Clock clock;
    private final Map<String, Cached> catalogs = new ConcurrentHashMap<>();

    FreeAiModels(EnhancementHttp http, Clock clock) {
        this.http = http;
        this.clock = clock;
    }

    Catalog list(EnhancementProvider provider, String region, String key, boolean refresh) {
        Instant now = clock.instant();
        if (!provider.supported()) return new Catalog(List.of(), provider.note, now);
        if (provider == EnhancementProvider.ZHIPU) {
            return new Catalog(provider.freeTierModels(region).stream().map(id -> new Model(id, id, "ZERO_PRICE")).toList(),
                "Free GLM Flash allowlist. Provider availability and rate limits apply.", now);
        }
        String cacheKey = provider.name() + "/" + region + "/" + Json.sha256(key);
        var cached = catalogs.get(cacheKey);
        if (!refresh && cached != null && cached.expiresAt().isAfter(now)) return cached.catalog();
        String endpoint = provider.base(region) + (provider == EnhancementProvider.GEMINI ? "/models?pageSize=1000" : "/models");
        var request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(6)).GET();
        if (provider != EnhancementProvider.OPENROUTER) {
            request.header(provider == EnhancementProvider.GEMINI ? "x-goog-api-key" : "Authorization",
                provider == EnhancementProvider.GEMINI ? key : "Bearer " + key);
        }
        Catalog result;
        try {
            var response = http.send(request.build(), 4_000_000);
            if (response.status() != 200) throw new IOException("Model catalog unavailable");
            var models = provider == EnhancementProvider.OPENROUTER ? openRouter(response.body())
                : freeTier(provider, region, response.body());
            result = new Catalog(models, models.isEmpty() ? "No eligible free model is currently available for this key."
                : "Eligible models checked against the provider catalog.", now);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Catalog(List.of(), "Model lookup was interrupted.", now);
        } catch (IOException | IllegalArgumentException e) {
            var models = provider == EnhancementProvider.OPENROUTER
                ? List.of(new Model("openrouter/free", "Automatic free model", "ZERO_PRICE")) : List.<Model>of();
            result = new Catalog(models, "Could not check the model catalog. Check the key or try Refresh models.", now);
        }
        // Failed lookups have a shorter cooldown. Old catalogs are never used as a billing decision.
        if (catalogs.size() >= 256) {
            catalogs.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
            if (catalogs.size() >= 256) catalogs.keySet().stream().findFirst().ifPresent(catalogs::remove);
        }
        catalogs.put(cacheKey, new Cached(result, now.plusSeconds(result.models().isEmpty() ? 30 : 300)));
        return result;
    }

    static List<Model> openRouter(JsonNode response) {
        var models = new TreeMap<String, Model>();
        models.put("openrouter/free", new Model("openrouter/free", "Automatic free model", "ZERO_PRICE"));
        for (var row : response.path("data")) {
            String id = row.path("id").asText();
            var price = row.path("pricing");
            boolean textOutput = row.path("architecture").path("modality").asText().endsWith("->text")
                || row.path("architecture").path("output_modalities").toString().contains("\"text\"");
            if (!validId(id) || !id.endsWith(":free") || !textOutput || row.path("context_length").asInt() < 8192
                || !zero(price.path("prompt")) || !zero(price.path("completion"))
                || price.hasNonNull("request") && !zero(price.path("request"))) continue;
            String name = row.path("name").asText(id);
            models.put(id, new Model(id, name.length() <= 200 ? name : id, "ZERO_PRICE"));
        }
        return List.copyOf(models.values());
    }

    private static List<Model> freeTier(EnhancementProvider provider, String region, JsonNode response) {
        var available = new HashSet<String>();
        var rows = provider == EnhancementProvider.GEMINI ? response.path("models") : response.path("data");
        for (var row : rows) {
            String id = row.path(provider == EnhancementProvider.GEMINI ? "name" : "id").asText().replaceFirst("^models/", "");
            if (row.path("active").isBoolean() && !row.path("active").asBoolean()) continue;
            if (provider == EnhancementProvider.GEMINI && !row.path("supportedGenerationMethods").toString().contains("\"generateContent\"")) continue;
            available.add(id);
        }
        return provider.freeTierModels(region).stream().filter(available::contains)
            .map(id -> new Model(id, id, "FREE_TIER_ACCOUNT")).toList();
    }

    static boolean validId(String id) {
        return id.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,119}") && !id.contains("..");
    }

    private static boolean zero(JsonNode value) {
        if (!value.isTextual() && !value.isNumber()) return false;
        try {
            return new BigDecimal(value.asText()).compareTo(BigDecimal.ZERO) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
