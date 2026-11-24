package cn.haike.schedule;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

final class Timetable {
    static final ZoneId CAMPUS = ZoneId.of("Asia/Shanghai");
    static final LocalDate FALL = LocalDate.of(2026, 8, 31);
    static final LocalDate SPRING = LocalDate.of(2027, 3, 1);
    static final int[][] STANDARD = {
        {490, 535}, {540, 585}, {615, 660}, {665, 710}, {870, 915},
        {920, 965}, {990, 1035}, {1040, 1085}, {1170, 1215}, {1225, 1270}
    };
    static final String[] DAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    record Course(String name, int day, int first, int last, long weeks, String campus,
                  String room, String teacher, String type) {
        String periodLabel() {
            if (first == 0) return "自定义时间";
            return first == last ? "第 " + first + " 节" : "第 " + first + "–" + last + " 节";
        }

        String roomLabel() { return room.trim().isEmpty() ? "教室待定" : room; }

        boolean staggered() {
            return (campus.contains("湖光") && (room.contains("广学楼") || room.contains("明德楼")))
                || (campus.contains("海滨") && (room.contains("实验楼") || room.contains("室外")));
        }
    }

    record Meeting(Course course, LocalDate date, int start, int end, String id) {
        long startsAt() { return epoch(date, start); }
        long endsAt() { return epoch(date, end); }
        String times() { return clock(start) + "–" + clock(end); }
        String notificationKey() { return id + "|" + startsAt() + "|" + end + "|" + course.campus + "|" + course.room; }
    }

    record Adjustment(LocalDate source, Meeting meeting, boolean cancelled) {
        String label() { return cancelled ? "停课" : source == null ? "补课" : "调课"; }
    }

    final String term;
    final LocalDate start;
    final boolean imported;
    final List<Course> courses;
    final List<Adjustment> adjustments;

    Timetable(String term, LocalDate start, boolean imported, List<Course> courses) {
        this(term, start, imported, courses, Collections.emptyList());
    }

    private Timetable(String term, LocalDate start, boolean imported, List<Course> courses, List<Adjustment> adjustments) {
        this.term = term;
        this.start = start;
        this.imported = imported;
        this.courses = Collections.unmodifiableList(new ArrayList<>(courses));
        this.adjustments = Collections.unmodifiableList(new ArrayList<>(adjustments));
    }

    Timetable withStart(LocalDate value) {
        Timetable shifted = new Timetable(term, value, imported, courses);
        List<Adjustment> retained = new ArrayList<>();
        for (Adjustment adjustment : adjustments) {
            if (adjustment.source == null || shifted.baseOn(adjustment.source, STANDARD).stream()
                .anyMatch(meeting -> meeting.id.equals(adjustment.meeting.id))) retained.add(adjustment);
        }
        return new Timetable(term, value, imported, courses, retained);
    }

    Adjustment adjustment(String id) {
        for (Adjustment value : adjustments) if (value.meeting.id.equals(id)) return value;
        return null;
    }

    Timetable withAdjustment(String id, Adjustment replacement) {
        List<Adjustment> values = new ArrayList<>(adjustments);
        values.removeIf(adjustment -> adjustment.meeting.id.equals(id));
        if (replacement != null) values.add(replacement);
        return new Timetable(term, start, imported, courses, values);
    }

    static LocalDate today() { return onDate(System.currentTimeMillis()); }
    static LocalDate onDate(long epoch) { return Instant.ofEpochMilli(epoch).atZone(CAMPUS).toLocalDate(); }
    static long epoch(LocalDate date, int minute) {
        return date.atTime(LocalTime.ofSecondOfDay(minute * 60L)).atZone(CAMPUS).toInstant().toEpochMilli();
    }
    static LocalDate monday(LocalDate day) { return day.minusDays(day.getDayOfWeek().getValue() - 1L); }
    static String clock(int minute) { return LocalTime.of(minute / 60, minute % 60).toString(); }
    static String dateLabel(LocalDate day) { return day.getMonthValue() + "月" + day.getDayOfMonth() + "日"; }
    static String weekday(LocalDate day) { return DAYS[day.getDayOfWeek().getValue() - 1]; }
    static String relative(LocalDate date, LocalDate today) {
        if (date.equals(today)) return "今天";
        if (date.equals(today.plusDays(1))) return "明天";
        return dateLabel(date) + " " + weekday(date);
    }

    int week(LocalDate day) {
        return (int) Math.floorDiv(ChronoUnit.DAYS.between(start, day), 7L) + 1;
    }

    List<Meeting> on(LocalDate date, int[][] periods) {
        List<Meeting> result = baseOn(date, periods);
        for (Adjustment adjustment : adjustments) {
            Meeting meeting = adjustment.meeting;
            if (date.equals(adjustment.source)) result.removeIf(original -> original.id.equals(meeting.id));
            if (!adjustment.cancelled && date.equals(meeting.date)) result.add(meeting);
        }
        result.sort(Comparator.comparingInt(Meeting::start).thenComparing(Meeting::id));
        return result;
    }

    Meeting find(long start, String id, int[][] periods) {
        if (id == null) return null;
        for (Meeting meeting : on(onDate(start), periods)) {
            if (meeting.startsAt() == start && meeting.id.equals(id)) return meeting;
        }
        return null;
    }

    private List<Meeting> baseOn(LocalDate date, int[][] periods) {
        int week = week(date);
        List<Meeting> result = new ArrayList<>();
        if (week < 1 || week > 40) return result;
        for (Course course : courses) {
            if (course.day != date.getDayOfWeek().getValue() || (course.weeks & (1L << week)) == 0) continue;
            int begins = periods[course.first - 1][0];
            int ends = periods[course.last - 1][1];
            if (imported && course.staggered()) {
                begins = staggeredStart(course.first, begins);
                ends = staggeredEnd(course.last, ends);
            }
            result.add(new Meeting(course, date, begins, ends, meetingId(course, date)));
        }
        return result;
    }

