package com.veloce.study;

import com.veloce.common.Problem;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StudyDayScheduleTest {
    private final Instant checkIn=Instant.parse("2026-11-17T04:30:12Z");
    private StudyDaySchedule.Block block(int minutes,String kind,boolean done) {
        return new StudyDaySchedule.Block(UUID.randomUUID(),checkIn.minusSeconds(3600),checkIn.minusSeconds(60),minutes,kind,done);
    }

    @Test void shiftsFromActualCheckInAndPreservesBreaksWithoutStartingOnAnOrphanBreak() {
        var first=block(45,"STUDY",false); var second=block(30,"STUDY",false);
        var rest=block(10,"BREAK",false);
        var pending=StudyDaySchedule.pending(List.of(block(10,"BREAK",false),block(15,"STUDY",true),
            block(10,"BREAK",false),first,rest,second,block(10,"BREAK",false)));
        assertThat(pending).containsExactly(first,rest,second);
        var result=StudyDaySchedule.place(pending,List.of(),checkIn,checkIn.plusSeconds(12*3600));
        assertThat(result).extracting(StudyDaySchedule.Placement::start)
            .containsExactly(checkIn,checkIn.plusSeconds(45*60),checkIn.plusSeconds(55*60));
        assertThat(result.getLast().end()).isEqualTo(checkIn.plusSeconds(85*60));
    }

    @Test void avoidsOtherWorkspaceCommitmentsEvenWhenIntervalsOverlap() {
        var occupied=List.of(new StudyDaySchedule.Occupied(checkIn.plusSeconds(10*60),checkIn.plusSeconds(30*60)),
            new StudyDaySchedule.Occupied(checkIn.plusSeconds(20*60),checkIn.plusSeconds(50*60)));
        var result=StudyDaySchedule.place(List.of(block(45,"STUDY",false)),occupied,checkIn,checkIn.plusSeconds(12*3600));
        assertThat(result.getFirst().start()).isEqualTo(checkIn.plusSeconds(50*60));
        assertThat(result.getFirst().end()).isEqualTo(checkIn.plusSeconds(95*60));
    }

    @Test void refusesAnOverflowBeforeReturningAnyPlacementsAndKeepsFullMinutes() {
        assertThatThrownBy(()->StudyDaySchedule.place(List.of(block(45,"STUDY",false),block(45,"STUDY",false)),
            List.of(),checkIn,checkIn.plusSeconds(60*60)))
            .isInstanceOfSatisfying(Problem.class,e->{assertThat(e.status).isEqualTo(409);assertThat(e.getMessage()).contains("midnight");});
        assertThat(StudyDaySchedule.place(List.of(block(60,"STUDY",false)),List.of(),checkIn,checkIn.plusSeconds(60*60))
            .getFirst().end()).isEqualTo(checkIn.plusSeconds(60*60));
    }
}
