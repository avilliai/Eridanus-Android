package com.eridanus.assistant.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.util.Base64
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.eridanus.assistant.R
import com.eridanus.assistant.net.EridanusApiClient
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class ChatImageItem(
    val url: String? = null,
    val base64: String? = null
)

class ChatAdapter(
    private val apiClient: EridanusApiClient,
    private val messages: MutableList<ChatMessage> = mutableListOf(),
    var botDisplayName: String = "Eridanus"
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    companion object {
        // Memory cache limit: up to 1/3 of max heap (or 128MB max)
        private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        private val cacheSize = (maxMemory / 3).coerceIn(32 * 1024, 128 * 1024)
        private val imageMemoryCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }

        fun md5Hex(str: String): String {
            return try {
                val digest = MessageDigest.getInstance("MD5")
                val bytes = digest.digest(str.toByteArray(Charsets.UTF_8))
                bytes.joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
                str.hashCode().toString()
            }
        }

        fun getDiskCacheFile(context: Context, rawUrl: String): File {
            val cacheDir = File(context.cacheDir, "chat_image_cache").apply { if (!exists()) mkdirs() }
            val filename = md5Hex(rawUrl) + ".img"
            return File(cacheDir, filename)
        }

        fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            val (height: Int, width: Int) = options.outHeight to options.outWidth
            var inSampleSize = 1

            if (height > reqHeight || width > reqWidth) {
                val halfHeight: Int = height / 2
                val halfWidth: Int = width / 2

                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize.coerceAtLeast(1)
        }
    }

    var onImageClickListener: ((item: ChatImageItem) -> Unit)? = null

    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val placeholderDrawable = ColorDrawable(0x12000000)

    class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val layoutBotMsg: LinearLayout = itemView.findViewById(R.id.layoutBotMsg)
        val tvBotName: TextView = itemView.findViewById(R.id.tvBotName)
        val tvBotText: TextView = itemView.findViewById(R.id.tvBotText)
        val ivBotImage: ImageView = itemView.findViewById(R.id.ivBotImage)

        val layoutUserMsg: LinearLayout = itemView.findViewById(R.id.layoutUserMsg)
        val tvUserText: TextView = itemView.findViewById(R.id.tvUserText)
        val ivUserImage: ImageView = itemView.findViewById(R.id.ivUserImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val msg = messages[position]
        if (msg.isFromUser) {
            holder.layoutUserMsg.visibility = View.VISIBLE
            holder.layoutBotMsg.visibility = View.GONE

            if (msg.text.isNotBlank()) {
                holder.tvUserText.visibility = View.VISIBLE
                holder.tvUserText.text = msg.text
            } else {
                holder.tvUserText.visibility = View.GONE
            }

            if (!msg.localImageBitmap.isNullOrBlank()) {
                holder.ivUserImage.visibility = View.VISIBLE
                try {
                    val bytes = Base64.decode(msg.localImageBitmap, Base64.NO_WRAP)
                    val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    holder.ivUserImage.setImageBitmap(bm)
                } catch (_: Exception) {
                    holder.ivUserImage.visibility = View.GONE
                }
                holder.ivUserImage.setOnClickListener {
                    onImageClickListener?.invoke(ChatImageItem(base64 = msg.localImageBitmap))
                }
            } else if (msg.imageUrls.isNotEmpty()) {
                val firstImg = msg.imageUrls.first()
                holder.ivUserImage.visibility = View.VISIBLE
                bindRemoteImage(holder.itemView.context, firstImg, holder.ivUserImage)
                holder.ivUserImage.setOnClickListener {
                    onImageClickListener?.invoke(ChatImageItem(url = firstImg))
                }
            } else {
                holder.ivUserImage.visibility = View.GONE
                holder.ivUserImage.setOnClickListener(null)
            }
        } else {
            holder.layoutUserMsg.visibility = View.GONE
            holder.layoutBotMsg.visibility = View.VISIBLE
            holder.tvBotName.text = botDisplayName

            if (msg.text.isNotBlank()) {
                holder.tvBotText.visibility = View.VISIBLE
                holder.tvBotText.text = msg.text
            } else {
                holder.tvBotText.visibility = View.GONE
            }

            val firstImg = msg.imageUrls.firstOrNull()
            if (!firstImg.isNullOrBlank()) {
                holder.ivBotImage.visibility = View.VISIBLE
                bindRemoteImage(holder.itemView.context, firstImg, holder.ivBotImage)
                holder.ivBotImage.setOnClickListener {
                    onImageClickListener?.invoke(ChatImageItem(url = firstImg))
                }
            } else {
                holder.ivBotImage.visibility = View.GONE
                holder.ivBotImage.setOnClickListener(null)
            }
        }
    }

    private fun bindRemoteImage(context: Context, rawUrl: String, iv: ImageView) {
        val prevTag = iv.tag as? String
        iv.tag = rawUrl

        // Check memory cache first
        val cachedBitmap = imageMemoryCache.get(rawUrl)
        if (cachedBitmap != null) {
            iv.setImageBitmap(cachedBitmap)
            iv.visibility = View.VISIBLE
            return
        }

        if (prevTag != rawUrl) {
            iv.setImageDrawable(placeholderDrawable)
        }
        iv.visibility = View.VISIBLE

        adapterScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                loadBitmapFromDiskOrNetwork(context, rawUrl)
            }

            if (iv.tag == rawUrl) {
                if (bitmap != null) {
                    imageMemoryCache.put(rawUrl, bitmap)
                    iv.setImageBitmap(bitmap)
                    iv.visibility = View.VISIBLE
                } else {
                    if (prevTag != rawUrl) {
                        iv.setImageDrawable(null)
                        iv.visibility = View.GONE
                    }
                }
            }
        }
    }

    private suspend fun loadBitmapFromDiskOrNetwork(context: Context, rawUrl: String): Bitmap? {
        val diskFile = getDiskCacheFile(context, rawUrl)

        val targetWidth = 600
        val targetHeight = 800

        // 1. Disk cache check
        if (diskFile.exists() && diskFile.length() > 0) {
            try {
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(diskFile.absolutePath, boundsOpts)

                val sampleOpts = BitmapFactory.Options().apply {
                    inSampleSize = calculateInSampleSize(boundsOpts, targetWidth, targetHeight)
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bm = BitmapFactory.decodeFile(diskFile.absolutePath, sampleOpts)
                if (bm != null) return bm
            } catch (_: Exception) {
            }
        }

        // 2. Network download
        val bytes = apiClient.downloadImageBytes(rawUrl) ?: return null
        if (bytes.isEmpty()) return null

        try {
            FileOutputStream(diskFile).use { fos ->
                fos.write(bytes)
                fos.flush()
            }
        } catch (_: Exception) {
        }

        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)

            val sampleOpts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(boundsOpts, targetWidth, targetHeight)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sampleOpts)
        } catch (_: Exception) {
            null
        }
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun setMessages(newMessages: List<ChatMessage>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    fun clearMessages() {
        messages.clear()
        notifyDataSetChanged()
    }

    fun hasMessageWithId(id: Long): Boolean {
        return messages.any { it.id == id }
    }

    fun getMessageById(id: Long): ChatMessage? {
        return messages.firstOrNull { it.id == id }
    }

    fun updateMessageById(id: Long, newText: String, images: List<String> = emptyList(), newId: Long? = null): Boolean {
        val idx = messages.indexOfFirst { it.id == id }
        if (idx != -1) {
            val current = messages[idx]
            messages[idx] = current.copy(
                id = newId ?: current.id,
                text = newText,
                imageUrls = images
            )
            notifyItemChanged(idx)
            return true
        }
        return false
    }

    fun removeMessageById(id: Long): Boolean {
        val idx = messages.indexOfFirst { it.id == id }
        if (idx != -1) {
            messages.removeAt(idx)
            notifyItemRemoved(idx)
            return true
        }
        return false
    }

    fun updateLastMessage(newText: String, images: List<String> = emptyList()) {
        if (messages.isNotEmpty()) {
            val lastIdx = messages.size - 1
            messages[lastIdx] = messages[lastIdx].copy(text = newText, imageUrls = images)
            notifyItemChanged(lastIdx)
        }
    }

    fun getMessages(): List<ChatMessage> = messages.toList()

    fun getLastMessage(): ChatMessage? = messages.lastOrNull()
}