    private static String meetingId(Course c, LocalDate date) {
        return UUID.nameUUIDFromBytes((date + "\n" + c).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static int staggeredStart(int period, int standard) {
        return switch (period) {
            case 3 -> 605;
            case 4 -> 650;
            case 7 -> 985;
            case 8 -> 1030;
            default -> standard;
        };
    }

    private static int staggeredEnd(int period, int standard) {
        return switch (period) {
            case 3 -> 650;
            case 4 -> 695;
            case 7 -> 1030;
            case 8 -> 1075;
            default -> standard;
        };
    }

    int lastWeek() {
        int week = 0;
        for (Course course : courses) week = Math.max(week, 63 - Long.numberOfLeadingZeros(course.weeks));
        return week;
    }

    Set<LocalDate> datesFrom(LocalDate first) {
        Set<LocalDate> dates = new TreeSet<>();
        LocalDate begin = first.isAfter(start) ? first : start;
        LocalDate lastBase = start.plusDays(lastWeek() * 7L - 1);
        for (LocalDate date = begin; !date.isAfter(lastBase); date = date.plusDays(1)) dates.add(date);
        for (Adjustment value : adjustments) {
            if (!value.cancelled && !value.meeting.date.isBefore(first)) dates.add(value.meeting.date);
        }
        return dates;
    }

    Meeting next(long now, int[][] periods) {
        return nextMeeting(now, periods, true);
    }

    Meeting upcoming(long now, int[][] periods) {
        return nextMeeting(now, periods, false);
    }

    private Meeting nextMeeting(long now, int[][] periods, boolean includeOngoing) {
        for (LocalDate day : datesFrom(onDate(now))) {
            for (Meeting meeting : on(day, periods)) {
                if ((includeOngoing ? meeting.endsAt() : meeting.startsAt()) > now) return meeting;
            }
        }
        return null;
    }

    static String weekText(long mask) {
        StringBuilder text = new StringBuilder();
        for (int week = 1; week <= 40; week++) {
            if ((mask & (1L << week)) == 0) continue;
            int begin = week;
            while (week < 40 && (mask & (1L << (week + 1))) != 0) week++;
            if (text.length() > 0) text.append("、");
            text.append(begin);
            if (begin != week) text.append("–").append(week);
        }
        return text + " 周";
    }

    JSONObject json() throws JSONException {
        JSONObject root = new JSONObject().put("term", term).put("termStart", start.toString()).put("imported", imported);
        JSONArray entries = new JSONArray();
        for (Course c : courses) entries.put(courseJson(c));
        JSONArray changes = new JSONArray();
        for (Adjustment value : adjustments) {
            Meeting m = value.meeting;
            changes.put(new JSONObject().put("id", m.id).put("source", value.source == null ? "" : value.source.toString())
                .put("date", m.date.toString()).put("start", m.start).put("end", m.end)
                .put("cancelled", value.cancelled).put("course", courseJson(m.course)));
        }
        return root.put("courses", entries).put("adjustments", changes);
    }

    private static JSONObject courseJson(Course c) throws JSONException {
        return new JSONObject().put("name", c.name).put("day", c.day).put("first", c.first)
            .put("last", c.last).put("mask", c.weeks).put("campus", c.campus).put("room", c.room)
            .put("teacher", c.teacher).put("type", c.type);
    }

    private static Course readCourse(JSONObject c, long mask) throws JSONException {
        return new Course(c.getString("name"), c.getInt("day"), c.getInt("first"),
            c.getInt("last"), mask, c.optString("campus"), c.optString("room"),
            c.optString("teacher"), c.optString("type"));
    }

    static Timetable from(JSONObject root) throws JSONException {
        JSONArray entries = root.getJSONArray("courses");
        List<Course> courses = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject c = entries.getJSONObject(i);
            long mask = c.optLong("mask");
            if (!c.has("mask")) {
                String parity = c.optString("parity");
                for (int week = c.getInt("from"); week <= c.getInt("to"); week++) {
                    if (parity.isEmpty() || parity.equals(week % 2 == 0 ? "even" : "odd")) mask |= 1L << week;
                }
            }
            Course course = readCourse(c, mask);
            if (course.first >= 1 && course.last >= course.first && course.last <= STANDARD.length
                && course.day >= 1 && course.day <= 7) courses.add(course);
        }
        List<Adjustment> adjustments = new ArrayList<>();
        JSONArray changes = root.optJSONArray("adjustments");
        for (int i = 0; changes != null && i < changes.length(); i++) {
            try {
                JSONObject entry = changes.getJSONObject(i);
                JSONObject course = entry.getJSONObject("course");
                String source = entry.optString("source");
                Meeting meeting = new Meeting(readCourse(course, course.optLong("mask")), LocalDate.parse(entry.getString("date")),
                    entry.getInt("start"), entry.getInt("end"), entry.getString("id"));
                if (meeting.start >= 0 && meeting.end > meeting.start && meeting.end < 1440) {
                    adjustments.add(new Adjustment(source.isEmpty() ? null : LocalDate.parse(source),
                        meeting, entry.optBoolean("cancelled")));
                }
            } catch (JSONException | java.time.DateTimeException invalid) {
                // Keep the underlying timetable available if an adjustment cannot be read.
            }
        }
        return new Timetable(root.optString("term", "模拟课表"), LocalDate.parse(root.getString("termStart")),
            root.optBoolean("imported"), courses, adjustments);
    }
}
