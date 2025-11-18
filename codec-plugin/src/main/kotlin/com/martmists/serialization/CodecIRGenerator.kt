package com.martmists.serialization

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
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
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionReferenceImplWithShape
import org.jetbrains.kotlin.ir.expressions.impl.IrPropertyReferenceImpl
import org.jetbrains.kotlin.ir.get
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
                val codecField = (companion ?: typeSymbol.owner).declarations.filterIsInstance<IrProperty>().firstOrNull { it.name.asString() == fieldName }?.backingField
                ?: error("Type ${typeSymbol.owner.name} does not have a CODEC property")

                val receiver = if (companion != null) irGetObjectValue(companion.defaultType, companion.symbol) else null

                irGetField(receiver, codecField)
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

    private fun IrPluginContext.buildSamLambdaExpression(
        startOffset: Int,
        endOffset: Int,
        samType: IrType,                          // expected java functional interface type (from call site)
        parent: IrDeclarationParent,              // where to place the generated IrSimpleFunction
        bodyBuilder: IrBlockBodyBuilder.(List<IrValueParameter>) -> IrExpression
    ): IrExpression {
        // require a simple type class-based SAM
        val simpleSam = samType as? IrSimpleType
            ?: error("SAM conversion requires IrSimpleType, got: $samType")

        val samClassSymbol = simpleSam.classifier as? IrClassSymbol
            ?: error("SAM type classifier is not a class symbol: $samType")

        val samClass = samClassSymbol.owner

        // Find the single abstract method (the SAM). Be tolerant: look for functions that are abstract (Kotlin fun interfaces or Java SAMs).
        val samMethod = samClass.functions.singleOrNull { it.modality == Modality.ABSTRACT }
            ?: error("Type $samType is not a valid SAM (no single abstract method)")

        // NOTE: if the SAM interface is generic, the method's parameter and return types may involve type parameters.
        // For a correct substitution you'd need to map the samType.typeArguments onto samClass.typeParameters and substitute.
        // In many practical cases (java.util.function.*) the method types are already concrete via samType arguments.
        // If you must support generic substitution, add a type-substitution step here. For many of your plugin uses it's not necessary.

        // Collect parameter and return types from the SAM method
        val paramTypes: List<IrType> = samMethod.parameters.filter { it.kind == IrParameterKind.Regular }.map { it.type }
        val returnType: IrType = samMethod.returnType

        // Create the IR function (the lambda's underlying function)
        val lambdaFun = this.irFactory.createSimpleFunction(
            startOffset,
            endOffset,
            IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA,
            Name.special("<sam_lambda>"),
            DescriptorVisibilities.LOCAL,
            isInline = false,
            isExpect = false,
            returnType = returnType,
            modality = Modality.FINAL,
            symbol = IrSimpleFunctionSymbolImpl(),
            isTailrec = false,
            isSuspend = false,
            isOperator = false,
            isInfix = false
        ).apply {
            this.parent = parent
        }

        // Add parameters to the lambda function according to the SAM signature
        val irParams: List<IrValueParameter> = paramTypes.mapIndexed { index, pType ->
            lambdaFun.addValueParameter {
                name = Name.identifier("p$index")
                type = pType
            }
        }

        // Build body using the provided bodyBuilder; it should produce the expression to return
        lambdaFun.body = IrBlockBuilder(this, Scope(lambdaFun.symbol), startOffset, endOffset)
            .irBlockBody {
                val res = bodyBuilder(irParams)
                +irReturn(res)
            }

        // Wrap the function in an IrFunctionExpressionImpl with the expected SAM type (so backend can convert it to the functional interface)
        return IrFunctionExpressionImpl(
            startOffset,
            endOffset,
            samType,
            lambdaFun,
            IrStatementOrigin.LAMBDA
        )
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
                                                    IrFunctionReferenceImpl(
                                                        klass.startOffset,
                                                        klass.endOffset,
                                                        forGetter.owner.parameters[1].type,
                                                        prop.getter!!.symbol,
                                                        0,
                                                    )
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
                                            apply.owner.parameters[2].type,
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
