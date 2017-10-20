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

package io.netflix.titus.common.framework.reconciler.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.base.Preconditions;
import io.netflix.titus.common.framework.reconciler.EntityHolder;
import io.netflix.titus.common.framework.reconciler.ReconcilerEvent;
import io.netflix.titus.common.framework.reconciler.ReconciliationEngine;
import io.netflix.titus.common.framework.reconciler.ReconciliationFramework;
import io.netflix.titus.common.util.ExceptionExt;
import io.netflix.titus.common.util.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Completable;
import rx.Observable;
import rx.Scheduler;
import rx.Subscriber;
import rx.subjects.PublishSubject;

public class DefaultReconciliationFramework<CHANGE> implements ReconciliationFramework<CHANGE> {

    private static final Logger logger = LoggerFactory.getLogger(DefaultReconciliationFramework.class);

    private final Function<EntityHolder, ReconciliationEngine<CHANGE>> engineFactory;
    private final long idleTimeoutMs;
    private final long activeTimeoutMs;

    private final Set<ReconciliationEngine<CHANGE>> engines = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final BlockingQueue<Pair<ReconciliationEngine<CHANGE>, Subscriber<ReconciliationEngine>>> enginesAdded = new LinkedBlockingQueue<>();
    private final BlockingQueue<Pair<ReconciliationEngine<CHANGE>, Subscriber<Void>>> enginesToRemove = new LinkedBlockingQueue<>();

    private IndexSet<EntityHolder> indexSet;

    private final Scheduler.Worker worker;

    private volatile boolean runnable = true;
    private volatile boolean started = false;

    private final PublishSubject<Observable<ReconcilerEvent>> eventsMergeSubject = PublishSubject.create();
    private final Observable<ReconcilerEvent> eventsObservable;

    public DefaultReconciliationFramework(Function<EntityHolder, ReconciliationEngine<CHANGE>> engineFactory,
                                          long idleTimeoutMs,
                                          long activeTimeoutMs,
                                          Map<Object, Comparator<EntityHolder>> indexComparators,
                                          Scheduler scheduler) {
        Preconditions.checkArgument(idleTimeoutMs > 0, "idleTimeout <= 0 (%s)", idleTimeoutMs);
        Preconditions.checkArgument(activeTimeoutMs <= idleTimeoutMs, "activeTimeout(%s) > idleTimeout(%s)", activeTimeoutMs, idleTimeoutMs);

        this.engineFactory = engineFactory;
        this.indexSet = IndexSet.newIndexSet(indexComparators);

        this.idleTimeoutMs = idleTimeoutMs;
        this.activeTimeoutMs = activeTimeoutMs;
        this.worker = scheduler.createWorker();
        this.eventsObservable = Observable.merge(eventsMergeSubject);
    }

    @Override
    public void start() {
        Preconditions.checkArgument(!started, "Framework already started");
        started = true;
        doSchedule(0);
    }

    @Override
    public boolean stop(long timeoutMs) {
        runnable = false;

        // In the test code when we use the TestScheduler we would always block here. One way to solve this is to return
        // Completable as a result, but this makes the API inconvenient. Instead we chose to look at the worker type,
        // and handle this differently for TestScheduler.
        if (worker.getClass().getName().contains("TestScheduler")) {
            stopEngines();
            return true;
        }

        // Run this on internal thread, just like other actions.
        CountDownLatch latch = new CountDownLatch(1);
        worker.schedule(() -> {
            stopEngines();
            latch.countDown();
        });
        ExceptionExt.silent(() -> latch.await(timeoutMs, TimeUnit.MILLISECONDS));
        return latch.getCount() == 0;
    }

    private void stopEngines() {
        engines.forEach(e -> {
            if (e instanceof DefaultReconciliationEngine) {
                ((DefaultReconciliationEngine) e).shutdown();
            }
        });
        engines.clear();
    }

    @Override
    public Observable<ReconciliationEngine<CHANGE>> newEngine(EntityHolder bootstrapModel) {
        return Observable.unsafeCreate(subscriber -> {
            if (!runnable) {
                subscriber.onError(new IllegalStateException("Reconciliation engine is stopped"));
                return;
            }
            ReconciliationEngine newEngine = engineFactory.apply(bootstrapModel);
            enginesAdded.add(Pair.of(newEngine, (Subscriber<ReconciliationEngine>) subscriber));
        });
    }

