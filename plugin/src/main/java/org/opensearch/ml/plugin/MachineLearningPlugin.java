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


package org.opensearch.ml.plugin;

import com.google.common.collect.ImmutableMap;
import org.opensearch.client.Client;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.io.stream.NamedWriteableRegistry;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.IndexScopedSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.settings.SettingsFilter;
import org.opensearch.common.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.ml.action.stats.MLStatsNodesAction;
import org.opensearch.ml.action.stats.MLStatsNodesTransportAction;
import org.opensearch.ml.rest.RestStatsMLAction;
import org.opensearch.ml.stats.MLStat;
import org.opensearch.ml.stats.MLStats;
import org.opensearch.ml.stats.StatNames;
import org.opensearch.ml.stats.suppliers.CounterSupplier;
import org.opensearch.plugins.ActionPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionResponse;
import com.google.common.collect.ImmutableList;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.rest.RestController;
import org.opensearch.rest.RestHandler;
import org.opensearch.script.ScriptService;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.watcher.ResourceWatcherService;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class MachineLearningPlugin extends Plugin implements ActionPlugin {
    public static final String ML_BASE_URI = "/_opensearch/_ml";

    private MLStats mlStats;

    @Override
    public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        return ImmutableList.of(
                new ActionHandler<>(MLStatsNodesAction.INSTANCE,
                        MLStatsNodesTransportAction.class)
        );
    }

    @Override
    public Collection<Object> createComponents(Client client, ClusterService clusterService, ThreadPool threadPool,
                                               ResourceWatcherService resourceWatcherService,
                                               ScriptService scriptService,
                                               NamedXContentRegistry xContentRegistry, Environment environment,
                                               NodeEnvironment nodeEnvironment,
                                               NamedWriteableRegistry namedWriteableRegistry,
                                               IndexNameExpressionResolver indexNameExpressionResolver,
                                               Supplier<RepositoriesService> repositoriesServiceSupplier) {
        Map<String, MLStat<?>> stats = ImmutableMap
                .<String, MLStat<?>>builder()
                .put(StatNames.ML_EXECUTING_TASK_COUNT.getName(), new MLStat<>(false, new CounterSupplier()))
                .build();
        this.mlStats = new MLStats(stats);
        return ImmutableList.of(mlStats);
    }

    @Override
    public List<RestHandler> getRestHandlers(
            Settings settings,
            RestController restController,
            ClusterSettings clusterSettings,
            IndexScopedSettings indexScopedSettings,
            SettingsFilter settingsFilter,
            IndexNameExpressionResolver indexNameExpressionResolver,
            Supplier<DiscoveryNodes> nodesInCluster
    ) {
        RestStatsMLAction restStatsMLAction = new RestStatsMLAction(mlStats);
        return ImmutableList
                .of(
                        restStatsMLAction
                );
    }
}