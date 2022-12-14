/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.atlas.discovery;


import org.apache.atlas.AtlasConfiguration;
import org.apache.atlas.AtlasErrorCode;
import org.apache.atlas.RequestContext;
import org.apache.atlas.annotation.GraphTransaction;
import org.apache.atlas.authorize.AtlasAuthorizationUtils;
import org.apache.atlas.authorize.AtlasEntityAccessRequest;
import org.apache.atlas.authorize.AtlasPrivilege;
import org.apache.atlas.authorize.AtlasSearchResultScrubRequest;
import org.apache.atlas.exception.AtlasBaseException;
import org.apache.atlas.model.discovery.AtlasSearchResult;
import org.apache.atlas.model.instance.AtlasEntity;
import org.apache.atlas.model.instance.AtlasEntity.AtlasEntityWithExtInfo;
import org.apache.atlas.model.instance.AtlasEntityHeader;
import org.apache.atlas.model.instance.AtlasObjectId;
import org.apache.atlas.model.lineage.AtlasLineageInfo;
import org.apache.atlas.model.lineage.AtlasLineageInfo.LineageDirection;
import org.apache.atlas.model.lineage.AtlasLineageInfo.LineageRelation;
import org.apache.atlas.model.lineage.AtlasLineageRequest;
import org.apache.atlas.repository.graph.GraphHelper;
import org.apache.atlas.repository.graphdb.AtlasEdge;
import org.apache.atlas.repository.graphdb.AtlasEdgeDirection;
import org.apache.atlas.repository.graphdb.AtlasGraph;
import org.apache.atlas.repository.graphdb.AtlasVertex;
import org.apache.atlas.repository.store.graph.v2.AtlasGraphUtilsV2;
import org.apache.atlas.repository.store.graph.v2.EntityGraphRetriever;
import org.apache.atlas.type.AtlasEntityType;
import org.apache.atlas.type.AtlasTypeRegistry;
import org.apache.atlas.type.AtlasTypeUtil;
import org.apache.atlas.util.AtlasGremlinQueryProvider;
import org.apache.atlas.utils.AtlasPerfMetrics;
import org.apache.atlas.v1.model.instance.Id;
import org.apache.atlas.v1.model.lineage.SchemaResponse.SchemaDetails;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.inject.Inject;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.apache.atlas.AtlasClient.DATA_SET_SUPER_TYPE;
import static org.apache.atlas.AtlasClient.PROCESS_SUPER_TYPE;
import static org.apache.atlas.AtlasErrorCode.INSTANCE_LINEAGE_QUERY_FAILED;
import static org.apache.atlas.model.lineage.AtlasLineageInfo.LineageDirection.BOTH;
import static org.apache.atlas.model.lineage.AtlasLineageInfo.LineageDirection.INPUT;
import static org.apache.atlas.model.lineage.AtlasLineageInfo.LineageDirection.OUTPUT;
import static org.apache.atlas.repository.Constants.RELATIONSHIP_GUID_PROPERTY_KEY;
import static org.apache.atlas.repository.graphdb.AtlasEdgeDirection.IN;
import static org.apache.atlas.repository.graphdb.AtlasEdgeDirection.OUT;
import static org.apache.atlas.util.AtlasGremlinQueryProvider.AtlasGremlinQuery.FULL_LINEAGE_DATASET;
import static org.apache.atlas.util.AtlasGremlinQueryProvider.AtlasGremlinQuery.FULL_LINEAGE_PROCESS;
import static org.apache.atlas.util.AtlasGremlinQueryProvider.AtlasGremlinQuery.PARTIAL_LINEAGE_DATASET;
import static org.apache.atlas.util.AtlasGremlinQueryProvider.AtlasGremlinQuery.PARTIAL_LINEAGE_PROCESS;

@Service
public class EntityLineageService implements AtlasLineageService {
    private static final Logger LOG = LoggerFactory.getLogger(EntityLineageService.class);

    private static final String  PROCESS_INPUTS_EDGE   = "__Process.inputs";
    private static final String  PROCESS_OUTPUTS_EDGE  = "__Process.outputs";
    private static final String  COLUMNS               = "columns";
    private static final boolean LINEAGE_USING_GREMLIN = AtlasConfiguration.LINEAGE_USING_GREMLIN.getBoolean();

