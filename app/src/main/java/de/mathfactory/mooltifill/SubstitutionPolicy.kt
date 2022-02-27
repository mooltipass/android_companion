package de.mathfactory.mooltifill

interface SubstitutionPolicy {
    companion object {
        private const val LENGTH_LIMIT = 31
        fun transform(query: String) = query.take(LENGTH_LIMIT)
    }
    fun policies(query: String): List<String>
}

class DefaultSubstitutionPolicy : SubstitutionPolicy {
    override fun policies(query: String): List<String> = listOf(SubstitutionPolicy.transform(query))
}
