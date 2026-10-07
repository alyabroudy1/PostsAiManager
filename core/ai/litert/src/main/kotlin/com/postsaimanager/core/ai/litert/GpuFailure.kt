package com.postsaimanager.core.ai.litert

/**
 * Whether an engine error message is the GPU backend failing, as opposed to anything else that can go wrong with a reply. The GPU
 * engine can start fine and only fail its first reply (the phone's OpenCL driver cannot be opened, a kernel does not compile);
 * the CPU does not have that problem, so such an error is answered by reloading on the CPU. The terms are the names of the GPU
 * stack, not words of any language.
 */
internal object GpuFailure {

    private val TERMS = listOf("opencl", "gpu", "delegate", "webgpu", "vulkan")

    fun matches(message: String?): Boolean {
        val text = message?.lowercase().orEmpty()
        return TERMS.any { it in text }
    }
}
