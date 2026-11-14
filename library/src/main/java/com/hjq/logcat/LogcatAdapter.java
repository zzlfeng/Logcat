package com.hjq.logcat;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.URLSpan;
import android.text.style.UnderlineSpan;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *    author : Android 轮子哥
 *    github : https://github.com/getActivity/Logcat
 *    time   : 2020/01/24
 *    desc   : 日志列表适配器
 */
@SuppressLint("NotifyDataSetChanged")
final class LogcatAdapter extends RecyclerView.Adapter<LogcatAdapter.ViewHolder> {

    /** 最大日志数量限制（避免日志过多导致出现 OOM） */
    static final int LOG_MAX_COUNT = 1000;

    /** 达到阈值时删除的日志数量 */
    private static final int LOG_REMOVE_COUNT = LOG_MAX_COUNT / 3;

    /** 报错代码行数正则表达式 */
    private static final Pattern CODE_LINES_REGEX = Pattern.compile("\\([\\w.$-]+:\\d+\\)");

    /** 链接正则表达式 */
    private static final Pattern LINK_REGEX = Pattern.compile("https?://[^\\x{4e00}-\\x{9fa5}\\n\\r\\s]{3,}");

    private final SparseBooleanArray mExpandSet = new SparseBooleanArray();
    private final SparseIntArray mScrollXSet = new SparseIntArray();

    private final List<LogcatInfo> mAllData = new ArrayList<>();
    private List<LogcatInfo> mShowData = mAllData;

    private final Context mContext;

    private String mKeyword = "";
    private Pattern mKeywordPattern;
    private String mLogLevel = LogLevel.VERBOSE;
    /** 等级过滤是否包含更高等级的日志 */
    private boolean mLogLevelAndAbove;
    private boolean mLogAutoMergePrint = true;

    private RecyclerView mRecyclerView;

    /** 条目点击监听器 */
    @Nullable
    private OnItemClickListener mItemClickListener;
    /** 条目长按监听器 */
    @Nullable
    private OnItemLongClickListener mItemLongClickListener;

