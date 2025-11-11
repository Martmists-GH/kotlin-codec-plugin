package com.martmists.serialization

import com.google.auto.service.AutoService
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

@AutoService(FirExtensionRegistrar::class)
class CodecFIRRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::CodecFIRGenerator
    }
}
