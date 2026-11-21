package com.veloce.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloce.common.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class StudyPreferences {
    private final StudyStore s;
    private final byte[] key;
    private final SecureRandom random=new SecureRandom();
    StudyPreferences(StudyStore s,@Value("${veloce.study-key:${veloce.jwt-secret}}") String secret) {
        this.s=s;
        try { key=MessageDigest.getInstance("SHA-256").digest(("Veloce study credentials\u0000"+secret).getBytes(StandardCharsets.UTF_8)); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    public ObjectNode defaults() {
        var defaults=s.json.object().put("hideEvidence",false).put("speakQuestions",true).put("showBreaks",true)
            .put("compactCards",false).put("theme","inherit").put("accent","indigo").put("textScale",100).put("headingScale",100)
            .put("provider","MANUAL").put("model","").put("practiceMinutes",20);
        defaults.set("enhancement",enhancementDefaults());
        return defaults;
    }
    public ObjectNode enhancementDefaults() {
        var data=s.json.object().put("preferredProvider","").put("preferredModel","").put("fallback",true);
        var providers=data.putObject("providers");
        for(var provider:EnhancementProvider.values())
            providers.putObject(provider.name()).put("enabled",false).put("freeTierConfirmed",false).put("region","INTERNATIONAL");
        return data;
    }
    private ObjectNode keys(UUID w) {
        return s.ws.db.query("SELECT provider_keys FROM study_preferences WHERE workspace_id=?",
            (r,n)->(ObjectNode)s.json.read(r.getString(1)),w).stream().findFirst().orElseGet(s.json::object);
    }
    public ObjectNode get(UUID w) {
        var result=defaults();
        var rows=s.ws.db.query("SELECT version,data FROM study_preferences WHERE workspace_id=?",
            (r,n)->Map.of("version",r.getLong(1),"data",s.json.read(r.getString(2))),w);
        long version=0;
        if (!rows.isEmpty()) {
            result.setAll((ObjectNode)rows.getFirst().get("data"));
            version=((Number)rows.getFirst().get("version")).longValue();
        }
        result.put("version",version);
        var saved=result.putObject("savedKeys"); var keys=keys(w);
        for (String provider:List.of("OPENAI","OPENROUTER","GEMINI")) saved.put(provider,keys.hasNonNull(provider));
        for (var provider:EnhancementProvider.values()) saved.put(provider.name(),keys.hasNonNull(provider.name()));
        return result;
    }
    public static String provider(String value) {
        if (!Set.of("MANUAL","OPENAI","OPENROUTER","GEMINI").contains(value)) throw Problem.invalid("Choose a supported practice provider.");
        return value;
    }
    public static String model(String value) {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,119}")||value.contains(".."))
            throw Problem.invalid("Enter a valid provider model ID.");
        return value;
    }
    private String seal(String value) {
        try {
            byte[] nonce=new byte[12]; random.nextBytes(nonce);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            return Base64.getEncoder().encodeToString(nonce)+"."+Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Could not encrypt the provider credential."); }
    }
    public String credential(UUID w,String provider) {
        keyProvider(provider);
        String encrypted=keys(w).path(provider).asText();
        if (encrypted.isEmpty()) throw new Problem(412,"PROVIDER_KEY_REQUIRED","Save the provider key in this workspace's settings before using AI.");
        try {
            var parts=encrypted.split("\\.");
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8);
        } catch (GeneralSecurityException|IllegalArgumentException|ArrayIndexOutOfBoundsException e) {
            throw new Problem(412,"PROVIDER_KEY_REQUIRED","Re-enter the provider key after the server credential key changed.");
        }
    }
    @Transactional
    public ObjectNode save(UUID owner,UUID w,JsonNode input) {
        var workspace=s.ws.owned(owner,w,true); s.writable(workspace);
        var old=get(w);
        if (StudyRules.version(input)!=old.path("version").asLong()) throw Problem.conflict("Study settings changed in another view. Reload before saving.");
        JsonNode fields=input.path("data");
        var clean=defaults();
        for (String field:List.of("hideEvidence","speakQuestions","showBreaks","compactCards")) {
            if (!fields.path(field).isBoolean()) throw Problem.invalid("Choose a value for "+field+".");
            clean.put(field,fields.path(field).asBoolean());
        }
        String theme=Json.text(fields,"theme",12,true),accent=Json.text(fields,"accent",12,true);
        if (!Set.of("inherit","light","dark").contains(theme)||!Set.of("teal","indigo","amber","rose").contains(accent))
            throw Problem.invalid("Choose the workspace theme and accent.");
        clean.put("theme",theme).put("accent",accent)
            .put("textScale",StudyRules.number(fields,"textScale",85,150,100))
            .put("headingScale",StudyRules.number(fields,"headingScale",85,160,100))
            .put("practiceMinutes",StudyRules.number(fields,"practiceMinutes",5,60,20));
        if (!Set.of(85,100,115,130,150).contains(clean.path("textScale").asInt())
            ||!Set.of(85,100,115,130,160).contains(clean.path("headingScale").asInt())
            ||!Set.of(5,10,15,20,25,30,45,60).contains(clean.path("practiceMinutes").asInt()))
            throw Problem.invalid("Choose one of the listed text sizes and practice targets.");
        String provider=provider(Json.text(fields,"provider",12,true));
        String model=Json.text(fields,"model",120,false);
        if (!provider.equals("MANUAL")) model(model);
        if (provider.equals("GEMINI")&&model.contains("/")) throw Problem.invalid("Use the Gemini model ID without a models/ prefix.");
        clean.put("provider",provider).put("model",model);
        clean.set("enhancement",old.path("enhancement"));
        var keys=keys(w);
        String secret=Json.text(input,"apiKey",2000,false).replaceFirst("(?i)^Bearer\\s+","");
        if (!secret.isEmpty()) {
            if (provider.equals("MANUAL")||secret.length()<8||secret.chars().anyMatch(c->c<33||c>126))
                throw Problem.invalid("Enter a valid provider key.");
            keys.put(provider,seal(secret));
        }
        s.ws.db.update("""
            INSERT INTO study_preferences(workspace_id,version,data,provider_keys) VALUES(?,?,?::jsonb,?::jsonb)
            ON CONFLICT(workspace_id) DO UPDATE SET version=excluded.version,data=excluded.data,
              provider_keys=excluded.provider_keys,updated_at=now()
            """,w,old.path("version").asLong()+1,s.json.write(clean),s.json.write(keys));
        return get(w);
    }
    @Transactional
    public ObjectNode forget(UUID owner,UUID w,String provider,long expected) {
        var workspace=s.ws.owned(owner,w,true); s.writable(workspace);
        var old=get(w);
        if (expected!=old.path("version").asLong()) throw Problem.conflict("Study settings changed in another view.");
        keyProvider(provider);
        var keys=keys(w); keys.remove(provider);
        s.ws.db.update("UPDATE study_preferences SET provider_keys=?::jsonb,version=version+1,updated_at=now() WHERE workspace_id=?",s.json.write(keys),w);
        return get(w);
    }

    private static void keyProvider(String value) {
        if(!value.equals("OPENAI")) EnhancementProvider.parse(value);
    }

    @Transactional
    public ObjectNode saveEnhancement(UUID owner, UUID workspaceId, JsonNode input) {
        var workspace=s.ws.owned(owner,workspaceId,true);
        s.writable(workspace);
        var old=get(workspaceId);
        if(StudyRules.version(input)!=old.path("version").asLong())
            throw Problem.conflict("Study settings changed in another view. Reload before saving AI settings.");
        var data=input.path("data");
        if(!data.path("fallback").isBoolean() || !data.path("providers").isObject())
            throw Problem.invalid("Choose the AI providers and fallback setting.");
        String preferred=Json.text(data,"preferredProvider",20,false);
        String selected=Json.text(data,"preferredModel",120,false);
        if(!preferred.isEmpty()) EnhancementProvider.parse(preferred);
        if(!selected.isEmpty()) model(selected);
        if(preferred.isEmpty()&&!selected.isEmpty()) throw Problem.invalid("Choose a provider for the preferred model.");
        var clean=enhancementDefaults().put("fallback",data.path("fallback").asBoolean())
            .put("preferredProvider",preferred).put("preferredModel",selected);
        var savedKeys=keys(workspaceId);
        for(var provider:EnhancementProvider.values()) {
            String id=provider.name();
            var fields=data.path("providers").path(id);
            boolean enabled=fields.path("enabled").asBoolean(false);
            boolean confirmed=fields.path("freeTierConfirmed").asBoolean(false);
            String region=Json.text(fields,"region",16,false);
            if(region.isEmpty()) region="INTERNATIONAL";
            if(!Set.of("INTERNATIONAL","CHINA").contains(region) || provider!=EnhancementProvider.ZHIPU&&!region.equals("INTERNATIONAL"))
                throw Problem.invalid("Choose the region that issued the Zhipu key.");
            String secret=Json.text(input.path("keys"),id,2000,false).replaceFirst("(?i)^Bearer\\s+","");
            if(!secret.isEmpty()) {
                if(secret.length()<8 || secret.chars().anyMatch(c->c<33||c>126)) throw Problem.invalid("Enter a valid provider key.");
                savedKeys.put(id,seal(secret));
            }
            if(input.path("removeKeys").path(id).asBoolean(false)) savedKeys.remove(id);
            if(enabled && !provider.supported()) throw Problem.invalid(provider.label+" has no verified free text-generation API route.");
            if(enabled && !savedKeys.hasNonNull(id)) throw Problem.invalid("Save a key before enabling "+provider.label+".");
            if(enabled && provider.freeTierRequired && !confirmed)
                throw Problem.invalid("Confirm that the "+provider.label+" account uses a free plan with paid billing disabled.");
            ((ObjectNode)clean.path("providers")).putObject(id).put("enabled",enabled)
                .put("freeTierConfirmed",confirmed).put("region",region);
        }
        if(!preferred.isEmpty() && !clean.path("providers").path(preferred).path("enabled").asBoolean())
            throw Problem.invalid("Enable the preferred provider, or choose Automatic.");
        if(preferred.equals("OPENROUTER") && !selected.isEmpty() && !selected.equals("openrouter/free") && !selected.endsWith(":free"))
            throw Problem.invalid("Choose a free OpenRouter model.");
        if(!preferred.isEmpty()&&!preferred.equals("OPENROUTER")&&!selected.isEmpty()) {
            String region=clean.path("providers").path(preferred).path("region").asText("INTERNATIONAL");
            if(!EnhancementProvider.parse(preferred).freeTierModels(region).contains(selected))
                throw Problem.invalid("Choose one of this provider's eligible free models.");
        }
        old.remove(List.of("version","savedKeys"));
        old.set("enhancement",clean);
        s.ws.db.update("""
            INSERT INTO study_preferences(workspace_id,version,data,provider_keys) VALUES(?,?,?::jsonb,?::jsonb)
            ON CONFLICT(workspace_id) DO UPDATE SET version=excluded.version,data=excluded.data,
              provider_keys=excluded.provider_keys,updated_at=now()
            """,workspaceId,StudyRules.version(input)+1,s.json.write(old),s.json.write(savedKeys));
        return get(workspaceId);
    }
}
