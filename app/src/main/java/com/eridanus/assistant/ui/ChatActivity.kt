package com.eridanus.assistant.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.eridanus.assistant.data.ConfigManager
import com.eridanus.assistant.databinding.ActivityChatBinding
import com.eridanus.assistant.net.EridanusApiClient
import com.eridanus.assistant.net.EridanusApiClient.HistoryItem
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.random.Random

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var configManager: ConfigManager
    private lateinit var apiClient: EridanusApiClient
    private lateinit var chatAdapter: ChatAdapter

    private var selectedImageBase64: String? = null
    private var selectedImageBitmap: Bitmap? = null

    private var pollJob: Job? = null
    private var lastSyncMsgId: Long = 0L
    private val displayedMsgIds = HashSet<Long>()
    private var isWaitingBotReply: Boolean = false
    private var currentPlaceholderId: Long? = null
    private var pendingUserMsgText: String? = null
    private var pendingUserMsgTime: Long = 0L

    companion object {
        fun splitByMessageDelimiters(text: String): List<String> {
            if (text.isBlank()) return emptyList()
            val rawParts = if (text.contains("||")) {
                text.split("||")
            } else if (text.contains("\n\n")) {
                text.split("\n\n")
            } else {
                listOf(text)
            }
            return rawParts.map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            handlePickedImage(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configManager = ConfigManager(this)
        apiClient = EridanusApiClient(configManager.serverUrl, configManager.authToken)

        setupUI()
        setupListeners()
        loadHistory()
    }

    override fun onResume() {
        super.onResume()
        startPollingForNewMessages()
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
    }

    private fun setupUI() {
        val botName = configManager.botName
        binding.tvChatTitle.text = botName
        binding.switchAtBot.text = "@"
        binding.switchAtBot.isChecked = configManager.atBotDefault

        chatAdapter = ChatAdapter(apiClient, botDisplayName = botName)
        binding.rvChat.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }

        binding.layoutImagePreview.visibility = View.GONE
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnClearChat.setOnClickListener {
            chatAdapter.clearMessages()
            displayedMsgIds.clear()
            chatAdapter.addMessage(
                ChatMessage(
                    text = "\u804a\u5929\u8bb0\u5f55\u5df2\u5728\u672c\u5730\u6e05\u7a7a\uff0c\u65b0\u6d88\u606f\u5c06\u7ee7\u7eed\u5b9e\u65f6\u540c\u6b65~",
                    isFromUser = false
                )
            )
        }

        binding.switchAtBot.setOnCheckedChangeListener { _, isChecked ->
            configManager.atBotDefault = isChecked
        }

        binding.btnPickImage.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.btnRemovePreview.setOnClickListener {
            clearSelectedImage()
        }

        binding.btnSend.setOnClickListener {
            sendMessage()
        }
    }

    private fun handlePickedImage(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream: InputStream? = contentResolver.openInputStream(uri)
                val originalBm = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (originalBm != null) {
                    val maxDimension = 1280
                    val width = originalBm.width
                    val height = originalBm.height
                    val scale = if (width > maxDimension || height > maxDimension) {
                        val rWidth = maxDimension.toFloat() / width
                        val rHeight = maxDimension.toFloat() / height
                        Math.min(rWidth, rHeight)
                    } else 1.0f

                    val scaledBm = if (scale < 1.0f) {
                        Bitmap.createScaledBitmap(
                            originalBm,
                            (width * scale).toInt(),
                            (height * scale).toInt(),
                            true
                        )
                    } else originalBm

                    val bos = ByteArrayOutputStream()
                    scaledBm.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                    val bytes = bos.toByteArray()
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

                    withContext(Dispatchers.Main) {
                        selectedImageBase64 = b64
                        selectedImageBitmap = scaledBm
                        binding.ivPreviewThumb.setImageBitmap(scaledBm)
                        binding.layoutImagePreview.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ChatActivity, "\u56fe\u7247\u5904\u7406\u5931\u8d25: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun clearSelectedImage() {
        selectedImageBase64 = null
        selectedImageBitmap = null
        binding.ivPreviewThumb.setImageDrawable(null)
        binding.layoutImagePreview.visibility = View.GONE
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            try {
                val res = apiClient.getChatHistory(limit = 50)
                if (res.isSuccess) {
                    val history = res.getOrNull() ?: emptyList<HistoryItem>()
                    if (history.isNotEmpty()) {
                        val historyList = mutableListOf<ChatMessage>()
                        for (item in history) {
                            val imgs: List<String> = item.images ?: emptyList<String>()
                            val isUser = item.is_user
                            val rawText = item.text
                            val rawMsgId = item.raw_msg_id ?: item.id
                            if (rawMsgId > lastSyncMsgId) {
                                lastSyncMsgId = rawMsgId
                            }

                            if (isUser) {
                                displayedMsgIds.add(item.id)
                                historyList.add(
                                    ChatMessage(
                                        id = item.id,
                                        text = rawText,
                                        imageUrls = imgs,
                                        isFromUser = true,
                                        timeStr = ""
                                    )
                                )
                            } else {
                                val segments = splitByMessageDelimiters(rawText)
                                if (segments.isEmpty() && imgs.isNotEmpty()) {
                                    displayedMsgIds.add(item.id)
                                    historyList.add(
                                        ChatMessage(
                                            id = item.id,
                                            text = "",
                                            imageUrls = imgs,
                                            isFromUser = false,
                                            timeStr = ""
                                        )
                                    )
                                } else if (segments.size <= 1) {
                                    displayedMsgIds.add(item.id)
                                    historyList.add(
                                        ChatMessage(
                                            id = item.id,
                                            text = segments.firstOrNull() ?: rawText,
                                            imageUrls = imgs,
                                            isFromUser = false,
                                            timeStr = ""
                                        )
                                    )
                                } else {
                                    for (si in segments.indices) {
                                        val segId = item.id + si
                                        displayedMsgIds.add(segId)
                                        val attachImgs: List<String> = if (si == segments.size - 1) imgs else emptyList<String>()
                                        historyList.add(
                                            ChatMessage(
                                                id = segId,
                                                text = segments[si],
                                                imageUrls = attachImgs,
                                                isFromUser = false,
                                                timeStr = ""
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        chatAdapter.setMessages(historyList)
                        binding.rvChat.scrollToPosition(chatAdapter.itemCount - 1)
                        return@launch
                    }
                }
            } catch (_: Exception) {
            }

            if (chatAdapter.itemCount == 0) {
                chatAdapter.addMessage(
                    ChatMessage(
                        text = "\u5df2\u8fde\u63a5\u5230\u52a9\u7406\u3002\u4f60\u53ef\u4ee5\u76f4\u63a5\u53d1\u6587\u5b57\u3001\u4e0a\u4f20\u56fe\u7247\u6216\u53d1\u9001\u753b\u56fe\u6307\u4ee4~",
                        isFromUser = false
                    )
                )
            }
        }
    }

    private fun startPollingForNewMessages() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                delay(1200L)

                try {
                    val res = apiClient.getChatHistory(limit = 20, sinceId = lastSyncMsgId)
                    if (res.isSuccess) {
                        val newItems = res.getOrNull() ?: emptyList<HistoryItem>()
                        if (newItems.isNotEmpty()) {
                            handleIncrementalMessages(newItems)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun handleIncrementalMessages(items: List<HistoryItem>) {
        for (item in items) {
            val rawMsgId = item.raw_msg_id ?: item.id
            if (rawMsgId > lastSyncMsgId) {
                lastSyncMsgId = rawMsgId
            }

            val imgs: List<String> = item.images ?: emptyList<String>()
            val isUser = item.is_user
            val rawText = item.text

            if (isUser) {
                val isRecentUserEcho = pendingUserMsgText != null &&
                        pendingUserMsgText == rawText &&
                        (System.currentTimeMillis() - pendingUserMsgTime < 30000L)

                if (!isRecentUserEcho && !displayedMsgIds.contains(item.id)) {
                    displayedMsgIds.add(item.id)
                    chatAdapter.addMessage(
                        ChatMessage(
                            id = item.id,
                            text = rawText,
                            imageUrls = imgs,
                            isFromUser = true
                        )
                    )
                    binding.rvChat.scrollToPosition(chatAdapter.itemCount - 1)
                } else {
                    displayedMsgIds.add(item.id)
                }
            } else {
                val segments = splitByMessageDelimiters(rawText)
                val pId = currentPlaceholderId
                val isPlaceholderWaiting = (pId != null && chatAdapter.hasMessageWithId(pId))

                if (isPlaceholderWaiting && pId != null) {
                    currentPlaceholderId = null
                    isWaitingBotReply = false
                    binding.btnSend.isEnabled = true
                    displayedMsgIds.add(item.id)

                    if (segments.isEmpty() && imgs.isNotEmpty()) {
                        chatAdapter.updateMessageById(pId, newText = "", images = imgs, newId = item.id)
                    } else if (segments.size <= 1) {
                        val firstTxt = segments.firstOrNull() ?: rawText
                        chatAdapter.updateMessageById(pId, newText = firstTxt, images = imgs, newId = item.id)
                    } else {
                        chatAdapter.updateMessageById(pId, newText = segments[0], images = emptyList(), newId = item.id)
                        for (si in 1 until segments.size) {
                            val segId = item.id + si
                            displayedMsgIds.add(segId)
                            val attachImgs: List<String> = if (si == segments.size - 1) imgs else emptyList<String>()
                            val typingDelay = (segments[si].length * 30L).coerceIn(350L, 1200L) + Random.nextLong(100, 300)
                            delay(typingDelay)
                            chatAdapter.addMessage(
                                ChatMessage(
                                    id = segId,
                                    text = segments[si],
                                    imageUrls = attachImgs,
                                    isFromUser = false
                                )
                            )
                            binding.rvChat.scrollToPosition(chatAdapter.itemCount - 1)
                        }
                    }
                    binding.rvChat.scrollToPosition(chatAdapter.itemCount - 1)
                } else {
                    if (segments.isEmpty() && imgs.isNotEmpty()) {
                        if (!displayedMsgIds.contains(item.id)) {
                            displayedMsgIds.add(item.id)
                            chatAdapter.addMessage(
                                ChatMessage(
                                    id = item.id,
                                    text = "",
                                    imageUrls = imgs,
                                    isFromUser = false
                                )
                            )
                            binding.rvChat.scrollToPosition(chatAdapter.itemCount - 1)
                        }
                    } else if (segments.size <= 1) {
                        val segText = segments.firstOrNull() ?: rawText
                        if (!displayedMsgIds.contains(item.id)) {
                            displayedMsgIds.add(item.id)
                            chatAdapter.addMessage(
                                ChatMessage(
                                    id = item.id,
                                    text = segText,
                                    imageUrls = imgs,
                                    isFromUser = false
                                )
                            )
                            binding.rvChat.scrollToPosition(chatAdapter.itemCount - 1)
                        }
                    } else {
                        for (si in segments.indices) {
                            val segId = item.id + si
                            if (!displayedMsgIds.contains(segId)) {
                                displayedMsgIds.add(segId)
                                val attachImgs: List<String> = if (si == segments.size - 1) imgs else emptyList<String>()
                                chatAdapter.addMessage(
                                    ChatMessage(
                                        id = segId,
                                        text = segments[si],
                                        imageUrls = attachImgs,
                                        isFromUser = false
                                    )
                                )
                                binding.rvChat.scrollToPosition(chatAdapter.itemCount - 1)
                                if (si < segments.size - 1) {
                                    val typingDelay = (segments[si].length * 30L).coerceIn(350L, 1200L) + Random.nextLong(100, 300)
                                    delay(typingDelay)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        val imageB64 = selectedImageBase64

        if (text.isEmpty() && imageB64.isNullOrBlank()) {
            Toast.makeText(this, "\u8bf7\u8f93\u5165\u5185\u5889\u6216\u9009\u62e9\u56fe\u7247", Toast.LENGTH_SHORT).show()
            return
        }

        val atBot = binding.switchAtBot.isChecked

        // 1. Add user message
        val userTempId = System.currentTimeMillis()
        displayedMsgIds.add(userTempId)
        pendingUserMsgText = text
        pendingUserMsgTime = userTempId

        val userMsg = ChatMessage(
            id = userTempId,
            text = text,
            localImageBitmap = imageB64,
            isFromUser = true
        )
        chatAdapter.addMessage(userMsg)
        binding.etMessage.setText("")
        clearSelectedImage()
        binding.rvChat.scrollToPosition(chatAdapter.itemCount - 1)

        // 2. Add bot placeholder
        val placeholderId = userTempId + 1
        currentPlaceholderId = placeholderId
        val botPlaceholder = ChatMessage(id = placeholderId, text = "\u601d\u8003\u4e2d...", isFromUser = false)
        chatAdapter.addMessage(botPlaceholder)
        binding.rvChat.scrollToPosition(chatAdapter.itemCount - 1)
        binding.btnSend.isEnabled = false
        isWaitingBotReply = true

        lifecycleScope.launch {
            try {
                val res = apiClient.askAssistant(
                    prompt = text,
                    imageBase64 = imageB64,
                    bindQqId = configManager.bindQqId,
                    atBot = atBot
                )

                val pId = currentPlaceholderId
                if (res.isSuccess) {
                    val askResp = res.getOrNull()
                    val reply = askResp?.reply ?: askResp?.message ?: ""
                    val images: List<String> = askResp?.images ?: emptyList<String>()
                    val serverMsgId = askResp?.msg_id

                    if (pId != null && chatAdapter.hasMessageWithId(pId)) {
                        currentPlaceholderId = null
                        isWaitingBotReply = false
                        val segments = splitByMessageDelimiters(reply)

                        if (segments.isEmpty() && images.isEmpty()) {
                            chatAdapter.updateMessageById(pId, "\uff08\u65e0\u56de\u590d\u5185\u5889\uff09")
                        } else if (segments.isEmpty() && images.isNotEmpty()) {
                            chatAdapter.updateMessageById(pId, newText = "", images = images, newId = serverMsgId)
                            if (serverMsgId != null && serverMsgId > 0) {
                                displayedMsgIds.add(serverMsgId)
                            }
                        } else if (segments.size <= 1) {
                            val singleText = segments.firstOrNull() ?: reply
                            chatAdapter.updateMessageById(pId, newText = singleText, images = images, newId = serverMsgId)
                            if (serverMsgId != null && serverMsgId > 0) {
                                displayedMsgIds.add(serverMsgId)
                            }
                        } else {
                            chatAdapter.updateMessageById(pId, newText = segments[0], images = emptyList(), newId = serverMsgId)
                            if (serverMsgId != null && serverMsgId > 0) {
                                displayedMsgIds.add(serverMsgId)
                            }

                            for (i in 1 until segments.size) {
                                val seg = segments[i]
                                val attachImgs = if (i == segments.size - 1) images else emptyList()

                                val typingDelay = (seg.length * 35L).coerceIn(400L, 1600L) + Random.nextLong(150, 400)
                                delay(typingDelay)

                                val segId = System.currentTimeMillis() + i
                                displayedMsgIds.add(segId)
                                chatAdapter.addMessage(ChatMessage(id = segId, text = seg, imageUrls = attachImgs, isFromUser = false))
                                binding.rvChat.scrollToPosition(chatAdapter.itemCount - 1)
                            }
                        }
                    } else {
                        // Placeholder was already consumed by the background poller!
                        // If askResp contains images and none of them were rendered, append them.
                        if (images.isNotEmpty()) {
                            val alreadyHasImage = chatAdapter.getMessages().any { m ->
                                !m.isFromUser && m.imageUrls.any { img -> images.contains(img) }
                            }
                            if (!alreadyHasImage) {
                                val imgMsgId = serverMsgId ?: System.currentTimeMillis()
                                if (!displayedMsgIds.contains(imgMsgId)) {
                                    displayedMsgIds.add(imgMsgId)
                                    chatAdapter.addMessage(ChatMessage(id = imgMsgId, text = "", imageUrls = images, isFromUser = false))
                                    binding.rvChat.scrollToPosition(chatAdapter.itemCount - 1)
                                }
                            }
                        }
                    }
                } else {
                    if (pId != null && chatAdapter.hasMessageWithId(pId)) {
                        currentPlaceholderId = null
                        isWaitingBotReply = false
                        chatAdapter.updateMessageById(pId, "\uff08\u7f51\u7edc\u8bf7\u6c42\u5f02\u5e38\u6216\u8d85\u65f6\uff0c\u8bf7\u91cd\u8bd5\uff09")
                    }
                }
            } catch (e: Exception) {
                val pId = currentPlaceholderId
                if (pId != null && chatAdapter.hasMessageWithId(pId)) {
                    currentPlaceholderId = null
                    isWaitingBotReply = false
                    chatAdapter.updateMessageById(pId, "\uff08\u8bf7\u6c42\u53d1\u9001\u5931\u8d25: ${e.message}\uff09")
                }
            } finally {
                isWaitingBotReply = false
                binding.btnSend.isEnabled = true
                binding.rvChat.scrollToPosition(chatAdapter.itemCount - 1)
            }
        }
    }
}