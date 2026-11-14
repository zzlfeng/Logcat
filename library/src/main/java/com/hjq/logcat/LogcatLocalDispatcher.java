package com.hjq.logcat;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.TypedValue;
import androidx.annotation.NonNull;
import java.util.HashMap;
import java.util.Map;

/**
 *    author : Android 轮子哥
 *    github : https://github.com/getActivity/Logcat
 *    time   : 2020/01/24
 *    desc   : Logcat 悬浮窗局部显示派发
 */
final class LogcatLocalDispatcher implements Application.ActivityLifecycleCallbacks {

    static void launch(Application application) {
        application.registerActivityLifecycleCallbacks(new LogcatLocalDispatcher());
    }

    private final Map<Activity, LogcatWindow> mWindows = new HashMap<>();

    @Override
    public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) {
        if (activity instanceof LogcatActivity) {
            return;
        }
        if (isActivityTranslucent(activity)) {
            // Activity是半透明的，则不展示悬浮窗
            return;
        }
        // 在每个 Activity 创建的时候派发
        LogcatWindow window = new LogcatWindow(activity);
        window.show();
        mWindows.put(activity, window);
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        // default implementation ignored
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        // 返回栈里的页面恢复时同步最新记忆位置
        LogcatWindow window = mWindows.get(activity);
        if (window != null) {
            window.syncWindowLocationFromConfig();
        }
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        // default implementation ignored
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        // default implementation ignored
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
        // default implementation ignored
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        mWindows.remove(activity);
    }

    /**
     * 判断当前 Activity 窗口是否为半透明的
     */
    public boolean isActivityTranslucent(@NonNull Activity activity) {
        TypedValue typedValue = new TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.windowIsTranslucent, typedValue, true);
        // 当前 android:windowIsTranslucent 为 true 时，typedValue.data 的值为 - 1
        // 如果不设置或者设置为 false，则 typedValue.data 的值为 0
        return typedValue.data != 0;
    }
}