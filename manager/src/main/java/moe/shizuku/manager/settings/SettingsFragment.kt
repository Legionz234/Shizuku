package moe.shizuku.manager.settings

import android.content.ComponentName
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.preference.*
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.KEEP_START_ON_BOOT
import moe.shizuku.manager.app.BackgroundHelper
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.app.ThemeHelper.KEY_BLACK_NIGHT_THEME
import moe.shizuku.manager.databinding.DialogThemeColorBinding
import moe.shizuku.manager.ktx.isComponentEnabled
import moe.shizuku.manager.ktx.setComponentEnabled
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.receiver.BootCompleteReceiver
import moe.shizuku.manager.utils.CustomTabsHelper
import rikka.core.util.ResourceUtils
import rikka.material.app.LocaleDelegate
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.fixEdgeEffect
import rikka.shizuku.manager.ShizukuLocales
import rikka.widget.borderview.BorderRecyclerView
import java.util.*
import moe.shizuku.manager.ShizukuSettings.LANGUAGE as KEY_LANGUAGE
import moe.shizuku.manager.ShizukuSettings.NIGHT_MODE as KEY_NIGHT_MODE

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var languagePreference: ListPreference
    private lateinit var nightModePreference: IntegerSimpleMenuPreference
    private lateinit var blackNightThemePreference: TwoStatePreference
    private lateinit var startOnBootPreference: TwoStatePreference
    private lateinit var startupPreference: PreferenceCategory
    private lateinit var translationPreference: Preference
    private lateinit var translationContributorsPreference: Preference
    private lateinit var themeColorSourcePreference: IntegerSimpleMenuPreference
    private lateinit var customThemeColorPreference: Preference
    private lateinit var backgroundPreference: Preference
    private lateinit var backgroundColorPreference: Preference
    private lateinit var backgroundBrightnessPreference: SeekBarPreference
    private lateinit var backgroundBlurPreference: SeekBarPreference
    private lateinit var removeBackgroundPreference: Preference

    private val pickBackground =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult

            val context = context?.applicationContext ?: return@registerForActivityResult
            CoroutineScope(Dispatchers.IO).launch {
                val saved = BackgroundHelper.saveBackground(context, uri)
                // 图片和纯色互斥：选了图就把纯色清掉
                if (saved) BackgroundHelper.clearCustomColor(context)
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext

                    if (saved) {
                        updateBackgroundPreference()
                        activity?.recreate()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            R.string.settings_custom_background_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()

        preferenceManager.setStorageDeviceProtected()
        preferenceManager.sharedPreferencesName = ShizukuSettings.NAME
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        setPreferencesFromResource(R.xml.settings, null)

        languagePreference = findPreference(KEY_LANGUAGE)!!
        nightModePreference = findPreference(KEY_NIGHT_MODE)!!
        blackNightThemePreference = findPreference(KEY_BLACK_NIGHT_THEME)!!
        startOnBootPreference = findPreference(KEEP_START_ON_BOOT)!!
        startupPreference = findPreference("startup")!!
        translationPreference = findPreference("translation")!!
        translationContributorsPreference = findPreference("translation_contributors")!!
        themeColorSourcePreference = findPreference(KEY_THEME_COLOR_SOURCE)!!
        customThemeColorPreference = findPreference(KEY_CUSTOM_THEME_COLOR)!!
        backgroundPreference = findPreference(KEY_CUSTOM_BACKGROUND)!!
        backgroundColorPreference = findPreference(KEY_BACKGROUND_COLOR)!!
        backgroundBrightnessPreference = findPreference(KEY_BACKGROUND_BRIGHTNESS)!!
        backgroundBlurPreference = findPreference(KEY_BACKGROUND_BLUR)!!
        removeBackgroundPreference = findPreference(KEY_CUSTOM_BACKGROUND_REMOVE)!!

        val componentName = ComponentName(context.packageName, BootCompleteReceiver::class.java.name)

        startOnBootPreference.isChecked = context.packageManager.isComponentEnabled(componentName)
        startOnBootPreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                if (newValue is Boolean) {
                    context.packageManager.setComponentEnabled(componentName, newValue)
                    context.packageManager.isComponentEnabled(componentName) == newValue
                } else false
            }
        languagePreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                if (newValue is String) {
                    val locale: Locale = if ("SYSTEM" == newValue) {
                        LocaleDelegate.systemLocale
                    } else {
                        Locale.forLanguageTag(newValue)
                    }
                    LocaleDelegate.defaultLocale = locale
                    activity?.recreate()
                }
                true
            }

        setupLocalePreference()

        nightModePreference.value = ShizukuSettings.getNightMode()
        nightModePreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, value: Any? ->
                if (value is Int) {
                    if (ShizukuSettings.getNightMode() != value) {
                        AppCompatDelegate.setDefaultNightMode(value)
                        activity?.recreate()
                    }
                }
                true
            }
        if (ShizukuSettings.getNightMode() != AppCompatDelegate.MODE_NIGHT_NO) {
            blackNightThemePreference.isChecked = ThemeHelper.isBlackNightTheme(context)
            blackNightThemePreference.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _: Preference?, _: Any? ->
                    if (ResourceUtils.isNightMode(context.resources.configuration)) {
                        activity?.recreate()
                    }
                    true
                }
        } else {
            blackNightThemePreference.isVisible = false
        }

        themeColorSourcePreference.value = ThemeHelper.getColorSource(context)
        themeColorSourcePreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, _: Any? ->
                // 主题色变了必须重建界面才能重新生成配色
                activity?.recreate()
                true
            }
        customThemeColorPreference.setOnPreferenceClickListener {
            showThemeColorDialog()
            true
        }
        updateThemeColorPreference()

        backgroundPreference.setOnPreferenceClickListener {
            pickBackground.launch(arrayOf("image/*"))
            true
        }

        backgroundColorPreference.setOnPreferenceClickListener {
            showBackgroundColorDialog()
            true
        }

        // 两个滑块都做实时预览：亮度只是一层遮罩、模糊只是重新解码一张小图，
        // 都不需要重建界面。拖动时把值先落到偏好设置，再重贴一次窗口背景。
        backgroundBrightnessPreference.value = BackgroundHelper.getBrightness(context)
        backgroundBrightnessPreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, value: Any? ->
                if (value is Int) {
                    persistInt(BackgroundHelper.KEY_BACKGROUND_BRIGHTNESS, value)
                    activity?.let { BackgroundHelper.applyToWindow(it) }
                }
                true
            }

        backgroundBlurPreference.value = BackgroundHelper.getBlurIntensity(context)
        backgroundBlurPreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, value: Any? ->
                if (value is Int) {
                    persistInt(BackgroundHelper.KEY_BACKGROUND_BLUR, value)
                    // 模糊强度参与缓存键，值变了会自动重新解码并重算
                    activity?.let { BackgroundHelper.applyToWindow(it) }
                }
                true
            }

        removeBackgroundPreference.setOnPreferenceClickListener {
            BackgroundHelper.clearBackground(requireContext())
            BackgroundHelper.clearCustomColor(requireContext())
            updateBackgroundPreference()
            activity?.recreate()
            true
        }
        updateBackgroundPreference()

        translationPreference.summary =
            context.getString(R.string.settings_translation_summary, context.getString(R.string.app_name))
        translationPreference.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(context, context.getString(R.string.translation_url))
            true
        }

        val contributors = context.getString(R.string.translation_contributors).toHtml().toString()
        if (contributors.isNotBlank()) {
            translationContributorsPreference.summary = contributors
        } else {
            translationContributorsPreference.isVisible = false
        }
    }

    private fun updateBackgroundPreference() {
        val context = context ?: return
        val hasImage = BackgroundHelper.hasCustomImage(context)
        val customColor = BackgroundHelper.getCustomColor(context)

        backgroundPreference.summary = getString(
            if (hasImage) R.string.settings_custom_background_summary_set
            else R.string.settings_custom_background_summary
        )
        // 图标只用来表明当前选的是哪张图；亮度和模糊的实时预览就是设置界面本身
        backgroundPreference.icon = if (hasImage) {
            BackgroundHelper.createThumbnail(context)
        } else {
            null
        }

        backgroundColorPreference.summary = if (customColor != 0) {
            getString(R.string.settings_background_color_summary_set, customHex(customColor) ?: "")
        } else {
            getString(R.string.settings_background_color_summary)
        }
        backgroundColorPreference.icon = customHex(customColor)?.let { colorSwatch(it) }

        // 亮度和模糊只对背景图有意义
        backgroundBrightnessPreference.isVisible = hasImage
        backgroundBlurPreference.isVisible = hasImage
        removeBackgroundPreference.isVisible = BackgroundHelper.hasCustomBackground(context)
    }

    /**
     * 纯色背景的取色对话框。
     *
     * 复用主题色那套布局（十六进制输入 + 实时预览），行为保持一致。
     * 这里不显示「从背景图提取」：纯色和背景图互斥，从一张即将被替换的图里取色没有意义。
     */
    private fun showBackgroundColorDialog() {
        val context = context ?: return
        val binding = DialogThemeColorBinding.inflate(layoutInflater)
        var preview: Int? = null

        val current = BackgroundHelper.getCustomColor(context)
        if (current != 0) {
            binding.colorInput.setText(customHex(current))
            preview = current
        }
        setPreviewColor(binding, preview)
        binding.extract.isVisible = false

        binding.colorInput.doAfterTextChanged { text ->
            preview = parseHexColor(text?.toString())
            setPreviewColor(binding, preview)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_background_color)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val color = parseHexColor(binding.colorInput.text?.toString())
                if (color == null) {
                    Toast.makeText(context, R.string.custom_theme_color_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    // 两者互斥：设了纯色就删掉背景图
                    BackgroundHelper.clearBackground(context)
                    BackgroundHelper.setCustomColor(context, color)
                    updateBackgroundPreference()
                    activity?.recreate()
                }
            }
            .setNeutralButton(R.string.action_clear) { _, _ ->
                BackgroundHelper.clearCustomColor(context)
                updateBackgroundPreference()
                activity?.recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateThemeColorPreference() {
        val context = context ?: return
        val custom = ThemeHelper.getCustomColor()
        val hex = customHex(custom)

        customThemeColorPreference.isVisible = ThemeHelper.getColorSource(context) == ThemeHelper.COLOR_SOURCE_CUSTOM
        customThemeColorPreference.summary = when {
            !ThemeHelper.isCustomColorSupported() -> getString(R.string.custom_theme_color_unsupported)
            hex != null -> getString(R.string.custom_theme_color_summary_set, hex)
            else -> getString(R.string.custom_theme_color_summary_none)
        }
        customThemeColorPreference.icon = hex?.let { colorSwatch(it) }
    }

    /** 弹出自定义主题色对话框：手输色号，或从背景图里提取。 */
    private fun showThemeColorDialog() {
        val context = context ?: return
        val binding = DialogThemeColorBinding.inflate(layoutInflater)

        val current = ThemeHelper.getCustomColor()
        var preview = if (current != 0) current else null
        if (current != 0) binding.colorInput.setText(customHex(current))
        setPreviewColor(binding, preview)

        binding.colorInput.doAfterTextChanged { text ->
            preview = parseHexColor(text?.toString())
            setPreviewColor(binding, preview)
        }

        binding.extract.setOnClickListener {
            val seed = BackgroundHelper.extractThemeSeedColor(context)
            if (seed == null) {
                Toast.makeText(context, R.string.custom_theme_color_no_background, Toast.LENGTH_SHORT).show()
            } else {
                val hex = customHex(seed)!!
                binding.colorInput.setText(hex)
                preview = seed
                setPreviewColor(binding, seed)
                Toast.makeText(
                    context,
                    getString(R.string.custom_theme_color_extracted, hex),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.custom_theme_color_dialog_title)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val color = parseHexColor(binding.colorInput.text?.toString())
                if (color == null) {
                    Toast.makeText(context, R.string.custom_theme_color_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    ThemeHelper.setCustomColor(color)
                    updateThemeColorPreference()
                    activity?.recreate()
                }
            }
            .setNeutralButton(R.string.action_clear) { _, _ ->
                ThemeHelper.clearCustomColor()
                updateThemeColorPreference()
                activity?.recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setPreviewColor(binding: DialogThemeColorBinding, color: Int?) {
        // 背景是圆形的 shape drawable，用 tint 上色；没解析出颜色时留灰
        binding.colorPreview.backgroundTintList = ColorStateList.valueOf(color ?: 0x33888888)
    }

    /** 小圆点，用来在设置项里显示当前选的色 */
    private fun colorSwatch(hex: String): Drawable? {
        val color = parseHexColor(hex) ?: return null
        val drawable = AppCompatResources.getDrawable(requireContext(), R.drawable.shape_circle_icon_background)
            ?: return null
        return DrawableCompat.wrap(drawable.mutate()).apply {
            DrawableCompat.setTint(this, color)
        }
    }

    /**
     * 解析色号，接受 #RGB / #RRGGBB / #AARRGGBB，也接受不带 #。
     *
     * @return 带不透明 alpha 的颜色；格式不对返回 null
     */
    private fun parseHexColor(text: String?): Int? {
        val trimmed = text?.trim()?.removePrefix("#") ?: return null
        if (trimmed.isEmpty()) return null

        val value = try {
            trimmed.toLong(16)
        } catch (e: NumberFormatException) {
            return null
        }

        return when (trimmed.length) {
            3 -> {
                // #RGB -> #RRGGBB
                val r = (value shr 8 and 0xF).toInt()
                val g = (value shr 4 and 0xF).toInt()
                val b = (value and 0xF).toInt()
                (0xFF shl 24) or (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)
            }
            6 -> (0xFF shl 24) or value.toInt()
            8 -> value.toInt()   // 自带 alpha，原样使用
            else -> null
        }
    }

    private fun customHex(color: Int): String? =
        if (color == 0) null else String.format("#%06X", color and 0xFFFFFF)

    /** 拖动滑块时先把值写进偏好设置，再重贴背景，这样实时预览读到的就是新值。 */
    private fun persistInt(key: String, value: Int) {
        ShizukuSettings.getPreferences()?.edit()?.putInt(key, value)?.apply()
    }

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?
    ): RecyclerView {
        val recyclerView = super.onCreateRecyclerView(inflater, parent, savedInstanceState) as BorderRecyclerView
        recyclerView.fixEdgeEffect()
        recyclerView.addEdgeSpacing(bottom = 8f, unit = TypedValue.COMPLEX_UNIT_DIP)

        val lp = recyclerView.layoutParams
        if (lp is FrameLayout.LayoutParams) {
            lp.rightMargin = recyclerView.context.resources.getDimension(R.dimen.rd_activity_horizontal_margin).toInt()
            lp.leftMargin = lp.rightMargin
        }

        return recyclerView
    }

    private fun setupLocalePreference() {
        val localeTags = ShizukuLocales.LOCALES
        val displayLocaleTags = ShizukuLocales.DISPLAY_LOCALES

        languagePreference.entries = displayLocaleTags
        languagePreference.entryValues = localeTags

        val currentLocaleTag = languagePreference.value
        val currentLocaleIndex = localeTags.indexOf(currentLocaleTag)
        val currentLocale = ShizukuSettings.getLocale()
        val localizedLocales = mutableListOf<CharSequence>()

        for ((index, displayLocale) in displayLocaleTags.withIndex()) {
            if (index == 0) {
                localizedLocales.add(getString(R.string.follow_system))
                continue
            }

            val locale = Locale.forLanguageTag(displayLocale.toString())
            val localeName = if (!TextUtils.isEmpty(locale.script))
                locale.getDisplayScript(locale)
            else
                locale.getDisplayName(locale)

            val localizedLocaleName = if (!TextUtils.isEmpty(locale.script))
                locale.getDisplayScript(currentLocale)
            else
                locale.getDisplayName(currentLocale)

            localizedLocales.add(
                if (index != currentLocaleIndex) {
                    "$localeName<br><small>$localizedLocaleName<small>".toHtml()
                } else {
                    localizedLocaleName
                }
            )
        }

        languagePreference.entries = localizedLocales.toTypedArray()

        languagePreference.summary = when {
            TextUtils.isEmpty(currentLocaleTag) || "SYSTEM" == currentLocaleTag -> {
                getString(R.string.follow_system)
            }
            currentLocaleIndex != -1 -> {
                val localizedLocale = localizedLocales[currentLocaleIndex]
                val newLineIndex = localizedLocale.indexOf('\n')
                if (newLineIndex == -1) {
                    localizedLocale.toString()
                } else {
                    localizedLocale.subSequence(0, newLineIndex).toString()
                }
            }
            else -> {
                ""
            }
        }
    }

    companion object {

        private const val KEY_THEME_COLOR_SOURCE = "theme_color_source"
        private const val KEY_CUSTOM_THEME_COLOR = "custom_theme_color"
        private const val KEY_CUSTOM_BACKGROUND = "custom_background"
        private const val KEY_BACKGROUND_COLOR = "background_color"
        private const val KEY_BACKGROUND_BRIGHTNESS = "background_brightness"
        private const val KEY_BACKGROUND_BLUR = "background_blur_intensity"
        private const val KEY_CUSTOM_BACKGROUND_REMOVE = "custom_background_remove"
    }
}
