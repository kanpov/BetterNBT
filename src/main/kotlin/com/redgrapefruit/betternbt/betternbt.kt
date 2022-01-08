@file:JvmName("BetterNBT") // prettier facade class name in crash logs

package com.redgrapefruit.betternbt

import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.hasAnnotation

// Internal Utils

@PublishedApi internal val betterNbtLogger: Logger = LogManager.getLogger("BetterNBT")

internal fun NbtCompound.getOrCreateSubNbt(key: String): NbtCompound {
    return if (contains(key)) {
        getCompound(key)
    } else {
        val created = NbtCompound()
        put(key, created)
        created
    }
}

internal fun assertDepth(depth: Int) {
    if (depth > MAX_NBT_DEPTH) {
        throw RuntimeException("Tried to operate on NBT with a depth larger than $MAX_NBT_DEPTH.")
    }
}

// Node Serializer API

/**
 * A [NodeSerializer] provides an interface for reading and writing a set data type from & to an NBT.
 *
 * There are several built-in [NodeSerializer]s provided by the library for:
 *
 * - [Byte]s
 * - [Short]s
 * - [Int]s
 * - [Long]s
 * - [Float]s
 * - [Double]s
 * - [String]s
 * - [ByteArray]s
 * - [IntArray]s
 * - [Boolean]s
 * - [java.util.UUID]s
 *
 * You **don't need a [NodeSerializer] if the type can be broken down into other nodes!**, e.g. is a _composite type_.
 *
 * If you are creating a custom [NodeSerializer], you must **register** it into the system with the [NodeSerializer.register]
 * extension or the [registerNodeSerializer] function. There, you must also provide the [KClass] of the type that this [NodeSerializer]
 * handles.
 *
 * It is preferred that custom [NodeSerializer]s are made into Kotlin `object`s for convenience.
 */
interface NodeSerializer<T : Any> {
    /**
     * Read the data from NBT into a given type.
     *
     * @return The decoded non-nullable type.
     */
    fun readNbt(
        /** The NBT to read from. */
        nbt: NbtCompound,
        /** The key that has the data you're meant to read. */
        key: String): T

    /**
     * Write the data from a runtime type to NBT.
     */
    fun writeNbt(
        /** The NBT to write to. */
        nbt: NbtCompound,
        /** The key in the NBT you're meant to write to. */
        key: String,
        /** The value you need to write. */
        value: T)

    companion object {
        private inline fun <reified T : Any> create(noinline r: NodeReadFunction<T>, noinline w: NodeWriteFunction<T>): NodeSerializer<T> {
            val serializer = BuiltinNodeSerializer(r, w)
            registerNodeSerializer(T::class, serializer)
            return serializer
        }

        internal fun createBuiltinSerializers() {
            create(NbtCompound::getByte, NbtCompound::putByte)
            create(NbtCompound::getShort, NbtCompound::putShort)
            create(NbtCompound::getInt, NbtCompound::putInt)
            create(NbtCompound::getLong, NbtCompound::putLong)
            create(NbtCompound::getFloat, NbtCompound::putFloat)
            create(NbtCompound::getDouble, NbtCompound::putDouble)
            create(NbtCompound::getString, NbtCompound::putString)
            create(NbtCompound::getByteArray, NbtCompound::putByteArray)
            create(NbtCompound::getIntArray, NbtCompound::putIntArray)
            create(NbtCompound::getBoolean, NbtCompound::putBoolean)
            create(NbtCompound::getUuid, NbtCompound::putUuid)
        }
    }
}

internal fun <T : Any> NodeSerializer<T>.readNbtUnsafe(nbt: NbtCompound, key: String): Any {
    return readNbt(nbt, key)
}

internal fun <T : Any> NodeSerializer<T>.writeNbtUnsafe(nbt: NbtCompound, key: String, value: Any) {
    writeNbt(nbt, key, value as? T ?: throw RuntimeException("Unsafe cast failed"))
}

private typealias NodeReadFunction<T> = (NbtCompound, String) -> T
private typealias NodeWriteFunction<T> = (NbtCompound, String, T) -> Unit

internal class BuiltinNodeSerializer<T : Any>(
    private val readFunction: NodeReadFunction<T>,
    private val writeFunction: NodeWriteFunction<T>
) : NodeSerializer<T> {

    override fun readNbt(nbt: NbtCompound, key: String): T {
        return readFunction.invoke(nbt, key)
    }

    override fun writeNbt(nbt: NbtCompound, key: String, value: T) {
        writeFunction.invoke(nbt, key, value)
    }
}

