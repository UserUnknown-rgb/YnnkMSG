package com.Ynnk.YnnkMsg

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.os.Bundle
import android.util.Log
import android.content.Context
import com.Ynnk.YnnkMsg.service.EmailCheckService
import com.Ynnk.YnnkMsg.service.EmailCheckWorker
import com.Ynnk.YnnkMsg.util.SecurePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class YnnkMsgApplication : Application(), Application.ActivityLifecycleCallbacks {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activityCount = 0

    companion object {
        private const val TAG = "YnnkMsgApplication"
        var isAppInForeground: Boolean = false
            private set
            
        var activeChatEmail: String? = null
    }

    override fun onCreate() {
        super.onCreate()
        
        // Initialize BouncyCastle for PGP. 
        appScope.launch {
            try {
                val bc = BouncyCastleProvider()
                Security.removeProvider("BC")
                Security.insertProviderAt(bc, 1)
                Log.i(TAG, "BouncyCastle provider initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize BouncyCastle", e)
            }
        }

        registerActivityLifecycleCallbacks(this)
        
        appScope.launch {
            try {
                SecurePrefs.initInBackground(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize SecurePrefs", e)
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    
    override fun onActivityStarted(activity: Activity) {
        activityCount++
        if (activityCount == 1) {
            isAppInForeground = true
            
            // Move background tasks to IO thread to prevent UI freezing
            appScope.launch {
                EmailCheckWorker.cancelBackgroundPolling(applicationContext)
                EmailCheckService.startIntensive(applicationContext)

                val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancelAll()
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    
    override fun onActivityStopped(activity: Activity) {
        activityCount--
        if (activityCount == 0) {
            isAppInForeground = false
            
            appScope.launch {
                EmailCheckService.stopService(applicationContext)
                EmailCheckWorker.scheduleBackgroundPolling(applicationContext)
            }
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
