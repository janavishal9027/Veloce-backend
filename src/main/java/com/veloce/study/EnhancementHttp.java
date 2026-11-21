package com.veloce.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloce.common.Json;
import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
class EnhancementHttp {
    interface Transport { HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException; }
    record Response(int status, JsonNode body, HttpHeaders headers) {}

    private final Json json;
    private final Transport transport;
    private static final ScheduledThreadPoolExecutor TIMEOUTS = timeouts();

    private static ScheduledThreadPoolExecutor timeouts() {
        var timer = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task,"veloce-ai-response-timeout");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    @org.springframework.beans.factory.annotation.Autowired
    EnhancementHttp(Json json) {
        this(json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    private EnhancementHttp(Json json, java.net.http.HttpClient client) {
        this(json, request -> client.send(request, HttpResponse.BodyHandlers.ofInputStream()));
    }

    EnhancementHttp(Json json, Transport transport) {
        this.json = json;
        this.transport = transport;
    }

    Response send(HttpRequest request, int maximumBytes) throws IOException, InterruptedException {
        long expires = System.nanoTime() + request.timeout().orElse(Duration.ofSeconds(18)).toNanos();
        var response = transport.send(request);
        try (var stream = response.body()) {
            // HttpClient's InputStream handler completes when headers arrive. Close a stalled body at the request deadline.
            var timeout = TIMEOUTS.schedule(() -> {
                try { stream.close(); } catch (IOException ignored) { }
            },Math.max(1,expires-System.nanoTime()),TimeUnit.NANOSECONDS);
            try {
                byte[] bytes = stream.readNBytes(maximumBytes + 1);
                if (System.nanoTime() >= expires) throw new IOException("AI response timed out");
                if (bytes.length > maximumBytes) throw new IOException("AI response exceeded the limit");
                JsonNode value = json.object();
                // Error bodies may contain credential fragments. Only the status and retry header leave this transport.
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    value = json.mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
                    if (value == null) throw new IOException("Empty AI response");
                }
                return new Response(response.statusCode(), value, response.headers());
            } finally {
                timeout.cancel(false);
            }
        }
    }
}
