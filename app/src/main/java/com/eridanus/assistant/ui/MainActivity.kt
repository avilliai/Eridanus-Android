package com.eridanus.assistant.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.eridanus.assistant.R
import com.eridanus.assistant.data.ConfigManager
import com.eridanus.assistant.databinding.ActivityMainBinding
import com.eridanus.assistant.net.EridanusApiClient
import com.eridanus.assistant.service.FloatingWindowService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var configManager: ConfigManager

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startFloatingService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "\u672a\u6388\u4e88\u5f55\u5c4f\u622a\u5c4f\u6743\u9650\uff0c\u4ec5\u542f\u7528\u60ac\u6d6e\u5bf9\u8bdd", Toast.LENGTH_SHORT).show()
            startFloatingService(0, null)
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (checkOverlayPermission()) {
            requestMediaProjectionPermission()
        } else {
            Toast.makeText(this, "\u8bf7\u6388\u4e88\u60ac\u6d6e\u7a97\u6743\u9650\u4ee5\u6b63\u5e38\u4f7f\u7528\u60ac\u6d6e\u529f\u80fd", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configManager = ConfigManager(this)

        // 动态读取当前版本号
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            binding.tvAppTitle.text = "Eridanus \u52a9\u7406 v${pInfo.versionName}"
        } catch (_: Exception) {
            binding.tvAppTitle.text = "Eridanus \u52a9\u7406 v1.1.0"
        }

        // 回显配置
        binding.etServerUrl.setText(configManager.serverUrl)
        binding.etAuthToken.setText(configManager.authToken)
        binding.etBindQq.setText(configManager.bindQqId.toString())
        binding.etBotName.setText(configManager.botName)

        updateServiceStatus()

        // 测试连接按钮
        binding.btnTestConn.setOnClickListener {
            saveCurrentConfig()
            val apiClient = EridanusApiClient(configManager.serverUrl, configManager.authToken)
            binding.tvStatus.text = "\u6b63\u5728\u6d4b\u8bd5\u8fde\u63a5..."
            lifecycleScope.launch {
                try {
                    val res = apiClient.checkStatus()
                    if (res.isSuccess) {
                        val status = res.getOrNull()
                        val botState = if (status?.botConnected == true) "Eridanus \u5df2\u8fde\u63a5" else "\u7b49\u5f85 Eridanus \u63a5\u5165"
                        val count = status?.activeClients ?: 0
                        binding.tvStatus.text = "\u2705 \u8fde\u63a5\u6210\u529f!\nBot\u72b6\u6001: " + botState + "\n\u5728\u7ebf\u5ba2\u6237\u7aef\u6570: " + count
                    } else {
                        val errMsg = res.exceptionOrNull()?.message ?: "\u8fde\u63a5\u5931\u8d25"
                        binding.tvStatus.text = "\u274c \u8fde\u63a5\u5931\u8d25: " + errMsg
                    }
                } catch (t: Throwable) {
                    binding.tvStatus.text = "\u274c \u53d1\u751f\u5f02\u5e38: " + (t.message ?: "")
                }
            }
        }

        // 恢复默认配置
        binding.btnResetConfig.setOnClickListener {
            configManager.clearConfig()
            binding.etServerUrl.setText("")
            binding.etAuthToken.setText("")
            binding.etBindQq.setText("1840094972")
            binding.etBotName.setText("Eridanus")
            binding.tvStatus.text = "\u72b6\u6001: \u914d\u7f6e\u5df2\u6e05\u7a7a"
            Toast.makeText(this, "\u5df2\u6062\u590d\u9ed8\u8ba4\u914d\u7f6e", Toast.LENGTH_SHORT).show()
        }

        // 切换悬浮窗
        binding.btnToggleFloating.setOnClickListener {
            saveCurrentConfig()
            if (configManager.isFloatingRunning) {
                stopFloatingService()
            } else {
                if (!checkOverlayPermission()) {
                    requestOverlayPermission()
                } else {
                    requestMediaProjectionPermission()
                }
            }
        }

        // 打开聊天界面
        binding.btnOpenChat.setOnClickListener {
            saveCurrentConfig()
            try {
                val intent = Intent(this, ChatActivity::class.java)
                startActivity(intent)
            } catch (t: Throwable) {
                Toast.makeText(this, "\u6253\u5f00\u804a\u5929\u5931\u8d25: " + (t.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveCurrentConfig() {
        val url = binding.etServerUrl.text?.toString()?.trim() ?: ""
        val token = binding.etAuthToken.text?.toString()?.trim() ?: ""
        val qqStr = binding.etBindQq.text?.toString()?.trim() ?: ""
        val botNameStr = binding.etBotName.text?.toString()?.trim() ?: ""
        if (url.isNotEmpty()) configManager.serverUrl = url
        configManager.authToken = token
        if (qqStr.isNotEmpty()) configManager.bindQqId = qqStr.toLongOrNull() ?: 1840094972L
        if (botNameStr.isNotEmpty()) configManager.botName = botNameStr
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)
                )
                overlayPermissionLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "\u65e0\u6cd5\u81ea\u52a8\u6253\u5f00\u60ac\u6d6e\u7a97\u6743\u9650\u8bbe\u7f6e\uff0c\u8bf7\u5728\u7cfb\u7edf\u8bbe\u7f6e\u4e2d\u624b\u52a8\u5f00\u542f", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestMediaProjectionPermission() {
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            Toast.makeText(this, "\u65e0\u6cd5\u542f\u52a8\u5f55\u5c4f\u8bf7\u6c42: " + (e.message ?: ""), Toast.LENGTH_SHORT).show()
            startFloatingService(0, null)
        }
    }

    private fun startFloatingService(resultCode: Int, data: Intent?) {
        try {
            val intent = Intent(this, FloatingWindowService::class.java).apply {
                action = FloatingWindowService.ACTION_START
                if (resultCode != 0 && data != null) {
                    putExtra(FloatingWindowService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(FloatingWindowService.EXTRA_RESULT_DATA, data)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            configManager.isFloatingRunning = true
            updateServiceStatus()
            Toast.makeText(this, "\u60ac\u6d6e\u7403\u5df2\u542f\u52a8\uff0c\u53ef\u5207\u6362\u5230\u5176\u4ed6 App \u4f7f\u7528", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        } catch (e: Exception) {
            Toast.makeText(this, "\u542f\u52a8\u60ac\u6d6e\u670d\u52a1\u5931\u8d25: " + (e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun stopFloatingService() {
        try {
            val intent = Intent(this, FloatingWindowService::class.java).apply {
                action = FloatingWindowService.ACTION_STOP
            }
            startService(intent)
            configManager.isFloatingRunning = false
            updateServiceStatus()
            Toast.makeText(this, "\u60ac\u6d6e\u670d\u52a1\u5df2\u505c\u6b62", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "\u505c\u6b62\u60ac\u6d6e\u670d\u52a1\u5931\u8d25: " + (e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateServiceStatus() {
        if (configManager.isFloatingRunning) {
            binding.btnToggleFloating.text = "\u505c\u6b62\u5c4f\u5e55\u60ac\u6d6e\u7403"
            binding.btnToggleFloating.setBackgroundColor(0xFFB00020.toInt())
            binding.tvStatus.text = "\u72b6\u6001: \u60ac\u6d6e\u7403\u8fd0\u884c\u4e2d"
        } else {
            binding.btnToggleFloating.text = "\u5f00\u542f\u5c4f\u5e55\u60ac\u6d6e\u7403"
            binding.btnToggleFloating.setBackgroundColor(getColor(R.color.primary))
            binding.tvStatus.text = "\u72b6\u6001: \u672a\u8fd0\u884c"
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }
}