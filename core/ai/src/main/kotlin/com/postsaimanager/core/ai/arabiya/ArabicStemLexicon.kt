package com.postsaimanager.core.ai.arabiya

/**
 * Arabic stem diacritization lexicon.
 * Ported from arabiya/engine.py _minimal_test_lexicon() + extended.
 *
 * Maps bare Arabic words → diacritized stems by POS tag.
 * Without this, the pipeline cannot produce proper internal vowels.
 */
object ArabicStemLexicon {

    /**
     * Look up the diacritized stem form for a bare word + POS tag.
     * Returns null if not in lexicon.
     */
    fun lookup(bareWord: String, pos: String): String? {
        val entry = LEXICON[bareWord] ?: return null
        // Try exact POS, then try related POS
        return entry[pos]
            ?: entry["NOUN"]?.takeIf { pos in setOf("PROPN", "NUM") }
            ?: entry.values.firstOrNull()
    }

    /**
     * Comprehensive Arabic stem lexicon.
     * Key: bare word (no diacritics), Value: map of POS → diacritized stem.
     */
    private val LEXICON: Map<String, Map<String, String>> = mapOf(
        // ═══════════════════════════════════════
        //  COMMON VERBS (Past Tense)
        // ═══════════════════════════════════════
        "ذهب" to mapOf("VERB" to "ذَهَبَ"),
        "كتب" to mapOf("VERB" to "كَتَبَ", "NOUN" to "كُتُب"),
        "قرأ" to mapOf("VERB" to "قَرَأَ"),
        "علم" to mapOf("VERB" to "عَلِمَ", "NOUN" to "عِلْم"),
        "قال" to mapOf("VERB" to "قَالَ"),
        "كان" to mapOf("VERB" to "كَانَ", "AUX" to "كَانَ"),
        "رجع" to mapOf("VERB" to "رَجَعَ"),
        "وجد" to mapOf("VERB" to "وَجَدَ"),
        "أكل" to mapOf("VERB" to "أَكَلَ"),
        "دخل" to mapOf("VERB" to "دَخَلَ"),
        "خرج" to mapOf("VERB" to "خَرَجَ"),
        "فتح" to mapOf("VERB" to "فَتَحَ"),
        "درس" to mapOf("VERB" to "دَرَسَ", "NOUN" to "دَرْس"),
        "فهم" to mapOf("VERB" to "فَهِمَ"),
        "جلس" to mapOf("VERB" to "جَلَسَ"),
        "سمع" to mapOf("VERB" to "سَمِعَ"),
        "نظر" to mapOf("VERB" to "نَظَرَ"),
        "وصل" to mapOf("VERB" to "وَصَلَ"),
        "حمل" to mapOf("VERB" to "حَمَلَ"),
        "وقع" to mapOf("VERB" to "وَقَعَ"),
        "ركب" to mapOf("VERB" to "رَكِبَ"),
        "سأل" to mapOf("VERB" to "سَأَلَ"),
        "شرب" to mapOf("VERB" to "شَرِبَ"),
        "لعب" to mapOf("VERB" to "لَعِبَ"),
        "ضرب" to mapOf("VERB" to "ضَرَبَ"),
        "نزل" to mapOf("VERB" to "نَزَلَ"),
        "طلب" to mapOf("VERB" to "طَلَبَ"),
        "جعل" to mapOf("VERB" to "جَعَلَ"),
        "أخذ" to mapOf("VERB" to "أَخَذَ"),
        "ترك" to mapOf("VERB" to "تَرَكَ"),
        "بدأ" to mapOf("VERB" to "بَدَأَ"),
        "رأى" to mapOf("VERB" to "رَأَى"),
        "جاء" to mapOf("VERB" to "جَاءَ"),
        "أصبح" to mapOf("VERB" to "أَصْبَحَ"),
        "صار" to mapOf("VERB" to "صَارَ"),
        "وضع" to mapOf("VERB" to "وَضَعَ"),
        "أعلن" to mapOf("VERB" to "أَعْلَنَ"),
        "أكد" to mapOf("VERB" to "أَكَّدَ"),
        "حقق" to mapOf("VERB" to "حَقَّقَ"),
        "أضاف" to mapOf("VERB" to "أَضَافَ"),
        "أشار" to mapOf("VERB" to "أَشَارَ"),
        "عاد" to mapOf("VERB" to "عَادَ"),
        "قرر" to mapOf("VERB" to "قَرَّرَ"),
        "أوضح" to mapOf("VERB" to "أَوْضَحَ"),
        "قدم" to mapOf("VERB" to "قَدَّمَ"),
        "حصل" to mapOf("VERB" to "حَصَلَ"),
        "أجرى" to mapOf("VERB" to "أَجْرَى"),
        "أراد" to mapOf("VERB" to "أَرَادَ"),
        "عرف" to mapOf("VERB" to "عَرَفَ"),
        "بعث" to mapOf("VERB" to "بَعَثَ"),
        "حضر" to mapOf("VERB" to "حَضَرَ"),
        "ظهر" to mapOf("VERB" to "ظَهَرَ"),
        "نام" to mapOf("VERB" to "نَامَ"),
        "مشى" to mapOf("VERB" to "مَشَى"),
        "غسل" to mapOf("VERB" to "غَسَلَ"),
        "زرع" to mapOf("VERB" to "زَرَعَ"),
        "صنع" to mapOf("VERB" to "صَنَعَ"),

        // ═══════════════════════════════════════
        //  PRESENT TENSE VERBS
        // ═══════════════════════════════════════
        "يذهب" to mapOf("VERB" to "يَذْهَبُ"),
        "يكتب" to mapOf("VERB" to "يَكْتُبُ"),
        "يقرأ" to mapOf("VERB" to "يَقْرَأُ"),
        "يعلم" to mapOf("VERB" to "يَعْلَمُ"),
        "يقول" to mapOf("VERB" to "يَقُولُ"),
        "يأكل" to mapOf("VERB" to "يَأْكُلُ"),
        "يشرب" to mapOf("VERB" to "يَشْرَبُ"),
        "يلعب" to mapOf("VERB" to "يَلْعَبُ"),
        "يدرس" to mapOf("VERB" to "يَدْرُسُ"),
        "يفهم" to mapOf("VERB" to "يَفْهَمُ"),
        "يجلس" to mapOf("VERB" to "يَجْلِسُ"),
        "يسمع" to mapOf("VERB" to "يَسْمَعُ"),
        "ينظر" to mapOf("VERB" to "يَنْظُرُ"),

        // ═══════════════════════════════════════
        //  COMMON NOUNS (Indefinite)
        // ═══════════════════════════════════════
        "كتاب" to mapOf("NOUN" to "كِتَاب"),
        "طالب" to mapOf("NOUN" to "طَالِب"),
        "معلم" to mapOf("NOUN" to "مُعَلِّم"),
        "مدرسة" to mapOf("NOUN" to "مَدْرَسَة"),
        "بيت" to mapOf("NOUN" to "بَيْت"),
        "ولد" to mapOf("NOUN" to "وَلَد"),
        "رجل" to mapOf("NOUN" to "رَجُل"),
        "يوم" to mapOf("NOUN" to "يَوْم"),
        "ماء" to mapOf("NOUN" to "مَاء"),
        "سماء" to mapOf("NOUN" to "سَمَاء"),
        "أرض" to mapOf("NOUN" to "أَرْض"),
        "نور" to mapOf("NOUN" to "نُور"),
        "عمل" to mapOf("NOUN" to "عَمَل"),
        "قلم" to mapOf("NOUN" to "قَلَم"),
        "باب" to mapOf("NOUN" to "بَاب"),
        "حديقة" to mapOf("NOUN" to "حَدِيقَة"),
        "سيارة" to mapOf("NOUN" to "سَيَّارَة"),
        "جامعة" to mapOf("NOUN" to "جَامِعَة"),
        "دروس" to mapOf("NOUN" to "دُرُوس"),
        "بنت" to mapOf("NOUN" to "بِنْت"),
        "أم" to mapOf("NOUN" to "أُمّ"),
        "أب" to mapOf("NOUN" to "أَب"),
        "أخ" to mapOf("NOUN" to "أَخ"),
        "شمس" to mapOf("NOUN" to "شَمْس"),
        "قمر" to mapOf("NOUN" to "قَمَر"),
        "نهر" to mapOf("NOUN" to "نَهْر"),
        "بحر" to mapOf("NOUN" to "بَحْر"),
        "جبل" to mapOf("NOUN" to "جَبَل"),
        "شجرة" to mapOf("NOUN" to "شَجَرَة"),
        "مدينة" to mapOf("NOUN" to "مَدِينَة"),
        "قرية" to mapOf("NOUN" to "قَرْيَة"),
        "شارع" to mapOf("NOUN" to "شَارِع"),
        "طريق" to mapOf("NOUN" to "طَرِيق"),
        "وقت" to mapOf("NOUN" to "وَقْت"),
        "ساعة" to mapOf("NOUN" to "سَاعَة"),
        "صباح" to mapOf("NOUN" to "صَبَاح"),
        "مساء" to mapOf("NOUN" to "مَسَاء"),
        "ليل" to mapOf("NOUN" to "لَيْل"),
        "طعام" to mapOf("NOUN" to "طَعَام"),
        "لغة" to mapOf("NOUN" to "لُغَة"),
        "كلمة" to mapOf("NOUN" to "كَلِمَة"),
        "جملة" to mapOf("NOUN" to "جُمْلَة"),
        "حرف" to mapOf("NOUN" to "حَرْف"),
        "سؤال" to mapOf("NOUN" to "سُؤَال"),
        "جواب" to mapOf("NOUN" to "جَوَاب"),

        // ═══════════════════════════════════════
        //  NOUNS WITH DEFINITE ARTICLE
        // ═══════════════════════════════════════
        "الكتاب" to mapOf("NOUN" to "الْكِتَاب"),
        "الطالب" to mapOf("NOUN" to "الطَّالِب"),
        "المعلم" to mapOf("NOUN" to "الْمُعَلِّم"),
        "المدرسة" to mapOf("NOUN" to "الْمَدْرَسَة"),
        "البيت" to mapOf("NOUN" to "الْبَيْت"),
        "اليوم" to mapOf("NOUN" to "الْيَوْم"),
        "السماء" to mapOf("NOUN" to "السَّمَاء"),
        "العلم" to mapOf("NOUN" to "الْعِلْم"),
        "الدرس" to mapOf("NOUN" to "الدَّرْس"),
        "المعلمون" to mapOf("NOUN" to "الْمُعَلِّمُون"),
        "الدروس" to mapOf("NOUN" to "الدُّرُوس"),
        "المدارس" to mapOf("NOUN" to "الْمَدَارِس"),
        "الماء" to mapOf("NOUN" to "الْمَاء"),
        "الأرض" to mapOf("NOUN" to "الْأَرْض"),
        "النور" to mapOf("NOUN" to "النُّور"),
        "الشمس" to mapOf("NOUN" to "الشَّمْس"),
        "القمر" to mapOf("NOUN" to "الْقَمَر"),
        "الولد" to mapOf("NOUN" to "الْوَلَد"),
        "البنت" to mapOf("NOUN" to "الْبِنْت"),
        "الرجل" to mapOf("NOUN" to "الرَّجُل"),
        "الباب" to mapOf("NOUN" to "الْبَاب"),
        "البحر" to mapOf("NOUN" to "الْبَحْر"),

        // ═══════════════════════════════════════
        //  ADJECTIVES
        // ═══════════════════════════════════════
        "كبير" to mapOf("ADJ" to "كَبِير"),
        "صغير" to mapOf("ADJ" to "صَغِير"),
        "جديد" to mapOf("ADJ" to "جَدِيد"),
        "قديم" to mapOf("ADJ" to "قَدِيم"),
        "جميل" to mapOf("ADJ" to "جَمِيل"),
        "طويل" to mapOf("ADJ" to "طَوِيل"),
        "قصير" to mapOf("ADJ" to "قَصِير"),
        "سريع" to mapOf("ADJ" to "سَرِيع"),
        "بطيء" to mapOf("ADJ" to "بَطِيء"),
        "قوي" to mapOf("ADJ" to "قَوِيّ"),
        "ضعيف" to mapOf("ADJ" to "ضَعِيف"),
        "الكبيرة" to mapOf("ADJ" to "الْكَبِيرَة"),
        "الجديدة" to mapOf("ADJ" to "الْجَدِيدَة"),
        "الجميلة" to mapOf("ADJ" to "الْجَمِيلَة"),
        "جديدة" to mapOf("ADJ" to "جَدِيدَة"),

        // ═══════════════════════════════════════
        //  PARTICLES, PREPOSITIONS, CONJUNCTIONS
        // ═══════════════════════════════════════
        "إلى" to mapOf("ADP" to "إِلَى"),
        "في" to mapOf("ADP" to "فِي"),
        "من" to mapOf("ADP" to "مِنْ"),
        "على" to mapOf("ADP" to "عَلَى"),
        "عن" to mapOf("ADP" to "عَنْ"),
        "حتى" to mapOf("ADP" to "حَتَّى"),
        "منذ" to mapOf("ADP" to "مُنْذُ"),
        "إن" to mapOf("PART" to "إِنَّ"),
        "أن" to mapOf("PART" to "أَنَّ"),
        "ما" to mapOf("PART" to "مَا"),
        "لا" to mapOf("PART" to "لَا"),
        "قد" to mapOf("PART" to "قَدْ"),
        "هل" to mapOf("PART" to "هَلْ"),
        "لم" to mapOf("PART" to "لَمْ"),
        "لن" to mapOf("PART" to "لَنْ"),

        // ═══════════════════════════════════════
        //  PRONOUNS / DEMONSTRATIVES
        // ═══════════════════════════════════════
        "هذا" to mapOf("DET" to "هٰذَا"),
        "هذه" to mapOf("DET" to "هٰذِهِ"),
        "ذلك" to mapOf("DET" to "ذٰلِكَ"),
        "هو" to mapOf("PRON" to "هُوَ"),
        "هي" to mapOf("PRON" to "هِيَ"),
        "هم" to mapOf("PRON" to "هُمْ"),
        "هن" to mapOf("PRON" to "هُنَّ"),
        "أنا" to mapOf("PRON" to "أَنَا"),
        "نحن" to mapOf("PRON" to "نَحْنُ"),
        "أنت" to mapOf("PRON" to "أَنْتَ"),

        // Special
        "أجمل" to mapOf("VERB" to "أَجْمَلَ", "ADJ" to "أَجْمَل"),
    )
}
