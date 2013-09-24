/**
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.data.neo4j.support.typerepresentation;

import org.neo4j.graphdb.*;
import org.neo4j.graphdb.index.Index;
import org.springframework.data.neo4j.annotation.QueryType;
import org.springframework.data.neo4j.core.GraphDatabase;
import org.springframework.data.neo4j.core.GraphDatabaseGlobalOperations;
import org.springframework.data.neo4j.core.NodeTypeRepresentationStrategy;
import org.springframework.data.neo4j.core.RelationshipTypeRepresentationStrategy;
import org.springframework.data.neo4j.repository.query.CypherQuery;
import org.springframework.data.neo4j.support.index.IndexProvider;
import org.springframework.data.neo4j.support.index.NoSuchIndexException;
import org.springframework.data.neo4j.support.query.QueryEngine;

import java.util.Collections;

public class TypeRepresentationStrategyFactory {
    private final GraphDatabase graphDatabaseService;
    private final Strategy strategy;
    private IndexProvider indexProvider;
    private QueryEngine<CypherQuery> queryEngine;

    public TypeRepresentationStrategyFactory(GraphDatabase graphDatabaseService) {
        this(graphDatabaseService,chooseStrategy(graphDatabaseService), null);
    }
    
    public TypeRepresentationStrategyFactory(GraphDatabase graphDatabaseService, IndexProvider indexProvider) {
        this(graphDatabaseService,chooseStrategy(graphDatabaseService), indexProvider);
    }
    
    public TypeRepresentationStrategyFactory(GraphDatabase graphDatabaseService,Strategy strategy) {
        this(graphDatabaseService, strategy, null);
    }

    public TypeRepresentationStrategyFactory(GraphDatabase graphDatabaseService,Strategy strategy,
                                             IndexProvider indexProvider) {
        this.indexProvider = indexProvider;
        this.graphDatabaseService = graphDatabaseService;
        this.strategy = strategy;
    }

    private static Strategy chooseStrategy(GraphDatabase graphDatabaseService) {
        Transaction tx = graphDatabaseService.beginTx();
        try {
            if (isAlreadyIndexed(graphDatabaseService)) return Strategy.Indexed;
            if (isAlreadySubRef(graphDatabaseService)) return Strategy.SubRef;
            if (isAlreadyLabeled(graphDatabaseService)) return Strategy.Labeled;
            return Strategy.Indexed;
        } finally {
            tx.success();tx.finish();
        }
    }

    private static boolean isAlreadyLabeled(GraphDatabase graphDatabaseService) {
        /*

        I don't think this is a very efficient query - find if there is a better way to
        do this in Cypher, also it seems to break everything else simply by creating the
        query engine in this manner. Sticking with GlobalGraphOps for now

        QueryEngine<CypherQuery> queryEngine = graphDatabaseService.queryEngineFor(QueryType.Cypher);
        Long numLabels = queryEngine.query("start n=node(*) return count( labels(n) ) ", Collections.EMPTY_MAP).to(Long.class).single();
        return numLabels > 0;
        */


        GraphDatabaseGlobalOperations globalOps = graphDatabaseService.getGlobalGraphOperations();
        try {
            return globalOps.getAllLabels().iterator().hasNext();
        } catch (UnsupportedOperationException e) {
            // Currently the REST DB does not support global ops
            // TODO : Look to change REST project to support it
            return false;
        }

    }

    private static boolean isAlreadyIndexed(GraphDatabase graphDatabaseService) {
        try {
            final Index<PropertyContainer> index = graphDatabaseService.getIndex(IndexingNodeTypeRepresentationStrategy.INDEX_NAME);
            return index!=null && Node.class.isAssignableFrom(index.getEntityType());
        } catch(NoSuchIndexException nsie) {
            return false;
        }
    }

    private static boolean isAlreadySubRef(GraphDatabase graphDatabaseService) {
        try {
            for (Relationship rel : graphDatabaseService.getReferenceNode().getRelationships()) {
                if (rel.getType().name().startsWith(SubReferenceNodeTypeRepresentationStrategy.SUBREF_PREFIX)) {
                    return true;
                }
            }
        } catch(NotFoundException nfe) {
            // ignore
        }
        return false;
    }

    public NodeTypeRepresentationStrategy getNodeTypeRepresentationStrategy() {
        return strategy.getNodeTypeRepresentationStrategy(graphDatabaseService, indexProvider);
    }

    public RelationshipTypeRepresentationStrategy getRelationshipTypeRepresentationStrategy() {
        return strategy.getRelationshipTypeRepresentationStrategy(graphDatabaseService, indexProvider);
    }
    
    public void setIndexProvider(IndexProvider indexProvider) {
        this.indexProvider = indexProvider;
    }

    public enum Strategy {
        SubRef {
            @Override
            public NodeTypeRepresentationStrategy getNodeTypeRepresentationStrategy(GraphDatabase graphDatabaseService, IndexProvider indexProvider) {
                return new SubReferenceNodeTypeRepresentationStrategy(graphDatabaseService);
            }

            @Override
            public RelationshipTypeRepresentationStrategy getRelationshipTypeRepresentationStrategy(GraphDatabase graphDatabaseService, IndexProvider indexProvider) {
                return new NoopRelationshipTypeRepresentationStrategy();
            }
        },
        Labeled {
            @Override
            public NodeTypeRepresentationStrategy getNodeTypeRepresentationStrategy(GraphDatabase graphDatabaseService, IndexProvider indexProvider) {
                return new CypherBasedLabelingNodeTypeRepresentationStrategy(graphDatabaseService);
            }

            @Override
            public RelationshipTypeRepresentationStrategy getRelationshipTypeRepresentationStrategy(GraphDatabase graphDatabaseService, IndexProvider indexProvider) {
                return new NoopRelationshipTypeRepresentationStrategy();
            }
        },
        Indexed {
            @Override
            public NodeTypeRepresentationStrategy getNodeTypeRepresentationStrategy(GraphDatabase graphDatabaseService, IndexProvider indexProvider) {
                return new IndexingNodeTypeRepresentationStrategy(graphDatabaseService, indexProvider);
            }

            @Override
            public RelationshipTypeRepresentationStrategy getRelationshipTypeRepresentationStrategy(GraphDatabase graphDatabaseService, IndexProvider indexProvider) {
                return new IndexingRelationshipTypeRepresentationStrategy(graphDatabaseService, indexProvider);
            }
        },
        Noop {
            @Override
            public NodeTypeRepresentationStrategy getNodeTypeRepresentationStrategy(GraphDatabase graphDatabaseService, IndexProvider indexProvider) {
                return new NoopNodeTypeRepresentationStrategy();
            }

            @Override
            public RelationshipTypeRepresentationStrategy getRelationshipTypeRepresentationStrategy(GraphDatabase graphDatabaseService, IndexProvider indexProvider) {
                return new NoopRelationshipTypeRepresentationStrategy();
            }
        };

        public abstract NodeTypeRepresentationStrategy getNodeTypeRepresentationStrategy(GraphDatabase graphDatabaseService, IndexProvider indexProvider);

        public abstract RelationshipTypeRepresentationStrategy getRelationshipTypeRepresentationStrategy(GraphDatabase graphDatabaseService, IndexProvider indexProvider);
    }
}
