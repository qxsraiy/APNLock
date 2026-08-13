package com.sraiy.apnlock.admin

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.telephony.TelephonyManager
import android.telephony.data.ApnSetting
import android.util.Log
import android.widget.Toast
import com.rosan.dhizuku.api.Dhizuku
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

    @SuppressLint("NewApi")
    fun syncApns(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(context, "仅支持 Android 9.0 以上系统", Toast.LENGTH_SHORT).show()
            return
        }

        val isDhizuku = try {
            Dhizuku.init(context)
            Dhizuku.isPermissionGranted()
        } catch (e: Exception) { false }

        // ★ 强化错误拦截：如果反射失败，直接阻断，避免触发 SecurityException 崩溃
        val dpm = if (isDhizuku) {
            try {
                getWrappedDpm(context)
            } catch (e: Exception) {
                Log.e(TAG, "Dhizuku Binder Wrapper 反射挂载异常: ", e)
                CoroutineScope(Dispatchers.Main).launch {
                    Toast.makeText(context, "底层接口被拦截，请确保 HiddenApiBypass 已生效！", Toast.LENGTH_LONG).show()
                }
                return
            }
        } else {
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        }

        val adminComponent = if (isDhizuku) {
            try {
                Dhizuku.getOwnerComponent()
            } catch (e: Exception) {
                ComponentName("com.rosan.dhizuku", "com.rosan.dhizuku.server.DhizukuDAReceiver")
            }
        } else {
            ComponentName(context, ApnAdminReceiver::class.java)
        }

        val hasPrivilege = if (isDhizuku) true else dpm.isDeviceOwnerApp(context.packageName)
        if (!hasPrivilege) {
            Toast.makeText(context, "请先激活设备所有者特权或 Dhizuku 授权！", Toast.LENGTH_SHORT).show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val existingApns = dpm.getOverrideApns(adminComponent)
                for (apn in existingApns) {
                    dpm.removeOverrideApn(adminComponent, apn.id)
                }

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

                if (activeApnObj == null) {
                    dpm.setOverrideApnsEnabled(adminComponent, false)
                    Log.i(TAG, "已恢复系统默认 APN")
                    return@launch
                }

                val apnName = activeApnObj.optString("apn")
                if (apnName.isBlank()) return@launch

                val mcc = activeApnObj.optString("mcc").trim()
                val mnc = activeApnObj.optString("mnc").trim()

                if (mcc.isEmpty() || mnc.isEmpty()) {
                    dpm.setOverrideApnsEnabled(adminComponent, false)
                    showToast(context, "❌ 注入被拦截：MCC 和 MNC 不能为空！")
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

                try {
                    val proxy = activeApnObj.optString("proxy")
                    if (proxy.isNotBlank()) builder.setProxyAddress(InetAddress.getByName(proxy))
                } catch (e: Exception) {
                    Log.w(TAG, "代理地址解析失败: ${e.message}")
                }

                val port = activeApnObj.optString("port")
                if (port.isNotBlank()) builder.setProxyPort(port.toInt())

                val user = activeApnObj.optString("user")
                if (user.isNotBlank()) builder.setUser(user)
                val pass = activeApnObj.optString("pass")
                if (pass.isNotBlank()) builder.setPassword(pass)

                try {
                    val mmsc = activeApnObj.optString("mmsc")
                    if (mmsc.isNotBlank()) builder.setMmsc(Uri.parse(mmsc))
                } catch (e: Exception) {}

                try {
                    val mmsProxy = activeApnObj.optString("mmsProxy")
                    if (mmsProxy.isNotBlank()) builder.setMmsProxyAddress(InetAddress.getByName(mmsProxy))
                } catch (e: Exception) {}

                val mmsPort = activeApnObj.optString("mmsPort")
                if (mmsPort.isNotBlank()) builder.setMmsProxyPort(mmsPort.toInt())

                val mvnoType = activeApnObj.optInt("mvnoType", -1)
                if (mvnoType != -1) {
                    builder.setMvnoType(mvnoType)
                }

                val insertedId = dpm.addOverrideApn(adminComponent, builder.build())
                if (insertedId != -1) {
                    dpm.setOverrideApnsEnabled(adminComponent, true)
                    showToast(context, "✅ APN [${activeApnObj.optString("name")}] 锁定且激活生效！")
                    Log.i(TAG, "已成功强行接管并唤醒网络为: $apnName")
                } else {
                    showToast(context, "❌ APN 注入失败，系统拒绝了该配置")
                }

            } catch (e: Exception) {
                Log.e(TAG, "APN 设置同步异常", e)
                showToast(context, "注入异常: ${e.message}")
            }
        }
    }

    /**
     * 去除 try-catch 拦截，让上层准确感知反射失败，避免硬着头皮走 Native DPM
     */
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