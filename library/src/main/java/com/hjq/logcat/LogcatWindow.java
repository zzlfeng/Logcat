package com.hjq.logcat;

import android.animation.Animator;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import com.hjq.window.EasyWindow;
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
        setWindowSize(size, size);

        setWindowAnim(android.R.style.Animation_Toast);
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
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int[] location = LogcatConfig.getFloatingWindowLocationConfig(metrics.widthPixels, metrics.heightPixels);
        setWindowLocation(location[0], location[1], location[2]);
        setOnClickListenerByView(android.R.id.icon, this);
    }

    private void saveWindowLocation() {
        WindowManager.LayoutParams params = getWindowParams();
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        LogcatConfig.setFloatingWindowLocationConfig(params.gravity, params.x, params.y,
                metrics.widthPixels, metrics.heightPixels);
    }

    @Override
    public void onClick(@NonNull EasyWindow<?> easyWindow, @NonNull View view) {
        saveWindowLocation();
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