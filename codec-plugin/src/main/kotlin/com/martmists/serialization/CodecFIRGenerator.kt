package com.martmists.serialization

import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.UnresolvedExpressionTypeAccess
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.getContainingClassLookupTag
import org.jetbrains.kotlin.fir.plugin.createCompanionObject
import org.jetbrains.kotlin.fir.plugin.createDefaultPrivateConstructor
import org.jetbrains.kotlin.fir.plugin.createMemberProperty
import org.jetbrains.kotlin.fir.references.impl.FirSimpleNamedReference
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.name.*

@OptIn(SymbolInternals::class, DirectDeclarationsAccess::class)
class CodecFIRGenerator(session: FirSession) : FirDeclarationGenerationExtension(session) {
    private val recordAnnotationId = ClassId(
        FqName("com.martmists.serialization"),
        Name.identifier("Record")
    )

    override fun getCallableNamesForClass(
        classSymbol: FirClassSymbol<*>,
        context: MemberGenerationContext
    ): Set<Name> {
        val outer = classSymbol.getContainingClassLookupTag()?.toSymbol(session) as? FirClassSymbol<*>
        if (classSymbol.isCompanion && outer?.hasAnnotation(recordAnnotationId, session) == true) {
            return setOf(Name.identifier("CODEC"), SpecialNames.INIT)
        }

        return emptySet()
    }


    @OptIn(UnresolvedExpressionTypeAccess::class)
    override fun getNestedClassifiersNames(
        classSymbol: FirClassSymbol<*>,
        context: NestedClassGenerationContext
    ): Set<Name> {
        if (classSymbol.annotations.isEmpty()) return emptySet()
        return if (classSymbol.annotations.filterIsInstance<FirAnnotationCall>().any { (it.calleeReference as? FirSimpleNamedReference)?.name?.asString() == "Record" }) {
            setOf(Name.identifier("Companion"))
        } else {
            emptySet()
        }
    }

    override fun generateNestedClassLikeDeclaration(
        owner: FirClassSymbol<*>,
        name: Name,
        context: NestedClassGenerationContext
    ): FirClassLikeSymbol<*>? {
        if (!owner.annotations.filterIsInstance<FirAnnotationCall>().any { (it.calleeReference as? FirSimpleNamedReference)?.name?.asString() == "Record" }) return null
        if (name.asString() != "Companion") return null

        val existing = owner.fir.declarations.filterIsInstance<FirRegularClass>().firstOrNull { it.isCompanion }
        if (existing != null) return null

        val companion = createCompanionObject(owner, CodecGenerationKey)
        return companion.symbol
    }

    override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
        val owner = context.owner
        val outer = owner.getContainingClassLookupTag()?.toSymbol(session) as? FirClassSymbol<*>

        if (!owner.isCompanion || outer == null ||
            !outer.hasAnnotation(recordAnnotationId, session)
        ) return emptyList()

        val ctor = createDefaultPrivateConstructor(owner, CodecGenerationKey)
        return listOf(ctor.symbol)
    }

    override fun generateProperties(
        callableId: CallableId,
        context: MemberGenerationContext?
    ): List<FirPropertySymbol> {
        if (context == null) return emptyList()
        if (callableId.callableName.asString() != "CODEC") return emptyList()

        val owner = context.owner
        val outer = owner.getContainingClassLookupTag()?.toSymbol(session) as? FirClassSymbol<*>
            ?: return emptyList()
        if (!owner.isCompanion || !outer.hasAnnotation(recordAnnotationId, session)) return emptyList()

        val codecClass = session.symbolProvider.getClassLikeSymbolByClassId(
            ClassId(FqName("com.mojang.serialization"), Name.identifier("Codec"))
        ) ?: return emptyList()

        val codecType = codecClass.constructType(arrayOf(outer.defaultType()))

        val prop = createMemberProperty(
            owner,
            CodecGenerationKey,
            Name.identifier("CODEC"),
            codecType,
        )

        return listOf(prop.symbol)
    }
}