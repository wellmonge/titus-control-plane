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

package io.netflix.titus.master.scheduler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.fenzo.AutoScaleRule;
import com.netflix.fenzo.PreferentialNamedConsumableResourceSet;
import com.netflix.fenzo.ScaleDownAction;
import com.netflix.fenzo.ScaleDownConstraintEvaluator;
import com.netflix.fenzo.ScaleDownOrderEvaluator;
import com.netflix.fenzo.ScaleUpAction;
import com.netflix.fenzo.SchedulingResult;
import com.netflix.fenzo.TaskAssignmentResult;
import com.netflix.fenzo.TaskRequest;
import com.netflix.fenzo.TaskScheduler;
import com.netflix.fenzo.TaskSchedulingService;
import com.netflix.fenzo.VMAssignmentResult;
import com.netflix.fenzo.VirtualMachineCurrentState;
import com.netflix.fenzo.VirtualMachineLease;
import com.netflix.fenzo.queues.QAttributes;
import com.netflix.fenzo.queues.QueuableTask;
import com.netflix.fenzo.queues.TaskQueue;
import com.netflix.fenzo.queues.TaskQueueException;
import com.netflix.fenzo.queues.TaskQueueMultiException;
import com.netflix.fenzo.queues.TaskQueues;
import com.netflix.spectator.api.Counter;
import com.netflix.spectator.api.Registry;
import com.netflix.spectator.api.Timer;
import io.netflix.titus.api.agent.model.event.AgentInstanceGroupRemovedEvent;
import io.netflix.titus.api.agent.model.event.AgentInstanceGroupUpdateEvent;
import io.netflix.titus.api.agent.service.AgentManagementFunctions;
import io.netflix.titus.api.agent.service.AgentManagementService;
import io.netflix.titus.api.agent.service.AgentStatusMonitor;
import io.netflix.titus.api.jobmanager.model.job.Job;
import io.netflix.titus.api.jobmanager.model.job.JobFunctions;
import io.netflix.titus.api.jobmanager.model.job.JobModel;
import io.netflix.titus.api.jobmanager.model.job.Task;
import io.netflix.titus.api.jobmanager.model.job.TaskState;
import io.netflix.titus.api.jobmanager.model.job.TaskStatus;
import io.netflix.titus.api.jobmanager.model.job.TwoLevelResource;
import io.netflix.titus.api.jobmanager.service.JobManagerException;
import io.netflix.titus.api.jobmanager.service.V3JobOperations;
import io.netflix.titus.api.model.v2.WorkerNaming;
import io.netflix.titus.api.store.v2.InvalidJobException;
import io.netflix.titus.common.runtime.TitusRuntime;
import io.netflix.titus.common.util.CollectionsExt;
import io.netflix.titus.common.util.ExceptionExt;
import io.netflix.titus.common.util.guice.annotation.Activator;
import io.netflix.titus.common.util.spectator.SpectatorExt;
import io.netflix.titus.common.util.tuple.Pair;
import io.netflix.titus.master.VirtualMachineMasterService;
import io.netflix.titus.master.config.MasterConfiguration;
import io.netflix.titus.master.job.JobMgr;
import io.netflix.titus.master.job.V2JobOperations;
import io.netflix.titus.master.jobmanager.service.TaskInfoFactory;
import io.netflix.titus.master.jobmanager.service.common.V3QueueableTask;
import io.netflix.titus.master.model.job.TitusQueuableTask;
import io.netflix.titus.master.scheduler.autoscale.DefaultAutoScaleController;
import io.netflix.titus.master.scheduler.constraint.GlobalConstraintEvaluator;
import io.netflix.titus.master.scheduler.fitness.AgentFitnessCalculator;
import io.netflix.titus.master.store.InvalidJobStateChangeException;
import io.netflix.titus.master.taskmigration.TaskMigrator;
import io.netflix.titus.runtime.endpoint.v3.grpc.TaskAttributes;
import org.apache.mesos.Protos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.Scheduler;
import rx.Subscription;
import rx.functions.Action1;
import rx.schedulers.Schedulers;

import static io.netflix.titus.common.util.CollectionsExt.isNullOrEmpty;
import static io.netflix.titus.master.MetricConstants.METRIC_SCHEDULING_SERVICE;

@Singleton
public class DefaultSchedulingService implements SchedulingService {

    private static final String NO_CLUSTER = "no_cluster";

    private static final String METRIC_SLA_UPDATES = METRIC_SCHEDULING_SERVICE + "slaUpdates";

    private static final long STORE_UPDATE_TIMEOUT_MS = 5_000;

    public static final int IDLE_MACHINE_CPU_THRESHOLD = 8;
    public static final int IDLE_MACHINE_MEMORY_THRESHOLD = 10 * 1024;

    private final VirtualMachineMasterService virtualMachineService;
    private final MasterConfiguration config;
    private final SchedulerConfiguration schedulerConfiguration;
    private final V2JobOperations v2JobOperations;
    private final V3JobOperations v3JobOperations;
    private final VMOperations vmOps;
    private static final Logger logger = LoggerFactory.getLogger(DefaultSchedulingService.class);
    private static final long vmCurrentStatesCheckIntervalMillis = 10_000L;
    private TaskScheduler taskScheduler;
    private TaskSchedulingService schedulingService;
    private TaskQueue taskQueue;
    private Subscription slaUpdateSubscription;
    // Choose this max delay between scheduling iterations with care. Making it too short makes scheduler do unnecessary
    // work when assignments are not possible. On the other hand, making it too long will delay other aspects such as
    // triggerring autoscale actions, expiring mesos offers, etc.
    private static final long MAX_DELAY_MILLIS_BETWEEN_SCHEDULING_ITERATIONS = 5_000L;
    private final ConstraintsEvaluators constraintsEvaluators;
    private final TaskToClusterMapper taskToClusterMapper = new TaskToClusterMapper();