    public LogcatAdapter(Context context) {
        mContext = context;
        Boolean metaBooleanData = LogcatUtils.getMetaBooleanData(
            context, LogcatContract.META_DATA_LOGCAT_AUTO_MERGE_PRINT);
        if (metaBooleanData != null) {
            mLogAutoMergePrint = metaBooleanData;
        }
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.logcat_item_logcat, parent, false));
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        holder.onBindView(getItem(position), position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getItemCount() {
        return mShowData.size();
    }

    public LogcatInfo getItem(int position) {
        return mShowData.get(position);
    }

    List<LogcatInfo> getShowData() {
        return mShowData;
    }

    /**
     * 批量添加数据（仅主线程调用）
     */
    void addItems(List<LogcatInfo> batch) {
        int oldSize = mShowData.size();
        boolean oldTailMerged = false;

        for (LogcatInfo info : batch) {
            if (mLogAutoMergePrint && !mShowData.isEmpty()) {
                LogcatInfo lastInfo = getItem(mShowData.size() - 1);
                if (info.getLevel().equals(lastInfo.getLevel()) &&
                        info.getTag().equals(lastInfo.getTag())) {
                    // 追加日志
                    lastInfo.addLogContent(info.getContent());
                    // 只有本批尚未插入新条目时，合并目标才是需要单独通知的老条目
                    if (mShowData.size() == oldSize) {
                        oldTailMerged = true;
                    }
                    continue;
                }
            }

            if (isConform(info)) {
                mShowData.add(info);
            }

            if (mShowData != mAllData) {
                mAllData.add(info);
            }
        }

        boolean trimmed = false;
        List<LogcatInfo> removedItems = new ArrayList<>();
        if (mShowData.size() > LOG_MAX_COUNT) {
            trimData(mShowData, removedItems);
            trimmed = true;
        }
        if (mShowData != mAllData) {
            trimData(mAllData, removedItems);
        }
        if (!removedItems.isEmpty()) {
            // 仅当两个列表都不再持有时才清理状态，避免误清仍在显示条目的展开状态
            Set<LogcatInfo> retained = new HashSet<>(mAllData);
            retained.addAll(mShowData);
            for (LogcatInfo removedInfo : removedItems) {
                if (!retained.contains(removedInfo)) {
                    mExpandSet.delete(removedInfo.hashCode());
                    mScrollXSet.delete(removedInfo.hashCode());
                }
            }
        }

        if (trimmed) {
            // 更新所有条目，这样日志的条目索引文案才能更新
            notifyDataSetChanged();
            if (mRecyclerView != null) {
                // 列表滚动到最后一条位置上面
                mRecyclerView.scrollToPosition(getItemCount() - 1);
            }
        } else {
            // 先通知老条目变更，再通知新条目插入，符合 RecyclerView 通知顺序契约
            if (oldTailMerged) {
                notifyItemChanged(oldSize - 1);
            }
            int insertedCount = mShowData.size() - oldSize;
            if (insertedCount > 0) {
                notifyItemRangeInserted(oldSize, insertedCount);
            }
        }
    }

    /**
     * 裁剪最旧的日志，被裁条目收集到 removedOut 供调用方统一清理状态
     */
    private void trimData(List<LogcatInfo> data, List<LogcatInfo> removedOut) {
        while (data.size() > LOG_MAX_COUNT) {
            List<LogcatInfo> removed = data.subList(0, LOG_REMOVE_COUNT);
            removedOut.addAll(removed);
            removed.clear();
        }
    }

    @Override
    public void onAttachedToRecyclerView(RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        mRecyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        mRecyclerView = null;
    }

    /**
     * 按对象删除某条数据（避免异步弹窗期间列表裁剪导致索引失效或越界）
     */
    void removeItem(LogcatInfo info) {
        if (info == null) {
            return;
        }
        int index = mShowData.indexOf(info);
        if (index != -1) {
            mShowData.remove(index);
            notifyItemRemoved(index);
        }
        if (mAllData != mShowData) {
            mAllData.remove(info);
        }
        mExpandSet.delete(info.hashCode());
        mScrollXSet.delete(info.hashCode());
    }

    /**
     * 从完整列表和显示列表中删除指定 Tag 的日志（屏蔽用）
     */
    void removeTag(String tag) {
        for (int i = mAllData.size() - 1; i >= 0; i--) {
            LogcatInfo info = mAllData.get(i);
            if (info.getTag().trim().equals(tag)) {
                mExpandSet.delete(info.hashCode());
                mScrollXSet.delete(info.hashCode());
                mAllData.remove(i);
            }
        }
        if (mShowData != mAllData) {
            for (int i = mShowData.size() - 1; i >= 0; i--) {
                if (mShowData.get(i).getTag().trim().equals(tag)) {
                    mShowData.remove(i);
                }
            }
        }
        notifyDataSetChanged();
    }

    /**
     * 清空当前数据
     */
    void clearData() {
        mExpandSet.clear();
        mScrollXSet.clear();
        mAllData.clear();
        if (mAllData != mShowData) {
            mShowData.clear();
        }
        notifyDataSetChanged();
    }

    void setKeyword(String keyword) {
        mKeyword = keyword;
        mKeywordPattern = keyword.isEmpty() ? null :
                Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        filterData();
        notifyDataSetChanged();
    }

    void setLogLevel(String logLevel) {
        mLogLevel = logLevel;
        filterData();
        notifyDataSetChanged();
    }

    void setLogLevelAndAbove(boolean andAbove) {
        mLogLevelAndAbove = andAbove;
        filterData();
        notifyDataSetChanged();
    }

    void onItemClick(int position) {
        LogcatInfo info = getItem(position);
        boolean expand = mExpandSet.get(info.hashCode());
        mExpandSet.put(info.hashCode(), !expand);
        notifyItemChanged(position);
    }

    private void filterData() {
        // V 及以上模式等同于不过滤；仅当前等级模式下 V 仍需过滤
        if (TextUtils.isEmpty(mKeyword) && mLogLevelAndAbove && LogLevel.VERBOSE.equals(mLogLevel)) {
            mShowData = mAllData;
            return;
        }

        if (mShowData == mAllData) {
            mShowData = new ArrayList<>();
        }
        mShowData.clear();

        for (LogcatInfo info : mAllData) {
            if (isConform(info)) {
                mShowData.add(info);
            }
        }
    }

    private boolean isConform(LogcatInfo info) {
        return (mKeywordPattern == null || mKeywordPattern.matcher(info.toString(mContext)).find()) &&
                isLevelConform(info);
    }

    private boolean isLevelConform(LogcatInfo info) {
        if (mLogLevelAndAbove) {
            // V 及以上即全部日志，未知等级也保留（沿用旧行为）
            return LogLevel.VERBOSE.equals(mLogLevel) ||
                    LogLevel.getLevelSeverity(info.getLevel()) >= LogLevel.getLevelSeverity(mLogLevel);
        }
        return info.getLevel().equals(mLogLevel);
    }

    /**
     * 设置 RecyclerView 条目点击监听
     */
    void setOnItemClickListener(@Nullable OnItemClickListener listener) {
        mItemClickListener = listener;
    }

    /**
     * 设置 RecyclerView 条目长按监听
     */
    void setOnItemLongClickListener(@Nullable OnItemLongClickListener listener) {
        mItemLongClickListener = listener;
    }

    public class ViewHolder extends RecyclerView.ViewHolder implements
            View.OnClickListener, View.OnLongClickListener {

        private final TextView mContentView;
        private final TextView mIndexView;
        private final View mLineView;

        @SuppressLint("ClickableViewAccessibility")
        public ViewHolder(View itemView) {
            super(itemView);

            mContentView = itemView.findViewById(R.id.tv_log_content);
            mIndexView = itemView.findViewById(R.id.tv_log_index);
            mLineView = itemView.findViewById(R.id.v_log_line);

            itemView.setOnClickListener(this);
            itemView.setOnLongClickListener(this);
        }

        @Override
        public void onClick(View v) {
            if (mItemClickListener == null) {
                return;
            }
            int layoutPosition = getLayoutPosition();
            mItemClickListener.onItemClick(getItem(layoutPosition), layoutPosition);
        }

        @Override
        public boolean onLongClick(View v) {
            if (mItemLongClickListener == null) {
                return false;
            }
            int layoutPosition = getLayoutPosition();
            return mItemLongClickListener.onItemLongClick(getItem(layoutPosition), layoutPosition);
        }

        @SuppressWarnings("deprecation")
        private void onBindView(LogcatInfo info, int position) {
            String content = info.toString(mContext);
            SpannableString spannable = new SpannableString(content);
            if (mKeywordPattern != null) {
                // 直接在原文上匹配，下标不会因大小写转换长度变化而错位
                Matcher keywordMatcher = mKeywordPattern.matcher(content);
                while (keywordMatcher.find()) {
                    int start = keywordMatcher.start();
                    int end = keywordMatcher.end();
                    spannable.setSpan(new BackgroundColorSpan(
                            ContextCompat.getColor(mContext, R.color.logcat_filter_background_color)),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spannable.setSpan(new ForegroundColorSpan(
                            ContextCompat.getColor(mContext, R.color.logcat_filter_foreground_color)),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } else {
                // 高亮代码行数
                Matcher matcher = CODE_LINES_REGEX.matcher(content);
                if (spannable.length() > 0) {
                    while (matcher.find()) {
                        // 不包含左括号（
                        int start = matcher.start() + "(".length();
                        // 不包含右括号 ）
                        int end = matcher.end() - ")".length();
                        // 设置前景
                        spannable.setSpan(new ForegroundColorSpan(
                                ContextCompat.getColor(mContext, R.color.logcat_code_lines_highlight_color)),
                                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        // 设置下划线
                        spannable.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
            }

            // 高亮 H5 链接
            Matcher matcher = LINK_REGEX.matcher(content);
            while (matcher.find()) {
                // 不包含左括号（
                int start = matcher.start();
                // 不包含右括号 ）
                int end = matcher.end();
                URLSpan urlSpan = new URLSpan(String.valueOf(spannable.subSequence(start, end)));
                spannable.setSpan(urlSpan, start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                // 为什么不设置点击事件，因为这样会导致外层 ScrollView 触摸事件和 itemView 点击事件无效
                //mContentView.setMovementMethod(LinkMovementMethod.getInstance());
            }

            mContentView.setText(spannable);

            final int resourceId;
            switch (info.getLevel()) {
                case LogLevel.DEBUG:
                    resourceId = R.color.logcat_level_debug_color;
                    break;
                case LogLevel.INFO:
                    resourceId = R.color.logcat_level_info_color;
                    break;
                case LogLevel.WARN:
                    resourceId = R.color.logcat_level_warn_color;
                    break;
                case LogLevel.ERROR:
                    resourceId = R.color.logcat_level_error_color;
                    break;
                case LogLevel.VERBOSE:
                    resourceId = R.color.logcat_level_verbose_color;
                    break;
                default:
                    resourceId = R.color.logcat_level_other_color;
                    break;
            }
            final int textColor;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                textColor = mContentView.getResources().getColor(resourceId, mContentView.getContext().getTheme());
            } else {
                textColor = mContentView.getResources().getColor(resourceId);
            }
            mContentView.setTextColor(textColor);
            mLineView.setVisibility((position == 0) ? View.INVISIBLE : View.VISIBLE);

            int maxLine;
            if (LogcatUtils.isPortrait(itemView.getContext())) {
                maxLine = 6;
            } else {
                maxLine = 4;
            }

            int lineCount = 1;
            for (int i = 0; i < content.length(); i++) {
                if (content.charAt(i) == '\n') {
                    lineCount++;
                }
            }
            if (lineCount - maxLine > 1) {

                if (mExpandSet.get(info.hashCode())) {
                    if (mContentView.getMaxLines() != Integer.MAX_VALUE) {
                        mContentView.setMaxLines(Integer.MAX_VALUE);
                    }
                    mIndexView.setText(String.valueOf(position + 1));
                } else {
                    if (mContentView.getMaxLines() != maxLine) {
                        mContentView.setMaxLines(maxLine);
                    }
                    mIndexView.setText(LogLevel.VERBOSE);
                }
            } else {
                mContentView.setMaxLines(Integer.MAX_VALUE);
                mIndexView.setText(String.valueOf(position + 1));
            }
        }
    }

    /**
     * RecyclerView 条目点击监听类
     */
    interface OnItemClickListener {

        /**
         * 当 RecyclerView 某个条目被点击时回调
         */
        void onItemClick(LogcatInfo info, int position);
    }

    /**
     * RecyclerView 条目长按监听类
     */
    interface OnItemLongClickListener {

        /**
         * 当 RecyclerView 某个条目被长按时回调
         */
        boolean onItemLongClick(LogcatInfo info, int position);
    }
}