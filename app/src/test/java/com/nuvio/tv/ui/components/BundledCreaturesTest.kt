package com.nuvio.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 44a: creature slugs are stored in look entries, so the set is pinned (append only). */
class BundledCreaturesTest {

    @Test
    fun `the stored slugs never change`() {
        assertEquals(
            listOf(
                "fox", "panda", "koala", "tiger_face", "lion", "frog", "owl", "penguin", "unicorn", "dog_face",
                "cat_face", "monkey_face", "rabbit_face", "hamster", "dragon_face", "octopus", "mouse_face", "wolf",
                "hatching_chick", "pig_face", "bear", "cow_face", "horse_face", "boar", "polar_bear", "raccoon",
                "parrot", "flamingo", "turtle", "spouting_whale", "dolphin", "robot", "alien", "ghost",
                "alien_monster", "t_rex", "baby_chick", "orangutan", "peacock", "butterfly"
            ),
            BundledCreatures.all.map { it.slug }
        )
        assertEquals(BundledCreatures.all.size, BundledCreatures.bySlug.size)
    }
}
