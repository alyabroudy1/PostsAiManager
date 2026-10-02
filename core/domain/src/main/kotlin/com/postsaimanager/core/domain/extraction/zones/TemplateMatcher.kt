package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.domain.extraction.layout.LetterLayout

/** The outcome of matching: the chosen template, its score, and every template's score for diagnostics. */
class TemplateMatch(val template: LayoutTemplate, val score: Float, val scores: Map<String, Float>) {
    /** True when nothing scored above the threshold and the fallback was taken. */
    val isFallback: Boolean get() = template.id == LayoutTemplates.GENERIC.id
}

/**
 * Picks the layout class of a [LetterLayout] from geometry alone: zone presence and position, column
 * structure, the page's shape, the reading direction. The score of a template is the weighted mean of
 * how well each part of its [TemplateSignature] fits; below [threshold] the letter is [LayoutTemplates.GENERIC].
 */
class TemplateMatcher(
    private val templates: List<LayoutTemplate> = LayoutTemplates.ALL,
    private val threshold: Float = DEFAULT_THRESHOLD,
) {

    /**
     * @param pageAspect width over height of the page when known; otherwise estimated from the text.
     * @param country the address country code when known (from the structured address), else null.
     * @param script the script code of the page's text when known, else null.
     *   [country] and [script] only break near-ties: a template whose [LayoutTemplate.locale] names them gets
     *   [LOCALE_TIE_BREAK] per match when choosing the best, and never reaches [threshold] because of it.
     *   With both null the choice is the geometry's alone.
     */
    fun match(layout: LetterLayout, pageAspect: Float? = null, country: String? = null, script: String? = null): TemplateMatch {
        val features = LayoutFeatures(layout, pageAspect)
        val scores = templates.associate { it.id to score(it.signature, features) }
        val best = templates.maxByOrNull { scores.getValue(it.id) + LOCALE_TIE_BREAK * (it.locale?.matches(country, script) ?: 0) }
        val top = best?.let { scores.getValue(it.id) } ?: 0f
        return if (best == null || top < threshold) {
            TemplateMatch(LayoutTemplates.GENERIC, top, scores)
        } else {
            TemplateMatch(best, top, scores)
        }
    }

    fun score(signature: TemplateSignature, f: LayoutFeatures): Float {
        var total = 0f
        var weight = 0f
        fun part(w: Float, fit: Float) {
            total += w * fit
            weight += w
        }
        signature.zones.forEach { part(it.weight, f.fit(it.zone, it.region)) }
        signature.absent.forEach { (zone, max) -> part(1f, if (f.count(zone) <= max) 1f else 0f) }
        signature.fontRatio?.let { part(it.weight, it.fit(f.fontRatio)) }
        signature.tableRows?.let { part(it.weight, it.fit(f.tableRows.toFloat())) }
        signature.keyValueShare?.let { part(it.weight, it.fit(f.keyValueShare)) }
        signature.rightToLeft?.let { part(1f, if (f.rightToLeft == it) 1f else 0f) }
        return if (weight == 0f) 0f else total / weight
    }

    companion object {
        const val DEFAULT_THRESHOLD = 0.6f

        /** Added per matching locale code when ranking; small against the geometry's score (0..1). */
        const val LOCALE_TIE_BREAK = 0.03f
    }
}
