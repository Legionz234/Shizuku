package moe.shizuku.manager.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;

import moe.shizuku.manager.R;
import moe.shizuku.manager.ShizukuSettings;
import moe.shizuku.manager.utils.EnvironmentUtils;
import rikka.core.util.ResourceUtils;

public class ThemeHelper {

    private static final String THEME_DEFAULT = "DEFAULT";
    private static final String THEME_BLACK = "BLACK";

    public static final String KEY_LIGHT_THEME = "light_theme";
    public static final String KEY_BLACK_NIGHT_THEME = "black_night_theme";

    /** 兼容旧版本的「使用系统主题色」开关，只用来迁移。 */
    public static final String KEY_USE_SYSTEM_COLOR = "use_system_color";

    /** 主题色来源，取值见下面的 COLOR_SOURCE_*。 */
    public static final String KEY_THEME_COLOR_SOURCE = "theme_color_source";

    /** 自定义主题色的种子色（ARGB，0 表示未设置）。 */
    public static final String KEY_CUSTOM_THEME_COLOR = "custom_theme_color";

    public static final int COLOR_SOURCE_SYSTEM = 0;
    public static final int COLOR_SOURCE_BUILTIN = 1;
    public static final int COLOR_SOURCE_CUSTOM = 2;

    @Nullable
    private static SharedPreferences prefs() {
        return ShizukuSettings.getPreferences();
    }

    public static boolean isBlackNightTheme(Context context) {
        SharedPreferences preferences = prefs();
        return preferences != null
                && preferences.getBoolean(KEY_BLACK_NIGHT_THEME, EnvironmentUtils.isWatch(context));
    }

    /**
     * 主题色来源。
     *
     * 老版本只有「使用系统主题色」这个开关，这里把它当作默认值迁移过来：
     * 开关打开 = 跟随系统，关闭 = 用应用自带的主色。
     */
    public static int getColorSource(Context context) {
        SharedPreferences preferences = prefs();
        if (preferences == null) return COLOR_SOURCE_SYSTEM;

        if (preferences.contains(KEY_THEME_COLOR_SOURCE)) {
            return preferences.getInt(KEY_THEME_COLOR_SOURCE, COLOR_SOURCE_SYSTEM);
        }

        return preferences.getBoolean(KEY_USE_SYSTEM_COLOR, true)
                ? COLOR_SOURCE_SYSTEM
                : COLOR_SOURCE_BUILTIN;
    }

    public static void setColorSource(int source) {
        SharedPreferences preferences = prefs();
        if (preferences != null) {
            preferences.edit().putInt(KEY_THEME_COLOR_SOURCE, source).apply();
        }
    }

    /** 自定义种子色，0 表示未设置。 */
    public static int getCustomColor() {
        SharedPreferences preferences = prefs();
        return preferences == null ? 0 : preferences.getInt(KEY_CUSTOM_THEME_COLOR, 0);
    }

    public static void setCustomColor(int color) {
        SharedPreferences preferences = prefs();
        if (preferences != null) {
            preferences.edit()
                    .putInt(KEY_CUSTOM_THEME_COLOR, color)
                    .putInt(KEY_THEME_COLOR_SOURCE, COLOR_SOURCE_CUSTOM)
                    .apply();
        }
    }

    public static void clearCustomColor() {
        SharedPreferences preferences = prefs();
        if (preferences != null) {
            preferences.edit()
                    .remove(KEY_CUSTOM_THEME_COLOR)
                    .putInt(KEY_THEME_COLOR_SOURCE, COLOR_SOURCE_BUILTIN)
                    .apply();
        }
    }

    /**
     * 平台是否支持按种子色生成整套配色。
     *
     * Material 的 content-based dynamic color 走的是 Android 12 的资源覆盖机制，
     * 低版本没有这个能力，只能退回自带配色。
     */
    public static boolean isCustomColorSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DynamicColors.isDynamicColorAvailable();
    }

    /** 是否需要铺 Material 的动态取色主题覆盖层（跟随系统 或 自定义）。 */
    public static boolean needsDynamicColors(Context context) {
        int source = getColorSource(context);
        if (source == COLOR_SOURCE_SYSTEM) return isCustomColorSupported();
        if (source == COLOR_SOURCE_CUSTOM) {
            return isCustomColorSupported() && getCustomColor() != 0;
        }
        return false;
    }

    /**
     * 自定义种子色：让 Material 用这个颜色重新生成整套浅色/深色配色。
     *
     * 必须在界面内容创建之前调用（本项目里由 MaterialActivity 在 onCreate 期间回调
     * onApplyUserThemeResource，早于 setContentView）。
     */
    public static void applyCustomColor(Activity activity) {
        if (!isCustomColorSupported()) return;
        if (getColorSource(activity) != COLOR_SOURCE_CUSTOM) return;

        int seed = getCustomColor();
        if (seed == 0) return;

        DynamicColorsOptions options = new DynamicColorsOptions.Builder()
                .setContentBasedSource(seed)
                .build();
        DynamicColors.applyToActivityIfAvailable(activity, options);
    }

    /**
     * 参与主题缓存键的颜色信息。
     *
     * 换了颜色来源或自定义色必须让键变化，否则 Rikka 的 MaterialActivity 会沿用旧主题。
     */
    public static String getColorKey(Context context) {
        return getColorSource(context) + ":" + getCustomColor();
    }

    public static String getTheme(Context context) {
        if (isBlackNightTheme(context)
                && ResourceUtils.isNightMode(context.getResources().getConfiguration()))
            return THEME_BLACK;

        SharedPreferences preferences = prefs();
        return preferences == null
                ? THEME_DEFAULT
                : preferences.getString(KEY_LIGHT_THEME, THEME_DEFAULT);
    }

    @StyleRes
    public static int getThemeStyleRes(Context context) {
        switch (getTheme(context)) {
            case THEME_BLACK:
                return R.style.ThemeOverlay_Black;
            case THEME_DEFAULT:
            default:
                return R.style.ThemeOverlay;
        }
    }
}
