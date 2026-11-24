package cn.haike.schedule;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class StudyItems {
    static final String OPEN = "cn.haike.schedule.OPEN_STUDY_ITEM";
    static final String ITEM_ID = "study_item_id";
    static final String CHANNEL = "study_reminders";
    private static final String ITEMS = "study_items";
    private static final int NOTICE = 218;
    private static final long MINUTE = 60_000L;
    private static final long LATE_WINDOW = 30 * MINUTE;

    record Item(String id, boolean exam, String title, LocalDate date, int minute, String course,
                String place, String notes, int lead, boolean done, long alarm, long sent) {
        long due() { return Timetable.epoch(date, minute); }
        String type() { return exam ? "考试" : "作业"; }
        String timeLabel() { return date + " " + Timetable.clock(minute); }
        boolean awaiting(long now) { return !done && alarm > 0 && alarm != sent && now < due() + LATE_WINDOW; }
        Item withDone(boolean value) {
            return new Item(id, exam, title, date, minute, course, place, notes, lead, value, alarm, sent);
        }
        Item markedSent() {
            return withSent(alarm);
        }
        Item withSent(long when) {
            return new Item(id, exam, title, date, minute, course, place, notes, lead, done, alarm, when);
        }
    }

    private StudyItems() {}

    static Item edited(Item existing, boolean exam, String title, LocalDate date, int minute, String course,
                       String place, String notes, int lead, long now) {
        long due = Timetable.epoch(date, minute);
        boolean same = existing != null && existing.due() == due && existing.lead == lead;
        long alarm = same ? existing.alarm : reminderAt(due, lead, now);
        long sent = existing == null ? 0 : existing.sent;
        return new Item(existing == null ? UUID.randomUUID().toString() : existing.id, exam, title,
            date, minute, course, place, notes, lead, existing != null && existing.done, alarm, sent);
    }

    static long reminderAt(long due, int lead, long now) {
        if (lead < 0 || due <= now) return 0;
        long planned = due - lead * MINUTE;
        return planned > now ? planned : due;
    }

    static List<Item> load(Context context) {
        List<Item> result = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(ScheduleStore.prefs(context).getString(ITEMS, "[]"));
            for (int i = 0; i < values.length(); i++) {
                try {
                    JSONObject entry = values.getJSONObject(i);
                    int minute = entry.getInt("minute");
                    if (minute < 0 || minute >= 1440) continue;
                    result.add(new Item(entry.getString("id"), entry.getBoolean("exam"), entry.getString("title"),
                        LocalDate.parse(entry.getString("date")), minute, entry.optString("course"),
                        entry.optString("place"), entry.optString("notes"), entry.getInt("lead"),
                        entry.optBoolean("done"), entry.optLong("alarm"), entry.optLong("sent")));
                } catch (JSONException | RuntimeException invalid) {
                    // Preserve the other items if an older or damaged entry cannot be read.
                }
            }
        } catch (JSONException invalid) {
            return result;
        }
        result.sort(Comparator.comparingLong(Item::due).thenComparing(Item::id));
        return result;
    }

    static boolean save(Context context, String id, Item updated) {
        List<Item> items = load(context);
        if (updated != null) {
            for (Item current : items) {
                if (!current.id.equals(id)) continue;
                // A reminder can fire while the editor still holds an older copy of this item.
                updated = updated.withSent(current.sent);
                break;
            }
        }
        items.removeIf(item -> item.id.equals(id));
        if (updated != null) items.add(updated);
        if (!write(context, items)) return false;
        context.getSystemService(NotificationManager.class).cancel(id, NOTICE);
        ScheduleAlarm.refreshStudy(context);
        return true;
    }

    private static boolean write(Context context, List<Item> items) {
        try {
            JSONArray values = new JSONArray();
            for (Item item : items) {
                values.put(new JSONObject().put("id", item.id).put("exam", item.exam).put("title", item.title)
                    .put("date", item.date.toString()).put("minute", item.minute).put("course", item.course)
                    .put("place", item.place).put("notes", item.notes).put("lead", item.lead).put("done", item.done)
                    .put("alarm", item.alarm).put("sent", item.sent));
            }
            return ScheduleStore.prefs(context).edit().putString(ITEMS, values.toString()).commit();
        } catch (JSONException invalid) {
            return false;
        }
    }

    static long nextReminder(Context context, long now) {
        if (!ScheduleAlarm.notificationsAllowed(context, CHANNEL)) return 0;
        long earliest = Long.MAX_VALUE;
        for (Item item : load(context)) if (item.awaiting(now)) earliest = Math.min(earliest, item.alarm);
        return earliest == Long.MAX_VALUE ? 0 : Math.max(earliest, now + 200);
    }

    static void deliver(Context context, long now) {
        if (!ScheduleAlarm.notificationsAllowed(context, CHANNEL)) return;
        List<Item> items = load(context);
        List<Item> due = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.awaiting(now) && item.alarm <= now) {
                items.set(i, item.markedSent());
                due.add(item);
            }
        }
        if (due.isEmpty() || !write(context, items)) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "作业与考试", NotificationManager.IMPORTANCE_HIGH));
        for (Item item : due) {
            Intent open = new Intent(context, MainActivity.class).setAction(OPEN).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .setData(new Uri.Builder().scheme("haiclass").authority("study").appendPath(item.id).build())
                .putExtra(ITEM_ID, item.id);
            String detail = item.timeLabel() + (item.place.isEmpty() ? "" : " · " + item.place);
            manager.notify(item.id, NOTICE, new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(item.type() + " · " + item.title)
                .setContentText(detail).setContentIntent(PendingIntent.getActivity(context, NOTICE, open, PendingIntent.FLAG_IMMUTABLE))
                .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build());
        }
    }
}
