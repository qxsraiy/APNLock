package com.sraiy.apnlock

import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Process
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import com.sraiy.apnlock.admin.RootApnManager
import com.sraiy.apnlock.receiver.ApnAdminReceiver
import com.sraiy.apnlock.ui.ApnSettingsLayout
import com.sraiy.apnlock.util.ApnBackupHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var apnFormLayout: ApnSettingsLayout
    private lateinit var statusText: TextView
    private lateinit var topHeaderLayout: LinearLayout

    private var hasRootPrivilege = false
    private var isNativeDo = false
    private var isDhizukuDo = false

    private val exportJsonLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            ApnBackupHelper.exportToJson(this, uri)
        }
    }

    private val importJsonLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            ApnBackupHelper.importFromJson(this, uri) {
                apnFormLayout.refreshList()
            }
        }
    }

    // ★ 新增：将 dp 转换为真实的屏幕 px，解决按钮被压扁遮挡的问题
    private fun dp2px(dp: Float): Int {
        return (dp * resources.displayMetrics.density + 0.5f).toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2F2F7"))
            fitsSystemWindows = true
            setPadding(dp2px(20f), dp2px(20f), dp2px(20f), 0)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        topHeaderLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        topHeaderLayout.addView(TextView(this).apply {
            text = "APN Lock 控制台"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1C1C1E"))
            setPadding(dp2px(5f), 0, 0, dp2px(15f))
        })

        val authCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp2px(20f), dp2px(20f), dp2px(20f), dp2px(20f))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp2px(16f).toFloat()
                setStroke(dp2px(1f), Color.parseColor("#D1D1D6"))
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp2px(10f)) }

            isClickable = true
            val outValue = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = getDrawable(outValue.resourceId)

            setOnClickListener { showAuthDialog() }
        }

        val authTitle = TextView(this).apply {
            text = "当前生效授权模式"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1C1C1E"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        statusText = TextView(this).apply {
            text = "检测中..."
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#8E8E93"))
        }

        val arrow = TextView(this).apply {
            text = " 〉"
            textSize = 15f
            setTextColor(Color.parseColor("#8E8E93"))
        }

        authCard.addView(authTitle)
        authCard.addView(statusText)
        authCard.addView(arrow)

        topHeaderLayout.addView(authCard)
        root.addView(topHeaderLayout)

        apnFormLayout = ApnSettingsLayout(
            context = this,
            onStateChange = { isEditing -> topHeaderLayout.visibility = if (isEditing) View.GONE else View.VISIBLE },
            onExportClick = { exportJsonLauncher.launch("apn_configs_${System.currentTimeMillis()}.json") },
            onImportClick = { importJsonLauncher.launch(arrayOf("application/json", "text/*")) },
            onFetchSystemClick = {
                ApnBackupHelper.fetchSystemApns(this) {
                    apnFormLayout.refreshList()
                }
            }
        )
        apnFormLayout.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        root.addView(apnFormLayout)

        setContentView(root)

        DisclaimerHelper.showIfNeeded(this) {
            checkAndPromptInitialAuth()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (apnFormLayout.isEditing()) {
                    apnFormLayout.onBackPressed()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        updateAuthStatus()
    }

    private fun checkAndPromptInitialAuth() {
        CoroutineScope(Dispatchers.Main).launch {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val isDo = dpm.isDeviceOwnerApp(packageName)
            val isDhi = try { Dhizuku.init(this@MainActivity); Dhizuku.isPermissionGranted() } catch (e: Exception) { false }
            val hasRt = withContext(Dispatchers.IO) { RootApnManager.checkRoot() }

            if (!isDo && !isDhi && !hasRt) {
                showAuthDialog()
            }
        }
    }

    private fun updateAuthStatus() {
        CoroutineScope(Dispatchers.Main).launch {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            isNativeDo = dpm.isDeviceOwnerApp(packageName)
            isDhizukuDo = try { Dhizuku.init(this@MainActivity); Dhizuku.isPermissionGranted() } catch (e: Exception) { false }
            hasRootPrivilege = withContext(Dispatchers.IO) { RootApnManager.checkRoot() }

            val prefs = getSharedPreferences("sraiy_apn_prefs", Context.MODE_PRIVATE)
            val preferredMode = prefs.getString("mode_override", "AUTO")

            when {
                preferredMode == "DO" && isNativeDo -> {
                    statusText.text = "设备所有者模式"
                    statusText.setTextColor(Color.parseColor("#34C759"))
                }
                preferredMode == "DHIZUKU" && isDhizukuDo -> {
                    statusText.text = "Dhizuku 授权模式"
                    statusText.setTextColor(Color.parseColor("#007AFF"))
                }
                preferredMode == "ROOT" && hasRootPrivilege -> {
                    statusText.text = "Root 模式"
                    statusText.setTextColor(Color.parseColor("#FF9500"))
                }
                isNativeDo -> {
                    statusText.text = "设备所有者模式"
                    statusText.setTextColor(Color.parseColor("#34C759"))
                }
                isDhizukuDo -> {
                    statusText.text = "Dhizuku 授权模式"
                    statusText.setTextColor(Color.parseColor("#007AFF"))
                }
                hasRootPrivilege -> {
                    statusText.text = "Root 模式 (不推荐)"
                    statusText.setTextColor(Color.parseColor("#FF9500"))
                }
                else -> {
                    statusText.text = "未获得授权"
                    statusText.setTextColor(Color.parseColor("#FF3B30"))
                }
            }
        }
    }

    private fun showAuthDialog() {
        val prefs = getSharedPreferences("sraiy_apn_prefs", Context.MODE_PRIVATE)
        val currentPreferred = prefs.getString("mode_override", "AUTO")

        val activeMode = when {
            currentPreferred == "DO" && isNativeDo -> "DO"
            currentPreferred == "DHIZUKU" && isDhizukuDo -> "DHIZUKU"
            currentPreferred == "ROOT" && hasRootPrivilege -> "ROOT"
            isNativeDo -> "DO"
            isDhizukuDo -> "DHIZUKU"
            hasRootPrivilege -> "ROOT"
            else -> "NONE"
        }

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(25f), dp2px(25f), dp2px(25f), dp2px(20f))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp2px(24f).toFloat()
            }
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        content.addView(TextView(this).apply {
            text = "授权方案与管理"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1C1C1E"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp2px(15f))
        })

        var dialogInstance: AlertDialog? = null

        fun createOptionCard(
            title: String, desc: String, tagColor: String, buttonText: String,
            hasPrivilege: Boolean, isCurrentMode: Boolean,
            onClick: () -> Unit,
            extraBottomView: View? = null
        ): LinearLayout {

            val bgColor = if (isCurrentMode) "#FFFFFF" else if (hasPrivilege) "#FAFAFA" else "#F2F2F7"
            val borderColor = if (isCurrentMode) tagColor else if (hasPrivilege) "#D1D1D6" else "#E5E5EA"
            val titleColor = if (isCurrentMode) "#1C1C1E" else if (hasPrivilege) "#3A3A3C" else "#8E8E93"
            val descColor = if (isCurrentMode) "#3A3A3C" else if (hasPrivilege) "#636366" else "#8E8E93"
            val btnColor = if (isCurrentMode) tagColor else if (hasPrivilege) "#8E8E93" else "#D1D1D6"

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp2px(15f), dp2px(15f), dp2px(15f), dp2px(15f))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(bgColor))
                    cornerRadius = dp2px(16f).toFloat()
                    setStroke(dp2px(if (isCurrentMode) 2f else 1f), Color.parseColor(borderColor))
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp2px(15f)) }
            }

            val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            header.addView(TextView(this).apply {
                text = title; textSize = 15f; setTypeface(null, Typeface.BOLD);
                setTextColor(Color.parseColor(titleColor))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            // ★ 核心修复：按钮固定宽度 105dp，固定高度 36dp，居中防遮挡！
            val btn = Button(this).apply {
                text = buttonText
                textSize = 12f
                isAllCaps = false
                setPadding(0, 0, 0, 0)
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(btnColor))
                    cornerRadius = dp2px(18f).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dp2px(105f), dp2px(36f))
                this.isEnabled = hasPrivilege || (!isCurrentMode && !hasPrivilege)
                setOnClickListener {
                    dialogInstance?.dismiss()
                    onClick()
                }
            }
            if (isCurrentMode) btn.isEnabled = false

            header.addView(btn)
            card.addView(header)

            card.addView(TextView(this).apply {
                text = desc
                textSize = 12f
                setTextColor(Color.parseColor(descColor))
                setLineSpacing(dp2px(2f).toFloat(), 1.0f)
                setPadding(0, dp2px(8f), 0, 0)
            })

            if (extraBottomView != null) {
                card.addView(extraBottomView)
            }

            return card
        }

        val doDesc = "【推荐】原生高优先接管通道。\n激活方式：使用电脑执行 ADB 命令设置 Owner。"
        val doBtnText = if (isNativeDo) (if (activeMode == "DO") "当前生效中" else "切换此模式") else "复制指令"

        var transferView: View? = null
        if (isNativeDo) {
            transferView = TextView(this).apply {
                text = "↳ 转移设备所有者权限给 Dhizuku"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#FF3B30"))
                setPadding(0, dp2px(12f), 0, 0)
                setOnClickListener {
                    dialogInstance?.dismiss()
                    transferDOToDhizuku()
                }
            }
        }

        content.addView(createOptionCard("1. 设备所有者 (DO)", doDesc, "#34C759", doBtnText, isNativeDo, activeMode == "DO", {
            if (isNativeDo) switchModeAndRestart("DO")
            else {
                val cmd = "adb shell dpm set-device-owner ${packageName}/com.sraiy.apnlock.receiver.ApnAdminReceiver"
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("adb", cmd))
                Toast.makeText(this, "ADB 指令已复制！", Toast.LENGTH_LONG).show()
            }
        }, transferView))

        val dhiDesc = "【推荐】利用 Dhizuku 共享免电脑激活 DO 权限。\n需先安装并激活 Dhizuku 应用后发起请求。"
        val dhiBtnText = if (isDhizukuDo) (if (activeMode == "DHIZUKU") "当前生效中" else "切换此模式") else "请求授权"
        content.addView(createOptionCard("2. Dhizuku 代理授权", dhiDesc, "#007AFF", dhiBtnText, isDhizukuDo, activeMode == "DHIZUKU", {
            if (isDhizukuDo) switchModeAndRestart("DHIZUKU") else requestDhizukuPermission()
        }))

        val rootDesc = "⚠️【不推荐】直接通过 Root 注入数据库。\n特别提示：重置基带有回落风险。"
        val rootBtnText = if (hasRootPrivilege) (if (activeMode == "ROOT") "当前生效中" else "切换此模式") else "自动探测"
        content.addView(createOptionCard("3. Root 模式", rootDesc, "#FF9500", rootBtnText, hasRootPrivilege, activeMode == "ROOT", {
            if (hasRootPrivilege) switchModeAndRestart("ROOT")
        }))

        scroll.addView(content)
        dialogView.addView(scroll)

        dialogInstance = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialogInstance.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialogInstance.show()
    }

    private fun switchModeAndRestart(mode: String) {
        val prefs = getSharedPreferences("sraiy_apn_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("mode_override", mode).apply()

        AlertDialog.Builder(this)
            .setTitle("授权模式已变更")
            .setMessage("首选模式已切换为 $mode。为了防止底层权限错乱，应用将立即退出重启。")
            .setCancelable(false)
            .setPositiveButton("立即重启") { _, _ ->
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                finishAffinity()
                Process.killProcess(Process.myPid())
            }
            .show()
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
                                switchModeAndRestart("DHIZUKU")
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