package com.example.electrophy_app

/** One callback-completed ATT write at a time. Accepted writes are never retried. */
internal class GattWriteGate {
    enum class Kind { SUBSCRIPTION, COMMAND }
    var pending: Kind? = null
        private set
    var label: String = ""
        private set
    var failed: Boolean = false
        private set
    var retryPending: Boolean = false
        private set
    private var refusals = 0

    fun begin(kind: Kind, description: String): Boolean {
        if (pending != null || failed || retryPending) return false
        pending = kind; label = description
        return true
    }

    fun complete(kind: Kind, success: Boolean): Boolean {
        if (pending != kind || failed) return false
        pending = null
        failed = !success
        refusals = 0
        return true
    }

    /** Only for a synchronous busy/refused start: Android has not accepted the write. */
    fun retryRefusedCommand(): Boolean {
        if (pending != Kind.COMMAND || failed || refusals >= 3) return false
        refusals++
        pending = null
        retryPending = true
        return true
    }

    fun retryReady(): Boolean {
        if (!retryPending || failed) return false
        retryPending = false
        return true
    }

    fun timeout(): Boolean {
        if (pending == null || failed) return false
        pending = null; failed = true
        return true
    }

    fun reset() {
        pending = null; label = ""; failed = false
        retryPending = false; refusals = 0
    }
}
