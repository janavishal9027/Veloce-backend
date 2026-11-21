package com.veloce.study;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloce.common.*;
import com.veloce.workspace.WorkspaceStore;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="VELOCE_UI_TEST_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:5432/veloce_history_verify_261115")
class EnhancementSettingsPostgresTest {
    @Test void providerKeysAreEncryptedPrivateVersionedAndSurviveAppearanceEdits() throws Exception {
        var source=new DriverManagerDataSource(System.getenv("VELOCE_UI_TEST_DB_URL"),System.getenv("VELOCE_UI_TEST_DB_USER"),
            System.getenv("VELOCE_UI_TEST_DB_PASSWORD"));
        try(var connection=source.getConnection()) {
            connection.setAutoCommit(false);
            var db=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            try {
                var json=new Json(new ObjectMapper().findAndRegisterModules());
                var clock=Clock.systemUTC();
                UUID owner=UUID.randomUUID(),other=UUID.randomUUID();
                for(var id:List.of(owner,other))
                    db.update("INSERT INTO accounts(id,email,display_name,password_hash) VALUES(?,?,'AI settings test','unused')",id,id+"@example.test");
                var ids=StudyCheckInPostgresTest.workspace(db,owner);
                var study=new StudyStore(new WorkspaceStore(db,json),clock);
                var prefs=new StudyPreferences(study,"fixture-encryption-secret");
                var config=prefs.enhancementDefaults();
                ((ObjectNode)config.path("providers").path("OPENROUTER")).put("enabled",true);
                var input=json.object().put("expectedVersion",0);
                input.set("data",config);
                input.putObject("keys").put("OPENROUTER","fixture-openrouter-secret-value").put("MISTRAL","fixture-mistral-secret-value");
                var saved=prefs.saveEnhancement(owner,ids[0],input);
                assertThat(saved.path("savedKeys").path("OPENROUTER").asBoolean()).isTrue();
                assertThat(saved.path("savedKeys").path("MISTRAL").asBoolean()).isTrue();
                assertThat(saved.toString()).doesNotContain("fixture-openrouter-secret-value","fixture-mistral-secret-value");
                String encrypted=db.queryForObject("SELECT provider_keys::text FROM study_preferences WHERE workspace_id=?",String.class,ids[0]);
                assertThat(encrypted).doesNotContain("fixture-openrouter-secret-value","fixture-mistral-secret-value");
                assertThat(prefs.credential(ids[0],"OPENROUTER")).isEqualTo("fixture-openrouter-secret-value");
                var appearance=prefs.defaults().put("theme","light");
                var update=json.object().put("expectedVersion",1);
                update.set("data",appearance);
                var after=prefs.save(owner,ids[0],update);
                assertThat(after.path("enhancement").path("providers").path("OPENROUTER").path("enabled").asBoolean()).isTrue();
                assertThatThrownBy(()->prefs.saveEnhancement(owner,ids[0],input))
                    .isInstanceOfSatisfying(Problem.class,error->assertThat(error.status).isEqualTo(409));
                assertThatThrownBy(()->prefs.saveEnhancement(other,ids[0],input))
                    .isInstanceOfSatisfying(Problem.class,error->assertThat(error.status).isEqualTo(404));
                var http=new EnhancementHttp(json,request->{throw new AssertionError("Settings must not generate a chart");});
                var ai=new PreparationAi(prefs,json,new FreeAiModels(http,clock),http,clock);
                var api=new StudyEnhancementApi(study,prefs,ai,new Limits(clock));
                assertThat(api.settings(owner,ids[0]).toString()).doesNotContain("fixture-openrouter-secret-value");
                assertThatThrownBy(()->api.settings(other,ids[0]))
                    .isInstanceOfSatisfying(Problem.class,error->assertThat(error.status).isEqualTo(404));
                ((ObjectNode)config.path("providers").path("GEMINI")).put("enabled",true);
                input.put("expectedVersion",2).putObject("keys").put("GEMINI","fixture-gemini-secret-value");
                assertThatThrownBy(()->prefs.saveEnhancement(owner,ids[0],input))
                    .isInstanceOfSatisfying(Problem.class,error->assertThat(error.getMessage()).contains("free plan"));
                ((ObjectNode)config.path("providers").path("GEMINI")).put("enabled",false);
                ((ObjectNode)config.path("providers").path("OPENROUTER")).put("enabled",false);
                input.putObject("removeKeys").put("OPENROUTER",true);
                prefs.saveEnhancement(owner,ids[0],input);
                assertThat(ai.configured(ids[0])).isFalse();
                assertThat(prefs.get(ids[0]).path("savedKeys").path("OPENROUTER").asBoolean()).isFalse();
            } finally {
                connection.rollback();
            }
        }
    }
}
