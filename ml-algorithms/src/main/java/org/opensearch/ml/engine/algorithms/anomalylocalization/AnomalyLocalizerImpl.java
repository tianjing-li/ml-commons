/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 *
 */

package org.opensearch.ml.engine.algorithms.anomalylocalization;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.opensearch.action.ActionListener;
import org.opensearch.action.NotifyOnceListener;
import org.opensearch.action.search.MultiSearchRequest;
import org.opensearch.action.search.MultiSearchResponse;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.client.Client;
import org.opensearch.common.settings.Settings;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.index.query.RangeQueryBuilder;
import org.opensearch.index.query.TermQueryBuilder;
import org.opensearch.search.aggregations.AggregationBuilder;
import org.opensearch.search.aggregations.AggregationBuilders;
import org.opensearch.search.aggregations.bucket.composite.CompositeAggregation;
import org.opensearch.search.aggregations.bucket.composite.CompositeAggregationBuilder;
import org.opensearch.search.aggregations.bucket.composite.TermsValuesSourceBuilder;
import org.opensearch.search.aggregations.bucket.filter.Filters;
import org.opensearch.search.aggregations.bucket.filter.FiltersAggregationBuilder;
import org.opensearch.search.aggregations.bucket.filter.FiltersAggregator.KeyedFilter;
import org.opensearch.search.aggregations.metrics.NumericMetricsAggregation.SingleValue;
import org.opensearch.search.builder.SearchSourceBuilder;

import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;

import static org.opensearch.action.ActionListener.wrap;
import static org.opensearch.search.aggregations.MultiBucketConsumerService.MAX_BUCKET_SETTING;

/**
 * Implementation of AnomalyLocalizer.
 */
@Log4j2
public class AnomalyLocalizerImpl implements AnomalyLocalizer {

    // Localize when the change of new value over base value is over the percentage.
    protected static final double MIN_DIFF_PCT = 0.01;

    // Partitions the whole data to up to this number of buckets by time.
    protected static final int MAX_TIME_BUCKETS = 8;

    private final Client client;
    private final Settings settings;

    /**
     * Constructor.
     *
     * @param client   Index data.
     * @param settings Settings information.
     */
    public AnomalyLocalizerImpl(
            Client client,
            Settings settings) {
        this.client = client;
        this.settings = settings;
    }

    /**
     * Implementation of localization.
     * <p>
     * This localization finds the largest contributors to changes in aggregated data over time.
     * The entire time range is first partitioned into equally sized buckets.
     * Each bucket of data is aggregated as a whole and also sliced into entity key-value pairs. With first bucket being the base,
     * the change from a new bucket later is the difference of the new aggregate value and the base aggregate value.
     * The contribution to the change from an entity is the difference between its new value and base value.
     */
    @Override
    @SneakyThrows
    public void getLocalizationResults(Input input, ActionListener<Output> listener) {
        Output output = new Output();
        input.getAggregations().stream().forEach(agg -> localizeByBuckets(input, agg, output, notifyOnce(listener)));
    }

    /**
     * Bucketizes data by time and get overall aggregates.
     */
    private void localizeByBuckets(Input input, AggregationBuilder agg, Output output, ActionListener<Output> listener) {
        LocalizationTimeBuckets timeBuckets = getTimeBuckets(input);
        getOverallAggregates(input, timeBuckets, agg, output, listener);
    }

    private void getOverallAggregates(Input input, LocalizationTimeBuckets timeBuckets, AggregationBuilder agg, Output output,
                                      ActionListener<Output> listener) {
        MultiSearchRequest searchRequest = newSearchRequestForOverallAggregates(input, agg, timeBuckets);
        client.multiSearch(searchRequest, wrap(r -> onOverallAggregatesResponse(r, input, agg, output, timeBuckets, listener),
                listener::onFailure));
    }