    @Override
    public Completable removeEngine(ReconciliationEngine engine) {
        return Observable.<Void>unsafeCreate(subscriber -> {
            if (!runnable) {
                subscriber.onError(new IllegalStateException("Reconciliation engine is stopped"));
                return;
            }
            enginesToRemove.add(Pair.of(engine, (Subscriber<Void>) subscriber));
        }).toCompletable();
    }

    @Override
    public Observable<ReconcilerEvent> events() {
        return eventsObservable;
    }

    @Override
    public Optional<ReconciliationEngine<CHANGE>> findEngineByRootId(String id) {
        return engines.stream().filter(e -> e.getReferenceView().getId().equals(id)).findFirst();
    }

    @Override
    public Optional<Pair<ReconciliationEngine<CHANGE>, EntityHolder>> findEngineByChildId(String childId) {
        for (ReconciliationEngine engine : engines) {
            Optional<EntityHolder> childHolder = engine.getReferenceView().getChildren().stream().filter(c -> c.getId().equals(childId)).findFirst();
            if (childHolder.isPresent()) {
                return Optional.of(Pair.of(engine, childHolder.get()));
            }
        }
        return Optional.empty();
    }

    @Override
    public <ORDER_BY> List<EntityHolder> orderedView(ORDER_BY orderingCriteria) {
        return indexSet.getOrdered(orderingCriteria);
    }

    private void doSchedule(long delayMs) {
        if (!runnable) {
            return;
        }
        worker.schedule(() -> {
            try {
                long nextDelayMs = doLoop();
                doSchedule(nextDelayMs);
            } catch (Exception e) {
                logger.warn("Unexpected error in the reconciliation loop", e);
                doSchedule(idleTimeoutMs);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private long doLoop() {
        // Add new engines.
        List<Pair<ReconciliationEngine<CHANGE>, Subscriber<ReconciliationEngine>>> recentlyAdded = new ArrayList<>();
        enginesAdded.drainTo(recentlyAdded);
        recentlyAdded.forEach(pair -> engines.add(pair.getLeft()));

        // Remove engines.
        List<Pair<ReconciliationEngine<CHANGE>, Subscriber<Void>>> recentlyRemoved = new ArrayList<>();
        enginesToRemove.drainTo(recentlyRemoved);
        shutdownEnginesToRemove(recentlyRemoved);

        boolean engineSetUpdate = !recentlyAdded.isEmpty() || !recentlyRemoved.isEmpty();

        // Update indexes to reflect engine collection update, before completing engine add/remove subscribers.
        if (engines.isEmpty()) {
            indexSet = indexSet.apply(Collections.emptyList());
        } else if (engineSetUpdate) {
            updateIndexSet();
        }

        // Complete engine add/remove subscribers.
        recentlyAdded.forEach(pair -> {
            Subscriber<ReconciliationEngine> subscriber = pair.getRight();
            if (!subscriber.isUnsubscribed()) {
                ReconciliationEngine newEngine = pair.getLeft();
                eventsMergeSubject.onNext(newEngine.events());
                subscriber.onNext(newEngine);
                subscriber.onCompleted();
            }
        });
        recentlyRemoved.forEach(pair -> pair.getRight().onCompleted());

        // Trigger events on engines.
        boolean modelUpdates = false;
        boolean pendingChangeActions = false;
        for (ReconciliationEngine engine : engines) {
            try {
                ReconciliationEngine.TriggerStatus triggerStatus = engine.triggerEvents();
                pendingChangeActions = pendingChangeActions || triggerStatus.isRunningChangeActions();
                modelUpdates = modelUpdates || triggerStatus.hasModelUpdates();
            } catch (Exception e) {
                logger.warn("Unexpected error from reconciliation engine 'triggerEvents' method", e);
            }
        }

        // Update indexes if there are model changes.
        if (modelUpdates) {
            updateIndexSet();
        }
        return pendingChangeActions ? activeTimeoutMs : idleTimeoutMs;
    }

    private void shutdownEnginesToRemove(List<Pair<ReconciliationEngine<CHANGE>, Subscriber<Void>>> toRemove) {
        toRemove.forEach(pair -> {
            ReconciliationEngine e = pair.getLeft();
            if (e instanceof DefaultReconciliationEngine) {
                ((DefaultReconciliationEngine) e).shutdown();
            }
            engines.remove(e);
        });
    }

    private void updateIndexSet() {
        indexSet = indexSet.apply(engines.stream().map(ReconciliationEngine::getReferenceView).collect(Collectors.toList()));
    }
}