/*
 * Portions Copyright 2013-2015 Technology Concepts & Design, Inc
 * Portions Copyright 2015-2017 ZomboDB, LLC
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
package com.tcdi.zombodb.query_parser.optimizers;

import com.tcdi.zombodb.query_parser.*;
import com.tcdi.zombodb.query_parser.metadata.Dijkstra;
import com.tcdi.zombodb.query_parser.metadata.IndexMetadataManager;
import com.tcdi.zombodb.query_parser.rewriters.QueryRewriter;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class IndexLinkOptimizer {
    private static final Map<String, Long> COUNT_ESTIMATE_CACHE = new ConcurrentHashMap<>(1000);

    private final Client client;
    private final QueryRewriter rewriter;
    private final ASTQueryTree tree;
    private final IndexMetadataManager metadataManager;

    public IndexLinkOptimizer(Client client, QueryRewriter rewriter, ASTQueryTree tree, IndexMetadataManager metadataManager) {
        this.client = client;
        this.rewriter = rewriter;
        this.tree = tree;
        this.metadataManager = metadataManager;
    }

    public void optimize() {
        assignIndexLinks(tree);

        QueryTreeOptimizer.rollupParentheticalGroups(tree);

        rewriteIndirectReferenceIndexLinks(tree);

        injectASTExpansionNodes(tree);

        int before;
        do {
            before = tree.countNodes();
            while (mergeAdjacentExpansions(tree) > 0) ;

            QueryTreeOptimizer.rollupParentheticalGroups(tree);
        } while (tree.countNodes() != before);

        ASTAggregate agg = tree.getAggregate();
        while (agg != null) {
            agg = agg.getSubAggregate();
        }

        QueryTreeOptimizer.reduce(tree);
    }


    private void assignIndexLinks(QueryParserNode root) {
        if (root == null || root.getChildren() == null || root.getChildren().isEmpty() || (root instanceof ASTExpansion && !((ASTExpansion) root).isGenerated())) {
            return;
        }

        for (int i = 0, many = root.getChildren().size(); i < many; i++) {
            QueryParserNode child = root.getChild(i);
            String fieldname = child.getFieldname();

            if (child instanceof ASTIndexLink || child instanceof ASTAggregate || child instanceof ASTSuggest)
                continue;

            if (fieldname != null) {
                if (fieldname.startsWith("_") && !"_all".equals(fieldname))
                    continue;

                ASTIndexLink link = child.getIndexLink();

                if (link == null) {
                    link = metadataManager.findField(fieldname);
                    child.setIndexLink(link);
                }
            }

            if (!(child instanceof ASTArray))
                assignIndexLinks(child);
        }
    }

    private void injectASTExpansionNodes(ASTQueryTree tree) {
        for (QueryParserNode child : tree) {
            if (child instanceof ASTOptions || child instanceof ASTLimit || child instanceof ASTFieldLists || child instanceof ASTAggregate || child instanceof ASTSuggest)
                continue;
            injectASTExpansionNodes(child);
        }
    }

    private void injectASTExpansionNodes(QueryParserNode root) {
        if (root == null)
            return;

        while (root instanceof ASTExpansion) {
            injectASTExpansionNodes(((ASTExpansion) root).getFilterQuery());

            root = ((ASTExpansion) root).getQuery();
        }

        Set<ASTIndexLink> links = collectIndexLinks(root, new HashSet<ASTIndexLink>());
        if (links.size() == 0)
            return;

        if (links.size() == 1) {
            ASTIndexLink link = links.iterator().next();
            if (link == null)
                return;

            QueryParserNode parent = (QueryParserNode) root.jjtGetParent();
            QueryParserNode last = null;
            QueryParserNode lastExpansion = null;
            String leftFieldname = null;
            String rightFieldname;
            ASTIndexLink parentLink = metadataManager.getMyIndex();
            QueryParserNode tmp = root;
            while (tmp != null && !(tmp instanceof ASTExpansion))
                tmp = (QueryParserNode) tmp.jjtGetParent();
            if (tmp != null)
                parentLink = tmp.getIndexLink();
            Stack<Dijkstra.NamedIndex> paths = metadataManager.calculatePath(link, parentLink);

            if (link.hasFieldname())
                stripPath(root, link.getFieldname());

            while (!paths.empty()) {
                Dijkstra.NamedIndex current = paths.pop();
                Dijkstra.NamedIndex next = paths.empty() ? null : paths.peek();

                if (next != null && !next.index.contains(":")) {
                    // consume entries that are simply index names
                    paths.pop();

                    do {
                        if (paths.empty())
                            throw new RuntimeException("Invalid path from " + link + " to " + metadataManager.getMyIndex());

                        current = paths.pop();
                        next = paths.empty() ? null : paths.peek();
                    } while (next != null && !next.index.contains(":"));
                }

                ASTExpansion expansion = new ASTExpansion(QueryParserTreeConstants.JJTEXPANSION);
                String indexName;
                String alias = null;

                indexName = current.index.substring(0, current.index.indexOf(':'));
                if (next != null) {
                    leftFieldname = next.index.substring(next.index.indexOf(':') + 1);
                    rightFieldname = current.index.substring(current.index.indexOf(':') + 1);
                } else {
                    if (last == null)
                        throw new IllegalStateException("Failed to build a proper expansion tree");

                    rightFieldname = leftFieldname;
                    leftFieldname = current.index.substring(current.index.indexOf(':') + 1);
                    indexName = lastExpansion.getIndexLink().getIndexName();
                    alias = lastExpansion.getIndexLink().getAlias();
                }

                if (leftFieldname.equals(rightFieldname) && next == null)
                    break;

                ASTIndexLink newLink = ASTIndexLink.create(leftFieldname, indexName, alias, rightFieldname);
                expansion.jjtAddChild(newLink, 0);
                expansion.jjtAddChild(last == null ? root : last, 1);
                newLink.setFieldname(link.getFieldname());

                lastExpansion = expansion;
                if (last == null && !newLink.getIndexName().equals(metadataManager.getMyIndex().getIndexName())) {
                    last = maybeInvertExpansion(expansion);
                } else {
                    last = expansion;
                }
            }

            if (last != null) {
                parent.replaceChild(root, last);
            } else if (parent.getIndexLink() == null || !parent.getIndexLink().getIndexName().equals(link.getIndexName())) {
                ASTExpansion expansion = new ASTExpansion(QueryParserTreeConstants.JJTEXPANSION);
                if (link == parentLink)
                    expansion.jjtAddChild(link, 0);
                else
                    expansion.jjtAddChild(link.getFieldname() == null && link.getIndexName().equals(parentLink.getIndexName()) ? ASTIndexLink.create(link.getRightFieldname(), link.getIndexName(), link.getAlias(), link.getRightFieldname(), true) : link, 0);
                expansion.jjtAddChild(root, 1);
                parent.replaceChild(root, expansion);
            }
        } else {
            for (QueryParserNode child : root)
                injectASTExpansionNodes(child);
        }

    }

    private Set<ASTIndexLink> collectIndexLinks(QueryParserNode root, Set<ASTIndexLink> links) {
        if (root == null)
            return links;

        for (QueryParserNode child : root)
            collectIndexLinks(child, links);

        if (root.getIndexLink() != null)
            links.add(root.getIndexLink());
        return links;
    }

    private int mergeAdjacentExpansions(QueryParserNode root) {
        int total = 0;

        for (QueryParserNode child : root) {
            total += mergeAdjacentExpansions(child);
        }

        Map<String, List<ASTExpansion>> sameExpansions = new HashMap<>();
        int cnt = 0;
        for (QueryParserNode child : root) {
            if (child instanceof ASTExpansion) {
                String key = child.getIndexLink().toStringNoFieldname();
                List<ASTExpansion> groups = sameExpansions.get(key);
                if (groups == null)
                    sameExpansions.put(key, groups = new ArrayList<>());

                groups.add((ASTExpansion) child);
                cnt++;
            }
        }

        if (cnt > 1) {

            for (List<ASTExpansion> entry : sameExpansions.values()) {
                if (entry.size() > 1) {
                    QueryParserNode container;
                    if (root instanceof ASTAnd)
                        container = new ASTAnd(QueryParserTreeConstants.JJTAND);
                    else if (root instanceof ASTOr)
                        container = new ASTOr(QueryParserTreeConstants.JJTOR);
                    else if (root instanceof ASTNot)
                        container = new ASTAnd(QueryParserTreeConstants.JJTAND);
                    else if (root instanceof ASTArray) {
                        container = new ASTArray(QueryParserTreeConstants.JJTARRAY);
                        container.setFieldname(root.getFieldname());
                        container.setBoost(root.getBoost());
                        container.setIndexLink(root.getIndexLink());
                        ((ASTArray) container).setAnd(((ASTArray) root).isAnd());
                    }
                    else
                        throw new RuntimeException("Don't know about parent container type: " + root.getClass());

                    ASTExpansion newExpansion = new ASTExpansion(QueryParserTreeConstants.JJTEXPANSION);
                    newExpansion.jjtAddChild(entry.get(0).getIndexLink(), 0);
                    newExpansion.setGenerated(entry.get(0).isGenerated());
                    newExpansion.jjtAddChild(container, 1);

                    int idx = 0;
                    for (ASTExpansion existingExpansion : entry) {
                        container.jjtAddChild(existingExpansion.getQuery(), container.jjtGetNumChildren());

                        if (idx == 0)
                            root.replaceChild(existingExpansion, newExpansion);
                        else
                            root.removeNode(existingExpansion);
                        idx++;
                    }

                    root.renumber();
                    if (entry.size() > 1)
                        total++;
                }
            }
        }

        return total;
    }

    private void rewriteIndirectReferenceIndexLinks(QueryParserNode node) {
        if (node instanceof ASTExpansion) {
            if (((ASTExpansion) node).isGenerated()) {
                final ASTIndexLink link = node.getIndexLink();
                if (link.getIndexName().equals("this.index")) {
                    ASTIndexLink newLink = new ASTIndexLink(QueryParserTreeConstants.JJTINDEXLINK) {
                        @Override
                        public String getLeftFieldname() {
                            return link.getLeftFieldname();
                        }

                        @Override
                        public String getIndexName() {
                            return metadataManager.findField(link.getLeftFieldname()).getIndexName();
                        }

                        @Override
                        public String getRightFieldname() {
                            return link.getRightFieldname();
                        }
                    };
                    node.jjtAddChild(newLink, 0);

                    //
                    // find the nearest parent node that is an ASTExpansion
                    // it is that node's ASTIndexLink that we need to expand against
                    //
                    ASTIndexLink parentLink = metadataManager.getMyIndex(); // assume our main index
                    QueryParserNode tmp = (QueryParserNode) node.jjtGetParent();
                    while (tmp != null) {
                        if (tmp instanceof ASTExpansion) {
                            // this is the ASTIndexLink that we need to target in this expansion
                            parentLink = tmp.getIndexLink();
                            break;
                        }
                        tmp = (QueryParserNode) tmp.jjtGetParent();
                    }

                    if (!link.getIndexName().equals(parentLink.getIndexName())) {
                        Stack<Dijkstra.NamedIndex> path = metadataManager.calculatePath(newLink, parentLink);
                        if (path.size() == 2) {
                            Dijkstra.NamedIndex top = path.pop();
                            Dijkstra.NamedIndex bottom = path.pop();
                            String leftFieldname = bottom.index.split("[:]")[1];
                            String rightFieldname = top.index.split("[:]")[1];
                            String indexName = top.index.split("[:]")[0];

                            ASTIndexLink intermediateLink = ASTIndexLink.create(leftFieldname, indexName, null, rightFieldname);
                            ASTExpansion expansion = new ASTExpansion(QueryParserTreeConstants.JJTEXPANSION);
                            ((QueryParserNode) node.jjtGetParent()).replaceChild(node, expansion);

                            expansion.jjtAddChild(intermediateLink, 0);
                            expansion.jjtAddChild(node, 1);
                        }
                    }

                }
            }
        }

        for (QueryParserNode child : node)
            rewriteIndirectReferenceIndexLinks(child);
    }

    private void stripPath(QueryParserNode root, String path) {
        if (root.getFieldname() != null && root.getFieldname().startsWith(path + "."))
            root.setFieldname(root.getFieldname().substring(path.length() + 1));

        for (QueryParserNode child : root) {
            stripPath(child, path);
        }

    }

    private QueryParserNode maybeInvertExpansion(ASTExpansion expansion) {
        if (metadataManager.getMetadataForMyIndex().alwaysResolveJoins())
            return expansion;

        long totalCnt, queryCnt;

        //
        // figure out how many records are in the index
        //
        totalCnt = estimateCount(expansion, false);

        //
        // then how many records this expansion is likely to return
        //
        queryCnt = estimateCount(expansion, true);

        if (queryCnt > totalCnt / 2) {
            //
            // and if the expansion is going to return more than 1/2 the database
            // invert it on the inner side of the expansion
            //
            ASTNot innerNot = new ASTNot(QueryParserTreeConstants.JJTNOT);
            innerNot.jjtAddChild(expansion.getQuery(), 0);
            expansion.jjtAddChild(innerNot, 1);

            //
            // and on the outer side.
            //
            // This way we're only shipping around the minimal number of rows
            // through the rest of the query
            //
            ASTNot outerNot = new ASTNot(QueryParserTreeConstants.JJTNOT);
            outerNot.jjtAddChild(expansion, 0);
            return outerNot;
        }

        return expansion;
    }

    private long estimateCount(ASTExpansion expansion, boolean useQuery) {
        SearchRequestBuilder builder = new SearchRequestBuilder(client);
        builder.setIndices(expansion.getIndexLink().getIndexName());
        builder.setTypes("data");
        builder.setSize(0);
        builder.setSearchType(SearchType.COUNT);
        builder.setQueryCache(true);
        builder.setFetchSource(false);
        builder.setTrackScores(false);
        builder.setNoFields();
        if (useQuery)
            builder.setQuery(rewriter.build(expansion.getQuery()));

        String key = expansion.getIndexLink().getIndexName() + ":" + builder.toString();
        Long count = COUNT_ESTIMATE_CACHE.get(key);
        if (count != null)
            return count;

        try {
            count = client.search(builder.request()).get().getHits().getTotalHits();
            if (COUNT_ESTIMATE_CACHE.size() >= 1000)
                COUNT_ESTIMATE_CACHE.clear();

            COUNT_ESTIMATE_CACHE.put(key, count);
            return count;
        } catch (Exception e) {
            throw new RuntimeException("Problem estimating count", e);
        }
    }

}
