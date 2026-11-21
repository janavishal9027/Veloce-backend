package com.veloce.study;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StudyViewTest {
    final LocalDate first=LocalDate.of(2026,11,10);
    final List<StudyView.Node> nodes=List.of(
        new StudyView.Node("phase",null,"Foundations","MODULE",0),
        new StudyView.Node("nested","phase","Nested topic","MODULE",1),
        new StudyView.Node("lesson","nested","Understand the concept","LESSON",2),
        new StudyView.Node("project","phase","Build an artifact","PROJECT",3));
    @SuppressWarnings("unchecked") List<Map<String,Object>> rows(Map<String,Object> view,String key) {return (List<Map<String,Object>>)view.get(key);}
    @SuppressWarnings("unchecked") Map<String,Object> map(Map<String,Object> view,String key) {return (Map<String,Object>)view.get(key);}

    @Test void phasesComeFromWorkspaceHierarchyAndDaysUseActualScheduledDates() {
        var view=StudyView.build("Design roadmap",nodes,List.of(
            new StudyView.Block(first,"lesson",90,3,1),new StudyView.Block(first.plusDays(3),"project",120,4,0)),
            List.of(),List.of(),Set.of(),Map.of(),List.of(),Map.of());
        assertThat(rows(view,"days")).hasSize(2);
        assertThat(rows(view,"days").get(1).get("date")).isEqualTo("2026-11-13");
        assertThat(rows(view,"phases")).hasSize(1);
        assertThat(rows(view,"phases").getFirst()).containsEntry("name","Foundations").containsEntry("days",2);
        assertThat(map(view,"totals")).containsEntry("plannedMinutes",210).containsEntry("projectCount",1);
    }
    @Test void progressUsesLatestSubmittedSnapshotAndFlagsLaterWorkEdits() {
        var reports=List.of(
            new StudyView.Report(first,1,List.of(new StudyView.Entry("lesson","PARTIAL",20,1))),
            new StudyView.Report(first,2,List.of(new StudyView.Entry("lesson","DONE",35,2))));
        var view=StudyView.build("Roadmap",nodes,List.of(new StudyView.Block(first,"lesson",90,3,0)),
            List.of(new StudyView.Work(first,"lesson","BLOCKED",70,3)),reports,Set.of(first),Map.of(first,600),List.of(),Map.of());
        var day=rows(view,"days").getFirst();
        assertThat(day).containsEntry("reportMinutes",35).containsEntry("workMinutes",70).containsEntry("reportFresh",false).containsEntry("reportComplete",true);
        assertThat(map(view,"totals")).containsEntry("reportedMinutes",35).containsEntry("completeDays",1).containsEntry("attendanceDays",1);
        assertThat(rows(view,"weeks").getFirst()).containsEntry("plannedMinutes",90).containsEntry("reportedMinutes",35).containsEntry("varianceMinutes",-55);
    }
    @Test void newTimetableActivitiesRequireReportCoverageAndDoNotInheritCompletion() {
        var view=StudyView.build("Roadmap",nodes,List.of(new StudyView.Block(first,"lesson",30,1,1),new StudyView.Block(first,"project",60,2,0)),
            List.of(new StudyView.Work(first,"lesson","DONE",30,1)),
            List.of(new StudyView.Report(first,1,List.of(new StudyView.Entry("lesson","DONE",30,1)))),
            Set.of(),Map.of(),List.of(),Map.of());
        assertThat(rows(view,"days").getFirst()).containsEntry("reportFresh",false).containsEntry("reportComplete",false).containsEntry("status","Report submitted");
        assertThat(map(view,"totals")).containsEntry("completeDays",0);
    }
    @Test void projectsRequireSixActualCompletedSectionsAndWeeksRespectGaps() {
        var sections=StudyRules.SECTIONS.stream().map(section->new StudyView.Section("project",section,"DONE")).toList();
        var view=StudyView.build("Roadmap",nodes,List.of(new StudyView.Block(first,"lesson",30,1,0),new StudyView.Block(first.plusDays(8),"project",60,2,0)),
            List.of(),List.of(),Set.of(),Map.of(),sections,Map.of("COMPLETED",2));
        assertThat(map(view,"portfolio")).containsEntry("done",1).containsEntry("notStarted",0);
        assertThat(rows(view,"weeks")).hasSize(2);
        assertThat(rows(view,"weeks").get(1)).containsEntry("number",2).containsEntry("studyDays",1);
        var flat=StudyView.build("Product design",List.of(new StudyView.Node("research",null,"User research","LESSON",0)),
            List.of(new StudyView.Block(first,"research",30,1,0)),List.of(),List.of(),Set.of(),Map.of(),List.of(),Map.of());
        assertThat(rows(flat,"phases").getFirst()).containsEntry("name","Product design");
    }
}
