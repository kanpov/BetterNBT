
# BetterNBT

A lightweight (under 250 lines of code) Kotlin library for [Fabric](https://fabricmc.net) 1.18.x that allows intuitively working
with Minecraft NBT by reading & writing to a custom data structure.

## Install

**Step 1:** put this into your `gradle.properties`:

```properties
better_nbt_version=v1.0.0
```

**Step 2:** put this into your `build.gradle`:

```groovy
repositories {
    maven {
        url = "https://redgrapefruit09.github.io/maven"
        content {
            includeGroup "com.redgrapefruit.betternbt"
        }
    }
}
```

**Step 3:** put this in your `dependencies` block in the `build.gradle` after the last line:

```groovy
modImplementation "com.redgrapefruit.betternbt:betternbt:${project.better_nbt_version}"
include "com.redgrapefruit.betternbt:betternbt:${project.better_nbt_version}"
```

**Step 4**: refresh your Gradle project.

## Usage guide

In this tutorial we'll be creating a simple counter item that:

- Stores an integer counter in its NBT
- Increments that counter every tick
- Displays the counter in its tooltip

First, we need to create the class, from which a **schema** can be generated.
A schema is a description of all **nodes** (regular fields) and **sub-schemas** (nested compounds) in the structure of an NBT.\
The schema will be created from a class's set of _mutable properties_, and if a property has a **node serializer**,
it's a node, else it will be interpreted as a sub-schema.

Our class only holds the integer property, so it'll look like this:

```kotlin
class CounterItemData {
    var counter: Int = 0
    
    companion object {
        // Store the schema for this class, use the T::class.schema() method to get
        // a schema for your structure class
        val SCHEMA = CounterItemData::class.schema()
    }
}
```

Now we'll have to create our `Item` class, from which we'll make `useNbt` calls to work with our data
and increment the counter & display it in the tooltip:

```kotlin
class CounterItem : Item(Settings().group(ItemGroup.MISC)) {
    override fun inventoryTick(stack: ItemStack, world: World, entity: Entity, slot: Int, selected: Boolean) {
        useNbt<CounterItemData /* the serialized structure */>(
            stack.orCreateNbt /* the NBT tag */,
            CounterItemData.SCHEMA /* the schema */) { data /* the instance of your structure, which has the data read
            in and, if you mutate it, will be saved back to the given NBT tag */ ->

            data.counter++ // increment the counter
        }
    }

    override fun appendTooltip(
        stack: ItemStack,
        world: World?,
        tooltip: MutableList<Text>,
        context: TooltipContext
    ) {
        // repeat the call from above
        useNbt<CounterItemData>(stack.orCreateNbt, CounterItemData.SCHEMA) { data ->
            tooltip += LiteralText(data.counter.toString()) // add a literal text with the counter to the item's tooltip
        }
    }
}
```

Now register your item into the game ([see this tutorial](https://fabricmc.net/wiki/tutorial:items) if you don't know how
to do it) and you'll see a saved counter item working just fine!
