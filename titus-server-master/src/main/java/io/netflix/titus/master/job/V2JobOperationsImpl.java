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

package io.netflix.titus.master.job;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.inject.Injector;
import com.netflix.fenzo.triggers.TriggerOperator;
import com.netflix.spectator.api.Counter;
import com.netflix.spectator.api.Registry;
import io.netflix.titus.api.audit.model.AuditLogEvent;
import io.netflix.titus.api.jobmanager.model.job.sanitizer.JobConfiguration;
import io.netflix.titus.api.model.v2.V2JobDefinition;
import io.netflix.titus.api.model.v2.V2JobState;
import io.netflix.titus.api.model.v2.WorkerNaming;
import io.netflix.titus.api.model.v2.parameter.Parameters;
import io.netflix.titus.api.model.v2.parameter.Parameters.JobType;
import io.netflix.titus.api.store.v2.InvalidJobException;
import io.netflix.titus.api.store.v2.V2WorkerMetadata;
import io.netflix.titus.common.util.guice.annotation.Activator;
import io.netflix.titus.common.util.rx.eventbus.RxEventBus;
import io.netflix.titus.master.ApiOperations;
import io.netflix.titus.master.JobSchedulingInfo;
import io.netflix.titus.master.MetricConstants;
import io.netflix.titus.master.VirtualMachineMasterService;
import io.netflix.titus.api.audit.service.AuditLogService;
import io.netflix.titus.master.config.MasterConfiguration;
import io.netflix.titus.master.job.batch.BatchJobMgr;
import io.netflix.titus.master.job.service.ServiceJobMgr;
import io.netflix.titus.master.job.worker.WorkerStateMonitor;
import io.netflix.titus.master.scheduler.SchedulingService;
import io.netflix.titus.master.service.management.ManagementSubsystemInitializer;
import io.netflix.titus.master.store.NamedJob;
import io.netflix.titus.master.store.NamedJobs;
import io.netflix.titus.master.store.V2JobStore;
import io.netflix.titus.master.store.V2StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.functions.Action1;
import rx.functions.Action2;
import rx.functions.Func2;
import rx.subjects.PublishSubject;
import rx.subjects.ReplaySubject;
import rx.subjects.Subject;

@Singleton
public class V2JobOperationsImpl implements V2JobOperations {

    private static final Logger logger = LoggerFactory.getLogger(V2JobOperationsImpl.class);
    private ConcurrentMap<String, V2JobMgrIntf> jobMgrConcurrentMap = new ConcurrentHashMap<>();
    private VirtualMachineMasterService vmService;
    private ReplaySubject<Observable<JobSchedulingInfo>> jobSchedulingObserver;
    private final ApiOperations apiOps;
    private final NamedJobs namedJobs;
    private final AtomicBoolean isReady = new AtomicBoolean(false);

    private final PublishSubject<V2JobMgrIntf> jobCreationPublishSubject;
    private final List<V2JobMgrIntf> initialJobMgrs = new ArrayList<>();
    private Action2<String, Long> agentDisablerAction = null;
    private Action1<String> agentEnableAction = null;
    private static final String JobIdReuseCounterName = "countJobIdReuse";
    private final Counter jobIdReuseCounter;
    private final Injector injector;
    private final RxEventBus eventBus;
    private final Registry registry;
    private ReplaySubject<NamedJob> namedJobReplaySubject;
    private static final String JobSlaEnforcerMillisCounterName = "jobSlaEnforcerMillis";
    private final Counter jobSlaEnforcerMillis;
    private static final String JobSlaEnforcerErrorsCounterName = "jobSlaEnforcerErrorsCount";
    private final Counter jobSlaEnforcerErrors;
    private ScheduledThreadPoolExecutor executor;
    private V2JobStore store;
    final AuditLogService auditLogService;
    private final JobManagerConfiguration jobManagerConfiguration;

    /**
     * WARNING: we depend here on {@link ManagementSubsystemInitializer} to enforce proper initialization order.
     */
    @Inject
    public V2JobOperationsImpl(Injector injector,
                               JobManagerConfiguration jobManagerConfiguration,
                               VirtualMachineMasterService vmService,
                               ApiOperations apiOps,
                               TriggerOperator triggerOperator,
                               MasterConfiguration config,
                               RxEventBus eventBus,
                               ManagementSubsystemInitializer managementSubsystemInitializer,
                               Registry registry) {
        this.injector = injector;
        auditLogService = injector.getInstance(AuditLogService.class);
        this.jobManagerConfiguration = jobManagerConfiguration;
        this.eventBus = eventBus;
        this.registry = registry;
        this.namedJobReplaySubject = ReplaySubject.create();

        this.vmService = vmService;
        vmService.setRunningWorkersGetter(() -> {
            List<V2WorkerMetadata> runningWorkers = new ArrayList<>();
            jobMgrConcurrentMap.values().forEach(m -> runningWorkers.addAll(m.getWorkers().stream()
                    .filter(t -> V2JobState.isRunningState(t.getState())).collect(Collectors.toList())));
            return runningWorkers;
        });

        this.jobSchedulingObserver = ReplaySubject.create();
        this.namedJobs = new NamedJobs(
                config,
                apiOps.getNamedJobs(),
                namedJobReplaySubject,
                jobMgrConcurrentMap,
                this,
                triggerOperator,
                auditLogService
        );
        jobCreationPublishSubject = PublishSubject.create();
        this.apiOps = apiOps;
        jobIdReuseCounter = registry.counter(MetricConstants.METRIC_SCHEDULING_JOB + JobIdReuseCounterName);
        jobSlaEnforcerMillis = registry.counter(MetricConstants.METRIC_SCHEDULING_JOB + JobSlaEnforcerMillisCounterName);
        jobSlaEnforcerErrors = registry.counter(MetricConstants.METRIC_SCHEDULING_JOB + JobSlaEnforcerErrorsCounterName);
        this.store = new V2JobStore(
                injector.getInstance(V2StorageProvider.class), auditLogService,
                this, eventBus, config, registry
        );
    }

