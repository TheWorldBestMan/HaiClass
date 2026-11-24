package cn.haike.schedule;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import java.time.LocalDate;
import java.util.List;

final class CourseNotifications {
    static final String NEXT_CHANNEL = "next_class_live";
    static final String TOMORROW_CHANNEL = "tomorrow_schedule";
    static final String COURSE_TIME = "course_time";
    static final String COURSE_NAME = "course_name";
    static final String COURSE_ID = "course_id";
    static final String COURSE_KEY = "course_key";
    static final long COUNTDOWN_WINDOW = 60 * 60_000L;
    private static final String NEXT_ENABLED = "live_next_enabled";
    private static final String TOMORROW_ENABLED = "tomorrow_enabled";
    private static final String SENT_DAY = "tomorrow_sent_day";
    private static final int NEXT_ID = 211, TOMORROW_ID = 212;

    record Window(int start, int end) {
        boolean valid() { return start >= 0 && start < end && end < 24 * 60; }
        String label() { return Timetable.clock(start) + "–" + Timetable.clock(end); }
    }

    static Window window(Context context) {
        SharedPreferences prefs = ScheduleStore.prefs(context);
        Window saved = new Window(prefs.getInt("tomorrow_start", 20 * 60), prefs.getInt("tomorrow_end", 22 * 60));
        return saved.valid() ? saved : new Window(20 * 60, 22 * 60);
    }

    static void saveWindow(Context context, Window window) {
        if (!window.valid()) throw new IllegalArgumentException("Invalid notification window");
        ScheduleStore.prefs(context).edit().putInt("tomorrow_start", window.start).putInt("tomorrow_end", window.end).apply();
    }

    static boolean nextEnabled(Context context) {
        return ScheduleStore.prefs(context).getBoolean(NEXT_ENABLED, false);
    }

    static boolean tomorrowEnabled(Context context) {
        return ScheduleStore.prefs(context).getBoolean(TOMORROW_ENABLED, false);
    }

    static void setEnabled(Context context, boolean live, boolean enabled) {
        ScheduleStore.prefs(context).edit().putBoolean(live ? NEXT_ENABLED : TOMORROW_ENABLED, enabled).apply();
    }

    static boolean promoted(Context context) {
        return Build.VERSION.SDK_INT >= 36 && context.getSystemService(NotificationManager.class).canPostPromotedNotifications();
    }

