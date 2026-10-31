package com.ark.recruit;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 启动页：三步权限引导。
 *
 * 视觉按 DESIGN.md 4.9：
 *   深色底 → 小字距标题 ARKNIGHTS / RECRUIT → 26sp 主标题 → 琥珀短线
 *   → 三步卡片（序号方框 + 标题 + 状态点 + 说明）
 *   → 主行动按钮（未就绪时 40% 透明）
 */
public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_NOTIF = 1002;
    static final String PREF = "ark_prefs";
    static final String KEY_RC = "proj_result_code";

    /**
     * 默认下载地址：Cloudflare R2（国内下得动）。
     *
     * ★ 发新版后不用改这里 ★ —— R2 上固定有一个 latest.apk，每次发布覆盖它。
     *
     * 为什么默认不用 GitHub：实测国内直连 github.com 只有约 17 KB/s
     * （15MB 要下 15 分钟以上，还经常断），而同一个包从 R2 拉是约 286 KB/s，
     * 快 17 倍。GitHub 发布页仍然保留，作为境外用户和归档入口。
     */
    private static final String DOWNLOAD_URL =
            "https://pub-f89e4319d7ba400db492a2608dc872ae.r2.dev/latest.apk";

    /** 备用入口：GitHub 发布页（境外快、且能看到版本说明与源码） */
    private static final String RELEASE_PAGE_URL =
            "https://github.com/TttMc/arknights-recruit-helper/releases/latest";

    private int lastResultCode = 0;
    private Intent lastResultData = null;

    private StepCard step1, step2, step3;
    private TextView btnStart;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.VOID);
        LinearLayout root = Ui.col(this);
        root.setPadding(Ui.dp(this, 24), Ui.dp(this, 44), Ui.dp(this, 24), Ui.dp(this, 32));
        scroll.addView(root);

        // ---------- 抬头 ----------
        TextView kicker = Ui.tv(this, "ARKNIGHTS / RECRUIT", 9.5f, Ui.AMBER);
        kicker.setTypeface(Ui.med());
        kicker.setLetterSpacing(0.24f);
        root.addView(kicker);

        TextView h1 = Ui.tv(this, "公招助手", 26f, Ui.TEXT);
        h1.setTypeface(Ui.cond());
        h1.setPadding(0, Ui.dp(this, 6), 0, 0);
        root.addView(h1);

        View rule = new View(this);
        rule.setBackgroundColor(Ui.AMBER);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 32), Math.max(1, Ui.dp(this, 2)));
        rLp.topMargin = Ui.dp(this, 10);
        root.addView(rule, rLp);

        TextView sub = Ui.tv(this, "点悬浮球 → 自动截屏 → 直接出组合", 13f, Ui.TEXT2);
        sub.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 26));
        root.addView(sub);

        // ---------- 准备步骤 ----------
        TextView label = Ui.sectionLabel(this, "准备步骤");
        label.setPadding(0, 0, 0, Ui.dp(this, 8));
        root.addView(label);

        step1 = new StepCard("01", "悬浮窗权限", "允许在其他应用上层显示",
                new View.OnClickListener() {
                    public void onClick(View v) {
                        if (Build.VERSION.SDK_INT >= 23
                                && !Settings.canDrawOverlays(MainActivity.this)) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                        } else {
                            toast("悬浮窗权限已开启");
                        }
                    }
                });
        root.addView(step1.view);

        step2 = new StepCard("02", "截屏权限", "系统弹窗必须点「立即开始」",
                new View.OnClickListener() {
                    public void onClick(View v) {
                        MediaProjectionManager mgr = (MediaProjectionManager)
                                getSystemService(MEDIA_PROJECTION_SERVICE);
                        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE);
                    }
                });
        root.addView(step2.view);

        step3 = new StepCard("03", "启动悬浮球", "启动后 App 会自动退到后台",
                new View.OnClickListener() {
                    public void onClick(View v) { doStart(); }
                });
        root.addView(step3.view);

        // ---------- 行动按钮 ----------
        btnStart = Ui.tv(this, "启动悬浮球", 15f, Ui.VOID);
        btnStart.setTypeface(Ui.med());
        btnStart.setGravity(Gravity.CENTER);
        btnStart.setBackground(getResources().getDrawable(R.drawable.bg_btn_primary, null));
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 52));
        bLp.topMargin = Ui.dp(this, 28);
        btnStart.setLayoutParams(bLp);
        btnStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doStart(); }
        });
        root.addView(btnStart);

        TextView btnStop = Ui.tv(this, "停止悬浮球", 13f, Ui.TEXT2);
        btnStop.setTypeface(Ui.med());
        btnStop.setGravity(Gravity.CENTER);
        btnStop.setBackground(getResources().getDrawable(R.drawable.bg_btn_outline, null));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 44));
        sLp.topMargin = Ui.dp(this, 10);
        btnStop.setLayoutParams(sLp);
        btnStop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // ★ v2.9.1（交叉审查）★
                // 原来只发 ACTION_HIDE：服务没在跑时 startService 会先
                // 把它创建起来（onCreate 里 startForeground 闪一下通知）再 stopSelf。
                //
                // ★ v2.9.2 ★ 现在直接 stopService 就够了，不再补一次 startService：
                // 那一次调用在 Android 12+ 的后台启动限制下可能抛异常，而且
                // 「先停止再发指令」本身是自相矛盾的 —— 服务没跑时 stopService
                // 是安全的空操作。
                try {
                    stopService(new Intent(MainActivity.this, OverlayService.class));
                } catch (Throwable ignored) {}
                toast("已停止");
                refresh();
            }
        });
        root.addView(btnStop);

        // ---------- 使用说明 ----------
        TextView help = Ui.tv(this,
                "使用说明\n"
                + "1. 打开游戏，进「公开招募」的新建招募页\n"
                + "2. 点悬浮球\n"
                + "3. 面板弹出，看推荐组合与时长\n"
                + "\n常见问题\n"
                + "· 点球提示「无法截屏」→ 回来重新做 02，弹窗点「立即开始」\n"
                + "· 识别不到标签 → 面板里可直接手输标签后点「重算」\n"
                + "· 手机重启后必须重新做 02 和 03（Android 限制）\n"
                + "· 小米/OPPO/vivo 需额外允许「后台弹出界面」和「自启动」\n"
                + "\n识图全在内存，不存任何截图，不联网", 11.5f, Ui.TEXT3);
        help.setPadding(0, Ui.dp(this, 28), 0, 0);
        root.addView(help);

        // ---------- 版本 / 获取最新版 ----------
        root.addView(buildUpdateCard());

        setContentView(scroll);

        // ★ v2.9.2 修复（交叉审查）★
        // 原来每次 onCreate 都无条件 requestPermissions，用户点了「不允许」
        // 下次进来又问一遍。现在只在确实未授权时问一次；即使被拒也不影响功能
        // （前台服务照样运行，只是通知进通知管理器而非抽屉），所以不提示失败。
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIF);
            }
        }
    }

    // ------------------------------------------------------------ 版本 / 更新
    /**
     * 「版本 + 获取最新版」小卡片。
     *
     * ★ 设计约束：本 App 不声明 INTERNET 权限 ★
     *
     * 所以这里**不自己联网检查版本**，也不自己下载 APK —— 那样做就必须把
     * INTERNET 加回 manifest，与「截屏与识别全在内存、不上传任何数据」的
     * 承诺冲突。链接交给系统浏览器打开：真正发请求的是浏览器，本 App
     * 只是递过去一个 URL，因此零权限。
     *
     * 取舍说明：代价是「有没有新版」要用户自己看一眼。对这类纯本地小工具
     * 来说，比常驻一个联网能力更划算。
     */
    private View buildUpdateCard() {
        LinearLayout card = Ui.col(this);
        card.setBackground(new BevelDrawable(Ui.PANEL, Ui.LINE,
                Ui.dp(this, 1), Ui.dp(this, 8), Ui.dp(this, 2)));
        card.setPadding(Ui.dp(this, 12), Ui.dp(this, 12),
                Ui.dp(this, 12), Ui.dp(this, 12));
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = Ui.dp(this, 20);
        card.setLayoutParams(cLp);

        LinearLayout headRow = Ui.row(this);
        TextView label = Ui.sectionLabel(this, "版本");
        headRow.addView(label);
        headRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView ver = Ui.mono(this, versionName() + " · " + versionCode(), 11f, Ui.TEXT3);
        headRow.addView(ver);
        card.addView(headRow);

        TextView tip = Ui.tv(this,
                "本机版本如上。点下方按钮用浏览器打开下载地址，App 自身不联网。",
                10.5f, Ui.TEXT3);
        tip.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        card.addView(tip);

        LinearLayout btnRow = Ui.row(this);

        TextView open = Ui.tv(this, "下载最新版", 13f, Ui.VOID);
        open.setTypeface(Ui.med());
        open.setGravity(Gravity.CENTER);
        open.setBackground(Ui.round(Ui.AMBER, 0, 0, Ui.dp(this, 6)));
        open.setPadding(0, Ui.dp(this, 11), 0, Ui.dp(this, 11));
        open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openUrl(DOWNLOAD_URL); }
        });
        btnRow.addView(open, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView copy = Ui.tv(this, "复制地址", 12f, Ui.TEXT2);
        copy.setTypeface(Ui.med());
        copy.setGravity(Gravity.CENTER);
        copy.setBackground(Ui.round(0x00000000, Ui.dp(this, 1), Ui.LINE2, Ui.dp(this, 6)));
        copy.setPadding(Ui.dp(this, 12), Ui.dp(this, 11), Ui.dp(this, 12), Ui.dp(this, 11));
        LinearLayout.LayoutParams cpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cpLp.leftMargin = Ui.dp(this, 8);
        copy.setLayoutParams(cpLp);
        // 复制按钮是给「打不开浏览器」准备的退路：地址拿去电脑或别处下载
        copy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { copyUrl(DOWNLOAD_URL); }
        });
        btnRow.addView(copy);

        card.addView(btnRow);

        TextView url = Ui.tv(this, DOWNLOAD_URL, 9.5f, Ui.TEXT3);
        url.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(url);

        // 备用入口：GitHub 发布页。放成一行小字链接而不是第二个按钮，
        // 免得主次不分 —— 默认路径是 R2。
        TextView alt = Ui.tv(this, "GitHub 发布页（版本说明 / 源码）›", 10.5f, Ui.TEXT2);
        alt.setPadding(0, Ui.dp(this, 8), 0, 0);
        alt.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openUrl(RELEASE_PAGE_URL); }
        });
        card.addView(alt);

        return card;
    }

    /**
     * 用浏览器打开 URL。
     *
     * ★ 这里不需要 INTERNET 权限 ★ —— 发请求的是浏览器进程，本应用只递了个字符串。
     * ActivityNotFoundException 必须处理：精简 ROM 可能没有浏览器，
     * 此时退回复制地址，不能让人卡在点不动的按钮上。
     */
    private void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            copyUrl(url);
            toast("没有可用浏览器，地址已复制");
        }
    }

    private void copyUrl(String url) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData
                    .newPlainText("公招助手下载地址", url));
            toast("地址已复制，可用浏览器打开");
        } catch (Throwable t) {
            toast("复制失败：" + url);
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private String versionCode() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            return "#" + (Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
        } catch (Throwable t) {
            return "#?";
        }
    }

    // ------------------------------------------------------------ 步骤卡
    /** 一步一张卡：序号方框 + 标题 + 状态点 + 说明 */
    private class StepCard {
        final View view;
        final TextView num;
        final TextView dot;
        final TextView dotText;

        StepCard(String no, String title, String desc, View.OnClickListener click) {
            LinearLayout card = Ui.col(MainActivity.this);
            card.setBackground(new BevelDrawable(Ui.PANEL, Ui.LINE,
                    Ui.dp(MainActivity.this, 1), Ui.dp(MainActivity.this, 8),
                    Ui.dp(MainActivity.this, 2)));
            card.setPadding(Ui.dp(MainActivity.this, 12), Ui.dp(MainActivity.this, 12),
                    Ui.dp(MainActivity.this, 12), Ui.dp(MainActivity.this, 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(MainActivity.this, 8);
            card.setLayoutParams(lp);
            card.setOnClickListener(click);

            LinearLayout row = Ui.row(MainActivity.this);

            num = Ui.display(MainActivity.this, no, 15f, Ui.TEXT3);
            num.setGravity(Gravity.CENTER);
            num.setBackground(Ui.round(0x00000000, Ui.dp(MainActivity.this, 1),
                    Ui.LINE2, Ui.dp(MainActivity.this, 2)));
            LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                    Ui.dp(MainActivity.this, 40), Ui.dp(MainActivity.this, 40));
            nLp.rightMargin = Ui.dp(MainActivity.this, 12);
            row.addView(num, nLp);

            LinearLayout mid = Ui.col(MainActivity.this);
            TextView t = Ui.tv(MainActivity.this, title, 14f, Ui.TEXT);
            t.setTypeface(Ui.med());
            mid.addView(t);
            TextView d = Ui.tv(MainActivity.this, desc, 11.5f, Ui.TEXT3);
            d.setPadding(0, Ui.dp(MainActivity.this, 3), 0, 0);
            mid.addView(d);
            row.addView(mid, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout right = Ui.row(MainActivity.this);
            dot = Ui.tv(MainActivity.this, "●", 11f, Ui.TEXT3);
            dotText = Ui.tv(MainActivity.this, "未开始", 10.5f, Ui.TEXT3);
            dotText.setPadding(Ui.dp(MainActivity.this, 4), 0, 0, 0);
            right.addView(dot);
            right.addView(dotText);
            row.addView(right);

            card.addView(row);
            this.view = card;
        }

        void setState(boolean done, boolean pending, String text) {
            int c = done ? Ui.GREEN : (pending ? Ui.RED : Ui.TEXT3);
            dot.setTextColor(c);
            dotText.setText(text);
            dotText.setTextColor(c);
            num.setTextColor(done ? Ui.AMBER : Ui.TEXT3);
            num.setBackground(Ui.round(0x00000000, Ui.dp(MainActivity.this, 1),
                    done ? Ui.AMBER : Ui.LINE2, Ui.dp(MainActivity.this, 2)));
        }
    }

    // ------------------------------------------------------------ 逻辑
    private void doStart() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            toast("请先开启悬浮窗权限（01）");
            return;
        }
        if (lastResultData == null) {
            toast("请先授予截屏权限（02）");
            return;
        }
        saveAuth(lastResultCode, lastResultData);

        Intent i = new Intent(this, OverlayService.class);
        i.setAction(OverlayService.ACTION_SHOW);
        i.putExtra("resultCode", lastResultCode);
        i.putExtra("resultData", lastResultData);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);

        toast("悬浮球已启动，回到游戏点球即可");
        moveTaskToBack(true);
    }

    private void saveAuth(int code, Intent data) {
        try {
            SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
            sp.edit().putInt(KEY_RC, code).putBoolean("has_auth", true).apply();
        } catch (Throwable ignored) {}
    }

    private void refresh() {
        boolean overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        boolean cap = lastResultData != null;
        boolean running = isServiceRunning();

        step1.setState(overlay, !overlay, overlay ? "已开启" : "未开启");
        step2.setState(cap, !cap, cap ? "已授予" : "未授予");
        step3.setState(running, false, running ? "运行中" : "未启动");

        boolean ready = overlay && cap;
        btnStart.setAlpha(ready ? 1f : 0.40f);
        btnStart.setEnabled(ready);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CAPTURE) {
            if (res == RESULT_OK && data != null) {
                lastResultCode = res;
                lastResultData = data;
                saveAuth(res, data);
                toast("截屏权限已授予，可以启动了");
            } else {
                toast("你取消了截屏授权");
            }
            refresh();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private boolean isServiceRunning() {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            for (android.app.ActivityManager.RunningServiceInfo s
                    : am.getRunningServices(Integer.MAX_VALUE)) {
                if (OverlayService.class.getName().equals(s.service.getClassName())) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
