package com.postsaimanager.core.domain.extraction.candidates

/** Shape (and checksum where one exists) per reference subtype. */
object ReferenceValidator {

    private val KVNR = Regex("^[A-Z]\\d{9}$")
    private val RVNR = Regex("^\\d{8}[A-Z]\\d{3}$")

    fun validate(subtype: ReferenceSubtype, value: String): Validation {
        val v = value.trim()
        val compact = v.filter { !it.isWhitespace() }
        return when (subtype) {
            ReferenceSubtype.TAX_ID -> validateSteuerId(compact)
            ReferenceSubtype.INSURANCE_NO -> when {
                KVNR.matches(compact.uppercase()) ->
                    if (kvnrChecksumOk(compact.uppercase())) Validation.Valid
                    else Validation.Invalid("KVNR checksum failed")
                RVNR.matches(compact.uppercase()) -> Validation.Valid
                else -> Validation.Unchecked
            }
            ReferenceSubtype.TAX_NO -> {
                val digits = compact.count { it.isDigit() }
                if (compact.all { it.isDigit() || it == '/' || it == '-' } && digits in 10..13) Validation.Valid
                else Validation.Invalid("Steuernummer has 10 to 13 digits, found $digits")
            }
            ReferenceSubtype.BEITRAGSNUMMER ->
                if (compact.length == 9 && compact.all { it.isDigit() }) Validation.Valid
                else Validation.Invalid("Beitragsnummer has 9 digits")
            else -> Validation.Unchecked
        }
    }

    /**
     * Steuerliche Identifikationsnummer: 11 digits, first digit not 0, among the first ten
     * exactly one digit occurs 2 or 3 times (never 3 in a row), and an ISO 7064 MOD 11,10
     * check digit.
     */
    fun validateSteuerId(digits: String): Validation {
        if (digits.length != 11 || !digits.all { it.isDigit() }) return Validation.Invalid("Steuer-ID has 11 digits")
        if (digits[0] == '0') return Validation.Invalid("Steuer-ID does not start with 0")
        val body = digits.substring(0, 10)
        val counts = body.groupingBy { it }.eachCount()
        val repeated = counts.filter { it.value > 1 }
        if (repeated.size != 1 || repeated.values.first() > 3) return Validation.Invalid("digit pattern impossible for a Steuer-ID")
        if (body.windowed(3).any { it[0] == it[1] && it[1] == it[2] }) return Validation.Invalid("three equal digits in a row")
        var product = 10
        for (c in body) {
            var sum = ((c - '0') + product) % 10
            if (sum == 0) sum = 10
            product = (2 * sum) % 11
        }
        var check = 11 - product
        if (check == 10) check = 0
        return if (check == digits[10] - '0') Validation.Valid else Validation.Invalid("Steuer-ID check digit failed")
    }

    /**
     * Krankenversichertennummer: letter + 9 digits. The letter becomes two digits (A=01..Z=26),
     * the following eight digits are appended, digits are weighted 1,2,1,2,..., products
     * cross-summed, and the sum mod 10 is the tenth character.
     */
    fun kvnrChecksumOk(kvnr: String): Boolean {
        if (!KVNR.matches(kvnr)) return false
        val letter = kvnr[0] - 'A' + 1
        val seq = "%02d".format(letter) + kvnr.substring(1, 9)
        var sum = 0
        seq.forEachIndexed { i, c ->
            val p = (c - '0') * (if (i % 2 == 0) 1 else 2)
            sum += p / 10 + p % 10
        }
        return sum % 10 == kvnr[9] - '0'
    }
}
