package com.martmists.serialization

import kotlin.reflect.KClass

@Target(AnnotationTarget.TYPE)
@Retention(AnnotationRetention.SOURCE)
annotation class CodecLocation(val klass: KClass<*>, val field: String)