@PublishedApi internal val nodeSerializerRegistry: MutableMap<KClass<*>, NodeSerializer<*>> = mutableMapOf()

/**
 * Registers a [NodeSerializer] into the system.
 *
 * In case you're trying to register a [NodeSerializer] for a type/[KClass] that already has one, there are two
 * potential outcomes:
 *
 * - If the [shouldOverwrite] flag is set to `false` (which is by default), a warning will be logged into the console
 * and the registering operation will be cancelled
 * - If you set [shouldOverwrite] to `true`, a message notifying about overwriting will be logged, and the already
 * existing [NodeSerializer] will be overwritten with the one you passed.
 */
fun <T : Any> registerNodeSerializer(
    /** The [KClass] of the type that the [NodeSerializer] handles. */
    clazz: KClass<T>,
    /** The instance of the [NodeSerializer]. */
    nodeSerializer: NodeSerializer<T>,
    /** Determines the behavior if trying to register a duplicate [NodeSerializer]. See KDoc above for more details. */
    shouldOverwrite: Boolean = false) {

    if (nodeSerializerRegistry.contains(clazz)) {
        if (shouldOverwrite) {
            betterNbtLogger.info("Overwriting an already existing NodeSerializer for ${clazz.fullName}.")
            nodeSerializerRegistry[clazz] = nodeSerializer
            return
        } else {
            betterNbtLogger.warn("Tried to register a duplicate NodeSerializer for ${clazz.fullName}. Enable the shouldOverwrite flag to ignore and overwrite the already existing value.")
            return
        }
    }

    nodeSerializerRegistry[clazz] = nodeSerializer
}

/**
 * An overload of [registerNodeSerializer] that uses the passed reified generic to determine the [KClass] of the
 * [NodeSerializer]'s handled type.
 */
inline fun <reified T : Any> registerNodeSerializer(nodeSerializer: NodeSerializer<T>, shouldOverwrite: Boolean = false) {
    registerNodeSerializer(T::class, nodeSerializer, shouldOverwrite)
}

/**
 * An extension for [NodeSerializer] that registers it to the [KClass] of the passed generic.
 *
 * Calls [registerNodeSerializer] under the hood, see its KDoc for more.
 */
inline fun <reified T : Any> NodeSerializer<T>.register(
    /** Overwrite behavior for scenarios where you're trying to register a duplicate [NodeSerializer]. */
    shouldOverwrite: Boolean = false) {

    registerNodeSerializer(T::class, this, shouldOverwrite)
}

inline val KClass<*>.fullName: String
    get() = qualifiedName ?: "unknown anonymous or local type"

// Schema API

/**
 * A [Schema] contains a description of your NBT data, in 2 parts:
 *
 * - **Nodes** are regular fields/keys in the NBT, that are serialized with an associated [NodeSerializer].
 * - **Sub-schemas** are [Schema]s nested in this [Schema], which are serialized by breaking them down with reflection.
 *
 * This description is inherently linked to a `class` or a `data class`, and for every node and sub-schema,
 * a [KMutableProperty1] is held, which will be accessed and mutated to read and write the data according to this
 * [Schema].
 *
 * **You cannot create [Schema]s yourself,** use the [KClass.schema] extension or the [createSchema] function.
 */
data class Schema internal constructor(
    /** A map of node keys to their associated [NodeSerializer]s. */
    val nodes: Map<String, NodeSerializer<*>>,
    /** A map of sub schema keys to the schemas themselves. */
    val schemas: Map<String, Schema>,
    /** The linked reflection properties for every node in the [nodes] map. */
    val nodeLinks: Map<String, KMutableProperty1<*, *>>,
    /** The linked sub-schema properties for every sub-schema in the [schemas] map. */
    val schemaLinks: Map<String, KMutableProperty1<*, *>>
)

private const val MAX_NBT_DEPTH = 256

