package com.sraiy.apnlock.util

import android.content.Context
import android.net.Uri
import android.telephony.SubscriptionManager
import android.util.Log
import android.widget.Toast
import com.sraiy.apnlock.admin.RootApnManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

object ApnBackupHelper {
    private const val TAG = "ApnBackupHelper"

    fun exportToJson(context: Context, uri: Uri): Boolean {
        return try {
            val prefs = context.getSharedPreferences("sraiy_apn_prefs", Context.MODE_PRIVATE)
            val apnListStr = prefs.getString("apn_list", "[]")

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(apnListStr)
                    writer.flush()
                }
            }
            Toast.makeText(context, "✅ 成功导出 APN 配置文件！", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Log.e(TAG, "导出失败", e)
            Toast.makeText(context, "❌ 导出失败: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    fun importFromJson(context: Context, uri: Uri, onComplete: () -> Unit): Boolean {
        return try {
            val stringBuilder = java.lang.StringBuilder()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    // ★ 修复：使用更安全的 Kotlin 语法
                    reader.forEachLine { line ->
                        stringBuilder.append(line)
                    }
                }
            }

            val importedArray = JSONArray(stringBuilder.toString())
            val prefs = context.getSharedPreferences("sraiy_apn_prefs", Context.MODE_PRIVATE)
            val existingArray = JSONArray(prefs.getString("apn_list", "[]"))

            var addCount = 0
            for (i in 0 until importedArray.length()) {
                val item = importedArray.getJSONObject(i)
                item.put("is_active", false) // 导入进来的默认处于未激活状态
                existingArray.put(item)
                addCount++
            }

            prefs.edit().putString("apn_list", existingArray.toString()).apply()
            Toast.makeText(context, "✅ 成功导入 $addCount 条配置！", Toast.LENGTH_SHORT).show()
            onComplete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "导入失败", e)
            Toast.makeText(context, "❌ 导入失败，请检查文件格式是否正确", Toast.LENGTH_LONG).show()
            false
        }
    }

    /**
     * 读取系统当前 SIM 卡的有效 APN 并添加到列表
     */
    fun fetchSystemApns(context: Context, onComplete: () -> Unit) {
        val prefs = context.getSharedPreferences("sraiy_apn_prefs", Context.MODE_PRIVATE)
        val existingArray = JSONArray(prefs.getString("apn_list", "[]"))
        var count = 0

        // 方式一：直接读取 PreferAPN
        try {
            val subId = SubscriptionManager.getDefaultDataSubscriptionId()
            val uriStr = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                "content://telephony/carriers/preferapn/subId/$subId"
            } else {
                "content://telephony/carriers/preferapn"
            }

            var cursor = context.contentResolver.query(Uri.parse(uriStr), null, null, null, null)

            // 如果 preferapn 为空，尝试回落 current=1 限制前 2 条
            if (cursor == null || cursor.count == 0) {
                cursor?.close()
                val fallbackUri = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) "content://telephony/carriers/subId/$subId" else "content://telephony/carriers"
                cursor = context.contentResolver.query(Uri.parse(fallbackUri), null, "current=1", null, "_id DESC LIMIT 2")
            }

            if (cursor != null && cursor.count > 0) {
                while (cursor.moveToNext()) {
                    val nameIdx = cursor.getColumnIndex("name")
                    val apnIdx = cursor.getColumnIndex("apn")
                    val mccIdx = cursor.getColumnIndex("mcc")
                    val mncIdx = cursor.getColumnIndex("mnc")
                    val typeIdx = cursor.getColumnIndex("type")
                    val proxyIdx = cursor.getColumnIndex("proxy")
                    val portIdx = cursor.getColumnIndex("port")

                    val apnName = if (apnIdx != -1) cursor.getString(apnIdx) else ""
                    if (apnName.isNullOrBlank()) continue

                    val obj = JSONObject().apply {
                        put("name", (if (nameIdx != -1) cursor.getString(nameIdx) else "系统默认") + "_读取")
                        put("apn", apnName)
                        put("mcc", if (mccIdx != -1) cursor.getString(mccIdx) ?: "" else "")
                        put("mnc", if (mncIdx != -1) cursor.getString(mncIdx) ?: "" else "")
                        put("apnType", if (typeIdx != -1) cursor.getString(typeIdx) ?: "default,supl" else "default,supl")
                        put("proxy", if (proxyIdx != -1) cursor.getString(proxyIdx) ?: "" else "")
                        put("port", if (portIdx != -1) cursor.getString(portIdx) ?: "" else "")
                        put("authType", 0)
                        put("protocol", 0)
                        put("roamingProtocol", 0)
                        put("is_active", false)
                    }
                    existingArray.put(obj)
                    count++
                }
                cursor.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "ContentResolver 读取受阻，切至 Root 通道...")
        }

        if (count > 0) {
            prefs.edit().putString("apn_list", existingArray.toString()).apply()
            Toast.makeText(context, "✅ 从系统成功提取当前网络配置！", Toast.LENGTH_SHORT).show()
            onComplete()
            return
        }

        // 方式二：降级使用 Root / Shell 挂载查询
        CoroutineScope(Dispatchers.IO).launch {
            if (RootApnManager.checkRoot()) {
                try {
                    val queryCmd = "content query --uri content://telephony/carriers/preferapn"
                    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", queryCmd))
                    val reader = BufferedReader(InputStreamReader(process.inputStream))

                    // ★ 修复：空安全问题，引入非空局部变量
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line!! // 强制非空断言

                        if (currentLine.isBlank()) continue

                        val nameMatch = Regex("name=([^,]+)").find(currentLine)
                        val apnMatch = Regex("apn=([^,]+)").find(currentLine)
                        val mccMatch = Regex("mcc=(\\d+)").find(currentLine)
                        val mncMatch = Regex("mnc=(\\d+)").find(currentLine)
                        val typeMatch = Regex("type=([^,]+)").find(currentLine)

                        val apnVal = apnMatch?.groupValues?.get(1)?.trim('"') ?: ""
                        if (apnVal.isNotBlank() && apnVal != "null") {
                            val obj = JSONObject().apply {
                                put("name", (nameMatch?.groupValues?.get(1)?.trim('"') ?: "系统默认") + "_Root")
                                put("apn", apnVal)
                                put("mcc", mccMatch?.groupValues?.get(1) ?: "460")
                                put("mnc", mncMatch?.groupValues?.get(1) ?: "00")
                                put("apnType", typeMatch?.groupValues?.get(1)?.trim('"') ?: "default,supl")
                                put("authType", 0)
                                put("protocol", 0)
                                put("roamingProtocol", 0)
                                put("is_active", false)
                            }
                            existingArray.put(obj)
                            count++
                        }
                    }
                    process.waitFor()
                } catch (e: Exception) {
                    Log.e(TAG, "Root 读取 APN 异常", e)
                }

                withContext(Dispatchers.Main) {
                    if (count > 0) {
                        prefs.edit().putString("apn_list", existingArray.toString()).apply()
                        Toast.makeText(context, "✅ 通过 Root 成功提取当前系统配置！", Toast.LENGTH_SHORT).show()
                        onComplete()
                    } else {
                        Toast.makeText(context, "未找到有效的系统 APN 配置，请手动添加", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "读取失败：系统拒绝访问，且未获取 Root 权限", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}