package com.postsaimanager.core.domain.setup

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.AiModelDescriptor
import com.postsaimanager.core.model.ChatModelFit
import com.postsaimanager.core.model.DeviceProfile
import com.postsaimanager.core.model.ModelRole
import com.postsaimanager.core.model.NotRecommendedReason
import org.junit.jupiter.api.Test

/** The recommendation over fake phones (as Android reports memory: a "4 GB" phone is about 3.7) and a catalog shaped like the bundled one. */
class RecommendChatModelUseCaseTest {

    private val gb = 1_000_000_000L

    private fun model(id: String, sizeGb: Double, min: Double, recommended: Double, role: ModelRole = ModelRole.CHAT, preselectable: Boolean = true) = AiModelDescriptor(
        id = id, name = id, family = "f", parameterCount = "p", quantization = "q", sizeBytes = (sizeGb * gb).toLong(),
        minAvailableRamBytes = 0, contextTokens = 4096, license = "l", role = role,
        minRamGb = min, recommendedRamGb = recommended, approxRamUseGb = sizeGb,
        preselectable = preselectable,
    )

    private val reader = model("reader", 0.5, 2.5, 3.0, ModelRole.READER_AND_CHAT)
    private val two = model("two", 1.3, 4.5, 5.0)
    // Slow on the CPU engine: selectable, never the default.
    private val four = model("four", 2.7, 8.0, 10.0, preselectable = false)
    private val catalog = listOf(reader, two, four)
    private val search = 0.27 * gb

    private fun phone(ramGb: Double, storageGb: Double = 50.0, is64Bit: Boolean = true) =
        DeviceProfile(ramGb, (storageGb * gb).toLong(), bigCoreCount = 4, is64Bit = is64Bit)

    private fun recommend(device: DeviceProfile, installed: Set<String> = emptySet(), searchBytes: Long = search.toLong()) =
        RecommendChatModelUseCase()(device, catalog, installed, searchBytes)

    @Test
    fun `a 4 GB phone gets the reader, and the larger models are not recommended for memory`() {
        val result = recommend(phone(3.7))
        assertThat(result.preselectedId).isEqualTo("reader")
        assertThat(result.option("reader")!!.fit).isEqualTo(ChatModelFit.Recommended)
        assertThat(result.option("two")!!.fit).isEqualTo(ChatModelFit.NotRecommended(NotRecommendedReason.MEMORY, requiredRamGb = 4.5))
        assertThat(result.option("four")!!.fit).isInstanceOf(ChatModelFit.NotRecommended::class.java)
    }

    @Test
    fun `a 6 GB phone preselects the 2B`() {
        val result = recommend(phone(5.6))
        assertThat(result.preselectedId).isEqualTo("two")
        assertThat(result.option("two")!!.fit).isEqualTo(ChatModelFit.Recommended)
        assertThat(result.option("four")!!.fit).isEqualTo(ChatModelFit.NotRecommended(NotRecommendedReason.MEMORY, requiredRamGb = 8.0))
    }

    @Test
    fun `an 8 GB phone still preselects the 2B, the 4B is below its recommended memory`() {
        val result = recommend(phone(7.4))
        assertThat(result.preselectedId).isEqualTo("two")
        // Not recommended: 7.4 is under the 4B's minimum of 8.
        assertThat(result.option("four")!!.fit).isInstanceOf(ChatModelFit.NotRecommended::class.java)
    }

    @Test
    fun `a model between its minimum and recommended memory is suitable and never preselected`() {
        val result = recommend(phone(4.8))
        assertThat(result.option("two")!!.fit).isEqualTo(ChatModelFit.Suitable)
        assertThat(result.preselectedId).isEqualTo("reader")
    }

    @Test
    fun `a 12 GB phone preselects the recommended 2B, the 4B is only suitable because it is too slow to be the default`() {
        val result = recommend(phone(11.3))
        assertThat(result.preselectedId).isEqualTo("two")
        assertThat(result.option("two")!!.fit).isEqualTo(ChatModelFit.Recommended)
        assertThat(result.option("four")!!.fit).isEqualTo(ChatModelFit.Suitable)
    }

    @Test
    fun `without a preselectable recommended model the reader is the default`() {
        val onlyBig = RecommendChatModelUseCase()(phone(11.3), listOf(reader.copy(preselectable = false), four), emptySet(), 0L)
        assertThat(onlyBig.preselectedId).isEqualTo("reader")
    }

    @Test
    fun `low storage rules a model out, counting the reader and the search model in its download`() {
        // 2B: reader 0.5 + 2B 1.3 + search 0.27 = 2.07 GB + 0.512 headroom = 2.58 GB.
        val result = recommend(phone(11.3, storageGb = 2.5))
        val fit = result.option("two")!!.fit as ChatModelFit.NotRecommended
        assertThat(fit.reason).isEqualTo(NotRecommendedReason.STORAGE)
        assertThat(fit.requiredStorageBytes).isAtLeast((2.07 * gb).toLong())
        assertThat(result.preselectedId).isEqualTo("reader")
    }

    @Test
    fun `a model is never preselected when its download does not fit, even if the memory is there`() {
        val result = recommend(phone(11.3, storageGb = 3.6))
        // 4B: 0.5 + 2.7 + 0.27 + 0.512 = 3.98 GB > 3.6; the 2B needs 2.58 GB and fits.
        assertThat(result.preselectedId).isEqualTo("two")
    }

