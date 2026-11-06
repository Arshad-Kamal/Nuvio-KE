package com.nuvio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DpadComboTest {

    private val sequence = listOf(
        DpadDirection.UP, DpadDirection.DOWN, DpadDirection.LEFT,
        DpadDirection.RIGHT, DpadDirection.UP, DpadDirection.UP
    )

    @Test
    fun `length bounds are six through twelve`() {
        assertFalse(DpadCombo.isValidLength(5))
        (DpadCombo.MIN_LENGTH..DpadCombo.MAX_LENGTH).forEach {
            assertTrue("expected $it valid", DpadCombo.isValidLength(it))
        }
        assertFalse(DpadCombo.isValidLength(13))
        assertFalse(DpadCombo.isValidLength(0))
    }

    @Test
    fun `hash is stable for the same sequence and salt`() {
        val salt = DpadCombo.generateSalt()
        assertEquals(DpadCombo.hash(sequence, salt), DpadCombo.hash(sequence, salt))
        // 64 lowercase hex characters.
        assertEquals(64, DpadCombo.hash(sequence, salt).length)
        assertTrue(DpadCombo.hash(sequence, salt).matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `hash differs across sequences and salts`() {
        val salt = DpadCombo.generateSalt()
        val otherSequence = sequence.toMutableList().also { it[0] = DpadDirection.RIGHT }
        assertNotEquals(DpadCombo.hash(sequence, salt), DpadCombo.hash(otherSequence, salt))
        assertNotEquals(DpadCombo.hash(sequence, salt), DpadCombo.hash(sequence, DpadCombo.generateSalt()))
    }

    @Test
    fun `salts are unique and well-formed`() {
        val salts = (1..8).map { DpadCombo.generateSalt() }.toSet()
        assertEquals(8, salts.size)
        salts.forEach { assertTrue(it.matches(Regex("[0-9a-f]{32}"))) }
    }

    @Test
    fun `verify accepts the saved sequence and rejects everything else`() {
        val salt = DpadCombo.generateSalt()
        val hash = DpadCombo.hash(sequence, salt)
        assertTrue(DpadCombo.verify(sequence, salt, hash))
        // Same length, one direction changed.
        val wrongSequence = sequence.toMutableList().also { it[3] = DpadDirection.LEFT }
        assertFalse(DpadCombo.verify(wrongSequence, salt, hash))
        // Wrong length: shorter and longer than the stored sequence.
        assertFalse(DpadCombo.verify(sequence.dropLast(1), salt, hash))
        assertFalse(DpadCombo.verify(sequence + DpadDirection.RIGHT, salt, hash))
        // Wrong salt.
        assertFalse(DpadCombo.verify(sequence, DpadCombo.generateSalt(), hash))
    }

    @Test
    fun `verify rejects empty salt or hash instead of hashing`() {
        val salt = DpadCombo.generateSalt()
        val hash = DpadCombo.hash(sequence, salt)
        assertFalse(DpadCombo.verify(sequence, "", hash))
        assertFalse(DpadCombo.verify(sequence, salt, ""))
    }

    @Test
    fun `similar sequences do not collide`() {
        val salt = DpadCombo.generateSalt()
        val a = listOf(
            DpadDirection.UP, DpadDirection.DOWN, DpadDirection.UP,
            DpadDirection.DOWN, DpadDirection.UP, DpadDirection.DOWN
        )
        val b = listOf(
            DpadDirection.DOWN, DpadDirection.UP, DpadDirection.DOWN,
            DpadDirection.UP, DpadDirection.DOWN, DpadDirection.UP
        )
        assertNotEquals(DpadCombo.hash(a, salt), DpadCombo.hash(b, salt))
    }
}