    private final AtlasGraph                graph;
    private final AtlasGremlinQueryProvider gremlinQueryProvider;
    private final EntityGraphRetriever      entityRetriever;
    private final AtlasTypeRegistry         atlasTypeRegistry;

    @Inject
    EntityLineageService(AtlasTypeRegistry typeRegistry, AtlasGraph atlasGraph) {
        this.graph = atlasGraph;
        this.gremlinQueryProvider = AtlasGremlinQueryProvider.INSTANCE;
        this.entityRetriever = new EntityGraphRetriever(atlasGraph, typeRegistry);
        this.atlasTypeRegistry = typeRegistry;
    }

    @Override
    public AtlasLineageInfo getAtlasLineageInfo(String guid, LineageDirection direction, int depth, boolean hideProcess) throws AtlasBaseException {
        return getAtlasLineageInfo(new AtlasLineageRequest(guid, depth, direction, hideProcess));
    }

    @Override
    @GraphTransaction
    public AtlasLineageInfo getAtlasLineageInfo(AtlasLineageRequest lineageRequest) throws AtlasBaseException {
        AtlasLineageInfo ret;
        String guid = lineageRequest.getGuid();
        AtlasLineageContext lineageRequestContext = new AtlasLineageContext(lineageRequest, atlasTypeRegistry);

        AtlasEntityHeader entity = entityRetriever.toAtlasEntityHeaderWithClassifications(guid);

        AtlasEntityType entityType = atlasTypeRegistry.getEntityTypeByName(entity.getTypeName());

        if (entityType == null) {
            throw new AtlasBaseException(AtlasErrorCode.TYPE_NAME_NOT_FOUND, entity.getTypeName());
        }

        boolean isDataSet = entityType.getTypeAndAllSuperTypes().contains(DATA_SET_SUPER_TYPE);

        if (!isDataSet) {
            boolean isProcess = entityType.getTypeAndAllSuperTypes().contains(PROCESS_SUPER_TYPE);

            if (!isProcess) {
                throw new AtlasBaseException(AtlasErrorCode.INVALID_LINEAGE_ENTITY_TYPE, guid, entity.getTypeName());
            } else if (lineageRequest.isHideProcess()) {
                throw new AtlasBaseException(AtlasErrorCode.INVALID_LINEAGE_ENTITY_TYPE_HIDE_PROCESS, guid, entity.getTypeName());
            }
            lineageRequestContext.setProcess(isProcess);
        }
        lineageRequestContext.setDataset(isDataSet);


        if (LINEAGE_USING_GREMLIN) {
            ret = getLineageInfoV1(lineageRequestContext);
        } else {
            ret = getLineageInfoV2(lineageRequestContext);
        }

        scrubLineageEntities(ret.getGuidEntityMap().values());

        return ret;
    }

    @Override
    @GraphTransaction
    public AtlasLineageInfo getAtlasLineageInfo(String guid, LineageDirection direction, int depth) throws AtlasBaseException {
        return getAtlasLineageInfo(guid, direction, depth, false);
    }

    @Override
    @GraphTransaction
    public SchemaDetails getSchemaForHiveTableByName(final String datasetName) throws AtlasBaseException {
        if (StringUtils.isEmpty(datasetName)) {
            // TODO: Complete error handling here
            throw new AtlasBaseException(AtlasErrorCode.BAD_REQUEST);
        }

        AtlasEntityType hive_table = atlasTypeRegistry.getEntityTypeByName("hive_table");

        Map<String, Object> lookupAttributes = new HashMap<>();
        lookupAttributes.put("qualifiedName", datasetName);
        String guid = AtlasGraphUtilsV2.getGuidByUniqueAttributes(hive_table, lookupAttributes);

        return getSchemaForHiveTableByGuid(guid);
    }

