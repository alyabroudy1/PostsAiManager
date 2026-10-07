package com.postsaimanager.core.data.importing

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** The 50 MB cap is checked while copying, so an oversized file is never read to its end. */
class BoundedCopyTest {

    private fun copy(data: ByteArray, max: Long): Pair<BoundedCopy.Result, ByteArray> {
        val out = ByteArrayOutputStream()
        val result = BoundedCopy.copy(ByteArrayInputStream(data), out, max)
        return result to out.toByteArray()
    }

    @Test
    fun `a file within the cap is copied whole, counted and hashed`() {
        val (result, written) = copy("abc".toByteArray(), max = 10)

        assertThat(written).isEqualTo("abc".toByteArray())
        assertThat(result).isEqualTo(
            BoundedCopy.Result.Copied(3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
        )
    }

    @Test
    fun `a file exactly at the cap is accepted and one byte more is not`() {
        assertThat(copy(ByteArray(100), max = 100).first).isInstanceOf(BoundedCopy.Result.Copied::class.java)
        assertThat(copy(ByteArray(101), max = 100).first).isEqualTo(BoundedCopy.Result.TooLarge)
    }

    @Test
    fun `an oversized stream stops early`() {
        var delivered = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 1

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                delivered += len
                return len
            }
        }

        val result = BoundedCopy.copy(endless, ByteArrayOutputStream(), maxBytes = 200_000)

        assertThat(result).isEqualTo(BoundedCopy.Result.TooLarge)
        assertThat(delivered).isLessThan(200_000L + 2 * 64 * 1024)
    }

    @Test
    fun `an empty file is copied as zero bytes`() {
        val result = copy(ByteArray(0), max = 10).first as BoundedCopy.Result.Copied
        assertThat(result.bytes).isEqualTo(0)
    }
}