    private final Counter taskFailsRequestCounterId;
    private final Counter taskFailsRequestFailedCounterId;
    private final Counter numWorkersLaunchedId;
    private final Counter numResourceOffersReceivedId;
    private final Counter numResourceOffersRejectedId;
    private final Counter numAutoScaleUpActionsId;
    private final Counter numAutoScaleDownActionsId;

    private final AtomicInteger totalActiveAgents;
    private final AtomicLong totalDisabledAgents;
    private final AtomicLong minDisableDuration;
    private final AtomicLong maxDisableDuration;
    private final AtomicLong totalAvailableCPUs;
    private final AtomicLong totalAllocatedCPUs;
    private final AtomicLong totalAvailableMemory;
    private final AtomicLong totalAllocatedMemory;
    private final AtomicLong totalAvailableNwMbps;
    private final AtomicLong totalAllocatedNwMbps;
    private final AtomicLong cpuUtilization;
    private final AtomicLong memoryUtilization;
    private final AtomicLong networkUtilization;
    private final AtomicLong dominantResUtilization;
    private final AtomicLong numPendingWorkers;

    private final Timer numSchedulerRunMillis;
    private final Timer numTotalWaitTimeMillis;

    private ConcurrentHashMap<String, AtomicInteger> idleAgentsByCluster = new ConcurrentHashMap<>();

    private final ConcurrentMap<Integer, List<VirtualMachineCurrentState>> vmCurrentStatesMap;
    private final GlobalConstraintEvaluator globalConstraintsEvaluator;
    private final Scheduler threadScheduler;
    private Action1<QueuableTask> taskQueueAction;
    private final TitusRuntime titusRuntime;
    private final BlockingQueue<Map<String, com.netflix.fenzo.functions.Action1<List<TaskAssignmentResult>>>>
            taskFailuresActions = new LinkedBlockingQueue<>(5);
    private final TierSlaUpdater tierSlaUpdater;
    private final Registry registry;
    private final TaskMigrator taskMigrator;
    private final AgentManagementService agentManagementService;
    private final DefaultAutoScaleController autoScaleController;
    private final AgentStatusMonitor agentStatusMonitor;
    private final TaskInfoFactory<Protos.TaskInfo> v3TaskInfoFactory;
    private Subscription vmStateUpdateSubscription;

    @Inject
    public DefaultSchedulingService(V2JobOperations v2JobOperations,
                                    V3JobOperations v3JobOperations,
                                    AgentManagementService agentManagementService,
                                    DefaultAutoScaleController autoScaleController,
                                    AgentStatusMonitor agentStatusMonitor,
                                    TaskInfoFactory<Protos.TaskInfo> v3TaskInfoFactory,
                                    VMOperations vmOps,
                                    final VirtualMachineMasterService virtualMachineService,
                                    MasterConfiguration config,
                                    SchedulerConfiguration schedulerConfiguration,
                                    GlobalConstraintEvaluator globalConstraintsEvaluator,
                                    ConstraintsEvaluators constraintsEvaluators,
                                    TierSlaUpdater tierSlaUpdater,
                                    Registry registry,
                                    ScaleDownOrderEvaluator scaleDownOrderEvaluator,
                                    Map<ScaleDownConstraintEvaluator, Double> weightedScaleDownConstraintEvaluators,
                                    TaskMigrator taskMigrator,
                                    TitusRuntime titusRuntime) {
        this(v2JobOperations, v3JobOperations, agentManagementService, autoScaleController, agentStatusMonitor, v3TaskInfoFactory, vmOps,
                virtualMachineService, config, schedulerConfiguration,
                globalConstraintsEvaluator, constraintsEvaluators,
                Schedulers.computation(),
                tierSlaUpdater, registry, scaleDownOrderEvaluator, weightedScaleDownConstraintEvaluators, taskMigrator,
                titusRuntime
        );
    }

