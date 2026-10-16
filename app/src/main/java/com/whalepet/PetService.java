package com.whalepet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ComponentCallbacks;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Random;


/**
 * 前台服务 + 悬浮窗。
 *
 * 三条硬规则（做错整个功能就废了）：
 *  1. 窗口必须小 —— 只包住宠物本身，绝不做全屏透明层。
 *  2. 始终带 FLAG_NOT_FOCUSABLE —— 否则抖音会失焦，评论时输入法弹不出来。
 *  3. 不可见时真的停止渲染（见 PetView.onWindowVisibilityChanged / onScreenOff）。
 */
public class PetService extends Service implements PetView.Listener, DragonView.Listener, InteractionController.Host {

    public static final String ACTION_SHOW = "com.whalepet.SHOW";
    public static final String ACTION_HIDE = "com.whalepet.HIDE";
    public static final String ACTION_REFRESH = "com.whalepet.REFRESH";
    public static final String ACTION_NEXT_FACE = "com.whalepet.NEXT_FACE";
    public static final String ACTION_FACE_COUNT = "com.whalepet.FACE_COUNT";
    public static final String ACTION_SOUND_DIAG = "com.whalepet.SOUND_DIAG";
    public static final String ACTION_SET_HIDDEN = "com.whalepet.SET_HIDDEN";
    public static final String ACTION_NEXT_SKIN = "com.whalepet.NEXT_SKIN";
    public static final String ACTION_PREVIEW_DUCK = "com.whalepet.PREVIEW_DUCK";
    public static final String EXTRA_COUNT = "count";
    public static final String EXTRA_HIDDEN = "hidden";

    /** 设置页打开期间她会被收起来，期间换的表情记在这里，回来时补上 */
    private int pendingFace = 0;
    private boolean hiddenForSettings = false;

    // 小黄鸭音效。用 SoundPool 而不是 MediaPlayer —— 短音效的延迟低得多，连点也不会卡。
    private SoundPool soundPool;
    private final int[] duckIds = new int[DUCK_COUNT];
    private int duckSel = 0;
    private boolean soundOn = true;
    // 长度必须跟着 DUCK_COUNT 走。写死 3 个的话，音效加到 8 个就会越界崩溃。
    private final int[] duckLoadStatus = newDuckStatus();

    private static int[] newDuckStatus() {
        int[] a = new int[DUCK_COUNT];
        java.util.Arrays.fill(a, -999);
        return a;
    }
    private final Random rnd = new Random();
    /** 静音提示的节流时间戳，避免连点时刷屏 */
    private long lastSilentHintMs = 0L;

    /**
     * 音效走哪条音频流 —— 必须走媒体音量（STREAM_MUSIC）。
     *
     * 以前这里是 USAGE_ASSISTANCE_SONIFICATION，它在 AudioService 里被映射成
     * STREAM_SYSTEM（"系统音效"）。MIUI / HyperOS 上那条通道在静音模式下会被压到 0，
     * 用户把"系统音效/触摸提示音"关掉后它也是 0 —— 结果就是 SoundPool.play()
     * 返回成功、但一点声音都没有，而媒体音量明明是正常的。
     * 表现完全就是"点她有反应、就是不出声"。
     */
    private static final int SOUND_STREAM = AudioManager.STREAM_MUSIC;

    public static final String PREFS = "whalepet";
    public static final String KEY_HOVER_H = "hover_height_dp";
    public static final String KEY_PERCH_W = "perch_width_dp";
    public static final String KEY_AMP = "amp_scale";
    /** 两个人的动作幅度各存各的。KEY_AMP 是旧版共用的那个，只当兜底默认值。 */
    public static final String KEY_FISH_AMP = "fish_amp_scale";
    public static final String KEY_DRAGON_AMP = "dragon_amp_scale";
    public static final String KEY_SOUND = "sound_on";
    public static final String KEY_SKIN = "skin_index";

    // 本分支新增（上游没有）：默认皮肤
    /**
     * 大肥鱼默认皮肤 = 女仆装（SKINS 里的索引 1）。
     *
     * 索引 0 是"饭盆头"（锅盖造型），那是⑤锅盖梗里临时戴上的；
     * docx 明确要求"默认女仆装不戴锅盖，锅盖只在对应互动中出现"，
     * 所以默认值不能是 0，否则锅盖常驻、那一段互动也就等于没演。
     */
    public static final int DEFAULT_SKIN = 1;
    public static final String KEY_X = "pos_x";
    public static final String KEY_DUCK = "duck_index";
    public static final int DUCK_COUNT = 8;
    public static final String KEY_WANDER = "wander_on";
    /** 小龙女自己的溜达开关，和大肥鱼的互不影响。 */
    public static final String KEY_DRAGON_WANDER = "dragon_wander_on";
    public static final String KEY_RUNNING = "running";
    public static final String KEY_EDGE = "edge";
    public static final String KEY_PERCHED = "perched";
    public static final String KEY_Y = "last_y";
    public static final String KEY_DRAGON_ENABLED = "dragon_enabled";
    /** 大肥鱼的显示开关。关掉它，小龙女可以自己一个人待着。 */
    public static final String KEY_FISH_ENABLED = "fish_enabled";
    public static final String KEY_DRAGON_PERCHED = "dragon_perched";
    /** 设置页是不是正在前台。服务可能是"被开关拉起来"的，那时收不到 SET_HIDDEN， */
    /** 只能靠这个记号知道"现在别放出来，免得盖住设置界面"。 */
    public static final String KEY_SETTINGS_OPEN = "settings_open";
    public static final String KEY_DRAGON_EDGE = "dragon_edge";
    public static final String KEY_DRAGON_X = "dragon_pos_x";
    public static final String KEY_DRAGON_Y = "dragon_pos_y";
    public static final String KEY_DRAGON_HEIGHT = "dragon_height_dp";
    /** 小龙女趴边时露出的脑袋宽度 —— 和大肥鱼的"趴边时脑袋宽度"同一个意思。 */
    public static final String KEY_DRAGON_PERCH_W = "dragon_perch_width_dp";
    /** 趴边满多少分钟进入睡觉待机。0 = 关闭。 */
    public static final String KEY_SLEEP_AFTER_MIN = "sleep_after_min";
    /**
     * 互动的活动范围：存的是**相对整屏的归一化坐标**（0~1），不是 dp。
     * 这样转屏 / 平板横屏之后，screenW/screenH 一变，范围自动跟着当前方向走 ——
     * 「上半屏」永远是当前方向的上半屏，而不是"竖屏时算出来的那半屏"。
     */
    public static final String KEY_REGION_L = "region_l";
    public static final String KEY_REGION_T = "region_t";
    public static final String KEY_REGION_R = "region_r";
    public static final String KEY_REGION_B = "region_b";
    /**
     * 「打闹」开关。默认开；关掉之后随机剧情里不会再抽到"互相追打"那一段，
     * 打招呼 / 拌嘴 / 吃醋 / 贴贴 / 锅盖 / 魔法 / 告别都照常。
     */
    public static final String KEY_PLAYFIGHT = "playfight_on";
    public static final int DEFAULT_SLEEP_AFTER_MIN = 3;
    public static final int MAX_SLEEP_AFTER_MIN = 10;
    public static final String ACTION_TOGGLE_DRAGON = "com.whalepet.TOGGLE_DRAGON";
    public static final String ACTION_TOGGLE_FISH = "com.whalepet.TOGGLE_FISH";
    public static final String ACTION_DRAGON_FACE = "com.whalepet.DRAGON_FACE";

    public static final float DEFAULT_HOVER_H = 240f;
    public static final float DEFAULT_PERCH_W = 96f;
    public static final float DEFAULT_DRAGON_PERCH_W = 96f;
    public static final float DEFAULT_AMP = 0.75f;
    private static final float SNAP_DP = 56f;

    /**
     * 剧情收工后的"冷静期"：这么久之内不会再自动凑一起开演。
     * 老版本这里是 5 秒，而且是从**开演时刻**算起 —— 一段剧情本身就有 6~7 秒，
     * 等于一散场马上就够条件，表现就是"分开没多久又打起来"。
     */
    private static final long INTERACTION_COOLDOWN_MS = 20000L;
    /**
     * 手动把两人拖到一起之后的冷却。那是明确的意图，等满 20 秒会像坏了一样，
     * 所以只挡"刚打完还站在原地"的那几秒。
     */
    private static final long MANUAL_INTERACTION_COOLDOWN_MS = 3000L;
    /** 打断互动后拉开的最小间距：屏幕宽度的 22%，但至少 72dp（小屏 / 分屏也别贴回去）。 */
    private static final float SEPARATE_GAP_RATIO_OF_SCREEN = 0.22f;
    private static final float SEPARATE_GAP_MIN_DP = 72f;
    /**
     * "真的贴在一起"的判定：两个窗口的交集面积要占到较小那个窗口的 35%。
     * 只看矩形相交（哪怕只擦到一个角）就开演的话，平时走位蹭一下也会触发打闹。
     */
    private static final float CLOSE_AREA_RATIO = 0.35f;

    private static final String CHANNEL = "pet";
    private static final int NOTI_ID = 1001;
    private static final long MENU_AUTO_HIDE_MS = 6000;

    private WindowManager wm;
    private PetView view;
    private WindowManager.LayoutParams lp;
    private DragonView dragonView;
    private WindowManager.LayoutParams dragonLp;
    private int dragonExpression = DragonView.STAND;
    private final InteractionController interactions = new InteractionController(this);
    private final Random interactionRandom = new Random();
    private long lastInteractionEndMs;
    private int fishSkinBeforeInteraction = -1;
    /** 趴边开始的时间戳（0 = 没趴边）；用来算"趴够几分钟该睡了"。 */
    private long fishPerchSinceMs, dragonPerchSinceMs;
    private long lastSleepStandbyMs;
    private final Runnable hideBubbleTask = this::hideBubble;
    private int interactionIndex = 0;
    private View menuView;
    private WindowManager.LayoutParams menuLp;
    /**
     * 台词气泡专用的一层：全屏、不可触摸、盖在两个角色窗口之上。
     * 以前气泡画在角色窗口里，两个角色一贴近就会互相遮挡（详见 BubbleLayerView 的注释）。
     */
    private BubbleLayerView bubbleLayer;
    private WindowManager.LayoutParams bubbleLayerLp;
    private final BubbleLayerView.Source bubbleSource = new BubbleLayerView.Source() {
        @Override public boolean rectOf(boolean dragon, Rect out) {
            WindowManager.LayoutParams p = dragon ? dragonLp : lp;
            View v = dragon ? dragonView : view;
            if (p == null || v == null) return false;
            out.set(p.x, p.y, p.x + p.width, p.y + p.height);
            return true;
        }
    };
    private SharedPreferences prefs;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable menuAutoHide = new Runnable() {
        @Override public void run() { hideMenu(); }
    };
    private final Runnable overlapInteractionTick = new Runnable() {
        @Override public void run() {
            if (dragonView == null || view == null || !prefs.getBoolean(KEY_RUNNING, false)) return;
            if (charactersReallyClose() && canInteract()
                    && cooldownPassed(INTERACTION_COOLDOWN_MS)) {
                startRandomInteraction();
            }
            handler.postDelayed(this, interactions.isRunning() ? 1500L : 7000L + interactionRandom.nextInt(9000));
        }
    };

    /** 睡觉待机：每 20 秒看一次"趴够时间没有"。 */
    private final Runnable sleepStandbyTick = new Runnable() {
        @Override public void run() {
            checkSleepStandby();
            handler.postDelayed(this, 20000L);
        }
    };
    private int screenW, screenH;
    /** 趴边时累计"往外拉了多少"。位置会一直贴回边缘，但拉出量必须留着， */
    /** 否则一松手就归零，她永远脱离不了边缘（上一版就栽在这）。 */
    private int perchPull = 0;
    // 两边都留
    /** 小龙女自己的"往外拉了多少"，跟大肥鱼各算各的。 */
    private int dragonPerchPull = 0;

    /**
     * 趴屏幕下边时露出多少。
     * 1.0 = 整个脑袋都露出来，站在屏幕下沿上（不切）。
     * 调小会让更多部分藏到屏幕外 —— 但要注意趴下边用的裁剪区比脸长，
     * 调小了会先切到脸。
     */
    private static final float BOTTOM_VISIBLE = 1.0f;

