package cn.haike.schedule;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.RemoteViews;

import java.time.LocalDate;

public final class CampusRunWidget extends AppWidgetProvider {
    private static final String CHECK = "cn.haike.schedule.CHECK_CAMPUS_RUN";
    private static final String CHECKED = "run_checked";

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        refresh(context);
        ScheduleAlarm.scheduleWidget(context);
    }

    @Override public void onDisabled(Context context) {
        ScheduleAlarm.scheduleWidget(context);
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (!CHECK.equals(intent.getAction())) {
            super.onReceive(context, intent);
            return;
        }
        LocalDate today = Timetable.today();
        String extra = Build.VERSION.SDK_INT >= 31 ? RemoteViews.EXTRA_CHECKED : CHECKED;
        for (CampusRun.Window window : CampusRun.Window.values()) {
            if (widgetDay(today, window).equals(intent.getData()) && intent.hasExtra(extra)) {
                CampusRun.setCompleted(context, today, window, intent.getBooleanExtra(extra, false));
                return;
            }
        }
        refresh(context);
    }

    static int[] ids(Context context) {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context, CampusRunWidget.class));
    }

    static void refresh(Context context) {
        int[] ids = ids(context);
        if (ids.length == 0) return;
        LocalDate today = Timetable.today();
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.campus_run_widget);
        bindCheck(context, views, today, CampusRun.Window.MORNING, R.id.run_widget_morning, R.string.run_morning_completed);
        bindCheck(context, views, today, CampusRun.Window.AFTERNOON, R.id.run_widget_afternoon, R.string.run_afternoon_completed);
        int count = CampusRun.count(context);
        int remaining = Math.max(0, CampusRun.GOAL - count);
        String status = remaining == 0 ? context.getString(R.string.run_goal_status)
            : context.getString(R.string.run_remaining, remaining);
        views.setTextViewText(R.id.run_widget_progress, context.getString(R.string.run_progress_short, count, CampusRun.GOAL));
        views.setProgressBar(R.id.run_widget_track, CampusRun.GOAL, Math.min(count, CampusRun.GOAL), false);
        views.setTextViewText(R.id.run_widget_status, context.getString(R.string.run_widget_status, status));
        views.setContentDescription(R.id.run_widget_summary, context.getString(R.string.run_widget_summary,
            context.getString(R.string.run_progress_detail, count, CampusRun.GOAL), status));
        Intent edit = new Intent(context, MainActivity.class).setAction(CampusRun.EDIT_COUNT)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        views.setOnClickPendingIntent(R.id.run_widget_summary,
            PendingIntent.getActivity(context, 0, edit, PendingIntent.FLAG_IMMUTABLE));
        AppWidgetManager.getInstance(context).updateAppWidget(ids, views);
    }

    private static void bindCheck(Context context, RemoteViews views, LocalDate day, CampusRun.Window window,
                                  int viewId, int label) {
        boolean completed = CampusRun.completed(context, day, window);
        Intent check = new Intent(context, CampusRunWidget.class).setAction(CHECK).setData(widgetDay(day, window));
        if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent update = PendingIntent.getBroadcast(context, 0, check, PendingIntent.FLAG_MUTABLE);
            views.setCompoundButtonChecked(viewId, completed);
            views.setContentDescription(viewId, context.getString(label));
            views.setOnCheckedChangeResponse(viewId, RemoteViews.RemoteResponse.fromPendingIntent(update));
        } else {
            check.putExtra(CHECKED, !completed);
            PendingIntent update = PendingIntent.getBroadcast(context, 0, check,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            int icon = completed ? R.drawable.ic_run_checked : R.drawable.ic_run_unchecked;
            views.setTextViewCompoundDrawablesRelative(viewId, icon, 0, 0, 0);
            views.setContentDescription(viewId,
                context.getString(completed ? R.string.run_checked : R.string.run_unchecked, context.getString(label)));
            views.setOnClickPendingIntent(viewId, update);
        }
    }

    private static Uri widgetDay(LocalDate day, CampusRun.Window window) {
        return Uri.parse("haiclass://widget/campus-run/" + day + "/" + window.name());
    }
}
