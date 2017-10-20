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

package io.netflix.titus.master.service.management.internal;

import java.util.Map;
import java.util.Set;

import io.netflix.titus.api.model.ResourceDimension;
import io.netflix.titus.api.model.Tier;
import io.netflix.titus.api.model.v2.V2JobState;
import io.netflix.titus.api.model.v2.parameter.Parameters;
import io.netflix.titus.api.store.v2.V2JobMetadata;
import io.netflix.titus.master.ApiOperations;
import io.netflix.titus.master.model.ResourceDimensions;
import io.netflix.titus.master.service.management.ApplicationSlaManagementService;
import io.netflix.titus.master.service.management.BeanCapacityManagementConfiguration;
import io.netflix.titus.master.service.management.CompositeResourceConsumption;
import io.netflix.titus.master.service.management.ResourceConsumption;
import io.netflix.titus.testkit.model.runtime.RuntimeModelGenerator;
import org.junit.Test;

import static io.netflix.titus.common.util.CollectionsExt.asSet;
import static io.netflix.titus.master.service.management.ResourceConsumptions.findConsumption;
import static io.netflix.titus.master.service.management.internal.ConsumptionModelGenerator.CRITICAL_SLA_1;
import static io.netflix.titus.master.service.management.internal.ConsumptionModelGenerator.DEFAULT_SLA;
import static io.netflix.titus.master.service.management.internal.ConsumptionModelGenerator.NOT_USED_SLA;
import static io.netflix.titus.master.service.management.internal.ConsumptionModelGenerator.capacityGroupLimit;
import static io.netflix.titus.master.service.management.internal.ConsumptionModelGenerator.singleWorkerConsumptionOf;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ResourceConsumptionEvaluatorTest {

    private static final double BUFFER = 0.5;

    private final BeanCapacityManagementConfiguration config = BeanCapacityManagementConfiguration.newBuilder()
            .withCriticalTierBuffer(BUFFER)
            .withFlexTierBuffer(BUFFER)
            .build();

    private final ApplicationSlaManagementService applicationSlaManagementService = mock(ApplicationSlaManagementService.class);

    private final ApiOperations apiOperations = mock(ApiOperations.class);

    private final RuntimeModelGenerator runtimeModelGenerator = new RuntimeModelGenerator();

    @Test
    public void testEvaluation() throws Exception {
        when(applicationSlaManagementService.getApplicationSLAs()).thenReturn(asList(DEFAULT_SLA, CRITICAL_SLA_1, NOT_USED_SLA));

        // Job with defined capacity group SLA
        V2JobMetadata goodCapacityJob = runtimeModelGenerator.newJobMetadata(Parameters.JobType.Service, "goodCapacityJob", CRITICAL_SLA_1.getAppName());
        runtimeModelGenerator.scheduleJob(goodCapacityJob.getJobId());
        runtimeModelGenerator.moveWorkerToState(goodCapacityJob.getJobId(), 0, V2JobState.Started);
        when(apiOperations.getJobMetadata(goodCapacityJob.getJobId())).thenReturn(goodCapacityJob);
        when(apiOperations.getAllWorkers(goodCapacityJob.getJobId())).thenReturn(runtimeModelGenerator.getAllWorkers(goodCapacityJob.getJobId()));
        when(apiOperations.getRunningWorkers(goodCapacityJob.getJobId())).thenReturn(runtimeModelGenerator.getRunningWorkers(goodCapacityJob.getJobId()));

        // Job without appName defined
        V2JobMetadata noAppNameJob = runtimeModelGenerator.newJobMetadata(Parameters.JobType.Service, null, DEFAULT_SLA.getAppName());
        runtimeModelGenerator.scheduleJob(noAppNameJob.getJobId());
        runtimeModelGenerator.moveWorkerToState(noAppNameJob.getJobId(), 0, V2JobState.Started);
        when(apiOperations.getJobMetadata(noAppNameJob.getJobId())).thenReturn(noAppNameJob);
        when(apiOperations.getAllWorkers(noAppNameJob.getJobId())).thenReturn(runtimeModelGenerator.getAllWorkers(noAppNameJob.getJobId()));
        when(apiOperations.getRunningWorkers(noAppNameJob.getJobId())).thenReturn(runtimeModelGenerator.getRunningWorkers(noAppNameJob.getJobId()));

        // Job with capacity group for which SLA is not defined
        V2JobMetadata badCapacityJob = runtimeModelGenerator.newJobMetadata(Parameters.JobType.Service, "badCapacityJob", "missingCapacityGroup");
        runtimeModelGenerator.scheduleJob(badCapacityJob.getJobId());
        runtimeModelGenerator.moveWorkerToState(badCapacityJob.getJobId(), 0, V2JobState.Started);
        when(apiOperations.getJobMetadata(badCapacityJob.getJobId())).thenReturn(badCapacityJob);
        when(apiOperations.getAllWorkers(badCapacityJob.getJobId())).thenReturn(runtimeModelGenerator.getAllWorkers(badCapacityJob.getJobId()));
        when(apiOperations.getRunningWorkers(badCapacityJob.getJobId())).thenReturn(runtimeModelGenerator.getRunningWorkers(badCapacityJob.getJobId()));

        when(apiOperations.getAllActiveJobs()).thenReturn(asSet(goodCapacityJob.getJobId(), noAppNameJob.getJobId(), badCapacityJob.getJobId()));

        // Evaluate
        ResourceConsumptionEvaluator evaluator = new ResourceConsumptionEvaluator(applicationSlaManagementService, apiOperations, config);

        Set<String> undefined = evaluator.getUndefinedCapacityGroups();
        assertThat(undefined).contains("missingCapacityGroup");

        CompositeResourceConsumption systemConsumption = evaluator.getSystemConsumption();
        Map<String, ResourceConsumption> tierConsumptions = systemConsumption.getContributors();
        assertThat(tierConsumptions).containsKeys(Tier.Critical.name(), Tier.Flex.name());

        // Critical capacity group
        CompositeResourceConsumption criticalConsumption = (CompositeResourceConsumption) findConsumption(
                systemConsumption, Tier.Critical.name(), CRITICAL_SLA_1.getAppName()
        ).get();
        assertThat(criticalConsumption.getCurrentConsumption()).isEqualTo(
                singleWorkerConsumptionOf(goodCapacityJob) // We have single worker in Started state
        );

        assertThat(criticalConsumption.getAllowedConsumption()).isEqualTo(ResourceDimensions.multiply(capacityGroupLimit(CRITICAL_SLA_1), (1 + BUFFER)));
        assertThat(criticalConsumption.isAboveLimit()).isTrue();

        // Default capacity group
        CompositeResourceConsumption defaultConsumption = (CompositeResourceConsumption) findConsumption(
                systemConsumption, Tier.Flex.name(), DEFAULT_SLA.getAppName()
        ).get();
        assertThat(defaultConsumption.getCurrentConsumption()).isEqualTo(
                ResourceDimensions.add(
                        singleWorkerConsumptionOf(noAppNameJob),
                        singleWorkerConsumptionOf(badCapacityJob)
                )
        );

        assertThat(defaultConsumption.getAllowedConsumption()).isEqualTo(ResourceDimensions.multiply(capacityGroupLimit(DEFAULT_SLA), (1 + BUFFER)));
        assertThat(defaultConsumption.isAboveLimit()).isFalse();

        // Not used capacity group
        CompositeResourceConsumption notUsedConsumption = (CompositeResourceConsumption) findConsumption(
                systemConsumption, Tier.Critical.name(), NOT_USED_SLA.getAppName()
        ).get();
        assertThat(notUsedConsumption.getCurrentConsumption()).isEqualTo(ResourceDimension.empty());
        assertThat(notUsedConsumption.getAllowedConsumption()).isEqualTo(ResourceDimensions.multiply(capacityGroupLimit(NOT_USED_SLA), (1 + BUFFER)));
        assertThat(notUsedConsumption.isAboveLimit()).isFalse();
    }
}