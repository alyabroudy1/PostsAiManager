package com.postsaimanager.core.domain.extraction.address

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.AddressPart
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AddressFormatsTest {

    private val registry = AddressFormats.default

    @Test
    fun `the shipped data holds exactly the countries in scope, loaded through the class loader`() {
        assertThat(registry.formats.map { it.iso2 }).containsExactly("DE", "GB", "US", "AE", "SA", "EG").inOrder()
        assertThat(AddressFormats.of("de")?.requires).containsExactly(AddressPart.STREET, AddressPart.POSTCODE, AddressPart.CITY)
        assertThat(AddressFormats.of("AE")?.postcodePattern).isNull()
        assertThat(AddressFormats.of("XX")).isNull()
    }

    @Test
    fun `the attribution constant names the licence and the source`() {
        assertThat(AddressFormats.ATTRIBUTION).contains("libaddressinput")
        assertThat(AddressFormats.ATTRIBUTION).contains("CC BY 4.0")
    }

    @Test
    fun `postcodes are verified by each country's own shape`() {
        assertThat(AddressFormats.of("DE")!!.isPostcode("54321")).isTrue()
        assertThat(AddressFormats.of("DE")!!.isPostcode("5432")).isFalse()
        assertThat(AddressFormats.of("GB")!!.isPostcode("SW1A 1AA")).isTrue()
        assertThat(AddressFormats.of("GB")!!.isPostcode("EX2 3PL")).isTrue()
        assertThat(AddressFormats.of("GB")!!.isPostcode("54321")).isFalse()
        assertThat(AddressFormats.of("US")!!.isPostcode("62704-1234")).isTrue()
        assertThat(AddressFormats.of("SA")!!.isPostcode("12345")).isTrue()
    }

    @Test
    fun `a country line is verified against the local and the english names, folded`() {
        assertThat(registry.byCountryName("Deutschland")?.iso2).isEqualTo("DE")
        assertThat(registry.byCountryName("GERMANY")?.iso2).isEqualTo("DE")
        assertThat(registry.byCountryName("U.S.A.")?.iso2).isEqualTo("US")
        assertThat(registry.byCountryName("United Kingdom")?.iso2).isEqualTo("GB")
        assertThat(registry.byCountryName("المملكة العربية السعودية")?.iso2).isEqualTo("SA")
        assertThat(registry.byCountryName("الإمارات العربية المتحدة")?.iso2).isEqualTo("AE")
        assertThat(registry.byCountryName("مصر")?.iso2).isEqualTo("EG")
        assertThat(registry.byCountryName("Frankreich")).isNull()
    }

    @Test
    fun `the postcode place line picks a country only when its shape is unambiguous`() {
        assertThat(registry.byPostcodeShape("54321 Beispieldorf").map { it.iso2 }).containsExactly("DE")
        assertThat(registry.byPostcodeShape("D-54321 Beispieldorf").map { it.iso2 }).containsExactly("DE")
        assertThat(registry.byPostcodeShape("Springfield, IL 62704").map { it.iso2 }).containsExactly("US")
        assertThat(registry.byPostcodeShape("Exampleton EX2 3PL").map { it.iso2 }).containsExactly("GB")
        assertThat(registry.byPostcodeShape("SW1A 1AA").map { it.iso2 }).containsExactly("GB")
        assertThat(registry.byPostcodeShape("الرياض 12345").map { it.iso2 }).containsExactly("SA")
    }

    @Test
    fun `a phone number is not a postcode place line`() {
        assertThat(registry.byPostcodeShape("Tel. 01234 567890")).isEmpty()
        assertThat(registry.byPostcodeShape("Aktenzeichen: EST-2025-0047118")).isEmpty()
    }

    @Test
    fun `a new country is a json entry, with no code change`() {
        val json = """
            {"formats":[
              {"iso2":"FR","postcode":"\\d{5}","postcodeBeforeCity":true,"houseNumberFirst":true,
               "requires":["street","postcode","city"],"lineOrder":["name","street","postcode","city"],
               "countryNames":["France","Frankreich","فرنسا"]}
            ]}
        """.trimIndent()
        val fr = AddressFormats.parse(json)
        assertThat(fr.of("FR")?.requires).containsExactly(AddressPart.STREET, AddressPart.POSTCODE, AddressPart.CITY)
        assertThat(fr.of("FR")!!.isPostcode("75002")).isTrue()
        assertThat(fr.byCountryName("Frankreich")?.iso2).isEqualTo("FR")
        assertThat(fr.byPostcodeShape("75002 Paris").map { it.iso2 }).containsExactly("FR")

        // and the labeller reads an address of that country with it
        val lines = listOf(
            line("Jean Dupont", 0.10f), line("10 Rue de la Paix", 0.12f), line("75002 Paris", 0.14f), line("Frankreich", 0.16f),
        )
        val labeled = AddressLineLabeler(fr).shape(lines)
        assertThat(labeled.format?.iso2).isEqualTo("FR")
        assertThat(AddressVerifier().verify(labeled).verified).isTrue()
    }

    @Test
    fun `an entry that names an unknown part is an error, not skipped`() {
        assertThrows(IllegalStateException::class.java) {
            AddressFormats.parse("""{"formats":[{"iso2":"XX","requires":["nonsense"]}]}""")
        }
    }
}
