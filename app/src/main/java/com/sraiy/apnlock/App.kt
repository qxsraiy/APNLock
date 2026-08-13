package com.sraiy.apnlock

import android.app.Application
import android.content.Context
import android.os.Build
import com.rosan.dhizuku.api.Dhizuku
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // ★ 核心破解：在 App 启动的最早期，破解 Android 9+ 的隐藏 API 限制
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            Dhizuku.init(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}