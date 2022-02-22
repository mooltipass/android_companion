package de.mathfactory.mooltifill

interface SubstitutionPolicy {
    fun policies(query: String): List<String>

}
