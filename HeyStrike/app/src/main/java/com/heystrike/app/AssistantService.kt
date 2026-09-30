package com.heystrike.app

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * System assistant-role path: when the user picks Hey Strike as the default
 * digital assistant, Android itself keeps this service alive for background
 * hotwording, headset long-press, and lockscreen invocation — same privilege
 * class as Siri / Google Assistant.
 *
 * Two services (framework requirement): the interaction service (identity)
 * plus a session service that mints VoiceInteractionSessions.
 */
class AssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
    }
}

class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle): VoiceInteractionSession =
        AssistantSession(this)
}

class AssistantSession(ctx: android.content.Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Siri orb over whatever is on screen (incl. lockscreen); the gate
        // in VoiceService keeps answering follow-ups without re-wake.
        OverlayService.show(context)
    }

    override fun onHide() {
        super.onHide()
        context.startService(Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_HIDE
        })
    }
}
