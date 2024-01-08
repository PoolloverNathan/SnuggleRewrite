package representation.asts.ir

import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import representation.asts.typed.FieldDef
import representation.asts.typed.MethodDef
import representation.asts.typed.TypeDef
import util.ConsList

data class Program(
    // Types that have been used and generated by the program.
    val generatedTypes: List<GeneratedType>,
    // The top-level code of each file.
    val topLevelCode: Map<String, Instruction.CodeBlock>
)


sealed interface GeneratedType {
    val runtimeName: String
    data class GeneratedClass(override val runtimeName: String, val supertypeName: String,
                              val fields: List<GeneratedField>, val methods: List<GeneratedMethod>): GeneratedType
    data class GeneratedValueType(override val runtimeName: String,
                                  val fields: List<GeneratedField>, val methods: List<GeneratedMethod>): GeneratedType
}

@JvmInline value class GeneratedField(val fieldDef: FieldDef)
data class GeneratedMethod(val methodDef: MethodDef.SnuggleMethodDef, val body: Instruction.CodeBlock)

sealed interface Instruction {
    // A collection of other instructions
    data class CodeBlock(val instructions: ConsList<Instruction>): Instruction
    // Some raw bytecodes supplied by the enclosing program
    data class Bytecodes(val cost: Long, val bytecodes: (MethodVisitor) -> Unit): Instruction
    // Import the file of the given name
    data class RunImport(val fileName: String): Instruction

    // Call the given method
    sealed interface MethodCall: Instruction {
        val methodToCall: MethodDef
        val invokeBytecode: Int
        data class Virtual(override val methodToCall: MethodDef): MethodCall {
            override val invokeBytecode: Int get() = Opcodes.INVOKEVIRTUAL
        }
        data class Static(override val methodToCall: MethodDef): MethodCall {
            override val invokeBytecode: Int get() = Opcodes.INVOKESTATIC
        }
        data class Special(override val methodToCall: MethodDef): MethodCall {
            override val invokeBytecode: Int get() = Opcodes.INVOKESPECIAL
        }
    }

    // Push the given value onto the stack
    data class Push(val valueToPush: Any, val type: TypeDef): Instruction
    // Pop the given type from the top of the stack
    data class Pop(val typeToPop: TypeDef): Instruction
    // Create a new, uninitialized instance of the given type on the stack
    data class NewRefAndDup(val typeToCreate: TypeDef): Instruction

    // Store a value of the given type as a local variable at the given index
    data class StoreLocal(val index: Int, val type: TypeDef): Instruction
    // Load a value of the given type from a local variable at the given index
    data class LoadLocal(val index: Int, val type: TypeDef): Instruction
}