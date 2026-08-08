package io.github.sepfins.limitguard.internal.hashing

import com.apicatalog.jcs.Jcs

internal object CanonicalJson {
    fun encodeObject(value: Map<String, Any?>): String =
        Jcs.canonize(value)
}