    private final BroadcastReceiver screenRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            boolean off = Intent.ACTION_SCREEN_OFF.equals(i.getAction());
            if (!off) onScreenMetricsChanged();   // 亮屏时顺手校一次屏幕尺寸（折叠、投屏都可能变）
            if (view != null) {
                if (off) view.onScreenOff(); else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) view.onScreenOn();
            }
            if (dragonView != null) dragonView.setWindowVisible(!off);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        // 服务可能是被设置页里的开关拉起来的，那时不会有 SET_HIDDEN 送进来。
        // 设置页自己会在 onResume / onPause 里更新这个记号。
        hiddenForSettings = prefs.getBoolean(KEY_SETTINGS_OPEN, false);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        measureScreen();

        // 上游新增，必须保留：屏幕旋转之后 screenW/screenH 会变，但 Service 不会自动收到通知 ——
        // 不重新量的话，横屏时"扒右边"会算到屏幕中间去。
        //
        // 合并要点：上游原版在这里直接 measureScreen() 再手动重贴大肥鱼。本分支已经有一套
        // 等价的 onScreenMetricsChanged()（onConfigurationChanged / 亮屏广播都会走它），
        // 直接照抄上游会在两边都触发，而且它先把 screenW/screenH 量掉了，
        // onScreenMetricsChanged() 的"尺寸没变就直接 return"就会误判、连小龙女都不重贴。
        // 所以这里让它走同一条路径 —— 上游的意图（转屏后重新量 + 重新贴边）一字不差地保留，
        // 顺带把小龙女也一起贴回去。
        registerComponentCallbacks(new ComponentCallbacks() {
            @Override public void onConfigurationChanged(Configuration cfg) {
                onScreenMetricsChanged();
            }
            @Override public void onLowMemory() { }
        });
        createChannel();

        soundOn = prefs.getBoolean(KEY_SOUND, true);
        // 溜达开关也要在这里读一次：服务重启后再挂定时器时，用的就是这两个值。
        // 只靠 applySizes() 的话，用户关掉溜达、把她们收起来再放出来，她又会自己爬。
        wanderOn = prefs.getBoolean(KEY_WANDER, true);
        dragonWanderOn = prefs.getBoolean(KEY_DRAGON_WANDER, true);
        createSoundPool();
        loadDuckSounds();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenRx, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // intent == null 说明是系统把服务"粘性重启"了（START_STICKY）。
        // 以前这里默认按 ACTION_SHOW 走 —— 于是用户从最近任务里划掉 App 之后，
        // 过一阵系统把服务拉起来，她又自己冒出来了（网友反馈过"开游戏加载时她会出现"）。
        if (intent == null && !prefs.getBoolean(KEY_RUNNING, false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent == null ? ACTION_SHOW : intent.getAction();
        startForeground(NOTI_ID, buildNotification());

        if (ACTION_HIDE.equals(action)) {
            prefs.edit().putBoolean(KEY_RUNNING, false).apply();
            hiddenForSettings = false;
            removePet();
            removeDragon();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_REFRESH.equals(action)) {
            applySizes();
            syncFish();
            syncDragon();
            return START_STICKY;
        }
        if (ACTION_TOGGLE_DRAGON.equals(action)) {
            // 只有"打开"才把服务拉起来。关掉开关时顺手把另一个角色召出来，
            // 是用户完全没要求的事（他会以为只是关掉了大肥鱼）。
            if (prefs.getBoolean(KEY_DRAGON_ENABLED, true)) ensureRunning();
            syncDragon();
            stopIfNothingToShow();
            return START_STICKY;
        }
        if (ACTION_TOGGLE_FISH.equals(action)) {
            if (prefs.getBoolean(KEY_FISH_ENABLED, true)) ensureRunning();
            syncFish();
            stopIfNothingToShow();
            return START_STICKY;
        }
        if (ACTION_DRAGON_FACE.equals(action)) {
            if (dragonView != null) {
                setDragonExpression((dragonExpression + 1) % 7);
                showDragonMoodBubble();
            }
            stopIfIdle();     // 她根本不在的时候，别留下一个前台服务空转
            return START_STICKY;
        }
        if (ACTION_SOUND_DIAG.equals(action)) {
            runSoundDiagnostic();
            stopIfIdle();
            return START_STICKY;
        }
        if (ACTION_NEXT_SKIN.equals(action)) {
            int n = (prefs.getInt(KEY_SKIN, DEFAULT_SKIN) + 1) % PetView.SKINS.length;
            prefs.edit().putInt(KEY_SKIN, n).apply();
            if (view != null) view.applySkin(n);
            // 不能直接 addPet()：大肥鱼被关掉的时候，换皮肤不该把她变回来
            else syncFish();
            stopIfIdle();
            return START_STICKY;
        }
        if (ACTION_NEXT_FACE.equals(action)) {
            if (view != null) {
                view.cycleFace();
                view.pokeFeedback();
            } else {
                // 设置页打开时她不在，先记住，回来再补
                pendingFace = (pendingFace + 1) % 7;
            }
            stopIfIdle();
            return START_STICKY;
        }
        // 去重：git 自动合并把这一段生成了两份（本分支一份 + 上游一份，内容一致，
        // 只有本分支这份多一次 stopIfIdle() 收尾）。只保留下面这一份。
        if (ACTION_PREVIEW_DUCK.equals(action)) {
            duckSel = prefs.getInt(KEY_DUCK, 0);
            if (soundPool != null) {
                int i = Math.max(0, Math.min(duckIds.length - 1, duckSel));
                if (duckIds[i] != 0) soundPool.play(duckIds[i], 1f, 1f, 1, 0, 1f);
            }
            stopIfIdle();
            return START_STICKY;
        }
        if (ACTION_SET_HIDDEN.equals(action)) {
            // 我们自己的设置页在前台时，必须把她收起来 ——
            // 悬浮窗永远在最上层，不收起来就会盖住自己的界面。
            boolean hide = intent.getBooleanExtra(EXTRA_HIDDEN, false);
            hiddenForSettings = hide;
            if (hide) {
                removePet();
                removeDragon();
            } else if (prefs.getBoolean(KEY_RUNNING, false)) {
                syncFish();
                syncDragon();
            }
            return START_STICKY;
        }
        if (ACTION_FACE_COUNT.equals(action)) {
            int n = view == null ? -1 : view.faceCount();
            sendBroadcast(new Intent(ACTION_FACE_COUNT).putExtra(EXTRA_COUNT, n)
                    .setPackage(getPackageName()));
            stopIfIdle();
            return START_STICKY;
        }

        prefs.edit().putBoolean(KEY_RUNNING, true).apply();
        // 这是一次明确的"让她出现"（用户按了按钮），之前为了不挡设置页而挂起的
        // 收起状态必须清掉，否则点"让她出现"会什么都不发生（然后被当成
        // "两个都关了"直接停掉服务）。
        // 注意只在真的收到 ACTION_SHOW 时清 —— 系统粘性重启（intent == null）
        // 也走这条路径，那时候设置页可能正开着，不能趁机把她们放出来。
        if (intent != null && ACTION_SHOW.equals(action)) hiddenForSettings = false;
        // 两个角色各自独立：谁开着就显示谁，不再要求"大肥鱼必须在"。
        if (!prefs.contains(KEY_FISH_ENABLED) && !prefs.contains(KEY_DRAGON_ENABLED)) {
            prefs.edit().putBoolean(KEY_FISH_ENABLED, true)
                    .putBoolean(KEY_DRAGON_ENABLED, true).apply();
        }
        syncFish();
        syncDragon();
        if (view == null && dragonView == null) {
            boolean anyEnabled = prefs.getBoolean(KEY_FISH_ENABLED, true)
                    || prefs.getBoolean(KEY_DRAGON_ENABLED, true);
            if (!canDrawOverlays()) {
                Toast.makeText(this, "还差「显示在其他应用上层」权限，否则她们没法浮在别的应用上面。",
                        Toast.LENGTH_LONG).show();
            } else if (!anyEnabled) {
                Toast.makeText(this, "大肥鱼和小龙女都被关掉了，先在设置里打开一个。",
                        Toast.LENGTH_LONG).show();
            } else {
                // 两个都开着，只是设置页正挡在前面 —— 服务留着，
                // 等用户离开设置页时 SET_HIDDEN(false) 再放她们出来。
                // 这里要是停了服务，等用户退出去就再也没人把她们放回来了。
                return START_STICKY;
            }
            prefs.edit().putBoolean(KEY_RUNNING, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        armOverlapInteraction();
        if (view != null && prefs.getBoolean(KEY_PERCHED, false)) {
            // 合并要点：这里必须认上游新增的「趴屏幕下边」(EDGE_BOTTOM)，
            // 不然从设置页回来她会从"趴下边"变成"扒左边"。
            int eStart = prefs.getInt(KEY_EDGE, 0);
            if (eStart == PetView.EDGE_BOTTOM) enterPerchBottom(false);
            else enterPerch(eStart == 1, false);
        }
        return START_STICKY;
    }

    // ==== 以下整段（syncFish … separateAfterInterruptedInteraction）是本分支新增：
    // ==== 上游在这一段位置上没有任何内容，属于纯新增，全部保留。
    /** 按开关把大肥鱼放出来 / 收回去。 */
    private void syncFish() {
        if (hiddenForSettings) return;      // 设置页开着的时候先别放，免得盖住界面
        if (prefs.getBoolean(KEY_FISH_ENABLED, true)) addPet();
        else removePet();
    }

    /** 按开关把小龙女放出来 / 收回去。 */
    private void syncDragon() {
        if (hiddenForSettings) return;
        if (prefs.getBoolean(KEY_DRAGON_ENABLED, true)) {
            addDragon();
            armOverlapInteraction();
        } else {
            removeDragon();
        }
    }

    /** 开关动过之后，只要还有角色开着，服务就该活着。 */
    private void ensureRunning() {
        if (prefs.getBoolean(KEY_FISH_ENABLED, true)
                || prefs.getBoolean(KEY_DRAGON_ENABLED, true)) {
            prefs.edit().putBoolean(KEY_RUNNING, true).apply();
        }
    }

    /** 两个都关了，服务就没必要留在后台。 */
    private void stopIfNothingToShow() {
        boolean any = prefs.getBoolean(KEY_FISH_ENABLED, true)
                || prefs.getBoolean(KEY_DRAGON_ENABLED, true);
        if (any && prefs.getBoolean(KEY_RUNNING, false)) return;
        if (view != null || dragonView != null) return;
        prefs.edit().putBoolean(KEY_RUNNING, false).apply();
        stopSelf();
    }

    /**
     * 「她不在，但这个请求还是要处理」的收尾。
     *
     * 换表情、试听音效这些按钮走 sendAction()，它会 startForegroundService ——
     * 光处理不回收的话，会留下一个没有窗口、却挂着通知的前台服务。
     */
    private void stopIfIdle() {
        if (prefs.getBoolean(KEY_RUNNING, false)) return;
        if (view != null || dragonView != null) return;
        stopSelf();
    }

    private boolean addDragon() {
        if (dragonView != null) return true;
        if (!canDrawOverlays()) return false;
        dragonView = new DragonView(this);
        dragonView.setListener(this);
        dragonView.setHoverHeightDp(prefs.getFloat(KEY_DRAGON_HEIGHT, 190f));
        dragonView.setPerchWidthDp(dragonPerchWidthDp());
        dragonView.setAmpScale(dragonAmpScale());
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        dragonLp = new WindowManager.LayoutParams(
                dragonView.windowW(), dragonView.windowH(), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        dragonLp.gravity = Gravity.TOP | Gravity.START;
        dragonLp.x = prefs.getInt(KEY_DRAGON_X, Math.max(0, screenW / 2 - dragonLp.width / 2));
        dragonLp.y = prefs.getInt(KEY_DRAGON_Y, Math.max(0, Math.round(screenH * 0.42f)));
        clampDragon();
        try {
            wm.addView(dragonView, dragonLp);
        } catch (Exception e) {
            dragonView = null;
            dragonLp = null;
            return false;
        }
        raiseBubbleLayer();          // 角色窗口是后加的，会盖住气泡层，这里把气泡层重新提到最上
        // 她上次是扒在边上的话，回来还得是扒着的样子
        if (prefs.getBoolean(KEY_DRAGON_PERCHED, false)) {
            // 和大肥鱼一样：要认得出"趴屏幕下边"，不然从设置页回来她会从趴下边变成扒左边
            int de = prefs.getInt(KEY_DRAGON_EDGE, 0);
            if (de == DragonView.EDGE_BOTTOM) enterDragonPerchBottom(false);
            else enterDragonPerch(de == 1, false);
        }
        // 溜达的定时器按当前开关重建，跟大肥鱼一样
        restartDragonWander();
        return true;
    }

    /** 小龙女趴边时露出的宽度。独立可调，和大肥鱼一样。 */
    private float dragonPerchWidthDp() {
        float w = prefs.getFloat(KEY_DRAGON_PERCH_W, DEFAULT_DRAGON_PERCH_W);
        return Math.max(56f, Math.min(160f, w));
    }

    /** 大肥鱼的动作幅度。老版本共用 KEY_AMP，没设过新键就拿它当默认。 */
    public float fishAmpScale() {
        return prefs.getFloat(KEY_FISH_AMP, prefs.getFloat(KEY_AMP, DEFAULT_AMP));
    }

    /** 小龙女的动作幅度，跟大肥鱼互不影响。 */
    public float dragonAmpScale() {
        return prefs.getFloat(KEY_DRAGON_AMP, prefs.getFloat(KEY_AMP, DEFAULT_AMP));
    }

    private void removeDragon() {
        handler.removeCallbacks(overlapInteractionTick);
        dragonWanderHandler.removeCallbacks(dragonWanderTick);
        dragonWanderStarted = false;
        interactions.cancel();
        if (dragonView != null && wm != null) {
            try { wm.removeView(dragonView); } catch (Exception ignored) { }
        }
        dragonView = null;
        dragonLp = null;
        hideBubble();
    }

    private void applyDragonLayout() {
        if (dragonView == null || dragonLp == null) return;
        dragonLp.width = dragonView.windowW();
        dragonLp.height = dragonView.windowH();
        clampDragon();
        try { wm.updateViewLayout(dragonView, dragonLp); } catch (Exception ignored) { }
    }
    private void clampDragon() {
        if (dragonLp == null) return;
        dragonLp.x = Math.max(0, Math.min(screenW - dragonLp.width, dragonLp.x));
        // 趴屏幕下边时她本来就该有一部分在屏幕外，不能按普通规则夹 y。
        // 和大肥鱼 clamp() 里那段一个道理：用脑袋真实高度定位，不是窗口高度
        // （窗口比脑袋高，拿窗口高度会让她离下沿差出一截）。
        if (dragonView != null && dragonView.getState() == DragonView.STATE_PERCH
                && dragonView.getPerchEdge() == DragonView.EDGE_BOTTOM) {
            // 顶上那条气泡留白要一起加回去：窗口往上撑，脑袋的位置不动
            dragonLp.y = Math.round(screenH
                    - (dragonView.perchVisibleH() + dragonView.perchBottomTopPad()) * BOTTOM_VISIBLE);
            return;
        }
        dragonLp.y = Math.max(0, Math.min(screenH - dragonLp.height, dragonLp.y));
    }

    private boolean charactersOverlap() {
        if (lp == null || dragonLp == null) return false;
        return lp.x < dragonLp.x + dragonLp.width
                && lp.x + lp.width > dragonLp.x
                && lp.y < dragonLp.y + dragonLp.height
                && lp.y + lp.height > dragonLp.y;
    }

    /**
     * "刚散场"的冷却。
     *
     * 注意计时基点是{@link #startRandomInteraction()} 里记下的**开演时刻**，
     * 所以实际"散场后再等多久"= 这个值减去剧情长度（6~7 秒）。
     */
    private boolean cooldownPassed(long ms) {
        return System.currentTimeMillis() - lastInteractionEndMs >= ms;
    }

    /**
     * "真的贴在一起"：两个窗口的交集面积要占到较小那个窗口的 {@link #CLOSE_AREA_RATIO}。
     *
     * 触发判定用这个更严的条件；别的调用方（比如打断后要不要拉开）仍然用
     * {@link #charactersOverlap()} 的普通相交语义。
     */
    private boolean charactersReallyClose() {
        if (lp == null || dragonLp == null || !charactersOverlap()) return false;
        long ix = Math.min(lp.x + lp.width, dragonLp.x + dragonLp.width) - Math.max(lp.x, dragonLp.x);
        long iy = Math.min(lp.y + lp.height, dragonLp.y + dragonLp.height) - Math.max(lp.y, dragonLp.y);
        if (ix <= 0 || iy <= 0) return false;
        long smaller = Math.min((long) lp.width * lp.height, (long) dragonLp.width * dragonLp.height);
        return smaller > 0 && ix * iy >= (long) (smaller * CLOSE_AREA_RATIO);
    }

    /**
     * 松手 / 点一下之后顺手看看要不要开演。
     *
     * 手动把她们拖到一起是明确的意图，所以这里只挡"刚打完还在原地"的那几秒，
     * 不像自动检测那样等满 {@link #INTERACTION_COOLDOWN_MS}。
     */
    private void startOverlapInteractionIfNeeded() {
        if (charactersReallyClose() && canInteract()
                && cooldownPassed(MANUAL_INTERACTION_COOLDOWN_MS)) {
            startRandomInteraction();
        }
    }

    /** 互动要两个人都在场、都悬空、而且没有别的互动在跑。 */
    private boolean canInteract() {
        return view != null && dragonView != null
                && view.getState() == PetView.STATE_HOVER
                && dragonView.getState() == DragonView.STATE_HOVER
                && !interactions.isRunning();
    }

    /**
     * 互动被打断（拖了一下）→ 把两人朝**相反方向**推开，
     * 让她们散场后不会站在原地又被判定"贴在一起"。
     *
     * 间距取"屏幕宽度的 {@link #SEPARATE_GAP_RATIO_OF_SCREEN} 和
     * {@link #SEPARATE_GAP_MIN_DP}dp 里更大的那个"：老版本只有 18dp，
     * 两个窗口本来就留着重叠的余量，18dp 等于没分开 —— 用户看到的正是
     * "分开的距离不够，很容易再次打闹"。
     * 只往外推、不往回拉，免得把本来已经分开的两个人拽回中间。
     */
    private void separateAfterInterruptedInteraction() {
        if (lp == null || dragonLp == null || view == null || dragonView == null) return;
        int gap = Math.max(Math.round(screenW * SEPARATE_GAP_RATIO_OF_SCREEN),
                Math.round(dp(SEPARATE_GAP_MIN_DP)));
        int mid = ((lp.x + lp.width / 2) + (dragonLp.x + dragonLp.width / 2)) / 2;
        int leftEdge = mid - gap / 2;      // 左边那位的右边缘应该在这儿
        int rightEdge = mid + gap / 2;     // 右边那位的左边缘应该在这儿
        if (dragonLp.x >= lp.x) {          // 小龙女在右、大肥鱼在左
            dragonLp.x = Math.max(dragonLp.x, rightEdge);
            lp.x = Math.min(lp.x, leftEdge - lp.width);
        } else {                           // 反过来
            lp.x = Math.max(lp.x, rightEdge);
            dragonLp.x = Math.min(dragonLp.x, leftEdge - dragonLp.width);
        }
        clamp();
        clampDragon();
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
        try { wm.updateViewLayout(dragonView, dragonLp); } catch (Exception ignored) { }
    }

    /**
     * 用户从「最近任务」里划掉了 App —— 这是明确的"我不要它跑"。
     * 真的收起来，别让系统过一会儿把服务拉起来、她又冒出来。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        prefs.edit().putBoolean(KEY_RUNNING, false).apply();
        // 两边都留：上游的 removePet() 加上本分支的小龙女/气泡/互动收尾
        hideBubble();
        interactions.cancel();
        handler.removeCallbacks(overlapInteractionTick);
        removePet();
        removeDragon();
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        hideMenu();
        hideBubble();
        interactions.cancel();
        removePet();
        removeDragon();
        wanderHandler.removeCallbacks(wanderTick);
        dragonWanderHandler.removeCallbacks(dragonWanderTick);
        if (soundPool != null) { soundPool.release(); soundPool = null; }
        try { unregisterReceiver(screenRx); } catch (Exception ignored) { }
        super.onDestroy();
    }

    // ---- 悬浮窗 ----

    private boolean addPet() {
        if (view != null) return true;
        if (!canDrawOverlays()) return false;

        view = new PetView(this);
        view.setListener(this);
        view.setHoverHeightDp(prefs.getFloat(KEY_HOVER_H, DEFAULT_HOVER_H));
        view.setPerchWidthDp(prefs.getFloat(KEY_PERCH_W, DEFAULT_PERCH_W));
        view.setAmpScale(fishAmpScale());

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        lp = new WindowManager.LayoutParams(
                Math.round(view.windowW()), Math.round(view.windowH()), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (screenW - lp.width) / 2;
        lp.y = Math.max(0, prefs.getInt(KEY_Y, (int) (screenH * 0.42f)));

        try {
            wm.addView(view, lp);
        } catch (Exception e) {
            Toast.makeText(this, "悬浮窗添加失败，请确认已授予「显示在其他应用上层」权限",
                    Toast.LENGTH_LONG).show();
            view = null;
            return false;
        }
        // 两边都留：本分支的 raiseBubbleLayer()（气泡层重新提到最上）
        raiseBubbleLayer();          // 同上：角色窗口后加，气泡层要重新提到最上
        // 恢复上次的样子。不恢复的话，从设置页回来她会变成一个
        // "站在屏幕正中间、默认皮肤、默认表情"的新人 —— 趴边状态也会丢。
        // 默认值用本分支的 DEFAULT_SKIN（女仆装）：上游这里写死 0（饭盆头），
        // 与本分支"默认不戴锅盖"的要求冲突，故取 DEFAULT_SKIN；存过的值不受影响。
        int sk = prefs.getInt(KEY_SKIN, DEFAULT_SKIN);
        if (sk > 0 && sk < PetView.SKINS.length) view.applySkin(sk);
        if (pendingFace != 0) {
            view.setFace(pendingFace);
            pendingFace = 0;
        }
        if (prefs.getBoolean(KEY_PERCHED, false)) {
            // 取上游这份：它多处理了「趴屏幕下边」(EDGE_BOTTOM)，
            // 是本分支那行 enterPerch(...) 的超集（左右边逻辑一致）。
            int e0 = prefs.getInt(KEY_EDGE, 0);
            if (e0 == PetView.EDGE_BOTTOM) enterPerchBottom(false);
            else enterPerch(e0 == 1, false);
        } else {
            lp.x = prefs.getInt(KEY_X, lp.x);
            applyLayout();
        }
        // 定时器状态按当前开关重建，杜绝"开关关了还爬"
        restartWander();
        return true;
    }

    private void removePet() {
        if (view != null && wm != null) {
            try { wm.removeView(view); } catch (Exception ignored) { }
        }
        view = null;
        // 本分支新增（上游没有）：
        // 互动是两个人的事，少一个就先停下，免得回调继续改表情 / 冒气泡
        interactions.cancel();
        hideBubble();
        dropBubbleLayerIfIdle();
        // 不重置的话，她回来之后自动溜达的定时器永远不会重新挂上
        wanderStarted = false;
    }

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return android.provider.Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void applySizes() {
        // 这两个开关跟"她在不在窗口里"无关，必须先更新。
        // 设置页打开时她已经从窗口移除（view == null），以前的写法会在这里提前
        // return，于是拨动音效开关后 soundOn 一直是旧值 —— 开关看着像失灵。
        boolean prevWander = wanderOn;
        // 本分支新增：小龙女的溜达开关也要一起读、一起比对
        boolean prevDragonWander = dragonWanderOn;
        soundOn = prefs.getBoolean(KEY_SOUND, true);
        wanderOn = prefs.getBoolean(KEY_WANDER, true);
        dragonWanderOn = prefs.getBoolean(KEY_DRAGON_WANDER, true);
        duckSel = prefs.getInt(KEY_DUCK, 0);
        // 开关动过就重挂一次定时器 —— 不重挂的话，那个 tick 一旦退出就再也回不来，
        // 表现为"关了还爬"或者"开了不爬"。
        if (wanderOn != prevWander) restartWander();
        // 本分支新增（上游没有）：
        if (dragonWanderOn != prevDragonWander) restartDragonWander();
        if (dragonView != null) {
            dragonView.setHoverHeightDp(prefs.getFloat(KEY_DRAGON_HEIGHT, 190f));
            applyDragonLayout();
        }
        if (view == null || lp == null) return;
        view.setHoverHeightDp(prefs.getFloat(KEY_HOVER_H, DEFAULT_HOVER_H));
        view.setPerchWidthDp(prefs.getFloat(KEY_PERCH_W, DEFAULT_PERCH_W));
        view.setAmpScale(fishAmpScale());
        int sk = prefs.getInt(KEY_SKIN, DEFAULT_SKIN);
        if (sk != view.currentSkin()) view.applySkin(sk);
        if (dragonView != null) {
            dragonView.setAmpScale(dragonAmpScale());
            dragonView.setHoverHeightDp(prefs.getFloat(KEY_DRAGON_HEIGHT, 190f));
            dragonView.setPerchWidthDp(dragonPerchWidthDp());
            applyDragonLayout();
        }
        applyLayout();
    }

    private void applyLayout() {
        if (view == null || lp == null) return;
        if (!wanderStarted) {                 // 窗口一就位就把溜达的定时器挂上
            wanderStarted = true;
            wanderHandler.postDelayed(wanderTick, 4000);
        }
        if (view.getState() == PetView.STATE_HOVER) {
            lp.width = Math.round(view.windowW());
            lp.height = Math.round(view.windowH());
        } else {
            lp.width = Math.round(view.perchWindowW());
            lp.height = Math.round(view.perchWindowH());
        }
        clamp();
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
        startOverlapInteractionIfNeeded();
    }

    /**
     * 量真实的显示区域（整块屏幕，含状态栏 / 导航栏）。
     *
     * 不能用 getResources().getDisplayMetrics() —— 它不含导航栏，
     * 于是她最多只能停在导航栏上方，怎么拖都到不了屏幕最底部（用户反馈过）。
     *
     * 大屏设备上还有两个坑：
     *  1) 服务里拿到的可能只是"应用可用区域"（分屏、兼容模式、自由窗口），
     *     比整块屏幕小。拿它当屏幕宽，贴边就会贴到屏幕中间去。
     *     所以优先要"整块屏幕"的尺寸（maximumWindowMetrics），拿不到再退。
     *  2) 这个值必须跟着转屏走 —— 只在 onCreate 量一次的话，横过来之后
     *     还在用竖屏那一条宽度，右边这条边就跑到屏幕中间了。
     *     转屏时系统会回调 onConfigurationChanged（见下），那里会重新量。
     */
    private void measureScreen() {
        int w = 0, h = 0;
        try {
            WindowManager wmgr = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 整块屏幕（跨分屏 / 自由窗口也不会变小）
                try {
                    Rect b = wmgr.getMaximumWindowMetrics().getBounds();
                    w = b.width();
                    h = b.height();
                } catch (Exception ignored) { }
                if (w <= 0 || h <= 0) {
                    try {
                        Rect b = wmgr.getCurrentWindowMetrics().getBounds();
                        w = b.width();
                        h = b.height();
                    } catch (Exception ignored) { }
                }
            }
            if (w <= 0 || h <= 0) {
                DisplayMetrics dm = new DisplayMetrics();
                wmgr.getDefaultDisplay().getRealMetrics(dm);
                w = dm.widthPixels;
                h = dm.heightPixels;
            }
        } catch (Exception ignored) { }
        if (w <= 0 || h <= 0) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            w = dm.widthPixels;
            h = dm.heightPixels;
        }
        // 万一面量出来的那个 API 给了另一个方向的值（折叠、转屏瞬间都可能），
        // 按当前的屏幕方向摆正，别让"右边"算到屏幕中间去。
        boolean land = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        if (land && h > w) {
            int tmp = w; w = h; h = tmp;
        } else if (!land && w > h) {
            int tmp = w; w = h; h = tmp;
        }
        screenW = w;
        screenH = h;
    }

    /**
     * 转屏 / 折叠 / 分屏变化之后：重新量屏幕，把两个角色贴回新的边界。
     *
     * 不重贴的话，横屏之后"右边"还是竖屏那条宽度 —— 拖到右边会停在屏幕中间，
     * 竖着也多出一大截可以拖到屏幕外面去。
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        onScreenMetricsChanged();
    }

    private void onScreenMetricsChanged() {
        int oldW = screenW, oldH = screenH;
        measureScreen();
        if (screenW == oldW && screenH == oldH) return;      // 尺寸没变，什么都不用做
        // 趴边的重新扒到新的边上；悬空的夹回新的可视范围
        if (view != null && lp != null) {
            if (view.getState() == PetView.STATE_PERCH) {
                // 合并要点：认上游新增的「趴屏幕下边」，否则转屏后她会从左/右边
                // 变成趴下边（或反过来）。
                int e1 = prefs.getInt(KEY_EDGE, 0);
                if (e1 == PetView.EDGE_BOTTOM) enterPerchBottom(false);
                else enterPerch(e1 == 1, false);
            } else {
                applyLayout();
            }
        }
        if (dragonView != null && dragonLp != null) {
            if (dragonView.getState() == DragonView.STATE_PERCH) {
                // 转屏 / 换屏之后照她**现在真正趴着的那条边**重贴，
                // 不读 prefs：视图才是真实状态（persist=false 的路径 prefs 会滞后）
                int de = dragonView.getPerchEdge();
                if (de == DragonView.EDGE_BOTTOM) enterDragonPerchBottom(false);
                else enterDragonPerch(de == DragonView.EDGE_RIGHT, false);
            } else {
                applyDragonLayout();
            }
        }
        // 溜达目标是按旧屏宽挑的，作废重挑
        wanderTargetX = -1f;
        resetDragonWanderTarget();
    }

    // 去重：git 自动合并把 measureScreen() 生成了两份。
    // 上面那份（含 getMaximumWindowMetrics + 方向纠正）是本分支的，功能更全，
    // 且完整覆盖了上游那份的取值路径（currentWindowMetrics → realMetrics → 资源兜底），
    // 因此删掉下面这份上游的重复定义。
    // （上游原定义见 git show upstream/main:app/src/main/java/com/whalepet/PetService.java）

    private void clamp() {
        int w = lp.width, h = lp.height;
        if (lp.x < 0) lp.x = 0;
        if (lp.x > screenW - w) lp.x = Math.max(0, screenW - w);
        // 趴屏幕下边时她本来就该有一部分在屏幕外，不能按普通规则夹 y
        if (view != null && view.getState() == PetView.STATE_PERCH
                && view.getPerchEdge() == PetView.EDGE_BOTTOM) {
            // 用脑袋真实高度定位，不是窗口高度 —— 窗口比脑袋高，
            // 拿窗口高度定位会让她离下沿差出一截。
            lp.y = Math.round(screenH - view.perchVisibleH() * BOTTOM_VISIBLE);
            return;
        }
        // 悬空 / 扒左右边：整个人必须完整留在屏幕里。
        // 上游原本允许上下各露 12%（topLimit=-12%h、bottomLimit=屏幕高-88%h），
        // 于是往屏幕上下边缘一拖，她就"半个脑袋挂在屏幕外"、看着像悬空在那里。
        // 这里和小龙女的 clampDragon() 统一成硬夹：只有趴屏幕下边才允许探出屏幕外。
        lp.y = Math.max(0, Math.min(screenH - h, lp.y));
    }

    // ---- 长按菜单 ----

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override
    public void onLongPress() {
        if (menuView != null) { hideMenu(); return; }
        if (view == null) return;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF01B2033);
        bg.setCornerRadius(dp(16));
        bg.setStroke(Math.round(dp(1)), 0x55FFFFFF);
        row.setBackground(bg);
        int pad = Math.round(dp(5));
        row.setPadding(pad, pad, pad, pad);
        row.setElevation(dp(6));

        row.addView(menuButton("皮肤：" + PetView.SKINS[prefs.getInt(KEY_SKIN, DEFAULT_SKIN) % PetView.SKINS.length].name,
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                int n = (prefs.getInt(KEY_SKIN, DEFAULT_SKIN) + 1) % PetView.SKINS.length;
                prefs.edit().putInt(KEY_SKIN, n).apply();
                ((TextView) v).setText("皮肤：" + PetView.SKINS[n].name);
                applySizes();          // 两套皮肤的宽高比不同，换完要重新量窗口
                // 换一次就重新计时，方便连着点着挑
                handler.removeCallbacks(menuAutoHide);
                handler.postDelayed(menuAutoHide, MENU_AUTO_HIDE_MS);
            }
        }));
        row.addView(menuButton("互动", new View.OnClickListener() {
            @Override public void onClick(View v) {
                hideMenu();
                startRandomInteraction();
            }
        }));
        row.addView(menuButton("设置", new View.OnClickListener() {
            @Override public void onClick(View v) { hideMenu(); openSettings(); }
        }));
        row.addView(menuButton("关闭大肥鱼", new View.OnClickListener() {
            @Override public void onClick(View v) {
                hideMenu();
                // 只关大肥鱼。小龙女要是还开着，就让她自己留着。
                prefs.edit().putBoolean(KEY_FISH_ENABLED, false).apply();
                interactions.cancel();
                removePet();
                // 判断"还有没有别人"要看开关，不能看窗口在不在 ——
                // 设置页把她们收起来的时候窗口本来就不在。
                if (!prefs.getBoolean(KEY_DRAGON_ENABLED, true)) {
                    prefs.edit().putBoolean(KEY_RUNNING, false).apply();
                    stopSelf();
                }
            }
        }));

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        menuLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        // 点菜单外面的地方也把菜单收掉（原来只能等 6 秒自动消失）
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        menuLp.gravity = Gravity.TOP | Gravity.START;
        menuLp.x = 0;
        menuLp.y = 0;
        // 收到 ACTION_OUTSIDE = 用户点了菜单外面 → 收起
        row.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent ev) {
                if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    hideMenu();
                    return true;
                }
                return false;
            }
        });
        menuView = row;

        try {
            wm.addView(menuView, menuLp);
        } catch (Exception e) {
            menuView = null;
            return;
        }
        menuView.post(new Runnable() {
            @Override public void run() { placeMenu(); }
        });
        handler.postDelayed(menuAutoHide, MENU_AUTO_HIDE_MS);
    }

    private TextView menuButton(String text, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(15);
        tv.setPadding(Math.round(dp(18)), Math.round(dp(10)),
                Math.round(dp(18)), Math.round(dp(10)));
        tv.setClickable(true);
        tv.setOnClickListener(l);
        return tv;
    }

    private void placeMenu() {
        if (menuView == null || view == null || lp == null) return;
        int mw = menuView.getWidth(), mh = menuView.getHeight();
        int gap = Math.round(dp(8));
        int x = lp.x + lp.width + gap;
        if (x + mw > screenW) x = lp.x - mw - gap;
        if (x < 0) x = Math.max(0, (screenW - mw) / 2);
        int y = lp.y + (lp.height - mh) / 2;
        if (y < 0) y = 0;
        if (y + mh > screenH) y = screenH - mh;
        menuLp.x = x;
        menuLp.y = y;
        try { wm.updateViewLayout(menuView, menuLp); } catch (Exception ignored) { }
    }

    private void hideMenu() {
        handler.removeCallbacks(menuAutoHide);
        if (menuView != null && wm != null) {
            try { wm.removeView(menuView); } catch (Exception ignored) { }
        }
        menuView = null;
    }

    private void openSettings() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    // ---- 状态切换 ----

    /** 趴到屏幕下边：脑袋从下沿探出来，水平位置不动 */
    private void enterPerchBottom(boolean persist) {
        if (view == null) return;
        measureScreen();
        perchPull = 0;
        view.setState(PetView.STATE_PERCH);
        view.setPerchEdge(PetView.EDGE_BOTTOM);
        view.setMirror(false);
        lp.width = Math.round(view.perchWindowW());
        lp.height = Math.round(view.perchWindowH());
        clamp();   // clamp 里会把 y 钉在屏幕下沿
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
        if (persist) {
            prefs.edit().putBoolean(KEY_PERCHED, true).putInt(KEY_EDGE, PetView.EDGE_BOTTOM).apply();
        }
    }

    private void enterPerch(boolean right, boolean persist) {
        if (view == null) return;
        // 两边都留，且去重：`perchPull = 0` 两边都有，只留一份
        measureScreen();   // 兜底：万一旋转监听没触发，这里也要用最新的屏幕尺寸
        perchPull = 0;
        fishPerchSinceMs = System.currentTimeMillis();      // 开始计时：趴够多久该睡
        view.setState(PetView.STATE_PERCH);
        view.setPerchEdge(right ? PetView.EDGE_RIGHT : PetView.EDGE_LEFT);
        view.setMirror(right);
        lp.width = Math.round(view.perchWindowW());
        lp.height = Math.round(view.perchWindowH());
        lp.x = right ? screenW - lp.width : 0;
        clamp();
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
        if (persist) {
            prefs.edit().putBoolean(KEY_PERCHED, true).putInt(KEY_EDGE, right ? 1 : 0).apply();
        }
    }

    private void exitPerch() {
        if (view == null) return;
        fishPerchSinceMs = 0;                              // 不趴了，睡觉计时清零
        view.setState(PetView.STATE_HOVER);
        view.setMirror(false);
        applyLayout();
        prefs.edit().putBoolean(KEY_PERCHED, false).apply();
    }

    // ---- 小龙女的趴边：和大肥鱼同一套做法，只是换了个窗口 ----

    private void enterDragonPerch(boolean right, boolean persist) {
        if (dragonView == null || dragonLp == null) return;
        measureScreen();                                    // 兜底：转屏监听没触发时也要用最新屏幕尺寸
        dragonPerchPull = 0;
        dragonPerchSinceMs = System.currentTimeMillis();     // 开始计时
        dragonView.setPerchWidthDp(dragonPerchWidthDp());
        dragonView.setPerchEdge(right ? DragonView.EDGE_RIGHT : DragonView.EDGE_LEFT);
        dragonView.setState(DragonView.STATE_PERCH);
        dragonView.setMirror(right);
        dragonLp.width = dragonView.windowW();
        dragonLp.height = dragonView.windowH();
        dragonLp.x = right ? screenW - dragonLp.width : 0;
        clampDragon();
        try { wm.updateViewLayout(dragonView, dragonLp); } catch (Exception ignored) { }
        if (persist) {
            prefs.edit().putBoolean(KEY_DRAGON_PERCHED, true)
                    .putInt(KEY_DRAGON_EDGE, right ? 1 : 0).apply();
        }
        resetDragonWanderTarget();      // 站边上了，之前挑的溜达目标作废
    }

    /**
     * 趴到屏幕下边：脑袋从下沿探出来，水平位置不动。
     *
     * 和大肥鱼 enterPerchBottom() 是同一套：窗口宽度按脑袋长宽比推、y 交给
     * clampDragon() 钉在屏幕下沿（不是这里写死，免得两处各算一套）。
     */
    private void enterDragonPerchBottom(boolean persist) {
        if (dragonView == null || dragonLp == null) return;
        measureScreen();
        dragonPerchPull = 0;
        dragonPerchSinceMs = System.currentTimeMillis();     // 开始计时（趴下边也会睡着）
        dragonView.setPerchWidthDp(dragonPerchWidthDp());
        dragonView.setPerchEdge(DragonView.EDGE_BOTTOM);
        dragonView.setState(DragonView.STATE_PERCH);
        dragonView.setMirror(false);
        dragonLp.width = dragonView.windowW();
        dragonLp.height = dragonView.windowH();
        clampDragon();   // clampDragon 里会把 y 钉在屏幕下沿
        try { wm.updateViewLayout(dragonView, dragonLp); } catch (Exception ignored) { }
        if (persist) {
            prefs.edit().putBoolean(KEY_DRAGON_PERCHED, true)
                    .putInt(KEY_DRAGON_EDGE, DragonView.EDGE_BOTTOM).apply();
        }
        resetDragonWanderTarget();
    }

    private void exitDragonPerch() {
        if (dragonView == null) return;
        dragonPerchSinceMs = 0;                             // 不趴了，睡觉计时清零
        dragonView.setState(DragonView.STATE_HOVER);
        dragonView.setMirror(false);
        applyDragonLayout();
        prefs.edit().putBoolean(KEY_DRAGON_PERCHED, false).apply();
        resetDragonWanderTarget();      // 下来了，等一会儿再重新挑目标
    }

    private void armOverlapInteraction() {
        handler.removeCallbacks(overlapInteractionTick);
        if (view != null && dragonView != null && prefs.getBoolean(KEY_RUNNING, false)) {
            handler.postDelayed(overlapInteractionTick, 6000L);
        }
        // 睡觉待机的检查跟"两个都在不在"无关，只要服务活着就一直挂着
        handler.removeCallbacks(sleepStandbyTick);
        if (prefs.getBoolean(KEY_RUNNING, false)) {
            handler.postDelayed(sleepStandbyTick, 20000L);
        }
    }

    /** 「打闹」开关（设置页那张卡上的 Switch）。关掉之后随机剧情里不再抽到互相追打。 */
    @Override public boolean playfightEnabled() {
        return prefs.getBoolean(KEY_PLAYFIGHT, true);
    }

    private void startRandomInteraction() {
        if (!canInteract()) return;
        // 冷却的计时基点是这里（开演时刻），一段剧情 6~7 秒，所以散场后实际还要再等十几秒
        lastInteractionEndMs = System.currentTimeMillis();
        interactions.startRandomEncounter();
    }

    // ---- 小剧场的走位：把窗口平滑地挪到目标位置 ----

    private final Handler moveHandler = new Handler(Looper.getMainLooper());
    private Runnable fishMove, dragonMove;

    /**
     * 按"可移动范围的比例"移动某个角色。
     *
     * xRatio / yRatio：0 = 贴左(上)，1 = 贴右(下)，0.5 = 居中。
     * 用比例而不是像素，屏幕多大、角色调多大都成立。
     */
    // ---------------- 活动范围 ----------------

    private final Rect regionRect = new Rect();

    /**
     * 互动活动范围的像素矩形。
     *
     * 存的是相对整屏的比例，所以横屏 / 平板换个方向也自动成立；
     * 四周留 8dp、顶部至少留 24dp —— 不然角色贴着状态栏，气泡没地方画。
     */
    private Rect activityRect() {
        float l = clamp01(prefs.getFloat(KEY_REGION_L, 0f));
        float t = clamp01(prefs.getFloat(KEY_REGION_T, 0f));
        float r = clamp01(prefs.getFloat(KEY_REGION_R, 1f));
        float b = clamp01(prefs.getFloat(KEY_REGION_B, 1f));
        if (r - l < 0.08f) { l = 0f; r = 1f; }      // 存坏了就退回全屏，别把角色锁在一个点上
        if (b - t < 0.08f) { t = 0f; b = 1f; }
        int pad = Math.round(dp(8f));
        int top = Math.max(pad, Math.round(dp(24f)));
        regionRect.set(Math.round(screenW * l) + pad, Math.round(screenH * t) + top,
                Math.round(screenW * r) - pad, Math.round(screenH * b) - pad);
        if (regionRect.right - regionRect.left < dp(60f)) regionRect.right = regionRect.left + Math.round(dp(60f));
        if (regionRect.bottom - regionRect.top < dp(60f)) regionRect.bottom = regionRect.top + Math.round(dp(60f));
        return regionRect;
    }

    /** 把窗口按比例放进活动范围并夹住（超出范围的弧线 / 跳跃都拉回来）。 */
    private void placeInRegion(WindowManager.LayoutParams p, float xRatio, float yRatio) {
        if (p == null) return;
        Rect rect = activityRect();
        float rw = Math.max(1f, rect.width() - p.width);
        float rh = Math.max(1f, rect.height() - p.height);
        p.x = Math.round(rect.left + rw * clamp01(xRatio));
        p.y = Math.round(rect.top + rh * clamp01(yRatio));
        clampIntoRegion(p);
    }

    private void clampIntoRegion(WindowManager.LayoutParams p) {
        if (p == null) return;
        Rect rect = activityRect();
        if (p.x < rect.left) p.x = rect.left;
        if (p.y < rect.top) p.y = rect.top;
        if (p.x + p.width > rect.right) p.x = Math.max(rect.left, rect.right - p.width);
        if (p.y + p.height > rect.bottom) p.y = Math.max(rect.top, rect.bottom - p.height);
    }

    @Override public void moveActor(String actor, float xRatio, float yRatio, long durationMs) {
        boolean dragon = "dragon".equals(actor);
        WindowManager.LayoutParams p = dragon ? dragonLp : lp;
        if (p == null || (dragon ? dragonView : view) == null) return;
        Rect rect = activityRect();
        float rw = Math.max(1f, rect.width() - p.width);
        float rh = Math.max(1f, rect.height() - p.height);
        int tx = Math.round(rect.left + rw * clamp01(xRatio));
        int ty = Math.round(rect.top + rh * clamp01(yRatio));
        animateWindow(actor, p.x, p.y, tx, ty, durationMs);
    }

    /** 相对挪动（顶一下、蹦一下、被推退半步）。 */
    @Override public void nudgeActor(String actor, int dx, int dy, long durationMs) {
        boolean dragon = "dragon".equals(actor);
        WindowManager.LayoutParams p = dragon ? dragonLp : lp;
        if (p == null || (dragon ? dragonView : view) == null) return;
        // 挪动也要留在活动范围里
        WindowManager.LayoutParams probe = new WindowManager.LayoutParams();
        probe.copyFrom(p);
        probe.x = p.x + dx;
        probe.y = p.y + dy;
        clampIntoRegion(probe);
        animateWindow(actor, p.x, p.y, probe.x, probe.y, durationMs);
    }

    // ---------------- 逐帧轨迹播放器 ----------------
    //
    // 剧情不再靠"一串 at() 一次性事件"拼动作，而是登记**连续轨迹**：
    // 每帧对每条轨道求值 → 每个角色取最后登记且未过期的移动轨道，
    // 姿态轨道全部叠加 → 一次 updateViewLayout + setPose。
    // 这样位移就是真的连续动画（追跑、抛物线、连跳、蛇形、绕圈），
    // 而且"从角色当前位置起步"可以直接用 actorRatio() 读出来。

    public static final int PATH_LINE = 0;   // 缓入缓出直线
    public static final int PATH_ARC = 1;    // 抛物线起跳（y 减去 amp*4k(1-k)）
    public static final int PATH_HOP = 2;    // 连跳（amp*|sin(π·cycles·k)|）
    public static final int PATH_SINE = 3;   // 蛇形（sin(2π·cycles·k) 加在 y 上）
    public static final int PATH_LOOP = 4;   // 绕圈（x/y 各加 cos/sin·amp）

    public static final int POSE_BOUNCE = 0; // 抬升 + 压扁回弹
    public static final int POSE_SHAKE = 1;  // 高频抖
    public static final int POSE_LEAN = 2;   // 正弦倾斜
    public static final int POSE_SPIN = 3;   // 自转倾斜

    private static final class MoveTrack {
        final boolean dragon;
        final long t0, t1;
        final float x0, y0, x1, y1, amp, cycles;
        final int path;
        MoveTrack(boolean dragon, long t0, long t1, float x0, float y0, float x1, float y1,
                  int path, float amp, float cycles) {
            this.dragon = dragon; this.t0 = t0; this.t1 = t1;
            this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
            this.path = path; this.amp = amp; this.cycles = cycles;
        }
    }

    private static final class PoseTrack {
        final boolean dragon;
        final long t0, t1;
        final int kind;
        final float amp, cycles;
        PoseTrack(boolean dragon, long t0, long t1, int kind, float amp, float cycles) {
            this.dragon = dragon; this.t0 = t0; this.t1 = t1;
            this.kind = kind; this.amp = amp; this.cycles = cycles;
        }
    }

    private final ArrayList<MoveTrack> moveTracks = new ArrayList<>();
    private final ArrayList<PoseTrack> poseTracks = new ArrayList<>();
    /** 场景开始时刻：轨道的 t0/t1 都是相对它的毫秒数，由 setSceneMode(true) 对齐。 */
    private long trackBaseMs;
    private boolean tracking;
    private final Runnable trackTick = new Runnable() {
        @Override public void run() { stepTracks(); }
    };

    /** 登记一段移动轨迹：t0~t1（相对场景开始的毫秒）沿 path 从 (x0,y0) 走到 (x1,y1)，都是范围内比例坐标。 */
    @Override public void trackMove(String actor, long t0, long t1, float x0, float y0,
                                    float x1, float y1, int path, float ampUnit, float cycles) {
        moveTracks.add(new MoveTrack("dragon".equals(actor), t0, Math.max(t0 + 40L, t1),
                x0, y0, x1, y1, path, ampUnit, Math.max(0.2f, cycles)));
        startTracking();
    }

    /** 登记一段姿态轨迹：t0~t1 之间按 kind 做动作，幅度 amp、循环 cycles。 */
    @Override public void trackPose(String actor, long t0, long t1, int kind, float amp, float cycles) {
        poseTracks.add(new PoseTrack("dragon".equals(actor), t0, Math.max(t0 + 40L, t1),
                kind, amp, Math.max(0.2f, cycles)));
        startTracking();
    }

    /** 角色当前在活动范围里的比例坐标（0~1），剧情可以从"她们现在站的地方"起步。 */
    @Override public float[] actorRatio(String actor) {
        boolean dragon = "dragon".equals(actor);
        WindowManager.LayoutParams p = dragon ? dragonLp : lp;
        if (p == null) return new float[]{0.5f, 0.5f};
        Rect rect = activityRect();
        float rw = Math.max(1f, rect.width() - p.width);
        float rh = Math.max(1f, rect.height() - p.height);
        return new float[]{clamp01((p.x - rect.left) / rw), clamp01((p.y - rect.top) / rh)};
    }

    private void startTracking() {
        if (tracking) return;
        tracking = true;
        moveHandler.post(trackTick);
    }

    /** 演出 / 拖动打断时立刻停掉所有轨道。 */
    private void stopTracks() {
        moveTracks.clear();
        poseTracks.clear();
        if (tracking) { moveHandler.removeCallbacks(trackTick); tracking = false; }
    }

    private void stepTracks() {
        if (!tracking) return;
        long now = System.currentTimeMillis() - trackBaseMs;
        applyMoveTrack(true, now);
        applyMoveTrack(false, now);
        applyPoseTracksFor(true, now);
        applyPoseTracksFor(false, now);
        // 清掉已经结束一段时间的，数组别越攒越长
        for (int i = moveTracks.size() - 1; i >= 0; i--) if (moveTracks.get(i).t1 + 600L < now) moveTracks.remove(i);
        for (int i = poseTracks.size() - 1; i >= 0; i--) if (poseTracks.get(i).t1 + 600L < now) poseTracks.remove(i);
        if (moveTracks.isEmpty() && poseTracks.isEmpty()) { tracking = false; return; }
        moveHandler.postDelayed(trackTick, 16L);
    }

    private void applyMoveTrack(boolean dragon, long now) {
        MoveTrack best = null;
        for (int i = 0; i < moveTracks.size(); i++) {
            MoveTrack t = moveTracks.get(i);
            if (t.dragon == dragon && t.t0 <= now && now <= t.t1) best = t;   // 后登记的覆盖先登记的
        }
        if (best == null) return;
        WindowManager.LayoutParams p = dragon ? dragonLp : lp;
        View v = dragon ? dragonView : view;
        if (p == null || v == null) return;
        float k = (now - best.t0) / (float) (best.t1 - best.t0);
        float e = k < 0.5f ? 2f * k * k : 1f - 2f * (1f - k) * (1f - k);     // ease-in-out
        float x = best.x0 + (best.x1 - best.x0) * e;
        float y = best.y0 + (best.y1 - best.y0) * e;
        switch (best.path) {
            case PATH_ARC:  y -= best.amp * 4f * k * (1f - k); break;
            case PATH_HOP:  y -= best.amp * Math.abs((float) Math.sin(Math.PI * best.cycles * k)); break;
            case PATH_SINE: y += (float) Math.sin(2 * Math.PI * best.cycles * k) * best.amp; break;
            case PATH_LOOP:
                x += (float) Math.cos(2 * Math.PI * best.cycles * k) * best.amp;
                y += (float) Math.sin(2 * Math.PI * best.cycles * k) * best.amp;
                break;
            default: break;                                                   // PATH_LINE
        }
        placeInRegion(p, x, y);
        try { wm.updateViewLayout(v, p); } catch (Exception ignored) { }
    }

    /** 叠加所有还没结束的姿态轨道，再叠到剧情设定的基值上，并按角色上限夹一次。 */
    private void applyPoseTracksFor(boolean dragon, long now) {
        float tLean = 0f, tSquash = 1f, tLift = 0f, tScale = 1f;
        boolean any = false;
        for (int i = 0; i < poseTracks.size(); i++) {
            PoseTrack t = poseTracks.get(i);
            if (t.dragon != dragon || now < t.t0 || now > t.t1) continue;
            float k = (now - t.t0) / (float) (t.t1 - t.t0);
            float wave = (float) Math.sin(2 * Math.PI * t.cycles * k);
            switch (t.kind) {
                case POSE_BOUNCE: {
                    float s = Math.abs((float) Math.sin(Math.PI * t.cycles * k));
                    tLift += 0.05f * t.amp * s;                 // 抬升上限就是身高 5%
                    tSquash *= 1f - 0.10f * t.amp * s;          // 起跳拉长、落地压扁
                    break;
                }
                case POSE_SHAKE:
                    tLean += 6f * t.amp * wave;
                    tSquash *= 1f + 0.04f * t.amp * wave;
                    break;
                case POSE_LEAN:
                    tLean += t.amp * wave;
                    break;
                default:                                        // POSE_SPIN
                    tLean += t.amp * wave;
                    tScale *= 1f + 0.03f * t.amp * (float) Math.cos(2 * Math.PI * t.cycles * k);
                    break;
            }
            any = true;
        }
        if (!any) return;
        float baseLean = dragon ? dragonLean : fishLean;
        float baseSquash = dragon ? dragonSquash : fishSquash;
        float baseLift = dragon ? dragonLift : fishLift;
        float baseScale = dragon ? dragonScale : fishScale;
        float maxLean = dragon ? 10f : 12f;                     // 超了会被自己的窗口裁掉
        float lean = Math.max(-maxLean, Math.min(maxLean, baseLean + tLean));
        float squash = Math.max(0.86f, Math.min(1.14f, baseSquash * tSquash));
        float lift = Math.max(0f, Math.min(0.05f, baseLift + tLift));
        float scale = Math.max(0.86f, Math.min(1.06f, baseScale * tScale));
        if (dragon) { if (dragonView != null) dragonView.setPose(lean, squash, lift, scale); }
        else if (view != null) view.setPose(lean, squash, lift, scale);
    }

    // ---------------- 小剧场姿态（倾斜 / 压扁 / 抬升 / 缩放） ----------------
    // 两只都是没有骨骼的整张 PNG，所以"演戏"只能靠整体变换：
    // 倾斜 = 靠过去 / 探身 / 被吓退，压扁 = 落地与被撞，抬升 = 蹦起来。

    private float fishLean, fishSquash = 1f, fishLift, fishScale = 1f;
    private float dragonLean, dragonSquash = 1f, dragonLift, dragonScale = 1f;
    private Runnable fishPoseAnim, dragonPoseAnim;

    @Override public void poseActor(String actor, float lean, float squash, float lift, float scale, long durationMs) {
        tweenPose("dragon".equals(actor), lean, squash, lift, scale, durationMs);
    }

    /**
     * 开演 / 收工。演的时候把两个窗口横向留宽 22%（倾斜时头顶会甩出立绘之外），
     * 收工立刻还原 —— 平时不留一块摸不着的空白触摸区。
     */
    @Override public void setSceneMode(boolean on) {
        if (on) {
            // 新场景：清掉上一段遗留的轨迹，并把时间基准对齐到这一刻（轨道的 t0/t1 都相对它）
            stopTracks();
            trackBaseMs = System.currentTimeMillis();
            // 溜达会让窗口自己走，和编排打架，演的时候先停掉，收工再挂回来
            wanderHandler.removeCallbacks(wanderTick);
            wanderStarted = false;
            dragonWanderHandler.removeCallbacks(dragonWanderTick);
            dragonWanderStarted = false;
            if (view != null) view.setWalking(false);
            if (dragonView != null) dragonView.setWalking(false);
        } else {
            stopTracks();
            restartWander();
            restartDragonWander();
        }
        if (view != null) view.setPoseSlack(on);
        if (dragonView != null) dragonView.setPoseSlack(on);
        relayoutKeepFootprint(false);
        relayoutKeepFootprint(true);
    }

    /** 谁挡在谁前面 —— 抱在一起、靠着睡的时候必须分前后。 */
    @Override public void raiseActor(String actor) {
        boolean dragon = "dragon".equals(actor);
        View v = dragon ? dragonView : view;
        WindowManager.LayoutParams p = dragon ? dragonLp : lp;
        if (v == null || p == null || wm == null) return;
        try { wm.removeView(v); } catch (Exception ignored) { }
        try { wm.addView(v, p); } catch (Exception ignored) { }
        raiseBubbleLayer();                       // 台词层还得压在两位上面
    }

    /** 窗口尺寸变了，但立绘在屏幕上的位置要钉住不动（宽了往左补一半，高了把底边对齐）。 */
    private void relayoutKeepFootprint(boolean dragon) {
        WindowManager.LayoutParams p = dragon ? dragonLp : lp;
        View v = dragon ? dragonView : view;
        if (p == null || v == null) return;
        int nw = dragon ? dragonView.windowW() : Math.round(view.windowW());
        int nh = dragon ? dragonView.windowH() : Math.round(view.windowH());
        if (nw == p.width && nh == p.height) return;
        p.x += (p.width - nw) / 2;
        p.y += p.height - nh;
        p.width = nw;
        p.height = nh;
        if (dragon) clampDragon(); else clamp();
        try { wm.updateViewLayout(v, p); } catch (Exception ignored) { }
    }

    /** 16ms 一步的姿态补间；同一角色上一段姿态动画会被取消。 */
    private void tweenPose(final boolean dragon, final float lean, final float squash,
                           final float lift, final float scale, long durationMs) {
        Runnable prev = dragon ? dragonPoseAnim : fishPoseAnim;
        if (prev != null) moveHandler.removeCallbacks(prev);
        final float l0 = dragon ? dragonLean : fishLean;
        final float q0 = dragon ? dragonSquash : fishSquash;
        final float f0 = dragon ? dragonLift : fishLift;
        final float s0 = dragon ? dragonScale : fishScale;
        final long dur = Math.max(60L, durationMs);
        final long start = System.currentTimeMillis();
        Runnable r = new Runnable() {
            @Override public void run() {
                float k = Math.min(1f, (System.currentTimeMillis() - start) / (float) dur);
                float e = k < 0.5f ? 2f * k * k : 1f - 2f * (1f - k) * (1f - k);   // ease-in-out
                applyPose(dragon, l0 + (lean - l0) * e, q0 + (squash - q0) * e,
                        f0 + (lift - f0) * e, s0 + (scale - s0) * e);
                if (k < 1f) moveHandler.postDelayed(this, 16L);
                else if (dragon) dragonPoseAnim = null; else fishPoseAnim = null;
            }
        };
        if (dragon) dragonPoseAnim = r; else fishPoseAnim = r;
        moveHandler.post(r);
    }

    private void applyPose(boolean dragon, float lean, float squash, float lift, float scale) {
        if (dragon) {
            dragonLean = lean; dragonSquash = squash; dragonLift = lift; dragonScale = scale;
        } else {
            fishLean = lean; fishSquash = squash; fishLift = lift; fishScale = scale;
        }
        // 有姿态轨道在跑的时候，view.setPose 交给 ticker 统一写 ——
        // 两个写手每 16ms 各写一次，画面会抖。
        if (hasActivePoseTrack(dragon)) return;
        if (dragon) { if (dragonView != null) dragonView.setPose(lean, squash, lift, scale); }
        else if (view != null) view.setPose(lean, squash, lift, scale);
    }

    private boolean hasActivePoseTrack(boolean dragon) {
        long now = System.currentTimeMillis() - trackBaseMs;
        for (int i = 0; i < poseTracks.size(); i++) {
            PoseTrack t = poseTracks.get(i);
            if (t.dragon == dragon && now >= t.t0 && now <= t.t1) return true;
        }
        return false;
    }

    @Override public void setSleeping(boolean fishSleeping, boolean dragonSleeping) {
        if (view != null) view.setSleeping(fishSleeping);
        if (dragonView != null) dragonView.setSleeping(dragonSleeping);
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    /** 16ms 一步的窗口动画，带缓入缓出；同一角色的上一次动画会被取消。 */
    private void animateWindow(final String actor, int fromX, int fromY,
                               int toX, int toY, long durationMs) {
        final boolean dragon = "dragon".equals(actor);
        Runnable prev = dragon ? dragonMove : fishMove;
        if (prev != null) moveHandler.removeCallbacks(prev);
        final long dur = Math.max(60L, durationMs);
        final long start = System.currentTimeMillis();
        final int fx = fromX, fy = fromY, dx = toX - fromX, dy = toY - fromY;
        Runnable r = new Runnable() {
            @Override public void run() {
                float k = Math.min(1f, (System.currentTimeMillis() - start) / (float) dur);
                float e = k < 0.5f ? 2f * k * k : 1f - 2f * (1f - k) * (1f - k);   // ease-in-out
                WindowManager.LayoutParams p = dragon ? dragonLp : lp;
                View v = dragon ? dragonView : view;
                if (p == null || v == null) return;
                p.x = fx + Math.round(dx * e);
                p.y = fy + Math.round(dy * e);
                if (dragon) clampDragon(); else clamp();
                try { wm.updateViewLayout(v, p); } catch (Exception ignored) { }
                if (k < 1f) moveHandler.postDelayed(this, 16L);
                else if (dragon) dragonMove = null; else fishMove = null;
            }
        };
        if (dragon) dragonMove = r; else fishMove = r;
        moveHandler.post(r);
    }

    private void stopActorMoves() {
        if (fishMove != null) { moveHandler.removeCallbacks(fishMove); fishMove = null; }
        if (dragonMove != null) { moveHandler.removeCallbacks(dragonMove); dragonMove = null; }
        if (fishPoseAnim != null) { moveHandler.removeCallbacks(fishPoseAnim); fishPoseAnim = null; }
        if (dragonPoseAnim != null) { moveHandler.removeCallbacks(dragonPoseAnim); dragonPoseAnim = null; }
        stopTracks();
    }

    /**
     * 趴边满设定分钟 → 睡觉待机。
     *
     * 只看趴边时长：扒在边上不动够久，就当她睡着了，偶尔冒一句文档里的睡觉短台词。
     * 睡着的不止一位时按文档顺序一来一回；只有一位趴着就只让那位说话。
     * 触发后 3 分钟内不再重复（文档要求"偶尔显示"，不能刷屏）。
     */
    private void checkSleepStandby() {
        if (!prefs.getBoolean(KEY_RUNNING, false)) return;
        int minutes = prefs.getInt(KEY_SLEEP_AFTER_MIN, DEFAULT_SLEEP_AFTER_MIN);
        if (minutes <= 0) return;                       // 关掉了
        if (interactions.isRunning()) return;
        long now = System.currentTimeMillis();
        if (now - lastSleepStandbyMs < 180000L) return;  // 冷却 3 分钟
        long limit = minutes * 60000L;
        boolean fishAsleep = view != null && lp != null
                && view.getState() == PetView.STATE_PERCH
                && fishPerchSinceMs > 0 && now - fishPerchSinceMs >= limit;
        boolean dragonAsleep = dragonView != null && dragonLp != null
                && dragonView.getState() == DragonView.STATE_PERCH
                && dragonPerchSinceMs > 0 && now - dragonPerchSinceMs >= limit;
        if (!fishAsleep && !dragonAsleep) return;
        lastSleepStandbyMs = now;
        interactions.startSleepStandby(fishAsleep, dragonAsleep);
    }

    /**
     * 把台词丢给说话的那一位，由她自己的窗口画出来。
     *
     * 不再加"小龙女：/大肥鱼："前缀 —— 气泡就画在说话人头顶上，
     * 谁在说一目了然，前缀反而占掉本来就窄的显示宽度。
     */
    private void showMoodBubble(String actor, String text) {
        ensureBubbleLayer();
        if (bubbleLayer != null) bubbleLayer.show(actor, text);
    }

    /**
     * 说完就把气泡层整个摘掉 —— 硬规则第一条：不留常驻的全屏透明层。
     * 她只在有话要说的那几秒存在。
     */
    private void hideBubble() {
        if (bubbleLayer != null) bubbleLayer.clearActive();
        if (view != null) view.clearBubble();
        if (dragonView != null) dragonView.clearBubble();
        dropBubbleLayer();
    }

    private void dropBubbleLayer() {
        if (bubbleLayer == null) return;
        if (wm != null) {
            try { wm.removeView(bubbleLayer); } catch (Exception ignored) { }
        }
        bubbleLayer = null;
        bubbleLayerLp = null;
    }

    // ---------------- 台词气泡层 ----------------

    private void ensureBubbleLayer() {
        if (bubbleLayer != null || wm == null) return;
        BubbleLayerView layer = new BubbleLayerView(this, bubbleSource);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(layer, p);
        } catch (Exception e) {
            return;
        }
        bubbleLayer = layer;
        bubbleLayerLp = p;
    }

    /**
     * 同类型的悬浮窗按"后加的盖前面的"排层级。角色窗口是后加的，
     * 所以每次加完角色都要把气泡层摘下来重挂一次，保证台词永远在最上面。
     */
    private void raiseBubbleLayer() {
        if (wm == null) return;
        if (bubbleLayer == null) {
            ensureBubbleLayer();
            return;
        }
        try { wm.removeView(bubbleLayer); } catch (Exception ignored) { }
        try { wm.addView(bubbleLayer, bubbleLayerLp); } catch (Exception ignored) { }
    }

    /** 两个角色都收了就把气泡层也收掉，别一直挂着个全屏窗口。 */
    private void dropBubbleLayerIfIdle() {
        if (view != null || dragonView != null) return;
        dropBubbleLayer();
    }

    private void showDragonMoodBubble() {
        String[][] lines = {
                {"我在看着你呢。", "陪我说说话吧。"},
                {"和你在一起真开心。", "再陪我玩一会儿。"},
                {"我有一点点难过。", "可以安慰我吗？"},
                {"不许欺负大肥鱼！", "哼，我有点生气了。"},
                {"咦？发生什么了？", "吓我一跳。"},
                {"你不要一直看我啦。", "我、我才没有害羞。"},
                {"我有点困了。", "晚安啦。"}
        };
        int mood = Math.max(0, Math.min(lines.length - 1, dragonExpression));
        showMoodBubble("dragon", lines[mood][interactionRandom.nextInt(lines[mood].length)]);
    }

    private void showFishMoodBubble() {
        int mood = view == null ? 0 : Math.max(0, Math.min(6, view.currentFace()));
        String[][] lines = {
                {"今天也要好好吃饭。", "陪我待一会儿嘛。"},
                {"嘿嘿，今天真开心。", "一起玩吧！"},
                {"我有点难过。", "可以安慰我吗？"},
                {"不许欺负我。", "你要先哄我。"},
                {"诶？发生什么了？", "吓我一跳！"},
                {"你看什么呢？", "我才没有害羞。"},
                {"我先休息一下。", "晚安哦。"}
        };
        showMoodBubble("bigfish", lines[mood][interactionRandom.nextInt(lines[mood].length)]);
    }

    @Override public void setDragonExpression(int expression) {
        dragonExpression = expression;
        if (dragonView != null) dragonView.setExpression(expression);
    }

    @Override public void setFishExpression(int faceIndex) {
        if (view != null) view.setFace(faceIndex);
    }

    @Override public void setFishSkin(int skinIndex) {
        if (view != null) view.applySkin(skinIndex);
    }

    @Override public int currentFishSkin() {
        return view == null ? prefs.getInt(KEY_SKIN, DEFAULT_SKIN) : view.currentSkin();
    }

    /** 情绪符号：跟着台词走，不是固定一个表情。 */
    @Override public void setDragonMood(int mood) {
        if (dragonView != null) dragonView.setMood(mood);
    }

    @Override public void setFishMood(int mood) {
        if (view != null) view.setMood(mood);
    }

    /** 互动收尾时把两个气泡一起收掉，回到干净的待机。 */
    @Override public void clearBubbles() {
        if (bubbleLayer != null) bubbleLayer.clearActive();
        if (view != null) view.clearBubble();
        if (dragonView != null) dragonView.clearBubble();
    }

    @Override public void animateInteraction(int phase) {
        if (view != null) view.setInteractionPhase(phase);
        if (dragonView != null) dragonView.setInteractionPhase(phase);
    }

    @Override public void showInteractionLine(String actor, String text) {
        showMoodBubble(actor, text);
    }

    @Override public void moveDragonNearFish() {
        if (dragonLp == null || lp == null) return;
        dragonLp.x = lp.x + Math.round(lp.width * 0.58f);
        dragonLp.y = lp.y + Math.round((lp.height - dragonLp.height) * 0.5f);
        clampDragon();
        try { wm.updateViewLayout(dragonView, dragonLp); } catch (Exception ignored) { }
        resetDragonWanderTarget();     // 被互动挪走了，别让她再往旧目标走
    }

    @Override public void moveFishNearDragon() {
        if (dragonLp == null || lp == null) return;
        lp.x = dragonLp.x - Math.round(lp.width * 0.58f);
        lp.y = dragonLp.y + Math.round((dragonLp.height - lp.height) * 0.5f);
        clamp();
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
    }

    // ---- PetView.Listener ----

    @Override
    // 取本分支这份：它是上游那行 `{ hideMenu(); perchPull = 0; }` 的超集，
    // 另外带上打断互动、收气泡、把两人拉开的本分支逻辑。
    public void onDragStart() {
        hideMenu();
        boolean interrupting = interactions.isRunning();
        interactions.cancel();
        stopActorMoves();
        interactions.wake();          // 被碰了 → 醒过来，收掉 Zzz
        handler.removeCallbacks(overlapInteractionTick);
        hideBubble();
        if (interrupting) separateAfterInterruptedInteraction();
        perchPull = 0;
    }

    @Override
    public void onDrag(float dx, float dy) {
        if (view == null) return;
        lp.y += Math.round(dy);

        // 两边都留：上游新增的「趴屏幕下边时往上拉才脱离」+ 本分支的左右扒边逻辑
        // （左右扒边那段两边代码完全一致，只保留一份，避免重复）
        if (view.getState() == PetView.STATE_PERCH
                && view.getPerchEdge() == PetView.EDGE_BOTTOM) {
            // 趴在下边：往上拉才把她拉起来（左右拉不算）
            perchPull += Math.round(-dy);
            if (perchPull < 0) perchPull = 0;
            if (perchPull > screenH * 0.08f) {
                perchPull = 0;
                exitPerch();
                return;
            }
            clamp();   // 还趴着：钉回屏幕下沿
            try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
            return;
        }

        if (view.getState() == PetView.STATE_PERCH) {
            // 用 View 身上的实时方向，不读 prefs —— 和小龙女那边同一套，
            // 免得 prefs 里存的方向跟当前状态不一致时"往里 / 往外"判反。
            boolean right = view.getPerchEdge() == PetView.EDGE_RIGHT;
            // "往外拉"的方向：贴右边时往左拉才算数，贴左边时往右拉才算数
            perchPull += Math.round(right ? -dx : dx);
            if (perchPull < 0) perchPull = 0;             // 往里推不算
            if (perchPull > screenW * 0.12f) {            // 拉够远了 → 脱离边缘
                perchPull = 0;
                lp.x = right ? screenW - lp.width : 0;
                exitPerch();
                return;
            }
            // 没拉够：位置贴回边缘，但 perchPull 留着（关键，别再抹掉）
            lp.x = right ? screenW - lp.width : 0;
        } else {
            lp.x += Math.round(dx);
        }

        clamp();
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
    }

    @Override
    public void onDragEnd(float rawX, float rawY) {
        if (view == null) return;
        prefs.edit().putInt(KEY_Y, lp.y).putInt(KEY_X, lp.x).apply();
        if (view.getState() == PetView.STATE_PERCH) {
            // 兜底：松手时还停在趴边态，就确保她贴回边上（上下位置保留）
            perchPull = 0;
            // 合并要点：趴屏幕下边时不能按"贴左/贴右"重算 x —— 她本来就居中，
            // 由 clamp() 把 y 钉回屏幕下沿即可（上游新增的 EDGE_BOTTOM）。
            int eEnd = view.getPerchEdge();
            if (eEnd != PetView.EDGE_BOTTOM) {
                lp.x = (eEnd == PetView.EDGE_RIGHT) ? screenW - lp.width : 0;
            }
            clamp();
            try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
            return;
        }
        perchPull = 0;

        // 吸附判定不能用一个固定像素值：她调小之后窗口本身就窄，
        // 固定的 56dp 会占掉她宽度的一大半，导致"稍微靠近边缘"就被吸走。
        // 所以取"固定值和窗口宽度 25% 里更小的那个"。
        float snapDp = SNAP_DP * getResources().getDisplayMetrics().density;
        float snap = Math.min(snapDp, lp.width * 0.25f);
        if (lp.x <= snap) enterPerch(false, true);
        else if (lp.x + lp.width >= screenW - snap) enterPerch(true, true);
        // 拖到屏幕下沿附近 → 趴到下边去（放在左右之后，角落处优先左右）
        else if (lp.y + lp.height >= screenH - Math.min(snapDp, lp.height * 0.30f)) enterPerchBottom(true);
        else applyLayout();
        startOverlapInteractionIfNeeded();
    }

    /**
     * 单击换表情在 PetView 里做；这里负责音效和缺素材时的兜底提示。
     */
    @Override
    public void onTap() {
        if (view != null && view.faceCount() == 0) {
            Toast.makeText(this, "表情素材没加载成功", Toast.LENGTH_SHORT).show();
        }
        interactions.wake();          // 点一下 = 叫醒
        showFishMoodBubble();
        startOverlapInteractionIfNeeded();
        if (!soundOn) return;
        playDuckAndWarn();
    }

    @Override public void onDragonDragStart() {
        hideMenu();
        boolean interrupting = interactions.isRunning();
        interactions.cancel();
        stopActorMoves();
        interactions.wake();
        handler.removeCallbacks(overlapInteractionTick);
        hideBubble();
        if (interrupting) separateAfterInterruptedInteraction();
        dragonPerchPull = 0;
    }

    @Override public void onDragonDrag(float dx, float dy) {
        if (dragonLp == null || dragonView == null) return;
        dragonLp.y += Math.round(dy);

        if (dragonView.getState() == DragonView.STATE_PERCH
                && dragonView.getPerchEdge() == DragonView.EDGE_BOTTOM) {
            // 趴在下边：往上拉才把她拉起来（左右拉不算）—— 和大肥鱼同一套
            dragonPerchPull += Math.round(-dy);
            if (dragonPerchPull < 0) dragonPerchPull = 0;
            if (dragonPerchPull > screenH * 0.08f) {
                dragonPerchPull = 0;
                exitDragonPerch();
                return;
            }
            clampDragon();   // 还趴着：钉回屏幕下沿
            try { wm.updateViewLayout(dragonView, dragonLp); } catch (Exception ignored) { }
            return;
        }

        if (dragonView.getState() == DragonView.STATE_PERCH) {
            boolean right = dragonView.getPerchEdge() == DragonView.EDGE_RIGHT;
            dragonPerchPull += Math.round(right ? -dx : dx);
            if (dragonPerchPull < 0) dragonPerchPull = 0;
            if (dragonPerchPull > screenW * 0.12f) {
                dragonPerchPull = 0;
                dragonLp.x = right ? screenW - dragonLp.width : 0;
                exitDragonPerch();
                return;
            }
            dragonLp.x = right ? screenW - dragonLp.width : 0;
        } else {
            dragonLp.x += Math.round(dx);
        }

        clampDragon();
        try { wm.updateViewLayout(dragonView, dragonLp); } catch (Exception ignored) { }
    }

    @Override public void onDragonDragEnd(float rawX, float rawY) {
        if (dragonLp == null || dragonView == null) return;
        prefs.edit().putInt(KEY_DRAGON_X, dragonLp.x).putInt(KEY_DRAGON_Y, dragonLp.y).apply();
        if (dragonView.getState() == DragonView.STATE_PERCH) {
            // 兜底：松手时还扒着，就确保她还贴在边上（上下位置保留）
            dragonPerchPull = 0;
            // 趴屏幕下边时不能按"贴左/贴右"重算 x —— 她本来就在下沿，
            // 由 clampDragon() 把 y 钉回下沿即可（和大肥鱼 onDragEnd 同一处理）
            if (dragonView.getPerchEdge() != DragonView.EDGE_BOTTOM) {
                dragonLp.x = dragonView.getPerchEdge() == DragonView.EDGE_RIGHT
                        ? screenW - dragonLp.width : 0;
            }
            clampDragon();
            try { wm.updateViewLayout(dragonView, dragonLp); } catch (Exception ignored) { }
            return;
        }
        dragonPerchPull = 0;

        float snapDp = SNAP_DP * getResources().getDisplayMetrics().density;
        float snap = Math.min(snapDp, dragonLp.width * 0.25f);
        if (dragonLp.x <= snap) enterDragonPerch(false, true);
        else if (dragonLp.x + dragonLp.width >= screenW - snap) enterDragonPerch(true, true);
        // 拖到屏幕下沿附近 → 趴到下边去（放在左右之后，角落处优先左右）
        else if (dragonLp.y + dragonLp.height >= screenH - Math.min(snapDp, dragonLp.height * 0.30f)) {
            enterDragonPerchBottom(true);
        }
        else applyDragonLayout();
        startOverlapInteractionIfNeeded();
        armOverlapInteraction();
    }

    @Override public void onDragonTap() {
        interactions.wake();          // 点一下 = 叫醒
        setDragonExpression((dragonExpression + 1) % 7);
        showDragonMoodBubble();
        if (soundOn) playDuckAndWarn();
    }

    @Override public void onDragonLongPress() {
        showDragonMenu();
    }

    private void showDragonMenu() {
        hideMenu();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF01B2033);
        bg.setCornerRadius(dp(14));
        row.setBackground(bg);
        int pad = Math.round(dp(5));
        row.setPadding(pad, pad, pad, pad);
        row.addView(menuButton("互动", v -> { hideMenu(); startRandomInteraction(); }));
        row.addView(menuButton("表情", v -> {
            setDragonExpression((dragonExpression + 1) % 7);
            hideMenu();
        }));
        row.addView(menuButton("关闭龙女", v -> {
            hideMenu();
            // 只关小龙女。大肥鱼要是还在，服务继续跑着。
            prefs.edit().putBoolean(KEY_DRAGON_ENABLED, false).apply();
            removeDragon();
            if (!prefs.getBoolean(KEY_FISH_ENABLED, true)) {
                prefs.edit().putBoolean(KEY_RUNNING, false).apply();
                stopSelf();
            }
        }));
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        menuLp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        menuLp.gravity = Gravity.TOP | Gravity.START;
        menuView = row;
        try { wm.addView(menuView, menuLp); }
        catch (Exception ignored) { menuView = null; menuLp = null; return; }
        menuView.post(() -> {
            if (dragonLp == null || menuView == null) return;
            menuLp.x = Math.max(0, Math.min(screenW - menuView.getWidth(), dragonLp.x));
            menuLp.y = Math.max(0, dragonLp.y - menuView.getHeight() - Math.round(dp(8)));
            try { wm.updateViewLayout(menuView, menuLp); } catch (Exception ignored) { }
        });
        handler.postDelayed(menuAutoHide, MENU_AUTO_HIDE_MS);
    }

    // ---- 音效 ----

    /**
     * 音效池。USAGE_MEDIA → STREAM_MUSIC（媒体音量）。
     *
     * 这里以前是 USAGE_ASSISTANCE_SONIFICATION，它落在 STREAM_SYSTEM。
     * MIUI / HyperOS 上"系统音效"那条通道在静音模式、或用户关掉触摸提示音后是 0，
     * 于是 play() 返回成功却完全没声音 —— 正是"点她有反应、就是不出声"。
     */
    private void createSoundPool() {
        soundPool = new SoundPool.Builder()
                .setMaxStreams(3)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        soundPool.setOnLoadCompleteListener((pool, sampleId, status) -> {
            for (int i = 0; i < duckIds.length; i++) {
                if (duckIds[i] == sampleId) duckLoadStatus[i] = status;
            }
            android.util.Log.d("WhalePetAudio", "loadComplete sample=" + sampleId
                    + " status=" + status);
        });
    }

    /** load() 是异步的，这里返回 0 说明这一步就失败了 —— 下次点击会再试一次 */
    private void loadDuckSounds() {
        if (soundPool == null) return;
        duckIds[0] = soundPool.load(this, R.raw.duck1, 1);
        duckIds[1] = soundPool.load(this, R.raw.duck2, 1);
        duckIds[2] = soundPool.load(this, R.raw.duck3, 1);
        duckIds[3] = soundPool.load(this, R.raw.duck4, 1);
        duckIds[4] = soundPool.load(this, R.raw.duck5, 1);
        duckIds[5] = soundPool.load(this, R.raw.duck6, 1);
        duckIds[6] = soundPool.load(this, R.raw.duck7, 1);
        duckIds[7] = soundPool.load(this, R.raw.duck8, 1);
        android.util.Log.d("WhalePetAudio", "load ids="
                + duckIds[0] + "," + duckIds[1] + "," + duckIds[2]);
    }

    /**
     * 随机播一声鸭子叫，返回 SoundPool.play() 的 streamId（0 = 没播出去）。
     * 采样还没 load 完时补一次 load —— 否则刚启动那几下点击是哑的。
     */
    private int playDuckSound() {
        if (soundPool == null) return 0;
        // 放用户选中的那一个（原来是从 8 个里随机挑 —— 用户没得选）
        int sel = Math.max(0, Math.min(duckIds.length - 1, duckSel));
        int id = duckIds[sel];
        if (id == 0) {
            loadDuckSounds();
            return 0;
        }
        int streamId = soundPool.play(id, 1f, 1f, 1, 0, 1f);
        android.util.Log.d("WhalePetAudio", "tap sample=" + id + " streamId=" + streamId
                + " soundOn=" + soundOn + " " + volumeReport());
        if (streamId == 0) loadDuckSounds();
        return streamId;
    }

    private void playDuckAndWarn() {
        int streamId = playDuckSound();
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        int vol = am.getStreamVolume(SOUND_STREAM);
        boolean muted = false;
        try { muted = am.isStreamMute(SOUND_STREAM); } catch (Exception ignored) { }
        // 只有确实查出问题才提示；正常出声时保持安静
        if (streamId != 0 && !muted && vol > 0) return;
        hintSilent(streamId, vol, muted);
    }

    /**
     * 没响的时候把原因直接说清楚，别让用户对着一个哑巴开关发呆。
     * 一分钟最多提示一次，免得连点时刷屏。
     */
    private void hintSilent(int streamId, int vol, boolean muted) {
        long now = System.currentTimeMillis();
        if (now - lastSilentHintMs < 60_000L) return;
        lastSilentHintMs = now;

        String why;
        if (streamId == 0) {
            why = "音效还没加载好，再点一下试试";
        } else if (muted) {
            why = "媒体音量被静音了 —— 关掉静音模式，或关掉 MIUI「静音时同时静音媒体」";
        } else if (vol <= 0) {
            why = "媒体音量是 0，按一下音量键调大就有声了";
        } else {
            why = "声音已经送出去了，检查是不是连着蓝牙耳机";
        }
        Toast.makeText(this, "鸭子叫没响：" + why, Toast.LENGTH_LONG).show();
    }

    private String volumeReport() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return "audio=null";
        StringBuilder sb = new StringBuilder("stream=").append(SOUND_STREAM)
                .append(" vol=").append(am.getStreamVolume(SOUND_STREAM))
                .append('/').append(am.getStreamMaxVolume(SOUND_STREAM));
        try {
            sb.append(" muted=").append(am.isStreamMute(SOUND_STREAM));
        } catch (Exception ignored) { }
        sb.append(" ringer=").append(am.getRingerMode());
        return sb.toString();
    }

    /**
     * 音效诊断：先真的播一声（数字看不出问题，听得到才算数），
     * 再把音频通道 / 音量 / 静音状态 / 本次播放结果一起报出来。
     */
    private void runSoundDiagnostic() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int streamId = playDuckSound();

        int vol = am == null ? -1 : am.getStreamVolume(SOUND_STREAM);
        int volMax = am == null ? -1 : am.getStreamMaxVolume(SOUND_STREAM);
        boolean muted = false;
        try { muted = am != null && am.isStreamMute(SOUND_STREAM); } catch (Exception ignored) { }
        int ringer = am == null ? -1 : am.getRingerMode();
        String ringerText = ringer == AudioManager.RINGER_MODE_SILENT ? "静音"
                : ringer == AudioManager.RINGER_MODE_VIBRATE ? "震动" : "正常";

        String msg = "音效诊断\n"
                + "sound_on=" + soundOn
                + "　加载=" + duckLoadStatus[0] + "," + duckLoadStatus[1] + "," + duckLoadStatus[2] + "\n"
                + "音频通道=媒体音量(STREAM_MUSIC)\n"
                + "媒体音量=" + vol + "/" + volMax + (muted ? "（已被系统静音）" : "") + "\n"
                + "铃声模式=" + ringerText + "\n"
                + "本次播放=" + (streamId == 0 ? "失败" : "成功 streamId=" + streamId) + "\n"
                + diagAdvice(streamId, vol, muted);
        android.util.Log.d("WhalePetAudio", msg.replace('\n', ' '));
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private String diagAdvice(int streamId, int vol, boolean muted) {
        if (streamId == 0) return "→ 音效没加载完或加载失败，再点一次";
        if (muted) return "→ 媒体通道被静音：关静音模式 / 关 MIUI「静音时同时静音媒体」";
        if (vol <= 0) return "→ 媒体音量是 0：按音量键调大";
        return "→ 通道与音量都正常。若仍听不到，看是否连着蓝牙耳机";
    }

    // ---- 通知 ----

    // ---------------- 自动溜达 ----------------
    // 她的位置是"窗口位置"，只有 Service 能动。所以这里挪窗口，
    // 同时告诉 View "正在走"（由 View 加摇晃和颠簸）。
    private boolean wanderOn = true;
    private final Handler wanderHandler = new Handler(Looper.getMainLooper());
    private float wanderX = 0f, wanderTargetX = -1f;
    private long wanderWaitUntil = 0L;
    private boolean wanderStarted = false;

    private final Runnable wanderTick = new Runnable() {
        @Override public void run() {
            if (!wanderOn || view == null || lp == null) return;
            if (!prefs.getBoolean(KEY_RUNNING, false)) return;
            // 趴边 / 正在被拖 / 刚被碰过 —— 都不动
            if (view.getState() != PetView.STATE_HOVER
                    || view.isDragging() || view.msSinceTouch() < 5000) {
                view.setWalking(false);
                wanderHandler.postDelayed(this, 300);
                return;
            }
            long now = System.currentTimeMillis();
            if (now < wanderWaitUntil) {
                view.setWalking(false);
                wanderHandler.postDelayed(this, 150);
                return;
            }
            if (wanderTargetX < 0f) {                 // 挑一个新目标
                wanderX = lp.x;
                int margin = Math.round(dp(14));
                int hi = Math.max(margin + 1, screenW - lp.width - margin);
                wanderTargetX = margin + rnd.nextInt(hi - margin);
                wanderWaitUntil = now + 800;
                wanderHandler.postDelayed(this, 150);
                return;
            }
            float d = wanderTargetX - wanderX;
            if (Math.abs(d) < 3f) {                   // 到站，歇一会儿
                wanderX = wanderTargetX;
                wanderTargetX = -1f;
                wanderWaitUntil = now + 1200 + rnd.nextInt(2400);
                view.setWalking(false);
                wanderHandler.postDelayed(this, 120);
                return;
            }
            wanderX += Math.signum(d) * Math.min(4f, Math.abs(d));
            lp.x = Math.round(wanderX);
            try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
            view.setWalking(true);
            wanderHandler.postDelayed(this, 16);
        }
    };

    /**
     * 按当前开关重新挂一次溜达定时器。
     *
     * wanderTick 退出时不会再排下一次（这是有意的：关掉就该停），
     * 但那样一来它就无法自己恢复 —— 必须由外部重新挂上。
     */
    private void restartWander() {
        wanderHandler.removeCallbacks(wanderTick);
        wanderStarted = false;
        if (!wanderOn) {
            if (view != null) view.setWalking(false);   // 立刻停下，别等下一次 tick
            return;
        }
        if (view != null && lp != null) {
            // 本分支新增（上游没有）：
            // 演出期间窗口被剧情挪走了，而 wanderX 还停在开演之前的位置 ——
            // 不重新对齐的话，溜达一恢复她就会"瞬移"回旧位置，
            // 刚拉开的那点距离也会被抹掉。
            wanderX = lp.x;
            wanderTargetX = -1f;
            wanderStarted = true;
            wanderHandler.postDelayed(wanderTick, 4000);
        }
    }

    // ==== 以下整段（小龙女的自动溜达）是本分支新增，上游该处没有任何内容，全部保留 ====
    // ---- 小龙女的自动溜达：和大肥鱼同一套代码，各用各的窗口和定时器 ----

    private boolean dragonWanderOn = true;
    private final Handler dragonWanderHandler = new Handler(Looper.getMainLooper());
    private float dragonWanderX = 0f, dragonWanderTargetX = -1f;
    private long dragonWanderWaitUntil = 0L;
    private boolean dragonWanderStarted = false;

    private final Runnable dragonWanderTick = new Runnable() {
        @Override public void run() {
            if (!dragonWanderOn || dragonView == null || dragonLp == null) return;
            if (!prefs.getBoolean(KEY_RUNNING, false)) return;
            // 趴边 / 正在被拖 / 刚被碰过 / 正在跟大肥鱼互动 —— 都不动。
            // 互动要自己挪她的位置，这里再插一脚会打架。
            if (dragonView.getState() != DragonView.STATE_HOVER
                    || dragonView.isDragging() || dragonView.msSinceTouch() < 5000
                    || interactions.isRunning()) {
                dragonView.setWalking(false);
                dragonWanderHandler.postDelayed(this, 300);
                return;
            }
            long now = System.currentTimeMillis();
            if (now < dragonWanderWaitUntil) {
                dragonView.setWalking(false);
                dragonWanderHandler.postDelayed(this, 150);
                return;
            }
            if (dragonWanderTargetX < 0f) {                 // 挑一个新目标
                dragonWanderX = dragonLp.x;
                int margin = Math.round(dp(14));
                int hi = Math.max(margin + 1, screenW - dragonLp.width - margin);
                dragonWanderTargetX = margin + rnd.nextInt(hi - margin);
                dragonWanderWaitUntil = now + 800;
                dragonWanderHandler.postDelayed(this, 150);
                return;
            }
            float d = dragonWanderTargetX - dragonWanderX;
            if (Math.abs(d) < 3f) {                          // 到站，歇一会儿
                dragonWanderX = dragonWanderTargetX;
                dragonWanderTargetX = -1f;
                dragonWanderWaitUntil = now + 1200 + rnd.nextInt(2400);
                dragonView.setWalking(false);
                dragonWanderHandler.postDelayed(this, 120);
                return;
            }
            dragonWanderX += Math.signum(d) * Math.min(4f, Math.abs(d));
            dragonLp.x = Math.round(dragonWanderX);
            clampDragon();
            try { wm.updateViewLayout(dragonView, dragonLp); } catch (Exception ignored) { }
            dragonView.setWalking(true);
            dragonWanderHandler.postDelayed(this, 16);
        }
    };

    /** 按开关重新挂一次小龙女的溜达定时器（她可能和大肥鱼各开各的）。 */
    private void restartDragonWander() {
        dragonWanderHandler.removeCallbacks(dragonWanderTick);
        dragonWanderStarted = false;
        if (!dragonWanderOn) {
            if (dragonView != null) dragonView.setWalking(false);
            return;
        }
        if (dragonView != null && dragonLp != null) {
            // 同 restartWander()：演出把她挪走之后必须重新对齐，否则会瞬移回旧位置。
            dragonWanderX = dragonLp.x;
            dragonWanderTargetX = -1f;
            dragonWanderStarted = true;
            dragonWanderHandler.postDelayed(dragonWanderTick, 4000);
        }
    }

    /** 互动把她挪走之后，重新挑目标，别让她再往旧位置走。 */
    private void resetDragonWanderTarget() {
        dragonWanderTargetX = -1f;
        dragonWanderWaitUntil = System.currentTimeMillis() + 2500;
        if (dragonView != null) dragonView.setWalking(false);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "鲸宠",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Intent hide = new Intent(this, PetService.class).setAction(ACTION_HIDE);
        PendingIntent piHide = PendingIntent.getService(this, 1, hide, piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("白饭鱼&小龙女")
                .setContentText("正在你桌面上陪着你 · 长按她们可以调整或关闭")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "收回", piHide).build());
        return b.build();
    }
}
