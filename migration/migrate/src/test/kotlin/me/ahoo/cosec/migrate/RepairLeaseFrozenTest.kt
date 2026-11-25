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
package me.ahoo.cosec.migrate

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * The repair's lease is shared by two releases' runs of `cosec-migrate` on one Redis, so its name is frozen within 8.x
 * with the control plane's other leases (RFC 0014 R6, `SNP-26`, `FrozenLayout.CONTROL_LEASES` in `cosec-cocache`, whose
 * test samples the key it is stored under). A run of another release that took another name would repair at the same
 * time as this one.
 */
class RepairLeaseFrozenTest {
    @Test
    fun theRepairLeaseIsFrozen() {
        ReferenceRepair.LEASE.assert()
            .describedAs(
                "the repair's lease is a name frozen within 8.x (RFC 0014 R6, SNP-26, FrozenLayout.CONTROL_LEASES)"
            )
            .isEqualTo("repair-references")
    }
}
