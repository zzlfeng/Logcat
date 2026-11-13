package com.hjq.logcat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *    author : Android 轮子哥
 *    github : https://github.com/getActivity/Logcat
 *    time   : 2020/01/24
 *    desc   : Logcat 显示窗口
 */
public final class LogcatActivity extends AppCompatActivity
        implements TextWatcher, View.OnLongClickListener, View.OnClickListener,
        CompoundButton.OnCheckedChangeListener, LogcatManager.Callback,
        LogcatAdapter.OnItemLongClickListener, LogcatAdapter.OnItemClickListener {

    private static final String[] ARRAY_LOG_LEVEL = {"Verbose", "Debug", "Info", "Warn", "Error"};
    private static final String[] ARRAY_LOG_LEVEL_PORTRAIT = {"V", "D", "I", "W", "E"};

    /** 搜索关键字记录上限 */
    private static final int SEARCH_KEYWORD_MAX_COUNT = 20;

    /** 日志批量消费间隔（毫秒） */
    private static final int LOG_DRAIN_INTERVAL_MILLIS = 150;

    private View mRootView;
    private View mBarView;
    private CheckBox mCheckBox;
    private View mSaveView;
    private ViewGroup mLevelLayout;
    private TextView mLevelView;
    private EditText mSearchView;
    private ImageView mIconView;
    private View mClearView;
    private View mHideView;
    private RecyclerView mRecyclerView;
    private View mDownView;

    private LinearLayoutManager mLinearLayoutManager;
    private LogcatAdapter mAdapter;

    /** 待显示日志队列（reader 线程入队，主线程批量消费） */
    private final Queue<LogcatInfo> mPendingLogs = new ConcurrentLinkedQueue<>();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    /** 是否有待执行的日志消费任务（0 无 1 有），用于入队踢一脚且保证单消费链 */
    private final AtomicInteger mDrainScheduled = new AtomicInteger();
    private volatile boolean mLogDrainEnabled;

    private String mLogLevel = LogLevel.VERBOSE;

    /** 等级过滤是否包含更高等级的日志 */
    private boolean mLogLevelAndAbove;

    /** 暂停输出日志标记 */
    private boolean mPauseLogFlag;

    /** Tag 过滤规则（reader 线程读、主线程写，需并发安全） */
    private final Set<String> mTagFilter = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** 搜索关键字 */
    private final List<String> mSearchKeyword = new ArrayList<>();

    /** 当前是否授予了读取日志权限 */
    private boolean mGrantedReadLogPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.logcat_activity_logcat);

        mRootView = findViewById(R.id.ll_log_root);
        mBarView = findViewById(R.id.ll_log_bar);
        mCheckBox = findViewById(R.id.cb_log_switch);
        mSaveView = findViewById(R.id.iv_log_save);
        mLevelLayout = findViewById(R.id.ll_log_level);
        mLevelView = findViewById(R.id.tv_log_level);
        mSearchView = findViewById(R.id.et_log_search_input);
        mIconView = findViewById(R.id.iv_log_search_icon);
        mClearView = findViewById(R.id.iv_log_logcat_clear);
        mHideView = findViewById(R.id.iv_log_logcat_hide);
        mRecyclerView = findViewById(R.id.lv_log_logcat_list);
        mDownView = findViewById(R.id.ib_log_logcat_down);

        mAdapter = new LogcatAdapter(this);
        mAdapter.setOnItemClickListener(this);
        mAdapter.setOnItemLongClickListener(this);

        mLinearLayoutManager = new LinearLayoutManager(this);
        mRecyclerView.setLayoutManager(mLinearLayoutManager);
        mRecyclerView.setAnimation(null);
        mCheckBox.setOnCheckedChangeListener(this);
        mSearchView.addTextChangedListener(this);

        mSaveView.setOnClickListener(this);
        mLevelLayout.setOnClickListener(this);
        mIconView.setOnClickListener(this);
        mClearView.setOnClickListener(this);
        mHideView.setOnClickListener(this);
        mDownView.setOnClickListener(this);

        mSaveView.setOnLongClickListener(this);
        mCheckBox.setOnLongClickListener(this);
        mLevelLayout.setOnLongClickListener(this);
        mClearView.setOnLongClickListener(this);
        mHideView.setOnLongClickListener(this);

        mGrantedReadLogPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
                (mGrantedReadLogPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)) {
            LogcatManager.setCanObtainUid(true);
        }

        LogcatManager.setCallback(this);

        // 先初始化过滤器再开始捕获，避免 reader 线程读到未初始化的过滤集合
        initLogFilter();

        // 开始捕获
        LogcatManager.start();

        initSearchCondition();
        refreshLayout();

        // mRecyclerView.setAdapter(mAdapter);
        long delayMillis;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            delayMillis = 500;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            delayMillis = 800;
        } else if (Build.VERSION.SDK_INT >= VERSION_CODES.M) {
            delayMillis = 1000;
        } else {
            delayMillis = 1200;
        }

        // 延迟设置适配器，这样可以提升性能，因为进入这个界面的时候，会频繁添加日志，如果这个时候适配器已经绑定
        // 那么就会引发 itemView 频繁测试和绘制，这样会很卡，并且还会出现 ANR 的情况
        mRecyclerView.postDelayed(() -> mRecyclerView.setAdapter(mAdapter), delayMillis);

        mRecyclerView.postDelayed(() ->
            mLinearLayoutManager.scrollToPosition(mAdapter.getItemCount() - 1), 1200);
    }

    private void initSearchCondition() {
        String searchKey = LogcatConfig.getSearchKeyConfig();
        if (searchKey != null && !searchKey.isEmpty()) {
            mSearchView.setText(searchKey);
        }

        mLogLevelAndAbove = LogcatConfig.getLogLevelModeConfig();
        mAdapter.setLogLevelAndAbove(mLogLevelAndAbove);

        String logLevel = LogcatConfig.getLogLevelConfig();
        setLogLevel(logLevel);
    }

    @Override
    public void onReceiveLog(LogcatInfo info) {
        // 如果当前没有授予读取日志权限，则只打印当前应用的日志，如果有权限，则显示所有日志
        if (!mGrantedReadLogPermission) {
            try {
                String uidString = info.getUid();
                if (uidString != null && !uidString.isEmpty()) {
                    int uid = Integer.parseInt(uidString);
                    if (uid != android.os.Process.myUid()) {
                        // 这个日志必须是当前应用打印的
                        return;
                    }
                }
            } catch (NumberFormatException ignore) {
                // Android 10 及以下机型获取日志打印的 uid 会返回几个空格
                // java.lang.NumberFormatException: For input string: "    "
                // 切勿在此处打印任何异常或者日志，避免造成无限递归
            }
        }

        // 这个 Tag 必须不在过滤列表中
        if (mTagFilter.contains(info.getTag().trim())) {
            return;
        }

        // 只入队，由主线程批量消费；延迟一个间隔再处理以攒批，避免逐条刷新列表
        mPendingLogs.offer(info);
        if (mLogDrainEnabled && mDrainScheduled.compareAndSet(0, 1)) {
            mMainHandler.postDelayed(mDrainRunnable, LOG_DRAIN_INTERVAL_MILLIS);
        }
    }

    @Override
    public boolean onLongClick(View v) {
        if (v == mCheckBox) {
            LogcatUtils.toast(this, R.string.logcat_capture);
        } else if (v == mSaveView) {
            LogcatUtils.toast(this, R.string.logcat_save);
        } else if (v == mLevelLayout) {
            setLogLevelAndAbove(!mLogLevelAndAbove);
            LogcatUtils.toast(this, mLogLevelAndAbove ?
                    R.string.logcat_level_mode_above : R.string.logcat_level_mode_exact);
        } else if (v == mClearView) {
            LogcatUtils.toast(this, R.string.logcat_empty);
        } else if (v == mHideView) {
            LogcatUtils.toast(this, R.string.logcat_close);
        }
        return true;
    }

    @Override
    public void onClick(View v) {
        if (v == mSaveView) {
            final List<LogcatInfo.Snapshot> data = new ArrayList<>(mAdapter.getShowData().size());
            for (LogcatInfo info : mAdapter.getShowData()) {
                data.add(info.snapshot());
            }
            new Thread(() -> {
                String message;
                try {
                    File file = LogcatUtils.saveLogToFile(this, data);
                    message = getResources().getString(R.string.logcat_save_succeed) + file.getPath();
                } catch (IOException e) {
                    e.printStackTrace();
                    message = getResources().getString(R.string.logcat_save_fail);
                }
                final String toastMessage = message;
                runOnUiThread(() -> {
                    if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
                        return;
                    }
                    LogcatUtils.toast(this, toastMessage);
                });
            }).start();
        } else if (v == mLevelLayout) {
            new ChooseWindow(this)
                    .setList(ARRAY_LOG_LEVEL)
                    .setListener(position -> {
                        switch (position) {
                            case 0:
                                setLogLevel(LogLevel.VERBOSE);
                                break;
                            case 1:
                                setLogLevel(LogLevel.DEBUG);
                                break;
                            case 2:
                                setLogLevel(LogLevel.INFO);
                                break;
                            case 3:
                                setLogLevel(LogLevel.WARN);
                                break;
                            case 4:
                                setLogLevel(LogLevel.ERROR);
                                break;
                            default:
                                break;
                        }
                    })
                    .show();
        } else if (v == mIconView) {
            String keyword = mSearchView.getText().toString();
            if (keyword.isEmpty()) {
                showSearchKeyword();
            } else {
                mSearchView.setText("");
            }
        } else if (v == mClearView) {
            LogcatManager.clear();
            mPendingLogs.clear();
            mAdapter.clearData();
        } else if (v == mHideView) {
            onBackPressed();
        } else if (v == mDownView) {
            // 滚动到列表最底部
            mLinearLayoutManager.scrollToPosition(mAdapter.getItemCount() - 1);
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (isChecked) {
            LogcatUtils.toast(this, R.string.logcat_capture_pause);
            LogcatManager.pause();
            stopLogDrain();
            mPauseLogFlag = true;
        } else {
            LogcatManager.resume();
            startLogDrain();
            mPauseLogFlag = false;
        }
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        // default implementation ignored
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        // default implementation ignored
    }

    @Override
    public void afterTextChanged(Editable s) {
        mSearchView.removeCallbacks(mSearchRunnable);
        mSearchView.postDelayed(mSearchRunnable, 500);

        mSearchView.removeCallbacks(mSearchKeywordRunnable);
        mSearchView.postDelayed(mSearchKeywordRunnable, 3000);
    }

    @Override
    public void onItemClick(LogcatInfo info, int position) {
        mAdapter.onItemClick(position);
    }

    @Override
    public boolean onItemLongClick(LogcatInfo info, final int position) {
        new ChooseWindow(this)
                .setList(R.string.logcat_options_copy, R.string.logcat_options_share, R.string.logcat_options_delete, R.string.logcat_options_shield)
                .setListener(location -> {
                    switch (location) {
                        case 0:
                            copyLog(position);
                            break;
                        case 1:
                            shareLog(position);
                            break;
                        case 2:
                            mAdapter.removeItem(position);
                            break;
                        case 3:
                            addFilter(mAdapter.getItem(position).getTag());
                            break;
                        default:
                            break;
                    }
                })
                .show();
        return true;
    }

    private void copyLog(int position) {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText("log", mAdapter.getItem(position).getContent()));
            LogcatUtils.toast(LogcatActivity.this, R.string.logcat_copy_succeed);
        } else {
            LogcatUtils.toast(LogcatActivity.this, R.string.logcat_copy_fail);
        }
    }

    private void shareLog(int position) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, mAdapter.getItem(position).getContent());
        startActivity(Intent.createChooser(intent, getResources().getString(R.string.logcat_options_share)));
    }

    private void setLogLevel(String level) {
        if (level.equalsIgnoreCase(mLogLevel)) {
            refreshLogLevelLayout();
            return;
        }

        mLogLevel = level;
        mAdapter.setLogLevel(level);
        LogcatConfig.setLogLevelConfig(level);
        afterTextChanged(mSearchView.getText());
        refreshLogLevelLayout();
    }

    private void setLogLevelAndAbove(boolean andAbove) {
        mLogLevelAndAbove = andAbove;
        mAdapter.setLogLevelAndAbove(andAbove);
        LogcatConfig.setLogLevelModeConfig(andAbove);
        refreshLogLevelLayout();
    }

    private void refreshLogLevelLayout() {
        String[] arrayLogLevel;
        if (LogcatUtils.isPortrait(this)) {
            arrayLogLevel = ARRAY_LOG_LEVEL_PORTRAIT;
        } else {
            arrayLogLevel = ARRAY_LOG_LEVEL;
        }
        int index = LogLevel.getLevelIndex(mLogLevel);
        if (index < 0) {
            return;
        }
        mLevelView.setText(mLogLevelAndAbove ? arrayLogLevel[index] + "+" : arrayLogLevel[index]);
    }

    /** 批量消费待显示日志（主线程，仅在有积压时被安排） */
    private final Runnable mDrainRunnable = new Runnable() {
        @Override
        public void run() {
            // 先清标记再取队列：期间入队的日志会自行安排下一轮，不会漏
            mDrainScheduled.set(0);
            if (!mLogDrainEnabled
                    || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
                return;
            }
            List<LogcatInfo> batch = new ArrayList<>();
            LogcatInfo info;
            while ((info = mPendingLogs.poll()) != null) {
                batch.add(info);
            }
            // 只保留最后上限条：超出部分交给 adapter 也会被裁掉，避免积压落后和做无用功
            if (batch.size() > LogcatAdapter.LOG_MAX_COUNT) {
                batch = batch.subList(batch.size() - LogcatAdapter.LOG_MAX_COUNT, batch.size());
            }
            if (!batch.isEmpty()) {
                mAdapter.addItems(batch);
            }
        }
    };

    private void startLogDrain() {
        mLogDrainEnabled = true;
        mDrainScheduled.set(1);
        mMainHandler.removeCallbacks(mDrainRunnable);
        mMainHandler.post(mDrainRunnable);
    }

    private void stopLogDrain() {
        mLogDrainEnabled = false;
        mMainHandler.removeCallbacks(mDrainRunnable);
        mDrainScheduled.set(0);
    }

    /**
     * 初始化 Tag 过滤器
     */
    private void initLogFilter() {
        try {
            for (String tag : LogcatUtils.readTagFilter(this)) {
                if (tag != null && !tag.trim().isEmpty()) {
                    mTagFilter.add(tag.trim());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            LogcatUtils.toast(this, R.string.logcat_read_config_fail);
        }

        String[] list = getResources().getStringArray(R.array.logcat_filter_list);
        for (String tag : list) {
            if (tag == null || tag.trim().isEmpty()) {
                continue;
            }
            mTagFilter.add(tag.trim());
        }
    }

    /**
     * 添加过滤的 TAG
     */
    private void addFilter(String tag) {
        // Tag 末尾常带空格，统一 trim 后存储，与 onReceiveLog 的比较口径一致
        tag = tag.trim();
        if (tag.isEmpty()) {
            return;
        }
        if (mTagFilter.contains(tag)) {
            return;
        }
        mTagFilter.add(tag);
        // 清掉队列中该 Tag 的积压日志，避免下一轮消费时复活
        // 不用 removeIf：Collection.removeIf 需要 API 24，minSdk 16 会 NoSuchMethodError
        for (Iterator<LogcatInfo> it = mPendingLogs.iterator(); it.hasNext(); ) {
            if (it.next().getTag().trim().equals(tag)) {
                it.remove();
            }
        }

        try {
            List<String> newTagFilter = new ArrayList<>(mTagFilter);
            newTagFilter.removeAll(Arrays.asList(getResources().getStringArray(R.array.logcat_filter_list)));

            File file = LogcatUtils.writeTagFilter(this, newTagFilter);
            LogcatUtils.toast(this, getResources().getString(R.string.logcat_shield_succeed) + file.getPath());

            // 从完整列表和显示列表中删除关于这个 Tag 的日志，取消筛选后也不会复活
            mAdapter.removeTag(tag);

        } catch (IOException e) {
            e.printStackTrace();
            LogcatUtils.toast(this, R.string.logcat_shield_fail);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // 清除输入焦点
        mSearchView.clearFocus();
        // 移动到上一个任务栈
        moveTaskToBack(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mPauseLogFlag) {
            return;
        }
        LogcatManager.resume();
        startLogDrain();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mPauseLogFlag) {
            return;
        }
        LogcatManager.pause();
        stopLogDrain();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LogcatManager.destroy();
        stopLogDrain();
        mPendingLogs.clear();
        mSearchView.removeCallbacks(mSearchRunnable);
        mSearchView.removeCallbacks(mSearchKeywordRunnable);
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshLogLevelLayout();
        if (mAdapter == null) {
            return;
        }
        mAdapter.notifyDataSetChanged();
    }

    private void showSearchKeyword() {
        if (mSearchKeyword.isEmpty()) {
            return;
        }
        new ChooseWindow(this)
                .setList(mSearchKeyword)
                .setListener(position -> {
                    mSearchView.setText(mSearchKeyword.get(position));
                    mSearchView.setSelection(mSearchView.getText().toString().length());
                })
                .show();
    }

    @SuppressWarnings("deprecation")
    private void refreshLayout() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            // 沉浸式状态栏只有 Android 4.4 才有的
            return;
        }

        Window window = getWindow();
        if (window == null) {
            return;
        }

        // 沉浸式状态栏
        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);

        mBarView.setPadding(0, 0, 0, 0);
        mRootView.setPadding(0, 0, 0, 0);

        if (LogcatUtils.isPortrait(this)) {
            // 在竖屏的状态下显示状态栏和导航栏
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // 实现状态栏图标和文字颜色为亮色
                window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams params = window.getAttributes();
                // 会让屏幕到延伸刘海区域中
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                window.setAttributes(params);
            }

            mBarView.setPadding(0, LogcatUtils.getStatusBarHeight(this), 0, 0);
            return;
        }

        // 在横屏的状态下隐藏状态栏和导航栏
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        mBarView.setPadding((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, getResources().getDisplayMetrics()), 0,
            (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, getResources().getDisplayMetrics()), 0);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams params = window.getAttributes();
            // 不让屏幕内容到延伸刘海区域中，会留出一片黑色区域
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
            window.setAttributes(params);
            return;
        }

        if (LogcatUtils.isActivityReverse(this)) {
            mRootView.setPadding(0, 0, LogcatUtils.getStatusBarHeight(this), 0);
        } else {
            mRootView.setPadding(LogcatUtils.getStatusBarHeight(this), 0, 0, 0);
        }
    }

    /**
     * 搜索关键字任务
     */
    private final Runnable mSearchRunnable = () -> {
        String keyword = mSearchView.getText().toString();
        LogcatConfig.setSearchKeyConfig(keyword);
        mAdapter.setKeyword(keyword);
        mLinearLayoutManager.scrollToPosition(mAdapter.getItemCount() - 1);

        if (!keyword.isEmpty()) {
            mIconView.setVisibility(View.VISIBLE);
            mIconView.setImageResource(R.drawable.logcat_ic_empty);
            return;
        }

        if (!mSearchKeyword.isEmpty()) {
            mIconView.setVisibility(View.VISIBLE);
            mIconView.setImageResource(R.drawable.logcat_ic_history);
        } else {
            mIconView.setVisibility(View.GONE);
        }
    };

    /**
     * 搜索关键字记录任务
     */
    private final Runnable mSearchKeywordRunnable = () -> {
        String keyword = mSearchView.getText().toString();
        if (keyword.isEmpty()) {
            return;
        }
        if (mSearchKeyword.contains(keyword)) {
            return;
        }
        mSearchKeyword.add(0, keyword);
        if (mSearchKeyword.size() > SEARCH_KEYWORD_MAX_COUNT) {
            mSearchKeyword.remove(mSearchKeyword.size() - 1);
        }
    };
}