    @Override
    @GraphTransaction
    public SchemaDetails getSchemaForHiveTableByGuid(final String guid) throws AtlasBaseException {
        if (StringUtils.isEmpty(guid)) {
            throw new AtlasBaseException(AtlasErrorCode.BAD_REQUEST);
        }
        SchemaDetails ret = new SchemaDetails();
        AtlasEntityType hive_column = atlasTypeRegistry.getEntityTypeByName("hive_column");

        ret.setDataType(AtlasTypeUtil.toClassTypeDefinition(hive_column));

        AtlasEntityWithExtInfo entityWithExtInfo = entityRetriever.toAtlasEntityWithExtInfo(guid);
        AtlasEntity            entity            = entityWithExtInfo.getEntity();

        AtlasAuthorizationUtils.verifyAccess(new AtlasEntityAccessRequest(atlasTypeRegistry, AtlasPrivilege.ENTITY_READ, new AtlasEntityHeader(entity)),
                                             "read entity schema: guid=", guid);

        Map<String, AtlasEntity> referredEntities = entityWithExtInfo.getReferredEntities();
        List<String>             columnIds        = getColumnIds(entity);

        if (MapUtils.isNotEmpty(referredEntities)) {
            List<Map<String, Object>> rows = referredEntities.entrySet()
                                                             .stream()
                                                             .filter(e -> isColumn(columnIds, e))
                                                             .map(e -> AtlasTypeUtil.toMap(e.getValue()))
                                                             .collect(Collectors.toList());
            ret.setRows(rows);
        }

        return ret;
    }

    private void scrubLineageEntities(Collection<AtlasEntityHeader> entityHeaders) throws AtlasBaseException {
        AtlasPerfMetrics.MetricRecorder metricRecorder = RequestContext.get().startMetricRecord("scrubLineageEntities");

        AtlasSearchResult searchResult = new AtlasSearchResult();
        searchResult.setEntities(new ArrayList<>(entityHeaders));
        AtlasSearchResultScrubRequest request = new AtlasSearchResultScrubRequest(atlasTypeRegistry, searchResult);

        AtlasAuthorizationUtils.scrubSearchResults(request, true);
        RequestContext.get().endMetricRecord(metricRecorder);
    }

    private List<String> getColumnIds(AtlasEntity entity) {
        List<String> ret        = new ArrayList<>();
        Object       columnObjs = entity.getAttribute(COLUMNS);

        if (columnObjs instanceof List) {
            for (Object pkObj : (List) columnObjs) {
                if (pkObj instanceof AtlasObjectId) {
                    ret.add(((AtlasObjectId) pkObj).getGuid());
                }
            }
        }

        return ret;
    }

    private boolean isColumn(List<String> columnIds, Map.Entry<String, AtlasEntity> e) {
        return columnIds.contains(e.getValue().getGuid());
    }

    private AtlasLineageInfo getLineageInfoV1(AtlasLineageContext lineageContext) throws AtlasBaseException {
        AtlasLineageInfo ret;
        LineageDirection direction = lineageContext.getDirection();

        if (direction.equals(INPUT)) {
            ret = getLineageInfo(lineageContext, INPUT);
        } else if (direction.equals(OUTPUT)) {
            ret = getLineageInfo(lineageContext, OUTPUT);
        } else {
            ret = getBothLineageInfoV1(lineageContext);
        }

        return ret;
    }

    private AtlasLineageInfo getLineageInfo(AtlasLineageContext lineageContext, LineageDirection direction) throws AtlasBaseException {
        int depth = lineageContext.getDepth();
        String guid = lineageContext.getGuid();
        boolean isDataSet = lineageContext.isDataset();

        final Map<String, Object>      bindings     = new HashMap<>();
        String                         lineageQuery = getLineageQuery(guid, direction, depth, isDataSet, bindings);
        List                           results      = executeGremlinScript(bindings, lineageQuery);
        Map<String, AtlasEntityHeader> entities     = new HashMap<>();
        Set<LineageRelation>           relations    = new HashSet<>();

        if (CollectionUtils.isNotEmpty(results)) {
            for (Object result : results) {
                if (result instanceof Map) {
                    for (final Object o : ((Map) result).entrySet()) {
                        final Map.Entry entry = (Map.Entry) o;
                        Object          value = entry.getValue();

                        if (value instanceof List) {
                            for (Object elem : (List) value) {
                                if (elem instanceof AtlasEdge) {
                                    processEdge((AtlasEdge) elem, entities, relations, lineageContext);
                                } else {
                                    LOG.warn("Invalid value of type {} found, ignoring", (elem != null ? elem.getClass().getSimpleName() : "null"));
                                }
                            }
                        } else if (value instanceof AtlasEdge) {
                            processEdge((AtlasEdge) value, entities, relations, lineageContext);
                        } else {
                            LOG.warn("Invalid value of type {} found, ignoring", (value != null ? value.getClass().getSimpleName() : "null"));
                        }
                    }
                } else if (result instanceof AtlasEdge) {
                    processEdge((AtlasEdge) result, entities, relations, lineageContext);
                }
            }
        }

        return new AtlasLineageInfo(guid, entities, relations, direction, depth, -1);
    }

