package com.nuvio.tv.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.LocalLookSets

/**
 * A bundled avatar (Spec 44 D1): a Microsoft Fluent Emoji 3D head (MIT, licence text in
 * `assets/licenses/fluentui-emoji-LICENSE.txt`), a transparent 256 px WebP drawn on a disc in
 * [colorHex]. [slug] is stored in profile look entries: never rename or reuse one.
 */
@Immutable
data class BundledCreature(
    val slug: String,
    val displayName: String,
    @param:DrawableRes val drawable: Int,
    val colorHex: String
)

/** The Creatures category (D1), in picker order; the first 22 colours are the approved mockup's. */
object BundledCreatures {
    val all: List<BundledCreature> = listOf(
        BundledCreature("fox", "Fox", R.drawable.creature_fox, "#E8743B"),
        BundledCreature("panda", "Panda", R.drawable.creature_panda, "#5B6B7A"),
        BundledCreature("koala", "Koala", R.drawable.creature_koala, "#6F8FA6"),
        BundledCreature("tiger_face", "Tiger", R.drawable.creature_tiger_face, "#D9822B"),
        BundledCreature("lion", "Lion", R.drawable.creature_lion, "#C99A2E"),
        BundledCreature("frog", "Frog", R.drawable.creature_frog, "#4C9A4A"),
        BundledCreature("owl", "Owl", R.drawable.creature_owl, "#8D6E63"),
        BundledCreature("penguin", "Penguin", R.drawable.creature_penguin, "#3F5B8A"),
        BundledCreature("unicorn", "Unicorn", R.drawable.creature_unicorn, "#9C6ADE"),
        BundledCreature("dog_face", "Dog", R.drawable.creature_dog_face, "#B07A4F"),
        BundledCreature("cat_face", "Cat", R.drawable.creature_cat_face, "#E0A030"),
        BundledCreature("monkey_face", "Monkey", R.drawable.creature_monkey_face, "#9A6B4B"),
        BundledCreature("rabbit_face", "Rabbit", R.drawable.creature_rabbit_face, "#C57BA0"),
        BundledCreature("hamster", "Hamster", R.drawable.creature_hamster, "#D08A5C"),
        BundledCreature("dragon_face", "Dragon", R.drawable.creature_dragon_face, "#2E9E83"),
        BundledCreature("octopus", "Octopus", R.drawable.creature_octopus, "#D2557A"),
        BundledCreature("mouse_face", "Mouse", R.drawable.creature_mouse_face, "#8A8FA8"),
        BundledCreature("wolf", "Wolf", R.drawable.creature_wolf, "#607080"),
        BundledCreature("hatching_chick", "Chick", R.drawable.creature_hatching_chick, "#D9B33A"),
        BundledCreature("pig_face", "Pig", R.drawable.creature_pig_face, "#D9708A"),
        BundledCreature("bear", "Bear", R.drawable.creature_bear, "#8B5A3C"),
        BundledCreature("cow_face", "Cow", R.drawable.creature_cow_face, "#7A8A6A"),
        BundledCreature("horse_face", "Horse", R.drawable.creature_horse_face, "#8B5E3C"),
        BundledCreature("boar", "Boar", R.drawable.creature_boar, "#8D5A4A"),
        BundledCreature("polar_bear", "Polar bear", R.drawable.creature_polar_bear, "#7A9CB8"),
        BundledCreature("raccoon", "Raccoon", R.drawable.creature_raccoon, "#6B7280"),
        BundledCreature("parrot", "Parrot", R.drawable.creature_parrot, "#CB4D55"),
        BundledCreature("flamingo", "Flamingo", R.drawable.creature_flamingo, "#D14780"),
        BundledCreature("turtle", "Turtle", R.drawable.creature_turtle, "#4C9A6A"),
        BundledCreature("spouting_whale", "Whale", R.drawable.creature_spouting_whale, "#4791D1"),
        BundledCreature("dolphin", "Dolphin", R.drawable.creature_dolphin, "#47A0D1"),
        BundledCreature("robot", "Robot", R.drawable.creature_robot, "#5C7FA3"),
        BundledCreature("alien", "Alien", R.drawable.creature_alien, "#6CBD5A"),
        BundledCreature("ghost", "Ghost", R.drawable.creature_ghost, "#8A7FC0"),
        BundledCreature("alien_monster", "Space invader", R.drawable.creature_alien_monster, "#6D56A1"),
        BundledCreature("t_rex", "T-rex", R.drawable.creature_t_rex, "#43A87A"),
        BundledCreature("baby_chick", "Baby chick", R.drawable.creature_baby_chick, "#D19D47"),
        BundledCreature("orangutan", "Orangutan", R.drawable.creature_orangutan, "#D07045"),
        BundledCreature("peacock", "Peacock", R.drawable.creature_peacock, "#3E9C8F"),
        BundledCreature("butterfly", "Butterfly", R.drawable.creature_butterfly, "#AC4A55")
    )

    val bySlug: Map<String, BundledCreature> = all.associateBy { it.slug }
}

/** The bundled sets local looks resolve against; [LocalLookLibrary] adds the current My photos. */
val BundledLookSets = LocalLookSets(
    photoIds = emptySet(),
    bundledAvatars = BundledCreatures.bySlug.keys,
    bundledBackgrounds = BundledBackgrounds.bySlug.keys
)
