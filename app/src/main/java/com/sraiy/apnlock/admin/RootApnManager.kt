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

    // ★ 智能拦截机制：防止连续执行两次时，误删刚建好的 APN 导致基带抓瞎
    private var lastClearTime = 0L

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
            os.close() // ★ 极其重要：关闭输入流防止底层 Shell 卡死堵塞

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
        val now = System.currentTimeMillis()
        // 智能拦截 3 秒内的重复清理，保护基带正在切换的热缓存
        if (now - lastClearTime < 3000) {
            Log.i(TAG, "短时间内重复执行清理，已智能拦截！")
            return
        }
        lastClearTime = now

        // 1. 删除我们锁定的专属 APN
        val deleteCmd = "content delete --uri content://telephony/carriers --where \"user_editable=0 AND user_visible=1\""
        executeSuCommand(deleteCmd)

        // 2. ★ 核心恢复 1：加上安全的 --where，防止被系统底层拦截无条件全表更新！
        // 强行把被休眠的系统原生 APN 全部唤醒！
        val enableCmd = "content update --uri content://telephony/carriers --bind carrier_enabled:i:1 --where \"carrier_enabled=0\""
        executeSuCommand(enableCmd)

        // 3. ★ 核心恢复 2：绝不使用 delete 破坏 preferapn！
        // 智能抓取一个存活的原生 APN，将首选指针指过去，修复系统瞎眼状态
        val queryResult = executeSuCommand("content query --uri content://telephony/carriers")
        var fallbackApnId: String? = null
        val rows = queryResult.split("Row: ")
        for (row in rows) {
            // 只要是有 ID 的（因为我们的专属 APN 刚才已经被删了，剩下的必然是原生的）
            val idMatch = Regex("_id=(\\d+)").find(row)
            if (idMatch != null) {
                fallbackApnId = idMatch.groupValues[1]
                break
            }
        }

        if (fallbackApnId != null) {
            // 采用双字段饱和式覆盖，安全重建系统的默认首选网络
            val preferUris = mutableListOf("content://telephony/carriers/preferapn")
            val subId = SubscriptionManager.getDefaultDataSubscriptionId()
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                preferUris.add("content://telephony/carriers/preferapn/subId/$subId")
            }
            for (uri in preferUris) {
                executeSuCommand("content insert --uri $uri --bind apn_id:i:$fallbackApnId --bind _id:i:$fallbackApnId")
                executeSuCommand("content update --uri $uri --bind apn_id:i:$fallbackApnId --bind _id:i:$fallbackApnId")
            }
            Log.i(TAG, "已成功修复首选 APN 指针，指向原生 ID: $fallbackApnId")
        }

        // 4. ★ 核心恢复 3：致命补全，必须重启基带！否则基带死抱着旧缓存不撒手！
        Log.i(TAG, "♻️ 正在重启移动数据，强制系统回落原生默认网络...")
        executeSuCommand("svc data disable")
        Thread.sleep(1500)
        executeSuCommand("svc data enable")

        Log.i(TAG, "已清理 Root 专属 APN，并全面唤醒了系统的原生 APN")
    }

    /**
     * 核心：通过 Root 注入、全量查询并强锁 APN (绝无删减，完全使用你的原版)
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

        // ==========================================
        // 先查询是否已经存在该 APN（应对 ActionSetApn 的第二次巩固调用）
        // ==========================================
        val queryCmd = "content query --uri content://telephony/carriers"
        var queryResult = executeSuCommand(queryCmd)

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

        // 如果不存在，则进行全新注入
        if (apnId == null) {
            val type = apnConfig.optString("apnType", "").ifEmpty { "default,supl" }
            val authType = apnConfig.optInt("authType", 0)

            val insertCmdBuilder = StringBuilder("content insert --uri content://telephony/carriers ")
            insertCmdBuilder.append("--bind name:s:\"$name\" ")
            insertCmdBuilder.append("--bind apn:s:\"$apn\" ")
            insertCmdBuilder.append("--bind mcc:s:\"$mcc\" ")
            insertCmdBuilder.append("--bind mnc:s:\"$mnc\" ")
            insertCmdBuilder.append("--bind numeric:s:\"${mcc}${mnc}\" ")
            insertCmdBuilder.append("--bind type:s:\"$type\" ")
            insertCmdBuilder.append("--bind authtype:i:$authType ")

            // 补充必带协议，防止挑剔的基带直接丢弃
            insertCmdBuilder.append("--bind protocol:s:\"IP\" ")
            insertCmdBuilder.append("--bind roaming_protocol:s:\"IP\" ")

            val proxy = apnConfig.optString("proxy", "")
            if (proxy.isNotEmpty()) insertCmdBuilder.append("--bind proxy:s:\"$proxy\" ")

            val port = apnConfig.optString("port", "")
            if (port.isNotEmpty()) insertCmdBuilder.append("--bind port:s:\"$port\" ")

            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                insertCmdBuilder.append("--bind sub_id:i:$subId ")
            }

            insertCmdBuilder.append("--bind user_editable:i:0 ")
            insertCmdBuilder.append("--bind user_visible:i:1 ")
            insertCmdBuilder.append("--bind current:i:1 ")
            insertCmdBuilder.append("--bind carrier_enabled:i:1 ")

            Log.i(TAG, "执行全新注入指令: $insertCmdBuilder")
            executeSuCommand(insertCmdBuilder.toString())

            Log.i(TAG, "等待数据库落盘...")
            Thread.sleep(1500)

            // 再次匹配真实 ID
            queryResult = executeSuCommand(queryCmd)
            val newRows = queryResult.split("Row: ")
            for (row in newRows.reversed()) {
                if (row.contains("name=$name") || row.contains("name=\"$name\"")) {
                    val idMatch = Regex("_id=(\\d+)").find(row)
                    if (idMatch != null) {
                        apnId = idMatch.groupValues[1]
                        break
                    }
                }
            }
        } else {
            Log.i(TAG, "检测到 APN (ID=$apnId) 已在上一轮写入，直接跳过注入，进入基带巩固锁定！")
        }

        if (apnId != null) {
            Log.i(TAG, "✅ 提取到真实 APN ID: $apnId，准备执行饱和式封锁！")

            // ==========================================
            // ★ 终极破局机制：釜底抽薪，把系统退路全砸了！
            // 强行将该 SIM 卡下其他所有的原生 APN 全部休眠 (carrier_enabled=0)
            // 逼迫 Android DcTracker 只能死死咬住我们的 APN！
            // ==========================================
            val disableOthersCmd = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                "content update --uri content://telephony/carriers --bind carrier_enabled:i:0 --where \"_id!=$apnId AND sub_id=$subId\""
            } else {
                "content update --uri content://telephony/carriers --bind carrier_enabled:i:0 --where \"_id!=$apnId\""
            }
            executeSuCommand(disableOthersCmd)
            Log.i(TAG, "已强行休眠所有原生 APN，切断了基带回落的退路")

            // ==========================================
            // ★ 完美切换：去除了导致失败的 _id 绑定，精准打击 preferapn
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