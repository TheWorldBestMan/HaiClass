package cn.haike.schedule;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class ScheduleStore {
    private static final String TABLE = "timetable";
    private static final String PREVIOUS = "previous_timetable";
    private ScheduleStore() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("schedule", Context.MODE_PRIVATE);
    }

    static Timetable load(Context context) {
        try {
            String saved = prefs(context).getString(TABLE, null);
            return saved == null ? demo(context) : Timetable.from(new JSONObject(saved));
        } catch (JSONException | RuntimeException ex) {
            return demo(context);
        }
    }

    static Timetable demo(Context context) {
        try (InputStream in = context.getAssets().open("demo-schedule.json")) {
            return Timetable.from(new JSONObject(new String(read(in, 65_536), StandardCharsets.UTF_8)));
        } catch (IOException | JSONException ex) {
            throw new IllegalStateException("无法读取模拟课表", ex);
        }
    }

    static byte[] read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (count > limit - bytes.size()) throw new IOException("文件过大");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }

    static boolean save(Context context, Timetable table) {
        try {
            return prefs(context).edit().putString(TABLE, table.json().toString()).commit();
        } catch (JSONException ex) {
            return false;
        }
    }

    static Timetable previous(Context context) {
        try {
            String saved = prefs(context).getString(PREVIOUS, null);
            return saved == null ? null : Timetable.from(new JSONObject(saved));
        } catch (JSONException | RuntimeException invalid) {
            return null;
        }
    }

    static boolean replace(Context context, Timetable updated, Timetable current) {
        try {
            return prefs(context).edit().putString(TABLE, updated.json().toString())
                .putString(PREVIOUS, current.json().toString()).commit();
        } catch (JSONException invalid) {
            return false;
        }
    }

    static int[][] periods(Context context) {
        int[][] times = new int[Timetable.STANDARD.length][2];
        try {
            JSONArray data = new JSONArray(prefs(context).getString("periods", "[]"));
            if (data.length() == times.length) {
                for (int i = 0; i < times.length; i++) {
                    JSONArray row = data.getJSONArray(i);
                    times[i] = new int[]{row.getInt(0), row.getInt(1)};
                }
                return times;
            }
        } catch (JSONException ignored) {
            // Use school defaults if the saved values are unreadable.
        }
        for (int i = 0; i < times.length; i++) times[i] = Timetable.STANDARD[i].clone();
        return times;
    }

    static boolean savePeriods(Context context, int[][] times) {
        JSONArray data = new JSONArray();
        for (int[] time : times) data.put(new JSONArray().put(time[0]).put(time[1]));
        return prefs(context).edit().putString("periods", data.toString()).commit();
    }
}
