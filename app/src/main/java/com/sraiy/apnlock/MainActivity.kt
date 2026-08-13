package com.sraiy.apnlock

import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import com.sraiy.apnlock.admin.RootApnManager
import com.sraiy.apnlock.receiver.ApnAdminReceiver
import com.sraiy.apnlock.ui.ApnSettingsLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var apnFormLayout: ApnSettingsLayout
    private lateinit var statusText: TextView
    private lateinit var topHeaderLayout: LinearLayout

    private var hasRootPrivilege = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F7"))
            setPadding(60, 100, 60, 0)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // ================= 1. 顶部信息 =================
        topHeaderLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        topHeaderLayout.addView(TextView(this).apply {
            text = "APN Lock 控制台"
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, 40)
        })

        // ================= 2. 授权状态卡片 (极简设计，点击弹窗) =================
        val authCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(50, 50, 50, 50)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 30f; setStroke(2, Color.parseColor("#E5E5EA")) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 40) }

            isClickable = true
            val outValue = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = getDrawable(outValue.resourceId)

            setOnClickListener { showAuthDialog() }
        }

        val authTitle = TextView(this).apply {
            text = "系统底层授权状态"
            textSize = 16f
            setTextColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        statusText = TextView(this).apply {
            text = "状态获取中..."
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
        }

        val arrow = TextView(this).apply {
            text = " 〉"
            textSize = 16f
            setTextColor(Color.parseColor("#C7C7CC"))
        }

        authCard.addView(authTitle)
        authCard.addView(statusText)
        authCard.addView(arrow)

        topHeaderLayout.addView(authCard)
        root.addView(topHeaderLayout)

        // ================= 3. 嵌入配置表单 =================
        apnFormLayout = ApnSettingsLayout(this) { isEditing ->
            topHeaderLayout.visibility = if (isEditing) View.GONE else View.VISIBLE
        }
        apnFormLayout.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        root.addView(apnFormLayout)

        setContentView(root)

        // 首次打开弹出免责声明
        DisclaimerHelper.showIfNeeded(this)


        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // ★ 核心修复：精准判断是否在编辑页面
                if (apnFormLayout.isEditing()) {
                    apnFormLayout.onBackPressed() // 返回列表
                } else {
                    finish() // 如果已经在列表了，则正常退出 Activity 回到桌面/上级
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        updateAuthStatus()
    }

    // ★ 智能探测层级：Root -> 原生 DO -> Dhizuku
    private fun updateAuthStatus() {
        CoroutineScope(Dispatchers.Main).launch {
            statusText.text = "探测中..."
            statusText.setTextColor(Color.parseColor("#999999"))

            // 异步探测 Root，防止阻塞主线程
            hasRootPrivilege = withContext(Dispatchers.IO) { RootApnManager.checkRoot() }

            if (hasRootPrivilege) {
                statusText.text = "Root 强控权限"
                statusText.setTextColor(Color.parseColor("#34C759")) // 绿色
                return@launch
            }

            // 没有 Root，回落到 DO/Dhizuku 判定
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val isNativeDo = dpm.isDeviceOwnerApp(packageName)
            val isDhizukuDo = try {
                Dhizuku.init(this@MainActivity)
                Dhizuku.isPermissionGranted()
            } catch (e: Exception) { false }

            when {
                isNativeDo -> {
                    statusText.text = "自有原生 DO 授权"
                    statusText.setTextColor(Color.parseColor("#34C759")) // 绿色
                }
                isDhizukuDo -> {
                    statusText.text = "Dhizuku 代理授权"
                    statusText.setTextColor(Color.parseColor("#007AFF")) // 蓝色
                }
                else -> {
                    statusText.text = "尚未获得授权"
                    statusText.setTextColor(Color.parseColor("#FF3B30")) // 红色
                }
            }
        }
    }

    // ================= 核心：现代化交互弹窗与逻辑 =================
    private fun showAuthDialog() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val isNativeDo = dpm.isDeviceOwnerApp(packageName)
        val isDhizukuDo = try { Dhizuku.init(this); Dhizuku.isPermissionGranted() } catch (e: Exception) { false }

        // ★ 核心状态机逻辑：
        // 0. 有 Root 权限时：自带无敌霸体，所有繁琐的按钮全部变灰不可点
        // 1. 没有权限时：前两个亮，最后一个灰
        // 2. 自有原生权限时：前两个灰，最后一个亮
        // 3. Dhizuku权限时：全部变灰
        val adbEnabled = !hasRootPrivilege && !isNativeDo && !isDhizukuDo
        val dhizukuEnabled = !hasRootPrivilege && !isNativeDo && !isDhizukuDo
        val transferEnabled = !hasRootPrivilege && isNativeDo

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(70, 70, 70, 50)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 50f }
        }

        dialogView.addView(TextView(this).apply {
            text = "授权管理"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#333333"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 50)
        })

        var dialogInstance: AlertDialog? = null

        fun createModernButton(text: String, isEnabled: Boolean, activeColor: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                this.text = text
                this.textSize = 15f
                this.setTypeface(null, Typeface.BOLD)
                this.isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 130).apply { setMargins(0, 0, 0, 35) }

                if (isEnabled) {
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { setColor(Color.parseColor(activeColor)); cornerRadius = 30f }
                    setOnClickListener {
                        dialogInstance?.dismiss()
                        onClick()
                    }
                } else {
                    setTextColor(Color.parseColor("#999999"))
                    background = GradientDrawable().apply { setColor(Color.parseColor("#F2F2F7")); cornerRadius = 30f }
                    this.isEnabled = false
                }
            }
        }

        val adbBtn = createModernButton("复制 ADB 激活指令", adbEnabled, "#007AFF") {
            val cmd = "adb shell dpm set-device-owner ${packageName}/com.sraiy.apnlock.receiver.ApnAdminReceiver"
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("adb", cmd))
            Toast.makeText(this, "ADB指令已复制，请前往电脑执行", Toast.LENGTH_LONG).show()
        }

        val dhizukuBtn = createModernButton("请求 Dhizuku 授权", dhizukuEnabled, "#34C759") {
            requestDhizukuPermission()
        }

        val transferBtn = createModernButton("转移权限给 Dhizuku", transferEnabled, "#FF9500") {
            transferDOToDhizuku()
        }

        dialogView.addView(adbBtn)
        dialogView.addView(dhizukuBtn)
        dialogView.addView(transferBtn)

        dialogInstance = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialogInstance.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialogInstance.show()
    }

    private fun requestDhizukuPermission() {
        try {
            if (!Dhizuku.init(this)) {
                Toast.makeText(this, "Dhizuku 初始化失败", Toast.LENGTH_SHORT).show()
                return
            }

            if (Dhizuku.isPermissionGranted()) {
                Toast.makeText(this, "Dhizuku 已授权！", Toast.LENGTH_SHORT).show()
                updateAuthStatus()
            } else {
                Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                    override fun onRequestPermission(grantResult: Int) {
                        runOnUiThread {
                            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                                Toast.makeText(this@MainActivity, "Dhizuku 授权成功！", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@MainActivity, "Dhizuku 授权被拒绝！", Toast.LENGTH_SHORT).show()
                            }
                            updateAuthStatus()
                        }
                    }
                })
            }
        } catch (e: Exception) {
            Toast.makeText(this, "未安装 Dhizuku 或其服务未运行", Toast.LENGTH_LONG).show()
        }
    }

    private fun transferDOToDhizuku() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(packageName)) return

        val myAdmin = ComponentName(this, ApnAdminReceiver::class.java)
        // 目标接收方：Dhizuku 的标准 Receiver 路径
        val dhizukuAdmin = ComponentName("com.rosan.dhizuku", "com.rosan.dhizuku.server.DhizukuDAReceiver")

        try {
            dpm.transferOwnership(myAdmin, dhizukuAdmin, null)
            Toast.makeText(this, "✅ 成功将设备所有者 (DO) 转移给 Dhizuku！", Toast.LENGTH_LONG).show()
            updateAuthStatus()
        } catch (e: Exception) {
            Toast.makeText(this, "❌ 转移失败：系统拒绝或 Dhizuku 不支持接收", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }
}