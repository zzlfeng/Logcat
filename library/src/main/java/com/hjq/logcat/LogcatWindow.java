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

    private int mWindowSize;
    private int mLastVisibleWidth = -1;
    private int mLastVisibleHeight = -1;
    private boolean mFirstLayoutDone;
    private boolean mLocationListenerAdded;

    /** 首次布局后定位，之后只在可见区域尺寸变化时夹取或重新贴边 */
    private final ViewTreeObserver.OnGlobalLayoutListener mLocationListener =
            new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
                    int[] visibleSize = getVisibleWindowSize(metrics);
                    if (!mFirstLayoutDone) {
                        mFirstLayoutDone = true;
                        mLastVisibleWidth = visibleSize[0];
                        mLastVisibleHeight = visibleSize[1];
                        applySavedWindowLocation(visibleSize[0], visibleSize[1]);
                        // 定位完成后再显示，避免先在旧位置出现再跳过去
                        setWindowAlpha(1f);
                        return;
                    }
                    if (visibleSize[0] == mLastVisibleWidth && visibleSize[1] == mLastVisibleHeight) {
                        return;
                    }
                    mLastVisibleWidth = visibleSize[0];
                    mLastVisibleHeight = visibleSize[1];
                    // 旋转、分屏、软键盘都按记忆重排，尺寸恢复后能回到原位
                    applySavedWindowLocation(visibleSize[0], visibleSize[1]);
                }
            };

    public LogcatWindow(Application application) {
        super(application);
        init(application);
    }

    public LogcatWindow(Activity activity) {
        super(activity);
        init(activity);
    }

    private void init(Context context) {
        ImageView imageView = new ImageView(context.getApplicationContext());
        imageView.setId(android.R.id.icon);
        imageView.setImageResource(R.drawable.logcat_selector_floating);
        setContentView(imageView);

        int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 45, context.getResources().getDisplayMetrics());
        mWindowSize = size;
        setWindowSize(size, size);

        setWindowAnim(android.R.style.Animation_Toast);
        // 首次布局完成定位前不可见，避免显示出现跳动
        setWindowAlpha(0f);

        SpringBackWindowDraggableRule draggableRule = new SpringBackWindowDraggableRule() {
            @Override
            public boolean isFollowScreenRotationChanges() {
                // 旋转定位统一由 mLocationListener 处理，避免拖拽规则按旧屏幕尺寸覆盖坐标
                return false;
            }
        };
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
                ViewGroup rootLayout = getRootLayout();
                if (rootLayout == null) {
                    mFirstLayoutDone = true;
                    DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
                    applySavedWindowLocation(metrics.widthPixels, metrics.heightPixels);
                    setWindowAlpha(1f);
                    return;
                }
                if (!mLocationListenerAdded) {
                    mLocationListenerAdded = true;
                    rootLayout.getViewTreeObserver().addOnGlobalLayoutListener(mLocationListener);
                }
            }

            @Override
            public void onWindowCancel(@NonNull EasyWindow<?> easyWindow) {
                removeLocationListener();
            }

            @Override
            public void onWindowRecycle(@NonNull EasyWindow<?> easyWindow) {
                removeLocationListener();
            }
        });
        setOnClickListenerByView(android.R.id.icon, this);
    }

    /**
     * 页面恢复时同步最新记忆位置（页面内模式每个页面各有一个悬浮窗实例）
     */
    void syncWindowLocationFromConfig() {
        if (!isShowing() || !mFirstLayoutDone) {
            return;
        }
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        int[] visibleSize = getVisibleWindowSize(metrics);
        mLastVisibleWidth = visibleSize[0];
        mLastVisibleHeight = visibleSize[1];
        applySavedWindowLocation(visibleSize[0], visibleSize[1]);
    }

    private void removeLocationListener() {
        ViewGroup rootLayout = getRootLayout();
        if (rootLayout == null) {
            return;
        }
        ViewTreeObserver observer = rootLayout.getViewTreeObserver();
        if (observer.isAlive()) {
            observer.removeOnGlobalLayoutListener(mLocationListener);
        }
        mLocationListenerAdded = false;
    }

    private void applySavedWindowLocation(int visibleWidth, int visibleHeight) {
        float[] saved = LogcatConfig.getFloatingWindowLocationConfig();
        if (saved == null) {
            applyDefaultWindowLocation(visibleWidth, visibleHeight);
            return;
        }
        int maxX = Math.max(visibleWidth - mWindowSize, 0);
        int maxY = Math.max(visibleHeight - mWindowSize, 0);
        int x = (int) saved[0] == LogcatConfig.FLOATING_WINDOW_EDGE_RIGHT ? maxX : 0;
        int y = Math.round(saved[1] * maxY);
        setWindowLocation(Gravity.TOP | Gravity.LEFT, x, y);
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
        int maxX = Math.max(visibleSize[0] - mWindowSize, 0);
        int maxY = Math.max(visibleSize[1] - mWindowSize, 0);
        int x = Math.min(Math.max(params.x, 0), maxX);
        int y = Math.min(Math.max(params.y, 0), maxY);
        int edge = x > maxX / 2 ? LogcatConfig.FLOATING_WINDOW_EDGE_RIGHT
                : LogcatConfig.FLOATING_WINDOW_EDGE_LEFT;
        float ratio = maxY > 0 ? (float) y / maxY : 0f;
        LogcatConfig.setFloatingWindowLocationConfig(edge, ratio);
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
