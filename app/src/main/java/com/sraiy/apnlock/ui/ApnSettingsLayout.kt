package com.sraiy.apnlock.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.telephony.data.ApnSetting
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.sraiy.apnlock.admin.ActionSetApn
import org.json.JSONArray
import org.json.JSONObject

@SuppressLint("ViewConstructor")
class ApnSettingsLayout(
    context: Context,
    private val onStateChange: (isEditing: Boolean) -> Unit,
    private val onExportClick: () -> Unit,
    private val onImportClick: () -> Unit,
    private val onFetchSystemClick: () -> Unit
) : FrameLayout(context) {

    private val prefs = context.getSharedPreferences("sraiy_apn_prefs", Context.MODE_PRIVATE)

    private val listContainer = LinearLayout(context)
    private val editContainer = LinearLayout(context)
    private val apnListLayout = LinearLayout(context)

    private lateinit var inputName: EditText
    private lateinit var inputApn: EditText
    private lateinit var inputProxy: EditText
    private lateinit var inputPort: EditText
    private lateinit var inputUser: EditText
    private lateinit var inputPass: EditText

    private lateinit var inputApnType: TextView
    private lateinit var inputNetworkType: TextView

    private lateinit var inputMmsc: EditText
    private lateinit var inputMmsProxy: EditText
    private lateinit var inputMmsPort: EditText
    private lateinit var inputProfileId: EditText
    private lateinit var inputCarrierId: EditText
    private lateinit var inputMtu4: EditText
    private lateinit var inputMtu6: EditText
    private lateinit var inputMcc: EditText
    private lateinit var inputMnc: EditText

    private lateinit var spinnerAuth: LinearLayout
    private lateinit var spinnerProto: LinearLayout
    private lateinit var spinnerRoaming: LinearLayout
    private lateinit var spinnerMvno: LinearLayout
    private lateinit var switchPersistent: Switch
    private lateinit var switchAlwaysOn: Switch

    private val authValues = intArrayOf(ApnSetting.AUTH_TYPE_NONE, ApnSetting.AUTH_TYPE_PAP, ApnSetting.AUTH_TYPE_CHAP, ApnSetting.AUTH_TYPE_PAP_OR_CHAP)
    private val protoValues = intArrayOf(ApnSetting.PROTOCOL_IP, ApnSetting.PROTOCOL_IPV6, ApnSetting.PROTOCOL_IPV4V6)
    private val mvnoValues = intArrayOf(-1, ApnSetting.MVNO_TYPE_SPN, ApnSetting.MVNO_TYPE_IMSI, ApnSetting.MVNO_TYPE_GID, ApnSetting.MVNO_TYPE_ICCID)

    private var currentEditIndex = -1

    // ★ 统一 DP 转换，杜绝按钮压扁
    private fun dp2px(dp: Float): Int {
        return (dp * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    init {
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setupListContainer()
        setupEditContainer()
        addView(listContainer)
        addView(editContainer)
        showList()
    }

    private fun setupListContainer() {
        listContainer.apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // ★ 核心修复：强制工具栏按钮高度为 42dp，文字绝对居中
        val toolbarLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp2px(15f)) }
            weightSum = 3f
        }

        fun createSmallBtn(text: String, colorStr: String, onClick: () -> Unit): Button {
            return Button(context).apply {
                this.text = text
                textSize = 12f
                setPadding(0, 0, 0, 0) // 去除原生 padding，防遮挡
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                isAllCaps = false
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(colorStr))
                    cornerRadius = dp2px(20f).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(0, dp2px(42f), 1f).apply { setMargins(dp2px(4f), 0, dp2px(4f), 0) }
                setOnClickListener { onClick() }
            }
        }

        toolbarLayout.addView(createSmallBtn("📤 导出", "#5856D6") { onExportClick() })
        toolbarLayout.addView(createSmallBtn("📥 导入", "#007AFF") { onImportClick() })
        toolbarLayout.addView(createSmallBtn("🔍 读取系统", "#34C759") { onFetchSystemClick() })
        listContainer.addView(toolbarLayout)

        val scroll = ScrollView(context).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        apnListLayout.orientation = LinearLayout.VERTICAL
        scroll.addView(apnListLayout)
        listContainer.addView(scroll)

        val addBtn = Button(context).apply {
            text = "+ 添加新的 APN"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#007AFF"))
                cornerRadius = dp2px(24f).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(55f)).apply { setMargins(0, dp2px(10f), 0, 0) }
            setOnClickListener { openEditor(-1) }
        }
        listContainer.addView(addBtn)
    }

    private fun setupEditContainer() {
        editContainer.apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp2px(15f))
            setOnClickListener { showList() }
        }
        header.addView(TextView(context).apply { text = "← 返回  "; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#007AFF")) })
        header.addView(TextView(context).apply { text = "编辑 APN 配置"; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#1C1C1E")) })
        editContainer.addView(header)

        val scroll = ScrollView(context).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        val formLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp2px(5f), 0, dp2px(5f), dp2px(20f)) }

        inputName = createInput("配置名称 (Name)", "")
        inputApn = createInput("接入点 (APN)", "必填")
        inputProxy = createInput("网络代理 (Proxy)", "")
        inputPort = createInput("代理端口 (Port)", "", InputType.TYPE_CLASS_NUMBER)
        inputUser = createInput("用户名 (Username)", "")
        inputPass = createInput("密码 (Password)", "", InputType.TYPE_TEXT_VARIATION_PASSWORD)

        inputApnType = createMultiSelectText("APN 类型 (APN type)")
        setupApnTypeMultiSelect()

        inputMmsc = createInput("彩信服务中心 (MMSC)", "")
        inputMmsProxy = createInput("彩信代理 (MMS proxy)", "")
        inputMmsPort = createInput("彩信端口 (MMS port)", "", InputType.TYPE_CLASS_NUMBER)

        formLayout.addView(inputName); formLayout.addView(inputApn); formLayout.addView(inputProxy); formLayout.addView(inputPort)
        formLayout.addView(inputUser); formLayout.addView(inputPass); formLayout.addView(inputApnType)
        formLayout.addView(inputMmsc); formLayout.addView(inputMmsProxy); formLayout.addView(inputMmsPort)

        spinnerAuth = createSpinner("认证类型 (Authentication type)", arrayOf("无 (None)", "PAP", "CHAP", "PAP or CHAP"))
        spinnerProto = createSpinner("APN 协议 (APN protocol)", arrayOf("IPv4", "IPv6", "IPv4/IPv6"))
        spinnerRoaming = createSpinner("APN 漫游协议 (APN roaming protocol)", arrayOf("IPv4", "IPv6", "IPv4/IPv6"))
        formLayout.addView(spinnerAuth); formLayout.addView(spinnerProto); formLayout.addView(spinnerRoaming)

        inputNetworkType = createMultiSelectText("承载网络类型 (Network type)")
        setupNetworkTypeMultiSelect()
        formLayout.addView(inputNetworkType)

        inputProfileId = createInput("配置文件 ID (Profile id)", "一般无需填写", InputType.TYPE_CLASS_NUMBER)
        inputCarrierId = createInput("运营商 ID (Carrier id)", "一般无需填写", InputType.TYPE_CLASS_NUMBER)
        formLayout.addView(inputProfileId); formLayout.addView(inputCarrierId)

        val mtuLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); weightSum = 2f }
        inputMtu4 = createInput("MTU (IPv4)", "留空自动协商", InputType.TYPE_CLASS_NUMBER).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp2px(8f), dp2px(10f)) } }
        inputMtu6 = createInput("MTU (IPv6)", "留空自动协商", InputType.TYPE_CLASS_NUMBER).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp2px(8f), 0, 0, dp2px(10f)) } }
        mtuLayout.addView(inputMtu4); mtuLayout.addView(inputMtu6)
        formLayout.addView(mtuLayout)

        spinnerMvno = createSpinner("虚拟运营商类型 (MVNO type)", arrayOf("无 (None)", "SPN", "IMSI", "GID", "ICCID"))
        formLayout.addView(spinnerMvno)

        val codeLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); weightSum = 2f }
        inputMcc = createInput("MCC (国家码)", "必填, 如: 460", InputType.TYPE_CLASS_NUMBER).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp2px(8f), dp2px(10f)) } }
        inputMnc = createInput("MNC (网络码)", "必填, 如: 00", InputType.TYPE_CLASS_NUMBER).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp2px(8f), 0, 0, dp2px(10f)) } }
        codeLayout.addView(inputMcc); codeLayout.addView(inputMnc)
        formLayout.addView(codeLayout)

        val perLayout = createSwitchItem("永久驻留 (Persistent)\n保存至基带存储，防重启丢失"); switchPersistent = perLayout.tag as Switch; formLayout.addView(perLayout)
        val alwLayout = createSwitchItem("始终保持连接 (Always on)\n强制数据链路不断开，适用严苛专网"); switchAlwaysOn = alwLayout.tag as Switch; formLayout.addView(alwLayout)

        val saveBtn = Button(context).apply {
            text = "保 存 配 置"
            textSize = 15f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply { setColor(Color.parseColor("#34C759")); cornerRadius = dp2px(24f).toFloat() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(55f)).apply { setMargins(0, dp2px(15f), 0, 0) }
            setOnClickListener { saveCurrentApn() }
        }
        formLayout.addView(saveBtn)

        scroll.addView(formLayout)
        editContainer.addView(scroll)
    }

    private fun setupApnTypeMultiSelect() {
        val actualArray = arrayOf("default", "mms", "supl", "hipri", "fota", "cbs", "mcx", "xcap", "dun", "ham", "ia", "pss", "wap", "wapless", "aol", "internet", "cs", "isp", "ike", "m2m", "x-web", "voicemail", "video", "mm", "ims")
        val displayArray = arrayOf("default (默认上网)", "mms (收发彩信)", "supl (辅助定位)", "hipri (高优先级流)", "fota (固件升级)", "cbs (小区广播)", "mcx (关键通讯)", "xcap", "dun (热点共享)", "ham", "ia (初始附着)", "pss", "wap", "wapless", "aol", "internet (互联网)", "cs", "isp", "ike", "m2m", "x-web", "voicemail", "video", "mm", "ims (VoLTE)")

        inputApnType.setOnClickListener {
            val currentValues = inputApnType.text.toString().split(",").map { it.trim() }
            val checkedItems = BooleanArray(actualArray.size) { i -> currentValues.contains(actualArray[i]) }

            AlertDialog.Builder(context).setTitle("选择 APN 类型").setMultiChoiceItems(displayArray, checkedItems) { _, which, isChecked -> checkedItems[which] = isChecked }.setPositiveButton("确定") { _, _ ->
                val selected = mutableListOf<String>()
                for (i in actualArray.indices) { if (checkedItems[i]) selected.add(actualArray[i]) }
                inputApnType.text = selected.joinToString(",")
            }.setNegativeButton("取消", null).show()
        }
    }

    private fun setupNetworkTypeMultiSelect() {
        val displayArray = arrayOf("NR (5G 专属网络)", "LTE (4G 网络)", "UMTS / HSPA (联通 3G)", "CDMA / EVDO (电信 2G/3G)", "GSM / EDGE (传统 2G)")
        val actualArray = arrayOf("NR", "LTE", "UMTS", "CDMA", "GSM")

        inputNetworkType.setOnClickListener {
            val currentValues = inputNetworkType.text.toString().split(",").map { it.trim() }
            val checkedItems = BooleanArray(actualArray.size) { i -> currentValues.contains(actualArray[i]) }

            AlertDialog.Builder(context).setTitle("选择网络承载").setMultiChoiceItems(displayArray, checkedItems) { _, which, isChecked -> checkedItems[which] = isChecked }.setPositiveButton("确定") { _, _ ->
                val selected = mutableListOf<String>()
                for (i in actualArray.indices) { if (checkedItems[i]) selected.add(actualArray[i]) }
                inputNetworkType.text = selected.joinToString(",")
            }.setNegativeButton("取消", null).show()
        }
    }

    fun refreshList() {
        showList()
    }

    private fun showList() {
        editContainer.visibility = View.GONE
        listContainer.visibility = View.VISIBLE
        onStateChange(false)
        apnListLayout.removeAllViews()

        val apnArray = JSONArray(prefs.getString("apn_list", "[]"))

        if (apnArray.length() == 0) {
            apnListLayout.addView(TextView(context).apply {
                text = "暂无配置，请点击添加、导入或读取"
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#8E8E93"))
                setPadding(0, dp2px(50f), 0, dp2px(50f))
            })
            return
        }

        for (i in 0 until apnArray.length()) {
            val obj = apnArray.getJSONObject(i)
            val isActive = obj.optBoolean("is_active", false)

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp2px(15f), dp2px(15f), dp2px(15f), dp2px(15f))
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp2px(16f).toFloat()
                    setStroke(dp2px(if (isActive) 2f else 1f), if (isActive) Color.parseColor("#007AFF") else Color.parseColor("#D1D1D6"))
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp2px(12f)) }
            }

            val switchLayout = LinearLayout(context).apply { setPadding(0, 0, dp2px(10f), 0) }
            val activeSwitch = Switch(context).apply {
                isChecked = isActive
                setOnCheckedChangeListener { _, checked ->
                    for (j in 0 until apnArray.length()) {
                        apnArray.getJSONObject(j).put("is_active", if (j == i) checked else false)
                    }
                    prefs.edit().putString("apn_list", apnArray.toString()).apply()
                    ActionSetApn.syncApns(context)
                    showList()
                }
            }
            switchLayout.addView(activeSwitch)

            val textLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                foreground = context.getDrawable(outValue.resourceId)
                isClickable = true; isFocusable = true
                setOnClickListener { openEditor(i) }
            }
            textLayout.addView(TextView(context).apply {
                text = obj.optString("name", "未命名")
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1C1C1E"))
            })
            textLayout.addView(TextView(context).apply {
                text = "APN: ${obj.optString("apn", "空")}"
                textSize = 12f
                setTextColor(Color.parseColor("#636366"))
                setPadding(0, dp2px(2f), 0, 0)
            })

            val delBtn = TextView(context).apply {
                text = "删除"
                textSize = 13f
                setTextColor(Color.parseColor("#FF3B30"))
                setPadding(dp2px(10f), dp2px(10f), 0, dp2px(10f))
                setOnClickListener {
                    apnArray.remove(i)
                    prefs.edit().putString("apn_list", apnArray.toString()).apply()
                    ActionSetApn.syncApns(context)
                    showList()
                }
            }

            card.addView(switchLayout)
            card.addView(textLayout)
            card.addView(delBtn)
            apnListLayout.addView(card)
        }
    }

    private fun openEditor(index: Int) {
        currentEditIndex = index
        listContainer.visibility = View.GONE
        editContainer.visibility = View.VISIBLE
        onStateChange(true)

        if (index == -1) {
            inputName.setText(""); inputApn.setText(""); inputProxy.setText(""); inputPort.setText(""); inputUser.setText(""); inputPass.setText("")
            inputApnType.text = ""; inputNetworkType.text = ""
            inputMmsc.setText(""); inputMmsProxy.setText(""); inputMmsPort.setText("")
            inputProfileId.setText(""); inputCarrierId.setText(""); inputMtu4.setText(""); inputMtu6.setText("")
            inputMcc.setText(""); inputMnc.setText("")
            setSpinnerSelection(spinnerAuth, ApnSetting.AUTH_TYPE_NONE, authValues)
            setSpinnerSelection(spinnerProto, ApnSetting.PROTOCOL_IP, protoValues)
            setSpinnerSelection(spinnerRoaming, ApnSetting.PROTOCOL_IP, protoValues)
            setSpinnerSelection(spinnerMvno, -1, mvnoValues)
            switchPersistent.isChecked = false; switchAlwaysOn.isChecked = false
        } else {
            val apnArray = JSONArray(prefs.getString("apn_list", "[]"))
            val obj = apnArray.getJSONObject(index)
            inputName.setText(obj.optString("name")); inputApn.setText(obj.optString("apn")); inputProxy.setText(obj.optString("proxy")); inputPort.setText(obj.optString("port")); inputUser.setText(obj.optString("user")); inputPass.setText(obj.optString("pass"))
            inputApnType.text = obj.optString("apnType", ""); inputNetworkType.text = obj.optString("networkType", "")
            inputMmsc.setText(obj.optString("mmsc")); inputMmsProxy.setText(obj.optString("mmsProxy")); inputMmsPort.setText(obj.optString("mmsPort")); inputProfileId.setText(obj.optString("profileId")); inputCarrierId.setText(obj.optString("carrierId")); inputMtu4.setText(obj.optString("mtu4")); inputMtu6.setText(obj.optString("mtu6")); inputMcc.setText(obj.optString("mcc")); inputMnc.setText(obj.optString("mnc"))
            setSpinnerSelection(spinnerAuth, obj.optInt("authType"), authValues); setSpinnerSelection(spinnerProto, obj.optInt("protocol"), protoValues); setSpinnerSelection(spinnerRoaming, obj.optInt("roamingProtocol"), protoValues); setSpinnerSelection(spinnerMvno, obj.optInt("mvnoType", -1), mvnoValues)
            switchPersistent.isChecked = obj.optBoolean("persistent", false); switchAlwaysOn.isChecked = obj.optBoolean("alwaysOn", false)
        }
    }

    private fun saveCurrentApn() {
        if (inputApn.text.isBlank() || inputMcc.text.isBlank() || inputMnc.text.isBlank()) {
            Toast.makeText(context, "APN、MCC、MNC不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        val apnArray = JSONArray(prefs.getString("apn_list", "[]"))
        val obj = JSONObject().apply {
            put("name", inputName.text.toString().trim()); put("apn", inputApn.text.toString().trim()); put("proxy", inputProxy.text.toString().trim()); put("port", inputPort.text.toString().trim()); put("user", inputUser.text.toString().trim()); put("pass", inputPass.text.toString().trim())
            put("apnType", inputApnType.text.toString().trim()); put("networkType", inputNetworkType.text.toString().trim())
            put("mmsc", inputMmsc.text.toString().trim()); put("mmsProxy", inputMmsProxy.text.toString().trim()); put("mmsPort", inputMmsPort.text.toString().trim()); put("profileId", inputProfileId.text.toString().trim()); put("carrierId", inputCarrierId.text.toString().trim()); put("mtu4", inputMtu4.text.toString().trim()); put("mtu6", inputMtu6.text.toString().trim()); put("mcc", inputMcc.text.toString().trim()); put("mnc", inputMnc.text.toString().trim())
            put("authType", authValues[spinnerAuth.selectedItemPosition]); put("protocol", protoValues[spinnerProto.selectedItemPosition]); put("roamingProtocol", protoValues[spinnerRoaming.selectedItemPosition]); put("mvnoType", mvnoValues[spinnerMvno.selectedItemPosition])
            put("persistent", switchPersistent.isChecked); put("alwaysOn", switchAlwaysOn.isChecked)
            put("is_active", if (currentEditIndex != -1) apnArray.getJSONObject(currentEditIndex).optBoolean("is_active", false) else false)
        }
        if (currentEditIndex == -1) apnArray.put(obj) else apnArray.put(currentEditIndex, obj)
        prefs.edit().putString("apn_list", apnArray.toString()).apply()
        ActionSetApn.syncApns(context)
        showList()
    }

    fun isEditing(): Boolean = editContainer.visibility == View.VISIBLE

    fun onBackPressed(): Boolean {
        if (isEditing()) { showList(); return true }
        return false
    }

    private fun createInput(hintText: String, placeholder: String, inputTypeFlag: Int = InputType.TYPE_CLASS_TEXT): EditText {
        return EditText(context).apply {
            hint = "$hintText${if (placeholder.isNotEmpty()) " - $placeholder" else ""}"
            textSize = 14f
            inputType = inputTypeFlag
            setPadding(dp2px(15f), dp2px(15f), dp2px(15f), dp2px(15f))
            setTextColor(Color.parseColor("#1C1C1E"))
            setHintTextColor(Color.parseColor("#8E8E93"))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp2px(12f).toFloat(); setStroke(dp2px(1f), Color.parseColor("#D1D1D6")) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp2px(10f)) }
        }
    }

    private fun createMultiSelectText(hintText: String): TextView {
        return TextView(context).apply {
            hint = hintText
            textSize = 14f
            setPadding(dp2px(15f), dp2px(15f), dp2px(15f), dp2px(15f))
            setTextColor(Color.parseColor("#1C1C1E"))
            setHintTextColor(Color.parseColor("#8E8E93"))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp2px(12f).toFloat(); setStroke(dp2px(1f), Color.parseColor("#D1D1D6")) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp2px(10f)) }
            isClickable = true
            isFocusable = false
        }
    }

    private fun createSpinner(titleText: String, items: Array<String>): LinearLayout {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp2px(12f).toFloat(); setStroke(dp2px(1f), Color.parseColor("#D1D1D6")) }
            setPadding(dp2px(10f), dp2px(5f), dp2px(10f), dp2px(10f))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp2px(10f)) }
        }
        layout.addView(TextView(context).apply { text = titleText; textSize = 11f; setTextColor(Color.parseColor("#8E8E93")); setPadding(dp2px(5f), dp2px(5f), dp2px(5f), 0) })
        val spinner = Spinner(context).apply { adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items) }
        layout.addView(spinner); layout.tag = spinner
        return layout
    }

    private fun createSwitchItem(label: String): LinearLayout {
        val layout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp2px(5f), dp2px(8f), dp2px(5f), dp2px(8f)) }
        layout.addView(TextView(context).apply { text = label; textSize = 12f; setTextColor(Color.parseColor("#1C1C1E")); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        val switch = Switch(context)
        layout.addView(switch); layout.tag = switch
        return layout
    }

    private val LinearLayout.selectedItemPosition: Int get() = (this.tag as Spinner).selectedItemPosition
    private fun setSpinnerSelection(spinnerLayout: LinearLayout, value: Int, valuesArray: IntArray) {
        val index = valuesArray.indexOf(value).takeIf { it >= 0 } ?: 0
        (spinnerLayout.tag as Spinner).setSelection(index)
    }
}