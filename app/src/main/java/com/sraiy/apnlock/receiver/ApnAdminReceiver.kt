package com.sraiy.apnlock.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log
import android.widget.Toast

class ApnAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i("ApnLock", "设备所有者 (DO) 已激活")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i("ApnLock", "设备所有者 (DO) 被取消激活")
    }

    // ★ 接收从 Dhizuku 转移过来的 DO 权限成功的回调
    override fun onTransferOwnershipComplete(context: Context, bundle: PersistableBundle?) {
        super.onTransferOwnershipComplete(context, bundle)
        Log.i("ApnLock", "✅ 成功接收设备所有者权限！")
        Toast.makeText(context, "✅ 成功接管设备所有者权限！", Toast.LENGTH_LONG).show()
    }
}