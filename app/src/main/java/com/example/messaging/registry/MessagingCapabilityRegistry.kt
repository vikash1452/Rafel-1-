package com.example.messaging.registry

import android.content.Context
import com.example.messaging.adapters.BaseMessagingAdapter
import com.example.messaging.adapters.GenericMessagingAdapter
import com.example.messaging.adapters.InstagramMessagingAdapter
import com.example.messaging.adapters.MessengerAdapter
import com.example.messaging.adapters.SignalMessagingAdapter
import com.example.messaging.adapters.SmsMessagingAdapter
import com.example.messaging.adapters.TelegramMessagingAdapter
import com.example.messaging.adapters.WhatsAppMessagingAdapter
import com.example.messaging.model.AppPlatform
import com.example.messaging.model.MessagingCapability

class MessagingCapabilityRegistry private constructor() {

    private val adapters = mutableMapOf<AppPlatform, BaseMessagingAdapter>()

    init {
        registerAdapter(WhatsAppMessagingAdapter(isBusiness = false))
        registerAdapter(WhatsAppMessagingAdapter(isBusiness = true))
        registerAdapter(InstagramMessagingAdapter())
        registerAdapter(MessengerAdapter())
        registerAdapter(TelegramMessagingAdapter())
        registerAdapter(SmsMessagingAdapter())
        registerAdapter(SignalMessagingAdapter())
    }

    fun registerAdapter(adapter: BaseMessagingAdapter) {
        adapters[adapter.platform] = adapter
    }

    fun getAdapter(platform: AppPlatform): BaseMessagingAdapter {
        return adapters[platform] ?: GenericMessagingAdapter()
    }

    fun findAdapterByName(appName: String): BaseMessagingAdapter {
        val platform = AppPlatform.fromUserQuery(appName)
        if (platform != null) {
            return getAdapter(platform)
        }
        return GenericMessagingAdapter(appName.trim())
    }

    fun getAllCapabilities(context: Context): List<MessagingCapability> {
        return adapters.values.map { it.getCapability(context) }
    }

    fun getInstalledPlatforms(context: Context): List<AppPlatform> {
        return adapters.values
            .filter { it.isInstalled(context) }
            .map { it.platform }
    }

    companion object {
        @Volatile
        private var instance: MessagingCapabilityRegistry? = null

        fun getInstance(): MessagingCapabilityRegistry {
            return instance ?: synchronized(this) {
                instance ?: MessagingCapabilityRegistry().also { instance = it }
            }
        }
    }
}
