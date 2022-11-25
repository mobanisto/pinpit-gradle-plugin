package de.mobanisto.pinpit.internal

fun <T> MutableList<T>.addUnique(item: T) {
    if (!contains(item)) add(item)
}