    private AtlasLineageInfo getLineageInfoV2(AtlasLineageContext lineageContext) throws AtlasBaseException {
        int depth = lineageContext.getDepth();
        String guid = lineageContext.getGuid();
        LineageDirection direction = lineageContext.getDirection();

        AtlasLineageInfo ret = initializeLineageInfo(guid, direction, depth, lineageContext.getLimit());

        if (depth == 0) {
            depth = -1;
        }

        if (lineageContext.isDataset()) {
            AtlasVertex datasetVertex = AtlasGraphUtilsV2.findByGuid(this.graph, guid);
            lineageContext.setStartDatasetVertex(datasetVertex);

            if (direction == INPUT || direction == BOTH) {
                traverseEdges(datasetVertex, true, depth, ret, lineageContext);
            }

            if (direction == OUTPUT || direction == BOTH) {
                traverseEdges(datasetVertex, false, depth, ret, lineageContext);
            }
        } else  {
            AtlasVertex processVertex = AtlasGraphUtilsV2.findByGuid(this.graph, guid);

            // make one hop to the next dataset vertices from process vertex and traverse with 'depth = depth - 1'
            if (direction == INPUT || direction == BOTH) {
                Iterator<AtlasEdge> processEdges = processVertex.getEdges(AtlasEdgeDirection.OUT, PROCESS_INPUTS_EDGE).iterator();

                List<AtlasEdge> qualifyingEdges = getQualifyingProcessEdges(processEdges, lineageContext);
                ret.addChildrenCount(GraphHelper.getGuid(processVertex), INPUT, qualifyingEdges.size());

                if (lineageContext.shouldApplyLimit() && qualifyingEdges.size() > lineageContext.getLimit()) {
                    qualifyingEdges = qualifyingEdges.subList(0, lineageContext.getLimit());
                }

                for (AtlasEdge processEdge : qualifyingEdges) {
                    addEdgeToResult(processEdge, ret, lineageContext);

                    AtlasVertex datasetVertex = processEdge.getInVertex();

                    traverseEdges(datasetVertex, true, depth - 1, ret, lineageContext);
                }
            }

            if (direction == OUTPUT || direction == BOTH) {
                Iterator<AtlasEdge> processEdges = processVertex.getEdges(AtlasEdgeDirection.OUT, PROCESS_OUTPUTS_EDGE).iterator();

                List<AtlasEdge> qualifyingEdges = getQualifyingProcessEdges(processEdges, lineageContext);
                ret.addChildrenCount(GraphHelper.getGuid(processVertex), OUTPUT, qualifyingEdges.size());

                if (lineageContext.shouldApplyLimit() && qualifyingEdges.size() > lineageContext.getLimit()) {
                    qualifyingEdges = qualifyingEdges.subList(0, lineageContext.getLimit());
                }

                for (AtlasEdge processEdge : qualifyingEdges) {
                    addEdgeToResult(processEdge, ret, lineageContext);

                    AtlasVertex datasetVertex = processEdge.getInVertex();

                    traverseEdges(datasetVertex, false, depth - 1, ret, lineageContext);
                }
            }
        }

        return ret;
    }

    private List<AtlasEdge> getQualifyingProcessEdges(Iterator<AtlasEdge> processEdges, AtlasLineageContext lineageContext) {

        List<AtlasEdge> qualifyingEdges = new ArrayList<>();
        while (processEdges.hasNext()) {
            AtlasEdge processEdge = processEdges.next();
            if(lineageContext.isAllowDeletedProcess() || GraphHelper.getStatus(processEdge) == AtlasEntity.Status.ACTIVE ) {
                if (lineageContext.evaluate(processEdge.getInVertex())) {
                    qualifyingEdges.add(processEdge);
                }
            }
        }

        return qualifyingEdges;
    }

    private void traverseEdges(AtlasVertex datasetVertex, boolean isInput, int depth, AtlasLineageInfo ret,
                               AtlasLineageContext lineageContext) throws AtlasBaseException {
        traverseEdges(datasetVertex, isInput, depth, new HashSet<>(), ret, lineageContext);
    }

