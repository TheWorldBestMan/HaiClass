package cn.haike.schedule;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class ScheduleWidget extends AppWidgetProvider {
    static final String OPEN_DAY = "cn.haike.schedule.OPEN_WIDGET_DAY";
    static final String DATE = "widget_date";
    private static final int DAYS = 7;

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        refresh(context);
        ScheduleAlarm.scheduleWidget(context);
    }

    @Override public void onDisabled(Context context) {
        ScheduleAlarm.scheduleWidget(context);
    }

    static int[] ids(Context context) {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context, ScheduleWidget.class));
    }

    static void refresh(Context context) {
        int[] ids = ids(context);
        if (ids.length == 0) return;
        CalendarData data = new CalendarData(context);
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (int id : ids) manager.updateAppWidget(id, calendar(context, id, data));
        if (Build.VERSION.SDK_INT < 31) manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_courses);
    }

    private static RemoteViews calendar(Context context, int id, CalendarData data) {
        String pkg = context.getPackageName();
        RemoteViews views = new RemoteViews(pkg, R.layout.schedule_widget);
        views.setTextViewText(R.id.widget_range, shortDate(data.today) + " – " + shortDate(data.today.plusDays(DAYS - 1)));
        views.removeAllViews(R.id.widget_days);
        for (int i = 0; i < DAYS; i++) {
            LocalDate date = data.today.plusDays(i);
            RemoteViews header = new RemoteViews(pkg, R.layout.widget_day_header);
            header.setTextViewText(R.id.widget_day, (i == 0 ? "今天" : Timetable.weekday(date)) + "\n" + shortDate(date));
            header.setContentDescription(R.id.widget_day, Timetable.dateLabel(date) + " " + Timetable.weekday(date)
                + "，" + data.days.get(i).size() + " 堂课");
            if (i == 0) {
                header.setInt(R.id.widget_day, "setBackgroundResource", R.drawable.widget_today);
                header.setTextColor(R.id.widget_day, Color.WHITE);
            }
            PendingIntent open = PendingIntent.getActivity(context, (int) date.toEpochDay(),
                activity(context).putExtra(DATE, date.toEpochDay()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            header.setOnClickPendingIntent(R.id.widget_day, open);
            views.addView(R.id.widget_days, header);
        }
        views.setOnClickPendingIntent(R.id.widget_banner, PendingIntent.getActivity(context, 20,
            activity(context), PendingIntent.FLAG_IMMUTABLE));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        views.setPendingIntentTemplate(R.id.widget_courses, PendingIntent.getActivity(context, 21, activity(context), flags));
        if (Build.VERSION.SDK_INT >= 31) {
            RemoteViews.RemoteCollectionItems.Builder rows = new RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true);
            for (int i = 0; i < data.rows; i++) rows.addItem(i, data.row(i));
            views.setRemoteAdapter(R.id.widget_courses, rows.build());
        } else {
            Intent adapter = new Intent(context, ScheduleWidgetService.class).setData(Uri.parse("haiclass://widget/" + id));
            views.setRemoteAdapter(R.id.widget_courses, adapter);
        }
        return views;
    }

    private static Intent activity(Context context) {
        return new Intent(context, MainActivity.class).setAction(OPEN_DAY).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    private static String shortDate(LocalDate date) {
        return date.getMonthValue() + "/" + date.getDayOfMonth();
    }

    private static String compactTitle(String title) {
        return title.replaceFirst("^[（(]网络课[）)]\\s*", "")
            .replaceFirst("^大学英语", "英语")
            .replaceFirst("^大学生", "")
            .replaceFirst("职业发展与就业指导", "就业指导")
            .replaceFirst("程序设计基础[（(]C[）)]", "C程序设计");
    }

    private static String compactRoom(String room) {
        String name = room.trim().replaceFirst("[（(]多[）)]$", "");
        if (name.isEmpty()) return "地点待定";
        return name.replaceFirst("^(.+?(?:楼|馆|场|中心))\\s*(\\S.*)$", "$1\n$2");
    }

    static final class CalendarData {
        final LocalDate today = Timetable.today();
        final List<List<Timetable.Meeting>> days = new ArrayList<>();
        final String pkg;
        final int rows;

        CalendarData(Context context) {
            pkg = context.getPackageName();
            Timetable timetable = ScheduleStore.load(context);
            int[][] periods = ScheduleStore.periods(context);
            int largestDay = 1;
            for (int i = 0; i < DAYS; i++) {
                List<Timetable.Meeting> meetings = timetable.on(today.plusDays(i), periods);
                days.add(meetings);
                largestDay = Math.max(largestDay, meetings.size());
            }
            rows = largestDay;
        }

        RemoteViews row(int index) {
            RemoteViews row = new RemoteViews(pkg, R.layout.widget_course_row);
            row.removeAllViews(R.id.widget_row);
            for (int col = 0; col < DAYS; col++) {
                List<Timetable.Meeting> meetings = days.get(col);
                RemoteViews cell = new RemoteViews(pkg, R.layout.widget_course_cell);
                if (index < meetings.size()) {
                    Timetable.Meeting meeting = meetings.get(index);
                    Timetable.Course course = meeting.course();
                    String period = course.first() == 0 ? (course.weeks() == 0 ? "补课" : "调课")
                        : (course.first() == course.last() ? String.valueOf(course.first())
                        : course.first() + "–" + course.last()) + "节";
                    cell.setTextViewText(R.id.widget_period, period);
                    cell.setTextViewText(R.id.widget_time, Timetable.clock(meeting.start()) + "\n" + Timetable.clock(meeting.end()));
                    cell.setTextViewText(R.id.widget_course, compactTitle(course.name()));
                    cell.setTextViewText(R.id.widget_room, compactRoom(course.room()));
                    cell.setViewVisibility(R.id.widget_room, View.VISIBLE);
                    cell.setContentDescription(R.id.widget_cell, course.name() + "，" + meeting.times()
                        + "，" + (course.first() == 0 ? period : "第" + period) + "，" + course.roomLabel());
                    cell.setOnClickFillInIntent(R.id.widget_cell, new Intent().putExtra(DATE, meeting.date().toEpochDay()));
                } else if (index == 0) {
                    cell.setTextViewText(R.id.widget_course, "无课");
                    cell.setOnClickFillInIntent(R.id.widget_cell, new Intent().putExtra(DATE, today.plusDays(col).toEpochDay()));
                } else {
                    cell.setViewVisibility(R.id.widget_cell, View.INVISIBLE);
                }
                row.addView(R.id.widget_row, cell);
            }
            return row;
        }
    }
}
