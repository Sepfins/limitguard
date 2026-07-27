package io.github.sepfins.limitguard.internal.hashing

interface Canonicalizer<T> {
    fun canonicalize(value: T): String
}