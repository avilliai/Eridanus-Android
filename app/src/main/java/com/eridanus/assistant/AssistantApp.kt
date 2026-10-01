package com.eridanus.assistant

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

class AssistantApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("CrashHandler", "Crash detected: " + throwable.message, throwable)
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(
                        applicationContext,
                        "发生异常: " + (throwable.message ?: throwable.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {}
            }
            try {
                Thread.sleep(2000)
            } catch (e: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}