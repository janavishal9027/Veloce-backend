package com.veloce.roadmap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloce.common.*;
import com.veloce.workspace.WorkspaceStore;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="VELOCE_UI_TEST_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:5432/veloce_history_verify_261115")
class ImportMemoryPostgresTest {
    @Test void confirmedCorrectionsAreReusedAcrossWorkspacesWithinTheSameAccount() throws Exception {
        var ds=new DriverManagerDataSource(System.getenv("VELOCE_UI_TEST_DB_URL"),
            System.getenv("VELOCE_UI_TEST_DB_USER"),System.getenv("VELOCE_UI_TEST_DB_PASSWORD"));
        try(var connection=ds.getConnection()) {
            connection.setAutoCommit(false);
            var db=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            try {
                var json=new Json(new ObjectMapper());
                var rules=new Roadmap(json);var parser=new ImportParser(json,rules);var store=new WorkspaceStore(db,json);
                var api=new ImportApi(store,rules,new RoadmapStore(db,json),parser,new Limits(Clock.systemUTC()),10_485_760);
                var worker=new ImportWorker(db,json,parser,true);
                UUID owner=account(db),other=account(db),first=workspace(db,owner),second=workspace(db,owner),foreign=workspace(db,other);
                var bytes=workbook("Original topic");var source=upload(api,owner,first,bytes);
                worker.tick();
                var status=json.mapper.valueToTree(api.status(owner,first,source));
                assertThat(status.path("state").asText()).isEqualTo("READY");
                assertThat(status.path("analysis").path("issues")).isEmpty();
                // An already-pending import from the previous application is repaired when reopened.
                var legacy=parser.analyze("xlsx",bytes,json.object().put("industry","Technology").put("targetRole","Engineer"));
                assertThat(legacy.issues()).isNotEmpty();
                db.update("UPDATE roadmap_sources SET analysis=?::jsonb WHERE id=?",json.write(legacy),source);
                var reopened=json.mapper.valueToTree(api.status(owner,first,source));
                assertThat(reopened.path("analysis").path("issues")).isEmpty();
                assertThat(reopened.path("analysis").path("candidate").path("nodes").get(1).path("resources")).hasSize(2);
                assertThat(db.queryForObject("SELECT jsonb_array_length(analysis->'issues') FROM roadmap_sources WHERE id=?",Integer.class,source)).isPositive();
                var mapping=json.object().put("expectedRevision",1).put("sheetId","sheet-2").put("included",false);
                var remapped=json.mapper.valueToTree(api.remap(owner,first,source,mapping));
                var candidate=remapped.path("analysis").path("candidate").deepCopy();
                for(var node:candidate.path("nodes")) if(!node.path("kind").asText().equals("MODULE")) {
                    ((ObjectNode)node).put("title","My reviewed topic").put("estimatedMinutes",55);
                }
                var confirmation=json.object().put("expectedRevision",2);confirmation.set("roadmap",candidate);
                var confirmed=json.mapper.valueToTree(api.confirm(owner,first,source,confirmation));
                assertThat(confirmed.path("analysis").path("issues")).isEmpty();
                assertThat(confirmed.path("roadmap").path("nodes")).hasSize(2);

                var nextSource=upload(api,owner,second,bytes);worker.tick();
                var next=json.mapper.valueToTree(api.status(owner,second,nextSource));
                assertThat(next.path("state").asText()).isEqualTo("READY");
                var remembered=json.as(json.read(db.queryForObject("SELECT analysis FROM roadmap_sources WHERE id=?",String.class,nextSource)),ImportParser.Analysis.class);
                var leaves=rules.confirmed(remembered.candidate()).nodes().stream().filter(n->n.kind()!=Roadmap.Kind.MODULE).toList();
                assertThat(leaves).extracting(Roadmap.Node::title).containsExactly("My reviewed topic");
                assertThat(leaves).extracting(Roadmap.Node::estimatedMinutes).containsExactly(55);
                assertThat(next.path("analysis").path("preparationNotes").toString()).contains("Reused your previously confirmed");
                assertThat(next.path("analysis").path("sheets").get(1).path("included").asBoolean()).isFalse();
                var nextConfirm=json.object().put("expectedRevision",1);nextConfirm.set("roadmap",remembered.candidate());
                assertThat(api.confirm(owner,second,nextSource,nextConfirm)).containsKey("roadmapId");

                var privateSource=upload(api,other,foreign,bytes);worker.tick();
                var untouched=json.mapper.valueToTree(api.status(other,foreign,privateSource));
                assertThat(untouched.path("analysis").path("candidate").toString()).contains("Original topic").doesNotContain("My reviewed topic");
                assertThat(untouched.path("analysis").path("preparationNotes").toString()).doesNotContain("Reused your previously confirmed");
                assertThatThrownBy(()->api.status(other,second,nextSource)).isInstanceOf(Problem.class);

                var changed=workspace(db,owner);var changedSource=upload(api,owner,changed,workbook("Different file content"));worker.tick();
                var changedStatus=json.mapper.valueToTree(api.status(owner,changed,changedSource));
                assertThat(changedStatus.path("analysis").path("candidate").toString()).contains("Different file content").doesNotContain("My reviewed topic");
                // An importer upgrade must not restore an older candidate that omitted workbook columns.
                db.update("UPDATE roadmap_sources SET profile=jsonb_set(profile,'{xlsxImportVersion}','0') WHERE workspace_id IN (?,?)",first,second);
                var upgraded=workspace(db,owner);var upgradedSource=upload(api,owner,upgraded,bytes);worker.tick();
                var refreshed=json.mapper.valueToTree(api.status(owner,upgraded,upgradedSource));
                assertThat(refreshed.path("analysis").path("candidate").toString()).contains("Original topic").doesNotContain("My reviewed topic");
                assertThat(refreshed.path("analysis").path("preparationNotes").toString()).doesNotContain("Reused your previously confirmed");
                assertThat(db.queryForObject("SELECT count(*) FROM schedule_slots WHERE workspace_id IN (?,?,?,?)",Integer.class,first,second,foreign,changed)).isZero();
            } finally { connection.rollback(); }
        }
    }

