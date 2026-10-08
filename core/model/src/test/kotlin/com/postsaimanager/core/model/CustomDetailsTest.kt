package com.postsaimanager.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The user's own named details: how they are stored, searched and edited (add, edit, delete, reorder). */
class CustomDetailsTest {

    private val customer = CustomDetail("Customer number", "4711")
    private val hours = CustomDetail("Opening hours", "Mon-Fri 8-12")
    private val room = CustomDetail("Room", "2.14")

    @Test
    fun `details survive being stored, in order, with any text`() {
        val details = listOf(customer, CustomDetail("Notiz \"quoted\"", "Straße ß, العربية"), hours)

        assertThat(CustomDetails.decode(CustomDetails.encode(details))).containsExactlyElementsIn(details).inOrder()
    }

    @Test
    fun `no details are no stored text, and an unreadable text is no details`() {
        assertThat(CustomDetails.encode(emptyList())).isNull()
        assertThat(CustomDetails.decode(null)).isEmpty()
        assertThat(CustomDetails.decode("")).isEmpty()
        assertThat(CustomDetails.decode("{not json")).isEmpty()
    }

    @Test
    fun `add appends, replace edits one, remove deletes one`() {
        val added = CustomDetails.add(listOf(customer), hours)
        assertThat(added).containsExactly(customer, hours).inOrder()

        val edited = CustomDetails.replace(added, 1, hours.copy(value = "Mon-Thu 9-12"))
        assertThat(edited).containsExactly(customer, hours.copy(value = "Mon-Thu 9-12")).inOrder()

        assertThat(CustomDetails.remove(edited, 0)).containsExactly(hours.copy(value = "Mon-Thu 9-12"))
        assertThat(CustomDetails.replace(edited, 5, room)).isEqualTo(edited)
        assertThat(CustomDetails.remove(edited, -1)).isEqualTo(edited)
    }

    @Test
    fun `move reorders and clamps`() {
        val list = listOf(customer, hours, room)

        assertThat(CustomDetails.move(list, 2, 0)).containsExactly(room, customer, hours).inOrder()
        assertThat(CustomDetails.move(list, 0, 1)).containsExactly(hours, customer, room).inOrder()
        assertThat(CustomDetails.move(list, 0, 99)).containsExactly(hours, room, customer).inOrder()
        assertThat(CustomDetails.move(list, 7, 0)).isEqualTo(list)
    }

    @Test
    fun `cleaning trims and drops a detail that lacks a label or a value`() {
        val cleaned = CustomDetails.cleaned(listOf(CustomDetail(" Customer number ", " 4711 "), CustomDetail("Room", " "), CustomDetail("", "x")))

        assertThat(cleaned).containsExactly(customer)
    }

    @Test
    fun `search looks at labels and values, ignoring case`() {
        val list = listOf(customer, hours)

        assertThat(CustomDetails.matches(list, "customer")).isTrue()
        assertThat(CustomDetails.matches(list, "MON-FRI")).isTrue()
        assertThat(CustomDetails.matches(list, "4711")).isTrue()
        assertThat(CustomDetails.matches(list, "zzz")).isFalse()
        assertThat(CustomDetails.matches(list, " ")).isFalse()
    }

    @Test
    fun `a postal value is kept one part per line and read back`() {
        val address = PostalValue(street = "Beispielweg 12", postalCode = "12345", city = "Musterstadt")

        assertThat(PostalValue.decode(address.encode())).isEqualTo(address)
        assertThat(address.oneLine()).isEqualTo("Beispielweg 12, 12345 Musterstadt")
        assertThat(PostalValue().isEmpty).isTrue()
        assertThat(PostalValue.decode("")).isEqualTo(PostalValue())
    }
}
