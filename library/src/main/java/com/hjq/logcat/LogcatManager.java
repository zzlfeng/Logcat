package com.hjq.logcat;

import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 *    author : Android 轮子哥
 *    github : https://github.com/getActivity/Logcat
 *    time   : 2020/01/24
 *    desc   : 日志管理类
 *    doc    : https://developer.android.google.cn/studio/command-line/logcat
 */
@SuppressWarnings("AlibabaAvoidManuallyCreateThread")
final class LogcatManager {

    /** 日志捕捉监听对象 */
    private static Callback sCallback;
    /** 是否能获取 uid */
    private static volatile boolean sCanObtainUid;
    /** 日志捕捉标记 */
    private static volatile boolean FLAG_WORK;
    private static volatile LogRunnable sLogRunnable;
    /** 备用存放集合（暂停期间缓存，超限丢弃最旧） */
    private static final ArrayDeque<LogcatInfo> LOG_BACKUP = new ArrayDeque<>();
    /** 备用集合上限 */
    private static final int LOG_BACKUP_MAX_COUNT = 1000;
    private static final long LOG_REPLAY_TIMEOUT_MILLIS = 5000;
    private static String sLogTimeWatermark;
    private static String sLastDeliveredLine;

    public static void setCanObtainUid(boolean canObtainUid) {
        sCanObtainUid = canObtainUid;
    }

    public static void setCallback(Callback callback) {
        synchronized (LOG_BACKUP) {
            sCallback = callback;
        }
    }

    /**
     * 开始捕捉
     */
    static void start() {
        LogRunnable previous;
        LogRunnable current;
        synchronized (LOG_BACKUP) {
            previous = sLogRunnable;
            current = new LogRunnable(sLastDeliveredLine, sLogTimeWatermark);
            sLogRunnable = current;
            FLAG_WORK = true;
        }
        if (previous != null) {
            previous.destroyProcess();
        }
        new Thread(current).start();
    }

    /**
     * 继续捕捉
     */
    static void resume() {
        synchronized (LOG_BACKUP) {
            final Callback callback = sCallback;
            // 锁内排空并投递，保证备份日志先于新日志；回调仅过滤+入队，锁内投递廉价无阻塞
            while (!LOG_BACKUP.isEmpty()) {
                List<LogcatInfo> backup = new ArrayList<>(LOG_BACKUP);
                LOG_BACKUP.clear();
                if (callback != null) {
                    for (LogcatInfo info : backup) {
                        callback.onReceiveLog(info);
                    }
                }
            }
            FLAG_WORK = true;
        }
    }

    /**
     * 暂停捕捉
     */
    static void pause() {
        synchronized (LOG_BACKUP) {
            FLAG_WORK = false;
        }
    }

    /**
     * 停止捕捉
     */
    static void destroy() {
        LogRunnable current;
        synchronized (LOG_BACKUP) {
            FLAG_WORK = false;
            sCallback = null;
            current = sLogRunnable;
            sLogRunnable = null;
        }
        if (current != null) {
            current.destroyProcess();
        }
    }

    /**
     * 清空日志
     */
    static void clear() {
        try {
            new ProcessBuilder("logcat", "-c").start();
        } catch (IOException ignored) {}
    }

    /**
     * 创建 Logcat 日志缓冲区
     */
    private static BufferedReader createLogcatBufferedReader(LogRunnable owner) throws IOException {
        // Process process = Runtime.getRuntime().exec("/system/bin/logcat -b " + "main -P '\"/" + android.os.Process.myPid() + " 10708\"'");
        // Process process = Runtime.getRuntime().exec("/system/bin/logcat -b all -v uid");
        // Process process = Runtime.getRuntime().exec("logcat -b all -v uid");
        // Process process = new ProcessBuilder("logcat", "-v", "threadtime").start();
        // 为什么是而是获取所有的日志，而不用直接 logcat --uid '10471' 命令？这是因为用了这个命令会读取不到日志
        // 我在电脑端用这个命令是可以的，但是在手机用这个命令不行，应该是手机隐私问题，不允许其他应用指定某个应用的日志
        // 还有在手机上面执行 logcat -v uid 获取日志，在 Android 10 及以下机子 uid 会为空格（一般为四个空格，还不算前后两个空格）
        // 多种日志格式拼接：logcat -v format, --format='threadtime uid usec color' -d
        String command = "logcat -v " + (sCanObtainUid ? "uid" : "threadtime");
        java.lang.Process process = Runtime.getRuntime().exec(command);
        owner.mProcess.set(process);
        return new BufferedReader(new InputStreamReader(process.getInputStream()));
    }