    @Test
    fun `a 32-bit phone gets nothing recommended and the reader as the fallback`() {
        val result = recommend(phone(11.3, is64Bit = false))
        assertThat(result.preselectedId).isEqualTo("reader")
        result.options.forEach { assertThat(it.fit).isEqualTo(ChatModelFit.NotRecommended(NotRecommendedReason.UNSUPPORTED_32_BIT)) }
    }

    @Test
    fun `the download is the reader and the chat model and the search model, the reader once when it chats`() {
        val result = recommend(phone(11.3))
        assertThat(result.option("reader")!!.downloadBytes).isEqualTo(reader.sizeBytes + search.toLong())
        assertThat(result.option("two")!!.downloadBytes).isEqualTo(reader.sizeBytes + two.sizeBytes + search.toLong())
    }

    @Test
    fun `a model that cannot chat is not offered as a chat model`() {
        val readingOnly = model("reading-only", 3.0, 4.0, 5.0).copy(supportsChat = false)
        val result = RecommendChatModelUseCase()(phone(11.3), catalog + readingOnly, emptySet(), search.toLong())
        assertThat(result.options.map { it.id }).containsExactly("reader", "two", "four")
        assertThat(result.option("reading-only")).isNull()
    }

    @Test
    fun `installed models and an installed search model add nothing`() {
        val result = recommend(phone(11.3), installed = setOf("reader", "two"), searchBytes = 0)
        assertThat(result.option("reader")!!.downloadBytes).isEqualTo(0L)
        assertThat(result.option("two")!!.downloadBytes).isEqualTo(0L)
        assertThat(result.option("four")!!.downloadBytes).isEqualTo(four.sizeBytes)
    }

    // The Gemma model: ranked first by the catalogue, reads letters itself, needs 8 GB.
    private val gemma = model("gemma", 2.6, 8.0, 8.0).copy(setupRank = 100, readsDocuments = true)
    private val withGemma = listOf(reader, two, four, gemma)

    private fun recommendWithGemma(device: DeviceProfile, installed: Set<String> = emptySet()) =
        RecommendChatModelUseCase()(device, withGemma, installed, search.toLong())

    @Test
    fun `a high-memory phone lists Gemma first, recommended, and preselects it`() {
        val result = recommendWithGemma(phone(11.3))
        assertThat(result.options.first().id).isEqualTo("gemma")
        assertThat(result.option("gemma")!!.fit).isEqualTo(ChatModelFit.Recommended)
        assertThat(result.preselectedId).isEqualTo("gemma")
    }

    @Test
    fun `choosing Gemma downloads Gemma and the search model only, no reader`() {
        val result = recommendWithGemma(phone(11.3))
        assertThat(result.option("gemma")!!.downloadBytes).isEqualTo(gemma.sizeBytes + search.toLong())
        // A model that only chats still comes with the reader.
        assertThat(result.option("two")!!.downloadBytes).isEqualTo(reader.sizeBytes + two.sizeBytes + search.toLong())
    }

    @Test
    fun `an installed Gemma adds nothing to its download`() {
        val result = recommendWithGemma(phone(11.3), installed = setOf("gemma"))
        assertThat(result.option("gemma")!!.downloadBytes).isEqualTo(search.toLong())
    }

    @Test
    fun `a phone below Gemma's memory keeps the best Qwen and shows Gemma as not recommended with the reason, after the others`() {
        val result = recommendWithGemma(phone(5.6))
        assertThat(result.preselectedId).isEqualTo("two")
        assertThat(result.option("gemma")!!.fit).isEqualTo(ChatModelFit.NotRecommended(NotRecommendedReason.MEMORY, requiredRamGb = 8.0))
        assertThat(result.options.map { it.id }).containsExactly("reader", "two", "gemma", "four").inOrder()
    }

    @Test
    fun `a 4 GB phone preselects the reader even with Gemma on offer`() {
        val result = recommendWithGemma(phone(3.7))
        assertThat(result.preselectedId).isEqualTo("reader")
        assertThat(result.option("gemma")!!.fit).isInstanceOf(ChatModelFit.NotRecommended::class.java)
    }

    @Test
    fun `Gemma is not preselected when its download does not fit, the next recommended model is`() {
        // Gemma 2.6 + search 0.27 + headroom 0.512 = 3.38 GB > 3.0; the 2B needs 2.58 GB.
        val result = recommendWithGemma(phone(11.3, storageGb = 3.0))
        assertThat(result.option("gemma")!!.fit).isInstanceOf(ChatModelFit.NotRecommended::class.java)
        assertThat(result.preselectedId).isEqualTo("two")
    }

    @Test
    fun `the rank decides the order and the default, not a model's name or size`() {
        val loud = model("big-unranked", 3.0, 4.0, 4.0)
        val result = RecommendChatModelUseCase()(phone(11.3), listOf(reader, loud, gemma.copy(setupRank = 5)), emptySet(), 0L)
        assertThat(result.preselectedId).isEqualTo("gemma")
        assertThat(result.options.first().id).isEqualTo("gemma")
    }
}
