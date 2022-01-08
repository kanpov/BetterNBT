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

interface NodeSerializer<T : Any> {
    fun readNbt(nbt: NbtCompound, key: String): T

    fun writeNbt(nbt: NbtCompound, key: String, value: T)

    companion object {
        private inline fun <reified T : Any> create(noinline r: NodeReadFunction<T>, noinline w: NodeWriteFunction<T>): NodeSerializer<T> {
            val serializer = NodeSerializerImpl(r, w)
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

internal class NodeSerializerImpl<T : Any>(
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

inline fun <reified T : Any> registerNodeSerializer(clazz: KClass<T>, nodeSerializer: NodeSerializer<T>, shouldOverwrite: Boolean = false) {
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

inline fun <reified T : Any> NodeSerializer<T>.register() {
    registerNodeSerializer(T::class, this)
}

inline val KClass<*>.fullName: String
    get() = qualifiedName ?: "unknown anonymous or local type"

// Schema API

data class Schema internal constructor(
    val nodes: Map<String, NodeSerializer<*>>,
    val schemas: Map<String, Schema>,
    val nodeLinks: Map<String, KMutableProperty1<*, *>>,
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

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class IgnoreInSchema

fun KClass<*>.schema(): Schema {
    return createSchemaInternal(this) ?: throw RuntimeException("Failed to generate schema for ${this.fullName}")
}

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
