package cn.haike.schedule;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

import org.json.JSONObject;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;

public final class RegressionRunner extends Instrumentation {
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            timetableAdjustments();
            studyReminders();
            result.putString("stream", "\nTimetable and study reminder regression checks passed.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError failure) {
            result.putString("stream", "\n" + android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private static void timetableAdjustments() throws Exception {
        LocalDate monday = LocalDate.of(2026, 11, 23);
        Timetable.Course oddWeeks = new Timetable.Course("英语", 1, 1, 2, (1L << 1) | (1L << 3),
            "湖光", "A1", "教师", "");
        Timetable table = new Timetable("测试课表", monday, true, Collections.singletonList(oddWeeks));
        int[][] periods = Timetable.STANDARD;
        Timetable.Meeting original = table.on(monday, periods).get(0);
        LocalDate later = monday.plusWeeks(5);
        Timetable.Meeting moved = new Timetable.Meeting(oddWeeks, later, 600, 645, original.id());
        Timetable adjusted = table.withAdjustment(original.id(), new Timetable.Adjustment(monday, moved, false));
        require(adjusted.on(monday, periods).isEmpty(), "Moved meeting was not removed from its original day");
        require(adjusted.on(monday.plusWeeks(1), periods).isEmpty(), "Even week received an odd-week course");
        require(adjusted.on(monday.plusWeeks(2), periods).size() == 1, "Another occurrence was affected");
        require(moved.equals(adjusted.upcoming(Timetable.epoch(monday.plusWeeks(3), 0), periods)),
            "An adjustment past the last teaching week did not reach the reminder schedule");
        Timetable cancelled = adjusted.withAdjustment(original.id(), new Timetable.Adjustment(monday, moved, true));
        require(cancelled.on(later, periods).isEmpty(), "Cancelled move remained visible");
        require(cancelled.withAdjustment(original.id(), null).on(monday, periods).equals(Arrays.asList(original)),
            "Undo did not restore the original meeting");
        Timetable.Meeting makeup = new Timetable.Meeting(oddWeeks, monday.minusDays(1), 700, 745, "new");
        adjusted = adjusted.withAdjustment(makeup.id(), new Timetable.Adjustment(null, makeup, false));
        Timetable restored = Timetable.from(adjusted.json());
        require(restored.on(later, periods).equals(Arrays.asList(moved)), "Moved meeting did not survive serialization");
        require(restored.upcoming(Timetable.epoch(makeup.date(), 0), periods).equals(makeup),
            "Makeup at the edge of the semester did not survive serialization or filtering");
        JSONObject old = table.json();
        old.remove("adjustments");
        require(Timetable.from(old).on(monday, periods).equals(Arrays.asList(original)), "v0.13 timetable format changed");
    }

    private static void studyReminders() {
        LocalDate date = LocalDate.of(2026, 11, 24);
        long due = Timetable.epoch(date, 540);
        require(StudyItems.reminderAt(due, 60, due - 2 * 3_600_000L) == due - 3_600_000L, "Lead time was lost");
        require(StudyItems.reminderAt(due, 60, due - 1000) == due, "Expired lead did not fall back to due time");
        require(StudyItems.reminderAt(due, 60, due) == 0, "Old item scheduled a reminder");
        require(StudyItems.reminderAt(due, -1, due - 1000) == 0, "Disabled item scheduled a reminder");
        StudyItems.Item created = StudyItems.edited(null, true, "复习", date, 540, "", "", "", 0, due - 1000);
        require(created.awaiting(due) && !created.awaiting(due + 30 * 60_000L), "Late alarm boundary changed");
        require(!created.withDone(true).awaiting(due), "Completed item can still notify");
        StudyItems.Item sent = created.markedSent();
        StudyItems.Item renamed = StudyItems.edited(sent, true, "新标题", date, 540, "", "", "", 0, due - 500);
        require(!renamed.awaiting(due), "Editing display fields sent a duplicate");
        StudyItems.Item changed = StudyItems.edited(sent, true, "新时间", date, 541, "", "", "", 0, due);
        require(changed.awaiting(due) && changed.alarm() == due + 60_000L, "A changed time was not rescheduled");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
