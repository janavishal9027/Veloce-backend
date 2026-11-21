package com.veloce.roadmap;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.*;

/** Reads the timetable tables as data. Reference prose and workbook formulas are never executed. */
public record WorkbookSchedule(String startDate, String endDate, int durationDays, String zone,
    String windowStart, String windowEnd, int dailyMinutes, List<Integer> excludedOffsets,
    List<Block> blocks, List<Reminder> reminders) {
    public record Block(String title, int minutes, int breakBeforeMinutes) {}
    public record Reminder(String title, int offsetFromFirstStudy) {}

    private static final Pattern IANA_ZONE = Pattern.compile("[A-Za-z_]+(?:/[A-Za-z0-9_+\\-]+)+");
    private static final ThreadLocal<DataFormatter> FORMAT = ThreadLocal.withInitial(() -> new DataFormatter(Locale.ROOT));

    static WorkbookSchedule read(Workbook workbook) {
        Sheet days = visible(workbook, "DAILY ROADMAP");
        Sheet timetable = visible(workbook, "DAILY TIMETABLE");
        if (days == null || timetable == null) return null;
        var dates = studyDates(days);
        var timed = timetable(timetable);
        if (dates.isEmpty() || timed.isEmpty()) return null;
        int duration = (int) ChronoUnit.DAYS.between(dates.first(), dates.last()) + 1;
        if (duration < 1 || duration > 730) return null;
        var first = timed.getFirst().start();
        var end = timed.getLast().end();
        int minutes = timed.stream().mapToInt(block -> (int) Duration.between(block.start(), block.end()).toMinutes()).sum();
        // The preparation form only accepts these configured daily study budgets.
        if (!Set.of(60, 90, 120, 150, 180, 210, 360, 480).contains(minutes)) return null;
        var excluded = new ArrayList<Integer>();
        for (int i = 0; i < duration; i++) {
            if (!dates.contains(dates.first().plusDays(i))) excluded.add(i);
        }
        return new WorkbookSchedule(dates.first().toString(), dates.last().toString(), duration,
            timezone(visible(workbook, "START HERE")), first.toString(), end.toString(), minutes, List.copyOf(excluded),
            timed.stream().map(block -> new Block(block.title(), (int) Duration.between(block.start(), block.end()).toMinutes(), block.breakBeforeMinutes())).toList(),
            reminders(visible(workbook, "FIVE REMINDERS"), first));
    }

    private record Interval(LocalTime start, LocalTime end, String title, int breakBeforeMinutes) {}

    private static List<Interval> timetable(Sheet sheet) {
        var header = header(sheet, "start", "end", "type", "session");
        if (header == null) return List.of();
        var result = new ArrayList<Interval>();
        LocalTime previousEnd = null;
        int rest = 0;
        for (Row row : sheet) {
            String type = cell(row, header, "type");
            if (row.getRowNum() <= header.row() || !(type.equalsIgnoreCase("Study") || type.equalsIgnoreCase("Break"))) continue;
            LocalTime start = time(cell(row, header, "start"));
            LocalTime end = time(cell(row, header, "end"));
            String title = cell(row, header, "session");
            if (start == null || end == null || !end.isAfter(start) || title.isBlank() || title.length() > 120
                || previousEnd != null && !start.equals(previousEnd)) {
                return List.of();
            }
            if (type.equalsIgnoreCase("Break")) {
                if (!result.isEmpty()) rest += (int) Duration.between(start, end).toMinutes();
            } else {
                if (result.size() >= 12) return List.of();
                result.add(new Interval(start, end, title, rest));
                rest = 0;
            }
            previousEnd = end;
        }
        return result;
    }

    private static List<Reminder> reminders(Sheet sheet, LocalTime first) {
        if (sheet == null) return List.of();
        var header = header(sheet, "at", "type");
        if (header == null) return List.of();
        var result = new LinkedHashSet<Reminder>();
        for (Row row : sheet) {
            if (row.getRowNum() <= header.row()) continue;
            LocalTime at = time(cell(row, header, "at"));
            String label = cell(row, header, "type");
            if (at == null || label.isBlank() || label.length() > 120) continue;
            int offset = (int) Duration.between(first, at).toMinutes();
            if (offset >= -120 && offset <= 780) result.add(new Reminder(label, offset));
            if (result.size() == 10) break;
        }
        return List.copyOf(result);
    }

    private static NavigableSet<LocalDate> studyDates(Sheet sheet) {
        var values = new TreeSet<LocalDate>();
        var header = header(sheet, "day", "date");
        if (header == null) return values;
        for (Row row : sheet) {
            if (row.getRowNum() <= header.row() || !cell(row, header, "day").matches("\\d{1,4}")) continue;
            var cell = row.getCell(header.columns().get("date"));
            try {
                LocalDate date = cell != null && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate() : LocalDate.parse(value(cell));
                if (date.getYear() < 2000 || date.getYear() > 2200 || !values.add(date)) return new TreeSet<>();
            } catch (DateTimeException | IllegalStateException e) {
                return new TreeSet<>();
            }
        }
        return values;
    }

    private static String timezone(Sheet sheet) {
        if (sheet == null) return "";
        for (Row row : sheet) {
            if (row.getRowNum() >= 12) break;
            for (Cell cell : row) {
                var matcher = IANA_ZONE.matcher(value(cell));
                while (matcher.find()) {
                    if (ZoneId.getAvailableZoneIds().contains(matcher.group())) return matcher.group();
                }
            }
        }
        return "";
    }

    private record Header(int row, Map<String, Integer> columns) {}

    private static Header header(Sheet sheet, String... required) {
        for (Row row : sheet) {
            if (row.getRowNum() >= 32) break;
            var columns = new HashMap<String, Integer>();
            for (Cell cell : row) {
                String key = value(cell).toLowerCase(Locale.ROOT);
                for (String name : required) {
                    if (key.equals(name) || key.startsWith(name + " ")) columns.putIfAbsent(name, cell.getColumnIndex());
                }
            }
            if (columns.size() == required.length) return new Header(row.getRowNum(), columns);
        }
        return null;
    }

    private static Sheet visible(Workbook workbook, String name) {
        int index = workbook.getSheetIndex(name);
        return index < 0 || workbook.isSheetHidden(index) || workbook.isSheetVeryHidden(index)
            ? null : workbook.getSheetAt(index);
    }

    private static String value(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.FORMULA) return "";
        return FORMAT.get().formatCellValue(cell).strip();
    }

    private static String cell(Row row, Header header, String key) {
        return value(row.getCell(header.columns().get(key)));
    }

    private static LocalTime time(String value) {
        try {
            return value.matches("\\d{2}:\\d{2}") ? LocalTime.parse(value) : null;
        } catch (DateTimeException e) {
            return null;
        }
    }
}
