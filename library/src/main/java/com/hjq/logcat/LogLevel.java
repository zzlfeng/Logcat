package com.hjq.logcat;

/**
 *    author : Android 轮子哥
 *    github : https://github.com/getActivity/Logcat
 *    time   : 2021/12/08
 *    desc   : 日志输出等级
 */
final class LogLevel {

    /** 全部日志 */
    static final String VERBOSE = "V";

    /** 调试日志 */
    static final String DEBUG = "D";

    /** 信息日志 */
    static final String INFO = "I";

    /** 警告日志 */
    static final String WARN = "W";

    /** 错误日志 */
    static final String ERROR = "E";

    /**
     * 获取日志等级的严重度序号（V=0 D=1 I=2 W=3 E=4），未知等级返回 -1
     */
    static int getLevelIndex(String level) {
        switch (level) {
            case VERBOSE:
                return 0;
            case DEBUG:
                return 1;
            case INFO:
                return 2;
            case WARN:
                return 3;
            case ERROR:
                return 4;
            default:
                return -1;
        }
    }
}