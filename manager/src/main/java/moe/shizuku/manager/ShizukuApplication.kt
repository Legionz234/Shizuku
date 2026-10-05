package moe.shizuku.manager

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.topjohnwu.superuser.Shell
import moe.shizuku.manager.app.BackgroundHelper
import moe.shizuku.manager.ktx.logd
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.core.util.BuildUtils.atLeast30
import rikka.material.app.LocaleDelegate

lateinit var application: ShizukuApplication

class ShizukuApplication : Application() {

    companion object {

        init {
            logd("ShizukuApplication", "init")

            Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR))
            if (Build.VERSION.SDK_INT >= 28) {
                HiddenApiBypass.setHiddenApiExemptions("")
            }
            if (atLeast30) {
                System.loadLibrary("adb")
            }
        }
    }

    private fun init(context: Context?) {
        ShizukuSettings.initialize(context)
        // 必须早于任何设置界面：界面一 inflation 就会把 background_mode 的默认值写进偏好，
        // 之后再迁移就分不清"用户选了默认"和"根本没人选过"了
        if (context != null) BackgroundHelper.migrateIfNeeded(context)
        LocaleDelegate.defaultLocale = ShizukuSettings.getLocale()
        AppCompatDelegate.setDefaultNightMode(ShizukuSettings.getNightMode())
    }

    override fun onCreate() {
        super.onCreate()
        application = this
        init(this)
    }

}
