/**
 * Copyright © 2016-2022 The Thingsboard Authors
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
package org.thingsboard.server.service.entitiy.entityView;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.EntityView;
import org.thingsboard.server.common.data.audit.ActionType;
import org.thingsboard.server.common.data.edge.Edge;
import org.thingsboard.server.common.data.edge.EdgeEventActionType;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EdgeId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.EntityViewId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.BaseReadTsKvQuery;
import org.thingsboard.server.common.data.kv.ReadTsKvQuery;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.dao.timeseries.TimeseriesService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.entitiy.AbstractTbEntityService;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.thingsboard.server.dao.service.Validator.validateId;

@Service
@TbCoreComponent
@AllArgsConstructor
@Slf4j
public class DefaultTbEntityViewService extends AbstractTbEntityService implements TbEntityViewService {

    private  final TimeseriesService tsService;

    @Override
    public EntityView save(EntityView entityView, SecurityUser user) throws ThingsboardException {
        ActionType actionType = entityView.getId() == null ? ActionType.ADDED : ActionType.UPDATED;
        try {
            List<ListenableFuture<?>> futures = new ArrayList<>();

            if (entityView.getId() == null) {
                accessControlService
                        .checkPermission(user, Resource.ENTITY_VIEW, Operation.CREATE, null, entityView);
            } else {
                EntityView existingEntityView = checkEntityViewId(entityView.getId(), Operation.WRITE, user);
                if (existingEntityView.getKeys() != null) {
                    if (existingEntityView.getKeys().getAttributes() != null) {
                        futures.add(deleteAttributesFromEntityView(existingEntityView, DataConstants.CLIENT_SCOPE, existingEntityView.getKeys().getAttributes().getCs(), user));
                        futures.add(deleteAttributesFromEntityView(existingEntityView, DataConstants.SERVER_SCOPE, existingEntityView.getKeys().getAttributes().getCs(), user));
                        futures.add(deleteAttributesFromEntityView(existingEntityView, DataConstants.SHARED_SCOPE, existingEntityView.getKeys().getAttributes().getCs(), user));
                    }
                }
                List<String> tsKeys = existingEntityView.getKeys() != null && existingEntityView.getKeys().getTimeseries() != null ?
                        existingEntityView.getKeys().getTimeseries() : Collections.emptyList();
                futures.add(deleteLatestFromEntityView(existingEntityView, tsKeys, user));
            }

            EntityView savedEntityView = checkNotNull(entityViewService.saveEntityView(entityView));
            if (savedEntityView.getKeys() != null) {
                if (savedEntityView.getKeys().getAttributes() != null) {
                    futures.add(copyAttributesFromEntityToEntityView(savedEntityView, DataConstants.CLIENT_SCOPE, savedEntityView.getKeys().getAttributes().getCs(), user));
                    futures.add(copyAttributesFromEntityToEntityView(savedEntityView, DataConstants.SERVER_SCOPE, savedEntityView.getKeys().getAttributes().getSs(), user));
                    futures.add(copyAttributesFromEntityToEntityView(savedEntityView, DataConstants.SHARED_SCOPE, savedEntityView.getKeys().getAttributes().getSh(), user));
                }
                futures.add(copyLatestFromEntityToEntityView(savedEntityView, user));
            }
            for (ListenableFuture<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException("Failed to copy attributes to entity view", e);
                }
            }

            notificationEntityService.notifyCreateOrUpdateEntity(user.getTenantId(), savedEntityView.getId(), savedEntityView,
                    null, actionType, user);

            return savedEntityView;
        } catch (Exception e) {
            notificationEntityService.notifyEntity(user.getTenantId(), emptyId(EntityType.ENTITY_VIEW), entityView, null, actionType, user, e);
            throw handleException(e);
        }
    }

    @Override
    public void delete(EntityView entityView, SecurityUser user) throws ThingsboardException {
        TenantId tenantId = entityView.getTenantId();
        EntityViewId entityViewId = entityView.getId();
        try {
            List<EdgeId> relatedEdgeIds = findRelatedEdgeIds(tenantId, entityViewId);
            entityViewService.deleteEntityView(tenantId, entityViewId);
            notificationEntityService.notifyDeleteEntity(tenantId, entityViewId, entityView, user.getCustomerId(), ActionType.DELETED,
                    relatedEdgeIds, user, entityViewId.toString());
        } catch (Exception e) {
            notificationEntityService.notifyEntity(tenantId, emptyId(EntityType.ENTITY_VIEW), null, null,
                    ActionType.DELETED, user, e, entityViewId.toString());
            throw handleException(e);
        }
    }

    @Override
    public EntityView assignEntityViewToCustomer(EntityViewId entityViewId, Customer customer, SecurityUser user) throws ThingsboardException {
        ActionType actionType = ActionType.ASSIGNED_TO_CUSTOMER;
        CustomerId customerId = customer.getId();
        try {
            EntityView savedEntityView = checkNotNull(entityViewService.assignEntityViewToCustomer(user.getTenantId(), entityViewId, customerId));
            notificationEntityService.notifyAssignOrUnassignEntityToCustomer(user.getTenantId(), entityViewId, customerId, savedEntityView,
                    actionType, EdgeEventActionType.ASSIGNED_TO_CUSTOMER, user, true, customerId.toString(), customer.getName());
            return savedEntityView;
        } catch (Exception e) {
            notificationEntityService.notifyEntity(user.getTenantId(), emptyId(EntityType.ENTITY_VIEW), null, null,
                    actionType, user, e, entityViewId.toString(), customerId.toString());
            throw handleException(e);
        }
    }

    @Override
    public EntityView assignEntityViewToPublicCustomer(EntityViewId entityViewId, SecurityUser user) throws ThingsboardException {
        ActionType actionType = ActionType.ASSIGNED_TO_CUSTOMER;
        try {
            Customer publicCustomer = customerService.findOrCreatePublicCustomer(user.getTenantId());
            EntityView savedEntityView = checkNotNull(entityViewService.assignEntityViewToCustomer(user.getTenantId(), entityViewId, publicCustomer.getId()));
            notificationEntityService.notifyAssignOrUnassignEntityToCustomer(user.getTenantId(), entityViewId, user.getCustomerId(), savedEntityView,
                    actionType, null, user, false, entityViewId.toString(),
                    publicCustomer.getId().toString(), publicCustomer.getName());
            return savedEntityView;
        } catch (Exception e) {
            notificationEntityService.notifyEntity(user.getTenantId(), emptyId(EntityType.ENTITY_VIEW), null, null,
                    actionType, user, e, entityViewId.toString());
            throw handleException(e);
        }
    }

    @Override
    public EntityView assignEntityViewToEdge(EntityViewId entityViewId, Edge edge, SecurityUser user) throws ThingsboardException {
        ActionType actionType = ActionType.ASSIGNED_TO_EDGE;
        EdgeId edgeId = edge.getId();
        TenantId tenantId = user.getTenantId();
        try {
            EntityView savedEntityView = checkNotNull(entityViewService.assignEntityViewToEdge(tenantId, entityViewId, edgeId));
            notificationEntityService.notifyAssignOrUnassignEntityToEdge(tenantId, entityViewId, user.getCustomerId(),
                    edgeId, savedEntityView, actionType, EdgeEventActionType.ASSIGNED_TO_EDGE, user, entityViewId.toString(),
                    edgeId.toString(), edge.getName());
            return savedEntityView;
        } catch (Exception e) {
            notificationEntityService.notifyEntity(tenantId, emptyId(EntityType.DEVICE), null, null,
                    actionType, user, e, entityViewId.toString(), edgeId.toString());
            throw handleException(e);
        }
    }

    @Override
    public EntityView unassignEntityViewFromEdge(EntityView entityView, Edge edge, SecurityUser user) throws ThingsboardException {
        ActionType actionType = ActionType.UNASSIGNED_FROM_EDGE;
        TenantId tenantId = user.getTenantId();
        EntityViewId entityViewId = entityView.getId();
        EdgeId edgeId = edge.getId();
        try {
            EntityView savedDevice = checkNotNull(entityViewService.unassignEntityViewFromEdge(tenantId, entityViewId, edgeId));

            notificationEntityService.notifyAssignOrUnassignEntityToEdge(tenantId, entityViewId, user.getCustomerId(),
                    edgeId, entityView, actionType, EdgeEventActionType.UNASSIGNED_FROM_EDGE, user, entityViewId.toString(),
                    edgeId.toString(), edge.getName());
            return savedDevice;
        } catch (Exception e) {
            notificationEntityService.notifyEntity(tenantId, emptyId(EntityType.ENTITY_VIEW), null, null,
                    actionType, user, e, entityViewId.toString(), edgeId.toString());
            throw handleException(e);
        }
    }

    @Override
    public EntityView unassignEntityViewFromCustomer(EntityView entityView, Customer customer, SecurityUser user) throws ThingsboardException {
        ActionType actionType = ActionType.UNASSIGNED_FROM_CUSTOMER;
        TenantId tenantId = entityView.getTenantId();
        try {
            EntityView savedEntityView = checkNotNull(entityViewService.unassignEntityViewFromCustomer(tenantId, entityView.getId()));
            notificationEntityService.notifyAssignOrUnassignEntityToCustomer(tenantId, entityView.getId(), customer.getId(), savedEntityView,
                    actionType, EdgeEventActionType.UNASSIGNED_FROM_CUSTOMER, user, true, customer.getId().toString(), customer.getName());
            return savedEntityView;
        } catch (Exception e) {
            notificationEntityService.notifyEntity(tenantId, emptyId(EntityType.ENTITY_VIEW), null, null,
                    actionType, user, e, entityView.getId().toString());
            throw handleException(e);
        }
    }

    private ListenableFuture<List<Void>> copyAttributesFromEntityToEntityView(EntityView entityView, String scope, Collection<String> keys, SecurityUser user) throws ThingsboardException {
        EntityViewId entityId = entityView.getId();
        if (keys != null && !keys.isEmpty()) {
            ListenableFuture<List<AttributeKvEntry>> getAttrFuture = attributesService.find(entityView.getTenantId(), entityView.getEntityId(), scope, keys);
            return Futures.transform(getAttrFuture, attributeKvEntries -> {
                List<AttributeKvEntry> attributes;
                if (attributeKvEntries != null && !attributeKvEntries.isEmpty()) {
                    attributes =
                            attributeKvEntries.stream()
                                    .filter(attributeKvEntry -> {
                                        long startTime = entityView.getStartTimeMs();
                                        long endTime = entityView.getEndTimeMs();
                                        long lastUpdateTs = attributeKvEntry.getLastUpdateTs();
                                        return startTime == 0 && endTime == 0 ||
                                                (endTime == 0 && startTime < lastUpdateTs) ||
                                                (startTime == 0 && endTime > lastUpdateTs)
                                                ? true : startTime < lastUpdateTs && endTime > lastUpdateTs;
                                    }).collect(Collectors.toList());
                    tsSubService.saveAndNotify(entityView.getTenantId(), entityId, scope, attributes, new FutureCallback<Void>() {
                        @Override
                        public void onSuccess(@Nullable Void tmp) {
                            try {
                                logAttributesUpdated(user, entityId, scope, attributes, null);
                            } catch (ThingsboardException e) {
                                log.error("Failed to log attribute updates", e);
                            }
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            try {
                                logAttributesUpdated(user, entityId, scope, attributes, t);
                            } catch (ThingsboardException e) {
                                log.error("Failed to log attribute updates", e);
                            }
                        }
                    });
                }
                return null;
            }, MoreExecutors.directExecutor());
        } else {
            return Futures.immediateFuture(null);
        }
    }

    private ListenableFuture<List<Void>> copyLatestFromEntityToEntityView(EntityView entityView, SecurityUser user) {
        EntityViewId entityId = entityView.getId();
        List<String> keys = entityView.getKeys() != null && entityView.getKeys().getTimeseries() != null ?
                entityView.getKeys().getTimeseries() : Collections.emptyList();
        long startTs = entityView.getStartTimeMs();
        long endTs = entityView.getEndTimeMs() == 0 ? Long.MAX_VALUE : entityView.getEndTimeMs();
        ListenableFuture<List<String>> keysFuture;
        if (keys.isEmpty()) {
            keysFuture = Futures.transform(tsService.findAllLatest(user.getTenantId(),
                    entityView.getEntityId()), latest -> latest.stream().map(TsKvEntry::getKey).collect(Collectors.toList()), MoreExecutors.directExecutor());
        } else {
            keysFuture = Futures.immediateFuture(keys);
        }
        ListenableFuture<List<TsKvEntry>> latestFuture = Futures.transformAsync(keysFuture, fetchKeys -> {
            List<ReadTsKvQuery> queries = fetchKeys.stream().filter(key -> !isBlank(key)).map(key -> new BaseReadTsKvQuery(key, startTs, endTs, 1, "DESC")).collect(Collectors.toList());
            if (!queries.isEmpty()) {
                return tsService.findAll(user.getTenantId(), entityView.getEntityId(), queries);
            } else {
                return Futures.immediateFuture(null);
            }
        }, MoreExecutors.directExecutor());
        return Futures.transform(latestFuture, latestValues -> {
            if (latestValues != null && !latestValues.isEmpty()) {
                tsSubService.saveLatestAndNotify(entityView.getTenantId(), entityId, latestValues, new FutureCallback<Void>() {
                    @Override
                    public void onSuccess(@Nullable Void tmp) {
                    }

                    @Override
                    public void onFailure(Throwable t) {
                    }
                });
            }
            return null;
        }, MoreExecutors.directExecutor());
    }

    private ListenableFuture<Void> deleteAttributesFromEntityView(EntityView entityView, String scope, List<String> keys, SecurityUser user) {
        EntityViewId entityId = entityView.getId();
        SettableFuture<Void> resultFuture = SettableFuture.create();
        if (keys != null && !keys.isEmpty()) {
            tsSubService.deleteAndNotify(entityView.getTenantId(), entityId, scope, keys, new FutureCallback<Void>() {
                @Override
                public void onSuccess(@Nullable Void tmp) {
                    try {
                        logAttributesDeleted(user, entityId, scope, keys, null);
                    } catch (ThingsboardException e) {
                        log.error("Failed to log attribute delete", e);
                    }
                    resultFuture.set(tmp);
                }

                @Override
                public void onFailure(Throwable t) {
                    try {
                        logAttributesDeleted(user, entityId, scope, keys, t);
                    } catch (ThingsboardException e) {
                        log.error("Failed to log attribute delete", e);
                    }
                    resultFuture.setException(t);
                }
            });
        } else {
            resultFuture.set(null);
        }
        return resultFuture;
    }

    private ListenableFuture<Void> deleteLatestFromEntityView(EntityView entityView, List<String> keys, SecurityUser user) {
        EntityViewId entityId = entityView.getId();
        SettableFuture<Void> resultFuture = SettableFuture.create();
        if (keys != null && !keys.isEmpty()) {
            tsSubService.deleteLatest(entityView.getTenantId(), entityId, keys, new FutureCallback<Void>() {
                @Override
                public void onSuccess(@Nullable Void tmp) {
                    try {
                        logTimeseriesDeleted(user, entityId, keys, null);
                    } catch (ThingsboardException e) {
                        log.error("Failed to log timeseries delete", e);
                    }
                    resultFuture.set(tmp);
                }

                @Override
                public void onFailure(Throwable t) {
                    try {
                        logTimeseriesDeleted(user, entityId, keys, t);
                    } catch (ThingsboardException e) {
                        log.error("Failed to log timeseries delete", e);
                    }
                    resultFuture.setException(t);
                }
            });
        } else {
            tsSubService.deleteAllLatest(entityView.getTenantId(), entityId, new FutureCallback<Collection<String>>() {
                @Override
                public void onSuccess(@Nullable Collection<String> keys) {
                    try {
                        logTimeseriesDeleted(user, entityId, new ArrayList<>(keys), null);
                    } catch (ThingsboardException e) {
                        log.error("Failed to log timeseries delete", e);
                    }
                    resultFuture.set(null);
                }

                @Override
                public void onFailure(Throwable t) {
                    try {
                        logTimeseriesDeleted(user, entityId, Collections.emptyList(), t);
                    } catch (ThingsboardException e) {
                        log.error("Failed to log timeseries delete", e);
                    }
                    resultFuture.setException(t);
                }
            });
        }
        return resultFuture;
    }

    private void logAttributesUpdated(SecurityUser user, EntityId entityId, String scope, List<AttributeKvEntry> attributes, Throwable e) throws ThingsboardException {
        notificationEntityService.notifyEntity(user.getTenantId(), entityId, null, null, ActionType.ATTRIBUTES_UPDATED, user, toException(e), scope, attributes);
    }

    private void logAttributesDeleted(SecurityUser user, EntityId entityId, String scope, List<String> keys, Throwable e) throws ThingsboardException {
        notificationEntityService.notifyEntity(user.getTenantId(), entityId, null, null, ActionType.ATTRIBUTES_DELETED, user, toException(e), scope, keys);
    }

    private void logTimeseriesDeleted(SecurityUser user, EntityId entityId, List<String> keys, Throwable e) throws ThingsboardException {
        notificationEntityService.notifyEntity(user.getTenantId(), entityId, null, null, ActionType.TIMESERIES_DELETED, user, toException(e), keys);
    }

    private EntityView checkEntityViewId(EntityViewId entityViewId, Operation operation, SecurityUser user) throws ThingsboardException {
        try {
            validateId(entityViewId, "Incorrect entityViewId " + entityViewId);
            EntityView entityView = entityViewService.findEntityViewById(user.getTenantId(), entityViewId);
            checkNotNull(entityView, "Entity view with id [" + entityViewId + "] is not found");
            accessControlService.checkPermission(user, Resource.ENTITY_VIEW, operation, entityViewId, entityView);
            return entityView;
        } catch (Exception e) {
            throw handleException(e, false);
        }
    }

    public static Exception toException(Throwable error) {
        return error != null ? (Exception.class.isInstance(error) ? (Exception) error : new Exception(error)) : null;
    }
}
