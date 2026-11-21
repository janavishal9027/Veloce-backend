package com.veloce;

import com.veloce.planning.*;
import com.veloce.common.Problem;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ScheduleConflictsTest {
    private Planner.Settings settings(List<Planner.Window> windows) {
        return new Planner.Settings(new Planner.Preparation(LocalDate.parse("2026-11-18"),LocalDate.parse("2026-11-20"),"Asia/Kolkata",120,Set.of(3,4),Set.of()),windows,45,10,0,3,Set.of());
    }
    private Planner.Commitment python(String start,String end){return new Planner.Commitment(Instant.parse(start),Instant.parse(end),"Python");}
    @Test void overlappingCourseReturnsActionableAlertInUserTimezone() {
        var config=settings(List.of(new Planner.Window(0,"18:00","20:00")));
        assertThatThrownBy(()->ScheduleConflicts.check(config,List.of(python("2026-11-18T12:30:00Z","2026-11-18T14:30:00Z"))))
            .isInstanceOfSatisfying(Problem.class,p->{assertThat(p.code).isEqualTo("SCHEDULE_CONFLICT");assertThat(p.getMessage()).contains("Ongoing course is not yet completed","Python","18:00");});
    }
    @Test void adjacentCoursesAreAllowedAndWeekdayOverrideReplacesDefault() {
        var config=settings(List.of(new Planner.Window(0,"18:00","20:00"),new Planner.Window(3,"20:00","21:00")));
        assertThatCode(()->ScheduleConflicts.check(config,List.of(python("2026-11-18T12:30:00Z","2026-11-18T14:30:00Z")))).doesNotThrowAnyException();
    }
}
