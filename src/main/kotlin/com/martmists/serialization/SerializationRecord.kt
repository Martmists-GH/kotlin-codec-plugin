package com.martmists.serialization

import net.fabricmc.api.ModInitializer
import net.minecraft.nbt.NbtOps

class SerializationRecord : ModInitializer {
    override fun onInitialize() {
        val item = TestData(1, "2")
        val enc = TestData.CODEC.encodeStart(NbtOps.INSTANCE, item)
        val dec = TestData.CODEC.decode(NbtOps.INSTANCE, enc.result().get()).result().get().first
        require(item == dec) { "Expected $item to match $dec" }
    }
}