    private void traverseEdges(AtlasVertex datasetVertex, boolean isInput, int depth, Set<String> visitedVertices, AtlasLineageInfo ret,
                               AtlasLineageContext lineageContext) throws AtlasBaseException {
        if (depth != 0) {
            // keep track of visited vertices to avoid circular loop
            visitedVertices.add(getId(datasetVertex));

            if (datasetVertex.equals(lineageContext.getStartDatasetVertex()) || lineageContext.evaluate(datasetVertex)) {
                Iterator<AtlasEdge> processEdges = datasetVertex.getEdges(IN, isInput ? PROCESS_OUTPUTS_EDGE : PROCESS_INPUTS_EDGE).iterator();

                List<AtlasEdge> processEdgesList = new ArrayList<>();

                while (processEdges.hasNext()) {
                    AtlasEdge processEdge = processEdges.next();
                    if (lineageContext.isAllowDeletedProcess() || GraphHelper.getStatus(processEdge) == AtlasEntity.Status.ACTIVE) {
                        processEdgesList.add(processEdge);
                    }
                }
                ret.addChildrenCount(GraphHelper.getGuid(datasetVertex), isInput ? INPUT : OUTPUT, processEdgesList.size());

                if (lineageContext.shouldApplyLimit() && processEdgesList.size() > lineageContext.getLimit()) {
                    processEdgesList = processEdgesList.subList(0, lineageContext.getLimit());
                }

                for (AtlasEdge incomingEdge : processEdgesList) {
                    AtlasVertex         processVertex = incomingEdge.getOutVertex();
                    Iterator<AtlasEdge> outgoingEdges = processVertex.getEdges(OUT, isInput ? PROCESS_INPUTS_EDGE : PROCESS_OUTPUTS_EDGE).iterator();

                    List<AtlasEdge> qualifyingOutgoingEdges = new ArrayList<>();
                    while (outgoingEdges.hasNext()) {
                        AtlasEdge edge = outgoingEdges.next();
                        if (lineageContext.isAllowDeletedProcess() || GraphHelper.getStatus(edge) == AtlasEntity.Status.ACTIVE) {
                            if ((edge.getInVertex().equals(lineageContext.getStartDatasetVertex()) || lineageContext.evaluate(edge.getInVertex()))) {
                                qualifyingOutgoingEdges.add(edge);
                            }
                        }
                    }
                    ret.addChildrenCount(GraphHelper.getGuid(processVertex), isInput ? INPUT : OUTPUT, qualifyingOutgoingEdges.size());

                    if (lineageContext.shouldApplyLimit() && qualifyingOutgoingEdges.size() > lineageContext.getLimit()) {
                        qualifyingOutgoingEdges = qualifyingOutgoingEdges.subList(0, lineageContext.getLimit());
                    }

                    for (AtlasEdge outgoingEdge : qualifyingOutgoingEdges) {
                        AtlasVertex entityVertex = outgoingEdge.getInVertex();

                        if (entityVertex != null) {
                            if (lineageContext.isHideProcess()) {
                                addVirtualEdgeToResult(incomingEdge, outgoingEdge, ret, lineageContext);
                            } else {
                                addEdgesToResult(incomingEdge, outgoingEdge, ret, lineageContext);
                            }

                            if (!visitedVertices.contains(getId(entityVertex))) {
                                traverseEdges(entityVertex, isInput, depth - 1, visitedVertices, ret, lineageContext);
                            }
                        }
                    }
                }
            }
        } else {
            Iterator<AtlasEdge> processEdges = datasetVertex.getEdges(IN, isInput ? PROCESS_OUTPUTS_EDGE : PROCESS_INPUTS_EDGE).iterator();

            List<AtlasEdge> processEdgesList = new ArrayList<>();
            processEdges.forEachRemaining(processEdgesList::add);

            int qualifyingEdges = 0;
            for (AtlasEdge incomingEdge : processEdgesList) {
                if (lineageContext.isAllowDeletedProcess() || GraphHelper.getStatus(incomingEdge) == AtlasEntity.Status.ACTIVE) {

                    AtlasVertex processVertex = incomingEdge.getOutVertex();
                    Iterator<AtlasEdge> outgoingEdges = processVertex.getEdges(OUT, isInput ? PROCESS_INPUTS_EDGE : PROCESS_OUTPUTS_EDGE).iterator();

                    while (outgoingEdges.hasNext()) {
                        AtlasEdge edge = outgoingEdges.next();
                        if (lineageContext.isAllowDeletedProcess() || GraphHelper.getStatus(edge) == AtlasEntity.Status.ACTIVE) {
                            if ((edge.getInVertex().equals(lineageContext.getStartDatasetVertex()) || lineageContext.evaluate(edge.getInVertex()))) {
                                qualifyingEdges++;
                                break;
                            }
                        }
                    }
                }

            }
            ret.addChildrenCount(GraphHelper.getGuid(datasetVertex), isInput ? INPUT : OUTPUT, qualifyingEdges);
        }
    }

