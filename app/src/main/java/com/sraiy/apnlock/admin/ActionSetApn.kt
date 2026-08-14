package com.sraiy.apnlock.admin

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.TelephonyManager
import android.telephony.data.ApnSetting
import android.util.Log
import android.widget.Toast
import com.rosan.dhizuku.api.Dhizuku
import com.sraiy.apnlock.MainActivity
import com.sraiy.apnlock.receiver.ApnAdminReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress

object ActionSetApn {
    private const val TAG = "ApnLock_Admin"

    // ★ 终极防死锁武器：记录当前进程生命周期内是否已经包装过 Dhizuku
    // 完美解决连续点击导致的 Binder 炸毁问题！
    private var isDhizukuWrapped = false

    @SuppressLint("NewApi")
    fun syncApns(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val prefs = context.getSharedPreferences("sraiy_apn_prefs", Context.MODE_PRIVATE)
            val apnListStr = prefs.getString("apn_list", "[]")
            val apnArray = JSONArray(apnListStr)

            var activeApnObj: JSONObject? = null
            for (i in 0 until apnArray.length()) {
                val obj = apnArray.getJSONObject(i)
                if (obj.optBoolean("is_active", false)) {
                    activeApnObj = obj
                    break
                }
            }

            // ==========================================
            // ★ 严格的权限探测顺序：原生 DO -> Dhizuku -> Root
            // ==========================================
            val rawDpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val isNativeDo = rawDpm.isDeviceOwnerApp(context.packageName)
            val isDhizuku = try { Dhizuku.init(context); Dhizuku.isPermissionGranted() } catch (e: Exception) { false }

            val hasRoot = if (!isNativeDo && !isDhizuku) RootApnManager.checkRoot() else false

            if (!isNativeDo && !isDhizuku && !hasRoot) {
                showToast(context, "未获取任何权限，请选择授权方案！")
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("SHOW_AUTH_DIALOG", true)
                }
                context.startActivity(intent)
                return@launch
            }

            // ==========================================
            // ★ 关闭/恢复情况
            // ==========================================
            if (activeApnObj == null) {
                if (isNativeDo) {
                    val adminComponent = ComponentName(context, ApnAdminReceiver::class.java)
                    rawDpm.setOverrideApnsEnabled(adminComponent, false)
                    try {
                        val existingApns = rawDpm.getOverrideApns(adminComponent)
                        for (apn in existingApns) { rawDpm.removeOverrideApn(adminComponent, apn.id) }
                    } catch (e: Exception) {}
                    showToast(context, "已恢复系统默认 APN (原生 DO)")
                    Log.i(TAG, "已恢复系统默认 APN (原生 DO)")
                }
                else if (isDhizuku) {
                    try {
                        // ★ 核心修复：只在没包装过的时候才去调你的原版方法！
                        val dpm = if (!isDhizukuWrapped) {
                            val tempDpm = getWrappedDpm(context)
                            isDhizukuWrapped = true // 标记已包装
                            tempDpm
                        } else {
                            rawDpm // 已经包装过，直接用系统的，底层依然是生效的
                        }

                        val adminComponent = try { Dhizuku.getOwnerComponent() } catch (e: Exception) { ComponentName("com.rosan.dhizuku", "com.rosan.dhizuku.server.DhizukuDAReceiver") }

                        // 盲发关闭指令，确保不管列表获取崩不崩，覆盖通道必须死！
                        try { dpm.setOverrideApnsEnabled(adminComponent, false) } catch (e: Exception) { }

                        // 尝试清理垃圾
                        try {
                            val existingApns = dpm.getOverrideApns(adminComponent)
                            for (apn in existingApns) { dpm.removeOverrideApn(adminComponent, apn.id) }
                        } catch (e: Exception) {
                            Log.w(TAG, "Dhizuku 获取列表异常，但覆盖通道已关闭", e)
                        }

                        showToast(context, "已恢复默认 APN (Dhizuku)\n⚠️ 若未生效请开关一次飞行模式")
                        Log.i(TAG, "已恢复系统默认 APN (Dhizuku)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Dhizuku 恢复异常", e)
                        showToast(context, "Dhizuku 恢复出现异常: ${e.message}")
                    }
                }
                else if (hasRoot) {
                    RootApnManager.clearApns()
                    showToast(context, "已清理 Root 锁定配置\n⚠️ 请开关一次飞行模式")
                }
                return@launch
            }

            // ==========================================
            // ★ 开启/注入情况 (配置参数组装，未作任何修改)
            // ==========================================
            val apnName = activeApnObj.optString("apn")
            val mcc = activeApnObj.optString("mcc").trim()
            val mnc = activeApnObj.optString("mnc").trim()