    private void onOverallAggregatesResponse(MultiSearchResponse response, Input input, AggregationBuilder agg, Output output,
                                             LocalizationTimeBuckets timeBuckets, ActionListener<Output> listener) {
        Output.Result result = new Output.Result();
        List<Map.Entry<Long, Long>> intervals = timeBuckets.getAllIntervals();
        for (int i = 0; i < intervals.size(); i++) {
            double value = getDoubleValue((SingleValue) response.getResponses()[i].getResponse().getAggregations().get(agg.getName()));

            Output.Bucket bucket = new Output.Bucket();
            bucket.setStartTime(intervals.get(i).getKey());
            bucket.setEndTime(intervals.get(i).getValue());
            bucket.setOverallAggValue(value);
            result.getBuckets().add(bucket);
        }

        output.getResults().put(agg.getName(), result);
        getLocalizedEntities(input, agg, result, output, listener);
    }

    /**
     * Identifies buckets of data that need localization and localizes entities in the bucket.
     */
    private void getLocalizedEntities(Input input, AggregationBuilder agg, Output.Result result, Output output,
                                      ActionListener<Output> listener) {
        if (setBase(result, input)) {
            Counter counter = new HybridCounter();
            result.getBuckets().stream().filter(e -> e.getBase().isPresent() && e.getBase().get().equals(e))
                    .forEach(e -> processBaseEntry(input, agg, result, e, counter, Optional.empty(), output, listener));
        }
        outputIfResultsAreComplete(output, listener);
    }

    private void outputIfResultsAreComplete(Output output, ActionListener<Output> listener) {
        if (output.getResults().values().stream().allMatch(this::isResultComplete)) {
            listener.onResponse(output);
        }
    }

    private boolean isResultComplete(Output.Result result) {
        // When completed is null, the bucket does not localization, base bucket for example.
        return result.getBuckets().stream().allMatch(e -> e.getCompleted() == null || e.getCompleted().get() == true);
    }

    private void processBaseEntry(Input input, AggregationBuilder agg, Output.Result result, Output.Bucket bucket, Counter counter,
                                  Optional<Map<String, Object>> afterKey, Output output, ActionListener<Output> listener) {
        SearchRequest request = newSearchRequestForEntry(input, agg, bucket, afterKey);
        client.search(request, wrap(r -> onBaseEntryResponse(r, input, agg, result, bucket, counter, output, listener),
                listener::onFailure));
    }

    /**
     * Keeps info from entities in the base bucket to compare entities from new buckets against.
     */
    private void onBaseEntryResponse(SearchResponse response, Input input, AggregationBuilder agg, Output.Result result,
                                     Output.Bucket bucket, Counter counter, Output output, ActionListener<Output> listener) {
        Optional<CompositeAggregation> respAgg =
                Optional.ofNullable(response.getAggregations()).map(aggs -> (CompositeAggregation) aggs.get(agg.getName()));
        respAgg.map(a -> a.getBuckets()).orElse(Collections.emptyList()).stream().forEach(b -> {
            counter.increment(toStringKey(b.getKey(), input), getDoubleValue((SingleValue) b.getAggregations().get(agg.getName())));
        });
        Optional<Map<String, Object>> afterKey = respAgg.map(r -> r.afterKey());
        if (afterKey.isPresent()) {
            processBaseEntry(input, agg, result, bucket, counter, afterKey, output, listener);
        } else {
            bucket.setCounter(Optional.of(counter));
            result.getBuckets().stream().filter(e -> e.getCompleted() != null && e.getCompleted().get() == false)
                    .forEach(e -> {
                        PriorityQueue<Output.Entity> queue;
                        if (e.getOverallAggValue() > 0) {
                            queue = new PriorityQueue<Output.Entity>(input.getNumOutputs(),
                                    (a, b) -> (int) Math.signum(a.getContributionValue() - b.getContributionValue()));
                        } else {
                            queue = new PriorityQueue<Output.Entity>(input.getNumOutputs(),
                                    (a, b) -> (int) Math.signum(b.getContributionValue() - a.getContributionValue()));
                        }
                        ;
                        processNewEntry(input, agg, result, e, Optional.empty(), queue, output, listener);
                    });
        }
    }

