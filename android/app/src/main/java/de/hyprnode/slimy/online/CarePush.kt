package de.hyprnode.slimy.online

import de.hyprnode.slimy.model.CareLink
import de.hyprnode.slimy.model.SlimeSnapshot

/**
 * Sending the shared slime (`PUT /care/snapshot`) and settling a version
 * conflict, apart from the store so it can be tested on its own. The server
 * takes a snapshot only at its version + 1; when the other phone saved
 * first it answers 409 with its newest version and slime, which this phone
 * reconciles with its own before trying once more.
 */
internal object CarePush {
    sealed class Outcome {
        /** Stored at this version. */
        data class saved(val version: Int) : Outcome()

        /** The server says nobody cares together anymore: a sync finds out what happened. */
        data object notPaired : Outcome()

        /** Still behind after the second try: the next push sends it. */
        data object behind : Outcome()

        /** Sharing ended or the partner changed while this was on its way: nothing more to do. */
        data object gone : Outcome()
    }

    /**
     * Up to two rounds of: take the snapshot, send it at the link's version
     * + 1, and on a conflict reconcile with the server's slime and take its
     * version. [onSending] hears the name of each snapshot sent, so a
     * refused name can be remembered. Errors other than a 409 are thrown.
     */
    suspend fun rounds(
        link: () -> CareLink?,
        snapshot: () -> SlimeSnapshot,
        put: suspend (SlimeSnapshot, Int) -> CareSnapshotResult,
        setVersion: (Int) -> Unit,
        reconcile: (SlimeSnapshot) -> Unit,
        onSending: (String) -> Unit = {},
    ): Outcome {
        repeat(2) {
            val sent = link() ?: return Outcome.gone
            val outgoing = snapshot()
            onSending(outgoing.name)
            val result = put(outgoing, sent.version + 1)
            val current = link()
            if (current == null || current.partnerID != sent.partnerID) return Outcome.gone
            when (result) {
                is CareSnapshotResult.saved -> {
                    setVersion(result.version)
                    return Outcome.saved(result.version)
                }
                is CareSnapshotResult.conflict -> {
                    val newest = result.version ?: return Outcome.notPaired
                    result.snapshot?.let(reconcile)
                    if (link() != null) setVersion(newest)
                }
            }
        }
        return Outcome.behind
    }
}
