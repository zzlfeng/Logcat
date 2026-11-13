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
     * 悬浮窗位置：优先记忆，屏幕尺寸变化（旋转/换设备）时记忆失效回落默认
     */
    static int[] getFloatingWindowLocationConfig(int screenWidth, int screenHeight) {
        String saved = sConfig.getString(LogcatContract.SP_KEY_LOGCAT_FLOATING_WINDOW_LOCATION, null);
        if (saved != null) {
            String[] parts = saved.split(",");
            if (parts.length == 5) {
                try {
                    if (Integer.parseInt(parts[3]) == screenWidth && Integer.parseInt(parts[4]) == screenHeight) {
                        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
                    }
                } catch (NumberFormatException ignored) {
                    // 记录损坏时回落默认位置
                }
            }
        }
        return getFloatingWindowDefaultLocation();
    }

    static void setFloatingWindowLocationConfig(int gravity, int x, int y, int screenWidth, int screenHeight) {
        sConfig.edit().putString(LogcatContract.SP_KEY_LOGCAT_FLOATING_WINDOW_LOCATION,
                gravity + "," + x + "," + y + "," + screenWidth + "," + screenHeight).apply();
    }

    private static int[] getFloatingWindowDefaultLocation() {
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