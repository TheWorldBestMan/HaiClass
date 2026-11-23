package cn.haike.schedule;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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
            return first == last ? "第 " + first + " 节" : "第 " + first + "–" + last + " 节";
        }

        String roomLabel() { return room.trim().isEmpty() ? "教室待定" : room; }

        boolean staggered() {
            return (campus.contains("湖光") && (room.contains("广学楼") || room.contains("明德楼")))
                || (campus.contains("海滨") && (room.contains("实验楼") || room.contains("室外")));
        }
    }

    record Meeting(Course course, LocalDate date, int start, int end) {
        long startsAt() { return epoch(date, start); }
        long endsAt() { return epoch(date, end); }
        String times() { return clock(start) + "–" + clock(end); }
    }

    final String term;
    final LocalDate start;
    final boolean imported;
    final List<Course> courses;

    Timetable(String term, LocalDate start, boolean imported, List<Course> courses) {
        this.term = term;
        this.start = start;
        this.imported = imported;
        this.courses = Collections.unmodifiableList(new ArrayList<>(courses));
    }

    Timetable withStart(LocalDate value) {
        return new Timetable(term, value, imported, courses);
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
            result.add(new Meeting(course, date, begins, ends));
        }
        result.sort(Comparator.comparingInt(Meeting::start));
        return result;
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

    LocalDate lastDay() { return start.plusDays(lastWeek() * 7L - 1); }

    Meeting next(long now, int[][] periods) {
        return nextMeeting(now, periods, true);
    }

    Meeting upcoming(long now, int[][] periods) {
        return nextMeeting(now, periods, false);
    }

    private Meeting nextMeeting(long now, int[][] periods, boolean includeOngoing) {
        LocalDate day = onDate(now);
        if (day.isBefore(start)) day = start;
        LocalDate last = lastDay();
        for (; !day.isAfter(last); day = day.plusDays(1)) {
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
        for (Course c : courses) {
            entries.put(new JSONObject().put("name", c.name).put("day", c.day).put("first", c.first)
                .put("last", c.last).put("mask", c.weeks).put("campus", c.campus).put("room", c.room)
                .put("teacher", c.teacher).put("type", c.type));
        }
        return root.put("courses", entries);
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
            courses.add(new Course(c.getString("name"), c.getInt("day"), c.getInt("first"),
                c.getInt("last"), mask, c.optString("campus"), c.optString("room"),
                c.optString("teacher"), c.optString("type")));
        }
        return new Timetable(root.optString("term", "模拟课表"), LocalDate.parse(root.getString("termStart")),
            root.optBoolean("imported"), courses);
    }
}
