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

package me.ahoo.cosec.runtime.shadow

import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** #1410: which document a cause in a view says failed, the one the shadow's candidate view keeps or drops by. */
class FailedDocumentTest {
    @Test
    fun `a document that cannot be read or does not compile is named, any other cause names none`() {
        val error = IllegalStateException("down")
        Cause.SourceUnavailable(DocumentKind.APP, error).failedDocument().assert().isEqualTo(DocumentKind.APP)
        Cause.DocumentInvalid(DocumentKind.TENANT, 3, error).failedDocument().assert().isEqualTo(DocumentKind.TENANT)
        Cause.AttributeUnavailable(setOf("blacklist.ip"), null).failedDocument().assert().isNull()
        Cause.NotLoaded(DocumentKind.APP).failedDocument().assert().isNull()
    }
}
