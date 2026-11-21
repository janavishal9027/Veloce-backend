package com.veloce.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloce.common.Json;
import com.veloce.common.Problem;
import com.veloce.roadmap.Roadmap.Resource;
import java.util.*;
import java.util.regex.Pattern;

final class PreparationGuide {
    record Activity(String id, String title, String kind, String outcome, int minutes, List<Resource> resources) {}
    record Step(String title, int minutes, List<String> concepts, String instructions, String deliverable) {}
    record Generation(String provider, String model, int attempts) {}
    record Chart(String title, String summary, List<String> prerequisites, List<Step> steps,
                 List<Resource> sources, int minutes, int budgetMinutes, String mode, Generation generation) {
        Chart(String title, String summary, List<String> prerequisites, List<Step> steps,
              List<Resource> sources, int minutes, int budgetMinutes, String mode) {
            this(title,summary,prerequisites,steps,sources,minutes,budgetMinutes,mode,null);
        }
    }
    private record Part(String title, String content) {}

    private static final Set<String> HEADINGS = Set.of(
        "Concepts", "Theory deliverable", "Coding practice", "Scenario and edge cases",
        "Project workstream", "Project sprint", "Project deliverable", "Interview prompt",
        "Prerequisites", "Review", "Next steps");
    private static final Pattern ESTIMATE = Pattern.compile("^\\s*(\\d{1,3})\\s*(?:min(?:utes?)?|m)\\b", Pattern.CASE_INSENSITIVE);

    private PreparationGuide() {}

    static Chart fromModel(JsonNode value, Chart outline, Generation generation) {
        var rows=value.path("steps");
        if(!rows.isArray() || rows.isEmpty() || rows.size()>16) throw Problem.invalid("Choose valid preparation steps.");
        var steps=new ArrayList<Step>();
        int minutes=0;
        for(var row:rows) {
            int time=StudyRules.number(row,"minutes",1,outline.budgetMinutes(),0);
            if(time==0) throw Problem.invalid("Set time for each preparation step.");
            minutes+=time;
            steps.add(new Step(Json.text(row,"title",200,true),time,phrases(row.path("concepts")),
                Json.text(row,"instructions",2500,true),Json.text(row,"deliverable",1200,true)));
        }
        if(minutes!=outline.budgetMinutes()) throw Problem.invalid("Match the preparation time to the roadmap.");
        return new Chart(Json.text(value,"title",200,true),Json.text(value,"summary",1200,true),
            phrases(value.path("prerequisites")),List.copyOf(steps),outline.sources(),minutes,outline.budgetMinutes(),"AI",generation);
    }

    private static List<String> phrases(JsonNode values) {
        if(!values.isArray() || values.size()>16) throw Problem.invalid("Check the preparation concept list.");
        var result=new ArrayList<String>();
        for(var value:values) {
            if(!value.isTextual() || value.asText().isBlank() || value.asText().length()>400)
                throw Problem.invalid("Check the preparation concept list.");
            result.add(value.asText().strip());
        }
        return List.copyOf(result);
    }