    @Activator
    public Observable<Void> enterActiveMode() {
        store.start();

        injector.getInstance(WorkerStateMonitor.class).start(initialJobMgrs);

        logger.info("state monitor started");
        setReady();
        logger.info("jobOps set to ready");
        apiOps.setReady(store);
        logger.info("apiOps set to ready");

        return Observable.empty();
    }

    @PreDestroy
    public void shutdown() {
        namedJobs.shutdown();
        if (executor != null) {
            executor.shutdown();
        }
        store.shutdown();
    }

    @Override
    public Subject<V2JobMgrIntf, V2JobMgrIntf> getJobCreationPublishSubject() {
        return jobCreationPublishSubject;
    }

    @Override
    public boolean deleteJob(String jobId) throws IOException {
        try {
            store.deleteJob(jobId);
            auditLogService.submit(new AuditLogEvent(AuditLogEvent.Type.JOB_DELETE, jobId, "", System.currentTimeMillis()));
            namedJobs.deleteJob(jobId);
            removeJobIdRef(jobId);
            return true;
        } catch (InvalidJobException e) {
            logger.error("Can't delete job " + jobId + " - " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public void killJob(String user, String jobId, String reason) {
        final V2JobMgrIntf jobMgr = jobMgrConcurrentMap.get(jobId);
        if (jobMgr != null) {
            jobMgr.killJob(user, reason);
        }
    }

    @Override
    public void terminateJob(String jobId) {
        removeJobIdRef(jobId);
    }

    private void removeJobIdRef(String jobId) {
        apiOps.removeJobIdRef(jobId);
        jobMgrConcurrentMap.remove(jobId);
    }

    private void addJobIdRef(String jobId, V2JobMgrIntf jobMgr) {
        jobMgrConcurrentMap.put(jobId, jobMgr);
        apiOps.addJobIdRef(jobId, jobMgr);
    }

    @Override
    public String submit(V2JobDefinition jobDefinition) throws IllegalArgumentException {
        awaitReady();
        JobType jobType = getJobType(jobDefinition);
        if (jobType == null) {
            throw new IllegalStateException("Job type not defined for job definition: " + jobDefinition);
        }
        V2JobMgrIntf jobMgr;
        jobMgr = createNewJobMgr(jobDefinition, jobType);
        addJobIdRef(jobMgr.getJobId(), jobMgr);
        namedJobs.getJobByName(jobDefinition.getName()).registerJobMgr(jobMgr);
        jobMgr.setTaskKillAction(tid -> vmService.killTask(tid));
        jobCreationPublishSubject.onNext(jobMgr);

        jobMgr.postInitializeNewJob();

        auditLogService.submit(new AuditLogEvent(AuditLogEvent.Type.JOB_SUBMIT, jobMgr.getJobId(), "user=" +
                jobDefinition.getUser(), System.currentTimeMillis()));

        return jobMgr.getJobId();
    }

    private JobType getJobType(V2JobDefinition jobDefinition) {
        // TODO need better way to get job type
        return Parameters.getJobType(jobDefinition.getParameters());
    }

    private V2JobMgrIntf createNewJobMgr(V2JobDefinition jobDefinition, JobType jobType) throws IllegalArgumentException {
        switch (jobType) {
            case Service:
                return ServiceJobMgr.acceptSubmit(
                        jobDefinition.getUser(), jobDefinition, namedJobs,
                        injector, jobSchedulingObserver, eventBus, registry, store
                );
            case Batch:
                return BatchJobMgr.acceptSubmit(
                        jobDefinition.getUser(), jobDefinition, namedJobs,
                        injector, jobSchedulingObserver, eventBus, registry, store
                );
            default:
                throw new IllegalArgumentException("Unknown type: " + jobType);
        }
    }

    private V2JobMgrIntf createJobMgr(String jobId, V2JobDefinition jobDefinition) {
        final NamedJob namedJob = namedJobs.getJobByName(NamedJobs.TitusJobName);
        JobType jobType = getJobType(jobDefinition);
        if (jobType == null) {
            throw new IllegalStateException("Job type not defined for job " + jobDefinition.getName());
        }
        switch (jobType) {
            case Service:
                return new ServiceJobMgr(injector, jobId, jobDefinition, namedJob,
                        jobSchedulingObserver, eventBus, registry);
            case Batch:
                return new BatchJobMgr(injector, jobId, jobDefinition, namedJob,
                        jobSchedulingObserver, injector.getInstance(MasterConfiguration.class),
                        jobManagerConfiguration, injector.getInstance(JobConfiguration.class),
                        eventBus, injector.getInstance(SchedulingService.class), registry);
            default:
                throw new IllegalArgumentException("Unknown type: " + jobType);
        }
    }

    @Override
    public Func2<V2JobStore, Map<String, V2JobDefinition>, Collection<NamedJob>> getJobsInitializer() {
        return (store, stringV2JobDefinitionMap) -> {
            try {
                logger.info("Initializing namedJobs");
                namedJobs.init(store.getStorageProvider());
                Map<String, Collection<V2JobMgrIntf>> nameToJobMgrsMap = new HashMap<>();
                List<Map.Entry<String, V2JobDefinition>> failedJobs = new ArrayList<>();
                for (Map.Entry<String, V2JobDefinition> entry : stringV2JobDefinitionMap.entrySet()) {
                    try {
                        logger.info("Initializing job {}...", entry.getKey());
                        V2JobMgrIntf jobMgr = createJobMgr(entry.getKey(), entry.getValue());
                        jobMgr.setTaskKillAction(tid -> vmService.killTask(tid));
                        addJobIdRef(entry.getKey(), jobMgr);
                        jobMgr.initialize(store);
                        initialJobMgrs.add(jobMgr);
                        Collection<V2JobMgrIntf> jobMgrs = nameToJobMgrsMap.computeIfAbsent(entry.getValue().getName(), k -> new ArrayList<>());
                        jobMgrs.add(jobMgr);
                        logger.info("Created job manager for job {}", entry.getKey());
                    } catch (Exception e) {
                        failedJobs.add(entry);
                        logger.warn("Skipping job because it failed to initialize: ", e);
                    }
                }
                List<String> ids = new ArrayList<>();
                for (Map.Entry<String, V2JobDefinition> failedJob : failedJobs) {
                    logger.info("JobId: {} failed to initialize: {}", failedJob.getKey(), failedJob.getValue());
                    ids.add(failedJob.getKey());
                }
                if (!ids.isEmpty()) {
                    logger.info("Failed to initialize {} jobs with ids: {}", ids.size(), ids);
                }
                if (failedJobs.size() > jobManagerConfiguration.getMaxFailedJobs()) {
                    logger.error("Exiting because the number of failed jobs was greater than: {}", jobManagerConfiguration.getMaxFailedJobs());
                    System.exit(1);
                }
                for (Map.Entry<String, Collection<V2JobMgrIntf>> entry : nameToJobMgrsMap.entrySet()) {
                    namedJobs.getNamedJobIdInitializer().call(entry.getKey(), entry.getValue());
                }
            } finally {
                jobMgrConcurrentMap.values().forEach(jm -> {
                    if (jm instanceof BaseJobMgr) {
                        jm.getWorkers().stream().filter(w -> V2JobState.isRunningState(w.getState()))
                                .forEach(w -> ((BaseJobMgr) jm).getJobMetrics().updateTaskMetrics(w));
                    }
                });
            }
            return namedJobs.getAllNamedJobs();
        };
    }

    @Override
    public Collection<V2JobMgrIntf> getAllJobMgrs() {
        awaitReady();
        return jobMgrConcurrentMap.values();
    }

    @Override
    public V2JobMgrIntf getJobMgr(String jobId) {
        return jobMgrConcurrentMap.get(jobId);
    }

    @Override
    public V2JobMgrIntf getJobMgrFromTaskId(String taskId) {
        return getJobMgr(WorkerNaming.getJobAndWorkerId(taskId).jobId);
    }

    private void awaitReady() {
        while (!isReady.get()) {
            synchronized (isReady) {
                if (!isReady.get()) {
                    try {
                        isReady.wait(10000); // arbitrary finite sleep
                    } catch (InterruptedException e) {
                        logger.debug("Interrupted waiting for readiness");
                    }
                }
            }
        }
    }

    private void setReady() {
        logger.info("setting ready state");
        synchronized (isReady) {
            isReady.set(true);
            isReady.notifyAll();
        }
        logger.info("set ready state");
        startJobSlaEnforcer();
    }

    private void startJobSlaEnforcer() {
        this.executor = new ScheduledThreadPoolExecutor(1);
        executor.scheduleWithFixedDelay(() -> {
            long now = System.currentTimeMillis();
            try {
                List<V2JobMgrIntf> jobMgrs = new LinkedList<>(jobMgrConcurrentMap.values());
                if (!jobMgrs.isEmpty()) {
                    for (V2JobMgrIntf j : jobMgrs) {
                        j.enforceSla();
                    }
                }
            } catch (Exception e) {
                logger.warn("Error enforcing job sla: " + e.getMessage(), e);
                jobSlaEnforcerErrors.increment();
            }
            jobSlaEnforcerMillis.increment(System.currentTimeMillis() - now);
        }, 60, 60, TimeUnit.SECONDS);
    }
}