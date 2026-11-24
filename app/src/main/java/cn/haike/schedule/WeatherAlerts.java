package cn.haike.schedule;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class WeatherAlerts {
    static final String CHANNEL = "campus_weather";
    static final String LIVE_CHANNEL = "campus_weather_live";
    static final String CLASS_CHANNEL = "class_weather";
    static final String OPEN_WEATHER = "cn.haike.schedule.OPEN_WEATHER";
    static final String CLASS_START = "weather_class_start";
    static final String CLASS_NAME = "weather_class_name";
    static final String WEATHER_CAMPUS = "weather_campus";
    private static final String SENT_CLASSES = "weather_sent_classes";
    private static final int WEATHER_ID = 213, LIVE_ID = 214, CLASS_ID = 215, JOB_ID = 220;
    private static final int CHECK_AT = 7 * 60, CHECK_UNTIL = 10 * 60;
    private static final long NEVER = Long.MAX_VALUE;

    enum Feature {
        MORNING("weather_enabled", CHANNEL),
        LIVE("weather_live_enabled", LIVE_CHANNEL),
        BEFORE_CLASS("weather_preclass_enabled", CLASS_CHANNEL);

        final String key;
        final String channel;

        Feature(String key, String channel) {
            this.key = key;
            this.channel = channel;
        }
    }

    private record Checks(WeatherForecast.Campus campus, boolean morning, boolean liveFetch, WeatherForecast.Campus nearCampus,
                          List<Timetable.Meeting> classes, long wake) {
        Set<WeatherForecast.Campus> campuses(Context context) {
            Set<WeatherForecast.Campus> needed = new LinkedHashSet<>();
            if (morning || liveFetch) needed.add(campus);
            if (nearCampus != null) needed.add(nearCampus);
            for (Timetable.Meeting meeting : classes) needed.add(WeatherForecast.classCampus(context, meeting));
            return needed;
        }
    }

    static boolean enabled(Context context, Feature feature) {
        return ScheduleStore.prefs(context).getBoolean(feature.key, false);
    }

    static void setEnabled(Context context, Feature feature, boolean enabled) {
        ScheduleStore.prefs(context).edit().putBoolean(feature.key, enabled).apply();
    }

    private static boolean allowed(Context context, Feature feature) {
        return enabled(context, feature) && ScheduleAlarm.notificationsAllowed(context, feature.channel);
    }

    static long reschedule(Context context, long now) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        Checks checks = checks(context, now);
        if (checks.wake == NEVER) {
            jobs.cancel(JOB_ID);
        } else if (!checks.campuses(context).isEmpty() && jobs.getPendingJob(JOB_ID) == null) {
            JobInfo.Builder job = new JobInfo.Builder(JOB_ID, new ComponentName(context, WeatherJobService.class))
                .setBackoffCriteria(3 * 60_000L, JobInfo.BACKOFF_POLICY_LINEAR);
            if (Build.VERSION.SDK_INT >= 28) {
                job.setRequiredNetwork(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build());
            }
            jobs.schedule(job.build());
        }
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (!allowed(context, Feature.MORNING)) notifications.cancel(WEATHER_ID);
        pruneClassNotifications(context, notifications);
        return checks.wake == NEVER ? 0 : checks.wake;
    }

    private static void pruneClassNotifications(Context context, NotificationManager manager) {
        boolean enabled = allowed(context, Feature.BEFORE_CLASS);
        Timetable table = null;
        int[][] periods = null;
        for (StatusBarNotification alert : manager.getActiveNotifications()) {
            if (!CLASS_CHANNEL.equals(alert.getNotification().getChannelId())) continue;
            if (table == null && enabled) {
                table = ScheduleStore.load(context);
                periods = ScheduleStore.periods(context);
            }
            Bundle extras = alert.getNotification().extras;
            Timetable.Meeting meeting = table == null ? null : table.find(extras.getLong(CLASS_START),
                extras.getString(CourseNotifications.COURSE_ID), periods);
            if (meeting == null || !classKey(context, meeting).equals(alert.getTag())) {
                manager.cancel(alert.getTag(), alert.getId());
            }
        }
    }

    static Set<WeatherForecast.Campus> dueCampuses(Context context) {
        return checks(context, System.currentTimeMillis()).campuses(context);
    }

    private static Checks checks(Context context, long now) {
        WeatherForecast.Campus campus = WeatherForecast.campus(context);
        LocalDate today = Timetable.onDate(now);
        boolean morningDue = false;
        boolean liveFetch = false;
        WeatherForecast.Campus nearCampus = null;
        long wake = NEVER;

        if (allowed(context, Feature.MORNING)) {
            long morning = Timetable.epoch(today, CHECK_AT);
            wake = now < morning ? morning : Timetable.epoch(today.plusDays(1), CHECK_AT);
            morningDue = now >= morning && now < Timetable.epoch(today, CHECK_UNTIL)
                && ScheduleStore.prefs(context).getLong(checkedKey(campus), Long.MIN_VALUE) != today.toEpochDay();
        }
        if (allowed(context, Feature.LIVE)) {
            WeatherForecast.Snapshot snapshot = WeatherForecast.cached(context, campus);
            liveFetch = snapshot == null || !snapshot.fresh(now);
            wake = Math.min(wake, (now / WeatherForecast.LIVE_REFRESH + 1) * WeatherForecast.LIVE_REFRESH);
            if (CourseNotifications.nextEnabled(context) && ScheduleAlarm.notificationsAllowed(context, CourseNotifications.NEXT_CHANNEL)) {
                Timetable.Meeting next = ScheduleStore.load(context).upcoming(now, ScheduleStore.periods(context));
                if (next != null) {
                    long before = next.startsAt() - WeatherForecast.HOUR;
                    if (before > now) wake = Math.min(wake, before);
                    else {
                        WeatherForecast.Campus classCampus = WeatherForecast.classCampus(context, next);
                        WeatherForecast.Snapshot classForecast = WeatherForecast.cached(context, classCampus);
                        if (classForecast == null || !classForecast.fresh(now)) nearCampus = classCampus;
                    }
                }
            }
        }

        List<Timetable.Meeting> dueClasses = new ArrayList<>();
        if (allowed(context, Feature.BEFORE_CLASS)) {
            Timetable table = ScheduleStore.load(context);
            int[][] periods = ScheduleStore.periods(context);
            Set<String> sent = ScheduleStore.prefs(context).getStringSet(SENT_CLASSES, Collections.emptySet());
            findClasses:
            for (LocalDate date : table.datesFrom(today)) {
                for (Timetable.Meeting meeting : table.on(date, periods)) {
                    long starts = meeting.startsAt();
                    if (starts <= now || sent.contains(classKey(context, meeting))) continue;
                    long trigger = starts - WeatherForecast.HOUR;
                    if (trigger > now) {
                        wake = Math.min(wake, trigger);
                        break findClasses;
                    }
                    dueClasses.add(meeting);
                    wake = Math.min(wake, starts);
                }
            }
        }
        return new Checks(campus, morningDue, liveFetch, nearCampus, dueClasses, wake);
    }

    static void deliverDue(Context context) {
        long now = System.currentTimeMillis();
        Checks checks = checks(context, now);
        if (checks.morning) {
            WeatherForecast.Snapshot snapshot = WeatherForecast.usable(context, checks.campus, now);
            LocalDate today = Timetable.onDate(now);
            WeatherForecast.Day day = snapshot == null ? null : snapshot.on(today);
            if (day != null) {
                List<String> tips = day.tips();
                if (!tips.isEmpty()) postMorning(context, checks.campus, day, tips);
                ScheduleStore.prefs(context).edit().putLong(checkedKey(checks.campus), today.toEpochDay()).apply();
            }
        }
        if (checks.classes.isEmpty()) return;

        Set<String> sent = new HashSet<>(ScheduleStore.prefs(context).getStringSet(SENT_CLASSES, Collections.emptySet()));
        sent.removeIf(key -> !recent(key, now));
        for (Timetable.Meeting meeting : checks.classes) {
            String key = classKey(context, meeting);
            if (sent.contains(key)) continue;
            WeatherForecast.ClassWindow window = WeatherForecast.before(context, meeting);
            WeatherForecast.Outlook forecast = window.outlook(WeatherForecast.usable(context, window.campus(), now));
            if (forecast != null) {
                postBeforeClass(context, key, meeting, window, forecast, now);
                sent.add(key);
            }
        }
        ScheduleStore.prefs(context).edit().putStringSet(SENT_CLASSES, sent).apply();
    }

    static WeatherForecast.Outlook liveOutlook(Context context, long now) {
        if (!allowed(context, Feature.LIVE)) return null;
        WeatherForecast.Snapshot snapshot = WeatherForecast.usable(context, WeatherForecast.campus(context), now);
        return snapshot == null ? null : snapshot.outlook(now, now + 3 * WeatherForecast.HOUR);
    }

    static WeatherForecast.Outlook upcomingClassOutlook(Context context, Timetable.Meeting meeting, long now) {
        if (!allowed(context, Feature.LIVE) || meeting.startsAt() - now > WeatherForecast.HOUR) return null;
        WeatherForecast.ClassWindow window = WeatherForecast.before(context, meeting);
        return window.outlook(WeatherForecast.usable(context, window.campus(), now));
    }

    static void displayStandalone(Context context, long now, boolean courseCardVisible) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        WeatherForecast.Outlook outlook = courseCardVisible ? null : liveOutlook(context, now);
        if (outlook == null) {
            manager.cancel(LIVE_ID);
            return;
        }
        WeatherForecast.Campus campus = WeatherForecast.campus(context);
        manager.createNotificationChannel(new NotificationChannel(LIVE_CHANNEL, "校园天气 · 实时通知", NotificationManager.IMPORTANCE_LOW));
        double temperature = WeatherForecast.currentTemperature(context, campus, now);
        String current = WeatherForecast.currentLabel(temperature);
        String first = current.isEmpty() ? "未来 3 小时 · " + outlook.conditionLabel() : current + " · 稍后" + outlook.conditionLabel();
        StringBuilder detail = new StringBuilder(first);
        List<String> metrics = new ArrayList<>();
        if (!outlook.rainMetric().isEmpty()) metrics.add(outlook.rainMetric());
        if (!outlook.uvMetric().isEmpty()) metrics.add(outlook.uvMetric());
        if (!metrics.isEmpty()) detail.append("\n").append("未来3小时 ").append(String.join(" · ", metrics));
        String tips = outlook.risk().quickTips();
        if (!tips.isEmpty()) detail.append("\n").append(tips);
        PendingIntent open = open(context, LIVE_ID);
        Notification.Builder card = new Notification.Builder(context, LIVE_CHANNEL)
            .setSmallIcon(R.drawable.ic_weather)
            .setContentTitle(campus.title + "天气")
            .setContentText(first)
            .setStyle(new Notification.BigTextStyle().bigText(detail))
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "查看完整天气", open).build())
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setTimeoutAfter(2 * WeatherForecast.HOUR);
        String chip = Double.isFinite(temperature)
            ? outlook.chipCondition() + Math.round(temperature) + "°" : outlook.conditionLabel();
        CourseNotifications.promote(card, chip);
        manager.notify(LIVE_ID, card.build());
    }

    private static String classKey(Context context, Timetable.Meeting meeting) {
        return meeting.startsAt() + "|" + WeatherForecast.classCampus(context, meeting).name()
            + "|" + meeting.notificationKey();
    }

    private static boolean recent(String key, long now) {
        int separator = key.indexOf('|');
        if (separator < 0) return false;
        try {
            return Long.parseLong(key.substring(0, separator)) >= now - 36 * WeatherForecast.HOUR;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static String checkedKey(WeatherForecast.Campus campus) {
        return "weather_checked_" + campus.name();
    }

    static void cancelNotification(Context context) {
        context.getSystemService(NotificationManager.class).cancel(WEATHER_ID);
    }

    private static PendingIntent open(Context context, int request) {
        Intent activity = new Intent(context, MainActivity.class).setAction(OPEN_WEATHER).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, request, activity, PendingIntent.FLAG_IMMUTABLE);
    }

    private static void postBeforeClass(Context context, String key, Timetable.Meeting meeting,
                                        WeatherForecast.ClassWindow window, WeatherForecast.Outlook forecast, long now) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CLASS_CHANNEL, "课前 1 小时天气", NotificationManager.IMPORTANCE_DEFAULT));
        long start = window.startsAt();
        String line = WeatherForecast.clock(start) + " 上课 · " + forecast.conditionLabel();
        String detail = meeting.course().name() + " · " + window.campus().title + "\n"
            + window.label() + " 课前预报 · " + forecast.summary();
        List<String> tips = forecast.risk().tips();
        if (!tips.isEmpty()) detail += "\n" + String.join("\n", tips);

        Intent day = new Intent(context, MainActivity.class).setAction(OPEN_WEATHER).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .setData(new Uri.Builder().scheme("haiclass").authority("class-weather").appendPath(key).build())
            .putExtra(ScheduleWidget.DATE, meeting.date().toEpochDay())
            .putExtra(WEATHER_CAMPUS, window.campus().name())
            .putExtra(CLASS_START, start)
            .putExtra(CourseNotifications.COURSE_ID, meeting.id());
        PendingIntent open = PendingIntent.getActivity(context, CLASS_ID, day,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Bundle extras = new Bundle();
        extras.putLong(CLASS_START, start);
        extras.putString(CourseNotifications.COURSE_ID, meeting.id());
        manager.notify(key, CLASS_ID, new Notification.Builder(context, CLASS_CHANNEL)
            .setSmallIcon(R.drawable.ic_weather)
            .setContentTitle("课前天气 · " + meeting.course().name())
            .setContentText(tips.isEmpty() ? line : line + " · " + tips.get(0))
            .setStyle(new Notification.BigTextStyle().bigText(detail))
            .setContentIntent(open)
            .addExtras(extras)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setTimeoutAfter(start - now)
            .build());
    }

    private static void postMorning(Context context, WeatherForecast.Campus campus, WeatherForecast.Day day, List<String> tips) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "校园天气提醒", NotificationManager.IMPORTANCE_DEFAULT));
        manager.notify(WEATHER_ID, new Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_weather)
            .setContentTitle(campus.title + " · 出门提醒")
            .setContentText(String.join(" · ", tips))
            .setStyle(new Notification.BigTextStyle().bigText(String.join("\n", tips) + "\n" + day.summary()))
            .setContentIntent(open(context, WEATHER_ID))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(Timetable.epoch(day.date().plusDays(1), 0) - System.currentTimeMillis())
            .build());
    }
}
