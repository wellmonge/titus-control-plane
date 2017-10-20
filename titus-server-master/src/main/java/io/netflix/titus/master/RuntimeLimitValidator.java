/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.netflix.titus.master;

import io.netflix.titus.api.endpoint.v2.rest.representation.TitusJobType;
import io.netflix.titus.master.config.MasterConfiguration;
import io.netflix.titus.master.endpoint.v2.rest.representation.TitusJobSpec;
import io.netflix.titus.master.endpoint.v2.validator.TitusJobSpecValidators;

public class RuntimeLimitValidator implements TitusJobSpecValidators.Validator {
    private final MasterConfiguration masterConfiguration;

    public RuntimeLimitValidator(MasterConfiguration masterConfiguration) {
        this.masterConfiguration = masterConfiguration;
    }

    @Override
    public boolean isValid(TitusJobSpec titusJobSpec) {
        return titusJobSpec.getType() == TitusJobType.service ||
                (titusJobSpec.getType() == TitusJobType.batch &&
                        titusJobSpec.getRuntimeLimitSecs() != null &&
                        titusJobSpec.getRuntimeLimitSecs() >= 0 &&
                        titusJobSpec.getRuntimeLimitSecs() <= masterConfiguration.getMaxRuntimeLimit());
    }
}