    private void addEdgeToResult(AtlasEdge edge, AtlasLineageInfo lineageInfo,
                                 AtlasLineageContext requestContext) throws AtlasBaseException {
        if (!lineageContainsEdge(lineageInfo, edge)) {
            processEdge(edge, lineageInfo, requestContext);
        }
    }
    private void addEdgesToResult(AtlasEdge incomingEdge, AtlasEdge outgoingEdge, AtlasLineageInfo lineageInfo,
                                 AtlasLineageContext requestContext) throws AtlasBaseException {
        processEdges(incomingEdge, outgoingEdge, lineageInfo, requestContext);
    }

    private boolean addVirtualEdgeToResult(AtlasEdge incomingEdge, AtlasEdge outgoingEdge, AtlasLineageInfo lineageInfo,
                                        AtlasLineageContext lineageContext) throws AtlasBaseException {
        return processVirtualEdge(incomingEdge, outgoingEdge, lineageInfo, lineageContext);
    }

    private boolean lineageContainsEdge(AtlasLineageInfo lineageInfo, AtlasEdge edge) {
        boolean ret = false;

        if (lineageInfo != null && CollectionUtils.isNotEmpty(lineageInfo.getRelations()) && edge != null) {
            String               relationGuid = AtlasGraphUtilsV2.getEncodedProperty(edge, RELATIONSHIP_GUID_PROPERTY_KEY, String.class);
            Set<LineageRelation> relations    = lineageInfo.getRelations();

            for (LineageRelation relation : relations) {
                if (relation.getRelationshipId().equals(relationGuid)) {
                    ret = true;
                    break;
                }
            }
        }

        return ret;
    }

    private AtlasLineageInfo initializeLineageInfo(String guid, LineageDirection direction, int depth, int limit) {
        return new AtlasLineageInfo(guid, new HashMap<>(), new HashSet<>(), direction, depth, limit);
    }

    private static String getId(AtlasVertex vertex) {
        return vertex.getIdForDisplay();
    }

    private List executeGremlinScript(Map<String, Object> bindings, String lineageQuery) throws AtlasBaseException {
        List         ret;
        ScriptEngine engine = graph.getGremlinScriptEngine();

        try {
            ret = (List) graph.executeGremlinScript(engine, bindings, lineageQuery, false);
        } catch (ScriptException e) {
            throw new AtlasBaseException(INSTANCE_LINEAGE_QUERY_FAILED, lineageQuery);
        } finally {
            graph.releaseGremlinScriptEngine(engine);
        }

        return ret;
    }

    private boolean processVirtualEdge(final AtlasEdge incomingEdge, final AtlasEdge outgoingEdge, AtlasLineageInfo lineageInfo,
                                       AtlasLineageContext lineageContext) throws AtlasBaseException {
        final Map<String, AtlasEntityHeader> entities = lineageInfo.getGuidEntityMap();
        final Set<LineageRelation> relations          = lineageInfo.getRelations();

        AtlasVertex inVertex      = incomingEdge.getInVertex();
        AtlasVertex outVertex     = outgoingEdge.getInVertex();
        AtlasVertex processVertex = outgoingEdge.getOutVertex();
        String      inGuid        = AtlasGraphUtilsV2.getIdFromVertex(inVertex);
        String      outGuid       = AtlasGraphUtilsV2.getIdFromVertex(outVertex);
        String      processGuid   = AtlasGraphUtilsV2.getIdFromVertex(processVertex);
        String      relationGuid  = null;
        boolean     isInputEdge   = incomingEdge.getLabel().equalsIgnoreCase(PROCESS_INPUTS_EDGE);

        if (!entities.containsKey(inGuid)) {
            AtlasEntityHeader entityHeader = entityRetriever.toAtlasEntityHeaderWithClassifications(inVertex, lineageContext.getAttributes());
            entities.put(inGuid, entityHeader);
        }

        if (!entities.containsKey(outGuid)) {
            AtlasEntityHeader entityHeader = entityRetriever.toAtlasEntityHeaderWithClassifications(outVertex, lineageContext.getAttributes());
            entities.put(outGuid, entityHeader);
        }

        if (!entities.containsKey(processGuid)) {
            AtlasEntityHeader entityHeader = entityRetriever.toAtlasEntityHeaderWithClassifications(processVertex, lineageContext.getAttributes());
            entities.put(processGuid, entityHeader);    
        }

        if (isInputEdge) {
            relations.add(new LineageRelation(inGuid, outGuid, relationGuid, GraphHelper.getGuid(processVertex)));
        } else {
            relations.add(new LineageRelation(outGuid, inGuid, relationGuid, GraphHelper.getGuid(processVertex)));
        }
        return false;
    }

