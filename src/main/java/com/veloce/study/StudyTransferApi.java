package com.veloce.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloce.common.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspaces/{w}/study")
public class StudyTransferApi {
    private final StudyStore s;
    StudyTransferApi(StudyStore s) { this.s=s; }
    @GetMapping("/export")
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    ResponseEntity<byte[]> export(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@RequestParam String date,
        @RequestParam(defaultValue="json") String format) throws IOException {
        var workspace=s.ws.owned(owner,w); var roadmap=s.roadmap(workspace); var selected=StudyRules.date(date);
        if (!Set.of("json","csv","xlsx").contains(format)) throw Problem.invalid("Choose JSON, CSV, or XLSX.");
        long bytes=s.ws.db.queryForObject("""
            SELECT (SELECT coalesce(sum(octet_length(data::text)),0) FROM study_records WHERE workspace_id=? AND roadmap_id=? AND study_date=? AND record_type<>'DRAFT')+
              (SELECT coalesce(sum(octet_length(data::text)),0) FROM study_reports WHERE workspace_id=? AND roadmap_id=? AND study_date=?)
            """,Long.class,w,roadmap,selected,w,roadmap,selected);
        if (bytes>4_000_000) throw Problem.invalid("This day's backup exceeds 4 MB. Export individual work history or shorten large pasted outputs.");
        var records=s.ws.db.query("SELECT * FROM study_records WHERE workspace_id=? AND roadmap_id=? AND study_date=? AND record_type<>'DRAFT' ORDER BY record_type,record_key LIMIT 1001",
            s::map,w,roadmap,selected);
        if (records.size()>1000) throw Problem.invalid("Export up to 1,000 records for a study day.");
        var reports=s.ws.db.query("""
            SELECT version,work_fingerprint,data,submitted_at FROM study_reports
            WHERE workspace_id=? AND roadmap_id=? AND study_date=? ORDER BY version
            """,(r,n)->Map.of("version",r.getLong(1),"workFingerprint",r.getString(2),"data",s.json.read(r.getString(3)),
                "submittedAt",r.getObject(4,OffsetDateTime.class).toInstant().toString()),w,roadmap,selected);
        var doc=s.json.object().put("schema","veloce-study-backup-v1").put("accountId",owner.toString())
            .put("workspaceId",w.toString()).put("workspaceName",workspace.name()).put("roadmapId",roadmap.toString())
            .put("date",date).put("exportedAt",s.clock.instant().toString());
        doc.set("records",s.json.mapper.valueToTree(records)); doc.set("reports",s.json.mapper.valueToTree(reports));
        byte[] output; String mime;
        if (format.equals("json")) {
            output=s.json.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(doc); mime="application/json";
        } else {
            var rows=new ArrayList<List<String>>();
            rows.add(List.of("Type","Record key","Activity ID","Study date","Version","Part","Data JSON"));
            for (var record:records) chunks(rows,List.of(record.type(),record.key(),record.nodeId()==null?"":record.nodeId(),
                record.date().toString(),Long.toString(record.version())),s.json.write(record.data()));
            for (var report:reports) chunks(rows,List.of("REPORT",date,"",date,report.get("version").toString()),s.json.write(report));
            if (format.equals("csv")) {
                var csv=new StringBuilder("\ufeff");
                for (var row:rows) csv.append(String.join(",",row.stream().map(StudyRules::csv).toList())).append("\r\n");
                output=csv.toString().getBytes(StandardCharsets.UTF_8); mime="text/csv";
            } else {
                try (var workbook=new XSSFWorkbook();var stream=new ByteArrayOutputStream()) {
                    var sheet=workbook.createSheet("Study records");
                    for (int i=0;i<rows.size();i++) {
                        var row=sheet.createRow(i);
                        for (int col=0;col<rows.get(i).size();col++) row.createCell(col).setCellValue(rows.get(i).get(col));
                    }
                    sheet.createFreezePane(0,1);
                    sheet.setColumnWidth(0,16*256); sheet.setColumnWidth(1,35*256); sheet.setColumnWidth(6,80*256);
                    workbook.write(stream); output=stream.toByteArray();
                }
                mime="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(mime))
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("veloce-study-"+date+"."+format,StandardCharsets.UTF_8).build().toString())
            .body(output);
    }
    private void chunks(List<List<String>> rows,List<String> prefix,String data) {
        for (int i=0;i<Math.max(1,data.length());i+=30_000) {
            var row=new ArrayList<>(prefix); row.add(Integer.toString(i/30_000+1));
            row.add(data.substring(i,Math.min(i+30_000,data.length()))); rows.add(row);
        }
    }
    @PostMapping("/restore")
    @Transactional
    Object restore(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@RequestBody JsonNode input) {
        var workspace=s.ws.owned(owner,w,true); s.writable(workspace); var roadmap=s.roadmap(workspace);
        if (!input.path("schema").asText().equals("veloce-study-backup-v1")
            ||!input.path("accountId").asText().equals(owner.toString())||!input.path("workspaceId").asText().equals(w.toString())
            ||!input.path("roadmapId").asText().equals(roadmap.toString()))
            throw Problem.invalid("Choose a study backup for this account, workspace, and roadmap.");
        if (!input.path("records").isArray()||input.path("records").size()>1000||s.json.write(input).length()>6_000_000)
            throw Problem.invalid("Choose a backup with up to 1,000 study records and 6 MB of JSON.");
        int restored=0,kept=0;
        var seen=new HashSet<String>();
        for (var record:input.path("records")) {
            String type=record.path("type").asText();
            if (!Set.of("WORK","PROJECT").contains(type)) continue;
            String key=Json.text(record,"key",200,true);
            String node=type.equals("PROJECT")&&!record.hasNonNull("nodeId")?key.split("/",2)[0]:Json.text(record,"nodeId",80,true);
            String storageNode=node;
            if(type.equals("PROJECT")) storageNode=new ProjectCatalog(s).find(workspace,node).storageNodeId();
            else s.node(w,roadmap,node);
            LocalDate date=StudyRules.date(Json.text(record,"date",10,true));
            if (type.equals("WORK")&&!key.equals(date+"/"+node)) throw Problem.invalid("Backup work key does not match its activity and date.");
            if (type.equals("PROJECT")&&(!key.startsWith(node+"/")
                ||!StudyRules.SECTIONS.contains(key.substring(node.length()+1)))) throw Problem.invalid("Invalid project section in backup.");
            String draftKey=type+"/"+key;
            if (!seen.add(draftKey)) throw Problem.invalid("Backup has duplicate records.");
            if(type.equals("WORK")&&record.path("data").path("dailySections").isObject()) {
                long base=s.get(w,roadmap,type,key).map(StudyStore.Record::version).orElse(0L);
                for(String section:DailySections.TITLES.keySet())if(record.path("data").path("dailySections").has(section)) {
                    String sectionKey="DAILY/"+key+"/"+section;
                    if(s.get(w,roadmap,"DRAFT",sectionKey).isPresent()){kept++;continue;}
                    var data=s.json.object().put("targetType","DAILY").put("baseVersion",base).put("restoredFromVersion",record.path("version").asLong());
                    data.set("work",StudyRules.work(s.json,record.path("data").path("dailySections").path(section),true));
                    s.save(w,roadmap,"DRAFT",sectionKey,node,date,data,0,UUID.randomUUID());restored++;
                }
                continue;
            }
            if (s.get(w,roadmap,"DRAFT",draftKey).isPresent()) { kept++; continue; }
            long base=s.get(w,roadmap,type,key).map(StudyStore.Record::version).orElse(0L);
            var data=s.json.object().put("targetType",type).put("baseVersion",base).put("restoredFromVersion",record.path("version").asLong());
            data.set("work",StudyRules.work(s.json,record.path("data"),true,type.equals("PROJECT")?43_200:1440));
            s.save(w,roadmap,"DRAFT",draftKey,storageNode,date,data,0,UUID.randomUUID()); restored++;
        }
        return Map.of("restoredDrafts",restored,"existingDraftsKept",kept,"historyRestored",false);
    }
}
