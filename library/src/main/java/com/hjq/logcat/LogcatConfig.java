package com.hjq.logcat;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.Gravity;
import java.util.Locale;

/**
 *    author : Android 轮子哥
 *    github : https://github.com/getActivity/Logcat
 *    time   : 2020/01/24
 *    desc   : 日志配置
 */
final class LogcatConfig {

    private static final String TAG = "LogcatConfig";

    /** 悬浮窗贴左边 */
    static final int FLOATING_WINDOW_EDGE_LEFT = 0;
    /** 悬浮窗贴右边 */
    static final int FLOATING_WINDOW_EDGE_RIGHT = 1;

    @SuppressLint("StaticFieldLeak")
    private static Context sApplicationContext;
    private static SharedPreferences sConfig;
    private static String sFloatingWindowGravityMeta;
    private static String sFloatingWindowOffsetXMeta;
    private static String sFloatingWindowOffsetYMeta;

    /**
     * 初始化
     */
    static void init(Context context) {
        sApplicationContext = context.getApplicationContext();
        sConfig = sApplicationContext.getSharedPreferences(
                LogcatContract.SP_FILE_NAME, Context.MODE_PRIVATE);
        // 清单 meta 只查一次缓存起来，避免每次创建悬浮窗都跨进程读取
        sFloatingWindowGravityMeta = LogcatUtils.getMetaStringData(sApplicationContext,
                LogcatContract.META_DATA_LOGCAT_FLOATING_WINDOW_GRAVITY);
        sFloatingWindowOffsetXMeta = LogcatUtils.getMetaStringData(sApplicationContext,
                LogcatContract.META_DATA_LOGCAT_FLOATING_WINDOW_OFFSET_X);
        sFloatingWindowOffsetYMeta = LogcatUtils.getMetaStringData(sApplicationContext,
                LogcatContract.META_DATA_LOGCAT_FLOATING_WINDOW_OFFSET_Y);
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
     * 悬浮窗位置记录：贴边方向 + 纵向比例（0~1），记录无效时返回 null
     */
    static float[] getFloatingWindowLocationConfig() {
        String saved = sConfig.getString(LogcatContract.SP_KEY_LOGCAT_FLOATING_WINDOW_LOCATION, null);
        if (saved == null) {
            return null;
        }
        String[] parts = saved.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            int edge = Integer.parseInt(parts[0]);
            float ratio = Float.parseFloat(parts[1]);
            if ((edge == FLOATING_WINDOW_EDGE_LEFT || edge == FLOATING_WINDOW_EDGE_RIGHT)
                    && ratio >= 0 && ratio <= 1) {
                return new float[]{edge, ratio};
            }
        } catch (NumberFormatException ignored) {
            // 记录损坏时按无记忆处理
        }
        return null;
    }

    /**
     * 只记贴边方向与纵向比例，可见区域尺寸、dpi 或窗口模式变化时由调用方重新换算并夹取
     */
    static void setFloatingWindowLocationConfig(int edge, float ratio) {
        sConfig.edit().putString(LogcatContract.SP_KEY_LOGCAT_FLOATING_WINDOW_LOCATION,
                edge + "," + ratio).apply();
    }

    static int[] getFloatingWindowDefaultLocation(int screenWidth, int screenHeight) {
        return new int[]{parseGravity(sFloatingWindowGravityMeta),
                parseMetaOffset(sFloatingWindowOffsetXMeta, screenWidth,
                        LogcatContract.META_DATA_LOGCAT_FLOATING_WINDOW_OFFSET_X),
                parseMetaOffset(sFloatingWindowOffsetYMeta, screenHeight,
                        LogcatContract.META_DATA_LOGCAT_FLOATING_WINDOW_OFFSET_Y)};
    }

    private static int parseMetaOffset(String value, int screenSize, String metaKey) {
        if (value != null) {
            try {
                int offset = Integer.parseInt(value.trim());
                if (offset >= -screenSize && offset <= screenSize) {
                    return offset;
                }
                // 资源引用等写法会解析出资源 id，超出屏幕范围的一律视为非法配置
                Log.w(TAG, metaKey + " 超出屏幕范围，按 0 处理：" + offset);
            } catch (NumberFormatException ignored) {
                // 非整数配置按默认值处理
            }
        }
        return 0;
    }

    private static int parseGravity(String value) {
        int defaultGravity = Gravity.END | Gravity.CENTER_VERTICAL;
        if (value == null || value.trim().isEmpty()) {
            return defaultGravity;
        }
        int horizontal = 0;
        int vertical = 0;
        boolean conflict = false;
        boolean invalid = false;
        for (String token : value.split("\\|")) {
            switch (token.trim().toLowerCase(Locale.ROOT)) {
                case "left":
                    conflict |= horizontal != 0 && horizontal != Gravity.LEFT;
                    horizontal = Gravity.LEFT;
                    break;
                case "right":
                    conflict |= horizontal != 0 && horizontal != Gravity.RIGHT;
                    horizontal = Gravity.RIGHT;
                    break;
                case "start":
                    conflict |= horizontal != 0 && horizontal != Gravity.START;
                    horizontal = Gravity.START;
                    break;
                case "end":
                    conflict |= horizontal != 0 && horizontal != Gravity.END;
                    horizontal = Gravity.END;
                    break;
                case "top":
                    conflict |= vertical != 0 && vertical != Gravity.TOP;
                    vertical = Gravity.TOP;
                    break;
                case "bottom":
                    conflict |= vertical != 0 && vertical != Gravity.BOTTOM;
                    vertical = Gravity.BOTTOM;
                    break;
                case "center":
                    conflict |= (horizontal != 0 && horizontal != Gravity.CENTER_HORIZONTAL)
                            || (vertical != 0 && vertical != Gravity.CENTER_VERTICAL);
                    horizontal = Gravity.CENTER_HORIZONTAL;
                    vertical = Gravity.CENTER_VERTICAL;
                    break;
                case "center_vertical":
                    conflict |= vertical != 0 && vertical != Gravity.CENTER_VERTICAL;
                    vertical = Gravity.CENTER_VERTICAL;
                    break;
                case "center_horizontal":
                    conflict |= horizontal != 0 && horizontal != Gravity.CENTER_HORIZONTAL;
                    horizontal = Gravity.CENTER_HORIZONTAL;
                    break;
                default:
                    invalid = true;
                    break;
            }
        }
        if (horizontal == 0 && vertical == 0) {
            Log.w(TAG, "悬浮窗 gravity 配置无效，按默认值处理：" + value);
            return defaultGravity;
        }
        if (invalid) {
            Log.w(TAG, "悬浮窗 gravity 包含无法识别的方向：" + value);
        }
        if (conflict) {
            Log.w(TAG, "悬浮窗 gravity 存在重复方向，取最后一个：" + value);
        }
        return horizontal | vertical;
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