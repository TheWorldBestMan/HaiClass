package cn.haike.schedule;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.time.LocalDate;

public final class ScheduleAlarm extends BroadcastReceiver {
    private static final String REMINDER = "cn.haike.schedule.COURSE_REMINDER";
    private static final String WIDGET = "cn.haike.schedule.REFRESH_WIDGET";
    private static final String TEST = "cn.haike.schedule.TEST_REMINDER";
    private static final String LIVE_NEXT = "cn.haike.schedule.LIVE_NEXT";
    private static final String TOMORROW = "cn.haike.schedule.TOMORROW";
    private static final String WEATHER = "cn.haike.schedule.WEATHER";
    private static final String CAMPUS_RUN = "cn.haike.schedule.CAMPUS_RUN";
    private static final String CHANNEL = "course_reminder";
    private static final long MINUTE = 60_000;

    static boolean enabled(Context context) { return ScheduleStore.prefs(context).getBoolean("reminders", false); }
    static int lead(Context context) { return ScheduleStore.prefs(context).getInt("lead", 10); }

    static boolean exact(Context context) {
        return Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    static boolean notificationsAllowed(Context context) {
        return notificationsAllowed(context, CHANNEL);
    }

    static boolean notificationsAllowed(Context context, String channelId) {
        if (Build.VERSION.SDK_INT >= 33
            && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(channelId);
        return manager.areNotificationsEnabled()
            && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    static void refresh(Context context) {
        ScheduleWidget.refresh(context);
        CampusRunWidget.refresh(context);
        scheduleWidget(context);
        refreshNotifications(context);
    }

    static void refreshNotifications(Context context) {
        scheduleReminder(context);
        refreshCampusRun(context);
        refreshTomorrow(context);
        refreshWeather(context);
    }

    static void refreshWeather(Context context) {
        set(context, WEATHER, WeatherAlerts.reschedule(context, System.currentTimeMillis()), true);
        refreshLiveNext(context);
    }

    static void refreshCampusRun(Context context) {
        set(context, CAMPUS_RUN, CampusRun.nextReminder(context, System.currentTimeMillis()), true);
    }

    private static void refreshLiveNext(Context context) {
        long now = System.currentTimeMillis();
        long wake = CourseNotifications.refreshNext(context, now);
        set(context, LIVE_NEXT, wake, true);
        WeatherAlerts.displayStandalone(context, now, wake != 0);
    }

    private static void refreshTomorrow(Context context) {
        set(context, TOMORROW, CourseNotifications.refreshTomorrow(context, System.currentTimeMillis()), true);
    }

    static void scheduleWidget(Context context) {
        if (ScheduleWidget.ids(context).length == 0 && CampusRunWidget.ids(context).length == 0) {
            set(context, WIDGET, 0, false);
            return;
        }
        long now = System.currentTimeMillis();
        long midnight = Timetable.onDate(now).plusDays(1).atStartOfDay(Timetable.CAMPUS).toInstant().toEpochMilli();
        set(context, WIDGET, midnight, false);
    }

    private static void scheduleReminder(Context context) {
        if (!enabled(context) || !notificationsAllowed(context)) {
            set(context, REMINDER, 0, true);
            return;
        }
        Timetable table = ScheduleStore.load(context);
        int[][] periods = ScheduleStore.periods(context);
        long now = System.currentTimeMillis();
        long leadMillis = lead(context) * MINUTE;
        LocalDate start = Timetable.onDate(now);
        if (start.isBefore(table.start)) start = table.start;
        long trigger = 0;
        LocalDate last = table.lastDay();
        for (LocalDate date = start; !date.isAfter(last) && trigger == 0; date = date.plusDays(1)) {
            for (Timetable.Meeting meeting : table.on(date, periods)) {
                long candidate = meeting.startsAt() - leadMillis;
                if (candidate > now) {
                    trigger = candidate;
                    break;
                }
            }
        }
        set(context, REMINDER, trigger, true);
    }

    private static void set(Context context, String action, long when, boolean wake) {
        Intent intent = new Intent(context, ScheduleAlarm.class).setAction(action).putExtra("when", when);
        PendingIntent pending = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (when == 0) {
            manager.cancel(pending);
            return;
        }
        int type = wake ? AlarmManager.RTC_WAKEUP : AlarmManager.RTC;
        if (exact(context)) {
            try {
                if (wake) manager.setExactAndAllowWhileIdle(type, when, pending);
                else manager.setExact(type, when, pending);
                return;
            } catch (SecurityException ignored) {
                // Permission may change between checking and scheduling.
            }
        }
        if (wake) manager.setAndAllowWhileIdle(type, when, pending);
        else manager.set(type, when, pending);
    }

    static boolean test(Context context) {
        if (exact(context)) {
            set(context, TEST, System.currentTimeMillis() + 7_000, true);
            return true;
        }
        post(context, 100, "海课 · 测试提醒", "上课通知已开启。");
        return false;
    }

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (TEST.equals(action)) {
            post(context, 100, "海课 · 测试提醒", "上课通知已开启。");
        } else if (REMINDER.equals(action)) {
            if (enabled(context)) deliver(context, intent.getLongExtra("when", 0));
            scheduleReminder(context);
        } else if (WIDGET.equals(action)) {
            ScheduleWidget.refresh(context);
            CampusRunWidget.refresh(context);
            scheduleWidget(context);
        } else if (CAMPUS_RUN.equals(action)) {
            CampusRun.deliver(context, intent.getLongExtra("when", 0), System.currentTimeMillis());
            refreshCampusRun(context);
        } else if (LIVE_NEXT.equals(action)) {
            refreshLiveNext(context);
        } else if (TOMORROW.equals(action)) {
            refreshTomorrow(context);
        } else if (WEATHER.equals(action)) {
            refreshWeather(context);
        } else {
            refresh(context);
        }
    }

    private static void deliver(Context context, long trigger) {
        if (trigger == 0) return;
        long starts = trigger + lead(context) * MINUTE;
        if (System.currentTimeMillis() >= starts) return;
        Timetable table = ScheduleStore.load(context);
        int[][] periods = ScheduleStore.periods(context);
        for (Timetable.Meeting meeting : table.on(Timetable.onDate(starts), periods)) {
            if (meeting.startsAt() == starts) {
                post(context, 1000 + meeting.hashCode() % 500_000_000, meeting.course().name(),
                    meeting.times() + " · " + meeting.course().roomLabel());
            }
        }
    }

    private static void post(Context context, int id, String title, String text) {
        if (!notificationsAllowed(context)) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "上课提醒", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent open = PendingIntent.getActivity(context, 30, new Intent(context, MainActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE);
        manager.notify(id, new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build());
    }
}
