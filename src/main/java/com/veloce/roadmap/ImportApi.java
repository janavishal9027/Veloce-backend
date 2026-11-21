package com.veloce.roadmap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloce.common.*;
import com.veloce.workspace.WorkspaceStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
class ImportApi {
    private static final Set<String> FORMATS=Set.of("json","xlsx","csv","md","txt","pdf");
    private static final int XLSX_IMPORT_VERSION = 7;
    private final WorkspaceStore ws;
    private final Roadmap roadmap;
    private final RoadmapStore roadmaps;
    private final ImportParser parser;
    private final Limits limits;
    private final int maxBytes;

    ImportApi(WorkspaceStore ws,Roadmap roadmap,RoadmapStore roadmaps,ImportParser parser,Limits limits,
              @Value("${veloce.upload-limit}") int maxBytes) {
        this.ws=ws; this.roadmap=roadmap; this.roadmaps=roadmaps; this.parser=parser; this.limits=limits;
        this.maxBytes=Math.min(maxBytes,10_485_760);
    }

    @GetMapping("/contracts/roadmap-v1")
    JsonNode schema() { return roadmap.contract(); }

    @PostMapping(value="/workspaces/{w}/roadmap-sources",consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    Map<String,Object> paste(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@RequestBody JsonNode input) {
        String text=Json.text(input,"text",1_000_000,true);
        String format=Json.text(input,"format",10,false);
        if (format.isBlank()) format=text.stripLeading().startsWith("{") || text.stripLeading().startsWith("\u0060\u0060\u0060json")?"json":"md";
        if (!Set.of("json","csv","md","txt").contains(format)) throw Problem.invalid("Paste JSON, CSV, Markdown, or plain text.");
        return store(owner,w,Json.revision(input),"Pasted roadmap."+format,format,text.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping(value="/workspaces/{w}/roadmap-sources",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    Map<String,Object> upload(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,
                              @RequestParam long expectedRevision,@RequestParam MultipartFile file) throws IOException {
        ws.owned(owner,w);
        if (file.isEmpty() || file.getSize()>maxBytes) throw new Problem(413,"FILE_TOO_LARGE","Choose a nonempty roadmap under 10 MB.");
        String filename=Optional.ofNullable(file.getOriginalFilename()).orElse("").replace('\\','/');
        filename=filename.substring(filename.lastIndexOf('/')+1).replaceAll("\\p{Cntrl}","");
        int dot=filename.lastIndexOf('.');
        String format=dot<0?"":filename.substring(dot+1).toLowerCase(Locale.ROOT);
        if (format.equals("markdown")) format="md";
        if (!FORMATS.contains(format)) throw Problem.invalid("Use JSON, XLSX, CSV, Markdown, TXT, or text-bearing PDF.");
        String mime=Optional.ofNullable(file.getContentType()).orElse("").toLowerCase(Locale.ROOT);
        if (mime.startsWith("image/") || mime.contains("wordprocessing") || mime.contains("executable"))
            throw Problem.invalid("This document type is unsupported.");
        if (filename.length()>180) filename=filename.substring(0,160)+"."+format;
        return store(owner,w,expectedRevision,filename,format,file.getBytes());
    }

    private Map<String,Object> store(UUID owner,UUID w,long rev,String name,String format,byte[] bytes) {
        limits.check("upload:"+owner,20,300);
        var workspace=ws.owned(owner,w,true); ws.draft(workspace); ws.expect(workspace,rev);
        if (workspace.step()<3) throw Problem.invalid("Choose a roadmap source first.");
        if (bytes.length==0 || bytes.length>maxBytes) throw new Problem(413,"FILE_TOO_LARGE","Choose a nonempty roadmap under 10 MB.");
        String hash=Json.sha256(bytes);
        String sourceProfile=profile(workspace,format);
        UUID source=ws.db.query("""
            SELECT id FROM roadmap_sources WHERE workspace_id=? AND sha256=? AND format=? AND state<>'FAILED'
               AND profile=?::jsonb ORDER BY created_at DESC LIMIT 1
            """,(r,n)->r.getObject(1,UUID.class),w,hash,format,sourceProfile).stream().findFirst().orElse(null);
        if (source==null) {
            source=UUID.randomUUID();
            ws.db.update("""
                INSERT INTO roadmap_sources(id,workspace_id,filename,format,sha256,content,profile)
                VALUES(?,?,?,?,?,?,?::jsonb)
                """,source,w,name,format,hash,bytes,sourceProfile);
        }
        if (!source.equals(workspace.sourceId())) {
            ws.db.update("UPDATE onboarding_drafts SET current_source_id=?,roadmap_id=NULL,step=3 WHERE workspace_id=?",source,w);
            ws.changed(w);
        }
        return Map.of("sourceId",source,"jobId",source,"revision",ws.owned(owner,w).revision());
    }

    private String profile(WorkspaceStore.Workspace w,String format) {
        var result=ws.json.object().put("industry",w.industry()).put("targetRole",w.targetRole());
        result.put("preparationVersion",1);
        if (format.equals("xlsx")) result.put("xlsxImportVersion",XLSX_IMPORT_VERSION);
        return ws.json.write(result);
    }

    private boolean needsWorkbookReanalysis(Source source) {
        return source.format().equals("xlsx") && source.state().equals("READY")
            && ws.json.read(source.profile()).path("xlsxImportVersion").asInt()<XLSX_IMPORT_VERSION;
    }
    private record Source(String filename,String format,String state,String error,String analysis,String profile) {}
    private Source source(UUID w,UUID s) {
        return ws.db.query("SELECT filename,format,state,error,analysis,profile FROM roadmap_sources WHERE workspace_id=? AND id=?",
            (r,n)->new Source(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6)),w,s)
            .stream().findFirst().orElseThrow(Problem::missing);
    }

    @GetMapping("/workspaces/{w}/import-jobs/{s}")
    Map<String,Object> status(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable UUID s) {
        var workspace=ws.owned(owner,w);
        var source=source(w,s);
        var result=new HashMap<String,Object>(Map.of("id",s,"filename",source.filename(),"format",source.format(),"state",source.state()));
        if (needsWorkbookReanalysis(source) && s.equals(workspace.sourceId())
            && workspace.status().equals("DRAFT") && workspace.roadmapId()==null) {
            result.put("canReanalyze",true);
        }
        if (source.error()!=null) result.put("message",source.error());
        if (source.analysis()!=null) result.put("analysis",publicAnalysis(ws.json.mapper.valueToTree(
            parser.prepare(ws.json.as(ws.json.read(source.analysis()),ImportParser.Analysis.class),ws.json.read(source.profile())))));
        return result;
    }

    @PostMapping("/workspaces/{w}/imports/{s}/reanalyze")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    Map<String,Object> reanalyze(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable UUID s,@RequestBody JsonNode input) {
        var workspace=ws.owned(owner,w,true); ws.draft(workspace); ws.expect(workspace,Json.revision(input));
        if (!s.equals(workspace.sourceId())) throw Problem.missing();
        var source=source(w,s);
        if (workspace.roadmapId()!=null || !needsWorkbookReanalysis(source))
            throw Problem.conflict("Only an unconfirmed workbook from the previous importer can be reanalyzed.");
        ws.db.update("""
            UPDATE roadmap_sources SET profile=?::jsonb,state='QUEUED',analysis=NULL,error=NULL,
                attempts=0,claim_token=NULL,lease_until=NULL WHERE workspace_id=? AND id=?
            """,profile(workspace,source.format()),w,s);
        ws.changed(w);
        return Map.of("sourceId",s,"jobId",s,"revision",workspace.revision()+1);
    }

    @PutMapping("/workspaces/{w}/imports/{s}/mapping")
    @Transactional
    Map<String,Object> remap(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable UUID s,@RequestBody JsonNode input) {
        var workspace=ws.owned(owner,w,true); ws.draft(workspace); ws.expect(workspace,Json.revision(input));
        if (!s.equals(workspace.sourceId())) throw Problem.missing();
        var source=source(w,s);
        if (!source.state().equals("READY")) throw Problem.conflict("Wait for the roadmap to finish parsing.");
        var analysis=parser.prepare(parser.remap(ws.json.as(ws.json.read(source.analysis()),ImportParser.Analysis.class),
            ws.json.read(source.profile()),input),ws.json.read(source.profile()));
        String updated=ws.json.write(analysis);
        ws.db.update("UPDATE roadmap_sources SET analysis=?::jsonb WHERE workspace_id=? AND id=?",updated,w,s);
        ws.db.update("UPDATE onboarding_drafts SET roadmap_id=NULL,step=3 WHERE workspace_id=?",w);
        ws.changed(w);
        return Map.of("analysis",publicAnalysis(ws.json.read(updated)),"revision",workspace.revision()+1,"step",3);
    }

    @PostMapping("/workspaces/{w}/imports/{s}/estimates")
    @Transactional
    Map<String,Object> estimates(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable UUID s,@RequestBody JsonNode input) {
        var workspace=ws.owned(owner,w,true); ws.draft(workspace); ws.expect(workspace,Json.revision(input));
        if (!s.equals(workspace.sourceId())) throw Problem.missing();
        var source=source(w,s);
        if (!source.state().equals("READY")) throw Problem.conflict("Wait for the roadmap to finish parsing.");
        if (workspace.roadmapId()!=null) throw Problem.conflict("Study estimates can only be applied to an unconfirmed import.");
        var candidate=input.path("roadmap");
        if (!candidate.isObject() || ws.json.write(candidate).length()>1_200_000)
            throw Problem.invalid("Submit an edited roadmap under 1.2 million characters.");
        var analysis=parser.prepare(parser.applyEstimates(ws.json.as(ws.json.read(source.analysis()),ImportParser.Analysis.class),
            ws.json.read(source.profile()),candidate,input.path("estimates")),ws.json.read(source.profile()));
        String updated=ws.json.write(analysis);
        String upgradedProfile=source.format().equals("xlsx") ? profile(workspace,"xlsx") : source.profile();
        ws.db.update("UPDATE roadmap_sources SET analysis=?::jsonb,profile=?::jsonb WHERE workspace_id=? AND id=?",
            updated,upgradedProfile,w,s);
        ws.changed(w);
        return Map.of("analysis",publicAnalysis(ws.json.read(updated)),"revision",workspace.revision()+1,"step",3);
    }

    @PostMapping("/workspaces/{w}/imports/{s}/confirm")
    @Transactional
    Map<String,Object> confirm(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable UUID s,@RequestBody JsonNode input) {
        var workspace=ws.owned(owner,w,true); ws.draft(workspace); ws.expect(workspace,Json.revision(input));
        if (!s.equals(workspace.sourceId())) throw Problem.missing();
        var source=source(w,s);
        if (!source.state().equals("READY")) throw Problem.conflict("Wait for the roadmap to finish parsing.");
        var candidate=input.path("roadmap");
        if (!candidate.isObject() || ws.json.write(candidate).length()>1_200_000)
            throw Problem.invalid("Submit an edited roadmap under 1.2 million characters.");
        var analysis=parser.review(ws.json.as(ws.json.read(source.analysis()),ImportParser.Analysis.class),ws.json.read(source.profile()),candidate);
        var confirmed=roadmap.confirmed(analysis.candidate());
        UUID id=roadmaps.insert(w,s,confirmed);
        ws.db.update("UPDATE roadmap_sources SET analysis=?::jsonb WHERE workspace_id=? AND id=?",ws.json.write(analysis),w,s);
        var setup=(ObjectNode)workspace.data().deepCopy();
        if (analysis.workbookSchedule()==null) setup.remove("workbookSchedule");
        else setup.set("workbookSchedule",ws.json.mapper.valueToTree(analysis.workbookSchedule()));
        ws.db.update("UPDATE onboarding_drafts SET roadmap_id=?,step=GREATEST(step,4),data=?::jsonb WHERE workspace_id=?",id,ws.json.write(setup),w);
        ws.changed(w);
        return Map.of("roadmapId",id,"revision",workspace.revision()+1,"step",Math.max(workspace.step(),4),
            "roadmap",confirmed,"analysis",publicAnalysis(ws.json.mapper.valueToTree(analysis)),"data",setup);
    }

    @GetMapping("/workspaces/{w}/roadmap-versions/{v}")
    Roadmap.Document roadmap(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable UUID v,
                             @RequestParam(defaultValue="0") int page) {
        ws.owned(owner,w);
        if (page<0 || page>50) throw Problem.invalid("Choose a valid roadmap page.");
        return roadmaps.page(w,v,page*100,100);
    }

    @GetMapping("/workspaces/{w}/roadmap-sources/{s}/download")
    ResponseEntity<byte[]> download(@AuthenticationPrincipal UUID owner,@PathVariable UUID w,@PathVariable UUID s) {
        ws.owned(owner,w);
        var meta=source(w,s);
        var bytes=ws.db.queryForObject("SELECT content FROM roadmap_sources WHERE workspace_id=? AND id=?",byte[].class,w,s);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).cacheControl(CacheControl.noStore())
            .header("Content-Disposition",ContentDisposition.attachment().filename(meta.filename(),StandardCharsets.UTF_8).build().toString()).body(bytes);
    }

    private JsonNode publicAnalysis(JsonNode analysis) {
        ObjectNode result=ws.json.object().setAll((ObjectNode)analysis);
        var parsed=ws.json.as(analysis,ImportParser.Analysis.class);
        result.set("estimateSuggestions",ws.json.mapper.valueToTree(parser.estimateSuggestions(parsed,parsed.candidate())));
        result.remove(List.of("rows","sheets"));
        addSampleRows(result,analysis.path("rows"));
        if (!analysis.path("sheets").isEmpty()) {
            var sheets=result.putArray("sheets");
            for (var sheet:analysis.path("sheets")) {
                ObjectNode view=ws.json.object().setAll((ObjectNode)sheet);
                view.remove("rows");
                var rows=sheet.path("rows");
                var mapping=sheet.path("mapping");
                int titleColumn=mapping.path("title").asInt(-1), count=0;
                var examples=view.putArray("sampleTopics");
                if (titleColumn>=0) {
                    for (var row:rows) {
                        String title=row.path("cells").path(titleColumn).asText("").strip();
                        if (title.isEmpty()) continue;
                        count++;
                        if (examples.size()<3) examples.add(title);
                    }
                }
                view.put("topicCount",count);
                addSampleRows(view,rows);
                sheets.add(view);
            }
        }
        return result;
    }

    private void addSampleRows(ObjectNode result,JsonNode rows) {
        if (rows.isArray() && !rows.isEmpty()) {
            var sample=result.putArray("sampleRows");
            for (int i=0;i<Math.min(rows.size(),3);i++) sample.add(rows.get(i));
        }
    }
}
