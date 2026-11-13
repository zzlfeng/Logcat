package com.hjq.logcat;

import android.animation.Animator;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import com.hjq.window.EasyWindow;
import com.hjq.window.OnWindowLifecycleCallback;
import com.hjq.window.OnWindowViewClickListener;
import com.hjq.window.draggable.SpringBackWindowDraggableRule;
import com.hjq.window.draggable.callback.OnSpringBackAnimCallback;
import com.hjq.window.draggable.callback.OnWindowDraggingCallback;

/**
 *    author : Android 轮子哥
 *    github : https://github.com/getActivity/Logcat
 *    time   : 2020/01/24
 *    desc   : 悬浮窗口
 */
final class LogcatWindow extends EasyWindow<LogcatWindow> implements OnWindowViewClickListener<View> {

    private static final int WINDOW_MODE_LOCAL = 1;
    private static final int WINDOW_MODE_GLOBAL = 2;

    private int mWindowMode;
    private int mWindowSize;
    private int[] mSavedLocation;

    public LogcatWindow(Application application) {
        super(application);
        init(application);
    }

    public LogcatWindow(Activity activity) {
        super(activity);
        init(activity);
    }

    private void init(Context context) {
        mWindowMode = context instanceof Activity ? WINDOW_MODE_LOCAL : WINDOW_MODE_GLOBAL;

        ImageView imageView = new ImageView(context.getApplicationContext());
        imageView.setId(android.R.id.icon);
        imageView.setImageResource(R.drawable.logcat_selector_floating);
        setContentView(imageView);

        int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 45, context.getResources().getDisplayMetrics());
        mWindowSize = size;
        setWindowSize(size, size);

        setWindowAnim(android.R.style.Animation_Toast);

        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        mSavedLocation = LogcatConfig.getFloatingWindowLocationConfig();
        if (hasMatchedSavedLocation(metrics.densityDpi)) {
            setWindowLocation(Gravity.TOP | Gravity.LEFT, mSavedLocation[0], mSavedLocation[1]);
        } else {
            applyDefaultWindowLocation(metrics.widthPixels, metrics.heightPixels);
        }

        SpringBackWindowDraggableRule draggableRule = new SpringBackWindowDraggableRule();
        draggableRule.setOnWindowDraggingCallback(new OnWindowDraggingCallback() {
            @Override
            public void onWindowDraggingStop(@NonNull EasyWindow<?> easyWindow) {
                // 已经贴边时不会触发吸边动画，松手时先记一次
                saveWindowLocation();
            }
        });
        // 吸边动画结束后才是最终位置，此时记忆
        draggableRule.setOnSpringBackAnimCallback(new OnSpringBackAnimCallback() {
            @Override
            public void onSpringBackAnimationEnd(@NonNull EasyWindow<?> easyWindow, @NonNull Animator animator) {
                saveWindowLocation();
            }
        });
        setWindowDraggableRule(draggableRule);

        setOnWindowLifecycleCallback(new OnWindowLifecycleCallback() {
            @Override
            public void onWindowShow(@NonNull EasyWindow<?> easyWindow) {
                // 显示并布局后才能拿到真实的可见区域尺寸
                restoreWindowLocationWhenLaidOut();
            }
        });
        setOnClickListenerByView(android.R.id.icon, this);
    }

    private void restoreWindowLocationWhenLaidOut() {
        ViewGroup rootLayout = getRootLayout();
        if (rootLayout == null) {
            return;
        }
        rootLayout.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                ViewTreeObserver observer = rootLayout.getViewTreeObserver();
                if (observer.isAlive()) {
                    observer.removeOnGlobalLayoutListener(this);
                }
                restoreWindowLocation();
            }
        });
    }

    private void restoreWindowLocation() {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        int[] visibleSize = getVisibleWindowSize(metrics);
        if (hasMatchedSavedLocation(metrics.densityDpi)
                && mSavedLocation[2] == visibleSize[0]
                && mSavedLocation[3] == visibleSize[1]) {
            int maxX = Math.max(visibleSize[0] - mWindowSize, 0);
            int maxY = Math.max(visibleSize[1] - mWindowSize, 0);
            int x = Math.min(Math.max(mSavedLocation[0], 0), maxX);
            int y = Math.min(Math.max(mSavedLocation[1], 0), maxY);
            if (x != mSavedLocation[0] || y != mSavedLocation[1]) {
                setWindowLocation(Gravity.TOP | Gravity.LEFT, x, y);
                saveWindowLocation();
            }
        } else {
            // 可见区域尺寸、dpi 或窗口模式变化，记忆失效
            applyDefaultWindowLocation(visibleSize[0], visibleSize[1]);
        }
    }

    private boolean hasMatchedSavedLocation(int densityDpi) {
        return mSavedLocation != null
                && mSavedLocation[4] == densityDpi
                && mSavedLocation[5] == mWindowMode;
    }

    private void applyDefaultWindowLocation(int screenWidth, int screenHeight) {
        int[] location = LogcatConfig.getFloatingWindowDefaultLocation(screenWidth, screenHeight);
        setWindowLocation(location[0], location[1], location[2]);
    }

    private int[] getVisibleWindowSize(DisplayMetrics metrics) {
        Rect visibleFrame = new Rect();
        ViewGroup rootLayout = getRootLayout();
        if (rootLayout != null) {
            rootLayout.getWindowVisibleDisplayFrame(visibleFrame);
        }
        if (visibleFrame.width() > 0 && visibleFrame.height() > 0) {
            return new int[]{visibleFrame.width(), visibleFrame.height()};
        }
        return new int[]{metrics.widthPixels, metrics.heightPixels};
    }

    private void saveWindowLocation() {
        WindowManager.LayoutParams params = getWindowParams();
        if (params.gravity != (Gravity.TOP | Gravity.LEFT)) {
            return;
        }
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        int[] visibleSize = getVisibleWindowSize(metrics);
        LogcatConfig.setFloatingWindowLocationConfig(params.x, params.y,
                visibleSize[0], visibleSize[1], metrics.densityDpi, mWindowMode);
    }

    @Override
    public void onClick(@NonNull EasyWindow<?> easyWindow, @NonNull View view) {
        Context context = getContext();
        Intent intent = new Intent(context, LogcatActivity.class);
        if (!(context instanceof Activity)) {
            // 如果当前的上下文不是 Activity，调用 startActivity 必须加入新任务栈的标记，否则会报错：android.util.AndroidRuntimeException
            // Calling startActivity() from outside of an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag. Is this really what you want?
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        startActivity(intent);
    }
}