    private void processNewEntry(Input input, AggregationBuilder agg, Output.Result result, Output.Bucket bucket, Optional<Map<String,
            Object>> afterKey, PriorityQueue<Output.Entity> queue, Output output, ActionListener<Output> listener) {
        SearchRequest request = newSearchRequestForEntry(input, agg, bucket, afterKey);
        client.search(request, wrap(r -> onNewEntryResponse(r, input, agg, result, bucket, queue, output, listener), listener::onFailure));
    }

    /**
     * Chooses entities from the new bucket that contribute the most to the overall change.
     */
    private void onNewEntryResponse(SearchResponse response, Input input, AggregationBuilder agg, Output.Result result,
                                    Output.Bucket outputBucket, PriorityQueue<Output.Entity> queue, Output output,
                                    ActionListener<Output> listener) {
        Optional<CompositeAggregation> respAgg =
                Optional.ofNullable(response.getAggregations()).map(aggs -> (CompositeAggregation) aggs.get(agg.getName()));
        for (CompositeAggregation.Bucket bucket : respAgg.map(a -> a.getBuckets()).orElse(Collections.emptyList())) {
            List<String> key = toStringKey(bucket.getKey(), input);
            Output.Entity entity = new Output.Entity();
            entity.setKey(key);
            entity.setNewValue(getDoubleValue((SingleValue) bucket.getAggregations().get(agg.getName())));
            entity.setBaseValue(outputBucket.getBase().get().getCounter().get().estimate(key));
            entity.setContributionValue(entity.getNewValue() - entity.getBaseValue());
            if (queue.size() < input.getNumOutputs()) {
                queue.add(entity);
            } else if (queue.comparator().compare(queue.peek(), entity) < 0) {
                queue.poll();
                queue.add(entity);
            }
        }
        Optional<Map<String, Object>> afterKey = respAgg.map(r -> r.afterKey());
        if (afterKey.isPresent()) {
            processNewEntry(input, agg, result, outputBucket, afterKey, queue, output, listener);
        } else {
            List<List<String>> keys = queue.stream().map(Output.Entity::getKey).collect(Collectors.toList());
            SearchRequest request = newSearchRequestForEntityKeys(input, agg, outputBucket, keys);
            client.search(request, wrap(r -> onEntityKeysResponse(r, input, agg, result, outputBucket, queue, output, listener),
                    listener::onFailure));
        }
    }

    /**
     * Updates to date entity contribution values in final output.
     */
    private void onEntityKeysResponse(SearchResponse response, Input input, AggregationBuilder agg, Output.Result result,
                                      Output.Bucket bucket, PriorityQueue<Output.Entity> queue, Output output,
                                      ActionListener<Output> listener) {
        List<Output.Entity> entities = new ArrayList<Output.Entity>(queue);
        Optional<Filters> respAgg = Optional.ofNullable(response.getAggregations()).map(aggs -> (Filters) aggs.get(agg.getName()));
        for (Filters.Bucket respBucket : respAgg.map(a -> a.getBuckets()).orElse(Collections.emptyList())) {
            int entityIndex = Integer.parseInt(respBucket.getKeyAsString());
            double aggValue = getDoubleValue((SingleValue) respBucket.getAggregations().get(agg.getName()));

            Output.Entity entity = entities.get(entityIndex);
            entity.setBaseValue(aggValue);
            entity.setContributionValue(entity.getNewValue() - entity.getBaseValue());
        }
        double newChangeSign = Math.signum(bucket.getOverallAggValue() - bucket.getBase().get().getOverallAggValue());
        entities =
                entities.stream().filter(entity -> Math.signum(entity.getContributionValue()) == newChangeSign).sorted(queue.comparator().reversed()).collect(Collectors.toList());
        bucket.setEntities(entities);
        bucket.getCompleted().set(true);

        outputIfResultsAreComplete(output, listener);
    }