    private void processEdges(final AtlasEdge incomingEdge, AtlasEdge outgoingEdge, AtlasLineageInfo lineageInfo,
                                 AtlasLineageContext lineageContext) throws AtlasBaseException {
        final Map<String, AtlasEntityHeader> entities = lineageInfo.getGuidEntityMap();
        final Set<LineageRelation> relations          = lineageInfo.getRelations();

        AtlasVertex leftVertex     = incomingEdge.getInVertex();
        AtlasVertex processVertex  = incomingEdge.getOutVertex();
        AtlasVertex rightVertex    = outgoingEdge.getInVertex();

        String leftGuid     = AtlasGraphUtilsV2.getIdFromVertex(leftVertex);
        String rightGuid    = AtlasGraphUtilsV2.getIdFromVertex(rightVertex);
        String processGuid  = AtlasGraphUtilsV2.getIdFromVertex(processVertex);;

        if (!entities.containsKey(leftGuid)) {
            AtlasEntityHeader entityHeader = entityRetriever.toAtlasEntityHeaderWithClassifications(leftVertex, lineageContext.getAttributes());
            entities.put(leftGuid, entityHeader);
        }

        if (!entities.containsKey(processGuid)) {
            AtlasEntityHeader entityHeader = entityRetriever.toAtlasEntityHeaderWithClassifications(processVertex, lineageContext.getAttributes());
            entities.put(processGuid, entityHeader);
        }

        if (!entities.containsKey(rightGuid)) {
            AtlasEntityHeader entityHeader = entityRetriever.toAtlasEntityHeaderWithClassifications(rightVertex, lineageContext.getAttributes());
            entities.put(rightGuid, entityHeader);
        }

        String relationGuid = AtlasGraphUtilsV2.getEncodedProperty(incomingEdge, RELATIONSHIP_GUID_PROPERTY_KEY, String.class);
        if (incomingEdge.getLabel().equalsIgnoreCase(PROCESS_INPUTS_EDGE)) {
            relations.add(new LineageRelation(leftGuid, processGuid, relationGuid));
        } else {
            relations.add(new LineageRelation(processGuid, leftGuid, relationGuid));
        }

        relationGuid = AtlasGraphUtilsV2.getEncodedProperty(outgoingEdge, RELATIONSHIP_GUID_PROPERTY_KEY, String.class);
        if (outgoingEdge.getLabel().equalsIgnoreCase(PROCESS_INPUTS_EDGE)) {
            relations.add(new LineageRelation(rightGuid, processGuid, relationGuid));
        } else {
            relations.add(new LineageRelation(processGuid, rightGuid, relationGuid));
        }
    }

    private void processEdge(final AtlasEdge edge, AtlasLineageInfo lineageInfo,
                             AtlasLineageContext lineageContext) throws AtlasBaseException {
        final Map<String, AtlasEntityHeader> entities = lineageInfo.getGuidEntityMap();
        final Set<LineageRelation> relations          = lineageInfo.getRelations();

        AtlasVertex inVertex     = edge.getInVertex();
        AtlasVertex outVertex    = edge.getOutVertex();
        String      inGuid       = AtlasGraphUtilsV2.getIdFromVertex(inVertex);
        String      outGuid      = AtlasGraphUtilsV2.getIdFromVertex(outVertex);
        String      relationGuid = AtlasGraphUtilsV2.getEncodedProperty(edge, RELATIONSHIP_GUID_PROPERTY_KEY, String.class);
        boolean     isInputEdge  = edge.getLabel().equalsIgnoreCase(PROCESS_INPUTS_EDGE);

        if (!entities.containsKey(inGuid)) {
            AtlasEntityHeader entityHeader = entityRetriever.toAtlasEntityHeaderWithClassifications(inVertex, lineageContext.getAttributes());
            entities.put(inGuid, entityHeader);
        }

        if (!entities.containsKey(outGuid)) {
            AtlasEntityHeader entityHeader = entityRetriever.toAtlasEntityHeaderWithClassifications(outVertex, lineageContext.getAttributes());
            entities.put(outGuid, entityHeader);
        }

        if (isInputEdge) {
            relations.add(new LineageRelation(inGuid, outGuid, relationGuid));
        } else {
            relations.add(new LineageRelation(outGuid, inGuid, relationGuid));
        }
    }

