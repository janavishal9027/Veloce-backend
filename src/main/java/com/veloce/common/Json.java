package com.veloce.common;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Json {
    public final ObjectMapper mapper;
    public Json(ObjectMapper mapper) { this.mapper = mapper; }
    public String write(Object object) {
        try { return mapper.writeValueAsString(object); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot encode application data", e); }
    }
    public JsonNode read(String raw) {
        try { return raw == null ? NullNode.getInstance() : mapper.readTree(raw); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid stored JSON", e); }
    }
    public <T> T as(JsonNode value, Class<T> type) {
        try { return mapper.treeToValue(value, type); }
        catch (JsonProcessingException | IllegalArgumentException e) { throw Problem.invalid("Check the format of your inputs."); }
    }
    public ObjectNode object() { return mapper.createObjectNode(); }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
    public static String text(JsonNode obj, String key, int max, boolean required) {
        JsonNode value = obj.path(key);
        if (!value.isMissingNode() && !value.isNull() && !value.isTextual()) throw Problem.invalid("Check " + key + ".");
        String s = value.asText("").strip();
        if (s.length() > max || s.indexOf(0) >= 0 || (required && s.isBlank())) {
            throw Problem.invalid("Enter " + key + " (" + (required ? "1" : "0") + "–" + max + " characters).");
        }
        return s;
    }
    public static long revision(JsonNode input) {
        if (!input.path("expectedRevision").isIntegralNumber() || !input.path("expectedRevision").canConvertToLong()
            || input.path("expectedRevision").asLong() < 0) throw Problem.invalid("A valid expectedRevision is required.");
        return input.path("expectedRevision").asLong();
    }
    public static String key(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{8,100}")) throw Problem.invalid("Send a valid Idempotency-Key.");
        return value;
    }
}
