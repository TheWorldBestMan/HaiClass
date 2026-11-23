package cn.haike.schedule;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class WeatherJobService extends JobService {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private JobParameters active;
    private Future<?> task;

    @Override public boolean onStartJob(JobParameters parameters) {
        Set<WeatherForecast.Campus> campuses = WeatherAlerts.dueCampuses(this);
        if (campuses.isEmpty()) return false;
        active = parameters;
        task = executor.submit(() -> {
            for (WeatherForecast.Campus campus : campuses) {
                if (Thread.currentThread().isInterrupted()) return;
                try {
                    WeatherForecast.refresh(getApplicationContext(), campus, false);
                } catch (IOException ignored) {
                    // A cached forecast can still be used, and remaining work will be retried.
                }
            }
            main.post(() -> finish(parameters));
        });
        return true;
    }

    private void finish(JobParameters parameters) {
        if (active != parameters) return;
        active = null;
        task = null;
        WeatherAlerts.deliverDue(this);
        jobFinished(parameters, !WeatherAlerts.dueCampuses(this).isEmpty());
        ScheduleAlarm.refreshWeather(this);
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        if (active != null && active.getJobId() == parameters.getJobId()) {
            active = null;
            if (task != null) task.cancel(true);
            task = null;
        }
        return !WeatherAlerts.dueCampuses(this).isEmpty();
    }

    @Override public void onDestroy() {
        active = null;
        executor.shutdownNow();
        super.onDestroy();
    }
}
