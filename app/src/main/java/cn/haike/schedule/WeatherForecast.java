package cn.haike.schedule;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class WeatherForecast {
    static final long HOUR = 60 * 60_000L;
    static final long LIVE_REFRESH = HOUR / 2;
    private static final long FRESH_FOR = 25 * 60_000L;
    static final String SOURCE = "https://open-meteo.com/";
    private static final Map<Campus, CachedForecast> DECODED = new ConcurrentHashMap<>();

    enum Campus {
        HUGUANG("湖光校区", 21.15456, 110.29720),
        HAIBIN("海滨校区", 21.22481, 110.41318),
        XIASHAN("霞山校区", 21.20103, 110.40609),
        YANGJIANG("阳江校区", 21.88118, 111.82206);

        final String title;
        final double latitude;
        final double longitude;

        Campus(String title, double latitude, double longitude) {
            this.title = title;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    record Day(LocalDate date, double temperature, double rainChance, double precipitation, double uv, int condition) {
        boolean storm() { return condition >= 95 && condition <= 99; }

        boolean rain() {
            return rainChance >= 50 || precipitation >= 0.5
                || (condition >= 51 && condition <= 67) || (condition >= 80 && condition <= 82) || storm();
        }

        List<String> tips() {
            List<String> messages = new ArrayList<>();
            if (rain()) messages.add(storm() ? "预计有雷阵雨，出门带伞" : "预计有雨，出门带伞");
            if (uv >= 6) messages.add("紫外线强，记得防晒");
            if (temperature >= 35) messages.add("天气炎热，出门带水");
            return messages;
        }

        String quickTips() {
            List<String> messages = new ArrayList<>();
            if (rain()) messages.add("带伞");
            if (uv >= 6) messages.add("防晒");
            if (temperature >= 35) messages.add("带水");
            return String.join(" · ", messages);
        }

        String summary() {
            return "最高 " + (Double.isFinite(temperature) ? Math.round(temperature) + "°C" : "—")
                + "  ·  降雨 " + (Double.isFinite(rainChance) ? Math.round(rainChance) + "%" : "—")
                + "  ·  紫外线 " + (Double.isFinite(uv) ? String.format(Locale.US, "%.1f", uv) : "—");
        }

        boolean hasData() {
            return Double.isFinite(temperature) || Double.isFinite(rainChance) || Double.isFinite(precipitation)
                || Double.isFinite(uv) || condition >= 0;
        }
    }

    record Hour(long endsAt, double temperature, double rainChance, double precipitation, double uv, int condition) {}

    private record CachedForecast(String json, Snapshot forecast) {}

    record ClassWindow(Campus campus, long startsAt) {
        long from() { return startsAt - HOUR; }
        String label() { return clock(from()) + "–" + clock(startsAt); }

        Outlook outlook(Snapshot snapshot) {
            return snapshot == null ? null : snapshot.outlook(from(), startsAt);
        }
    }

    record Outlook(Day risk, double low) {
        String conditionLabel() {
            if (risk.storm()) return "雷阵雨";
            if (risk.rain()) return "有雨";
            return switch (risk.condition()) {
                case 0, 1 -> "晴";
                case 2 -> "多云";
                case 3 -> "阴";
                case 45, 48 -> "有雾";
                case 71, 73, 75, 77, 85, 86 -> "有雪";
                default -> "天气预报";
            };
        }

        String chipCondition() {
            return switch (conditionLabel()) {
                case "雷阵雨", "有雨" -> "雨";
                case "多云" -> "云";
                case "有雾" -> "雾";
                case "有雪" -> "雪";
                case "晴", "阴" -> conditionLabel();
                default -> "";
            };
        }

        String rainMetric() {
            return Double.isFinite(risk.rainChance()) ? "降雨 " + Math.round(risk.rainChance()) + "%" : "";
        }

        String uvMetric() {
            return Double.isFinite(risk.uv()) ? "紫外线 " + String.format(Locale.US, "%.1f", risk.uv()) : "";
        }

        String summary() {
            String result = conditionLabel();
            if (Double.isFinite(risk.temperature())) {
                long high = Math.round(risk.temperature());
                long minimum = Math.round(low);
                result += " · " + (high == minimum ? String.valueOf(high) : minimum + "–" + high) + "°C";
            }
            if (Double.isFinite(risk.rainChance())) result += " · 降雨最高 " + Math.round(risk.rainChance()) + "%";
            if (Double.isFinite(risk.uv())) result += " · 紫外线最高 " + String.format(Locale.US, "%.1f", risk.uv());
            return result;
        }
    }

    record Current(long at, double temperature) {}

    record Snapshot(long fetched, List<Day> days, List<Hour> hours, Current current) {
        Day on(LocalDate date) {
            for (Day forecast : days) if (forecast.date.equals(date)) return forecast;
            return null;
        }

        boolean fresh(long now) {
            return now >= fetched && now - fetched < FRESH_FOR && current != null && on(Timetable.onDate(now)) != null
                && outlook(now, now + 3 * HOUR) != null;
        }

        double temperatureNow(long now) {
            if (current == null) return Double.NaN;
            long age = now - current.at();
            return age >= 0 && age < HOUR ? current.temperature() : Double.NaN;
        }

        Outlook outlook(long from, long until) {
            double high = Double.NaN, low = Double.NaN, rain = Double.NaN, precip = Double.NaN, uv = Double.NaN;
            int condition = -1;
            for (Hour hour : hours) {
                // Precipitation for an hourly timestamp describes the preceding hour.
                if (hour.endsAt <= from || hour.endsAt - HOUR >= until) continue;
                high = maximum(high, hour.temperature);
                if (Double.isFinite(hour.temperature)) low = Double.isNaN(low) ? hour.temperature : Math.min(low, hour.temperature);
                rain = maximum(rain, hour.rainChance);
                precip = maximum(precip, hour.precipitation);
                uv = maximum(uv, hour.uv);
                condition = Math.max(condition, hour.condition);
            }
            Day risk = new Day(Timetable.onDate(from), high, rain, precip, uv, condition);
            return risk.hasData() ? new Outlook(risk, low) : null;
        }
    }

    static Campus campus(Context context) {
        try {
            return Campus.valueOf(ScheduleStore.prefs(context).getString("weather_campus", Campus.HUGUANG.name()));
        } catch (IllegalArgumentException invalid) {
            return Campus.HUGUANG;
        }
    }

    static void selectCampus(Context context, Campus campus) {
        ScheduleStore.prefs(context).edit().putString("weather_campus", campus.name()).apply();
    }

    static Campus classCampus(Context context, Timetable.Meeting meeting) {
        for (Campus campus : Campus.values()) {
            if (meeting.course().campus().contains(campus.title)) return campus;
        }
        return campus(context);
    }

    static ClassWindow before(Context context, Timetable.Meeting meeting) {
        return new ClassWindow(classCampus(context, meeting), meeting.startsAt());
    }

    static Snapshot usable(Context context, Campus campus, long now) {
        Snapshot snapshot = cached(context, campus);
        if (snapshot == null) return null;
        long age = now - snapshot.fetched();
        return age >= 0 && age < 6 * HOUR ? snapshot : null;
    }

    static String clock(long time) {
        var local = Instant.ofEpochMilli(time).atZone(Timetable.CAMPUS);
        return Timetable.clock(local.getHour() * 60 + local.getMinute());
    }

    static double currentTemperature(Context context, Campus campus, long now) {
        Snapshot snapshot = cached(context, campus);
        return snapshot == null ? Double.NaN : snapshot.temperatureNow(now);
    }

    static String currentLabel(double temperature) {
        return Double.isFinite(temperature) ? "当前 " + Math.round(temperature) + "°C" : "";
    }

    static Snapshot cached(Context context, Campus campus) {
        String value = ScheduleStore.prefs(context).getString(cacheKey(campus), null);
        if (value == null) return null;
        CachedForecast decoded = DECODED.get(campus);
        if (decoded != null && value.equals(decoded.json)) return decoded.forecast;
        try {
            JSONObject cache = new JSONObject(value);
            Snapshot snapshot = parse(cache.getJSONObject("forecast"), cache.getLong("fetched"));
            DECODED.put(campus, new CachedForecast(value, snapshot));
            return snapshot;
        } catch (JSONException | DateTimeParseException invalid) {
            return null;
        }
    }

    static synchronized Snapshot refresh(Context context, Campus campus, boolean force) throws IOException {
        long now = System.currentTimeMillis();
        Snapshot existing = cached(context, campus);
        if (!force && existing != null && existing.fresh(now)) return existing;

        String address = String.format(Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f"
                + "&current=temperature_2m"
                + "&daily=temperature_2m_max,precipitation_probability_max,precipitation_sum,uv_index_max,weather_code"
                + "&hourly=temperature_2m,precipitation_probability,precipitation,uv_index,weather_code"
                + "&timezone=Asia%%2FShanghai&forecast_days=2", campus.latitude, campus.longitude);
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(7000);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("Weather service unavailable");
            try (InputStream response = connection.getInputStream()) {
                JSONObject value = new JSONObject(new String(ScheduleStore.read(response, 65_536), StandardCharsets.UTF_8));
                long fetched = System.currentTimeMillis();
                Snapshot snapshot = parse(value, fetched);
                if (snapshot.on(Timetable.onDate(fetched)) == null || snapshot.hours().isEmpty()) {
                    throw new IOException("No forecast for current date");
                }
                String cache = new JSONObject().put("fetched", fetched).put("forecast", value).toString();
                ScheduleStore.prefs(context).edit().putString(cacheKey(campus), cache).apply();
                DECODED.put(campus, new CachedForecast(cache, snapshot));
                return snapshot;
            }
        } catch (JSONException | DateTimeParseException invalid) {
            throw new IOException("Invalid forecast", invalid);
        } finally {
            connection.disconnect();
        }
    }

    private static String cacheKey(Campus campus) { return "weather_cache_" + campus.name(); }

    static Snapshot parse(JSONObject forecast, long fetched) throws JSONException {
        JSONObject daily = forecast.getJSONObject("daily");
        JSONArray dates = daily.getJSONArray("time");
        JSONArray high = daily.optJSONArray("temperature_2m_max");
        JSONArray rain = daily.optJSONArray("precipitation_probability_max");
        JSONArray amount = daily.optJSONArray("precipitation_sum");
        JSONArray uv = daily.optJSONArray("uv_index_max");
        JSONArray codes = daily.optJSONArray("weather_code");
        List<Day> forecasts = new ArrayList<>();
        for (int i = 0; i < dates.length(); i++) {
            Day day = new Day(LocalDate.parse(dates.getString(i)), value(high, i), value(rain, i), value(amount, i),
                value(uv, i), codes == null ? -1 : codes.optInt(i, -1));
            if (day.hasData()) forecasts.add(day);
        }
        List<Hour> hours = new ArrayList<>();
        JSONObject hourly = forecast.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray times = hourly.getJSONArray("time");
            JSONArray temperatures = hourly.optJSONArray("temperature_2m");
            JSONArray probabilities = hourly.optJSONArray("precipitation_probability");
            JSONArray precipitation = hourly.optJSONArray("precipitation");
            JSONArray ultraviolet = hourly.optJSONArray("uv_index");
            JSONArray conditions = hourly.optJSONArray("weather_code");
            for (int i = 0; i < times.length(); i++) {
                long time = LocalDateTime.parse(times.getString(i)).atZone(Timetable.CAMPUS).toInstant().toEpochMilli();
                hours.add(new Hour(time, value(temperatures, i), value(probabilities, i), value(precipitation, i),
                    value(ultraviolet, i), conditions == null ? -1 : conditions.optInt(i, -1)));
            }
        }
        JSONObject conditions = forecast.optJSONObject("current");
        Current current = null;
        if (conditions != null) {
            try {
                long time = LocalDateTime.parse(conditions.optString("time")).atZone(Timetable.CAMPUS).toInstant().toEpochMilli();
                current = new Current(time, conditions.optDouble("temperature_2m"));
            } catch (DateTimeParseException invalid) {
                current = new Current(fetched, Double.NaN);
            }
        }
        return new Snapshot(fetched, forecasts, hours, current);
    }

    private static double value(JSONArray array, int index) {
        return array == null || array.isNull(index) ? Double.NaN : array.optDouble(index);
    }

    private static double maximum(double a, double b) {
        if (Double.isNaN(a)) return b;
        return Double.isNaN(b) ? a : Math.max(a, b);
    }
}
