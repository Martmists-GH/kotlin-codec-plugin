package com.martmists.serialization

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.diagnostics.AbstractSourceElementPositioningStrategy
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategy
import org.jetbrains.kotlin.ir.PsiSourceManager.findPsiElement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrPropertyReferenceImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

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

                addEmptyCodecField(companion, pluginContext)
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

    private fun addEmptyCodecField(companion: IrClass, pluginContext: IrPluginContext) {
        val props = companion.declarations.filterIsInstance<IrProperty>()
        if (props.any { it.name.asString() == "CODEC" }) return

        val field = pluginContext.irFactory.buildField {
            startOffset = companion.startOffset
            endOffset = companion.endOffset
            name = Name.identifier("CODEC")
            type = pluginContext.irBuiltIns.anyType
        }
        field.parent = companion
        companion.declarations.add(field)
    }

    private fun IrBuilder.getCodec(pluginContext: IrPluginContext, type: IrType): IrExpression {
        val codecLocationAnnotation = FqName("com.martmists.serialization.CodecLocation")

        val codecKlass = pluginContext.referenceClass(
            ClassId(
                FqName("com.mojang.serialization"),
                Name.identifier("Codec"),
            )
        )!!

        return when {
            type == pluginContext.irBuiltIns.intType -> irGetStaticCodec(codecKlass, "INT")
            type == pluginContext.irBuiltIns.floatType -> irGetStaticCodec(codecKlass, "FLOAT")
            type == pluginContext.irBuiltIns.booleanType -> irGetStaticCodec(codecKlass, "BOOLEAN")
            type == pluginContext.irBuiltIns.stringType -> irGetStaticCodec(codecKlass, "STRING")

            type.classOrNull == pluginContext.irBuiltIns.listClass -> {
                val elementType = (type as IrSimpleType).arguments.first().typeOrFail
                val elementCodec = getCodec(pluginContext, elementType)
                irCall(codecKlass.owner.functions.first { it.name.asString() == "list" && it.parameters.size == 1 }).apply {
                    arguments[0] = elementCodec
                }
            }

            else -> {
                val ann = type.annotations.firstOrNull { it.isAnnotationWithEqualFqName(codecLocationAnnotation) }
                val (typeSymbol, fieldName) = if (ann == null) {
                    val klass = type.classOrNull ?: error("Type has no class: $type")
                    klass to "CODEC"
                } else {
                    val klass = ann.arguments[0] as IrClassReference
                    val name = ann.arguments[1] as IrConst
                    (klass.symbol as IrClassSymbol) to (name.value as String)
                }

                val companion = typeSymbol.owner.companionObject()

                val codecProp = (companion ?: typeSymbol.owner).declarations.filterIsInstance<IrProperty>().firstOrNull { it.name.asString() == fieldName } ?: error("Type ${typeSymbol.owner.name} does not have a CODEC property")
                val receiver = if (companion != null) irGetObjectValue(companion.defaultType, companion.symbol) else null

                if (codecProp.getter != null) {
                    irCall(codecProp.getter!!).apply {
                        dispatchReceiver = receiver ?: irGetObjectValue(typeSymbol.owner.defaultType, typeSymbol)
                    }
                } else {
                    irGetField(receiver, codecProp.backingField!!)
                }
            }
        }
    }

    private fun IrBuilder.irGetStaticCodec(codecKlass: IrClassSymbol, fieldName: String): IrExpression {
        val field = codecKlass.owner.declarations
            .filterIsInstance<IrProperty>()
            .first { it.name.asString() == fieldName }
            .backingField!!
        return irGetField(null, field)
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

        val field = companion.declarations.filterIsInstance<IrProperty>().first { it.name.asString() == "CODEC" }.backingField!!

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
        val productKlass = pluginContext.referenceClass(
            ClassId(
                FqName("com.mojang.datafixers"),
                FqName("Products.P${ctor.nonDispatchParameters.size}"),
                false,
            )
        )!!
        val appKlass = pluginContext.referenceClass(
            ClassId(
                FqName("com.mojang.datafixers.kinds"),
                FqName("App"),
                false,
            )
        )!!

        val create = codecBuilderKlass.functions.first { it.owner.name.asString() == "create" }
        val group = codecBuilderInstanceKlass.functions.first {
            it.owner.name.asString() == "group" && it.owner.nonDispatchParameters.size == ctor.parameters.size
        }
        val fieldOf = codecKlass.functions.first { it.owner.name.asString() == "fieldOf" }
        val optionalFieldOf = codecKlass.functions.first { it.owner.name.asString() == "optionalFieldOf" && it.owner.nonDispatchParameters.size == 2 }
        val forGetter = mapCodecKlass.functions.first { it.owner.name.asString() == "forGetter" }
        val apply = productKlass.functions.first { it.owner.name.asString() == "apply" }

        field.initializer = pluginContext.irFactory.createExpressionBody(
            IrBlockBuilder(pluginContext, Scope(companion.symbol), klass.startOffset, klass.endOffset).buildStatement(
                klass.startOffset,
                klass.endOffset
            ) {
                irCall(create).apply {
                    typeArguments[0] = klass.defaultType

                    val lambdaType = pluginContext.irBuiltIns.functionN(1).typeWith(codecBuilderInstanceKlass.typeWith(klass.defaultType), appKlass.defaultType)
                    val lambda = pluginContext.irFactory.createSimpleFunction(
                        klass.startOffset,
                        klass.endOffset,
                        IrDeclarationOrigin.DEFINED,
                        Name.special("<anonymous>"),
                        DescriptorVisibilities.LOCAL,
                        isInline = false,
                        isExpect = false,
                        returnType = appKlass.defaultType,
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
                                        val name = (klass.properties.firstOrNull { it.name.asString() == arg.name.asString() }?.getAnnotation(FqName("kotlinx.serialization.SerialName"))?.arguments?.get(0) as IrConst?)?.value as String? ?: arg.name.asString()

                                        val field = if (arg.hasDefaultValue()) {
                                            irCall(optionalFieldOf).apply {
                                                dispatchReceiver = codec
                                                arguments.addAll(1,
                                                    listOf(
                                                        irString(name),
                                                        arg.defaultValue!!.expression,
                                                    )
                                                )
                                            }
                                        } else {
                                            irCall(fieldOf).apply {
                                                dispatchReceiver = codec
                                                arguments.addAll(1,
                                                    listOf(
                                                        irString(name),
                                                    )
                                                )
                                            }
                                        }

                                        irCall(forGetter).apply {
                                            typeArguments[0] = klass.defaultType
                                            dispatchReceiver = field
                                            val prop = klass.properties.first { it.name == arg.name }
                                            val propRef = IrPropertyReferenceImpl(
                                                startOffset,
                                                endOffset,
                                                pluginContext.irBuiltIns.kProperty1Class.typeWith(klass.defaultType, prop.getter!!.returnType),
                                                prop.symbol,
                                                0,
                                                null,
                                                prop.getter!!.symbol,
                                                null,
                                                null,
                                            )

                                            arguments.addAll(1,
                                                listOf(
                                                    irSamConversion(propRef, forGetter.owner.parameters[1].type.classOrNull!!.typeWith(klass.defaultType, prop.getter!!.returnType)),
                                                )
                                            )
                                        }
                                    }
                                )
                            }

                            val result = irCall(apply).apply {
                                typeArguments[0] = klass.defaultType
                                dispatchReceiver = product

                                val ctorRef = IrFunctionReferenceImpl(
                                    startOffset,
                                    endOffset,
                                    pluginContext.irBuiltIns.functionN(ctor.nonDispatchParameters.size).typeWith(
                                        *ctor.nonDispatchParameters.map { it.type }.toTypedArray(),
                                        klass.defaultType,
                                    ),
                                    ctor.symbol,
                                    0
                                )

                                arguments.addAll(1,
                                    listOf(
                                        builder,
                                        irSamConversion(ctorRef, apply.owner.parameters[2].type.classOrNull!!.typeWith(
                                            *ctor.nonDispatchParameters.map { it.type }.toTypedArray(),
                                            klass.defaultType
                                        ))
                                    )
                                )
                            }

                            +irReturn(result)
                        }
                    }

                    arguments[0] = irSamConversion(
                        IrFunctionExpressionImpl(companion.startOffset, companion.endOffset, lambdaType, lambda, IrStatementOrigin.LAMBDA),
                        create.owner.parameters[0].type
                    )
                }
            }
        )
    }

    private fun IrPluginContext.reportError(target: IrDeclaration, message: String) {
        // TODO: diagnosticReporter.at(target).report(...)
        throw Exception(message)
    }
}
