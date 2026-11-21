package com.example.messaging.adapters

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.messaging.model.AppPlatform
import com.example.messaging.model.ComposeResult
import com.example.messaging.model.MessagingCapability

abstract class BaseMessagingAdapter(val platform: AppPlatform) {

    abstract fun getCapability(context: Context): MessagingCapability

    abstract fun composeMessage(
        context: Context,
        recipientName: String,
        phoneNumber: String?,
        message: String
    ): ComposeResult

    protected fun resolveInstalledPackage(context: Context, packages: List<String>): String? {
        val pm = context.packageManager
        for (pkg in packages) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: PackageManager.NameNotFoundException) {}
        }
        return null
    }

    open fun isInstalled(context: Context): Boolean {
        val packagesToCheck = listOf(platform.primaryPackage) + platform.alternatePackages
        return resolveInstalledPackage(context, packagesToCheck) != null
    }

    protected fun launchIntentSafely(context: Context, intent: Intent): Boolean {
        val action = intent.action
        val data = intent.dataString ?: "(no uri data)"
        val pkg = intent.getPackage() ?: intent.component?.packageName ?: "(no package)"
        android.util.Log.i("RAFEL_MESSAGE_TRACE", "Stage 6: Intent action=$action, URI=$data, targetPackage=$pkg")

        val pm = context.packageManager
        val resolved = intent.resolveActivity(pm) != null
        android.util.Log.i("RAFEL_MESSAGE_TRACE", "Stage 7: PackageManager resolves intent = $resolved")

        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            android.util.Log.i("RAFEL_MESSAGE_TRACE", "Stage 7 success: startActivity() succeeded for package $pkg")
            true
        } catch (e: Exception) {
            android.util.Log.e("RAFEL_MESSAGE_TRACE", "Stage 7 failed: startActivity() threw exception: ${e.message}", e)
            false
        }
    }
}
