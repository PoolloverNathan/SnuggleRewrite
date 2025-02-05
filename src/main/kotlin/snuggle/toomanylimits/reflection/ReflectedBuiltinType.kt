package snuggle.toomanylimits.reflection

import snuggle.toomanylimits.builtins.BuiltinType
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import snuggle.toomanylimits.reflection.annotations.*
import snuggle.toomanylimits.representation.asts.typed.FieldDef
import snuggle.toomanylimits.representation.asts.typed.MethodDef
import snuggle.toomanylimits.representation.asts.typed.TypeDef
import snuggle.toomanylimits.representation.passes.output.getRuntimeClassName
import snuggle.toomanylimits.representation.passes.output.getStaticObjectName
import snuggle.toomanylimits.representation.passes.typing.TypingCache
import snuggle.toomanylimits.representation.passes.typing.getReflectedBuiltin
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.reflect.KClass

/**
 * A BuiltinType generated by reflecting a java/kotlin Class.
 *
 * ObjectIndex is an optional value that should be set when the class
 * has the @SnuggleStatic annotation. With it, an instance of reflectedClass will
 * be stored in static variable "#STATIC_OBJECT#objectIndex" inside the SnuggleRuntime.
 */
class ReflectedBuiltinType(val reflectedClass: Class<*>, private val objectIndex: Int?): BuiltinType {

    init {
        if (reflectedClass.typeParameters.isNotEmpty())
            if (!reflectedClass.isAnnotationPresent(SnuggleAcknowledgeGenerics::class.java))
                throw IllegalArgumentException("Generic jvm classes not supported for reflection - to override this, add @SnuggleAcknowledgeGenerics. Generic types will be erased to Object.")
        if ((objectIndex == null) == reflectedClass.isAnnotationPresent(SnuggleStatic::class.java))
            throw IllegalArgumentException("@SnuggleStatic classes must have an objectIndex, and non-@SnuggleStatic classes must not.")
    }

    // Whether this uses static-instance mode. If it does, then an instance of reflectedClass will
    // be stored in static variable "#StaticObject#objectIndex" inside the SnuggleRuntime.
    private val isStaticInstanceMode: Boolean = objectIndex != null;

    // Generic classes not supported
    override val numGenerics: Int get() = 0

    override val baseName: String = reflectedClass.annotationOrElse(SnuggleRename::class, reflectedClass.simpleName) { it.value }

    override fun name(generics: List<TypeDef>, typeCache: TypingCache): String = baseName
    override val nameable: Boolean = true //TODO configurable with annotation
    override fun runtimeName(generics: List<TypeDef>, typeCache: TypingCache): String =
        Type.getInternalName(reflectedClass)
    override fun descriptor(generics: List<TypeDef>, typeCache: TypingCache): List<String> =
        listOf("L" + this.runtimeName(generics, typeCache) + ";")
    override fun stackSlots(generics: List<TypeDef>, typeCache: TypingCache): Int = 1
    override fun isPlural(generics: List<TypeDef>, typeCache: TypingCache): Boolean = false
    override fun isReferenceType(generics: List<TypeDef>, typeCache: TypingCache): Boolean = true
    override fun hasStaticConstructor(generics: List<TypeDef>, typeCache: TypingCache): Boolean = true //TODO Configurable
    override fun getPrimarySupertype(generics: List<TypeDef>, typeCache: TypingCache): TypeDef
        = fetchType(reflectedClass.annotatedSuperclass, typeCache)

    override fun getFields(generics: List<TypeDef>, typeCache: TypingCache): List<FieldDef> {
        return reflectedClass.declaredFields.mapNotNull {
            // If this is denied, or neither this nor the class is allowed, don't emit a field.
            if (it.isAnnotationPresent(SnuggleDeny::class.java) ||
                !it.isAnnotationPresent(SnuggleAllow::class.java) &&
                !reflectedClass.isAnnotationPresent(SnuggleAllow::class.java)
            ) null
            else if (isStaticInstanceMode)
                throw IllegalArgumentException("Allowed fields not yet supported in @SnuggleStatic types")
            // Otherwise, do emit one.
            else FieldDef.BuiltinField(
                Modifier.isPublic(it.modifiers),
                Modifier.isStatic(it.modifiers),
                !Modifier.isFinal(it.modifiers),
                it.name,
                null,
                fetchType(it.annotatedType, typeCache)
            )
        }
    }

    override fun getMethods(generics: List<TypeDef>, typeCache: TypingCache): List<MethodDef> {
        val thisType = getReflectedBuiltin(reflectedClass, typeCache)
        return reflectedClass.declaredMethods.mapNotNull {
            reflectMethod(it, thisType, typeCache)
        }
    }

    private fun reflectMethod(method: Method, owningType: TypeDef, typeCache: TypingCache): MethodDef? {
        // If this is denied, or neither this nor the class is allowed, don't emit the method.
        if (method.isAnnotationPresent(SnuggleDeny::class.java) ||
            !method.isAnnotationPresent(SnuggleAllow::class.java) &&
            !reflectedClass.isAnnotationPresent(SnuggleAllow::class.java)
        ) return null
        val isStatic = Modifier.isStatic(method.modifiers)
        val name = method.annotationOrElse(SnuggleRename::class, method.name) { it.value }

        return MethodDef.BytecodeMethodDef(
            pub = true, isStatic || isStaticInstanceMode, owningType, name,
            returnType = fetchType(method.annotatedReturnType, typeCache),
            paramTypes = method.annotatedParameterTypes.map { fetchType(it, typeCache) },
            preBytecode = { writer, _, _ ->
                // If we're in static instance mode, then load a "receiver" on the stack first!
                if (isStaticInstanceMode && !isStatic) {
                    // If the method was non-static, but we're in static instance mode, then we need
                    // to fetch the instance from the runtime before invoking the method.
                    val runtimeName = getRuntimeClassName()
                    val instanceFieldName = getStaticObjectName(objectIndex!!)
                    val instanceFieldDesc = owningType.descriptor[0]
                    writer.visitFieldInsn(Opcodes.GETSTATIC, runtimeName, instanceFieldName, instanceFieldDesc)
                }
            },
            bytecode = { writer, _, _ ->
                // Now call the method.
                val opcode = if (isStatic) Opcodes.INVOKESTATIC else Opcodes.INVOKEVIRTUAL
                val owner = owningType.runtimeName
                val descriptor = Type.getMethodDescriptor(method)
                writer.visitMethodInsn(opcode, owner, method.name, descriptor, false)
            },
            desiredReceiverFields = null
        )
    }

}

// Helper for classes and methods and w/e else

private fun <C: Annotation, T> AnnotatedElement.annotationOrElse(annotationClass: KClass<C>, defaultValue: T, getter: (C) -> T): T {
    if (this.isAnnotationPresent(annotationClass.java))
        return getter(this.getAnnotation(annotationClass.java))
    return defaultValue
}