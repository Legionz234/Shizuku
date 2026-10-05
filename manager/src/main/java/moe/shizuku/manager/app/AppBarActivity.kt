package moe.shizuku.manager.app

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.Toolbar
import com.google.android.material.appbar.AppBarLayout
import moe.shizuku.manager.R
import rikka.core.ktx.unsafeLazy

abstract class AppBarActivity : AppActivity() {

    /** 主题原本的顶栏背景，用于在移除背景图后恢复 */
    private var defaultAppBarBackground: Drawable? = null
    private var defaultAppBarBackgroundSaved = false

    /** 当前窗口背景是否是我们贴上去的 */
    private var customBackgroundApplied = false

    private val rootView: ViewGroup by unsafeLazy {
        findViewById<ViewGroup>(R.id.root)
    }

    private val toolbarContainer: AppBarLayout by unsafeLazy {
        findViewById<AppBarLayout>(R.id.toolbar_container)
    }

    private val toolbar: Toolbar by unsafeLazy {
        findViewById<Toolbar>(R.id.toolbar)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        super.setContentView(getLayoutId())

        applyCustomBackground()

        setSupportActionBar(toolbar)
    }

    override fun onResume() {
        super.onResume()
        // 背景图可能在别的界面被换掉或删掉（例如设置页），而本 Activity 只是从后台回到
        // 前台、并不会重建，窗口上贴的还是旧图。这里重新贴一次；如果图片文件真的变了，
        // 缓存键（路径|大小|修改时间|模糊档位）也会变，于是会重新解码。
        applyCustomBackground()
    }

    /**
     * Applies the background image picked by the user in the settings, if any.
     *
     * The app bar is made transparent as well, otherwise its opaque surface color would cover
     * the top part of the image. When no image is set, the theme values are put back, so that
     * removing the image also takes effect without restarting the app.
     */
    private fun applyCustomBackground() {
        if (!defaultAppBarBackgroundSaved) {
            defaultAppBarBackground = toolbarContainer.background
            defaultAppBarBackgroundSaved = true
        }

        if (BackgroundHelper.hasCustomBackground(this)) {
            BackgroundHelper.applyToWindow(this)
            toolbarContainer.background = null
            customBackgroundApplied = true
        } else if (customBackgroundApplied) {
            BackgroundHelper.restoreWindowBackground(this)
            toolbarContainer.background = defaultAppBarBackground
            customBackgroundApplied = false
        }
    }

    @LayoutRes
    open fun getLayoutId(): Int {
        return R.layout.appbar_activity
    }

    override fun setContentView(layoutResID: Int) {
        layoutInflater.inflate(layoutResID, rootView, true)
        rootView.bringChildToFront(toolbarContainer)
    }

    override fun setContentView(view: View?) {
        setContentView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        rootView.addView(view, 0, params)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onApplyTranslucentSystemBars() {
        super.onApplyTranslucentSystemBars()
        window?.statusBarColor = Color.TRANSPARENT
    }
}

abstract class AppBarFragmentActivity : AppBarActivity() {

    override fun getLayoutId(): Int {
        return R.layout.appbar_fragment_activity
    }
}