    // The returned wall-clock instant is the next time this card needs to change.
    static long refreshNext(Context context, long now) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!nextEnabled(context) || !ScheduleAlarm.notificationsAllowed(context, NEXT_CHANNEL)) {
            manager.cancel(NEXT_ID);
            return 0;
        }
        Timetable.Meeting next = ScheduleStore.load(context).upcoming(now, ScheduleStore.periods(context));
        if (next == null) {
            manager.cancel(NEXT_ID);
            return 0;
        }
        manager.createNotificationChannel(new NotificationChannel(NEXT_CHANNEL, "下一堂课 · 实时通知", NotificationManager.IMPORTANCE_LOW));
        LocalDate today = Timetable.onDate(now);
        Timetable.Course course = next.course();
        long remaining = next.startsAt() - now;
        boolean countingDown = remaining <= COUNTDOWN_WINDOW;
        String day = shortDate(next.date(), today);
        String start = Timetable.clock(next.start());
        String glance = (countingDown ? course.name() + " · " : "") + day + " " + start + " · " + course.roomLabel();
        StringBuilder detail = new StringBuilder();
        if (countingDown) detail.append(course.name()).append("\n");
        detail.append(day).append(" ").append(next.times())
            .append(" · ").append(course.periodLabel().replace(" ", "")).append("\n").append(course.roomLabel());
        String chip = start;
        WeatherForecast.Outlook near = WeatherAlerts.upcomingClassOutlook(context, next, now);
        WeatherForecast.Outlook live = WeatherAlerts.liveOutlook(context, now);
        WeatherForecast.Outlook glanceWeather = near == null ? live : near;
        if (glanceWeather != null) {
            WeatherForecast.Campus campus = near == null ? WeatherForecast.campus(context) : WeatherForecast.classCampus(context, next);
            String current = WeatherForecast.currentLabel(WeatherForecast.currentTemperature(context, campus, now));
            detail.append("\n");
            if (!current.isEmpty()) detail.append(current).append(" · ");
            detail.append(near == null ? "稍后" : "课前").append(glanceWeather.conditionLabel());
            String tips = glanceWeather.risk().quickTips();
            if (!tips.isEmpty()) detail.append("\n").append(tips);
            if (!current.isEmpty()) glance += " · " + current;
            chip = glanceWeather.chipCondition() + start;
        } else if (!course.teacher().isEmpty()) {
            detail.append("\n").append(course.teacher());
        }
        PendingIntent open = open(context, next.date(), next, NEXT_ID);
        Notification.Builder card = new Notification.Builder(context, NEXT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(countingDown ? "下一堂课" : course.name())
            .setSubText(countingDown ? null : "下一堂课")
            .setContentText(glance)
            .setStyle(new Notification.BigTextStyle().bigText(detail))
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "查看完整详情", open).build())
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setTimeoutAfter(remaining);
        if (countingDown) {
            card.setWhen(next.startsAt()).setShowWhen(true)
                .setUsesChronometer(true).setChronometerCountDown(true);
        }
        promote(card, countingDown ? null : chip);
        manager.notify(NEXT_ID, card.build());
        long nextChange = countingDown ? next.startsAt() : next.startsAt() - COUNTDOWN_WINDOW;
        return Math.min(nextChange, Timetable.epoch(today.plusDays(1), 0));
    }

    private static String shortDate(LocalDate date, LocalDate today) {
        if (date.equals(today)) return "今天";
        if (date.equals(today.plusDays(1))) return "明天";
        return date.getMonthValue() + "/" + date.getDayOfMonth();
    }

    static void promote(Notification.Builder card, String shortText) {
        if (Build.VERSION.SDK_INT >= 36) {
            Bundle extras = new Bundle();
            extras.putBoolean("android.requestPromotedOngoing", true);
            card.addExtras(extras).setShortCriticalText(shortText);
        }
    }

    static long refreshTomorrow(Context context, long now) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!tomorrowEnabled(context) || !ScheduleAlarm.notificationsAllowed(context, TOMORROW_CHANNEL)) {
            manager.cancel(TOMORROW_ID);
            return 0;
        }
        LocalDate today = Timetable.onDate(now);
        Window window = window(context);
        long start = Timetable.epoch(today, window.start);
        long end = Timetable.epoch(today, window.end);
        if (now < start || now >= end) {
            manager.cancel(TOMORROW_ID);
            return now < start ? start : Timetable.epoch(today.plusDays(1), window.start);
        }
        LocalDate tomorrow = today.plusDays(1);
        List<Timetable.Meeting> meetings = ScheduleStore.load(context).on(tomorrow, ScheduleStore.periods(context));
        if (meetings.isEmpty()) {
            manager.cancel(TOMORROW_ID);
            return end;
        }
        SharedPreferences prefs = ScheduleStore.prefs(context);
        long targetDay = tomorrow.toEpochDay();
        boolean alreadySent = prefs.getLong(SENT_DAY, Long.MIN_VALUE) == targetDay;
        if (alreadySent && !visible(manager, TOMORROW_ID)) return end;

        manager.createNotificationChannel(new NotificationChannel(TOMORROW_CHANNEL, "明天课表", NotificationManager.IMPORTANCE_DEFAULT));
        Timetable.Meeting first = meetings.get(0);
        StringBuilder detail = new StringBuilder();
        for (Timetable.Meeting meeting : meetings) {
            if (detail.length() > 0) detail.append("\n");
            Timetable.Course course = meeting.course();
            detail.append(meeting.times()).append(" · ").append(course.periodLabel()).append("\n")
                .append(course.name()).append(" · ").append(course.roomLabel());
        }
        PendingIntent open = open(context, tomorrow, null, TOMORROW_ID);
        Notification note = new Notification.Builder(context, TOMORROW_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("明天课表 · " + meetings.size() + " 堂课")
            .setContentText(Timetable.clock(first.start()) + " 首课 · " + first.course().name())
            .setStyle(new Notification.BigTextStyle().bigText(detail)
                .setSummaryText(Timetable.dateLabel(tomorrow) + " " + Timetable.weekday(tomorrow)))
            .addAction(new Notification.Action.Builder(null, "打开明天课表", open).build())
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setTimeoutAfter(end - now)
            .build();
        manager.notify(TOMORROW_ID, note);
        if (!alreadySent) prefs.edit().putLong(SENT_DAY, targetDay).apply();
        return end;
    }

    private static boolean visible(NotificationManager manager, int id) {
        for (StatusBarNotification item : manager.getActiveNotifications()) {
            if (item.getId() == id) return true;
        }
        return false;
    }

    static PendingIntent open(Context context, LocalDate date, Timetable.Meeting meeting, int id) {
        Uri.Builder target = new Uri.Builder().scheme("haiclass").authority("notification");
        target.appendPath(meeting == null ? "day" : "lesson").appendPath(date.toString());
        Intent intent = new Intent(context, MainActivity.class).setAction(ScheduleWidget.OPEN_DAY)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(ScheduleWidget.DATE, date.toEpochDay());
        if (meeting != null) {
            target.appendPath(Long.toString(meeting.startsAt())).appendPath(meeting.id());
            intent.putExtra(COURSE_TIME, meeting.startsAt()).putExtra(COURSE_ID, meeting.id());
        }
        intent.setData(target.build());
        return PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE);
    }
}