@PublishedApi
internal fun createSchemaInternal(clazz: KClass<*>, depth: Int = 1): Schema {
    assertDepth(depth)

    if (nodeSerializerRegistry.isEmpty()) NodeSerializer.createBuiltinSerializers()

    val nodes: MutableMap<String, NodeSerializer<*>> = mutableMapOf()
    val schemas: MutableMap<String, Schema> = mutableMapOf()
    val nodeLinks: MutableMap<String, KMutableProperty1<*, *>> = mutableMapOf()
    val schemaLinks: MutableMap<String, KMutableProperty1<*, *>> = mutableMapOf()

    for (property in clazz.declaredMemberProperties) {
        if (property.hasAnnotation<IgnoreInSchema>() || property !is KMutableProperty1) continue

        val propertyClazz = property.returnType.classifier as? KClass<*>

        if (propertyClazz == null) {
            betterNbtLogger.warn("Could not resolve class of property: ${property.name}")
            continue
        }

        if (nodeSerializerRegistry.contains(propertyClazz)) {
            nodes[property.name] = nodeSerializerRegistry[propertyClazz]!!
            nodeLinks[property.name] = property
        } else {
            val nestedSchema = createSchemaInternal(propertyClazz, depth + 1)
            schemas[property.name] = nestedSchema
            schemaLinks[property.name] = property
        }
    }

    return Schema(nodes, schemas, nodeLinks, schemaLinks)
}

/**
 * Use this annotation on a `var`/mutable property in your schematic class (a class, from which a [Schema] is generated)
 * to ignore this property as a node/sub-schema, e.g. it won't be serialized.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class IgnoreInSchema

/**
 * Generates a [Schema] from this [KClass], for example:
 *
 * ```kotlin
 * val mySchema = MyDataClass::class.schema()
 * ```
 */
fun KClass<*>.schema(): Schema {
    return createSchemaInternal(this) ?: throw RuntimeException("Failed to generate schema for ${this.fullName}")
}

/**
 * Generates a [Schema] from the [KClass] of the [T] reified generic.
 */
inline fun <reified T> createSchema(): Schema = T::class.schema()

// Serialization Impl

@PublishedApi internal fun readFromNbt(nbt: NbtCompound, schema: Schema, obj: Any, depth: Int = 1) {
    assertDepth(depth)

    schema.nodes.forEach { (key, serializer) ->
        val property = schema.nodeLinks[key] ?: throw RuntimeException("Node link not found for $key.")
        val value = serializer.readNbtUnsafe(nbt, key)
        property.setter.call(obj, value)
    }

    schema.schemas.forEach { (key, subSchema) ->
        val property = schema.schemaLinks[key] ?: throw RuntimeException("Schema link not found for $key.")
        val subNbt = nbt.getOrCreateSubNbt(key)

        var value: Any? = property.getter.call(obj)

        if (value == null) {
            val propertyClazz = property.returnType.classifier as? KClass<*> ?: throw RuntimeException("Could not obtain KClass of property's return type.")
            value = propertyClazz.createInstance()
        }

        readFromNbt(subNbt, subSchema, value, depth + 1)
        property.setter.call(obj, value)
    }
}

@PublishedApi internal fun writeToNbt(nbt: NbtCompound, schema: Schema, obj: Any, depth: Int = 1) {
    assertDepth(depth)

    schema.nodes.forEach { (key, serializer) ->
        val property = schema.nodeLinks[key] ?: throw RuntimeException("Node link not found for $key.")
        val value = property.getter.call(obj) ?: throw RuntimeException("Value not found for $key.")
        serializer.writeNbtUnsafe(nbt, key, value)
    }

    schema.schemas.forEach { (key, subSchema) ->
        val property = schema.schemaLinks[key] ?: throw RuntimeException("Schema link not found for $key.")
        val subNbt = nbt.getOrCreateSubNbt(key)
        val value = property.getter.call(obj) ?: throw RuntimeException("Value not found for $key.")
        writeToNbt(subNbt, subSchema, value, depth + 1)
    }
}

// Core Serialization API

inline fun <reified T : Any> useNbt(nbt: NbtCompound, schema: Schema, action: (T) -> Unit) {
    val obj = T::class.createInstance()

    readFromNbt(nbt, schema, obj)
    action.invoke(obj)
    writeToNbt(nbt, schema, obj)
}

@PublishedApi internal val schemaCache: MutableMap<KClass<out Any>, Schema> = mutableMapOf()

inline fun <reified T : Any> useNbt(nbt: NbtCompound, action: (T) -> Unit) {
    val clazz = T::class
    if (!schemaCache.contains(clazz)) schemaCache[clazz] = clazz.schema()

    useNbt(nbt, schemaCache[clazz]!!, action)
}

inline fun <reified T : Any> NbtCompound.use(action: (T) -> Unit) {
    useNbt(this, action)
}

// Serialization Adapters (Items, etc.)

inline fun <reified T : Any> ItemStack.useNbt(schema: Schema, action: (T) -> Unit) {
    useNbt(orCreateNbt, schema, action)
}

inline fun <reified T : Any> ItemStack.useNbt(action: (T) -> Unit) {
    useNbt(orCreateNbt, action)
}