    public DefaultSchedulingService(V2JobOperations v2JobOperations,
                                    V3JobOperations v3JobOperations,
                                    AgentManagementService agentManagementService,
                                    DefaultAutoScaleController autoScaleController,
                                    AgentStatusMonitor agentStatusMonitor,
                                    TaskInfoFactory<Protos.TaskInfo> v3TaskInfoFactory,
                                    VMOperations vmOps,
                                    final VirtualMachineMasterService virtualMachineService,
                                    MasterConfiguration config,
                                    SchedulerConfiguration schedulerConfiguration,
                                    GlobalConstraintEvaluator globalConstraintsEvaluator,
                                    ConstraintsEvaluators constraintsEvaluators,
                                    Scheduler threadScheduler,
                                    TierSlaUpdater tierSlaUpdater,
                                    Registry registry,
                                    ScaleDownOrderEvaluator scaleDownOrderEvaluator,
                                    Map<ScaleDownConstraintEvaluator, Double> weightedScaleDownConstraintEvaluators,
                                    TaskMigrator taskMigrator,
                                    TitusRuntime titusRuntime) {
        this.v2JobOperations = v2JobOperations;
        this.v3JobOperations = v3JobOperations;
        this.agentManagementService = agentManagementService;
        this.autoScaleController = autoScaleController;
        this.agentStatusMonitor = agentStatusMonitor;
        this.v3TaskInfoFactory = v3TaskInfoFactory;
        this.vmOps = vmOps;
        this.virtualMachineService = virtualMachineService;
        this.config = config;
        this.schedulerConfiguration = schedulerConfiguration;
        this.constraintsEvaluators = constraintsEvaluators;
        this.threadScheduler = threadScheduler;
        this.tierSlaUpdater = tierSlaUpdater;
        this.registry = registry;
        this.taskMigrator = taskMigrator;
        this.titusRuntime = titusRuntime;
        this.globalConstraintsEvaluator = globalConstraintsEvaluator;

        TaskScheduler.Builder schedulerBuilder = new TaskScheduler.Builder()
                .withLeaseRejectAction(virtualMachineLease -> {
                    logger.info("Rejecting lease: " + virtualMachineLease.getId() + " on host: " + virtualMachineLease.hostname() +
                            " from " + (System.currentTimeMillis() - virtualMachineLease.getOfferedTime()) / 1000L + " secs ago");
                    virtualMachineService.rejectLease(virtualMachineLease);
                })
                .withLeaseOfferExpirySecs(config.getMesosLeaseOfferExpirySecs())
                .withFitnessCalculator(new AgentFitnessCalculator())
                .withFitnessGoodEnoughFunction(AgentFitnessCalculator.fitnessGoodEnoughFunc)
                .withAutoScaleByAttributeName(config.getAutoscaleByAttributeName())
                .withScaleDownOrderEvaluator(scaleDownOrderEvaluator)
                .withWeightedScaleDownConstraintEvaluators(weightedScaleDownConstraintEvaluators);

        taskScheduler = setupTaskSchedulerAndAutoScaler(virtualMachineService.getLeaseRescindedObservable(), schedulerBuilder);
        taskQueue = TaskQueues.createTieredQueue(2);
        schedulingService = setupTaskSchedulingService(taskScheduler);
        virtualMachineService.setVMLeaseHandler(schedulingService::addLeases);
        taskQueueAction = taskQueue::queueTask;

        taskFailsRequestCounterId = registry.counter(METRIC_SCHEDULING_SERVICE + "taskFailuresRequests");
        taskFailsRequestFailedCounterId = registry.counter(METRIC_SCHEDULING_SERVICE + "taskFailuresRequestsLimitReached");
        numWorkersLaunchedId = registry.counter(METRIC_SCHEDULING_SERVICE + "numWorkersLaunched");
        numResourceOffersReceivedId = registry.counter(METRIC_SCHEDULING_SERVICE + "numResourceOffersReceived");
        numResourceOffersRejectedId = registry.counter(METRIC_SCHEDULING_SERVICE + "numResourceOffersRejected");
        numSchedulerRunMillis = registry.timer(METRIC_SCHEDULING_SERVICE + "schedulerRunMillis");
        numTotalWaitTimeMillis = registry.timer(METRIC_SCHEDULING_SERVICE + "numTotalWaitTimeMillis");
        numAutoScaleUpActionsId = registry.counter(METRIC_SCHEDULING_SERVICE + "numAutoScaleUpActions");
        numAutoScaleDownActionsId = registry.counter(METRIC_SCHEDULING_SERVICE + "numAutoScaleDownActions");
        totalActiveAgents = registry.gauge(METRIC_SCHEDULING_SERVICE + "totalActiveAgents", new AtomicInteger(0));
        totalDisabledAgents = registry.gauge(METRIC_SCHEDULING_SERVICE + "totalDisabledAgents", new AtomicLong(0));
        minDisableDuration = registry.gauge(METRIC_SCHEDULING_SERVICE + "minDisableDuration", new AtomicLong(0));
        maxDisableDuration = registry.gauge(METRIC_SCHEDULING_SERVICE + "maxDisableDuration", new AtomicLong(0));
        totalAvailableCPUs = registry.gauge(METRIC_SCHEDULING_SERVICE + "totalAvailableCPUs", new AtomicLong(0));
        totalAllocatedCPUs = registry.gauge(METRIC_SCHEDULING_SERVICE + "totalAllocatedCPUs", new AtomicLong(0));
        totalAvailableMemory = registry.gauge(METRIC_SCHEDULING_SERVICE + "totalAvailableMemory", new AtomicLong(0));
        totalAllocatedMemory = registry.gauge(METRIC_SCHEDULING_SERVICE + "totalAllocatedMemory", new AtomicLong(0));
        totalAvailableNwMbps = registry.gauge(METRIC_SCHEDULING_SERVICE + "totalAvailableNwMbps", new AtomicLong(0));
        totalAllocatedNwMbps = registry.gauge(METRIC_SCHEDULING_SERVICE + "totalAllocatedNwMbps", new AtomicLong(0));
        cpuUtilization = registry.gauge(METRIC_SCHEDULING_SERVICE + "cpuUtilization", new AtomicLong(0));
        memoryUtilization = registry.gauge(METRIC_SCHEDULING_SERVICE + "memoryUtilization", new AtomicLong(0));
        networkUtilization = registry.gauge(METRIC_SCHEDULING_SERVICE + "networkUtilization", new AtomicLong(0));
        dominantResUtilization = registry.gauge(METRIC_SCHEDULING_SERVICE + "dominantResUtilization", new AtomicLong(0));
        numPendingWorkers = registry.gauge(METRIC_SCHEDULING_SERVICE + "pendingWorkers", new AtomicLong(0));

        vmCurrentStatesMap = new ConcurrentHashMap<>();
    }

    private TaskSchedulingService setupTaskSchedulingService(TaskScheduler taskScheduler) {
        TaskSchedulingService.Builder builder = new TaskSchedulingService.Builder()
                .withLoopIntervalMillis(schedulerConfiguration.getSchedulerIterationIntervalMs())
                .withMaxDelayMillis(MAX_DELAY_MILLIS_BETWEEN_SCHEDULING_ITERATIONS) // sort of rate limiting when no assignments were made and no new offers available
                .withTaskQueue(taskQueue)
                .withPreSchedulingLoopHook(this::preSchedulingHook)
                .withSchedulingResultCallback(this::schedulingResultsHandler)
                .withTaskScheduler(taskScheduler);
        if (schedulerConfiguration.isOptimizingShortfallEvaluatorEnabled()) {
            builder.withOptimizingShortfallEvaluator();
        }
        return builder.build();
    }

    @Override
    public GlobalConstraintEvaluator getGlobalConstraints() {
        return globalConstraintsEvaluator;
    }

