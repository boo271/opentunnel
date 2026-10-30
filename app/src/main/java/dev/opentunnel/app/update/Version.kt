package dev.opentunnel.app.update

/** Release tags are compared as numbers: v0.3.10 is newer than v0.3.9. */
object Version {

    /** "v0.3.4-beta.1" becomes "0.3.4"; null when the tag has no readable numbers. */
    fun normalize(raw: String): String? {
        val core = raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
        if (core.isEmpty()) return null
        val numbers = core.split('.').map { part ->
            part.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        }
        if (numbers.isEmpty()) return null
        return numbers.joinToString(".")
    }

    fun isNewer(remote: String, current: String): Boolean {
        val newer = normalize(remote) ?: return false
        val installed = normalize(current) ?: return false
        val left = newer.split('.').map { it.toInt() }
        val right = installed.split('.').map { it.toInt() }
        for (i in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(i) { 0 }
            val b = right.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
