package representation.passes.lowering

import representation.asts.ir.GeneratedType
import representation.asts.ir.Instruction
import representation.asts.typed.FieldDef
import representation.asts.typed.MethodDef
import representation.asts.typed.TypeDef
import representation.asts.typed.TypedExpr
import representation.passes.typing.isFallible
import util.Cons
import util.ConsList
import util.caching.IdentityIncrementalCalculator

/**
 * Lowering an expression into IR.
 *
 * desiredFields is a concept related to the implementation of fields of plural types.
 *
 * If you have a plural type, like a tuple, stored as a local variable, the implementation
 * in the runtime is to instead have multiple local variables, one for each element
 * of the tuple. (This recurses if an element of the tuple is also a plural type).
 * Say, for instance, you have a field-get expression ((x).y).z, where x is a local variable
 * with plural type, y is a field of x that has a plural type, and z is a field of that
 * plural type. The progression of the calls would be as follows:
 * - lowerExpr "((x).y).z", desiredFields = []
 * - lowerExpr "(x).y", desiredFields = ["z"]
 * - lowerExpr "x", desiredFields = ["y", "z"]
 * Then, the handler for "local variable" (which is what x is) will take
 * the desired fields into account, and select the correct backing field.
 * In short:
 * DesiredFields are generated by Field* expressions, and consumed by other expressions.
 */

