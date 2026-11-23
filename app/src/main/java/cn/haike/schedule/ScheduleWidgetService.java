package cn.haike.schedule;

import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

public final class ScheduleWidgetService extends RemoteViewsService {
    @Override public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new RemoteViewsFactory() {
            private ScheduleWidget.CalendarData data;

            @Override public void onCreate() { onDataSetChanged(); }
            @Override public void onDataSetChanged() { data = new ScheduleWidget.CalendarData(ScheduleWidgetService.this); }
            @Override public void onDestroy() { data = null; }
            @Override public int getCount() { return data == null ? 0 : data.rows; }
            @Override public RemoteViews getViewAt(int position) { return data == null ? null : data.row(position); }
            @Override public RemoteViews getLoadingView() { return null; }
            @Override public int getViewTypeCount() { return 1; }
            @Override public long getItemId(int position) { return position; }
            @Override public boolean hasStableIds() { return true; }
        };
    }
}
