package com.sraiy.apnlock

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog

object DisclaimerHelper {

    private const val PREFS = "disclaimer_prefs"
    private const val KEY_AGREED = "agreed"

    fun showIfNeeded(activity: Activity) {
        val sp = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // 已同意过，直接放行
        if (sp.getBoolean(KEY_AGREED, false)) return

        AlertDialog.Builder(activity)
            .setTitle("免责声明")
            .setMessage(
                "本应用仅供学习交流、企业设备管理和家长控制使用。\n\n" +
                        "严禁用于绕过运营商计费或获取不当利益等违法犯罪行为。\n\n" +
                        "使用本应用所产生的一切后果由使用者自行承担。\n\n" +
                        "点击\"同意并继续\"即表示您已阅读并同意以上条款。"
            )
            .setCancelable(false) // 不可点击外部关闭，必须做出选择
            .setPositiveButton("同意并继续") { _, _ ->
                sp.edit().putBoolean(KEY_AGREED, true).apply()
            }
            .setNegativeButton("退出") { _, _ ->
                activity.finish() // 不同意则退出应用
            }
            .show()
    }
}
