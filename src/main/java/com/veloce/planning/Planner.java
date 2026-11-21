package com.veloce.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloce.common.Json;
import com.veloce.common.Problem;
import com.veloce.roadmap.Roadmap.*;
import java.time.*;
import java.time.format.TextStyle;
import java.time.zone.ZoneRules;
import java.util.*;

public final class Planner {
    public static final int VERSION=4;
    public static final int MAX_SLOTS=100_000;
    private Planner() {}

    public record Preparation(LocalDate start,LocalDate endExclusive,String zone,int dailyMinutes,
                              Set<Integer> weekdays,Set<LocalDate> excluded) {}
    public record Window(int day,String start,String end) {}
    public record FocusBlock(String title, int minutes, int breakBeforeMinutes) {
        public FocusBlock(String title, int minutes) { this(title,minutes,0); }
    }
    public record Settings(Preparation preparation,List<Window> windows,int focusMinutes,int breakMinutes,
                           int longBreakMinutes,int longBreakEvery,Set<String> indivisible,List<FocusBlock> sequence) {
        private static final Set<Integer> DAILY_MINUTE_OPTIONS = Set.of(60, 90, 120, 150, 180, 210, 360, 480);

        public Settings(Preparation p,List<Window> windows,int focus,int rest,int longRest,int every,Set<String> indivisible) {
            this(p,windows,focus,rest,longRest,every,indivisible,List.of());
        }

        public static void checkDailyHoursChoice(JsonNode input, JsonNode saved) {
            var minutes = input.path("dailyMinutes");
            // A saved custom value can still be reused while editing the other settings.
            if (minutes.equals(saved.path("dailyMinutes"))) return;
            if (!minutes.isIntegralNumber() || !minutes.canConvertToInt() || !DAILY_MINUTE_OPTIONS.contains(minutes.asInt()))
                throw Problem.invalid("Choose one of the listed daily study hours.");
        }

        public static Preparation preparation(JsonNode input,Clock clock,boolean reschedule) {
            String zone=text(input,"zone");
            if (!ZoneId.getAvailableZoneIds().contains(zone)) throw Problem.invalid("Choose a valid IANA timezone (for example Asia/Kolkata).");
            LocalDate start=date(input,"startDate");
            LocalDate today=LocalDate.now(clock.withZone(ZoneId.of(zone)));
            if (input.path("zoneConfirmed").isBoolean() && !input.path("zoneConfirmed").asBoolean())
                throw Problem.invalid("Confirm the timezone for this plan.");
            if (start.isBefore(today)) throw Problem.invalid("Choose today or a later start date.");
            if (start.isAfter(today.plusYears(5))) throw Problem.invalid("Choose a start date within the next five years.");
            LocalDate end=(input.has("duration")
                ? PreparationDuration.fromSelection(input.path("duration"))
                : savedDuration(input)).endExclusive(start);
            int dailyMinutes=number(input,"dailyMinutes",15,720);
            if (input.path("invalidMinutePart").asBoolean())
                throw Problem.invalid("Choose your daily study hours from the dropdown.");
            var days=new TreeSet<Integer>();
            if (!input.path("weekdays").isArray() || input.path("weekdays").size()>7) throw Problem.invalid("Select your study weekdays.");
            for (var d:input.path("weekdays")) {
                if (!d.isIntegralNumber() || !d.canConvertToInt() || d.asInt()<1 || d.asInt()>7)
                    throw Problem.invalid("Choose weekdays from Monday to Sunday.");
                days.add(d.asInt());
            }
            if (days.isEmpty()) throw Problem.invalid("Select at least one study weekday.");
            var excluded=new TreeSet<LocalDate>();
            if (!input.path("excludedDates").isMissingNode() &&
                (!input.path("excludedDates").isArray() || input.path("excludedDates").size()>732))
                throw Problem.invalid("Check the excluded dates.");
            for (var d:input.path("excludedDates")) {
                try { excluded.add(LocalDate.parse(d.asText())); }
                catch (DateTimeException e) { throw Problem.invalid("Use a valid excluded date."); }
            }
            return new Preparation(start,end,zone,dailyMinutes,Collections.unmodifiableSet(days),Collections.unmodifiableSet(excluded));
        }

        private static PreparationDuration savedDuration(JsonNode input) {
            int value=number(input,"durationValue",1,732);
            return switch(text(input,"durationUnit")) {
                case "days" -> new PreparationDuration(0,value);
                case "weeks" -> new PreparationDuration(0,value*7);
                case "months" -> new PreparationDuration(value,0);
                case "years" -> new PreparationDuration(value*12,0);
                default -> throw Problem.invalid("Choose a preparation duration.");
            };
        }

        public static Settings from(JsonNode input,Clock clock,boolean reschedule) {
            var p=preparation(input.path("preparation"),clock,reschedule);
            var tt=input.path("timetable");
            List<Window> windows=new ArrayList<>();
            if (!tt.path("windows").isArray() || tt.path("windows").size()>35)
                throw Problem.invalid("Set between one and five study windows per weekday.");
            for (var w:tt.path("windows")) {
                int day=number(w,"day",0,7);
                String start=text(w,"start"), end=text(w,"end");
                if (!time(start).isBefore(time(end))) throw Problem.invalid("Every study window must end after it starts.");
                windows.add(new Window(day,start,end));
            }
            var sorted=windows.stream().sorted(Comparator.comparingInt(Window::day).thenComparing(Window::start)).toList();
            var missing=new ArrayList<String>();
            for (int day:p.weekdays()) {
                var applicable=windowsFor(sorted,day);
                String name=DayOfWeek.of(day).getDisplayName(TextStyle.FULL,Locale.ENGLISH);
                if (applicable.isEmpty()) missing.add(name);
                if (applicable.size()>5) throw Problem.invalid("Use at most five study windows for "+name+".");
                String end="";
                for (var w:applicable) {
                    if (end.compareTo(w.start())>0) throw Problem.invalid("Study windows for "+name+" overlap.");
                    end=w.end();
                }
            }
            if (!missing.isEmpty())
                throw Problem.invalid("Add study windows for "+String.join(", ",missing)+" or add a default window.");
            Set<String> indivisible=new TreeSet<>();
            var noSplit=tt.path("indivisible");
            if (!noSplit.isMissingNode()) {
                if (!noSplit.isArray() || noSplit.size()>5000) throw Problem.invalid("Check single-session activities.");
                for (var n:noSplit) {
                    if (!n.isTextual() || !n.asText().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")) throw Problem.invalid("Invalid activity ID.");
                    indivisible.add(n.asText());
                }
            }
            int longBreakMinutes=number(tt,"longBreakMinutes",0,120);
            int longBreakEvery=longBreakMinutes==0?3:number(tt,"longBreakEvery",2,12);
            int rest=number(tt,"breakMinutes",0,60);
            var sequence=sequence(tt.path("workbookBlocks"),p.dailyMinutes());
            if (!sequence.isEmpty() && (rest!=0 || longBreakMinutes!=0 || !indivisible.isEmpty()))
                throw Problem.invalid("Switch to custom focus blocks to add breaks or keep topics in one session.");
            return new Settings(p,sorted,number(tt,"focusMinutes",15,180),rest,
                longBreakMinutes,longBreakEvery,Collections.unmodifiableSet(indivisible),sequence);
        }

        private static List<FocusBlock> sequence(JsonNode input,int dailyMinutes) {
            if (input.isMissingNode() || input.isNull()) return List.of();
            if (!input.isArray() || input.size()>12) throw Problem.invalid("Check the workbook focus blocks.");
            if (input.isEmpty()) return List.of();
            var blocks=new ArrayList<FocusBlock>();
            for (var block:input) blocks.add(new FocusBlock(Json.text(block,"title",120,true),number(block,"minutes",1,720),
                block.has("breakBeforeMinutes")?number(block,"breakBeforeMinutes",0,720):0));
            if (blocks.getFirst().breakBeforeMinutes()!=0) throw Problem.invalid("The workbook timetable must begin with a study block.");
            if (blocks.stream().mapToInt(FocusBlock::minutes).sum()!=dailyMinutes)
                throw Problem.invalid("Workbook focus blocks must add up to your selected daily study time.");
            return List.copyOf(blocks);
        }
    }

    public record Commitment(Instant start,Instant end,String label) {}
    public record Slot(LocalDate date,Instant start,Instant end,int minutes,String kind,String nodeId,String trackLabel) {
        public Slot(LocalDate date,Instant start,Instant end,int minutes,String kind,String nodeId) {
            this(date,start,end,minutes,kind,nodeId,null);
        }
    }
    public record Milestone(String nodeId,String title,LocalDate date) {}
    public record Preview(int effort,int previouslyCompleted,int allocated,int capacity,int breakMinutes,int unallocated,
                          int studyBlocks,boolean feasible,LocalDate completionDate,LocalDate finalDate,
                          List<String> warnings,List<Milestone> milestones,List<Slot> slots) {}
    private record Range(Instant start,Instant end) {}
    private record Day(LocalDate date,List<Range> free) {}
    private static final class Cursor { int index,remaining; }

    public static Preview plan(Document roadmap,Settings settings,List<Commitment> commitments,
                               Map<String,Integer> completed,Instant earliest) {
        return plan(roadmap,settings,commitments,completed,earliest,Map.of());
    }

    public static Preview plan(Document roadmap,Settings settings,List<Commitment> commitments,
                               Map<String,Integer> completed,Instant earliest,Map<LocalDate,Integer> retainedMinutes) {
        List<Node> nodes=topological(roadmap);
        Map<String,Node> byId=new HashMap<>();
        nodes.forEach(n -> byId.put(n.id(),n));
        if (!byId.keySet().containsAll(settings.indivisible())) throw Problem.invalid("A selected single-session activity no longer exists.");
        int effort=nodes.stream().mapToInt(Node::estimatedMinutes).sum();
        int done=nodes.stream().mapToInt(n->Math.min(n.estimatedMinutes(),completed.getOrDefault(n.id(),0))).sum();
        var schedule=new ArrayList<Slot>();
        var milestones=new ArrayList<Milestone>();
        var warnings=new LinkedHashSet<String>();
        var days=availability(settings,commitments,earliest,warnings);
        var cursor=new Cursor();
        int capacity=0, allocated=0, breaks=0, study=0;
        for (Day day:days) {
            int retained=retainedMinutes.getOrDefault(day.date(),0);
            capacity+=fillDay(day,settings,null,null,null,null,null,retained);
            if (cursor.index>=nodes.size()) continue;
            int before=schedule.size();
            allocated+=fillDay(day,settings,nodes,cursor,completed,schedule,milestones,retained);
            if (schedule.size()>MAX_SLOTS) throw Problem.invalid("The schedule exceeds 100,000 blocks. Use longer focus blocks or fewer windows.");
            for (int i=before;i<schedule.size();i++) {
                var slot=schedule.get(i);
                if (slot.kind().equals("BREAK")) breaks+=slot.minutes(); else study++;
            }
        }
        int missing=effort-done-allocated;
        if (missing>0 && cursor.index<nodes.size() && settings.indivisible().contains(nodes.get(cursor.index).id()))
            warnings.add("“"+nodes.get(cursor.index).title()+"” needs an uninterrupted study window.");
        return new Preview(effort,done,allocated,capacity,breaks,missing,study,missing==0,
            schedule.isEmpty()?null:schedule.getLast().date(),settings.preparation().endExclusive().minusDays(1),
            List.copyOf(warnings),List.copyOf(milestones),List.copyOf(schedule));
    }

    private static int fillDay(Day day,Settings s,List<Node> nodes,Cursor cursor,Map<String,Integer> completed,
                               List<Slot> slots,List<Milestone> milestones,int retainedMinutes) {
        boolean capacityOnly=nodes==null;
        int retained=Math.min(s.preparation().dailyMinutes(),Math.max(0,retainedMinutes));
        int daily=retained, blocks=0;
        Instant previous=null;
        for (Range window:day.free()) {
            Instant at=window.start();
            while (daily<s.preparation().dailyMinutes() && at.isBefore(window.end())) {
                Node node=null;
                if (!capacityOnly) {
                    while (cursor.remaining==0 && cursor.index<nodes.size()) {
                        node=nodes.get(cursor.index);
                        cursor.remaining=node.estimatedMinutes()-Math.min(node.estimatedMinutes(),completed.getOrDefault(node.id(),0));
                        if (cursor.remaining==0) cursor.index++;
                    }
                    if (cursor.index>=nodes.size()) return daily-retained;
                    node=nodes.get(cursor.index);
                }
                var focus=focusBlock(s,daily);
                int required=s.sequence().isEmpty()
                    ? (blocks==0?0:(s.longBreakMinutes()>0 && blocks%s.longBreakEvery()==0?s.longBreakMinutes():s.breakMinutes()))
                    : focus.breakBeforeMinutes();
                boolean uninterrupted=previous!=null && previous.equals(at) && at.isAfter(window.start());
                // A gap between study windows already counts as rest. If it is short, delay the next block.
                int rest=required;
                if (previous!=null && !uninterrupted) rest=Math.max(0,required-(int)Duration.between(previous,at).toMinutes());
                Instant start=at.plusSeconds(rest*60L);
                int available=(int)Math.min(Duration.between(start,window.end()).toMinutes(),s.preparation().dailyMinutes()-daily);
                if (available<=0) break;
                boolean indivisible=!capacityOnly && s.indivisible().contains(node.id());
                int desired=capacityOnly?focus.minutes():(indivisible?cursor.remaining:Math.min(focus.minutes(),cursor.remaining));
                if (indivisible && desired>available) break;
                int minutes=Math.min(desired,available);
                Instant end=start.plusSeconds(minutes*60L);
                if (!capacityOnly) {
                    if (rest>0) slots.add(new Slot(day.date(),at,start,rest,"BREAK",null));
                    slots.add(new Slot(day.date(),start,end,minutes,"STUDY",node.id(),focus.title()));
                    cursor.remaining-=minutes;
                    if (cursor.remaining==0) {
                        if (node.kind()==Kind.PROJECT || node.kind()==Kind.ASSESSMENT) milestones.add(new Milestone(node.id(),node.title(),day.date()));
                        cursor.index++;
                    }
                }
                at=end; previous=end; blocks++; daily+=minutes;
            }
        }
        return daily-retained;
    }

    private static FocusBlock focusBlock(Settings settings,int used) {
        for (var block:settings.sequence()) {
            if (used<block.minutes()) return new FocusBlock(block.title(),block.minutes()-used,used==0?block.breakBeforeMinutes():0);
            used-=block.minutes();
        }
        return new FocusBlock(null,settings.focusMinutes());
    }

    private static List<Day> availability(Settings settings,List<Commitment> commitments,Instant earliest,Set<String> warnings) {
        Preparation p=settings.preparation();
        ZoneId zone=ZoneId.of(p.zone());
        List<Commitment> sorted=commitments.stream().sorted(Comparator.comparing(Commitment::start).thenComparing(Commitment::end)).toList();
        List<Day> out=new ArrayList<>();
        int first=0;
        for (LocalDate day=p.start(); day.isBefore(p.endExclusive()); day=day.plusDays(1)) {
            int dow=day.getDayOfWeek().getValue();
            if (!p.weekdays().contains(dow) || p.excluded().contains(day)) continue;
            List<Range> free=new ArrayList<>();
            for (Window window:windowsFor(settings.windows(),dow)) {
                Instant start=resolve(day,time(window.start()),zone);
                Instant end=resolve(day,time(window.end()),zone);
                if (!start.isBefore(end)) throw Problem.invalid("The study window on "+day+" has no elapsed time.");
                if (earliest!=null && start.isBefore(earliest)) start=earliest;
                if (!start.isBefore(end)) continue;
                while (first<sorted.size() && !sorted.get(first).end().isAfter(start)) first++;
                Instant at=start;
                for (int i=first;i<sorted.size();i++) {
                    var busy=sorted.get(i);
                    if (!busy.start().isBefore(end)) break;
                    if (!busy.end().isAfter(at)) continue;
                    if (warnings.size()<8) {
                        var range=busy.start().atZone(zone).toLocalTime()+"–"+busy.end().atZone(zone).toLocalTime();
                        warnings.add("Reserved in "+busy.label()+": "+day+" · "+range+". Overlapping time was removed.");
                    }
                    if (busy.start().isAfter(at)) free.add(new Range(at,busy.start()));
                    if (busy.end().isAfter(at)) at=busy.end();
                    if (!at.isBefore(end)) break;
                }
                if (at.isBefore(end)) free.add(new Range(at,end));
            }
            out.add(new Day(day,free));
        }
        return out;
    }

    static Instant resolve(LocalDate day,LocalTime time,ZoneId zone) {
        LocalDateTime local=day.atTime(time);
        ZoneRules rules=zone.getRules();
        var offsets=rules.getValidOffsets(local);
        if (offsets.size()!=1) throw Problem.invalid("On "+day+", "+time+" is "+
            (offsets.isEmpty()?"skipped":"ambiguous")+" in "+zone+". Change the window or exclude this date.");
        return local.toInstant(offsets.getFirst());
    }

    public static List<Node> topological(Document doc) {
        var nodes=new LinkedHashMap<String,Node>();
        var order=new HashMap<String,Integer>();
        var incoming=new HashMap<String,Integer>();
        var forward=new HashMap<String,List<String>>();
        for (Node node:doc.nodes()) if (node.kind()!=Kind.MODULE) {
            order.put(node.id(),nodes.size()); nodes.put(node.id(),node);
            incoming.put(node.id(),node.dependsOn().size());
            for (var dep:node.dependsOn()) forward.computeIfAbsent(dep,k->new ArrayList<>()).add(node.id());
        }
        var ready=new PriorityQueue<Node>(Comparator.comparing(Node::priority).thenComparing(n->order.get(n.id())).thenComparing(Node::id));
        nodes.values().stream().filter(n->n.dependsOn().isEmpty()).forEach(ready::add);
        List<Node> out=new ArrayList<>();
        while (!ready.isEmpty()) {
            var node=ready.remove(); out.add(node);
            for (String child:forward.getOrDefault(node.id(),List.of()))
                if (incoming.merge(child,-1,Integer::sum)==0) ready.add(nodes.get(child));
        }
        if (out.size()!=nodes.size()) throw Problem.invalid("Roadmap prerequisites are invalid.");
        return out;
    }

    public static void verify(Preview preview,Document roadmap,Settings s,Map<String,Integer> completed) {
        verify(preview,roadmap,s,completed,Map.of());
    }

    public static void verify(Preview preview,Document roadmap,Settings s,Map<String,Integer> completed,Map<LocalDate,Integer> retainedMinutes) {
        if (!preview.feasible() || preview.unallocated()!=0 || preview.slots().size()>MAX_SLOTS) throw Problem.stale();
        var expected=new HashMap<String,Integer>();
        var prerequisites=new HashMap<String,List<String>>();
        for (var n:roadmap.nodes()) if (n.kind()!=Kind.MODULE) {
            expected.put(n.id(),Math.max(0,n.estimatedMinutes()-completed.getOrDefault(n.id(),0)));
            prerequisites.put(n.id(),n.dependsOn());
        }
        var daily=new HashMap<>(retainedMinutes);
        Instant previous=null;
        ZoneId zone=ZoneId.of(s.preparation().zone());
        int minutes=0;
        for (Slot slot:preview.slots()) {
            if (previous!=null && previous.isAfter(slot.start()) || !slot.start().isBefore(slot.end()) ||
                !Duration.between(slot.start(),slot.end()).equals(Duration.ofMinutes(slot.minutes())) ||
                !slot.date().equals(slot.start().atZone(zone).toLocalDate()) ||
                slot.date().isBefore(s.preparation().start()) || !slot.date().isBefore(s.preparation().endExclusive()) ||
                !s.preparation().weekdays().contains(slot.date().getDayOfWeek().getValue()) ||
                s.preparation().excluded().contains(slot.date())) throw Problem.stale();
            if (windowsFor(s.windows(),slot.date().getDayOfWeek().getValue()).stream().noneMatch(w ->
                    !slot.start().isBefore(resolve(slot.date(),time(w.start()),zone)) &&
                    !slot.end().isAfter(resolve(slot.date(),time(w.end()),zone)))) throw Problem.stale();
            if (slot.kind().equals("STUDY")) {
                Integer left=expected.get(slot.nodeId());
                if (left==null || left<slot.minutes() || prerequisites.get(slot.nodeId()).stream().anyMatch(dep -> expected.get(dep)!=0)
                    || daily.merge(slot.date(),slot.minutes(),Integer::sum)>s.preparation().dailyMinutes()
                    || s.indivisible().contains(slot.nodeId()) && left!=slot.minutes()) throw Problem.stale();
                expected.put(slot.nodeId(),left-slot.minutes()); minutes+=slot.minutes();
            } else if (!slot.kind().equals("BREAK") || slot.nodeId()!=null) throw Problem.stale();
            previous=slot.end();
        }
        if (minutes!=preview.allocated() || expected.values().stream().anyMatch(v->v!=0)) throw Problem.stale();
    }

    static List<Window> windowsFor(List<Window> windows,int day) {
        List<Window> exact=windows.stream().filter(w->w.day()==day).toList();
        return exact.isEmpty()?windows.stream().filter(w->w.day()==0).toList():exact;
    }
    private static String text(JsonNode n,String key) {
        if (!n.path(key).isTextual()) throw Problem.invalid("Enter "+key+".");
        return n.path(key).asText();
    }
    private static int number(JsonNode n,String key,int min,int max) {
        var v=n.path(key);
        if (!v.isIntegralNumber() || !v.canConvertToInt() || v.asInt()<min || v.asInt()>max)
            throw Problem.invalid("Enter "+key+" from "+min+" to "+max+".");
        return v.asInt();
    }
    private static LocalTime time(String value) {
        try { if (!value.matches("\\d{2}:\\d{2}")) throw new DateTimeException(""); return LocalTime.parse(value); }
        catch (DateTimeException e) { throw Problem.invalid("Enter study times as HH:mm."); }
    }
    private static LocalDate date(JsonNode n,String key) {
        try { return LocalDate.parse(text(n,key)); } catch (DateTimeException e) { throw Problem.invalid("Choose a valid "+key+"."); }
    }
}
