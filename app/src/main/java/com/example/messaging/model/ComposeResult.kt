package com.example.messaging.model

sealed class ComposeResult {
    data class DraftPrepared(
        val platform: AppPlatform,
        val recipientName: String,
        val messageText: String,
        val targetPackage: String,
        val explanation: String
    ) : ComposeResult()

    data class AppOpenedWithLimitation(
        val platform: AppPlatform,
        val recipientName: String,
        val explanation: String
    ) : ComposeResult()

    data class NotInstalled(
        val platform: AppPlatform,
        val requiredPackage: String
    ) : ComposeResult()

    data class AmbiguousRecipient(
        val platform: AppPlatform,
        val candidateContacts: List<Pair<String, String>>,
        val promptText: String
    ) : ComposeResult()

    data class PermissionDenied(
        val platform: AppPlatform,
        val requiredPermission: String,
        val message: String
    ) : ComposeResult()

    data class Error(
        val platform: AppPlatform,
        val errorMessage: String
    ) : ComposeResult()
}
