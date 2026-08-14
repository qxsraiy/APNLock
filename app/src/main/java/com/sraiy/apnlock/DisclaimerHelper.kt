package com.sraiy.apnlock

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.CountDownTimer
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

object DisclaimerHelper {

    private const val PREFS = "disclaimer_prefs"
    private const val KEY_AGREED = "agreed"

    fun showIfNeeded(activity: Activity, onAgreed: () -> Unit) {
        val sp = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (sp.getBoolean(KEY_AGREED, false)) {
            onAgreed()
            return
        }

        val dialogView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 60, 60, 50)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EEFFFFFF"))
                cornerRadius = 60f
            }
        }

        dialogView.addView(TextView(activity).apply {
            text = "免责声明与使用须知"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1C1C1E"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        })

        dialogView.addView(TextView(activity).apply {
            text = "本应用仅供企业设备管理、网络安全测试和家长控制使用。\n\n" +
                    "• 严禁利用本工具绕过运营商计费或从事非法网络活动。\n" +
                    "• 修改底层 APN 配置可能会短暂断开数据连接。\n" +
                    "• 使用本软件产生的任何网络异常或后果需由使用者自行承担。"
            textSize = 14f
            setTextColor(Color.parseColor("#3A3A3C"))
            setLineSpacing(10f, 1.1f)
            setPadding(0, 0, 0, 40)
        })

        var dialog: AlertDialog? = null

        val agreeBtn = android.widget.Button(activity).apply {
            text = "同意并继续"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#007AFF"))
                cornerRadius = 30f
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120)
            setOnClickListener {
                sp.edit().putBoolean(KEY_AGREED, true).apply()
                dialog?.dismiss()
                onAgreed()
            }
        }

        val exitBtn = android.widget.Button(activity).apply {
            text = "拒绝并退出"
            textSize = 14f
            setTextColor(Color.parseColor("#8E8E93"))
            background = null
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100).apply { setMargins(0, 10, 0, 0) }
            setOnClickListener {
                activity.finish()
            }
        }

        dialogView.addView(agreeBtn)
        dialogView.addView(exitBtn)

        dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    /**
     * ★ 方向 3： Root 模式下全屏 10 秒倒计时防切出蒙版
     */
    fun showRootProgressOverlay(context: Context, onComplete: () -> Unit) {
        val overlayView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(80, 80, 80, 80)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2000000")) // 半透明高斯蒙版
                cornerRadius = 60f
            }
        }

        val progressSpinner = ProgressBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(120, 120).apply { setMargins(0, 0, 0, 40) }
        }

        val titleText = TextView(context).apply {
            text = "🛡️ Root 强控写入中..."
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }

        val timerText = TextView(context).apply {
            text = "正在重置基带与数据库缓存 (10s)"
            textSize = 14f
            setTextColor(Color.parseColor("#EBEBF5"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        }

        val warningText = TextView(context).apply {
            text = "⚠️ 请耐心等待，切勿切出应用或关闭屏幕"
            textSize = 12f
            setTextColor(Color.parseColor("#FF9500"))
            gravity = Gravity.CENTER
        }

        overlayView.addView(progressSpinner)
        overlayView.addView(titleText)
        overlayView.addView(timerText)
        overlayView.addView(warningText)

        val dialog = AlertDialog.Builder(context)
            .setView(overlayView)
            .setCancelable(false) // 强行禁止切出/点击外部关闭
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()

        // 10 秒倒计时
        object : CountDownTimer(10000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = millisUntilFinished / 1000
                timerText.text = "正在重置基带与数据库缓存 (${sec}s)"
            }

            override fun onFinish() {
                dialog.dismiss()
                onComplete()
            }
        }.start()
    }
}