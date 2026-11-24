package cn.haike.schedule;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Chronometer;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class MainActivity extends Activity {
    private static final int STUDY_TAB = 3;
    private static final int DOCUMENT = 10, NOTIFY = 11;
    private static final int MATCH = -1, WRAP = -2;
    private static final int GROUND = 0xfff3f7ff, PAPER = Color.WHITE, INK = 0xff13284b;
    private static final int MUTED = 0xff4e6483, BLUE = 0xff205ccc, DEEP = 0xff1746a8;
    private static final int HERO_BADGE = 0xffe8f2ff, HERO_TEXT = 0xffedf5ff;
    private static final int PALE_BLUE = 0xffe5efff, LINE = 0xffdce7f7;
    private static final int[] COURSE_TONES = {0xffeaf3ff, 0xffe6efff, 0xffe8f5fc, 0xffedf0ff};
    private static final String CALENDAR = "https://jwc.gdou.edu.cn/info/1167/11863.htm";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService weatherWorker = Executors.newSingleThreadExecutor();
    private final EnumSet<WeatherForecast.Campus> weatherLoading = EnumSet.noneOf(WeatherForecast.Campus.class);
    private final EnumMap<WeatherForecast.Campus, Long> weatherAttempts = new EnumMap<>(WeatherForecast.Campus.class);
    private final EnumMap<WeatherForecast.Campus, List<Consumer<Boolean>>> weatherCallbacks =
        new EnumMap<>(WeatherForecast.Campus.class);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable clock = new Runnable() {
        @Override public void run() {
            if (!Timetable.today().equals(today)) {
                render(true);
            } else if (tab == STUDY_TAB) {
                render(true);
            } else if (tab == 0) {
                refreshHero();
                if (weatherSlot != null) loadWeather(false);
            }
            handler.postDelayed(this, 30_000);
        }
    };
    private LinearLayout root, body, nav, heroSlot, weatherSlot;
    private LinearLayout campusRunCard;
    private AlertDialog campusRunCountEditor;
    private ScrollView scroll;
    private TextView source;
    private Timetable table;
    private int[][] periods;
    private int tab;
    private LocalDate today = Timetable.today();
    private LocalDate selected = today;
    private LocalDate week = Timetable.monday(today);
    private boolean importing;
    private Uri importedUri;
    private WeatherCardState weatherCardState;

    private record WeatherCardState(LocalDate day, Timetable.Meeting meeting, WeatherForecast.Campus campus,
                                    long fetched, boolean loading, boolean stale) {}

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        table = ScheduleStore.load(this);
        periods = ScheduleStore.periods(this);
        if (saved != null) {
            tab = saved.getInt("tab");
            selected = LocalDate.parse(saved.getString("selected", today.toString()));
            week = LocalDate.parse(saved.getString("week", week.toString()));
            String pending = saved.getString("import");
            if (pending != null) importedUri = Uri.parse(pending);
        } else if (ScheduleWidget.OPEN_DAY.equals(getIntent().getAction())
            || WeatherAlerts.OPEN_WEATHER.equals(getIntent().getAction())
            || StudyItems.OPEN.equals(getIntent().getAction())
            || CampusRun.opensSettings(getIntent())) {
            selectLinkedDay(getIntent());
        }
        frame();
        render(false);
        if (importedUri != null) readPdf(importedUri);
        else {
            acceptPdf(getIntent());
            if (saved == null) showLinkedCourse(getIntent());
            if (saved == null && WeatherAlerts.OPEN_WEATHER.equals(getIntent().getAction())) showNotificationWeather(getIntent());
            if (saved == null) openLinkedCampusRun(getIntent());
            if (saved == null) showLinkedStudy(getIntent());
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("tab", tab);
        out.putString("selected", selected.toString());
        out.putString("week", week.toString());
        if (importedUri != null) out.putString("import", importedUri.toString());
        super.onSaveInstanceState(out);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (Intent.ACTION_VIEW.equals(intent.getAction())) acceptPdf(intent);
        else {
            selectLinkedDay(intent);
            render(false);
            showLinkedCourse(intent);
            if (WeatherAlerts.OPEN_WEATHER.equals(intent.getAction())) showNotificationWeather(intent);
            openLinkedCampusRun(intent);
            showLinkedStudy(intent);
        }
    }

    private void selectLinkedDay(Intent intent) {
        tab = StudyItems.OPEN.equals(intent.getAction()) ? STUDY_TAB : CampusRun.opensSettings(intent) ? 2 : 0;
        LocalDate now = Timetable.today();
        long requested = intent.getLongExtra(ScheduleWidget.DATE, now.toEpochDay());
        if (requested < LocalDate.of(1900, 1, 1).toEpochDay() || requested > LocalDate.of(2100, 12, 31).toEpochDay()) {
            requested = now.toEpochDay();
        }
        selected = LocalDate.ofEpochDay(requested);
        week = Timetable.monday(selected);
    }

    private void showLinkedCourse(Intent intent) {
        if (!ScheduleWidget.OPEN_DAY.equals(intent.getAction()) || !intent.hasExtra(CourseNotifications.COURSE_TIME)) return;
        Timetable.Meeting meeting = findMeeting(intent, CourseNotifications.COURSE_TIME, CourseNotifications.COURSE_NAME);
        if (meeting != null) showLiveCourseDetails(meeting);
    }

    private Timetable.Meeting findMeeting(Intent intent, String timeKey, String nameKey) {
        long start = intent.getLongExtra(timeKey, Long.MIN_VALUE);
        if (start == Long.MIN_VALUE) return null;
        String id = intent.getStringExtra(CourseNotifications.COURSE_ID);
        if (id != null) return table.find(start, id, periods);
        String name = intent.getStringExtra(nameKey);
        LocalDate date = Timetable.onDate(start);
        for (Timetable.Meeting meeting : table.on(date, periods)) {
            if (meeting.startsAt() == start && meeting.course().name().equals(name)) return meeting;
        }
        return null;
    }

    @Override protected void onResume() {
        super.onResume();
        ScheduleAlarm.refresh(this);
        if (tab == 2 || tab == STUDY_TAB || !Timetable.today().equals(today)) render(true);
        else if (tab == 0) refreshHero();
        if (weatherSlot != null) loadWeather(false);
        handler.postDelayed(clock, 30_000);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(clock);
        super.onPause();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        weatherWorker.shutdownNow();
        super.onDestroy();
    }

    private void frame() {
        root = column();
        root.setBackgroundColor(GROUND);
        LinearLayout top = row();
        pad(top, 16, 10, 16, 10);
        top.setBackgroundColor(PAPER);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_app);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams logoSize = size(38, 38);
        logoSize.rightMargin = dp(9);
        top.addView(logo, logoSize);
        LinearLayout brand = column();
        brand.addView(text("海课", 21, INK, true));
        source = text("", 12, MUTED, false);
        brand.addView(source);
        top.addView(brand, new LinearLayout.LayoutParams(0, WRAP, 1));
        top.addView(action("导入 PDF", DEEP, Color.WHITE, this::pickPdf), size(WRAP, 44));
        root.addView(top, size(MATCH, WRAP));
        addDivider(root);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        body = column();
        pad(body, 16, 19, 16, 20);
        scroll.addView(body, new ScrollView.LayoutParams(MATCH, WRAP));
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1));
        nav = row();
        nav.setBackgroundColor(PAPER);
        pad(nav, 8, 6, 8, 6);
        addDivider(root);
        root.addView(nav, size(MATCH, WRAP));
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarContrastEnforced(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(light, light);
            }
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                root.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        } else {
            getWindow().setStatusBarColor(GROUND);
            getWindow().setNavigationBarColor(GROUND);
        }
    }

    private void addDivider(LinearLayout container) {
        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        container.addView(divider, size(MATCH, 1));
    }

    private void render(boolean keepScroll) {
        int offset = keepScroll ? scroll.getScrollY() : 0;
        LocalDate day = Timetable.today();
        if (!day.equals(today)) {
            if (selected.equals(today)) selected = day;
            if (week.equals(Timetable.monday(today))) week = Timetable.monday(day);
            today = day;
        }
        source.setText(table.imported ? "广东海洋大学 · PDF 课表" : "广东海洋大学 · 模拟课表");
        body.removeAllViews();
        nav.removeAllViews();
        heroSlot = null;
        weatherSlot = null;
        weatherCardState = null;
        drawNavigation();
        switch (tab) {
            case 1 -> drawWeek();
            case 2 -> drawSettings();
            case STUDY_TAB -> drawStudy();
            default -> drawToday();
        }
        scroll.post(() -> scroll.scrollTo(0, offset));
    }

    private void drawNavigation() {
        String[] titles = {"日课表", "一周课表", "上课设置", "学业"};
        int[] icons = {R.drawable.ic_nav_day, R.drawable.ic_nav_week, R.drawable.ic_nav_settings, R.drawable.ic_nav_study};
        for (int index : new int[]{0, 1, STUDY_TAB, 2}) {
            LinearLayout item = column();
            item.setGravity(Gravity.CENTER);
            item.setContentDescription(titles[index]);
            item.setSelected(tab == index);
            int color = tab == index ? DEEP : MUTED;
            ImageView symbol = new ImageView(this);
            symbol.setImageResource(icons[index]);
            symbol.setImageTintList(ColorStateList.valueOf(color));
            symbol.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            if (tab == index) symbol.setBackground(shape(PALE_BLUE, 12));
            item.addView(symbol, size(44, 28));
            TextView caption = text(titles[index], 12, color, tab == index);
            caption.setGravity(Gravity.CENTER);
            item.addView(caption);
            item.setOnClickListener(v -> { tab = index; render(false); });
            nav.addView(item, new LinearLayout.LayoutParams(0, dp(52), 1));
        }
    }

    private void drawToday() {
        boolean onToday = selected.equals(today);
        heading(Timetable.dateLabel(selected) + " " + Timetable.weekday(selected) + "  ·  " + weekLabel(selected),
            onToday ? "今日课表" : Timetable.weekday(selected) + "课表");
        heroSlot = column();
        add(body, heroSlot, 0, 14);
        refreshHero();

        if (selected.equals(today) || selected.equals(today.plusDays(1))) {
            weatherSlot = column();
            add(body, weatherSlot, 0, 12);
            loadWeather(false);
        }

        LinearLayout agenda = card();
        LinearLayout titleRow = row();
        titleRow.addView(text("课程安排", 19, INK, true), new LinearLayout.LayoutParams(0, WRAP, 1));
        if (!onToday) {
            titleRow.addView(action("回今天", PALE_BLUE, BLUE, () -> { selected = today; render(true); }), size(WRAP, 36));
        }
        agenda.addView(titleRow, size(MATCH, WRAP));

        LocalDate monday = Timetable.monday(selected);
        LinearLayout dates = row();
        String range = Timetable.dateLabel(monday) + "–" + Timetable.dateLabel(monday.plusDays(6));
        dates.addView(text(weekLabel(selected) + "\n" + range, 12, MUTED, false),
            new LinearLayout.LayoutParams(0, WRAP, 1));
        dates.addView(action("‹", PALE_BLUE, BLUE, () -> shiftDay(-1), "上一周"), size(33, 36));
        long dayDifference = ChronoUnit.DAYS.between(today, selected);
        dates.addView(dateOffset(dayDifference, "今天", "天"), size(WRAP, 36));
        dates.addView(action("›", PALE_BLUE, BLUE, () -> shiftDay(1), "下一周"), size(33, 36));
        add(agenda, dates, 6, 10);
        LinearLayout days = row();
        for (int i = 0; i < 7; i++) {
            LocalDate date = monday.plusDays(i);
            boolean current = date.equals(selected);
            boolean hasClass = !table.on(date, periods).isEmpty();
            String label = Timetable.weekday(date) + "\n" + date.getDayOfMonth() + (hasClass ? " •" : "");
            TextView day = action(label, current ? DEEP : GROUND, current ? Color.WHITE : INK,
                () -> { selected = date; render(true); }, Timetable.dateLabel(date) + " " + Timetable.weekday(date));
            day.setTextSize(12);
            day.setSelected(current);
            LinearLayout.LayoutParams dayParams = new LinearLayout.LayoutParams(0, dp(50), 1);
            if (i > 0) dayParams.leftMargin = dp(3);
            days.addView(day, dayParams);
        }
        add(agenda, days, 0, 13);
        List<Timetable.Meeting> meetings = table.on(selected, periods);
        String shown = Timetable.dateLabel(selected) + " " + Timetable.weekday(selected);
        add(agenda, text(shown + "  ·  " + meetings.size() + " 堂", 14, MUTED, true), 0, 7);
        drawMeetings(agenda, meetings);
        add(agenda, action("添加一次补课", PALE_BLUE, BLUE, () -> editAdjustment(null)), 8, 0, 42);
        add(body, agenda, 0, 13);
        drawStudyAgenda();

        LinearLayout widget = card();
        widget.addView(text("桌面小组件", 18, INK, true));
        add(widget, text("从今天起显示七天课表。上下滑动查看其余课程，长按组件可拉高。", 14, MUTED, false), 4, 10);
        add(widget, action("添加到桌面", PALE_BLUE, BLUE, this::pinScheduleWidget), 0, 0, 42);
        body.addView(widget);
    }

    private Timetable.Meeting previewMeeting() {
        long now = System.currentTimeMillis();
        if (selected.equals(today)) {
            Timetable.Meeting next = table.upcoming(now, periods);
            return next != null && !next.date().isAfter(today.plusDays(1)) ? next : null;
        }
        if (selected.equals(today.plusDays(1))) {
            List<Timetable.Meeting> meetings = table.on(selected, periods);
            return meetings.isEmpty() ? null : meetings.get(0);
        }
        return null;
    }

    private void loadWeather(boolean force) {
        Timetable.Meeting meeting = previewMeeting();
        WeatherForecast.Campus campus = meeting == null ? WeatherForecast.campus(this) : WeatherForecast.classCampus(this, meeting);
        loadWeather(campus, force);
    }

    private void loadWeather(WeatherForecast.Campus campus, boolean force) {
        loadWeather(campus, force, null);
    }

    private void loadWeather(WeatherForecast.Campus campus, boolean force, Consumer<Boolean> completed) {
        if (completed != null) weatherCallbacks.computeIfAbsent(campus, ignored -> new ArrayList<>()).add(completed);
        if (weatherLoading.contains(campus)) {
            updateWeatherCard();
            return;
        }
        WeatherForecast.Snapshot cached = WeatherForecast.cached(this, campus);
        long elapsed = SystemClock.elapsedRealtime();
        Long lastAttempt = weatherAttempts.get(campus);
        boolean recentlyAttempted = lastAttempt != null && elapsed - lastAttempt < 5 * 60_000L;
        if (!force && ((cached != null && cached.fresh(System.currentTimeMillis())) || recentlyAttempted)) {
            updateWeatherCard();
            notifyWeatherCallbacks(campus, cached != null);
            return;
        }
        weatherAttempts.put(campus, elapsed);
        weatherLoading.add(campus);
        updateWeatherCard();
        weatherWorker.execute(() -> {
            boolean failed;
            try {
                WeatherForecast.refresh(getApplicationContext(), campus, force);
                failed = false;
            } catch (IOException unavailable) {
                failed = true;
            }
            boolean unavailable = failed;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                weatherLoading.remove(campus);
                updateWeatherCard();
                if (!unavailable) ScheduleAlarm.refreshWeather(this);
                notifyWeatherCallbacks(campus, !unavailable);
            });
        });
    }

    private void notifyWeatherCallbacks(WeatherForecast.Campus campus, boolean succeeded) {
        List<Consumer<Boolean>> callbacks = weatherCallbacks.remove(campus);
        if (callbacks != null) for (Consumer<Boolean> callback : callbacks) callback.accept(succeeded);
    }

    private void updateWeatherCard() {
        if (weatherSlot == null) return;
        Timetable.Meeting meeting = previewMeeting();
        WeatherForecast.ClassWindow window = meeting == null ? null : WeatherForecast.before(this, meeting);
        WeatherForecast.Campus campus = window == null ? WeatherForecast.campus(this) : window.campus();
        WeatherForecast.Snapshot cached = WeatherForecast.cached(this, campus);
        long now = System.currentTimeMillis();
        boolean stale = cached != null && !cached.fresh(now);
        boolean loading = weatherLoading.contains(campus);
        WeatherCardState state = new WeatherCardState(selected, meeting, campus, cached == null ? -1 : cached.fetched(),
            loading, stale);
        if (state.equals(weatherCardState)) return;
        weatherCardState = state;
        weatherSlot.removeAllViews();

        if (window != null) {
            weatherSlot.addView(preparationCard(meeting, window, cached, loading, stale), size(MATCH, WRAP));
            return;
        }
        WeatherForecast.Day forecast = cached == null ? null : cached.on(selected);
        LinearLayout card = card();
        String day = selected.equals(today) ? "今天" : "明天";
        card.addView(text(campus.title + " · " + day + "天气", 16, INK, true));
        if (forecast == null) {
            add(card, text(loading ? "正在获取预报…" : "天气暂不可用，点按重试", 13, MUTED, false), 4, 0);
            card.setOnClickListener(v -> loadWeather(campus, true));
        } else {
            add(card, text(forecast.summary(), 13, MUTED, false), 4, 0);
            List<String> tips = forecast.tips();
            if (!tips.isEmpty()) add(card, text(String.join("  ·  ", tips), 14, DEEP, true), 5, 0);
            if (stale) add(card, text("显示上次更新的预报，点按查看详情", 12, MUTED, false), 4, 0);
            card.setOnClickListener(v -> showWeather(campus));
        }
        weatherSlot.addView(card, size(MATCH, WRAP));
    }

    private LinearLayout preparationCard(Timetable.Meeting meeting, WeatherForecast.ClassWindow window,
                                         WeatherForecast.Snapshot snapshot, boolean loading, boolean stale) {
        LinearLayout card = card();
        LinearLayout header = row();
        header.addView(text("课前出门准备", 16, INK, true), new LinearLayout.LayoutParams(0, WRAP, 1));
        String when = Timetable.relative(meeting.date(), today) + " " + Timetable.clock(meeting.start());
        TextView pill = text(when, 12, DEEP, true);
        pill.setBackground(shape(PALE_BLUE, 10));
        pad(pill, 8, 5, 8, 5);
        header.addView(pill);
        card.addView(header);
        add(card, text(meeting.course().name() + " · " + meeting.course().roomLabel(), 14, INK, true), 5, 0);
        add(card, text(window.campus().title + " · " + window.label() + " 的天气", 12, MUTED, false), 4, 0);
        WeatherForecast.Outlook forecast = window.outlook(snapshot);
        if (forecast == null) {
            add(card, text(loading ? "正在获取课前预报…" : "暂无课前预报", 13, MUTED, false), 4, 0);
        } else {
            add(card, text(forecast.summary(), 13, MUTED, false), 4, 0);
            List<String> tips = forecast.risk().tips();
            if (!tips.isEmpty()) add(card, text(String.join("  ·  ", tips), 13, DEEP, true), 5, 0);
            if (stale) add(card, text("上次更新的预报", 12, MUTED, false), 4, 0);
        }
        card.setFocusable(true);
        card.setOnClickListener(view -> showClassWeather(meeting));
        return card;
    }

    private void showClassWeather(Timetable.Meeting meeting) {
        WeatherForecast.ClassWindow window = WeatherForecast.before(this, meeting);
        WeatherForecast.Snapshot snapshot = WeatherForecast.cached(this, window.campus());
        WeatherForecast.Outlook forecast = window.outlook(snapshot);
        AlertDialog.Builder panel = new AlertDialog.Builder(this).setTitle("课前天气 · " + meeting.course().name())
            .setMessage(classWeatherMessage(meeting, window))
            .setNeutralButton("课程详情", (dialog, button) -> showCourse(meeting))
            .setPositiveButton("完成", null);
        if (forecast == null) {
            AlertDialog detail = showWideDetails(panel.setNegativeButton("刷新", null));
            bindWeatherRefresh(detail, window.campus(), () -> classWeatherMessage(meeting, window));
        } else {
            showWideDetails(panel.setNegativeButton("两天预报", (dialog, button) -> showWeather(window.campus())));
        }
    }

    private String classWeatherMessage(Timetable.Meeting meeting, WeatherForecast.ClassWindow window) {
        WeatherForecast.Snapshot snapshot = WeatherForecast.cached(this, window.campus());
        WeatherForecast.Outlook forecast = window.outlook(snapshot);
        StringBuilder detail = new StringBuilder(Timetable.dateLabel(meeting.date()) + " " + Timetable.weekday(meeting.date()))
            .append(" · ").append(meeting.times()).append(" 上课\n")
            .append(window.campus().title).append(" · ").append(meeting.course().roomLabel())
            .append("\n\n").append(window.label()).append(" 课前预报\n");
        if (forecast == null) {
            detail.append("暂时没有这个时段的预报。");
        } else {
            detail.append(forecast.summary());
            for (String tip : forecast.risk().tips()) detail.append("\n").append(tip);
            detail.append("\n\n更新于 ").append(weatherUpdated(snapshot)).append(" · 北京时间");
        }
        return detail.toString();
    }

    private void showNotificationWeather(Intent intent) {
        Timetable.Meeting meeting = findMeeting(intent, WeatherAlerts.CLASS_START, WeatherAlerts.CLASS_NAME);
        if (meeting != null) {
            showClassWeather(meeting);
            return;
        }
        if (intent.hasExtra(WeatherAlerts.CLASS_START)) return;
        String name = intent.getStringExtra(WeatherAlerts.WEATHER_CAMPUS);
        for (WeatherForecast.Campus campus : WeatherForecast.Campus.values()) {
            if (campus.name().equals(name)) {
                showWeather(campus);
                return;
            }
        }
        showWeather(WeatherForecast.campus(this));
    }

    private void showWeather(WeatherForecast.Campus campus) {
        AlertDialog detail = showWideDetails(new AlertDialog.Builder(this).setTitle(campus.title + " · 天气预报")
            .setMessage(weatherMessage(campus))
            .setNeutralButton("数据来源", (dialog, action) -> openWeb(WeatherForecast.SOURCE))
            .setNegativeButton("刷新", null)
            .setPositiveButton("完成", null));
        bindWeatherRefresh(detail, campus, () -> weatherMessage(campus));
    }

    private void bindWeatherRefresh(AlertDialog dialog, WeatherForecast.Campus campus, Supplier<String> message) {
        var refresh = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        refresh.setOnClickListener(view -> {
            refresh.setEnabled(false);
            refresh.setText("刷新中…");
            loadWeather(campus, true, succeeded -> {
                if (!dialog.isShowing()) return;
                String content = message.get();
                dialog.setMessage(succeeded ? content : "暂时无法刷新。\n\n" + content);
                refresh.setText("刷新");
                refresh.setEnabled(true);
            });
        });
    }

    private String weatherMessage(WeatherForecast.Campus campus) {
        WeatherForecast.Snapshot cached = WeatherForecast.cached(this, campus);
        StringBuilder message = new StringBuilder();
        if (cached != null) {
            long now = System.currentTimeMillis();
            String current = temperatureWithTime(cached, now);
            if (!current.isEmpty()) message.append(current);
            WeatherForecast.Outlook upcoming = cached.outlook(now, now + 3 * WeatherForecast.HOUR);
            if (upcoming != null) {
                if (message.length() > 0) message.append("\n\n");
                message.append("未来 3 小时\n").append(upcoming.summary());
                for (String tip : upcoming.risk().tips()) message.append("\n").append(tip);
            }
            for (LocalDate date : new LocalDate[]{today, today.plusDays(1)}) {
                WeatherForecast.Day forecast = cached.on(date);
                if (forecast == null) continue;
                if (message.length() > 0) message.append("\n\n");
                message.append(Timetable.relative(date, today)).append(" · ").append(Timetable.dateLabel(date))
                    .append("\n").append(forecast.summary());
                for (String tip : forecast.tips()) message.append("\n").append(tip);
            }
            if (message.length() > 0) {
                message.append("\n\n更新于 ").append(weatherUpdated(cached)).append(" · 北京时间");
            }
        }
        if (message.length() == 0) message.append("暂时没有可用的天气预报。");
        return message.append("\n预报数据来自 Open-Meteo").toString();
    }

    private static String temperatureWithTime(WeatherForecast.Snapshot snapshot, long now) {
        String current = WeatherForecast.currentLabel(snapshot.temperatureNow(now));
        if (current.isEmpty()) return current;
        return current + " · " + WeatherForecast.clock(snapshot.current().at()) + " 数据";
    }

    private static String weatherUpdated(WeatherForecast.Snapshot snapshot) {
        return Instant.ofEpochMilli(snapshot.fetched()).atZone(Timetable.CAMPUS).format(DateTimeFormatter.ofPattern("M/d HH:mm"));
    }

    private void refreshHero() {
        if (heroSlot == null) return;
        heroSlot.removeAllViews();
        LinearLayout panel = column();
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xff173caa, 0xff236ad5});
        background.setCornerRadius(dp(21));
        panel.setBackground(background);
        pad(panel, 18, 16, 18, 18);
        long now = System.currentTimeMillis();
        boolean onToday = selected.equals(today);
        List<Timetable.Meeting> day = onToday ? null : table.on(selected, periods);
        Timetable.Meeting next = onToday ? table.next(now, periods) : (day.isEmpty() ? null : day.get(0));
        String phase = onToday ? (next != null && now >= next.startsAt() ? "正在上课" : "下一堂课")
            : (next == null ? "课程安排" : "当天第一堂课");
        LinearLayout label = row();
        TextView badge = text(phase, 13, DEEP, true);
        pad(badge, 10, 5, 10, 5);
        badge.setBackground(shape(HERO_BADGE, 16));
        label.addView(badge);
        if (next != null) {
            String dateLabel = onToday ? Timetable.relative(next.date(), today) : Timetable.dateLabel(selected);
            TextView date = text(dateLabel, 14, HERO_TEXT, false);
            date.setGravity(Gravity.END);
            label.addView(date, new LinearLayout.LayoutParams(0, WRAP, 1));
        }
        panel.addView(label);
        if (next == null) {
            add(panel, text(onToday ? "近期没有排课" : "当天没有排课", 23, Color.WHITE, true), 15, 5);
            if (onToday) panel.addView(text("可以导入课表，或检查学期起始日期。", 14, HERO_TEXT, false));
        } else {
            TextView time = text(next.times(), 37, Color.WHITE, true);
            time.setLetterSpacing(-0.025f);
            add(panel, time, 10, 3);
            TextView title = text(next.course().name(), 21, Color.WHITE, true);
            add(panel, title, 0, 5);
            panel.addView(text(next.course().roomLabel() + "  ·  " + next.course().periodLabel(), 14, HERO_TEXT, false));
            panel.setOnClickListener(v -> showCourse(next));
        }
        heroSlot.addView(panel);
    }

    private void shiftDay(int offset) {
        selected = selected.plusWeeks(offset);
        render(true);
    }

    private void drawWeek() {
        LocalDate currentWeek = Timetable.monday(today);
        long weekDifference = ChronoUnit.WEEKS.between(currentWeek, week);
        View reset = weekDifference == 0 ? null : action("回本周", PALE_BLUE, BLUE, () -> {
            week = Timetable.monday(today);
            render(false);
        });
        heading(weekLabel(week) + "  ·  " + Timetable.dateLabel(week) + "–" + Timetable.dateLabel(week.plusDays(6)),
            "一周课表", reset);
        LinearLayout controls = row();
        controls.addView(action("‹  上一周", PALE_BLUE, BLUE, () -> { week = week.minusWeeks(1); render(false); }),
            new LinearLayout.LayoutParams(0, dp(41), 1));
        TextView current = dateOffset(weekDifference, "本周", "周");
        LinearLayout.LayoutParams center = size(WRAP, 41);
        center.setMargins(dp(5), 0, dp(5), 0);
        controls.addView(current, center);
        controls.addView(action("下一周  ›", PALE_BLUE, BLUE, () -> { week = week.plusWeeks(1); render(false); }),
            new LinearLayout.LayoutParams(0, dp(41), 1));
        add(body, controls, 0, 13);
        for (int i = 0; i < 7; i++) {
            LocalDate date = week.plusDays(i);
            List<Timetable.Meeting> meetings = table.on(date, periods);
            LinearLayout card = card();
            String label = Timetable.weekday(date) + "  " + Timetable.dateLabel(date) + (date.equals(today) ? "  ·  今天" : "");
            add(card, text(label, 16, INK, true), 0, 7);
            drawMeetings(card, meetings);
            add(body, card, 0, 9);
        }
    }

    private void drawMeetings(LinearLayout parent, List<Timetable.Meeting> meetings) {
        if (meetings.isEmpty()) {
            add(parent, text("这一天没有安排课程", 14, MUTED, false), 1, 2);
            return;
        }
        for (Timetable.Meeting meeting : meetings) {
            Timetable.Course course = meeting.course();
            LinearLayout line = row();
            LinearLayout time = column();
            time.addView(text(Timetable.clock(meeting.start()), 18, DEEP, true));
            time.addView(text(Timetable.clock(meeting.end()), 13, MUTED, false));
            line.addView(time, size(59, WRAP));
            LinearLayout detail = column();
            pad(detail, 10, 9, 10, 9);
            int tone = COURSE_TONES[Math.floorMod(course.name().hashCode(), COURSE_TONES.length)];
            detail.setBackground(shape(tone, 11));
            detail.addView(text(course.name(), 16, INK, true));
            add(detail, text(course.roomLabel() + "  ·  " + course.periodLabel(), 13, MUTED, false), 4, 0);
            line.addView(detail, new LinearLayout.LayoutParams(0, WRAP, 1));
            line.setOnClickListener(v -> showCourse(meeting));
            add(parent, line, 0, 6);
        }
    }

    private void showCourse(Timetable.Meeting meeting) {
        Timetable.Course course = meeting.course();
        String info = meeting.times() + "\n" + Timetable.dateLabel(meeting.date()) + " " + Timetable.weekday(meeting.date())
            + "  ·  " + course.periodLabel()
            + "\n\n" + course.campus() + "  " + course.roomLabel()
            + (course.teacher().isEmpty() ? "" : "\n教师  " + course.teacher())
            + (course.type().isEmpty() ? "" : "\n课型  " + course.type())
            + (course.weeks() == 0 ? "" : "\n上课周  " + Timetable.weekText(course.weeks()));
        new AlertDialog.Builder(this).setTitle(course.name()).setMessage(info.trim()).setPositiveButton("完成", null)
            .setNeutralButton("临时调整", (dialog, which) -> showAdjustmentActions(meeting)).show();
    }

    private void showLiveCourseDetails(Timetable.Meeting meeting) {
        Timetable.Course course = meeting.course();
        LinearLayout detail = column();
        pad(detail, 22, 20, 22, 16);
        long remaining = meeting.startsAt() - System.currentTimeMillis();
        add(detail, text(remaining > 0 ? "下一堂课" : "课程详情", 13, BLUE, true), 0, 5);
        add(detail, text(course.name(), 23, INK, true), 0, 8);
        add(detail, text(meeting.times(), 29, DEEP, true), 0, 4);
        add(detail, text(Timetable.dateLabel(meeting.date()) + " " + Timetable.weekday(meeting.date())
            + "  ·  " + course.periodLabel(), 14, MUTED, false), 0, 10);
        Chronometer countdown = remaining > 0 && remaining <= CourseNotifications.COUNTDOWN_WINDOW
            ? addCountdown(detail, meeting.startsAt(), remaining) : null;
        add(detail, text((course.campus().isEmpty() ? "" : course.campus() + "  ·  ") + course.roomLabel(),
            16, INK, true), 0, 4);
        if (!course.teacher().isEmpty()) add(detail, text("教师  " + course.teacher(), 14, MUTED, false), 0, 4);
        if (!course.type().isEmpty()) add(detail, text("课型  " + course.type(), 14, MUTED, false), 0, 4);
        if (course.weeks() != 0) add(detail, text("上课周  " + Timetable.weekText(course.weeks()), 14, MUTED, false), 0, 6);

        if (WeatherAlerts.enabled(this, WeatherAlerts.Feature.LIVE)) {
            long now = System.currentTimeMillis();
            WeatherForecast.ClassWindow window = WeatherForecast.before(this, meeting);
            WeatherForecast.Campus campus = WeatherForecast.campus(this);
            WeatherForecast.Snapshot local = WeatherForecast.usable(this, campus, now);
            WeatherForecast.Snapshot atClass = WeatherForecast.usable(this, window.campus(), now);
            WeatherForecast.Outlook before = window.outlook(atClass);
            WeatherForecast.Outlook later = local == null ? null : local.outlook(now, now + 3 * WeatherForecast.HOUR);
            WeatherForecast.Snapshot currentSource = before == null ? local : atClass;
            WeatherForecast.Campus currentCampus = before == null ? campus : window.campus();
            String current = currentSource == null ? "" : temperatureWithTime(currentSource, now);
            if (!current.isEmpty()) add(detail, text(currentCampus.title + "\n" + current, 16, DEEP, true), 10, 0);
            if (before != null) addOutlook(detail, window.label() + "  课前预报 · " + window.campus().title, before);
            if (later != null) addOutlook(detail, "未来 3 小时 · " + campus.title, later);
        }
        ScrollView content = new ScrollView(this);
        content.addView(detail, new ScrollView.LayoutParams(MATCH, WRAP));
        AlertDialog dialog = showWideDetails(new AlertDialog.Builder(this).setView(content).setPositiveButton("完成", null)
            .setNeutralButton("临时调整", (dismissed, which) -> showAdjustmentActions(meeting)));
        if (countdown != null) {
            dialog.setOnDismissListener(dismissed -> countdown.stop());
            countdown.start();
        }
    }

    private Chronometer addCountdown(LinearLayout detail, long startsAt, long remaining) {
        Chronometer countdown = new Chronometer(this);
        countdown.setCountDown(true);
        countdown.setFormat("距开课 %s");
        countdown.setBase(SystemClock.elapsedRealtime() + remaining);
        countdown.setTextColor(DEEP);
        countdown.setTextSize(16);
        countdown.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        countdown.setOnChronometerTickListener(clock -> {
            if (System.currentTimeMillis() >= startsAt) {
                clock.stop();
                clock.setText("已到开课时间");
            }
        });
        add(detail, countdown, 0, 10);
        return countdown;
    }

    private void showAdjustmentActions(Timetable.Meeting meeting) {
        Timetable.Adjustment current = table.adjustment(meeting.id());
        boolean supplemental = current != null && current.source() == null;
        String[] choices = supplemental ? new String[]{"编辑补课", "删除这次补课"} : current == null
            ? new String[]{"调课", "停课"} : new String[]{"调课", "停课", "撤销临时调整"};
        new AlertDialog.Builder(this).setTitle("这一次课程").setItems(choices, (dialog, selected) -> {
            if (selected == 0) editAdjustment(meeting);
            else if (selected == 2 || supplemental) confirmUndoAdjustment(current);
            else {
                LocalDate source = current == null ? meeting.date() : current.source();
                new AlertDialog.Builder(this).setTitle("停掉这一次课程？")
                    .setMessage(meeting.course().name() + " · " + meeting.date() + " " + meeting.times())
                    .setNegativeButton("取消", null).setPositiveButton("确认停课", (confirm, which) ->
                        saveAdjustment(meeting.id(), new Timetable.Adjustment(source, meeting, true), meeting.date()))
                    .show();
            }
        }).setNegativeButton("关闭", null).show();
    }

    private void editAdjustment(Timetable.Meeting original) {
        Timetable.Adjustment current = original == null ? null : table.adjustment(original.id());
        boolean supplemental = original == null || (current != null && current.source() == null);
        Timetable.Course course = original == null ? null : original.course();
        LocalDate[] day = {original == null ? selected : original.date()};
        int[] times = {original == null ? 8 * 60 + 10 : original.start(), original == null ? 8 * 60 + 55 : original.end()};
        LinearLayout fields = dialogFields();
        EditText name = formText(fields, "课程名称", course == null ? "" : course.name(), false);
        name.setEnabled(supplemental);
        addDateField(fields, "上课日期", day);
        addTimeField(fields, "开始时间", times, 0);
        addTimeField(fields, "结束时间", times, 1);
        EditText campus = formText(fields, "校区", course == null ? "" : course.campus(), false);
        EditText room = formText(fields, "地点", course == null ? "" : course.room(), false);
        ScrollView content = new ScrollView(this);
        content.addView(fields);
        AlertDialog editor = new AlertDialog.Builder(this).setTitle(supplemental ? "补课安排" : "调课")
            .setView(content).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        editor.show();
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            if (value(name).isEmpty()) { name.setError("请填写课程名称"); return; }
            if (times[0] >= times[1]) { toast("结束时间须晚于开始时间"); return; }
            String id = original == null ? UUID.randomUUID().toString() : original.id();
            Timetable.Course updated = new Timetable.Course(value(name), day[0].getDayOfWeek().getValue(),
                0, 0, course == null ? 0 : course.weeks(),
                value(campus), value(room), course == null ? "" : course.teacher(), course == null ? "" : course.type());
            Timetable.Meeting changed = new Timetable.Meeting(updated, day[0], times[0], times[1], id);
            LocalDate source = supplemental ? null : current == null ? original.date() : current.source();
            Runnable save = () -> {
                if (saveAdjustment(id, new Timetable.Adjustment(source, changed, false), changed.date())) editor.dismiss();
            };
            boolean overlaps = table.on(day[0], periods).stream().anyMatch(meeting ->
                !id.equals(meeting.id()) && meeting.start() < times[1] && times[0] < meeting.end());
            if (overlaps) {
                new AlertDialog.Builder(this).setTitle("上课时间有重叠")
                    .setMessage("这段时间已有课程安排。").setNegativeButton("继续编辑", null)
                    .setPositiveButton("仍然保存", (dialog, which) -> save.run()).show();
            } else save.run();
        });
    }

    private boolean saveAdjustment(String id, Timetable.Adjustment adjustment, LocalDate day) {
        Timetable updated = table.withAdjustment(id, adjustment);
        if (!ScheduleStore.save(this, updated)) { toast("保存失败，请重试"); return false; }
        table = updated;
        if (day != null) {
            selected = day;
            week = Timetable.monday(day);
            tab = 0;
        }
        ScheduleAlarm.refresh(this);
        render(day == null);
        toast(adjustment == null ? "已撤销临时调整" : "已保存" + adjustment.label());
        return true;
    }

    private void confirmUndoAdjustment(Timetable.Adjustment adjustment) {
        if (adjustment == null) return;
        new AlertDialog.Builder(this).setTitle(adjustment.source() == null ? "删除这次补课？" : "撤销这次调整？")
            .setMessage(adjustment.meeting().course().name()).setNegativeButton("返回", null)
            .setPositiveButton("确认", (dialog, which) -> saveAdjustment(adjustment.meeting().id(), null, null)).show();
    }

    private void showAdjustments() {
        List<Timetable.Adjustment> changes = new ArrayList<>(table.adjustments);
        changes.sort(java.util.Comparator.comparing(change -> change.meeting().date()));
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("临时调整").setNegativeButton("关闭", null);
        if (changes.isEmpty()) dialog.setMessage("当前课表还没有临时调整");
        else {
            String[] labels = changes.stream().map(change -> change.label() + " · " + change.meeting().course().name()
                + "\n" + change.meeting().date() + " " + change.meeting().times()).toArray(String[]::new);
            dialog.setItems(labels, (view, index) -> confirmUndoAdjustment(changes.get(index)));
        }
        dialog.show();
    }

    private void restoreTimetable() {
        Timetable previous = ScheduleStore.previous(this);
        if (previous == null) return;
        new AlertDialog.Builder(this).setTitle("恢复上一次课表")
            .setMessage(previous.term + "\n第 1 周从 " + previous.start + " 开始")
            .setNegativeButton("取消", null).setPositiveButton("恢复", (dialog, which) -> {
                if (!ScheduleStore.replace(this, previous, table)) { toast("恢复失败，请重试"); return; }
                table = previous;
                selected = today;
                week = Timetable.monday(today);
                tab = 0;
                ScheduleAlarm.refresh(this);
                render(false);
                toast("已恢复上一次课表");
            }).show();
    }

    private LinearLayout dialogFields() {
        LinearLayout fields = column();
        pad(fields, 22, 6, 22, 12);
        fields.setFocusableInTouchMode(true);
        return fields;
    }

    private EditText formText(LinearLayout fields, String label, String current, boolean multiline) {
        add(fields, text(label, 13, MUTED, true), 9, 0);
        EditText input = new EditText(this);
        input.setSingleLine(!multiline);
        input.setHint(label);
        input.setContentDescription(label);
        input.setText(current);
        add(fields, input, 0, 0);
        return input;
    }

    private static String value(EditText input) { return input.getText().toString().trim(); }

    private void addDateField(LinearLayout fields, String label, LocalDate[] day) {
        add(fields, text(label, 13, MUTED, true), 9, 3);
        TextView button = action(day[0].toString(), PALE_BLUE, BLUE, () -> {});
        button.setContentDescription(label + " " + day[0]);
        button.setOnClickListener(view -> new DatePickerDialog(this, (picker, year, month, date) -> {
            day[0] = LocalDate.of(year, month + 1, date);
            button.setText(day[0].toString());
            button.setContentDescription(label + " " + day[0]);
        }, day[0].getYear(), day[0].getMonthValue() - 1, day[0].getDayOfMonth()).show());
        add(fields, button, 0, 0, 42);
    }

    private void addTimeField(LinearLayout fields, String label, int[] times, int index) {
        add(fields, text(label, 13, MUTED, true), 9, 3);
        TextView button = action(Timetable.clock(times[index]), PALE_BLUE, BLUE, () -> {});
        button.setContentDescription(label + " " + Timetable.clock(times[index]));
        button.setOnClickListener(view -> new TimePickerDialog(this, (picker, hour, minute) -> {
            times[index] = hour * 60 + minute;
            button.setText(Timetable.clock(times[index]));
            button.setContentDescription(label + " " + Timetable.clock(times[index]));
        }, times[index] / 60, times[index] % 60, true).show());
        add(fields, button, 0, 0, 42);
    }

    private static final int[] STUDY_LEADS = {-1, 0, 30, 60, 1440};
    private static final String[] STUDY_LEAD_LABELS = {"关闭提醒", "到时提醒", "提前 30 分钟", "提前 1 小时", "提前 1 天"};

    private void drawStudy() {
        heading("作业与考试", "学业");
        LinearLayout actions = row();
        TextView homework = action("添加作业", DEEP, PAPER, () -> editStudy(null, false, today));
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, dp(45), 1);
        first.rightMargin = dp(8);
        actions.addView(homework, first);
        actions.addView(action("添加考试", PALE_BLUE, DEEP, () -> editStudy(null, true, today)),
            new LinearLayout.LayoutParams(0, dp(45), 1));
        add(body, actions, 0, 10);
        if (!ScheduleAlarm.notificationsAllowed(this, StudyItems.CHANNEL)) {
            add(body, action("允许学业通知", PALE_BLUE, BLUE, () -> requestNotifications(StudyItems.CHANNEL)), 0, 8, 41);
        } else if (!ScheduleAlarm.exact(this)) {
            add(body, text("开启准时提醒后可减少通知延迟", 13, MUTED, false), 0, 3);
            add(body, action("开启准时提醒", PALE_BLUE, BLUE, this::exactSettings), 0, 8, 41);
        }
        List<StudyItems.Item> items = StudyItems.load(this);
        drawStudyGroup("待办", items, false);
        drawStudyGroup("已完成", items, true);
    }

    private void drawStudyGroup(String title, List<StudyItems.Item> items, boolean done) {
        LinearLayout group = card();
        add(group, text(title, 18, INK, true), 0, 6);
        boolean empty = true;
        for (StudyItems.Item item : items) {
            if (item.done() != done) continue;
            drawStudyItem(group, item);
            empty = false;
        }
        if (empty) add(group, text(done ? "还没有已完成事项" : "当前没有待办", 14, MUTED, false), 1, 2);
        add(body, group, 0, 10);
    }

    private void drawStudyAgenda() {
        LinearLayout group = card();
        List<StudyItems.Item> items = StudyItems.load(this);
        add(group, text("学业事项", 18, INK, true), 0, 6);
        boolean empty = true;
        for (StudyItems.Item item : items) {
            if (!item.date().equals(selected)) continue;
            drawStudyItem(group, item);
            empty = false;
        }
        if (empty) add(group, text("这一天没有作业或考试安排", 14, MUTED, false), 1, 5);
        add(group, action("管理作业与考试", PALE_BLUE, BLUE, () -> { tab = STUDY_TAB; render(false); }), 6, 0, 42);
        add(body, group, 0, 13);
    }

    private void drawStudyItem(LinearLayout group, StudyItems.Item item) {
        LinearLayout itemView = column();
        itemView.setBackground(shape(GROUND, 12));
        pad(itemView, 10, 9, 10, 9);
        add(itemView, text(item.type() + " · " + item.title(), 16, INK, true), 0, 4);
        add(itemView, text(item.timeLabel() + " · " + studyStatus(item)
            + (item.course().isEmpty() ? "" : " · " + item.course()), 13, MUTED, false), 0, 0);
        itemView.setFocusable(true);
        itemView.setOnClickListener(view -> showStudyItem(item));
        add(group, itemView, 2, 5);
    }

    private static String studyStatus(StudyItems.Item item) {
        if (item.done()) return "已完成";
        long left = item.due() - System.currentTimeMillis();
        if (left <= 0) return item.exam() ? "已到开考时间" : "已逾期";
        if (!item.exam()) return "待完成";
        long hours = left / 3_600_000;
        if (hours >= 24) return "距考试 " + hours / 24 + " 天" + (hours % 24 == 0 ? "" : " " + hours % 24 + " 小时");
        return left < 3_600_000 ? "距考试不足 1 小时" : "距考试 " + hours + " 小时";
    }

    private void showLinkedStudy(Intent intent) {
        if (!StudyItems.OPEN.equals(intent.getAction())) return;
        String id = intent.getStringExtra(StudyItems.ITEM_ID);
        for (StudyItems.Item item : StudyItems.load(this)) {
            if (item.id().equals(id)) { showStudyItem(item); return; }
        }
    }

    private void showStudyItem(StudyItems.Item item) {
        LinearLayout detail = dialogFields();
        add(detail, text(item.type() + " · " + studyStatus(item), 14, DEEP, true), 2, 7);
        add(detail, text(item.timeLabel() + " · 北京时间", 17, INK, true), 0, 6);
        if (!item.course().isEmpty()) add(detail, text("课程  " + item.course(), 14, MUTED, false), 0, 5);
        if (!item.place().isEmpty()) add(detail, text("地点  " + item.place(), 14, MUTED, false), 0, 5);
        if (!item.notes().isEmpty()) add(detail, text(item.notes(), 14, INK, false), 0, 5);
        ScrollView content = new ScrollView(this);
        content.addView(detail);
        showWideDetails(new AlertDialog.Builder(this).setTitle(item.title()).setView(content).setNegativeButton("关闭", null)
            .setNeutralButton("编辑", (dialog, which) -> editStudy(item, item.exam(), item.date()))
            .setPositiveButton(item.done() ? "恢复待办" : "标记完成",
                (dialog, which) -> saveStudy(item.id(), item.withDone(!item.done()))));
    }

    private void editStudy(StudyItems.Item old, boolean exam, LocalDate initialDate) {
        LocalDate[] date = {initialDate};
        int[] time = {old == null ? exam ? 9 * 60 : 1439 : old.minute()};
        int[] leadIndex = {exam ? 3 : 4};
        if (old != null) {
            for (int i = 0; i < STUDY_LEADS.length; i++) if (old.lead() == STUDY_LEADS[i]) leadIndex[0] = i;
        }
        LinearLayout form = dialogFields();
        EditText title = formText(form, "标题", old == null ? "" : old.title(), false);
        addDateField(form, exam ? "考试日期" : "截止日期", date);
        addTimeField(form, exam ? "开考时间" : "截止时间", time, 0);
        add(form, text("提醒", 13, MUTED, true), 9, 3);
        TextView lead = action(STUDY_LEAD_LABELS[leadIndex[0]], PALE_BLUE, BLUE, () -> {});
        lead.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle("提醒时间")
            .setSingleChoiceItems(STUDY_LEAD_LABELS, leadIndex[0], (dialog, index) -> {
                leadIndex[0] = index;
                lead.setText(STUDY_LEAD_LABELS[index]);
                lead.setContentDescription(STUDY_LEAD_LABELS[index]);
                dialog.dismiss();
            }).setNegativeButton("取消", null).show());
        add(form, lead, 0, 2, 42);
        EditText course = formText(form, "课程（选填）", old == null ? "" : old.course(), false);
        EditText place = formText(form, "地点（选填）", old == null ? "" : old.place(), false);
        EditText notes = formText(form, "备注（选填）", old == null ? "" : old.notes(), true);
        ScrollView content = new ScrollView(this);
        content.addView(form);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle((old == null ? "添加" : "编辑") + (exam ? "考试" : "作业"))
            .setView(content).setNegativeButton("取消", null).setPositiveButton("保存", null);
        if (old != null) builder.setNeutralButton("删除", (dialog, which) -> new AlertDialog.Builder(this)
            .setTitle("删除这项" + old.type() + "？").setMessage(old.title()).setNegativeButton("取消", null)
            .setPositiveButton("删除", (confirm, choice) -> saveStudy(old.id(), null)).show());
        AlertDialog editor = builder.create();
        editor.show();
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            if (value(title).isEmpty()) { title.setError("请填写标题"); return; }
            StudyItems.Item item = StudyItems.edited(old, exam, value(title), date[0], time[0], value(course),
                value(place), value(notes), STUDY_LEADS[leadIndex[0]], System.currentTimeMillis());
            if (saveStudy(item.id(), item)) {
                editor.dismiss();
                if (!item.done() && item.alarm() > System.currentTimeMillis()
                    && !ScheduleAlarm.notificationsAllowed(this, StudyItems.CHANNEL)) requestNotifications(StudyItems.CHANNEL);
            }
        });
    }

    private boolean saveStudy(String id, StudyItems.Item item) {
        if (!StudyItems.save(this, id, item)) { toast("保存失败，请重试"); return false; }
        render(true);
        return true;
    }

    private void addOutlook(LinearLayout parent, String heading, WeatherForecast.Outlook forecast) {
        LinearLayout section = card();
        section.addView(text(heading, 14, INK, true));
        add(section, text(forecast.summary(), 14, MUTED, false), 5, 0);
        for (String tip : forecast.risk().tips()) add(section, text(tip, 14, DEEP, false), 3, 0);
        add(parent, section, 10, 0);
    }

    private AlertDialog showWideDetails(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.show();
        if (dialog.getWindow() != null) {
            int width = getResources().getDisplayMetrics().widthPixels - dp(20);
            dialog.getWindow().setLayout(Math.min(dp(560), width), WRAP);
        }
        return dialog;
    }

    private void drawSettings() {
        heading("课表与上课通知", "上课设置");
        LinearLayout reminder = card();
        reminder.addView(text("上课提醒", 19, INK, true));
        String status = ScheduleAlarm.enabled(this) ? "已开启 · 提前 " + ScheduleAlarm.lead(this) + " 分钟" : "已关闭";
        if (!ScheduleAlarm.notificationsAllowed(this)) status += "\n通知权限待开启";
        else if (!ScheduleAlarm.exact(this)) status += "\n启用准时提醒后可按设定时间通知";
        add(reminder, text(status, 14, MUTED, false), 5, 9);
        add(reminder, action(ScheduleAlarm.enabled(this) ? "关闭上课提醒" : "开启上课提醒",
            DEEP, Color.WHITE, this::toggleReminder), 0, 6, 43);
        add(reminder, action("设置提醒提前量", PALE_BLUE, BLUE, this::chooseLead), 0, 6, 41);
        if (!ScheduleAlarm.exact(this)) add(reminder, action("开启准时提醒", PALE_BLUE, BLUE, this::exactSettings), 0, 6, 41);
        add(reminder, action("测试通知", GROUND, BLUE, this::testNotification), 0, 0, 41);
        add(body, reminder, 0, 12);

        drawCampusRunSettings();
        drawCourseNotifications();
        drawWeatherSettings();

        LinearLayout date = card();
        date.addView(text("教学周与节次", 19, INK, true));
        add(date, text(table.term + "\n第 1 周从 " + table.start + " 开始 · 北京时间", 14, MUTED, false), 5, 9);
        add(date, action("修改第 1 周起始日", PALE_BLUE, BLUE,
            () -> chooseMonday(table.start, selected -> saveTable(table.withStart(selected), false))), 0, 6, 41);
        add(date, action("编辑常规节次时间", GROUND, BLUE, this::editPeriods), 0, 5, 41);
        add(date, action("查看临时调整", GROUND, BLUE, this::showAdjustments), 0, 5, 41);
        if (ScheduleStore.previous(this) != null) {
            add(date, action("恢复上一次课表", PALE_BLUE, BLUE, this::restoreTimetable), 0, 5, 41);
        }
        add(date, text("湖光校区广学楼、明德楼按错峰时间上课，第 3–4 节 10:05–11:35，第 7–8 节 16:25–17:55。", 13, MUTED, false), 3, 6);
        add(date, action("查看学校校历", GROUND, BLUE, () -> openWeb(CALENDAR)), 0, 0, 41);
        add(body, date, 0, 12);

        LinearLayout tools = card();
        tools.addView(text("课表与桌面", 19, INK, true));
        add(tools, action("添加到桌面", DEEP, Color.WHITE, this::pinScheduleWidget), 9, 6, 43);
        add(tools, action("打开教务系统", PALE_BLUE, BLUE, () -> openWeb("https://jw.gdou.edu.cn/")), 0, 0, 41);
        add(body, tools, 0, 0);
    }

    private void drawCampusRunSettings() {
        LinearLayout run = card();
        campusRunCard = run;
        run.addView(text(getString(R.string.run_channel), 19, INK, true));
        int count = CampusRun.count(this);
        boolean reached = count >= CampusRun.GOAL;
        add(run, text(getString(R.string.run_progress_detail, count, CampusRun.GOAL), 15, DEEP, true), 5, 4);
        String description = reached ? getString(R.string.run_goal_reached)
            : "开课前 30 分钟提醒，上午和下午各最多一次。勾选后跳过该时段的提醒。";
        add(run, text(description, 14, MUTED, false), 1, 5);
        LinearLayout checkboxes = row();
        addCampusRunCheck(checkboxes, CampusRun.Window.MORNING, R.string.run_morning_completed);
        addCampusRunCheck(checkboxes, CampusRun.Window.AFTERNOON, R.string.run_afternoon_completed);
        run.addView(checkboxes, size(MATCH, WRAP));
        add(run, action(getString(R.string.run_edit_count), PALE_BLUE, BLUE, this::editCampusRunCount), 2, 6, 41);
        if (!reached) {
            boolean enabled = CampusRun.enabled(this);
            add(run, action(enabled ? "关闭校园跑提醒" : "开启校园跑提醒", enabled ? PALE_BLUE : DEEP,
                enabled ? BLUE : Color.WHITE, () -> {
                    CampusRun.setEnabled(this, !enabled);
                    render(true);
                    if (!enabled && !ScheduleAlarm.notificationsAllowed(this, CampusRun.CHANNEL)) {
                        requestNotifications(CampusRun.CHANNEL);
                    }
                }), 2, 6, 43);
            if (enabled && !ScheduleAlarm.notificationsAllowed(this, CampusRun.CHANNEL)) {
                add(run, action("允许校园跑通知", GROUND, BLUE, () -> requestNotifications(CampusRun.CHANNEL)), 0, 6, 41);
            } else if (enabled && !ScheduleAlarm.exact(this)) {
                add(run, action("开启准时提醒", GROUND, BLUE, this::exactSettings), 0, 6, 41);
            }
        }
        add(run, action("添加校园跑小组件", GROUND, BLUE,
            () -> pinWidget(CampusRunWidget.class, R.string.run_widget_name)), 0, 0, 41);
        add(body, run, 0, 12);
    }

    private void addCampusRunCheck(LinearLayout row, CampusRun.Window window, int label) {
        LocalDate day = today;
        CheckBox complete = new CheckBox(this);
        complete.setText(label);
        complete.setTextColor(INK);
        complete.setButtonTintList(ColorStateList.valueOf(BLUE));
        complete.setMinHeight(dp(48));
        complete.setChecked(CampusRun.completed(this, day, window));
        complete.setOnCheckedChangeListener((view, checked) -> {
            CampusRun.setCompleted(this, day, window, checked);
            render(true);
        });
        row.addView(complete, new LinearLayout.LayoutParams(0, WRAP, 1));
    }

    private void openLinkedCampusRun(Intent intent) {
        if (!CampusRun.opensSettings(intent)) return;
        scroll.post(() -> {
            if (tab == 2 && campusRunCard != null) scroll.scrollTo(0, campusRunCard.getTop());
        });
        if (CampusRun.EDIT_COUNT.equals(intent.getAction())) editCampusRunCount();
    }

    private void editCampusRunCount() {
        if (campusRunCountEditor != null) return;
        LinearLayout content = column();
        pad(content, 24, 10, 24, 8);
        add(content, text(getString(R.string.run_edit_count_help, CampusRun.todayCount(this)), 14, MUTED, false), 0, 7);
        EditText value = new EditText(this);
        value.setSingleLine(true);
        value.setInputType(InputType.TYPE_CLASS_NUMBER);
        value.setHint(R.string.run_edit_count_hint);
        value.setText(Integer.toString(CampusRun.count(this)));
        value.setSelectAllOnFocus(true);
        add(content, value, 0, 0);
        AlertDialog editor = new AlertDialog.Builder(this).setTitle(R.string.run_edit_count).setView(content)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        campusRunCountEditor = editor;
        editor.setOnDismissListener(dialog -> campusRunCountEditor = null);
        value.requestFocus();
        if (editor.getWindow() != null) editor.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        editor.show();
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            int count;
            try {
                count = Integer.parseInt(value.getText().toString().trim());
            } catch (NumberFormatException invalid) {
                value.setError(getString(R.string.run_edit_count_invalid));
                return;
            }
            if (!CampusRun.setCount(this, count)) {
                value.setError(getString(R.string.run_edit_count_minimum, CampusRun.todayCount(this)));
                return;
            }
            editor.dismiss();
            render(true);
        });
    }

    private void drawWeatherSettings() {
        LinearLayout weather = card();
        weather.addView(text("校园天气提醒", 19, INK, true));
        add(weather, text(WeatherForecast.campus(this).title + " · 按北京时间更新预报", 14, MUTED, false), 5, 9);
        addWeatherToggle(weather, WeatherAlerts.Feature.LIVE, "流体云天气", "实时卡片显示未来 3 小时天气，与下一堂课卡片合并显示。");
        addWeatherToggle(weather, WeatherAlerts.Feature.BEFORE_CLASS, "课前 1 小时天气", "按课程地点预报出门时段，开课前 1 小时单独提醒。");
        addWeatherToggle(weather, WeatherAlerts.Feature.MORNING, "天气提醒", "每天早上 7:00 检查是否需要带伞、防晒或带水。");
        add(weather, action("选择预报校区", PALE_BLUE, BLUE, this::chooseWeatherCampus), 9, 0, 41);
        if (Build.VERSION.SDK_INT >= 36 && WeatherAlerts.enabled(this, WeatherAlerts.Feature.LIVE)
            && !CourseNotifications.promoted(this)) {
            add(weather, action("开启系统实时显示", PALE_BLUE, BLUE, this::promotionSettings), 6, 0, 41);
        }
        add(body, weather, 0, 12);
    }

    private void addWeatherToggle(LinearLayout card, WeatherAlerts.Feature feature, String label, String description) {
        boolean enabled = WeatherAlerts.enabled(this, feature);
        add(card, text(description, 13, MUTED, false), 7, 5);
        add(card, action((enabled ? "关闭" : "开启") + label, enabled ? PALE_BLUE : DEEP,
            enabled ? BLUE : Color.WHITE, () -> {
                WeatherAlerts.setEnabled(this, feature, !enabled);
                ScheduleAlarm.refreshWeather(this);
                render(true);
                if (!enabled && !ScheduleAlarm.notificationsAllowed(this, feature.channel)) requestNotifications(feature.channel);
            }), 0, 3, 42);
        if (enabled && !ScheduleAlarm.notificationsAllowed(this, feature.channel)) {
            add(card, action("允许" + label + "通知", PALE_BLUE, BLUE,
                () -> requestNotifications(feature.channel)), 3, 2, 41);
        }
    }

    private void chooseWeatherCampus() {
        WeatherForecast.Campus[] campuses = WeatherForecast.Campus.values();
        String[] names = new String[campuses.length];
        for (int i = 0; i < campuses.length; i++) names[i] = campuses[i].title;
        new AlertDialog.Builder(this).setTitle("选择预报校区")
            .setSingleChoiceItems(names, WeatherForecast.campus(this).ordinal(), (dialog, index) -> {
                if (campuses[index] != WeatherForecast.campus(this)) WeatherAlerts.cancelNotification(this);
                WeatherForecast.selectCampus(this, campuses[index]);
                ScheduleAlarm.refreshWeather(this);
                dialog.dismiss();
                render(true);
                loadWeather(campuses[index], false);
            }).setNegativeButton("取消", null).show();
    }

    private void drawCourseNotifications() {
        LinearLayout live = card();
        live.addView(text("下一堂课 · 实时通知", 19, INK, true));
        boolean nextEnabled = CourseNotifications.nextEnabled(this);
        String liveStatus = nextEnabled ? "已开启" : "已关闭";
        add(live, text(liveStatus + " · 开课时切换到下一堂，点通知查看详情。", 14, MUTED, false), 5, 9);
        add(live, action(nextEnabled ? "关闭下一堂实时通知" : "开启下一堂实时通知",
            DEEP, Color.WHITE, () -> toggleCourseNotification(true)), 0, 0, 43);
        if (nextEnabled && !ScheduleAlarm.notificationsAllowed(this, CourseNotifications.NEXT_CHANNEL)) {
            add(live, action("允许实时通知", PALE_BLUE, BLUE,
                () -> requestNotifications(CourseNotifications.NEXT_CHANNEL)), 6, 0, 41);
        } else if (Build.VERSION.SDK_INT >= 36 && nextEnabled && !CourseNotifications.promoted(this)) {
            add(live, action("开启系统实时显示", PALE_BLUE, BLUE, this::promotionSettings), 6, 0, 41);
        }
        add(body, live, 0, 12);

        LinearLayout tomorrow = card();
        tomorrow.addView(text("明天课表通知", 19, INK, true));
        boolean tomorrowEnabled = CourseNotifications.tomorrowEnabled(this);
        String tomorrowStatus = tomorrowEnabled ? "已开启" : "已关闭";
        add(tomorrow, text(tomorrowStatus + " · " + CourseNotifications.window(this).label() + "（北京时间）"
            + "\n明天有课时，在这个时段提醒一次。", 14, MUTED, false), 5, 9);
        add(tomorrow, action(tomorrowEnabled ? "关闭明天课表通知" : "开启明天课表通知",
            DEEP, Color.WHITE, () -> toggleCourseNotification(false)), 0, 6, 43);
        add(tomorrow, action("设置通知时段", PALE_BLUE, BLUE, this::editTomorrowWindow), 0, 0, 41);
        if (tomorrowEnabled && !ScheduleAlarm.notificationsAllowed(this, CourseNotifications.TOMORROW_CHANNEL)) {
            add(tomorrow, action("允许明天课表通知", PALE_BLUE, BLUE,
                () -> requestNotifications(CourseNotifications.TOMORROW_CHANNEL)), 6, 0, 41);
        }
        add(body, tomorrow, 0, 12);
    }

    private void pickPdf() {
        if (importing) return;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf");
        try { startActivityForResult(pick, DOCUMENT); }
        catch (ActivityNotFoundException ex) { toast("找不到可用的系统文件选择器"); }
    }

    private void acceptPdf(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            Uri uri = intent.getData();
            if ("content".equals(uri.getScheme())) readPdf(uri);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == DOCUMENT && result == RESULT_OK && data != null && data.getData() != null) readPdf(data.getData());
    }

    private void readPdf(Uri uri) {
        if (importing) return;
        importing = true;
        importedUri = uri;
        toast("正在读取 PDF 课表");
        worker.execute(() -> {
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                if (stream == null) throw new IOException("无法打开所选文件");
                PDFBoxResourceLoader.init(getApplicationContext());
                Timetable parsed = PdfImport.parse(ScheduleStore.read(stream, 4 * 1024 * 1024));
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    importing = false;
                    if (parsed.start == null) chooseMonday(Timetable.monday(today), start -> preview(parsed.withStart(start)));
                    else preview(parsed);
                });
            } catch (IOException | RuntimeException ex) {
                android.util.Log.w("HaiClass", "PDF import failed: " + ex.getClass().getSimpleName());
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    importing = false;
                    importedUri = null;
                    String reason = ex.getMessage();
                    if (reason == null || !reason.matches(".*[\\u4e00-\\u9fff].*")) {
                        reason = "请检查文件是否能打开，并确认它是教务系统生成的文字版课表。";
                    }
                    new AlertDialog.Builder(this).setTitle("无法导入课表").setMessage(reason).setPositiveButton("知道了", null).show();
                });
            }
        });
    }

    private void preview(Timetable parsed) {
        LinearLayout layout = column();
        pad(layout, 21, 4, 21, 15);
        add(layout, text(parsed.term + "\n识别到 " + parsed.courses.size() + " 条排课\n第 1 周从 " + parsed.start + " 开始", 15, INK, true), 0, 12);
        LocalDate day = today.isBefore(parsed.start) ? parsed.start : today;
        Timetable.Meeting next = parsed.next(day.atStartOfDay(Timetable.CAMPUS).toInstant().toEpochMilli(), periods);
        if (next != null) {
            for (Timetable.Meeting meeting : parsed.on(next.date(), periods)) {
                add(layout, text(Timetable.dateLabel(meeting.date()) + " · " + meeting.times() + "\n"
                    + meeting.course().name() + " · " + meeting.course().roomLabel(), 14, MUTED, false), 0, 8);
            }
        }
        add(layout, text("导入后可在上课设置恢复上一次课表。", 13, MUTED, false), 1, 0);
        ScrollView preview = new ScrollView(this);
        preview.addView(layout);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("确认导入 PDF").setView(preview)
            .setNeutralButton("修改起始日", (d, which) -> chooseMonday(parsed.start, start -> preview(parsed.withStart(start))))
            .setNegativeButton("取消", (d, which) -> importedUri = null)
            .setPositiveButton("确认导入", (d, which) -> saveTable(parsed, true)).create();
        dialog.setOnCancelListener(d -> importedUri = null);
        dialog.show();
    }

    private void saveTable(Timetable parsed, boolean fromPdf) {
        if (!ScheduleStore.replace(this, parsed, table)) { toast("保存失败，请重试"); return; }
        table = parsed;
        if (fromPdf) {
            importedUri = null;
            selected = today;
            week = Timetable.monday(today);
            tab = 0;
        }
        render(!fromPdf);
        ScheduleAlarm.refresh(this);
        toast(fromPdf ? "已导入 " + parsed.courses.size() + " 条排课" : "学期起始日已保存");
    }

    private void chooseMonday(LocalDate initial, java.util.function.Consumer<LocalDate> selected) {
        DatePickerDialog picker = new DatePickerDialog(this, (view, year, month, day) -> {
            LocalDate date = LocalDate.of(year, month + 1, day);
            if (!date.equals(Timetable.monday(date))) {
                toast("请选择教学第 1 周的星期一");
                chooseMonday(date, selected);
                return;
            }
            selected.accept(date);
        }, initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth());
        picker.setTitle("选择第 1 周的星期一");
        picker.setOnCancelListener(d -> importedUri = null);
        picker.show();
    }

    private void editPeriods() {
        int[][] pending = ScheduleStore.periods(this);
        LinearLayout rows = column();
        pad(rows, 15, 0, 15, 9);
        for (int i = 0; i < pending.length; i++) {
            LinearLayout row = row();
            row.addView(text("第 " + (i + 1) + " 节", 15, INK, true), new LinearLayout.LayoutParams(0, WRAP, 1));
            for (int side = 0; side < 2; side++) {
                int edge = side;
                int[] period = pending[i];
                TextView time = action(Timetable.clock(period[side]), PALE_BLUE, BLUE, () -> {});
                time.setOnClickListener(v -> new TimePickerDialog(this, (view, hour, minute) -> {
                    period[edge] = hour * 60 + minute;
                    time.setText(Timetable.clock(period[edge]));
                }, period[edge] / 60, period[edge] % 60, true).show());
                LinearLayout.LayoutParams params = size(74, 39);
                params.leftMargin = dp(5);
                row.addView(time, params);
            }
            add(rows, row, 0, 6);
        }
        ScrollView container = new ScrollView(this);
        container.addView(rows);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("常规节次时间").setView(container)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int previousEnd = -1;
            for (int i = 0; i < pending.length; i++) {
                int[] period = pending[i];
                if (period[0] >= period[1] || period[0] < previousEnd) {
                    toast("请检查第 " + (i + 1) + " 节的起止时间和相邻节次");
                    return;
                }
                previousEnd = period[1];
            }
            if (!ScheduleStore.savePeriods(this, pending)) { toast("保存失败，请重试"); return; }
            periods = pending;
            ScheduleAlarm.refresh(this);
            dialog.dismiss();
            render(true);
        });
    }

    private void pinScheduleWidget() {
        pinWidget(ScheduleWidget.class, R.string.widget_name);
    }

    private void pinWidget(Class<?> provider, int label) {
        AppWidgetManager manager = getSystemService(AppWidgetManager.class);
        if (manager.isRequestPinAppWidgetSupported()) {
            try {
                if (manager.requestPinAppWidget(new ComponentName(this, provider), null, null)) return;
            } catch (IllegalStateException ignored) {
                // The launcher may stop handling pinning while opening its confirmation.
            }
        }
        new AlertDialog.Builder(this).setTitle("添加桌面小组件")
            .setMessage("回到桌面，长按空白处，打开「小组件」并选择「" + getString(label) + "」。")
            .setPositiveButton("知道了", null).show();
    }

    private void toggleReminder() {
        boolean enable = !ScheduleAlarm.enabled(this);
        ScheduleStore.prefs(this).edit().putBoolean("reminders", enable).apply();
        if (enable && !ScheduleAlarm.notificationsAllowed(this)) requestNotifications();
        if (!enable) toast("已关闭上课提醒");
        ScheduleAlarm.refresh(this);
        render(true);
    }

    private void chooseLead() {
        int[] minutes = {5, 10, 15, 30};
        int checked = 1;
        for (int i = 0; i < minutes.length; i++) if (minutes[i] == ScheduleAlarm.lead(this)) checked = i;
        new AlertDialog.Builder(this).setTitle("上课前多久提醒")
            .setSingleChoiceItems(new String[]{"5 分钟", "10 分钟", "15 分钟", "30 分钟"}, checked, (dialog, item) -> {
                ScheduleStore.prefs(this).edit().putInt("lead", minutes[item]).apply();
                ScheduleAlarm.refresh(this);
                dialog.dismiss();
                render(true);
            }).setNegativeButton("取消", null).show();
    }

    private void exactSettings() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException ex) {
                toast("请在系统设置的「闹钟和提醒」中允许海课");
            }
        }
    }

    private void requestNotifications() {
        requestNotifications(null);
    }

    private void requestNotifications(String channel) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFY);
        } else {
            boolean hasChannel = channel != null && getSystemService(android.app.NotificationManager.class).getNotificationChannel(channel) != null;
            Intent settings = new Intent(hasChannel ? Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS : Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            if (hasChannel) settings.putExtra(Settings.EXTRA_CHANNEL_ID, channel);
            startActivity(settings);
        }
    }

    private void promotionSettings() {
        if (Build.VERSION.SDK_INT < 36) return;
        try {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } catch (ActivityNotFoundException ex) {
            requestNotifications(CourseNotifications.NEXT_CHANNEL);
        }
    }

    private void toggleCourseNotification(boolean live) {
        boolean enabled = !(live ? CourseNotifications.nextEnabled(this) : CourseNotifications.tomorrowEnabled(this));
        String channel = live ? CourseNotifications.NEXT_CHANNEL : CourseNotifications.TOMORROW_CHANNEL;
        CourseNotifications.setEnabled(this, live, enabled);
        ScheduleAlarm.refreshNotifications(this);
        render(true);
        if (enabled && !ScheduleAlarm.notificationsAllowed(this, channel)) requestNotifications(channel);
    }

    private void editTomorrowWindow() {
        CourseNotifications.Window current = CourseNotifications.window(this);
        int[] times = {current.start(), current.end()};
        LinearLayout body = column();
        pad(body, 21, 6, 21, 12);
        String[] labels = {"开始", "结束"};
        for (int i = 0; i < times.length; i++) {
            int index = i;
            add(body, text(labels[i], 14, MUTED, true), 0, 5);
            TextView time = action(Timetable.clock(times[i]), PALE_BLUE, BLUE, () -> {});
            time.setOnClickListener(view -> new TimePickerDialog(this, (picker, hour, minute) -> {
                times[index] = hour * 60 + minute;
                time.setText(Timetable.clock(times[index]));
                time.setContentDescription(labels[index] + " " + time.getText());
            }, times[index] / 60, times[index] % 60, true).show());
            time.setContentDescription(labels[index] + " " + time.getText());
            add(body, time, 0, 8, 45);
        }
        add(body, text("结束时间须晚于开始时间。", 13, MUTED, false), 2, 0);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("明天课表通知时段").setView(body)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            CourseNotifications.Window proposed = new CourseNotifications.Window(times[0], times[1]);
            if (!proposed.valid()) {
                toast("请选择同一天内的通知时段，结束时间须晚于开始时间");
                return;
            }
            CourseNotifications.saveWindow(this, proposed);
            ScheduleAlarm.refreshNotifications(this);
            dialog.dismiss();
            render(true);
        });
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == NOTIFY) {
            ScheduleAlarm.refresh(this);
            if (tab == 2) render(true);
        }
    }

    private void testNotification() {
        if (!ScheduleAlarm.notificationsAllowed(this)) { requestNotifications(); return; }
        boolean delayed = ScheduleAlarm.test(this);
        toast(delayed ? "约 7 秒后发送测试通知" : "已发送测试通知");
    }

    private void openWeb(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException ex) { toast("手机上找不到浏览器"); }
    }

    private void heading(String subtitle, String title) {
        heading(subtitle, title, null);
    }

    private void heading(String subtitle, String title, View aside) {
        body.addView(text(subtitle, 13, MUTED, true));
        LinearLayout heading = row();
        heading.addView(text(title, 29, INK, true), new LinearLayout.LayoutParams(0, WRAP, 1));
        if (aside != null) heading.addView(aside, size(WRAP, 36));
        add(body, heading, 3, 13);
    }

    private TextView dateOffset(long difference, String current, String unit) {
        String label = difference == 0 ? current : Math.abs(difference) + " " + unit + (difference < 0 ? "前" : "后");
        TextView indicator = text(label, 13, BLUE, true);
        indicator.setGravity(Gravity.CENTER);
        indicator.setMinWidth(dp(65));
        indicator.setMaxLines(1);
        pad(indicator, 5, 0, 5, 0);
        return indicator;
    }

    private String weekLabel(LocalDate date) {
        int number = table.week(date);
        return number < 1 || number > table.lastWeek() ? "非教学周" : "第 " + number + " 教学周";
    }

    private LinearLayout card() {
        LinearLayout card = column();
        GradientDrawable background = shape(PAPER, 17);
        background.setStroke(dp(1), LINE);
        card.setBackground(background);
        pad(card, 13, 12, 13, 12);
        return card;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setIncludeFontPadding(false);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private TextView action(String label, int background, int color, Runnable run) {
        return action(label, background, color, run, label);
    }

    private TextView action(String label, int background, int color, Runnable run, String description) {
        TextView button = text(label, 14, color, true);
        button.setGravity(Gravity.CENTER);
        if (label.length() > 2) pad(button, 9, 2, 9, 2);
        button.setContentDescription(description);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x263e79e8), shape(background, 10), shape(Color.WHITE, 10)));
        button.setFocusable(true);
        button.setOnClickListener(v -> run.run());
        return button;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private LinearLayout.LayoutParams size(int width, int height) {
        return new LinearLayout.LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height));
    }

    private void add(LinearLayout parent, View view, int top, int bottom) { add(parent, view, top, bottom, WRAP); }

    private void add(LinearLayout parent, View view, int top, int bottom, int height) {
        LinearLayout.LayoutParams params = size(MATCH, height);
        params.setMargins(0, dp(top), 0, dp(bottom));
        parent.addView(view, params);
    }

    private void pad(View view, int left, int top, int right, int bottom) { view.setPadding(dp(left), dp(top), dp(right), dp(bottom)); }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
