package com.veloce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.veloce.common.Problem;
import com.veloce.planning.Planner;
import com.veloce.planning.Planner.*;
import com.veloce.roadmap.Roadmap.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PlannerTest {
    @Test void reschedulingCountsCompletedStudyAgainstTheSameDaysBudget() {
        var document=doc(node("today",180),node("tomorrow",180));
        var settings=settings(MONDAY,MONDAY.plusDays(2),"UTC",180,Set.of(1,2),Set.of(),"07:00","12:00",Set.of());
        var completed=Map.of("today",60);var retained=Map.of(MONDAY,60);
        var preview=Planner.plan(document,settings,List.of(),completed,null,retained);
        assertThat(preview.feasible()).isTrue();
        assertThat(preview.allocated()).isEqualTo(300);
        assertThat(preview.capacity()).isEqualTo(300);
        var first=preview.slots().stream().filter(s->s.date().equals(MONDAY)&&s.kind().equals("STUDY")).toList();
        assertThat(first).allMatch(s->s.nodeId().equals("today"));
        assertThat(first.stream().mapToInt(Slot::minutes).sum()).isEqualTo(120);
        Planner.verify(preview,document,settings,completed,retained);
    }
    static final Clock CLOCK=Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),ZoneOffset.UTC);
    static final LocalDate MONDAY=LocalDate.of(2026,1,5);

    @Test void conservesEffortAndOrdersPrerequisitesWithRealBreaks() {
        var doc=doc(node("prototype",100,"research"),node("research",80));
        var s=settings(MONDAY,MONDAY.plusDays(1),"Asia/Kolkata",180,Set.of(1),Set.of(),"07:00","10:30",Set.of());
        var preview=Planner.plan(doc,s,List.of(),Map.of(),null);
        assertThat(preview.feasible()).isTrue();
        assertThat(preview.allocated()).isEqualTo(180);
        assertThat(preview.breakMinutes()).isEqualTo(30);
        assertThat(preview.slots().stream().filter(i->i.kind().equals("STUDY")).map(Slot::nodeId))
            .containsExactly("research","research","prototype","prototype");
        assertThat(preview.slots().getFirst().start()).isEqualTo(Instant.parse("2026-01-05T01:30:00Z"));
        assertThat(preview.slots().getLast().end()).isEqualTo(Instant.parse("2026-01-05T05:00:00Z"));
        Planner.verify(preview,doc,s,Map.of());
        assertThat(Planner.plan(doc,s,List.of(),Map.of(),null)).isEqualTo(preview);
    }

    @Test void accountsForCommitmentsAndDoesNotCompressImpossibleScope() {
        var doc=doc(node("practice",180));
        var s=settings(MONDAY,MONDAY.plusDays(2),"UTC",180,Set.of(1,2),Set.of(MONDAY.plusDays(1)),"07:00","10:30",Set.of());
        var busy=new Commitment(Instant.parse("2026-01-05T08:00:00Z"),Instant.parse("2026-01-05T09:00:00Z"),"Other workspace");
        var p=Planner.plan(doc,s,List.of(busy),Map.of(),null);
        assertThat(p.feasible()).isFalse();
        assertThat(p.allocated()+p.unallocated()).isEqualTo(180);
        assertThat(p.warnings()).anyMatch(s1->s1.contains("Other workspace"));
        assertThat(p.slots()).allMatch(b->!b.end().isAfter(busy.start()) || !b.start().isBefore(busy.end()));
    }

    @Test void rejectsSkippedAndAmbiguousLocalWindowBoundaries() {
        for (LocalDate d:List.of(LocalDate.of(2026,3,8),LocalDate.of(2026,11,1))) {
            String start=d.getMonthValue()==3?"02:30":"01:30";
            var s=settings(d,d.plusDays(1),"America/New_York",180,Set.of(7),Set.of(),start,"04:00",Set.of());
            assertThatThrownBy(()->Planner.plan(doc(node("one",40)),s,List.of(),Map.of(),null)).isInstanceOf(Problem.class);
        }
    }

    @Test void clampsMonthsAndYearsAndUsesInclusiveFinalDay() {
        ObjectMapper json=new ObjectMapper();
        var input=json.createObjectNode().put("zone","UTC").put("dailyMinutes",180)
            .put("durationValue",1).put("durationUnit","months").put("startDate","2026-01-31");
        input.putArray("weekdays").add(1);
        var p=Settings.preparation(input,CLOCK,false);
        assertThat(p.endExclusive()).isEqualTo(LocalDate.of(2026,2,28));
        input.put("startDate","2028-02-29").put("durationUnit","years");
        assertThat(Settings.preparation(input,CLOCK,false).endExclusive()).isEqualTo(LocalDate.of(2029,2,28));
    }

    @Test void handlesPreparationDropdownsAndCalendarBoundaries() {
        var input=new ObjectMapper().createObjectNode().put("zone","UTC").put("startDate","2028-01-31")
            .put("dailyMinutes",210);
        input.putArray("weekdays").add(1);
        var duration=input.putObject("duration").put("mode","preset").put("primary",1).put("secondary",15);
        assertThat(Settings.preparation(input,CLOCK,false).endExclusive()).isEqualTo(LocalDate.of(2028,3,15));

        duration.put("mode","yearsMonths").put("primary",1).put("secondary",5);
        assertThat(Settings.preparation(input,CLOCK,false).endExclusive()).isEqualTo(LocalDate.of(2029,6,30));

        duration.put("mode","monthsWeeks").put("primary",1).put("secondary",2);
        assertThat(Settings.preparation(input,CLOCK,false).endExclusive()).isEqualTo(LocalDate.of(2028,3,14));

        duration.put("mode","weeksDays").put("primary",2).put("secondary",3);
        assertThat(Settings.preparation(input,CLOCK,false).endExclusive()).isEqualTo(LocalDate.of(2028,2,17));

        duration.put("mode","yearsMonths").put("primary",2).put("secondary",1);
        assertThatThrownBy(()->Settings.preparation(input,CLOCK,false))
            .isInstanceOf(Problem.class).hasMessageContaining("two calendar years");
        duration.put("mode","weeksDays").put("primary",0).put("secondary",0);
        assertThatThrownBy(()->Settings.preparation(input,CLOCK,false))
            .isInstanceOf(Problem.class).hasMessageContaining("at least one day");
        duration.put("mode","preset").put("primary",4).put("secondary",0);
        assertThatThrownBy(()->Settings.preparation(input,CLOCK,false))
            .isInstanceOf(Problem.class).hasMessageContaining("listed preparation duration");
    }

    @Test void checksNewDailyHoursChoicesAndPreservesSavedMinutes() {
        ObjectNode saved=new ObjectMapper().createObjectNode().put("dailyMinutes",240);
        for (int minutes:List.of(60,90,120,150,180,210,360,480))
            Settings.checkDailyHoursChoice(saved.deepCopy().put("dailyMinutes",minutes), saved);

        Settings.checkDailyHoursChoice(saved.deepCopy(), saved);
        assertThatThrownBy(()->Settings.checkDailyHoursChoice(saved.deepCopy().put("dailyMinutes",241),saved))
            .isInstanceOf(Problem.class).hasMessageContaining("listed daily study hours");
    }

    @Test void focusAndBreaksPreserveTheSixWeekCapacityShortfall() throws Exception {
        var json=new ObjectMapper();
        var input=json.readTree("""
            {
              "preparation":{
                "zone":"Asia/Kolkata","startDate":"2026-11-14","durationValue":6,"durationUnit":"weeks",
                "dailyMinutes":180,"weekdays":[1,2,3,4,5,6,7]
              },
              "timetable":{
                "windows":[{"day":0,"start":"18:00","end":"20:30"}],
                "focusMinutes":15,"breakMinutes":10,"longBreakMinutes":0,"longBreakEvery":0
              }
            }
            """);
        var settings=Settings.from(input,CLOCK,false);
        var activities=new Node[45];
        for (int i=0;i<activities.length;i++) activities[i]=node("topic-"+i,210);
        var preview=Planner.plan(doc(activities),settings,List.of(),Map.of(),null);

        assertThat(preview.feasible()).isFalse();
        assertThat(preview.effort()).isEqualTo(9450);
        assertThat(preview.allocated()).isEqualTo(3780);
        assertThat(preview.capacity()).isEqualTo(3780);
        assertThat(preview.unallocated()).isEqualTo(5670);
        var firstDay=preview.slots().stream().filter(slot->slot.date().equals(settings.preparation().start())).toList();
        assertThat(firstDay.stream().filter(slot->slot.kind().equals("STUDY"))).hasSize(6);
        assertThat(firstDay.stream().filter(slot->slot.kind().equals("BREAK"))).hasSize(5);
        assertThat(firstDay.getLast().kind()).isEqualTo("STUDY");
    }

    @Test void identifiesWeekdaysMissingWindowsAndHonorsDefaultFallback() throws Exception {
        var json=new ObjectMapper();
        var input=json.readTree("""
            {
              "preparation":{
                "zone":"UTC","startDate":"2026-01-05","durationValue":2,"durationUnit":"days",
                "dailyMinutes":90,"weekdays":[1,2]
              },
              "timetable":{
                "windows":[{"day":1,"start":"19:00","end":"21:30"}],
                "focusMinutes":15,"breakMinutes":10,"longBreakMinutes":0
              }
            }
            """);
        assertThatThrownBy(()->Settings.from(input,CLOCK,false))
            .isInstanceOf(Problem.class).hasMessageContaining("Tuesday").hasMessageContaining("default window");

        ((com.fasterxml.jackson.databind.node.ArrayNode)input.path("timetable").path("windows"))
            .addObject().put("day",0).put("start","18:00").put("end","20:30");
        var settings=Settings.from(input,CLOCK,false);
        var preview=Planner.plan(doc(node("practice",180)),settings,List.of(),Map.of(),null);
        assertThat(preview.feasible()).isTrue();
        assertThat(preview.slots().getFirst().start()).isEqualTo(Instant.parse("2026-01-05T19:00:00Z"));
        assertThat(preview.slots().stream().filter(slot->slot.date().equals(MONDAY.plusDays(1))).findFirst())
            .get().extracting(Slot::start).isEqualTo(Instant.parse("2026-01-06T18:00:00Z"));
    }

    @Test void rebalanceSubtractsHistoryAndKeepsIndivisibleActivityWhole() {
        var s=settings(MONDAY,MONDAY.plusDays(1),"UTC",180,Set.of(1),Set.of(),"07:00","09:00",Set.of("exam"));
        var doc=doc(node("foundation",50),node("exam",90,"foundation"));
        var completed=Map.of("foundation",50);
        var p=Planner.plan(doc,s,List.of(),completed,null);
        assertThat(p.previouslyCompleted()).isEqualTo(50);
        assertThat(p.slots()).singleElement().satisfies(slot->{
            assertThat(slot.nodeId()).isEqualTo("exam");assertThat(slot.minutes()).isEqualTo(90);
        });
        Planner.verify(p,doc,s,completed);
    }

    private Settings settings(LocalDate start,LocalDate end,String zone,int daily,Set<Integer> weekdays,Set<LocalDate> excluded,
                              String open,String close,Set<String> indivisible) {
        return new Settings(new Preparation(start,end,zone,daily,weekdays,excluded),List.of(new Window(0,open,close)),50,10,0,3,indivisible);
    }
    private Document doc(Node... nodes) { return new Document(1,"Curriculum","Design","Product designer",List.of(nodes)); }
    private Node node(String id,int minutes,String... deps) {
        return new Node(id,null,id,Kind.PRACTICE,minutes,List.of(deps),Priority.MUST,"Produce one artifact",List.of());
    }
}
