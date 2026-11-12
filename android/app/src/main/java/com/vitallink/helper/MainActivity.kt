package com.vitallink.helper

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One screen. Not linked: the server and the code from the website (Settings, Connected Devices, Android phone).
 * Linked: whose account it sends to, Allow access (Health Connect permissions), Sync now and Unlink.
 */
class MainActivity : ComponentActivity() {

    private lateinit var store: Store
    private lateinit var root: LinearLayout

    private val askPermissions = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        render()
        if (it.isNotEmpty()) startSync()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val pad = dp(20)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }.also { root.addView(it) }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }.also { root.addView(it) }

    private fun field(hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = type
        setSingleLine()
    }.also { root.addView(it) }

    private fun render() {
        root.removeAllViews()
        text("VitalLink Sync", 24f, bold = true)
        text("Sends your Health Connect data to your VitalLink account.", 15f)

        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> {}
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                text("Health Connect needs to be installed or updated first.")
                button("Get Health Connect") {
                    val uri = Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
                    startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending"))
                }
                return
            }
            else -> {
                text("Health Connect isn't available on this phone (it needs Android 8 or later).")
                return
            }
        }

        if (!store.linked) renderLink() else renderLinked()
    }

    private fun renderLink() {
        text("1. On the VitalLink website, open Settings › Connected Devices › Add a device › Android phone.", 15f)
        text("2. Type the code it shows here.", 15f)
        val code = field("Code, e.g. ABCD-EFGH", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        val server = field("Server", store.server, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val status = text("", 14f)
        lateinit var linkButton: Button
        linkButton = button("Link this phone") {
            val c = code.text.toString().trim()
            if (c.isEmpty()) {
                status.text = "Enter the code from the website."
                return@button
            }
            linkButton.isEnabled = false
            status.text = "Linking… (the server can take up to a minute to wake up)"
            store.server = server.text.toString().ifBlank { BuildConfig.DEFAULT_SERVER }
            lifecycleScope.launch {
                try {
                    val name = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
                    val linked = withContext(Dispatchers.IO) { Api(store.server).link(c, name) }
                    store.token = linked.token
                    store.email = linked.email
                    SyncWorker.schedule(this@MainActivity)
                    render()
                } catch (e: IOException) {
                    status.text = e.message ?: "Couldn't reach VitalLink."
                    linkButton.isEnabled = true
                }
            }
        }
    }

    private fun renderLinked() {
        text("Sending to ${store.email}", 16f, bold = true)
        val client = HealthConnectClient.getOrCreate(this)
        val perms = text("Checking access…", 14f)
        val result = text(store.lastResult ?: "Not synced yet.", 14f)
        button("Allow access") {
            askPermissions.launch(Reader.READ_PERMISSIONS + Reader.BACKGROUND + Reader.HISTORY)
        }
        button("Sync now") { startSync(result) }
        text("It also syncs about every 3 hours on its own.", 13f)
        button("Unlink this phone") {
            SyncWorker.cancel(this)
            store.unlink()
            render()
        }
        text("Unlinking here stops uploads from this app. To void the link completely, also remove the phone on the website.", 13f)
        lifecycleScope.launch {
            val granted = client.permissionController.getGrantedPermissions()
            val n = Reader.READ_PERMISSIONS.count { it in granted }
            perms.text = when {
                n == 0 -> "No health data allowed yet. Tap Allow access."
                Reader.BACKGROUND !in granted -> "$n of ${Reader.READ_PERMISSIONS.size} data types allowed. Background reading is off, so syncs happen when you open the app."
                else -> "$n of ${Reader.READ_PERMISSIONS.size} data types allowed, including background reading."
            }
        }
    }

    private fun startSync(result: TextView? = null) {
        result?.text = "Syncing…"
        val id = SyncWorker.now(this)
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(id).observe(this) { info ->
            if (info != null && info.state.isFinished) {
                if (!store.linked) render() else result?.text = store.lastResult ?: info.state.name
            } else if (info?.state == WorkInfo.State.ENQUEUED) {
                result?.text = "Waiting for a network connection…"
            }
        }
    }
}
