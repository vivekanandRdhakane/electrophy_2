package com.example.electrophy_app

/** One callback-completed ATT write at a time. Failure requires a new connection. */
internal class GattWriteGate {
    enum class Kind { SUBSCRIPTION, COMMAND }
    var pending: Kind? = null
        private set
    var label: String = ""
        private set
    var failed: Boolean = false
        private set

    fun begin(kind: Kind, description: String): Boolean {
        if (pending != null || failed) return false
        pending = kind; label = description
        return true
    }

    fun complete(kind: Kind, success: Boolean): Boolean {
        if (pending != kind || failed) return false
        pending = null
        failed = !success
        return true
    }

    fun timeout(): Boolean {
        if (pending == null || failed) return false
        pending = null; failed = true
        return true
    }

    fun reset() { pending = null; label = ""; failed = false }
}
