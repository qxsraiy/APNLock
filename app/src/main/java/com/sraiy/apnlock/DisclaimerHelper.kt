package com.sraiy.apnlock

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

object DisclaimerHelper {

    private const val PREFS = "disclaimer_prefs"
    private const val KEY_AGREED = "agreed"

    // ★ 核心修复：将 dp 转换为真实的屏幕 px，彻底解决按钮被压扁遮挡的问题
    private fun dp2px(context: Context, dp: Float): Int {
        return (dp * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    fun showIfNeeded(activity: Activity, onAgreed: () -> Unit) {
        val sp = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (sp.getBoolean(KEY_AGREED, false)) {
            onAgreed()
            return
        }

        val dialogView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            // 转换为 dp 间距
            setPadding(dp2px(activity, 25f), dp2px(activity, 25f), dp2px(activity, 25f), dp2px(activity, 20f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EEFFFFFF"))
                cornerRadius = dp2px(activity, 24f).toFloat()
            }
        }

        dialogView.addView(TextView(activity).apply {
            text = "免责声明与使用须知"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1C1C1E"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp2px(activity, 15f))
        })

        dialogView.addView(TextView(activity).apply {
            text = "• 本应用仅供企业设备管理、网络安全测试或家长控制使用。\n" +
                    "• 严禁利用本工具绕过运营商计费或从事非法网络活动。\n" +
                    "• 修改底层 APN 配置可能会短暂断开数据连接。\n" +
                    "• 使用本软件产生的任何网络异常、设备损坏或法律后果需由使用者自行承担。"
            textSize = 14f
            setTextColor(Color.parseColor("#3A3A3C"))
            setLineSpacing(dp2px(activity, 4f).toFloat(), 1.1f)
            setPadding(0, 0, 0, dp2px(activity, 20f))
        })

        var dialog: AlertDialog? = null

        // ★ 修复：高度自适应 WRAP_CONTENT，靠内部 Padding 撑开，永不遮挡
        val agreeBtn = android.widget.Button(activity).apply {
            text = "同意并继续"
            textSize = 15f
            isAllCaps = false
            setPadding(0, dp2px(activity, 12f), 0, dp2px(activity, 12f))
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#007AFF"))
                cornerRadius = dp2px(activity, 20f).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                sp.edit().putBoolean(KEY_AGREED, true).apply()
                dialog?.dismiss()
                onAgreed()
            }
        }

        val exitBtn = android.widget.Button(activity).apply {
            text = "拒绝并退出"
            textSize = 14f
            isAllCaps = false
            setPadding(0, dp2px(activity, 10f), 0, dp2px(activity, 10f))
            setTextColor(Color.parseColor("#8E8E93"))
            background = null
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp2px(activity, 5f), 0, 0)
            }
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
     * Root 模式下真实耗时等待蒙版
     */
    fun showRootProgressOverlay(context: Context, isEnabling: Boolean): AlertDialog {
        val overlayView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            // 同样转为 dp 避免过大或过小
            setPadding(dp2px(context, 30f), dp2px(context, 30f), dp2px(context, 30f), dp2px(context, 30f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2000000")) // 半透明高斯蒙版
                cornerRadius = dp2px(context, 24f).toFloat()
            }
        }

        val progressSpinner = ProgressBar(context).apply {
            // 转为 dp 确保图标尺寸合适
            layoutParams = LinearLayout.LayoutParams(dp2px(context, 45f), dp2px(context, 45f)).apply {
                setMargins(0, 0, 0, dp2px(context, 15f))
            }
        }

        val titleStr = if (isEnabling) " Root 模式Apn写入中..." else " Root 恢复默认中..."
        val timerBaseStr = if (isEnabling) "正在写入设置与数据库" else "正在清理配置并唤醒原生Apn"

        val titleText = TextView(context).apply {
            text = titleStr
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp2px(context, 8f))
        }

        val timerText = TextView(context).apply {
            text = "$timerBaseStr (0s)"
            textSize = 14f
            setTextColor(Color.parseColor("#EBEBF5"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp2px(context, 15f))
        }

        val warningText = TextView(context).apply {
            text = "⚠️ 正在进行底层操作，请勿切出应用"
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
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()

        var seconds = 0
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                seconds++
                timerText.text = "$timerBaseStr (${seconds}s)"
                if (dialog.isShowing) {
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.postDelayed(runnable, 1000)

        return dialog
    }
}