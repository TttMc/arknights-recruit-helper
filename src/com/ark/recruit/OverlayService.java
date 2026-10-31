package com.ark.recruit;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 悬浮球 + 结果面板 + 截屏识别。
 *
 * ============================================================
 * 截图的生命周期（明确保证不落盘）
 * ============================================================
 *  ImageReader 缓冲区
 *        ↓ acquireLatestImage()
 *      Bitmap（仅内存）
 *        ↓ OCR
 *      bmp.recycle()          ← 用完立刻释放
 *
 *  全程没有任何 FileOutputStream / MediaStore / compress() 调用，
 *  截图不会出现在相册、文件管理器或 App 私有目录里。
 *  内存峰值约 30MB（ImageReader 双缓冲 + 一份 Bitmap），释放后归零。
 *
 *  当前版本连「可选保留」都没有实现 —— 一次都不落盘。
 *  原因：贴主明确不想要截图留存，留着反而是隐患。
 *
 * ============================================================
 * v1.2 修复
 * ============================================================
 *  1) 面板关不掉：原来 EditText 自动抢焦点 → 软键盘弹出盖住底部按钮。
 *     现在改为「全屏半透明遮罩 + 点遮罩即关」，标题栏右上角有固定 ×，
 *     并拦截返回键关闭。EditText 默认不抢焦点。
 *  2) toBitmap 的 rowStride 处理有误：部分机型行尾有 padding，
 *     原来逐行 put 可能少拷字节导致画面错位。现在按行精确裁剪。
 *  3) 截图改为显式回收并可选「只保留一张」排查文件。
 */
public class OverlayService extends android.app.Service {

    public static final String ACTION_SHOW = "com.ark.recruit.SHOW";
    public static final String ACTION_HIDE = "com.ark.recruit.HIDE";

    static final String PREF = "ark_prefs";
    static final String KEY_KEEP_SHOT = "keep_last_shot";

    private WindowManager wm;
    private View ball;
    private BallView myBall;
    private View panel;              // 全屏遮罩容器（含内容）
    private WindowManager.LayoutParams ballLp;

    // ★ v2.9.1（交叉审查）★ 这些字段全部跨线程读写：
    //   bg 线程：setupProjection 写 projection / imageReader / capW / capH / virtualDisplay
    //   UI 线程：onBallClick 读 projection / imageReader，读 lastError 拼错误面板
    // 原来都是普通字段，没有 happens-before 关系，理论上可能「通道刚建好却报
    // 『无法截屏』」，或读到上一次的 lastError 给出误导提示。统一加 volatile。
    private volatile MediaProjection projection;
    private volatile VirtualDisplay virtualDisplay;
    private volatile ImageReader imageReader;
    private volatile int capW, capH;

    private int resultCode;
    private Intent resultData;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread bgThread;
    private Handler bg;

    /**
     * 识别中标志。
     *
     * ★ v2.9：加 volatile ★
     * 现在是在 UI 线程写（onBallClick）、在后台线程的 finally 里清（bg.post 的 run 里），
     * 也是被 UI 线程读（onBallClick 的防重入判断）。普通 boolean 没有跨线程可见性保证，
     * JIT 甚至可能把它提到循环外，导致「识别完点球没反应」或「重复触发识别」这类
     * 靠运气才不出现的 bug。
     */
    private volatile boolean busy;
    private volatile String lastError;

    /**
     * Service 已销毁。
     *
     * ★ v2.9.1（交叉审查）★
     * onDestroy 里的 quitSafely() 不会中断正在跑的 doCaptureAndAnalyze ——
     * 手里的 OCR 最长还会跑 20 秒，跑完还会 ui.post 出一个面板；此时
     * wm.addView 挂出去的窗口没人负责关闭（Service 已经没了），
     * 屏幕上会留下一个点不掉的遮罩。所以所有 ui.post 前都要检查这个标志。
     */
    private volatile boolean destroyed;

    /**
     * 截屏通道的长边上限（像素）。
     * ★ v2.9.2 ★ 见 setupProjection 里的说明：1920 对识别完全够用，
     * 但能把 4K 机型的抓帧内存从约 128MB 峰值压到约 32MB。
     */
    private static final int CAP_MAX_LONG_SIDE = 1920;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        bgThread = new HandlerThread("ark-worker");
        bgThread.start();
        bg = new Handler(bgThread.getLooper());

        try {
            Recruit.load(getResources().getStringArray(R.array.operators));
        } catch (Throwable e) {
            lastError = "干员数据加载失败: " + e;
        }

