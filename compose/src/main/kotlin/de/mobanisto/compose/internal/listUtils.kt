package de.mobanisto.compose.internal

fun <T> MutableList<T>.addUnique(item: T) {
    if (!contains(item)) add(item)
}
