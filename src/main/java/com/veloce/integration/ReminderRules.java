package com.veloce.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloce.common.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

public record ReminderRules(String channel, List<Rule> rules, String quietStart, String quietEnd,
                            boolean genericTitles, List<DayReminder> dayReminders) {
    public record Rule(String scope, int offset) {}
    public record DayReminder(String title,int offsetFromFirstStudy) {}
    public record CalendarReminder(UUID id,String title,Instant at) {}
    public static ReminderRules from(JsonNode input,Json json) {
        if (input.isMissingNode() || input.isNull()) return new ReminderRules("OFF",List.of(),"22:00","07:00",false,List.of());
        if (!input.isObject()) throw Problem.invalid("Check your reminder preferences.");
        String channel=input.path("channel").asText("OFF");
        if (!Set.of("OFF","APP","CALENDAR","BOTH").contains(channel)) throw Problem.invalid("Choose a reminder channel.");
        String start=input.path("quietStart").asText("22:00"),end=input.path("quietEnd").asText("07:00");
        for (String value:List.of(start,end)) {
            try { if (!value.matches("\\d\\d:\\d\\d")) throw new DateTimeException(""); LocalTime.parse(value); }
            catch (DateTimeException e) { throw Problem.invalid("Enter valid quiet hours."); }
        }
        var rules=new LinkedHashSet<Rule>();
        if (!input.path("rules").isMissingNode() && (!input.path("rules").isArray() || input.path("rules").size()>10))
            throw Problem.invalid("Use at most ten reminder rules.");
        for (JsonNode r:input.path("rules")) {
            String scope=r.path("scope").asText();
            int offset=r.path("offset").asInt(-1);
            if (!Set.of("FIRST","ALL","PROJECT","REVIEW","ASSESSMENT").contains(scope)
                || !r.path("offset").isIntegralNumber() || !r.path("offset").canConvertToInt() || offset<0 || offset>120)
                throw Problem.invalid("Choose a reminder scope and an offset from 0 to 120 minutes.");
            rules.add(new Rule(scope,offset));
        }
        var dayReminders=new LinkedHashSet<DayReminder>();
        var daily=input.path("dayReminders");
        if (!daily.isMissingNode() && (!daily.isArray() || daily.size()>10))
            throw Problem.invalid("Use at most ten timed reminders per study day.");
        for (var reminder:daily) {
            var offset=reminder.path("offsetFromFirstStudy");
            if (!offset.isIntegralNumber() || !offset.canConvertToInt() || offset.asInt() < -120 || offset.asInt() > 780)
                throw Problem.invalid("Review the timed study reminders.");
            dayReminders.add(new DayReminder(Json.text(reminder,"title",120,true),offset.asInt()));
        }
        return new ReminderRules(channel,List.copyOf(rules),start,end,input.path("genericTitles").asBoolean(),List.copyOf(dayReminders));
    }

    public List<Integer> offsets(boolean first,String kind,Instant starts,ZoneId zone) {
        return rules.stream().filter(r->r.scope.equals("ALL") || r.scope.equals("FIRST") && first || r.scope.equals(kind))
            .map(Rule::offset).filter(offset->!quiet(starts.minusSeconds(offset*60L).atZone(zone).toLocalTime())).distinct().sorted().toList();
    }

    public List<CalendarReminder> calendarReminders(UUID workspace, LocalDate date, Instant firstStudy, ZoneId zone) {
        if (!Set.of("CALENDAR","BOTH").contains(channel)) return List.of();
        var reminders=new ArrayList<CalendarReminder>();
        for (int index=0;index<dayReminders.size();index++) {
            var rule=dayReminders.get(index);
            var at=firstStudy.plusSeconds(rule.offsetFromFirstStudy()*60L);
            var local=at.atZone(zone);
            if (!local.toLocalDate().equals(date) || quiet(local.toLocalTime())) continue;
            UUID id=UUID.nameUUIDFromBytes(("study-reminder/"+workspace+"/"+date+"/"+index).getBytes(StandardCharsets.UTF_8));
            reminders.add(new CalendarReminder(id,genericTitles?"Veloce study":rule.title(),at));
        }
        return reminders;
    }

    private boolean quiet(LocalTime value) {
        var start=LocalTime.parse(quietStart); var end=LocalTime.parse(quietEnd);
        if (start.equals(end)) return false;
        return start.isBefore(end) ? !value.isBefore(start) && value.isBefore(end) : !value.isBefore(start) || value.isBefore(end);
    }
}