        startForegroundNotice();
        addBall();
    }

    private void startForegroundNotice() {
        String ch = "overlay";
        if (Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(ch) == null) {
                nm.createNotificationChannel(new android.app.NotificationChannel(
                        ch, "悬浮窗", android.app.NotificationManager.IMPORTANCE_LOW));
            }
            android.app.Notification.Builder b = new android.app.Notification.Builder(this, ch)
                    .setContentTitle("公招助手运行中")
                    .setContentText("点悬浮球识别标签")
                    .setSmallIcon(android.R.drawable.ic_menu_search);
            // ★ v2.9.2 修复（交叉审查）★
            // targetSdk 34 下必须显式声明前台服务类型，否则启动时抛
            // MissingForegroundServiceTypeException（Android 14 行为变更）。
            // manifest 里已声明 foregroundServiceType="mediaProjection"。
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1001, b.build(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(1001, b.build());
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (ACTION_HIDE.equals(intent.getAction())) {
                stopSelf();
                return START_NOT_STICKY;
            }
            if (intent.hasExtra("resultCode")) {
                resultCode = intent.getIntExtra("resultCode", 0);
                resultData = intent.getParcelableExtra("resultData");
                final Intent data = resultData;
                final int code = resultCode;
                bg.post(new Runnable() {
                    public void run() { setupProjection(code, data); }
                });
            }
        }
        // ★ v2.9.2 修复（交叉审查）★
        // 原来是 START_STICKY：Service 被系统回收后，系统会用**空 Intent** 重启它，
        // onCreate 照样加悬浮球，但 resultData 没有持久化 —— 球复活了却永远截不了屏，
        // 用户只能点球看一句「抓不到画面」，反而比没有球更困惑。
        // 截屏授权也不可能跨进程重启保留（令牌是一次性的），所以不复活才是正解：
        // 想用时回 App 重新走 02、03 即可。
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------ 截屏通道
    // ------------------------------------------------------------ 截屏通道（支持横竖屏）
    /**
     * 建立截屏通道。
     *
     * ★ 横屏支持（2026-10-30 修）★
     * 游戏是横屏的，但授权时手机是竖屏。原来只在初始化拿一次尺寸，
     * 进横屏后 VirtualDisplay 尺寸和实际画面不一致，结果就是拉伸/黑边，
     * OCR 必然认不出标签。
     *
     * 现在做三件事：
     *  1. 每次抓帧前用 getRealMetrics 重新读当前尺寸（旋转后该值会变）
     *  2. 尺寸变化时重建 VirtualDisplay（MediaProjection 允许 resize，
     *     但部分机型不支持，重建最稳）
     *  3. ImageReader 重建后，用新尺寸解析帧
     */
    // ------------------------------------------------------------ 截屏通道（恢复 v1.8 原逻辑）
    /**
     * 建立截屏通道。
     *
     * 这里刻意保持最朴素的做法：授权时建一次 VirtualDisplay，
     * 之后一直复用。之前加过「每次抓帧前比对屏幕尺寸、不一致就重建」
     * 的逻辑（v2.2），结果反而导致截屏失败 —— 原因是：
     *  · 部分机型 createVirtualDisplay 第二次调用直接返回 null；
     *  · 重建和抓帧之间有竞态，ImageReader 被关掉时正好在 acquire。
     * 所以回退到「建一次、稳定复用」，横屏靠 ImageReader 自身跟随
     * 屏幕旋转（MediaProjection 默认行为）。
     */
    private void setupProjection(int code, Intent data) {
        if (data == null) {
            lastError = "没有截屏授权数据";
            return;
        }
        try {
            // ★ v2.9.1 修复（交叉审查）★
            //
            // 原来无条件先 releaseProjection() 再 getMediaProjection(code, data)。
            // 但 Android 14 起 MediaProjection 授权令牌是「一次性」的：同一个
            // resultData 只能成功换取一次 projection，第二次会抛 SecurityException；
            // 而我们又把上一次正常工作的通道先释放掉了 —— 结果就是用户在 App 里
            // 多点一次「③ 启动悬浮球」，本来好的截屏能力被永久拆掉，只能重开 App
            // 重新走 createScreenCaptureIntent 授权。
            //
            // 现在：通道已经健在就直接复用，不重建、不重新换取令牌。
            if (projection != null && imageReader != null) {
                return;
            }
            releaseProjection();

            MediaProjectionManager mgr =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mgr.getMediaProjection(code, data);

            // Android 14 起必须先注册回调，否则 createVirtualDisplay 抛异常
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    lastError = "截屏授权已失效，请回 App 重新授权";
                    releaseProjection();
                }
            }, bg);

            DisplayMetrics dm = new DisplayMetrics();
            Display d = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
            d.getRealMetrics(dm);

            // ★ v2.9.2 修复（交叉审查）：截屏通道限长边 ★
            //
            // 原来按屏幕原始分辨率建 ImageReader。4K 机型（3840×2160）下
            // ImageReader 双缓冲本身就要 3840×2160×4×2 ≈ 66MB，抓帧时
            // toBitmap 再 allocate 一份 w*h*4 ≈ 33MB，加上临时行缓冲和 OCR 图，
            // 峰值逼近 128MB —— 中端机上是一次 OOM 事故。
            //
            // 而识别用不到那么高分辨率：findDarkBars 反正要把短边降到约 320，
            // 按钮再按 2x 裁出来给 ML Kit。长边 1920 已经完全够用（实测按钮文字
            // 在这一档仍清晰可辨），内存直接降到约 1/4。
            //
            // 关键点：宽高比保持原样（cropToCurrentScreen 靠比例判断是否需要裁），
            // dpi 同步缩放，否则 VirtualDisplay 会把内容按错误密度渲染。
            int realW = dm.widthPixels, realH = dm.heightPixels;
            float scale = 1f;
            int longSide = Math.max(realW, realH);
            if (longSide > CAP_MAX_LONG_SIDE) {
                scale = CAP_MAX_LONG_SIDE / (float) longSide;
            }
            capW = Math.max(2, Math.round(realW * scale));
            capH = Math.max(2, Math.round(realH * scale));
            int dpi = Math.max(60, Math.round(dm.densityDpi * scale));

            imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
            virtualDisplay = projection.createVirtualDisplay(
                    "ark-cap", capW, capH, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, bg);

            if (virtualDisplay == null) lastError = "createVirtualDisplay 返回 null";
        } catch (Throwable t) {
            lastError = "建立截屏通道失败: " + describe(t);
            releaseProjection();
        }
    }

    /** 横屏判定（仅用于面板排版，不影响截屏） */
    private boolean isLandscape() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        return m.widthPixels > m.heightPixels;
    }

    /**
     * 面板卡片宽度。
     *
     * ★ v2.9（原审查第 7 条）★
     * 原来手机/平板一律 dp(400)（横屏 dp(520)）。在 3008×1880 的 KPad2 上
     * 那只有屏宽的 13~17%，正文被挤成一条竖线，完全没法读。
     *
     * 现在：手机仍按固定 dp 上限（保持紧凑），平板按「屏幕短边比例」放宽，
     * 同时留至少 6% 的边距，保证遮罩始终点得到（点遮罩是关闭面板的方式之一）。
     */
    private int panelWidth(int screenW, int screenH) {
        int shortSide = Math.min(screenW, screenH);
        int baseMax = screenW > screenH ? dp(520) : dp(400);
        // 平板/大屏：短边（dp）超过 700 时说明是平板，按比例放宽
        int maxW = Math.max(baseMax, (int) (shortSide * 0.72f));
        maxW = Math.min(maxW, (int) (screenW * 0.94f));
        int margin = screenW > screenH ? dp(48) : dp(32);
        return Math.min(screenW - margin, maxW);
    }
    private void releaseProjection() {
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Throwable ignored) {}
        virtualDisplay = null;
        try { if (imageReader != null) imageReader.close(); } catch (Throwable ignored) {}
        imageReader = null;
        try { if (projection != null) projection.stop(); } catch (Throwable ignored) {}
        projection = null;
    }

    // ------------------------------------------------------------ 悬浮球
    private void addBall() {
        int size = dp(60);
        BallView v = new BallView(this);
        v.setContentDescription("识别招募标签");
        myBall = v;

        ballLp = new WindowManager.LayoutParams(
                size, size,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        ballLp.gravity = Gravity.TOP | Gravity.START;
        ballLp.x = dp(10);
        ballLp.y = dp(180);

        v.setOnTouchListener(new BallTouch());
        ball = v;
        try {
            wm.addView(ball, ballLp);
        } catch (Throwable t) {
            lastError = "悬浮球添加失败: " + describe(t);
        }
    }

    private class BallTouch implements View.OnTouchListener {
        private int startX, startY;
        private float downX, downY;
        private boolean moved;

        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = ballLp.x; startY = ballLp.y;
                    downX = e.getRawX(); downY = e.getRawY();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = (int) (e.getRawX() - downX);
                    int dy = (int) (e.getRawY() - downY);
                    if (Math.abs(dx) > dp(10) || Math.abs(dy) > dp(10)) moved = true;
                    if (moved) {
                        // ★ v2.9.1（交叉审查）★
                        // 悬浮球带 FLAG_LAYOUT_NO_LIMITS，不钳制就能拖到屏幕外，
                        // 之后彻底找不回来（只能去 App 里停服务重启）。
                        // 按「屏幕尺寸 - 球尺寸」钳制在可见范围内。
                        DisplayMetrics dm = getResources().getDisplayMetrics();
                        int maxX = Math.max(0, dm.widthPixels - dp(60));
                        int maxY = Math.max(0, dm.heightPixels - dp(60));
                        int nx = startX + dx;
                        int ny = startY + dy;
                        if (nx < 0) nx = 0; else if (nx > maxX) nx = maxX;
                        if (ny < 0) ny = 0; else if (ny > maxY) ny = maxY;
                        ballLp.x = nx;
                        ballLp.y = ny;
                        try { wm.updateViewLayout(v, ballLp); } catch (Throwable ignored) {}
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!moved) { v.performClick(); onBallClick(); }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
            }
            return false;
        }
    }

    private void onBallClick() {
        if (busy) { toast("正在识别…"); return; }

        if (projection == null || imageReader == null) {
            String reason = lastError != null ? lastError
                    : "截屏通道未建立。请回 App 点「② 授予截屏权限」再「③ 启动悬浮球」。";
            showPanel("无法截屏\n\n" + reason
                    + "\n\n解决：\n1. 打开「公招助手」\n"
                    + "2. 点「② 授予截屏权限」，系统弹窗点「立即开始」\n"
                    + "3. 点「③ 启动悬浮球」\n"
                    + "4. 回游戏再点球", null, false);
            return;
        }

        busy = true;
        setBallState(BallView.BUSY);
        // ★ v2.9.1 关键修复（交叉审查发现的最严重问题）★
        //
        // 原来这里先 showSkeletonPanel() 再 bg.post 去抓帧 —— 但骨架面板是一个
        // 全屏 0x9E000000（62% 不透明）的遮罩 + 居中卡片，MediaProjection 截的是
        // 「合成后的屏幕」，所以截到的画面自带一层黑纱，中间的卡片还可能正好盖住
        // 标签区。公招页是静态画面，acquireLatestImage 拿到的第一帧必然是带遮罩的，
        // 等于每次识别都在给自己上难度。
        //
        // 现在改成：先抓帧（此时屏幕上只有游戏），抓到了再显示骨架。
        // 代价是点击后约 50~100ms 没有视觉反馈，用球本身的 BUSY 状态顶上。
        bg.post(new Runnable() {
            public void run() {
                try {
                    doCaptureAndAnalyze();
                } catch (Throwable t) {
                    final String msg = describe(t);
                    ui.post(new Runnable() {
                        public void run() {
                            setBallState(BallView.ERROR);
                            showPanel("识别失败\n\n" + msg, null, false);
                        }
                    });
                } finally {
                    busy = false;
                }
            }
        });
    }

    // ------------------------------------------------------------ 抓帧
    private void doCaptureAndAnalyze() {
        Bitmap bmp = grabFrame();
        if (bmp == null) {
            final String msg = "抓不到画面"
                    + (lastError != null ? ("\n" + lastError) : "")
                    + "\n\n可能：授权失效 / 屏幕被其他录屏占用。";
            ui.post(new Runnable() {
                public void run() {
                    setBallState(BallView.ERROR);
                    showPanel(msg, null, false);
                }
            });
            return;
        }

        // 帧已经到手，这时才把骨架遮罩加上（不会再被截进画面）
        ui.post(new Runnable() {
            public void run() { showSkeletonPanel(); }
        });

        TagFinder.Result r = null;
        String failMsg = null;
        try {
            r = TagFinder.find(bmp, this);
        } catch (Throwable t) {
            failMsg = "OCR 失败\n\n" + describe(t);
        } finally {
            // ★ v2.9.2 修复（交叉审查，最严重的一处）★
            //
            // 原来这里无条件 recycle。但 TagFinder 的整屏兜底会走到
            // Bitmap.createBitmap(src, 0, 0, W, H) —— 全区域创建**直接返回 src 本身**，
            // 也就是说交给 ML Kit 的就是这张 bmp。一旦等待超时，回调仍在后台线程
            // 读它，此时 recycle 就是 native 层 use-after-free：随机 SIGSEGV、
            // Java 层没有堆栈、日志查不到原因。
            //
            // TagFinder 内部已经保证「超时就绝不 recycle」，现在把同样的判断带到外层：
            // srcMayBeInUse 为真时放弃回收，交给 GC（一张图的代价远小于崩溃）。
            if (r == null || !r.srcMayBeInUse) {
                if (!bmp.isRecycled()) bmp.recycle();
            }
        }

        if (failMsg != null) {
            final String m = failMsg;
            ui.post(new Runnable() {
                public void run() {
                    setBallState(BallView.ERROR);
                    showPanel(m, null, false);
                }
            });
            return;
        }

        final List<String> tags = r.tags;
        final int slotCount = r.slots;
        // ★ v2.9：把「识别可能不可靠」的原因带进面板 ★
        // 原来 TagFinder.Result.note / uncertain 算出来却没人读，等于白算：
        // UI 改版导致定位选错组时，面板照样给出「第1选 XX」，用户无从察觉。
        final String warning = buildWarning(r);
        ui.post(new Runnable() {
            public void run() {
                // ★ v2.9.2 修复（交叉审查）★
                // 识别最长 20 秒，这期间用户完全可能停掉服务（点宿主 App 的
                // 「停止悬浮球」或直接后退）。onDestroy 的 removeCallbacksAndMessages
                // 只能丢掉**已排队**的任务，后台线程跑完后新 post 的这个 runnable
                // 不受影响 —— 它会在已销毁的 Service 上 wm.addView，挂出一个
                // 点不掉的全屏遮罩，用户只能杀进程。
                // showPanel 那条路径早有这个检查，这里漏了。
                if (destroyed) return;
                if (tags.isEmpty()) {
                    setBallState(BallView.ERROR);
                    showPanel("没识别到标签\n\n"
                            + "找到 " + slotCount + " 个候选按钮，但没读出合法标签。\n"
                            + (warning != null ? ("\n" + warning + "\n") : "")
                            + "\n可能原因：\n"
                            + "· 截图不是公招「新建招募」页（要能看到 5 个标签那一页）\n"
                            + "· 标签按钮太小 / 画面太暗\n"
                            + "· 屏幕旋转后画面方向不对（横屏进游戏再点一次球）\n\n"
                            + "点「重截」再试，或关掉后回游戏重开。", null, false);
                    return;
                }
                String[] arr = tags.toArray(new String[0]);
                setBallState(BallView.OK);
                List<Recruit.Combo> combos = Recruit.analyze(arr, Recruit.MAX_TAGS);
                showResultPanel(combos, tags, warning);
            }
        });
    }

    /**
     * 把 TagFinder 的判定翻译成一句给用户看的提示。
     *
     * 返回 null 表示「识别可信，无需提示」。
     * 三种情况要提示：
     *   · 标签区按钮数不是 5 —— 几何假设失效（UI 改版 / 分屏）
     *   · 读到但不满 5 个 —— 少一个标签会漏掉含该标签的组合，结论偏保守
     *   · 有底层 note（超预算 / OCR 异常）—— 说明这次没走完整流程
     */
    private static String buildWarning(TagFinder.Result r) {
        StringBuilder sb = new StringBuilder();
        if (r.note != null && r.note.length() > 0) sb.append(r.note);
        if (r.uncertain) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("标签数不足 5 个或定位可疑，结论可能偏保守/不准 —— 建议点「重截」再试一次。");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 从 ImageReader 抓一帧，最多重试约 1.2 秒 */
    private Bitmap grabFrame() {
        ImageReader reader = imageReader;
        if (reader == null) {
            lastError = "截屏通道未就绪";
            return null;
        }
        for (int i = 0; i < 12; i++) {
            if (destroyed) return null;
            Image img = null;
            try {
                img = reader.acquireLatestImage();
            } catch (Throwable ignored) {}
            if (img != null) {
                try {
                    // ★ v2.9.1（交叉审查）★
                    // 不能再用建 reader 时的 capW/capH：部分机型旋转后
                    // ImageReader 交上来的帧宽高是交换过的，硬套旧尺寸会让
                    // 整幅画面对角错位，OCR 必然全灭。以帧自带尺寸为准。
                    int iw = img.getWidth();
                    int ih = img.getHeight();
                    if (iw <= 0 || ih <= 0) { iw = capW; ih = capH; }
                    Bitmap raw = toBitmap(img, iw, ih);
                    // ★ v2.9.2 修复（交叉审查）★
                    // 原来这里直接 return cropToCurrentScreen(raw)：toBitmap 返回
                    // null（缓冲区不足 = 坏帧）时，cropToCurrentScreen(null) 内部
                    // NPE 被 catch 吞掉返回 null，于是整个函数当场返回 null ——
                    // 下面 12 次重试循环形同虚设，一帧坏掉就报「抓不到画面」。
                    // 现在 null 时继续下一帧。
                    if (raw != null) return cropToCurrentScreen(raw);
                } finally {
                    img.close();
                }
            }
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
        lastError = "连续 12 次都没拿到可用画面帧";
        return null;
    }

    /**
     * 按当前屏幕真实比例裁出有效画面。
     *
     * ★ 平板/横竖屏尺寸不匹配（2026-10-30）★
     *
     * VirtualDisplay 是在「授权时」按当时的屏幕尺寸建的
     * （K Pad 2 竖屏 3200×2136 之类）。之后横屏进游戏（2136×3200），
     * MediaProjection 会把横屏内容压缩/信箱化进那个竖屏画布，
     * 结果就是用户看到的「截图区域变得很小、识别不全」。
     *
     * 为什么不在尺寸变化时重建 VirtualDisplay：
     * 上一版这么干过，导致 createVirtualDisplay 二次调用返回 null
     * 或与 acquireLatestImage 竞态，直接截屏失败。得不偿失。
     *
     * 正确的轻量做法：照常抓帧，然后按「当前屏幕真实宽高比」从帧里
     * 裁出应该是游戏画面的那块。横屏时游戏画面通常纵向居中、
     * 上下有黑边，裁掉黑边即可恢复正确比例。
     */
    private Bitmap cropToCurrentScreen(Bitmap raw) {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            Display d = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
            d.getRealMetrics(dm);
            int wantW = dm.widthPixels, wantH = dm.heightPixels;
            int haveW = raw.getWidth(), haveH = raw.getHeight();

            if (wantW <= 0 || wantH <= 0 || haveW <= 0 || haveH <= 0) return raw;

            float wantRatio = wantW / (float) wantH;
            float haveRatio = haveW / (float) haveH;

            // 比例一致（误差 <2%）说明画面正常，不用裁
            if (Math.abs(wantRatio - haveRatio) / haveRatio < 0.02f) return raw;

            int cw = haveW, ch = haveH, cx = 0, cy = 0;
            if (wantRatio > haveRatio) {
                // 目标是更宽：保留全宽，纵向裁掉多余（上下黑边）
                ch = Math.round(haveW / wantRatio);
                if (ch > haveH) ch = haveH;
                cy = (haveH - ch) / 2;
            } else {
                // 目标是更高：保留全高，横向裁掉多余
                cw = Math.round(haveH * wantRatio);
                if (cw > haveW) cw = haveW;
                cx = (haveW - cw) / 2;
            }
            if (cw <= 16 || ch <= 16) return raw;
            if (cw == haveW && ch == haveH) return raw;

            Bitmap out = Bitmap.createBitmap(raw, cx, cy, cw, ch);
            if (out != raw) {
                try { raw.recycle(); } catch (Throwable ignored) {}
            }
            return out;
        } catch (Throwable t) {
            return raw;
        }
    }

    /**
     * Image → Bitmap。
     *
     * 关键：很多机型 rowStride > width * pixelStride（行尾有 padding），
     * 必须按「有效字节数 w*pixelStride」逐行精确搬运，否则画面会整体错位。
     */
    private Bitmap toBitmap(Image img, int w, int h) {
        Image.Plane p = img.getPlanes()[0];
        ByteBuffer buf = p.getBuffer();
        int rowStride = p.getRowStride();
        int pixStride = p.getPixelStride();
        int rowBytes = w * pixStride;

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);

        if (rowStride == rowBytes && pixStride == 4) {
            // 紧凑布局，一次拷完
            buf.rewind();
            bmp.copyPixelsFromBuffer(buf);
            return bmp;
        }

        // 非紧凑：逐行裁剪 padding
        byte[] row = new byte[rowStride];
        ByteBuffer dst = ByteBuffer.allocate(w * h * 4);
        for (int y = 0; y < h; y++) {
            int pos = y * rowStride;
            if (pos >= buf.limit()) break;
            buf.position(pos);
            int n = Math.min(rowStride, buf.remaining());
            if (n <= 0) break;
            buf.get(row, 0, n);

            if (pixStride == 4) {
                // 直接拷 rowBytes
                dst.put(row, 0, Math.min(rowBytes, n));
            } else {
                // 极少见：每像素 1~3 字节，按像素展开成 4 字节
                int count = Math.min(w, n / pixStride);
                for (int x = 0; x < count; x++) {
                    int off = x * pixStride;
                    int b = row[off] & 0xFF;
                    int gg = pixStride > 1 ? (row[off + 1] & 0xFF) : b;
                    int rr = pixStride > 2 ? (row[off + 2] & 0xFF) : b;
                    dst.put((byte) b).put((byte) gg).put((byte) rr).put((byte) 0xFF);
                }
            }
        }
        // ★ v2.9.1（交叉审查）★
        // 上面循环里的 break（缓冲区提前耗尽、帧尺寸不符）会让 dst 少于
        // w*h*4 字节，此时 copyPixelsFromBuffer 会抛
        // 「Buffer not large enough for pixels」，在 UI 上表现为一串裸堆栈。
        // 缺帧本该重试，不该硬拷贝：不足时直接返回 null，让 grabFrame 拿下一帧。
        if (dst.position() < w * h * 4) {
            try { bmp.recycle(); } catch (Throwable ignored) {}
            return null;
        }
        dst.rewind();
        bmp.copyPixelsFromBuffer(dst);
        return bmp;
    }

    // ------------------------------------------------------------ 面板
    private void showPanel(String text, List<String> parsedTags, boolean allowManual) {
        ui.post(new Runnable() {
            public void run() {
                if (destroyed) return;
                buildPanel(text, parsedTags, allowManual);
            }
        });
    }

    /**
     * 面板结构：
     *   全屏 FrameLayout（半透明黑遮罩，点它就关闭）
     *     └ 居中的内容卡片（点击事件在此终止，不会穿透到遮罩）
     *         ├ 标题行：[标题]            [×]
     *         ├ 内容 ScrollView
     *         ├ 手动输入框（可选，默认不抢焦点）
     *         └ 按钮行：[重算] [重截] [关闭]
     *
     * 关闭方式有三条，任何一条都能用：
     *   ① 标题栏右上角 ×
     *   ② 点卡片外面的黑色遮罩
     *   ③ 按返回键
     */
    // ------------------------------------------------------------ 面板（新版 UI）
    /**
     * 结果面板：走 PanelBuilder 的结构化渲染。
     *
     * 保留旧签名是为了让 doCaptureAndAnalyze 里的调用点不用大改：
     * 当 parsedTags 非空且 combos 能算出来时走新 UI；
     * 否则退回纯文本的错误/提示面板。
     */
    private void buildPanel(String text, List<String> parsedTags, boolean allowManual) {
        if (parsedTags != null && !parsedTags.isEmpty()) {
            String[] arr = parsedTags.toArray(new String[0]);
            List<Recruit.Combo> combos = Recruit.analyze(arr, Recruit.MAX_TAGS);
            if (!combos.isEmpty()) {
                showResultPanel(combos, parsedTags);
                return;
            }
        }
        showMessagePanel(text);
    }

    /**
     * 识别中的骨架面板。
     *
     * 用 3 条带扫光的占位块代替「正在识别…」这行字：
     * 用户能看到「有东西在动」，比纯文字等待感短。
     * 动效开关关掉时静态显示（SkeletonView 内部处理）。
     */
    private void showSkeletonPanel() {
        // Service 已销毁就别再往 WindowManager 挂窗口了（挂了也没人收）
        if (destroyed) return;
        removePanel();

        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x9E000000);
        // 点遮罩空白处收起骨架（识别继续跑，结果到了自己弹）
        overlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { removePanel(); }
        });

        LinearLayout card = Ui.col(this);
        card.setBackground(new BevelDrawable(Ui.PANEL, Ui.LINE2,
                dp(1), dp(10), dp(2)));
        card.setPadding(dp(14), dp(12), dp(14), dp(14));

        // 标题行
        LinearLayout head = Ui.row(this);
        View accent = new View(this);
        accent.setBackgroundColor(Ui.AMBER);
        accent.setLayoutParams(new LinearLayout.LayoutParams(dp(3), dp(16)));
        head.addView(accent);
        TextView title = Ui.tv(this, "公招组合预测", 15f, Ui.TEXT);
        title.setTypeface(Ui.med());
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = dp(8);
        head.addView(title, tLp);
        TextView busy = Ui.tv(this, "正在识别…", 11f, Ui.AMBER);
        head.addView(busy);

        // ★ v2.9.1（交叉审查）★ 识别最长可能占 20 秒，原来骨架面板带
        // FLAG_NOT_TOUCHABLE 且没有关闭入口，用户这 20 秒被全屏盖住且无能为力。
        // 给一个小 ✕：只是关掉这块遮罩，识别仍在后台继续（结果到了会自己弹）。
        TextView cancel = Ui.tv(this, "✕", 15f, Ui.TEXT3);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(8), 0, 0, 0);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { removePanel(); }
        });
        head.addView(cancel);
        card.addView(head);

        // 3 条骨架
        boolean anim = animationsEnabled();
        for (int i = 0; i < 3; i++) {
            SkeletonView sk = new SkeletonView(this, anim);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
            sLp.topMargin = dp(8);
            card.addView(sk, sLp);
        }

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int w = panelWidth(screenW, screenH);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                w, FrameLayout.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        // 卡片自己吞掉点击，别让它传到遮罩导致「点卡片也关掉」
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { /* 吞掉 */ }
        });
        overlay.addView(card, cardLp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                // FLAG_NOT_FOCUSABLE 保留（不抢游戏焦点），但去掉 NOT_TOUCHABLE，
                // 否则新加的 ✕ 点不到。整块遮罩设成可点击区域，点空白处也能收起。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;

        panel = overlay;
        try {
            wm.addView(panel, lp);
        } catch (Throwable t) {
            panel = null;
        }
    }

    /** 结构化结果面板 */
    private void showResultPanel(List<Recruit.Combo> combos, List<String> tags) {
        showResultPanel(combos, tags, null);
    }

    /** 结构化结果面板（warning 非空时在结论区上方显示可靠性提示） */
    private void showResultPanel(List<Recruit.Combo> combos, List<String> tags,
                                 String warning) {
        removePanel();

        // 空组合不能进结构化面板：PanelBuilder 里结论区会退化成「无」，
        // 用户看到的是一片空白卡片。这里直接给文字提示。
        if (combos == null || combos.isEmpty()) {
            showMessagePanel("这组标签算不出任何组合。\n\n"
                    + "通常是手动输入里有非法标签，或标签之间没有公共干员。\n"
                    + (warning != null ? ("\n" + warning) : ""));
            return;
        }

        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x9E000000);
        overlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { removePanel(); }
        });
        overlay.setFocusableInTouchMode(true);

        View card = new PanelBuilder(this, combos, tags, new PanelBuilder.Callback() {
            public void onRecalc(String manualTags) {
                List<String> parsed = parseTags(manualTags);
                if (parsed.isEmpty()) {
                    // ★ v2.9.2 ★ 把具体哪个词没认出来告诉用户，不再只说一句「没有合法标签」
                    String bad = joinRejected();
                    toast(bad == null ? "没有合法标签" : ("认不出：" + bad));
                    return;
                }
                String[] arr = parsed.toArray(new String[0]);
                // 手输重算的结果不继承截图时的 warning：标签是用户自己给的。
                // 但若有个别词没认出来，要让它出现在面板里，而不是看起来「全都生效了」。
                String bad = joinRejected();
                String warn = bad == null ? null : ("这些词不是公招标签，已忽略：" + bad);
                showResultPanel(Recruit.analyze(arr, Recruit.MAX_TAGS), parsed, warn);
            }
            public void onRecapture() {
                removePanel();
                onBallClick();
            }
            public void onClose() { removePanel(); }
        }, warning).build();

        // 卡片点击不穿透到遮罩
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { /* 吞掉 */ }
        });

        // 横屏时屏高很小（如 1080x2400 横过来高度只有 1080），
        // 用竖屏的 78% 会挤成一条缝，所以横屏给 88% 并放宽宽度上限。
        boolean land = isLandscape();
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int w = panelWidth(screenW, screenH);
        int maxH = (int) (screenH * (land ? 0.88f : 0.78f));

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(w, maxH);
        cardLp.gravity = Gravity.CENTER;
        overlay.addView(card, cardLp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                0, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        // ★ v2.9.2 修复（交叉审查）★
        // 面板可聚焦（用户要能改标签），但没设 softInputMode 时软键盘会直接
        // 盖住底部的「重算」按钮 —— 唯一能提交改动的按钮被挡住。
        // SOFT_INPUT_ADJUST_PAN 让内容整体上移，按钮始终可见。
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;

        panel = overlay;
        try {
            wm.addView(panel, lp);
            panel.requestFocus();
            panel.setOnKeyListener(new View.OnKeyListener() {
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (keyCode == KeyEvent.KEYCODE_BACK
                            && event.getAction() == KeyEvent.ACTION_UP) {
                        removePanel();
                        return true;
                    }
                    return false;
                }
            });
            // 入场动画
            if (animationsEnabled()) {
                card.setAlpha(0f);
                card.setTranslationY(dp(24));
                card.animate().alpha(1f).translationY(0f)
                        .setDuration(220)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(2f))
                        .start();
            }
            // 面板内滚动区优先接管触摸
            card.setOnTouchListener(null);
        } catch (Throwable t) {
            lastError = "面板显示失败: " + describe(t);
            toast(lastError);
            panel = null;
        }
    }

    /** 纯文本/错误面板（沿用旧版结构，但配色换成新色板） */
    private void showMessagePanel(String text) {
        removePanel();

        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x9E000000);
        overlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { removePanel(); }
        });
        overlay.setFocusableInTouchMode(true);

        LinearLayout card = Ui.col(this);
        card.setBackground(new BevelDrawable(Ui.PANEL, Ui.LINE2,
                dp(1), dp(10), dp(2)));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { /* 吞掉 */ }
        });

        // 标题行
        LinearLayout head = Ui.row(this);
        View accent = new View(this);
        accent.setBackgroundColor(Ui.AMBER);
        accent.setLayoutParams(new LinearLayout.LayoutParams(dp(3), dp(16)));
        head.addView(accent);
        TextView title = Ui.tv(this, "公招组合预测", 15f, Ui.TEXT);
        title.setTypeface(Ui.med());
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = dp(8);
        head.addView(title, tLp);
        TextView close = Ui.tv(this, "✕", 18f, Ui.TEXT2);
        close.setGravity(Gravity.CENTER);
        close.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { removePanel(); }
        });
        head.addView(close);
        card.addView(head);

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView body = Ui.tv(this, text, 12f, Ui.TEXT2);
        body.setPadding(0, dp(8), 0, dp(8));
        sv.addView(body);
        card.addView(sv);

        LinearLayout btns = Ui.row(this);
        btns.setPadding(0, dp(6), 0, 0);
        TextView again = Ui.tv(this, "重截", 12.5f, Ui.VOID);
        again.setTypeface(Ui.med());
        again.setGravity(Gravity.CENTER);
        again.setBackground(getResources().getDrawable(R.drawable.bg_btn_primary, null));
        again.setLayoutParams(new LinearLayout.LayoutParams(0, dp(40), 1f));
        again.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { removePanel(); onBallClick(); }
        });
        btns.addView(again);

        TextView close2 = Ui.tv(this, "关闭", 12.5f, Ui.TEXT);
        close2.setTypeface(Ui.med());
        close2.setGravity(Gravity.CENTER);
        close2.setBackground(getResources().getDrawable(R.drawable.bg_btn_outline, null));
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        cLp.leftMargin = dp(8);
        close2.setLayoutParams(cLp);
        close2.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { removePanel(); }
        });
        btns.addView(close2);
        card.addView(btns);

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // 与结果面板同一口径：平板上允许更宽，避免字被挤成一条竖线
        int w = panelWidth(screenW, screenH);
        int maxH = (int) (screenH * 0.66f);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(w, maxH);
        cardLp.gravity = Gravity.CENTER;
        overlay.addView(card, cardLp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                0, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        // ★ v2.9.2 修复（交叉审查）★
        // 面板可聚焦（用户要能改标签），但没设 softInputMode 时软键盘会直接
        // 盖住底部的「重算」按钮 —— 唯一能提交改动的按钮被挡住。
        // SOFT_INPUT_ADJUST_PAN 让内容整体上移，按钮始终可见。
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
        panel = overlay;
        try {
            wm.addView(panel, lp);
            panel.requestFocus();
            panel.setOnKeyListener(new View.OnKeyListener() {
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (keyCode == KeyEvent.KEYCODE_BACK
                            && event.getAction() == KeyEvent.ACTION_UP) {
                        removePanel();
                        return true;
                    }
                    return false;
                }
            });
        } catch (Throwable t) {
            lastError = "面板显示失败: " + describe(t);
            toast(lastError);
            panel = null;
        }
    }

    /** 最近一次 parseTags 里没认出来的词（供面板/toast 提示，用完即清） */
    private final List<String> rejected = new ArrayList<>();

    /** 手输标签解析 */
    private List<String> parseTags(String s) {
        List<String> out = new ArrayList<>();
        rejected.clear();
        if (s == null) return out;
        for (String t : s.split("[、,，\\s;；]+")) {
            if (t.isEmpty()) continue;
            // ★ v2.9.2 修复（交叉审查）★
            // 原来用 Recruit.isKnownTag 精确匹配，用户手打「远程」「近战」
            // 或者只写「术师」（合法标签是「术师干员」）会被静默丢弃 ——
            // 面板只显示部分标签的结论，用户不知道哪个没生效。
            // 现在先过 TagFinder.normalize（含包含/编辑距离/子序列容错），
            // 仍认不出就记进 rejected，由调用方统一提示。
            String norm = TagFinder.normalize(t);
            if (norm != null && Recruit.isKnownTag(norm)) {
                if (!out.contains(norm)) out.add(norm);
            } else if (!rejected.contains(t)) {
                rejected.add(t);
            }
        }
        return out;
    }

    /** rejected 拼成一句话；空则返回 null */
    private String joinRejected() {
        if (rejected.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rejected.size(); i++) {
            if (i > 0) sb.append("、");
            sb.append(rejected.get(i));
        }
        return sb.toString();
    }

    /** 更新悬浮球视觉状态（不阻塞主流程，失败静默） */
    private void setBallState(final int st) {
        ui.post(new Runnable() {
            public void run() {
                if (myBall != null) myBall.setState(st);
            }
        });
    }

    private boolean animationsEnabled() {
        try {
            return android.provider.Settings.Global.getFloat(getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (Throwable t) {
            return true;
        }
    }
    private void removePanel() {
        View p = panel;
        panel = null;
        if (p != null) {
            try { wm.removeView(p); } catch (Throwable ignored) {}
        }
    }

    @Override
    public android.os.IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        // 先置标志：让还在跑的识别流程后续的 ui.post 全部失效，
        // 避免在已销毁的 Service 上 wm.addView 挂出没人管的全屏遮罩。
        destroyed = true;
        // 把排队中的 UI 任务（骨架面板、结果面板等）一并丢掉
        try { ui.removeCallbacksAndMessages(null); } catch (Throwable ignored) {}
        removePanel();
        if (ball != null) {
            try { wm.removeView(ball); } catch (Throwable ignored) {}
            ball = null;
        }
        releaseProjection();
        if (bgThread != null) {
            try { bgThread.quitSafely(); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    private static String describe(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 900 ? s.substring(0, 900) : s;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