// I hope these "sequence {}" blocks aren't slow since they're really convenient
fun lowerExpr(expr: TypedExpr, desiredFields: ConsList<FieldDef>, typeCalc: IdentityIncrementalCalculator<TypeDef, GeneratedType>): Sequence<Instruction> = when (expr) {
    // Just a RunImport
    is TypedExpr.Import -> sequenceOf(Instruction.RunImport(expr.file))
    // Sequence the operations inside the block
    is TypedExpr.Block -> sequence {
        for (i in 0 until expr.exprs.size - 1) {
            yieldAll(lowerExpr(expr.exprs[i], ConsList.nil(), typeCalc))
            yield(Instruction.Pop(expr.exprs[i].type))
        }
        yieldAll(lowerExpr(expr.exprs.last(), desiredFields, typeCalc))
    }
    // What to do for a declaration depends on the type of pattern
    is TypedExpr.Declaration -> sequence {
        // Yield the things
        if (isFallible(expr.pattern)) {
            TODO()
        } else {
            // Push the initializer
            yieldAll(lowerExpr(expr.initializer, ConsList.nil(), typeCalc))
            // Apply the pattern
            yieldAll(lowerPattern(expr.pattern, typeCalc))
//            // Store/bind local variable
//            yield(Instruction.StoreLocal(expr.variableIndex, expr.pattern.type))
            // Push true
            yield(Instruction.Push(true, expr.type))
        }
    }

    // Extracted to special method, since it's complex
    is TypedExpr.Assignment -> handleAssignment(expr.lhs, expr.rhs, ConsList.nil(), expr.maxVariable, typeCalc)

    // Load the local variable
    is TypedExpr.Variable -> sequenceOf(Instruction.LoadLocal(
        expr.variableIndex + getPluralOffset(desiredFields),
        desiredFields.lastOrNull()?.type ?: expr.type
    ))

    // Push the literal
    is TypedExpr.Literal -> sequenceOf(Instruction.Push(expr.value, expr.type))

    is TypedExpr.FieldAccess -> sequence {
        if (expr.receiver.type.isReferenceType) {
            // If the receiver is a reference type, then
            // we don't need to pass the fields further down.
            // Instead, we handle them all here.
            // Compile the receiver: (which yields a reference type on the stack)
            yieldAll(lowerExpr(expr.receiver, ConsList.nil(), typeCalc))
            // Now, we follow back up the list of desired fields.
            val namePrefix = desiredFields.fold(expr.fieldName) {prefix, field -> prefix + "$" + field.name}
            // If the last field in the chain is plural, then we need multiple operations; otherwise just one.
            val lastField = desiredFields.lastOrNull() ?: expr.fieldDef
            if (lastField.type.isPlural) {
                // If the last field in the chain is plural, store the reference type
                // as a top local variable, then repeatedly grab it and GetReferenceTypeField.
                yield(Instruction.StoreLocal(expr.maxVariable, expr.receiver.type))
                for ((pathToField, field) in lastField.type.recursiveNonStaticFields) {
                    yield(Instruction.LoadLocal(expr.maxVariable, expr.receiver.type))
                    yield(Instruction.GetReferenceTypeField(expr.receiver.type, field.type, namePrefix + "$" + pathToField))
                }
            } else {
                // If not, then simply grab the field with the name prefix.
                yield(Instruction.GetReferenceTypeField(expr.receiver.type, lastField.type, namePrefix))
            }
        } else if (expr.receiver.type.isPlural) {
            // Otherwise, if the receiver is not a reference type,
            // but is instead plural, we will pass the problem
            // down a level, by consing a new field def onto the desired fields.
            yieldAll(lowerExpr(expr.receiver, Cons(expr.fieldDef, desiredFields), typeCalc))
        } else {
            // Any type that has accessible fields should either be a reference
            // type, or plural. This is a bug!
            throw IllegalStateException("Types with accessible fields should be either reference types or plural - " +
                    "but type \"${expr.receiver.type.name}\" is neither! Whoever implemented this type has made " +
                    "a mistake. Please contact them!")
        }
    }
    is TypedExpr.StaticFieldAccess -> sequence {
        // Similar code to the "if reference type" branch of the regular field access.
        val namePrefix = desiredFields.fold(expr.fieldName) {prefix, field -> prefix + "$" + field.name}
        val lastField = desiredFields.lastOrNull() ?: expr.fieldDef
        if (lastField.type.isPlural) {
            for ((pathToField, field) in lastField.type.recursiveNonStaticFields)
                yield(Instruction.GetStaticField(expr.receiverType, field.type, namePrefix + "$" + pathToField))
        } else {
            yield(Instruction.GetStaticField(expr.receiverType, lastField.type, lastField.name))
        }
    }

    // Compile arguments, make call
    is TypedExpr.MethodCall -> sequence {
        yieldAll(lowerExpr(expr.receiver, ConsList.nil(), typeCalc))
        for (arg in expr.args)
            yieldAll(lowerExpr(arg, ConsList.nil(), typeCalc))
        if (expr.methodDef.owningType.unwrap() is TypeDef.ClassDef)
            yieldAll(createCall(expr.methodDef, desiredFields) { Instruction.MethodCall.Virtual(it) })
        else
            yieldAll(createCall(expr.methodDef, desiredFields) { Instruction.MethodCall.Static(it) })
    }
    is TypedExpr.StaticMethodCall -> sequence {
        lowerTypeDef(expr.receiverType, typeCalc)
        for (arg in expr.args)
            yieldAll(lowerExpr(arg, ConsList.nil(), typeCalc))
        yieldAll(createCall(expr.methodDef, desiredFields) { Instruction.MethodCall.Static(it) })
    }
    is TypedExpr.SuperMethodCall -> sequence {
        // Load "this" on the stack
        yield(Instruction.LoadRefType(expr.thisVariableIndex))
        // Push args
        for (arg in expr.args)
            yieldAll(lowerExpr(arg, ConsList.nil(), typeCalc))
        // Create call
        yieldAll(createCall(expr.methodDef, desiredFields) { Instruction.MethodCall.Special(it) })
    }
    is TypedExpr.ClassConstructorCall -> sequence {
        lowerTypeDef(expr.type, typeCalc)
        // Push and dup the receiver
        yield(Instruction.NewRefAndDup(expr.type))
        // Push args
        for (arg in expr.args)
            yieldAll(lowerExpr(arg, ConsList.nil(), typeCalc))
        yieldAll(createCall(expr.methodDef, desiredFields) { Instruction.MethodCall.Special(it) })
    }
    is TypedExpr.RawStructConstructor -> sequence {
        expr.type.nonStaticFields.zip(expr.fieldValues).forEach { (field, value) ->
            if (desiredFields is Cons && desiredFields.elem == field) {
                // We desire this field specifically, so lower it
                yieldAll(lowerExpr(value, desiredFields.rest, typeCalc))
            } else {
                // Yield the field value
                yieldAll(lowerExpr(value, ConsList.nil(), typeCalc))
                // If we desire something specific, but this is not it, then pop the value
                if (desiredFields is Cons)
                    yield(Instruction.Pop(value.type))
            }
        }
    }

    is TypedExpr.Return -> sequence {
        // Yield the RHS, pushing it on the stack
        yieldAll(lowerExpr(expr.rhs, ConsList.nil(), typeCalc))
        val returnType = expr.rhs.type
        // If it's plural, decompose into static field writes and one return.
        if (returnType.isPlural) {
            // Empty plural type: Just return void (null)
            if (returnType.recursiveNonStaticFields.isEmpty())
                yield(Instruction.Return(null))
            else {
                // The code here closely mirrors the code for getMethodResults.
                // This is where the static fields are STORED, and in getMethodResults,
                // they're READ.
                val namePrefix = "RETURN! "
                returnType.recursiveNonStaticFields.asReversed().dropLast(1).forEach { (pathToField, field) ->
                    // Put all except the first into static fields.
                    yield(Instruction.PutStaticField(returnType, field.type, namePrefix + "$" + pathToField))
                }
                // Return the last.
                yield(Instruction.Return(returnType.recursiveNonStaticFields.first().second.type))
            }
        } else {
            // If return type is not plural, just return normally.
            yield(Instruction.Return(returnType))
        }
    }
}

private fun getPluralOffset(fieldsToFollow: ConsList<FieldDef>): Int = fieldsToFollow.sumOf { it.pluralOffset!! }

