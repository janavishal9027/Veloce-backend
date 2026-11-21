package com.veloce.planning;

import com.veloce.common.Problem;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** A course's requested windows must not overlap another active course. */
public final class ScheduleConflicts {
    private ScheduleConflicts() {}
    public static void check(Planner.Settings settings,List<Planner.Commitment> commitments) {
        check(settings, commitments, Instant.MIN);
    }

    static void check(Planner.Settings settings,List<Planner.Commitment> commitments,Instant earliest) {
        if (commitments.isEmpty()) return;
        var ordered = commitments.stream().sorted(Comparator.comparing(Planner.Commitment::start)).toList();
        var prep = settings.preparation();
        var zone = ZoneId.of(prep.zone());
        int current = 0;
        for (LocalDate date = prep.start(); date.isBefore(prep.endExclusive()); date = date.plusDays(1)) {
            int day = date.getDayOfWeek().getValue();
            if (!prep.weekdays().contains(day) || prep.excluded().contains(date)) continue;
            var windows = Planner.windowsFor(settings.windows(), day).stream().sorted(Comparator.comparing(Planner.Window::start)).toList();
            for (var window : windows) {
                var start = Planner.resolve(date, LocalTime.parse(window.start()), zone);
                var end = Planner.resolve(date, LocalTime.parse(window.end()), zone);
                if (start.isBefore(earliest)) start = earliest;
                if (!end.isAfter(start)) continue;
                while (current < ordered.size() && !ordered.get(current).end().isAfter(start)) current++;
                if (current == ordered.size()) return;
                var busy = ordered.get(current);
                if (!busy.start().isBefore(end)) continue;
                String when = DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm", Locale.ENGLISH).format(busy.start().atZone(zone));
                throw new Problem(409, "SCHEDULE_CONFLICT", "Ongoing course is not yet completed. " + busy.label()
                    + " already has study scheduled at " + when + " (" + prep.zone() + "). Choose a different study window.");
            }
        }
    }
}