            if (apnName.isBlank() || mcc.isEmpty() || mnc.isEmpty()) {
                showToast(context, "❌ 注入被拦截：APN、MCC、MNC 不能为空！")
                return@launch
            }

            val builder = ApnSetting.Builder()
                .setEntryName(activeApnObj.optString("name").ifBlank { "Locked_APN" })
                .setApnName(apnName)
                .setAuthType(activeApnObj.optInt("authType", ApnSetting.AUTH_TYPE_NONE))
                .setProtocol(activeApnObj.optInt("protocol", ApnSetting.PROTOCOL_IP))
                .setRoamingProtocol(activeApnObj.optInt("roamingProtocol", ApnSetting.PROTOCOL_IP))
                .setOperatorNumeric(mcc + mnc)
                .setCarrierEnabled(true)

            val apnTypeStr = activeApnObj.optString("apnType", "").lowercase()
            var apnTypeBitmask = 0
            if (apnTypeStr.contains("default")) apnTypeBitmask = apnTypeBitmask or ApnSetting.TYPE_DEFAULT
            if (apnTypeStr.contains("mms")) apnTypeBitmask = apnTypeBitmask or ApnSetting.TYPE_MMS
            if (apnTypeStr.contains("supl")) apnTypeBitmask = apnTypeBitmask or ApnSetting.TYPE_SUPL
            if (apnTypeStr.contains("dun")) apnTypeBitmask = apnTypeBitmask or ApnSetting.TYPE_DUN
            if (apnTypeStr.contains("hipri")) apnTypeBitmask = apnTypeBitmask or ApnSetting.TYPE_HIPRI
            if (apnTypeStr.contains("fota")) apnTypeBitmask = apnTypeBitmask or ApnSetting.TYPE_FOTA
            if (apnTypeStr.contains("ims")) apnTypeBitmask = apnTypeBitmask or ApnSetting.TYPE_IMS
            if (apnTypeStr.contains("cbs")) apnTypeBitmask = apnTypeBitmask or ApnSetting.TYPE_CBS
            if (apnTypeStr.contains("ia")) apnTypeBitmask = apnTypeBitmask or ApnSetting.TYPE_IA
            if (apnTypeStr.contains("emergency")) apnTypeBitmask = apnTypeBitmask or ApnSetting.TYPE_EMERGENCY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && apnTypeStr.contains("mcx")) apnTypeBitmask = apnTypeBitmask or ApnSetting.TYPE_MCX
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && apnTypeStr.contains("xcap")) apnTypeBitmask = apnTypeBitmask or ApnSetting.TYPE_XCAP

            if (apnTypeBitmask == 0) {
                apnTypeBitmask = ApnSetting.TYPE_DEFAULT or ApnSetting.TYPE_SUPL
            }
            builder.setApnTypeBitmask(apnTypeBitmask)

            val networkTypeStr = activeApnObj.optString("networkType", "")
            var networkTypeBitmask = 0
            if (networkTypeStr.contains("LTE")) networkTypeBitmask = networkTypeBitmask or TelephonyManager.NETWORK_TYPE_BITMASK_LTE.toInt()
            if (networkTypeStr.contains("UMTS")) networkTypeBitmask = networkTypeBitmask or TelephonyManager.NETWORK_TYPE_BITMASK_UMTS.toInt() or TelephonyManager.NETWORK_TYPE_BITMASK_HSPAP.toInt()
            if (networkTypeStr.contains("CDMA")) networkTypeBitmask = networkTypeBitmask or TelephonyManager.NETWORK_TYPE_BITMASK_CDMA.toInt() or TelephonyManager.NETWORK_TYPE_BITMASK_EVDO_A.toInt()
            if (networkTypeStr.contains("GSM")) networkTypeBitmask = networkTypeBitmask or TelephonyManager.NETWORK_TYPE_BITMASK_GSM.toInt() or TelephonyManager.NETWORK_TYPE_BITMASK_EDGE.toInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (networkTypeStr.contains("NR")) networkTypeBitmask = networkTypeBitmask or TelephonyManager.NETWORK_TYPE_BITMASK_NR.toInt()
            }
            builder.setNetworkTypeBitmask(networkTypeBitmask)

            try { val proxy = activeApnObj.optString("proxy"); if (proxy.isNotBlank()) builder.setProxyAddress(InetAddress.getByName(proxy)) } catch (e: Exception) { Log.w(TAG, "代理地址解析失败") }
            val port = activeApnObj.optString("port"); if (port.isNotBlank()) builder.setProxyPort(port.toInt())
            val user = activeApnObj.optString("user"); if (user.isNotBlank()) builder.setUser(user)
            val pass = activeApnObj.optString("pass"); if (pass.isNotBlank()) builder.setPassword(pass)
            try { val mmsc = activeApnObj.optString("mmsc"); if (mmsc.isNotBlank()) builder.setMmsc(Uri.parse(mmsc)) } catch (e: Exception) {}
            try { val mmsProxy = activeApnObj.optString("mmsProxy"); if (mmsProxy.isNotBlank()) builder.setMmsProxyAddress(InetAddress.getByName(mmsProxy)) } catch (e: Exception) {}
            val mmsPort = activeApnObj.optString("mmsPort"); if (mmsPort.isNotBlank()) builder.setMmsProxyPort(mmsPort.toInt())
            val mvnoType = activeApnObj.optInt("mvnoType", -1); if (mvnoType != -1) { builder.setMvnoType(mvnoType) }

            // ==========================================
            // ★ 分流注入：原生 DO -> Dhizuku -> Root
            // ==========================================
            if (isNativeDo) {
                Log.i(TAG, "🛡️ 执行 DO 通道注入")
                val adminComponent = ComponentName(context, ApnAdminReceiver::class.java)
                try {
                    val existingApns = rawDpm.getOverrideApns(adminComponent)
                    for (apn in existingApns) { rawDpm.removeOverrideApn(adminComponent, apn.id) }
                } catch (e: Exception) {}

                val insertedId = rawDpm.addOverrideApn(adminComponent, builder.build())
                if (insertedId != -1) {
                    rawDpm.setOverrideApnsEnabled(adminComponent, true)
                    showToast(context, "✅ [原生 DO] APN 锁定且激活生效！")
                } else {
                    showToast(context, "❌ [原生 DO] APN 注入失败，系统拒绝")
                }
            }
            else if (isDhizuku) {
                Log.i(TAG, "🛡️ 执行 Dhizuku 通道注入")
                try {
                    val dpm = if (!isDhizukuWrapped) {
                        val tempDpm = getWrappedDpm(context)
                        isDhizukuWrapped = true
                        tempDpm
                    } else {
                        rawDpm
                    }
                    val adminComponent = try { Dhizuku.getOwnerComponent() } catch (e: Exception) { ComponentName("com.rosan.dhizuku", "com.rosan.dhizuku.server.DhizukuDAReceiver") }

                    try {
                        val existingApns = dpm.getOverrideApns(adminComponent)
                        for (apn in existingApns) { dpm.removeOverrideApn(adminComponent, apn.id) }
                    } catch (e: Exception) {
                        Log.w(TAG, "Dhizuku 获取旧列表异常，直接强行注入", e)
                    }

                    val insertedId = dpm.addOverrideApn(adminComponent, builder.build())
                    if (insertedId != -1) {
                        dpm.setOverrideApnsEnabled(adminComponent, true)
                        showToast(context, "✅ [Dhizuku] APN 锁定且激活生效！")
                    } else {
                        showToast(context, "❌ [Dhizuku] APN 注入失败，系统拒绝")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Dhizuku 注入异常", e)
                    showToast(context, "Dhizuku 注入异常: ${e.message}")
                }
            }
            else if (hasRoot) {
                Log.i(TAG, "🛡️ 执行 Root 通道注入")

                Log.i(TAG, "=== 开始执行 Root 第 1 次锁定 ===")
                RootApnManager.clearApns()
                RootApnManager.injectAndLockApn(context, activeApnObj)

                Thread.sleep(1500)

                Log.i(TAG, "=== 开始执行 Root 第 2 次锁定 (巩固) ===")
                RootApnManager.clearApns()
                val success = RootApnManager.injectAndLockApn(context, activeApnObj)

                if (success) {
                    showToast(context, "✅ [Root] APN 连续巩固锁定生效！")
                } else {
                    showToast(context, "❌ [Root] APN 注入失败！")
                }
            }
        }
    }

    // ==========================================
    // 自己的设备所有者模式下的方法不要改：一字未动！
    // ==========================================
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
    @Throws(Exception::class)
    private fun getWrappedDpm(context: Context): DevicePolicyManager {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val field = DevicePolicyManager::class.java.getDeclaredField("mService")
        field.isAccessible = true
        val mService = field.get(dpm)
        if (mService != null) {
            val asBinderMethod = mService.javaClass.getMethod("asBinder")
            val binder = asBinderMethod.invoke(mService) as android.os.IBinder
            val wrapped = Dhizuku.binderWrapper(binder)
            val stub = Class.forName("android.app.admin.IDevicePolicyManager\$Stub")
            val newService = stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, wrapped)
            field.set(dpm, newService)
        }
        return dpm
    }

    private suspend fun showToast(context: Context, msg: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }
}