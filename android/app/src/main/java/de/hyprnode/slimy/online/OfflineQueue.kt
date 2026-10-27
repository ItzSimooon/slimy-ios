package de.hyprnode.slimy.online

import de.hyprnode.slimy.model.timeIntervalSince
import kotlinx.coroutines.CancellationException
import java.time.Instant

/**
 * The rules of the offline queue, apart from the store so they can be tested
 * on their own: what goes when the queue is full or old, and how a flush
 * treats each answer. Every queued write keeps its idempotency key
 * ([PendingAction.key]), so sending it again never counts twice.
 */
internal object OfflineQueue {
    /** Seconds. Old entries would only confuse a friend days later. */
    const val maxAge = 3.0 * 86400

    /** The most actions kept. */
    const val limit = 50

    /** The queue without the actions that are too old, and those (to refund). */
    fun withoutStale(queue: List<PendingAction>, now: Instant): Pair<List<PendingAction>, List<PendingAction>> =
        queue.partition { now.timeIntervalSince(it.createdAt) <= maxAge }

    /**
     * The queue with [action] added, and the actions that had to go (to
     * refund): first the stale ones, then while it is over [limit] the
     * oldest care action (the next snapshot carries what it did), else the
     * oldest of the rest.
     */
    fun adding(action: PendingAction, queue: List<PendingAction>, now: Instant): Pair<List<PendingAction>, List<PendingAction>> {
        val (fresh, dropped) = withoutStale(queue + action, now)
        val kept = fresh.toMutableList()
        val removed = dropped.toMutableList()
        while (kept.size > limit) {
            val index = kept.indexOfFirst { it.isCare }.takeIf { it >= 0 } ?: 0
            removed.add(kept.removeAt(index))
        }
        return kept to removed
    }

    /** What an answer means for the action that was sent. */
    enum class Outcome {
        /** Sent (or the server refused it for good): it leaves the queue. */
        done,

        /** A daily limit: it stays and goes out on a later flush. */
        later,

        /** The network or the server is down, or the device needs to register again: stop and keep the rest. */
        stop,

        /** The device is banned: stop. */
        banned,

        /** Refused for good: it leaves the queue and coins paid for it come back. */
        refused,
    }

    fun outcome(error: Throwable): Outcome {
        val api = error as? APIError ?: return Outcome.refused
        return when {
            api.isRateLimited -> Outcome.later
            api.isTransient -> Outcome.stop
            api.isBanned -> Outcome.banned
            api.isUnauthorized -> Outcome.stop
            else -> Outcome.refused
        }
    }

    /**
     * Sends [pending] in order, oldest first. Stops at the first network
     * problem; skips what hit a daily limit; drops what the server refuses
     * for good (with a refund, unless it already left the queue some other
     * way). Actions that left the queue while this ran, or anything after
     * going offline, are skipped. Returns whether a care action was answered
     * with 409, which means the partner stopped sharing.
     *
     * @param remove takes the action out of the queue; `refund` says whether coins paid for it come back.
     */
    suspend fun flush(
        pending: List<PendingAction>,
        isQueued: (PendingAction) -> Boolean,
        isOnline: () -> Boolean,
        send: suspend (PendingAction) -> Unit,
        remove: (PendingAction, refund: Boolean) -> Unit,
        banned: (APIError) -> Unit,
    ): Boolean {
        var careEnded = false
        for (action in pending) {
            // Removing a friend in the meantime already refunded their actions.
            if (!isOnline() || !isQueued(action)) continue
            var refund = false
            try {
                send(action)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                when (outcome(e)) {
                    Outcome.later -> continue
                    Outcome.stop -> return careEnded
                    Outcome.banned -> {
                        banned(e as APIError)
                        return careEnded
                    }
                    Outcome.refused, Outcome.done -> {
                        if (action.isCare && (e as? APIError)?.status == 409) careEnded = true
                        refund = isQueued(action)
                    }
                }
            }
            remove(action, refund)
        }
        return careEnded
    }
}
