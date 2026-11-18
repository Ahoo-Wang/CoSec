/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.spring.boot.jwt

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spring.boot.pipeline.CoCacheRevocation
import org.springframework.beans.factory.ListableBeanFactory

/**
 * The stores CoSec's tokens need, and the one startup failure that names every one of them missing: whichever of the
 * three is created first, the message is the same, so a host does not learn of them one restart at a time.
 * The starter's own [TokenStore] and [TokenFamilyStore] beans are stand-ins that fail as they are created; they exist only
 * while JWT is on, so a store is counted missing only where JWT needs it.
 */
internal object TokenStores {
    /** The bean name of the [TokenStore] stand-in. */
    const val TOKEN_STORE_STAND_IN = "cosecTokenStore"

    /** The bean name of the [TokenFamilyStore] stand-in. */
    const val FAMILY_STORE_STAND_IN = "cosecTokenFamilyStore"

    /**
     * The stores missing in [beans], by type name, in the order the message gives them. Reads the bean definitions, and
     * creates the application's [TokenStore] when it is not the stand-in: whether it yields a reader depends on the
     * instance (CoCache's does), not on the type a `@Bean` method declares. So an application [TokenStore] that needs a
     * [TokenFamilyStore] meets Spring's circular-creation error here rather than this message: an accepted corner.
     */
    fun missing(beans: ListableBeanFactory): List<String> {
        val tokenStores = beans.getBeanNamesForType(TokenStore::class.java, true, false).toList()
        val tokenStoreMissing = tokenStores == listOf(TOKEN_STORE_STAND_IN)
        val familyStoreMissing = beans.getBeanNamesForType(TokenFamilyStore::class.java, true, false)
            .toList() == listOf(FAMILY_STORE_STAND_IN)
        val readerMissing = beans.getBeanNamesForType(RevocationReader::class.java, true, false).isEmpty() &&
            (tokenStoreMissing || CoCacheRevocation.readerOf(beans.getBeanProvider(TokenStore::class.java).ifUnique) == null)
        return buildList {
            if (tokenStoreMissing) add("TokenStore")
            if (readerMissing) add("RevocationReader")
            if (familyStoreMissing) add("TokenFamilyStore")
        }
    }

    /** The startup failure that names every store [beans] is missing: its callers throw it themselves. */
    fun failure(beans: ListableBeanFactory): IllegalStateException = IllegalStateException(message(missing(beans)))

    fun message(missing: List<String>): String =
        "CoSec's tokens are on, but ${missing.joinToString(", ")} ${if (missing.size == 1) "is" else "are"} missing. " +
            "Verifying and refreshing tokens needs three stores: a TokenStore records revoked tokens, a " +
            "RevocationReader reads them for every token verified, a TokenFamilyStore keeps the refresh token " +
            "families. Add cosec-cocache, which brings all three, or a bean of each missing one, or set " +
            "${JwtProperties.PREFIX}.enabled=false. RevocationReader.NONE accepts revoked tokens until they expire: " +
            "use it only where no one revokes a token, on this service or on the one that issued it."
}