    private SearchRequest newSearchRequestForEntityKeys(Input input, AggregationBuilder agg, Output.Bucket bucket,
                                                        List<List<String>> keys) {
        RangeQueryBuilder timeRangeFilter = new RangeQueryBuilder(input.getTimeFieldName())
                .from(bucket.getBase().get().getStartTime(), true)
                .to(bucket.getBase().get().getEndTime(), true);
        BoolQueryBuilder filter = QueryBuilders.boolQuery().filter(timeRangeFilter);
        input.getFilterQuery().ifPresent(q -> filter.filter(q));
        KeyedFilter[] filters = IntStream.range(0, keys.size()).mapToObj(i -> new KeyedFilter(Integer.toString(i),
                newQueryByKey(keys.get(i), input))).toArray(KeyedFilter[]::new);
        FiltersAggregationBuilder filtersAgg = AggregationBuilders.filters(agg.getName(), filters);
        filtersAgg.subAggregation(agg);
        SearchSourceBuilder search = new SearchSourceBuilder().size(0).query(filter).aggregation(filtersAgg);
        SearchRequest searchRequest = new SearchRequest(new String[]{input.getIndexName()}, search);
        return searchRequest;
    }

    private BoolQueryBuilder newQueryByKey(List<String> key, Input input) {
        BoolQueryBuilder bool = new BoolQueryBuilder();
        IntStream.range(0, key.size()).forEach(i -> bool.filter(new TermQueryBuilder(input.getAttributeFieldNames().get(i), key.get(i))));
        return bool;
    }

    private List<String> toStringKey(Map<String, Object> key, Input input) {
        return input.getAttributeFieldNames().stream().map(name -> key.get(name).toString()).collect(Collectors.toList());
    }

    private SearchRequest newSearchRequestForEntry(Input input, AggregationBuilder agg, Output.Bucket bucket, Optional<Map<String,
            Object>> afterKey) {
        RangeQueryBuilder timeRangeFilter = new RangeQueryBuilder(input.getTimeFieldName())
                .from(bucket.getStartTime(), true)
                .to(bucket.getEndTime(), true);
        BoolQueryBuilder filter = QueryBuilders.boolQuery().filter(timeRangeFilter);
        input.getFilterQuery().ifPresent(q -> filter.filter(q));
        CompositeAggregationBuilder compositeAgg = new CompositeAggregationBuilder(agg.getName(),
                input.getAttributeFieldNames().stream().map(name -> new TermsValuesSourceBuilder(name).field(name)).collect(Collectors.toList())).size(MAX_BUCKET_SETTING.get(this.settings));
        compositeAgg.subAggregation(agg);
        if (afterKey.isPresent()) {
            compositeAgg.aggregateAfter(afterKey.get());
        }
        SearchSourceBuilder search = new SearchSourceBuilder().size(0).query(filter).aggregation(compositeAgg);
        SearchRequest searchRequest = new SearchRequest(new String[]{input.getIndexName()}, search);
        return searchRequest;
    }

    private boolean setBase(Output.Result result, Input input) {
        boolean newEntry = false;
        List<Output.Bucket> entries = result.getBuckets();
        int baseEntryIndex = 0;
        Output.Bucket baseEntry = entries.get(baseEntryIndex);
        baseEntry.setBase(Optional.of(baseEntry));
        for (int i = 1; i < entries.size(); i++) {
            Output.Bucket currentEntry = entries.get(i);
            if (input.getAnomalyStartTime().isPresent()) {
                if (currentEntry.getEndTime() > input.getAnomalyStartTime().get()) {
                    currentEntry.setBase(Optional.of(baseEntry));
                    currentEntry.setCompleted(new AtomicBoolean(false));
                    newEntry = true;
                }
            } else {
                if (Math.abs(1. - currentEntry.getOverallAggValue() / baseEntry.getOverallAggValue()) > MIN_DIFF_PCT) {
                    currentEntry.setBase(Optional.of(baseEntry));
                    currentEntry.setCompleted(new AtomicBoolean(false));
                    newEntry = true;
                }
            }
        }
        return newEntry;
    }

