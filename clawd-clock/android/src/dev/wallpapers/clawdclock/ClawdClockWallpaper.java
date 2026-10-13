package dev.wallpapers.clawdclock;

import android.app.AlarmManager;
import android.content.SharedPreferences;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * Native port of clawd-clock/index.html. Everything is drawn in a 1080x2400
 * design space and scaled to cover the screen (like SVG "xMidYMid slice").
 */
public class ClawdClockWallpaper extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new ClawdEngine();
    }

    private static final float DW = 1080f, DH = 2400f;
    private static final long FRAME_MS = 80;

    // Seven-segment geometry (same numbers as the HTML version).
    private static final float SW = 150, SH = 240, ST = 30, TOP = 855;
    private static final float[] DIGIT_X = {165, 335, 595, 765};
    private static final float[][] SEG = {
            {ST, 0, SW - 2 * ST, ST},                               // a
            {SW - ST, ST, ST, SH / 2 - ST - ST / 2},                // b
            {SW - ST, SH / 2 + ST / 2, ST, SH / 2 - ST - ST / 2},   // c
            {ST, SH - ST, SW - 2 * ST, ST},                         // d
            {0, SH / 2 + ST / 2, ST, SH / 2 - ST - ST / 2},         // e
            {0, ST, ST, SH / 2 - ST - ST / 2},                      // f
            {ST, SH / 2 - ST / 2, SW - 2 * ST, ST},                 // g
    };
    private static final String[] DIGITS = {
            "abcdef", "bc", "abdeg", "abcdg", "bcfg", "acdfg", "acdefg", "abc", "abcdefg", "abcdfg"};

    private static final String[] DAYS = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};
    private static final String[] MONTHS = {
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};

    // {x, y, size}
    private static final int[][] STARS = {
            {90, 260, 8}, {240, 420, 6}, {470, 190, 10}, {700, 330, 6}, {930, 220, 8}, {860, 520, 6},
            {150, 640, 6}, {990, 690, 10}, {60, 1050, 6}, {1020, 1420, 8}, {40, 1650, 10}, {1000, 1900, 6},
            {180, 2050, 8}, {560, 2120, 6}, {880, 2200, 10}, {330, 2280, 6}, {620, 560, 6}, {380, 600, 8}};

    private static final Map<Character, String> FONT = new HashMap<>();
    static {
        String[] f = {
                "A.###.#...#######...##...#", "B####.#...#####.#...#####.", "C.#####....#....#.....####",
                "D####.#...##...##...#####.", "E######....####.#....#####", "F######....####.#....#....",
                "G.#####....#.####...#.####", "H#...##...#######...##...#", "I#####..#....#....#..#####",
                "J..###...#....#.#..#..##..", "L#....#....#....#....#####", "M#...###.###.#.##...##...#",
                "N#...###..##.#.##..###...#", "O.###.#...##...##...#.###.", "P####.#...#####.#....#....",
                "R####.#...#####.#..#.#...#", "S.#####.....###.....#####.", "T#####..#....#....#....#..",
                "U#...##...##...##...#.###.", "V#...##...##...#.#.#...#..", "W#...##...##.#.###.###...#",
                "Y#...#.#.#...#....#....#..",
                "0.###.#..###.#.###..#.###.", "1..#...##....#....#...###.", "2####.....#.###.#....#####",
                "3####.....#.###.....#####.", "4#...##...######....#....#", "5######....####.....#####.",
                "6.###.#....####.#...#.###.", "7#####....#...#...#....#..", "8.###.#...#.###.#...#.###.",
                "9.###.#...#.####....#.###.", ":.......#.........#.......",
                "%##..###.#...#...#.###..##", "\u00b0.#...#.#...#..............",
                "-...........###...........",
        };
        for (String s : f) FONT.put(s.charAt(0), s.substring(1));
    }

    // 9x9 pixel icons for the status chips.
    private static final String[] SUN = {
            "Y...Y...Y", ".Y.....Y.", "...YYY...", "..YYYYY..", "Y.YYYYY.Y",
            "..YYYYY..", "...YYY...", ".Y.....Y.", "Y...Y...Y"};
    private static final String[] MOON = {
            "..MMMM...", ".MMM.....", "MMM......", "MMM......", "MMM......",
            "MMMM....M", ".MMMMMMM.", "..MMMMM..", "........."};
    private static final String[] PARTLY = {
            "..Y.Y....", "Y.YYY....", ".YYYYWW..", "YYYWWWWW.", ".YWWWWWWW",
            "WWWWWWWWW", "WWWWWWWWW", ".WWWWWWW.", "........."};
    private static final String[] CLOUD = {
            ".........", "...WWW...", "..WWWWW..", ".WWWWWWW.", "WWWWWWWWW",
            "WWWWWWWWW", ".WWWWWWW.", ".........", "........."};
    private static final String[] RAIN = {
            "...GGG...", "..GGGGG..", ".GGGGGGG.", "GGGGGGGGG", ".GGGGGGG.",
            ".........", ".B..B..B.", "B..B..B..", "........."};
    private static final String[] SNOW = {
            "...WWW...", "..WWWWW..", ".WWWWWWW.", "WWWWWWWWW", ".WWWWWWW.",
            ".........", "S...S...S", "..S...S..", "S...S...S"};
    private static final String[] STORM = {
            "...GGG...", "..GGGGG..", ".GGGGGGG.", "GGGGGGGGG", ".GGGYGGG.",
            "...YY....", "....YY...", "...YY....", "..Y......"};
    private static final String[] FOG = {
            ".........", "WWWWWWW..", ".........", "..WWWWWWW", ".........",
            "WWWWWWW..", ".........", "..WWWWWWW", "........."};
    private static final String[] BELL = {
            "....O....", "..OOOOO..", ".OOOOOOO.", ".OOOOOOO.", ".OOOOOOO.",
            ".OOOOOOO.", "OOOOOOOOO", ".........", "...OOO..."};
    private static final Map<Character, Integer> ICON_COLORS = new HashMap<>();
    static {
        ICON_COLORS.put('Y', 0xFFFFC857);
        ICON_COLORS.put('M', 0xFFF0E6C8);
        ICON_COLORS.put('W', 0xFFD9D4CC);
        ICON_COLORS.put('G', 0xFF8F8780);
        ICON_COLORS.put('B', 0xFF6FA8DC);
        ICON_COLORS.put('S', 0xFFFFFFFF);
        ICON_COLORS.put('O', 0xFFFF9A6C);
    }

    private static final int CHIP_TEXT = 0xFFFF9A6C;
    private static final long WEATHER_EVERY_MS = 30 * 60 * 1000, WEATHER_RETRY_MS = 5 * 60 * 1000;
    private static final long STATUS_EVERY_MS = 15 * 1000;

    private class ClawdEngine extends Engine {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable frame = this::drawFrame;
        private boolean visible;
        private int width, height;

        // status chip state
        private int battery = -1;
        private boolean charging;
        private long nextAlarm;          // epoch ms, 0 = none
        private long lastStatusPoll;
        private volatile boolean hasWeather, isDay = true, fetching;
        private volatile int tempC, weatherCode;
        private volatile long lastWeatherAttempt;
        private SharedPreferences prefs;

        private final Paint fill = new Paint();
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bg = new Paint();
        private final Paint halo = new Paint();
        private final Path casing = poly(130,780, 950,780, 950,800, 970,800, 970,820, 990,820, 990,1180,
                970,1180, 970,1200, 950,1200, 950,1220, 130,1220, 130,1200, 110,1200, 110,1180, 90,1180,
                90,820, 110,820, 110,800, 130,800);
        private final Path screen = poly(150,830, 930,830, 930,840, 940,840, 940,1110, 930,1110, 930,1120,
                150,1120, 150,1110, 140,1110, 140,840, 150,840);

        ClawdEngine() {
            bg.setShader(new LinearGradient(0, 0, 0, DH,
                    new int[]{0xFF0D0A09, 0xFF1C1512, 0xFF0D0A09}, new float[]{0f, .45f, 1f},
                    Shader.TileMode.CLAMP));
            halo.setShader(new RadialGradient(540, 1000, 760,
                    new int[]{0x38D97757, 0x12D97757, 0x00D97757}, new float[]{0f, .5f, 1f},
                    Shader.TileMode.CLAMP));
            glow.setColor(0xFFFF9A6C);
            glow.setMaskFilter(new BlurMaskFilter(20, BlurMaskFilter.Blur.NORMAL));

            prefs = getSharedPreferences("weather", MODE_PRIVATE);
            if (prefs.contains("temp")) {
                tempC = prefs.getInt("temp", 0);
                weatherCode = prefs.getInt("code", 0);
                isDay = prefs.getBoolean("day", true);
                hasWeather = true;
                // cached weather is shown immediately but still refreshed if stale
                lastWeatherAttempt = prefs.getLong("at", 0) - System.currentTimeMillis() + SystemClock.elapsedRealtime();
            } else {
                lastWeatherAttempt = -WEATHER_EVERY_MS;
            }
        }

        private void pollStatus() {
            long now = SystemClock.elapsedRealtime();
            if (now - lastStatusPoll >= STATUS_EVERY_MS || battery < 0) {
                lastStatusPoll = now;
                BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
                if (bm != null) {
                    battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                    charging = bm.isCharging();
                }
                AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
                AlarmManager.AlarmClockInfo next = am != null ? am.getNextAlarmClock() : null;
                nextAlarm = next != null ? next.getTriggerTime() : 0;
            }
            long since = now - lastWeatherAttempt;
            boolean due = since >= WEATHER_EVERY_MS || (!hasWeather && since >= WEATHER_RETRY_MS);
            if (due && !fetching) {
                fetching = true;
                lastWeatherAttempt = now;
                new Thread(this::fetchWeather, "clawd-weather").start();
            }
        }

        /** Rough location from the public IP, then current conditions from Open-Meteo. */
        private void fetchWeather() {
            try {
                JSONObject geo = new JSONObject(get("https://get.geojs.io/v1/ip/geo.json"));
                String lat = geo.getString("latitude"), lon = geo.getString("longitude");
                JSONObject cur = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                        + "&longitude=" + lon + "&current=temperature_2m,weather_code,is_day"))
                        .getJSONObject("current");
                tempC = (int) Math.round(cur.getDouble("temperature_2m"));
                weatherCode = cur.getInt("weather_code");
                isDay = cur.getInt("is_day") == 1;
                hasWeather = true;
                prefs.edit().putInt("temp", tempC).putInt("code", weatherCode).putBoolean("day", isDay)
                        .putLong("at", System.currentTimeMillis()).apply();
            } catch (Exception ignored) {
                // offline or service down: keep the last known weather, retry later
                if (hasWeather) lastWeatherAttempt -= WEATHER_EVERY_MS - WEATHER_RETRY_MS;
            } finally {
                fetching = false;
            }
        }

        private String get(String url) throws Exception {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toString("UTF-8");
            } finally {
                conn.disconnect();
            }
        }

        @Override
        public void onVisibilityChanged(boolean v) {
            visible = v;
            if (v) drawFrame(); else handler.removeCallbacks(frame);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            width = w;
            height = h;
            drawFrame();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            visible = false;
            handler.removeCallbacks(frame);
        }

        @Override
        public void onDestroy() {
            handler.removeCallbacks(frame);
        }

        private void drawFrame() {
            handler.removeCallbacks(frame);
            SurfaceHolder holder = getSurfaceHolder();
            Canvas c = null;
            try {
                c = Build.VERSION.SDK_INT >= 28 ? holder.lockHardwareCanvas() : holder.lockCanvas();
                if (c != null) draw(c);
            } catch (IllegalStateException | IllegalArgumentException ignored) {
                // surface went away mid-frame
            } finally {
                if (c != null) {
                    try { holder.unlockCanvasAndPost(c); } catch (IllegalStateException ignored) { }
                }
            }
            if (visible) handler.postDelayed(frame, FRAME_MS);
        }

        private void draw(Canvas c) {
            long t = SystemClock.uptimeMillis();
            Calendar now = Calendar.getInstance();

            c.drawColor(0xFF0D0A09);
            float s = Math.max(width / DW, height / DH);
            c.save();
            c.translate((width - DW * s) / 2f, (height - DH * s) / 2f);
            c.scale(s, s);

            c.drawRect(0, 0, DW, DH, bg);
            c.drawRect(0, 0, DW, DH, halo);
            drawStars(c, t);

            // ground shadow
            rect(c, 230, 1792, 620, 16, 0x73000000);
            rect(c, 270, 1808, 540, 10, 0x40000000);

            pollStatus();
            drawChips(c, now);

            // pixel "bob": up 10 units for the second half of a 2.4s cycle
            c.save();
            if ((t % 2400) >= 1200) c.translate(0, -10);
            drawClock(c, now);
            drawClawd(c, t);
            c.restore();

            c.restore();
        }

        private void drawStars(Canvas c, long t) {
            for (int i = 0; i < STARS.length; i++) {
                int x = STARS[i][0], y = STARS[i][1], sz = STARS[i][2];
                double phase = (t / 3200.0) + (i * 0.73 / 3.2);
                float a = (float) (0.15 + 0.55 * (0.5 - 0.5 * Math.cos(2 * Math.PI * phase)));
                int col = ((int) (a * 255) << 24) | 0xF0EEE6;
                rect(c, x, y, sz, sz, col);
                if (sz >= 10) {
                    rect(c, x - sz, y, sz, sz, col);
                    rect(c, x + sz, y, sz, sz, col);
                    rect(c, x, y - sz, sz, sz, col);
                    rect(c, x, y + sz, sz, sz, col);
                }
            }
        }

        private void drawClock(Canvas c, Calendar now) {
            // buttons
            rect(c, 250, 752, 100, 28, 0xFFD97757);
            rect(c, 250, 752, 100, 8, 0xFFEB9A7A);
            rect(c, 730, 752, 100, 28, 0xFF5A514A);
            rect(c, 730, 752, 100, 8, 0xFF6E645C);

            // casing
            fill.setColor(0xFF3A332E);
            c.drawPath(casing, fill);
            rect(c, 130, 780, 820, 10, 0xFF4D4540);
            rect(c, 130, 1210, 820, 10, 0xFF26201C);

            // screen
            fill.setColor(0xFF140E0B);
            c.drawPath(screen, fill);

            // ghost segments
            fill.setColor(0xFF2B1A13);
            for (float dx : DIGIT_X)
                for (float[] sg : SEG) c.drawRect(dx + sg[0], TOP + sg[1], dx + sg[0] + sg[2], TOP + sg[1] + sg[3], fill);

            // lit segments: blurred pass for the glow, then crisp pass on top
            String hhmm = String.format("%02d%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE));
            boolean colonOn = now.get(Calendar.SECOND) % 2 == 0;
            for (int pass = 0; pass < 2; pass++) {
                Paint p = pass == 0 ? glow : fill;
                fill.setColor(0xFFFF9A6C);
                for (int i = 0; i < 4; i++) {
                    for (char sc : DIGITS[hhmm.charAt(i) - '0'].toCharArray()) {
                        float[] sg = SEG[sc - 'a'];
                        float x = DIGIT_X[i] + sg[0], y = TOP + sg[1];
                        c.drawRect(x, y, x + sg[2], y + sg[3], p);
                    }
                }
                if (colonOn) {
                    c.drawRect(525, 915, 555, 945, p);
                    c.drawRect(525, 1005, 555, 1035, p);
                }
            }
            if (!colonOn) {
                rect(c, 525, 915, 30, 30, 0xFF2B1A13);
                rect(c, 525, 1005, 30, 30, 0xFF2B1A13);
            }

            // scanlines + glare
            fill.setColor(0x47000000);
            for (float y = 830; y < 1120; y += 8) c.drawRect(140, y, 940, Math.min(y + 3, 1120), fill);
            rect(c, 170, 848, 120, 8, 0x0DFFFFFF);
            rect(c, 170, 856, 8, 40, 0x0DFFFFFF);

            // date + seconds strip
            String date = DAYS[now.get(Calendar.DAY_OF_WEEK) - 1] + " "
                    + String.format("%02d", now.get(Calendar.DAY_OF_MONTH)) + " "
                    + MONTHS[now.get(Calendar.MONTH)];
            pixText(c, date, 220, 1149, 7, 0xFFC9876A, false);
            pixText(c, String.format(":%02d", now.get(Calendar.SECOND)), 860, 1149, 7, 0xFFC9876A, true);
        }

        private void drawClawd(Canvas c, long t) {
            // arms
            rect(c, 190, 1400, 50, 50, 0xFFD97757);
            rect(c, 140, 1180, 50, 270, 0xFFD97757);
            rect(c, 140, 1180, 12, 270, 0xFFE8906F);
            rect(c, 840, 1400, 50, 50, 0xFFD97757);
            rect(c, 890, 1180, 50, 270, 0xFFD97757);
            rect(c, 928, 1180, 12, 270, 0xFFB9603F);
            // legs
            for (int x : new int[]{290, 390, 640, 740}) rect(c, x, 1700, 50, 90, 0xFFC4694A);
            // body
            rect(c, 240, 1300, 600, 400, 0xFFD97757);
            rect(c, 240, 1300, 600, 25, 0xFFEA9877);
            rect(c, 240, 1675, 600, 25, 0xFFB9603F);
            // eyes, blinking once every 5s
            long p = t % 5000;
            float eh = (p > 4780 && p < 4920) ? 10 : 100;
            float ey = 1400 + (100 - eh) / 2;
            rect(c, 340, ey, 50, eh, 0xFF14100E);
            rect(c, 690, ey, 50, eh, 0xFF14100E);
        }

        private void drawChips(Canvas c, Calendar now) {
            final float u = 6, cy = 1894;
            float[] xs = {45, 390, 735};
            for (float x : xs) {
                fill.setColor(0xFF3A332E);
                c.drawPath(poly(x + 8, 1846, x + 292, 1846, x + 292, 1854, x + 300, 1854, x + 300, 1934,
                        x + 292, 1934, x + 292, 1942, x + 8, 1942, x + 8, 1934, x, 1934, x, 1854, x + 8, 1854), fill);
                rect(c, x + 10, 1856, 280, 76, 0xFF140E0B);
            }

            // weather
            String temp = hasWeather ? tempC + "\u00b0C" : "--\u00b0C";
            drawIconChip(c, xs[0], cy, u, weatherIcon(), temp);

            // battery: 11x6 body + 1x2 terminal, fill in 9 columns
            String pct = battery >= 0 ? battery + "%" : "--%";
            float tw = textWidth(pct, u), total = 12 * u + 3 * u + tw;
            float bx = xs[1] + (300 - total) / 2, by = cy - 3 * u;
            int edge = 0xFFC9876A, level = battery <= 20 && battery >= 0 ? 0xFFE5534B : CHIP_TEXT;
            rect(c, bx, by, 11 * u, u, edge);
            rect(c, bx, by + 5 * u, 11 * u, u, edge);
            rect(c, bx, by, u, 6 * u, edge);
            rect(c, bx + 10 * u, by, u, 6 * u, edge);
            rect(c, bx + 11 * u, by + 2 * u, u, 2 * u, edge);
            int cols = battery > 0 ? Math.max(1, Math.round(battery * 9 / 100f)) : 0;
            rect(c, bx + u, by + u, cols * u, 4 * u, level);
            if (charging) {
                int[][] bolt = {{5, 0}, {4, 1}, {5, 1}, {3, 2}, {4, 2}, {3, 3}};
                for (int[] b : bolt) rect(c, bx + (1 + b[0]) * u, by + (1 + b[1]) * u, u, u, 0xFFFFC857);
            }
            pixText(c, pct, bx + 15 * u, cy - 2.5f * u, u, CHIP_TEXT, false);

            // next alarm
            String alarm = "OFF";
            if (nextAlarm > 0) {
                Calendar a = Calendar.getInstance();
                a.setTimeInMillis(nextAlarm);
                alarm = nextAlarm - now.getTimeInMillis() < 24 * 3600 * 1000L
                        ? String.format("%02d:%02d", a.get(Calendar.HOUR_OF_DAY), a.get(Calendar.MINUTE))
                        : DAYS[a.get(Calendar.DAY_OF_WEEK) - 1];
            }
            drawIconChip(c, xs[2], cy, u, BELL, alarm);
        }

        private String[] weatherIcon() {
            int w = weatherCode;
            if (!hasWeather) return CLOUD;
            if (w == 0) return isDay ? SUN : MOON;
            if (w <= 2) return isDay ? PARTLY : MOON;
            if (w == 3) return CLOUD;
            if (w == 45 || w == 48) return FOG;
            if ((w >= 71 && w <= 77) || w == 85 || w == 86) return SNOW;
            if (w >= 95) return STORM;
            return RAIN;
        }

        private void drawIconChip(Canvas c, float chipX, float cy, float u, String[] icon, String text) {
            float total = 9 * u + 3 * u + textWidth(text, u);
            float x = chipX + (300 - total) / 2, y = cy - 4.5f * u;
            for (int r = 0; r < icon.length; r++) {
                for (int k = 0; k < icon[r].length(); k++) {
                    Integer col = ICON_COLORS.get(icon[r].charAt(k));
                    if (col != null) rect(c, x + k * u, y + r * u, u, u, col);
                }
            }
            pixText(c, text, x + 12 * u, cy - 2.5f * u, u, CHIP_TEXT, false);
        }

        private float textWidth(String str, float s) {
            return str.length() * 6 * s - s;
        }

        private void pixText(Canvas c, String str, float x, float y, float s, int color, boolean alignRight) {
            float w = str.length() * 6 * s - s;
            float cx = alignRight ? x - w : x;
            fill.setColor(color);
            for (int k = 0; k < str.length(); k++) {
                String g = FONT.get(str.charAt(k));
                if (g != null) {
                    for (int i = 0; i < g.length(); i++) {
                        if (g.charAt(i) == '#') {
                            float px = cx + (i % 5) * s, py = y + (i / 5) * s;
                            c.drawRect(px, py, px + s, py + s, fill);
                        }
                    }
                }
                cx += 6 * s;
            }
        }

        private void rect(Canvas c, float x, float y, float w, float h, int color) {
            fill.setColor(color);
            c.drawRect(x, y, x + w, y + h, fill);
        }
    }

    private static Path poly(float... pts) {
        Path p = new Path();
        p.moveTo(pts[0], pts[1]);
        for (int i = 2; i < pts.length; i += 2) p.lineTo(pts[i], pts[i + 1]);
        p.close();
        return p;
    }
}
