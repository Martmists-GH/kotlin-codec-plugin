package com.martmists.serialization

/**
 * Creates a companion CODEC field of type Codec<T> based on the data class primary constructor
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class RecordCodec
