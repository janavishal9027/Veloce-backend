package com.veloce.roadmap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.veloce.common.Json;
import com.veloce.integration.ReminderRules;
import com.veloce.planning.Planner;
import com.veloce.roadmap.Roadmap.*;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WorkbookScheduleTest {
    @Test void workbookDaysStudySequenceAndRemindersDriveCalendarTimes() throws Exception {
        try(var book=new XSSFWorkbook()) {
            var days=book.createSheet("DAILY ROADMAP");
            cells(days.createRow(3),"Day","Date","Topic");
            cells(days.createRow(4),"1","2026-11-16","First topic");
            cells(days.createRow(5),"2","2026-11-18","Second topic");
            cells(book.createSheet("START HERE").createRow(1),"Asia/Kolkata");
            var timetable=book.createSheet("DAILY TIMETABLE");
            cells(timetable.createRow(2),"START IST","END IST","TYPE","SESSION / REMINDER");
            cells(timetable.createRow(3),"08:50","08:55","Reminder","Prepare");
            cells(timetable.createRow(4),"09:00","09:35","Study","Concepts");
            cells(timetable.createRow(5),"09:35","10:05","Study","Coding");
            cells(timetable.createRow(6),"10:05","10:40","Study","Scenario");
            cells(timetable.createRow(7),"10:40","11:45","Study","Project");
            cells(timetable.createRow(8),"11:45","12:00","Study","Interview");
            var reminders=book.createSheet("FIVE REMINDERS");
            cells(reminders.createRow(2),"AT IST","TYPE");
            for(var item:List.of(List.of("08:50","Prepare"),List.of("08:55","Check in"),List.of("10:05","Scenario"),
                List.of("12:00","Report"),List.of("12:05","Logout"))) {
                cells(reminders.createRow(reminders.getLastRowNum()+1),item.getFirst(),item.getLast());
            }
            var imported=WorkbookSchedule.read(book);
            assertThat(imported).isNotNull();
            assertThat(imported.zone()).isEqualTo("Asia/Kolkata");
            assertThat(imported.excludedOffsets()).containsExactly(1);
            assertThat(imported.reminders()).extracting(WorkbookSchedule.Reminder::offsetFromFirstStudy).containsExactly(-10,-5,65,180,185);
            var date=LocalDate.parse(imported.startDate());
            var node=new Node("topic",null,"First topic",Kind.LESSON,180,List.of(),Priority.MUST,"Learn the lesson",List.of());
            var config=new Planner.Settings(
                new Planner.Preparation(date,date.plusDays(3),imported.zone(),180,Set.of(1,2,3,4,5,6,7),Set.of(date.plusDays(1))),
                List.of(new Planner.Window(0,imported.windowStart(),imported.windowEnd())),50,0,0,3,Set.of(),
                imported.blocks().stream().map(row->new Planner.FocusBlock(row.title(),row.minutes(),row.breakBeforeMinutes())).toList());
            var chart=Planner.plan(new Document(1,"Test","Technology","Developer",List.of(node)),config,List.of(),Map.of(),Instant.parse("2026-11-15T00:00:00Z"));
            assertThat(chart.feasible()).isTrue();
            assertThat(chart.slots()).extracting(Planner.Slot::minutes).containsExactly(35,30,35,65,15);
            assertThat(chart.slots()).extracting(Planner.Slot::trackLabel).containsExactly("Concepts","Coding","Scenario","Project","Interview");
            var json=new Json(new ObjectMapper());
            var preferences=json.object().put("channel","CALENDAR");
            preferences.set("dayReminders",json.mapper.valueToTree(imported.reminders()));
            var events=ReminderRules.from(preferences,json).calendarReminders(UUID.randomUUID(),date,chart.slots().getFirst().start(),ZoneId.of(imported.zone()));
            assertThat(events).extracting(ReminderRules.CalendarReminder::at).containsExactly(
                Instant.parse("2026-11-16T03:20:00Z"),Instant.parse("2026-11-16T03:25:00Z"),Instant.parse("2026-11-16T04:35:00Z"),
                Instant.parse("2026-11-16T06:30:00Z"),Instant.parse("2026-11-16T06:35:00Z"));
        }
    }

    @Test void studyBreaksAndOverlappingReminderRowsPreserveTheFullNinetyTwoDayTimetable() throws Exception {
        try(var book=new XSSFWorkbook()) {
            var days=book.createSheet("DAILY ROADMAP");
            cells(days.createRow(3),"DAY","DATE");
            var start=LocalDate.parse("2026-11-21");
            var nodes=new ArrayList<Node>();
            for(int i=0;i<92;i++) {
                cells(days.createRow(i+4),String.valueOf(i+1),start.plusDays(i).toString());
                nodes.add(new Node("day"+i,null,"Topic "+i,Kind.LESSON,360,List.of(),Priority.MUST,"Daily concepts",List.of()));
            }
            var timetable=book.createSheet("DAILY TIMETABLE");
            cells(timetable.createRow(3),"START IST","END IST","TYPE","SESSION / REMINDER");
            String[][] rows={
                {"09:00","09:10","Reminder","Check in"},{"09:10","10:20","Study","Concepts"},
                {"10:20","10:35","Break","Break"},{"10:35","11:35","Study","Coding"},
                {"11:35","12:20","Break","Lunch"},{"12:20","12:25","Reminder","Resume"},
                {"12:20","13:20","Study","Scenario"},{"13:20","13:35","Break","Break"},
                {"13:35","15:35","Study","Project"},{"15:35","15:50","Break","Break"},
                {"15:50","16:30","Study","Interview"},{"16:30","16:40","Study","Review"},
                {"16:40","16:55","Reminder","Report"}};
            for(int i=0;i<rows.length;i++)cells(timetable.createRow(i+4),rows[i]);
            var reminders=book.createSheet("FIVE REMINDERS");
            cells(reminders.createRow(3),"AT IST","TYPE");
            String[] times={"08:50","09:00","12:20","16:40","16:55"};
            for(int i=0;i<times.length;i++)cells(reminders.createRow(i+4),times[i],"Reminder "+i);
            var imported=WorkbookSchedule.read(book);
            assertThat(imported).isNotNull();
            assertThat(imported.durationDays()).isEqualTo(92);
            assertThat(imported.dailyMinutes()).isEqualTo(360);
            assertThat(imported.windowStart()).isEqualTo("09:10");
            assertThat(imported.windowEnd()).isEqualTo("16:40");
            assertThat(imported.blocks()).extracting(WorkbookSchedule.Block::breakBeforeMinutes).containsExactly(0,15,45,15,15,0);
            assertThat(imported.reminders()).hasSize(5);
            var json=new Json(new ObjectMapper());
            var settings=json.object();
            var prep=settings.putObject("preparation").put("startDate",start.toString()).put("zone","Asia/Kolkata")
                .put("dailyMinutes",360);
            prep.putObject("duration").put("mode","weeksDays").put("primary",13).put("secondary",1);
            prep.putArray("weekdays").add(1).add(2).add(3).add(4).add(5).add(6).add(7);
            var tt=settings.putObject("timetable").put("focusMinutes",50).put("breakMinutes",0).put("longBreakMinutes",0);
            tt.putArray("windows").addObject().put("day",0).put("start",imported.windowStart()).put("end",imported.windowEnd());
            tt.set("workbookBlocks",json.mapper.valueToTree(imported.blocks()));
            var config=Planner.Settings.from(settings,Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"),ZoneOffset.UTC),false);
            var document=new Document(1,"AI Engineering","Technology","AI Engineer",nodes);
            var chart=Planner.plan(document,config,List.of(),Map.of(),Instant.parse("2026-11-20T00:00:00Z"));
            assertThat(chart.feasible()).isTrue();
            assertThat(chart.allocated()).isEqualTo(552*60);
            assertThat(chart.breakMinutes()).isEqualTo(92*90);
            assertThat(chart.studyBlocks()).isEqualTo(92*6);
            var firstDay=chart.slots().stream().filter(slot->slot.date().equals(start)).toList();
            assertThat(firstDay).extracting(Planner.Slot::minutes).containsExactly(70,15,60,45,60,15,120,15,40,10);
            assertThat(firstDay.getLast().end().atZone(ZoneId.of("Asia/Kolkata")).toLocalTime()).isEqualTo(LocalTime.parse("16:40"));
            Planner.verify(chart,document,config,Map.of());
        }
    }

    @Test void missingBreakRowsDoNotSilentlyInventRest() throws Exception {
        try(var book=new XSSFWorkbook()) {
            var days=book.createSheet("DAILY ROADMAP");cells(days.createRow(0),"Day","Date");
            cells(days.createRow(1),"1","2026-11-21");
            var timetable=book.createSheet("DAILY TIMETABLE");cells(timetable.createRow(0),"Start","End","Type","Session");
            cells(timetable.createRow(1),"09:00","10:00","Study","Concepts");
            cells(timetable.createRow(2),"10:30","11:30","Study","Coding");
            assertThat(WorkbookSchedule.read(book)).isNull();
        }
    }

    private static void cells(org.apache.poi.ss.usermodel.Row row,String... data) {
        for(int i=0;i<data.length;i++) row.createCell(i).setCellValue(data[i]);
    }
}