    @Override
    public ConstraintsEvaluators getConstraintsEvaluators() {
        return constraintsEvaluators;
    }

    private void setupVmOps(final String attrName) {
        taskScheduler.setActiveVmGroupAttributeName(config.getActiveSlaveAttributeName());
        vmOps.setJobsOnVMsGetter(() -> {
            List<VMOperations.JobsOnVMStatus> result = new ArrayList<>();
            final List<VirtualMachineCurrentState> vmCurrentStates = vmCurrentStatesMap.get(0);
            if (vmCurrentStates != null && !vmCurrentStates.isEmpty()) {
                for (VirtualMachineCurrentState currentState : vmCurrentStates) {
                    final VirtualMachineLease currAvailableResources = currentState.getCurrAvailableResources();
                    if (currAvailableResources != null) {
                        final Protos.Attribute attribute = currAvailableResources.getAttributeMap().get(attrName);
                        if (attribute != null) {
                            VMOperations.JobsOnVMStatus s =
                                    new VMOperations.JobsOnVMStatus(currAvailableResources.hostname(),
                                            attribute.getText().getValue());
                            for (TaskRequest r : currentState.getRunningTasks()) {
                                if (r instanceof ScheduledRequest) {
                                    // TODO need to remove dependency on WorkerNaming
                                    final WorkerNaming.JobWorkerIdPair j = WorkerNaming.getJobAndWorkerId(r.getId());
                                    s.addJob(new VMOperations.JobOnVMInfo(j.jobId, -1, j.workerIndex, j.workerNumber));
                                } else if (r instanceof V3QueueableTask) {
                                    s.addJob(new VMOperations.JobOnVMInfo(((V3QueueableTask) r).getJob().getId(), -1, 0, 0));
                                }
                            }
                            result.add(s);
                        }
                    }
                }
            }
            return result;
        });

        titusRuntime.persistentStream(AgentManagementFunctions.observeActiveInstanceGroupIds(agentManagementService))
                .subscribe(ids -> {
                    taskScheduler.setActiveVmGroups(ids);
                    logger.info("Updating Fenzo taskScheduler active instance group list to: {}", ids);
                });
    }

    private TaskScheduler setupTaskSchedulerAndAutoScaler(Observable<String> vmLeaseRescindedObservable,
                                                          TaskScheduler.Builder schedulerBuilder) {
        int minMinIdle = 4;
        schedulerBuilder = schedulerBuilder
                .withAutoScalerMapHostnameAttributeName(config.getAutoScalerMapHostnameAttributeName())
                .withDelayAutoscaleUpBySecs(schedulerConfiguration.getDelayAutoScaleUpBySecs())
                .withDelayAutoscaleDownBySecs(schedulerConfiguration.getDelayAutoScaleDownBySecs());
        schedulerBuilder = schedulerBuilder.withMaxOffersToReject(Math.max(1, minMinIdle));
        final TaskScheduler scheduler = schedulerBuilder.build();
        vmLeaseRescindedObservable
                .doOnNext(s -> {
                    if (s.equals("ALL")) {
                        scheduler.expireAllLeases();
                    } else {
                        scheduler.expireLease(s);
                    }
                })
                .subscribe();
        scheduler.setAutoscalerCallback(action -> {
            try {
                switch (action.getType()) {
                    case Up:
                        numAutoScaleUpActionsId.increment();
                        autoScaleController.handleScaleUpAction(action.getRuleName(), ((ScaleUpAction) action).getScaleUpCount());
                        break;
                    case Down:
                        numAutoScaleDownActionsId.increment();

                        // The API here is misleading. The 'hosts' attribute of ScaleDownAction contains instance ids.
                        Set<String> idsToTerminate = new HashSet<>(((ScaleDownAction) action).getHosts());
                        Pair<Set<String>, Set<String>> resultPair = autoScaleController.handleScaleDownAction(action.getRuleName(), idsToTerminate);
                        Set<String> notTerminatedInstances = resultPair.getRight();

                        // Now we need to convert instance ids to host names, as this is what the scheduler expects
                        notTerminatedInstances.forEach(id ->
                                ExceptionExt.silent(() -> taskScheduler.enableVM(agentManagementService.getAgentInstance(id).getIpAddress()))
                        );
                        break;
                }
            } catch (Exception e) {
                logger.warn("Will continue after exception calling autoscale action observer: " + e.getMessage(), e);
            }
        });
        return scheduler;
    }

    private void setupAutoscaleRulesDynamicUpdater() {
        titusRuntime.persistentStream(agentManagementService.events(true)).subscribe(
                next -> {
                    try {
                        if (next instanceof AgentInstanceGroupUpdateEvent) {
                            AgentInstanceGroupUpdateEvent updateEvent = (AgentInstanceGroupUpdateEvent) next;
                            io.netflix.titus.api.agent.model.AutoScaleRule rule = updateEvent.getAgentInstanceGroup().getAutoScaleRule();

                            logger.info("Setting up autoscale rule for the agent instance group {}: {}", updateEvent.getAgentInstanceGroup().getId(), rule);
                            taskScheduler.addOrReplaceAutoScaleRule(new FenzoAutoScaleRuleWrapper(rule));
                        } else if (next instanceof AgentInstanceGroupRemovedEvent) {
                            String instanceGroupId = ((AgentInstanceGroupRemovedEvent) next).getInstanceGroupId();

                            logger.info("Removing autoscale rule for the agent instance group {}", instanceGroupId);
                            taskScheduler.removeAutoScaleRule(instanceGroupId);
                        }
                    } catch (Exception e) {
                        logger.warn("Unexpected error updating cluster autoscale rules: " + e.getMessage());
                    }
                }
        );
    }

