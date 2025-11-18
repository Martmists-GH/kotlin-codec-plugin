package com.martmists.serialization

import com.mojang.authlib.GameProfile
import com.mojang.datafixers.Products
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.dynamic.Codecs

// Before plugin:

@Record
data class TestData(
    val x: Int,
    val y: String,
)

@Record
data class RelocatedTestData(
    val profile: @CodecLocation(Codecs::class, "GAME_PROFILE_CODEC") GameProfile,
)

// After plugin:

data class TestDataGenerated(
    val x: Int,
    val y: String,
) {
    companion object {
        val CODEC: Codec<TestDataGenerated> = RecordCodecBuilder.create<TestDataGenerated> {
            val prod: Products.P2<RecordCodecBuilder.Mu<TestDataGenerated>, Int, String> = it.group<Int, String>(
                Codec.INT.fieldOf("x").forGetter<TestDataGenerated>(TestDataGenerated::x),
                Codec.STRING.fieldOf("y").forGetter<TestDataGenerated>(TestDataGenerated::y),
            )
            prod.apply<TestDataGenerated>(it, ::TestDataGenerated)
        }
    }

    // Proving it exists:
    init {
        val codec: Codec<TestData> = TestData.CODEC
    }
}
