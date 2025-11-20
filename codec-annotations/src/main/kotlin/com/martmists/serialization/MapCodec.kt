package com.martmists.serialization

/**
 * Creates a companion CODEC field of type MapCodec<T> based on the data class primary constructor
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class MapCodec
