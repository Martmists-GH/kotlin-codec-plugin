package com.martmists.serialization

import org.jetbrains.kotlin.backend.common.KtDefaultCommonBackendErrorMessages
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.wrapWithLambdaCall
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.jvm.fieldByName
import org.jetbrains.kotlin.backend.jvm.ir.kClassReference
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.ir.builders.IrBlockBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.Scope
import org.jetbrains.kotlin.ir.builders.buildStatement
import org.jetbrains.kotlin.ir.builders.declarations.IrValueParameterBuilder
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObjectValue
import org.jetbrains.kotlin.ir.builders.irRawFunctionReference
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irReturnUnit
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrPropertyReferenceImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.model.getArgument
import org.jetbrains.kotlin.types.model.getType

@OptIn(UnsafeDuringIrConstructionAPI::class)
class CodecIRGenerator : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val recordAnnotation = FqName("com.martmists.serialization.Record")

        val recordClasses = mutableListOf<Pair<IrClass, IrClass>>()
        moduleFragment.files.forEach { file ->
            file.declarations.filterIsInstance<IrClass>().forEach { klass ->
                if (!klass.annotations.hasAnnotation(recordAnnotation)) return@forEach

                if (!klass.isData) {
                    pluginContext.reportError(
                        klass,
                        "@Record can only be applied to data classes"
                    )
                    return@forEach
                }

                val companion = klass.declarations.filterIsInstance<IrClass>().firstOrNull { it.isCompanion } ?: createCompanionObject(klass, pluginContext)

                if (companion.declarations.any { it is IrField && it.name.asString() == "CODEC" }) {
                    pluginContext.reportError(
                        companion,
                        "@Record classes should not have a CODEC field"
                    )
                }

                addEmptyCodecField(klass, companion, pluginContext)
                recordClasses += klass to companion
            }
        }

        recordClasses.forEach { (klass, companion) ->
            initializeCodecField(klass, companion, pluginContext)
        }
    }

    private fun createCompanionObject(
        klass: IrClass,
        pluginContext: IrPluginContext
    ): IrClass {
        val companion = pluginContext.irFactory.buildClass {
            startOffset = klass.startOffset
            endOffset = klass.endOffset
            name = Name.identifier("Companion")
            isCompanion = true
            kind = ClassKind.OBJECT
        }
        companion.createThisReceiverParameter()
        companion.addConstructor {
            isPrimary = true
        }.apply {
            body = pluginContext.irFactory.createExpressionBody(
                companion.startOffset,
                companion.endOffset,
                IrBlockBuilder(pluginContext, Scope(symbol), klass.startOffset, klass.endOffset).buildStatement(
                    klass.startOffset,
                    klass.endOffset
                ) {
                    irReturnUnit()
                }
            )
        }
        companion.parent = klass
        klass.declarations.add(companion)
        return companion
    }

    private fun addEmptyCodecField(klass: IrClass, companion: IrClass, pluginContext: IrPluginContext) {
        val field = pluginContext.irFactory.buildField {
            startOffset = klass.startOffset
            endOffset = klass.endOffset
            name = Name.identifier("CODEC")
            type = pluginContext.irBuiltIns.anyType
        }
        field.parent = companion
        companion.declarations.add(field)
    }

    private fun IrBuilder.getCodec(pluginContext: IrPluginContext, type: IrType): IrExpression {
        val codecKlass = pluginContext.referenceClass(
            ClassId(
                FqName("com.mojang.serialization"),
                Name.identifier("Codec"),
            )
        )!!

        return when {
            type == pluginContext.irBuiltIns.intType -> {
                irGetField(
                    null,
                    codecKlass.owner.declarations.filterIsInstance<IrProperty>()
                        .first { it.name.asString() == "INT" }.backingField!!
                )
            }
            type == pluginContext.irBuiltIns.longType -> {
                irGetField(
                    null,
                    codecKlass.owner.declarations.filterIsInstance<IrProperty>()
                        .first { it.name.asString() == "LONG" }.backingField!!
                )
            }
            type == pluginContext.irBuiltIns.floatType -> {
                irGetField(
                    null,
                    codecKlass.owner.declarations.filterIsInstance<IrProperty>()
                        .first { it.name.asString() == "FLOAT" }.backingField!!
                )
            }
            type == pluginContext.irBuiltIns.doubleType -> {
                irGetField(
                    null,
                    codecKlass.owner.declarations.filterIsInstance<IrProperty>()
                        .first { it.name.asString() == "DOUBLE" }.backingField!!
                )
            }
            type == pluginContext.irBuiltIns.booleanType -> {
                irGetField(
                    null,
                    codecKlass.owner.declarations.filterIsInstance<IrProperty>()
                        .first { it.name.asString() == "BOOLEAN" }.backingField!!
                )
            }
            type == pluginContext.irBuiltIns.stringType -> {
                irGetField(
                    null,
                    codecKlass.owner.declarations.filterIsInstance<IrProperty>()
                        .first { it.name.asString() == "STRING" }.backingField!!
                )
            }

            type.classOrNull == pluginContext.irBuiltIns.listClass -> {
                val nestedType = (type as IrSimpleType).arguments.first().typeOrFail
                val nested = getCodec(pluginContext, nestedType)
                irCall(codecKlass.owner.functions.first { it.name.asString() == "list" }).apply {
                    arguments[0] = nested
                }
            }

            else -> {
                val typeSymbol = type.classOrNull ?: error("Type has no class: $type")
                val codecProperty = (typeSymbol.owner.companionObject() ?: typeSymbol.owner).declarations
                    .filterIsInstance<IrProperty>()
                    .firstOrNull { it.name.asString() == "CODEC" }
                    ?: error("Type ${typeSymbol.owner.name} does not have a CODEC property")

                val field = codecProperty.backingField
                    ?: error("CODEC field not found for type $type")

                val dispatchReceiver = codecProperty.parent as? IrClass
                val receiverExpression = if (dispatchReceiver?.isCompanion == true) {
                    irGetObjectValue(dispatchReceiver.defaultType, dispatchReceiver.symbol)
                } else {
                    null
                }

                irGetField(receiverExpression, field)
            }
        }
    }

    private fun initializeCodecField(
        klass: IrClass,
        companion: IrClass,
        pluginContext: IrPluginContext,
    ) {
        val ctor = klass.primaryConstructor ?: run {
            pluginContext.reportError(klass, "@Record requires a primary constructor")
            return
        }

        val field = companion.fields.first { it.name.asString() == "CODEC" }

        val codecBuilderKlass = pluginContext.referenceClass(
            ClassId(
                FqName("com.mojang.serialization.codecs"),
                Name.identifier("RecordCodecBuilder")
            )
        )!!
        val codecBuilderInstanceKlass = pluginContext.referenceClass(
            ClassId(
                FqName("com.mojang.serialization.codecs"),
                FqName("RecordCodecBuilder.Instance"),
                false,
            )
        )!!
        val codecKlass = pluginContext.referenceClass(
            ClassId(
                FqName("com.mojang.serialization"),
                Name.identifier("Codec"),
            )
        )!!
        val mapCodecKlass = pluginContext.referenceClass(
            ClassId(
                FqName("com.mojang.serialization"),
                Name.identifier("MapCodec"),
            )
        )!!
        val appKlass = pluginContext.referenceClass(
            ClassId(
                FqName("com.mojang.datafixers"),
                FqName("Products.P${ctor.nonDispatchParameters.size}"),
                false,
            )
        )!!

        val create = codecBuilderKlass.functions.first { it.owner.name.asString() == "create" }
        val group = codecBuilderInstanceKlass.functions.first {
            it.owner.name.asString() == "group" && it.owner.nonDispatchParameters.size == ctor.parameters.size
        }
        val fieldOf = codecKlass.functions.first { it.owner.name.asString() == "fieldOf" }
        val optionalFieldOf = codecKlass.functions.first { it.owner.name.asString() == "optionalFieldOf" }
        val forGetter = mapCodecKlass.functions.first { it.owner.name.asString() == "forGetter" }
        val apply = appKlass.functions.first { it.owner.name.asString() == "apply" }

        field.parent = companion
        field.initializer = pluginContext.irFactory.createExpressionBody(
            IrBlockBuilder(pluginContext, Scope(companion.symbol), klass.startOffset, klass.endOffset).buildStatement(
                klass.startOffset,
                klass.endOffset
            ) {
                irCall(create).apply {
                    typeArguments[0] = klass.defaultType

                    val codecType = codecKlass.typeWith(klass.defaultType)
                    val lambdaType = pluginContext.referenceClass(StandardNames.getFunctionClassId(1))!!.typeWith(codecBuilderInstanceKlass.typeWith(klass.defaultType), codecType)
                    val lambda = pluginContext.irFactory.createSimpleFunction(
                        klass.startOffset,
                        klass.endOffset,
                        IrDeclarationOrigin.DEFINED,
                        Name.special("<anonymous>"),
                        DescriptorVisibilities.LOCAL,
                        isInline = false,
                        isExpect = false,
                        returnType = codecType,
                        modality = Modality.FINAL,
                        symbol = IrSimpleFunctionSymbolImpl(),
                        isTailrec = false,
                        isSuspend = false,
                        isOperator = false,
                        isInfix = false,
                    ).apply {
                        origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
                        parent = companion

                        val param = addValueParameter {
                            name = Name.identifier("it")
                            type = codecBuilderInstanceKlass.typeWith(klass.defaultType)
                        }

                        body = IrBlockBuilder(pluginContext, Scope(symbol), startOffset, endOffset).irBlockBody {
                            val builder = irGet(param)

                            val product = irCall(group).apply {
                                dispatchReceiver = builder
                                ctor.nonDispatchParameters.forEachIndexed { index, arg ->
                                    typeArguments[index] = arg.type
                                }
                                arguments.addAll(1,
                                    ctor.nonDispatchParameters.map { arg ->
                                        val codec = getCodec(pluginContext, arg.type)

                                        val field = if (arg.hasDefaultValue()) {
                                            irCall(optionalFieldOf).apply {
                                                dispatchReceiver = codec
                                                arguments.addAll(1,
                                                    listOf(
                                                        irString(arg.name.asString()),
                                                        arg.defaultValue!!.expression,
                                                    )
                                                )
                                            }
                                        } else {
                                            irCall(fieldOf).apply {
                                                dispatchReceiver = codec
                                                arguments.addAll(1,
                                                    listOf(
                                                        irString(arg.name.asString()),
                                                    )
                                                )
                                            }
                                        }

                                        irCall(forGetter).apply {
                                            typeArguments[0] = klass.defaultType
                                            dispatchReceiver = field
                                            val prop = klass.properties.first { it.name == arg.name }
                                            arguments.addAll(1,
                                                listOf(
                                                    IrPropertyReferenceImpl(
                                                        klass.startOffset,
                                                        klass.endOffset,
                                                        pluginContext.referenceClass(ClassId(
                                                            FqName("kotlin.reflect"),
                                                            Name.identifier(StandardNames.K_PROPERTY_PREFIX+"1")
                                                        ))!!.typeWith(listOf(klass.defaultType, arg.type)),
                                                        prop.symbol,
                                                        0,
                                                        null,
                                                        prop.getter?.symbol,
                                                        null,
                                                        null
                                                    ).apply {

                                                    }
                                                )
                                            )
                                        }
                                    }
                                )
                            }

                            val result = irCall(apply).apply {
                                typeArguments[0] = klass.defaultType
                                dispatchReceiver = product
                                arguments.addAll(1,
                                    listOf(
                                        builder,
                                        IrFunctionReferenceImpl(
                                            klass.startOffset,
                                            klass.endOffset,
                                            pluginContext.irBuiltIns.functionN(ctor.nonDispatchParameters.size).typeWith(*ctor.nonDispatchParameters.map { it.type }.toTypedArray(), codecType),
                                            ctor.symbol,
                                            0,
                                        )
                                    )
                                )
                            }

                            +irReturn(result)
                        }
                    }

                    arguments[0] = IrFunctionExpressionImpl(companion.startOffset, companion.endOffset, lambdaType, lambda, IrStatementOrigin.LAMBDA)
                }
            }
        )
    }

    private fun IrPluginContext.reportError(target: IrDeclaration, message: String) {
        // TODO: diagnosticReporter.at(target).report(...)
        throw Exception(message)
    }
}