private inline fun createCall(
    methodDef: MethodDef, desiredFields: ConsList<FieldDef>,
    crossinline snuggleCallType: (MethodDef.SnuggleMethodDef) -> Instruction.MethodCall
): Sequence<Instruction> {
    return when (methodDef) {
        is MethodDef.BytecodeMethodDef -> sequenceOf(Instruction.Bytecodes(0, methodDef.bytecode)) //TODO: Cost
        is MethodDef.InterfaceMethodDef -> sequenceOf(Instruction.MethodCall.Interface(methodDef))
        // Invoke according to the snuggle call type
        is MethodDef.SnuggleMethodDef -> sequence {
            yield(snuggleCallType(methodDef))
            yieldAll(getMethodResults(methodDef, desiredFields))
        }
        is MethodDef.ConstMethodDef,
        is MethodDef.StaticConstMethodDef -> throw IllegalStateException("Cannot lower const method def - bug in compiler, please report")
        is MethodDef.GenericMethodDef<*> -> throw IllegalStateException("Cannot lower generic method call - bug in compiler, please report")
    }
}

/**
 * This handles fetching method results after a method call is made.
 * In most cases, the return is just a single value, and this doesn't
 * need to do anything.
 *
 * However, when the return value is a plural type with more than 1
 * element, this will need to grab most of them out of their static variables in the end.
 */
private fun getMethodResults(methodToCall: MethodDef, desiredFields: ConsList<FieldDef>): Sequence<Instruction> {
    return if (methodToCall.returnType.isPlural && methodToCall.returnType.recursiveNonStaticFields.size > 1) {
        val namePrefix = desiredFields.fold("RETURN! ") {prefix, field -> prefix + "$" + field.name}
        val lastDesiredType = desiredFields.lastOrNull()?.type ?: methodToCall.returnType
        sequence {
            // If we have desired fields, and the first element of recursive nonstatic fields isn't one of them, then
            // pop it off the stack
            if (desiredFields is Cons && desiredFields.elem === methodToCall.returnType.nonStaticFields.first { it.type.stackSlots > 0 })
                yield(Instruction.Pop(methodToCall.returnType.recursiveNonStaticFields[0].second.type))
            // Grab whichever static fields we need off the stack
            lastDesiredType.recursiveNonStaticFields.asSequence().drop(1).forEach { (pathToField, field) ->
                yield(Instruction.GetStaticField(methodToCall.returnType, field.type, namePrefix + "$" + pathToField))
            }
        }
    } else sequenceOf()
}

/**
 * Handle assignment to an lvalue, recursively (plural field accesses, etc)
 *
 * fieldsToFollow starts as nil.
 * assignmentType is the type being assigned to the lvalue.
 */
fun handleAssignment(lhs: TypedExpr, rhs: TypedExpr, fieldsToFollow: ConsList<FieldDef>, maxVariable: Int, typeCalc: IdentityIncrementalCalculator<TypeDef, GeneratedType>): Sequence<Instruction> = when {
    lhs is TypedExpr.Variable -> sequence {
        yieldAll(lowerExpr(rhs, ConsList.nil(), typeCalc))
        yield(Instruction.StoreLocal(lhs.variableIndex + getPluralOffset(fieldsToFollow), rhs.type))
    }

    lhs is TypedExpr.StaticFieldAccess -> sequence {
        val nameSuffix = fieldsToFollow.fold(lhs.fieldName) { suffix, field -> suffix + "$" + field.name }
        if (rhs.type.isPlural) {
            for ((pathToField, field) in rhs.type.recursiveNonStaticFields.asReversed())
                yield(Instruction.PutStaticField(lhs.receiverType, field.type, pathToField + "$" + nameSuffix))
        } else {
            yield(Instruction.PutStaticField(lhs.receiverType, rhs.type, lhs.fieldName))
        }
    }

    lhs is TypedExpr.FieldAccess && lhs.receiver.type.isReferenceType -> sequence {
        yieldAll(lowerExpr(lhs.receiver, ConsList.nil(), typeCalc))
        val nameSuffix = fieldsToFollow.fold(lhs.fieldName) { suffix, field -> suffix + "$" + field.name }
        if (rhs.type.isPlural) {
            // Store the reference type as a local, to grab later
            yield(Instruction.StoreLocal(maxVariable, lhs.receiver.type))
            // Emit the RHS onto the stack
            yieldAll(lowerExpr(rhs, ConsList.nil(), typeCalc))
            // For each field, store it
            for ((pathToField, field) in rhs.type.recursiveNonStaticFields.asReversed()) {
                yield(Instruction.LoadLocal(maxVariable, lhs.receiver.type))
                yield(Instruction.SwapBasic(lhs.receiver.type, field.type))
                yield(Instruction.PutReferenceTypeField(lhs.receiver.type, field.type, pathToField + "$" + nameSuffix))
            }
        } else {
            // Just set the field directly
            yieldAll(lowerExpr(rhs, ConsList.nil(), typeCalc))
            yield(Instruction.PutReferenceTypeField(lhs.receiver.type, rhs.type, nameSuffix))
        }
    }

    // Recursive case: The LHS is a field access, but its receiver is not a reference type.
    lhs is TypedExpr.FieldAccess -> handleAssignment(lhs.receiver, rhs, Cons(lhs.fieldDef, fieldsToFollow), maxVariable, typeCalc)

    else -> throw IllegalStateException("Illegal assignment case - bug in compiler, please report")
}