    private MultiSearchRequest newSearchRequestForOverallAggregates(Input input, AggregationBuilder agg,
                                                                    LocalizationTimeBuckets timeBuckets) {
        MultiSearchRequest multiSearchRequest = new MultiSearchRequest();
        timeBuckets.getAllIntervals().stream().map(i -> {
            RangeQueryBuilder timeRangeFilter = new RangeQueryBuilder(input.getTimeFieldName())
                    .from(i.getKey(), true)
                    .to(i.getValue(), true);
            BoolQueryBuilder filter = QueryBuilders.boolQuery().filter(timeRangeFilter);
            input.getFilterQuery().ifPresent(q -> filter.filter(q));
            SearchSourceBuilder search = new SearchSourceBuilder().size(0).query(filter).aggregation(agg);
            SearchRequest searchRequest = new SearchRequest(new String[]{input.getIndexName()}, search);
            return searchRequest;
        }).forEach(multiSearchRequest::add);
        return multiSearchRequest;
    }

    private LocalizationTimeBuckets getTimeBuckets(Input input) {
        if ((input.getEndTime() - input.getStartTime()) < 2 * input.getMinTimeInterval()) {
            throw new IllegalArgumentException("Time range is too short");
        }

        LocalizationTimeBuckets buckets;
        if (input.getAnomalyStartTime().isPresent()) {
            long anomalyStart = input.getAnomalyStartTime().get();
            long end = Math.max(input.getEndTime(), anomalyStart + input.getMinTimeInterval());
            int numBuckets = Math.min((int) ((end - anomalyStart) / input.getMinTimeInterval()), MAX_TIME_BUCKETS - 1);
            long bucketInterval = (end - anomalyStart) / numBuckets;
            long start = Math.min(input.getStartTime(), anomalyStart - bucketInterval);
            buckets = new LocalizationTimeBuckets(bucketInterval, start,
                    IntStream.range(0, numBuckets).mapToLong(i -> anomalyStart + i * bucketInterval).toArray());
        } else {
            int numBuckets = Math.min((int) ((input.getEndTime() - input.getStartTime()) / input.getMinTimeInterval()), MAX_TIME_BUCKETS);
            long bucketIntervalMillis = (input.getEndTime() - input.getStartTime()) / numBuckets;
            buckets = new LocalizationTimeBuckets(bucketIntervalMillis, input.getStartTime(),
                    IntStream.rangeClosed(1, numBuckets - 1).mapToLong(i -> input.getStartTime() + i * bucketIntervalMillis).toArray());
        }
        return buckets;
    }

    private <R> ActionListener<R> notifyOnce(ActionListener<R> listener) {
        return new NotifyOnceListener<R>() {
            @Override
            public void innerOnResponse(R r) {
                listener.onResponse(r);
            }

            @Override
            public void innerOnFailure(Exception e) {
                listener.onFailure(e);
            }
        };
    }

    private double getDoubleValue(SingleValue singleValue) {
        double value = singleValue.value();
        return Double.isFinite(value) ? value : 0.0;
    }

    @Data
    protected static class LocalizationTimeBuckets {
        private final long interval;
        private final long baseBucket;
        private final long[] newBuckets;

        protected List<Map.Entry<Long, Long>> getAllIntervals() {
            List<Map.Entry<Long, Long>> intervals = new ArrayList<>(newBuckets.length + 1);
            intervals.add(new SimpleEntry<>(baseBucket, baseBucket + interval));
            Arrays.stream(newBuckets).forEach(t -> intervals.add(new SimpleEntry<>(t, t + interval)));
            return intervals;
        }
    }
}