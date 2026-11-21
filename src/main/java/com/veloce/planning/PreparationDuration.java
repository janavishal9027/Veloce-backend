package com.veloce.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloce.common.Problem;
import java.time.LocalDate;
import java.util.Set;

record PreparationDuration(int months, int days) {
    private static final Set<PreparationDuration> PRESETS = Set.of(
        new PreparationDuration(1, 0), new PreparationDuration(1, 15),
        new PreparationDuration(2, 0), new PreparationDuration(2, 15),
        new PreparationDuration(3, 0), new PreparationDuration(3, 15),
        new PreparationDuration(5, 0), new PreparationDuration(6, 0),
        new PreparationDuration(8, 0), new PreparationDuration(12, 0),
        new PreparationDuration(17, 0), new PreparationDuration(24, 0)
    );

    static PreparationDuration fromSelection(JsonNode selection) {
        if (!selection.isObject()) throw Problem.invalid("Choose a preparation duration.");
        String mode = selection.path("mode").asText("");
        return switch (mode) {
            case "preset" -> {
                var preset = new PreparationDuration(quantity(selection, "primary", 24), quantity(selection, "secondary", 15));
                if (!PRESETS.contains(preset)) throw Problem.invalid("Choose a listed preparation duration.");
                yield preset;
            }
            case "yearsMonths" -> new PreparationDuration(
                quantity(selection, "primary", 2) * 12 + quantity(selection, "secondary", 11), 0);
            case "monthsWeeks" -> new PreparationDuration(
                quantity(selection, "primary", 24), quantity(selection, "secondary", 4) * 7);
            case "weeksDays" -> new PreparationDuration(
                0, quantity(selection, "primary", 104) * 7 + quantity(selection, "secondary", 6));
            default -> throw Problem.invalid("Choose a preparation duration.");
        };
    }

    LocalDate endExclusive(LocalDate start) {
        if (months == 0 && days == 0) throw Problem.invalid("Choose a preparation duration of at least one day.");
        if (months > 24) throw Problem.invalid("Duration cannot exceed two calendar years.");
        var end = start.plusMonths(months).plusDays(days);
        if (end.isAfter(start.plusYears(2))) throw Problem.invalid("Duration cannot exceed two calendar years.");
        return end;
    }

    private static int quantity(JsonNode selection, String key, int max) {
        var value = selection.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0 || value.asInt() > max)
            throw Problem.invalid("Choose the preparation duration from the dropdowns.");
        return value.asInt();
    }
}
