package com.example.android_cast.cast

import android.content.Context
import java.io.File

/**
 * Persists pairing records on Android (private app storage). Mirror of [PairingStore]
 * on the Mac — both sides must agree the peer is paired for the PIN to be skipped.
 */
class PairingStore private constructor(
    private val file: File,
    contextFilesDir: File?,
) {
    private val records = mutableMapOf<String, PairingRecord>()

    /** Android production: store under the app's private files dir. */
    constructor(context: Context) : this(
        file = File(context.filesDir, "pairing.json"),
        contextFilesDir = context.filesDir,
    )

    /** JVM-testable: caller supplies an explicit file. */
    constructor(file: File) : this(file = file, contextFilesDir = null)

    init { load() }

    data class PairingRecord(
        val peerDeviceID: String,
        val peerDisplayName: String,
        val ourDeviceID: String,
        val createdAtMs: Long,
        val lastConnectedAtMs: Long,
    )

    @Synchronized
    fun isPaired(peerDeviceID: String): Boolean = records.containsKey(peerDeviceID)

    @Synchronized
    fun upsert(record: PairingRecord) {
        records[record.peerDeviceID] = record
        save()
    }

    @Synchronized
    fun remove(peerDeviceID: String) {
        records.remove(peerDeviceID)
        save()
    }

    @Synchronized
    fun touchLastConnected(peerDeviceID: String, nowMs: Long = System.currentTimeMillis()) {
        val rec = records[peerDeviceID] ?: return
        records[peerDeviceID] = rec.copy(lastConnectedAtMs = nowMs)
        save()
    }

    @Synchronized
    fun all(): List<PairingRecord> = records.values.toList()

    @Synchronized
    private fun load() {
        if (!file.exists()) return
        runCatching {
            // Each line is one record: peer|display|our|created|lastConnected
            file.useLines { lines ->
                for (line in lines) {
                    val parts = line.split("|")
                    if (parts.size != 5) continue
                    val r = PairingRecord(
                        peerDeviceID = parts[0],
                        peerDisplayName = parts[1],
                        ourDeviceID = parts[2],
                        createdAtMs = parts[3].toLongOrNull() ?: 0L,
                        lastConnectedAtMs = parts[4].toLongOrNull() ?: 0L,
                    )
                    records[r.peerDeviceID] = r
                }
            }
        }
    }

    @Synchronized
    private fun save() {
        val sb = StringBuilder()
        for (r in records.values) {
            sb.append(r.peerDeviceID)
            sb.append("|").append(r.peerDisplayName)
            sb.append("|").append(r.ourDeviceID)
            sb.append("|").append(r.createdAtMs)
            sb.append("|").append(r.lastConnectedAtMs)
            sb.append("\n")
        }
        file.writeText(sb.toString())
    }
}

/**
 * Drives the Android-side pairing flow with the Mac. Resolves a PIN from the
 * receiver (via a pairing transport — implementation deferred to the networking
 * layer; tests inject a fake), prompts the user, and on success persists a record.
 *
 * HITL (issue #9): PIN retry/expiry policy is owned by the receiver; this client
 * is policy-free and just relays the user's input.
 */
class PairingClient(
    private val store: PairingStore,
    private val transport: Transport,
    private val promptUser: (peerName: String, onResult: (String?) -> Unit) -> Unit,
) {
    /** Testable seam: real impl talks HTTP/mDNS-SD TXT to the receiver. */
    interface Transport {
        /** Returns the PIN the receiver wants the user to confirm, or null if paired. */
        fun requestPin(peerDeviceID: String): String?
        /** Returns true if the receiver accepted the supplied PIN. */
        fun confirmPin(peerDeviceID: String, pin: String): Boolean
    }

    sealed interface Outcome {
        data class Paired(val record: PairingStore.PairingRecord) : Outcome
        data object AlreadyPaired : Outcome
        data object Cancelled : Outcome
        data object Rejected : Outcome
    }

    fun pair(
        peerDeviceID: String,
        peerDisplayName: String,
        ourDeviceID: String,
    ): Outcome {
        if (store.isPaired(peerDeviceID)) return Outcome.AlreadyPaired

        val pin = transport.requestPin(peerDeviceID) ?: return Outcome.Rejected
        var result: Outcome = Outcome.Cancelled
        promptUser(peerDisplayName) { input ->
            if (input == null) {
                result = Outcome.Cancelled
                return@promptUser
            }
            if (transport.confirmPin(peerDeviceID, input) && input == pin) {
                val now = System.currentTimeMillis()
                val record = PairingStore.PairingRecord(
                    peerDeviceID = peerDeviceID,
                    peerDisplayName = peerDisplayName,
                    ourDeviceID = ourDeviceID,
                    createdAtMs = now,
                    lastConnectedAtMs = now,
                )
                store.upsert(record)
                result = Outcome.Paired(record)
            } else {
                result = Outcome.Rejected
            }
        }
        return result
    }
}
