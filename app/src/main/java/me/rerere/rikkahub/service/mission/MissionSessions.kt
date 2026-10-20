package me.rerere.rikkahub.service.mission

import java.util.concurrent.ConcurrentHashMap

/**
 * Batch A — in-memory conversation ↔ mission registry (process-scoped, not persisted).
 *
 * Why it exists: a mission's execution conversation is the coordinate tools and the
 * supervisor work with — incoming tool calls and yield checks need to answer "is this
 * conversation part of a mission, and which one?" without a DB round trip.
 * Registration must happen BEFORE generation is dispatched: [MissionLauncher] binds a
 * known execution conversation, and `ChatServiceRunService.submit` binds the id of a
 * freshly created one (a mission's first round) before calling `sendMessage` — the round's
 * tool assembly reads it back synchronously and attaches `mission_report` only on a hit.
 * The supervisor reads [missionIdFor] / [conversationIdFor] the same way.
 *
 * Consistency: [register] keeps both directions in sync — re-pointing either side first
 * drops the stale pointer on the other side, so `byConversation[c] == m ⟺
 * byMission[m] == c` always holds once a write has committed. Writers are serialised
 * ([Synchronized]); reads are lock-free and return whatever was last committed — callers
 * treat both lookups as hints, and a miss is never an error.
 *
 * Process restart loses the registry;
 * [me.rerere.rikkahub.data.mission.MissionRepository.findActiveByConversation] remains
 * the persisted fallback.
 */
object MissionSessions {

    private val byConversation = ConcurrentHashMap<String, String>()
    private val byMission = ConcurrentHashMap<String, String>()

    /** Register (or re-point) the mapping between [conversationId] and [missionId]. */
    @Synchronized
    fun register(conversationId: String, missionId: String) {
        // Drop stale pointers in both directions first, so re-registering either side can
        // never leave a half-updated pair behind.
        byConversation.remove(conversationId)?.let { byMission.remove(it) }
        byMission.remove(missionId)?.let { byConversation.remove(it) }
        byConversation[conversationId] = missionId
        byMission[missionId] = conversationId
    }

    /** Remove [conversationId]'s mapping (both directions). No-op when unknown. */
    @Synchronized
    fun unregister(conversationId: String) {
        val missionId = byConversation.remove(conversationId) ?: return
        byMission.remove(missionId)
    }

    /** The mission owning [conversationId], or null when the conversation is unregistered. */
    fun missionIdFor(conversationId: String): String? = byConversation[conversationId]

    /** The execution conversation of [missionId], or null when unknown. */
    fun conversationIdFor(missionId: String): String? = byMission[missionId]
}
