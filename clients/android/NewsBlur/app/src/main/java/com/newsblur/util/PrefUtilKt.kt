package com.newsblur.util

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.newsblur.database.BlurDatabaseHelper
import com.newsblur.network.APIConstants
import com.newsblur.service.NBSyncService
import com.newsblur.service.NBSyncService.Companion.pendingInfo
import com.newsblur.service.NBSyncService.Companion.speedInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

object PrefsUtilsKt {

    suspend fun createFeedbackLink(context: Context, dbHelper: BlurDatabaseHelper): String {
        val s = StringBuilder(AppConstants.FEEDBACK_URL)
        s.append("<give us some feedback!>%0A%0A%0A")
        val info = getDebugInfo(context, dbHelper)
        s.append(info.replace("\n", "%0A"))
        return s.toString()
    }

    private suspend fun getDebugInfo(context: Context, dbHelper: BlurDatabaseHelper): String {
        val s = StringBuilder()
        s.append("app version: ").append(PrefsUtils.getVersion(context))
        s.append("\n")
        s.append("android version: ").append(Build.VERSION.RELEASE).append(" (").append(Build.DISPLAY).append(")")
        s.append("\n")
        s.append("device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append(" (").append(Build.BOARD).append(")")
        s.append("\n")
        s.append("sqlite version: ").append(dbHelper.getEngineVersion())
        s.append("\n")
        s.append("username: ").append(PrefsUtils.getUserDetails(context).username)
        s.append("\n")
        s.append("server: ").append(if (APIConstants.isCustomServer()) "custom" else "default")
        s.append("\n")
        s.append("speed: ").append(speedInfo)
        s.append("\n")
        s.append("pending actions: ").append(pendingInfo)
        s.append("\n")
        s.append("premium: ")
        if (NBSyncService.isPremium == true) {
            s.append("yes")
        } else if (NBSyncService.isPremium == false) {
            s.append("no")
        } else {
            s.append("unknown")
        }
        s.append("\n")
        s.append("prefetch: ").append(if (PrefsUtils.isOfflineEnabled(context)) "yes" else "no")
        s.append("\n")
        s.append("notifications: ").append(if (PrefsUtils.isEnableNotifications(context)) "yes" else "no")
        s.append("\n")
        s.append("keepread: ").append(if (PrefsUtils.isKeepOldStories(context)) "yes" else "no")
        s.append("\n")
        s.append("thumbs: ").append(if (PrefsUtils.isShowThumbnails(context)) "yes" else "no")
        s.append("\n")
        return s.toString()
    }

    suspend fun sendLogEmail(context: Context, dbHelper: BlurDatabaseHelper) = coroutineScope {
        val f = Log.getLogfile() ?: return@coroutineScope
        val debugInfo = """
            Tell us a bit about your problem:
            
            
            
            ${getDebugInfo(context, dbHelper)}
            """.trimIndent()
        withContext(Dispatchers.Main) {
            val localPath = FileProvider.getUriForFile(context, "com.newsblur.fileprovider", f)
            val i = Intent(Intent.ACTION_SEND)
            i.setType("*/*")
            i.putExtra(Intent.EXTRA_EMAIL, arrayOf("android@newsblur.com"))
            i.putExtra(Intent.EXTRA_SUBJECT, "Android logs (" + PrefsUtils.getUserDetails(context).username + ")")
            i.putExtra(Intent.EXTRA_TEXT, debugInfo)
            i.putExtra(Intent.EXTRA_STREAM, localPath)
            if (i.resolveActivity(context.packageManager) != null) {
                context.startActivity(i)
            }
        }
    }
}