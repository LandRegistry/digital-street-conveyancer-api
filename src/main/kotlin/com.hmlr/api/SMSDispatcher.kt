package com.hmlr.api

import khttp.post
import khttp.structures.authorization.BasicAuthorization
import net.corda.core.utilities.loggerFor
import org.slf4j.Logger

enum class SMSTemplate(val templateText: String) {
    YOTI_SIGN_REQUEST("Hi %1.\nPlease verify your identity using Yoti. You'll need to do this before we can proceed with the sale.\nContinue at %0"),
    AGREEMENT_SIGN_REQUEST_SELLER("Good news %1!\nYour sales and transfer agreements are ready to sign.\nContinue at %0"),
    AGREEMENT_SIGN_REQUEST_BUYER("Good news %1!\nYour sales, mortgage and transfer agreements are ready to sign.\nContinue at %0"),
    TITLE_TRANSFERRED("Hi %1. It's completion day!\nYour transfer of ownership is complete.\nYou can view confirmation of this at %0"),
}

object SMSDispatcher {

    private val logger: Logger = loggerFor<SMSDispatcher>()

    fun sendSMSYotiSignRequest(recipientPhoneNumber: String, recipientName: String, url: String): Boolean {
        return sendSMS(recipientPhoneNumber, SMSTemplate.YOTI_SIGN_REQUEST, url, recipientName)
    }

    fun sendSMSAgreementSignRequestSeller(recipientPhoneNumber: String, recipientName: String, titleNumber: String): Boolean {
        val url = System.getenv("UI_URL_AGREEMENT_SIGN")
                .replace("%titleNumber%", titleNumber)
        return sendSMS(recipientPhoneNumber, SMSTemplate.AGREEMENT_SIGN_REQUEST_SELLER, url, recipientName)
    }

    fun sendSMSAgreementSignRequestBuyer(recipientPhoneNumber: String, recipientName: String, titleNumber: String): Boolean {
        val url = System.getenv("UI_URL_AGREEMENT_SIGN")
                .replace("%titleNumber%", titleNumber)
        return sendSMS(recipientPhoneNumber, SMSTemplate.AGREEMENT_SIGN_REQUEST_BUYER, url, recipientName)
    }

    fun sendSMSTitleTransferred(recipientPhoneNumber: String, recipientName: String, titleNumber: String): Boolean {
        val url = System.getenv("UI_URL_TITLE_TRANSFERRED")
                .replace("%titleNumber%", titleNumber)
        return sendSMS(recipientPhoneNumber, SMSTemplate.TITLE_TRANSFERRED, url, recipientName)
    }

    fun sendSMS(recipientPhoneNumber: String, template: SMSTemplate, vararg templateInfills: String): Boolean {
        if (!"^\\+\\d{1,15}\$".toRegex().matches(recipientPhoneNumber)) {
            return logErrorAndFalse("Phone number '$recipientPhoneNumber' is not in international E.164 format! Not sending SMS.")
        }

        val templateText = template.templateText.let {
            val isTrialVar = System.getenv("TWILIO_IS_TRIAL") ?: return@let it
            when (isTrialVar.toLowerCase()) {
                "1", "true", "yes", "y" -> "\n$it"
                else -> it
            }
        }

        val body = templateText.replace("%([0-9])".toRegex(RegexOption.MULTILINE)) { match ->
            val index = match.groupValues[1].toInt()
            templateInfills.getOrElse(index) { match.value }
        }

        val ofcomMediaNumberPrefixes = listOf(
                "+441134960", "+441144960", "+441154960", "+441164960",
                "+441174960", "+441184960", "+441214960", "+441314960",
                "+441414960", "+441514960", "+441614960", "+442079460",
                "+441914980", "+442896496", "+442920180", "+441632960",
                "+447700900", "+448081570", "+449098790", "+443069990"
        )
        if (ofcomMediaNumberPrefixes.any { recipientPhoneNumber.startsWith(it) }) {
            return logErrorAndFalse("Phone number '$recipientPhoneNumber' is an Ofcom number for media! Not sending SMS. Would have sent \"$body\" to '$recipientPhoneNumber'.")
        }

        val authUser = System.getenv("TWILIO_ACCOUNT_SID") ?: return logErrorAndFalse("TWILIO_ACCOUNT_SID env var is not set. Not sending SMS. Would have sent \"$body\" to '$recipientPhoneNumber'.")
        val authPass = System.getenv("TWILIO_AUTH_TOKEN") ?: return logErrorAndFalse("TWILIO_AUTH_TOKEN env var is not set. Not sending SMS. Would have sent \"$body\" to '$recipientPhoneNumber'.")
        val twilioNumber = System.getenv("TWILIO_PHONE_NUMBER") ?: return logErrorAndFalse("TWILIO_PHONE_NUMBER env var is not set. Not sending SMS. Would have sent \"$body\" to '$recipientPhoneNumber'.")

        val url = "https://api.twilio.com/2010-04-01/Accounts/$authUser/Messages.json"
        val data = mapOf(
                "From" to twilioNumber,
                "To" to recipientPhoneNumber,
                "Body" to body
        )
        val response = post(
                url = url,
                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                data = data,
                auth = BasicAuthorization(authUser, authPass),
                timeout = 10.0
        )

        if (response.statusCode != 201) {
            return if (response.statusCode == 400) {
                val errorCode = response.jsonObject.getInt("code")
                if (errorCode == 21608) {
                    logErrorAndFalse("Phone number '$recipientPhoneNumber' is not a Twilio verified number! SMS not sent.")
                } else {
                    logErrorAndFalse("Twilio API returned error code $errorCode! SMS not sent.")
                }
            } else {
                logErrorAndFalse("Twilio API returned ${response.statusCode}:\n${response.text}")
            }
        }

        return try {
            val smsSID = response.jsonObject.getString("sid")
            val segments = response.jsonObject.getString("num_segments")
            val status = response.jsonObject.getString("status")
            logger.info("SMS ($smsSID) sent to '$recipientPhoneNumber' in $segments segments. Response: $status.")
            true
        } catch (e: org.json.JSONException) {
            logErrorAndFalse("Unknown if SMS was sent to '$recipientPhoneNumber'. Cannot read response from API:\n${response.text}")
        }
    }

    private fun logErrorAndFalse(msg: String): Boolean {
        logger.error("TWILIO: $msg")
        return false
    }

}