    private UUID account(JdbcTemplate db) {
        var id=UUID.randomUUID();db.update("INSERT INTO accounts(id,email,display_name,password_hash) VALUES(?,?,?,'not-a-password')",id,id+"@example.test","Import test");return id;
    }
    private UUID workspace(JdbcTemplate db,UUID owner) {
        var id=UUID.randomUUID();db.update("INSERT INTO workspaces(id,owner_id,name,industry,target_role,status) VALUES(?,?,'Test roadmap','Technology','Engineer','DRAFT')",id,owner);
        db.update("INSERT INTO onboarding_drafts(workspace_id,step,data) VALUES(?,3,'{}')",id);return id;
    }
    private UUID upload(ImportApi api,UUID owner,UUID workspace,byte[] bytes) throws Exception {
        return (UUID)api.upload(owner,workspace,0,new MockMultipartFile("file","plan.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes)).get("sourceId");
    }
    private byte[] workbook(String topic) throws Exception {
        try(var workbook=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var learning=workbook.createSheet("Learning");var header=learning.createRow(0);
            for(int i=0;i<4;i++) header.createCell(i).setCellValue(List.of("Title","Minutes","Outcome","Resource URL").get(i));
            var row=learning.createRow(1);row.createCell(0).setCellValue(topic);row.createCell(1).setCellValue(45);row.createCell(2).setCellValue("Demonstrate the topic");
            row.createCell(3).setCellValue("https://www.postgresql.org/docs/current/; https://www.mongodb.com/docs/manual/");
            var extras=workbook.createSheet("Exercises");var eh=extras.createRow(0);eh.createCell(0).setCellValue("Title");eh.createCell(1).setCellValue("Minutes");
            var er=extras.createRow(1);er.createCell(0).setCellValue("Extra activity");er.createCell(1).setCellValue(20);
            workbook.write(out);return out.toByteArray();
        }
    }
}
