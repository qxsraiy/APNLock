package com.sraiy.apnlock.admin

import android.content.Context
import android.telephony.SubscriptionManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

object RootApnManager {
    private const val TAG = "ApnLock_Root"

    /**
     * 执行 Root Shell 命令并返回结果
     */
    private suspend fun executeSuCommand(command: String): String = withContext(Dispatchers.IO) {
        var output = ""
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes(command + "\n")
            os.writeBytes("exit\n")
            os.flush()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            val sb = StringBuilder()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }
            while (errorReader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }
            process.waitFor()
            output = sb.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "Root 命令执行失败: ${e.message}")
        }
        output
    }

    suspend fun checkRoot(): Boolean {
        return executeSuCommand("id").contains("uid=0")
    }

    suspend fun clearApns() {
        val deleteCmd = "content delete --uri content://telephony/carriers --where \"user_editable=0 AND user_visible=1\""
        executeSuCommand(deleteCmd)
        Log.i(TAG, "已清理旧的 Root APN 配置")
    }

    /**
     * 核心：通过 Root 注入、全量查询并强锁 APN
     */
    suspend fun injectAndLockApn(context: Context, apnConfig: JSONObject): Boolean = withContext(Dispatchers.IO) {
        val name = apnConfig.optString("name", "Sraiy_APN").ifBlank { "Sraiy_APN" }
        val apn = apnConfig.optString("apn", "")
        val mcc = apnConfig.optString("mcc", "")
        val mnc = apnConfig.optString("mnc", "")

        if (apn.isEmpty() || mcc.isEmpty() || mnc.isEmpty()) {
            Log.e(TAG, "APN/MCC/MNC 不能为空")
            return@withContext false
        }

        val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        Log.i(TAG, "🎯 锁定当前数据卡: subId=$subId")

        val type = apnConfig.optString("apnType", "").ifEmpty { "default,supl" }
        val authType = apnConfig.optInt("authType", 0)

        // 1. 组装插入命令
        val insertCmdBuilder = StringBuilder("content insert --uri content://telephony/carriers ")
        insertCmdBuilder.append("--bind name:s:\"$name\" ")
        insertCmdBuilder.append("--bind apn:s:\"$apn\" ")
        insertCmdBuilder.append("--bind mcc:s:\"$mcc\" ")
        insertCmdBuilder.append("--bind mnc:s:\"$mnc\" ")
        insertCmdBuilder.append("--bind numeric:s:\"${mcc}${mnc}\" ")
        insertCmdBuilder.append("--bind type:s:\"$type\" ")
        insertCmdBuilder.append("--bind authtype:i:$authType ")

        val proxy = apnConfig.optString("proxy", "")
        if (proxy.isNotEmpty()) insertCmdBuilder.append("--bind proxy:s:\"$proxy\" ")

        val port = apnConfig.optString("port", "")
        if (port.isNotEmpty()) insertCmdBuilder.append("--bind port:s:\"$port\" ")

        // 绑定真实的 SIM 卡 ID
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            insertCmdBuilder.append("--bind sub_id:i:$subId ")
        }

        // 强锁机制：不可编辑 + 强制激活
        insertCmdBuilder.append("--bind user_editable:i:0 ")
        insertCmdBuilder.append("--bind user_visible:i:1 ")
        insertCmdBuilder.append("--bind current:i:1 ")
        insertCmdBuilder.append("--bind carrier_enabled:i:1 ")

        Log.i(TAG, "执行注入指令: $insertCmdBuilder")
        executeSuCommand(insertCmdBuilder.toString())

        // ==========================================
        // ★ 核心修复 1：将等待时间增加到 1500ms，确保底层 SQLite 彻底落盘完毕
        // 否则后续设置 preferapn 时，系统会因为找不到这个 ID 而默默丢弃指令！
        // ==========================================
        Log.i(TAG, "等待数据库落盘...")
        Thread.sleep(1500)

        // 2. 内存匹配获取真实 ID
        val queryCmd = "content query --uri content://telephony/carriers"
        val queryResult = executeSuCommand(queryCmd)

        var apnId: String? = null
        val rows = queryResult.split("Row: ")
        for (row in rows.reversed()) {
            if (row.contains("name=$name") || row.contains("name=\"$name\"")) {
                val idMatch = Regex("_id=(\\d+)").find(row)
                if (idMatch != null) {
                    apnId = idMatch.groupValues[1]
                    break
                }
            }
        }

        if (apnId != null) {
            Log.i(TAG, "✅ 提取到真实 APN ID: $apnId，执行无损平滑切换！")

            // ==========================================
            // ★ 核心修复 2：绝不使用 delete 破坏 XML 结构，直接用 update 强制覆写
            // ==========================================
            val preferUris = mutableListOf("content://telephony/carriers/preferapn")
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                preferUris.add("content://telephony/carriers/preferapn/subId/$subId")
            }

            for (uri in preferUris) {
                // 先 update 后 insert，确保不管系统底层认哪种方式，都能将指针掰过去
                executeSuCommand("content update --uri $uri --bind apn_id:i:$apnId")
                executeSuCommand("content insert --uri $uri --bind apn_id:i:$apnId")
            }

            // 3. 重启移动数据，强制系统 UI 和基带重载配置
            Log.i(TAG, "♻️ 正在重启移动数据，强制基带重载配置...")
            executeSuCommand("svc data disable")
            Thread.sleep(1500)
            executeSuCommand("svc data enable")

            Log.i(TAG, "✅ 移动数据已重启，锁定彻底生效！")
            return@withContext true
        } else {
            Log.e(TAG, "❌ 在全量查询列表中未找到名为 $name 的记录！")
            return@withContext false
        }
    }
}