    private void processEdge(final AtlasEdge edge, final Map<String, AtlasEntityHeader> entities,
                             final Set<LineageRelation> relations, AtlasLineageContext lineageContext) throws AtlasBaseException {
        //Backward compatibility method
        AtlasVertex inVertex     = edge.getInVertex();
        AtlasVertex outVertex    = edge.getOutVertex();
        String      inGuid       = AtlasGraphUtilsV2.getIdFromVertex(inVertex);
        String      outGuid      = AtlasGraphUtilsV2.getIdFromVertex(outVertex);
        String      relationGuid = AtlasGraphUtilsV2.getEncodedProperty(edge, RELATIONSHIP_GUID_PROPERTY_KEY, String.class);
        boolean     isInputEdge  = edge.getLabel().equalsIgnoreCase(PROCESS_INPUTS_EDGE);

        if (!entities.containsKey(inGuid)) {
            AtlasEntityHeader entityHeader = entityRetriever.toAtlasEntityHeaderWithClassifications(inVertex, lineageContext.getAttributes());
            entities.put(inGuid, entityHeader);
        }

        if (!entities.containsKey(outGuid)) {
            AtlasEntityHeader entityHeader = entityRetriever.toAtlasEntityHeaderWithClassifications(outVertex, lineageContext.getAttributes());
            entities.put(outGuid, entityHeader);
        }

        if (isInputEdge) {
            relations.add(new LineageRelation(inGuid, outGuid, relationGuid));
        } else {
            relations.add(new LineageRelation(outGuid, inGuid, relationGuid));
        }
    }

    private AtlasLineageInfo getBothLineageInfoV1(AtlasLineageContext lineageContext) throws AtlasBaseException {
        AtlasLineageInfo inputLineage  = getLineageInfo(lineageContext, INPUT);
        AtlasLineageInfo outputLineage = getLineageInfo(lineageContext, OUTPUT);
        AtlasLineageInfo ret           = inputLineage;

        ret.getRelations().addAll(outputLineage.getRelations());
        ret.getGuidEntityMap().putAll(outputLineage.getGuidEntityMap());
        ret.setLineageDirection(BOTH);

        return ret;
    }

    private String getLineageQuery(String entityGuid, LineageDirection direction, int depth, boolean isDataSet, Map<String, Object> bindings) {
        String incomingFrom = null;
        String outgoingTo   = null;
        String ret;

        if (direction.equals(INPUT)) {
            incomingFrom = PROCESS_OUTPUTS_EDGE;
            outgoingTo   = PROCESS_INPUTS_EDGE;
        } else if (direction.equals(OUTPUT)) {
            incomingFrom = PROCESS_INPUTS_EDGE;
            outgoingTo   = PROCESS_OUTPUTS_EDGE;
        }

        bindings.put("guid", entityGuid);
        bindings.put("incomingEdgeLabel", incomingFrom);
        bindings.put("outgoingEdgeLabel", outgoingTo);
        bindings.put("dataSetDepth", depth);
        bindings.put("processDepth", depth - 1);

        if (depth < 1) {
            ret = isDataSet ? gremlinQueryProvider.getQuery(FULL_LINEAGE_DATASET) :
                              gremlinQueryProvider.getQuery(FULL_LINEAGE_PROCESS);
        } else {
            ret = isDataSet ? gremlinQueryProvider.getQuery(PARTIAL_LINEAGE_DATASET) :
                              gremlinQueryProvider.getQuery(PARTIAL_LINEAGE_PROCESS);
        }

        return ret;
    }
}