    /**
     * 关闭流
     */
    private static void closeStream(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static class LogRunnable implements Runnable {

        private final AtomicReference<java.lang.Process> mProcess = new AtomicReference<>();
        private String mLastDeliveredLine;
        private String mLogTimeWatermark;
        private boolean mReplaying;

        LogRunnable(String lastDeliveredLine, String logTimeWatermark) {
            mLastDeliveredLine = lastDeliveredLine;
            mLogTimeWatermark = logTimeWatermark;
            mReplaying = lastDeliveredLine != null;
        }

        private boolean isCurrent() {
            return sLogRunnable == this;
        }

        private void destroyProcess() {
            java.lang.Process process = mProcess.getAndSet(null);
            if (process != null) {
                try {
                    process.destroy();
                } catch (Exception ignored) {}
            }
        }

        @Override
        public void run() {
            BufferedReader reader = null;
            long replayStartTime = 0;
            try {
                String line;
                while (isCurrent()) {
                    if (reader == null) {
                        try {
                            reader = createLogcatBufferedReader(this);
                            replayStartTime = SystemClock.elapsedRealtime();
                        } catch (IOException e) {
                            e.printStackTrace();
                            break;
                        }
                    }
                    if (!isCurrent()) {
                        break;
                    }
                    try {
                        line = reader.readLine();
                    } catch (IOException e) {
                        e.printStackTrace();
                        break;
                    }
                    if (!isCurrent()) {
                        break;
                    }
                    if (line == null) {
                        // 正常情况讲，line 是不会为空的，因为没有新的日志前提下 reader.readLine() 会阻塞读取
                        // 但是在某些特殊机型（vivo iQOO 9 Pro Android 12）上面会出现，在没有新的日志前提下，会返回 null
                        // 并且等待一会儿再读取还不行，无论循环等待多次，因为原先的流里面已经没有东西了，要读取新的日志必须创建新的流
                        // 虽然这种方案不完美，但是已经我能想到的最好方案，最起码不会因为没有最新日志而停止读取了
                        // 同样的 Android 12 手机，我用小米 12 就没有出现这种情况，所以大家要怪只能怪厂商了
                        closeStream(reader);
                        reader = null;
                        destroyProcess();
                        boolean wasReplaying = mReplaying;
                        mReplaying = mLastDeliveredLine != null;
                        SystemClock.sleep(5000);
                        // 连续 EOF 重建不能每轮重置超时，导致重读永远无法结束
                        if (wasReplaying
                                && SystemClock.elapsedRealtime() - replayStartTime >= LOG_REPLAY_TIMEOUT_MILLIS) {
                            mReplaying = false;
                        }
                        continue;
                    }

                    if (LogcatInfo.IGNORED_LOG.contains(line)) {
                        continue;
                    }

                    LogcatInfo info = LogcatInfo.create(line, sCanObtainUid);
                    if (info == null) {
                        continue;
                    }

                    // 仅重读阶段过滤：正常读日志不过滤，避免乱序时间戳和时钟回拨静默丢日志
                    if (mReplaying) {
                        if (line.equals(mLastDeliveredLine)) {
                            // 追上次最后一行，重读结束；该行本身已显示过
                            mReplaying = false;
                            continue;
                        }
                        // 标记缺失且时间回退时，也必须有有限的重读退出边界
                        if (info.getTime().compareTo(mLogTimeWatermark) > 0
                                || SystemClock.elapsedRealtime() - replayStartTime >= LOG_REPLAY_TIMEOUT_MILLIS) {
                            mReplaying = false;
                        } else {
                            continue;
                        }
                    }

                    mLastDeliveredLine = line;
                    // 水位跟随最后一行，不能保留跨年或回拨前的最大时间
                    mLogTimeWatermark = info.getTime();
                    synchronized (LOG_BACKUP) {
                        if (!isCurrent()) {
                            break;
                        }
                        sLastDeliveredLine = mLastDeliveredLine;
                        sLogTimeWatermark = mLogTimeWatermark;
                        if (!FLAG_WORK) {
                            LOG_BACKUP.addLast(info);
                            if (LOG_BACKUP.size() > LOG_BACKUP_MAX_COUNT) {
                                LOG_BACKUP.removeFirst();
                            }
                            continue;
                        }
                        final Callback callback = sCallback;
                        if (callback == null) {
                            break;
                        }
                        // 与销毁及切换会话互斥，不能在失去归属后向新页面投递
                        callback.onReceiveLog(info);
                    }
                }
            } finally {
                closeStream(reader);
                destroyProcess();
            }
        }
    }

    interface Callback {

        /**
         * 收到日志
         */
        void onReceiveLog(LogcatInfo info);
    }
}