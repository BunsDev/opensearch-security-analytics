/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.securityanalytics.correlation;

import kotlin.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.search.join.ScoreMode;
import org.opensearch.OpenSearchStatusException;
import org.opensearch.cluster.routing.Preference;
import org.opensearch.commons.alerting.model.DocLevelQuery;
import org.opensearch.core.action.ActionListener;
import org.opensearch.action.search.MultiSearchRequest;
import org.opensearch.action.search.MultiSearchResponse;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.client.Client;
import org.opensearch.common.xcontent.LoggingDeprecationHandler;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.commons.alerting.action.PublishFindingsRequest;
import org.opensearch.commons.alerting.model.Finding;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.MatchQueryBuilder;
import org.opensearch.index.query.NestedQueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.index.query.RangeQueryBuilder;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.search.SearchHit;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.securityanalytics.config.monitors.DetectorMonitorConfig;
import org.opensearch.securityanalytics.logtype.LogTypeService;
import org.opensearch.securityanalytics.model.CorrelationQuery;
import org.opensearch.securityanalytics.model.CorrelationRule;
import org.opensearch.securityanalytics.model.Detector;
import org.opensearch.securityanalytics.transport.TransportCorrelateFindingAction;
import org.opensearch.securityanalytics.util.AutoCorrelationsRepo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;


public class JoinEngine {

    private final Client client;

    private final PublishFindingsRequest request;

    private final NamedXContentRegistry xContentRegistry;

    private volatile long corrTimeWindow;

    private final TransportCorrelateFindingAction.AsyncCorrelateFindingAction correlateFindingAction;

    private final LogTypeService logTypeService;

    private static final Logger log = LogManager.getLogger(JoinEngine.class);

    public JoinEngine(Client client, PublishFindingsRequest request, NamedXContentRegistry xContentRegistry,
                      long corrTimeWindow, TransportCorrelateFindingAction.AsyncCorrelateFindingAction correlateFindingAction,
                      LogTypeService logTypeService) {
        this.client = client;
        this.request = request;
        this.xContentRegistry = xContentRegistry;
        this.corrTimeWindow = corrTimeWindow;
        this.correlateFindingAction = correlateFindingAction;
        this.logTypeService = logTypeService;
    }

