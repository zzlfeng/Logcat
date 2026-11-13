package com.hjq.logcat;

import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    /** 日志进程对象 */
    private static volatile java.lang.Process sProcess;
    /** 备用存放集合（暂停期间缓存，超限丢弃最旧） */
    private static final ArrayDeque<LogcatInfo> LOG_BACKUP = new ArrayDeque<>();
    /** 备用集合上限 */
    private static final int LOG_BACKUP_MAX_COUNT = 1000;
    /** 已投递日志的时间水位（MM-DD hh:mm:ss.mmm），重建流后据此跳过重读的旧日志 */
    private static String sLogTimeWatermark;
    /** 与水位同一毫秒内的日志行，用于同时间戳去重（仅 reader 线程访问） */
    private static final Set<String> LOG_SAME_TIME = new HashSet<>();

    public static void setCanObtainUid(boolean canObtainUid) {
        sCanObtainUid = canObtainUid;
    }

    public static void setCallback(Callback callback) {
        sCallback = callback;
    }

    /**
     * 开始捕捉
     */
    static void start() {
        FLAG_WORK = true;
        new Thread(new LogRunnable()).start();
    }

    /**
     * 继续捕捉
     */
    static void resume() {
        final Callback callback = sCallback;
        synchronized (LOG_BACKUP) {
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
        FLAG_WORK = false;
    }

    /**
     * 停止捕捉
     */
    static void destroy() {
        FLAG_WORK = false;
        // 把监听对象置空，不然会导致内存泄漏
        sCallback = null;
        java.lang.Process process = sProcess;
        sProcess = null;
        if (process != null) {
            try {
                process.destroy();
            } catch (Exception ignored) {}
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
    private static BufferedReader createLogcatBufferedReader() throws IOException {
        // Process process = Runtime.getRuntime().exec("/system/bin/logcat -b " + "main -P '\"/" + android.os.Process.myPid() + " 10708\"'");
        // Process process = Runtime.getRuntime().exec("/system/bin/logcat -b all -v uid");
        // Process process = Runtime.getRuntime().exec("logcat -b all -v uid");
        // Process process = new ProcessBuilder("logcat", "-v", "threadtime").start();
        // 为什么是而是获取所有的日志，而不用直接 logcat --uid '10471' 命令？这是因为用了这个命令会读取不到日志
        // 我在电脑端用这个命令是可以的，但是在手机用这个命令不行，应该是手机隐私问题，不允许其他应用指定某个应用的日志
        // 还有在手机上面执行 logcat -v uid 获取日志，在 Android 10 及以下机子 uid 会为空格（一般为四个空格，还不算前后两个空格）
        // 多种日志格式拼接：logcat -v format, --format='threadtime uid usec color' -d
        String command = "logcat -v " + (sCanObtainUid ? "uid" : "threadtime");
        sProcess = Runtime.getRuntime().exec(command);
        return new BufferedReader(new InputStreamReader(sProcess.getInputStream()));
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

        @Override
        public void run() {
            BufferedReader reader = null;
            try {
                String line;
                while (true) {
                    if (reader == null) {
                        try {
                            reader = createLogcatBufferedReader();
                        } catch (IOException e) {
                            e.printStackTrace();
                            break;
                        }
                    }
                    try {
                        line = reader.readLine();
                    } catch (IOException e) {
                        e.printStackTrace();
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
                        if (sProcess != null) {
                            try {
                                sProcess.destroy();
                            } catch (Exception ignored) {}
                            sProcess = null;
                        }
                        SystemClock.sleep(5000);
                        continue;
                    }

                    if (LogcatInfo.IGNORED_LOG.contains(line)) {
                        continue;
                    }

                    LogcatInfo info = LogcatInfo.create(line, sCanObtainUid);
                    if (info == null) {
                        continue;
                    }

                    // 某些机型读不到新日志时会重建流并从头重读整个缓冲区，早于时间水位的行一律跳过；
                    // 不用有限缓存去重：缓存窗口小于重读量时，淘汰顺序与重读顺序同向会导致全量重复
                    String time = info.getTime();
                    if (sLogTimeWatermark == null) {
                        sLogTimeWatermark = time;
                        LOG_SAME_TIME.add(line);
                    } else {
                        int cmp = time.compareTo(sLogTimeWatermark);
                        if (cmp > 0) {
                            sLogTimeWatermark = time;
                            LOG_SAME_TIME.clear();
                            LOG_SAME_TIME.add(line);
                        } else if (cmp == 0) {
                            if (!LOG_SAME_TIME.add(line)) {
                                continue;
                            }
                        } else if (time.substring(0, 2).compareTo(sLogTimeWatermark.substring(0, 2)) < 0) {
                            // 月份回退视为跨年，重置水位
                            sLogTimeWatermark = time;
                            LOG_SAME_TIME.clear();
                            LOG_SAME_TIME.add(line);
                        } else {
                            continue;
                        }
                    }
                    // 检查与入备在同一把锁内，与 resume 的排空+置位互斥，避免丢日志或插队
                    synchronized (LOG_BACKUP) {
                        if (!FLAG_WORK) {
                            LOG_BACKUP.addLast(info);
                            if (LOG_BACKUP.size() > LOG_BACKUP_MAX_COUNT) {
                                LOG_BACKUP.removeFirst();
                            }
                            continue;
                        }
                    }

                    final Callback callback = sCallback;
                    if (callback == null) {
                        break;
                    }
                    callback.onReceiveLog(info);
                }
            } finally {
                closeStream(reader);
                if (sProcess != null) {
                    try {
                        sProcess.destroy();
                    } catch (Exception ignored) {}
                    sProcess = null;
                }
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