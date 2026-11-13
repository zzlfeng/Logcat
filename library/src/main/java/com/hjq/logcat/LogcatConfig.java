package com.hjq.logcat;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import java.util.Locale;

/**
 *    author : Android 轮子哥
 *    github : https://github.com/getActivity/Logcat
 *    time   : 2020/01/24
 *    desc   : 日志配置
 */
final class LogcatConfig {

    @SuppressLint("StaticFieldLeak")
    private static Context sApplicationContext;
    private static SharedPreferences sConfig;

    /**
     * 初始化
     */
    static void init(Context context) {
        sApplicationContext = context.getApplicationContext();
        sConfig = sApplicationContext.getSharedPreferences(
                LogcatContract.SP_FILE_NAME, Context.MODE_PRIVATE);
    }

    static String getLogLevelConfig() {
        String defaultLogLevel = LogcatUtils.getMetaStringData(sApplicationContext,
                LogcatContract.META_DATA_LOGCAT_DEFAULT_SEARCH_LEVEL);
        if (defaultLogLevel != null && !defaultLogLevel.isEmpty()) {
            defaultLogLevel = defaultLogLevel.toUpperCase(Locale.ROOT);
        }
        String logLevel = sConfig.getString(LogcatContract.SP_KEY_LOGCAT_LOG_LEVEL, defaultLogLevel);
        if (logLevel == null || logLevel.isEmpty()) {
            logLevel = LogLevel.VERBOSE;
        }
        return logLevel;
    }

    static void setLogLevelConfig(String logLevel) {
        sConfig.edit().putString(LogcatContract.SP_KEY_LOGCAT_LOG_LEVEL, logLevel).apply();
    }

    static boolean getLogLevelModeConfig() {
        Boolean defaultMode = LogcatUtils.getMetaBooleanData(sApplicationContext,
                LogcatContract.META_DATA_LOGCAT_DEFAULT_LOG_LEVEL_MODE);
        return sConfig.getBoolean(LogcatContract.SP_KEY_LOGCAT_LOG_LEVEL_MODE,
                defaultMode != null && defaultMode);
    }

    static void setLogLevelModeConfig(boolean andAbove) {
        sConfig.edit().putBoolean(LogcatContract.SP_KEY_LOGCAT_LOG_LEVEL_MODE, andAbove).apply();
    }

    /**
     * 悬浮窗位置记录：x,y,可见区域宽,可见区域高,dpi,窗口模式，记录无效时返回 null
     */
    static int[] getFloatingWindowLocationConfig() {
        String saved = sConfig.getString(LogcatContract.SP_KEY_LOGCAT_FLOATING_WINDOW_LOCATION, null);
        if (saved == null) {
            return null;
        }
        String[] parts = saved.split(",");
        if (parts.length != 6) {
            return null;
        }
        try {
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            int visibleWidth = Integer.parseInt(parts[2]);
            int visibleHeight = Integer.parseInt(parts[3]);
            int densityDpi = Integer.parseInt(parts[4]);
            int mode = Integer.parseInt(parts[5]);
            if (visibleWidth > 0 && visibleHeight > 0 && densityDpi > 0) {
                return new int[]{x, y, visibleWidth, visibleHeight, densityDpi, mode};
            }
        } catch (NumberFormatException ignored) {
            // 记录损坏时按无记忆处理
        }
        return null;
    }

    /**
     * 记录的是可见区域坐标系下的绝对坐标，可见区域尺寸、dpi 或窗口模式变化时由调用方判失效
     */
    static void setFloatingWindowLocationConfig(int x, int y, int visibleWidth, int visibleHeight,
            int densityDpi, int mode) {
        sConfig.edit().putString(LogcatContract.SP_KEY_LOGCAT_FLOATING_WINDOW_LOCATION,
                x + "," + y + "," + visibleWidth + "," + visibleHeight + "," + densityDpi + "," + mode).apply();
    }

    static int[] getFloatingWindowDefaultLocation() {
        int gravity = parseGravity(LogcatUtils.getMetaStringData(sApplicationContext,
                LogcatContract.META_DATA_LOGCAT_FLOATING_WINDOW_GRAVITY));
        return new int[]{gravity,
                getMetaInt(LogcatContract.META_DATA_LOGCAT_FLOATING_WINDOW_OFFSET_X),
                getMetaInt(LogcatContract.META_DATA_LOGCAT_FLOATING_WINDOW_OFFSET_Y)};
    }

    private static int getMetaInt(String metaKey) {
        String value = LogcatUtils.getMetaStringData(sApplicationContext, metaKey);
        if (value != null) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static int parseGravity(String value) {
        int defaultGravity = Gravity.END | Gravity.CENTER_VERTICAL;
        if (value == null || value.isEmpty()) {
            return defaultGravity;
        }
        int gravity = 0;
        for (String token : value.split("\\|")) {
            switch (token.trim()) {
                case "left":
                    gravity |= Gravity.LEFT;
                    break;
                case "right":
                    gravity |= Gravity.RIGHT;
                    break;
                case "start":
                    gravity |= Gravity.START;
                    break;
                case "end":
                    gravity |= Gravity.END;
                    break;
                case "top":
                    gravity |= Gravity.TOP;
                    break;
                case "bottom":
                    gravity |= Gravity.BOTTOM;
                    break;
                case "center":
                    gravity |= Gravity.CENTER;
                    break;
                case "center_vertical":
                    gravity |= Gravity.CENTER_VERTICAL;
                    break;
                case "center_horizontal":
                    gravity |= Gravity.CENTER_HORIZONTAL;
                    break;
                default:
                    break;
            }
        }
        return gravity == 0 ? defaultGravity : gravity;
    }

    static String getSearchKeyConfig() {
        String defaultSearchKey = LogcatUtils.getMetaStringData(sApplicationContext,
                LogcatContract.META_DATA_LOGCAT_DEFAULT_SEARCH_KEY);
        return sConfig.getString(LogcatContract.SP_KEY_LOGCAT_SEARCH_KEY, defaultSearchKey);
    }

    static void setSearchKeyConfig(String searchKey) {
        sConfig.edit().putString(LogcatContract.SP_KEY_LOGCAT_SEARCH_KEY, searchKey).apply();
    }
}