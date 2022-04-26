
/*
 * Copyright (C) 2022 Bernhard Rauch.
 *
 * This file is part of Mooltifill.
 *
 * Mooltifill is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Mooltifill is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Mooltifill.  If not, see <https://www.gnu.org/licenses/>.
 */

import android.content.Context
import kotlinx.coroutines.runBlocking
import mozilla.components.lib.publicsuffixlist.PublicSuffixList

object PublicSuffixManager {

    var suffixList: PublicSuffixList? = null

    /**
     * Just initialize suffixList object with application provided context.
     */

    operator fun invoke(context: Context): PublicSuffixManager {
        suffixList = PublicSuffixList(context)
        suffixList!!.prefetch()
        return this
    }

    /**
     * Returns the public suffix and one more level; known as the registrable domain. Returns `null` if
     * [domain] is a public suffix itself
     * @param [domain] _must_ be a valid domain. [PublicSuffixList] performs no validation, and if any unexpected values
     * are passed (e.g., a full URL, a domain with a trailing '/', etc) this may return an incorrect result.
     */

    fun getPublicSuffixPlusOne(domain: String): String
    {
        val isPublicSuffix = runBlocking { suffixList!!.isPublicSuffix(domain).await() }

        if (isPublicSuffix)
        {
            return domain
        }

        return runBlocking { suffixList!!.getPublicSuffixPlusOne(domain).await() }!!
    }


    /**
     * Returns the public suffix and two more level; Returns `null` if
     * [domain] is a public suffix itself
     * @param [domain] _must_ be a valid domain. [PublicSuffixList] performs no validation, and if any unexpected values
     * are passed (e.g., a full URL, a domain with a trailing '/', etc) this may return an incorrect result.
     */

    fun getPublicSuffixWithSubdomain(domain: String): String
    {
        val isPublicSuffix = runBlocking { suffixList!!.isPublicSuffix(domain).await() }
        if (isPublicSuffix)
        {
            return domain
        }

        val strippedDomain = domain.replace("www.","",true)
        val tld = runBlocking { suffixList!!.getPublicSuffixPlusOne(domain).await() }!!
        val compareResult = tld.compareTo(strippedDomain)

        when (compareResult) {
            0 ->  {
                return tld
            }
            else -> { // Explicitly combining TLD+one part with next level subdomain
                val strippedTokens = domain.substringBefore("."+tld).split(".")
                val subdomain = strippedTokens.last() + "." + tld
                return subdomain
            }
        }

        return domain
    }


    /**
     *
     *
     * Note: Added these methods because in future we may have some requirement change accordinly
     *
     *
     * Returns the public suffix of the given [domain]; known as the effective top-level domain (eTLD). Returns `null`
     * if the [domain] is a public suffix itself.
     * @param [domain] _must_ be a valid domain. [PublicSuffixList] performs no validation, and if any unexpected values
     * are passed (e.g., a full URL, a domain with a trailing '/', etc) this may return an incorrect result.
     */

    fun getPublicSuffix(domain: String): String
    {
        val isPublicSuffix = runBlocking { suffixList!!.isPublicSuffix(domain).await() }

        if (isPublicSuffix)
        {
            return domain
        }

        return runBlocking { suffixList!!.getPublicSuffix(domain).await() }!!
    }

    /**
     *
     * Note: Added these methods because in future we may have some requirement change accordinly
     *
     *
     * Strips the public suffix from the given [domain]. Returns the original domain if no public suffix could be
     * stripped.
     * @param [domain] _must_ be a valid domain. [PublicSuffixList] performs no validation, and if any unexpected values
     * are passed (e.g., a full URL, a domain with a trailing '/', etc) this may return an incorrect result.
     */

    fun skipPublicSuffix(domain: String): String
    {
        val isPublicSuffix = runBlocking { suffixList!!.isPublicSuffix(domain).await() }

        if (isPublicSuffix)
        {
            return domain
        }

        return runBlocking { suffixList!!.stripPublicSuffix(domain).await() }!!
    }
}