    private void setupVmStatesUpdate() {
        this.vmStateUpdateSubscription = threadScheduler.createWorker().schedulePeriodically(
                () -> {
                    try {
                        schedulingService.requestVmCurrentStates(
                                states -> {
                                    vmCurrentStatesMap.put(0, states);
                                    verifyAndReportResUsageMetrics(states);
                                    checkInactiveVMs(states);
                                    vmOps.setAgentInfos(states);
                                }
                        );
                    } catch (TaskQueueException e) {
                        logger.error(e.getMessage(), e);
                    }
                },
                vmCurrentStatesCheckIntervalMillis, vmCurrentStatesCheckIntervalMillis,
                TimeUnit.MILLISECONDS
        );
    }

    @Override
    public void registerTaskQListAction(
            com.netflix.fenzo.functions.Action1<Map<TaskQueue.TaskState, Collection<QueuableTask>>> action
    ) throws IllegalStateException {
        try {
            schedulingService.requestAllTasks(action);
        } catch (TaskQueueException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public void registerTaskFailuresAction(
            String taskId, com.netflix.fenzo.functions.Action1<List<TaskAssignmentResult>> action
    ) throws IllegalStateException {
        if (!taskFailuresActions.offer(Collections.singletonMap(taskId, action))) {
            taskFailsRequestFailedCounterId.increment();
            throw new IllegalStateException("Too many concurrent requests");
        } else {
            taskFailsRequestCounterId.increment();
        }
    }

    private void preSchedulingHook() {
        globalConstraintsEvaluator.prepare();
        setupTierAutoscalerConfig();
    }

    private void setupTierAutoscalerConfig() {
        taskToClusterMapper.update(agentManagementService);
        schedulingService.setTaskToClusterAutoScalerMapGetter(taskToClusterMapper.getMapperFunc1());
    }

    private void checkIfExitOnSchedError(String s) {
        if (schedulerConfiguration.isExitUponFenzoSchedulingErrorEnabled()) {
            logger.error("Exiting due to fatal error: " + s);
            CountDownLatch latch = new CountDownLatch(3);
            final ObjectMapper mapper = new ObjectMapper();
            try {
                schedulingService.requestVmCurrentStates(currentStates ->
                        printFenzoStateDump(mapper, "agent current states", currentStates, latch));
                schedulingService.requestAllTasks(taskStateCollectionMap ->
                        printFenzoStateDump(mapper, "task queue", taskStateCollectionMap, latch));
                schedulingService.requestResourceStatus(resourceStatus ->
                        printFenzoStateDump(mapper, "resource status", resourceStatus, latch));
            } catch (TaskQueueException e) {
                logger.error("Couldn't request state dump from Fenzo: " + e.getMessage(), e);
            }
            try {
                if (!latch.await(MAX_DELAY_MILLIS_BETWEEN_SCHEDULING_ITERATIONS * 3, TimeUnit.MILLISECONDS)) {
                    logger.error("Timeout waiting for Fenzo state dump");
                }
            } catch (InterruptedException e) {
                logger.error("Interrupted while waiting for Fenzo state dump: " + e.getMessage(), e);
            }
            System.exit(3);
        }
    }

    private void printFenzoStateDump(ObjectMapper mapper, String what, Object dump, CountDownLatch latch) {
        try {
            logger.info("Fenzo state dump of " + what + ": " + mapper.writeValueAsString(dump));
        } catch (JsonProcessingException e) {
            logger.error("Error dumping Fenzo state for " + what + ": " + e.getMessage(), e);
        } finally {
            latch.countDown();
        }
    }

    private void schedulingResultsHandler(SchedulingResult schedulingResult) {
        int workersLaunched = 0;
        if (!schedulingResult.getExceptions().isEmpty()) {
            logger.error("Exceptions in scheduling iteration:");
            for (Exception e : schedulingResult.getExceptions()) {
                if (e instanceof TaskQueueMultiException) {
                    for (Exception ee : ((TaskQueueMultiException) e).getExceptions()) {
                        logger.error(ee.getMessage(), ee);
                    }
                } else {
                    logger.error(e.getMessage(), e);
                }
            }
            checkIfExitOnSchedError("One or more errors in Fenzo scheduling iteration");
            return;
        }
        SchedulerCounters.getInstance().incrementResourceAllocationTrials(schedulingResult.getNumAllocations());
        Map<String, VMAssignmentResult> assignmentResultMap = schedulingResult.getResultMap();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, VMAssignmentResult> aResult : assignmentResultMap.entrySet()) {
            final List<TaskAssignmentResult> launchedTasks = launchTasks(aResult.getValue().getTasksAssigned(), aResult.getValue().getLeasesUsed());
            for (TaskAssignmentResult r : launchedTasks) {
                final TitusQueuableTask task = (TitusQueuableTask) r.getRequest();
                JobMgr jobMgr = v2JobOperations.getJobMgrFromTaskId(task.getId());
                if (jobMgr != null) {
                    numTotalWaitTimeMillis.record(now - jobMgr.getTaskCreateTime(task.getId()), TimeUnit.MILLISECONDS);
                }
            }
            workersLaunched += launchedTasks.size();
        }
        // for workers that didn't get scheduled, rate limit them
        int workersFailed = 0;
        List<Map<String, com.netflix.fenzo.functions.Action1<List<TaskAssignmentResult>>>> failActions = new ArrayList<>();
        taskFailuresActions.drainTo(failActions);
        for (Map.Entry<TaskRequest, List<TaskAssignmentResult>> entry : schedulingResult.getFailures().entrySet()) {
            final TitusQueuableTask req = (TitusQueuableTask) entry.getKey();
            workersFailed++;
            if (!failActions.isEmpty()) {
                final Iterator<Map<String, com.netflix.fenzo.functions.Action1<List<TaskAssignmentResult>>>> iterator =
                        failActions.iterator();
                while (iterator.hasNext()) { // iterate over all of them, there could be multiple requests with the same taskId
                    final Map<String, com.netflix.fenzo.functions.Action1<List<TaskAssignmentResult>>> next = iterator.next();
                    final String reqId = next.keySet().iterator().next();
                    final com.netflix.fenzo.functions.Action1<List<TaskAssignmentResult>> a = next.values().iterator().next();
                    if (req.getId().equals(reqId)) {
                        a.call(entry.getValue());
                        iterator.remove();
                    }
                }
            }
        }
        if (!failActions.isEmpty()) { // If no such tasks for the registered actions, call them with null result
            failActions.forEach(action -> action.values().iterator().next().call(null));
        }
        numPendingWorkers.set(workersFailed);
        numResourceOffersReceivedId.increment(schedulingResult.getLeasesAdded());
        numResourceOffersRejectedId.increment(schedulingResult.getLeasesRejected());
        if ((workersFailed + workersLaunched) > 0) {
            numWorkersLaunchedId.increment(workersLaunched);
            numSchedulerRunMillis.record(schedulingResult.getRuntime(), TimeUnit.MILLISECONDS);
        }
        totalActiveAgents.set(schedulingResult.getTotalVMsCount());
        SchedulerCounters.getInstance().endIteration(workersFailed + workersLaunched, workersLaunched, assignmentResultMap.size(),
                schedulingResult.getLeasesRejected());
        if (SchedulerCounters.getInstance().getCounter().getIterationNumber() % 100 == 0) {
            logger.info("Scheduling iteration result: " + SchedulerCounters.getInstance().toJsonString());
        }
    }

    private List<TaskAssignmentResult> launchTasks(Collection<TaskAssignmentResult> requests, List<VirtualMachineLease> leases) {
        List<TaskAssignmentResult> launchedTasks = new LinkedList<>();
        final List<Protos.TaskInfo> taskInfoList = new LinkedList<>();
        for (TaskAssignmentResult assignmentResult : requests) {

            List<PreferentialNamedConsumableResourceSet.ConsumeResult> consumeResults = assignmentResult.getrSets();
            TitusQueuableTask task = (TitusQueuableTask) assignmentResult.getRequest();

            boolean taskFound;
            PreferentialNamedConsumableResourceSet.ConsumeResult consumeResult = consumeResults.get(0);
            if (JobFunctions.isV2Task(task.getId())) {
                final JobMgr jobMgr = v2JobOperations.getJobMgrFromTaskId(task.getId());
                taskFound = jobMgr != null;
                if (taskFound) {
                    final VirtualMachineLease lease = leases.get(0);
                    try {
                        taskInfoList.add(jobMgr.setLaunchedAndCreateTaskInfo(task, lease.hostname(), getAttributesMap(lease), lease.getOffer().getSlaveId(),
                                consumeResult, assignmentResult.getAssignedPorts()));
                    } catch (InvalidJobStateChangeException | InvalidJobException e) {
                        logger.warn("Not launching task due to error setting state to launched for " + task.getId() + " - " +
                                e.getMessage());
                    } catch (Exception e) {
                        // unexpected error creating task info
                        String msg = "fatal error creating taskInfo for " + task.getId() + ": " + e.getMessage();
                        logger.warn("Killing job " + jobMgr.getJobId() + ": " + msg, e);
                        jobMgr.killJob("SYSTEM", msg);
                    }
                }
            } else { // V3 task
                Optional<Pair<Job<?>, Task>> v3JobAndTask = v3JobOperations.findTaskById(task.getId());
                taskFound = v3JobAndTask.isPresent();
                if (taskFound) {
                    Job v3Job = v3JobAndTask.get().getLeft();
                    Task v3Task = v3JobAndTask.get().getRight();
                    final VirtualMachineLease lease = leases.get(0);
                    try {
                        Protos.TaskInfo taskInfo = v3TaskInfoFactory.newTaskInfo(
                                task, v3Job, v3Task, lease.hostname(), getAttributesMap(lease), lease.getOffer().getSlaveId(),
                                consumeResult);

                        TaskStatus taskStatus = JobModel.newTaskStatus()
                                .withState(TaskState.Launched)
                                .withReasonCode("scheduled")
                                .withReasonMessage("Fenzo task placement")
                                .build();

                        TwoLevelResource twoLevelResource = TwoLevelResource.newBuilder()
                                .withName(consumeResult.getAttrName())
                                .withValue(consumeResult.getResName())
                                .withIndex(consumeResult.getIndex())
                                .build();

                        Map<String, String> taskContext = new HashMap<>();
                        taskContext.put(TaskAttributes.TASK_ATTRIBUTES_AGENT_HOST, lease.hostname());

                        Map<String, Protos.Attribute> attributes = lease.getAttributeMap();
                        if (!isNullOrEmpty(attributes)) {
                            getAttributesMap(lease).forEach((k, v) -> taskContext.put("agent." + k, v));

                            // TODO Some agent attribute names are configurable, some not. We need to clean this up.
                            addAttributeToContext(attributes, config.getHostZoneAttributeName()).ifPresent(value ->
                                    taskContext.put(TaskAttributes.TASK_ATTRIBUTES_AGENT_ZONE, value)
                            );
                            addAttributeToContext(attributes, "id").ifPresent(value ->
                                    taskContext.put(TaskAttributes.TASK_ATTRIBUTES_AGENT_INSTANCE_ID, value)
                            );
                        }

                        Function<Task, Task> changeFunction = oldTask -> {
                            if (oldTask.getStatus().getState() != TaskState.Accepted) {
                                throw JobManagerException.unexpectedTaskState(oldTask, TaskState.Accepted);
                            }
                            return JobFunctions.updateTaskAfterScheduling(oldTask, taskStatus, twoLevelResource, taskContext);
                        };
                        boolean updated = v3JobOperations.updateTaskAfterStore(task.getId(), changeFunction).await(STORE_UPDATE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                        if (updated) {
                            taskInfoList.add(taskInfo);
                        } else {
                            killBrokenTask(task, "store update timeout");
                            logger.error("Timed out during writing task {} (job {}) status update to the store", task.getId(), v3Job.getId());
                        }
                    } catch (Exception e) {
                        killBrokenTask(task, e.toString());
                        logger.error("Fatal error when creating TaskInfo for {}", task.getId(), e);
                    }
                }
            }
            if (!taskFound) {
                // job must have been terminated, remove task from Fenzo
                logger.warn("Rejecting assignment and removing task after not finding jobMgr for " + task.getId());
                schedulingService.removeTask(task.getId(), task.getQAttributes(), assignmentResult.getHostname());
            }
        }
        if (taskInfoList.isEmpty()) {
            leases.forEach(virtualMachineService::rejectLease);
        } else {
            virtualMachineService.launchTasks(taskInfoList, leases);
        }
        return launchedTasks;
    }

    private void killBrokenTask(TitusQueuableTask task, String reason) {
        v3JobOperations.killTask(task.getId(), false, String.format("Failed to launch task %s due to %s", task.getId(), reason)).subscribe(
                next -> {
                },
                e -> logger.warn("Failed to terminate task {}", task.getId(), e),
                () -> logger.warn("Terminated task {} as launch operation could not be completed", task.getId())
        );
    }

    private Optional<String> addAttributeToContext(Map<String, Protos.Attribute> attributes, String name) {
        Protos.Attribute attribute = attributes.get(name);
        return (attribute != null) ? Optional.of(attribute.getText().getValue()) : Optional.empty();
    }

    private Map<String, String> getAttributesMap(VirtualMachineLease virtualMachineLease) {
        final Map<String, Protos.Attribute> attributeMap = virtualMachineLease.getAttributeMap();
        final Map<String, String> result = new HashMap<>();
        if (!attributeMap.isEmpty()) {
            for (Map.Entry<String, Protos.Attribute> entry : attributeMap.entrySet()) {
                result.put(entry.getKey(), entry.getValue().getText().getValue());
            }
        }
        return result;
    }

    @Override
    public Action1<QueuableTask> getTaskQueueAction() {
        return taskQueueAction;
    }

    @Override
    public void removeTask(String taskid, QAttributes qAttributes, String hostname) {
        schedulingService.removeTask(taskid, qAttributes, hostname);
    }

    @Override
    public void initRunningTask(QueuableTask task, String hostname) {
        schedulingService.initializeRunningTask(task, hostname);
    }

    @Activator
    public Observable<Void> enterActiveMode() {
        logger.info("Scheduling service starting now");

        setupVmOps(config.getActiveSlaveAttributeName());
        setupAutoscaleRulesDynamicUpdater();

        this.slaUpdateSubscription = tierSlaUpdater.tieredQueueSlaUpdates()
                .compose(SpectatorExt.subscriptionMetrics(METRIC_SLA_UPDATES, DefaultSchedulingService.class, registry))
                .subscribe(
                        update -> {
                            try {
                                taskQueue.setSla(update);
                            } catch (Throwable e) {
                                logger.error("Unexpected error in SLA update routine", e);
                            }
                        },
                        e -> logger.error("Unexpected error in SLA update routine", e)
                );

        if (schedulerConfiguration.isSchedulerEnabled()) {
            logger.info("Starting the scheduling service");
            schedulingService.start();
        } else {
            logger.info("Not starting the scheduling service");
        }

        setupVmStatesUpdate();
        activateAgentStatusMonitoring();

        return Observable.empty();
    }

    private void activateAgentStatusMonitoring() {
        agentStatusMonitor.monitor().subscribe(
                status -> {
                    // Update enable/disable information only for agents known to Fenzo
                    List<VirtualMachineCurrentState> vmsList = getVmCurrentStates();
                    if (CollectionsExt.isNullOrEmpty(vmsList)) {
                        return;
                    }
                    for (VirtualMachineCurrentState vms : vmsList) {
                        String agentHostname = status.getInstance().getIpAddress();
                        if (vms.getHostname().equals(agentHostname)) {
                            switch (status.getStatusCode()) {
                                case Healthy:
                                    logger.info("Enabling agent {}", agentHostname);
                                    taskScheduler.enableVM(agentHostname);
                                    break;
                                case Unhealthy:
                                    logger.info("Disabling agent {} for {}ms", agentHostname, status.getDisableTime());
                                    taskScheduler.disableVM(agentHostname, status.getDisableTime());
                                    break;
                            }
                        }
                    }
                },
                e -> logger.error("Agent status monitoring stream terminated with an error", e),
                () -> logger.info("Agent status monitoring stream onCompleted")
        );
    }

    public List<VirtualMachineCurrentState> getVmCurrentStates() {
        return vmCurrentStatesMap.get(0);
    }

    private void verifyAndReportResUsageMetrics(List<VirtualMachineCurrentState> vmCurrentStates) {
        double totalCPU = 0.0;
        double usedCPU = 0.0;
        double totalMemory = 0.0;
        double usedMemory = 0.0;
        double totalNwMbps = 0.0;
        double usedNwMbps = 0.0;
        long totalDisabled = 0;
        long currentMinDisableDuration = 0;
        long currentMaxDisableDuration = 0;
        long now = System.currentTimeMillis();

        Map<String, Integer> idleAgentsByClusterName = new HashMap<>();

        for (VirtualMachineCurrentState state : vmCurrentStates) {
            final VirtualMachineLease currAvailableResources = state.getCurrAvailableResources();

            if (currAvailableResources != null) {
                totalCPU += currAvailableResources.cpuCores();
                totalMemory += currAvailableResources.memoryMB();
                totalNwMbps += currAvailableResources.networkMbps();
            }
            long disableDuration = state.getDisabledUntil() - now;
            if (disableDuration > 0) {
                totalDisabled++;
                currentMinDisableDuration = Math.min(currentMinDisableDuration, disableDuration);
                currentMaxDisableDuration = Math.max(currentMinDisableDuration, disableDuration);
            }
            final Collection<TaskRequest> runningTasks = state.getRunningTasks();
            if (runningTasks != null && !runningTasks.isEmpty()) {
                for (TaskRequest t : runningTasks) {
                    QueuableTask qt = (QueuableTask) t;
                    if (qt instanceof ScheduledRequest) {
                        final JobMgr jobMgr = v2JobOperations.getJobMgrFromTaskId(t.getId());
                        if (jobMgr == null || !jobMgr.isTaskValid(t.getId())) {
                            schedulingService.removeTask(qt.getId(), qt.getQAttributes(), state.getHostname());
                        } else {
                            usedCPU += t.getCPUs();
                            totalCPU += t.getCPUs();
                            usedMemory += t.getMemory();
                            totalMemory += t.getMemory();
                            usedNwMbps += t.getNetworkMbps();
                            totalNwMbps += t.getNetworkMbps();
                        }
                    } else if (qt instanceof V3QueueableTask) {
                        //TODO redo the metrics publishing but we should keep it the same as v2 for now
                        usedCPU += t.getCPUs();
                        totalCPU += t.getCPUs();
                        usedMemory += t.getMemory();
                        totalMemory += t.getMemory();
                        usedNwMbps += t.getNetworkMbps();
                        totalNwMbps += t.getNetworkMbps();
                    }
                }
            }
            if (currAvailableResources != null) {
                final Protos.Attribute clusterAttr = state.getCurrAvailableResources().getAttributeMap().get(config.getAutoscaleByAttributeName());
                String clusterName;
                AutoScaleRule rule = null;
                if (clusterAttr != null && clusterAttr.getText().hasValue()) {
                    clusterName = clusterAttr.getText().getValue();
                    rule = taskScheduler.getAutoScaleRules().stream()
                            .filter(r -> r.getRuleName().equals(clusterName))
                            .findFirst()
                            .orElse(null);
                } else {
                    clusterName = NO_CLUSTER;
                }

                boolean isIdle;
                if (rule != null) {
                    isIdle = !rule.idleMachineTooSmall(currAvailableResources);
                } else {
                    final boolean hasEnoughCpu = currAvailableResources.cpuCores() >= IDLE_MACHINE_CPU_THRESHOLD;
                    final boolean hasEnoughMemory = currAvailableResources.memoryMB() >= IDLE_MACHINE_MEMORY_THRESHOLD;
                    isIdle = hasEnoughCpu && hasEnoughMemory;
                }
                if (isIdle) {
                    if (idleAgentsByClusterName.containsKey(clusterName)) {
                        idleAgentsByClusterName.put(clusterName, idleAgentsByClusterName.get(clusterName) + 1);
                    } else {
                        idleAgentsByClusterName.put(clusterName, 1);
                    }
                }
            }
        }

        publishIdleAgentsMetric(idleAgentsByClusterName);
        totalDisabledAgents.set(totalDisabled);
        minDisableDuration.set(currentMinDisableDuration);
        maxDisableDuration.set(currentMaxDisableDuration);
        totalAvailableCPUs.set((long) totalCPU);
        totalAllocatedCPUs.set((long) usedCPU);
        cpuUtilization.set((long) (usedCPU * 100.0 / Math.max(1.0, totalCPU)));
        double DRU = usedCPU * 100.0 / totalCPU;
        totalAvailableMemory.set((long) totalMemory);
        totalAllocatedMemory.set((long) usedMemory);
        memoryUtilization.set((long) (usedMemory * 100.0 / Math.max(1.0, totalMemory)));
        DRU = Math.max(DRU, usedMemory * 100.0 / totalMemory);
        totalAvailableNwMbps.set((long) totalNwMbps);
        totalAllocatedNwMbps.set((long) usedNwMbps);
        networkUtilization.set((long) (usedNwMbps * 100.0 / Math.max(1.0, totalNwMbps)));
        DRU = Math.max(DRU, usedNwMbps * 100.0 / totalNwMbps);
        dominantResUtilization.set((long) DRU);
    }

    private void publishIdleAgentsMetric(Map<String, Integer> currentIdleAgentsByCluster) {
        for (Map.Entry<String, AtomicInteger> entry : idleAgentsByCluster.entrySet()) {
            entry.getValue().set(currentIdleAgentsByCluster.getOrDefault(entry.getKey(), 0));
        }
    }

    private void checkInactiveVMs(List<VirtualMachineCurrentState> vmCurrentStates) {
        logger.info("Checking on any workers on VMs that are not active anymore");
        List<VirtualMachineCurrentState> inactiveVmStates = VMStateMgr.getInactiveVMs(config.getActiveSlaveAttributeName(), agentManagementService, vmCurrentStates);

        // get all running tasks on the inactive vms
        Collection<TaskRequest> tasksToBeMigrated = inactiveVmStates.stream()
                .flatMap(ivm -> ivm.getRunningTasks().stream())
                .collect(Collectors.toList());

        // schedule the inactive tasks for migration
        taskMigrator.migrate(tasksToBeMigrated);

        // expire all leases on inactive vms
        for (VirtualMachineCurrentState inactiveVmState : inactiveVmStates) {
            VirtualMachineLease lease = inactiveVmState.getCurrAvailableResources();
            String vmHost = lease.hostname();
            logger.info("expiring all leases of inactive vm " + vmHost);
            taskScheduler.expireAllLeases(vmHost);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (slaUpdateSubscription != null) {
            slaUpdateSubscription.unsubscribe();
        }
        if (vmStateUpdateSubscription != null) {
            vmStateUpdateSubscription.unsubscribe();
        }
        taskScheduler.shutdown();
        schedulingService.shutdown();
    }

    @Override
    public TaskScheduler getTaskScheduler() {
        return taskScheduler;
    }
}