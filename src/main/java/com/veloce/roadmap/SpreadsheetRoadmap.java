package com.veloce.roadmap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloce.common.Problem;
import com.veloce.roadmap.ImportParser.Analysis;
import com.veloce.roadmap.ImportParser.Row;
import com.veloce.roadmap.ImportParser.Worksheet;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Pattern;

final class SpreadsheetRoadmap {
    enum Role { TOPICS, SUPPLEMENT, REFERENCE, UNMAPPED }

    private static final Map<String,List<String>> ALIASES = Map.ofEntries(
        Map.entry("id", List.of("id", "topicid", "activityid")),
        Map.entry("parentId", List.of("parentid", "moduleid")),
        Map.entry("title", List.of("title", "topictitle", "subtopic", "topic", "subphase", "realworldproject", "projecttitle",
            "activitytitle", "lessontitle", "tasktitle", "lesson", "activity", "task", "project", "subject",
            "requiredpracticaldemo", "reviewquestions", "scenarioquestion", "interviewquestion", "question", "name")),
        Map.entry("section", List.of("phase", "phasename", "learningphase", "section", "sectionname", "module",
            "modulename", "category", "chapter", "unit", "track")),
        Map.entry("kind", List.of("kind", "activitykind", "activitytype")),
        Map.entry("estimatedMinutes", List.of("estimatedminutes", "minutes", "durationminutes", "effortminutes",
            "studyminutes", "plannedminutes", "timeminutes", "durationmins", "estimatedmins", "estimatedhours",
            "estimatedhrs", "esthours", "estimatedefforthours", "plannedhours", "plannedhrs", "fullbuildesthrs", "efforthours",
            "durationhours", "durationhrs", "studyhours", "hours", "duration", "timeestimate", "effort")),
        Map.entry("dependsOn", List.of("dependson", "prerequisiteids", "dependencyids", "prerequisites",
            "prerequisite", "dependencies")),
        Map.entry("priority", List.of("priority")),
        Map.entry("outcome", List.of("outcome", "learningoutcome", "objective", "dayexitcriteria",
            "acceptancecriteria", "passcriterion", "expectedapproachformat", "answerstructure", "deliverable",
            "theorydeliverable", "learningobjective", "description")),
        Map.entry("resourceUrl", List.of("resourceurl", "learningreferenceurl", "referenceurl", "resourcelink",
            "learninglink", "repositoryurl", "link", "url"))
    );
    private static final Map<String,String> DETAIL_LABELS = Map.ofEntries(
        Map.entry("deepconcepts", "Concepts"),
        Map.entry("theorydeliverable", "Theory deliverable"),
        Map.entry("basiccodingtask", "Coding practice"),
        Map.entry("scenariocodingedgecases", "Scenario and edge cases"),
        Map.entry("projectworkstream", "Project workstream"),
        Map.entry("projecttask", "Project sprint"),
        Map.entry("topic", "Topic"),
        Map.entry("projectdeliverableproof", "Project deliverable"),
        Map.entry("interviewprompt", "Interview prompt"),
        Map.entry("scopemvp", "Scope"),
        Map.entry("scopeprototype", "Scope"),
        Map.entry("featuresfullscope", "Features"),
        Map.entry("fullbuildextension", "Full build extension"),
        Map.entry("techstack", "Tech stack"),
        Map.entry("interviewarchitecturetopic", "Interview topic"),
        Map.entry("scenarioquestion", "Scenario question"),
        Map.entry("interviewquestion", "Interview question"),
        Map.entry("question", "Question"),
        Map.entry("prerequisites", "Prerequisites"),
        Map.entry("prerequisite", "Prerequisite"),
        Map.entry("notes", "Notes")
    );
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]");
    private static final Pattern DURATION = Pattern.compile(
        "(\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|h|minutes?|mins?|m)?(?:\\s*(\\d+)\\s*(?:minutes?|mins?|m))?");
    private static final Pattern CLOCK_DURATION = Pattern.compile("(\\d+):([0-5]\\d)");
    private static final Pattern PROJECT_TASK = Pattern.compile("(?:\\d+(?:min(?:utes?)?|m|hours?|hrs?|h))?project(?:task|sprint)");
    private static final Set<String> REFERENCE_SHEETS = Set.of("starthere", "readme", "overview", "phaseplan",
        "dailyreport", "dailytimetable", "fivereminders", "dashboard", "appbuildprompt", "calendarguide", "sourcenotes");

    private final ImportParser parser;

    SpreadsheetRoadmap(ImportParser parser) {
        this.parser = parser;
    }

    List<Worksheet> classify(List<Worksheet> sheets) {
        boolean dailyPlan = sheets.stream().anyMatch(sheet -> isDailyPlan(sheet.headers()));
        return sheets.stream().map(sheet -> {
            var headers = keys(sheet.headers());
            var mapping = new LinkedHashMap<>(sheet.mapping());
            // The weekly report's planned hours are a roll-up of the days in that week.
            if (headers.containsAll(List.of("reviewquestions", "days", "actualhoursauto"))) mapping.remove("estimatedMinutes");
            // In the project backlog this column describes prior knowledge in prose.
            if (headers.contains("realworldproject") && prosePrerequisite(mapping, headers)) mapping.remove("dependsOn");

            Role role = sheet.role();
            if (role == Role.TOPICS && dailyPlan && isSupplement(headers)) role = Role.SUPPLEMENT;
            return new Worksheet(sheet.id(), sheet.name(), sheet.headerLocation(), sheet.headers(), sheet.rows(),
                mapping, role == Role.TOPICS, role);
        }).toList();
    }

    Worksheet worksheet(String id, String name, List<Row> scannedRows) {
        int headerIndex = 0;
        int bestScore = -1;
        int firstTableRow = -1;
        for (int i = 0; i < Math.min(32, scannedRows.size()); i++) {
            var cells = scannedRows.get(i).cells();
            var mapping = guess(keys(cells));
            int populated = (int) cells.stream().filter(cell -> !cell.isBlank()).count();
            if (firstTableRow < 0 && populated >= 2) firstTableRow = i;
            if (mapping.isEmpty() || (populated == 1 && firstTableRow < 0
                && i + 1 < scannedRows.size() && scannedRows.get(i + 1).cells().stream().filter(cell -> !cell.isBlank()).count() > 1)) continue;
            int score = (mapping.containsKey("title") ? 100 : 0) + 20 * mapping.size() + Math.min(populated, 12);
            if (score > bestScore) {
                bestScore = score;
                headerIndex = i;
            }
        }
        if (bestScore < 0 && firstTableRow >= 0) headerIndex = firstTableRow;
        var header = scannedRows.get(headerIndex);
        var keys = keys(header.cells());
        var mapping = guess(keys);
        Role role = Role.UNMAPPED;
        if (isReference(name, scannedRows, mapping)) {
            // Keep reference tabs discoverable by name. Their cells are not part of the import.
            return new Worksheet(id, name, header.location(), List.of(), List.of(), Map.of(), false, Role.REFERENCE);
        }
        if (mapping.containsKey("title")) role = Role.TOPICS;
        var rows = scannedRows.subList(headerIndex + 1, scannedRows.size()).stream()
            .filter(row -> !keys(row.cells()).equals(keys)).toList();
        return new Worksheet(id, name, header.location(), header.cells(), rows, mapping, false, role);
    }

    private boolean isReference(String name, List<Row> rows, Map<String,Integer> mapping) {
        if (key(name).equals("appbuildprompt")) return true;
        if (rows.stream().limit(32).map(row -> keys(row.cells())).anyMatch(cells ->
            cells.contains("promptimplementationrequirement") || cells.containsAll(List.of("item", "value", "guidance"))))
            return true;
        return !mapping.containsKey("title") && REFERENCE_SHEETS.contains(key(name));
    }

    private boolean isDailyPlan(List<String> headers) {
        var keys = keys(headers);
        return keys.containsAll(List.of("day", "subphase", "plannedhours"))
            && (keys.contains("dayexitcriteria") || keys.stream().anyMatch(key -> PROJECT_TASK.matcher(key).matches()));
    }

    private boolean isSupplement(List<String> keys) {
        return keys.containsAll(List.of("realworldproject", "fullbuildesthrs"))
            || keys.containsAll(List.of("day", "scenarioquestion", "expectedapproachformat"))
            || keys.containsAll(List.of("day", "question", "answerstructure"))
            || keys.containsAll(List.of("requiredpracticaldemo", "passcriterion"))
            || keys.containsAll(List.of("reviewquestions", "actualhoursauto"));
    }

    Analysis workbook(List<Worksheet> sheets, JsonNode profile) {
        return build(sheets, profile, true);
    }

    Analysis csv(List<String> headers, List<Row> rows, JsonNode profile, Map<String,Integer> mapping) {
        if (headers.isEmpty()) throw Problem.invalid("The table has no column headers.");
        if (rows.size() > ImportParser.NODE_LIMIT) throw Problem.invalid("Import up to 5,000 topic rows at a time.");
        var table = new Worksheet("csv", "CSV", null, headers, rows, mapping == null ? guess(keys(headers)) : mapping,
            true, Role.TOPICS);
        return build(List.of(table), profile, false);
    }

    Analysis remap(Analysis old, JsonNode profile, JsonNode input) {
        if (old.sheets().isEmpty()) {
            if (old.headers().isEmpty()) throw Problem.invalid("Column mapping is available for CSV and XLSX files.");
            return csv(old.headers(), old.rows(), profile, mapping(input.path("mapping"), old.headers().size()));
        }
        String id = input.path("sheetId").asText();
        var sheet = old.sheets().stream().filter(s -> s.id().equals(id)).findFirst()
            .orElseThrow(() -> Problem.invalid("Choose a workbook sheet."));
        if (sheet.role() == Role.REFERENCE) throw Problem.invalid("Choose a sheet containing study topics.");
        if (input.has("included") && !input.path("included").isBoolean()) throw Problem.invalid("Choose whether to include the sheet.");
        var mapping = input.has("mapping") ? mapping(input.path("mapping"), sheet.headers().size()) : sheet.mapping();
        boolean included = input.path("included").asBoolean(sheet.included());
        if (included && !mapping.containsKey("title")) throw Problem.invalid("Map the topic title column before including the sheet.");
        if (!input.has("mapping") && !input.has("included")) throw Problem.invalid("Choose a mapping or whether to include the sheet.");
        var changed = new Worksheet(sheet.id(), sheet.name(), sheet.headerLocation(), sheet.headers(), sheet.rows(),
            mapping, included, sheet.role());
        return workbook(old.sheets().stream().map(s -> s.id().equals(id) ? changed : s).toList(), profile);
    }

    Analysis review(Analysis old, JsonNode profile, JsonNode candidate) {
        var warnings = old.sheets().isEmpty() && old.headers().isEmpty() ? old.warnings()
            : (old.sheets().isEmpty() ? csv(old.headers(),old.rows(),profile,old.mapping())
                : workbook(old.sheets(),profile)).warnings();
        var sources = new HashMap<String,String>();
        for (int i=0;i<old.candidate().path("nodes").size() && i<old.locations().size();i++)
            sources.put(old.candidate().path("nodes").get(i).path("id").asText(),old.locations().get(i));
        var locations = new ArrayList<String>();
        for (var node:candidate.path("nodes")) locations.add(sources.getOrDefault(node.path("id").asText(),"Edited activity"));
        return new Analysis(candidate,warnings.stream().filter(w->!w.startsWith("Review study time for ")).toList(),
            parser.issues(candidate),old.headers(),old.rows(),old.mapping(),locations,old.extractedText(),old.sheets());
    }

    private Map<String,Integer> mapping(JsonNode selection, int columns) {
        if (!selection.isObject()) throw Problem.invalid("Choose a column mapping.");
        var result = new LinkedHashMap<String,Integer>();
        selection.properties().forEach(e -> {
            if (!ALIASES.containsKey(e.getKey()) || !e.getValue().isIntegralNumber() || !e.getValue().canConvertToInt()
                || e.getValue().asInt() < 0 || e.getValue().asInt() >= columns) throw Problem.invalid("Choose valid column indices.");
            result.put(e.getKey(), e.getValue().asInt());
        });
        return result;
    }

    private Map<String,Integer> guess(List<String> headers) {
        var mapping = new LinkedHashMap<String,Integer>();
        ALIASES.forEach((field, aliases) -> {
            for (String alias : aliases) {
                int index = headers.indexOf(alias);
                if (index < 0) continue;
                if (index == headers.lastIndexOf(alias)) mapping.put(field, index);
                break;
            }
        });
        int topic = headers.indexOf("topic");
        if (Objects.equals(mapping.get("title"), headers.indexOf("subtopic")) && topic >= 0 && topic == headers.lastIndexOf("topic"))
            mapping.putIfAbsent("section", topic);
        return mapping;
    }

    private Analysis build(List<Worksheet> tables, JsonNode profile, boolean workbook) {
        var doc = parser.document(profile);
        var nodes = (ArrayNode) doc.path("nodes");
        var locations = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        var included = tables.stream().filter(Worksheet::included).toList();
        var usedIds = new HashSet<String>();
        for (var table : tables) {
            for (var row : table.rows()) {
                String id = cell(row, table.mapping().get("id"));
                if (!id.isEmpty()) usedIds.add(id);
            }
        }
        boolean multipleTopicSheets = tables.stream().filter(t -> t.role() == Role.TOPICS || t.role() == Role.SUPPLEMENT
            || (t.included() && t.role() == Role.UNMAPPED)).count() > 1;
        for (var table : included) append(table, workbook, multipleTopicSheets, usedIds, nodes, locations, warnings);
        if (included.isEmpty()) warnings.add("Select a sheet with study topics or map its title column to start the import.");
        var first = tables.getFirst();
        return new Analysis(doc, warnings, parser.issues(doc), workbook ? List.of() : first.headers(),
            workbook ? List.of() : first.rows(), workbook ? Map.of() : first.mapping(), locations, null,
            workbook ? tables : List.of());
    }

    private void append(Worksheet table, boolean workbook, boolean multipleTopicSheets, Set<String> usedIds, ArrayNode nodes,
                        List<String> locations, List<String> warnings) {
        var mapping = table.mapping();
        String sheetLabel = workbook ? "“" + table.name() + "”" : "the CSV";
        if (!mapping.containsKey("title")) {
            warnings.add("Map the topic title column in " + sheetLabel + " before confirming.");
            return;
        }
        var headers = keys(table.headers());
        var details = new LinkedHashMap<Integer,String>();
        if (workbook) {
            for (int i = 0; i < headers.size(); i++) {
                String header = headers.get(i);
                String label = DETAIL_LABELS.get(PROJECT_TASK.matcher(header).matches() ? "projecttask" : header);
                if (label != null && !mapping.containsValue(i)) details.put(i, label);
            }
        }
        var mappedColumns = new HashSet<>(mapping.values());
        mappedColumns.addAll(details.keySet());
        var sections = new LinkedHashMap<String,String>();
        String sheetModule = null, currentSection = "";
        boolean explicitParents = mapping.containsKey("parentId");
        boolean useSheetModule = workbook && !explicitParents && (table.role() == Role.SUPPLEMENT
            || (multipleTopicSheets && !mapping.containsKey("section")));
        String defaultKind = workbook ? kind(headers) : "LESSON";
        int skipped = 0, formulas = 0, missing = 0;
        for (int index = 0; index < table.rows().size(); index++) {
            var row = table.rows().get(index);
            if (repeatedHeader(table, row) || aggregateFooter(table, row)) continue;
            String title = cell(row, mapping.get("title"));
            for (int formula : row.formulas()) if (mappedColumns.contains(formula)) formulas++;
            String section = cell(row, mapping.get("section"));
            if (!section.isEmpty()) currentSection = section;
            if (title.isEmpty()) {
                boolean sectionLabel = !section.isEmpty() && mappedColumns.stream()
                    .filter(col -> !col.equals(mapping.get("section")))
                    .noneMatch(col -> !cell(row, col).isEmpty() || row.formulas().contains(col));
                if (!sectionLabel) skipped++;
                continue;
            }

            String id = cell(row, mapping.get("id"));
            if (id.isEmpty()) id = unique((workbook ? table.id() + "-row-" : "topic-") + (index + 1), usedIds);
            String parent = cell(row, mapping.get("parentId"));
            if (!explicitParents) {
                if (useSheetModule && sheetModule == null) {
                    sheetModule = unique(table.id() + "-module", usedIds);
                    addModule(nodes, locations, sheetModule, table.name(), null, table.headerLocation());
                }
                if (!currentSection.isEmpty()) {
                    parent = sections.get(currentSection);
                    if (parent == null) {
                        parent = unique(table.id() + "-section-" + (index + 1), usedIds);
                        sections.put(currentSection, parent);
                        addModule(nodes, locations, parent, currentSection, sheetModule, row.location());
                    }
                } else {
                    parent = sheetModule;
                }
            }
            String kind = cell(row, mapping.get("kind")).toUpperCase(Locale.ROOT);
            if (kind.isEmpty()) kind = defaultKind;
            String rawDuration = cell(row, mapping.get("estimatedMinutes"));
            Integer minutes = duration(rawDuration, mapping.get("estimatedMinutes"), headers);
            if (kind.equals("MODULE") && rawDuration.isEmpty()) minutes = 0;
            if (!kind.equals("MODULE") && (minutes == null || minutes <= 0 || minutes > 43_200)) missing++;
            var node = parser.node(id, title, parent == null || parent.isEmpty() ? null : parent, kind, minutes);
            String priority = cell(row, mapping.get("priority")).toUpperCase(Locale.ROOT);
            if (!priority.isEmpty()) node.put("priority", priority);
            String outcome = outcome(row, mapping.get("outcome"), details);
            if (outcome.isEmpty() && headers.contains("reviewquestions")) {
                outcome = "Answer the review questions and identify topics to revisit.";
            }
            node.put("outcome", outcome);
            var deps = (ArrayNode) node.path("dependsOn");
            for (String dependency : cell(row, mapping.get("dependsOn")).split("[,;\\s]+")) {
                if (!dependency.isBlank()) deps.add(dependency);
            }
            String url = cell(row, mapping.get("resourceUrl"));
            if (!url.isEmpty()) ((ArrayNode) node.path("resources")).addObject().put("title", "Resource").put("url", url);
            add(nodes, locations, node, row.location());
        }
        if (skipped > 0) warnings.add("Skipped " + skipped + (skipped == 1 ? " row" : " rows")
            + " without a topic title in " + sheetLabel + ".");
        if (formulas > 0) warnings.add("Left " + formulas + " mapped formula cells blank in " + sheetLabel + ".");
        if (missing > 0) warnings.add("Review study time for " + missing + " activities in " + sheetLabel + ".");
    }

    private void addModule(ArrayNode nodes, List<String> locations, String id, String title, String parent, String location) {
        add(nodes, locations, parser.node(id, title, parent, "MODULE", 0).put("outcome", "Complete the activities in this section."),
            location);
    }

    private void add(ArrayNode nodes, List<String> locations, ObjectNode node, String location) {
        if (nodes.size() >= ImportParser.NODE_LIMIT) throw Problem.invalid("The roadmap exceeds 5,000 topics and sections.");
        nodes.add(node);
        locations.add(location);
    }

    private String unique(String base, Set<String> used) {
        String id = base;
        for (int suffix = 2; !used.add(id); suffix++) id = base + "-" + suffix;
        return id;
    }

    private String kind(List<String> keys) {
        if (keys.contains("realworldproject") || keys.contains("projecttitle") || keys.contains("project")) return "PROJECT";
        if (keys.contains("requiredpracticaldemo")) return "ASSESSMENT";
        if (keys.contains("scenarioquestion")) return "PRACTICE";
        if (keys.contains("answerstructure") || keys.contains("reviewquestions")) return "REVIEW";
        return "LESSON";
    }

    private String outcome(Row row, Integer column, Map<Integer,String> details) {
        var text = new StringJoiner("\n\n");
        String outcome = cell(row, column);
        if (!outcome.isEmpty()) text.add(outcome);
        details.forEach((index, label) -> {
            String value = cell(row, index);
            if (!value.isEmpty()) text.add(label + "\n" + value);
        });
        return text.toString();
    }

    private Integer duration(String raw, Integer column, List<String> headers) {
        if (raw.isEmpty() || raw.length() > 40) return null;
        String value = raw.toLowerCase(Locale.ROOT);
        if (value.matches("\\d{1,3}(,\\d{3})+(\\.\\d+)?")) value = value.replace(",", "");
        var clock = CLOCK_DURATION.matcher(value);
        var match = DURATION.matcher(value);
        try {
            BigDecimal minutes;
            if (clock.matches()) {
                minutes = new BigDecimal(clock.group(1)).multiply(BigDecimal.valueOf(60)).add(new BigDecimal(clock.group(2)));
            } else if (match.matches()) {
                String unit = match.group(2);
                String header = headers.get(column);
                boolean hours = unit == null ? header.endsWith("hours") || header.endsWith("hrs") : unit.startsWith("h");
                if (!hours && match.group(3) != null) return null;
                minutes = new BigDecimal(match.group(1));
                if (hours) minutes = minutes.multiply(BigDecimal.valueOf(60));
                if (match.group(3) != null) minutes = minutes.add(new BigDecimal(match.group(3)));
            } else {
                return null;
            }
            return minutes.setScale(0, RoundingMode.HALF_UP).intValueExact();
        } catch (ArithmeticException ex) {
            return null;
        }
    }

    private boolean prosePrerequisite(Map<String,Integer> mapping, List<String> headers) {
        Integer column = mapping.get("dependsOn");
        return column != null && Set.of("prerequisite", "prerequisites").contains(headers.get(column));
    }

    private static boolean aggregateFooter(Worksheet sheet, Row row) {
        Integer title = sheet.mapping().get("title");
        if (title == null || !cell(row,title).isEmpty() || row.formulas().contains(title)
            || !cell(row,sheet.mapping().get("id")).isEmpty()) return false;
        boolean aggregate = false;
        for (String cell:row.cells()) {
            String value = cell.strip().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) continue;
            if (Set.of("total","grand total","subtotal").contains(value)) aggregate = true;
            else if (!value.matches("-?[0-9][0-9,]*(?:\\.[0-9]+)?%?")) return false;
        }
        return aggregate;
    }

    private static boolean repeatedHeader(Worksheet sheet, Row row) {
        Integer column = sheet.mapping().get("title");
        return column != null && cell(row, column).equalsIgnoreCase(sheet.headers().get(column).strip())
            && keys(row.cells()).equals(keys(sheet.headers()));
    }

    private static String cell(Row row, Integer column) {
        return column == null || column >= row.cells().size() ? "" : row.cells().get(column).strip();
    }

    private static List<String> keys(List<String> headers) {
        return headers.stream().map(SpreadsheetRoadmap::key).toList();
    }

    private static String key(String header) {
        return NON_ALPHANUMERIC.matcher(header.toLowerCase(Locale.ROOT)).replaceAll("");
    }
}
