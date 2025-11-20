package com.martmists.serialization

/**
 * Marks a codec reference as being wrapped in Codec.lazyInitialized { ... }
 */
@Target(AnnotationTarget.TYPE)
@Retention(AnnotationRetention.SOURCE)
annotation class LazyCodec