    public void onSearchDetectorResponse(Detector detector, Finding finding) {
        try {
            generateAutoCorrelations(detector, finding);
        } catch (IOException ex) {
            correlateFindingAction.onFailures(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private void generateAutoCorrelations(Detector detector, Finding finding) throws IOException {
        Map<String, Set<String>> autoCorrelations = AutoCorrelationsRepo.autoCorrelationsAsMap();
        long findingTimestamp = finding.getTimestamp().toEpochMilli();

        Set<String> tags = new HashSet<>();
        for (DocLevelQuery query : finding.getDocLevelQueries()) {
            tags.addAll(query.getTags().stream().filter(tag -> tag.startsWith("attack.")).collect(Collectors.toList()));
        }
        Set<String> validIntrusionSets = AutoCorrelationsRepo.validIntrusionSets(autoCorrelations, tags);

        MatchQueryBuilder queryBuilder = QueryBuilders.matchQuery("source", "Sigma");

        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.query(queryBuilder);
        searchSourceBuilder.size(100);

        SearchRequest request = new SearchRequest();
        request.source(searchSourceBuilder);
        logTypeService.searchLogTypes(request, new ActionListener<>() {
            @Override
            public void onResponse(SearchResponse response) {
                MultiSearchRequest mSearchRequest = new MultiSearchRequest();
                SearchHit[] logTypes = response.getHits().getHits();
                List<String> logTypeNames = new ArrayList<>();
                for (SearchHit logType: logTypes) {
                    String logTypeName = logType.getSourceAsMap().get("name").toString();
                    logTypeNames.add(logTypeName);

                    RangeQueryBuilder queryBuilder = QueryBuilders.rangeQuery("timestamp")
                            .gte(findingTimestamp - corrTimeWindow)
                            .lte(findingTimestamp + corrTimeWindow);

                    SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
                    searchSourceBuilder.query(queryBuilder);
                    searchSourceBuilder.size(10000);
                    searchSourceBuilder.fetchField("queries");
                    SearchRequest searchRequest = new SearchRequest();
                    searchRequest.indices(DetectorMonitorConfig.getAllFindingsIndicesPattern(logTypeName));
                    searchRequest.source(searchSourceBuilder);
                    searchRequest.preference(Preference.PRIMARY_FIRST.type());
                    mSearchRequest.add(searchRequest);
                }

                if (!mSearchRequest.requests().isEmpty()) {
                    client.multiSearch(mSearchRequest, new ActionListener<>() {
                        @Override
                        public void onResponse(MultiSearchResponse items) {
                            MultiSearchResponse.Item[] responses = items.getResponses();

                            Map<String, List<String>> autoCorrelationsMap = new HashMap<>();
                            int idx = 0;
                            for (MultiSearchResponse.Item response : responses) {
                                if (response.isFailure()) {
                                    log.info(response.getFailureMessage());
                                    continue;
                                }
                                String logTypeName = logTypeNames.get(idx);

                                SearchHit[] findings = response.getResponse().getHits().getHits();

                                for (SearchHit foundFinding : findings) {
                                    if (!foundFinding.getId().equals(finding.getId())) {
                                        Set<String> findingTags = new HashSet<>();
                                        List<Map<String, Object>> queries = (List<Map<String, Object>>) foundFinding.getSourceAsMap().get("queries");
                                        for (Map<String, Object> query : queries) {
                                            List<String> queryTags = (List<String>) query.get("tags");
                                            findingTags.addAll(queryTags.stream().filter(queryTag -> queryTag.startsWith("attack.")).collect(Collectors.toList()));
                                        }

                                        boolean canCorrelate = false;
                                        for (String tag: tags) {
                                            if (findingTags.contains(tag)) {
                                                canCorrelate = true;
                                                break;
                                            }
                                        }

                                        Set<String> foundIntrusionSets = AutoCorrelationsRepo.validIntrusionSets(autoCorrelations, findingTags);
                                        for (String validIntrusionSet: validIntrusionSets) {
                                            if (foundIntrusionSets.contains(validIntrusionSet)) {
                                                canCorrelate = true;
                                                break;
                                            }
                                        }

                                        if (canCorrelate) {
                                            if (autoCorrelationsMap.containsKey(logTypeName)) {
                                                autoCorrelationsMap.get(logTypeName).add(foundFinding.getId());
                                            } else {
                                                List<String> autoCorrelatedFindings = new ArrayList<>();
                                                autoCorrelatedFindings.add(foundFinding.getId());
                                                autoCorrelationsMap.put(logTypeName, autoCorrelatedFindings);
                                            }
                                        }
                                    }
                                }
                                ++idx;
                            }
                            onAutoCorrelations(detector, finding, autoCorrelationsMap);
                        }

                        @Override
                        public void onFailure(Exception e) {
                            correlateFindingAction.onFailures(e);
                        }
                    });
                }
            }

            @Override
            public void onFailure(Exception e) {
                correlateFindingAction.onFailures(e);
            }
        });
    }

    private void onAutoCorrelations(Detector detector, Finding finding, Map<String, List<String>> autoCorrelations) {
        String detectorType = detector.getDetectorType().toLowerCase(Locale.ROOT);
        List<String> indices = detector.getInputs().get(0).getIndices();
        List<String> relatedDocIds = finding.getCorrelatedDocIds();

        NestedQueryBuilder queryBuilder = QueryBuilders.nestedQuery(
                "correlate",
                QueryBuilders.matchQuery("correlate.category", detectorType),
                ScoreMode.None
        );
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.query(queryBuilder);
        searchSourceBuilder.fetchSource(true);

        SearchRequest searchRequest = new SearchRequest();
        searchRequest.indices(CorrelationRule.CORRELATION_RULE_INDEX);
        searchRequest.source(searchSourceBuilder);
        searchRequest.preference(Preference.PRIMARY_FIRST.type());

        client.search(searchRequest, new ActionListener<>() {
            @Override
            public void onResponse(SearchResponse response) {
                if (response.isTimedOut()) {
                    correlateFindingAction.onFailures(new OpenSearchStatusException("Search request timed out", RestStatus.REQUEST_TIMEOUT));
                }

                Iterator<SearchHit> hits = response.getHits().iterator();
                List<CorrelationRule> correlationRules = new ArrayList<>();
                while (hits.hasNext()) {
                    try {
                        SearchHit hit = hits.next();

                        XContentParser xcp = XContentType.JSON.xContent().createParser(
                                xContentRegistry,
                                LoggingDeprecationHandler.INSTANCE, hit.getSourceAsString()
                        );

                        CorrelationRule rule = CorrelationRule.parse(xcp, hit.getId(), hit.getVersion());
                        correlationRules.add(rule);
                    } catch (IOException e) {
                        correlateFindingAction.onFailures(e);
                    }
                }

                getValidDocuments(detectorType, indices, correlationRules, relatedDocIds, autoCorrelations);
            }

            @Override
            public void onFailure(Exception e) {
                getValidDocuments(detectorType, indices, List.of(), List.of(), autoCorrelations);
            }
        });
    }

    /**
     * this method checks if the finding to be correlated has valid related docs(or not) which match join criteria.
     */
    private void getValidDocuments(String detectorType, List<String> indices, List<CorrelationRule> correlationRules, List<String> relatedDocIds, Map<String, List<String>> autoCorrelations) {
        MultiSearchRequest mSearchRequest = new MultiSearchRequest();
        List<CorrelationRule> validCorrelationRules = new ArrayList<>();

        for (CorrelationRule rule: correlationRules) {
            Optional<CorrelationQuery> query = rule.getCorrelationQueries().stream()
                    .filter(correlationQuery -> correlationQuery.getCategory().equals(detectorType)).findFirst();

            if (query.isPresent()) {
                BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery()
                        .filter(QueryBuilders.termsQuery("_id", relatedDocIds))
                        .must(QueryBuilders.queryStringQuery(query.get().getQuery()));

                SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
                searchSourceBuilder.query(queryBuilder);
                searchSourceBuilder.fetchSource(false);
                searchSourceBuilder.size(10000);
                SearchRequest searchRequest = new SearchRequest();
                searchRequest.indices(indices.toArray(new String[]{}));
                searchRequest.source(searchSourceBuilder);
                searchRequest.preference(Preference.PRIMARY_FIRST.type());

                validCorrelationRules.add(rule);
                mSearchRequest.add(searchRequest);
            }
        }

        if (!mSearchRequest.requests().isEmpty()) {
            client.multiSearch(mSearchRequest, new ActionListener<>() {
                @Override
                public void onResponse(MultiSearchResponse items) {
                    MultiSearchResponse.Item[] responses = items.getResponses();
                    List<CorrelationRule> filteredCorrelationRules = new ArrayList<>();

                    int idx = 0;
                    for (MultiSearchResponse.Item response : responses) {
                        if (response.isFailure()) {
                            log.info(response.getFailureMessage());
                            continue;
                        }

                        if (response.getResponse().getHits().getTotalHits().value > 0L) {
                            filteredCorrelationRules.add(validCorrelationRules.get(idx));
                        }
                        ++idx;
                    }

                    Map<String, List<CorrelationQuery>> categoryToQueriesMap = new HashMap<>();
                    for (CorrelationRule rule: filteredCorrelationRules) {
                        List<CorrelationQuery> queries = rule.getCorrelationQueries();

                        for (CorrelationQuery query: queries) {
                            List<CorrelationQuery> correlationQueries;
                            if (categoryToQueriesMap.containsKey(query.getCategory())) {
                                correlationQueries = categoryToQueriesMap.get(query.getCategory());
                            } else {
                                correlationQueries = new ArrayList<>();
                            }
                            correlationQueries.add(query);
                            categoryToQueriesMap.put(query.getCategory(), correlationQueries);
                        }
                    }
                    searchFindingsByTimestamp(detectorType, categoryToQueriesMap,
                            filteredCorrelationRules.stream().map(CorrelationRule::getId).collect(Collectors.toList()),
                            autoCorrelations
                    );
                }

                @Override
                public void onFailure(Exception e) {
                    correlateFindingAction.onFailures(e);
                }
            });
        } else {
            if (!autoCorrelations.isEmpty()) {
                correlateFindingAction.getTimestampFeature(detectorType, autoCorrelations, null, List.of());
            } else {
                correlateFindingAction.getTimestampFeature(detectorType, null, request.getFinding(), List.of());
            }
        }
    }

    /**
     * this method searches for parent findings given the log category & correlation time window & collects all related docs
     * for them.
     */
    private void searchFindingsByTimestamp(String detectorType, Map<String, List<CorrelationQuery>> categoryToQueriesMap, List<String> correlationRules, Map<String, List<String>> autoCorrelations) {
        long findingTimestamp = request.getFinding().getTimestamp().toEpochMilli();
        MultiSearchRequest mSearchRequest = new MultiSearchRequest();
        List<Pair<String, List<CorrelationQuery>>> categoryToQueriesPairs = new ArrayList<>();

        for (Map.Entry<String, List<CorrelationQuery>> categoryToQueries: categoryToQueriesMap.entrySet()) {
            RangeQueryBuilder queryBuilder = QueryBuilders.rangeQuery("timestamp")
                    .gte(findingTimestamp - corrTimeWindow)
                    .lte(findingTimestamp + corrTimeWindow);

            SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
            searchSourceBuilder.query(queryBuilder);
            searchSourceBuilder.fetchSource(false);
            searchSourceBuilder.size(10000);
            searchSourceBuilder.fetchField("correlated_doc_ids");
            SearchRequest searchRequest = new SearchRequest();
            searchRequest.indices(DetectorMonitorConfig.getAllFindingsIndicesPattern(categoryToQueries.getKey()));
            searchRequest.source(searchSourceBuilder);
            searchRequest.preference(Preference.PRIMARY_FIRST.type());
            mSearchRequest.add(searchRequest);
            categoryToQueriesPairs.add(new Pair<>(categoryToQueries.getKey(), categoryToQueries.getValue()));
        }

        if (!mSearchRequest.requests().isEmpty()) {
            client.multiSearch(mSearchRequest, new ActionListener<>() {
                @Override
                public void onResponse(MultiSearchResponse items) {
                    MultiSearchResponse.Item[] responses = items.getResponses();
                    Map<String, DocSearchCriteria> relatedDocsMap = new HashMap<>();

                    int idx = 0;
                    for (MultiSearchResponse.Item response : responses) {
                        if (response.isFailure()) {
                            log.info(response.getFailureMessage());
                            continue;
                        }

                        List<String> relatedDocIds = new ArrayList<>();
                        SearchHit[] hits = response.getResponse().getHits().getHits();
                        for (SearchHit hit : hits) {
                            relatedDocIds.addAll(hit.getFields().get("correlated_doc_ids").getValues().stream()
                                    .map(Object::toString).collect(Collectors.toList()));
                        }

                        List<CorrelationQuery> correlationQueries = categoryToQueriesPairs.get(idx).getSecond();
                        List<String> indices = correlationQueries.stream().map(CorrelationQuery::getIndex).collect(Collectors.toList());
                        List<String> queries = correlationQueries.stream().map(CorrelationQuery::getQuery).collect(Collectors.toList());
                        relatedDocsMap.put(categoryToQueriesPairs.get(idx).getFirst(),
                                new DocSearchCriteria(
                                        indices,
                                        queries,
                                        relatedDocIds));
                        ++idx;
                    }
                    searchDocsWithFilterKeys(detectorType, relatedDocsMap, correlationRules, autoCorrelations);
                }

                @Override
                public void onFailure(Exception e) {
                    correlateFindingAction.onFailures(e);
                }
            });
        } else {
            if (!autoCorrelations.isEmpty()) {
                correlateFindingAction.getTimestampFeature(detectorType, autoCorrelations, null, List.of());
            } else {
                correlateFindingAction.getTimestampFeature(detectorType, null, request.getFinding(), correlationRules);
            }
        }
    }

    /**
     * Given the related docs from parent findings, this method filters only those related docs which match parent join criteria.
     */
    private void searchDocsWithFilterKeys(String detectorType, Map<String, DocSearchCriteria> relatedDocsMap, List<String> correlationRules, Map<String, List<String>> autoCorrelations) {
        MultiSearchRequest mSearchRequest = new MultiSearchRequest();
        List<String> categories = new ArrayList<>();

        for (Map.Entry<String, DocSearchCriteria> docSearchCriteria: relatedDocsMap.entrySet()) {
            BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery()
                    .filter(QueryBuilders.termsQuery("_id", docSearchCriteria.getValue().relatedDocIds));

            for (String query: docSearchCriteria.getValue().queries) {
                queryBuilder = queryBuilder.should(QueryBuilders.queryStringQuery(query));
            }

            SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
            searchSourceBuilder.query(queryBuilder);
            searchSourceBuilder.fetchSource(false);
            searchSourceBuilder.size(10000);
            SearchRequest searchRequest = new SearchRequest();
            searchRequest.indices(docSearchCriteria.getValue().indices.toArray(new String[]{}));
            searchRequest.source(searchSourceBuilder);
            searchRequest.preference(Preference.PRIMARY_FIRST.type());

            categories.add(docSearchCriteria.getKey());
            mSearchRequest.add(searchRequest);
        }

        if (!mSearchRequest.requests().isEmpty()) {
            client.multiSearch(mSearchRequest, new ActionListener<>() {
                @Override
                public void onResponse(MultiSearchResponse items) {
                    MultiSearchResponse.Item[] responses = items.getResponses();
                    Map<String, List<String>> filteredRelatedDocIds = new HashMap<>();

                    int idx = 0;
                    for (MultiSearchResponse.Item response : responses) {
                        if (response.isFailure()) {
                            log.info(response.getFailureMessage());
                            continue;
                        }

                        SearchHit[] hits = response.getResponse().getHits().getHits();
                        List<String> docIds = new ArrayList<>();

                        for (SearchHit hit : hits) {
                            docIds.add(hit.getId());
                        }
                        filteredRelatedDocIds.put(categories.get(idx), docIds);
                        ++idx;
                    }
                    getCorrelatedFindings(detectorType, filteredRelatedDocIds, correlationRules, autoCorrelations);
                }

                @Override
                public void onFailure(Exception e) {
                    correlateFindingAction.onFailures(e);
                }
            });
        } else {
            if (!autoCorrelations.isEmpty()) {
                correlateFindingAction.getTimestampFeature(detectorType, autoCorrelations, null, List.of());
            } else {
                correlateFindingAction.getTimestampFeature(detectorType, null, request.getFinding(), correlationRules);
            }
        }
    }

    /**
     * Given the filtered related docs of the parent findings, this method gets the actual filtered parent findings for
     * the finding to be correlated.
     */
    private void getCorrelatedFindings(String detectorType, Map<String, List<String>> filteredRelatedDocIds, List<String> correlationRules, Map<String, List<String>> autoCorrelations) {
        long findingTimestamp = request.getFinding().getTimestamp().toEpochMilli();
        MultiSearchRequest mSearchRequest = new MultiSearchRequest();
        List<String> categories = new ArrayList<>();

        for (Map.Entry<String, List<String>> relatedDocIds: filteredRelatedDocIds.entrySet()) {
            BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery()
                    .filter(QueryBuilders.rangeQuery("timestamp")
                            .gte(findingTimestamp - corrTimeWindow)
                            .lte(findingTimestamp + corrTimeWindow))
                    .must(QueryBuilders.termsQuery("correlated_doc_ids", relatedDocIds.getValue()));

            if (relatedDocIds.getKey().equals(detectorType)) {
                queryBuilder = queryBuilder.mustNot(QueryBuilders.matchQuery("_id", request.getFinding().getId()));
            }

            SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
            searchSourceBuilder.query(queryBuilder);
            searchSourceBuilder.fetchSource(false);
            searchSourceBuilder.size(10000);
            SearchRequest searchRequest = new SearchRequest();
            searchRequest.indices(DetectorMonitorConfig.getAllFindingsIndicesPattern(relatedDocIds.getKey()));
            searchRequest.source(searchSourceBuilder);
            searchRequest.preference(Preference.PRIMARY_FIRST.type());

            categories.add(relatedDocIds.getKey());
            mSearchRequest.add(searchRequest);
        }

        if (!mSearchRequest.requests().isEmpty()) {
            client.multiSearch(mSearchRequest, new ActionListener<>() {
                @Override
                public void onResponse(MultiSearchResponse items) {
                    MultiSearchResponse.Item[] responses = items.getResponses();
                    Map<String, List<String>> correlatedFindings = new HashMap<>();

                    int idx = 0;
                    for (MultiSearchResponse.Item response : responses) {
                        if (response.isFailure()) {
                            log.info(response.getFailureMessage());
                            ++idx;
                            continue;
                        }

                        SearchHit[] hits = response.getResponse().getHits().getHits();
                        List<String> findings = new ArrayList<>();

                        for (SearchHit hit : hits) {
                            findings.add(hit.getId());
                        }

                        if (!findings.isEmpty()) {
                            correlatedFindings.put(categories.get(idx), findings);
                        }
                        ++idx;
                    }

                    for (Map.Entry<String, List<String>> autoCorrelation: autoCorrelations.entrySet()) {
                        if (correlatedFindings.containsKey(autoCorrelation.getKey())) {
                            Set<String> alreadyCorrelatedFindings = new HashSet<>(correlatedFindings.get(autoCorrelation.getKey()));
                            alreadyCorrelatedFindings.addAll(autoCorrelation.getValue());
                            correlatedFindings.put(autoCorrelation.getKey(), new ArrayList<>(alreadyCorrelatedFindings));
                        } else {
                            correlatedFindings.put(autoCorrelation.getKey(), autoCorrelation.getValue());
                        }
                    }
                    correlateFindingAction.initCorrelationIndex(detectorType, correlatedFindings, correlationRules);
                }

                @Override
                public void onFailure(Exception e) {
                    correlateFindingAction.onFailures(e);
                }
            });
        } else {
            if (!autoCorrelations.isEmpty()) {
                correlateFindingAction.getTimestampFeature(detectorType, autoCorrelations, null, List.of());
            } else {
                correlateFindingAction.getTimestampFeature(detectorType, null, request.getFinding(), correlationRules);
            }
        }
    }

    static class DocSearchCriteria {
        List<String> indices;
        List<String> queries;
        List<String> relatedDocIds;

        public DocSearchCriteria(List<String> indices, List<String> queries, List<String> relatedDocIds) {
            this.indices = indices;
            this.queries = queries;
            this.relatedDocIds = relatedDocIds;
        }
    }

    static class ParentJoinCriteria {
        String category;
        String index;
        String parentJoinQuery;

        public ParentJoinCriteria(String category, String index, String parentJoinQuery) {
            this.category = category;
            this.index = index;
            this.parentJoinQuery = parentJoinQuery;
        }
    }
}