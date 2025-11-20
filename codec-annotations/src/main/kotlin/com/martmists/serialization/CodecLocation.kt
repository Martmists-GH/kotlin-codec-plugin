package com.martmists.serialization

import kotlin.reflect.KClass

/**
 * If the annotated type does not have a static CODEC field, this can be used to point to the correct field.
 * Example:
 * ```
 * val text: @CodecLocation(TextCodecs::class, "CODEC") Text
 * ```
 */
@Target(AnnotationTarget.TYPE)
@Retention(AnnotationRetention.SOURCE)
annotation class CodecLocation(val klass: KClass<*>, val field: String)
