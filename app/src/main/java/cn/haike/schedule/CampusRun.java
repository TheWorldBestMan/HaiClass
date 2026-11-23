package cn.haike.schedule;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class CampusRun {
    static final String CHANNEL = "campus_run";
    static final String OPEN_SETTINGS = "cn.haike.schedule.OPEN_CAMPUS_RUN";
    static final String EDIT_COUNT = "cn.haike.schedule.EDIT_CAMPUS_RUN_COUNT";
    static final int GOAL = 60;
    private static final String ENABLED = "campus_run_enabled";
    private static final String COUNT_ADJUSTMENT = "campus_run_count_adjustment";
    private static final int NOON = 12 * 60;
    private static final int NOTIFICATION_ID = 213;
    private static final long LEAD = 30 * 60_000L;

    enum Window {
        MORNING("campus_run_completed_days", "campus_run_morning_sent"),
        AFTERNOON("campus_run_afternoon_completed_days", "campus_run_afternoon_sent");

        final String completedDays;
        final String sentDay;

        Window(String completedDays, String sentDay) {
            this.completedDays = completedDays;
            this.sentDay = sentDay;
        }
    }

    private CampusRun() {}

    static boolean opensSettings(Intent intent) {
        return OPEN_SETTINGS.equals(intent.getAction()) || EDIT_COUNT.equals(intent.getAction());
    }

    static boolean enabled(Context context) {
        return ScheduleStore.prefs(context).getBoolean(ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        ScheduleStore.prefs(context).edit().putBoolean(ENABLED, enabled).apply();
        if (!enabled) context.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        ScheduleAlarm.refreshCampusRun(context);
    }

    static boolean completed(Context context, LocalDate day, Window window) {
        return completedDays(ScheduleStore.prefs(context), window).contains(day.toString());
    }

    static int count(Context context) {
        SharedPreferences prefs = ScheduleStore.prefs(context);
        long adjusted = (long) recordedCount(prefs) + prefs.getInt(COUNT_ADJUSTMENT, 0);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(todayCount(context), adjusted));
    }

    static int todayCount(Context context) {
        LocalDate day = Timetable.today();
        int total = completed(context, day, Window.MORNING) ? 1 : 0;
        return total + (completed(context, day, Window.AFTERNOON) ? 1 : 0);
    }

    static boolean setCount(Context context, int total) {
        if (total < todayCount(context)) return false;
        SharedPreferences prefs = ScheduleStore.prefs(context);
        prefs.edit().putInt(COUNT_ADJUSTMENT, total - recordedCount(prefs)).apply();
        refreshProgress(context, total >= GOAL);
        return true;
    }

    private static int recordedCount(SharedPreferences prefs) {
        return completedDays(prefs, Window.MORNING).size() + completedDays(prefs, Window.AFTERNOON).size();
    }

    static void setCompleted(Context context, LocalDate day, Window window, boolean completed) {
        long now = System.currentTimeMillis();
        if (!day.equals(Timetable.onDate(now))) {
            CampusRunWidget.refresh(context);
            return;
        }
        SharedPreferences prefs = ScheduleStore.prefs(context);
        Set<String> days = new HashSet<>(completedDays(prefs, window));
        if (completed) days.add(day.toString());
        else days.remove(day.toString());
        prefs.edit().putStringSet(window.completedDays, days).apply();
        refreshProgress(context, completed && (window == windowAt(now, day) || count(context) >= GOAL));
    }

    private static void refreshProgress(Context context, boolean clearNotification) {
        if (clearNotification) context.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        ScheduleAlarm.refreshCampusRun(context);
        CampusRunWidget.refresh(context);
    }

    static long nextReminder(Context context, long now) {
        if (!canNotify(context)) return 0;
        Timetable table = ScheduleStore.load(context);
        int[][] periods = ScheduleStore.periods(context);
        SharedPreferences prefs = ScheduleStore.prefs(context);
        LocalDate first = Timetable.onDate(now);
        if (first.isBefore(table.start)) first = table.start;
        LocalDate last = table.lastDay();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            for (Timetable.Meeting meeting : table.on(day, periods)) {
                long reminder = meeting.startsAt() - LEAD;
                if (reminder > now && canRemindAt(prefs, reminder)) return reminder;
            }
        }
        return 0;
    }

    static void deliver(Context context, long reminder, long now) {
        if (reminder <= 0 || now < reminder || now >= reminder + LEAD || !canNotify(context)) return;
        // A delayed alarm cannot use up the following half-day's reminder.
        long end = windowEnd(reminder);
        SharedPreferences prefs = ScheduleStore.prefs(context);
        if (now >= end || !canRemindAt(prefs, reminder)) return;
        long starts = reminder + LEAD;
        Timetable table = ScheduleStore.load(context);
        for (Timetable.Meeting meeting : table.on(Timetable.onDate(starts), ScheduleStore.periods(context))) {
            if (meeting.startsAt() != starts) continue;
            post(context, meeting, Math.min(end, starts) - now);
            LocalDate day = Timetable.onDate(reminder);
            prefs.edit().putLong(windowAt(reminder, day).sentDay, day.toEpochDay()).apply();
            return;
        }
    }

    private static boolean canNotify(Context context) {
        return enabled(context) && count(context) < GOAL && ScheduleAlarm.notificationsAllowed(context, CHANNEL);
    }

    private static boolean canRemindAt(SharedPreferences prefs, long reminder) {
        LocalDate day = Timetable.onDate(reminder);
        Window window = windowAt(reminder, day);
        return !completedDays(prefs, window).contains(day.toString())
            && prefs.getLong(window.sentDay, Long.MIN_VALUE) != day.toEpochDay();
    }

    private static Set<String> completedDays(SharedPreferences prefs, Window window) {
        return prefs.getStringSet(window.completedDays, Collections.emptySet());
    }

    private static Window windowAt(long when, LocalDate day) {
        return when < Timetable.epoch(day, NOON) ? Window.MORNING : Window.AFTERNOON;
    }

    private static long windowEnd(long when) {
        LocalDate day = Timetable.onDate(when);
        long noon = Timetable.epoch(day, NOON);
        return when < noon ? noon : Timetable.epoch(day.plusDays(1), 0);
    }

    private static void post(Context context, Timetable.Meeting meeting, long expiresAfter) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        String title = context.getString(R.string.run_channel);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, title, NotificationManager.IMPORTANCE_HIGH));
        PendingIntent open = PendingIntent.getActivity(context, NOTIFICATION_ID, new Intent(context, MainActivity.class)
            .setAction(OPEN_SETTINGS).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE);
        String text = Timetable.clock(meeting.start()) + " 上「" + meeting.course().name() + "」，记得去校园跑。";
        manager.notify(NOTIFICATION_ID, new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
            .setTimeoutAfter(expiresAfter).setAutoCancel(true).setContentIntent(open).build());
    }
}