    static Chart outline(List<Activity> activities, boolean deepen) {
        var prerequisites = new LinkedHashSet<String>();
        var steps = new ArrayList<Step>();
        var sources = new LinkedHashMap<String, Resource>();
        int budget = 0;
        for (var activity : activities) {
            budget += activity.minutes();
            activity.resources().forEach(resource -> sources.putIfAbsent(resource.url(), resource));
            var parts = sections(activity.outcome());
            String outcome = parts.remove("Day outcome");
            String before = parts.remove("Prerequisites");
            if (before != null) prerequisites.add(before);
            if (parts.isEmpty()) parts.put(activity.title(), outcome == null ? activity.title() : outcome);
            List<Part> rows = new ArrayList<>();
            parts.forEach((title, content) -> rows.add(new Part(title, content)));
            if (deepen && activity.minutes() > rows.size()) {
                rows.add(new Part("Extend " + activity.title(), extension(activity.kind(), parts)));
            }
            if (activity.minutes() < rows.size()) {
                String combined = String.join("\n\n", rows.stream().map(row -> row.title() + "\n" + row.content()).toList());
                rows.clear();
                rows.add(new Part(activity.title(), combined));
            }
            var weights = rows.stream().mapToInt(row -> weight(row.content())).toArray();
            int totalWeight = Arrays.stream(weights).sum();
            int elapsed = 0, weightBefore = 0;
            var concepts = concepts(parts.getOrDefault("Concepts", activity.title()));
            for (int index = 0; index < rows.size(); index++) {
                var row = rows.get(index);
                weightBefore += weights[index];
                int end = index + 1 + (activity.minutes() - rows.size()) * weightBefore / totalWeight;
                String title = activities.size() == 1 ? row.title() : activity.title() + " · " + row.title();
                String deliverable = index == rows.size() - 1 && !deepen && outcome != null
                    ? outcome : check(row.title());
                steps.add(new Step(title, end - elapsed, index == 0 ? concepts : List.of(),
                    ESTIMATE.matcher(row.content()).replaceFirst("").replaceFirst("^\\s*[:–-]\\s*", "").strip(),
                    deliverable));
                elapsed = end;
            }
        }
        String title = activities.size() == 1 ? activities.getFirst().title() : "Study plan for this day";
        String summary = deepen
            ? "Work through your roadmap and use the extra practice to test your understanding."
            : "A study guide built from your roadmap for this day.";
        return new Chart(title, summary, List.copyOf(prerequisites), List.copyOf(steps),
            List.copyOf(sources.values()), budget, budget, "ROADMAP");
    }

    private static LinkedHashMap<String, String> sections(String text) {
        var parts = new LinkedHashMap<String, String>();
        var body = new StringBuilder();
        String title = "Day outcome";
        for (var line : text.split("\\R")) {
            String candidate = line.strip();
            if (HEADINGS.contains(candidate)) {
                append(parts, title, body);
                title = candidate;
            } else {
                body.append(line).append('\n');
            }
        }
        append(parts, title, body);
        return parts;
    }

    private static void append(Map<String, String> parts, String title, StringBuilder body) {
        String content = body.toString().strip();
        if (!content.isEmpty()) parts.merge(title, content, (a, b) -> a + "\n\n" + b);
        body.setLength(0);
    }

    private static int weight(String content) {
        var estimate = ESTIMATE.matcher(content);
        return estimate.find() ? Math.max(1, Integer.parseInt(estimate.group(1))) : 15;
    }

    private static List<String> concepts(String content) {
        return Arrays.stream(content.split("[;\\n]+")).map(String::strip)
            .filter(value -> !value.isEmpty()).distinct().limit(24).toList();
    }

    private static String extension(String kind, Map<String, String> parts) {
        if (parts.containsKey("Scenario and edge cases")) {
            return "Change one assumption in the roadmap scenario. Predict the result, then test the alternate case and explain the difference.\n\n"
                + parts.get("Scenario and edge cases");
        }
        if (parts.containsKey("Interview prompt")) {
            return "Answer from memory, then expand your answer with a real example and one limitation.\n\n"
                + parts.get("Interview prompt");
        }
        return switch (kind) {
            case "PROJECT", "PRACTICE" -> "Try one variation on the deliverable. Compare the results and write down a trade-off.";
            case "ASSESSMENT", "REVIEW" -> "Explain one incorrect answer and create a short follow-up question for it.";
            default -> "Pick two of the day's concepts. Explain how they relate, give an example of each, and identify when each example would fail.";
        };
    }

    private static String check(String title) {
        return switch (title) {
            case "Concepts", "Theory deliverable" -> "Explain what you learned in your own words and give an example.";
            case "Coding practice", "Scenario and edge cases" -> "Keep the result and the evidence that it meets the task.";
            case "Project workstream", "Project sprint", "Project deliverable" -> "Capture the deliverable and verify it against the roadmap.";
            case "Interview prompt", "Review", "Next steps" -> "Record your answer and one thing to revisit.";
            default -> "Write down the result and one point to review.";
        };
    }
}
