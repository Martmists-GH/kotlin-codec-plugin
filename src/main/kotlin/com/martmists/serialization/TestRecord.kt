package com.martmists.serialization

import com.mojang.authlib.GameProfile
import com.mojang.datafixers.Products
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.dynamic.Codecs
import kotlin.reflect.KProperty1

// Before plugin:

@RecordCodec
data class TestData(
    val x: Int,
    val y: String,
)

// After plugin:

data class TestDataGenerated(
    val x: Int,
    val y: String,
) {
    companion object {
        val CODEC: Codec<TestDataGenerated> = RecordCodecBuilder.create<TestDataGenerated> {
            val prod: Products.P2<RecordCodecBuilder.Mu<TestDataGenerated>, Int, String> = it.group<Int, String>(
                Codec.INT.fieldOf("x").forGetter<TestDataGenerated>(TestDataGenerated::x as KProperty1<TestDataGenerated, Int>),
                Codec.STRING.fieldOf("y").forGetter<TestDataGenerated>(TestDataGenerated::y),
            )
            prod.apply<TestDataGenerated>(it, ::TestDataGenerated)
        }
    }
}
